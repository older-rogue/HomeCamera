package com.zx.homecamera.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.zx.homecamera.R
import com.zx.homecamera.RecordingPlaybackViewModel
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.RecordingPlaybackStatus
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.video.VideoCacheManager

class RecordingPlaybackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val device = CollectorDevice.fromIntent(intent)
        val fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: ""
        setContent {
            val viewModel: RecordingPlaybackViewModel = viewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(device, fileId) {
                device?.let { viewModel.init(it, fileId) }
            }

            HomeCameraTheme {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    when (state.status) {
                        RecordingPlaybackStatus.Loading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("加载录像中...", color = Color.White, fontSize = 14.sp)
                            }
                        }
                        RecordingPlaybackStatus.Error -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(state.errorMessage ?: "加载失败", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                            }
                        }
                        RecordingPlaybackStatus.Playing -> {
                            val playbackUrl = state.playbackUrl
                            if (playbackUrl != null) {
                                ExoVideoPlayer(
                                    playbackUrl = playbackUrl,
                                    onPlaybackError = { viewModel.onPlaybackError("视频加载失败，可能文件损坏") },
                                )
                            }
                        }
                    }

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
                            .clickable { finish() }
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
                }
            }
        }
    }

    companion object {
        const val EXTRA_FILE_ID = "file_id"
    }
}

/**
 * ExoPlayer 视频播放器。
 *
 * - 500ms 起播缓冲（DefaultLoadControl），LAN 下近乎即时出画面
 * - 磁盘缓存（VideoCacheManager），二次观看/拖拽已缓存区域秒开
 * - PlayerView 自带控制器（进度条、播放/暂停），点击显示/自动隐藏
 */
@OptIn(UnstableApi::class)
@Composable
private fun ExoVideoPlayer(
    playbackUrl: String,
    onPlaybackError: () -> Unit,
) {
    val context = LocalContext.current
    // VideoCacheManager 进程级单例：缓存跨播放实例复用，二次观看秒开。
    // 必须用单例：SimpleCache 会锁定缓存目录，重复创建会抛异常。
    val videoCacheManager = remember { VideoCacheManager.get(context) }

    val exoPlayer = remember(playbackUrl) {
        val loadControl = DefaultLoadControl.Builder()
            // (minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
            // 500ms 即可起播，同时最多缓冲 60s 保证流畅；LAN 带宽充足不会卡顿。
            .setBufferDurationsMs(15_000, 60_000, 500, 1_000)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(videoCacheManager.dataSourceFactory()),
            )
            .build()
            .apply {
                setMediaItem(Media3MediaItem.fromUri(playbackUrl))
                prepare()
                playWhenReady = true
            }
    }

    // 退出时释放 player，避免后台继续拉流占用采集端连接。
    DisposableEffect(playbackUrl) {
        onDispose { exoPlayer.release() }
    }

    // 播放出错回调
    DisposableEffect(playbackUrl) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("RecordingPlayback", "ExoPlayer error: ${error.errorCodeName} cause=${error.cause?.message}", error)
                onPlaybackError()
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    AndroidView(
        factory = { ctx ->
            val view = android.view.LayoutInflater.from(ctx)
                .inflate(com.zx.homecamera.R.layout.player_view, null, false) as PlayerView
            view.player = exoPlayer
            view.useController = true
            view.controllerAutoShow = true
            view
        },
        modifier = Modifier.fillMaxSize(),
    )
}
