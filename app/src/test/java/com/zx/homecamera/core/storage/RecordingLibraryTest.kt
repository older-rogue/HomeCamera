package com.zx.homecamera.core.storage

import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingLibraryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun root() = temporaryFolder.newFolder("recordings")

    @Test
    fun listDatesReturnsDateDirectoriesSortedDescending() {
        val root = root()
        File(root, "2026-07-20").mkdirs()
        File(root, "2026-07-21").mkdirs()
        File(root, "2026-07-19").mkdirs()

        val dates = RecordingLibrary(root).listDates()

        assertEquals(listOf("2026-07-21", "2026-07-20", "2026-07-19"), dates)
    }

    @Test
    fun listDatesIgnoresNonDateDirectories() {
        val root = root()
        File(root, "2026-07-21").mkdirs()
        File(root, "thumbnails").mkdirs()
        File(root, "not-a-date").mkdirs()

        val dates = RecordingLibrary(root).listDates()

        assertEquals(listOf("2026-07-21"), dates)
    }

    @Test
    fun listDatesReturnsEmptyWhenRootMissing() {
        val missingRoot = File(temporaryFolder.root, "does-not-exist")
        assertEquals(emptyList<String>(), RecordingLibrary(missingRoot).listDates())
    }

    @Test
    fun listFilesReturnsMp4EntriesSortedByStartMillis() {
        val root = root()
        val dateDir = File(root, "2026-07-21").apply { mkdirs() }
        File(dateDir, "14-30-00.mp4").writeText("aaa")
        File(dateDir, "14-20-00.mp4").writeText("bb")
        File(dateDir, "14-10-00.mp4").writeText("c")

        val files = RecordingLibrary(root).listFiles("2026-07-21")

        assertEquals(3, files.size)
        // 按时间升序
        assertEquals("2026-07-21/14-10-00.mp4", files[0].fileId)
        assertEquals("2026-07-21/14-20-00.mp4", files[1].fileId)
        assertEquals("2026-07-21/14-30-00.mp4", files[2].fileId)
        assertTrue(files[0].startMillis < files[1].startMillis)
        assertTrue(files[1].startMillis < files[2].startMillis)
        assertEquals(1L, files[0].sizeBytes)
        assertEquals(2L, files[1].sizeBytes)
        assertEquals(3L, files[2].sizeBytes)
    }

    @Test
    fun listFilesIgnoresNonMp4Files() {
        val root = root()
        val dateDir = File(root, "2026-07-21").apply { mkdirs() }
        File(dateDir, "14-30-00.mp4").writeText("aaa")
        File(dateDir, "notes.txt").writeText("ignore")

        val files = RecordingLibrary(root).listFiles("2026-07-21")

        assertEquals(1, files.size)
        assertEquals("2026-07-21/14-30-00.mp4", files[0].fileId)
    }

    @Test
    fun listFilesReturnsEmptyForMissingDate() {
        val root = root()
        assertEquals(emptyList<RecordingFileEntry>(), RecordingLibrary(root).listFiles("2026-07-21"))
    }

    @Test
    fun startMillisMatchesLocalEpochForFileName() {
        val root = root()
        val dateDir = File(root, "2026-07-21").apply { mkdirs() }
        File(dateDir, "14-30-00.mp4").writeText("a")

        val entry = RecordingLibrary(root).listFiles("2026-07-21").single()

        val expected = java.time.LocalDateTime
            .parse("2026-07-21T14:30:00", java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, entry.startMillis)
    }

    @Test
    fun listFilesMarksRecordingEntriesWhenFileIdMatches() {
        val root = root()
        val dateDir = File(root, "2026-07-21").apply { mkdirs() }
        File(dateDir, "14-30-00.mp4").writeText("a")
        File(dateDir, "14-40-00.mp4").writeText("b")

        val files = RecordingLibrary(root).listFiles(
            date = "2026-07-21",
            recordingFileIds = setOf("2026-07-21/14-40-00.mp4"),
        )

        assertEquals(2, files.size)
        assertEquals(false, files[0].recording)
        assertEquals(true, files[1].recording)
    }

    @Test
    fun listFilesWithoutRecordingIdsDefaultsAllToNotRecording() {
        val root = root()
        val dateDir = File(root, "2026-07-21").apply { mkdirs() }
        File(dateDir, "14-30-00.mp4").writeText("a")

        val files = RecordingLibrary(root).listFiles("2026-07-21")

        assertEquals(1, files.size)
        assertEquals(false, files[0].recording)
    }
}
