package com.zx.homecamera.core.storage

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class RecordingRetentionPolicy(
    private val keepDays: Long = 7,
    private val minimumFreeBytes: Long = 512L * 1024L * 1024L,
) {
    private val directoryFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun directoriesToDelete(
        root: File,
        today: LocalDate,
        usableBytes: Long,
    ): List<File> {
        val dateDirectories = root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { file -> file.parseDateDirectory()?.let { date -> date to file } }
            ?.sortedBy { (date, _) -> date }
            .orEmpty()

        val oldestKeptDate = today.minusDays(keepDays - 1)
        val expired = dateDirectories
            .filter { (date, _) -> date.isBefore(oldestKeptDate) }
            .map { (_, file) -> file }

        val expiredSet = expired.toSet()
        var projectedUsableBytes = usableBytes + expired.sumOf { it.sizeBytes() }
        if (projectedUsableBytes >= minimumFreeBytes) return expired

        val pressureDeletions = dateDirectories
            .asSequence()
            .map { (_, file) -> file }
            .filterNot { it in expiredSet }
            .takeWhileInclusive { file ->
                val shouldTake = projectedUsableBytes < minimumFreeBytes
                if (shouldTake) projectedUsableBytes += file.sizeBytes()
                shouldTake
            }
            .toList()

        return expired + pressureDeletions
    }

    private fun File.parseDateDirectory(): LocalDate? =
        runCatching { LocalDate.parse(name, directoryFormatter) }.getOrNull()

    private fun File.sizeBytes(): Long {
        if (isFile) return length()
        return walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun <T> Sequence<T>.takeWhileInclusive(predicate: (T) -> Boolean): Sequence<T> =
        sequence {
            for (item in this@takeWhileInclusive) {
                if (!predicate(item)) break
                yield(item)
            }
        }
}
