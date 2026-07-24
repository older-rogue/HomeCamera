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

    @Test
    fun deletesOldestFilesWhenUnderSpacePressure() {
        val root = temporaryFolder.newFolder("recordings")
        // 过期目录
        val expired = root.resolve("2026-06-01").also { check(it.mkdir()) }
        expired.resolve("00-00-00.mp4").writeBytes(ByteArray(40))
        // 未过期目录，两个文件
        val keptDir = root.resolve("2026-06-08").also { check(it.mkdir()) }
        val oldestFile = keptDir.resolve("00-00-00.mp4").also { it.writeBytes(ByteArray(30)) }
        val newerFile = keptDir.resolve("01-00-00.mp4").also { it.writeBytes(ByteArray(30)) }

        // usableBytes=45，删过期(40)后投影=85，阈值100：删 00-00-00(30)->115 达标
        val cleaner = RecordingStorageCleaner(
            policy = RecordingRetentionPolicy(keepDays = 7, minimumFreeBytes = 100),
        )
        val result = cleaner.clean(root, today = LocalDate.of(2026, 6, 8), usableBytes = 45)

        assertTrue(result.deletedDirectories.contains(expired))
        assertFalse(expired.exists())
        // 最旧文件被删除
        assertTrue(result.deletedFiles.contains(oldestFile))
        assertFalse(oldestFile.exists())
        // 较新文件保留
        assertTrue(newerFile.exists())
        assertTrue(keptDir.exists())
    }
}
