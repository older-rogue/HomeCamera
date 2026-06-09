package com.zx.homecamera.core.storage

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingRetentionPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deletesDateDirectoriesOlderThanRetentionWindow() {
        val root = temporaryFolder.newFolder("recordings")
        val oldest = root.dateDirectory("2026-05-31", bytes = 12)
        val old = root.dateDirectory("2026-06-01", bytes = 12)
        root.dateDirectory("2026-06-02", bytes = 12)
        root.dateDirectory("2026-06-08", bytes = 12)
        root.mkdir("misc")

        val deletions = RecordingRetentionPolicy(keepDays = 7, minimumFreeBytes = 100)
            .directoriesToDelete(
                root = root,
                today = LocalDate.of(2026, 6, 8),
                usableBytes = 500,
            )

        assertEquals(listOf(oldest, old), deletions)
    }

    @Test
    fun deletesOldestRemainingDateDirectoriesUntilFreeSpaceIsSafe() {
        val root = temporaryFolder.newFolder("recordings")
        val expired = root.dateDirectory("2026-06-01", bytes = 40)
        val first = root.dateDirectory("2026-06-02", bytes = 30)
        val second = root.dateDirectory("2026-06-03", bytes = 30)
        root.dateDirectory("2026-06-04", bytes = 80)
        root.dateDirectory("2026-06-08", bytes = 80)

        val deletions = RecordingRetentionPolicy(keepDays = 7, minimumFreeBytes = 100)
            .directoriesToDelete(
                root = root,
                today = LocalDate.of(2026, 6, 8),
                usableBytes = 45,
            )

        assertEquals(listOf(expired, first), deletions)
    }

    private fun File.dateDirectory(name: String, bytes: Int): File {
        val directory = mkdir(name)
        File(directory, "clip.mp4").writeBytes(ByteArray(bytes))
        return directory
    }

    private fun File.mkdir(name: String): File =
        File(this, name).also { check(it.mkdir()) }
}
