package com.zx.homecamera.video

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

        // 编码分辨率固定为配置上限内的最大可用尺寸，不再依赖预览容器尺寸匹配。
        // 客户端负责全屏渲染适配，编码尺寸与预览 UI 解耦。
        val bufferSize = boundedCandidates.maxByOrNull { it.area } ?: fallback
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
}
