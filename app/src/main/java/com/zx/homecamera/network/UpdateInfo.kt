package com.zx.homecamera.network

/**
 * 应用更新信息模型
 *
 * @property buildVersionNo 版本号
 * @property buildVersion 版本名称
 * @property appUrl 下载地址
 * @property buildUpdateDescription 更新说明
 */
data class UpdateInfo(
    val buildVersionNo: String = "0",
    val buildVersion: String = "",
    val appUrl: String = "",
    val buildUpdateDescription: String = "无",
)
