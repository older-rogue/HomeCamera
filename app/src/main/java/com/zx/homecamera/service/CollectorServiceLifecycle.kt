package com.zx.homecamera.service

class CollectorServiceLifecycle {
    private var running = false
    private var latestStartId = 0
    private var pendingStopStartId: Int? = null

    @Synchronized
    fun onStart(startId: Int): Decision {
        latestStartId = maxOf(latestStartId, startId)
        val shouldStartResources = !running
        running = true
        return Decision(
            shouldStartResources = shouldStartResources,
            isRunning = running,
        )
    }

    @Synchronized
    fun onStop(startId: Int): Decision {
        if (startId < latestStartId) {
            return Decision(isRunning = running)
        }

        latestStartId = maxOf(latestStartId, startId)
        val shouldReleaseResources = running
        running = false
        return Decision(
            shouldReleaseResources = shouldReleaseResources,
            shouldKeepServiceForeground = true,
            isRunning = running,
        )
    }

    @Synchronized
    fun onStartFailed(startId: Int): Decision {
        if (startId < latestStartId) {
            return Decision(isRunning = running)
        }

        running = false
        return Decision(
            shouldReleaseResources = true,
            shouldRequestServiceStop = true,
            isRunning = running,
        )
    }

    @Synchronized
    fun onServiceStopResult(startId: Int, stopped: Boolean) {
        if (stopped) {
            pendingStopStartId = startId
        }
    }

    @Synchronized
    fun onDestroyed(): Decision {
        val stopStartId = pendingStopStartId
        val shouldReleaseResources = stopStartId == null || stopStartId >= latestStartId
        if (shouldReleaseResources) {
            running = false
        }
        pendingStopStartId = null
        return Decision(
            shouldReleaseResources = shouldReleaseResources,
            isRunning = running,
        )
    }

    data class Decision(
        val shouldStartResources: Boolean = false,
        val shouldReleaseResources: Boolean = false,
        val shouldRequestServiceStop: Boolean = false,
        val shouldKeepServiceForeground: Boolean = false,
        val isRunning: Boolean = false,
    )
}
