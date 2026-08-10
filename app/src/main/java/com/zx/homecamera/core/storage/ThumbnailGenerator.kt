package com.zx.homecamera.core.storage

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 采集端首帧缩略图生成器。
 *
 * 用 [MediaMetadataRetriever] 抽取 mp4 首帧（关键帧对齐），按 muxer 写入的旋转标记校正朝向后
 * 压成小 JPEG。结构与 [RecordingIntegrityChecker] 对齐：以 (fileId, sizeBytes) 为键做内存缓存，
 * 文件大小变化（被覆盖/续写）时重生成；录像文件一旦写完即不可变，缓存长期有效。
 *
 * 缓存的是已压缩的 JPEG 字节而非 Bitmap：采集端只负责生成字节流，由 HTTP 服务器直接写响应；
 * 查看端解码 Bitmap 并自行 LruCache。这样采集端内存占用按 JPEG 大小计（数 KB），而非按
 * Bitmap 像素数（数百 KB），且不耦合查看端的 Bitmap 复用。
 *
 * 录制中（未 stop、缺 moov）或损坏的文件无法被 [MediaMetadataRetriever] 解析，返回 null。
 */
class ThumbnailGenerator {
    /**
     * 已生成缩略图的缓存。key = fileId，value = 生成时的文件大小与 JPEG 字节。
     * 用 [ConcurrentHashMap] 支持多个 HTTP 连接并发请求不同文件。
     */
    private val cache = ConcurrentHashMap<String, CachedThumb>()

    /**
     * 每个 fileId 生成时的锁，避免并发请求同一文件时重复抽帧。
     */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * 返回 [fileId] 对应录像的首帧 JPEG 字节；无法生成（损坏/录制中）返回 null。
     * 命中缓存且文件大小未变时直接返回；否则重新生成并更新缓存。
     */
    fun thumbnailBytes(fileId: String, file: File): ByteArray? {
        val size = file.length()
        val cached = cache[fileId]
        if (cached != null && cached.sizeBytes == size) {
            return cached.jpegBytes
        }
        // per-fileId 锁去重：并发请求同一文件时只生成一次，其余等待结果后读缓存。
        val lock = locks.computeIfAbsent(fileId) { Any() }
        val jpeg = synchronized(lock) {
            // 拿到锁后再次确认缓存，可能在等待期间已被其他线程生成。
            val again = cache[fileId]
            if (again != null && again.sizeBytes == size) {
                return@synchronized again.jpegBytes
            }
            generate(file)
        } ?: return null
        cache[fileId] = CachedThumb(sizeBytes = size, jpegBytes = jpeg)
        return jpeg
    }

    private fun generate(file: File): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            // 首帧：与关键帧对齐（OPTION_CLOSEST_SYNC），最快且即录像起始画面。
            val rawFrame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            android.util.Log.i(
                TAG,
                "generate ${file.name}: rotation=$rotation rawFrame=${rawFrame.width}x${rawFrame.height}",
            )
            val rotated = applyRotation(rotation, rawFrame)
            // 居中裁剪正方形，不缩放不压缩，保留原始清晰度。UI 以正方形显示，只需保留中心区域。
            val square = centerCropSquare(rotated)
            val jpeg = ByteArrayOutputStream().use { out ->
                square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            // 回收中间 Bitmap（square 可能即 rotated，避免重复回收）。
            if (square !== rotated) rotated.recycle()
            square.recycle()
            jpeg
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 按 muxer 写入的旋转标记 [rotation] 旋转帧，使缩略图朝向与播放器一致。
     *
     * 注意：[MediaMetadataRetriever.getFrameAtTime] 的行为因设备/版本而异--部分实现已应用
     * 容器旋转标记返回正向帧，部分返回未旋转的原始帧。直接按 [rotation] 再旋转一次会导致
     * 已旋转的帧被二次旋转（画面变横）。
     *
     * 这里用帧的宽高比推断：90°/270° 旋转会交换宽高。若原始帧的宽高比已经是"旋转后"的
     * （即与 rotation 期望的最终朝向一致），说明 getFrameAtTime 已旋转过，不再处理；
     * 否则视为未旋转的原始帧，手动应用 [rotation]。
     */
    private fun applyRotation(rotation: Int, frame: Bitmap): Bitmap {
        val normalized = ((rotation % 360) + 360) % 360
        if (normalized == 0 || normalized == 180) return frame
        // 90/270 旋转会交换宽高。若帧已经是"竖"（高>宽），而 rotation 又是 90/270，
        // 说明 getFrameAtTime 已应用旋转，无需再转；若帧是"横"（宽>高），则是原始帧，需旋转。
        val alreadyRotated = frame.height > frame.width
        if (alreadyRotated) return frame
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
    }

    /**
     * 居中裁剪正方形，保留原始分辨率不缩放。
     * UI 以正方形显示首帧预览，只保留画面中心区域，丢弃上下/左右边缘。
     */
    private fun centerCropSquare(frame: Bitmap): Bitmap {
        val side = minOf(frame.width, frame.height)
        val xOffset = (frame.width - side) / 2
        val yOffset = (frame.height - side) / 2
        return Bitmap.createBitmap(frame, xOffset, yOffset, side, side)
    }

    /**
     * 清除全部缓存。采集端停止时可选调用；不调用也无害（缓存条目按文件数有限）。
     */
    fun clear() {
        cache.clear()
        locks.clear()
    }

    /**
     * 缓存条目。用普通 class 而非 data class，避免 [ByteArray] 的内容式 equals 警告——
     * 这里只按 [sizeBytes] 判定是否失效，从不比较 [jpegBytes] 内容。
     */
    private class CachedThumb(
        val sizeBytes: Long,
        val jpegBytes: ByteArray,
    )

    companion object {
        private const val TAG = "ThumbnailGenerator"
        private const val JPEG_QUALITY = 80
    }
}
