package com.zx.homecamera.core.storage

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 扫描录像根目录，生成可用的日期列表和（指定日期的）文件列表。
 * 纯逻辑，不涉及 IO 副作用（read-only 扫描），便于单测。
 */
class RecordingLibrary(
    private val root: File,
) {
    fun listDates(): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles { file -> file.isDirectory && file.name.matches(DATE_PATTERN) }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()
    }

    fun listFiles(date: String): List<RecordingFileEntry> =
        listFiles(date, recordingFileIds = emptySet())

    /**
     * 扫描指定日期的文件列表。[recordingFileIds] 中的文件会被标记为 recording=true，
     * 用于在列表里区分"正在录制中"的片段（未 stop 的 mp4 缺少 moov，无法播放）。
     */
    fun listFiles(date: String, recordingFileIds: Set<String>): List<RecordingFileEntry> {
        val dateDir = File(root, date)
        if (!dateDir.isDirectory) return emptyList()
        return dateDir.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            ?.mapNotNull { file -> toEntry(date, file, recordingFileIds) }
            ?.sortedBy { it.startMillis }
            ?: emptyList()
    }

    private fun toEntry(date: String, file: File, recordingFileIds: Set<String>): RecordingFileEntry? {
        val startMillis = parseStartMillis(date, file.nameWithoutExtension) ?: return null
        val fileId = "$date/${file.name}"
        return RecordingFileEntry(
            fileId = fileId,
            sizeBytes = file.length(),
            startMillis = startMillis,
            recording = fileId in recordingFileIds,
        )
    }

    private fun parseStartMillis(date: String, timeName: String): Long? {
        val dateTime = runCatching {
            LocalDateTime.parse(
                "${date}T$timeName",
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"),
            )
        }.getOrNull() ?: return null
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    companion object {
        private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}

data class RecordingFileEntry(
    val fileId: String,
    val sizeBytes: Long,
    val startMillis: Long,
    val recording: Boolean = false,
    val corrupted: Boolean = false,
)
