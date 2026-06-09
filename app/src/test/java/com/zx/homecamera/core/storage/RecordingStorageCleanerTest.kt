package com.zx.homecamera.core.storage

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingStorageCleanerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deletesDirectoriesSelectedByRetentionPolicy() {
        val root = temporaryFolder.newFolder("recordings")
        val expired = root.resolve("2026-06-01").also { check(it.mkdir()) }
        expired.resolve("clip.mp4").writeText("old")
        val current = root.resolve("2026-06-08").also { check(it.mkdir()) }
        current.resolve("clip.mp4").writeText("new")

        val cleaner = RecordingStorageCleaner(
            policy = RecordingRetentionPolicy(keepDays = 7, minimumFreeBytes = 1),
        )
        val result = cleaner.clean(root, today = LocalDate.of(2026, 6, 8), usableBytes = 500)

        assertTrue(result.deletedDirectories.contains(expired))
        assertFalse(expired.exists())
        assertTrue(current.exists())
    }
}
