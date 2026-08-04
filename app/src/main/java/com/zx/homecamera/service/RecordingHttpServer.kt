package com.zx.homecamera.service

import com.zx.homecamera.core.storage.ThumbnailGenerator
import com.zx.homecamera.network.logNet
import com.zx.homecamera.network.logNetError
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 采集端录像 HTTP 服务器。
 *
 * 在固定端口 [port] 上监听，为客户端提供支持 Range 请求的 HTTP 访问，使 VideoView/MediaPlayer
 * 能边下边播（先请求文件尾的 moov，再按需分段请求 mdat）并支持进度条拖拽。
 *
 * 请求格式：`GET /<fileId> HTTP/1.1`，fileId 形如 `2026-07-23/14-30-00.mp4`。
 * 响应：支持 `Range: bytes=start-end`，返回 `206 Partial Content` 或 `200 OK`。
 *
 * 路径穿越校验复用 [RecordingTransferServer] 的安全逻辑，确保文件在 [recordingRoot] 内。
 */
class RecordingHttpServer(
    private val recordingRoot: File,
    private val port: Int,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var acceptThread: Thread? = null

    /**
     * 首帧缩略图生成器。服务器自持，[thumbnailBytes] 内部带 (fileId, size) 缓存，
     * 无需改 [CollectorForegroundService] 接线。多个 HTTP 连接并发请求不同文件时各自生成。
     */
    private val thumbnailGenerator = ThumbnailGenerator()

    /**
     * 启动 HTTP 监听，阻塞调用者前先提交到内部线程池。
     * 重复调用安全（已运行则直接返回）。
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        // 改用固定大小线程池：newCachedThreadPool 无线程数上限，并发 Range 请求或异常客户端
        // 可导致线程暴涨。8 个线程足以支撑边下边播的并发分段请求。
        executor = Executors.newFixedThreadPool(HTTP_WORKER_THREADS)
        acceptThread = Thread(::acceptLoop, "recording-http-server").also { it.start() }
    }

    /**
     * 停止监听并关闭所有连接。可从任意线程调用。
     */
    fun stop() {
        running.set(false)
        serverSocket?.runCatching { close() }
        serverSocket = null
        // 等待 accept 线程真正退出（serverSocket.close 后 accept 抛异常即退出）
        acceptThread?.runCatching { join(500L) }
        acceptThread = null
        executor?.runCatching { shutdownNow() }
        // 等待工作线程退出，避免 stop 后仍残留 IO 线程占用采集端资源
        executor?.runCatching { awaitTermination(1_000, TimeUnit.MILLISECONDS) }
        executor = null
    }

    private fun acceptLoop() {
        try {
            val server = ServerSocket(port)
            serverSocket = server
            logNet("recording http server listening port=$port")
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    if (running.get()) continue else break
                }
                executor?.execute {
                    runCatching { handleConnection(socket) }.onFailure { error ->
                        logNetError("recording http connection error: ${error.message}", error)
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                logNetError("recording http server failed: ${error.message}", error)
            }
        } finally {
            running.set(false)
            logNet("recording http server stopped port=$port")
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = SOCKET_TIMEOUT_MILLIS
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                // 解析请求行：GET /<fileId> HTTP/1.1
                val parts = requestLine.split(" ")
                if (parts.size < 3 || parts[0] != "GET") {
                    writeError(client.getOutputStream(), 405, "Method Not Allowed")
                    return
                }
                val rawPath = parts[1].removePrefix("/")
                // 读取头部直到空行，收集 Range
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim()
                        if (key.equals("Range", ignoreCase = true)) {
                            rangeHeader = line.substring(colon + 1).trim()
                        }
                    }
                }
                // 分离查询串：缩略图请求形如 /<fileId>?thumb=1，播放请求无查询串。
                // 播放路径（无 `?`）pathPart 即 rawPath，行为与历史完全一致。
                val questionIdx = rawPath.indexOf('?')
                val pathPart = if (questionIdx >= 0) rawPath.substring(0, questionIdx) else rawPath
                val queryPart = if (questionIdx >= 0) rawPath.substring(questionIdx + 1) else ""
                val isThumb = queryPart.split("&").any { it == "thumb=1" }

                val file = resolveSafeFile(pathPart)
                if (file == null || !file.isFile) {
                    writeError(client.getOutputStream(), 404, "Not Found")
                    return
                }
                if (isThumb) {
                    serveThumbnail(client.getOutputStream(), pathPart, file)
                } else {
                    serveFile(client.getOutputStream(), file, rangeHeader)
                }
            } catch (_: java.io.IOException) {
                // 客户端中途断开（Broken pipe / Connection reset）是 HTTP Range 播放的正常情况：
                // MediaPlayer 拿到所需字节后会主动关闭连接。此处静默处理，不崩溃。
                logNet("recording http client disconnected path=${socket.inetAddress?.hostAddress}")
            }
        }
    }

    private fun serveFile(output: OutputStream, file: File, rangeHeader: String?) {
        val total = file.length()
        val (start, end) = parseRange(rangeHeader, total)
        val length = end - start + 1

        val status = if (rangeHeader != null) "206 Partial Content" else "200 OK"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status\r\n")
        sb.append("Content-Type: video/mp4\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: $length\r\n")
        if (rangeHeader != null) {
            sb.append("Content-Range: bytes $start-$end/$total\r\n")
        }
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = length
            while (remaining > 0L) {
                // 停止时主动退出，避免 shutdownNow 后 IO 线程仍阻塞在 output.write
                if (!running.get()) break
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        output.flush()
    }

    /**
     * 返回 [fileId] 的首帧缩略图 JPEG。无法生成（损坏/录制中文件缺 moov）时返回 404，
     * 由查看端显示占位。缩略图很小（数 KB），不支持 Range，直接整段写回。
     */
    private fun serveThumbnail(output: OutputStream, fileId: String, file: File) {
        val jpeg = thumbnailGenerator.thumbnailBytes(fileId, file)
        if (jpeg == null) {
            writeError(output, 404, "Not Found")
            return
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: image/jpeg\r\n")
        sb.append("Content-Length: ${jpeg.size}\r\n")
        // 缩略图按 (fileId, size) 缓存且录像文件不可变，允许查看端/中间代理缓存 1 小时。
        sb.append("Cache-Control: max-age=3600\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.write(jpeg)
        output.flush()
    }

    private fun parseRange(rangeHeader: String?, total: Long): Pair<Long, Long> {
        val default = 0L to (total - 1)
        if (rangeHeader == null) return default
        // 格式：bytes=start-end / bytes=start- / bytes=-suffix
        val spec = rangeHeader.removePrefix("bytes=").trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return default
        val startStr = spec.substring(0, dash)
        val endStr = spec.substring(dash + 1)
        return when {
            startStr.isEmpty() -> {
                // bytes=-N：最后 N 字节
                val suffix = endStr.toLongOrNull() ?: return default
                val start = (total - suffix).coerceAtLeast(0L)
                start to (total - 1)
            }
            else -> {
                val start = startStr.toLongOrNull() ?: return default
                val end = if (endStr.isEmpty()) total - 1 else endStr.toLongOrNull() ?: (total - 1)
                start to end.coerceAtMost(total - 1)
            }
        }
    }

    private fun writeError(output: OutputStream, code: Int, message: String) {
        val body = "$code $message"
        val response = "HTTP/1.1 $body\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        runCatching {
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    /**
     * 校验 fileId 不含 `..` 且解析后绝对路径在 [recordingRoot] 目录内，防止路径穿越。
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

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val SOCKET_TIMEOUT_MILLIS = 30_000
        private const val HTTP_WORKER_THREADS = 8
    }
}
