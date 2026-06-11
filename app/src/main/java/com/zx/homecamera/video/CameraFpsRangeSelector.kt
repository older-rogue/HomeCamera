package com.zx.homecamera.video

import kotlin.math.abs

data class CameraFpsRange(
    val min: Int,
    val max: Int,
)

object CameraFpsRangeSelector {
    private const val TIGHT_VARIABLE_RANGE_SPAN = 5
    private const val NEARBY_FIXED_RANGE_MAX_DISTANCE = 10

    fun choose(ranges: List<CameraFpsRange>, targetFps: Int): CameraFpsRange? {
        if (ranges.isEmpty()) return null
        ranges.firstOrNull { it.min == targetFps && it.max == targetFps }?.let { return it }
        ranges
            .filter { targetFps in it.min..it.max && it.max - it.min <= TIGHT_VARIABLE_RANGE_SPAN }
            .minWithOrNull(
                compareBy<CameraFpsRange>(
                    { it.max - it.min },
                    { abs(it.max - targetFps) },
                    { abs(it.min - targetFps) },
                ),
            )
            ?.let { return it }
        ranges
            .filter { it.min == it.max && abs(it.max - targetFps) <= NEARBY_FIXED_RANGE_MAX_DISTANCE }
            .minWithOrNull(compareBy<CameraFpsRange> { abs(it.max - targetFps) })
            ?.let { return it }
        return ranges.minWith(
            compareBy<CameraFpsRange>(
                { if (targetFps in it.min..it.max) 0 else 1 },
                { if (targetFps in it.min..it.max) it.max - it.min else abs(it.max - targetFps) },
                { it.max - it.min },
                { abs(it.min - targetFps) },
            ),
        )
    }
}
