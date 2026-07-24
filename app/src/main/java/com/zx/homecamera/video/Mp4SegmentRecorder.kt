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

class Mp4SegmentRecorder(
    private val root: File,
    private val planner: RecordingFilePlanner = RecordingFilePlanner(root),
    private val clock: RecordingSegmentClock = RecordingSegmentClock(
        RecordingFilePlanner.SEGMENT_DURATION_MILLIS * 1_000L,
    ),
    private val onError: (Throwable) -> Unit = {},
    private val onSegmentStarted: () -> Unit = {},
) {
    private var videoOutputFormat: MediaFormat? = null
    private var audioOutputFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    /**
     * 音视频各自独立的 segment 基准 PTS。视频基准取首个视频关键帧 PTS，
     * 音频基准取 segment 内首个音频帧 PTS。这样无论两者时钟源是否一致，
     * 每个轨道内部相对时间戳都从 0 开始单调递增，MediaMuxer 能正确交错音视频。
     */
    private var videoSegmentBaseMicros: Long = 0L
    private var audioSegmentBaseMicros: Long = -1L
    private var audioBaseCaptured = false
    private var currentSegmentFile: File? = null
    private val segmentCounter = AtomicInteger(0)
    private var lastErrorReportAtMillis = 0L
    private var firstAudioSampleLogged = false
    private var firstVideoSampleLogged = false

    /**
     * 写入 mp4 容器的旋转标记（0/90/180/270）。由采集端根据 sensorOrientation 与
     * displayRotation 的相对旋转设置，使播放器/相册按正确朝向显示。默认 0（不旋转）。
     */
    @Volatile
    private var orientationHintDegrees: Int = 0

    /**
     * 设置录像文件的旋转标记，对后续新开的 segment 生效。
     */
    fun setOrientationHint(degrees: Int) {
        orientationHintDegrees = degrees
    }

    @Synchronized
    fun onOutputFormatChanged(format: MediaFormat) {
        videoOutputFormat = MediaFormat(format)
    }

    /**
     * 保存音频编码器输出格式。若当前 segment 已经以纯视频方式开启，则关闭它，
     * 等待下一个视频关键帧重新开启一个含音频轨道的 segment，避免写出中途追加轨道的非法 mp4。
     */
    @Synchronized
    fun onAudioOutputFormatChanged(format: MediaFormat) {
        audioOutputFormat = MediaFormat(format)
        if (muxer != null && audioTrackIndex < 0) {
            closeCurrentSegment()
        }
    }

    @Synchronized
    fun writeSample(data: ByteArray, flags: Int, timestampMicros: Long) {
        runCatching {
            writeSampleOrThrow(data, flags, timestampMicros)
        }.onFailure(::reportError)
    }

    @Synchronized
    fun writeAudioSample(data: ByteArray, flags: Int, timestampMicros: Long) {
        runCatching {
            writeAudioSampleOrThrow(data, flags, timestampMicros)
        }.onFailure(::reportError)
    }

    private fun writeSampleOrThrow(data: ByteArray, flags: Int, timestampMicros: Long) {
        if (flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0) return

        val isKeyFrame = flags and MediaUdpPacket.FLAG_KEY_FRAME != 0
        if (clock.shouldStartSegment(timestampMicros)) {
            if (!isKeyFrame || videoOutputFormat == null) return
            startSegment(timestampMicros)
        } else if (clock.shouldRotate(timestampMicros, isKeyFrame)) {
            closeCurrentSegment()
            startSegment(timestampMicros)
        }

        val activeMuxer = muxer ?: return
        val relativePts = sampleTimestampMicros(timestampMicros, videoSegmentBaseMicros)
        if (!firstVideoSampleLogged) {
            firstVideoSampleLogged = true
            Log.i(
                TAG,
                "first video sample: rawPts=$timestampMicros base=$videoSegmentBaseMicros " +
                    "relativePts=$relativePts isKeyFrame=$isKeyFrame",
            )
        }
        val info = MediaCodec.BufferInfo().apply {
            set(
                0,
                data.size,
                relativePts,
                if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )
        }
        activeMuxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(data), info)
    }

    private fun writeAudioSampleOrThrow(data: ByteArray, flags: Int, timestampMicros: Long) {
        if (flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0) return
        val activeMuxer = muxer ?: return
        if (audioTrackIndex < 0) return
        // 音频用独立基准：取 segment 内首个音频帧 PTS。
        if (!audioBaseCaptured) {
            audioSegmentBaseMicros = timestampMicros
            audioBaseCaptured = true
        }
        val relativePts = sampleTimestampMicros(timestampMicros, audioSegmentBaseMicros)
        if (!firstAudioSampleLogged) {
            firstAudioSampleLogged = true
            Log.i(
                TAG,
                "first audio sample: rawPts=$timestampMicros base=$audioSegmentBaseMicros " +
                    "relativePts=$relativePts trackIndex=$audioTrackIndex " +
                    "videoBase=$videoSegmentBaseMicros",
            )
        }
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, relativePts, 0)
        }
        activeMuxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(data), info)
    }

    @Synchronized
    fun stop() {
        closeCurrentSegment()
        clock.reset()
    }

    private fun startSegment(timestampMicros: Long) {
        val videoFormat = videoOutputFormat ?: return
        val audioFormat = audioOutputFormat
        val plannedFile = planner.nextSegmentFile(LocalDateTime.now())
        val file = plannedFile.uniqueSegmentFile()
        Log.i(
            TAG,
            "startSegment: videoPtsBase=$timestampMicros audioFormatPresent=${audioFormat != null} " +
                "file=${file.name}",
        )
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { newMuxer ->
            // setOrientationHint 必须在 addTrack/start 之前调用，写入容器旋转标记，
            // 让播放器/相册按采集端竖屏朝向显示。
            newMuxer.setOrientationHint(orientationHintDegrees)
            videoTrackIndex = newMuxer.addTrack(videoFormat)
            audioTrackIndex = if (audioFormat != null) newMuxer.addTrack(audioFormat) else -1
            newMuxer.start()
        }
        currentSegmentFile = file
        videoSegmentBaseMicros = timestampMicros
        audioSegmentBaseMicros = -1L
        audioBaseCaptured = false
        clock.onSegmentStarted(timestampMicros)
        onSegmentStarted()
    }

    private fun closeCurrentSegment() {
        muxer?.runCatching { stop() }?.onFailure(::reportError)
        muxer?.runCatching { release() }?.onFailure(::reportError)
        muxer = null
        videoTrackIndex = -1
        audioTrackIndex = -1
        firstAudioSampleLogged = false
        firstVideoSampleLogged = false
        audioBaseCaptured = false
        audioSegmentBaseMicros = -1L
        currentSegmentFile = null
    }

    /**
     * 返回当前正在录制的文件相对录像根目录的路径（如 `2026-07-22/14-30-00.mp4`），
     * 没有活跃 segment 时返回 null。用于在录像列表里标记"录制中"的文件，
     * 让客户端禁用该文件的播放/下载（未 stop 的 mp4 缺少 moov，无法播放）。
     */
    @Synchronized
    fun currentRecordingFileId(): String? {
        val file = currentSegmentFile ?: return null
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar)
        val filePath = file.canonicalPath
        if (!filePath.startsWith(rootPath + File.separatorChar)) return null
        return filePath.substring(rootPath.length + 1).replace(File.separatorChar, '/')
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

        /**
         * 计算写入 mp4 的相对时间戳。负值兜底为 0，避免 MediaMuxer 拒绝负 PTS。
         */
        fun sampleTimestampMicros(timestampMicros: Long, segmentBaseTimestampMicros: Long): Long =
            (timestampMicros - segmentBaseTimestampMicros).coerceAtLeast(0L)
    }
}
