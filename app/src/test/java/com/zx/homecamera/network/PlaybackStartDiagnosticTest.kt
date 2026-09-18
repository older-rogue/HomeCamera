package com.zx.homecamera.network

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import com.zx.homecamera.service.RecordingHttpServer
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史录像 HTTP 起播链路回归测试。
 *
 * 采集端录像为 MediaMuxer 产物（moov 在文件尾部）。起播是否快，取决于 Media3 解析器
 * 是否走"定位尾部 Range 读 moov 再回 0 拉数据"的快路径，以及 HTTP 服务端能否快速响应
 * 起播序列。两个测试分别锁定这两点，防止将来改动破坏起播速度。
 */
class PlaybackStartDiagnosticTest {

    private var server: RecordingHttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun moovEndFile(): File =
        File(javaClass.classLoader!!.getResource("moov_end.mp4").toURI())

    private fun startServer(root: File): Pair<RecordingHttpServer, Int> {
        val port = freePort()
        val srv = RecordingHttpServer(root, port, passwordVerifier = { true })
        srv.start()
        server = srv
        return srv to port
    }

    /**
     * moov-at-end 文件起播必须走快路径：首条连接只读文件头部一小段（ftyp + mdat 头），
     * 随即把读取位置定位到文件尾部（跳过 mdat 找 moov），而不是把整个文件下载一遍。
     */
    @Test
    fun moovAtEndStartSeeksToTailWithoutDownloadingWholeFile() {
        val file = moovEndFile()
        val (srv, port) = startServer(file.parentFile)
        val uri = URI("http://127.0.0.1:$port/${file.name}")

        val firstConnection = SimpleHttpDataSource(uri)
        firstConnection.openForTest()
        var input = DefaultExtractorInput(firstConnection, 0L, C.LENGTH_UNSET.toLong())
        val output = CollectingOutput()
        val extractor = Mp4Extractor()
        extractor.init(output)
        val positionHolder = PositionHolder()

        var tailSeekObserved = false
        var reads = 0
        while (!tailSeekObserved && reads < 10) {
            reads++
            val result = extractor.read(input, positionHolder)
            if (positionHolder.position != C.POSITION_UNSET.toLong()) {
                // 解析器请求定位到文件后半段：即"跳过 mdat 找尾部 moov"的快路径。
                tailSeekObserved = positionHolder.position.toLong() > file.length() / 2
                break
            }
            if (result == Extractor.RESULT_END_OF_INPUT) break
        }
        srv.stop()
        server = null

        assertTrue(
            "moov-at-end start should seek to the file tail (skip mdat to find moov)",
            tailSeekObserved,
        )
        // 定位前首条连接只读取文件的一小部分（ftyp + mdat 头），绝不接近整个文件。
        val firstConnectionBytes = firstConnection.bytesRead
        assertTrue(
            "should not download the whole file before start, read $firstConnectionBytes of ${file.length()}",
            firstConnectionBytes < file.length() / 2,
        )
    }

    /**
     * 服务端对 ExoPlayer 起播序列（200 整文件开头 -> 尾部 Range 读 moov -> 重新拉 0 起数据）
     * 的每个阶段都应毫秒级响应，且 Range 字节数精确。
     */
    @Test
    fun httpServerServesExoPlayerStartSequenceWithLowLatency() {
        // 模拟真实 2 分钟片段的大小（约 30MB），文件内容无关紧要，服务端按字节 + Range 响应。
        val bigFile = File.createTempFile("playback_probe", ".mp4")
        bigFile.deleteOnExit()
        RandomAccessFile(bigFile, "rw").use { raf ->
            raf.setLength(30L * 1024 * 1024)
        }
        val (srv, port) = startServer(bigFile.parentFile)

        val tail = bigFile.length() - 4_096
        val phase1 = requestPhase(port, bigFile.name, range = null, readBytes = 64 * 1024)
        val phase2 = requestPhase(port, bigFile.name, range = "bytes=$tail-", readBytes = Int.MAX_VALUE)
        val phase3 = requestPhase(port, bigFile.name, range = null, readBytes = 2 * 1024 * 1024)
        srv.stop()
        server = null
        bigFile.delete()

        // LAN 场景下每个阶段都应毫秒级；即使 CI 慢机器也远小于 3s。
        assertTrue("phase1 too slow: ${phase1.first}ms", phase1.first < 3_000)
        assertTrue("phase2 too slow: ${phase2.first}ms", phase2.first < 3_000)
        assertTrue("phase3 too slow: ${phase3.first}ms", phase3.first < 3_000)
        assertEquals(64L * 1024, phase1.second.toLong())
        assertEquals(4_096L, phase2.second.toLong())
        assertEquals(2L * 1024 * 1024, phase3.second.toLong())
    }

