package com.zx.homecamera

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.RecordingPlaybackState
import com.zx.homecamera.core.app.RecordingPlaybackStatus
import com.zx.homecamera.service.CollectorForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class RecordingPlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(RecordingPlaybackState())
    val state: StateFlow<RecordingPlaybackState> = _state.asStateFlow()

    private val recordingExecutor = Executors.newSingleThreadExecutor()

    private var device: CollectorDevice? = null

    fun init(device: CollectorDevice, fileId: String) {
        this.device = device
        // 在线播放：直接构造采集端 HTTP URL，VideoView 边下边播，无需先下载完整文件。
        val url = "http://${device.hostAddress}:${CollectorForegroundService.HTTP_PORT}/$fileId"
        _state.value = _state.value.copy(
            fileId = fileId,
            playbackUrl = url,
            status = RecordingPlaybackStatus.Playing,
        )
    }

    fun savePlaybackToGallery() {
        val device = device ?: return
        val fileId = _state.value.fileId
        val context = getApplication<Application>()
        _state.value = _state.value.copy(savingToGallery = true, savedToGallery = false)
        recordingExecutor.execute {
            val url = "http://${device.hostAddress}:${CollectorForegroundService.HTTP_PORT}/$fileId"
            val uri = runCatching {
                downloadToGallery(context, url, fileId)
            }.onFailure {
                Log.w(TAG, "save to gallery failed fileId=$fileId", it)
            }.getOrNull()
            viewModelScope.launch {
                _state.value = _state.value.copy(
                    savedToGallery = uri != null,
                    savingToGallery = false,
                )
            }
        }
    }

    /**
     * VideoView 播放出错时由 UI 回调，置 Error 状态并显示提示，避免黑屏无反馈。
     */
    fun onPlaybackError(message: String?) {
        _state.value = _state.value.copy(
            status = RecordingPlaybackStatus.Error,
            errorMessage = message ?: "播放失败",
        )
    }

    private fun downloadToGallery(
        context: Application,
        url: String,
        fileId: String,
    ): Uri? {
        val displayName = "HomeCamera_${fileId.replace('/', '_')}"
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/HomeCamera")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                downloadFile(url, output)
                output.flush()
            } ?: throw IllegalStateException("cannot open output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, finalValues, null, null)
            }
            uri
        }.onFailure {
            runCatching { resolver.delete(uri, null, null) }
        }.getOrNull()
    }

    private fun downloadFile(url: String, output: OutputStream) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            requestMethod = "GET"
        }
        connection.use { conn ->
            conn.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    override fun onCleared() {
        recordingExecutor.shutdownNow()
        super.onCleared()
    }

    companion object {
        private const val TAG = "RecordingPlaybackVM"
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
    }
}

private fun HttpURLConnection.use(block: (HttpURLConnection) -> Unit) {
    try {
        block(this)
    } finally {
        disconnect()
    }
}
