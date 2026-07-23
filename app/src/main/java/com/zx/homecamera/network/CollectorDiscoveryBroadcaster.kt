package com.zx.homecamera.network

import com.zx.homecamera.core.protocol.ControlMessage
import com.zx.homecamera.core.protocol.ControlProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 采集端发现广播器：周期性向局域网广播地址 [BROADCAST_ADDRESS] : [discoveryPort] 发送
 * [ControlMessage.Discovery] 编码包，使客户端无需逐个探测子网主机即可秒级发现采集端。
 *
 * 由采集端服务在后台线程调用 [run] 阻塞执行；停止时调用 [stop] 中断循环。
 *
 * 采用 255.255.255.255 限定广播地址，仅需已有 INTERNET 权限，无需多播权限与 MulticastLock。
 */
class CollectorDiscoveryBroadcaster(
    private val deviceId: String,
    private val deviceName: String,
    private val controlPort: Int,
    private val discoveryPort: Int = DEFAULT_DISCOVERY_PORT,
    private val broadcastIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    @Volatile
    private var running = false
    private var socket: DatagramSocket? = null

    /**
     * 阻塞式广播循环，直到 [stop] 被调用或线程被中断。
     */
    fun run() {
        running = true
        val payload = ControlProtocol.encode(
            ControlMessage.Discovery(
                deviceId = deviceId,
                deviceName = deviceName,
                controlPort = controlPort,
            ),
        ).toByteArray(Charsets.UTF_8)
        val broadcastAddress = InetAddress.getByName(BROADCAST_ADDRESS)
        val packet = DatagramPacket(payload, payload.size, broadcastAddress, discoveryPort)
        logNet("discovery broadcast start port=$discoveryPort controlPort=$controlPort deviceId=$deviceId")
        try {
            DatagramSocket().use { datagramSocket ->
                socket = datagramSocket
                datagramSocket.broadcast = true
                while (running && !Thread.currentThread().isInterrupted) {
                    runCatching {
                        datagramSocket.send(packet)
                        logNet("discovery broadcast sent port=$discoveryPort bytes=${payload.size}")
                    }.onFailure { error ->
                        logNetError("discovery broadcast send failed: ${error.message}", error)
                    }
                    try {
                        Thread.sleep(broadcastIntervalMillis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        } catch (_: Exception) {
            // socket 关闭或被中断时优雅退出
        } finally {
            running = false
            socket = null
            logNet("discovery broadcast stopped port=$discoveryPort")
        }
    }

    /**
     * 中断广播循环。可从任意线程调用。
     */
    fun stop() {
        running = false
        socket?.runCatching { close() }
    }

    companion object {
        const val DEFAULT_DISCOVERY_PORT = 62002
        const val BROADCAST_ADDRESS = "255.255.255.255"
        const val DEFAULT_INTERVAL_MILLIS = 1_000L
    }
}
