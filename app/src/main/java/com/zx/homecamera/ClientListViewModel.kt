package com.zx.homecamera

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.ClientState
import com.zx.homecamera.core.app.ScanStatus
import com.zx.homecamera.network.CollectorDiscoveryListener
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
    private val discoveryExecutor = Executors.newSingleThreadExecutor()
    private val scanGeneration = AtomicLong()
    private var scanFuture: Future<*>? = null
    private var discoveryFuture: Future<*>? = null
    private var discoveryListener: CollectorDiscoveryListener? = null

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

        // 主通道：UDP 广播监听，采集端周期性广播 -> 秒级发现
        startDiscoveryListener(generation, context)

        // 辅通道：TCP 子网兜底扫描，覆盖广播被路由器过滤的场景
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
                    publishDevices(generation, devices)
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

    private fun startDiscoveryListener(generation: Long, context: Application) {
        val listener = CollectorDiscoveryListener(
            onDevice = { device ->
                viewModelScope.launch {
                    if (scanGeneration.get() == generation) {
                        addDevice(generation, device)
                    }
                }
            },
        )
        discoveryListener = listener
        discoveryFuture = discoveryExecutor.submit {
            listener.run()
        }
    }

    /**
     * 广播通道增量合并发现的设备。同一 deviceId 已存在时用广播设备替换：
     * 广播携带用户配置的名称（权威来源），TCP 兜底扫描的名字只是"采集端 <ip>"占位，
     * 不能让它覆盖用户设置的名称。
     */
    private fun addDevice(generation: Long, device: CollectorDevice) {
        if (scanGeneration.get() != generation) return
        val current = _state.value.devices
        val merged = if (current.any { it.deviceId == device.deviceId }) {
            current.map { existing ->
                if (existing.deviceId == device.deviceId) device else existing
            }
        } else {
            current + device
        }.sortedWith(
            compareByDescending<CollectorDevice> { it.online }.thenBy { it.name },
        )
        _state.value = _state.value.copy(scanStatus = ScanStatus.Finished, devices = merged)
        logNet("discovery merged deviceId=${device.deviceId} total=${merged.size}")
    }

    /**
     * TCP 兜底通道一次性发布整批发现的设备。与广播通道已发现的设备按 deviceId 去重：
     * 已存在（广播已提供权威名称）的设备保留原条目，仅补充广播未发现的设备。
     */
    private fun publishDevices(generation: Long, devices: List<CollectorDevice>) {
        viewModelScope.launch {
            if (scanGeneration.get() == generation) {
                val existing = _state.value.devices.associateBy { it.deviceId }
                val newOnes = devices.associateBy { it.deviceId }
                    .filterKeys { deviceId -> deviceId !in existing }
                val merged = (existing + newOnes)
                    .values
                    .sortedWith(compareByDescending<CollectorDevice> { it.online }.thenBy { it.name })
                _state.value = _state.value.copy(scanStatus = ScanStatus.Finished, devices = merged)
            }
        }
    }

    private fun cancelScan() {
        scanGeneration.incrementAndGet()
        discoveryListener?.stop()
        discoveryListener = null
        scanFuture?.cancel(true)
        scanFuture = null
        discoveryFuture?.cancel(true)
        discoveryFuture = null
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
        discoveryExecutor.shutdownNow()
        super.onCleared()
    }

    companion object {
        private const val SCAN_RETRY_DELAY_MILLIS = 5_000L
    }
}
