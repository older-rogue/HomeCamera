package com.zx.homecamera.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerZoomControllerTest {

    @Test
    fun initialStateIsFitWithMinScale() {
        val controller = ViewerZoomController()
        assertEquals(ViewerZoomController.Mode.Fit, controller.mode)
        assertEquals(ViewerZoomController.MIN_SCALE, controller.scale, 0.001f)
    }

    @Test
    fun onScaleClampsToMaxScale() {
        val controller = ViewerZoomController()
        repeat(20) {
            controller.onScale(2.0f, 0f, 0f, baseWidth = 1000, baseHeight = 1000)
        }
        assertEquals(ViewerZoomController.MAX_SCALE, controller.scale, 0.001f)
    }

    @Test
    fun onScaleDoesNotGoBelowMinScale() {
        val controller = ViewerZoomController()
        controller.onScale(0.1f, 0f, 0f, baseWidth = 1000, baseHeight = 1000)
        assertEquals(ViewerZoomController.MIN_SCALE, controller.scale, 0.001f)
    }

    @Test
    fun dragDoesNotChangeScale() {
        val controller = ViewerZoomController()
        controller.onScale(2.0f, 0f, 0f, baseWidth = 1000, baseHeight = 1000)
        controller.onDrag(10_000f, 10_000f, baseWidth = 1000, baseHeight = 1000)
        // 拖动后平移被钳制，但 scale 不变。
        assertEquals(2.0f, controller.scale, 0.001f)
    }

    @Test
    fun onDoubleTapSwitchesFitToCropAndBack() {
        val controller = ViewerZoomController()
        controller.onDoubleTap(containerWidth = 1000, containerHeight = 1000, baseWidth = 1000, baseHeight = 1000)
        assertEquals(ViewerZoomController.Mode.Crop, controller.mode)

        controller.onDoubleTap(containerWidth = 1000, containerHeight = 1000, baseWidth = 1000, baseHeight = 1000)
        assertEquals(ViewerZoomController.Mode.Fit, controller.mode)
    }

    @Test
    fun onDoubleTapCropScaleFillsShortSide() {
        val controller = ViewerZoomController()
        // 容器 16:9 (1600x900)，fit 基准约 1200x900 (4:3 内容)
        // cropScale = max(16/9 ÷ 4/3, 4/3 ÷ 16/9) = max(1.333, 0.75) = 1.333
        controller.onDoubleTap(containerWidth = 1600, containerHeight = 900, baseWidth = 1200, baseHeight = 900)
        assertEquals(ViewerZoomController.Mode.Crop, controller.mode)
        assertTrue("cropScale 应大于 1", controller.scale > 1.0f)
        assertTrue("cropScale 应在合理范围", controller.scale < 2.0f)
    }

    @Test
    fun resetRestoresFitAndMinScale() {
        val controller = ViewerZoomController()
        controller.onScale(3.0f, 0f, 0f, baseWidth = 1000, baseHeight = 1000)
        controller.onDoubleTap(containerWidth = 1000, containerHeight = 1000, baseWidth = 1000, baseHeight = 1000)
        controller.reset()
        assertEquals(ViewerZoomController.Mode.Fit, controller.mode)
        assertEquals(ViewerZoomController.MIN_SCALE, controller.scale, 0.001f)
        assertEquals(0f, controller.translateX(), 0.001f)
        assertEquals(0f, controller.translateY(), 0.001f)
    }

    @Test
    fun translateIsZeroWhenNotZoomed() {
        val controller = ViewerZoomController()
        controller.onDrag(500f, 500f, baseWidth = 1000, baseHeight = 1000)
        // scale=1 时 maxOffset=0，平移被钳为 0。
        assertEquals(0f, controller.translateX(), 0.001f)
        assertEquals(0f, controller.translateY(), 0.001f)
    }
}
