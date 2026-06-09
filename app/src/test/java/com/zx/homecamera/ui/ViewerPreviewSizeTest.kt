package com.zx.homecamera.ui

import com.zx.homecamera.network.ViewerConnection
import com.zx.homecamera.video.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerPreviewSizeTest {
    @Test
    fun usesConnectedStreamResolutionForViewerPreview() {
        val connection = ViewerConnection(
            collectorDeviceId = "collector-1",
            streamUdpPort = 62010,
            streamWidth = 640,
            streamHeight = 480,
            displayWidth = 480,
            displayHeight = 640,
            streamFps = 15,
        )

        assertEquals(VideoSize(480, 640), ViewerPreviewSize.displaySize(connection))
        assertEquals(VideoSize(640, 480), ViewerPreviewSize.surfaceSize(connection))
        assertEquals(90f, ViewerPreviewSize.rotationDegrees(connection))
    }

    @Test
    fun fallsBackToPortraitDefaultBeforeConnectionCompletes() {
        assertEquals(VideoSize(480, 640), ViewerPreviewSize.displaySize(null))
        assertEquals(VideoSize(640, 480), ViewerPreviewSize.surfaceSize(null))
        assertEquals(90f, ViewerPreviewSize.rotationDegrees(null))
    }
}
