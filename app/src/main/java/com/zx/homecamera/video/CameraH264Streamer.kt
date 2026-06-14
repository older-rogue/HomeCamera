package com.zx.homecamera.video

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import com.zx.homecamera.audio.AacAudioConfig
import com.zx.homecamera.audio.AacAudioStreamer
import com.zx.homecamera.audio.EncodedAudioEvent
import com.zx.homecamera.core.protocol.MediaCodecType
import com.zx.homecamera.core.protocol.MediaTrack
import com.zx.homecamera.core.protocol.MediaUdpPacket
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CameraH264Streamer(
    private val context: Context,
    recordingRoot: File? = context.getExternalFilesDir(null)?.resolve("recordings"),
) {
    private val running = AtomicBoolean(false)
    private val sequenceNumber = AtomicInteger(0)
    private val clients = CopyOnWriteArraySet<StreamClient>()
    private var drainExecutor: ExecutorService? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureSessionGeneration = 0
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    @Volatile
    private var previewHolder: SurfaceHolder? = null
    @Volatile
    private var previewTargetSize: VideoSize = VideoSize(0, 0)
    @Volatile
    private var previewDisplaySizeListener: ((VideoSize) -> Unit)? = null
    @Volatile
    private var displayRotationDegrees: Int = 0
    private var selectedCameraId: String? = null
    private var selectedPreviewSize: PreviewSize? = null
    @Volatile
    private var streamSelection: H264StreamSelection = H264StreamSelection(
        bufferSize = VideoSize(H264StreamConfig.WIDTH, H264StreamConfig.HEIGHT),
        displaySize = VideoSize(H264StreamConfig.HEIGHT, H264StreamConfig.WIDTH),
        fps = H264StreamConfig.FPS,
        bitrate = H264StreamConfig.BITRATE,
        iFrameIntervalSeconds = H264StreamConfig.I_FRAME_INTERVAL_SECONDS,
    )
    private var appliedPreviewSize: PreviewSize? = null
    private var autofocusMode: Int? = null
    private var targetFpsRange: CameraFpsRange? = null
    private var streamSocket: DatagramSocket? = null
    private var realtimeSender: RealtimeUdpSender? = null
    @Volatile
    private var latestCodecConfig: ByteArray? = null
    private var latestAudioCodecConfig: ByteArray? = null
    private var audioStreamer: AacAudioStreamer? = null
    private val recorder = recordingRoot?.let(::Mp4SegmentRecorder)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            running.set(false)
            throw SecurityException("Camera permission is required")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running.set(false)
            throw SecurityException("Microphone permission is required")
        }

        cameraThread = HandlerThread("camera-h264-streamer").also { thread ->
            thread.start()
            cameraHandler = Handler(thread.looper)
        }
        streamSocket = DatagramSocket().also { socket ->
            socket.sendBufferSize = UDP_SOCKET_BUFFER_BYTES
            realtimeSender = RealtimeUdpSender(
                clients = {
                    clients.map { client ->
                        StreamDestination(client.address.hostAddress ?: client.address.hostName, client.udpPort)
                    }
                },
                socket = DatagramRealtimeUdpSocket(socket),
            ).also(RealtimeUdpSender::start)
        }
        selectCameraAndStreamConfig()
        startEncoder()
        openCamera()
        drainExecutor = Executors.newSingleThreadExecutor().also { executor ->
            executor.execute(::drainEncoder)
        }
        startAudioStreamer()
    }

    fun stop() {
        running.set(false)
        clients.clear()
        closeCamera()
        previewHolder = null
        appliedPreviewSize = null
        drainExecutor?.shutdownNow()
        drainExecutor?.awaitTermination(2_000, TimeUnit.MILLISECONDS)
        drainExecutor = null
        encoderInputSurface?.runCatching { release() }
        encoderInputSurface = null
        encoder?.runCatching { stop() }
        encoder?.runCatching { release() }
        encoder = null
        audioStreamer?.stop()
        audioStreamer = null
        latestAudioCodecConfig = null
        recorder?.stop()
        realtimeSender?.stop()
        realtimeSender = null
        streamSocket?.runCatching { close() }
        streamSocket = null
        cameraThread?.runCatching { quitSafely() }
        cameraHandler = null
        cameraThread = null
    }

    fun addClient(address: InetAddress, udpPort: Int) {
        clients.add(StreamClient(address, udpPort))
        latestCodecConfig?.let { config ->
            sendMediaPayload(
                track = MediaTrack.Video,
                codec = MediaCodecType.H264,
                data = config,
                flags = MediaUdpPacket.FLAG_CODEC_CONFIG,
                timestampMicros = System.nanoTime() / 1_000L,
            )
        }
        latestAudioCodecConfig?.let { config ->
            sendMediaPayload(
                track = MediaTrack.Audio,
                codec = MediaCodecType.Aac,
                data = config,
                flags = MediaUdpPacket.FLAG_CODEC_CONFIG,
                timestampMicros = System.nanoTime() / 1_000L,
            )
        }
        requestKeyFrame()
    }

    fun setPreviewSurface(holder: SurfaceHolder?, width: Int = 0, height: Int = 0) {
        previewHolder = holder?.takeIf { it.surface?.isValid == true }
        previewTargetSize = VideoSize(width, height)
        val handler = cameraHandler
        val camera = cameraDevice
        if (handler != null && camera != null && running.get()) {
            handler.post { startCaptureSession(camera) }
        }
    }

    fun setPreviewDisplaySizeListener(listener: ((VideoSize) -> Unit)?) {
        previewDisplaySizeListener = listener
        selectedPreviewSize?.let { size ->
            previewDisplaySizeListener?.invoke(size.displaySize)
        }
    }

    fun setDisplayRotationDegrees(degrees: Int) {
        displayRotationDegrees = degrees
        selectedPreviewSize?.let { size ->
            previewDisplaySizeListener?.invoke(size.displaySize)
        }
    }

    fun setPreselectedPreviewSize(size: PreviewSize?) {
        selectedPreviewSize = size
        size?.let { previewDisplaySizeListener?.invoke(it.displaySize) }
    }

    fun streamConfig(): H264StreamSelection = streamSelection

    fun audioConfig(): AacAudioConfig =
        audioStreamer?.config ?: AacAudioConfig.Default

    private fun startEncoder() {
        val selection = streamSelection
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            selection.bufferSize.width,
            selection.bufferSize.height,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, selection.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, selection.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, selection.iFrameIntervalSeconds)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec ->
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = codec.createInputSurface()
            codec.start()
        }
    }

    private fun selectCameraAndStreamConfig() {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = CameraPreviewConfigReader.findCameraId(manager)
        selectedCameraId = cameraId
        val characteristics = manager.getCameraCharacteristics(cameraId)
        autofocusMode = CameraFocusModeSelector.choose(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES))
        val supportedSizes = CameraPreviewConfigReader.run {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).encoderSizes()
        }
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val target = previewTargetSize
        streamSelection = H264StreamConfigSelector.choose(
            supportedSizes = supportedSizes,
            surfaceWidth = target.width,
            surfaceHeight = target.height,
            sensorOrientationDegrees = sensorOrientation,
            displayRotationDegrees = displayRotationDegrees,
        )
        val availableFpsRanges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { range -> CameraFpsRange(range.lower, range.upper) }
            .orEmpty()
        targetFpsRange = CameraFpsRangeSelector.choose(
            ranges = availableFpsRanges,
            targetFps = streamSelection.fps,
        )
        val availableFpsRangesLog = availableFpsRanges.joinToString(
            prefix = "[",
            postfix = "]",
        ) { range -> range.formatForLog() }
        Log.i(
            TAG,
            "stream config: camera=$cameraId " +
                "buffer=${streamSelection.bufferSize.width}x${streamSelection.bufferSize.height} " +
                "display=${streamSelection.displaySize.width}x${streamSelection.displaySize.height} " +
                "fps=${streamSelection.fps} bitrate=${streamSelection.bitrate} " +
                "iFrame=${streamSelection.iFrameIntervalSeconds}s " +
                "aeRange=${targetFpsRange.formatForLog()} " +
                "availableAeRanges=$availableFpsRangesLog",
        )
    }

    private fun CameraFpsRange?.formatForLog(): String =
        this?.let { "${it.min}-${it.max}" } ?: "none"

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = selectedCameraId ?: CameraPreviewConfigReader.findCameraId(manager)
        manager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!running.get()) {
                        camera.runCatching { close() }
                        return
                    }
                    cameraDevice = camera
                    startCaptureSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.runCatching { close() }
                    if (cameraDevice == camera) cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.runCatching { close() }
                    if (cameraDevice == camera) cameraDevice = null
                }
            },
            cameraHandler,
        )
    }

    private fun startCaptureSession(camera: CameraDevice) {
        if (!running.get()) return
        val inputSurface = encoderInputSurface ?: return
        val generation = ++captureSessionGeneration
        val targets = buildList {
            add(inputSurface)
            previewHolder?.takeIf { it.surface?.isValid == true }?.let { holder ->
                resolvePreviewSize()?.let { size ->
                    if (size.needsFixedSizeUpdate(appliedPreviewSize)) {
                        holder.setFixedSize(size.bufferSize.width, size.bufferSize.height)
                        appliedPreviewSize = size
                    }
                    selectedPreviewSize = size
                    previewDisplaySizeListener?.invoke(size.displaySize)
                }
                add(holder.surface)
            }
        }
        captureSession?.runCatching { close() }
        captureSession = null
        runCatching {
            camera.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running.get() || generation != captureSessionGeneration) {
                            session.runCatching { close() }
                            return
                        }
                        captureSession = session
                        runCatching {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                targets.forEach(::addTarget)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                autofocusMode?.let { mode ->
                                    set(CaptureRequest.CONTROL_AF_MODE, mode)
                                }
                                targetFpsRange?.let { range ->
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(range.min, range.max))
                                }
                            }.build()
                            session.setRepeatingRequest(request, null, cameraHandler)
                            Log.i(
                                TAG,
                                "capture request started: generation=$generation " +
                                    "targets=${targets.size} aeRange=${targetFpsRange.formatForLog()}",
                            )
                        }.onFailure {
                            if (captureSession == session) {
                                captureSession = null
                            }
                            session.runCatching { close() }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (generation == captureSessionGeneration && captureSession == session) {
                            captureSession = null
                        }
                        session.runCatching { close() }
                    }
                },
                cameraHandler,
            )
        }.onFailure {
            if (generation == captureSessionGeneration) {
                captureSession = null
            }
        }
    }

    private fun drainEncoder() {
        val codec = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)

                            val flags = when {
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ->
                                    MediaUdpPacket.FLAG_CODEC_CONFIG
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ->
                                    MediaUdpPacket.FLAG_KEY_FRAME
                                else -> 0
                            }
                            if (flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0) {
                                latestCodecConfig = data
                            }
                            sendVideoFrame(data, flags, bufferInfo.presentationTimeUs)
                            recorder?.writeSample(data, flags, bufferInfo.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        recorder?.onOutputFormatChanged(codec.outputFormat)
                    }
                }
            }
        } catch (error: IllegalStateException) {
            if (running.get()) throw error
        }
    }

    private fun startAudioStreamer() {
        audioStreamer = AacAudioStreamer(context).also { streamer ->
            streamer.start(
                onEvent = { event ->
                    when (event) {
                        is EncodedAudioEvent.FormatChanged -> Unit
                        is EncodedAudioEvent.Sample -> {
                            if (event.flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0) {
                                latestAudioCodecConfig = event.data
                            }
                            sendAudioFrame(event.data, event.flags, event.timestampMicros)
                        }
                    }
                },
                onError = { error ->
                    Log.w(TAG, "Audio streaming stopped after capture or encode failure", error)
                    // Keep video streaming if audio capture fails after collector startup.
                },
            )
        }
    }

    private fun sendVideoFrame(data: ByteArray, flags: Int, timestampMicros: Long) {
        if (clients.isEmpty()) return
        if (flags and MediaUdpPacket.FLAG_KEY_FRAME != 0) {
            latestCodecConfig?.let { config ->
                sendMediaPayload(
                    track = MediaTrack.Video,
                    codec = MediaCodecType.H264,
                    data = config,
                    flags = MediaUdpPacket.FLAG_CODEC_CONFIG,
                    timestampMicros = timestampMicros,
                )
            }
        }
        sendMediaPayload(
            track = MediaTrack.Video,
            codec = MediaCodecType.H264,
            data = data,
            flags = flags,
            timestampMicros = timestampMicros,
        )
    }

    private fun sendAudioFrame(data: ByteArray, flags: Int, timestampMicros: Long) {
        if (clients.isEmpty()) return
        sendMediaPayload(
            track = MediaTrack.Audio,
            codec = MediaCodecType.Aac,
            data = data,
            flags = flags,
            timestampMicros = timestampMicros,
        )
    }

    private fun sendMediaPayload(
        track: MediaTrack,
        codec: MediaCodecType,
        data: ByteArray,
        flags: Int,
        timestampMicros: Long,
    ) {
        realtimeSender?.offer(
            OutboundMediaFrame(
                track = track,
                codec = codec,
                sequenceNumber = sequenceNumber.incrementAndGet(),
                timestampMicros = timestampMicros,
                flags = flags,
                data = data,
            ),
        )
    }

    fun requestKeyFrame() {
        runCatching {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            encoder?.setParameters(params)
        }
    }

    private fun closeCamera() {
        captureSessionGeneration++
        captureSession?.runCatching { close() }
        captureSession = null
        cameraDevice?.runCatching { close() }
        cameraDevice = null
    }

    private fun resolvePreviewSize(): PreviewSize? {
        selectedPreviewSize?.let { return it }
        val target = previewTargetSize
        val cameraId = selectedCameraId ?: return null
        val manager = context.getSystemService(CameraManager::class.java)
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val supportedSizes = CameraPreviewConfigReader.run {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).previewSizes()
        }
        val sensorOrientation = characteristics
            .get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: 0
        return PreviewSizeSelector.chooseConfig(
            supportedSizes = supportedSizes,
            surfaceWidth = target.width,
            surfaceHeight = target.height,
            sensorOrientationDegrees = sensorOrientation,
            displayRotationDegrees = displayRotationDegrees,
        )
    }

    companion object {
        private const val TAG = "CameraH264Streamer"
        private const val UDP_SOCKET_BUFFER_BYTES = 1_048_576

        fun rotationDegrees(rotation: Int): Int =
            when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
    }

    private data class StreamClient(
        val address: InetAddress,
        val udpPort: Int,
    )
}
