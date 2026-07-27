package com.zx.homecamera

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.RecordingPlaybackState
import com.zx.homecamera.core.app.RecordingPlaybackStatus
import com.zx.homecamera.service.CollectorForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * VideoView 播放出错时由 UI 回调，置 Error 状态并显示提示，避免黑屏无反馈。
     */
    fun onPlaybackError(message: String?) {
        _state.value = _state.value.copy(
            status = RecordingPlaybackStatus.Error,
            errorMessage = message ?: "播放失败",
        )
    }

    override fun onCleared() {
        recordingExecutor.shutdownNow()
        super.onCleared()
    }
}
