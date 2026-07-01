package com.zx.homecamera.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

class WifiSubnetProvider(private val context: Context) {
    fun subnet(): Result<WifiSubnet> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Result.failure(IllegalStateException("无法获取网络状态"))
        val wifiNetwork = connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return Result.failure(IllegalStateException("未连接 Wi-Fi"))
        val linkProperties = connectivityManager.getLinkProperties(wifiNetwork)
            ?: return Result.failure(IllegalStateException("无法获取 Wi-Fi 地址"))
        val address = linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?: return Result.failure(IllegalStateException("无法获取 Wi-Fi IPv4 地址"))
        val localAddress = address.hostAddress
            ?: return Result.failure(IllegalStateException("无法获取 Wi-Fi IPv4 地址"))
        val hosts = generateSubnetHosts(localAddress)
        if (hosts.isEmpty()) return Result.failure(IllegalStateException("Wi-Fi IPv4 地址无效"))

        logNet("wifi subnet local=$localAddress range=${hosts.first()}..${hosts.last()} count=${hosts.size}")
        return Result.success(
            WifiSubnet(
                network = wifiNetwork,
                localAddress = localAddress,
                hosts = hosts,
            ),
        )
    }
}

data class WifiSubnet(
    val network: Network,
    val localAddress: String,
    val hosts: List<String>,
)

fun generateSubnetHosts(localAddress: String): List<String> {
    val parts = localAddress.split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return emptyList()

    val prefix = parts.take(3).joinToString(".")
    return (2..255)
        .map { "$prefix.$it" }
        .filterNot { it == localAddress }
}
