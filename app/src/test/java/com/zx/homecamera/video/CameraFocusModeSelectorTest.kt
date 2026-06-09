package com.zx.homecamera.video

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFocusModeSelectorTest {
    @Test
    fun choosesContinuousVideoAutofocusWhenAvailable() {
        val selected = CameraFocusModeSelector.choose(
            intArrayOf(
                CaptureRequest.CONTROL_AF_MODE_AUTO,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            ),
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, selected)
    }

    @Test
    fun fallsBackToAutoAutofocusWhenContinuousVideoIsUnavailable() {
        val selected = CameraFocusModeSelector.choose(
            intArrayOf(CaptureRequest.CONTROL_AF_MODE_AUTO),
        )

        assertEquals(CaptureRequest.CONTROL_AF_MODE_AUTO, selected)
    }

    @Test
    fun returnsNullWhenNoSupportedAutofocusModeExists() {
        val selected = CameraFocusModeSelector.choose(
            intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF),
        )

        assertNull(selected)
    }
}
