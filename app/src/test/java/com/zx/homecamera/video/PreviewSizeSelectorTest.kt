package com.zx.homecamera.video

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSizeSelectorTest {
    @Test
    fun choosesSupportedSizeClosestToSurfaceAspectRatio() {
        val selected = PreviewSizeSelector.choose(
            supportedSizes = listOf(
                VideoSize(640, 480),
                VideoSize(1280, 720),
                VideoSize(1920, 1080),
            ),
            surfaceWidth = 1000,
            surfaceHeight = 750,
        )

        assertEquals(VideoSize(640, 480), selected)
    }

    @Test
    fun fallsBackToFirstSupportedSizeWhenSurfaceIsNotMeasured() {
        val selected = PreviewSizeSelector.choose(
            supportedSizes = listOf(VideoSize(800, 600)),
            surfaceWidth = 0,
            surfaceHeight = 0,
        )

        assertEquals(VideoSize(800, 600), selected)
    }

    @Test
    fun displaySizeSwapsPreviewDimensionsWhenSensorOutputIsRotated() {
        val displaySize = PreviewSizeSelector.displaySize(
            previewSize = VideoSize(640, 480),
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
        )

        assertEquals(VideoSize(480, 640), displaySize)
    }

    @Test
    fun displaySizeKeepsPreviewDimensionsWhenSensorOutputIsNotRotated() {
        val displaySize = PreviewSizeSelector.displaySize(
            previewSize = VideoSize(640, 480),
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 90,
        )

        assertEquals(VideoSize(640, 480), displaySize)
    }

    @Test
    fun displaySizeUsesProvidedDisplayRotationWithoutReadingAndroidContext() {
        val displaySize = PreviewSizeSelector.displaySize(
            previewSize = VideoSize(640, 480),
            sensorOrientationDegrees = 270,
            displayRotationDegrees = 270,
        )

        assertEquals(VideoSize(640, 480), displaySize)
    }

    @Test
    fun previewConfigIncludesSelectedBufferAndDisplaySize() {
        val config = PreviewSizeSelector.chooseConfig(
            supportedSizes = listOf(
                VideoSize(640, 480),
                VideoSize(1280, 720),
            ),
            surfaceWidth = 1000,
            surfaceHeight = 750,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
        )

        assertEquals(VideoSize(640, 480), config.bufferSize)
        assertEquals(VideoSize(480, 640), config.displaySize)
    }

    @Test
    fun fixedSizeChangeIsSkippedWhenSizeMatchesPreviousValue() {
        val previous = PreviewSize(
            bufferSize = VideoSize(640, 480),
            displaySize = VideoSize(480, 640),
        )
        val next = PreviewSize(
            bufferSize = VideoSize(640, 480),
            displaySize = VideoSize(480, 640),
        )

        assertEquals(false, next.needsFixedSizeUpdate(previous))
    }
}
