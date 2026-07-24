package com.zx.homecamera

import android.app.Application
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.DownloadStatus
import com.zx.homecamera.core.app.DownloadTaskState
import com.zx.homecamera.core.app.RecordingLibraryState
import com.zx.homecamera.core.app.RecordingLibraryStatus
import com.zx.homecamera.core.protocol.RecordingEntry
import com.zx.homecamera.network.RecordingApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RecordingLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(RecordingLibraryState())
    val state: StateFlow<RecordingLibraryState> = _state.asStateFlow()

    private val recordingApi = RecordingApiClient()
    // 列表/刷新专用执行器：与下载分离，避免下载阻塞界面刷新。
    private val recordingExecutor = Executors.newSingleThreadExecutor()
    // 下载专用执行器：单线程串行执行，保证一次只下载一个文件。
    private val downloadExecutor = Executors.newSingleThreadExecutor()

    private var device: CollectorDevice? = null

    // 下载队列：FIFO，受 [queueLock] 保护。同一时刻仅队首为下载中。
    private val queueLock = Any()
    private val downloadQueue = ArrayDeque<String>()
    private var currentDownloadFileId: String? = null
    private val downloadLoopActive = AtomicBoolean(false)

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

    /**
     * 下载录像文件到系统相册。同一时刻只下载一个；已在下载/排队/已下载的文件会被忽略。
     * 多次点击不同文件会依次入队，按顺序下载。
     */
    fun downloadRecordingToGallery(fileId: String) {
        synchronized(queueLock) {
            // 已下载或已在队列/下载中，忽略重复点击
            if (fileId in _state.value.downloadedFileIds) return
            if (fileId == currentDownloadFileId) return
            if (downloadQueue.contains(fileId)) return
            downloadQueue.add(fileId)
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                downloads = _state.value.downloads + (fileId to DownloadTaskState(DownloadStatus.Queued, 0f)),
            )
        }
        ensureDownloadLoop()
    }

    /**
     * 取消下载：[fileId] 为当前下载时中断传输，下一个排队任务自动开始；
     * 仍在队列中尚未开始时直接移除。对未在下载/排队的 [fileId] 安全。
     */
    fun cancelDownload(fileId: String) {
        val isCurrent = synchronized(queueLock) {
            if (fileId == currentDownloadFileId) {
                true
            } else {
                downloadQueue.remove(fileId)
                false
            }
        }
        if (isCurrent) {
            // 关闭传输 socket，中断阻塞中的下载；循环捕获后自动处理下一个
            recordingApi.cancel()
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                downloads = _state.value.downloads - fileId,
            )
        }
    }

    /**
     * 确保下载循环在 [downloadExecutor] 上运行。同一时刻只有一个循环在消费队列。
     */
    private fun ensureDownloadLoop() {
        if (!downloadLoopActive.compareAndSet(false, true)) return
        downloadExecutor.execute(::runDownloadLoop)
    }

    private fun runDownloadLoop() {
        try {
            while (true) {
                val fileId = synchronized(queueLock) {
                    currentDownloadFileId = downloadQueue.pollFirst()
                    currentDownloadFileId
                } ?: break // 队列空，退出循环

                processDownload(fileId)
                synchronized(queueLock) {
                    if (currentDownloadFileId == fileId) currentDownloadFileId = null
                }
            }
        } finally {
            downloadLoopActive.set(false)
            // 在退出循环与置位之间可能有新任务入队，需重新确认
            synchronized(queueLock) {
                if (downloadQueue.isNotEmpty() && currentDownloadFileId == null) {
                    ensureDownloadLoop()
                }
            }
        }
    }

    private fun processDownload(fileId: String) {
        val device = selectedDevice() ?: return
        val context = getApplication<Application>()
        val entry = _state.value.files.firstOrNull { it.fileId == fileId } ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                downloads = _state.value.downloads + (fileId to DownloadTaskState(DownloadStatus.Downloading, 0f)),
            )
        }
        val result = runCatching {
            recordingApi.downloadToGallery(
                context = context,
                device = device,
                entry = entry,
                onProgress = { transferred, total ->
                    val progress = if (total > 0) (transferred.toFloat() / total).coerceIn(0f, 1f) else 0f
                    viewModelScope.launch {
                        _state.value = _state.value.copy(
                            downloads = _state.value.downloads + (fileId to DownloadTaskState(DownloadStatus.Downloading, progress)),
                        )
                    }
                },
            )
        }
        val error = result.exceptionOrNull()?.message ?: if (result.getOrNull() == null) "下载失败" else null
        viewModelScope.launch {
            // 被取消时 downloads 已被移除；这里仅在仍存在时清理
            val currentDownloads = _state.value.downloads
            if (fileId !in currentDownloads) return@launch
            val newDownloaded = if (result.getOrNull() != null) {
                _state.value.downloadedFileIds + fileId
            } else {
                _state.value.downloadedFileIds
            }
            _state.value = _state.value.copy(
                downloads = currentDownloads - fileId,
                downloadedFileIds = newDownloaded,
                errorMessage = error,
            )
        }
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
        recordingApi.cancel()
        recordingExecutor.shutdownNow()
        downloadExecutor.shutdownNow()
        super.onCleared()
    }
}
