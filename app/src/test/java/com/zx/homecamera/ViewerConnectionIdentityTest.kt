package com.zx.homecamera

import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.network.ViewerConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerConnectionIdentityTest {
    @Test
    fun activeConnectionMatchesSelectedDeviceByNetworkEndpoint() {
        val selectedDevice = CollectorDevice(
            deviceId = "192.168.1.10:62001",
            name = "采集端 192.168.1.10",
            hostAddress = "192.168.1.10",
            tcpPort = 62001,
            online = true,
        )
        val connection = ViewerConnection(
            collectorDeviceId = "android-secure-id",
            collectorHostAddress = "192.168.1.10",
            collectorTcpPort = 62001,
            streamUdpPort = 62010,
            streamWidth = 1280,
            streamHeight = 720,
            displayWidth = 720,
            displayHeight = 1280,
            streamFps = 20,
        )

        assertTrue(
            ViewerConnectionIdentity.isActiveConnection(
                expectedGeneration = 4,
                currentGeneration = 4,
                selectedDevice = selectedDevice,
                connection = connection,
            ),
        )
    }

    @Test
    fun activeConnectionRejectsStaleGenerationOrDifferentEndpoint() {
        val selectedDevice = CollectorDevice(
            deviceId = "192.168.1.10:62001",
            name = "采集端 192.168.1.10",
            hostAddress = "192.168.1.10",
            tcpPort = 62001,
            online = true,
        )
        val connection = ViewerConnection(
            collectorDeviceId = "android-secure-id",
            collectorHostAddress = "192.168.1.11",
            collectorTcpPort = 62001,
            streamUdpPort = 62010,
            streamWidth = 1280,
            streamHeight = 720,
            displayWidth = 720,
            displayHeight = 1280,
            streamFps = 20,
        )

        assertFalse(
            ViewerConnectionIdentity.isActiveConnection(
                expectedGeneration = 3,
                currentGeneration = 4,
                selectedDevice = selectedDevice,
                connection = connection,
            ),
        )
        assertFalse(
            ViewerConnectionIdentity.isActiveConnection(
                expectedGeneration = 4,
                currentGeneration = 4,
                selectedDevice = selectedDevice,
                connection = connection,
            ),
        )
    }
}
