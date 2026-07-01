package com.zx.homecamera.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlProtocolTest {
    @Test
    fun helloMessageRoundTripsWithDeviceInfo() {
        val message = ControlMessage.Hello(
            deviceId = "collector-1",
            deviceName = "客厅旧手机",
            udpPort = 62010,
            streamWidth = 640,
            streamHeight = 480,
            displayWidth = 480,
            displayHeight = 640,
            streamFps = 15,
            audioEnabled = true,
            audioCodec = "aac",
            audioSampleRate = 44_100,
            audioChannelCount = 1,
            audioBitrate = 64_000,
        )

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun helloMessageUsesAudioDefaultsForLegacyCollectors() {
        val legacy = listOf(
            "HOME_CAMERA_CONTROL",
            "version=1",
            "type=HELLO",
            "deviceId=collector-1",
            "deviceName=legacy",
            "udpPort=62010",
            "streamWidth=640",
            "streamHeight=480",
            "displayWidth=480",
            "displayHeight=640",
            "streamFps=15",
        ).joinToString("|")

        val decoded = ControlProtocol.decode(legacy)

        assertEquals(
            ControlMessage.Hello(
                deviceId = "collector-1",
                deviceName = "legacy",
                udpPort = 62010,
                streamWidth = 640,
                streamHeight = 480,
                displayWidth = 480,
                displayHeight = 640,
                streamFps = 15,
                audioEnabled = true,
                audioCodec = "aac",
                audioSampleRate = 44_100,
                audioChannelCount = 1,
                audioBitrate = 64_000,
            ),
            decoded,
        )
    }

    @Test
    fun viewStartMessageRoundTripsWithClientUdpPort() {
        val message = ControlMessage.ViewStart(
            clientId = "client-phone",
            udpPort = 62011,
        )

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun requestKeyFrameMessageRoundTripsWithReason() {
        val message = ControlMessage.RequestKeyFrame(reason = "video_queue_drop")

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun pingMessageRoundTripsWithTimestamp() {
        val message = ControlMessage.Ping(timestampMillis = 123_456L)

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun pongMessageRoundTripsWithTimestamp() {
        val message = ControlMessage.Pong(timestampMillis = 123_456L)

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun byeMessageRoundTripsWithReason() {
        val message = ControlMessage.Bye(reason = "viewer_stop")

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun invalidMessageReturnsNull() {
        assertNull(ControlProtocol.decode("NOT_HOME_CAMERA|value=1"))
    }
}
