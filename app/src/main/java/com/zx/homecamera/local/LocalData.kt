package com.zx.homecamera.local

import android.content.Context

/**
 * 本地数据管理类
 *
 * 用于管理应用的本地配置数据，如是否首次启动。
 */
object LocalData {
    private const val PREF_NAME = "local_data"
    private const val KEY_IS_FIRST = "key_is_first"

    /**
     * 检查是否首次启动
     */
    fun getIsFirst(context: Context): Boolean {
        val sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean(KEY_IS_FIRST, true)
    }

    /**
     * 标记已非首次启动
     */
    fun setIsFirst(context: Context) {
        val sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sharedPreferences.edit()
            .putBoolean(KEY_IS_FIRST, false)
            .apply()
    }
}
