package com.zx.homecamera.ui

import kotlin.math.abs
import kotlin.math.max

/**
 * 客户端预览的手势缩放/平移控制器。
 *
 * 只管理缩放倍数与平移偏移量，不触碰 TextureView 的变换矩阵或旋转。
 * 缩放与平移由 [com.zx.homecamera.ui.RotatedViewerTextureLayout] 的 onMeasure/onLayout
 * 应用到 TextureView 的 layout 尺寸与位置上，旋转仍由 textureView.rotation 处理。
 * 这样所有手势坐标都是 View layout 坐标，与旋转无关，方向天然正确。
 *
 * - 双指缩放：以双指中点为中心，缩放范围 [MIN_SCALE, MAX_SCALE]。
 * - 单指拖动：按当前缩放倍数钳制边界，画面不会被拖出可视区。
 * - 双击：在 Fit（1x 原始适配）与 Crop（铺满裁切）之间切换。
 */
class ViewerZoomController {

    enum class Mode { Fit, Crop }

    var scale: Float = MIN_SCALE
        private set
    var mode: Mode = Mode.Fit
        private set
    private var translateX: Float = 0f
    private var translateY: Float = 0f

    /**
     * 双指缩放。[factor] 为本次缩放因子，[focusOffsetX]/[focusOffsetY] 为焦点相对 fit 画面中心的偏移
     * （fit 居中位置为基准），[baseWidth]/[baseHeight] 为 fit 基准（未缩放）的 layout 尺寸。
     */
    fun onScale(factor: Float, focusOffsetX: Float, focusOffsetY: Float, baseWidth: Int, baseHeight: Int) {
        if (factor <= 0f) return
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (newScale == scale) return
        // 以焦点为中心缩放：保持焦点下的内容位置不变。
        // 焦点偏移在缩放后应放大 actualFactor 倍，为保持焦点不动需平移补偿。
        val actualFactor = newScale / scale
        translateX = (translateX - focusOffsetX) * actualFactor + focusOffsetX
        translateY = (translateY - focusOffsetY) * actualFactor + focusOffsetY
        scale = newScale
        clampTranslation(baseWidth, baseHeight)
    }

    /**
     * 单指拖动。[dx]/[dy] 为本次拖动增量，钳制边界使画面不溢出容器。
     * [baseWidth]/[baseHeight] 为 fit 基准 layout 尺寸。
     */
    fun onDrag(dx: Float, dy: Float, baseWidth: Int, baseHeight: Int) {
        if (baseWidth <= 0 || baseHeight <= 0) return
        translateX += dx
        translateY += dy
        clampTranslation(baseWidth, baseHeight)
    }

    /**
     * 双击切换 Fit↔Crop。[containerWidth]×[containerHeight] 为容器尺寸，
     * [baseWidth]×[baseHeight] 为 fit 基准 layout 尺寸。
     */
    fun onDoubleTap(containerWidth: Int, containerHeight: Int, baseWidth: Int, baseHeight: Int) {
        if (containerWidth <= 0 || containerHeight <= 0 || baseWidth <= 0 || baseHeight <= 0) return
        when (mode) {
            Mode.Fit -> {
                // 铺满裁切倍数 = 短边撑满所需的放大比。
                val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
                val baseAspect = baseWidth.toFloat() / baseHeight.toFloat()
                val cropScale = max(containerAspect / baseAspect, baseAspect / containerAspect)
                scale = cropScale.coerceIn(MIN_SCALE, MAX_SCALE)
                mode = Mode.Crop
            }
            Mode.Crop -> {
                scale = MIN_SCALE
                mode = Mode.Fit
            }
        }
        translateX = 0f
        translateY = 0f
    }

    /** 连接/配置变更时重置回 Fit。 */
    fun reset() {
        scale = MIN_SCALE
        mode = Mode.Fit
        translateX = 0f
        translateY = 0f
    }

    /** 当前 X 平移偏移（相对 fit 居中位置，单位像素）。 */
    fun translateX(): Float = translateX

    /** 当前 Y 平移偏移（相对 fit 居中位置，单位像素）。 */
    fun translateY(): Float = translateY

    private fun clampTranslation(baseWidth: Int, baseHeight: Int) {
        // 放大后画面比容器多出 (scale-1)*baseSize（每侧 (scale-1)*baseSize/2），
        // 平移量以此为中心对称钳制。scale=1 时偏移为 0。
        val maxX = maxOffset(baseWidth)
        val maxY = maxOffset(baseHeight)
        translateX = translateX.coerceIn(-maxX, maxX)
        translateY = translateY.coerceIn(-maxY, maxY)
    }

    private fun maxOffset(baseSize: Int): Float {
        if (baseSize <= 0) return 0f
        return abs(scale - MIN_SCALE) * baseSize / 2f
    }

    companion object {
        const val MIN_SCALE = 1.0f
        const val MAX_SCALE = 5.0f
    }
}
