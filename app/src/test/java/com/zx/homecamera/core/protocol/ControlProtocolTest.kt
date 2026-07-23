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

    @Test
    fun listRecordingsWithoutDateRoundTrips() {
        val message = ControlMessage.ListRecordings(date = null)

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun listRecordingsWithDateRoundTrips() {
        val message = ControlMessage.ListRecordings(date = "2026-07-21")

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun recordingListRoundTripsWithDatesAndFiles() {
        val message = ControlMessage.RecordingList(
            dates = listOf("2026-07-21", "2026-07-20"),
            files = listOf(
                RecordingEntry(fileId = "2026-07-21/14-30-00.mp4", sizeBytes = 12_345L, startMillis = 1_700_000_000_000L, recording = false),
                RecordingEntry(fileId = "2026-07-21/14-40-00.mp4", sizeBytes = 67_890L, startMillis = 1_700_000_600_000L, recording = true),
            ),
        )

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun recordingListDecodesLegacyThreeFieldEntriesWithoutRecordingFlag() {
        // 旧采集端只发 fileId,sizeBytes,startMillis 三字段，recording 应默认 false。
        val legacy = listOf(
            "HOME_CAMERA_CONTROL",
            "version=1",
            "type=RECORDING_LIST",
            "dates=2026-07-21",
            "files=2026-07-21/14-30-00.mp4,12345,1700000000000",
        ).joinToString("|")

        val decoded = ControlProtocol.decode(legacy) as? ControlMessage.RecordingList

        assertEquals(listOf("2026-07-21"), decoded?.dates)
        val entry = decoded?.files?.singleOrNull()
        assertEquals("2026-07-21/14-30-00.mp4", entry?.fileId)
        assertEquals(12_345L, entry?.sizeBytes)
        assertEquals(1_700_000_000_000L, entry?.startMillis)
        assertEquals(false, entry?.recording)
    }

    @Test
    fun recordingListHandlesEmptyDatesAndFiles() {
        val message = ControlMessage.RecordingList(dates = emptyList(), files = emptyList())

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun openRecordingRoundTripsWithFileId() {
        val message = ControlMessage.OpenRecording(fileId = "2026-07-21/14-30-00.mp4")

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun recordingReadyRoundTripsWithTransferPort() {
        val message = ControlMessage.RecordingReady(
            fileId = "2026-07-21/14-30-00.mp4",
            sizeBytes = 12_345L,
            transferPort = 51234,
        )

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun fileIdWithSlashesIsUrlEncodedSafely() {
        // fileId 含路径分隔符 /，需确保 encode/decode 不破坏路径结构。
        val message = ControlMessage.OpenRecording(fileId = "2026-07-21/14-30-00.mp4")
        val encoded = ControlProtocol.encode(message)
        // URL 编码中 / 可被部分实现保留，但解码后必须还原。
        val decoded = ControlProtocol.decode(encoded)
        assertEquals(message, decoded)
    }

    @Test
    fun discoveryMessageRoundTripsWithCollectorInfo() {
        val message = ControlMessage.Discovery(
            deviceId = "collector-1",
            deviceName = "客厅旧手机",
            controlPort = 62001,
        )

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun discoveryDecodesMessageWithUrlEncodedDeviceName() {
        // 采集端名称可能含特殊字符（如空格、中文），需 URL 编码后安全往返。
        val message = ControlMessage.Discovery(
            deviceId = "collector-1",
            deviceName = "客厅 旧手机",
            controlPort = 62001,
        )

        val encoded = ControlProtocol.encode(message)
        val decoded = ControlProtocol.decode(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun discoveryReturnsNullWhenControlPortMissing() {
        val incomplete = listOf(
            "HOME_CAMERA_CONTROL",
            "version=1",
            "type=DISCOVERY",
            "deviceId=collector-1",
            "deviceName=客厅",
        ).joinToString("|")

        assertNull(ControlProtocol.decode(incomplete))
    }
}
