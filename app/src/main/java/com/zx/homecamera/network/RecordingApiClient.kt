package com.zx.homecamera.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.protocol.ControlMessage
import com.zx.homecamera.core.protocol.ControlProtocol
import com.zx.homecamera.core.protocol.RecordingEntry
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 客户端录像 API。通过 TCP 控制端口向采集端查询录像列表、拉取录像文件字节流，
 * 写入系统相册（MediaStore）或应用缓存目录供本地播放。
 *
 * 每次请求建立一条独立的短连接，不复用实时观看的控制连接，避免阻塞实时流。
 */
class RecordingApiClient {
    /**
     * 当前下载使用的 socket，供 [cancel] 中断下载。同一时刻只有一个下载在进行。
     */
    @Volatile
    private var activeTransferSocket: Socket? = null

    /**
     * 中断当前正在进行的下载：关闭传输 socket，使阻塞中的 read 抛异常从而终止下载。
     * 可从任意线程调用，对未在下载的状态安全。
     */
    fun cancel() {
        activeTransferSocket?.runCatching { close() }
        activeTransferSocket = null
    }

    /**
     * 请求录像日期列表。返回所有可用日期（降序）。
     */
    fun listDates(device: CollectorDevice, timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): List<String> {
        val response = request(device, ControlMessage.ListRecordings(date = null), timeoutMillis)
            as? ControlMessage.RecordingList
            ?: return emptyList()
        return response.dates
    }

    /**
     * 请求指定日期的录像文件列表。
     */
    fun listFiles(device: CollectorDevice, date: String, timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): List<RecordingEntry> {
        val response = request(device, ControlMessage.ListRecordings(date = date), timeoutMillis)
            as? ControlMessage.RecordingList
            ?: return emptyList()
        return response.files
    }

    /**
     * 下载录像文件到应用缓存目录，供 App 内播放。返回下载后的本地文件。
     * [onProgress] 回调已传输字节数和总字节数。
     */
    fun downloadToCache(
        device: CollectorDevice,
        fileId: String,
        cacheDir: File,
        onProgress: (transferred: Long, total: Long) -> Unit = { _, _ -> },
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): File? {
        val transfer = openTransfer(device, fileId, timeoutMillis) ?: return null
        val target = File(cacheDir, "playback_${fileId.replace('/', '_').replace(".mp4", "")}.mp4")
        return runCatching {
            transfer.socket.use { socket ->
                socket.soTimeout = DEFAULT_TRANSFER_TIMEOUT_MILLIS
                FileOutputStream(target).use { output ->
                    val input = socket.getInputStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var transferred = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        transferred += read
                        onProgress(transferred, transfer.sizeBytes)
                    }
                    output.flush()
                }
            }
            target
        }.onFailure {
            Log.w(TAG, "download to cache failed fileId=$fileId", it)
            target.delete()
        }.getOrNull()
    }

    /**
     * 下载录像文件到系统相册（Movies/HomeCamera/）。Android 10+ 无需写存储权限。
     * 返回插入的 MediaStore Uri，失败返回 null。
     * [onProgress] 回调已传输字节数和总字节数。
     */
    fun downloadToGallery(
        context: Context,
        device: CollectorDevice,
        entry: RecordingEntry,
        onProgress: (transferred: Long, total: Long) -> Unit = { _, _ -> },
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Uri? {
        val transfer = openTransfer(device, entry.fileId, timeoutMillis) ?: return null
        activeTransferSocket = transfer.socket
        val displayName = galleryDisplayName(entry)
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/HomeCamera")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: run {
            transfer.socket.close()
            activeTransferSocket = null
            return null
        }
        return runCatching {
            transfer.socket.use { socket ->
                socket.soTimeout = DEFAULT_TRANSFER_TIMEOUT_MILLIS
                resolver.openOutputStream(uri)?.use { output ->
                    val input = socket.getInputStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var transferred = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        transferred += read
                        onProgress(transferred, transfer.sizeBytes)
                    }
                    output.flush()
                } ?: throw IllegalStateException("cannot open output stream for $uri")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, finalValues, null, null)
            }
            uri
        }.onFailure { error ->
            Log.w(TAG, "download to gallery failed fileId=${entry.fileId}", error)
            runCatching { resolver.delete(uri, null, null) }
            runCatching { transfer.socket.close() }
        }.getOrNull().also {
            activeTransferSocket = null
        }
    }

    private fun request(
        device: CollectorDevice,
        message: ControlMessage,
        timeoutMillis: Int,
    ): ControlMessage? {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(device.hostAddress, device.tcpPort), timeoutMillis)
                socket.soTimeout = timeoutMillis
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                writer.write(ControlProtocol.encode(message))
                writer.newLine()
                writer.flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                reader.readLine()?.let(ControlProtocol::decode)
            }
        }.onFailure {
            Log.w(TAG, "recording request failed: ${it.message}", it)
        }.getOrNull()
    }

    private fun openTransfer(
        device: CollectorDevice,
        fileId: String,
        timeoutMillis: Int,
    ): Transfer? {
        val ready = request(device, ControlMessage.OpenRecording(fileId), timeoutMillis)
            as? ControlMessage.RecordingReady
            ?: return null
        return runCatching {
            val socket = Socket()
            socket.connect(InetSocketAddress(device.hostAddress, ready.transferPort), timeoutMillis)
            Transfer(socket = socket, sizeBytes = ready.sizeBytes)
        }.onFailure {
            Log.w(TAG, "open transfer failed fileId=$fileId: ${it.message}", it)
        }.getOrNull()
    }

    private fun galleryDisplayName(entry: RecordingEntry): String {
        // fileId 形如 2026-07-21/14-30-00.mp4，转成 HomeCamera_2026-07-21_14-30-00.mp4
        val safeName = entry.fileId.replace('/', '_')
        return if (safeName.startsWith("HomeCamera_")) safeName else "HomeCamera_$safeName"
    }

    private data class Transfer(
        val socket: Socket,
        val sizeBytes: Long,
    )

    companion object {
        private const val TAG = "RecordingApiClient"
        private const val DEFAULT_TIMEOUT_MILLIS = 5_000
        private const val DEFAULT_TRANSFER_TIMEOUT_MILLIS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
    }
}
