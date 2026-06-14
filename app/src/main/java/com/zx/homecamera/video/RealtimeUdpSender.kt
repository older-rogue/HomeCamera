package com.zx.homecamera.video

import android.util.Log
import com.zx.homecamera.core.protocol.MediaCodecType
import com.zx.homecamera.core.protocol.MediaTrack
import com.zx.homecamera.core.protocol.MediaUdpPacket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class OutboundMediaFrame(
    val track: MediaTrack,
    val codec: MediaCodecType,
    val sequenceNumber: Int,
    val timestampMicros: Long,
    val flags: Int,
    val data: ByteArray,
)

data class StreamDestination(
    val hostAddress: String,
    val udpPort: Int,
) {
    val address: InetAddress by lazy { InetAddress.getByName(hostAddress) }
}

interface RealtimeUdpSocket {
    fun send(datagram: ByteArray, destination: StreamDestination)
}

class DatagramRealtimeUdpSocket(
    private val socket: DatagramSocket,
) : RealtimeUdpSocket {
    override fun send(datagram: ByteArray, destination: StreamDestination) {
        socket.send(DatagramPacket(datagram, datagram.size, destination.address, destination.udpPort))
    }
}

class RealtimeUdpSender(
    private val clients: () -> List<StreamDestination>,
    private val socket: RealtimeUdpSocket,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val packetPacingMicros: Long = DEFAULT_PACKET_PACING_MICROS,
    private val sleeper: (Long) -> Unit = ::sleepMicros,
) {
    private val running = AtomicBoolean(false)
    private val lock = Any()
    private val queue = ArrayDeque<OutboundMediaFrame>(queueCapacity)
    private var executor: ExecutorService? = null
    private var droppedFrames = 0L
    private var sentVideoFrames = 0L
    private var sentKeyFrames = 0L
    private var sentDatagrams = 0L
    private var maxDatagramsPerFrame = 0
    private var lastStatsLogAtMillis = System.currentTimeMillis()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor = Executors.newSingleThreadExecutor().also { pool ->
            pool.execute {
                try {
                    while (running.get()) {
                        sendNextBlocking()
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        synchronized(lock) {
            queue.clear()
            (lock as Object).notifyAll()
        }
        executor?.shutdownNow()
        executor = null
    }

    fun offer(frame: OutboundMediaFrame) {
        synchronized(lock) {
            if (queue.size >= queueCapacity) {
                val dropped = removeOldestNonKeyVideoFrame() ?: queue.pollFirst()
                if (dropped != null) droppedFrames++
            }
            queue.addLast(frame)
            (lock as Object).notifyAll()
        }
    }

    fun sendNextForTest() {
        val frame = synchronized(lock) { queue.pollFirst() } ?: return
        sendFrame(frame)
    }

    fun drainQueuedForTest(): List<OutboundMediaFrame> =
        synchronized(lock) { queue.toList() }

    private fun sendNextBlocking() {
        val frame = synchronized(lock) {
            while (running.get() && queue.isEmpty()) {
                try {
                    (lock as Object).wait(20L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            queue.pollFirst()
        } ?: return
        sendFrame(frame)
    }

    private fun sendFrame(frame: OutboundMediaFrame) {
        val destinations = clients()
        if (destinations.isEmpty()) return

        val datagrams = MediaUdpPacket.encodeFrame(
            track = frame.track,
            codec = frame.codec,
            sequenceNumber = frame.sequenceNumber,
            timestampMicros = frame.timestampMicros,
            flags = frame.flags,
            data = frame.data,
        )
        datagrams.forEachIndexed { index, datagram ->
            destinations.forEach { destination ->
                runCatching {
                    socket.send(datagram, destination)
                }
            }
            if (packetPacingMicros > 0L && index < datagrams.lastIndex) {
                sleeper(packetPacingMicros)
            }
        }
        recordStats(frame, datagrams.size)
    }

    private fun removeOldestNonKeyVideoFrame(): OutboundMediaFrame? {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            if (frame.track == MediaTrack.Video && frame.flags and FLAG_KEY_FRAME == 0) {
                iterator.remove()
                return frame
            }
        }
        return null
    }

    private fun recordStats(frame: OutboundMediaFrame, datagramCount: Int) {
        sentDatagrams += datagramCount
        maxDatagramsPerFrame = maxOf(maxDatagramsPerFrame, datagramCount)
        if (frame.track == MediaTrack.Video) {
            sentVideoFrames++
            if (frame.flags and FLAG_KEY_FRAME != 0) sentKeyFrames++
        }
        val now = System.currentTimeMillis()
        if (now - lastStatsLogAtMillis >= STATS_LOG_INTERVAL_MILLIS) {
            Log.i(
                TAG,
                "udp sender: video=$sentVideoFrames key=$sentKeyFrames datagrams=$sentDatagrams " +
                    "maxFrameDatagrams=$maxDatagramsPerFrame dropped=$droppedFrames",
            )
            sentVideoFrames = 0L
            sentKeyFrames = 0L
            sentDatagrams = 0L
            maxDatagramsPerFrame = 0
            droppedFrames = 0L
            lastStatsLogAtMillis = now
        }
    }

    companion object {
        const val FLAG_KEY_FRAME = MediaUdpPacket.FLAG_KEY_FRAME
        const val DEFAULT_QUEUE_CAPACITY = 12
        const val DEFAULT_PACKET_PACING_MICROS = 250L
        private const val STATS_LOG_INTERVAL_MILLIS = 1_000L
        private const val TAG = "RealtimeUdpSender"

        private fun sleepMicros(micros: Long) {
            val millis = micros / 1_000L
            val nanos = ((micros % 1_000L) * 1_000L).toInt()
            Thread.sleep(millis, nanos)
        }
    }
}
