package com.zx.homecamera.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeCameraReducerTest {
    @Test
    fun selectsCollectorModeWithIdleServiceState() {
        val state = HomeCameraReducer.reduce(
            state = HomeCameraState(),
            action = HomeCameraAction.SelectRole(AppRole.Collector),
        )

        assertEquals(AppRole.Collector, state.role)
        assertEquals(Screen.Collector, state.screen)
        assertEquals(ServiceStatus.NotStarted, state.collector.serviceStatus)
        assertEquals(RecordingStatus.Idle, state.collector.recordingStatus)
    }

    @Test
    fun collectorStartFlowUpdatesServiceAndRecordingStatus() {
        val selected = HomeCameraReducer.reduce(
            HomeCameraState(),
            HomeCameraAction.SelectRole(AppRole.Collector),
        )
        val starting = HomeCameraReducer.reduce(selected, HomeCameraAction.StartCollector)
        val running = HomeCameraReducer.reduce(starting, HomeCameraAction.CollectorStarted)

        assertEquals(ServiceStatus.Starting, starting.collector.serviceStatus)
        assertEquals(RecordingStatus.Starting, starting.collector.recordingStatus)
        assertEquals(ServiceStatus.Running, running.collector.serviceStatus)
        assertEquals(RecordingStatus.Recording, running.collector.recordingStatus)
    }

    @Test
    fun clientDeviceSelectionOpensViewer() {
        val device = CollectorDevice(
            deviceId = "collector-1",
            name = "客厅采集端",
            hostAddress = "192.168.1.24",
            tcpPort = 62000,
            online = true,
        )
        val clientState = HomeCameraReducer.reduce(
            HomeCameraState(),
            HomeCameraAction.SelectRole(AppRole.Client),
        )
        val scanned = HomeCameraReducer.reduce(
            clientState,
            HomeCameraAction.DevicesDiscovered(listOf(device)),
        )
        val viewing = HomeCameraReducer.reduce(
            scanned,
            HomeCameraAction.OpenViewer(device.deviceId),
        )

        assertEquals(Screen.Viewer, viewing.screen)
        assertEquals(device, viewing.viewer.selectedDevice)
        assertEquals(ViewerStatus.Connecting, viewing.viewer.status)
    }

    @Test
    fun returningFromViewerKeepsScannedDevices() {
        val device = CollectorDevice(
            deviceId = "collector-1",
            name = "客厅采集端",
            hostAddress = "192.168.1.24",
            tcpPort = 62000,
            online = true,
        )
        val state = HomeCameraState(
            role = AppRole.Client,
            screen = Screen.Viewer,
            client = ClientState(devices = listOf(device)),
            viewer = ViewerState(selectedDevice = device, status = ViewerStatus.Playing),
        )

        val result = HomeCameraReducer.reduce(state, HomeCameraAction.BackToClientList)

        assertEquals(Screen.ClientList, result.screen)
        assertEquals(listOf(device), result.client.devices)
        assertNull(result.viewer.selectedDevice)
        assertEquals(ViewerStatus.Idle, result.viewer.status)
    }

    @Test
    fun startScanClearsStaleDeviceList() {
        val device = CollectorDevice(
            deviceId = "collector-1",
            name = "客厅采集端",
            hostAddress = "192.168.1.24",
            tcpPort = 62000,
            online = true,
        )
        val state = HomeCameraState(
            role = AppRole.Client,
            screen = Screen.ClientList,
            client = ClientState(
                scanStatus = ScanStatus.Finished,
                devices = listOf(device),
            ),
        )

        val result = HomeCameraReducer.reduce(state, HomeCameraAction.StartScan)

        assertEquals(ScanStatus.Scanning, result.client.scanStatus)
        assertEquals(emptyList<CollectorDevice>(), result.client.devices)
    }

    @Test
    fun recordingFailureDoesNotFailRunningCollectorService() {
        val running = HomeCameraState(
            collector = CollectorState(
                serviceStatus = ServiceStatus.Running,
                recordingStatus = RecordingStatus.Recording,
            ),
        )

        val result = HomeCameraReducer.reduce(running, HomeCameraAction.RecordingFailed("磁盘已满"))

        assertEquals(ServiceStatus.Running, result.collector.serviceStatus)
        assertEquals(RecordingStatus.Error, result.collector.recordingStatus)
        assertEquals("磁盘已满", result.collector.errorMessage)
    }

    @Test
    fun clientCountChangedUpdatesConnectionStatus() {
        val connected = HomeCameraReducer.reduce(HomeCameraState(), HomeCameraAction.ClientCountChanged(2))
        val disconnected = HomeCameraReducer.reduce(connected, HomeCameraAction.ClientCountChanged(0))

        assertEquals(2, connected.collector.connectedClientCount)
        assertEquals(ConnectionStatus.Connected, connected.collector.connectionStatus)
        assertEquals(0, disconnected.collector.connectedClientCount)
        assertEquals(ConnectionStatus.NoClient, disconnected.collector.connectionStatus)
    }
}
