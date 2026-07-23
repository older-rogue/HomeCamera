package com.zx.homecamera.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import com.zx.homecamera.video.H264StreamConfig
import com.zx.homecamera.video.VideoSize
import kotlin.math.roundToInt

class CenterCropSurfaceLayout(context: Context) : FrameLayout(context) {
    val surfaceView: SurfaceView = SurfaceView(context)
    var onContainerChanged: ((SurfaceHolder, Int, Int) -> Unit)? = null

    private var previewWidth = H264StreamConfig.WIDTH
    private var previewHeight = H264StreamConfig.HEIGHT

    init {
        clipChildren = true
        addView(surfaceView)
    }

    fun updatePreviewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (previewWidth == width && previewHeight == height) return
        previewWidth = width
        previewHeight = height
        requestLayout()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0 && surfaceView.holder.surface?.isValid == true) {
            onContainerChanged?.invoke(surfaceView.holder, width, height)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val containerWidth = right - left
        val containerHeight = bottom - top
        if (containerWidth <= 0 || containerHeight <= 0) return

        val (childWidth, childHeight) = centerCropSize(containerWidth, containerHeight)
        val childLeft = (containerWidth - childWidth) / 2
        val childTop = (containerHeight - childHeight) / 2
        surfaceView.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val (childWidth, childHeight) = centerCropSize(measuredWidth, measuredHeight)
        surfaceView.measure(
            MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
        )
    }

    private fun centerCropSize(containerWidth: Int, containerHeight: Int): Pair<Int, Int> {
        if (containerWidth <= 0 || containerHeight <= 0) return 0 to 0
        val previewAspect = previewWidth.toFloat() / previewHeight.toFloat()
        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
        return if (previewAspect > containerAspect) {
            val childHeight = containerHeight
            val childWidth = (childHeight * previewAspect).roundToInt()
            childWidth to childHeight
        } else {
            val childWidth = containerWidth
            val childHeight = (childWidth / previewAspect).roundToInt()
            childWidth to childHeight
        }
    }
}

class RotatedViewerTextureLayout(context: Context) : FrameLayout(context) {
    private val textureView: TextureView = TextureView(context)
    private var surfaceSize = VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT)
    private var rotationDegrees = 0f
    private var decoderSurface: Surface? = null
    var onSurfaceAvailable: ((Surface) -> Unit)? = null
    var onSurfaceDestroyed: (() -> Unit)? = null

    init {
        clipChildren = true
        addView(textureView)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                surfaceTexture.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
                decoderSurface?.release()
                decoderSurface = Surface(surfaceTexture).also { surface ->
                    onSurfaceAvailable?.invoke(surface)
                }
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                surfaceTexture.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
                textureView.setTransform(null)
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                decoderSurface?.release()
                decoderSurface = null
                onSurfaceDestroyed?.invoke()
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
    }

    fun updateConfig(surfaceSize: VideoSize, rotationDegrees: Float) {
        if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return
        this.surfaceSize = surfaceSize
        this.rotationDegrees = rotationDegrees
        textureView.rotation = rotationDegrees
        textureView.surfaceTexture?.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
        textureView.setTransform(null)
        dispatchAvailableSurface()
        requestLayout()
    }

    fun dispatchAvailableSurface() {
        val surface = decoderSurface
        if (surface != null && surface.isValid) {
            onSurfaceAvailable?.invoke(surface)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val containerWidth = right - left
        val containerHeight = bottom - top
        if (containerWidth <= 0 || containerHeight <= 0) return

        val size = ViewerTextureTransform.textureLayoutSize(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            surfaceSize = surfaceSize,
            rotationDegrees = rotationDegrees,
        )
        val childLeft = (containerWidth - size.width) / 2
        val childTop = (containerHeight - size.height) / 2
        textureView.layout(childLeft, childTop, childLeft + size.width, childTop + size.height)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = ViewerTextureTransform.textureLayoutSize(
            containerWidth = measuredWidth,
            containerHeight = measuredHeight,
            surfaceSize = surfaceSize,
            rotationDegrees = rotationDegrees,
        )
        textureView.measure(
            MeasureSpec.makeMeasureSpec(size.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size.height, MeasureSpec.EXACTLY),
        )
    }
}
