package com.zx.homecamera.network

import com.zx.homecamera.audio.AacAudioConfig
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.protocol.ControlMessage
import com.zx.homecamera.core.protocol.ControlProtocol
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class LanViewerConnector {
    fun connect(device: CollectorDevice, timeoutMillis: Int): ViewerConnection {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(device.hostAddress, device.tcpPort), timeoutMillis)
            socket.soTimeout = timeoutMillis

            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            writer.write(
                ControlProtocol.encode(
                    ControlMessage.ViewStart(
                        clientId = UUID.randomUUID().toString(),
                        udpPort = CLIENT_UDP_PORT,
                    ),
                ),
            )
            writer.newLine()
            writer.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val response = ControlProtocol.decode(reader.readLine())
            require(response is ControlMessage.Hello) {
                "Collector did not return HELLO"
            }
            return ViewerConnection(
                collectorDeviceId = response.deviceId,
                streamUdpPort = response.udpPort,
                streamWidth = response.streamWidth,
                streamHeight = response.streamHeight,
                displayWidth = response.displayWidth,
                displayHeight = response.displayHeight,
                streamFps = response.streamFps,
                audioEnabled = response.audioEnabled,
                audioCodec = response.audioCodec,
                audioSampleRate = response.audioSampleRate,
                audioChannelCount = response.audioChannelCount,
                audioBitrate = response.audioBitrate,
            )
        }
    }

    companion object {
        const val CLIENT_UDP_PORT = 62011
    }
}

data class ViewerConnection(
    val collectorDeviceId: String,
    val streamUdpPort: Int,
    val streamWidth: Int,
    val streamHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val streamFps: Int,
    val audioEnabled: Boolean = AacAudioConfig.DEFAULT_ENABLED,
    val audioCodec: String = AacAudioConfig.CODEC,
    val audioSampleRate: Int = AacAudioConfig.SAMPLE_RATE,
    val audioChannelCount: Int = AacAudioConfig.CHANNEL_COUNT,
    val audioBitrate: Int = AacAudioConfig.BITRATE,
)
