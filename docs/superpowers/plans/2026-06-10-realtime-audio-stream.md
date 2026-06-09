# Realtime Audio Stream Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add realtime AAC microphone audio to the existing collector-to-viewer UDP stream while preserving the existing H.264 video behavior.

**Architecture:** Generalize the H.264 UDP packet format into a media packet format that carries a media track and codec. The collector adds an AAC encoder fed by `AudioRecord`, sends audio frames through the same UDP client list, and the viewer dispatches received frames to H.264 video decode or AAC audio decode plus `AudioTrack` playback.

**Tech Stack:** Android Kotlin, Camera2, `AudioRecord`, `AudioTrack`, `MediaCodec`, UDP datagrams, JUnit.

---

## File Structure

- Create `app/src/main/java/com/zx/homecamera/core/protocol/MediaUdpPacket.kt`: media packet model, encode/decode, frame reassembly for audio and video.
- Modify `app/src/main/java/com/zx/homecamera/core/protocol/H264UdpPacket.kt`: keep compatibility facade over `MediaUdpPacket` so existing video tests and callers can migrate incrementally.
- Modify `app/src/test/java/com/zx/homecamera/core/protocol/H264UdpPacketTest.kt`: retain existing video coverage through the compatibility facade.
- Create `app/src/test/java/com/zx/homecamera/core/protocol/MediaUdpPacketTest.kt`: test audio/video media packet encode/decode and reassembly.
- Modify `app/src/main/java/com/zx/homecamera/core/protocol/ControlProtocol.kt`: add audio metadata to `Hello` with defaults.
- Modify `app/src/test/java/com/zx/homecamera/core/protocol/ControlProtocolTest.kt`: cover audio metadata round trip and fallback defaults.
- Create `app/src/main/java/com/zx/homecamera/audio/AacAudioConfig.kt`: shared audio defaults used by collector, viewer, and control handshake.
- Create `app/src/main/java/com/zx/homecamera/audio/AacAudioStreamer.kt`: microphone capture and AAC encode component that emits encoded events.
- Modify `app/src/main/java/com/zx/homecamera/video/CameraH264Streamer.kt`: start/stop `AacAudioStreamer`, send audio frames over UDP, expose audio config.
- Modify `app/src/main/java/com/zx/homecamera/service/CollectorForegroundService.kt`: include audio metadata in `HELLO`.
- Modify `app/src/main/java/com/zx/homecamera/network/LanViewerConnector.kt`: carry audio metadata in `ViewerConnection`.
- Modify `app/src/main/java/com/zx/homecamera/network/H264UdpViewer.kt`: dispatch media frames by track, decode AAC, and play PCM through `AudioTrack`.

## Task 1: Media UDP Protocol

**Files:**
- Create: `app/src/main/java/com/zx/homecamera/core/protocol/MediaUdpPacket.kt`
- Modify: `app/src/main/java/com/zx/homecamera/core/protocol/H264UdpPacket.kt`
- Test: `app/src/test/java/com/zx/homecamera/core/protocol/MediaUdpPacketTest.kt`
- Test: `app/src/test/java/com/zx/homecamera/core/protocol/H264UdpPacketTest.kt`

- [ ] **Step 1: Write failing protocol tests**

Add `MediaUdpPacketTest` with tests for video packet metadata, audio packet metadata, fragmented reassembly, and invalid datagrams.

- [ ] **Step 2: Run failing protocol tests**

Run: `./gradlew testDebugUnitTest --tests com.zx.homecamera.core.protocol.MediaUdpPacketTest`

Expected: fail because `MediaUdpPacket` does not exist.

- [ ] **Step 3: Implement media packet protocol**

Create `MediaUdpPacket` with enums for `MediaTrack` and `MediaCodecType`, flags for key frame and codec config, `encodeFrame`, `decode`, and `MediaFrameReassembler`.

- [ ] **Step 4: Keep H.264 compatibility facade**

Change `H264UdpPacket` so `encodeFrame` and `decode` delegate to `MediaUdpPacket` using `MediaTrack.Video` and `MediaCodecType.H264`. Keep the existing `H264Packet`, `EncodedH264Frame`, and `H264FrameReassembler` public API.

- [ ] **Step 5: Run protocol tests**

Run: `./gradlew testDebugUnitTest --tests com.zx.homecamera.core.protocol.MediaUdpPacketTest --tests com.zx.homecamera.core.protocol.H264UdpPacketTest`

Expected: pass.

## Task 2: Control Handshake Audio Metadata

