package com.zx.homecamera.video

import org.junit.Assert.assertEquals
import org.junit.Test

class H264StreamConfigSelectorTest {
    @Test
    fun choosesSupportedStreamSizeThatMatchesRotatedPortraitSurface() {
        val config = H264StreamConfigSelector.choose(
            supportedSizes = listOf(
                VideoSize(1920, 1080),
                VideoSize(1280, 720),
                VideoSize(640, 480),
            ),
            surfaceWidth = 1080,
            surfaceHeight = 1800,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
        )

        assertEquals(VideoSize(1280, 720), config.bufferSize)
        assertEquals(VideoSize(720, 1280), config.displaySize)
    }

    @Test
    fun choosesFourThreeSizeForFourThreeLandscapeSurface() {
        val config = H264StreamConfigSelector.choose(
            supportedSizes = listOf(
                VideoSize(1280, 720),
                VideoSize(640, 480),
            ),
            surfaceWidth = 1000,
            surfaceHeight = 750,
            sensorOrientationDegrees = 0,
            displayRotationDegrees = 0,
        )

        assertEquals(VideoSize(640, 480), config.bufferSize)
        assertEquals(VideoSize(640, 480), config.displaySize)
    }

    @Test
    fun derivesBitrateAndFpsFromSelectedResolution() {
        val config = H264StreamConfigSelector.choose(
            supportedSizes = listOf(VideoSize(1280, 720)),
            surfaceWidth = 1080,
            surfaceHeight = 1800,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
        )

        assertEquals(15, config.fps)
        assertEquals(2_400_000, config.bitrate)
        assertEquals(H264StreamConfig.I_FRAME_INTERVAL_SECONDS, config.iFrameIntervalSeconds)
    }
}
