package com.zx.homecamera.video

import android.view.SurfaceHolder

object CollectorCameraRuntime {
    private var streamer: CameraH264Streamer? = null
    private var previewHolder: SurfaceHolder? = null
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0
    private var previewSizeListener: ((VideoSize) -> Unit)? = null
    private var displayRotationDegrees: Int = 0
    private var preselectedPreviewSize: PreviewSize? = null

    @Synchronized
    fun attachStreamer(streamer: CameraH264Streamer) {
        this.streamer = streamer
        streamer.setPreviewDisplaySizeListener(previewSizeListener)
        streamer.setDisplayRotationDegrees(displayRotationDegrees)
        streamer.setPreselectedPreviewSize(preselectedPreviewSize)
        streamer.setPreviewSurface(previewHolder, previewWidth, previewHeight)
    }

    @Synchronized
    fun detachStreamer(streamer: CameraH264Streamer) {
        if (this.streamer == streamer) {
            streamer.setPreviewSurface(null)
            streamer.setPreviewDisplaySizeListener(null)
            this.streamer = null
            previewHolder = null
            previewWidth = 0
            previewHeight = 0
            previewSizeListener = null
        }
    }

    @Synchronized
    fun setPreviewSurface(holder: SurfaceHolder?, width: Int = 0, height: Int = 0) {
        previewHolder = holder?.takeIf { it.surface?.isValid == true }
        previewWidth = width
        previewHeight = height
        streamer?.setPreviewSurface(previewHolder, previewWidth, previewHeight)
    }

    @Synchronized
    fun setPreviewDisplaySizeListener(listener: ((VideoSize) -> Unit)?) {
        previewSizeListener = listener
        streamer?.setPreviewDisplaySizeListener(listener)
    }

    @Synchronized
    fun setDisplayRotationDegrees(degrees: Int) {
        displayRotationDegrees = degrees
        streamer?.setDisplayRotationDegrees(degrees)
    }

    @Synchronized
    fun setPreselectedPreviewSize(size: PreviewSize?) {
        preselectedPreviewSize = size
        streamer?.setPreselectedPreviewSize(size)
    }
}
