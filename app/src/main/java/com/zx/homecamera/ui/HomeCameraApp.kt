package com.zx.homecamera.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zx.homecamera.R
import com.zx.homecamera.core.app.AppRole
import com.zx.homecamera.core.app.ClientState
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.CollectorState
import com.zx.homecamera.core.app.HomeCameraAction
import com.zx.homecamera.core.app.HomeCameraState
import com.zx.homecamera.core.app.RecordingLibraryState
import com.zx.homecamera.core.app.RecordingLibraryStatus
import com.zx.homecamera.core.app.RecordingPlaybackState
import com.zx.homecamera.core.app.RecordingPlaybackStatus
import com.zx.homecamera.core.app.ScanStatus
import com.zx.homecamera.core.app.Screen
import com.zx.homecamera.core.app.ServiceStatus
import com.zx.homecamera.core.app.ViewerState
import com.zx.homecamera.core.app.ViewerStatus
import com.zx.homecamera.core.protocol.RecordingEntry
import com.zx.homecamera.network.ViewerConnection
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.ui.theme.Teal700
import com.zx.homecamera.video.H264StreamConfig
import com.zx.homecamera.video.VideoSize
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeCameraApp(
    state: HomeCameraState,
    onAction: (HomeCameraAction) -> Unit,
    onCollectorSurfaceReady: (SurfaceHolder, Int, Int) -> Unit = { _, _, _ -> },
    onCollectorSurfaceDestroyed: () -> Unit = {},
    collectorInitialPreviewSize: VideoSize = VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT),
    onCollectorPreviewSizeChanged: (((VideoSize) -> Unit)?) -> Unit = {},
    viewerConnection: ViewerConnection? = null,
    onViewerSurfaceReady: (Surface) -> Unit = {},
    onViewerSurfaceDestroyed: () -> Unit = {},
    onExitApp: () -> Unit = {},
) {
    var collectorPreviewWidth by rememberSaveable(collectorInitialPreviewSize) {
        mutableStateOf(collectorInitialPreviewSize.width)
    }
    var collectorPreviewHeight by rememberSaveable(collectorInitialPreviewSize) {
        mutableStateOf(collectorInitialPreviewSize.height)
    }
    val collectorPreviewSize = VideoSize(collectorPreviewWidth, collectorPreviewHeight)

    DisposableEffect(onCollectorPreviewSizeChanged) {
        onCollectorPreviewSizeChanged { size ->
            collectorPreviewWidth = size.width
            collectorPreviewHeight = size.height
        }
        onDispose {
            onCollectorPreviewSizeChanged(null)
        }
    }

    if (state.screen == Screen.Viewer) {
        ViewerScreen(
            state = state.viewer,
            modifier = Modifier.fillMaxSize(),
            onAction = onAction,
            displaySize = ViewerPreviewSize.displaySize(viewerConnection),
            surfaceSize = ViewerPreviewSize.surfaceSize(viewerConnection),
            rotationDegrees = ViewerPreviewSize.rotationDegrees(viewerConnection),
            onSurfaceReady = onViewerSurfaceReady,
            onSurfaceDestroyed = onViewerSurfaceDestroyed,
        )
        return
    }

    if (state.screen == Screen.RecordingLibrary) {
        RecordingLibraryScreen(
            state = state.recordingLibrary,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            onAction = onAction,
        )
        return
    }

    if (state.screen == Screen.RecordingPlayback) {
        RecordingPlaybackScreen(
            state = state.recordingPlayback,
            modifier = Modifier.fillMaxSize(),
            onAction = onAction,
        )
        return
    }

    // 一级一级往回退：子页面拦截系统返回键回到上级，模式选择页才退出 App。
    BackHandler(enabled = state.screen != Screen.RoleSelection) {
        onAction(
            when (state.screen) {
                Screen.Viewer -> HomeCameraAction.BackToClientList
                Screen.ClientList -> HomeCameraAction.BackToRoleSelection
                Screen.Collector -> HomeCameraAction.BackToRoleSelection
                Screen.RecordingLibrary -> HomeCameraAction.BackToViewer
                Screen.RecordingPlayback -> HomeCameraAction.BackToRecordingLibrary
                Screen.RoleSelection -> HomeCameraAction.BackToRoleSelection
            },
        )
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
    ) {
        // 通用 TopAppBar（客户端 / 采集端有返回按钮，模式选择页仅标题）
        // Viewer/RecordingLibrary/RecordingPlayback 已在上方提前 return，这里不会到达。
        val title = when (state.screen) {
            Screen.RoleSelection -> "HomeCamera"
            Screen.Collector -> "采集端"
            Screen.ClientList -> "客户端"
            Screen.Viewer, Screen.RecordingLibrary, Screen.RecordingPlayback -> ""
        }
        val showBack = state.screen == Screen.Collector ||
            state.screen == Screen.ClientList
        val onBackAction: HomeCameraAction = when (state.screen) {
            Screen.ClientList -> HomeCameraAction.BackToRoleSelection
            else -> HomeCameraAction.BackToRoleSelection
        }
        AppTopBar(
            title = title,
            showBack = showBack,
            onBack = { onAction(onBackAction) },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
        ) {
            when (state.screen) {
                Screen.RoleSelection -> RoleSelectionScreen(onAction)
                Screen.Collector -> CollectorScreen(
                    state = state.collector,
                    onAction = onAction,
                    onSurfaceReady = onCollectorSurfaceReady,
                    onSurfaceDestroyed = onCollectorSurfaceDestroyed,
                    previewDisplaySize = collectorPreviewSize,
                )
                Screen.ClientList -> ClientListScreen(state.client, onAction)
                Screen.Viewer, Screen.RecordingLibrary, Screen.RecordingPlayback -> Unit
            }
        }
    }
}

