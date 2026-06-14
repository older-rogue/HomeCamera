package com.zx.homecamera.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.zx.homecamera.core.protocol.MediaUdpPacket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AacAudioStreamer(
    private val context: Context,
    val config: AacAudioConfig = AacAudioConfig.Default,
) {
    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null
    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null

    fun start(
        onEvent: (EncodedAudioEvent) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            throw SecurityException("Microphone permission is required")
        }

        executor = Executors.newSingleThreadExecutor().also { pool ->
            pool.execute {
                runCatching {
                    captureAndEncode(onEvent)
                }.onFailure { error ->
                    if (running.get()) onError(error)
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        // 先停 executor，等线程退出后再释放 codec/recorder
        executor?.shutdownNow()
        executor?.awaitTermination(2_000, TimeUnit.MILLISECONDS)
        executor = null
        audioRecord?.runCatching { stop() }
        audioRecord?.runCatching { release() }
        audioRecord = null
        encoder?.runCatching { stop() }
        encoder?.runCatching { release() }
        encoder = null
    }

    @SuppressLint("MissingPermission")
    private fun captureAndEncode(onEvent: (EncodedAudioEvent) -> Unit) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBufferSize > 0) { "AudioRecord does not support AAC input config" }
        val readBufferSize = minBufferSize.coerceAtLeast(config.sampleRate / 10 * BYTES_PER_SAMPLE)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            readBufferSize * 2,
        )
        require(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize"
        }
        audioRecord = recorder

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            config.sampleRate,
            config.channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder = codec
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        recorder.startRecording()
        Log.i(TAG, "AAC audio capture started: ${config.sampleRate} Hz, ${config.channelCount} ch")

        val readBuffer = ByteArray(readBufferSize)
        val outputInfo = MediaCodec.BufferInfo()
        var submittedFrames = 0L
        try {
            while (running.get()) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    val timestampMicros = submittedFrames * 1_000_000L / config.sampleRate
                    if (inputBuffer == null) {
                        codec.queueInputBuffer(inputIndex, 0, 0, timestampMicros, 0)
                    } else {
                        inputBuffer.clear()
                        val readLimit = minOf(readBuffer.size, inputBuffer.remaining())
                        val read = recorder.read(readBuffer, 0, readLimit)
                        if (read <= 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, timestampMicros, 0)
                        } else {
                            inputBuffer.put(readBuffer, 0, read)
                            codec.queueInputBuffer(inputIndex, 0, read, timestampMicros, 0)
                            submittedFrames += read / BYTES_PER_SAMPLE / config.channelCount
                        }
                    }
                }
                drainEncoder(codec, outputInfo, onEvent)
            }
        } finally {
            drainEncoder(codec, outputInfo, onEvent)
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        outputInfo: MediaCodec.BufferInfo,
        onEvent: (EncodedAudioEvent) -> Unit,
    ) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(outputInfo, 0)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && outputInfo.size > 0) {
                        outputBuffer.position(outputInfo.offset)
                        outputBuffer.limit(outputInfo.offset + outputInfo.size)
                        val data = ByteArray(outputInfo.size)
                        outputBuffer.get(data)
                        val flags = when {
                            outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ->
                                MediaUdpPacket.FLAG_CODEC_CONFIG
                            else -> 0
                        }
                        onEvent(
                            EncodedAudioEvent.Sample(
                                data = data,
                                flags = flags,
                                timestampMicros = outputInfo.presentationTimeUs,
                            ),
                        )
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    Log.i(TAG, "AAC encoder output format changed: $format")
                    onEvent(EncodedAudioEvent.FormatChanged(format))
                    format.getByteBuffer(CSD_0_KEY)?.toByteArray()?.takeIf { it.isNotEmpty() }?.let { config ->
                        onEvent(
                            EncodedAudioEvent.Sample(
                                data = config,
                                flags = MediaUdpPacket.FLAG_CODEC_CONFIG,
                                timestampMicros = 0L,
                            ),
                        )
                    }
                }

                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            }
        }
    }

    companion object {
        private const val TAG = "AacAudioStreamer"
        private const val BYTES_PER_SAMPLE = 2
        private const val CSD_0_KEY = "csd-0"
    }
}

sealed interface EncodedAudioEvent {
    data class FormatChanged(val format: MediaFormat) : EncodedAudioEvent

    data class Sample(
        val data: ByteArray,
        val flags: Int,
        val timestampMicros: Long,
    ) : EncodedAudioEvent
}

private fun ByteBuffer.toByteArray(): ByteArray {
    val duplicate = duplicate()
    val bytes = ByteArray(duplicate.remaining())
    duplicate.get(bytes)
    return bytes
}
