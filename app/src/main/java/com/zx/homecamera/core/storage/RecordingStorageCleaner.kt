package com.zx.homecamera.core.storage

import java.io.File
import java.time.LocalDate

class RecordingStorageCleaner(
    private val policy: RecordingRetentionPolicy = RecordingRetentionPolicy(),
) {
    fun clean(
        root: File,
        today: LocalDate,
        usableBytes: Long,
    ): CleanResult {
        val deleted = policy.directoriesToDelete(root, today, usableBytes)
            .filter { it.deleteRecursively() }
        return CleanResult(deletedDirectories = deleted)
    }
}

data class CleanResult(
    val deletedDirectories: List<File>,
)
