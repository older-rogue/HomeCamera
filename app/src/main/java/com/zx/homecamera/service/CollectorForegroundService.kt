package com.zx.homecamera.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zx.homecamera.R
import com.zx.homecamera.audio.AacAudioConfig
import com.zx.homecamera.core.protocol.ControlMessage
import com.zx.homecamera.core.protocol.ControlProtocol
import com.zx.homecamera.core.storage.RecordingStorageCleaner
import com.zx.homecamera.network.logNet
import com.zx.homecamera.network.logNetError
import com.zx.homecamera.video.CameraH264Streamer
import com.zx.homecamera.video.CollectorCameraRuntime
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CollectorForegroundService : Service() {
    private val lifecycle = CollectorServiceLifecycle()
    private var serverExecutor: ExecutorService? = null
    private var clientExecutor: ExecutorService? = null
    private var maintenanceExecutor: ExecutorService? = null
    private var serverSocket: ServerSocket? = null
    private var cameraStreamer: CameraH264Streamer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private val recordingRoot: File
        get() = File(getExternalFilesDir(null), "recordings")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                startCollector(startId)
                START_STICKY
            }
            ACTION_STOP -> {
                stopCollector(startId)
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
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

        startCollectorForeground(isCollecting = true)
        try {
            val streamer = CameraH264Streamer(
                context = applicationContext,
                recordingRoot = recordingRoot,
                onRecordingError = { error ->
                    sendStatusBroadcast(
                        STATUS_RECORDING_ERROR,
                        EXTRA_MESSAGE,
                        error.message ?: "录像写入失败",
                    )
                },
            )
            cameraStreamer = streamer
            CollectorCameraRuntime.attachStreamer(streamer)
            streamer.start()
            sendStatusBroadcast(STATUS_RUNNING)
            acquireWifiLock()
            acquireCpuWakeLock()
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

        serverExecutor = Executors.newSingleThreadExecutor().also { executor ->
            executor.executeCatching(::runControlServer)
        }
        clientExecutor = Executors.newFixedThreadPool(CLIENT_HANDLER_THREADS)
        maintenanceExecutor = Executors.newSingleThreadExecutor().also { executor ->
            executor.executeCatching(::cleanRecordingsOnce)
        }
    }

    private fun stopCollector(startId: Int) {
        // 通过 startForegroundService() 拉起本服务来执行 STOP 时，系统要求在 5s 内
        // 调用 startForeground()，否则抛出 ForegroundServiceDidNotStartInTimeException。
        // 这里先以一条临时通知（与启动一致的带类型前台）满足系统约束，再按 lifecycle 决策走停止流程。
        startCollectorForeground(isCollecting = false)

        val decision = lifecycle.onStop(startId)
        when {
            decision.shouldReleaseResources -> {
                // 正常停止：释放资源、移除通知、广播停止、结束自身。
                releaseCollectorResources()
                stopForeground(STOP_FOREGROUND_REMOVE)
                sendStatusBroadcast(STATUS_STOPPED)
                lifecycle.onServiceStopResult(startId, stopSelfResult(startId))
            }
            !decision.isRunning -> {
                // 服务本就未运行（如重复 STOP）：清场后结束自身。
                stopForeground(STOP_FOREGROUND_REMOVE)
                lifecycle.onServiceStopResult(startId, stopSelfResult(startId))
            }
            else -> {
                // 过时的 STOP（已有更新的 START 把服务重新拉起）：保持运行状态，
                // 将通知恢复为「运行中」，避免误显示为已停止。
                startCollectorForeground(isCollecting = true)
            }
        }
    }

    private fun releaseCollectorResources() {
        serverSocket?.runCatching { close() }
        serverSocket = null
        cameraStreamer?.runCatching { stop() }
        cameraStreamer?.runCatching { CollectorCameraRuntime.detachStreamer(this) }
        cameraStreamer = null
        serverExecutor?.runCatching { shutdownNow() }
        clientExecutor?.runCatching { shutdownNow() }
        maintenanceExecutor?.runCatching { shutdownNow() }
        releaseWifiLock()
        releaseCpuWakeLock()
        serverExecutor = null
        clientExecutor = null
        maintenanceExecutor = null
    }

    private fun runControlServer() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "collector"
        val deviceName = Build.MODEL ?: "Android 采集端"
        try {
            ServerSocket(CONTROL_PORT).use { server ->
                serverSocket = server
                logNet("collector tcp listening port=$CONTROL_PORT deviceId=$deviceId name=$deviceName ips=${localIpv4Addresses()}")
                while (lifecycle.isRunning) {
                    val socket = server.accept()
                    logNet("collector tcp accepted remote=${socket.inetAddress?.hostAddress}:${socket.port}")
                    clientExecutor?.executeCatching {
                        handleClient(socket, deviceId, deviceName)
                    }
                }
            }
        } catch (_: SocketException) {
            logNet("collector tcp server closed")
        } catch (error: Throwable) {
            logNetError( "collector tcp server failed: ${error.message}", error)
        }
    }

    private fun handleClient(socket: Socket, deviceId: String, deviceName: String) {
        socket.use { client ->
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val firstLine = reader.readLine()
            val message = firstLine?.let(ControlProtocol::decode)
            if (message !is ControlMessage.ViewStart) {
                logNet("collector tcp probe remote=${client.inetAddress?.hostAddress}:${client.port} firstLine=${firstLine?.take(80)}")
                return
            }
            logNet("collector received ViewStart remote=${client.inetAddress?.hostAddress}:${client.port} udpPort=${message.udpPort}")

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
            logNet("collector sent Hello remote=${client.inetAddress?.hostAddress}:${client.port} streamPort=$STREAM_PORT")
            var clientAdded = false
            try {
                cameraStreamer?.addClient(client.inetAddress, message.udpPort)
                clientAdded = true
                sendStatusBroadcast(STATUS_RUNNING, EXTRA_CLIENT_COUNT, cameraStreamer?.clientCount()?.toString() ?: "0")
                client.soTimeout = CONTROL_READ_TIMEOUT_MILLIS

                while (lifecycle.isRunning) {
                    val controlMessage = try {
                        reader.readLine()?.let(ControlProtocol::decode) ?: return
                    } catch (_: SocketTimeoutException) {
                        logNet("collector tcp client timeout remote=${client.inetAddress?.hostAddress}:${client.port}")
                        return
                    }
                    when (controlMessage) {
                        is ControlMessage.RequestKeyFrame -> cameraStreamer?.requestKeyFrame()
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
                    cameraStreamer?.removeClient(client.inetAddress, message.udpPort)
                    sendStatusBroadcast(STATUS_RUNNING, EXTRA_CLIENT_COUNT, cameraStreamer?.clientCount()?.toString() ?: "0")
                }
            }
        }
    }

    private fun ExecutorService.executeCatching(block: () -> Unit) {
        execute {
            runCatching(block)
        }
    }

    private fun localIpv4Addresses(): String =
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.toList()
                    .filter { address -> address is java.net.Inet4Address && !address.isLoopbackAddress }
                    .map { address -> "${networkInterface.name}=${address.hostAddress}" }
            }
            .joinToString(",")
            .ifBlank { "none" }

    private fun startCollectorForeground(isCollecting: Boolean) {
        val notification = buildNotification(isCollecting)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val cameraType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val microphoneType = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return cameraType or microphoneType
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
            .setSmallIcon(R.drawable.ic_collector)
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

    private fun sendStatusBroadcast(status: String, extraKey: String? = null, extraValue: String? = null) {
        val intent = Intent(ACTION_COLLECTOR_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            extraKey?.let { putExtra(it, extraValue ?: "") }
        }
        sendBroadcast(intent)
    }

    private fun acquireWifiLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifiManager?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "HomeCamera:Collector",
        )
        wifiLock?.acquire()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireCpuWakeLock() {
        val currentLock = cpuWakeLock
        if (currentLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HomeCamera:CollectorCpu",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseCpuWakeLock() {
        cpuWakeLock?.run {
            if (isHeld) release()
        }
        cpuWakeLock = null
    }

    private fun releaseWifiLock() {
        wifiLock?.run {
            if (isHeld) release()
        }
        wifiLock = null
    }

    companion object {
        const val ACTION_START = "com.zx.homecamera.action.START_COLLECTOR"
        const val ACTION_STOP = "com.zx.homecamera.action.STOP_COLLECTOR"
        const val ACTION_COLLECTOR_STATUS = "com.zx.homecamera.action.COLLECTOR_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_CLIENT_COUNT = "client_count"
        const val STATUS_RUNNING = "running"
        const val STATUS_ERROR = "error"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_RECORDING_ERROR = "recording_error"
        const val CONTROL_PORT = 62001
        const val STREAM_PORT = 62010
        private const val CHANNEL_ID = "collector"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_STREAM_WIDTH = 640
        private const val DEFAULT_STREAM_HEIGHT = 480
        private const val DEFAULT_DISPLAY_WIDTH = 480
        private const val DEFAULT_DISPLAY_HEIGHT = 640
        private const val DEFAULT_STREAM_FPS = 15
        private const val CONTROL_READ_TIMEOUT_MILLIS = 15_000
        private const val CLIENT_HANDLER_THREADS = 4
    }
}
