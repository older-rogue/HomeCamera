package com.zx.homecamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zx.homecamera.core.app.AppRole
import com.zx.homecamera.core.app.HomeCameraAction
import com.zx.homecamera.core.app.HomeCameraReducer
import com.zx.homecamera.core.app.HomeCameraState
import com.zx.homecamera.core.app.ViewerStatus
import com.zx.homecamera.network.H264UdpViewer
import com.zx.homecamera.network.LanDiscoveryScanner
import com.zx.homecamera.network.LanViewerConnector
import com.zx.homecamera.network.ViewerConnection
import com.zx.homecamera.service.CollectorForegroundService
import com.zx.homecamera.ui.HomeCameraApp
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.video.CameraH264Streamer
import com.zx.homecamera.video.CameraPreviewConfigReader
import com.zx.homecamera.video.CollectorCameraRuntime
import com.zx.homecamera.video.H264StreamConfig
import com.zx.homecamera.video.PreviewSize
import com.zx.homecamera.video.VideoSize
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val scannerExecutor = Executors.newSingleThreadExecutor()
    private val viewerExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var state by remember { mutableStateOf(HomeCameraState()) }
            var viewerConnection by remember { mutableStateOf<ViewerConnection?>(null) }
            var viewerSurface by remember { mutableStateOf<Surface?>(null) }
            var viewerStreamKey by remember { mutableStateOf<String?>(null) }
            var collectorPreviewSize by remember { mutableStateOf(defaultCollectorPreviewSize()) }
            val viewerStream = remember { H264UdpViewer() }
            val context = LocalContext.current
            val collectorPermissions = remember { collectorPermissions() }
            fun displayRotationDegrees(): Int =
                CameraH264Streamer.rotationDegrees(display?.rotation ?: Surface.ROTATION_0)
            fun preselectCollectorPreviewSize() {
                val previewSize = runCatching {
                    CameraPreviewConfigReader.read(
                        context = context,
                        displayRotationDegrees = displayRotationDegrees(),
                    )
                }.getOrNull() ?: return
                collectorPreviewSize = previewSize
                CollectorCameraRuntime.setPreselectedPreviewSize(previewSize)
            }
            fun startViewerStreamIfReady() {
                val connection = viewerConnection
                val surface = viewerSurface
                if (connection != null && surface != null && surface.isValid) {
                    val streamKey = "${connection.collectorDeviceId}:${surface.hashCode()}"
                    if (viewerStreamKey == streamKey) return
                    viewerStreamKey = streamKey
                    viewerStream.start(
                        connection = connection,
                        surface = surface,
                        onFirstFrame = {
                            runOnUiThread {
                                state = HomeCameraReducer.reduce(
                                    state,
                                    HomeCameraAction.ViewerStatusChanged(ViewerStatus.Playing),
                                )
                            }
                        },
                        onError = { message ->
                            runOnUiThread {
                                viewerStream.stop()
                                viewerConnection = null
                                viewerStreamKey = null
                                state = HomeCameraReducer.reduce(
                                    state,
                                    HomeCameraAction.ViewerStatusChanged(ViewerStatus.Reconnecting, message),
                                )
                                state.viewer.selectedDevice?.let { device ->
                                    connectViewer(device, maxAttempts = 5) { reconnected, reconnectMessage ->
                                        if (reconnected == null) {
                                            state = HomeCameraReducer.reduce(
                                                state,
                                                HomeCameraAction.ViewerStatusChanged(
                                                    ViewerStatus.Error,
                                                    reconnectMessage,
                                                ),
                                            )
                                        } else {
                                            viewerConnection = reconnected
                                            startViewerStreamIfReady()
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }
            fun connectViewerStream(device: com.zx.homecamera.core.app.CollectorDevice, maxAttempts: Int = 1) {
                connectViewer(device, maxAttempts) { connection, message ->
                    if (connection == null) {
                        state = HomeCameraReducer.reduce(
                            state,
                            HomeCameraAction.ViewerStatusChanged(ViewerStatus.Error, message),
                        )
                    } else {
                        viewerConnection = connection
                        startViewerStreamIfReady()
                    }
                }
            }
            DisposableEffect(Unit) {
                CollectorCameraRuntime.setDisplayRotationDegrees(displayRotationDegrees())
                onDispose { viewerStream.stop() }
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                if (grants[Manifest.permission.CAMERA] == true &&
                    grants[Manifest.permission.RECORD_AUDIO] == true
                ) {
                    preselectCollectorPreviewSize()
                    state = HomeCameraReducer.reduce(state, HomeCameraAction.StartCollector)
                    context.startCollectorService()
                    state = HomeCameraReducer.reduce(state, HomeCameraAction.CollectorStarted)
                } else {
                    val message = if (grants[Manifest.permission.CAMERA] != true) {
                        "摄像头权限被拒绝，无法启动采集端"
                    } else {
                        "麦克风权限被拒绝，无法启动实时音频采集"
                    }
                    state = HomeCameraReducer.reduce(
                        state,
                        HomeCameraAction.CollectorFailed(message),
                    )
                }
            }

            HomeCameraTheme {
                HomeCameraApp(
                    state = state,
                    onAction = { action ->
                        when (action) {
                            HomeCameraAction.StartCollector -> {
                                preselectCollectorPreviewSize()
                                permissionLauncher.launch(collectorPermissions)
                            }
                            HomeCameraAction.StopCollector -> {
                                context.stopCollectorService()
                                state = HomeCameraReducer.reduce(state, action)
                            }

                            is HomeCameraAction.SelectRole -> {
                                state = HomeCameraReducer.reduce(state, action)
                                if (action.role == AppRole.Collector) {
                                    preselectCollectorPreviewSize()
                                }
                                if (action.role == AppRole.Client) {
                                    state = HomeCameraReducer.reduce(state, HomeCameraAction.StartScan)
                                    scanCollectors { discovered ->
                                        state = HomeCameraReducer.reduce(
                                            state,
                                            HomeCameraAction.DevicesDiscovered(discovered),
                                        )
                                    }
                                }
                            }

                            HomeCameraAction.StartScan -> {
                                state = HomeCameraReducer.reduce(state, action)
                                scanCollectors { discovered ->
                                    state = HomeCameraReducer.reduce(
                                        state,
                                        HomeCameraAction.DevicesDiscovered(discovered),
                                    )
                                }
                            }

                            is HomeCameraAction.OpenViewer -> {
                                viewerStream.stop()
                                viewerConnection = null
                                viewerStreamKey = null
                                state = HomeCameraReducer.reduce(state, action)
                                state.viewer.selectedDevice?.let { device ->
                                    connectViewerStream(device)
                                }
                            }

                            HomeCameraAction.BackToClientList -> {
                                viewerStream.stop()
                                viewerConnection = null
                                viewerSurface = null
                                viewerStreamKey = null
                                state = HomeCameraReducer.reduce(state, action)
                            }

                            else -> {
                                state = HomeCameraReducer.reduce(state, action)
                            }
                        }
                    },
                    onCollectorSurfaceReady = { holder, width, height ->
                        CollectorCameraRuntime.setPreviewSurface(holder, width, height)
                    },
                    onCollectorSurfaceDestroyed = {
                        CollectorCameraRuntime.setPreviewSurface(null)
                    },
                    collectorInitialPreviewSize = collectorPreviewSize.displaySize,
                    onCollectorPreviewSizeChanged = { listener ->
                        CollectorCameraRuntime.setPreviewDisplaySizeListener(listener)
                    },
                    viewerConnection = viewerConnection,
                    onViewerSurfaceReady = { surface ->
                        viewerSurface = surface
                        startViewerStreamIfReady()
                    },
                    onViewerSurfaceDestroyed = {
                        viewerSurface = null
                        viewerStream.stop()
                        viewerStreamKey = null
                        if (state.viewer.status == ViewerStatus.Playing) {
                            state = HomeCameraReducer.reduce(
                                state,
                                HomeCameraAction.ViewerStatusChanged(ViewerStatus.Disconnected),
                            )
                        }
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        scannerExecutor.shutdownNow()
        viewerExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun scanCollectors(onResult: (List<com.zx.homecamera.core.app.CollectorDevice>) -> Unit) {
        scannerExecutor.execute {
            val devices = LanDiscoveryScanner().scan(timeoutMillis = 1_800)
            runOnUiThread { onResult(devices) }
        }
    }

    private fun connectViewer(
        device: com.zx.homecamera.core.app.CollectorDevice,
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
            runOnUiThread {
                onResult(
                    connection,
                    lastError?.message,
                )
            }
        }
    }

    private fun collectorPermissions(): Array<String> =
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private fun defaultCollectorPreviewSize(): PreviewSize {
        val size = VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT)
        return PreviewSize(bufferSize = size, displaySize = size)
    }

    private fun Context.startCollectorService() {
        val intent = Intent(this, CollectorForegroundService::class.java)
            .setAction(CollectorForegroundService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun Context.stopCollectorService() {
        startService(
            Intent(this, CollectorForegroundService::class.java)
                .setAction(CollectorForegroundService.ACTION_STOP),
        )
    }
}
