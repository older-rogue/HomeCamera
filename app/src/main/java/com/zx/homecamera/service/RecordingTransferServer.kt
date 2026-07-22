package com.zx.homecamera.service

import com.zx.homecamera.network.logNet
import com.zx.homecamera.network.logNetError
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * 采集端录像文件传输服务。为每个 [OpenRecording] 请求开启一个临时 TCP 端口，
 * 接受一个连接后流式发送文件字节，发送完毕即关闭。文件传输走独立 TCP 端口而非
 * 复用控制端口，避免控制消息和大数据流混在一条 socket 上。
 */
class RecordingTransferServer(
    private val recordingRoot: File,
    private val executor: ExecutorService,
) {
    /**
     * 为 [fileId] 开启一个临时端口，返回监听 socket 和校验后的文件。
     * 调用方负责把端口通过 [ControlMessage.RecordingReady] 回传客户端，然后调用
     * [acceptAndSend] 接受连接并发送字节流。
     */
    fun prepareTransfer(fileId: String): TransferSession? {
        val file = resolveSafeFile(fileId) ?: return null
        if (!file.isFile) return null
        return runCatching {
            val server = ServerSocket(0)
            TransferSession(server = server, file = file, fileId = fileId)
        }.onFailure {
            logNetError("recording transfer prepare failed fileId=$fileId: ${it.message}", it)
        }.getOrNull()
    }

    /**
     * 阻塞等待一个客户端连接，发送文件字节流后关闭 server socket。
     * 限制最多等待 [acceptTimeoutMillis] 一个连接，超时则关闭。
     */
    fun acceptAndSend(session: TransferSession, acceptTimeoutMillis: Int = DEFAULT_ACCEPT_TIMEOUT_MILLIS) {
        session.server.soTimeout = acceptTimeoutMillis
        try {
            val socket = session.server.accept()
            executor.execute {
                runCatching { streamFile(socket, session.file) }
                    .onFailure { logNetError("recording transfer failed fileId=${session.fileId}: ${it.message}", it) }
            }
        } catch (error: Throwable) {
            logNetError("recording transfer accept failed fileId=${session.fileId}: ${error.message}", error)
            session.server.runCatching { close() }
        }
    }

    private fun streamFile(socket: Socket, file: File) {
        socket.use { client ->
            client.soTimeout = DEFAULT_SOCKET_TIMEOUT_MILLIS
            val output = client.getOutputStream()
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
            output.flush()
            logNet("recording transfer done file=${file.name} size=${file.length()}")
        }
    }

    /**
     * 校验 [fileId] 不含 `..` 且解析后绝对路径在 [recordingRoot] 目录内，防止路径穿越。
     */
    private fun resolveSafeFile(fileId: String): File? {
        if (fileId.isBlank()) return null
        if (fileId.contains("..")) return null
        if (fileId.startsWith("/")) return null
        if (fileId.contains("\\")) return null
        val resolved = File(recordingRoot, fileId).canonicalFile
        val rootCanonical = recordingRoot.canonicalFile
        if (!resolved.path.startsWith(rootCanonical.path + File.separator) && resolved != rootCanonical) {
            return null
        }
        return resolved
    }

    data class TransferSession(
        val server: ServerSocket,
        val file: File,
        val fileId: String,
    ) {
        val transferPort: Int get() = server.localPort
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_ACCEPT_TIMEOUT_MILLIS = 15_000
        private const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 30_000
    }
}
