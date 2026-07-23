package com.zx.homecamera.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zx.homecamera.R
import com.zx.homecamera.RecordingLibraryViewModel
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.RecordingLibraryStatus
import com.zx.homecamera.core.protocol.RecordingEntry
import com.zx.homecamera.ui.PillButton
import com.zx.homecamera.ui.ScanRefreshButton
import com.zx.homecamera.ui.theme.HomeCameraTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
                    // 顶栏（返回 + 刷新）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(MaterialTheme.colorScheme.surface),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { finish() },
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
                        ScanRefreshButton(onClick = {
                            val selectedDate = state.selectedDate
                            if (selectedDate != null) viewModel.loadRecordingFiles(selectedDate)
                            else viewModel.loadRecordingDates()
                        })
                        Spacer(Modifier.width(12.dp))
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .padding(top = 4.dp, bottom = 16.dp),
                    ) {
                        if (state.dates.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
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
                            Spacer(Modifier.height(12.dp))
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
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(state.files, key = { it.fileId }) { entry ->
                                        RecordingFileItem(
                                            entry = entry,
                                            isDownloading = state.downloadingFileId == entry.fileId,
                                            downloadProgress = if (state.downloadingFileId == entry.fileId) state.downloadProgress else 0f,
                                            onPlay = { openPlayback(device!!, entry) },
                                            onDownload = { viewModel.downloadRecordingToGallery(entry.fileId) },
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
            modifier = Modifier.fillMaxWidth().padding(14.dp),
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
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
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

private fun formatRecordingTime(startMillis: Long): String {
    if (startMillis <= 0L) return "录像"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(startMillis))
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    if (bytes < 1024 * 1024) return "${bytes / 1024}KB"
    return "%.1fMB".format(bytes / (1024.0 * 1024.0))
}
