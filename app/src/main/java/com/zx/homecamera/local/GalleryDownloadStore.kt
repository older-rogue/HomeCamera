package com.zx.homecamera.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 已下载到系统相册的录像 fileId 持久化记录。
 *
 * 历史录像列表与回放页各自保存录像到相册后，将 fileId 记入本地；
 * 进入页面时从本地读取，恢复「已下载/已保存」状态。
 *
 * 选择 SharedPreferences（而非查询 MediaStore）作为状态来源，是因为 MediaStore
 * 对应用刚插入的文件存在索引延迟，且 `RELATIVE_PATH` 查询条件在不同版本上匹配
 * 不稳定，导致「下载后立刻退出再进来，状态丢失」的问题。本地存储是确定性的：
 * 下载成功即写入，进入即读取，与相册索引无关。
 */
object GalleryDownloadStore {
    private const val PREF_NAME = "gallery_downloads"
    private const val KEY_FILE_IDS = "downloaded_file_ids"

    /**
     * 根据 [fileId] 计算保存到相册时的显示名。
     * fileId 形如 `2026-07-21/14-30-00.mp4`，转成 `HomeCamera_2026-07-21_14-30-00.mp4`。
     * 规则与 [com.zx.homecamera.network.RecordingApiClient.galleryDisplayName] 一致。
     */
    fun displayNameFor(fileId: String): String {
        val safeName = fileId.replace('/', '_')
        return if (safeName.startsWith("HomeCamera_")) safeName else "HomeCamera_$safeName"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 读取所有已下载的 fileId 集合。可在任意线程调用。
     */
    fun getDownloadedFileIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FILE_IDS, emptySet()) ?: emptySet()

    /**
     * 记录一个 fileId 为已下载。可在任意线程调用。
     */
    fun markDownloaded(context: Context, fileId: String) {
        val current = getDownloadedFileIds(context)
        if (fileId in current) return
        prefs(context).edit().putStringSet(KEY_FILE_IDS, current + fileId).apply()
    }

    /**
     * 判断指定 [fileId] 是否已下载。可在任意线程调用。
     */
    fun isDownloaded(context: Context, fileId: String): Boolean =
        fileId in getDownloadedFileIds(context)
}
