package com.zx.homecamera.video

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFpsRangeSelectorTest {
    @Test
    fun prefersFixedRangeMatchingTargetFps() {
        val range = CameraFpsRangeSelector.choose(
            ranges = listOf(
                CameraFpsRange(15, 30),
                CameraFpsRange(20, 20),
                CameraFpsRange(30, 30),
            ),
            targetFps = 20,
        )

        assertEquals(CameraFpsRange(20, 20), range)
    }

    @Test
    fun choosesTightestRangeContainingTargetFps() {
        val range = CameraFpsRangeSelector.choose(
            ranges = listOf(
                CameraFpsRange(10, 30),
                CameraFpsRange(15, 20),
                CameraFpsRange(15, 30),
            ),
            targetFps = 20,
        )

        assertEquals(CameraFpsRange(15, 20), range)
    }

    @Test
    fun fallsBackToClosestUpperBoundWhenTargetIsUnavailable() {
        val range = CameraFpsRangeSelector.choose(
            ranges = listOf(
                CameraFpsRange(24, 24),
                CameraFpsRange(30, 30),
            ),
            targetFps = 20,
        )

        assertEquals(CameraFpsRange(24, 24), range)
    }

    @Test
    fun prefersNearbyFixedRangeOverBroadVariableRange() {
        val range = CameraFpsRangeSelector.choose(
            ranges = listOf(
                CameraFpsRange(15, 30),
                CameraFpsRange(24, 24),
            ),
            targetFps = 20,
        )

        assertEquals(CameraFpsRange(24, 24), range)
    }
}