    /** 发起一次 HTTP 请求并读 [readBytes] 字节（读满或 EOF）。返回 (耗时ms, 响应体字节数)。 */
    private fun requestPhase(
        port: Int,
        path: String,
        range: String?,
        readBytes: Int,
    ): Pair<Long, Int> {
        val start = System.nanoTime()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val request = buildString {
                append("GET /$path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                if (range != null) append("Range: $range\r\n")
                append("\r\n")
            }
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()
            readUntilHeadersEnd(socket.getInputStream())
            var remaining = readBytes
            var read = 0
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = socket.getInputStream().read(buffer, 0, minOf(buffer.size, remaining))
                if (n <= 0) break
                read += n
                remaining -= n
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            return elapsedMs to read
        }
    }

    /** 逐字节读到 \r\n\r\n（响应头结束）。 */
    private fun readUntilHeadersEnd(input: InputStream) {
        val window = ByteArray(4)
        var filled = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            window[filled % 4] = b.toByte()
            filled++
            if (filled >= 4 &&
                window[(filled - 1) % 4] == '\n'.code.toByte() &&
                window[(filled - 2) % 4] == '\r'.code.toByte() &&
                window[(filled - 3) % 4] == '\n'.code.toByte() &&
                window[(filled - 4) % 4] == '\r'.code.toByte()
            ) {
                break
            }
        }
    }
}

/** 纯 JVM HTTP 数据源：统计本连接累计读取字节，供判断起播阶段下载量。 */
private class SimpleHttpDataSource(
    private val url: URI,
    private val startPosition: Long = 0L,
) : DataSource {
    var bytesRead = 0L
    var openCount = 0
    private var conn: HttpURLConnection? = null
    private var stream: InputStream? = null

    override fun open(dataSpec: DataSpec): Long {
        doOpen()
        return conn?.contentLengthLong ?: C.LENGTH_UNSET.toLong()
    }

    /** 无 DataSpec 打开（URL 已烘焙进构造），供测试直接驱动。 */
    fun openForTest() = doOpen()

    private fun doOpen() {
        openCount++
        val connection = (url.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 10_000
            if (startPosition != 0L) setRequestProperty("Range", "bytes=$startPosition-")
        }
        conn = connection
        stream = connection.inputStream
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = stream?.read(buffer, offset, length) ?: -1
        if (n > 0) bytesRead += n
        return n
    }

    override fun close() {
        runCatching { stream?.close() }
        runCatching { conn?.disconnect() }
    }

    override fun getUri(): android.net.Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = Unit
}

/** 只关心 SeekMap 是否产出，轨道输出全部丢弃。 */
private class CollectingOutput : ExtractorOutput {
    var seekMap: SeekMap? = null

    override fun track(id: Int, type: Int): TrackOutput = DummyTrackOutput
    override fun endTracks() = Unit
    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }

    private object DummyTrackOutput : TrackOutput {
        override fun format(format: Format) = Unit
        override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean): Int = 0
        override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = 0
        override fun sampleData(buffer: androidx.media3.common.util.ParsableByteArray, length: Int) = Unit
        override fun sampleData(buffer: androidx.media3.common.util.ParsableByteArray, length: Int, sampleDataPart: Int) = Unit
        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = Unit
    }
}
