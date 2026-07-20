package com.zx.homecamera.network

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 网络请求服务类，专门处理网络相关操作
 *
 * 提供应用更新检查能力。
 */
class ApiService private constructor() {

    companion object {
        private const val TAG = "ApiService"

        // ====== 以下地址/密钥为占位，请自行填写 ======

        /** 更新检查接口地址 */
        private const val UPDATE_CHECK_URL = "https://www.pgyer.com/apiv2/app/check"

        /** 蒲公英 API Key */
        private const val API_KEY = "56eac30dc043da146b8b834b88ab1ff8"

        /** 蒲公英 App Key */
        private const val APP_KEY = "fcdaec5f20d465f244d3df154f29574b"

        // =============================================

        private const val TIMEOUT_MILLIS = 10_000

        val instance: ApiService by lazy { ApiService() }
    }

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 检查应用更新
     *
     * 调用蒲公英风格的更新接口，解析返回的 [UpdateInfo]。
     * 失败时静默处理，不影响主流程。
     *
     * @param callback 解析成功后的回调（在后台线程触发，调用方需自行切回主线程）
     */
    fun checkUpdate(callback: (UpdateInfo) -> Unit) {
        if (UPDATE_CHECK_URL.isEmpty() || API_KEY.isEmpty() || APP_KEY.isEmpty()) {
            // 未配置更新接口，静默跳过
            return
        }

        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val params = "_api_key=${URLEncoder.encode(API_KEY, "UTF-8")}" +
                    "&appKey=${URLEncoder.encode(APP_KEY, "UTF-8")}"
                connection = (URL(UPDATE_CHECK_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MILLIS
                    readTimeout = TIMEOUT_MILLIS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }

                OutputStreamWriter(connection.outputStream, "UTF-8").use { it.write(params) }

                if (connection.responseCode != 200) return@execute
                val data = connection.inputStream.bufferedReader().use { it.readText() }
                if (data.isEmpty()) return@execute

                val jsonObject = JSONObject(data)
                val finalData = jsonObject.getJSONObject("data")
                val updateInfo = UpdateInfo(
                    buildVersionNo = finalData.optString("buildVersionNo", "0"),
                    buildVersion = finalData.optString("buildVersion", ""),
                    appUrl = finalData.optString("appURl", ""),
                    buildUpdateDescription = finalData.optString("buildUpdateDescription", "无"),
                )
                callback(updateInfo)
            } catch (e: Exception) {
                // 静默失败，不影响主流程
                Log.w(TAG, "checkUpdate failed: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }
}
