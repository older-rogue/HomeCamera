package com.zx.homecamera.core.storage

import android.media.MediaMetadataRetriever
import java.io.File

/**
 * 检测录像 mp4 文件是否可播放（完整性校验）。
 *
 * 采集端进程被强杀时，[android.media.MediaMuxer] 的 stop() 不保证执行，导致最后一段录像
 * 缺少 moov box（索引元数据）无法播放。此类用 [MediaMetadataRetriever] 尝试读取时长，
 * 解析失败或时长非正即视为损坏。
 *
 * 封装为独立类，避免向纯逻辑的 [RecordingLibrary] 注入 Android 系统 API，保持其可测试性。
 */
class RecordingIntegrityChecker {

    /**
     * 返回 true 表示文件可正常播放，false 表示损坏（缺 moov / 截断 / 解析失败）。
     * 正在录制的文件（未 stop）也会返回 false，由调用方结合 recording 标记区分。
     */
    fun isPlayable(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs?.toLongOrNull()?.let { it > 0 } ?: false
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }
}
