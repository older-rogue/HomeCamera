package com.zx.homecamera

import android.app.Application
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.ClientState
import com.zx.homecamera.core.app.ScanStatus
import com.zx.homecamera.network.SocketTcpPortConnector
import com.zx.homecamera.network.TcpSubnetScanner
import com.zx.homecamera.network.WifiSubnetProvider
import com.zx.homecamera.network.logNet
import com.zx.homecamera.service.CollectorForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

class ClientListViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ClientState())
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private val scannerExecutor = Executors.newSingleThreadExecutor()
    private val scanGeneration = AtomicLong()
    private var scanFuture: Future<*>? = null

    init {
        startScan()
    }

    fun stopScan() {
        cancelScan()
    }

    fun startScan() {
        cancelScan()
        val generation = scanGeneration.incrementAndGet()
        _state.value = _state.value.copy(scanStatus = ScanStatus.Scanning, devices = emptyList())
        val context = getApplication<Application>()
        scanFuture = scannerExecutor.submit {
            val subnetProvider = WifiSubnetProvider(context)
            while (!Thread.currentThread().isInterrupted && scanGeneration.get() == generation) {
                val subnet = subnetProvider.subnet().getOrElse { error ->
                    dispatchScanFailed(generation, error.message ?: "无法获取 Wi-Fi 网段")
                    return@submit
                }
                logNet("scan loop generation=$generation local=${subnet.localAddress} hosts=${subnet.hosts.first()}..${subnet.hosts.last()}")
                val devices = TcpSubnetScanner(
                    SocketTcpPortConnector(subnet.network.socketFactory),
                ).scan(
                    hosts = subnet.hosts,
                    tcpPort = CollectorForegroundService.CONTROL_PORT,
                )
                if (scanGeneration.get() != generation || Thread.currentThread().isInterrupted) return@submit
                if (devices.isNotEmpty()) {
                    viewModelScope.launch {
                        if (scanGeneration.get() == generation) {
                            _state.value = _state.value.copy(
                                scanStatus = ScanStatus.Finished,
                                devices = devices.sortedWith(
                                    compareByDescending<CollectorDevice> { it.online }.thenBy { it.name },
                                ),
                            )
                        }
                    }
                    return@submit
                }
                try {
                    Thread.sleep(SCAN_RETRY_DELAY_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@submit
                }
            }
        }
    }

    private fun cancelScan() {
        scanGeneration.incrementAndGet()
        scanFuture?.cancel(true)
        scanFuture = null
    }

    private fun dispatchScanFailed(generation: Long, reason: String) {
        viewModelScope.launch {
            if (scanGeneration.get() == generation) {
                _state.value = _state.value.copy(scanStatus = ScanStatus.Error)
            }
        }
    }

    override fun onCleared() {
        cancelScan()
        scannerExecutor.shutdownNow()
        super.onCleared()
    }

    companion object {
        private const val SCAN_RETRY_DELAY_MILLIS = 5_000L
    }
}
