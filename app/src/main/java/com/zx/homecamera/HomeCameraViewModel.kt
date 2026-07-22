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
import com.zx.homecamera.network.H264UdpViewer
import com.zx.homecamera.network.LanViewerConnector
import com.zx.homecamera.network.RecordingApiClient
import com.zx.homecamera.network.logNet
import com.zx.homecamera.network.SocketTcpPortConnector
import com.zx.homecamera.network.TcpSubnetScanner
import com.zx.homecamera.network.ViewerConnection
import com.zx.homecamera.network.WifiSubnetProvider
import com.zx.homecamera.service.CollectorForegroundService
import com.zx.homecamera.video.CollectorCameraRuntime
import com.zx.homecamera.core.protocol.RecordingEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

class HomeCameraViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(HomeCameraState())
    val state: StateFlow<HomeCameraState> = _state.asStateFlow()

    private val scannerExecutor = Executors.newSingleThreadExecutor()
    private val viewerExecutor = Executors.newSingleThreadExecutor()
    private val recordingExecutor = Executors.newSingleThreadExecutor()
    private val viewerStream = H264UdpViewer()
    private val recordingApi = RecordingApiClient()
    private val scanGeneration = AtomicLong()
    private val viewerGeneration = AtomicLong()
    private var scanFuture: Future<*>? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _viewerConnectionState = MutableStateFlow<ViewerConnection?>(null)
    val viewerConnectionState: StateFlow<ViewerConnection?> = _viewerConnectionState.asStateFlow()

    private var viewerConnection: ViewerConnection?
        get() = _viewerConnectionState.value
        set(value) {
            _viewerConnectionState.value = value
        }

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
                _state.value = HomeCameraReducer.reduce(_state.value, action)
            }

            HomeCameraAction.OpenRecordingLibrary -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
                loadRecordingDates()
            }

            HomeCameraAction.RefreshRecordingLibrary -> {
                val selectedDate = _state.value.recordingLibrary.selectedDate
                if (selectedDate != null) {
                    loadRecordingFiles(selectedDate)
                } else {
                    loadRecordingDates()
                }
            }

            is HomeCameraAction.SelectRecordingDate -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
                loadRecordingFiles(action.date)
            }

            is HomeCameraAction.DownloadRecording -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
                downloadRecordingToGallery(action.fileId)
            }

            is HomeCameraAction.OpenRecordingPlayback -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
                loadRecordingForPlayback(action.fileId)
            }

            HomeCameraAction.BackToViewer -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
            }

            HomeCameraAction.BackToRecordingLibrary -> {
                _state.value = HomeCameraReducer.reduce(_state.value, action)
            }

            HomeCameraAction.SavePlaybackToGallery -> {
                savePlaybackToGallery()
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

    fun stopCollectorService() {
        val context = getApplication<Application>()
        context.startCollectorService(ACTION_STOP)
        _state.value = HomeCameraReducer.reduce(_state.value, HomeCameraAction.StopCollector)
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

    private fun selectedDevice(): CollectorDevice? = _state.value.viewer.selectedDevice

    private fun loadRecordingDates() {
        val device = selectedDevice() ?: return
        recordingExecutor.execute {
            val result = runCatching { recordingApi.listDates(device) }
            val dates = result.getOrDefault(emptyList())
            val error = result.exceptionOrNull()?.message
            viewModelScope.launch {
                _state.value = HomeCameraReducer.reduce(
                    _state.value,
                    HomeCameraAction.RecordingDatesLoaded(dates, error),
                )
                // 默认加载第一个日期的文件列表
                _state.value.recordingLibrary.selectedDate?.let { date ->
                    loadRecordingFiles(date)
                }
            }
        }
    }

    private fun loadRecordingFiles(date: String) {
        val device = selectedDevice() ?: return
        recordingExecutor.execute {
            val result = runCatching { recordingApi.listFiles(device, date) }
            val files = result.getOrDefault(emptyList())
            val error = result.exceptionOrNull()?.message
            viewModelScope.launch {
                _state.value = HomeCameraReducer.reduce(
                    _state.value,
                    HomeCameraAction.RecordingFilesLoaded(date, files, error),
                )
            }
        }
    }

    private fun downloadRecordingToGallery(fileId: String) {
        val device = selectedDevice() ?: return
        val context = getApplication<Application>()
        val entry = _state.value.recordingLibrary.files.firstOrNull { it.fileId == fileId } ?: return
        recordingExecutor.execute {
            val result = runCatching {
                recordingApi.downloadToGallery(
                    context = context,
                    device = device,
                    entry = entry,
                    onProgress = { transferred, total ->
                        viewModelScope.launch {
                            _state.value = HomeCameraReducer.reduce(
                                _state.value,
                                HomeCameraAction.DownloadProgressChanged(fileId, transferred, total),
                            )
                        }
                    },
                )
            }
            val error = result.exceptionOrNull()?.message ?: if (result.getOrNull() == null) "下载失败" else null
            viewModelScope.launch {
                _state.value = HomeCameraReducer.reduce(
                    _state.value,
                    HomeCameraAction.DownloadCompleted(fileId, error),
                )
            }
        }
    }

    private fun loadRecordingForPlayback(fileId: String) {
        val device = selectedDevice() ?: return
        val context = getApplication<Application>()
        recordingExecutor.execute {
            val result = runCatching {
                recordingApi.downloadToCache(
                    device = device,
                    fileId = fileId,
                    cacheDir = context.cacheDir,
                )
            }
            val file = result.getOrNull()
            val error = if (file == null) result.exceptionOrNull()?.message ?: "加载录像失败" else null
            viewModelScope.launch {
                _state.value = HomeCameraReducer.reduce(
                    _state.value,
                    HomeCameraAction.RecordingPlaybackLoaded(fileId, file, error),
                )
            }
        }
    }

    fun savePlaybackToGallery() {
        val playback = _state.value.recordingPlayback
        val cachedFile = playback.cachedFile ?: return
        val device = selectedDevice() ?: return
        val context = getApplication<Application>()
        // 从 fileId 还原 RecordingEntry（播放所需信息）。播放缓存文件名包含 fileId 信息，
        // 但下载到相册需要 sizeBytes/startMillis，这里用 cachedFile 大小兜底。
        val entry = RecordingEntry(
            fileId = playback.fileId,
            sizeBytes = cachedFile.length(),
            startMillis = 0L,
        )
        // 复用下载逻辑：先把 savingToGallery 置位，再走 RecordingApiClient。
        _state.value = HomeCameraReducer.reduce(
            _state.value,
            HomeCameraAction.RecordingPlaybackSavedToGallery(false),
        )
        // 标记正在保存
        _state.value = _state.value.copy(
            recordingPlayback = _state.value.recordingPlayback.copy(savingToGallery = true, savedToGallery = false),
        )
        recordingExecutor.execute {
            // 已有缓存文件，直接写入相册，不走网络。
            val uri = runCatching {
                writeCachedFileToGallery(context, cachedFile, entry.fileId)
            }.getOrNull()
            viewModelScope.launch {
                _state.value = HomeCameraReducer.reduce(
                    _state.value,
                    HomeCameraAction.RecordingPlaybackSavedToGallery(uri != null),
                )
            }
        }
    }

    private fun writeCachedFileToGallery(
        context: Application,
        cachedFile: File,
        fileId: String,
    ): android.net.Uri? {
        val displayName = "HomeCamera_${fileId.replace('/', '_')}"
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Video.Media
            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_MOVIES}/HomeCamera")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                cachedFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.flush()
            } ?: throw IllegalStateException("cannot open output stream")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val finalValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, finalValues, null, null)
            }
            uri
        }.onFailure {
            runCatching { resolver.delete(uri, null, null) }
        }.getOrNull()
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
                context = getApplication(),
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
                            reconnectViewerStream(reconnectGeneration, device)
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
                        LanViewerConnector().connect(device, timeoutMillis = VIEWER_CONNECT_TIMEOUT_MILLIS)
                    }
                    connection = result.getOrNull()
                    lastError = result.exceptionOrNull()
                    if (connection == null && attempt < maxAttempts - 1) {
                        Thread.sleep(VIEWER_RECONNECT_FAST_INTERVAL_MILLIS)
                    }
                }
            }
            viewModelScope.launch {
                onResult(connection, lastError?.message)
            }
        }
    }

    /**
     * 持续重连采集端，直到连接成功或当前 viewer generation 失效（用户退出/切换设备）。
     * 先快速重试几次，之后转为慢速间隔，避免采集端短暂故障后客户端永久卡死。
     */
    private fun reconnectViewerStream(generation: Long, device: CollectorDevice) {
        viewerExecutor.execute {
            var attempt = 0
            var lastError: Throwable? = null
            while (isViewerGenerationActive(generation, device)) {
                val result = runCatching {
                    LanViewerConnector().connect(device, timeoutMillis = VIEWER_CONNECT_TIMEOUT_MILLIS)
                }
                val connection = result.getOrNull()
                if (connection != null) {
                    viewModelScope.launch {
                        if (!isViewerGenerationActive(generation, device)) {
                            connection.close()
                            return@launch
                        }
                        viewerConnection = connection
                        startViewerStreamIfReady(generation)
                    }
                    return@execute
                }
                lastError = result.exceptionOrNull()
                attempt++
                val delayMillis = if (attempt < VIEWER_RECONNECT_FAST_ATTEMPTS) {
                    VIEWER_RECONNECT_FAST_INTERVAL_MILLIS
                } else {
                    VIEWER_RECONNECT_SLOW_INTERVAL_MILLIS
                }
                try {
                    Thread.sleep(delayMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
            val failure = lastError?.message
            viewModelScope.launch {
                if (isViewerGenerationActive(generation, device)) {
                    _state.value = HomeCameraReducer.reduce(
                        _state.value,
                        HomeCameraAction.ViewerStatusChanged(ViewerStatus.Error, failure ?: "重连失败"),
                    )
                }
            }
        }
    }

    override fun onCleared() {
        viewerGeneration.incrementAndGet()
        cancelScan()
        viewerStream.stop()
        releaseWifiLock()
        scannerExecutor.shutdownNow()
        viewerExecutor.shutdownNow()
        recordingExecutor.shutdownNow()
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
        private const val VIEWER_CONNECT_TIMEOUT_MILLIS = 2_500
        private const val VIEWER_RECONNECT_FAST_INTERVAL_MILLIS = 1_000L
        private const val VIEWER_RECONNECT_SLOW_INTERVAL_MILLIS = 5_000L
        private const val VIEWER_RECONNECT_FAST_ATTEMPTS = 5
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
