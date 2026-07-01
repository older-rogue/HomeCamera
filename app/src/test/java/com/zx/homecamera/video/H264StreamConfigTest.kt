package com.zx.homecamera.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264StreamConfigTest {
    @Test
    fun defaultEncoderResolutionUsesCameraFriendlyLandscapeSize() {
        assertEquals(1280, H264StreamConfig.WIDTH)
        assertEquals(720, H264StreamConfig.HEIGHT)
        assertTrue(H264StreamConfig.WIDTH > H264StreamConfig.HEIGHT)
    }

    @Test
    fun defaultKeyFrameIntervalAvoidsFrequentRealtimeStutter() {
        assertTrue(H264StreamConfig.I_FRAME_INTERVAL_SECONDS >= 1)
    }
}
