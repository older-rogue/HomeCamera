package com.zx.homecamera.video

import com.zx.homecamera.core.protocol.MediaCodecType
import com.zx.homecamera.core.protocol.MediaTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

class RealtimeUdpSenderTest {
    @Test
    fun interruptedBlockingSendReturnsWithoutThrowing() {
        val sender = RealtimeUdpSender(
            clients = { listOf(StreamDestination("127.0.0.1", 62011)) },
            socket = FakeUdpSocket(),
            queueCapacity = 4,
            packetPacingMicros = 0L,
            sleeper = {},
        )
        sender.setRunningForTest(true)
        val thrown = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                sender.sendNextBlockingForTest()
            } catch (throwable: Throwable) {
                thrown.set(throwable)
            }
        }

        worker.start()
        assertTrue(waitUntilWaiting(worker))
        sender.setRunningForTest(false)
        worker.interrupt()
        worker.join(1_000L)

        assertTrue(!worker.isAlive)
        assertNull(thrown.get())
    }

    private fun RealtimeUdpSender.setRunningForTest(value: Boolean) {
        val field = RealtimeUdpSender::class.java.getDeclaredField("running").apply {
            isAccessible = true
        }
        field.get(this).let { running ->
            running::class.java.getMethod("set", Boolean::class.javaPrimitiveType).invoke(running, value)
        }
    }

    private fun RealtimeUdpSender.sendNextBlockingForTest() {
        val method: Method = RealtimeUdpSender::class.java.getDeclaredMethod("sendNextBlocking").apply {
            isAccessible = true
        }
        try {
            method.invoke(this)
        } catch (exception: InvocationTargetException) {
            throw exception.cause ?: exception
        }
    }

    private fun waitUntilWaiting(thread: Thread): Boolean {
        val deadline = System.currentTimeMillis() + 1_000L
        while (System.currentTimeMillis() < deadline) {
            if (thread.state == Thread.State.WAITING || thread.state == Thread.State.TIMED_WAITING) return true
            Thread.sleep(10L)
        }
        return false
    }

    @Test
    fun boundedQueueDropsOldestNonKeyVideoFrame() {
        val socket = FakeUdpSocket()
        val sender = RealtimeUdpSender(
            clients = { listOf(StreamDestination("127.0.0.1", 62011)) },
            socket = socket,
            queueCapacity = 3,
            packetPacingMicros = 0L,
            sleeper = {},
        )

        sender.offer(frame(sequenceNumber = 1, flags = RealtimeUdpSender.FLAG_KEY_FRAME))
        sender.offer(frame(sequenceNumber = 2))
        sender.offer(frame(sequenceNumber = 3))
        sender.offer(frame(sequenceNumber = 4))

        assertEquals(listOf(1, 3, 4), sender.drainQueuedForTest().map { it.sequenceNumber })
    }

    @Test
    fun pacingSleepsBetweenDatagrams() {
        val sleeps = mutableListOf<Long>()
        val socket = FakeUdpSocket()
        val sender = RealtimeUdpSender(
            clients = { listOf(StreamDestination("127.0.0.1", 62011)) },
            socket = socket,
            queueCapacity = 4,
            packetPacingMicros = 500L,
            sleeper = sleeps::add,
        )

        sender.offer(frame(sequenceNumber = 1, data = ByteArray(2_400) { 1 }))
        sender.sendNextForTest()

        assertTrue(socket.sentDatagrams.size > 1)
        assertEquals(socket.sentDatagrams.size - 1, sleeps.size)
        assertTrue(sleeps.all { it == 500L })
    }

    private fun frame(
        sequenceNumber: Int,
        flags: Int = 0,
        data: ByteArray = byteArrayOf(sequenceNumber.toByte()),
    ): OutboundMediaFrame =
        OutboundMediaFrame(
            track = MediaTrack.Video,
            codec = MediaCodecType.H264,
            sequenceNumber = sequenceNumber,
            timestampMicros = sequenceNumber * 1_000L,
            flags = flags,
            data = data,
        )
}

private class FakeUdpSocket : RealtimeUdpSocket {
    val sentDatagrams = mutableListOf<ByteArray>()

    override fun send(datagram: ByteArray, destination: StreamDestination) {
        sentDatagrams.add(datagram)
    }
}
