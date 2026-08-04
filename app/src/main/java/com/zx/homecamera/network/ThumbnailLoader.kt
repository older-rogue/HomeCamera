package com.zx.homecamera.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.zx.homecamera.core.app.CollectorDevice
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 查看端缩略图加载器（进程级单例）。
 *
 * 通过采集端已有的 [com.zx.homecamera.service.RecordingHttpServer] 拉取首帧缩略图：
 * `GET http://<host>:62003/<fileId>?thumb=1`，返回 JPEG 字节流。采集端用
 * [com.zx.homecamera.core.storage.ThumbnailGenerator] 懒生成并缓存，这里负责查看端的
 * Bitmap 解码与 LruCache，以及并发去重。
 *
 * 做成 object 而非随 ViewModel 生命周期：缩略图解码有成本，跨 Activity/ViewModel 重建复用，
 * 避免退出历史页再进入时重复拉取。LruCache 按 Bitmap 字节数计容，OOM 时自动淘汰。
 *
 * 失败（404 损坏/录制中、网络异常、解码失败）回调 null，由调用方显示占位且不再重试
 * （单条记录在本次会话内只请求一次，避免对损坏文件反复打 404）。
 */
object ThumbnailLoader {
    private const val TAG = "ThumbnailLoader"
    private const val HTTP_PORT = 62003
    private const val CONNECT_TIMEOUT_MILLIS = 5_000
    private const val READ_TIMEOUT_MILLIS = 5_000

    // 6MB：按 ~320×240 ARGB_8888 约 300KB 计，约可缓存 20 张，覆盖两列网格一屏多页。
    private val cache = object : android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 正在加载的 fileId -> 回调集合。同一 fileId 并发请求时只发一次 HTTP，结果广播给所有回调。
     */
    private val inFlight = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()

    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "thumbnail-loader").apply { isDaemon = true }
    }

    /**
     * 同步读缓存。命中返回 Bitmap，未命中返回 null（不代表失败，仅表示尚未加载）。
     */
    fun get(fileId: String): Bitmap? = cache.get(fileId)

    /**
     * 异步加载 [fileId] 的缩略图。命中缓存时回调仍通过 executor 触发，保证调用方回调线程一致。
     * 未命中时提交 HTTP 请求；并发同 fileId 只发一次，所有回调共享结果。
     * [onResult] 在工作线程触发，调用方需自行切回主线程更新 UI。
     */
    fun load(device: CollectorDevice, fileId: String, onResult: (Bitmap?) -> Unit) {
        cache.get(fileId)?.let { bmp ->
            executor.execute { onResult(bmp) }
            return
        }
        // 加入 inFlight；若已是首条则由本调用发起 HTTP，否则仅排队等结果。
        val callbacks = inFlight.computeIfAbsent(fileId) { mutableListOf() }
        synchronized(callbacks) { callbacks.add(onResult) }
        if (callbacks.size > 1) return // 已有请求在飞，等广播即可

        executor.execute {
            val result = fetch(device, fileId)
            if (result != null) cache.put(fileId, result)
            // 广播给所有排队回调（含本次）。取后移除 inFlight，允许后续重试/重新加载。
            val list = inFlight.remove(fileId)
            if (list != null) {
                synchronized(list) {
                    for (cb in list) {
                        runCatching { cb(result) }
                    }
                }
            }
        }
    }

    private fun fetch(device: CollectorDevice, fileId: String): Bitmap? {
        // fileId 形如 2026-07-21/14-30-00.mp4，直接作为 HTTP 路径（采集端按 `/` 分层解析）。
        // 播放 URL 即用同样形式，此处保持一致。
        val url = "http://${device.hostAddress}:$HTTP_PORT/$fileId?thumb=1"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            conn.use { c ->
                if (c.responseCode != HttpURLConnection.HTTP_OK) return null
                val bytes = c.inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.onFailure {
            Log.w(TAG, "thumbnail fetch failed fileId=$fileId: ${it.message}")
        }.getOrNull()
    }

    /**
     * 清空缓存。仅在显式需要时调用；正常场景靠 LruCache 自动淘汰。
     */
    fun clear() {
        cache.evictAll()
        inFlight.clear()
    }

    /** 让 [HttpURLConnection] 读取完毕后自动 disconnect。 */
    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }
}
