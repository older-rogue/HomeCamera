package com.zx.homecamera.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zx.homecamera.core.app.AppRole
import com.zx.homecamera.core.app.ClientState
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.CollectorState
import com.zx.homecamera.core.app.HomeCameraAction
import com.zx.homecamera.core.app.HomeCameraState
import com.zx.homecamera.core.app.ScanStatus
import com.zx.homecamera.core.app.Screen
import com.zx.homecamera.core.app.ServiceStatus
import com.zx.homecamera.core.app.ViewerState
import com.zx.homecamera.core.app.ViewerStatus
import com.zx.homecamera.network.ViewerConnection
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.video.H264StreamConfig
import com.zx.homecamera.video.VideoSize
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
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
    onLocalDebugViewerSurfaceReady: (Surface) -> Unit = {},
    onLocalDebugViewerSurfaceDestroyed: () -> Unit = {},
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (state.screen) {
                            Screen.RoleSelection -> "HomeCamera"
                            Screen.Collector -> "采集端"
                            Screen.ClientList -> "客户端"
                            Screen.Viewer -> "实时观看"
                            Screen.LocalDebug -> "本地调试"
                        },
                    )
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
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
                Screen.Viewer -> Unit
                Screen.LocalDebug -> LocalDebugScreen(
                    state = state.localDebug,
                    onAction = onAction,
                    onCollectorSurfaceReady = onCollectorSurfaceReady,
                    onCollectorSurfaceDestroyed = onCollectorSurfaceDestroyed,
                    collectorPreviewSize = collectorPreviewSize,
                    onViewerSurfaceReady = onLocalDebugViewerSurfaceReady,
                    onViewerSurfaceDestroyed = onLocalDebugViewerSurfaceDestroyed,
                )
            }
        }
    }
}

@Composable
private fun RoleSelectionScreen(onAction: (HomeCameraAction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "选择运行模式",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(HomeCameraAction.SelectRole(AppRole.Collector)) },
        ) {
            Text("采集端")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(HomeCameraAction.SelectRole(AppRole.Client)) },
        ) {
            Text("客户端")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(HomeCameraAction.EnterLocalDebug) },
        ) {
            Text("本地调试")
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(previewDisplaySize.width.toFloat() / previewDisplaySize.height.toFloat())
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
                Text(
                    text = "摄像头预览",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        StatusRow("服务状态", state.serviceStatus.label())
        StatusRow(
            "连接状态",
            if (state.connectedClientCount > 0) "已连接 ${state.connectedClientCount} 个客户端" else "无客户端连接",
        )
        StatusRow("录像状态", state.recordingStatus.label())
        state.errorMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = state.serviceStatus != ServiceStatus.Running &&
                    state.serviceStatus != ServiceStatus.Starting,
                onClick = { onAction(HomeCameraAction.StartCollector) },
            ) {
                Text("开始服务")
            }
            OutlinedButton(
                enabled = state.serviceStatus == ServiceStatus.Running,
                onClick = { onAction(HomeCameraAction.StopCollector) },
            ) {
                Text("停止服务")
            }
        }
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

@Composable
private fun ClientListScreen(
    state: ClientState,
    onAction: (HomeCameraAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.scanStatus.label(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = { onAction(HomeCameraAction.StartScan) }) {
                Text("刷新")
            }
        }

        if (state.devices.isEmpty()) {
            EmptyState(state.scanStatus)
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
private fun ViewerScreen(
    modifier: Modifier = Modifier,
    onAction: (HomeCameraAction) -> Unit,
    displaySize: VideoSize,
    surfaceSize: VideoSize,
    rotationDegrees: Float,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
) {
    Box(
        modifier = modifier.background(Color.Black),
    ) {
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
        OutlinedButton(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            onClick = { onAction(HomeCameraAction.BackToClientList) },
        ) {
            Text("返回")
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

@Composable
private fun DeviceItem(device: CollectorDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = device.online, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (device.online) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(12.dp))
                Text(if (device.online) "在线" else "离线")
            }
            Spacer(Modifier.height(6.dp))
            Text("${device.hostAddress}:${device.tcpPort}")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyState(scanStatus: ScanStatus) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (scanStatus == ScanStatus.Scanning) "正在扫描局域网采集端" else "未发现采集端",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun com.zx.homecamera.core.app.RecordingStatus.label(): String =
    when (this) {
        com.zx.homecamera.core.app.RecordingStatus.Idle -> "未录像"
        com.zx.homecamera.core.app.RecordingStatus.Starting -> "准备录像"
        com.zx.homecamera.core.app.RecordingStatus.Recording -> "录像中"
        com.zx.homecamera.core.app.RecordingStatus.StorageLow -> "存储不足"
        com.zx.homecamera.core.app.RecordingStatus.Cleaning -> "清理中"
        com.zx.homecamera.core.app.RecordingStatus.Error -> "异常"
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

@Composable
private fun LocalDebugScreen(
    state: ViewerState,
    onAction: (HomeCameraAction) -> Unit,
    onCollectorSurfaceReady: (SurfaceHolder, Int, Int) -> Unit,
    onCollectorSurfaceDestroyed: () -> Unit,
    collectorPreviewSize: VideoSize,
    onViewerSurfaceReady: (Surface) -> Unit,
    onViewerSurfaceDestroyed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "采集端预览",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(collectorPreviewSize.width.toFloat() / collectorPreviewSize.height.toFloat())
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    CenterCropSurfaceLayout(context).apply {
                        onContainerChanged = { holder, width, height ->
                            onCollectorSurfaceReady(holder, width, height)
                        }
                        surfaceView.holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    onCollectorSurfaceReady(holder, this@apply.width, this@apply.height)
                                }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    onCollectorSurfaceReady(holder, this@apply.width, this@apply.height)
                                }
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    onCollectorSurfaceDestroyed()
                                }
                            },
                        )
                    }
                },
                update = { layout ->
                    layout.updatePreviewSize(collectorPreviewSize.width, collectorPreviewSize.height)
                },
            )
        }

        Text(
            text = "客户端画面",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val displaySize = VideoSize(480, 640)
            val surfaceSize = VideoSize(640, 480)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    RotatedViewerTextureLayout(context).apply {
                        updateConfig(surfaceSize, 0f)
                        this.onSurfaceAvailable = onViewerSurfaceReady
                        this.onSurfaceDestroyed = onViewerSurfaceDestroyed
                    }
                },
                update = { layout ->
                    layout.onSurfaceAvailable = onViewerSurfaceReady
                    layout.onSurfaceDestroyed = onViewerSurfaceDestroyed
                    layout.updateConfig(surfaceSize, 0f)
                },
            )
            if (state.status != ViewerStatus.Playing) {
                Text(
                    text = state.status.label(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Text(text = "状态: ${state.status.label()}")
        state.errorMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onAction(HomeCameraAction.ExitLocalDebug) }) {
                Text("退出调试")
            }
        }
    }
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
