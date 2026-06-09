package com.zx.homecamera.ui

import com.zx.homecamera.video.VideoSize
import kotlin.math.roundToInt

object ViewerTextureTransform {
    fun textureLayoutSize(
        containerWidth: Int,
        containerHeight: Int,
        surfaceSize: VideoSize,
        rotationDegrees: Float,
    ): VideoSize {
        if (containerWidth <= 0 || containerHeight <= 0 || surfaceSize.width <= 0 || surfaceSize.height <= 0) {
            return VideoSize(0, 0)
        }

        val rotateQuarterTurn = rotationDegrees == 90f || rotationDegrees == 270f
        val visualAspectRatio = if (rotateQuarterTurn) {
            surfaceSize.height.toFloat() / surfaceSize.width.toFloat()
        } else {
            surfaceSize.width.toFloat() / surfaceSize.height.toFloat()
        }
        val containerAspectRatio = containerWidth.toFloat() / containerHeight.toFloat()
        val visualSize = if (visualAspectRatio > containerAspectRatio) {
            val visualHeight = containerHeight
            val visualWidth = (visualHeight * visualAspectRatio).roundToInt()
            VideoSize(visualWidth, visualHeight)
        } else {
            val visualWidth = containerWidth
            val visualHeight = (visualWidth / visualAspectRatio).roundToInt()
            VideoSize(visualWidth, visualHeight)
        }

        return if (rotateQuarterTurn) {
            VideoSize(visualSize.height, visualSize.width)
        } else {
            visualSize
        }
    }
}
