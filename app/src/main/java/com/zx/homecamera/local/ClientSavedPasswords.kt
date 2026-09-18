package com.zx.homecamera.local

import android.content.Context

/**
 * 客户端按采集端保存的访问密码。
 *
 * 验证通过后以 deviceId（"host:port"）为键保存密码，下次进入观看页直接携带，
 * 免重复输入；验证失败（含采集端改密）时由调用方清除，下次点击重新弹密码框。
 */
object ClientSavedPasswords {
    private const val PREF_NAME = "client_saved_passwords"
    private const val KEY_PREFIX = "password_for_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getPassword(context: Context, deviceId: String): String? =
        prefs(context).getString(KEY_PREFIX + deviceId, null)?.takeIf { it.isNotEmpty() }

    fun savePassword(context: Context, deviceId: String, password: String) {
        prefs(context).edit()
            .putString(KEY_PREFIX + deviceId, password)
            .apply()
    }

    fun clearPassword(context: Context, deviceId: String) {
        prefs(context).edit().remove(KEY_PREFIX + deviceId).apply()
    }
}
