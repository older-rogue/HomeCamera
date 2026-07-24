package com.zx.homecamera.core.storage

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        val plan = RecordingRetentionPolicy(keepDays = 7, minimumFreeBytes = 100)
            .deletionPlan(
                root = root,
                today = LocalDate.of(2026, 6, 8),
                usableBytes = 500,
            )

        assertEquals(listOf(oldest, old), plan.directories)
        assertTrue(plan.files.isEmpty())
    }

    @Test
    fun deletesOldestFilesUntilFreeSpaceIsSafe() {
        val root = temporaryFolder.newFolder("recordings")
        // 过期目录（整天删）
        val expired = root.dateDirectory("2026-06-01", bytes = 40)
        // 未过期目录：每个目录里放多个 mp4，按 HH-mm-ss 命名（字典序即时间序）
        val firstDir = root.directoryWithFiles("2026-06-02", "00-00-00" to 20, "01-00-00" to 20)
        val secondDir = root.directoryWithFiles("2026-06-03", "00-00-00" to 20, "01-00-00" to 20)
        root.directoryWithFiles("2026-06-08", "00-00-00" to 20, "01-00-00" to 20)

        // usableBytes=45，删过期(40)后投影=85，仍不足100，需从未过期目录按最旧文件继续删
        // 06-02/00-00-00(20) -> 投影=105 >= 100，达标，该文件被包含删除（takeWhileInclusive 语义）
        val plan = RecordingRetentionPolicy(keepDays = 7, minimumFreeBytes = 100)
            .deletionPlan(
                root = root,
                today = LocalDate.of(2026, 6, 8),
                usableBytes = 45,
            )

        assertEquals(listOf(expired), plan.directories)
        assertEquals(listOf(firstDir.resolve("00-00-00.mp4")), plan.files)
        // 较新文件保留
        assertTrue(firstDir.resolve("01-00-00.mp4").exists())
        assertTrue(secondDir.resolve("00-00-00.mp4").exists())
    }

    @Test
    fun deletesFilesAcrossMultipleDirectoriesUntilFreeSpaceIsSafe() {
        val root = temporaryFolder.newFolder("recordings")
        root.dateDirectory("2026-06-01", bytes = 20) // 过期
        val firstDir = root.directoryWithFiles("2026-06-02", "00-00-00" to 30, "01-00-00" to 30)
        val secondDir = root.directoryWithFiles("2026-06-03", "00-00-00" to 30, "01-00-00" to 30)

        // usableBytes=10，删过期(20)后投影=30，阈值100：
        // 06-02/00-00-00(30)->60, 06-02/01-00-00(30)->90, 06-03/00-00-00(30)->120>=100 达标
        val plan = RecordingRetentionPolicy(keepDays = 7, minimumFreeBytes = 100)
            .deletionPlan(
                root = root,
                today = LocalDate.of(2026, 6, 8),
                usableBytes = 10,
            )

        assertEquals(
            listOf(
                firstDir.resolve("00-00-00.mp4"),
                firstDir.resolve("01-00-00.mp4"),
                secondDir.resolve("00-00-00.mp4"),
            ),
            plan.files,
        )
        // 达标目录里的较新文件保留
        assertTrue(secondDir.resolve("01-00-00.mp4").exists())
    }

    private fun File.dateDirectory(name: String, bytes: Int): File {
        val directory = mkdir(name)
        File(directory, "clip.mp4").writeBytes(ByteArray(bytes))
        return directory
    }

    private fun File.directoryWithFiles(
        dirName: String,
        vararg files: Pair<String, Int>,
    ): File {
        val directory = mkdir(dirName)
        files.forEach { (fileName, bytes) ->
            File(directory, "$fileName.mp4").writeBytes(ByteArray(bytes))
        }
        return directory
    }

    private fun File.mkdir(name: String): File =
        File(this, name).also { check(it.mkdir()) }
}
