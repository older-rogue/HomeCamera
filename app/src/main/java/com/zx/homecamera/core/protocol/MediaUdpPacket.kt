package com.zx.homecamera.core.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MediaTrack(val id: Int) {
    Video(1),
    Audio(2),
    ;

    companion object {
        fun fromId(id: Int): MediaTrack? = entries.firstOrNull { it.id == id }
    }
}

enum class MediaCodecType(val id: Int) {
    H264(1),
    Aac(2),
    ;

    companion object {
        fun fromId(id: Int): MediaCodecType? = entries.firstOrNull { it.id == id }
    }
}

data class MediaPacket(
    val track: MediaTrack,
    val codec: MediaCodecType,
    val sequenceNumber: Int,
    val timestampMicros: Long,
    val flags: Int,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val payload: ByteArray,
)

data class EncodedMediaFrame(
    val track: MediaTrack,
    val codec: MediaCodecType,
    val sequenceNumber: Int,
    val timestampMicros: Long,
    val flags: Int,
    val data: ByteArray,
)

object MediaUdpPacket {
    const val FLAG_KEY_FRAME = 1
    const val FLAG_CODEC_CONFIG = 1 shl 1
    const val DEFAULT_MAX_DATAGRAM_SIZE = 1_200
    const val MAX_DATAGRAM_SIZE_BYTES = DEFAULT_MAX_DATAGRAM_SIZE
    const val MAX_FRAGMENTS_PER_FRAME = 1_024
    const val MAX_FRAME_SIZE_BYTES = 2 * 1024 * 1024

    private const val MAGIC = 0x48434d46 // HCMF
    private const val VERSION = 1
    private const val HEADER_SIZE = 26
    private const val MAX_UNSIGNED_SHORT = 0xffff

    fun encodeFrame(
        track: MediaTrack,
        codec: MediaCodecType,
        sequenceNumber: Int,
        timestampMicros: Long,
        flags: Int,
        data: ByteArray,
        maxDatagramSize: Int = DEFAULT_MAX_DATAGRAM_SIZE,
    ): List<ByteArray> {
        require(maxDatagramSize > HEADER_SIZE) { "maxDatagramSize must leave room for payload" }
        require(maxDatagramSize <= MAX_DATAGRAM_SIZE_BYTES) { "maxDatagramSize exceeds protocol limit" }
        require(data.isNotEmpty()) { "data must not be empty" }
        require(data.size <= MAX_FRAME_SIZE_BYTES) { "frame exceeds protocol size limit" }

        val maxPayloadSize = maxDatagramSize - HEADER_SIZE
        require(maxPayloadSize <= MAX_UNSIGNED_SHORT) { "payload size exceeds protocol field limit" }
        val fragmentCount = (data.size + maxPayloadSize - 1) / maxPayloadSize
        require(fragmentCount <= MAX_FRAGMENTS_PER_FRAME) { "frame has too many fragments" }

        return List(fragmentCount) { index ->
            val offset = index * maxPayloadSize
            val payloadSize = minOf(maxPayloadSize, data.size - offset)
            ByteBuffer.allocate(HEADER_SIZE + payloadSize)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC)
                .put(VERSION.toByte())
                .put(track.id.toByte())
                .put(codec.id.toByte())
                .put(flags.toByte())
                .putInt(sequenceNumber)
                .putLong(timestampMicros)
                .putShort(index.toShort())
                .putShort(fragmentCount.toShort())
                .putShort(payloadSize.toShort())
                .put(data, offset, payloadSize)
                .array()
        }
    }

    fun decode(datagram: ByteArray, length: Int = datagram.size): MediaPacket? {
        if (length < HEADER_SIZE || datagram.size < length || length > MAX_DATAGRAM_SIZE_BYTES) return null
        val buffer = ByteBuffer.wrap(datagram, 0, length).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != MAGIC) return null
        if (buffer.get().toInt() != VERSION) return null

        val track = MediaTrack.fromId(buffer.get().toInt() and 0xff) ?: return null
        val codec = MediaCodecType.fromId(buffer.get().toInt() and 0xff) ?: return null
        val flags = buffer.get().toInt() and 0xff
        val sequenceNumber = buffer.int
        val timestampMicros = buffer.long
        val fragmentIndex = buffer.short.toInt() and 0xffff
        val fragmentCount = buffer.short.toInt() and 0xffff
        val payloadLength = buffer.short.toInt() and 0xffff
        if (fragmentCount <= 0 || fragmentCount > MAX_FRAGMENTS_PER_FRAME || fragmentIndex >= fragmentCount) return null
        if (payloadLength != length - HEADER_SIZE) return null

        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        return MediaPacket(
            track = track,
            codec = codec,
            sequenceNumber = sequenceNumber,
            timestampMicros = timestampMicros,
            flags = flags,
            fragmentIndex = fragmentIndex,
            fragmentCount = fragmentCount,
            payload = payload,
        )
    }

    fun isStreamPacket(datagram: ByteArray, length: Int = datagram.size): Boolean =
        decode(datagram, length) != null
}

class MediaFrameReassembler(
    private val maxPendingFrames: Int = 64,
) {
    private val pending = linkedMapOf<FrameKey, PendingFrame>()

    fun accept(packet: MediaPacket): EncodedMediaFrame? {
        val key = FrameKey(packet.track, packet.codec, packet.sequenceNumber)
        val frame = pending.getOrPut(key) {
            PendingFrame(
                track = packet.track,
                codec = packet.codec,
                sequenceNumber = packet.sequenceNumber,
                timestampMicros = packet.timestampMicros,
                flags = packet.flags,
                fragmentCount = packet.fragmentCount,
            )
        }
        if (!frame.accept(packet)) {
            pending.remove(key)
            return null
        }

        trimOldFrames()
        if (!frame.isComplete()) return null

        pending.remove(key)
        return EncodedMediaFrame(
            track = frame.track,
            codec = frame.codec,
            sequenceNumber = frame.sequenceNumber,
            timestampMicros = frame.timestampMicros,
            flags = frame.flags,
            data = frame.joinPayloads(),
        )
    }

    private fun trimOldFrames() {
        while (pending.size > maxPendingFrames) {
            val firstKey = pending.keys.firstOrNull() ?: return
            pending.remove(firstKey)
        }
    }

    private data class FrameKey(
        val track: MediaTrack,
        val codec: MediaCodecType,
        val sequenceNumber: Int,
    )

    private class PendingFrame(
        val track: MediaTrack,
        val codec: MediaCodecType,
        val sequenceNumber: Int,
        val timestampMicros: Long,
        val flags: Int,
        val fragmentCount: Int,
    ) {
        private val fragments = arrayOfNulls<ByteArray>(fragmentCount)
        private var payloadBytes = 0

        fun accept(packet: MediaPacket): Boolean {
            if (packet.track != track) return false
            if (packet.codec != codec) return false
            if (packet.fragmentCount != fragmentCount) return false
            val previous = fragments[packet.fragmentIndex]
            if (previous == null) {
                payloadBytes += packet.payload.size
                if (payloadBytes > MediaUdpPacket.MAX_FRAME_SIZE_BYTES) return false
            }
            fragments[packet.fragmentIndex] = packet.payload
            return true
        }

        fun isComplete(): Boolean = fragments.all { it != null }

        fun joinPayloads(): ByteArray {
            val output = ByteArrayOutputStream()
            fragments.forEach { fragment ->
                requireNotNull(fragment)
                output.write(fragment)
            }
            return output.toByteArray()
        }
    }
}
