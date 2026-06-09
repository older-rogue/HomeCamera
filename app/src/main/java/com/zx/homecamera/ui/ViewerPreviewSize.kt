package com.zx.homecamera.ui

import com.zx.homecamera.network.ViewerConnection
import com.zx.homecamera.video.H264StreamConfig
import com.zx.homecamera.video.VideoSize

object ViewerPreviewSize {
    fun displaySize(connection: ViewerConnection?): VideoSize =
        connection?.let { VideoSize(it.displayWidth, it.displayHeight) }
            ?: VideoSize(H264StreamConfig.HEIGHT, H264StreamConfig.WIDTH)

    fun surfaceSize(connection: ViewerConnection?): VideoSize =
        connection?.let { VideoSize(it.streamWidth, it.streamHeight) }
            ?: VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT)

    fun rotationDegrees(connection: ViewerConnection?): Float {
        val displaySize = displaySize(connection)
        val surfaceSize = surfaceSize(connection)
        return if (
            displaySize.width == surfaceSize.height &&
            displaySize.height == surfaceSize.width
        ) {
            90f
        } else {
            0f
        }
    }
}
