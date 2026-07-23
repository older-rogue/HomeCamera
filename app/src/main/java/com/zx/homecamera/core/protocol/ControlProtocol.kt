package com.zx.homecamera.core.protocol

import com.zx.homecamera.audio.AacAudioConfig
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed interface ControlMessage {
    data class Hello(
        val deviceId: String,
        val deviceName: String,
        val udpPort: Int,
        val streamWidth: Int = 640,
        val streamHeight: Int = 480,
        val displayWidth: Int = streamWidth,
        val displayHeight: Int = streamHeight,
        val streamFps: Int = 15,
        val audioEnabled: Boolean = AacAudioConfig.DEFAULT_ENABLED,
        val audioCodec: String = AacAudioConfig.CODEC,
        val audioSampleRate: Int = AacAudioConfig.SAMPLE_RATE,
        val audioChannelCount: Int = AacAudioConfig.CHANNEL_COUNT,
        val audioBitrate: Int = AacAudioConfig.BITRATE,
    ) : ControlMessage

    data class ViewStart(
        val clientId: String,
        val udpPort: Int,
    ) : ControlMessage

    data class Ping(val timestampMillis: Long) : ControlMessage

    data class Pong(val timestampMillis: Long) : ControlMessage

    data class RequestKeyFrame(val reason: String) : ControlMessage

    data class Bye(val reason: String) : ControlMessage

    /**
     * 客户端请求录像列表。date 为空时返回所有可用日期；指定日期时返回该日期的文件列表。
     */
    data class ListRecordings(val date: String? = null) : ControlMessage

    /**
     * 采集端返回录像列表。dates 为所有可用日期；files 为（指定日期时的）文件列表。
     */
    data class RecordingList(
        val dates: List<String>,
        val files: List<RecordingEntry>,
    ) : ControlMessage

    /**
     * 客户端请求打开某录像文件（用于播放或下载）。
     */
    data class OpenRecording(val fileId: String) : ControlMessage

    /**
     * 采集端为该文件开了一个独立 TCP 端口传字节流，客户端连接该端口拉取文件内容。
     */
    data class RecordingReady(
        val fileId: String,
        val sizeBytes: Long,
        val transferPort: Int,
    ) : ControlMessage

    /**
     * 采集端通过 UDP 广播周期性发送的发现通告，客户端据此秒级发现采集端，
     * 无需逐个探测子网主机。controlPort 为采集端的 TCP 控制端口。
     */
    data class Discovery(
        val deviceId: String,
        val deviceName: String,
        val controlPort: Int,
    ) : ControlMessage
}

data class RecordingEntry(
    val fileId: String,
    val sizeBytes: Long,
    val startMillis: Long,
    val recording: Boolean = false,
    val corrupted: Boolean = false,
)

object ControlProtocol {
    private const val PREFIX = "HOME_CAMERA_CONTROL"
    private const val VERSION = "1"

    fun encode(message: ControlMessage): String {
        val fields = when (message) {
            is ControlMessage.Hello -> listOf(
                "type" to "HELLO",
                "deviceId" to message.deviceId,
                "deviceName" to message.deviceName,
                "udpPort" to message.udpPort.toString(),
                "streamWidth" to message.streamWidth.toString(),
                "streamHeight" to message.streamHeight.toString(),
                "displayWidth" to message.displayWidth.toString(),
                "displayHeight" to message.displayHeight.toString(),
                "streamFps" to message.streamFps.toString(),
                "audioEnabled" to message.audioEnabled.toString(),
                "audioCodec" to message.audioCodec,
                "audioSampleRate" to message.audioSampleRate.toString(),
                "audioChannelCount" to message.audioChannelCount.toString(),
                "audioBitrate" to message.audioBitrate.toString(),
            )

            is ControlMessage.ViewStart -> listOf(
                "type" to "VIEW_START",
                "clientId" to message.clientId,
                "udpPort" to message.udpPort.toString(),
            )

            is ControlMessage.Ping -> listOf(
                "type" to "PING",
                "timestampMillis" to message.timestampMillis.toString(),
            )

            is ControlMessage.Pong -> listOf(
                "type" to "PONG",
                "timestampMillis" to message.timestampMillis.toString(),
            )

            is ControlMessage.RequestKeyFrame -> listOf(
                "type" to "REQUEST_KEY_FRAME",
                "reason" to message.reason,
            )

            is ControlMessage.Bye -> listOf(
                "type" to "BYE",
                "reason" to message.reason,
            )

            is ControlMessage.ListRecordings -> buildList {
                add("type" to "LIST_RECORDINGS")
                message.date?.let { add("date" to it) }
            }

            is ControlMessage.RecordingList -> listOf(
                "type" to "RECORDING_LIST",
                "dates" to message.dates.joinToString(";"),
                "files" to message.files.joinToString(";") { entry ->
                    "${entry.fileId},${entry.sizeBytes},${entry.startMillis},${entry.recording},${entry.corrupted}"
                },
            )

            is ControlMessage.OpenRecording -> listOf(
                "type" to "OPEN_RECORDING",
                "fileId" to message.fileId,
            )

            is ControlMessage.RecordingReady -> listOf(
                "type" to "RECORDING_READY",
                "fileId" to message.fileId,
                "sizeBytes" to message.sizeBytes.toString(),
                "transferPort" to message.transferPort.toString(),
            )

            is ControlMessage.Discovery -> listOf(
                "type" to "DISCOVERY",
                "deviceId" to message.deviceId,
                "deviceName" to message.deviceName,
                "controlPort" to message.controlPort.toString(),
            )
        }
        return listOf(PREFIX, "version=$VERSION")
            .plus(fields.map { (key, value) -> "$key=${value.urlEncode()}" })
            .joinToString("|")
    }

