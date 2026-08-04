package com.zx.homecamera.local

import android.content.Context

/**
 * 智能内容清理的本地设置（无 UI，仅提供读写 API）。
 *
 * 复用 SharedPreferences 模式（参照 [LocalData]）。功能默认开启，未来要加设置页时直接对接即可。
 *
 * - [KEY_ENABLED]：智能清理总开关，默认 true。
 * - [KEY_GUARD_WINDOW_MINUTES]：保护窗（分钟），距当前时刻未超过此值的切片不参与内容分析，
 *   避免误删近期事件边缘。默认 30 分钟（约 15 个 2 分钟切片）。
 */
object SmartCleanupSettings {
    private const val PREF_NAME = "smart_cleanup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_GUARD_WINDOW_MINUTES = "guard_window_minutes"

    private const val DEFAULT_ENABLED = true
    private const val DEFAULT_GUARD_WINDOW_MINUTES = 30

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun guardWindowMinutes(context: Context): Int =
        prefs(context).getInt(KEY_GUARD_WINDOW_MINUTES, DEFAULT_GUARD_WINDOW_MINUTES)

    fun setGuardWindowMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_GUARD_WINDOW_MINUTES, minutes.coerceAtLeast(0)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
