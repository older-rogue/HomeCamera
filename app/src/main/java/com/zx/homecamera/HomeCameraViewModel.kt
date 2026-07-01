package com.zx.homecamera

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zx.homecamera.core.app.AppRole
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.HomeCameraAction
import com.zx.homecamera.core.app.HomeCameraReducer
import com.zx.homecamera.core.app.HomeCameraState
import com.zx.homecamera.core.app.ViewerStatus
import com.zx.homecamera.debug.LocalDebugSession
import com.zx.homecamera.network.H264UdpViewer
import com.zx.homecamera.network.LanViewerConnector
import com.zx.homecamera.network.logNet
import com.zx.homecamera.network.SocketTcpPortConnector
import com.zx.homecamera.network.TcpSubnetScanner
import com.zx.homecamera.network.ViewerConnection
import com.zx.homecamera.network.WifiSubnetProvider
import com.zx.homecamera.service.CollectorForegroundService
import com.zx.homecamera.video.CameraH264Streamer
import com.zx.homecamera.video.CollectorCameraRuntime
import com.zx.homecamera.video.H264StreamConfig
import com.zx.homecamera.video.PreviewSize
import com.zx.homecamera.video.VideoSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

class HomeCameraViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(HomeCameraState())
    val state: StateFlow<HomeCameraState> = _state.asStateFlow()

    private val scannerExecutor = Executors.newSingleThreadExecutor()
    private val viewerExecutor = Executors.newSingleThreadExecutor()
    private val viewerStream = H264UdpViewer()
    private val scanGeneration = AtomicLong()
    private val viewerGeneration = AtomicLong()
    private var scanFuture: Future<*>? = null
    private var localDebugSession: LocalDebugSession? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var viewerConnection: ViewerConnection? = null

    @Volatile
    private var viewerSurface: Surface? = null
    private var viewerStreamKey: String? = null

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CollectorForegroundService.ACTION_COLLECTOR_STATUS) return
            val status = intent.getStringExtra(CollectorForegroundService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(CollectorForegroundService.EXTRA_MESSAGE)
            val clientCount = intent.getStringExtra(CollectorForegroundService.EXTRA_CLIENT_COUNT)?.toIntOrNull()
            viewModelScope.launch {
                when (status) {
                    CollectorForegroundService.STATUS_RUNNING -> {
                        _state.value = HomeCameraReducer.reduce(
                            _state.value,
                            HomeCameraAction.CollectorStarted,
                        )
                    }
                    CollectorForegroundService.STATUS_ERROR -> {
                        _state.value = HomeCameraReducer.reduce(
                            _state.value,
                            HomeCameraAction.CollectorFailed(message ?: "采集端启动失败"),
                        )
                    }
                    CollectorForegroundService.STATUS_STOPPED -> {
                        _state.value = HomeCameraReducer.reduce(
                            _state.value,
                            HomeCameraAction.StopCollector,
                        )
                    }
                    CollectorForegroundService.STATUS_RECORDING_ERROR -> {
                        _state.value = HomeCameraReducer.reduce(
                            _state.value,
                            HomeCameraAction.RecordingFailed(message ?: "录像写入失败"),
                        )
                    }
                }
                clientCount?.let { count ->
                    _state.value = HomeCameraReducer.reduce(
                        _state.value,
                        HomeCameraAction.ClientCountChanged(count),
                    )
                }
            }
        }
    }

    init {
        val filter = IntentFilter(CollectorForegroundService.ACTION_COLLECTOR_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(serviceStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            application.registerReceiver(serviceStatusReceiver, filter)
        }
    }

    fun onAction(action: HomeCameraAction) {
        val context = getApplication<Application>()
        when (action) {
            is HomeCameraAction.SelectRole -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
                if (action.role == AppRole.Client) {
                    _state.value = HomeCameraReducer.reduce(_state.value, HomeCameraAction.StartScan)
                    scanCollectors()
                }
            }

            HomeCameraAction.StartCollector -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
                // Service start is handled via permission flow in MainActivity
            }

            HomeCameraAction.StopCollector -> {
                context.startCollectorService(ACTION_STOP)
                _state.value = HomeCameraReducer.reduce(_state.value, action)
            }

            HomeCameraAction.StartScan -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
                scanCollectors()
            }

            is HomeCameraAction.OpenViewer -> {
                cancelScan()
                viewerGeneration.incrementAndGet()
                viewerStream.stop()
        releaseWifiLock()
                viewerConnection = null
                viewerStreamKey = null
                _state.value = HomeCameraReducer.reduce(_state.value, action)
                _state.value.viewer.selectedDevice?.let { device ->
                    connectViewerStream(device)
                }
            }

            HomeCameraAction.BackToClientList -> {
                viewerGeneration.incrementAndGet()
                viewerStream.stop()
        releaseWifiLock()
                viewerConnection = null
                viewerSurface = null
                viewerStreamKey = null
                _state.value = HomeCameraReducer.reduce(_state.value, action)
            }

            HomeCameraAction.BackToRoleSelection -> {
                cancelScan()
                viewerGeneration.incrementAndGet()
                viewerStream.stop()
        releaseWifiLock()
                viewerConnection = null
                viewerSurface = null
                viewerStreamKey = null
                localDebugSession?.stop()
                localDebugSession = null
                _state.value = HomeCameraReducer.reduce(_state.value, action)
            }

            HomeCameraAction.EnterLocalDebug -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
            }

            HomeCameraAction.ExitLocalDebug -> {
                localDebugSession?.stop()
                localDebugSession = null
                _state.value = HomeCameraReducer.reduce(_state.value, action)
            }

            is HomeCameraAction.LocalDebugStatusChanged -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
            }

            else -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
            }
        }
    }

    fun startCollectorService() {
        val context = getApplication<Application>()
        context.startCollectorService(ACTION_START)
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
        if (_state.value.viewer.status == ViewerStatus.Playing) {
            _state.value = HomeCameraReducer.reduce(
                _state.value,
                HomeCameraAction.ViewerStatusChanged(ViewerStatus.Disconnected),
            )
        }
    }

    fun setDisplayRotationDegrees(degrees: Int) {
        CollectorCameraRuntime.setDisplayRotationDegrees(degrees)
    }

    fun setCollectorPreviewSurface(holder: android.view.SurfaceHolder?, width: Int, height: Int) {
        CollectorCameraRuntime.setPreviewSurface(holder, width, height)
    }

    fun clearCollectorPreviewSurface() {
        CollectorCameraRuntime.setPreviewSurface(null)
    }

    fun setCollectorPreviewSizeListener(listener: ((VideoSize) -> Unit)?) {
        CollectorCameraRuntime.setPreviewDisplaySizeListener(listener)
    }

    fun startLocalDebugSession(surface: Surface) {
        localDebugSession?.stop()
        val session = LocalDebugSession(getApplication())
        localDebugSession = session
        runCatching {
            session.start(
                viewerSurface = surface,
                onFirstFrame = {
                    viewModelScope.launch {
                        _state.value = HomeCameraReducer.reduce(
                            _state.value,
                            HomeCameraAction.LocalDebugStatusChanged(ViewerStatus.Playing),
                        )
                    }
                },
                onError = { message ->
                    viewModelScope.launch {
                        _state.value = HomeCameraReducer.reduce(
                            _state.value,
                            HomeCameraAction.LocalDebugStatusChanged(ViewerStatus.Error, message),
                        )
                    }
                },
            )
        }.onFailure { error ->
            if (localDebugSession == session) {
                session.stop()
                localDebugSession = null
            }
            _state.value = HomeCameraReducer.reduce(
                _state.value,
                HomeCameraAction.LocalDebugStatusChanged(
                    ViewerStatus.Error,
                    error.message ?: "本地调试启动失败",
                ),
            )
        }
    }

    fun stopLocalDebugSession() {
        localDebugSession?.stop()
        localDebugSession = null
    }

    private fun scanCollectors() {
        cancelScan()
        val generation = scanGeneration.incrementAndGet()
        val context = getApplication<Application>()
        scanFuture = scannerExecutor.submit {
            val subnetProvider = WifiSubnetProvider(context)
            while (!Thread.currentThread().isInterrupted && scanGeneration.get() == generation) {
                val subnet = subnetProvider.subnet().getOrElse { error ->
                    dispatchScanFailed(generation, error.message ?: "无法获取 Wi-Fi 网段")
                    return@submit
                }
                logNet("scan loop generation=$generation local=${subnet.localAddress} hosts=${subnet.hosts.first()}..${subnet.hosts.last()}")
                val devices = TcpSubnetScanner(
                    SocketTcpPortConnector(subnet.network.socketFactory),
                ).scan(
                    hosts = subnet.hosts,
                    tcpPort = CollectorForegroundService.CONTROL_PORT,
                )
                if (scanGeneration.get() != generation || Thread.currentThread().isInterrupted) return@submit
                if (devices.isNotEmpty()) {
                    viewModelScope.launch {
                        if (scanGeneration.get() == generation) {
                            _state.value = HomeCameraReducer.reduce(
                                _state.value,
                                HomeCameraAction.DevicesDiscovered(devices),
                            )
                        }
                    }
                    return@submit
                }
                try {
                    Thread.sleep(SCAN_RETRY_DELAY_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@submit
                }
            }
        }
    }

    private fun cancelScan() {
        scanGeneration.incrementAndGet()
        scanFuture?.cancel(true)
        scanFuture = null
    }

    private fun dispatchScanFailed(generation: Long, reason: String) {
        viewModelScope.launch {
            if (scanGeneration.get() == generation) {
                _state.value = HomeCameraReducer.reduce(
                    _state.value,
                    HomeCameraAction.ScanFailed(reason),
                )
            }
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
                _state.value = HomeCameraReducer.reduce(
                    _state.value,
                    HomeCameraAction.ViewerStatusChanged(ViewerStatus.Error, message),
                )
            } else {
                viewerConnection = connection
                startViewerStreamIfReady(generation)
            }
        }
    }

    private fun startViewerStreamIfReady(generation: Long = viewerGeneration.get()) {
        val connection = viewerConnection
        val surface = viewerSurface
        if (connection != null && surface != null && surface.isValid) {
            if (!isViewerGenerationActive(generation, _state.value.viewer.selectedDevice)) return
            val streamKey = "$generation:${connection.collectorDeviceId}:${surface.hashCode()}"
            if (viewerStreamKey == streamKey) return
            acquireWifiLock()
            viewerStreamKey = streamKey
            viewerStream.start(
                connection = connection,
                surface = surface,
                onFirstFrame = {
                    viewModelScope.launch {
                        if (!isViewerGenerationActive(generation, connection)) return@launch
                        _state.value = HomeCameraReducer.reduce(
                            _state.value,
                            HomeCameraAction.ViewerStatusChanged(ViewerStatus.Playing),
                        )
                    }
                },
                onError = { message ->
                    viewModelScope.launch {
                        if (!isViewerGenerationActive(generation, connection)) return@launch
                        viewerStream.stop()
                        releaseWifiLock()
                        viewerConnection = null
                        viewerStreamKey = null
                        _state.value = HomeCameraReducer.reduce(
                            _state.value,
                            HomeCameraAction.ViewerStatusChanged(ViewerStatus.Reconnecting, message),
                        )
                        val reconnectGeneration = viewerGeneration.get()
                        _state.value.viewer.selectedDevice?.let { device ->
                            connectViewer(device, maxAttempts = 5) { reconnected, reconnectMessage ->
                                if (!isViewerGenerationActive(reconnectGeneration, device)) {
                                    reconnected?.close()
                                    return@connectViewer
                                }
                                if (reconnected == null) {
                                    _state.value = HomeCameraReducer.reduce(
                                        _state.value,
                                        HomeCameraAction.ViewerStatusChanged(
                                            ViewerStatus.Error,
                                            reconnectMessage,
                                        ),
                                    )
                                } else {
                                    viewerConnection = reconnected
                                    startViewerStreamIfReady(reconnectGeneration)
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    private fun isViewerGenerationActive(generation: Long, device: CollectorDevice?): Boolean =
        viewerGeneration.get() == generation &&
            _state.value.viewer.selectedDevice?.deviceId == device?.deviceId

    private fun isViewerGenerationActive(generation: Long, connection: ViewerConnection): Boolean =
        viewerGeneration.get() == generation &&
            _state.value.viewer.selectedDevice?.deviceId == connection.collectorDeviceId

    private fun connectViewer(
        device: CollectorDevice,
        maxAttempts: Int = 1,
        onResult: (ViewerConnection?, String?) -> Unit,
    ) {
        viewerExecutor.execute {
            var lastError: Throwable? = null
            var connection: ViewerConnection? = null
            repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
                if (connection == null) {
                    val result = runCatching {
                        LanViewerConnector().connect(device, timeoutMillis = 2_500)
                    }
                    connection = result.getOrNull()
                    lastError = result.exceptionOrNull()
                    if (connection == null && attempt < maxAttempts - 1) {
                        Thread.sleep(1_000)
                    }
                }
            }
            viewModelScope.launch {
                onResult(connection, lastError?.message)
            }
        }
    }

    override fun onCleared() {
        viewerGeneration.incrementAndGet()
        cancelScan()
        viewerStream.stop()
        releaseWifiLock()
        localDebugSession?.stop()
        localDebugSession = null
        scannerExecutor.shutdownNow()
        viewerExecutor.shutdownNow()
        try {
            getApplication<Application>().unregisterReceiver(serviceStatusReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered
        }
        super.onCleared()
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
        wifiLock?.run {
            if (isHeld) release()
        }
        wifiLock = null
    }

    companion object {
        private const val SCAN_RETRY_DELAY_MILLIS = 5_000L
        private const val ACTION_START = CollectorForegroundService.ACTION_START
        private const val ACTION_STOP = CollectorForegroundService.ACTION_STOP
    }
}

private fun Context.startCollectorService(action: String) {
    val intent = Intent(this, CollectorForegroundService::class.java).setAction(action)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
    } else {
        startService(intent)
    }
}
