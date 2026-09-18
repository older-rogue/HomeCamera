package com.zx.homecamera.local

import android.content.Context
import android.os.Build
import com.zx.homecamera.core.security.PasswordHasher

/**
 * 采集端访问配置：设备名称与访问密码。
 *
 * 名称明文保存，供发现广播与客户端列表展示；密码只存 SHA-256 摘要（见
 * [PasswordHasher]），采集端自身永远不保留明文。进入采集端时首次设置，
 * 之后可通过采集端界面的"名称与密码"入口修改。
 */
object CollectorAuthSettings {
    private const val PREF_NAME = "collector_auth"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_PASSWORD_HASH = "password_hash"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 用户配置的设备名称，未设置时回退为机型名。 */
    fun deviceName(context: Context): String =
        prefs(context).getString(KEY_DEVICE_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: (Build.MODEL ?: "Android 采集端")

    fun passwordHash(context: Context): String? =
        prefs(context).getString(KEY_PASSWORD_HASH, null)?.takeIf { it.isNotEmpty() }

    /** 是否已设置密码（配置过名称即视为完成设置）。 */
    fun isConfigured(context: Context): Boolean =
        !prefs(context).getString(KEY_DEVICE_NAME, null).isNullOrBlank()

    /** 保存名称与密码。密码为空时仅更新名称并清除密码（等价于关闭鉴权）。 */
    fun save(context: Context, name: String, password: String) {
        prefs(context).edit()
            .putString(KEY_DEVICE_NAME, name.trim())
            .putString(KEY_PASSWORD_HASH, if (password.isNotEmpty()) PasswordHasher.sha256Hex(password) else null)
            .apply()
    }

    /**
     * 校验客户端提交的密码。未设置密码时恒通过（兼容未开启鉴权的采集端）。
     */
    fun verify(context: Context, provided: String): Boolean {
        val hash = passwordHash(context) ?: return true
        return PasswordHasher.sha256Hex(provided) == hash
    }
}
