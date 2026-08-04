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

class RecordingPlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(RecordingPlaybackState())
    val state: StateFlow<RecordingPlaybackState> = _state.asStateFlow()

    fun init(device: CollectorDevice, fileId: String) {
        // 直接用采集端 HTTP URL 播放。ExoPlayer 边下边播 + 500ms 起播缓冲，LAN 下近乎即时出画面。
        // 磁盘缓存由 ExoPlayer 的 SimpleCache（VideoCacheManager）透明处理：二次观看/拖拽已缓存区域秒开，
        // 无需此前 downloadToCache 整文件下载方案。
        val url = "http://${device.hostAddress}:${CollectorForegroundService.HTTP_PORT}/$fileId"
        _state.value = _state.value.copy(
            fileId = fileId,
            playbackUrl = url,
            status = RecordingPlaybackStatus.Playing,
        )
    }

    /**
     * 播放出错时由 UI 回调，置 Error 状态并显示提示，避免黑屏无反馈。
     */
    fun onPlaybackError(message: String?) {
        _state.value = _state.value.copy(
            status = RecordingPlaybackStatus.Error,
            errorMessage = message ?: "播放失败",
        )
    }
}
