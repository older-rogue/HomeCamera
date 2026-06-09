package com.zx.homecamera.video

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.view.SurfaceHolder

object CameraPreviewConfigReader {
    fun read(
        context: Context,
        displayRotationDegrees: Int,
        targetWidth: Int = H264StreamConfig.WIDTH,
        targetHeight: Int = H264StreamConfig.HEIGHT,
    ): PreviewSize {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = findCameraId(manager)
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val supportedSizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            .previewSizes()
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return PreviewSizeSelector.chooseConfig(
            supportedSizes = supportedSizes,
            surfaceWidth = targetWidth,
            surfaceHeight = targetHeight,
            sensorOrientationDegrees = sensorOrientation,
            displayRotationDegrees = displayRotationDegrees,
        )
    }

    fun findCameraId(manager: CameraManager): String {
        val ids = manager.cameraIdList
        val backCamera = ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        return backCamera ?: ids.first()
    }

    fun StreamConfigurationMap?.previewSizes(): List<VideoSize> =
        this?.getOutputSizes(SurfaceHolder::class.java)
            ?.map { VideoSize(it.width, it.height) }
            ?.ifEmpty { listOf(VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT)) }
            ?: listOf(VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT))

    fun StreamConfigurationMap?.encoderSizes(): List<VideoSize> =
        this?.getOutputSizes(MediaCodec::class.java)
            ?.map { VideoSize(it.width, it.height) }
            ?.ifEmpty { previewSizes() }
            ?: previewSizes()
}
