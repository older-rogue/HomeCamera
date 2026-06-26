package com.zx.homecamera

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zx.homecamera.core.app.HomeCameraAction
import com.zx.homecamera.core.app.Screen
import com.zx.homecamera.core.app.ViewerStatus
import com.zx.homecamera.ui.HomeCameraApp
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.video.CameraH264Streamer
import com.zx.homecamera.video.CameraPreviewConfigReader
import com.zx.homecamera.video.CollectorCameraRuntime
import com.zx.homecamera.video.H264StreamConfig
import com.zx.homecamera.video.PreviewSize
import com.zx.homecamera.video.VideoSize

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: HomeCameraViewModel = viewModel()
            val state by viewModel.state.collectAsState()
            var collectorPreviewSize by remember { mutableStateOf(defaultCollectorPreviewSize()) }
            val context = LocalContext.current

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

            DisposableEffect(Unit) {
                viewModel.setDisplayRotationDegrees(displayRotationDegrees())
                onDispose {}
            }

            DisposableEffect(state.screen == Screen.Viewer) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (state.screen == Screen.Viewer) {
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
                onDispose {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            LaunchedEffect(state.screen, state.viewer.status, state.viewer.errorMessage) {
                if (state.screen == Screen.Viewer && state.viewer.status == ViewerStatus.Error) {
                    Toast.makeText(context, state.viewer.errorMessage ?: "连接失败", Toast.LENGTH_SHORT).show()
                    viewModel.onAction(HomeCameraAction.BackToClientList)
                }
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                if (grants[Manifest.permission.CAMERA] == true &&
                    grants[Manifest.permission.RECORD_AUDIO] == true
                ) {
                    preselectCollectorPreviewSize()
                    viewModel.onAction(HomeCameraAction.StartCollector)
                    viewModel.startCollectorService()
                } else {
                    val message = if (grants[Manifest.permission.CAMERA] != true) {
                        "摄像头权限被拒绝，无法启动采集端"
                    } else {
                        "麦克风权限被拒绝，无法启动实时音频采集"
                    }
                    viewModel.onAction(HomeCameraAction.CollectorFailed(message))
                }
            }

            HomeCameraTheme {
                HomeCameraApp(
                    state = state,
                    onAction = { action ->
                        when (action) {
                            HomeCameraAction.StartCollector -> {
                                preselectCollectorPreviewSize()
                                permissionLauncher.launch(collectorPermissions())
                            }
                            else -> viewModel.onAction(action)
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
                    viewerConnection = null, // ViewModel manages viewer connection internally
                    onViewerSurfaceReady = { surface ->
                        viewModel.onViewerSurfaceReady(surface)
                    },
                    onViewerSurfaceDestroyed = {
                        viewModel.onViewerSurfaceDestroyed()
                    },
                    onLocalDebugViewerSurfaceReady = { surface ->
                        viewModel.startLocalDebugSession(surface)
                    },
                    onLocalDebugViewerSurfaceDestroyed = {
                        viewModel.stopLocalDebugSession()
                    },
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
}
