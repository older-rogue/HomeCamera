package com.zx.homecamera.core.storage

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class RecordingRetentionPolicy(
    private val keepDays: Long = 7,
    private val minimumFreeBytes: Long = 2L * 1024L * 1024L * 1024L,
) {
    private val directoryFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun deletionPlan(
        root: File,
        today: LocalDate,
        usableBytes: Long,
    ): DeletionPlan {
        val dateDirectories = root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { file -> file.parseDateDirectory()?.let { date -> date to file } }
            ?.sortedBy { (date, _) -> date }
            .orEmpty()

        val oldestKeptDate = today.minusDays(keepDays - 1)
        val expired = dateDirectories
            .filter { (date, _) -> date.isBefore(oldestKeptDate) }
            .map { (_, file) -> file }

        var projectedUsableBytes = usableBytes + expired.sumOf { it.sizeBytes() }
        if (projectedUsableBytes >= minimumFreeBytes) return DeletionPlan(expired, emptyList())

        // 空间压力删除：从未过期目录里按日期从旧到新，逐个删最旧 mp4 文件，
        // 直到投影可用空间达标（含达标那一个，与原 takeWhileInclusive 语义一致）。
        val expiredSet = expired.toSet()
        val pressureFiles = mutableListOf<File>()
        for ((_, directory) in dateDirectories.filterNot { it.second in expiredSet }) {
            if (projectedUsableBytes >= minimumFreeBytes) break
            // mp4 文件名 HH-mm-ss，字典序即时间序
            val files = directory.listFiles { it.isFile && it.extension.equals("mp4", true) }
                ?.sortedBy { it.nameWithoutExtension }
                .orEmpty()
            for (file in files) {
                pressureFiles.add(file)
                projectedUsableBytes += file.length()
                if (projectedUsableBytes >= minimumFreeBytes) break
            }
        }
        return DeletionPlan(expired, pressureFiles)
    }

    private fun File.parseDateDirectory(): LocalDate? =
        runCatching { LocalDate.parse(name, directoryFormatter) }.getOrNull()

    private fun File.sizeBytes(): Long {
        if (isFile) return length()
        return walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
}

data class DeletionPlan(
    val directories: List<File>,
    val files: List<File>,
)
