package com.zx.homecamera

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.RecordingPlaybackState
import com.zx.homecamera.core.app.RecordingPlaybackStatus
import com.zx.homecamera.network.RecordingApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

class RecordingPlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(RecordingPlaybackState())
    val state: StateFlow<RecordingPlaybackState> = _state.asStateFlow()

    private val recordingApi = RecordingApiClient()
    private val recordingExecutor = Executors.newSingleThreadExecutor()

    private var device: CollectorDevice? = null

    fun init(device: CollectorDevice, fileId: String) {
        this.device = device
        _state.value = _state.value.copy(fileId = fileId, status = RecordingPlaybackStatus.Loading)
        loadRecordingForPlayback(fileId)
    }

    private fun loadRecordingForPlayback(fileId: String) {
        val device = device ?: return
        val context = getApplication<Application>()
        recordingExecutor.execute {
            val result = runCatching {
                recordingApi.downloadToCache(
                    device = device,
                    fileId = fileId,
                    cacheDir = context.cacheDir,
                )
            }
            val file = result.getOrNull()
            val error = if (file == null) result.exceptionOrNull()?.message ?: "加载录像失败" else null
            viewModelScope.launch {
                _state.value = _state.value.copy(
                    fileId = fileId,
                    status = if (file != null) RecordingPlaybackStatus.Playing else RecordingPlaybackStatus.Error,
                    cachedFile = file,
                    errorMessage = error,
                )
            }
        }
    }

    fun savePlaybackToGallery() {
        val cachedFile = _state.value.cachedFile ?: return
        val fileId = _state.value.fileId
        val context = getApplication<Application>()
        _state.value = _state.value.copy(savingToGallery = true, savedToGallery = false)
        recordingExecutor.execute {
            val uri = runCatching {
                writeCachedFileToGallery(context, cachedFile, fileId)
            }.getOrNull()
            viewModelScope.launch {
                _state.value = _state.value.copy(
                    savedToGallery = uri != null,
                    savingToGallery = false,
                )
            }
        }
    }

    private fun writeCachedFileToGallery(
        context: Application,
        cachedFile: File,
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
                cachedFile.inputStream().use { input -> input.copyTo(output) }
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

    override fun onCleared() {
        recordingExecutor.shutdownNow()
        super.onCleared()
    }
}
