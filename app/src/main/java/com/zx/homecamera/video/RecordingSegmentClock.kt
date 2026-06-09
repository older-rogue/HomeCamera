package com.zx.homecamera.video

class RecordingSegmentClock(
    private val segmentDurationMicros: Long,
) {
    private var segmentStartMicros: Long? = null

    fun shouldStartSegment(timestampMicros: Long): Boolean =
        segmentStartMicros == null

    fun shouldRotate(timestampMicros: Long, isKeyFrame: Boolean): Boolean {
        val startMicros = segmentStartMicros ?: return false
        return isKeyFrame && timestampMicros - startMicros >= segmentDurationMicros
    }

    fun onSegmentStarted(timestampMicros: Long) {
        segmentStartMicros = timestampMicros
    }

    fun reset() {
        segmentStartMicros = null
    }
}
