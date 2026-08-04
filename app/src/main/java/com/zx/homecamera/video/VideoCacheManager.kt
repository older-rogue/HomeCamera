package com.zx.homecamera.video

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 视频磁盘缓存管理器（进程级单例）。
 *
 * 用 Media3 的 [SimpleCache] 缓存视频字节分片，LRU 淘汰，上限 500MB。
 * 同一视频再次观看时直接读缓存（秒开），拖拽进度条到已缓存区域也无需重新下载。
 *
 * 替代了此前 [com.zx.homecamera.network.RecordingApiClient.downloadToCache] 的整文件下载方案：
 * SimpleCache 按 byte-range 缓存，更细粒度，且与 ExoPlayer 的 Range 请求天然配合。
 *
 * 必须为单例：[SimpleCache] 会锁定缓存目录，重复创建会抛异常。通过 [get] 获取进程级实例。
 */
class VideoCacheManager private constructor(private val context: Context) {

    private val cacheDir: File = File(context.cacheDir, "video_cache").apply { mkdirs() }

    val cache: SimpleCache = SimpleCache(
        cacheDir,
        LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        StandaloneDatabaseProvider(context),
    )

    /** 构造缓存优先的 DataSource.Factory：命中缓存直接读，未命中走网络并写入缓存。 */
    fun dataSourceFactory(): CacheDataSource.Factory {
        val upstream = DefaultDataSource.Factory(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    companion object {
        private const val MAX_BYTES = 500L * 1024 * 1024

        @Volatile
        private var instance: VideoCacheManager? = null

        /** 获取进程级单例。首次调用时创建，后续复用同一 SimpleCache。 */
        fun get(context: Context): VideoCacheManager =
            instance ?: synchronized(this) {
                instance ?: VideoCacheManager(context.applicationContext).also { instance = it }
            }
    }
}
