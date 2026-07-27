package com.zx.homecamera.core.storage

import android.media.MediaMetadataRetriever
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 检测录像 mp4 文件是否可播放（完整性校验）。
 *
 * 采集端进程被强杀时，[android.media.MediaMuxer] 的 stop() 不保证执行，导致最后一段录像
 * 缺少 moov box（索引元数据）无法播放。此类用 [MediaMetadataRetriever] 尝试读取时长，
 * 解析失败或时长非正即视为损坏。
 *
 * 封装为独立类，避免向纯逻辑的 [RecordingLibrary] 注入 Android 系统 API，保持其可测试性。
 *
 * ## 结果缓存
 * [MediaMetadataRetriever] 解析 mp4 开销较大（打开文件、定位 moov box），逐文件串行校验
 * 会导致录像列表响应明显变慢。录像文件一旦写完就不再变化，因此以 (fileId, sizeBytes) 为键
 * 缓存校验结果，仅当文件大小变化（说明被覆盖/续写）时重新校验。正在录制的文件不进入缓存。
 */
class RecordingIntegrityChecker {
    /**
     * 已校验文件的缓存。key = fileId，value = 校验时的文件大小与结果。
     * 使用 [ConcurrentHashMap] 支持多客户端请求并发校验。
     */
    private val cache = ConcurrentHashMap<String, CachedResult>()

    /**
     * 返回 true 表示文件可正常播放，false 表示损坏（缺 moov / 截断 / 解析失败）。
     * 正在录制的文件（未 stop）也会返回 false，由调用方结合 recording 标记区分。
     */
    fun isPlayable(file: File): Boolean = isPlayable(fileIdFor(file), file)

    /**
     * 带显式 [fileId] 的校验入口，避免依赖文件路径推导 fileId。
     * 命中缓存时直接返回，未命中或大小变化时重新校验并更新缓存。
     */
    fun isPlayable(fileId: String, file: File): Boolean {
        val size = file.length()
        val cached = cache[fileId]
        if (cached != null && cached.sizeBytes == size) {
            return cached.playable
        }
        val playable = checkPlayable(file)
        cache[fileId] = CachedResult(sizeBytes = size, playable = playable)
        return playable
    }

    private fun checkPlayable(file: File): Boolean {
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

    /**
     * 清除指定 [fileId] 的缓存。文件被删除或重写后可调用，使下次校验重新执行。
     */
    fun invalidate(fileId: String) {
        cache.remove(fileId)
    }

    /**
     * 清除全部缓存。
     */
    fun clear() {
        cache.clear()
    }

    private fun fileIdFor(file: File): String = file.name

    private data class CachedResult(
        val sizeBytes: Long,
        val playable: Boolean,
    )
}