    fun decode(line: String): ControlMessage? {
        val parts = line.split("|")
        if (parts.size < 3 || parts.first() != PREFIX) return null

        val fields = parts.drop(1)
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null else {
                    part.substring(0, separator) to part.substring(separator + 1).urlDecode()
                }
            }
            .toMap()

        if (fields["version"] != VERSION) return null

        return when (fields["type"]) {
            "HELLO" -> ControlMessage.Hello(
                deviceId = fields["deviceId"] ?: return null,
                deviceName = fields["deviceName"] ?: return null,
                udpPort = fields["udpPort"]?.toIntOrNull() ?: return null,
                streamWidth = fields["streamWidth"]?.toIntOrNull() ?: 640,
                streamHeight = fields["streamHeight"]?.toIntOrNull() ?: 480,
                displayWidth = fields["displayWidth"]?.toIntOrNull()
                    ?: fields["streamWidth"]?.toIntOrNull()
                    ?: 640,
                displayHeight = fields["displayHeight"]?.toIntOrNull()
                    ?: fields["streamHeight"]?.toIntOrNull()
                    ?: 480,
                streamFps = fields["streamFps"]?.toIntOrNull() ?: 15,
                audioEnabled = fields["audioEnabled"]?.toBooleanStrictOrNull()
                    ?: AacAudioConfig.DEFAULT_ENABLED,
                audioCodec = fields["audioCodec"] ?: AacAudioConfig.CODEC,
                audioSampleRate = fields["audioSampleRate"]?.toIntOrNull()
                    ?: AacAudioConfig.SAMPLE_RATE,
                audioChannelCount = fields["audioChannelCount"]?.toIntOrNull()
                    ?: AacAudioConfig.CHANNEL_COUNT,
                audioBitrate = fields["audioBitrate"]?.toIntOrNull()
                    ?: AacAudioConfig.BITRATE,
            )

            "VIEW_START" -> ControlMessage.ViewStart(
                clientId = fields["clientId"] ?: return null,
                udpPort = fields["udpPort"]?.toIntOrNull() ?: return null,
            )

            "PING" -> ControlMessage.Ping(
                timestampMillis = fields["timestampMillis"]?.toLongOrNull() ?: return null,
            )

            "PONG" -> ControlMessage.Pong(
                timestampMillis = fields["timestampMillis"]?.toLongOrNull() ?: return null,
            )

            "REQUEST_KEY_FRAME" -> ControlMessage.RequestKeyFrame(
                reason = fields["reason"] ?: "",
            )

            "BYE" -> ControlMessage.Bye(
                reason = fields["reason"] ?: return null,
            )

            "LIST_RECORDINGS" -> ControlMessage.ListRecordings(
                date = fields["date"]?.takeIf { it.isNotEmpty() },
            )

            "RECORDING_LIST" -> ControlMessage.RecordingList(
                dates = fields["dates"]?.split(";")?.filter { it.isNotEmpty() } ?: emptyList(),
                files = fields["files"]?.split(";")?.filter { it.isNotEmpty() }?.mapNotNull { entry ->
                    val parts = entry.split(",")
                    if (parts.size < 3) return@mapNotNull null
                    val fileId = parts[0]
                    val sizeBytes = parts[1].toLongOrNull() ?: return@mapNotNull null
                    val startMillis = parts[2].toLongOrNull() ?: return@mapNotNull null
                    // 第 4 字段 recording 可选，兼容旧采集端只发 3 字段的情况
                    val recording = parts.getOrNull(3)?.toBooleanStrictOrNull() ?: false
                    // 第 5 字段 corrupted 可选，兼容旧采集端只发 4 字段的情况
                    val corrupted = parts.getOrNull(4)?.toBooleanStrictOrNull() ?: false
                    RecordingEntry(fileId, sizeBytes, startMillis, recording, corrupted)
                } ?: emptyList(),
            )

            "OPEN_RECORDING" -> ControlMessage.OpenRecording(
                fileId = fields["fileId"] ?: return null,
            )

            "RECORDING_READY" -> ControlMessage.RecordingReady(
                fileId = fields["fileId"] ?: return null,
                sizeBytes = fields["sizeBytes"]?.toLongOrNull() ?: return null,
                transferPort = fields["transferPort"]?.toIntOrNull() ?: return null,
            )

            "DISCOVERY" -> ControlMessage.Discovery(
                deviceId = fields["deviceId"] ?: return null,
                deviceName = fields["deviceName"] ?: return null,
                controlPort = fields["controlPort"]?.toIntOrNull() ?: return null,
            )

            else -> null
        }
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun String.urlDecode(): String =
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())
}
