package com.zx.homecamera.network

import android.util.Log
import com.zx.homecamera.core.app.CollectorDevice
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

fun interface TcpPortConnector {
    fun canConnect(host: String, port: Int, timeoutMillis: Int): Boolean
}

class SocketTcpPortConnector(
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
) : TcpPortConnector {
    override fun canConnect(host: String, port: Int, timeoutMillis: Int): Boolean {
        socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
        }
        return true
    }
}

class TcpSubnetScanner(
    private val connector: TcpPortConnector = SocketTcpPortConnector(),
) {
    fun scan(
        hosts: List<String>,
        tcpPort: Int,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
        parallelism: Int = DEFAULT_PARALLELISM,
    ): List<CollectorDevice> {
        if (hosts.isEmpty()) return emptyList()

        val uniqueHosts = hosts.distinct()
        val failures = ConcurrentHashMap<String, AtomicInteger>()
        logNet("scan tcp start hosts=${uniqueHosts.size} port=$tcpPort timeoutMs=$timeoutMillis parallelism=$parallelism")
        val executor = Executors.newFixedThreadPool(parallelism.coerceIn(1, uniqueHosts.size))
        val completion = ExecutorCompletionService<CollectorDevice?>(executor)
        var submitted = 0

        return try {
            uniqueHosts.forEach { host ->
                completion.submit(
                    Callable {
                        if (Thread.currentThread().isInterrupted) {
                            null
                        } else {
                            probeHost(host, tcpPort, timeoutMillis, failures)
                        }
                    },
                )
                submitted += 1
            }

            buildList {
                repeat(submitted) {
                    val device = completion.take().get()
                    if (device != null) add(device)
                }
            }.sortedBy { it.hostAddress }.also { devices ->
                logNet(
                    "scan tcp done found=${devices.size} devices=${devices.joinToString { it.hostAddress }} failures=${failureSummary(failures)}",
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probeHost(
        host: String,
        tcpPort: Int,
        timeoutMillis: Int,
        failures: ConcurrentHashMap<String, AtomicInteger>,
    ): CollectorDevice? {
        return try {
            if (!connector.canConnect(host, tcpPort, timeoutMillis)) {
                failures.increment("false")
                null
            } else {
                logNet("scan tcp hit host=$host port=$tcpPort")
                CollectorDevice(
                    deviceId = "$host:$tcpPort",
                    name = "采集端 $host",
                    hostAddress = host,
                    tcpPort = tcpPort,
                    online = true,
                )
            }
        } catch (error: Exception) {
            failures.increment(error.failureName())
            null
        }
    }

    private fun failureSummary(failures: Map<String, AtomicInteger>): String =
        failures.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value.get()}" }
            .ifBlank { "none" }

    private fun ConcurrentHashMap<String, AtomicInteger>.increment(key: String) {
        computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
    }

    private fun Exception.failureName(): String = when (this) {
        is SocketTimeoutException -> "timeout"
        is ConnectException -> "refused"
        is NoRouteToHostException -> "no_route"
        else -> this::class.java.simpleName.ifBlank { "error" }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 500
        const val DEFAULT_PARALLELISM = 32
    }
}

const val NETWORK_LOG_TAG = "HomeCameraNet"

fun logNet(message: String) {
    try {
        Log.d(NETWORK_LOG_TAG, message)
    } catch (_: RuntimeException) {
    }
}

fun logNetError(message: String, error: Throwable) {
    try {
        Log.e(NETWORK_LOG_TAG, message, error)
    } catch (_: RuntimeException) {
    }
}
