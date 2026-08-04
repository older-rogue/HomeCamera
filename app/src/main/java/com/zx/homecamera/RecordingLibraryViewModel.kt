package com.zx.homecamera

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.DownloadStatus
import com.zx.homecamera.core.app.DownloadTaskState
import com.zx.homecamera.core.app.RecordingLibraryState
import com.zx.homecamera.core.app.RecordingLibraryStatus
import com.zx.homecamera.core.protocol.RecordingEntry
import com.zx.homecamera.local.GalleryDownloadStore
import com.zx.homecamera.network.RecordingApiClient
import com.zx.homecamera.network.ThumbnailLoader
import android.graphics.Bitmap
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

    // 日期 -> 文件列表缓存。录像文件列表在采集端，每次切换日期都需网络请求 + 采集端校验，
    // 耗时较高；切回已加载过的日期时直接用缓存秒回。仅缓存文件列表本身（不含下载状态，
    // 下载状态每次从本地持久化重算）。刷新时清空。
    private val dateFilesCache = java.util.Collections.synchronizedMap(HashMap<String, List<RecordingEntry>>())

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
        // 重新加载日期列表属于全量刷新场景，清空文件列表缓存避免显示过期数据
        dateFilesCache.clear()
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
        val startMs = System.currentTimeMillis()
        recordingExecutor.execute {
            // 命中缓存则跳过网络请求，仅重算下载状态（下载状态可能因在别处下载而变化）
            val cached = dateFilesCache[date]
            val (files, error, fromCache) = if (cached != null) {
                Triple(cached, null, true)
            } else {
                val result = runCatching { recordingApi.listFiles(device, date) }
                val fs = result.getOrDefault(emptyList())
                if (result.isSuccess) dateFilesCache[date] = fs
                Triple(fs, result.exceptionOrNull()?.message, false)
            }
            val downloaded = queryDownloadedFileIds(files)
            val cost = System.currentTimeMillis() - startMs
            android.util.Log.d("RecordingLibrary", "loadRecordingFiles date=$date files=${files.size} cost=${cost}ms cache=$fromCache err=$error")
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
     * 强制刷新：清空日期文件列表缓存，重新请求当前选中日期（或全部日期）。
     */
    fun refresh() {
        dateFilesCache.clear()
        val selectedDate = _state.value.selectedDate
        if (selectedDate != null) loadRecordingFiles(selectedDate)
        else loadRecordingDates()
    }

    /**
     * 请求某录像的首帧缩略图。命中 [ThumbnailLoader] 内存缓存时立即更新 state；
     * 否则异步拉取（用 ThumbnailLoader 自带执行器，不占用 [recordingExecutor]，
     * 避免阻塞列表/刷新），回调后切回主线程写入 [RecordingLibraryState.thumbnails]。
     *
     * 失败（损坏/录制中/旧采集端返回 404）回调 null：这里**不写入** null，即该 fileId
     * 不出现在 thumbnails map 中，UI 据此显示占位；避免 null 覆盖后再来请求时被短路。
     * 单条记录在本次会话内只请求一次：inFlight 去重 + 命中即返回。
     */
    fun requestThumbnail(fileId: String) {
        val device = selectedDevice() ?: return
        // 已加载过（含 null 已失败的标记不在 map 中，会重新请求一次，但 inFlight 去重 + 缓存命中）。
        ThumbnailLoader.get(fileId)?.let { bmp ->
            updateThumbnail(fileId, bmp)
            return
        }
        ThumbnailLoader.load(device, fileId) { bmp ->
            if (bmp != null) {
                viewModelScope.launch { updateThumbnail(fileId, bmp) }
            }
        }
    }

    /**
     * 写入缩略图到 state。仅当 state 中尚无该 fileId 缩略图时写入，避免重复 copy。
     */
    private fun updateThumbnail(fileId: String, bmp: Bitmap) {
        val current = _state.value.thumbnails
        if (current[fileId] != null) return
        _state.value = _state.value.copy(thumbnails = current + (fileId to bmp))
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
                // 下载成功，持久化到本地，保证退出后重进仍显示「已下载」
                GalleryDownloadStore.markDownloaded(getApplication(), fileId)
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
     * 查询已下载到系统相册的录像 fileId 集合。
     * 以本地持久化记录为准（[GalleryDownloadStore]），不依赖 MediaStore 索引，
     * 保证下载后退出再进入状态不丢失。
     */
    private fun queryDownloadedFileIds(files: List<RecordingEntry>): Set<String> {
        if (files.isEmpty()) return emptySet()
        val context = getApplication<Application>()
        val downloaded = GalleryDownloadStore.getDownloadedFileIds(context)
        if (downloaded.isEmpty()) return emptySet()
        return files.mapNotNull { entry ->
            if (entry.fileId in downloaded) entry.fileId else null
        }.toSet()
    }

    override fun onCleared() {
        recordingApi.cancel()
        recordingExecutor.shutdownNow()
        downloadExecutor.shutdownNow()
        super.onCleared()
    }
}
