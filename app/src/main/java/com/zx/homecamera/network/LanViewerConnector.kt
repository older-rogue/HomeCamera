package com.zx.homecamera.network

import com.zx.homecamera.audio.AacAudioConfig
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.protocol.ControlMessage
import com.zx.homecamera.core.protocol.ControlProtocol
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class LanViewerConnector {
    /**
     * 连接采集端并完成握手。password 为采集端访问密码；采集端开启密码且不匹配时
     * 回复 [ControlMessage.AuthFailed]，这里抛出 [AuthFailedException] 供上层区分"密码错误"
     * 与普通连接失败（后者用于清除已保存密码并重新提示输入）。
     */
    fun connect(device: CollectorDevice, timeoutMillis: Int, password: String = ""): ViewerConnection {
        val socket = Socket()
        val udpSocket = DatagramSocket(0)
        try {
            logNet("viewer connect start host=${device.hostAddress} port=${device.tcpPort} timeoutMs=$timeoutMillis")
            socket.connect(InetSocketAddress(device.hostAddress, device.tcpPort), timeoutMillis)
            logNet("viewer connect tcp ok host=${device.hostAddress} port=${device.tcpPort}")
            socket.soTimeout = timeoutMillis

            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            writer.write(
                ControlProtocol.encode(
                    ControlMessage.ViewStart(
                        clientId = UUID.randomUUID().toString(),
                        udpPort = udpSocket.localPort,
                        password = password,
                    ),
                ),
            )
            writer.newLine()
            writer.flush()
            logNet("viewer sent ViewStart udpPort=${udpSocket.localPort}")

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val response = ControlProtocol.decode(reader.readLine())
            if (response is ControlMessage.AuthFailed) {
                throw AuthFailedException(response.reason.ifBlank { "密码错误" })
            }
            require(response is ControlMessage.Hello) {
                "Collector did not return HELLO"
            }
            logNet(
                "viewer received Hello deviceId=${response.deviceId} stream=${response.streamWidth}x${response.streamHeight} udpPort=${response.udpPort}",
            )
            return ViewerConnection(
                collectorDeviceId = response.deviceId,
                collectorHostAddress = device.hostAddress,
                collectorTcpPort = device.tcpPort,
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
                udpSocket = udpSocket,
                controlSocket = socket,
            )
        } catch (error: Throwable) {
            logNetError("viewer connect failed host=${device.hostAddress} port=${device.tcpPort}: ${error.message}", error)
            socket.close()
            udpSocket.close()
            throw error
        }
    }

    companion object {
        const val CLIENT_UDP_PORT = 62011
    }
}

data class ViewerConnection(
    val collectorDeviceId: String,
    val collectorHostAddress: String,
    val collectorTcpPort: Int,
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
    val udpSocket: DatagramSocket? = null,
    val controlSocket: Socket? = null,
) {
    fun close() {
        controlSocket?.runCatching { close() }
        udpSocket?.runCatching { close() }
    }
}

/**
 * 采集端拒绝访问（密码错误）。与网络层异常区分，使观看页能明确提示并清除已保存密码。
 */
class AuthFailedException(message: String) : Exception(message)
