package com.zx.homecamera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.zx.homecamera.R
import com.zx.homecamera.audio.AacAudioConfig
import com.zx.homecamera.core.discovery.DiscoveryPacket
import com.zx.homecamera.core.discovery.DiscoveryProtocol
import com.zx.homecamera.core.protocol.ControlMessage
import com.zx.homecamera.core.protocol.ControlProtocol
import com.zx.homecamera.core.storage.RecordingStorageCleaner
import com.zx.homecamera.video.CameraH264Streamer
import com.zx.homecamera.video.CollectorCameraRuntime
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.LocalDate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CollectorForegroundService : Service() {
    private val lifecycle = CollectorServiceLifecycle()
    private var executor: ExecutorService? = null
    private var discoverySocket: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null
    private var cameraStreamer: CameraH264Streamer? = null
    private val recordingRoot: File
        get() = File(getExternalFilesDir(null), "recordings")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCollector(startId)
            else -> startCollector(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val decision = lifecycle.onDestroyed()
        if (decision.shouldReleaseResources) {
            releaseCollectorResources()
        }
        super.onDestroy()
    }

    private fun startCollector(startId: Int) {
        val decision = lifecycle.onStart(startId)
        if (!decision.shouldStartResources) return

        startForeground(NOTIFICATION_ID, buildNotification(isCollecting = true))
        try {
            val streamer = CameraH264Streamer(applicationContext, recordingRoot)
            cameraStreamer = streamer
            CollectorCameraRuntime.attachStreamer(streamer)
            streamer.start()
            sendStatusBroadcast(STATUS_RUNNING)
        } catch (error: Exception) {
            val failure = lifecycle.onStartFailed(startId)
            if (failure.shouldReleaseResources) {
                releaseCollectorResources()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            sendStatusBroadcast(STATUS_ERROR, EXTRA_MESSAGE, error.message ?: "采集端启动失败")
            if (failure.shouldRequestServiceStop) {
                lifecycle.onServiceStopResult(startId, stopSelfResult(startId))
            }
            return
        }

        executor = Executors.newFixedThreadPool(4).also { pool ->
            pool.executeCatching(::runDiscoveryResponder)
            pool.executeCatching(::runControlServer)
            pool.executeCatching(::cleanRecordingsOnce)
        }
    }

    private fun stopCollector(startId: Int) {
        val decision = lifecycle.onStop(startId)
        if (decision.shouldReleaseResources) {
            releaseCollectorResources()
            sendStatusBroadcast(STATUS_STOPPED)
        }
        if (decision.shouldKeepServiceForeground) {
            startForeground(NOTIFICATION_ID, buildNotification(isCollecting = false))
        }
        if (decision.shouldRequestServiceStop) {
            lifecycle.onServiceStopResult(startId, stopSelfResult(startId))
        }
    }

    private fun releaseCollectorResources() {
        discoverySocket?.runCatching { close() }
        discoverySocket = null
        serverSocket?.runCatching { close() }
        serverSocket = null
        cameraStreamer?.runCatching { stop() }
        cameraStreamer?.runCatching { CollectorCameraRuntime.detachStreamer(this) }
        cameraStreamer = null
        executor?.runCatching { shutdownNow() }
        executor = null
    }

    private fun runDiscoveryResponder() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "collector"
        val deviceName = Build.MODEL ?: "Android 采集端"
        try {
            DatagramSocket(DiscoveryProtocol.UDP_PORT).use { socket ->
                discoverySocket = socket
                val buffer = ByteArray(2048)
                while (lifecycle.isRunning) {
                    val request = DatagramPacket(buffer, buffer.size)
                    socket.receive(request)
                    val text = String(request.data, request.offset, request.length, Charsets.UTF_8)
                    if (DiscoveryProtocol.decode(text) == DiscoveryPacket.Discover) {
                        val response = DiscoveryProtocol.encode(
                            DiscoveryPacket.Announce(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                hostAddress = localHostAddress(),
                                tcpPort = CONTROL_PORT,
                            ),
                        ).toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(response, response.size, request.address, request.port))
                    }
                }
            }
        } catch (_: SocketException) {
            // Socket is closed during normal service shutdown.
        }
    }

    private fun runControlServer() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "collector"
        val deviceName = Build.MODEL ?: "Android 采集端"
        try {
            ServerSocket(CONTROL_PORT).use { server ->
                serverSocket = server
                while (lifecycle.isRunning) {
                    val socket = server.accept()
                    executor?.executeCatching {
                        handleClient(socket, deviceId, deviceName)
                    }
                }
            }
        } catch (_: SocketException) {
            // Socket is closed during normal service shutdown.
        }
    }

    private fun handleClient(socket: Socket, deviceId: String, deviceName: String) {
        socket.use { client ->
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val firstLine = reader.readLine()
            val message = firstLine?.let(ControlProtocol::decode)
            if (message !is ControlMessage.ViewStart) return

            val streamConfig = cameraStreamer?.streamConfig()
            val audioConfig = cameraStreamer?.audioConfig()
            writer.write(
                ControlProtocol.encode(
                    ControlMessage.Hello(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        udpPort = STREAM_PORT,
                        streamWidth = streamConfig?.bufferSize?.width ?: DEFAULT_STREAM_WIDTH,
                        streamHeight = streamConfig?.bufferSize?.height ?: DEFAULT_STREAM_HEIGHT,
                        displayWidth = streamConfig?.displaySize?.width ?: DEFAULT_DISPLAY_WIDTH,
                        displayHeight = streamConfig?.displaySize?.height ?: DEFAULT_DISPLAY_HEIGHT,
                        streamFps = streamConfig?.fps ?: DEFAULT_STREAM_FPS,
                        audioEnabled = audioConfig?.enabled ?: AacAudioConfig.DEFAULT_ENABLED,
                        audioCodec = audioConfig?.codec ?: AacAudioConfig.CODEC,
                        audioSampleRate = audioConfig?.sampleRate ?: AacAudioConfig.SAMPLE_RATE,
                        audioChannelCount = audioConfig?.channelCount ?: AacAudioConfig.CHANNEL_COUNT,
                        audioBitrate = audioConfig?.bitrate ?: AacAudioConfig.BITRATE,
                    ),
                ),
            )
            writer.newLine()
            writer.flush()
            cameraStreamer?.addClient(client.inetAddress, message.udpPort)
            client.soTimeout = 0

            while (lifecycle.isRunning) {
                val controlMessage = reader.readLine()?.let(ControlProtocol::decode) ?: return
                if (controlMessage is ControlMessage.RequestKeyFrame) {
                    cameraStreamer?.requestKeyFrame()
                }
            }
        }
    }

    private fun ExecutorService.executeCatching(block: () -> Unit) {
        execute {
            runCatching(block)
        }
    }

    private fun buildNotification(isCollecting: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "HomeCamera 采集端",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                if (isCollecting) {
                    "HomeCamera 采集端运行中"
                } else {
                    "HomeCamera 采集端已停止"
                },
            )
            .setContentText(
                if (isCollecting) {
                    "录像目录：${recordingRoot.absolutePath}"
                } else {
                    "服务保持空闲，点击开始可重新采集"
                },
            )
            .setOngoing(true)
            .build()
    }

    private fun cleanRecordingsOnce() {
        val root = recordingRoot
        if (!root.exists()) root.mkdirs()
        RecordingStorageCleaner().clean(
            root = root,
            today = LocalDate.now(),
            usableBytes = root.usableSpace,
        )
    }

    private fun localHostAddress(): String {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "0.0.0.0"
        val network = cm.activeNetwork ?: return "0.0.0.0"
        val linkProperties = cm.getLinkProperties(network) ?: return "0.0.0.0"
        return linkProperties.linkAddresses
            .firstOrNull { it.address is java.net.Inet4Address }
            ?.address?.hostAddress ?: "0.0.0.0"
    }

    private fun sendStatusBroadcast(status: String, extraKey: String? = null, extraValue: String? = null) {
        val intent = Intent(ACTION_COLLECTOR_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            extraKey?.let { putExtra(it, extraValue ?: "") }
        }
        sendBroadcast(intent)
    }

    companion object {
        const val ACTION_START = "com.zx.homecamera.action.START_COLLECTOR"
        const val ACTION_STOP = "com.zx.homecamera.action.STOP_COLLECTOR"
        const val ACTION_COLLECTOR_STATUS = "com.zx.homecamera.action.COLLECTOR_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val STATUS_RUNNING = "running"
        const val STATUS_ERROR = "error"
        const val STATUS_STOPPED = "stopped"
        const val CONTROL_PORT = 62001
        const val STREAM_PORT = 62010
        private const val CHANNEL_ID = "collector"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_STREAM_WIDTH = 640
        private const val DEFAULT_STREAM_HEIGHT = 480
        private const val DEFAULT_DISPLAY_WIDTH = 480
        private const val DEFAULT_DISPLAY_HEIGHT = 640
        private const val DEFAULT_STREAM_FPS = 15
    }
}
