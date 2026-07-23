package com.zx.homecamera.video

import kotlin.math.abs

data class H264StreamSelection(
    val bufferSize: VideoSize,
    val displaySize: VideoSize,
    val fps: Int,
    val bitrate: Int,
    val iFrameIntervalSeconds: Int,
)

object H264StreamConfigSelector {
    private val maxStreamSize = VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT)

    fun choose(
        supportedSizes: List<VideoSize>,
        surfaceWidth: Int,
        surfaceHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
    ): H264StreamSelection {
        val fallback = VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT)
        val candidates = supportedSizes.ifEmpty { listOf(fallback) }
        val boundedCandidates = candidates
            .filter { it.width <= maxStreamSize.width && it.height <= maxStreamSize.height }
            .ifEmpty { candidates }

        val bufferSize = chooseBufferSize(
            supportedSizes = boundedCandidates,
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            sensorOrientationDegrees = sensorOrientationDegrees,
            displayRotationDegrees = displayRotationDegrees,
        )
        val displaySize = PreviewSizeSelector.displaySize(
            previewSize = bufferSize,
            sensorOrientationDegrees = sensorOrientationDegrees,
            displayRotationDegrees = displayRotationDegrees,
        )
        return H264StreamSelection(
            bufferSize = bufferSize,
            displaySize = displaySize,
            fps = H264StreamConfig.FPS,
            bitrate = H264StreamConfig.BITRATE,
            iFrameIntervalSeconds = H264StreamConfig.I_FRAME_INTERVAL_SECONDS,
        )
    }

    private fun chooseBufferSize(
        supportedSizes: List<VideoSize>,
        surfaceWidth: Int,
        surfaceHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
    ): VideoSize {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            return supportedSizes.firstOrNull() ?: VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT)
        }

        val targetAspectRatio = surfaceWidth.toDouble() / surfaceHeight.toDouble()
        val targetArea = surfaceWidth * surfaceHeight
        return supportedSizes.minBy { size ->
            val displaySize = PreviewSizeSelector.displaySize(
                previewSize = size,
                sensorOrientationDegrees = sensorOrientationDegrees,
                displayRotationDegrees = displayRotationDegrees,
            )
            val aspectPenalty = abs(displaySize.aspectRatio - targetAspectRatio) * 10_000
            val areaPenalty = abs(displaySize.area - targetArea).toDouble() / targetArea.coerceAtLeast(1)
            aspectPenalty + areaPenalty
        }
    }
}
