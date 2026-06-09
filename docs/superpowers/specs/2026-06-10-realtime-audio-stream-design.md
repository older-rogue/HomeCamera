# Realtime Audio Stream Design

## Goal

Add realtime microphone audio to the existing collector-to-viewer stream. The collector will send encoded audio alongside the current H.264 video stream, and the viewer will decode and play audio while continuing to render video.

This implementation is limited to realtime playback. MP4 recording will remain video-only in this phase, but the audio capture and encode path must expose the same encoded AAC samples and format events needed for a later audio track in `Mp4SegmentRecorder`.

## Current System

- `CameraH264Streamer` captures camera frames, encodes H.264 with `MediaCodec`, records video segments, and sends encoded frames through UDP.
- `H264UdpPacket` defines a video-specific UDP frame format with sequence number, timestamp, flags, fragmentation metadata, and payload.
- `H264UdpViewer` receives UDP datagrams on one client port, reassembles H.264 frames, decodes them with `MediaCodec`, and renders to a `Surface`.
- `ControlProtocol.Hello` sends video stream dimensions and FPS during TCP connection setup.
- `MainActivity` already requests `RECORD_AUDIO`, and `AndroidManifest.xml` already declares microphone and foreground microphone service permissions.

## Approach

Use AAC-LC for audio and carry audio frames over the same UDP socket as video. Generalize the current H.264 packet layer into a media packet layer that can represent multiple tracks:

- `video/h264`
- `audio/aac`

The existing video path remains functionally unchanged. Audio adds a parallel capture and encode path on the collector and a parallel decode and playback path on the viewer.

## Protocol

Replace or wrap `H264UdpPacket` with a media-oriented packet API while keeping the same fragmentation model:

- magic/version
- track type: video or audio
- codec type: H.264 or AAC
- flags
- sequence number
- presentation timestamp in microseconds
- fragment index and fragment count
- payload length and payload

Flags should continue to support:

- key frame
- codec config

For audio, codec config is used for AAC decoder initialization data when emitted by `MediaCodec`. Audio does not use key frame semantics.

The viewer receives all datagrams from the same UDP port and dispatches decoded packets by track type. Unknown track or codec values are ignored so that malformed or future packets do not crash playback.

## Collector Audio

Add an audio streaming component responsible for:

- Creating `AudioRecord` with microphone source, mono channel input, 16-bit PCM, and a stable sample rate.
- Encoding PCM to AAC-LC using `MediaCodec`.
- Emitting audio output format changes.
- Emitting AAC codec config buffers.
- Emitting AAC encoded samples with presentation timestamps.
- Stopping cleanly when collector service stops.

Initial audio settings:

- sample rate: 44,100 Hz
- channels: mono
- AAC bitrate: 64 kbps
- PCM format: 16-bit

The component should expose encoded audio events instead of directly depending on recording. In this phase, `CameraH264Streamer` will subscribe to those events and send AAC frames through UDP. In the next phase, `Mp4SegmentRecorder` can subscribe to the same output format and sample events to add an MP4 audio track.

## Viewer Audio

Extend the viewer stream receiver so one UDP receive loop feeds both media tracks:

- Video packets go to the existing H.264 reassembler and decoder.
- Audio packets go to a new AAC reassembler and decoder.

The AAC decoder outputs PCM. PCM is written to `AudioTrack` in streaming mode.

The viewer lifecycle remains controlled by the existing start/stop calls. Stopping the viewer must stop and release:

- UDP socket
- video decoder
- audio decoder
- audio track
- executor threads

## Timing

Both tracks use presentation timestamps in microseconds.

Video continues to use timestamps from the H.264 encoder. Audio timestamps are derived from captured PCM sample counts so that they advance according to the audio sample rate. This avoids wall-clock drift inside the audio stream and provides the timestamp basis needed for later muxing.

This phase does not add a full A/V synchronization jitter buffer. Realtime playback is low-latency and best-effort:

- video renders as decoded
- audio plays through `AudioTrack` buffering
- packet loss may cause short audio gaps

The shared timestamp model leaves room for a later jitter buffer if stricter A/V sync is needed.

## Control Handshake

Add audio stream metadata to `ControlMessage.Hello` with backward-compatible defaults:

- `audioEnabled`
- `audioCodec`
- `audioSampleRate`
- `audioChannelCount`
- `audioBitrate`

The viewer uses these values to configure AAC decoding and `AudioTrack`. If metadata is missing, decoding falls back to the initial defaults above.

## Error Handling

- If microphone permission is missing, collector start fails with a clear error rather than starting video-only silently.
- If audio capture or encode fails after startup, the collector should keep video streaming and report/log audio failure without crashing the service.
- If audio decode or playback fails on the viewer, video playback should continue and the viewer status should not be forced into reconnect unless the shared UDP stream itself stalls.
- Invalid media packets are ignored.

## Tests

Unit tests:

- media packet encode/decode for video and audio packets
- fragmented media frame reassembly
- invalid packet rejection
- control protocol round trip with audio metadata
- control protocol fallback defaults for missing audio metadata

Manual device verification:

- collector starts only after camera and microphone permissions are granted
- viewer displays realtime video and plays microphone audio
- stopping viewer releases audio playback
- stopping collector releases microphone capture
- reconnect restores both video and audio

## Future Recording Phase

The next phase will add audio to MP4 segment recording by extending `Mp4SegmentRecorder`:

- add an audio track after receiving the AAC output format
- wait for both required track formats before starting a muxer segment
- write AAC samples with timestamps relative to the current segment base
- rotate segments without losing track state

To support that cleanly, this phase must keep audio capture and encoding separate from UDP packet sending. The encoded audio events should be reusable by both realtime streaming and future recording.
