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
        val plan = policy.deletionPlan(root, today, usableBytes)
        val deletedDirectories = plan.directories.filter { it.deleteRecursively() }
        val deletedFiles = plan.files.filter { it.delete() }
        return CleanResult(
            deletedDirectories = deletedDirectories,
            deletedFiles = deletedFiles,
        )
    }
}

data class CleanResult(
    val deletedDirectories: List<File>,
    val deletedFiles: List<File> = emptyList(),
)
