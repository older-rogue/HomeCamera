package com.zx.homecamera.core.storage

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class RecordingFilePlanner(
    private val root: File,
) {
    fun nextSegmentFile(startTime: LocalDateTime): File {
        val dateDirectory = File(root, startTime.format(DATE_FORMATTER))
        check(dateDirectory.exists() || dateDirectory.mkdirs()) {
            "Unable to create recording directory: ${dateDirectory.absolutePath}"
        }
        return File(dateDirectory, "${startTime.format(TIME_FORMATTER)}.mp4")
    }

    companion object {
        const val SEGMENT_DURATION_MILLIS = 2 * 60 * 1000L
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH-mm-ss")
    }
}
