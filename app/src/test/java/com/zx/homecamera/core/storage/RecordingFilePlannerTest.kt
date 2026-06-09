package com.zx.homecamera.core.storage

import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingFilePlannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createsDateDirectoryAndTimeBasedMp4File() {
        val root = temporaryFolder.newFolder("recordings")
        val planner = RecordingFilePlanner(root)

        val file = planner.nextSegmentFile(LocalDateTime.of(2026, 6, 8, 21, 35, 12))

        assertEquals(File(root, "2026-06-08/21-35-12.mp4"), file)
        assertTrue(File(root, "2026-06-08").isDirectory)
    }

    @Test
    fun segmentDurationIsTenMinutes() {
        assertEquals(10 * 60 * 1000L, RecordingFilePlanner.SEGMENT_DURATION_MILLIS)
    }
}
