package com.zx.homecamera.network

import com.zx.homecamera.core.protocol.EncodedMediaFrame
import com.zx.homecamera.core.protocol.MediaCodecType
import com.zx.homecamera.core.protocol.MediaTrack
import com.zx.homecamera.core.protocol.MediaUdpPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeVideoFrameQueueTest {
    private var nowMillis = 1_000L
    private var keyFrameRequests = 0

    @Test
    fun fullQueueDropsOldestNonKeyVideoFrameAndKeepsNewestFrames() {
        val queue = newQueue(capacity = 3)

        queue.offer(frame(sequenceNumber = 1, flags = MediaUdpPacket.FLAG_KEY_FRAME))
        queue.offer(frame(sequenceNumber = 2))
        queue.offer(frame(sequenceNumber = 3))
        queue.offer(frame(sequenceNumber = 4))

        assertEquals(1, queue.poll()?.sequenceNumber)
        assertEquals(3, queue.poll()?.sequenceNumber)
        assertEquals(4, queue.poll()?.sequenceNumber)
        assertNull(queue.poll())
        assertEquals(0, keyFrameRequests)
    }

    @Test
    fun codecConfigRespectsCapacityAndDropsOldestNonKeyFrame() {
        val queue = newQueue(capacity = 2)

        queue.offer(frame(sequenceNumber = 1, flags = MediaUdpPacket.FLAG_KEY_FRAME))
        queue.offer(frame(sequenceNumber = 2))
        queue.offer(frame(sequenceNumber = 3, flags = MediaUdpPacket.FLAG_CODEC_CONFIG))

        assertEquals(1, queue.poll()?.sequenceNumber)
        assertEquals(3, queue.poll()?.sequenceNumber)
        assertNull(queue.poll())
        assertEquals(0, keyFrameRequests)
    }

    @Test
    fun staleDeltaFrameIsDroppedWithoutWaitingForNextKeyFrame() {
        val queue = newQueue(maxQueueDelayMillis = 400L)

        queue.offer(frame(sequenceNumber = 1, flags = MediaUdpPacket.FLAG_KEY_FRAME))
        assertEquals(1, queue.poll()?.sequenceNumber)

        queue.offer(frame(sequenceNumber = 2))
        nowMillis += 401L

        assertNull(queue.poll())
        assertEquals(0, keyFrameRequests)

        queue.offer(frame(sequenceNumber = 3))
        assertEquals(3, queue.poll()?.sequenceNumber)
    }

    @Test
    fun staleKeyFrameIsDroppedAndPlaybackWaitsForNextKeyFrame() {
        val queue = newQueue(maxQueueDelayMillis = 400L)

        queue.offer(frame(sequenceNumber = 1, flags = MediaUdpPacket.FLAG_KEY_FRAME))
        nowMillis += 401L

        assertNull(queue.poll())
        assertEquals(1, keyFrameRequests)

        queue.offer(frame(sequenceNumber = 2))
        assertNull(queue.poll())

        queue.offer(frame(sequenceNumber = 3, flags = MediaUdpPacket.FLAG_KEY_FRAME))
        assertEquals(3, queue.poll()?.sequenceNumber)
    }

    @Test
    fun codecConfigDoesNotResumeDeltaFramesUntilKeyFrameArrives() {
        val queue = newQueue()

        queue.offer(frame(sequenceNumber = 1, flags = MediaUdpPacket.FLAG_CODEC_CONFIG))
        queue.offer(frame(sequenceNumber = 2))

        assertEquals(1, queue.poll()?.sequenceNumber)
        assertNull(queue.poll())

        queue.offer(frame(sequenceNumber = 3, flags = MediaUdpPacket.FLAG_KEY_FRAME))
        assertEquals(3, queue.poll()?.sequenceNumber)
    }

    @Test
    fun freshKeyFrameDropsOlderQueuedVideoFramesAndKeepsLatestCodecConfig() {
        val queue = newQueue(capacity = 6)

        queue.offer(frame(sequenceNumber = 1, flags = MediaUdpPacket.FLAG_KEY_FRAME))
        queue.offer(frame(sequenceNumber = 2))
        queue.offer(frame(sequenceNumber = 3))
        queue.offer(frame(sequenceNumber = 4, flags = MediaUdpPacket.FLAG_CODEC_CONFIG))
        queue.offer(frame(sequenceNumber = 5, flags = MediaUdpPacket.FLAG_KEY_FRAME))

        assertEquals(4, queue.poll()?.sequenceNumber)
        assertEquals(5, queue.poll()?.sequenceNumber)
        assertNull(queue.poll())
    }

    @Test
    fun keyFrameKeepsImmediatelyPrecedingCodecConfigForDecoderStartup() {
        val queue = newQueue(capacity = 4)

        queue.offer(frame(sequenceNumber = 1, flags = MediaUdpPacket.FLAG_CODEC_CONFIG))
        queue.offer(frame(sequenceNumber = 2, flags = MediaUdpPacket.FLAG_KEY_FRAME))

        assertEquals(1, queue.poll()?.sequenceNumber)
        assertEquals(2, queue.poll()?.sequenceNumber)
        assertNull(queue.poll())
    }

    private fun newQueue(
        capacity: Int = 4,
        maxQueueDelayMillis: Long = 400L,
    ): RealtimeVideoFrameQueue =
        RealtimeVideoFrameQueue(
            capacity = capacity,
            maxQueueDelayMillis = maxQueueDelayMillis,
            keyFrameRequestIntervalMillis = 0L,
            clockMillis = { nowMillis },
            onKeyFrameNeeded = { keyFrameRequests++ },
        )

    private fun frame(sequenceNumber: Int, flags: Int = 0): EncodedMediaFrame =
        EncodedMediaFrame(
            track = MediaTrack.Video,
            codec = MediaCodecType.H264,
            sequenceNumber = sequenceNumber,
            timestampMicros = sequenceNumber * 1_000L,
            flags = flags,
            data = byteArrayOf(sequenceNumber.toByte()),
        )
}
