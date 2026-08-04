package com.zx.homecamera.core.app

import android.content.Intent
import com.zx.homecamera.core.protocol.RecordingEntry

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

enum class DownloadStatus {
    Queued,
    Downloading,
}

data class DownloadTaskState(
    val status: DownloadStatus,
    val progress: Float,
)

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
    val downloads: Map<String, DownloadTaskState> = emptyMap(),
    val downloadedFileIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
    /**
     * 首帧预览缩略图。key = fileId，value = 已解码的 Bitmap。
     * 用 android.graphics.Bitmap 而非 Compose ImageBitmap，保持 core/app 不耦合 Compose；
     * UI 层用 asImageBitmap() 转换。仿 [downloads] 的 map 范式，新增缩略图时整体 copy 触发重组。
     * 仅缓存当前已加载过的 fileId；未加载或损坏/录制中（采集端返回 404）的不在此 map 中。
     */
    val thumbnails: Map<String, android.graphics.Bitmap> = emptyMap(),
)

data class RecordingPlaybackState(
    val fileId: String = "",
    val status: RecordingPlaybackStatus = RecordingPlaybackStatus.Loading,
    val playbackUrl: String? = null,
    val errorMessage: String? = null,
)
