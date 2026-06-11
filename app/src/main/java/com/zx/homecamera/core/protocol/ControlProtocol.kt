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
}

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

            else -> null
        }
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun String.urlDecode(): String =
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())
}
