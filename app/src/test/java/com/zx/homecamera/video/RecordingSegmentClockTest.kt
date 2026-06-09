package com.zx.homecamera.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSegmentClockTest {
    @Test
    fun startsFirstSegmentImmediately() {
        val clock = RecordingSegmentClock(segmentDurationMicros = 10_000)

        assertTrue(clock.shouldStartSegment(timestampMicros = 5_000))
    }

    @Test
    fun rotatesOnlyAfterDurationAndOnKeyFrame() {
        val clock = RecordingSegmentClock(segmentDurationMicros = 10_000)
        clock.onSegmentStarted(timestampMicros = 1_000)

        assertFalse(clock.shouldRotate(timestampMicros = 10_999, isKeyFrame = true))
        assertFalse(clock.shouldRotate(timestampMicros = 11_000, isKeyFrame = false))
        assertTrue(clock.shouldRotate(timestampMicros = 11_000, isKeyFrame = true))
    }
}
