package com.zx.homecamera.core.discovery

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed interface DiscoveryPacket {
    data object Discover : DiscoveryPacket

    data class Announce(
        val deviceId: String,
        val deviceName: String,
        val hostAddress: String,
        val tcpPort: Int,
    ) : DiscoveryPacket
}

object DiscoveryProtocol {
    const val UDP_PORT = 62000
    private const val PREFIX = "HOME_CAMERA_DISCOVERY"
    private const val VERSION = "1"

    fun encode(packet: DiscoveryPacket): String {
        val fields = when (packet) {
            DiscoveryPacket.Discover -> listOf("type" to "DISCOVER")
            is DiscoveryPacket.Announce -> listOf(
                "type" to "ANNOUNCE",
                "deviceId" to packet.deviceId,
                "deviceName" to packet.deviceName,
                "hostAddress" to packet.hostAddress,
                "tcpPort" to packet.tcpPort.toString(),
            )
        }

        return listOf(PREFIX, "version=$VERSION")
            .plus(fields.map { (key, value) -> "$key=${value.urlEncode()}" })
            .joinToString("|")
    }

    fun decode(text: String): DiscoveryPacket? {
        val parts = text.split("|")
        if (parts.size < 3 || parts.first() != PREFIX) return null

        val fields = parts.drop(1)
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null else {
                    part.substring(0, separator) to part.substring(separator + 1).urlDecode()
                }
            }
            .toMap()

        if (fields["version"] != VERSION) return null

        return when (fields["type"]) {
            "DISCOVER" -> DiscoveryPacket.Discover
            "ANNOUNCE" -> DiscoveryPacket.Announce(
                deviceId = fields["deviceId"] ?: return null,
                deviceName = fields["deviceName"] ?: return null,
                hostAddress = fields["hostAddress"] ?: return null,
                tcpPort = fields["tcpPort"]?.toIntOrNull() ?: return null,
            )

            else -> null
        }
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun String.urlDecode(): String =
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())
}
