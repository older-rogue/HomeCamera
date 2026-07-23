package com.zx.homecamera.network

import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.protocol.ControlMessage
import com.zx.homecamera.core.protocol.ControlProtocol

/**
 * 客户端发现监听器：在固定端口 [discoveryPort] 监听采集端广播的 [ControlMessage.Discovery]
 * 通告包，解析后通过 [onDevice] 回调发现的采集端设备。
 *
 * 由客户端 ViewModel 在后台线程调用 [run] 阻塞接收；停止时调用 [stop] 中断循环。
 *
 * 与 [CollectorDiscoveryBroadcaster] 配对：采集端周期性广播，客户端被动监听，
 * 无需逐个探测子网主机，实现秒级发现。
 */
class CollectorDiscoveryListener(
    private val onDevice: (CollectorDevice) -> Unit,
    private val discoveryPort: Int = DEFAULT_DISCOVERY_PORT,
    private val onError: (String) -> Unit = {},
) {
    @Volatile
    private var running = false
    private var socket: java.net.DatagramSocket? = null

    /**
     * 阻塞式接收循环，直到 [stop] 被调用或线程被中断。
     * 端口绑定失败时通过 [onError] 上报并退出，不影响调用方的 TCP 兜底通道。
     */
    fun run() {
        running = true
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        val packet = java.net.DatagramPacket(buffer, buffer.size)
        logNet("discovery listen start port=$discoveryPort")
        var datagramSocket: java.net.DatagramSocket? = null
        try {
            datagramSocket = java.net.DatagramSocket(discoveryPort)
            socket = datagramSocket
            while (running && !Thread.currentThread().isInterrupted) {
                datagramSocket.receive(packet)
                val hostAddress = packet.address?.hostAddress
                val payload = packet.data.copyOfRange(0, packet.length).toString(Charsets.UTF_8)
                val message = ControlProtocol.decode(payload)
                if (message is ControlMessage.Discovery && hostAddress != null) {
                    val device = CollectorDevice(
                        deviceId = "$hostAddress:${message.controlPort}",
                        name = message.deviceName,
                        hostAddress = hostAddress,
                        tcpPort = message.controlPort,
                        online = true,
                    )
                    logNet("discovery listen hit host=$hostAddress controlPort=${message.controlPort} name=${message.deviceName}")
                    onDevice(device)
                } else if (message == null) {
                    logNet("discovery listen ignored unknown packet host=$hostAddress len=${packet.length}")
                }
                packet.length = buffer.size
            }
        } catch (_: java.net.SocketException) {
            if (running) {
                onError("发现端口 $discoveryPort 绑定失败或被占用")
            }
        } catch (error: Exception) {
            if (running) {
                logNetError("discovery listen failed: ${error.message}", error)
                onError("发现监听异常: ${error.message ?: ""}")
            }
        } finally {
            running = false
            socket?.runCatching { close() }
            socket = null
            logNet("discovery listen stopped port=$discoveryPort")
        }
    }

    /**
     * 中断接收循环。可从任意线程调用。
     */
    fun stop() {
        running = false
        socket?.runCatching { close() }
    }

    companion object {
        const val DEFAULT_DISCOVERY_PORT = CollectorDiscoveryBroadcaster.DEFAULT_DISCOVERY_PORT
        private const val RECEIVE_BUFFER_BYTES = 1024
    }
}
