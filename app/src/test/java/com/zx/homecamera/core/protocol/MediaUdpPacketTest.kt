package com.zx.homecamera.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaUdpPacketTest {
    @Test
    fun encodesAndDecodesVideoPacketMetadata() {
        val frame = ByteArray(900) { index -> (index % 127).toByte() }

        val datagrams = MediaUdpPacket.encodeFrame(
            track = MediaTrack.Video,
            codec = MediaCodecType.H264,
            sequenceNumber = 42,
            timestampMicros = 123_456L,
            flags = MediaUdpPacket.FLAG_KEY_FRAME,
            data = frame,
        )

        assertEquals(1, datagrams.size)
        val packet = MediaUdpPacket.decode(datagrams.single())

        assertNotNull(packet)
        requireNotNull(packet)
        assertEquals(MediaTrack.Video, packet.track)
        assertEquals(MediaCodecType.H264, packet.codec)
        assertEquals(42, packet.sequenceNumber)
        assertEquals(123_456L, packet.timestampMicros)
        assertEquals(MediaUdpPacket.FLAG_KEY_FRAME, packet.flags)
        assertEquals(0, packet.fragmentIndex)
        assertEquals(1, packet.fragmentCount)
        assertArrayEquals(frame, packet.payload)
    }

    @Test
    fun encodesAndDecodesAudioPacketMetadata() {
        val frame = ByteArray(320) { index -> (255 - index % 255).toByte() }

        val datagrams = MediaUdpPacket.encodeFrame(
            track = MediaTrack.Audio,
            codec = MediaCodecType.Aac,
            sequenceNumber = 7,
            timestampMicros = 22_000L,
            flags = MediaUdpPacket.FLAG_CODEC_CONFIG,
            data = frame,
        )

        assertEquals(1, datagrams.size)
        val packet = MediaUdpPacket.decode(datagrams.single())

        assertNotNull(packet)
        requireNotNull(packet)
        assertEquals(MediaTrack.Audio, packet.track)
        assertEquals(MediaCodecType.Aac, packet.codec)
        assertEquals(7, packet.sequenceNumber)
        assertEquals(22_000L, packet.timestampMicros)
        assertEquals(MediaUdpPacket.FLAG_CODEC_CONFIG, packet.flags)
        assertArrayEquals(frame, packet.payload)
    }

    @Test
    fun reassemblesFragmentedAudioFrameOutOfOrder() {
        val frame = ByteArray(2_100) { index -> (index % 251).toByte() }
        val datagrams = MediaUdpPacket.encodeFrame(
            track = MediaTrack.Audio,
            codec = MediaCodecType.Aac,
            sequenceNumber = 99,
            timestampMicros = 88_000L,
            flags = 0,
            data = frame,
            maxDatagramSize = 700,
        )
        val reassembler = MediaFrameReassembler()

        assertEquals(4, datagrams.size)
        assertNull(reassembler.accept(requireNotNull(MediaUdpPacket.decode(datagrams[2]))))
        assertNull(reassembler.accept(requireNotNull(MediaUdpPacket.decode(datagrams[0]))))
        assertNull(reassembler.accept(requireNotNull(MediaUdpPacket.decode(datagrams[3]))))
        val complete = reassembler.accept(requireNotNull(MediaUdpPacket.decode(datagrams[1])))

        assertNotNull(complete)
        requireNotNull(complete)
        assertEquals(MediaTrack.Audio, complete.track)
        assertEquals(MediaCodecType.Aac, complete.codec)
        assertEquals(99, complete.sequenceNumber)
        assertEquals(88_000L, complete.timestampMicros)
        assertArrayEquals(frame, complete.data)
    }

    @Test
    fun rejectsInvalidDatagram() {
        val invalid = ByteArray(32) { 1 }

        assertNull(MediaUdpPacket.decode(invalid))
        assertFalse(MediaUdpPacket.isStreamPacket(invalid))
    }
}
