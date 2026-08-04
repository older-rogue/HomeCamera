package com.zx.homecamera.ui.activity

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zx.homecamera.RecordingLibraryViewModel
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.DownloadStatus
import com.zx.homecamera.core.app.RecordingLibraryStatus
import com.zx.homecamera.core.protocol.RecordingEntry
import com.zx.homecamera.ui.AppTopBar
import com.zx.homecamera.ui.PillButton
import com.zx.homecamera.ui.ScanRefreshButton
import com.zx.homecamera.ui.theme.HomeCameraTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingLibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val device = CollectorDevice.fromIntent(intent)
        setContent {
            val viewModel: RecordingLibraryViewModel = viewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(device) {
                device?.let { viewModel.setDevice(it) }
            }

            HomeCameraTheme {
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    AppTopBar(title = "历史录像", showBack = true, onBack = { finish() })

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .padding(top = 4.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 内容头部：当前选中日期 + 刷新
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = state.selectedDate ?: "历史录像",
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            ScanRefreshButton(onClick = { viewModel.refresh() })
                        }

                        if (state.dates.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                items(state.dates, key = { it }) { date ->
                                    val selected = date == state.selectedDate
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surface,
                                            )
                                            .border(
                                                1.dp,
                                                if (selected) Color.Transparent else MaterialTheme.colorScheme.outline,
                                                RoundedCornerShape(12.dp),
                                            )
                                            .clickable { viewModel.loadRecordingFiles(date) }
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
                        }

                        when {
                            state.status == RecordingLibraryStatus.Loading && state.files.isEmpty() -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("加载中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            state.status == RecordingLibraryStatus.Error && state.files.isEmpty() -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(state.errorMessage ?: "加载失败", color = MaterialTheme.colorScheme.error)
                                }
                            }
                            state.files.isEmpty() -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("当日无录像", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            else -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(state.files, key = { it.fileId }) { entry ->
                                        val task = state.downloads[entry.fileId]
                                        // 滚入视野时请求缩略图，滚出自动取消，避免预取不可见项。
                                        LaunchedEffect(entry.fileId) {
                                            viewModel.requestThumbnail(entry.fileId)
                                        }
                                        RecordingFileItem(
                                            entry = entry,
                                            thumbnail = state.thumbnails[entry.fileId],
                                            downloadStatus = task?.status,
                                            downloadProgress = task?.progress ?: 0f,
                                            isDownloaded = entry.fileId in state.downloadedFileIds,
                                            onPlay = { openPlayback(device!!, entry) },
                                            onDownload = { viewModel.downloadRecordingToGallery(entry.fileId) },
                                            onCancel = { viewModel.cancelDownload(entry.fileId) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openPlayback(device: CollectorDevice, entry: RecordingEntry) {
        val intent = Intent(this, RecordingPlaybackActivity::class.java)
        device.toIntent(intent)
        intent.putExtra(RecordingPlaybackActivity.EXTRA_FILE_ID, entry.fileId)
        startActivity(intent)
    }
}

@Composable
private fun RecordingFileItem(
    entry: RecordingEntry,
    thumbnail: Bitmap?,
    downloadStatus: DownloadStatus?,
    downloadProgress: Float,
    isDownloaded: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val isRecording = entry.recording
    val isCorrupted = entry.corrupted
    val isDownloading = downloadStatus == DownloadStatus.Downloading
    val isQueued = downloadStatus == DownloadStatus.Queued
    val containerColor = if (isRecording || isCorrupted) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .padding(10.dp),
    ) {
        // 正方形首帧预览：aspectRatio(1f) 保证宽高相等，填满网格列宽。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // 无缩略图占位：损坏/录制中/加载中均显示此占位，信息由下方 badge 补充。
                Text(
                    text = "▶",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 22.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatRecordingTime(entry.startMillis),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isRecording || isCorrupted) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = if (isRecording) "录制中" else "已损坏",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Text(
            text = when {
                isRecording -> "录制中，结束后可查看"
                isCorrupted -> "文件损坏，无法播放"
                isQueued -> "排队中 · ${formatFileSize(entry.sizeBytes)}"
                else -> formatFileSize(entry.sizeBytes)
            },
            modifier = Modifier.padding(top = 2.dp),
            color = if (isQueued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        if (isDownloading) {
            Spacer(Modifier.height(6.dp))
            // 外层 = 整条轨道：一个实心、贯通左右的圆角"槽"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))                                  // 裁出圆角槽，并裁切内部填充的左端
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)) // 轨道色：清晰的中性灰槽
            ) {
                // 内层 = 已下载部分：叠在槽上的实心蓝条，右端是干净竖直边，左端被外层裁成圆角
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = downloadProgress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PillButton(
                text = "播放",
                primary = true,
                enabled = !isRecording && !isCorrupted,
                onClick = onPlay,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                text = when {
                    isDownloading -> "停止"
                    isQueued -> "取消"
                    isDownloaded -> "已下载"
                    else -> "下载"
                },
                primary = false,
                enabled = !isRecording && !isCorrupted && !isDownloaded,
                onClick = if (isDownloading || isQueued) onCancel else onDownload,
                modifier = Modifier.weight(1f),
            )
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
