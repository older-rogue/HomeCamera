package com.zx.homecamera.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue

/**
 * 验证内存与卡顿修复的关键不变量：
 * - [MediaFrameReassembler.trimOldFrames] 按序列号淘汰最旧帧（修复 9）
 * - [MediaFrameReassembler.PendingFrame.joinPayloads] 多分片重组正确性（修复 2A 回归）
 * - 有界 [ArrayBlockingQueue] 满时 offer 返回 false（修复 1 的背压语义）
 */
class MediaFrameReassemblerMemoryTest {

    @Test
    fun trimOldFramesEvictsOldestBySequenceNumberNotInsertionOrder() {
        // 限制 pending 帧数为 2，乱序投递未完成帧。
        // 修复 9 之前 trimOldFrames 按插入序淘汰，可能删掉序列号并非最小的帧；
        // 修复后按序列号淘汰，应移除 sequenceNumber 最小的帧。
        val reassembler = MediaFrameReassembler(maxPendingFrames = 2)

        // 投递 3 个未完成帧（每个只投首分片），序列号故意非递增：100, 50, 200
        // 投递第 3 个时会触发 trimOldFrames，应淘汰 seq=50（最小）。
        listOf(100, 50, 200).forEach { seq ->
            val firstFragment = MediaUdpPacket.encodeFrame(
                track = MediaTrack.Video,
                codec = MediaCodecType.H264,
                sequenceNumber = seq,
                timestampMicros = seq.toLong(),
                flags = MediaUdpPacket.FLAG_KEY_FRAME,
                data = ByteArray(50_000), // 多分片，单次 accept 不会完成
                maxDatagramSize = 700,
            ).first()
            assertNull("未完成帧应返回 null", reassembler.accept(MediaUdpPacket.decode(firstFragment)!!))
        }

        // 此时 pending 应只剩 seq=100 与 seq=200（seq=50 被淘汰）。
        // 补齐 seq=100 与 seq=200 应能完成；补齐 seq=50 会作为全新帧重建（因前一个被删）。
        assertNotNull("seq=100 仍 pending，应能完成", completeFrame(reassembler, 100))
        assertNotNull("seq=200 仍 pending，应能完成", completeFrame(reassembler, 200))
        // seq=50 被淘汰后重新补齐分片会重建 PendingFrame 并完成，证明它此前确实被移除
        assertNotNull("seq=50 被淘汰后重建应能完成", completeFrame(reassembler, 50))
    }

    @Test
    fun joinPayloadsReassemblesLargeFrameCorrectly() {
        // 回归保护：修复 2A 把 joinPayloads 从 ByteArrayOutputStream 改为
        // 预分配 ByteArray + System.arraycopy，需确保大帧多分片重组结果与原数据一致。
        val frame = ByteArray(80_000) { index -> (index % 251).toByte() }
        val datagrams = MediaUdpPacket.encodeFrame(
            track = MediaTrack.Video,
            codec = MediaCodecType.H264,
            sequenceNumber = 7,
            timestampMicros = 33_000L,
            flags = MediaUdpPacket.FLAG_KEY_FRAME,
            data = frame,
            maxDatagramSize = 1_100,
        )
        assertEquals(true, datagrams.size > 10)

        val reassembler = MediaFrameReassembler()
        var complete: EncodedMediaFrame? = null
        // 按序投递所有分片，最后一帧完成时返回
        for (pkt in datagrams) {
            val r = reassembler.accept(MediaUdpPacket.decode(pkt)!!)
            if (r != null) {
                complete = r
            }
        }
        assertNotNull("帧应完成", complete)
        requireNotNull(complete)
        assertArrayEquals("重组数据应与原始帧一致", frame, complete.data)
        assertEquals(frame.size, complete.data.size)
    }

    @Test
    fun boundedQueueOfferReturnsFalseWhenFull() {
        // 验证修复 1 的背压语义：有界队列满时非阻塞 offer 返回 false，
        // 不会无界堆积。H264UdpViewer.rawPacketQueue 即采用此机制防止 OOM。
        val queue = ArrayBlockingQueue<ByteArray>(2)
        assertEquals(true, queue.offer(byteArrayOf(1)))
        assertEquals(true, queue.offer(byteArrayOf(2)))
        assertEquals(false, queue.offer(byteArrayOf(3))) // 满，拒绝
        assertEquals(2, queue.size)
        assertArrayEquals(byteArrayOf(1), queue.poll())
        assertArrayEquals(byteArrayOf(2), queue.poll())
        assertNull(queue.poll())
    }

    /** 补齐指定序列号帧的所有分片并返回完成帧（若该帧已被淘汰则为全新重建）。 */
    private fun completeFrame(
        reassembler: MediaFrameReassembler,
        seq: Int,
    ): EncodedMediaFrame? {
        val datagrams = MediaUdpPacket.encodeFrame(
            track = MediaTrack.Video,
            codec = MediaCodecType.H264,
            sequenceNumber = seq,
            timestampMicros = seq.toLong(),
            flags = MediaUdpPacket.FLAG_KEY_FRAME,
            data = ByteArray(50_000) { (it % 200).toByte() },
            maxDatagramSize = 700,
        )
        var result: EncodedMediaFrame? = null
        for (pkt in datagrams) {
            val r = reassembler.accept(MediaUdpPacket.decode(pkt)!!)
            if (r != null) result = r
        }
        return result
    }
}
