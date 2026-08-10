package com.zx.homecamera.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import com.zx.homecamera.R
import com.zx.homecamera.ViewerViewModel
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.ViewerStatus
import com.zx.homecamera.ui.RotatedViewerTextureLayout
import com.zx.homecamera.ui.StatusDot
import com.zx.homecamera.ui.StatusDotView
import com.zx.homecamera.ui.ViewerPreviewSize
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.ui.theme.Teal700
import com.zx.homecamera.video.CameraH264Streamer
import com.zx.homecamera.video.VideoSize

class ViewerActivity : ComponentActivity() {
    private val viewModel: ViewerViewModel by lazy {
        ViewModelProvider(this)[ViewerViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 预览页背景为黑色，系统状态栏图标/文字使用浅色（白色），与黑底保持高对比。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val device = CollectorDevice.fromIntent(intent)
        setContent {
            val state by viewModel.state.collectAsState()
            val viewerConnection by viewModel.viewerConnectionState.collectAsState()
            val lastFrameTimeText by viewModel.lastFrameTimeText.collectAsState()

            LaunchedEffect(device) {
                device?.let { viewModel.setDevice(it) }
            }

            LaunchedEffect(state.status, state.errorMessage) {
                if (state.status == ViewerStatus.Error) {
                    Toast.makeText(this@ViewerActivity, state.errorMessage ?: "连接失败", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }

            HomeCameraTheme {
                ViewerScreen(
                    state = state,
                    viewerConnection = viewerConnection,
                    lastFrameTimeText = lastFrameTimeText,
                    onSurfaceReady = { surface -> viewModel.onViewerSurfaceReady(surface) },
                    onSurfaceDestroyed = { viewModel.onViewerSurfaceDestroyed() },
                    onBack = { finish() },
                    onOpenRecordingLibrary = { device?.let { openRecordingLibrary(it) } },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.resume()
    }

    override fun onPause() {
        super.onPause()
        viewModel.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun openRecordingLibrary(device: CollectorDevice) {
        val intent = Intent(this, RecordingLibraryActivity::class.java)
        device.toIntent(intent)
        startActivity(intent)
    }
}

@Composable
private fun ViewerScreen(
    state: com.zx.homecamera.core.app.ViewerState,
    viewerConnection: com.zx.homecamera.network.ViewerConnection?,
    lastFrameTimeText: String,
    onSurfaceReady: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onBack: () -> Unit,
    onOpenRecordingLibrary: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val displaySize = ViewerPreviewSize.displaySize(viewerConnection)
        val surfaceSize = ViewerPreviewSize.surfaceSize(viewerConnection)
        val rotationDegrees = ViewerPreviewSize.rotationDegrees(viewerConnection)

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                RotatedViewerTextureLayout(context).apply {
                    updateConfig(surfaceSize, rotationDegrees)
                    this.onSurfaceAvailable = onSurfaceReady
                    this.onSurfaceDestroyed = onSurfaceDestroyed
                }
            },
            update = { layout ->
                layout.onSurfaceAvailable = onSurfaceReady
                layout.onSurfaceDestroyed = onSurfaceDestroyed
                layout.updateConfig(surfaceSize, rotationDegrees)
            },
        )

        // 返回按钮
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 8.dp, start = 16.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { onBack() }
                .padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_back),
                contentDescription = "返回",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(text = "返回", color = Color.White, fontSize = 13.sp)
        }

        // 历史录像入口
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 8.dp, start = 96.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { onOpenRecordingLibrary() }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "历史录像", color = Color.White, fontSize = 13.sp)
        }

        // 右上角设备标签
        state.selectedDevice?.let { device ->
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 12.dp, end = 16.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = device.name,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${device.hostAddress}:${device.tcpPort}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }
        }

        // 底部状态条
        ViewerStatusBar(status = state.status, errorMessage = state.errorMessage, lastFrameTimeText = lastFrameTimeText)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ViewerStatusBar(
    status: ViewerStatus,
    errorMessage: String?,
    lastFrameTimeText: String,
) {
    val text = when (status) {
        ViewerStatus.Playing -> "播放中"
        ViewerStatus.Connecting -> "连接中"
        ViewerStatus.Reconnecting -> "重连中"
        ViewerStatus.Disconnected -> "已断开"
        ViewerStatus.Idle -> "未连接"
        ViewerStatus.Error -> errorMessage ?: "连接异常"
    }
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == ViewerStatus.Playing) StatusDotView(StatusDot.Active) else StatusDotView(StatusDot.Gray)
        Spacer(Modifier.width(10.dp))
        Text(text = text, color = Color.White, fontSize = 13.sp)
        if (lastFrameTimeText.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(text = lastFrameTimeText, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}
