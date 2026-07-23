package com.zx.homecamera.ui.activity

import android.content.Intent
import android.net.Uri
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
import android.widget.VideoView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zx.homecamera.R
import com.zx.homecamera.RecordingPlaybackViewModel
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.RecordingPlaybackStatus
import com.zx.homecamera.ui.theme.HomeCameraTheme

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

            // 沉浸式
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())

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

                    // 保存到相册按钮
                    if (state.status == RecordingPlaybackStatus.Playing) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                                .height(42.dp)
                                .clip(RoundedCornerShape(21.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(enabled = !state.savingToGallery && !state.savedToGallery) {
                                    viewModel.savePlaybackToGallery()
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        const val EXTRA_FILE_ID = "file_id"
    }
}
