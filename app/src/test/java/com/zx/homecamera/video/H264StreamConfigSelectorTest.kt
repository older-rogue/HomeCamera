package com.zx.homecamera.video

import org.junit.Assert.assertEquals
import org.junit.Test

class H264StreamConfigSelectorTest {
    @Test
    fun choosesLargestSizeWithinLimitForRotatedPortraitSurface() {
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

        assertEquals(VideoSize(1920, 1080), config.bufferSize)
        assertEquals(VideoSize(1080, 1920), config.displaySize)
    }

    @Test
    fun choosesLargestSizeWithinLimitForFourThreeLandscapeSurface() {
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

        assertEquals(VideoSize(1280, 720), config.bufferSize)
        assertEquals(VideoSize(1280, 720), config.displaySize)
    }

    @Test
    fun derivesUdpFriendlyBitrateAndFpsFromSelectedResolution() {
        val config = H264StreamConfigSelector.choose(
            supportedSizes = listOf(VideoSize(1280, 720)),
            surfaceWidth = 1080,
            surfaceHeight = 1800,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
        )

        assertEquals(15, config.fps)
        assertEquals(H264StreamConfig.BITRATE, config.bitrate)
        assertEquals(H264StreamConfig.I_FRAME_INTERVAL_SECONDS, config.iFrameIntervalSeconds)
    }

    @Test
    fun ignoresPreviewContainerSizeWhenSelectingBuffer() {
        // 编码尺寸只取决于配置上限与相机支持的尺寸，与预览容器尺寸无关，
        // 避免启动时预览尺寸未知导致编码分辨率被固定为小尺寸。
        val largePreview = H264StreamConfigSelector.choose(
            supportedSizes = listOf(VideoSize(1920, 1080), VideoSize(640, 360)),
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
        )
        val zeroPreview = H264StreamConfigSelector.choose(
            supportedSizes = listOf(VideoSize(1920, 1080), VideoSize(640, 360)),
            surfaceWidth = 0,
            surfaceHeight = 0,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
        )

        assertEquals(VideoSize(1920, 1080), largePreview.bufferSize)
        assertEquals(VideoSize(1920, 1080), zeroPreview.bufferSize)
    }

    @Test
    fun fallsBackToAllCandidatesWhenNoneWithinLimit() {
        // 所有候选都超过上限时退回全部候选，仍选面积最大者，避免因过滤为空而无选择。
        val config = H264StreamConfigSelector.choose(
            supportedSizes = listOf(VideoSize(3840, 2160), VideoSize(2560, 1440)),
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            sensorOrientationDegrees = 0,
            displayRotationDegrees = 0,
        )

        assertEquals(VideoSize(3840, 2160), config.bufferSize)
    }
}
