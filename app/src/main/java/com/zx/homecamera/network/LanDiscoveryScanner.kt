package com.zx.homecamera.network

import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.discovery.DiscoveryPacket
import com.zx.homecamera.core.discovery.DiscoveryProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class LanDiscoveryScanner {
    fun scan(timeoutMillis: Int): List<CollectorDevice> {
        val discovered = linkedMapOf<String, CollectorDevice>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250

            val request = DiscoveryProtocol.encode(DiscoveryPacket.Discover).toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                request,
                request.size,
                InetAddress.getByName("255.255.255.255"),
                DiscoveryProtocol.UDP_PORT,
            )
            socket.send(packet)

            val startedAt = System.currentTimeMillis()
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() - startedAt < timeoutMillis) {
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                    val text = String(response.data, response.offset, response.length, Charsets.UTF_8)
                    val announce = DiscoveryProtocol.decode(text) as? DiscoveryPacket.Announce
                    if (announce != null) {
                        discovered[announce.deviceId] = CollectorDevice(
                            deviceId = announce.deviceId,
                            name = announce.deviceName.ifBlank { "采集端 ${announce.hostAddress}" },
                            hostAddress = announce.hostAddress,
                            tcpPort = announce.tcpPort,
                            online = true,
                        )
                    }
                } catch (_: SocketTimeoutException) {
                    // Keep waiting until the total scan window ends.
                }
            }
        }
        return discovered.values.toList()
    }
}
