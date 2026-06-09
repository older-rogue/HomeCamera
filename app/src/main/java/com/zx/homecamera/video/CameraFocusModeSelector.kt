package com.zx.homecamera.video

import android.hardware.camera2.CaptureRequest

object CameraFocusModeSelector {
    fun choose(availableModes: IntArray?): Int? {
        val modes = availableModes?.toSet().orEmpty()
        return when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in modes ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            CaptureRequest.CONTROL_AF_MODE_AUTO in modes ->
                CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> null
        }
    }
}
