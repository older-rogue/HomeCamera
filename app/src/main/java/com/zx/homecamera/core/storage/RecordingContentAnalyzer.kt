package com.zx.homecamera.core.storage

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * 智能内容清理的判定阈值与抽帧参数。
 *
 * 判定逻辑（保守优先，宁可保留不误删）：
 * - [luminanceThreshold] 以下视为"黑"像素。
 * - 一段录像的抽帧全部平均亮度低于 [luminanceThreshold] -> 判为全黑无效。
 * - 一段录像相邻帧的平均绝对差(MAD)全部低于 [motionThreshold] -> 判为静止无效。
 * - 任一帧有亮度，或任一相邻帧对有运动，即判为有效（保留）。
 *
 * 抽帧参数 [framesPerSegment]/[sampleIntervalSeconds] 与采集端 2 分钟切片配合：
 * 120s / 5s = 24 帧。
 */
data class ContentAnalysisConfig(
    /** 平均亮度低于此值(0-255)的一帧视为黑帧。默认 12，约极暗，避免夜灯场景误判。 */
    val luminanceThreshold: Int = 12,
    /** 相邻帧平均绝对差(0-255)低于此值视为静止。默认 2.5。 */
    val motionThreshold: Double = 2.5,
    /** 每个切片抽取的帧数。默认 24（对应 2 分钟 / 5 秒）。 */
    val framesPerSegment: Int = 24,
    /** 抽帧间隔（秒）。默认 5。 */
    val sampleIntervalSeconds: Int = 5,
    /** 采样缩放宽度，下采样以降低逐像素计算开销。固定 32x18 保持 16:9 比例。 */
    val sampleWidth: Int = 32,
    val sampleHeight: Int = 18,
) {
    companion object {
        val Default = ContentAnalysisConfig()
    }
}

/**
 * 抽帧器抽象。默认实现 [MediaMetadataFrameSampler] 走 Android MediaMetadataRetriever；
 * 单测可用 fake 实现注入固定帧数据，从而把判定逻辑放在纯 JVM 环境验证。
 */
interface FrameSampler {
    /**
     * 从 [file] 中按 [config] 间隔抽取若干帧，每帧下采样为灰度像素数组（长度 = width*height）。
     * 返回 null 表示无法抽帧（文件损坏 / 解码失败），调用方据此判为有效（保守保留）。
     */
    fun sample(file: File, config: ContentAnalysisConfig): List<IntArray>?
}

/**
 * 纯逻辑内容分析器：根据 [FrameSampler] 抽取的灰度帧判定一个录像片段是否"有效"。
 *
 * 不直接依赖 Android API（依赖通过 [FrameSampler] 注入），因此判定核心可在 JVM 单测中验证，
 * 与 [RecordingRetentionPolicy] 保持同款可测设计。
 */
class RecordingContentAnalyzer(
    private val frameSampler: FrameSampler,
    private val config: ContentAnalysisConfig = ContentAnalysisConfig.Default,
) {
    /**
     * 判定单个文件是否有效（true=保留，false=无效可删）。
     * 抽帧失败、帧数不足、异常均返回 true（保守，宁可保留不误删）。
     */
    fun isEffective(file: File): ContentAnalysisResult {
        val frames = runCatching { frameSampler.sample(file, config) }.getOrNull()
            ?: return ContentAnalysisResult.effective(file, Reason.SAMPLE_FAILED)
        if (frames.isEmpty()) return ContentAnalysisResult.effective(file, Reason.SAMPLE_FAILED)

        val luminances = frames.map { averageLuminance(it) }
        // 全黑判定：所有帧平均亮度均低于阈值 -> 无效。
        val allDark = luminances.all { it < config.luminanceThreshold }
        if (allDark) return ContentAnalysisResult.ineffective(file, Reason.ALL_DARK)

        // 静止判定：相邻帧 MAD 全部低于阈值 -> 无效。
        // 单帧无法比较运动，只有一帧时无法判静止，保守视为有效。
        if (frames.size >= 2) {
            val allStatic = (1 until frames.size).all { i ->
                meanAbsoluteDifference(frames[i - 1], frames[i]) < config.motionThreshold
            }
            if (allStatic) return ContentAnalysisResult.ineffective(file, Reason.STATIC)
        }
        return ContentAnalysisResult.effective(file, Reason.HAS_CONTENT)
    }

    /**
     * 批量判定，返回判为无效（可删）的文件列表。
     */
    fun ineffectiveFiles(files: List<File>): List<File> =
        files.mapNotNull { file ->
            val result = isEffective(file)
            if (result.effective) null else result.file
        }

    private fun averageLuminance(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 0
        var sum = 0L
        for (px in pixels) sum += px
        return (sum / pixels.size).toInt()
    }

    /**
     * 两帧逐像素平均绝对差。长度不一致时按较短帧计算，避免因采样尺寸抖动导致 NPE。
     */
    private fun meanAbsoluteDifference(a: IntArray, b: IntArray): Double {
        val len = minOf(a.size, b.size)
        if (len == 0) return 0.0
        var sum = 0L
        for (i in 0 until len) sum += kotlin.math.abs(a[i] - b[i])
        return sum.toDouble() / len
    }
}

