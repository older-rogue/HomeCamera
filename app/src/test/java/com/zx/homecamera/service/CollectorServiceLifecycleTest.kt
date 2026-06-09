package com.zx.homecamera.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectorServiceLifecycleTest {
    @Test
    fun staleStopAfterRestartDoesNotReleaseActiveCollector() {
        val lifecycle = CollectorServiceLifecycle()

        assertTrue(lifecycle.onStart(startId = 1).shouldStartResources)
        assertTrue(lifecycle.onStart(startId = 3).isRunning)

        val staleStop = lifecycle.onStop(startId = 2)

        assertFalse(staleStop.shouldReleaseResources)
        assertFalse(staleStop.shouldRequestServiceStop)
        assertTrue(staleStop.isRunning)
    }

    @Test
    fun currentStopReleasesCollectorWithoutStoppingService() {
        val lifecycle = CollectorServiceLifecycle()
        lifecycle.onStart(startId = 1)

        val stop = lifecycle.onStop(startId = 1)

        assertTrue(stop.shouldReleaseResources)
        assertFalse(stop.shouldRequestServiceStop)
        assertTrue(stop.shouldKeepServiceForeground)
        assertFalse(stop.isRunning)
    }

    @Test
    fun destroyFromOlderServiceStopDoesNotReleaseRestartedCollector() {
        val lifecycle = CollectorServiceLifecycle()
        lifecycle.onStart(startId = 1)
        lifecycle.onServiceStopResult(startId = 2, stopped = true)
        lifecycle.onStart(startId = 3)

        val destroy = lifecycle.onDestroyed()

        assertFalse(destroy.shouldReleaseResources)
        assertTrue(destroy.isRunning)
    }
}
