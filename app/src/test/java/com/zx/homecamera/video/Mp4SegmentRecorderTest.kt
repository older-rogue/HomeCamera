package com.zx.homecamera.video

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4SegmentRecorderTest {

    private fun recorder(): Mp4SegmentRecorder =
        Mp4SegmentRecorder(root = File.createTempFile("recorder-test", "").parentFile!!)

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

    @Test
    fun firstSegmentStartsImmediatelyWhenAudioNotExpected() {
        val recorder = recorder()
        recorder.setAudioExpected(false)
        assertTrue(recorder.isAudioReadyForFirstSegment(1_000L))
    }

    @Test
    fun firstSegmentWaitsForAudioFormatWhenAudioExpected() {
        val recorder = recorder()
        recorder.setAudioExpected(true)
        // 音频格式未就绪：首个关键帧先等待，避免首段被截成微小文件。
        assertFalse(recorder.isAudioReadyForFirstSegment(1_000L))
        assertFalse(recorder.isAudioReadyForFirstSegment(2_000_000L))
        // 超过等待超时（5s）仍无音频格式：按纯视频兜底，不能无限阻塞录像。
        assertTrue(recorder.isAudioReadyForFirstSegment(6_000_000L))
    }
}
