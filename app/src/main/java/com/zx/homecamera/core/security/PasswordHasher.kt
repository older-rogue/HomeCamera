package com.zx.homecamera.core.security

import java.security.MessageDigest

/**
 * 采集端密码哈希工具。
 *
 * 采集端只保存密码的 SHA-256 十六进制摘要，不保存明文；连接校验时对客户端提交的
 * 明文做同样哈希后比对。局域网内防的是"随手翻文件/广播偷听"，客户端与采集端之间
 * 的 TCP 控制通道仍按现有明文协议传输（与应用整体不加密的威胁模型一致）。
 */
object PasswordHasher {
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