@Composable
private fun AppTopBar(title: String, showBack: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_back),
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            Spacer(Modifier.size(44.dp))
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 19.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.size(44.dp))
    }
}

@Composable
private fun RoleSelectionScreen(onAction: (HomeCameraAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "选择运行模式",
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        RoleCard(
            iconRes = R.drawable.ic_collector,
            name = "采集端",
            sub = "实时编码 H.264/AAC 推流",
            primary = false,
            onClick = { onAction(HomeCameraAction.SelectRole(AppRole.Collector)) },
        )
        RoleCard(
            iconRes = R.drawable.ic_client,
            name = "客户端",
            sub = "查看局域网采集端画面",
            primary = false,
            onClick = { onAction(HomeCameraAction.SelectRole(AppRole.Client)) },
        )
    }
}

@Composable
private fun RoleCard(
    iconRes: Int,
    name: String,
    sub: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor = if (primary) Color.White else MaterialTheme.colorScheme.onSurface
    val iconBg = if (primary) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val iconTint = if (primary) Color.White else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (primary) {
                    Modifier.background(containerColor)
                } else {
                    Modifier
                        .background(containerColor)
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                },
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            Text(
                text = sub,
                fontSize = 12.sp,
                color = contentColor.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun CollectorScreen(
    state: CollectorState,
    onAction: (HomeCameraAction) -> Unit,
    onSurfaceReady: (SurfaceHolder, Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    previewDisplaySize: VideoSize,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    onSurfaceReady(holder, this@apply.width, this@apply.height)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    onSurfaceReady(holder, this@apply.width, this@apply.height)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
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
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "摄像头预览",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 状态卡片
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
                    StatusRow(
                        "错误",
                        it,
                        StatusDot.Error,
                        showDivider = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, dot: StatusDot, showDivider: Boolean = true) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dot)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.Black.copy(alpha = 0.05f)),
            )
        }
    }
}

private enum class StatusDot { Active, Error, Gray }

