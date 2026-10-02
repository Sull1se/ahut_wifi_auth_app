package ahut.wifiauth.android.model

import android.net.Network

/**
 * 封装 Wi-Fi 关联的底层 Network、分配的 IPv4 地址以及网络 SSID
 */
data class WifiInfo(
    val network: Network,
    val ipv4Address: String,
    val ssid: String? = null,
    val connectionGeneration: Long = 0L
) {
    val connectionId: String get() = network.toString()
}
