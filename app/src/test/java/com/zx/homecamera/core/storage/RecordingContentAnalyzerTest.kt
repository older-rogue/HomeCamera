package com.zx.homecamera.core.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingContentAnalyzerTest {
    private val config = ContentAnalysisConfig(
        luminanceThreshold = 12,
        motionThreshold = 2.5,
        framesPerSegment = 24,
        sampleIntervalSeconds = 5,
        sampleWidth = 4,
        sampleHeight = 4,
    )

    /**
     * 构造一帧灰度像素。
     * - [value] 均匀填充基准亮度。
     * - [noiseSeed] 为 0 时整帧均匀（用于全黑/纯静止）；
     *   非 0 时每个像素叠加 (index % 3) * [noiseSeed]，模拟空间噪声。
     *   不同帧用不同 [noiseSeed] 即可产生帧间运动。
     */
    private fun frame(value: Int, noiseSeed: Int = 0): IntArray =
        IntArray(config.sampleWidth * config.sampleHeight) { value + (it % 3) * noiseSeed }

    /** fake 抽帧器，按预设帧序列返回。 */
    private class FakeFrameSampler(val frames: List<IntArray>?) : FrameSampler {
        override fun sample(file: File, config: ContentAnalysisConfig): List<IntArray>? = frames
    }

    private fun analyzer(frames: List<IntArray>?) =
        RecordingContentAnalyzer(FakeFrameSampler(frames), config)

    @Test
    fun allDarkFramesAreIneffective() {
        // 24 帧全部亮度 5（< 阈值 12）-> 全黑无效
        val dark = List(24) { frame(5) }
        val result = analyzer(dark).isEffective(File("dark.mp4"))
        assertFalse(result.effective)
        assertEquals(Reason.ALL_DARK, result.reason)
    }

    @Test
    fun staticBrightFramesAreIneffective() {
        // 24 帧亮度 100（亮）但彼此完全相同 -> 静止无效
        val bright = List(24) { frame(100) }
        val result = analyzer(bright).isEffective(File("static.mp4"))
        assertFalse(result.effective)
        assertEquals(Reason.STATIC, result.reason)
    }

    @Test
    fun framesWithMotionAreEffective() {
        // 24 帧，其中一帧有显著变化（亮度 200，且与相邻帧差很大）-> 有运动，保留
        val frames = MutableList(24) { frame(100) }
        frames[10] = frame(200)
        val result = analyzer(frames).isEffective(File("motion.mp4"))
        assertTrue(result.effective)
        assertEquals(Reason.HAS_CONTENT, result.reason)
    }

    @Test
    fun singleBrightFrameWithNoComparisonIsEffective() {
        // 只有一帧（亮），无法判静止，保守视为有效
        val result = analyzer(listOf(frame(100))).isEffective(File("single.mp4"))
        assertTrue(result.effective)
        assertEquals(Reason.HAS_CONTENT, result.reason)
    }

    @Test
    fun sampleFailureIsEffective() {
        // 抽帧返回 null -> 保守保留
        val result = analyzer(null).isEffective(File("broken.mp4"))
        assertTrue(result.effective)
        assertEquals(Reason.SAMPLE_FAILED, result.reason)
    }

    @Test
    fun emptyFramesAreEffective() {
        // 抽到 0 帧 -> 保守保留
        val result = analyzer(emptyList()).isEffective(File("empty.mp4"))
        assertTrue(result.effective)
        assertEquals(Reason.SAMPLE_FAILED, result.reason)
    }

    @Test
    fun samplerThrowingIsEffective() {
        // 抽帧抛异常 -> 保守保留
        val throwing = object : FrameSampler {
            override fun sample(file: File, config: ContentAnalysisConfig): List<IntArray>? = error("boom")
        }
        val result = RecordingContentAnalyzer(throwing, config).isEffective(File("throw.mp4"))
        assertTrue(result.effective)
        assertEquals(Reason.SAMPLE_FAILED, result.reason)
    }

    @Test
    fun ineffectiveFilesReturnsOnlyInvalidFiles() {
        val darkFile = File("dark.mp4")
        val motionFile = File("motion.mp4")
        val staticFile = File("static.mp4")
        val analyzer = RecordingContentAnalyzer(
            object : FrameSampler {
                override fun sample(file: File, config: ContentAnalysisConfig): List<IntArray>? =
                    when (file) {
                        darkFile -> List(24) { frame(5) }
                        staticFile -> List(24) { frame(100) }
                        motionFile -> MutableList(24) { frame(100) }.also { it[5] = frame(180) }
                        else -> null
                    }
            },
            config,
        )
        val ineffective = analyzer.ineffectiveFiles(listOf(darkFile, motionFile, staticFile))
        assertEquals(listOf(darkFile, staticFile), ineffective)
    }

    @Test
    fun slightFrameDifferenceBelowMotionThresholdIsStatic() {
        // 亮度 100，相邻帧整体偏移 1（MAD=1 < 阈值 2.5）-> 静止无效
        val frames = List(24) { i -> frame(100 + i % 2) }
        val result = analyzer(frames).isEffective(File("slight-noise.mp4"))
        assertFalse(result.effective)
        assertEquals(Reason.STATIC, result.reason)
    }

    @Test
    fun frameDifferenceAboveMotionThresholdIsEffective() {
        // 亮度 100，相邻帧整体偏移 5（MAD=5 > 阈值 2.5）-> 有运动，保留
        val frames = List(24) { i -> frame(100 + (i % 2) * 5) }
        val result = analyzer(frames).isEffective(File("noisy.mp4"))
        assertTrue(result.effective)
    }
}
