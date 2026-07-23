package com.zx.homecamera

import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.network.ViewerConnection

object ViewerConnectionIdentity {
    fun isActiveConnection(
        expectedGeneration: Long,
        currentGeneration: Long,
        selectedDevice: CollectorDevice?,
        connection: ViewerConnection,
    ): Boolean =
        currentGeneration == expectedGeneration &&
            selectedDevice?.hostAddress == connection.collectorHostAddress &&
            selectedDevice.tcpPort == connection.collectorTcpPort
}
