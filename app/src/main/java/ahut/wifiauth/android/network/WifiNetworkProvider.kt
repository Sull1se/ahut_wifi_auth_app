package ahut.wifiauth.android.network

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo as AndroidWifiInfo
import android.net.wifi.WifiManager
import android.location.LocationManager
import android.os.Build
import ahut.wifiauth.android.auth.PortalSsidPolicy
import ahut.wifiauth.android.model.WifiInfo
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 负责扫描与监听 Wi-Fi 网络、获取内网 IPv4 地址及当前连接的 Wi-Fi SSID。
 *
 * 核心设计：
 * 1. 仅要求 TRANSPORT_WIFI，绝不要求 NET_CAPABILITY_VALIDATED，以兼容未通过 Portal 认证的校园 Wi-Fi；
 * 2. 严格排除 TRANSPORT_VPN 且要求 NET_CAPABILITY_NOT_VPN，杜绝手机常驻开启 VPN 时将 tun0 虚拟网卡（如 172.19.0.1）误当物理 Wi-Fi；
 * 3. 提取 LinkProperties 中的 Inet4Address 作为 wlan_user_ip；
 * 4. 仅从对应 NetworkCapabilities.transportInfo 提取 SSID，剥离 Android 包装双引号；
 * 5. 支持即时检测与基于 NetworkCallback 的动态更新。
 */
