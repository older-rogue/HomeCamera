package com.zx.homecamera.video

import org.junit.Assert.assertEquals
import org.junit.Test

class Mp4SegmentRecorderTest {
    @Test
    fun sampleTimestampIsZeroAtSegmentBase() {
        assertEquals(0L, Mp4SegmentRecorder.sampleTimestampMicros(1_000L, 1_000L))
    }

    @Test
    fun sampleTimestampIsPositiveAfterBase() {
        assertEquals(500L, Mp4SegmentRecorder.sampleTimestampMicros(1_500L, 1_000L))
    }

    @Test
    fun audioTimestampBeforeVideoBaseIsClampedToZero() {
        // 音频编码器可能在视频首个关键帧之前就产生帧，PTS 小于 segment 基准。
        // 负值会被 MediaMuxer 拒绝，需兜底为 0。
        assertEquals(0L, Mp4SegmentRecorder.sampleTimestampMicros(900L, 1_000L))
    }

    @Test
    fun audioAndVideoShareSameSegmentBase() {
        // 同一 segment 内，视频基准 1_000，音频帧 PTS 1_200，相对时间戳应一致减去同一基准。
        val base = 1_000L
        assertEquals(0L, Mp4SegmentRecorder.sampleTimestampMicros(1_000L, base))
        assertEquals(200L, Mp4SegmentRecorder.sampleTimestampMicros(1_200L, base))
    }
}
