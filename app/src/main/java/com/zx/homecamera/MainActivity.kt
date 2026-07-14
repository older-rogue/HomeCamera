package com.zx.homecamera

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zx.homecamera.core.app.AppRole
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
            val viewerConnection by viewModel.viewerConnectionState.collectAsState()
            var collectorPreviewSize by remember { mutableStateOf(defaultCollectorPreviewSize()) }
            var permissionRequestTarget by remember { mutableStateOf<PermissionRequestTarget?>(null) }
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
                val target = permissionRequestTarget
                permissionRequestTarget = null
                if (grants[Manifest.permission.CAMERA] != true) {
                    val message = when (target) {
                        PermissionRequestTarget.LocalDebug -> "摄像头权限被拒绝，无法启动本地调试"
                        else -> "摄像头权限被拒绝，无法启动采集端"
                    }
                    when (target) {
                        PermissionRequestTarget.LocalDebug -> viewModel.onAction(
                            HomeCameraAction.LocalDebugStatusChanged(ViewerStatus.Error, message),
                        )
                        else -> viewModel.onAction(HomeCameraAction.CollectorFailed(message))
                    }
                    return@rememberLauncherForActivityResult
                }

                preselectCollectorPreviewSize()
                when (target) {
                    PermissionRequestTarget.LocalDebug -> viewModel.onAction(HomeCameraAction.EnterLocalDebug)
                    else -> {
                        viewModel.onAction(HomeCameraAction.StartCollector)
                        viewModel.startCollectorService()
                    }
                }
            }

            val ensureCollectorPermissionsAndStart = {
                val permissions = collectorPermissions()
                val allGranted = permissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (allGranted) {
                    preselectCollectorPreviewSize()
                    viewModel.onAction(HomeCameraAction.StartCollector)
                    viewModel.startCollectorService()
                } else {
                    preselectCollectorPreviewSize()
                    permissionRequestTarget = PermissionRequestTarget.Collector
                    permissionLauncher.launch(permissions)
                }
            }

            HomeCameraTheme {
                HomeCameraApp(
                    state = state,
                    onAction = { action ->
                        when (action) {
                            is HomeCameraAction.SelectRole -> {
                                viewModel.onAction(action)
                                if (action.role == AppRole.Collector) {
                                    ensureCollectorPermissionsAndStart()
                                }
                            }
                            HomeCameraAction.StartCollector -> {
                                ensureCollectorPermissionsAndStart()
                            }
                            HomeCameraAction.BackToRoleSelection -> {
                                viewModel.stopCollectorService()
                                viewModel.onAction(action)
                            }
                            HomeCameraAction.EnterLocalDebug -> {
                                permissionRequestTarget = PermissionRequestTarget.LocalDebug
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
                    viewerConnection = viewerConnection,
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
                    onExitApp = {
                        viewModel.stopCollectorService()
                        finishAndRemoveTask()
                    },
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges 让旋转时不重建 Activity，这里同步刷新采集端旋转元数据，
        // 否则 H.264 SPS/PPS 里的旋转角度会停留在首次进入时的值。
        // Activity 未重建，ViewModelStore 不变，取到的是同一个 ViewModel 实例。
        val viewModel = ViewModelProvider(this)[HomeCameraViewModel::class.java]
        val degrees = CameraH264Streamer.rotationDegrees(display?.rotation ?: Surface.ROTATION_0)
        viewModel.setDisplayRotationDegrees(degrees)
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

    private enum class PermissionRequestTarget {
        Collector,
        LocalDebug,
    }
}
