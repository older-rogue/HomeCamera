package com.zx.homecamera

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.ViewerState
import com.zx.homecamera.core.app.ViewerStatus
import com.zx.homecamera.network.H264UdpViewer
import com.zx.homecamera.network.LanViewerConnector
import com.zx.homecamera.network.ViewerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val _viewerConnectionState = MutableStateFlow<ViewerConnection?>(null)
    val viewerConnectionState: StateFlow<ViewerConnection?> = _viewerConnectionState.asStateFlow()

    private val viewerStream = H264UdpViewer()
    private val viewerGeneration = AtomicLong()
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var viewerSurface: Surface? = null
    private var viewerStreamKey: String? = null
    @Volatile
    private var paused = false

    fun setDevice(device: CollectorDevice) {
        paused = false
        resetViewerConnection()
        _state.value = ViewerState(selectedDevice = device, status = ViewerStatus.Connecting)
        connectViewerStream(device)
    }

    /**
     * Activity onPause 时调用：断开采集端连接、停止接收解码与释放 WifiLock，
     * 减少 pause 期间无效的网络接收与解码开销。保留已选设备与 surface 引用，
     * 以便 [resume] 时重新连接。置 Disconnected 反映当前无数据状态。
     */
    fun pause() {
        paused = true
        resetViewerConnection()
        if (_state.value.status == ViewerStatus.Playing ||
            _state.value.status == ViewerStatus.Connecting ||
            _state.value.status == ViewerStatus.Reconnecting
        ) {
            _state.value = _state.value.copy(status = ViewerStatus.Disconnected)
        }
    }

    /**
     * Activity onResume 时调用：若此前 [pause] 过且仍持有已选设备，重新连接采集端。
     * 连接成功后因 surface 通常仍就绪，会自动恢复接收解码渲染。
     */
    fun resume() {
        if (!paused) return
        paused = false
        val device = _state.value.selectedDevice ?: return
        _state.value = _state.value.copy(status = ViewerStatus.Connecting)
        connectViewerStream(device)
    }

    fun onViewerSurfaceReady(surface: Surface) {
        viewerSurface = surface
        startViewerStreamIfReady()
    }

    fun onViewerSurfaceDestroyed() {
        viewerGeneration.incrementAndGet()
        viewerSurface = null
        viewerStream.stop()
        releaseWifiLock()
        viewerStreamKey = null
        if (_state.value.status == ViewerStatus.Playing) {
            _state.value = _state.value.copy(status = ViewerStatus.Disconnected)
        }
    }

    private fun connectViewerStream(device: CollectorDevice, maxAttempts: Int = 1) {
        val generation = viewerGeneration.get()
        connectViewer(device, maxAttempts) { connection, message ->
            if (!isViewerGenerationActive(generation, device)) {
                connection?.close()
                return@connectViewer
            }
            if (connection == null) {
                _state.value = _state.value.copy(
                    status = ViewerStatus.Error,
                    errorMessage = message,
                )
            } else {
                _viewerConnectionState.value = connection
                startViewerStreamIfReady(generation)
            }
        }
    }

    private fun startViewerStreamIfReady(generation: Long = viewerGeneration.get()) {
        val connection = _viewerConnectionState.value
        val surface = viewerSurface
        if (connection != null && surface != null && surface.isValid) {
            if (!isViewerGenerationActive(generation, _state.value.selectedDevice)) return
            val streamKey = "$generation:${connection.collectorDeviceId}:${surface.hashCode()}"
            if (viewerStreamKey == streamKey) return
            acquireWifiLock()
            viewerStreamKey = streamKey
            viewerStream.start(
                context = getApplication(),
                connection = connection,
                surface = surface,
                onFirstFrame = {
                    viewModelScope.launch {
                        if (!isViewerGenerationActive(generation, connection)) return@launch
                        _state.value = _state.value.copy(status = ViewerStatus.Playing)
                    }
                },
                onError = { message ->
                    viewModelScope.launch {
                        if (!isViewerGenerationActive(generation, connection)) return@launch
                        viewerStream.stop()
                        releaseWifiLock()
                        _viewerConnectionState.value = null
                        viewerStreamKey = null
                        _state.value = _state.value.copy(
                            status = ViewerStatus.Reconnecting,
                            errorMessage = message,
                        )
                        val reconnectGeneration = viewerGeneration.get()
                        _state.value.selectedDevice?.let { device ->
                            reconnectViewerStream(reconnectGeneration, device)
                        }
                    }
                },
            )
        }
    }

    private fun isViewerGenerationActive(generation: Long, device: CollectorDevice?): Boolean =
        viewerGeneration.get() == generation && _state.value.selectedDevice?.deviceId == device?.deviceId

    private fun isViewerGenerationActive(generation: Long, connection: ViewerConnection): Boolean =
        ViewerConnectionIdentity.isActiveConnection(
            expectedGeneration = generation,
            currentGeneration = viewerGeneration.get(),
            selectedDevice = _state.value.selectedDevice,
            connection = connection,
        )

    private fun resetViewerConnection() {
        viewerGeneration.incrementAndGet()
        viewerStream.stop()
        _viewerConnectionState.value?.close()
        _viewerConnectionState.value = null
        releaseWifiLock()
        viewerStreamKey = null
    }

    private fun connectViewer(
        device: CollectorDevice,
        maxAttempts: Int = 1,
        onResult: (ViewerConnection?, String?) -> Unit,
    ) = viewModelScope.launch {
        // 改用 viewModelScope 协程：随 ViewModel 生命周期自动取消，避免裸线程+Thread.sleep
        // 在 onCleared 后仍阻塞（connect 卡在 socket 超时期间持有 Application 引用）。
        // connect 是阻塞 IO，切到 Dispatchers.IO 执行。
        var lastError: Throwable? = null
        var connection: ViewerConnection? = null
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            if (connection == null) {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        LanViewerConnector().connect(device, timeoutMillis = VIEWER_CONNECT_TIMEOUT_MILLIS)
                    }
                }
                connection = result.getOrNull()
                lastError = result.exceptionOrNull()
                if (connection == null && attempt < maxAttempts - 1) {
                    delay(VIEWER_RECONNECT_FAST_INTERVAL_MILLIS)
                }
            }
        }
        onResult(connection, lastError?.message)
    }

    private fun reconnectViewerStream(generation: Long, device: CollectorDevice) = viewModelScope.launch {
        // 改用协程后，重连循环不再独占单线程 executor（原实现会阻塞后续 connectViewer 请求，
        // 导致快速切换设备时连接请求串行卡顿）。delay 取代 Thread.sleep，随 viewModelScope 取消。
        var attempt = 0
        var lastError: Throwable? = null
        while (isViewerGenerationActive(generation, device)) {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    LanViewerConnector().connect(device, timeoutMillis = VIEWER_CONNECT_TIMEOUT_MILLIS)
                }
            }
            val connection = result.getOrNull()
            if (connection != null) {
                if (!isViewerGenerationActive(generation, device)) {
                    connection.close()
                    return@launch
                }
                _viewerConnectionState.value = connection
                startViewerStreamIfReady(generation)
                return@launch
            }
            lastError = result.exceptionOrNull()
            attempt++
            val delayMillis = if (attempt < VIEWER_RECONNECT_FAST_ATTEMPTS) {
                VIEWER_RECONNECT_FAST_INTERVAL_MILLIS
            } else {
                VIEWER_RECONNECT_SLOW_INTERVAL_MILLIS
            }
            delay(delayMillis)
        }
        if (isViewerGenerationActive(generation, device)) {
            _state.value = _state.value.copy(
                status = ViewerStatus.Error,
                errorMessage = lastError?.message ?: "重连失败",
            )
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = getApplication<Application>().getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifiManager?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "HomeCamera:Viewer",
        )
        wifiLock?.acquire()
    }

    private fun releaseWifiLock() {
        wifiLock?.run { if (isHeld) release() }
        wifiLock = null
    }

    override fun onCleared() {
        viewerGeneration.incrementAndGet()
        viewerStream.stop()
        releaseWifiLock()
        // viewerExecutor 已移除，重连/连接协程随 viewModelScope 自动取消
        super.onCleared()
    }

    companion object {
        private const val VIEWER_CONNECT_TIMEOUT_MILLIS = 2_500
        private const val VIEWER_RECONNECT_FAST_INTERVAL_MILLIS = 1_000L
        private const val VIEWER_RECONNECT_SLOW_INTERVAL_MILLIS = 5_000L
        private const val VIEWER_RECONNECT_FAST_ATTEMPTS = 5
    }
}
