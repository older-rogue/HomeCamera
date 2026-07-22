package com.zx.homecamera.core.app

import com.zx.homecamera.core.protocol.RecordingEntry
import java.io.File

enum class AppRole {
    Collector,
    Client,
}

enum class Screen {
    RoleSelection,
    Collector,
    ClientList,
    Viewer,
    RecordingLibrary,
    RecordingPlayback,
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

enum class RecordingLibraryStatus {
    Loading,
    Loaded,
    Error,
}

enum class RecordingPlaybackStatus {
    Loading,
    Playing,
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
)

data class ViewerState(
    val selectedDevice: CollectorDevice? = null,
    val status: ViewerStatus = ViewerStatus.Idle,
    val errorMessage: String? = null,
)

data class RecordingLibraryState(
    val status: RecordingLibraryStatus = RecordingLibraryStatus.Loading,
    val dates: List<String> = emptyList(),
    val selectedDate: String? = null,
    val files: List<RecordingEntry> = emptyList(),
    val downloadingFileId: String? = null,
    val downloadProgress: Float = 0f,
    val errorMessage: String? = null,
)

data class RecordingPlaybackState(
    val fileId: String = "",
    val status: RecordingPlaybackStatus = RecordingPlaybackStatus.Loading,
    val cachedFile: File? = null,
    val errorMessage: String? = null,
    val savedToGallery: Boolean = false,
    val savingToGallery: Boolean = false,
)

data class HomeCameraState(
    val role: AppRole? = null,
    val screen: Screen = Screen.RoleSelection,
    val collector: CollectorState = CollectorState(),
    val client: ClientState = ClientState(),
    val viewer: ViewerState = ViewerState(),
    val recordingLibrary: RecordingLibraryState = RecordingLibraryState(),
    val recordingPlayback: RecordingPlaybackState = RecordingPlaybackState(),
)

sealed interface HomeCameraAction {
    data class SelectRole(val role: AppRole) : HomeCameraAction
    data object StartCollector : HomeCameraAction
    data object CollectorStarted : HomeCameraAction
    data class CollectorFailed(val reason: String) : HomeCameraAction
    data class RecordingFailed(val reason: String) : HomeCameraAction
    data class ClientCountChanged(val count: Int) : HomeCameraAction
    data object StopCollector : HomeCameraAction
    data object StartScan : HomeCameraAction
    data class DevicesDiscovered(val devices: List<CollectorDevice>) : HomeCameraAction
    data class ScanFailed(val reason: String) : HomeCameraAction
    data class OpenViewer(val deviceId: String) : HomeCameraAction
    data class ViewerStatusChanged(val status: ViewerStatus, val errorMessage: String? = null) : HomeCameraAction
    data object BackToClientList : HomeCameraAction
    data object BackToRoleSelection : HomeCameraAction
    data object OpenRecordingLibrary : HomeCameraAction
    data object RefreshRecordingLibrary : HomeCameraAction
    data class SelectRecordingDate(val date: String) : HomeCameraAction
    data class RecordingDatesLoaded(val dates: List<String>, val errorMessage: String? = null) : HomeCameraAction
    data class RecordingFilesLoaded(val date: String, val files: List<RecordingEntry>, val errorMessage: String? = null) : HomeCameraAction
    data class DownloadRecording(val fileId: String) : HomeCameraAction
    data class DownloadProgressChanged(val fileId: String, val transferred: Long, val total: Long) : HomeCameraAction
    data class DownloadCompleted(val fileId: String, val errorMessage: String? = null) : HomeCameraAction
    data class OpenRecordingPlayback(val fileId: String) : HomeCameraAction
    data class RecordingPlaybackLoaded(val fileId: String, val cachedFile: File?, val errorMessage: String? = null) : HomeCameraAction
    data class RecordingPlaybackStatusChanged(val status: RecordingPlaybackStatus, val errorMessage: String? = null) : HomeCameraAction
    data class RecordingPlaybackSavedToGallery(val success: Boolean) : HomeCameraAction
    data object SavePlaybackToGallery : HomeCameraAction
    data object BackToViewer : HomeCameraAction
    data object BackToRecordingLibrary : HomeCameraAction
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

