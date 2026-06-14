package com.zx.homecamera.core.app

enum class AppRole {
    Collector,
    Client,
}

enum class Screen {
    RoleSelection,
    Collector,
    ClientList,
    Viewer,
}

enum class ServiceStatus {
    NotStarted,
    Starting,
    Running,
    Stopped,
    Error,
}

enum class ConnectionStatus {
    NoClient,
    Connected,
}

enum class RecordingStatus {
    Idle,
    Starting,
    Recording,
    StorageLow,
    Cleaning,
    Error,
}

enum class ScanStatus {
    Idle,
    Scanning,
    Finished,
    Error,
}

enum class ViewerStatus {
    Idle,
    Connecting,
    Playing,
    Disconnected,
    Reconnecting,
    Error,
}

data class CollectorDevice(
    val deviceId: String,
    val name: String,
    val hostAddress: String,
    val tcpPort: Int,
    val online: Boolean,
)

data class CollectorState(
    val serviceStatus: ServiceStatus = ServiceStatus.NotStarted,
    val connectionStatus: ConnectionStatus = ConnectionStatus.NoClient,
    val connectedClientCount: Int = 0,
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val errorMessage: String? = null,
)

data class ClientState(
    val scanStatus: ScanStatus = ScanStatus.Idle,
    val devices: List<CollectorDevice> = emptyList(),
    val errorMessage: String? = null,
)

data class ViewerState(
    val selectedDevice: CollectorDevice? = null,
    val status: ViewerStatus = ViewerStatus.Idle,
    val errorMessage: String? = null,
)

data class HomeCameraState(
    val role: AppRole? = null,
    val screen: Screen = Screen.RoleSelection,
    val collector: CollectorState = CollectorState(),
    val client: ClientState = ClientState(),
    val viewer: ViewerState = ViewerState(),
)

sealed interface HomeCameraAction {
    data class SelectRole(val role: AppRole) : HomeCameraAction
    data object StartCollector : HomeCameraAction
    data object CollectorStarted : HomeCameraAction
    data class CollectorFailed(val reason: String) : HomeCameraAction
    data class ClientCountChanged(val count: Int) : HomeCameraAction
    data object StopCollector : HomeCameraAction
    data object StartScan : HomeCameraAction
    data class DevicesDiscovered(val devices: List<CollectorDevice>) : HomeCameraAction
    data class ScanFailed(val reason: String) : HomeCameraAction
    data class OpenViewer(val deviceId: String) : HomeCameraAction
    data class ViewerStatusChanged(val status: ViewerStatus, val errorMessage: String? = null) : HomeCameraAction
    data object BackToClientList : HomeCameraAction
    data object BackToRoleSelection : HomeCameraAction
}

object HomeCameraReducer {
    fun reduce(state: HomeCameraState, action: HomeCameraAction): HomeCameraState =
        when (action) {
            is HomeCameraAction.SelectRole -> when (action.role) {
                AppRole.Collector -> state.copy(
                    role = AppRole.Collector,
                    screen = Screen.Collector,
                )

                AppRole.Client -> state.copy(
                    role = AppRole.Client,
                    screen = Screen.ClientList,
                )
            }

            HomeCameraAction.StartCollector -> state.copy(
                collector = state.collector.copy(
                    serviceStatus = ServiceStatus.Starting,
                    recordingStatus = RecordingStatus.Starting,
                    errorMessage = null,
                ),
            )

            HomeCameraAction.CollectorStarted -> state.copy(
                collector = state.collector.copy(
                    serviceStatus = ServiceStatus.Running,
                    recordingStatus = RecordingStatus.Recording,
                    errorMessage = null,
                ),
            )

            is HomeCameraAction.CollectorFailed -> state.copy(
                collector = state.collector.copy(
                    serviceStatus = ServiceStatus.Error,
                    recordingStatus = RecordingStatus.Error,
                    errorMessage = action.reason,
                ),
            )

            is HomeCameraAction.ClientCountChanged -> state.copy(
                collector = state.collector.copy(
                    connectedClientCount = action.count,
                    connectionStatus = if (action.count > 0) {
                        ConnectionStatus.Connected
                    } else {
                        ConnectionStatus.NoClient
                    },
                ),
            )

            HomeCameraAction.StopCollector -> state.copy(
                collector = state.collector.copy(
                    serviceStatus = ServiceStatus.Stopped,
                    recordingStatus = RecordingStatus.Idle,
                    connectedClientCount = 0,
                    connectionStatus = ConnectionStatus.NoClient,
                ),
            )

            HomeCameraAction.StartScan -> state.copy(
                client = state.client.copy(scanStatus = ScanStatus.Scanning, errorMessage = null),
            )

            is HomeCameraAction.DevicesDiscovered -> state.copy(
                client = state.client.copy(
                    scanStatus = ScanStatus.Finished,
                    devices = action.devices.sortedWith(compareByDescending<CollectorDevice> { it.online }.thenBy { it.name }),
                    errorMessage = null,
                ),
            )

            is HomeCameraAction.ScanFailed -> state.copy(
                client = state.client.copy(scanStatus = ScanStatus.Error, errorMessage = action.reason),
            )

            is HomeCameraAction.OpenViewer -> {
                val device = state.client.devices.firstOrNull { it.deviceId == action.deviceId }
                if (device == null) {
                    state
                } else {
                    state.copy(
                        screen = Screen.Viewer,
                        viewer = ViewerState(
                            selectedDevice = device,
                            status = ViewerStatus.Connecting,
                        ),
                    )
                }
            }

            is HomeCameraAction.ViewerStatusChanged -> state.copy(
                viewer = state.viewer.copy(
                    status = action.status,
                    errorMessage = action.errorMessage,
                ),
            )

            HomeCameraAction.BackToClientList -> state.copy(
                screen = Screen.ClientList,
                viewer = ViewerState(),
            )

            HomeCameraAction.BackToRoleSelection -> HomeCameraState()
        }
}
