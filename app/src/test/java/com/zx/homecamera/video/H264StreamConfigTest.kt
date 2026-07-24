package com.zx.homecamera.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264StreamConfigTest {
    @Test
    fun defaultEncoderResolutionUsesUdpFriendlyLandscapeSize() {
        assertEquals(1920, H264StreamConfig.WIDTH)
        assertEquals(1080, H264StreamConfig.HEIGHT)
        assertTrue(H264StreamConfig.WIDTH > H264StreamConfig.HEIGHT)
    }

    @Test
    fun defaultBitrateAndPacketPacingAvoidLargeUdpBursts() {
        assertTrue(H264StreamConfig.BITRATE <= 6_000_000)
        assertTrue(H264StreamConfig.PACKET_PACING_MICROS >= 10L)
    }

    @Test
    fun defaultKeyFrameIntervalAvoidsFrequentRealtimeStutter() {
        assertTrue(H264StreamConfig.I_FRAME_INTERVAL_SECONDS >= 1)
    }
}
