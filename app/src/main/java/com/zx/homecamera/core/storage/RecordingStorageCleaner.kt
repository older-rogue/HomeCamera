package com.zx.homecamera.core.storage

import java.io.File
import java.time.LocalDate

class RecordingStorageCleaner(
    private val policy: RecordingRetentionPolicy = RecordingRetentionPolicy(),
) {
    /**
     * @param excludeFiles 不参与删除的文件（如正在录制的片段），见
     * [RecordingRetentionPolicy.deletionPlan]。
     */
    fun clean(
        root: File,
        today: LocalDate,
        usableBytes: Long,
        excludeFiles: Set<File> = emptySet(),
    ): CleanResult {
        val plan = policy.deletionPlan(root, today, usableBytes, excludeFiles)
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
