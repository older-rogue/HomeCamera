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
import com.zx.homecamera.core.protocol.RecordingEntry
import com.zx.homecamera.core.storage.MediaMetadataFrameSampler
import com.zx.homecamera.core.storage.RecordingContentAnalyzer
import com.zx.homecamera.core.storage.RecordingIntegrityChecker
import com.zx.homecamera.core.storage.RecordingLibrary
import com.zx.homecamera.core.storage.RecordingMetadataStore
import com.zx.homecamera.core.storage.RecordingStorageCleaner
import com.zx.homecamera.core.storage.SmartCleanupCoordinator
import com.zx.homecamera.local.SmartCleanupSettings
import com.zx.homecamera.network.CollectorDiscoveryBroadcaster
import com.zx.homecamera.network.logNet
import com.zx.homecamera.network.logNetError
import com.zx.homecamera.video.CameraH264Streamer
import com.zx.homecamera.video.CollectorCameraRuntime
import java.io.BufferedReader
import java.io.BufferedWriter
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
    // 保留策略清理（日期过期 + 空间压力删除）：任务轻量（纯文件扫描），高频触发
    // （服务启动 / 每次切片 / 录像失败）。
    private var maintenanceExecutor: ExecutorService? = null
    // 智能内容清理（抽帧分析，单轮可达小时级）：独立执行器自循环，低频触发。
    // 与 maintenanceExecutor 分离是关键：共用单线程执行器时，耗时的内容分析会
    // 把空间压力清理排队饿死，磁盘写满后只剩「录像写入失败」而无法自动恢复。
    private var smartCleanupExecutor: ExecutorService? = null
    private var serverSocket: ServerSocket? = null
    private var discoveryBroadcaster: CollectorDiscoveryBroadcaster? = null
    private var discoveryExecutor: ExecutorService? = null
    private var cameraStreamer: CameraH264Streamer? = null
    private var transferServer: RecordingTransferServer? = null
    private var transferExecutor: ExecutorService? = null
    private var recordingHttpServer: RecordingHttpServer? = null
    // 录像完整性校验器（带结果缓存，跨请求复用，避免每次列表请求都重新解析 mp4）
    private val integrityChecker = RecordingIntegrityChecker()
    // 录像元数据持久化缓存（JSON）：segment 关闭时预计算完整性并写入，列表请求直接读缓存秒回。
    // 用 lazy 延迟到首次访问（此时 Context 已 attach），避免在 <init> 阶段调用
    // getExternalFilesDir 触发 NPE（Service 构造时 base context 尚未就绪）。
    private val metadataStore by lazy { RecordingMetadataStore(recordingRoot) }
    // 跟踪活跃 client socket，停止时主动关闭以触发 readLine 立即抛异常退出，
    // 避免 clientExecutor 线程等到 soTimeout(15s) 才退出。
    private val activeClientSockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
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
            // 清理执行器必须在 streamer 启动前就绪：streamer.start() 后第一个 segment
            // 几乎立刻开始（首个关键帧即触发 onSegmentStarted），若此时 executor 还未
            // 创建（旧代码在 start() 之后才创建），首个清理任务会被 ?. 静默丢弃。
            maintenanceExecutor = Executors.newSingleThreadExecutor()
            smartCleanupExecutor = Executors.newSingleThreadExecutor().also { executor ->
                executor.executeCatching(::runSmartCleanupLoop)
            }
            val streamer = CameraH264Streamer(
                context = applicationContext,
                recordingRoot = recordingRoot,
                onRecordingError = { error ->
                    sendStatusBroadcast(
                        STATUS_RECORDING_ERROR,
                        EXTRA_MESSAGE,
                        error.message ?: "录像写入失败",
                    )
                    // 录像写入失败最常见的原因是磁盘写满（ENOSPC）。立即调度一次
                    // 保留策略清理释放空间——它只做目录/文件扫描，秒级完成；空间释放后
                    // 录制会在下一个关键帧自动重开 segment（MediaCodec 流未被破坏）。
                    // 不能等下一个 2 分钟切片：那意味着丢整整一段录像。
                    runCatching { maintenanceExecutor?.executeCatching(::cleanRetentionPolicyOnce) }
                },
                onSegmentStarted = {
                    // 每次切片（约 2 分钟）触发一次保留策略清理（日期过期 + 空间压力
                    // 删除），任务轻量（纯文件扫描），保证空间压力及时释放。
                    // 耗时的智能内容分析由 smartCleanupExecutor 的独立循环低频执行，
                    // 不在此触发——共用一个单线程执行器时分析任务会把清理排队饿死，
                    // 磁盘写满时无法及时释放空间（历史 bug）。
                    // maintenanceExecutor 是单线程执行器，任务自动串行；executeCatching
                    // 内部的 execute{} 在 runCatching 外，shutdownNow 后提交会抛
                    // RejectedExecutionException，外层 runCatching 确保停止过程中触发的
                    // 回调不崩溃。
                    runCatching { maintenanceExecutor?.executeCatching(::cleanRetentionPolicyOnce) }
                },
                onSegmentClosed = { file ->
                    // segment 落盘后异步校验完整性并写入元数据缓存，使列表请求无需逐文件打开
                    // MediaMetadataRetriever。与清理任务共用 maintenanceExecutor 串行执行，不阻塞录制。
                    runCatching {
                        maintenanceExecutor?.executeCatching { onSegmentClosedInternal(file) }
                    }
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
        val clientPool = Executors.newFixedThreadPool(CLIENT_HANDLER_THREADS)
        clientExecutor = clientPool
        // 文件传输用独立线程池，避免与控制连接（ViewStart 等）争用线程。
        // 原实现复用 clientExecutor（4 线程），大文件传输会占满线程导致新观看连接被饿死。
        transferExecutor = Executors.newFixedThreadPool(TRANSFER_HANDLER_THREADS)
        transferServer = RecordingTransferServer(recordingRoot, transferExecutor!!)
        recordingHttpServer = RecordingHttpServer(recordingRoot, HTTP_PORT).also { it.start() }
        // 启动即执行一次保留策略清理（过期目录可能在停机期间积压）。
        runCatching { maintenanceExecutor?.executeCatching(::cleanRetentionPolicyOnce) }
        startDiscoveryBroadcast()
    }

    private fun startDiscoveryBroadcast() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "collector"
        val deviceName = Build.MODEL ?: "Android 采集端"
        val broadcaster = CollectorDiscoveryBroadcaster(
            deviceId = deviceId,
            deviceName = deviceName,
            controlPort = CONTROL_PORT,
        )
        discoveryBroadcaster = broadcaster
        discoveryExecutor = Executors.newSingleThreadExecutor().also { executor ->
            executor.executeCatching { broadcaster.run() }
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
        discoveryBroadcaster?.runCatching { stop() }
        discoveryBroadcaster = null
        cameraStreamer?.runCatching { stop() }
        cameraStreamer?.runCatching { CollectorCameraRuntime.detachStreamer(this) }
        cameraStreamer = null
        transferServer = null
        recordingHttpServer?.runCatching { stop() }
        recordingHttpServer = null
        // 先关闭所有活跃 client socket，触发 handleClient/handleViewStart 中阻塞的
        // readLine 立即抛 SocketException 退出，无需等到 soTimeout(15s)。
        synchronized(activeClientSockets) {
            activeClientSockets.forEach { it.runCatching { close() } }
            activeClientSockets.clear()
        }
        serverExecutor?.runCatching { shutdownNow() }
        clientExecutor?.runCatching { shutdownNow() }
        transferExecutor?.runCatching { shutdownNow() }
        maintenanceExecutor?.runCatching { shutdownNow() }
        // shutdownNow 会中断睡眠中的清理循环线程，使其立即退出。
        smartCleanupExecutor?.runCatching { shutdownNow() }
        discoveryExecutor?.runCatching { shutdownNow() }
        releaseWifiLock()
        releaseCpuWakeLock()
        serverExecutor = null
        clientExecutor = null
        transferExecutor = null
        maintenanceExecutor = null
        smartCleanupExecutor = null
        discoveryExecutor = null
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
        activeClientSockets.add(socket)
        try {
            socket.use { client ->
                client.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val firstLine = reader.readLine()
                val message = firstLine?.let(ControlProtocol::decode)
                when (message) {
                    is ControlMessage.ViewStart -> handleViewStart(client, reader, writer, message, deviceId, deviceName)
                    is ControlMessage.ListRecordings -> handleListRecordings(writer, message)
                    is ControlMessage.OpenRecording -> handleOpenRecording(writer, message)
                    else -> logNet("collector tcp probe remote=${client.inetAddress?.hostAddress}:${client.port} firstLine=${firstLine?.take(80)}")
                }
            }
        } finally {
            activeClientSockets.remove(socket)
        }
    }

    private fun handleViewStart(
        client: Socket,
        reader: BufferedReader,
        writer: BufferedWriter,
        message: ControlMessage.ViewStart,
        deviceId: String,
        deviceName: String,
    ) {
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

    /**
     * segment 关闭后的后台处理：完整性校验 + 元数据落盘。
     * 在 maintenanceExecutor 上串行执行，不阻塞录制线程。任一步骤失败不影响后续步骤。
     *
     * 不再做 mp4 faststart 重排：迁移到 ExoPlayer + SimpleCache 后，moov-at-end 的 seek 往返
     * 在 LAN 上仅几十毫秒，且 moov 部分会被 SimpleCache 缓存，二次播放无需再 seek。
     * faststart 的复杂度与潜在 bug（size mismatch）不值得此微小收益。
     */
    private fun onSegmentClosedInternal(file: File) {
        runCatching {
            val library = RecordingLibrary(recordingRoot)
            val entry = library.entryFor(file) ?: return@runCatching
            val corrupted = !integrityChecker.isPlayable(entry.fileId, file)
            metadataStore.put(
                fileId = entry.fileId,
                sizeBytes = entry.sizeBytes,
                startMillis = entry.startMillis,
                corrupted = corrupted,
            )
            logNet("segment metadata cached fileId=${entry.fileId} corrupted=$corrupted")
        }
    }

    private fun handleListRecordings(writer: BufferedWriter, message: ControlMessage.ListRecordings) {
        val totalStart = System.currentTimeMillis()
        val library = RecordingLibrary(recordingRoot)
        val dates = library.listDates()
        val recordingFileIds = cameraStreamer?.currentRecordingFileId()?.let { setOf(it) } ?: emptySet()
        // 元数据缓存：已关闭录像的完整性预计算结果。loadValid 会顺带剔除已删除文件的残留条目。
        val cached = metadataStore.loadValid()
        val files = message.date?.let { date -> library.listFiles(date, recordingFileIds) } ?: emptyList()
        val checkStart = System.currentTimeMillis()
        var cacheHits = 0
        var cacheMisses = 0
        val annotatedFiles = files.map { entry ->
            if (entry.recording) {
                // 正在录制的文件不进缓存（未 stop 缺 moov），直接标记，不校验
                entry
            } else {
                val cachedEntry = cached[entry.fileId]
                if (cachedEntry != null && cachedEntry.sizeBytes == entry.sizeBytes) {
                    // 命中缓存：直接用预计算的 corrupted 状态，跳过 MediaMetadataRetriever
                    cacheHits++
                    entry.copy(corrupted = cachedEntry.corrupted)
                } else {
                    // 未命中（进程重启后首次访问 / 大小变化）：实时校验并回填缓存
                    cacheMisses++
                    val corrupted = !integrityChecker.isPlayable(entry.fileId, File(recordingRoot, entry.fileId))
                    metadataStore.put(entry.fileId, entry.sizeBytes, entry.startMillis, corrupted)
                    entry.copy(corrupted = corrupted)
                }
            }
        }
        val checkCost = System.currentTimeMillis() - checkStart
        val corruptedCount = annotatedFiles.count { it.corrupted }
        writer.write(
            ControlProtocol.encode(
                ControlMessage.RecordingList(dates = dates, files = annotatedFiles.map { it.toEntry() }),
            ),
        )
        writer.newLine()
        writer.flush()
        val totalCost = System.currentTimeMillis() - totalStart
        logNet("collector sent RecordingList date=${message.date} dates=${dates.size} files=${annotatedFiles.size} recording=${recordingFileIds.size} corrupted=$corruptedCount cacheHits=$cacheHits cacheMisses=$cacheMisses check=${checkCost}ms total=${totalCost}ms")
    }

    private fun handleOpenRecording(writer: BufferedWriter, message: ControlMessage.OpenRecording) {
        val server = transferServer ?: return
        val session = server.prepareTransfer(message.fileId)
        if (session == null) {
            logNet("collector OpenRecording rejected fileId=${message.fileId}")
            return
        }
        writer.write(
            ControlProtocol.encode(
                ControlMessage.RecordingReady(
                    fileId = message.fileId,
                    sizeBytes = session.file.length(),
                    transferPort = session.transferPort,
                ),
            ),
        )
        writer.newLine()
        writer.flush()
        logNet("collector sent RecordingReady fileId=${message.fileId} port=${session.transferPort} size=${session.file.length()}")
        // 写完 RecordingReady 后当前 socket 即可关闭，文件字节流走独立 TCP 连接。
        server.acceptAndSend(session)
    }

    private fun com.zx.homecamera.core.storage.RecordingFileEntry.toEntry(): RecordingEntry =
        RecordingEntry(fileId = fileId, sizeBytes = sizeBytes, startMillis = startMillis, recording = recording, corrupted = corrupted)

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
            // 必须显式传 type：若走两参 startForeground()，会继承 manifest 中声明的
            // camera|microphone 类型，系统会据此校验 RECORD_AUDIO/CAMERA 权限——在停止、
            // 未授权等场景下会抛 SecurityException。这里始终传由 foregroundServiceType()
            // 计算出的类型（至少为 dataSync，永不返回 0）。
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        // 仅在对应权限已授予时才声明 camera/microphone 类型，避免停止等场景下权限缺失时
        // 触发 SecurityException；二者皆未授予时退回 dataSync（HTTP/文件传输属于数据同步
        // 语义），保证至少有一个不需要运行时权限的前台服务类型可用。
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
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

    /**
     * 保留策略清理：日期过期（默认 3 天，见 [com.zx.homecamera.core.storage.RecordingRetentionPolicy]）
     * + 空间压力删除，并同步移除被删文件的元数据缓存。
     * 纯目录/文件扫描，无媒体解析，秒级完成；在 maintenanceExecutor 上串行执行。
     *
     * 正在录制的 segment 通过 [excludeFiles] 排除：unlink 已打开的文件不会真正释放
     * 空间（fd 被 muxer 持有到 stop），且会白白丢掉正在录制的片段。
     */
    private fun cleanRetentionPolicyOnce() {
        val root = recordingRoot
        if (!root.exists()) root.mkdirs()
        val excludeFiles = currentRecordingFiles()
        val cleanResult = RecordingStorageCleaner().clean(
            root = root,
            today = LocalDate.now(),
            usableBytes = root.usableSpace,
            excludeFiles = excludeFiles,
        )
        // 清除被删文件/目录的元数据缓存。
        // 单文件删除：按 fileId 精确移除。
        cleanResult.deletedFiles.forEach { file ->
            val date = file.parentFile?.name ?: return@forEach
            metadataStore.remove("$date/${file.name}")
        }
        // 目录级删除：整个日期目录过期被删，遍历目录内 mp4 逐个移除元数据。
        // 不依赖 loadValid 兜底，确保客户端下次请求列表时缓存已是干净的。
        cleanResult.deletedDirectories.forEach { dir ->
            val date = dir.name
            dir.listFiles { it.isFile && it.extension.equals("mp4", true) }?.forEach { file ->
                metadataStore.remove("$date/${file.name}")
            }
        }
        if (cleanResult.deletedFiles.isNotEmpty() || cleanResult.deletedDirectories.isNotEmpty()) {
            logNet(
                "retention cleanup: deletedFiles=${cleanResult.deletedFiles.size} " +
                    "deletedDirs=${cleanResult.deletedDirectories.size} excluded=${excludeFiles.size}",
            )
        }
    }

    /**
     * 智能内容清理循环（smartCleanupExecutor）：启动后立即执行一轮，之后每
     * [SMART_CLEANUP_INTERVAL_MILLIS] 一轮。删除全黑/静止的无效片段，减少无用文件。
     * 线程被中断（服务停止 shutdownNow）时退出。
     */
    private fun runSmartCleanupLoop() {
        while (!Thread.currentThread().isInterrupted) {
            runCatching { runSmartCleanupOnce() }
                .onFailure { error -> logNet("smart cleanup failed: ${error.message}") }
            try {
                Thread.sleep(SMART_CLEANUP_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** 单轮智能内容清理。候选过滤（保护窗/录制中/损坏）由 coordinator 负责。 */
    private fun runSmartCleanupOnce() {
        if (!SmartCleanupSettings.isEnabled(this)) {
            logNet("smart cleanup: disabled by settings")
            return
        }
        val root = recordingRoot
        val guardWindowMinutes = SmartCleanupSettings.guardWindowMinutes(this)
        val cutoff = SmartCleanupCoordinator.guardWindowCutoff(
            nowMillis = System.currentTimeMillis(),
            guardWindowMinutes = guardWindowMinutes,
        )
        val excludeFileIds = cameraStreamer?.currentRecordingFileId()?.let { setOf(it) } ?: emptySet()
        val coordinator = SmartCleanupCoordinator(
            library = RecordingLibrary(root),
            contentAnalyzer = RecordingContentAnalyzer(MediaMetadataFrameSampler()),
            isPlayable = integrityChecker::isPlayable,
            invalidate = integrityChecker::invalidate,
            // 分析结果持久化到元数据缓存：录像关闭后内容不再变化，已分析的片段
            // （无论有效与否）跳过，使每轮只抽帧分析新关闭的片段。
            isAnalyzed = metadataStore::isAnalyzed,
            markAnalyzed = metadataStore::markAnalyzed,
        )
        val deleted = coordinator.clean(root = root, excludeFileIds = excludeFileIds, guardWindowCutoffMillis = cutoff)
        // 同步清除被删文件的元数据缓存，避免残留。
        deleted.forEach { file ->
            val date = file.parentFile?.name ?: return@forEach
            metadataStore.remove("$date/${file.name}")
        }
        logNet("smart cleanup: deleted=${deleted.size} cutoff=$cutoff exclude=$excludeFileIds")
    }

    /** 当前正在写入的录像文件（fileId 形如 "2026-08-24/14-30-00.mp4"）。 */
    private fun currentRecordingFiles(): Set<File> =
        cameraStreamer?.currentRecordingFileId()
            ?.let { setOf(File(recordingRoot, it)) }
            ?: emptySet()

    private fun sendStatusBroadcast(status: String, extraKey: String? = null, extraValue: String? = null) {
        // 限定到本应用包名：Android 13+ 注册接收器时使用 RECEIVER_NOT_EXPORTED，
        // 若发送侧仍用不带 setPackage 的隐式广播，在 Android 14/16 上不会被投递
        // 给 NOT_EXPORTED 接收器，导致采集端状态（服务/连接/录像）无法同步到 UI。
        val intent = Intent(ACTION_COLLECTOR_STATUS).apply {
            setPackage(packageName)
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
        const val HTTP_PORT = 62003
        private const val CHANNEL_ID = "collector"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_STREAM_WIDTH = 640
        private const val DEFAULT_STREAM_HEIGHT = 480
        private const val DEFAULT_DISPLAY_WIDTH = 480
        private const val DEFAULT_DISPLAY_HEIGHT = 640
        private const val DEFAULT_STREAM_FPS = 15
        private const val CONTROL_READ_TIMEOUT_MILLIS = 15_000
        private const val CLIENT_HANDLER_THREADS = 4
        private const val TRANSFER_HANDLER_THREADS = 4

        /**
         * 智能内容清理的轮询间隔。首轮在服务启动后立即执行（清历史积压），
         * 之后每 30 分钟一轮：每轮只分析新关闭的片段（结果持久化），常态开销极小。
         */
        private const val SMART_CLEANUP_INTERVAL_MILLIS = 30 * 60 * 1000L
    }
}