/** 单文件判定结果。 */
data class ContentAnalysisResult(
    val file: File,
    /** true=有效保留，false=无效可删。 */
    val effective: Boolean,
    val reason: Reason,
) {
    companion object {
        fun effective(file: File, reason: Reason) = ContentAnalysisResult(file, effective = true, reason = reason)
        fun ineffective(file: File, reason: Reason) = ContentAnalysisResult(file, effective = false, reason = reason)
    }
}

enum class Reason {
    /** 抽帧失败，保守保留。 */
    SAMPLE_FAILED,
    /** 全部抽帧为黑。 */
    ALL_DARK,
    /** 全程静止（相邻帧无运动）。 */
    STATIC,
    /** 存在有效内容，保留。 */
    HAS_CONTENT,
}

/**
 * 基于 [MediaMetadataRetriever] 的默认抽帧实现。
 *
 * 用 [MediaMetadataRetriever.METADATA_KEY_DURATION] 得到片段时长，按 [ContentAnalysisConfig.sampleIntervalSeconds]
 * 等间隔抽帧，每帧缩放到 [ContentAnalysisConfig.sampleWidth]x[sampleHeight] 并转灰度。
 * 一个文件用完即 [release] 关闭 retriever，与 [RecordingIntegrityChecker] 一致。
 */
class MediaMetadataFrameSampler : FrameSampler {
    override fun sample(file: File, config: ContentAnalysisConfig): List<IntArray>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: return null
            if (durationMs <= 0L) return null

            val intervalMs = config.sampleIntervalSeconds * 1000L
            // 在 [0, duration) 内按 interval 采样；首帧从 0 开始以覆盖片段开头。
            val sampleTimes = ArrayList<Long>()
            var t = 0L
            while (t < durationMs) {
                sampleTimes.add(t)
                t += intervalMs
            }
            // 至少保证一帧（极短片段），但 framesPerSegment 上限裁剪，避免异常长文件抽帧过多。
            if (sampleTimes.isEmpty()) sampleTimes.add(0L)
            val capped = sampleTimes.take(config.framesPerSegment)

            val frames = ArrayList<IntArray>(capped.size)
            for (timeMs in capped) {
                val bitmap = retriever.getFrameAtTime(
                    timeMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: continue
                val gray = downsampleToGray(bitmap, config.sampleWidth, config.sampleHeight)
                if (gray != null) frames.add(gray)
            }
            if (frames.isEmpty()) null else frames
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun downsampleToGray(bitmap: Bitmap, width: Int, height: Int): IntArray? {
        return runCatching {
            val scaled = if (bitmap.width == width && bitmap.height == height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            }
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            // 转 0-255 灰度：用标准亮度系数。
            for (i in pixels.indices) {
                val c = pixels[i]
                pixels[i] = luminance(Color.red(c), Color.green(c), Color.blue(c))
            }
            pixels
        }.getOrNull()
    }

    private fun luminance(r: Int, g: Int, b: Int): Int =
        (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
}
