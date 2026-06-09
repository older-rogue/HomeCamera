package com.zx.homecamera.core.protocol

data class H264Packet(
    val sequenceNumber: Int,
    val timestampMicros: Long,
    val flags: Int,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val payload: ByteArray,
)

data class EncodedH264Frame(
    val sequenceNumber: Int,
    val timestampMicros: Long,
    val flags: Int,
    val data: ByteArray,
)

object H264UdpPacket {
    const val FLAG_KEY_FRAME = MediaUdpPacket.FLAG_KEY_FRAME
    const val FLAG_CODEC_CONFIG = MediaUdpPacket.FLAG_CODEC_CONFIG
    const val DEFAULT_MAX_DATAGRAM_SIZE = MediaUdpPacket.DEFAULT_MAX_DATAGRAM_SIZE

    fun encodeFrame(
        sequenceNumber: Int,
        timestampMicros: Long,
        flags: Int,
        data: ByteArray,
        maxDatagramSize: Int = DEFAULT_MAX_DATAGRAM_SIZE,
    ): List<ByteArray> =
        MediaUdpPacket.encodeFrame(
            track = MediaTrack.Video,
            codec = MediaCodecType.H264,
            sequenceNumber = sequenceNumber,
            timestampMicros = timestampMicros,
            flags = flags,
            data = data,
            maxDatagramSize = maxDatagramSize,
        )

    fun decode(datagram: ByteArray, length: Int = datagram.size): H264Packet? {
        val packet = MediaUdpPacket.decode(datagram, length) ?: return null
        if (packet.track != MediaTrack.Video || packet.codec != MediaCodecType.H264) return null
        return H264Packet(
            sequenceNumber = packet.sequenceNumber,
            timestampMicros = packet.timestampMicros,
            flags = packet.flags,
            fragmentIndex = packet.fragmentIndex,
            fragmentCount = packet.fragmentCount,
            payload = packet.payload,
        )
    }

    fun isStreamPacket(datagram: ByteArray, length: Int = datagram.size): Boolean =
        decode(datagram, length) != null
}

class H264FrameReassembler(
    private val mediaReassembler: MediaFrameReassembler = MediaFrameReassembler(),
) {
    constructor(maxPendingFrames: Int) : this(MediaFrameReassembler(maxPendingFrames))

    fun accept(packet: H264Packet): EncodedH264Frame? {
        val mediaFrame = mediaReassembler.accept(
            MediaPacket(
                track = MediaTrack.Video,
                codec = MediaCodecType.H264,
                sequenceNumber = packet.sequenceNumber,
                timestampMicros = packet.timestampMicros,
                flags = packet.flags,
                fragmentIndex = packet.fragmentIndex,
                fragmentCount = packet.fragmentCount,
                payload = packet.payload,
            ),
        ) ?: return null
        return EncodedH264Frame(
            sequenceNumber = mediaFrame.sequenceNumber,
            timestampMicros = mediaFrame.timestampMicros,
            flags = mediaFrame.flags,
            data = mediaFrame.data,
        )
    }
}
