package com.zx.homecamera.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpSubnetScannerTest {
    @Test
    fun returnsDeviceForConnectableHost() {
        val scanner = TcpSubnetScanner { host, _, _ -> host == "192.168.1.10" }

        val devices = scanner.scan(
            hosts = listOf("192.168.1.9", "192.168.1.10", "192.168.1.11"),
            tcpPort = 62001,
            timeoutMillis = 1,
            parallelism = 2,
        )

        assertEquals(1, devices.size)
        assertEquals("192.168.1.10:62001", devices.single().deviceId)
        assertEquals("采集端 192.168.1.10", devices.single().name)
        assertEquals("192.168.1.10", devices.single().hostAddress)
        assertEquals(62001, devices.single().tcpPort)
        assertTrue(devices.single().online)
    }

    @Test
    fun returnsEmptyListWhenNoHostConnects() {
        val scanner = TcpSubnetScanner { _, _, _ -> false }

        val devices = scanner.scan(
            hosts = listOf("192.168.1.9", "192.168.1.10"),
            tcpPort = 62001,
            timeoutMillis = 1,
            parallelism = 2,
        )

        assertTrue(devices.isEmpty())
    }

    @Test
    fun ignoresFailuresFromIndividualHosts() {
        val scanner = TcpSubnetScanner { host, _, _ ->
            if (host == "192.168.1.9") error("connection failed")
            host == "192.168.1.10"
        }

        val devices = scanner.scan(
            hosts = listOf("192.168.1.9", "192.168.1.10"),
            tcpPort = 62001,
            timeoutMillis = 1,
            parallelism = 2,
        )

        assertEquals(listOf("192.168.1.10"), devices.map { it.hostAddress })
    }

    @Test
    fun generateSubnetHostsUsesCurrentSlash24AndSkipsSelf() {
        val hosts = generateSubnetHosts("192.168.1.37")

        assertEquals("192.168.1.2", hosts.first())
        assertEquals("192.168.1.255", hosts.last())
        assertFalse(hosts.contains("192.168.1.37"))
        assertTrue(hosts.contains("192.168.1.255"))
        assertEquals(253, hosts.size)
    }

    @Test
    fun generateSubnetHostsReturnsEmptyListForInvalidAddress() {
        assertTrue(generateSubnetHosts("not-an-ip").isEmpty())
        assertTrue(generateSubnetHosts("192.168.1.999").isEmpty())
    }
}
