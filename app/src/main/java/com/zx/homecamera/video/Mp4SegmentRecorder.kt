package com.zx.homecamera.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.zx.homecamera.core.protocol.MediaUdpPacket
import com.zx.homecamera.core.storage.RecordingFilePlanner
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class Mp4SegmentRecorder(
    private val root: File,
    private val planner: RecordingFilePlanner = RecordingFilePlanner(root),
    private val clock: RecordingSegmentClock = RecordingSegmentClock(
        RecordingFilePlanner.SEGMENT_DURATION_MILLIS * 1_000L,
    ),
    private val onError: (Throwable) -> Unit = {},
) {
    private var outputFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    private var segmentBaseTimestampMicros: Long = 0L
    private val segmentCounter = AtomicInteger(0)
    private var lastErrorReportAtMillis = 0L

    @Synchronized
    fun onOutputFormatChanged(format: MediaFormat) {
        outputFormat = MediaFormat(format)
    }

    @Synchronized
    fun writeSample(data: ByteArray, flags: Int, timestampMicros: Long) {
        runCatching {
            writeSampleOrThrow(data, flags, timestampMicros)
        }.onFailure(::reportError)
    }

    private fun writeSampleOrThrow(data: ByteArray, flags: Int, timestampMicros: Long) {
        if (flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0) return

        val isKeyFrame = flags and MediaUdpPacket.FLAG_KEY_FRAME != 0
        if (clock.shouldStartSegment(timestampMicros)) {
            if (!isKeyFrame || outputFormat == null) return
            startSegment(timestampMicros)
        } else if (clock.shouldRotate(timestampMicros, isKeyFrame)) {
            closeCurrentSegment()
            startSegment(timestampMicros)
        }

        val activeMuxer = muxer ?: return
        val info = MediaCodec.BufferInfo().apply {
            set(
                0,
                data.size,
                max(0L, timestampMicros - segmentBaseTimestampMicros),
                if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )
        }
        activeMuxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(data), info)
    }

    @Synchronized
    fun stop() {
        closeCurrentSegment()
        clock.reset()
    }

    private fun startSegment(timestampMicros: Long) {
        val format = outputFormat ?: return
        val plannedFile = planner.nextSegmentFile(LocalDateTime.now())
        val file = plannedFile.uniqueSegmentFile()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { newMuxer ->
            videoTrackIndex = newMuxer.addTrack(format)
            newMuxer.start()
        }
        segmentBaseTimestampMicros = timestampMicros
        clock.onSegmentStarted(timestampMicros)
    }

    private fun closeCurrentSegment() {
        muxer?.runCatching { stop() }?.onFailure(::reportError)
        muxer?.runCatching { release() }?.onFailure(::reportError)
        muxer = null
        videoTrackIndex = -1
    }

    private fun File.uniqueSegmentFile(): File {
        if (!exists()) return this
        while (true) {
            val suffix = segmentCounter.incrementAndGet()
            val candidate = File(parentFile, "$nameWithoutExtension-$suffix.$extension")
            if (!candidate.exists()) return candidate
        }
    }

    private fun reportError(error: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastErrorReportAtMillis < ERROR_REPORT_INTERVAL_MILLIS) return
        lastErrorReportAtMillis = now
        Log.w(TAG, "MP4 segment recorder error", error)
        onError(error)
    }

    companion object {
        private const val TAG = "Mp4SegmentRecorder"
        private const val ERROR_REPORT_INTERVAL_MILLIS = 5_000L
    }
}
