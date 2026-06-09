package com.zx.homecamera.video

import kotlin.math.abs

data class VideoSize(
    val width: Int,
    val height: Int,
) {
    val area: Int = width * height
    val aspectRatio: Double = width.toDouble() / height.toDouble()
}

data class PreviewSize(
    val bufferSize: VideoSize,
    val displaySize: VideoSize,
) {
    fun needsFixedSizeUpdate(previous: PreviewSize?): Boolean =
        previous?.bufferSize != bufferSize
}

object PreviewSizeSelector {
    fun choose(
        supportedSizes: List<VideoSize>,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): VideoSize {
        val fallback = VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT)
        if (supportedSizes.isEmpty() || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return supportedSizes.firstOrNull() ?: fallback
        }

        val targetAspectRatio = surfaceWidth.toDouble() / surfaceHeight.toDouble()
        val targetArea = surfaceWidth * surfaceHeight
        return supportedSizes.minBy { size ->
            val aspectPenalty = abs(size.aspectRatio - targetAspectRatio) * 10_000
            val areaPenalty = abs(size.area - targetArea).toDouble() / targetArea.coerceAtLeast(1)
            aspectPenalty + areaPenalty
        }
    }

    fun displaySize(
        previewSize: VideoSize,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
    ): VideoSize {
        val relativeRotation = floorMod(sensorOrientationDegrees - displayRotationDegrees, 360)
        return if (relativeRotation == 90 || relativeRotation == 270) {
            VideoSize(previewSize.height, previewSize.width)
        } else {
            previewSize
        }
    }

    fun chooseConfig(
        supportedSizes: List<VideoSize>,
        surfaceWidth: Int,
        surfaceHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
    ): PreviewSize {
        val bufferSize = choose(supportedSizes, surfaceWidth, surfaceHeight)
        return PreviewSize(
            bufferSize = bufferSize,
            displaySize = displaySize(
                previewSize = bufferSize,
                sensorOrientationDegrees = sensorOrientationDegrees,
                displayRotationDegrees = displayRotationDegrees,
            ),
        )
    }

    private fun floorMod(value: Int, modulus: Int): Int =
        ((value % modulus) + modulus) % modulus
}
