package com.zx.homecamera.core.app

import android.content.Intent
import com.zx.homecamera.core.protocol.RecordingEntry
import java.io.File

enum class AppRole {
    Collector,
    Client,
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
) {
    fun toIntent(intent: Intent) {
        intent.putExtra(EXTRA_DEVICE_ID, deviceId)
        intent.putExtra(EXTRA_DEVICE_NAME, name)
        intent.putExtra(EXTRA_HOST_ADDRESS, hostAddress)
        intent.putExtra(EXTRA_TCP_PORT, tcpPort)
        intent.putExtra(EXTRA_ONLINE, online)
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_HOST_ADDRESS = "host_address"
        const val EXTRA_TCP_PORT = "tcp_port"
        const val EXTRA_ONLINE = "online"

        fun fromIntent(intent: Intent): CollectorDevice? {
            val hostAddress = intent.getStringExtra(EXTRA_HOST_ADDRESS) ?: return null
            val tcpPort = intent.getIntExtra(EXTRA_TCP_PORT, -1)
            if (tcpPort < 0) return null
            return CollectorDevice(
                deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: "$hostAddress:$tcpPort",
                name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "采集端 $hostAddress",
                hostAddress = hostAddress,
                tcpPort = tcpPort,
                online = intent.getBooleanExtra(EXTRA_ONLINE, true),
            )
        }
    }
}

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