@Composable
private fun StatusDot(dot: StatusDot) {
    val color = when (dot) {
        StatusDot.Active -> Teal700
        StatusDot.Error -> MaterialTheme.colorScheme.error
        StatusDot.Gray -> Color(0xFF9AA0B4)
    }
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun ClientListScreen(
    state: ClientState,
    onAction: (HomeCameraAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(StatusDot.Active)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.scanStatus.label(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            ScanRefreshButton(onClick = { onAction(HomeCameraAction.StartScan) })
        }

        if (state.devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.scanStatus == ScanStatus.Scanning) "正在扫描局域网采集端" else "未发现采集端",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.devices, key = { it.deviceId }) { device ->
                    DeviceItem(
                        device = device,
                        onClick = { onAction(HomeCameraAction.OpenViewer(device.deviceId)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanRefreshButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_refresh),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "刷新",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DeviceItem(device: CollectorDevice, onClick: () -> Unit) {
    val containerColor = if (device.online) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .clickable(enabled = device.online, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_collector),
                contentDescription = null,
                tint = if (device.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.name,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (device.online) StatusDot.Active else StatusDot.Error)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (device.online) "在线" else "离线",
                        color = if (device.online) Teal700 else MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
            }
            Text(
                text = "${device.hostAddress}:${device.tcpPort}",
                modifier = Modifier.padding(top = 3.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ViewerScreen(
    state: ViewerState,
    modifier: Modifier = Modifier,
    onAction: (HomeCameraAction) -> Unit,
    displaySize: VideoSize,
    surfaceSize: VideoSize,
    rotationDegrees: Float,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
) {
    Box(modifier = modifier.background(Color.Black)) {
        BackHandler { onAction(HomeCameraAction.BackToClientList) }
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

        // 返回按钮（胶囊浮层）
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 52.dp, start = 16.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { onAction(HomeCameraAction.BackToClientList) }
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

        // 历史录像入口（胶囊浮层）
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 52.dp, start = 96.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { onAction(HomeCameraAction.OpenRecordingLibrary) }
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
                    .padding(top = 56.dp, end = 16.dp),
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
        ViewerStatusBar(status = state.status, errorMessage = state.errorMessage)
    }
}

@Composable
private fun RecordingLibraryScreen(
    state: RecordingLibraryState,
    modifier: Modifier = Modifier,
    onAction: (HomeCameraAction) -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onAction(HomeCameraAction.BackToViewer) },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_back),
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "历史录像",
                modifier = Modifier.weight(1f),
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            ScanRefreshButton(onClick = { onAction(HomeCameraAction.RefreshRecordingLibrary) })
            Spacer(Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
        ) {
            // 日期选择器
            if (state.dates.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                ) {
                    items(state.dates, key = { it }) { date ->
                        val selected = date == state.selectedDate
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .border(
                                    1.dp,
                                    if (selected) Color.Transparent else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(16.dp),
                                )
                                .clickable { onAction(HomeCameraAction.SelectRecordingDate(date)) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = date,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            when {
                state.status == RecordingLibraryStatus.Loading && state.files.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "加载中...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.status == RecordingLibraryStatus.Error && state.files.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.errorMessage ?: "加载失败",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                state.files.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "当日无录像",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.files, key = { it.fileId }) { entry ->
                            RecordingFileItem(
                                entry = entry,
                                isDownloading = state.downloadingFileId == entry.fileId,
                                downloadProgress = if (state.downloadingFileId == entry.fileId) state.downloadProgress else 0f,
                                onPlay = { onAction(HomeCameraAction.OpenRecordingPlayback(entry.fileId)) },
                                onDownload = { onAction(HomeCameraAction.DownloadRecording(entry.fileId)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingFileItem(
    entry: RecordingEntry,
    isDownloading: Boolean,
    downloadProgress: Float,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val isRecording = entry.recording
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isRecording) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatRecordingTime(entry.startMillis),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (isRecording) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "录制中",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Text(
                    text = if (isRecording) "录制中，结束后可查看" else "大小 ${formatFileSize(entry.sizeBytes)}",
                    modifier = Modifier.padding(top = 3.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                if (isDownloading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            PillButton(text = "播放", primary = true, enabled = !isRecording, onClick = onPlay)
            Spacer(Modifier.width(8.dp))
            PillButton(
                text = if (isDownloading) "${(downloadProgress * 100).roundToInt()}%" else "下载",
                primary = false,
                enabled = !isDownloading && !isRecording,
                onClick = onDownload,
            )
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val content = if (primary) Color.White else MaterialTheme.colorScheme.primary
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(bg.copy(alpha = alpha))
            .then(
                if (primary) Modifier
                else Modifier.border(1.5.dp, content.copy(alpha = alpha), RoundedCornerShape(17.dp)),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = content.copy(alpha = alpha),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun RecordingPlaybackScreen(
    state: RecordingPlaybackState,
    modifier: Modifier = Modifier,
    onAction: (HomeCameraAction) -> Unit,
) {
    Box(modifier = modifier.background(Color.Black)) {
        BackHandler { onAction(HomeCameraAction.BackToRecordingLibrary) }

        when (state.status) {
            RecordingPlaybackStatus.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "加载录像中...", color = Color.White, fontSize = 14.sp)
                }
            }
            RecordingPlaybackStatus.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.errorMessage ?: "加载失败",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                }
            }
            RecordingPlaybackStatus.Playing -> {
                val cachedFile = state.cachedFile
                if (cachedFile != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            VideoView(context).apply {
                                setVideoURI(Uri.fromFile(cachedFile))
                                setOnPreparedListener { it.isLooping = false }
                                start()
                            }
                        },
                    )
                }
            }
        }

        // 返回按钮
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 52.dp, start = 16.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { onAction(HomeCameraAction.BackToRecordingLibrary) }
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

        // 底部保存到相册按钮
        if (state.status == RecordingPlaybackStatus.Playing) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = !state.savingToGallery && !state.savedToGallery) {
                        onAction(HomeCameraAction.SavePlaybackToGallery)
                    }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = when {
                    state.savedToGallery -> "已保存到相册"
                    state.savingToGallery -> "保存中..."
                    else -> "保存到相册"
                }
                Text(text = label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun formatRecordingTime(startMillis: Long): String {
    if (startMillis <= 0L) return "录像"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(startMillis))
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    if (bytes < 1024 * 1024) return "${bytes / 1024}KB"
    return "%.1fMB".format(bytes / (1024.0 * 1024.0))
}

@Composable
private fun BoxScope.ViewerStatusBar(status: ViewerStatus, errorMessage: String?) {
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
        StatusDot(if (status == ViewerStatus.Playing) StatusDot.Active else StatusDot.Gray)
        Spacer(Modifier.width(10.dp))
        Text(text = text, color = Color.White, fontSize = 13.sp)
        if (status == ViewerStatus.Playing) {
            Spacer(Modifier.width(10.dp))
            LoadingMiniBar()
        }
    }
}

@Composable
private fun LoadingMiniBar() {
    val transition = rememberInfiniteTransition(label = "miniBar")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress",
    )
    Box(
        modifier = Modifier
            .width(60.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        // 宽度 40% 的滑块从左滑到右，复刻 CSS translateX(-100%)->(350%)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.4f)
                .clip(RoundedCornerShape(3.dp))
                .background(Teal700)
                .offset(x = ((progress * 3.5f - 1f) * 60).dp),
        )
    }
}

private class CenterCropSurfaceLayout(context: Context) : FrameLayout(context) {
    val surfaceView: SurfaceView = SurfaceView(context)
    var onContainerChanged: ((SurfaceHolder, Int, Int) -> Unit)? = null

    private var previewWidth = H264StreamConfig.WIDTH
    private var previewHeight = H264StreamConfig.HEIGHT

    init {
        clipChildren = true
        addView(surfaceView)
    }

    fun updatePreviewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (previewWidth == width && previewHeight == height) return
        previewWidth = width
        previewHeight = height
        requestLayout()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0 && surfaceView.holder.surface?.isValid == true) {
            onContainerChanged?.invoke(surfaceView.holder, width, height)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val containerWidth = right - left
        val containerHeight = bottom - top
        if (containerWidth <= 0 || containerHeight <= 0) return

        val (childWidth, childHeight) = centerCropSize(containerWidth, containerHeight)
        val childLeft = (containerWidth - childWidth) / 2
        val childTop = (containerHeight - childHeight) / 2
        surfaceView.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val (childWidth, childHeight) = centerCropSize(measuredWidth, measuredHeight)
        surfaceView.measure(
            MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
        )
    }

    private fun centerCropSize(containerWidth: Int, containerHeight: Int): Pair<Int, Int> {
        if (containerWidth <= 0 || containerHeight <= 0) return 0 to 0
        val previewAspect = previewWidth.toFloat() / previewHeight.toFloat()
        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
        return if (previewAspect > containerAspect) {
            val childHeight = containerHeight
            val childWidth = (childHeight * previewAspect).roundToInt()
            childWidth to childHeight
        } else {
            val childWidth = containerWidth
            val childHeight = (childWidth / previewAspect).roundToInt()
            childWidth to childHeight
        }
    }
}

private class RotatedViewerTextureLayout(context: Context) : FrameLayout(context) {
    private val textureView: TextureView = TextureView(context)
    private var surfaceSize = VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT)
    private var rotationDegrees = 0f
    private var decoderSurface: Surface? = null
    var onSurfaceAvailable: ((Surface) -> Unit)? = null
    var onSurfaceDestroyed: (() -> Unit)? = null

    init {
        clipChildren = true
        addView(textureView)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                surfaceTexture.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
                decoderSurface?.release()
                decoderSurface = Surface(surfaceTexture).also { surface ->
                    onSurfaceAvailable?.invoke(surface)
                }
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                surfaceTexture.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
                textureView.setTransform(null)
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                decoderSurface?.release()
                decoderSurface = null
                onSurfaceDestroyed?.invoke()
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
    }

    fun updateConfig(surfaceSize: VideoSize, rotationDegrees: Float) {
        if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return
        this.surfaceSize = surfaceSize
        this.rotationDegrees = rotationDegrees
        textureView.rotation = rotationDegrees
        textureView.surfaceTexture?.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
        textureView.setTransform(null)
        dispatchAvailableSurface()
        requestLayout()
    }

    fun dispatchAvailableSurface() {
        val surface = decoderSurface
        if (surface != null && surface.isValid) {
            onSurfaceAvailable?.invoke(surface)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val containerWidth = right - left
        val containerHeight = bottom - top
        if (containerWidth <= 0 || containerHeight <= 0) return

        val size = ViewerTextureTransform.textureLayoutSize(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            surfaceSize = surfaceSize,
            rotationDegrees = rotationDegrees,
        )
        val childLeft = (containerWidth - size.width) / 2
        val childTop = (containerHeight - size.height) / 2
        textureView.layout(childLeft, childTop, childLeft + size.width, childTop + size.height)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = ViewerTextureTransform.textureLayoutSize(
            containerWidth = measuredWidth,
            containerHeight = measuredHeight,
            surfaceSize = surfaceSize,
            rotationDegrees = rotationDegrees,
        )
        textureView.measure(
            MeasureSpec.makeMeasureSpec(size.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size.height, MeasureSpec.EXACTLY),
        )
    }
}

private fun ServiceStatus.label(): String =
    when (this) {
        ServiceStatus.NotStarted -> "未启动"
        ServiceStatus.Starting -> "启动中"
        ServiceStatus.Running -> "运行中"
        ServiceStatus.Stopped -> "已停止"
        ServiceStatus.Error -> "异常"
    }

private fun ServiceStatus.dotColor(): StatusDot =
    when (this) {
        ServiceStatus.Running -> StatusDot.Active
        ServiceStatus.Error -> StatusDot.Error
        else -> StatusDot.Gray
    }

private fun com.zx.homecamera.core.app.RecordingStatus.label(): String =
    when (this) {
        com.zx.homecamera.core.app.RecordingStatus.Idle -> "未录像"
        com.zx.homecamera.core.app.RecordingStatus.Starting -> "准备录像"
        com.zx.homecamera.core.app.RecordingStatus.Recording -> "录像中"
        com.zx.homecamera.core.app.RecordingStatus.StorageLow -> "存储不足"
        com.zx.homecamera.core.app.RecordingStatus.Cleaning -> "清理中"
        com.zx.homecamera.core.app.RecordingStatus.Error -> "异常"
    }

private fun com.zx.homecamera.core.app.RecordingStatus.dotColor(): StatusDot =
    when (this) {
        com.zx.homecamera.core.app.RecordingStatus.Recording -> StatusDot.Active
        com.zx.homecamera.core.app.RecordingStatus.Error -> StatusDot.Error
        else -> StatusDot.Gray
    }

private fun ScanStatus.label(): String =
    when (this) {
        ScanStatus.Idle -> "等待扫描"
        ScanStatus.Scanning -> "扫描中"
        ScanStatus.Finished -> "扫描完成"
        ScanStatus.Error -> "扫描异常"
    }

private fun ViewerStatus.label(): String =
    when (this) {
        ViewerStatus.Idle -> "未连接"
        ViewerStatus.Connecting -> "连接中"
        ViewerStatus.Playing -> "播放中"
        ViewerStatus.Disconnected -> "已断开"
        ViewerStatus.Reconnecting -> "重连中"
        ViewerStatus.Error -> "连接异常"
    }

@Preview(showBackground = true)
@Composable
private fun HomeCameraPreview() {
    HomeCameraTheme {
        HomeCameraApp(
            state = HomeCameraState(),
            onAction = {},
        )
    }
}