**Files:**
- Create: `app/src/main/java/com/zx/homecamera/audio/AacAudioConfig.kt`
- Modify: `app/src/main/java/com/zx/homecamera/core/protocol/ControlProtocol.kt`
- Modify: `app/src/main/java/com/zx/homecamera/network/LanViewerConnector.kt`
- Test: `app/src/test/java/com/zx/homecamera/core/protocol/ControlProtocolTest.kt`

- [ ] **Step 1: Write failing handshake tests**

Extend `ControlProtocolTest` so `Hello` round-trips `audioEnabled=true`, `audioCodec=aac`, `audioSampleRate=44100`, `audioChannelCount=1`, and `audioBitrate=64000`. Add a fallback test that decodes a legacy `HELLO` without audio fields and gets the same defaults.

- [ ] **Step 2: Run failing handshake tests**

Run: `./gradlew testDebugUnitTest --tests com.zx.homecamera.core.protocol.ControlProtocolTest`

Expected: fail because the audio fields do not exist.

- [ ] **Step 3: Add audio config and handshake fields**

Add shared defaults in `AacAudioConfig`, add fields to `ControlMessage.Hello`, encode/decode them with default values, and add the fields to `ViewerConnection`.

- [ ] **Step 4: Run handshake tests**

Run: `./gradlew testDebugUnitTest --tests com.zx.homecamera.core.protocol.ControlProtocolTest`

Expected: pass.

## Task 3: Collector AAC Encoder

**Files:**
- Create: `app/src/main/java/com/zx/homecamera/audio/AacAudioStreamer.kt`
- Modify: `app/src/main/java/com/zx/homecamera/video/CameraH264Streamer.kt`

- [ ] **Step 1: Add audio streamer component**

Implement `AacAudioStreamer` with:

- `start(onEvent: (EncodedAudioEvent) -> Unit, onError: (Throwable) -> Unit)`
- `stop()`
- `EncodedAudioEvent.FormatChanged`
- `EncodedAudioEvent.Sample`

Use `AudioRecord` mono 16-bit PCM and AAC-LC `MediaCodec`. Compute audio timestamps from total PCM frames submitted to the encoder.

- [ ] **Step 2: Wire collector lifecycle**

In `CameraH264Streamer.start`, verify `RECORD_AUDIO` permission, start `AacAudioStreamer` after the UDP socket is ready, and send AAC events through the same `sendPayload` helper using `MediaTrack.Audio`.

- [ ] **Step 3: Preserve future recording seam**

Keep `EncodedAudioEvent.FormatChanged` and `EncodedAudioEvent.Sample` independent from UDP sending. Do not pass `Mp4SegmentRecorder` into `AacAudioStreamer`.

## Task 4: Viewer AAC Decode and Playback

**Files:**
- Modify: `app/src/main/java/com/zx/homecamera/network/H264UdpViewer.kt`

- [ ] **Step 1: Split receive dispatch**

Change the UDP loop to decode `MediaUdpPacket`, dispatch video frames to the existing decoder, and dispatch audio frames to an AAC decoder path.

- [ ] **Step 2: Add audio decoder lifecycle**

Configure `MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)` using `ViewerConnection` audio metadata, drain PCM output buffers, and write them to `AudioTrack` in streaming mode.

- [ ] **Step 3: Make audio failure non-fatal**

If AAC decoder or `AudioTrack` setup fails, disable audio for the current session while keeping video playback and UDP stall detection active.

## Task 5: Service Handshake and Verification

**Files:**
- Modify: `app/src/main/java/com/zx/homecamera/service/CollectorForegroundService.kt`
- Modify: `app/src/main/java/com/zx/homecamera/network/LanViewerConnector.kt`
- Test: impacted unit tests

- [ ] **Step 1: Include collector audio metadata**

When responding with `ControlMessage.Hello`, populate audio fields from `CameraH264Streamer.audioConfig()`.

- [ ] **Step 2: Run unit test suite**

Run: `./gradlew testDebugUnitTest`

Expected: pass.

- [ ] **Step 3: Build the app**

Run: `./gradlew assembleDebug`

Expected: pass.

- [ ] **Step 4: Manual verification on devices**

Start one device as collector and one as client. Verify video appears, microphone audio plays, stopping/reconnecting releases and restores audio, and collector stop releases microphone capture.

## Future Recording Hook

Do not implement recording audio in this plan. The required follow-up is:

- extend `Mp4SegmentRecorder` to accept an audio `MediaFormat`
- add an audio track to each `MediaMuxer`
- write AAC samples using the timestamps emitted by `AacAudioStreamer`
- start segments only when the required track formats are available
