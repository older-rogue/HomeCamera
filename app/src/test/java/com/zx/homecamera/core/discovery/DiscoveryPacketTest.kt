package com.zx.homecamera.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryPacketTest {
    @Test
    fun announcePacketRoundTripsCollectorIdentity() {
        val packet = DiscoveryPacket.Announce(
            deviceId = "collector-1",
            deviceName = "书房采集端",
            hostAddress = "192.168.1.24",
            tcpPort = 62000,
        )

        val encoded = DiscoveryProtocol.encode(packet)
        val decoded = DiscoveryProtocol.decode(encoded)

        assertEquals(packet, decoded)
    }

    @Test
    fun discoverPacketRoundTrips() {
        val encoded = DiscoveryProtocol.encode(DiscoveryPacket.Discover)
        val decoded = DiscoveryProtocol.decode(encoded)

        assertEquals(DiscoveryPacket.Discover, decoded)
    }

    @Test
    fun invalidPacketReturnsNull() {
        assertNull(DiscoveryProtocol.decode("HELLO"))
    }
}
