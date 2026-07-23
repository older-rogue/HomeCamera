package com.zx.homecamera.network

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import android.view.Surface
import com.zx.homecamera.audio.AacAudioConfig
import com.zx.homecamera.core.protocol.ControlMessage
import com.zx.homecamera.core.protocol.ControlProtocol
import com.zx.homecamera.core.protocol.EncodedMediaFrame
import com.zx.homecamera.core.protocol.MediaCodecType
import com.zx.homecamera.core.protocol.MediaFrameReassembler
import com.zx.homecamera.core.protocol.MediaTrack
import com.zx.homecamera.core.protocol.MediaUdpPacket
import java.io.BufferedWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class H264UdpViewer {
    private val currentSession = AtomicReference<ViewerSession?>()

    fun start(
        context: Context,
        connection: ViewerConnection,
        surface: Surface,
        onFirstFrame: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        val videoQueue = RealtimeVideoFrameQueue(
            onKeyFrameNeeded = {
                currentSession.get()?.let { session -> requestKeyFrame(session, "video_queue_drop") }
            },
        )
        val audioQueue = ArrayBlockingQueue<EncodedMediaFrame>(AUDIO_QUEUE_CAPACITY)
        val rawPacketQueue = ConcurrentLinkedQueue<ByteArray>()
        val udpSocket = connection.udpSocket ?: DatagramSocket(LanViewerConnector.CLIENT_UDP_PORT)
        val controlWriter = connection.controlSocket?.getOutputStream()?.bufferedWriter(Charsets.UTF_8)
        val executor = Executors.newFixedThreadPool(5)
        val session = ViewerSession(
            connection = connection,
            executor = executor,
            udpSocket = udpSocket,
            controlSocket = connection.controlSocket,
            controlWriter = controlWriter,
            videoQueue = videoQueue,
            audioQueue = audioQueue,
            rawPacketQueue = rawPacketQueue,
            appContext = context.applicationContext,
        )
        currentSession.set(session)

        executor.executeCatching(session, onError) {
            // receive 线程设为最高优先级，确保 socket.receive() 不被其他线程抢占，
            // 避免内核 socket buffer 溢出导致丢包。
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            receiveFrames(session)
        }
        executor.executeCatching(session, onError) {
            reassembleFrames(session)
        }
        executor.executeCatching(session, onError) {
            decodeVideoFrames(session, surface, onFirstFrame)
        }
        executor.executeCatching(session, onError) {
            decodeAudioFrames(session)
        }
        executor.executeCatching(session, onError) {
            sendHeartbeats(session)
        }
    }

    fun stop() {
        val session = currentSession.getAndSet(null) ?: return
        session.stop(sendBye = true)
    }

    /**
     * 接收线程：只做 socket.receive() + 拷贝到独立 ByteArray + 入无锁队列。
     * 不做 decode/reassemble/offer，不持任何锁，确保 receive() 以最高频率被调用，
     * 避免内核 socket buffer 因来不及取包而溢出丢包。
     */
    private fun receiveFrames(session: ViewerSession) {
        val buffer = ByteArray(MediaUdpPacket.DEFAULT_MAX_DATAGRAM_SIZE)
        var lastPacketAtNanos = System.nanoTime()
        var lastStatsLogAtMillis = System.currentTimeMillis()
        var receivedPackets = 0L
        session.udpSocket.use { udpSocket ->
            udpSocket.receiveBufferSize = UDP_SOCKET_BUFFER_BYTES
            udpSocket.soTimeout = UDP_RECEIVE_TIMEOUT_MILLIS.toInt()
            while (session.running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udpSocket.receive(packet)
                    val packetNanos = System.nanoTime()
                    val packetIntervalMs = (packetNanos - lastPacketAtNanos) / 1_000_000.0
                    lastPacketAtNanos = packetNanos
                    session.lastPacketAtMillis.set(System.currentTimeMillis())
                    // 拷贝到独立数组后立即入队，让 receive 循环尽快回到下一次 receive()。
                    val copy = packet.data.copyOf(packet.length)
                    session.rawPacketQueue.offer(copy)
                    receivedPackets++
                    if (packetIntervalMs > 50.0) {
                        Log.w(TAG, "udp recv gap: %.0fms".format(packetIntervalMs))
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastStatsLogAtMillis >= STATS_LOG_INTERVAL_MILLIS) {
                        Log.i(TAG, "udp receiver: packets=$receivedPackets queue=${session.rawPacketQueue.size}")
                        receivedPackets = 0L
                        lastStatsLogAtMillis = now
                    }
                } catch (_: SocketTimeoutException) {
                    if (System.currentTimeMillis() - session.lastPacketAtMillis.get() > STREAM_STALL_TIMEOUT_MILLIS) {
                        throw IllegalStateException("实时视频流已中断，正在重连")
                    }
                } catch (_: SocketException) {
                    if (session.running.get()) throw IllegalStateException("UDP 视频端口异常")
                }
            }
        }
    }

    /**
     * 重组线程：从无锁队列取原始字节 -> decode -> reassemble -> offer 到 videoQueue/audioQueue。
     * 与 receive 线程分离，避免 decode/reassemble 的 HashMap/数组拷贝阻塞 receive。
     */
    private fun reassembleFrames(session: ViewerSession) {
        val reassembler = MediaFrameReassembler()
        var completedVideoFrames = 0L
        var completedAudioFrames = 0L
        var incompleteFragments = 0L
        var lastStatsLogAtMillis = System.currentTimeMillis()
        while (session.running.get()) {
            val raw = session.rawPacketQueue.poll()
            if (raw == null) {
                // 队列空时短暂休眠，避免空转占 CPU
                Thread.sleep(1L)
                continue
            }
            val streamPacket = MediaUdpPacket.decode(raw, raw.size) ?: continue
            val frame = reassembler.accept(streamPacket)
            if (frame == null) {
                incompleteFragments++
                continue
            }
            when (frame.track) {
                MediaTrack.Video -> if (frame.codec == MediaCodecType.H264) {
                    completedVideoFrames++
                    session.videoQueue.offer(frame)
                }

                MediaTrack.Audio -> if (frame.codec == MediaCodecType.Aac) {
                    completedAudioFrames++
                    offerLatestAudioFrame(session.audioQueue, frame)
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastStatsLogAtMillis >= STATS_LOG_INTERVAL_MILLIS) {
                Log.i(TAG, "reassembler: video=$completedVideoFrames audio=$completedAudioFrames incomplete=$incompleteFragments queue=${session.rawPacketQueue.size}")
                completedVideoFrames = 0L
                completedAudioFrames = 0L
                incompleteFragments = 0L
                lastStatsLogAtMillis = now
            }
        }
    }

    private fun decodeVideoFrames(
        session: ViewerSession,
        surface: Surface,
        onFirstFrame: () -> Unit,
    ) {
        val mediaFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            session.connection.streamWidth,
            session.connection.streamHeight,
        )
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(mediaFormat, surface, null, 0)
            codec.start()
            var firstFrameRendered = false
            var queuedInputFrames = 0L
            var renderedFrames = 0L
            var lastStatsLogAtMillis = System.currentTimeMillis()
            var lastRenderAtNanos = System.nanoTime()
            while (session.running.get()) {
                val frame = session.videoQueue.poll(VIDEO_QUEUE_POLL_TIMEOUT_MILLIS)
                if (frame != null) {
                    if (queueVideoFrame(session, codec, frame.data, frame.timestampMicros, frame.flags)) {
                        queuedInputFrames++
                    } else {
                        Log.w(TAG, "decoder input buffer full, frame dropped")
                    }
                }
                val drainedFrames = drainVideoDecoder(codec)
                if (drainedFrames > 0) {
                    val renderNanos = System.nanoTime()
                    val renderIntervalMs = (renderNanos - lastRenderAtNanos) / 1_000_000.0
                    lastRenderAtNanos = renderNanos
                    renderedFrames += drainedFrames
                    if (renderIntervalMs > 100.0) {
                        Log.w(TAG, "render gap: %.0fms drained=$drainedFrames".format(renderIntervalMs))
                    }
                }
                if (drainedFrames > 0 && !firstFrameRendered) {
                    firstFrameRendered = true
                    onFirstFrame()
                }
                val now = System.currentTimeMillis()
                if (now - lastStatsLogAtMillis >= STATS_LOG_INTERVAL_MILLIS) {
                    Log.i(TAG, "video decoder: input=$queuedInputFrames rendered=$renderedFrames")
                    queuedInputFrames = 0L
                    renderedFrames = 0L
                    lastStatsLogAtMillis = now
                }
                if (System.currentTimeMillis() - session.lastPacketAtMillis.get() > STREAM_STALL_TIMEOUT_MILLIS) {
                    throw IllegalStateException("实时视频流已中断，正在重连")
                }
            }
        } finally {
            codec.runCatching { stop() }
            codec.runCatching { release() }
        }
    }

    private fun decodeAudioFrames(session: ViewerSession) {
        try {
            startAudioPlayback(session)
            while (session.running.get()) {
                val frame = session.audioQueue.poll(AUDIO_QUEUE_POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    handleAudioFrame(session, frame)
                } else {
                    drainAudioDecoder(session)
                }
            }
        } finally {
            disableAudioPlayback(session)
        }
    }

    private fun offerLatestAudioFrame(
        audioQueue: ArrayBlockingQueue<EncodedMediaFrame>,
        frame: EncodedMediaFrame,
    ) {
        if (audioQueue.offer(frame)) return
        if (frame.flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0) {
            audioQueue.clear()
            audioQueue.offer(frame)
            return
        }
        while (!audioQueue.offer(frame)) {
            val dropped = audioQueue.poll() ?: return
            if (dropped.flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0) {
                audioQueue.offer(dropped)
                return
            }
        }
    }

    private fun requestKeyFrame(session: ViewerSession, reason: String) {
        runCatching {
            sendControlMessage(session, ControlMessage.RequestKeyFrame(reason))
        }.onFailure {
            Log.w(TAG, "Failed to request key frame", it)
        }
    }

    private fun sendHeartbeats(session: ViewerSession) {
        while (session.running.get()) {
            Thread.sleep(CONTROL_HEARTBEAT_INTERVAL_MILLIS)
            sendControlMessage(session, ControlMessage.Ping(System.currentTimeMillis()))
        }
    }

    private fun sendControlMessage(session: ViewerSession, message: ControlMessage) {
        val writer = session.controlWriter ?: return
        synchronized(writer) {
            writer.write(ControlProtocol.encode(message))
            writer.newLine()
            writer.flush()
        }
    }

    private fun ExecutorService.executeCatching(
        session: ViewerSession,
        onError: (String) -> Unit,
        block: () -> Unit,
    ) {
        execute {
            runCatching(block).onFailure { error ->
                if (currentSession.get() == session && session.running.get()) {
                    session.running.set(false)
                    session.udpSocket.close()
                    onError(error.message ?: "实时视频接收异常")
                }
            }
        }
    }

    private fun queueVideoFrame(
        session: ViewerSession,
        codec: MediaCodec,
        data: ByteArray,
        timestampMicros: Long,
        flags: Int,
    ): Boolean {
        val inputIndex = codec.dequeueInputBuffer(VIDEO_INPUT_TIMEOUT_MICROS)
        if (inputIndex < 0) return false

        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return false
        inputBuffer.clear()
        if (data.size > inputBuffer.remaining()) {
            codec.queueInputBuffer(inputIndex, 0, 0, timestampMicros, 0)
            requestKeyFrame(session, "decoder_input_too_large")
            return false
        }
        inputBuffer.put(data)
        val codecFlags = when {
            flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0 -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            flags and MediaUdpPacket.FLAG_KEY_FRAME != 0 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
            else -> 0
        }
        codec.queueInputBuffer(inputIndex, 0, data.size, timestampMicros, codecFlags)
        return true
    }

    private fun drainVideoDecoder(codec: MediaCodec): Int {
        val bufferInfo = MediaCodec.BufferInfo()
        var rendered = 0
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputIndex >= 0 -> {
                    val shouldRender = bufferInfo.size > 0
                    codec.releaseOutputBuffer(outputIndex, shouldRender)
                    if (shouldRender) rendered++
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return rendered
            }
        }
    }

    private fun startAudioPlayback(session: ViewerSession) {
        val connection = session.connection
        session.audioEnabled = connection.audioEnabled && connection.audioCodec == AacAudioConfig.CODEC
        if (!session.audioEnabled) return

        runCatching {
            session.audioSampleRate = connection.audioSampleRate
            session.audioChannelCount = connection.audioChannelCount
            enableSpeakerphone(session, true)
            val outputChannelMask = when (connection.audioChannelCount) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> AudioFormat.CHANNEL_OUT_MONO
            }
            val minBufferSize = AudioTrack.getMinBufferSize(
                connection.audioSampleRate,
                outputChannelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minBufferSize > 0) { "AudioTrack does not support AAC output config" }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(connection.audioSampleRate)
                        .setChannelMask(outputChannelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            session.audioTrack = track
            track.play()
        }.onFailure {
            Log.w(TAG, "Audio playback disabled during setup", it)
            disableAudioPlayback(session)
        }
    }

    private fun handleAudioFrame(session: ViewerSession, frame: EncodedMediaFrame) {
        if (!session.audioEnabled) return
        runCatching {
            val isCodecConfig = frame.flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0
            if (isCodecConfig) {
                configureAudioDecoder(session, frame.data)
                return
            }
            if (!session.audioDecoderConfigured) return

            val decoder = session.audioDecoder ?: return
            val inputIndex = decoder.dequeueInputBuffer(
                AUDIO_INPUT_TIMEOUT_MICROS,
            )
            if (inputIndex < 0) return
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                if (frame.data.size > inputBuffer.remaining()) {
                    decoder.queueInputBuffer(inputIndex, 0, 0, frame.timestampMicros, 0)
                    Log.w(TAG, "AAC input buffer too small, dropping frame size=${frame.data.size}")
                    return
                }
                inputBuffer.put(frame.data)
                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    frame.data.size,
                    frame.timestampMicros,
                    0,
                )
            }
            drainAudioDecoder(session)
        }.onFailure {
            Log.w(TAG, "Audio playback disabled after decode failure", it)
            disableAudioPlayback(session)
        }
    }

    private fun configureAudioDecoder(session: ViewerSession, codecConfig: ByteArray) {
        if (session.audioDecoderConfigured) return
        runCatching {
            val decoderFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                session.audioSampleRate,
                session.audioChannelCount,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setByteBuffer(CSD_0_KEY, ByteBuffer.wrap(codecConfig))
            }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            session.audioDecoder = decoder
            decoder.configure(decoderFormat, null, null, 0)
            decoder.start()
            session.audioDecoderConfigured = true
            Log.i(TAG, "AAC decoder configured from codec config: ${codecConfig.size} bytes")
        }.onFailure {
            Log.w(TAG, "Audio playback disabled after AAC codec config failure", it)
            disableAudioPlayback(session)
        }
    }

    private fun drainAudioDecoder(session: ViewerSession) {
        if (!session.audioEnabled) return
        if (!session.audioDecoderConfigured) return
        val decoder = session.audioDecoder ?: return
        val track = session.audioTrack ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        runCatching {
            while (true) {
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            track.write(outputBuffer, bufferInfo.size, AudioTrack.WRITE_NON_BLOCKING)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                }
            }
        }.onFailure {
            Log.w(TAG, "Audio playback disabled after drain failure", it)
            disableAudioPlayback(session)
        }
    }

    private fun disableAudioPlayback(session: ViewerSession) {
        session.audioEnabled = false
        session.audioDecoder?.runCatching { stop() }
        session.audioDecoder?.runCatching { release() }
        session.audioDecoder = null
        session.audioTrack?.runCatching { stop() }
        session.audioTrack?.runCatching { release() }
        session.audioTrack = null
        session.audioDecoderConfigured = false
        enableSpeakerphone(session, false)
    }

    /**
     * 开启/关闭免提（扬声器外放）。播放端走媒体音量 + 扬声器，音量更大；
     * 回声仍由采集端 AEC 消除。
     */
    private fun enableSpeakerphone(session: ViewerSession, enabled: Boolean) {
        runCatching {
            val audioManager = session.appContext.getSystemService(AudioManager::class.java) ?: return@runCatching
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = enabled
            Log.i(TAG, "speakerphone enabled=$enabled")
        }.onFailure {
            Log.w(TAG, "Failed to set speakerphone=$enabled", it)
        }
    }

    private inner class ViewerSession(
        val connection: ViewerConnection,
        val executor: ExecutorService,
        val udpSocket: DatagramSocket,
        val controlSocket: Socket?,
        val controlWriter: BufferedWriter?,
        val videoQueue: RealtimeVideoFrameQueue,
        val audioQueue: ArrayBlockingQueue<EncodedMediaFrame>,
        val rawPacketQueue: ConcurrentLinkedQueue<ByteArray>,
        val appContext: Context,
    ) {
        val running = AtomicBoolean(true)
        val lastPacketAtMillis = AtomicLong(System.currentTimeMillis())
        @Volatile
        var audioEnabled = false
        @Volatile
        var audioDecoderConfigured = false
        var audioSampleRate = AacAudioConfig.SAMPLE_RATE
        var audioChannelCount = AacAudioConfig.CHANNEL_COUNT
        var audioDecoder: MediaCodec? = null
        var audioTrack: AudioTrack? = null

        fun stop(sendBye: Boolean) {
            running.set(false)
            if (sendBye) {
                runCatching { sendControlMessage(this, ControlMessage.Bye("viewer_stop")) }
            }
            udpSocket.runCatching { close() }
            videoQueue.clear()
            audioQueue.clear()
            rawPacketQueue.clear()
            controlSocket?.runCatching { close() }
            executor.shutdownNow()
            executor.runCatching { awaitTermination(WORKER_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
            resetAudioMode()
        }

        private fun resetAudioMode() {
            runCatching {
                val audioManager = appContext.getSystemService(AudioManager::class.java) ?: return@runCatching
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    companion object {
        private const val TAG = "H264UdpViewer"
        private const val CSD_0_KEY = "csd-0"
        private const val STREAM_STALL_TIMEOUT_MILLIS = 3_000L
        private const val VIDEO_INPUT_TIMEOUT_MICROS = 1_000L
        private const val AUDIO_INPUT_TIMEOUT_MICROS = 10_000L
        private const val UDP_RECEIVE_TIMEOUT_MILLIS = 1_000L
        private const val VIDEO_QUEUE_POLL_TIMEOUT_MILLIS = 20L
        private const val AUDIO_QUEUE_POLL_TIMEOUT_MILLIS = 20L
        private const val AUDIO_QUEUE_CAPACITY = 16
        private const val WORKER_STOP_TIMEOUT_MILLIS = 500L
        private const val CONTROL_HEARTBEAT_INTERVAL_MILLIS = 5_000L
        private const val UDP_SOCKET_BUFFER_BYTES = 1_048_576
        private const val STATS_LOG_INTERVAL_MILLIS = 1_000L
    }
}
