package com.zx.homecamera.network

import com.zx.homecamera.core.protocol.EncodedMediaFrame
import com.zx.homecamera.core.protocol.MediaUdpPacket
import java.util.ArrayDeque

class RealtimeVideoFrameQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxQueueDelayMillis: Long = DEFAULT_MAX_QUEUE_DELAY_MILLIS,
    private val keyFrameRequestIntervalMillis: Long = DEFAULT_KEY_FRAME_REQUEST_INTERVAL_MILLIS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val onKeyFrameNeeded: () -> Unit = {},
) {
    private val frames = ArrayDeque<QueuedFrame>(capacity)
    private var waitingForKeyFrame = true
    private var lastKeyFrameRequestAtMillis = Long.MIN_VALUE

    @Synchronized
    fun offer(frame: EncodedMediaFrame) {
        if (frame.isCodecConfig()) {
            replaceQueuedCodecConfig(frame)
            (this as java.lang.Object).notifyAll()
            return
        }

        if (waitingForKeyFrame && !frame.isKeyFrame()) {
            requestKeyFrameIfDue()
            return
        }

        if (frame.isKeyFrame()) {
            keepOnlyLatestCodecConfig()
            waitingForKeyFrame = false
        }

        if (frames.size >= capacity) {
            val dropped = removeOldestNonKeyFrame() ?: frames.pollFirst()
            if (dropped?.frame?.isKeyOrCodecConfig() == true) {
                frames.clear()
                waitingForKeyFrame = true
                requestKeyFrameIfDue()
                return
            }
        }

        frames.addLast(QueuedFrame(frame, clockMillis()))
        (this as java.lang.Object).notifyAll()
    }

    @Synchronized
    fun poll(timeoutMillis: Long = 0L): EncodedMediaFrame? {
        if (frames.isEmpty() && timeoutMillis > 0L) {
            (this as java.lang.Object).wait(timeoutMillis)
        }

        while (true) {
            val queued = frames.pollFirst() ?: return null
            if (clockMillis() - queued.enqueuedAtMillis <= maxQueueDelayMillis) {
                return queued.frame
            }

            if (queued.frame.isKeyOrCodecConfig()) {
                frames.clear()
                waitingForKeyFrame = true
                requestKeyFrameIfDue()
            }
        }
    }

    @Synchronized
    fun clear() {
        frames.clear()
        waitingForKeyFrame = true
    }

    private fun removeOldestNonKeyFrame(): QueuedFrame? {
        val iterator = frames.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            if (!queued.frame.isKeyOrCodecConfig()) {
                iterator.remove()
                return queued
            }
        }
        return null
    }

    private fun replaceQueuedCodecConfig(frame: EncodedMediaFrame) {
        val iterator = frames.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().frame.isCodecConfig()) {
                iterator.remove()
            }
        }
        frames.addLast(QueuedFrame(frame, clockMillis()))
        trimToCapacity()
    }

    private fun keepOnlyLatestCodecConfig() {
        val latestConfig = frames
            .asSequence()
            .filter { it.frame.isCodecConfig() }
            .lastOrNull()
        frames.clear()
        latestConfig?.let(frames::addLast)
    }

    private fun trimToCapacity() {
        while (frames.size > capacity) {
            val dropped = removeOldestNonKeyFrame() ?: frames.pollFirst()
            if (dropped?.frame?.isKeyOrCodecConfig() == true) {
                frames.clear()
                waitingForKeyFrame = true
                requestKeyFrameIfDue()
                return
            }
        }
    }

    private fun requestKeyFrameIfDue() {
        val now = clockMillis()
        if (lastKeyFrameRequestAtMillis == Long.MIN_VALUE ||
            now - lastKeyFrameRequestAtMillis >= keyFrameRequestIntervalMillis
        ) {
            lastKeyFrameRequestAtMillis = now
            onKeyFrameNeeded()
        }
    }

    private data class QueuedFrame(
        val frame: EncodedMediaFrame,
        val enqueuedAtMillis: Long,
    )

    companion object {
        const val DEFAULT_CAPACITY = 4
        const val DEFAULT_MAX_QUEUE_DELAY_MILLIS = 300L
        const val DEFAULT_KEY_FRAME_REQUEST_INTERVAL_MILLIS = 500L
    }
}

private fun EncodedMediaFrame.isKeyOrCodecConfig(): Boolean =
    isKeyFrame() || isCodecConfig()

private fun EncodedMediaFrame.isKeyFrame(): Boolean =
    flags and MediaUdpPacket.FLAG_KEY_FRAME != 0

private fun EncodedMediaFrame.isCodecConfig(): Boolean =
    flags and MediaUdpPacket.FLAG_CODEC_CONFIG != 0
