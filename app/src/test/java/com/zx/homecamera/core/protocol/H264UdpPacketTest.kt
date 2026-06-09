package com.zx.homecamera.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class H264UdpPacketTest {
    @Test
    fun encodesAndDecodesFragmentedFrame() {
        val frame = ByteArray(2_500) { index -> (index % 127).toByte() }

        val datagrams = H264UdpPacket.encodeFrame(
            sequenceNumber = 42,
            timestampMicros = 123_456L,
            flags = H264UdpPacket.FLAG_KEY_FRAME,
            data = frame,
            maxDatagramSize = 700,
        )

        assertEquals(4, datagrams.size)

        val packets = datagrams.map { H264UdpPacket.decode(it) }
        assertEquals(4, packets.size)
        packets.forEachIndexed { index, packet ->
            assertNotNull(packet)
            requireNotNull(packet)
            assertEquals(42, packet.sequenceNumber)
            assertEquals(123_456L, packet.timestampMicros)
            assertEquals(H264UdpPacket.FLAG_KEY_FRAME, packet.flags)
            assertEquals(index, packet.fragmentIndex)
            assertEquals(4, packet.fragmentCount)
        }
    }

    @Test
    fun reassemblesOutOfOrderFragments() {
        val frame = ByteArray(1_800) { index -> (255 - index % 255).toByte() }
        val datagrams = H264UdpPacket.encodeFrame(
            sequenceNumber = 7,
            timestampMicros = 456_000L,
            flags = H264UdpPacket.FLAG_CODEC_CONFIG,
            data = frame,
            maxDatagramSize = 650,
        )
        val reassembler = H264FrameReassembler()

        assertNull(reassembler.accept(requireNotNull(H264UdpPacket.decode(datagrams[2]))))
        assertNull(reassembler.accept(requireNotNull(H264UdpPacket.decode(datagrams[0]))))
        val complete = reassembler.accept(requireNotNull(H264UdpPacket.decode(datagrams[1])))

        assertNotNull(complete)
        requireNotNull(complete)
        assertEquals(7, complete.sequenceNumber)
        assertEquals(456_000L, complete.timestampMicros)
        assertEquals(H264UdpPacket.FLAG_CODEC_CONFIG, complete.flags)
        assertArrayEquals(frame, complete.data)
    }

    @Test
    fun rejectsInvalidDatagram() {
        val invalid = ByteArray(32) { 1 }

        assertNull(H264UdpPacket.decode(invalid))
        assertFalse(H264UdpPacket.isStreamPacket(invalid))
    }
}
