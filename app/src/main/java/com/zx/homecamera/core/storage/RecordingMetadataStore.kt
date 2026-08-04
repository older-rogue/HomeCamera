package com.zx.homecamera.core.storage

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 录像文件元数据持久化缓存（JSON）。
 *
 * 将每个已关闭录像的元信息（fileId / size / startMillis / corrupted）缓存到
 * `recordings/.metadata/index.json`，使客户端列表请求无需逐文件打开
 * [MediaMetadataRetriever] 校验完整性，直接读缓存秒回。
 *
 * 元数据在录像 segment 关闭后（[Mp4Faststart] 重排完成）写入。读取时与实际文件系统
 * 对账：文件不存在的条目视为已删除并清理，文件大小变化的标记为需重算（返回 null 让
 * 调用方回退到实时校验）。损坏状态只在写入时计算一次，后续不再重算（录像文件关闭后不变）。
 *
 * JSON 格式（当前数据量小，单文件即可）：
 * ```
 * { "entries": [
 *   {"fileId":"2026-08-04/06-10-29.mp4","sizeBytes":72941295,"startMillis":...,"corrupted":false},
 *   ...
 * ]}
 * ```
 */
class RecordingMetadataStore(
    private val root: File,
) {
    private val metadataDir = File(root, METADATA_DIR)
    private val indexFile = File(metadataDir, INDEX_FILE)

    /**
     * 读取并校验元数据缓存。返回 fileId -> [Entry] 的映射。
     * 仅包含**当前文件存在且大小一致**的条目；文件已删除或大小变化的条目不返回，
     * 并顺带清理缓存（写回精简后的 index）。
     *
     * 调用方仍需自行补充"正在录制中"的文件（这些文件不在缓存里，因为尚未关闭）。
     */
    fun loadValid(): Map<String, Entry> = synchronized(lock) {
        val entries = loadRaw()
        // 对账：剔除文件不存在或大小不一致的条目。
        val valid = HashMap<String, Entry>()
        var changed = false
        for ((fileId, entry) in entries) {
            val file = File(root, fileId)
            if (!file.isFile) {
                changed = true // 文件已删除，丢弃缓存
                continue
            }
            if (file.length() != entry.sizeBytes) {
                changed = true // 大小变化（被覆盖/续写），丢弃让调用方实时校验
                continue
            }
            valid[fileId] = entry
        }
        if (changed) save(valid)
        valid
    }

    /**
     * 写入或更新单个录像的元数据。segment 关闭后调用：完整性已校验，直接落盘。
     */
    fun put(fileId: String, sizeBytes: Long, startMillis: Long, corrupted: Boolean) {
        synchronized(lock) {
            val current = loadRaw()
            current[fileId] = Entry(fileId, sizeBytes, startMillis, corrupted)
            save(current)
        }
    }

    /**
     * 删除指定 fileId 的元数据。文件被清理时调用，避免缓存残留。
     */
    fun remove(fileId: String) {
        synchronized(lock) {
            val current = loadRaw()
            if (current.remove(fileId) != null) save(current)
        }
    }

    /**
     * 读取原始缓存（不做文件对账），所有写操作基于此快照修改后写回。
     * 调用方须持有 [lock]。
     */
    private fun loadRaw(): MutableMap<String, Entry> {
        val json = runCatching { indexFile.readText() }.getOrNull() ?: return HashMap()
        return runCatching {
            val arr = JSONObject(json).optJSONArray(KEY_ENTRIES) ?: return HashMap()
            val map = HashMap<String, Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val entry = Entry.fromJson(obj) ?: continue
                map[entry.fileId] = entry
            }
            map
        }.onFailure {
            Log.w(TAG, "metadata index parse failed: ${it.message}")
        }.getOrNull() ?: HashMap()
    }

    /**
     * 写回元数据索引。调用方须持有 [lock]（可重入）。
     */
    private fun save(entries: Map<String, Entry>) {
        runCatching {
            metadataDir.mkdirs()
            val arr = JSONArray()
            // 按日期/时间排序写回，便于人工查看与稳定 diff。
            entries.values.sortedBy { it.fileId }.forEach { arr.put(it.toJson()) }
            val obj = JSONObject().put(KEY_ENTRIES, arr)
            indexFile.writeText(obj.toString())
        }.onFailure {
            Log.w(TAG, "metadata index save failed: ${it.message}")
        }
    }

    data class Entry(
        val fileId: String,
        val sizeBytes: Long,
        val startMillis: Long,
        val corrupted: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put(KEY_FILE_ID, fileId)
            .put(KEY_SIZE, sizeBytes)
            .put(KEY_START, startMillis)
            .put(KEY_CORRUPTED, corrupted)

        companion object {
            fun fromJson(obj: JSONObject): Entry? {
                val fileId = obj.optString(KEY_FILE_ID).takeIf { it.isNotEmpty() } ?: return null
                val size = obj.optLong(KEY_SIZE, -1L)
                if (size < 0) return null
                val start = obj.optLong(KEY_START, 0L)
                val corrupted = obj.optBoolean(KEY_CORRUPTED, false)
                return Entry(fileId, size, start, corrupted)
            }
        }
    }

    companion object {
        private const val TAG = "RecordingMetadataStore"
        private const val METADATA_DIR = ".metadata"
        private const val INDEX_FILE = "index.json"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_FILE_ID = "fileId"
        private const val KEY_SIZE = "sizeBytes"
        private const val KEY_START = "startMillis"
        private const val KEY_CORRUPTED = "corrupted"
        private val lock = Any()
    }
}
