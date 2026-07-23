package com.zx.homecamera.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zx.homecamera.CollectorViewModel
import com.zx.homecamera.R
import com.zx.homecamera.core.app.RecordingStatus
import com.zx.homecamera.core.app.ServiceStatus
import com.zx.homecamera.ui.AppTopBar
import com.zx.homecamera.ui.CenterCropSurfaceLayout
import com.zx.homecamera.ui.StatusDot
import com.zx.homecamera.ui.StatusRow
import com.zx.homecamera.ui.StatusDotView
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.video.CameraH264Streamer
import com.zx.homecamera.video.CameraPreviewConfigReader
import com.zx.homecamera.video.CollectorCameraRuntime
import com.zx.homecamera.video.H264StreamConfig
import com.zx.homecamera.video.PreviewSize
import com.zx.homecamera.video.VideoSize

class CollectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: CollectorViewModel = viewModel()
            val state by viewModel.state.collectAsState()
            val context = LocalContext.current
            var collectorPreviewSize by remember { mutableStateOf(defaultCollectorPreviewSize()) }

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

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                if (grants[Manifest.permission.CAMERA] != true) {
                    viewModel.onServiceStarting()
                    return@rememberLauncherForActivityResult
                }
                preselectCollectorPreviewSize()
                viewModel.startCollectorService()
            }

            fun ensureCollectorPermissionsAndStart() {
                val permissions = collectorPermissions()
                val allGranted = permissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (allGranted) {
                    preselectCollectorPreviewSize()
                    viewModel.onServiceStarting()
                    viewModel.startCollectorService()
                } else {
                    preselectCollectorPreviewSize()
                    permissionLauncher.launch(permissions)
                }
            }

            // 进入采集端即申请权限并启动服务，与重构前“选择采集端即启动”的行为一致。
            LaunchedEffect(Unit) {
                ensureCollectorPermissionsAndStart()
            }

            HomeCameraTheme {
                CollectorScreen(
                    state = state,
                    previewDisplaySize = collectorPreviewSize.displaySize,
                    onSurfaceReady = { holder, width, height ->
                        CollectorCameraRuntime.setPreviewSurface(holder, width, height)
                    },
                    onSurfaceDestroyed = {
                        CollectorCameraRuntime.setPreviewSurface(null)
                    },
                    onPreviewSizeChanged = { listener ->
                        CollectorCameraRuntime.setPreviewDisplaySizeListener(listener)
                    },
                    onStartCollector = { ensureCollectorPermissionsAndStart() },
                    onBack = {
                        viewModel.stopCollectorService()
                        finish()
                    },
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val viewModel = androidx.lifecycle.ViewModelProvider(this)[CollectorViewModel::class.java]
        val degrees = CameraH264Streamer.rotationDegrees(display?.rotation ?: Surface.ROTATION_0)
        viewModel.setDisplayRotationDegrees(degrees)
    }

    override fun onStop() {
        super.onStop()
        // 仅在采集端界面真正结束（返回键 / 退回角色选择）时停止采集服务，避免：
        // 1) 后台采集（切到桌面仍录像）能力被误关——onStop 在切后台时也会触发，但此时
        //    isFinishing 为 false，不停止服务；
        // 2) 切到客户端角色时采集端服务仍在后台运行——CollectorForegroundService 是
        //    START_STICKY 前台服务，持有相机/麦克风/编码器/录像线程，若放任其在后台继续
        //    运行，会与新启动的客户端接收/解码线程抢占 CPU，导致 UDP 接收线程饿死、视频
        //    分片大量丢失而黑屏（已实测：A 同时跑采集+客户端时丢包 1/3、rendered=0）。
        // RoleSelectionActivity 进入客户端时另有一道兜底 STOP，此处覆盖"返回键直接 finish"。
        if (isFinishing) {
            val viewModel = androidx.lifecycle.ViewModelProvider(this)[CollectorViewModel::class.java]
            viewModel.stopCollectorService()
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

@Composable
private fun CollectorScreen(
    state: com.zx.homecamera.core.app.CollectorState,
    previewDisplaySize: VideoSize,
    onSurfaceReady: (android.view.SurfaceHolder, Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onPreviewSizeChanged: (((VideoSize) -> Unit)?) -> Unit,
    onStartCollector: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        AppTopBar(title = "采集端", showBack = true, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .aspectRatio(
                        previewDisplaySize.width.toFloat() / previewDisplaySize.height.toFloat(),
                        matchHeightConstraintsFirst = true,
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        CenterCropSurfaceLayout(context).apply {
                            onContainerChanged = { holder, width, height ->
                                onSurfaceReady(holder, width, height)
                            }
                            surfaceView.holder.addCallback(
                                object : android.view.SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                        onSurfaceReady(holder, this@apply.width, this@apply.height)
                                    }

                                    override fun surfaceChanged(
                                        holder: android.view.SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int,
                                    ) {
                                        onSurfaceReady(holder, this@apply.width, this@apply.height)
                                    }

                                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                        onSurfaceDestroyed()
                                    }
                                },
                            )
                        }
                    },
                    update = { layout ->
                        layout.updatePreviewSize(previewDisplaySize.width, previewDisplaySize.height)
                    },
                )
                if (state.serviceStatus != ServiceStatus.Running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_collector),
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(28.dp).padding(),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "摄像头预览",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                    StatusRow("服务状态", state.serviceStatus.label(), state.serviceStatus.dotColor())
                    StatusRow(
                        "连接状态",
                        if (state.connectedClientCount > 0) "已连接 ${state.connectedClientCount} 个客户端" else "无客户端连接",
                        if (state.connectedClientCount > 0) StatusDot.Active else StatusDot.Gray,
                    )
                    StatusRow(
                        "录像状态",
                        state.recordingStatus.label(),
                        state.recordingStatus.dotColor(),
                        showDivider = false,
                    )
                    state.errorMessage?.let {
                        StatusRow("错误", it, StatusDot.Error, showDivider = false)
                    }
                }
            }
        }
    }
}

private fun ServiceStatus.label(): String = when (this) {
    ServiceStatus.NotStarted -> "未启动"
    ServiceStatus.Starting -> "启动中"
    ServiceStatus.Running -> "运行中"
    ServiceStatus.Stopped -> "已停止"
    ServiceStatus.Error -> "异常"
}

private fun ServiceStatus.dotColor(): StatusDot = when (this) {
    ServiceStatus.Running -> StatusDot.Active
    ServiceStatus.Error -> StatusDot.Error
    else -> StatusDot.Gray
}

private fun RecordingStatus.label(): String = when (this) {
    RecordingStatus.Idle -> "未录像"
    RecordingStatus.Starting -> "准备录像"
    RecordingStatus.Recording -> "录像中"
    RecordingStatus.StorageLow -> "存储不足"
    RecordingStatus.Cleaning -> "清理中"
    RecordingStatus.Error -> "异常"
}

private fun RecordingStatus.dotColor(): StatusDot = when (this) {
    RecordingStatus.Recording -> StatusDot.Active
    RecordingStatus.Error -> StatusDot.Error
    else -> StatusDot.Gray
}
