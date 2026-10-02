package ahut.wifiauth.android.model

/**
 * 校园网在线状态模型
 */
sealed class OnlineStatus {
    /**
     * 已在线
     * @param uid 在线学号/账号
     * @param ip 在线 IPv4 地址
     * @param time 已在线时长（秒）
     * @param flow 已使用流量（字节）
     */
    data class Online(
        val uid: String,
        val ip: String,
        val time: Long? = null,
        val flow: Long? = null
    ) : OnlineStatus()

    /**
     * 未在线（未登录或已注销）
     */
    data object Offline : OnlineStatus()

    /**
     * 检测失败（网络异常、超时或非校园网网关环境）
     */
    data class Error(val message: String) : OnlineStatus()
}
