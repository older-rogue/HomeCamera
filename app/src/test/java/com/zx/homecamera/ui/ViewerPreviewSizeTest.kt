package com.zx.homecamera.ui

import com.zx.homecamera.network.ViewerConnection
import com.zx.homecamera.video.H264StreamConfig
import com.zx.homecamera.video.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerPreviewSizeTest {
    @Test
    fun usesConnectedStreamResolutionForViewerPreview() {
        val connection = ViewerConnection(
            collectorDeviceId = "collector-1",
            collectorHostAddress = "127.0.0.1",
            collectorTcpPort = 62001,
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
        assertEquals(
            VideoSize(H264StreamConfig.HEIGHT, H264StreamConfig.WIDTH),
            ViewerPreviewSize.displaySize(null),
        )
        assertEquals(
            VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT),
            ViewerPreviewSize.surfaceSize(null),
        )
        assertEquals(90f, ViewerPreviewSize.rotationDegrees(null))
    }

    @Test
    fun noRotationWhenCollectorDisplaySizeMatchesStreamBuffer() {
        // Portrait device with back camera (sensor 90°, display rotation 270°) yields a
        // relative rotation of 180°, so the collector keeps bufferSize == displaySize
        // (no aspect-ratio swap). The viewer must NOT apply a quarter-turn rotation.
        val connection = ViewerConnection(
            collectorDeviceId = "collector-1",
            collectorHostAddress = "127.0.0.1",
            collectorTcpPort = 62001,
            streamUdpPort = 62010,
            streamWidth = 1280,
            streamHeight = 720,
            displayWidth = 1280,
            displayHeight = 720,
            streamFps = 15,
        )

        assertEquals(VideoSize(1280, 720), ViewerPreviewSize.displaySize(connection))
        assertEquals(VideoSize(1280, 720), ViewerPreviewSize.surfaceSize(connection))
        assertEquals(0f, ViewerPreviewSize.rotationDegrees(connection))
    }
}
