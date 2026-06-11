package com.zx.homecamera.network

import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.protocol.ControlMessage
import com.zx.homecamera.core.protocol.ControlProtocol
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanViewerConnectorTest {
    @Test
    fun sendsViewStartAndReadsCollectorHello() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val request = ControlProtocol.decode(reader.readLine())
                    assertTrue(request is ControlMessage.ViewStart)

                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    writer.write(
                        ControlProtocol.encode(
                            ControlMessage.Hello(
                                deviceId = "collector-1",
                                deviceName = "采集端",
                                udpPort = 62010,
                                streamWidth = 1280,
                                streamHeight = 720,
                                displayWidth = 720,
                                displayHeight = 1280,
                                streamFps = 20,
                            ),
                        ),
                    )
                    writer.newLine()
                    writer.flush()
                }
            }

            val result = LanViewerConnector().connect(
                device = CollectorDevice(
                    deviceId = "collector-1",
                    name = "采集端",
                    hostAddress = "127.0.0.1",
                    tcpPort = server.localPort,
                    online = true,
                ),
                timeoutMillis = 1_000,
            )

            assertEquals(62010, result.streamUdpPort)
            assertEquals(1280, result.streamWidth)
            assertEquals(720, result.streamHeight)
            assertEquals(720, result.displayWidth)
            assertEquals(1280, result.displayHeight)
            assertEquals(20, result.streamFps)
            assertEquals("127.0.0.1", result.collectorHostAddress)
            assertEquals(server.localPort, result.collectorTcpPort)
            executor.shutdownNow()
        }
    }
}
