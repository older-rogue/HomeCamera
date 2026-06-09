package com.zx.homecamera.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.zx.homecamera.audio.AacAudioConfig
import com.zx.homecamera.core.protocol.EncodedMediaFrame
import com.zx.homecamera.core.protocol.MediaCodecType
import com.zx.homecamera.core.protocol.MediaFrameReassembler
import com.zx.homecamera.core.protocol.MediaTrack
import com.zx.homecamera.core.protocol.MediaUdpPacket
import java.nio.ByteBuffer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class H264UdpViewer {
    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null
    private var socket: DatagramSocket? = null
    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var audioEnabled = false
    private var audioDecoderConfigured = false
    private var audioSampleRate = AacAudioConfig.SAMPLE_RATE
    private var audioChannelCount = AacAudioConfig.CHANNEL_COUNT

    fun start(
        connection: ViewerConnection,
        surface: Surface,
        onFirstFrame: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        running.set(true)
        executor = Executors.newSingleThreadExecutor().also { pool ->
            pool.execute {
                runCatching {
                    receiveAndDecode(connection, surface, onFirstFrame)
                }.onFailure { error ->
                    if (running.get()) {
                        onError(error.message ?: "实时视频接收异常")
                    }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        socket = null
        videoDecoder?.runCatching { stop() }
        videoDecoder?.release()
        videoDecoder = null
        audioDecoder?.runCatching { stop() }
        audioDecoder?.release()
        audioDecoder = null
        audioTrack?.runCatching { stop() }
        audioTrack?.runCatching { release() }
        audioTrack = null
        audioEnabled = false
        audioDecoderConfigured = false
        executor?.shutdownNow()
        executor = null
    }

    private fun receiveAndDecode(
        connection: ViewerConnection,
        surface: Surface,
        onFirstFrame: () -> Unit,
    ) {
        val mediaFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            connection.streamWidth,
            connection.streamHeight,
        )
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoDecoder = codec
        codec.configure(mediaFormat, surface, null, 0)
        codec.start()
        startAudioPlayback(connection)

        val reassembler = MediaFrameReassembler()
        val buffer = ByteArray(MediaUdpPacket.DEFAULT_MAX_DATAGRAM_SIZE)
        var firstFrameRendered = false
        var lastPacketAtMillis = System.currentTimeMillis()
        DatagramSocket(LanViewerConnector.CLIENT_UDP_PORT).use { udpSocket ->
            socket = udpSocket
            udpSocket.soTimeout = 1_000
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udpSocket.receive(packet)
                    lastPacketAtMillis = System.currentTimeMillis()
                    val streamPacket = MediaUdpPacket.decode(packet.data, packet.length) ?: continue
                    val frame = reassembler.accept(streamPacket) ?: continue
                    when (frame.track) {
                        MediaTrack.Video -> {
                            if (frame.codec == MediaCodecType.H264) {
                                queueVideoFrame(codec, frame.data, frame.timestampMicros, frame.flags)
                                if (drainVideoDecoder(codec) && !firstFrameRendered) {
                                    firstFrameRendered = true
                                    onFirstFrame()
                                }
                            }
                        }

                        MediaTrack.Audio -> {
                            if (frame.codec == MediaCodecType.Aac) {
                                handleAudioFrame(frame)
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    drainVideoDecoder(codec)
                    drainAudioDecoder()
                    if (System.currentTimeMillis() - lastPacketAtMillis > STREAM_STALL_TIMEOUT_MILLIS) {
                        throw IllegalStateException("实时视频流已中断，正在重连")
                    }
                } catch (_: SocketException) {
                    if (running.get()) throw IllegalStateException("UDP 视频端口异常")
                }
            }
        }
    }

    private fun queueVideoFrame(codec: MediaCodec, data: ByteArray, timestampMicros: Long, flags: Int) {
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return

        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(data)
        val codecFlags = when {
            flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0 -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            flags and MediaUdpPacket.FLAG_KEY_FRAME != 0 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
            else -> 0
        }
        codec.queueInputBuffer(inputIndex, 0, data.size, timestampMicros, codecFlags)
    }

    private fun drainVideoDecoder(codec: MediaCodec): Boolean {
        val bufferInfo = MediaCodec.BufferInfo()
        var rendered = false
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputIndex >= 0 -> {
                    val shouldRender = bufferInfo.size > 0
                    codec.releaseOutputBuffer(outputIndex, shouldRender)
                    rendered = rendered || shouldRender
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return rendered
            }
        }
    }

    private fun startAudioPlayback(connection: ViewerConnection) {
        audioEnabled = connection.audioEnabled && connection.audioCodec == AacAudioConfig.CODEC
        if (!audioEnabled) return

        runCatching {
            audioSampleRate = connection.audioSampleRate
            audioChannelCount = connection.audioChannelCount
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
            audioTrack = track
            track.play()
        }.onFailure {
            Log.w(TAG, "Audio playback disabled during setup", it)
            disableAudioPlayback()
        }
    }

    private fun handleAudioFrame(frame: EncodedMediaFrame) {
        if (!audioEnabled) return
        runCatching {
            val isCodecConfig = frame.flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0
            if (isCodecConfig) {
                configureAudioDecoder(frame.data)
                return
            }
            if (!audioDecoderConfigured) return

            val decoder = audioDecoder ?: return
            val inputIndex = decoder.dequeueInputBuffer(
                AUDIO_INPUT_TIMEOUT_MICROS,
            )
            if (inputIndex < 0) return
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(frame.data)
                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    frame.data.size,
                    frame.timestampMicros,
                    0,
                )
            }
            drainAudioDecoder()
        }.onFailure {
            Log.w(TAG, "Audio playback disabled after decode failure", it)
            disableAudioPlayback()
        }
    }

    private fun configureAudioDecoder(codecConfig: ByteArray) {
        if (audioDecoderConfigured) return
        runCatching {
            val decoderFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                audioSampleRate,
                audioChannelCount,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setByteBuffer(CSD_0_KEY, ByteBuffer.wrap(codecConfig))
            }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioDecoder = decoder
            decoder.configure(decoderFormat, null, null, 0)
            decoder.start()
            audioDecoderConfigured = true
            Log.i(TAG, "AAC decoder configured from codec config: ${codecConfig.size} bytes")
        }.onFailure {
            Log.w(TAG, "Audio playback disabled after AAC codec config failure", it)
            disableAudioPlayback()
        }
    }

    private fun drainAudioDecoder() {
        if (!audioEnabled) return
        if (!audioDecoderConfigured) return
        val decoder = audioDecoder ?: return
        val track = audioTrack ?: return
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
            disableAudioPlayback()
        }
    }

    private fun disableAudioPlayback() {
        audioEnabled = false
        audioDecoder?.runCatching { stop() }
        audioDecoder?.runCatching { release() }
        audioDecoder = null
        audioTrack?.runCatching { stop() }
        audioTrack?.runCatching { release() }
        audioTrack = null
        audioDecoderConfigured = false
    }

    companion object {
        private const val TAG = "H264UdpViewer"
        private const val CSD_0_KEY = "csd-0"
        private const val STREAM_STALL_TIMEOUT_MILLIS = 3_000L
        private const val AUDIO_INPUT_TIMEOUT_MICROS = 10_000L
    }
}
