package com.zx.homecamera.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264StreamConfigTest {
    @Test
    fun defaultEncoderResolutionUsesCameraFriendlyLandscapeSize() {
        assertEquals(640, H264StreamConfig.WIDTH)
        assertEquals(480, H264StreamConfig.HEIGHT)
        assertTrue(H264StreamConfig.WIDTH > H264StreamConfig.HEIGHT)
    }
}