            is HomeCameraAction.RecordingFailed -> state.copy(
                collector = state.collector.copy(
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
                client = state.client.copy(
                    scanStatus = ScanStatus.Scanning,
                    devices = emptyList(),
                ),
            )

            is HomeCameraAction.DevicesDiscovered -> state.copy(
                client = state.client.copy(
                    scanStatus = ScanStatus.Finished,
                    devices = action.devices.sortedWith(compareByDescending<CollectorDevice> { it.online }.thenBy { it.name }),
                ),
            )

            is HomeCameraAction.ScanFailed -> state.copy(
                client = state.client.copy(scanStatus = ScanStatus.Error),
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

            HomeCameraAction.OpenRecordingLibrary -> state.copy(
                screen = Screen.RecordingLibrary,
                recordingLibrary = RecordingLibraryState(),
            )

            HomeCameraAction.RefreshRecordingLibrary -> state

            is HomeCameraAction.SelectRecordingDate -> state.copy(
                recordingLibrary = state.recordingLibrary.copy(
                    status = RecordingLibraryStatus.Loading,
                    selectedDate = action.date,
                    files = emptyList(),
                    errorMessage = null,
                ),
            )

            is HomeCameraAction.RecordingDatesLoaded -> {
                val selectedDate = state.recordingLibrary.selectedDate
                    ?: action.dates.firstOrNull()
                state.copy(
                    recordingLibrary = state.recordingLibrary.copy(
                        status = if (action.errorMessage != null) RecordingLibraryStatus.Error else RecordingLibraryStatus.Loaded,
                        dates = action.dates,
                        selectedDate = selectedDate,
                        errorMessage = action.errorMessage,
                    ),
                )
            }

            is HomeCameraAction.RecordingFilesLoaded -> state.copy(
                recordingLibrary = state.recordingLibrary.copy(
                    status = if (action.errorMessage != null) RecordingLibraryStatus.Error else RecordingLibraryStatus.Loaded,
                    selectedDate = action.date,
                    files = action.files,
                    errorMessage = action.errorMessage,
                ),
            )

            is HomeCameraAction.DownloadRecording -> state.copy(
                recordingLibrary = state.recordingLibrary.copy(
                    downloadingFileId = action.fileId,
                    downloadProgress = 0f,
                ),
            )

            is HomeCameraAction.DownloadProgressChanged -> {
                val progress = if (action.total > 0) action.transferred.toFloat() / action.total else 0f
                state.copy(
                    recordingLibrary = state.recordingLibrary.copy(
                        downloadingFileId = action.fileId,
                        downloadProgress = progress.coerceIn(0f, 1f),
                    ),
                )
            }

            is HomeCameraAction.DownloadCompleted -> state.copy(
                recordingLibrary = state.recordingLibrary.copy(
                    downloadingFileId = null,
                    downloadProgress = 0f,
                    errorMessage = action.errorMessage,
                ),
            )

            is HomeCameraAction.OpenRecordingPlayback -> state.copy(
                screen = Screen.RecordingPlayback,
                recordingPlayback = RecordingPlaybackState(
                    fileId = action.fileId,
                    status = RecordingPlaybackStatus.Loading,
                ),
            )

            is HomeCameraAction.RecordingPlaybackLoaded -> state.copy(
                recordingPlayback = state.recordingPlayback.copy(
                    fileId = action.fileId,
                    status = if (action.cachedFile != null) RecordingPlaybackStatus.Playing else RecordingPlaybackStatus.Error,
                    cachedFile = action.cachedFile,
                    errorMessage = action.errorMessage,
                ),
            )

            is HomeCameraAction.RecordingPlaybackStatusChanged -> state.copy(
                recordingPlayback = state.recordingPlayback.copy(
                    status = action.status,
                    errorMessage = action.errorMessage,
                ),
            )

            is HomeCameraAction.RecordingPlaybackSavedToGallery -> state.copy(
                recordingPlayback = state.recordingPlayback.copy(
                    savedToGallery = action.success,
                    savingToGallery = false,
                ),
            )

            HomeCameraAction.SavePlaybackToGallery -> state

            HomeCameraAction.BackToViewer -> state.copy(
                screen = Screen.Viewer,
                recordingLibrary = RecordingLibraryState(),
            )

            HomeCameraAction.BackToRecordingLibrary -> state.copy(
                screen = Screen.RecordingLibrary,
                recordingPlayback = RecordingPlaybackState(),
            )
        }
}
