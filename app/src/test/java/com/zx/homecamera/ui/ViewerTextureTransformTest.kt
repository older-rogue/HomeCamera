package com.zx.homecamera.ui

import com.zx.homecamera.video.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerTextureTransformTest {
    @Test
    fun rotatedLandscapeStreamKeepsTextureViewInLandscapeAspectRatio() {
        val size = ViewerTextureTransform.textureLayoutSize(
            containerWidth = 720,
            containerHeight = 1280,
            surfaceSize = VideoSize(1280, 720),
            rotationDegrees = 90f,
        )

        assertEquals(VideoSize(1280, 720), size)
    }

    @Test
    fun unrotatedLandscapeStreamKeepsTextureViewInLandscapeAspectRatio() {
        val size = ViewerTextureTransform.textureLayoutSize(
            containerWidth = 1280,
            containerHeight = 720,
            surfaceSize = VideoSize(1280, 720),
            rotationDegrees = 0f,
        )

        assertEquals(VideoSize(1280, 720), size)
    }
}
