package com.zx.homecamera

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.RecordingLibraryState
import com.zx.homecamera.core.app.RecordingLibraryStatus
import com.zx.homecamera.core.protocol.RecordingEntry
import com.zx.homecamera.network.RecordingApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class RecordingLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(RecordingLibraryState())
    val state: StateFlow<RecordingLibraryState> = _state.asStateFlow()

    private val recordingApi = RecordingApiClient()
    private val recordingExecutor = Executors.newSingleThreadExecutor()

    private var device: CollectorDevice? = null

    fun setDevice(device: CollectorDevice) {
        this.device = device
        loadRecordingDates()
    }

    private fun selectedDevice(): CollectorDevice? = device

    fun loadRecordingDates() {
        val device = selectedDevice() ?: return
        recordingExecutor.execute {
            val result = runCatching { recordingApi.listDates(device) }
            val dates = result.getOrDefault(emptyList())
            val error = result.exceptionOrNull()?.message
            viewModelScope.launch {
                val selectedDate = _state.value.selectedDate ?: dates.firstOrNull()
                _state.value = _state.value.copy(
                    status = if (error != null) RecordingLibraryStatus.Error else RecordingLibraryStatus.Loaded,
                    dates = dates,
                    selectedDate = selectedDate,
                    errorMessage = error,
                )
                _state.value.selectedDate?.let { date -> loadRecordingFiles(date) }
            }
        }
    }

    fun loadRecordingFiles(date: String) {
        val device = selectedDevice() ?: return
        recordingExecutor.execute {
            val result = runCatching { recordingApi.listFiles(device, date) }
            val files = result.getOrDefault(emptyList())
            val error = result.exceptionOrNull()?.message
            val downloaded = queryDownloadedFileIds(files)
            viewModelScope.launch {
                _state.value = _state.value.copy(
                    status = if (error != null) RecordingLibraryStatus.Error else RecordingLibraryStatus.Loaded,
                    selectedDate = date,
                    files = files,
                    downloadedFileIds = downloaded,
                    errorMessage = error,
                )
            }
        }
    }

    fun downloadRecordingToGallery(fileId: String) {
        val device = selectedDevice() ?: return
        val context = getApplication<Application>()
        val entry = _state.value.files.firstOrNull { it.fileId == fileId } ?: return
        _state.value = _state.value.copy(downloadingFileId = fileId, downloadProgress = 0f)
        recordingExecutor.execute {
            val result = runCatching {
                recordingApi.downloadToGallery(
                    context = context,
                    device = device,
                    entry = entry,
                    onProgress = { transferred, total ->
                        viewModelScope.launch {
                            _state.value = _state.value.copy(
                                downloadingFileId = fileId,
                                downloadProgress = if (total > 0) (transferred.toFloat() / total).coerceIn(0f, 1f) else 0f,
                            )
                        }
                    },
                )
            }
            val error = result.exceptionOrNull()?.message ?: if (result.getOrNull() == null) "下载失败" else null
            viewModelScope.launch {
                val downloaded = if (result.getOrNull() != null) {
                    _state.value.downloadedFileIds + fileId
                } else {
                    _state.value.downloadedFileIds
                }
                _state.value = _state.value.copy(
                    downloadingFileId = null,
                    downloadProgress = 0f,
                    downloadedFileIds = downloaded,
                    errorMessage = error,
                )
            }
        }
    }

    fun cancelDownload() {
        recordingApi.cancel()
        _state.value = _state.value.copy(
            downloadingFileId = null,
            downloadProgress = 0f,
        )
    }

    /**
     * 查询系统相册（Movies/HomeCamera）中已存在的录像文件，返回已下载的 fileId 集合。
     * displayName 规则与 [RecordingApiClient] 一一对应：HomeCamera_<fileId 中 / 替换为 _>。
     */
    private fun queryDownloadedFileIds(files: List<RecordingEntry>): Set<String> {
        if (files.isEmpty()) return emptySet()
        val context = getApplication<Application>()
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("${android.os.Environment.DIRECTORY_MOVIES}/HomeCamera/")
        val existing = mutableSetOf<String>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (nameIndex >= 0) {
                    existing.add(cursor.getString(nameIndex))
                }
            }
        }
        if (existing.isEmpty()) return emptySet()
        return files.mapNotNull { entry ->
            val displayName = "HomeCamera_${entry.fileId.replace('/', '_')}"
            if (displayName in existing) entry.fileId else null
        }.toSet()
    }

    override fun onCleared() {
        recordingExecutor.shutdownNow()
        super.onCleared()
    }
}
