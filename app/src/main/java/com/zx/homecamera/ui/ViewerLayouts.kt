package com.zx.homecamera.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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

    private val zoomController = ViewerZoomController()
    private var isScaling = false
    // fit 基准（未缩放）的 layout 尺寸与居中位置，供手势计算焦点偏移用。
    private var fitWidth = 0
    private var fitHeight = 0
    private var fitLeft = 0
    private var fitTop = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // 焦点相对 fit 画面中心的偏移。
            val fitCenterX = fitLeft + fitWidth / 2f
            val fitCenterY = fitTop + fitHeight / 2f
            zoomController.onScale(
                factor = detector.scaleFactor,
                focusOffsetX = detector.focusX - fitCenterX,
                focusOffsetY = detector.focusY - fitCenterY,
                baseWidth = fitWidth,
                baseHeight = fitHeight,
            )
            requestLayout()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomController.onDoubleTap(
                containerWidth = width,
                containerHeight = height,
                baseWidth = fitWidth,
                baseHeight = fitHeight,
            )
            requestLayout()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // 缩放进行时不处理拖动，避免双指移动同时触发缩放和平移导致抖动。
            if (isScaling) return false
            // distanceX/Y 为已消费的位移，方向与拖动相反。
            zoomController.onDrag(-distanceX, -distanceY, fitWidth, fitHeight)
            requestLayout()
            return true
        }
    })

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)
        return scaleHandled || gestureHandled || super.onTouchEvent(event)
    }

    fun updateConfig(surfaceSize: VideoSize, rotationDegrees: Float) {
        if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return
        // 连接/配置变更时重置缩放，避免旧缩放状态残留。
        zoomController.reset()
        this.surfaceSize = surfaceSize
        this.rotationDegrees = rotationDegrees
        textureView.rotation = rotationDegrees
        textureView.surfaceTexture?.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
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

        computeFitSize(containerWidth, containerHeight)
        val scale = zoomController.scale
        // 缩放后的尺寸（以 fit 为基准）。
        val scaledWidth = (fitWidth * scale).toInt()
        val scaledHeight = (fitHeight * scale).toInt()
        // 居中后再加平移偏移。
        val baseLeft = (containerWidth - scaledWidth) / 2
        val baseTop = (containerHeight - scaledHeight) / 2
        val childLeft = (baseLeft + zoomController.translateX()).toInt()
        val childTop = (baseTop + zoomController.translateY()).toInt()
        textureView.layout(childLeft, childTop, childLeft + scaledWidth, childTop + scaledHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        computeFitSize(measuredWidth, measuredHeight)
        val scale = zoomController.scale
        val scaledWidth = (fitWidth * scale).toInt()
        val scaledHeight = (fitHeight * scale).toInt()
        textureView.measure(
            MeasureSpec.makeMeasureSpec(scaledWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(scaledHeight, MeasureSpec.EXACTLY),
        )
    }

    private fun computeFitSize(containerWidth: Int, containerHeight: Int) {
        val size = ViewerTextureTransform.textureLayoutSize(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            surfaceSize = surfaceSize,
            rotationDegrees = rotationDegrees,
        )
        fitWidth = size.width
        fitHeight = size.height
        fitLeft = (containerWidth - fitWidth) / 2
        fitTop = (containerHeight - fitHeight) / 2
    }
}
