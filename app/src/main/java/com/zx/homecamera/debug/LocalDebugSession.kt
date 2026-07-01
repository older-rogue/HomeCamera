package com.zx.homecamera.debug

import android.content.Context
import android.util.Log
import android.view.Surface
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.protocol.ControlMessage
import com.zx.homecamera.core.protocol.ControlProtocol
import com.zx.homecamera.network.H264UdpViewer
import com.zx.homecamera.network.LanViewerConnector
import com.zx.homecamera.video.CameraH264Streamer
import com.zx.homecamera.video.CollectorCameraRuntime
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LocalDebugSession(private val context: Context) {
    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null
    private var streamer: CameraH264Streamer? = null
    private var viewer: H264UdpViewer? = null
    private var serverSocket: ServerSocket? = null

    fun start(
        viewerSurface: Surface,
        onFirstFrame: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return

        val serverReady = CountDownLatch(1)
        val streamer = CameraH264Streamer(context, null)
        this.streamer = streamer
        runCatching {
            CollectorCameraRuntime.attachStreamer(streamer)
            streamer.start()
            executor = Executors.newFixedThreadPool(3).also { pool ->
                pool.execute { runControlServer(streamer, serverReady, onError) }
                pool.execute {
                    try {
                        check(serverReady.await(LOCAL_SERVER_START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            "本地调试控制服务启动超时"
                        }
                        val device = CollectorDevice(
                            deviceId = "local_debug",
                            name = "本地调试",
                            hostAddress = "127.0.0.1",
                            tcpPort = LOCAL_CONTROL_PORT,
                            online = true,
                        )
                        val connection = LanViewerConnector().connect(device, timeoutMillis = 5_000)
                        val localViewer = H264UdpViewer()
                        viewer = localViewer
                        localViewer.start(connection, viewerSurface, onFirstFrame, onError)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start local viewer", e)
                        onError(e.message ?: "本地调试启动失败")
                        stop()
                    }
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to start local debug session", error)
            onError(error.message ?: "本地调试启动失败")
            stop()
        }
    }

    fun stop() {
        running.set(false)
        viewer?.stop()
        viewer = null
        serverSocket?.close()
        serverSocket = null
        streamer?.runCatching { stop() }
        streamer?.runCatching { CollectorCameraRuntime.detachStreamer(this) }
        streamer = null
        executor?.shutdownNow()
        executor = null
    }

    fun requestKeyFrame() {
        streamer?.requestKeyFrame()
    }

    private fun runControlServer(
        streamer: CameraH264Streamer,
        serverReady: CountDownLatch,
        onError: (String) -> Unit,
    ) {
        try {
            ServerSocket(LOCAL_CONTROL_PORT).use { server ->
                serverSocket = server
                serverReady.countDown()
                while (running.get()) {
                    val socket = server.accept()
                    executor?.execute {
                        runCatching { handleClient(socket, streamer) }.onFailure { error ->
                            Log.w(TAG, "Local debug control client failed", error)
                        }
                    }
                }
            }
        } catch (_: SocketException) {
            serverReady.countDown()
        } catch (error: Exception) {
            serverReady.countDown()
            Log.e(TAG, "Local debug control server failed", error)
            if (running.get()) onError(error.message ?: "本地调试控制服务异常")
        }
    }

    private fun handleClient(socket: Socket, streamer: CameraH264Streamer) {
        socket.use { client ->
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val firstLine = reader.readLine()
            val message = firstLine?.let(ControlProtocol::decode)
            if (message !is ControlMessage.ViewStart) return

            val streamConfig = streamer.streamConfig()
            val audioConfig = streamer.audioConfig()
            writer.write(
                ControlProtocol.encode(
                    ControlMessage.Hello(
                        deviceId = "local_debug",
                        deviceName = "本地调试采集端",
                        udpPort = LOCAL_STREAM_PORT,
                        streamWidth = streamConfig.bufferSize.width,
                        streamHeight = streamConfig.bufferSize.height,
                        displayWidth = streamConfig.displaySize.width,
                        displayHeight = streamConfig.displaySize.height,
                        streamFps = streamConfig.fps,
                        audioEnabled = audioConfig.enabled,
                        audioCodec = audioConfig.codec,
                        audioSampleRate = audioConfig.sampleRate,
                        audioChannelCount = audioConfig.channelCount,
                        audioBitrate = audioConfig.bitrate,
                    ),
                ),
            )
            writer.newLine()
            writer.flush()

            var clientAdded = false
            try {
                streamer.addClient(client.inetAddress, message.udpPort)
                clientAdded = true
                client.soTimeout = CONTROL_READ_TIMEOUT_MILLIS

                while (running.get()) {
                    val controlMessage = try {
                        reader.readLine()?.let(ControlProtocol::decode) ?: return
                    } catch (_: SocketTimeoutException) {
                        return
                    }
                    when (controlMessage) {
                        is ControlMessage.RequestKeyFrame -> streamer.requestKeyFrame()
                        is ControlMessage.Ping -> {
                            writer.write(ControlProtocol.encode(ControlMessage.Pong(controlMessage.timestampMillis)))
                            writer.newLine()
                            writer.flush()
                        }
                        is ControlMessage.Bye -> return
                        else -> Unit
                    }
                }
            } finally {
                if (clientAdded) {
                    streamer.removeClient(client.inetAddress, message.udpPort)
                }
            }
        }
    }

    companion object {
        const val LOCAL_CONTROL_PORT = 62002
        const val LOCAL_STREAM_PORT = 62010
        private const val LOCAL_SERVER_START_TIMEOUT_MILLIS = 2_000L
        private const val CONTROL_READ_TIMEOUT_MILLIS = 15_000
        private const val TAG = "LocalDebugSession"
    }
}