class WifiNetworkProvider(private val context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val callbackSsidByNetwork = ConcurrentHashMap<Network, String>()
    @Volatile private var activeNetwork: Network? = null
    @Volatile private var connectionGeneration: Long = 0L

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 同步扫描当前所有活跃网络，查找真实物理 Wi-Fi 及其 IPv4 地址与 SSID。
     *
     * 关键防御：
     * 必须排除具备 TRANSPORT_VPN 或缺失 NET_CAPABILITY_NOT_VPN 的虚拟网络。
     * 当手机开启 VPN（如 Clash / Sing-box / V2Ray 等）时，VPN 虚拟接口（tun0）可能会将 Wi-Fi 声明为
     * underlyingNetwork，导致虚拟网卡同时携带 TRANSPORT_WIFI 特性与 172.19.0.1 虚拟 IP。
     * 此处严格过滤，确保 100% 仅拾取真实物理硬件 Wi-Fi 接口。
     */
    fun getCurrentWifiInfo(): WifiInfo? {
        val networks = connectivityManager.allNetworks
        val eligibleNetworks = networks.filter { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@filter false
            isEligibleWifi(capabilities)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && eligibleNetworks.size != 1) return null
        for (network in eligibleNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            wifiInfoFor(network, capabilities)?.let { return it }
        }
        return null
    }

    /**
     * 获取 Wi-Fi SSID 名称并规范化
     */
    fun getWifiSsid(capabilities: NetworkCapabilities? = null): String? {
        if (!isSsidReadingAvailable()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rawSsid = (capabilities?.transportInfo as? AndroidWifiInfo)?.ssid
        return PortalSsidPolicy.normalize(rawSsid)
    }

    fun isSsidReadingAvailable(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager.isLocationEnabled
            else locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            false
        }
    }

    private fun wifiInfoFor(network: Network, capabilities: NetworkCapabilities?): WifiInfo? {
        capabilities ?: return null
        if (!isEligibleWifi(capabilities)) return null
        val ip = getIpv4Address(network) ?: return null
        val ssid = if (!isSsidReadingAvailable()) null else callbackSsidByNetwork[network]
            ?: if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) legacySsidFor(network, ip)
            else getWifiSsid(capabilities)
        val generation = synchronized(this) {
            if (activeNetwork != network) {
                activeNetwork = network
                connectionGeneration += 1
            }
            connectionGeneration
        }
        return WifiInfo(network, ip, ssid, generation)
    }

    private fun isEligibleWifi(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

    /** Android 7–9 have no per-Network SSID API. Accept the global legacy value only when it
     * uniquely and repeatedly matches the sole physical Wi-Fi Network's assigned IPv4 address. */
    @Suppress("DEPRECATION")
    private fun legacySsidFor(network: Network, networkIp: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
        if (!isSsidReadingAvailable()) return null
        val eligible = connectivityManager.allNetworks.filter { candidate ->
            val caps = connectivityManager.getNetworkCapabilities(candidate) ?: return@filter false
            isEligibleWifi(caps)
        }
        if (eligible.size != 1 || eligible.single() != network) return null

        val before = try {
            wifiManager?.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        val rawSsid = before.ssid
        val beforeIp = legacyIpv4(before.ipAddress) ?: return null
        if (beforeIp != networkIp || getIpv4Address(network) != networkIp) return null
        val after = try {
            wifiManager?.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (after.ssid != rawSsid || after.ipAddress != before.ipAddress) return null
        val finalCandidates = connectivityManager.allNetworks.filter { candidate ->
            val caps = connectivityManager.getNetworkCapabilities(candidate) ?: return@filter false
            isEligibleWifi(caps)
        }
        if (finalCandidates.size != 1 || finalCandidates.single() != network) return null
        if (getIpv4Address(network) != networkIp) return null
        val currentCaps = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!isEligibleWifi(currentCaps)) return null
        return PortalSsidPolicy.normalize(rawSsid)
    }

    @Suppress("DEPRECATION")
    private fun legacyIpv4(address: Int): String? {
        if (address == 0) return null
        val bytes = byteArrayOf(
            (address and 0xff).toByte(),
            (address shr 8 and 0xff).toByte(),
            (address shr 16 and 0xff).toByte(),
            (address shr 24 and 0xff).toByte()
        )
        return (InetAddress.getByAddress(bytes) as? Inet4Address)?.hostAddress
    }

    /**
     * 从指定 Network 的 LinkProperties 中提取首个非回环 IPv4 地址
     */
    fun getIpv4Address(network: Network): String? {
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            if (address is Inet4Address && !address.isLoopbackAddress) {
                return address.hostAddress
            }
        }
        return null
    }

    /**
     * 注册网络监听，当 Wi-Fi 连接、断开或 IP/能力变更时回调
     */
    fun registerWifiCallback(
        onWifiChanged: (WifiInfo?) -> Unit,
        onConfirmedNetworkLost: (String) -> Unit = {}
    ) {
        unregisterWifiCallback()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = createCallback(onWifiChanged, onConfirmedNetworkLost)
        this.networkCallback = callback
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            // 异常兜底
        }
    }

    private fun createCallback(
        onWifiChanged: (WifiInfo?) -> Unit,
        onConfirmedNetworkLost: (String) -> Unit
    ): ConnectivityManager.NetworkCallback {
        fun snapshot(network: Network, capabilities: NetworkCapabilities? = null): WifiInfo? =
            wifiInfoFor(network, capabilities ?: connectivityManager.getNetworkCapabilities(network))
        fun lost(network: Network) {
            callbackSsidByNetwork.remove(network)
            val wasActive = synchronized(this) {
                if (activeNetwork == network) {
                    activeNetwork = null
                    connectionGeneration += 1
                    true
                } else false
            }
            if (wasActive) onConfirmedNetworkLost(network.toString())
            onWifiChanged(getCurrentWifiInfo())
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
            ) {
            override fun onAvailable(network: Network) {
                onWifiChanged(snapshot(network))
            }

            override fun onLost(network: Network) {
                lost(network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                onWifiChanged(snapshot(network))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val callbackSsid = getWifiSsid(networkCapabilities)
                if (callbackSsid == null) callbackSsidByNetwork.remove(network)
                else callbackSsidByNetwork[network] = callbackSsid
                onWifiChanged(snapshot(network, networkCapabilities))
            }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { onWifiChanged(snapshot(network)) }
                override fun onLost(network: Network) {
                    lost(network)
                }
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    onWifiChanged(snapshot(network))
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val callbackSsid = getWifiSsid(networkCapabilities)
                    if (callbackSsid == null) callbackSsidByNetwork.remove(network)
                    else callbackSsidByNetwork[network] = callbackSsid
                    onWifiChanged(snapshot(network, networkCapabilities))
                }
            }
        }
    }

    /**
     * 注销网络监听，释放资源
     */
    fun unregisterWifiCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
                // 忽略注销可能抛出的异常
            }
            networkCallback = null
        }
        callbackSsidByNetwork.clear()
    }
}
