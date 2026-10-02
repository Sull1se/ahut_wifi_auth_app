package ahut.wifiauth.android.network

import android.net.Uri
import ahut.wifiauth.android.auth.PortalSsidPolicy
import ahut.wifiauth.android.model.LoginResult
import ahut.wifiauth.android.model.OnlineStatus
import ahut.wifiauth.android.model.WifiInfo
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * ePortal 认证网关请求客户端
 *
 * 负责：
 * 1. 组装抓包固定的认证 GET 参数与动态参数；
 * 2. 强制绑定单连接至 Wi-Fi Network；
 * 3. 在线状态检测 (/drcom/chkstatus)；
 * 4. 用户注销 (/eportal/portal/logout)；
 * 5. 提取并解析 JSONP 响应，判定状态。
 */
class EPortalClient(
    val baseUrl: String = DEFAULT_BASE_URL,
    val statusUrl: String = DEFAULT_STATUS_URL,
    val logoutUrl: String = DEFAULT_LOGOUT_URL
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://10.255.255.154:801/eportal/portal/login"
        const val DEFAULT_STATUS_URL = "http://10.255.255.154/drcom/chkstatus"
        const val DEFAULT_LOGOUT_URL = "http://10.255.255.154:801/eportal/portal/logout"

        const val CALLBACK_LOGIN = "dr1003"
        const val CALLBACK_STATUS = "dr1002"
        const val CALLBACK_LOGOUT = "dr1004"
    }

    /**
     * 构建认证 GET 请求完整 URL
     */
    fun buildAuthUrl(
        username: String,
        password: String,
        wifiIp: String,
        v: Int = Random.nextInt(1000, 10000)
    ): String {
        return try {
            Uri.parse(baseUrl)
                .buildUpon()
                .appendQueryParameter("callback", CALLBACK_LOGIN)
                .appendQueryParameter("login_method", "1")
                .appendQueryParameter("wlan_user_ipv6", "")
                .appendQueryParameter("wlan_user_mac", "000000000000")
                .appendQueryParameter("wlan_ac_ip", "")
                .appendQueryParameter("wlan_ac_name", "")
                .appendQueryParameter("jsVersion", "4.1.3")
                .appendQueryParameter("terminal_type", "1")
                .appendQueryParameter("lang", "zh-cn")
                .appendQueryParameter("lang", "zh")
                .appendQueryParameter("user_account", ",0,$username")
                .appendQueryParameter("user_password", password)
                .appendQueryParameter("wlan_user_ip", wifiIp)
                .appendQueryParameter("v", v.toString())
                .build()
                .toString()
        } catch (_: RuntimeException) {
            buildUrlFallback(username, password, wifiIp, v)
        }
    }

    internal fun buildUrlFallback(
        username: String,
        password: String,
        wifiIp: String,
        v: Int
    ): String {
        val params = listOf(
            "callback" to CALLBACK_LOGIN,
            "login_method" to "1",
            "wlan_user_ipv6" to "",
            "wlan_user_mac" to "000000000000",
            "wlan_ac_ip" to "",
            "wlan_ac_name" to "",
            "jsVersion" to "4.1.3",
            "terminal_type" to "1",
            "lang" to "zh-cn",
            "lang" to "zh",
            "user_account" to ",0,$username",
            "user_password" to password,
            "wlan_user_ip" to wifiIp,
            "v" to v.toString()
        )
        val query = params.joinToString("&") { (key, value) ->
            val encodedVal = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
                .replace("%2C", ",")
            "$key=$encodedVal"
        }
        return "$baseUrl?$query"
    }

    /**
     * 强制通过指定 Wi-Fi Network 向 ePortal 发送登录 GET 请求
     */
    suspend fun login(
        wifiInfo: WifiInfo,
        username: String,
        password: String,
        validateSnapshot: () -> Boolean = { true }
    ): LoginResult = withCancellableIo { attachConnection ->
        if (!PortalSsidPolicy.isWorkSsid(wifiInfo.ssid)) {
            return@withCancellableIo LoginResult.NetworkError("当前 Wi-Fi SSID 未识别或不在校园网白名单中")
        }
        if (!validateSnapshot()) return@withCancellableIo LoginResult.NetworkError("Wi-Fi 连接已变化")
        val urlString = buildAuthUrl(username, password, wifiInfo.ipv4Address)
        val url = URL(urlString)

        var connection: HttpURLConnection? = null
        try {
            connection = wifiInfo.network.openConnection(url) as HttpURLConnection
            attachConnection(connection)
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.useCaches = false

            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            if (!validateSnapshot()) return@withCancellableIo LoginResult.NetworkError("Wi-Fi 连接已变化")
            if (responseBody.isBlank()) {
                LoginResult.NetworkError("服务器未返回任何内容 (HTTP $responseCode)")
            } else {
                when (val parsed = parseJsonpResponse(responseBody)) {
                    is LoginResult.PortalError -> parsed.copy(
                        message = redactCredentials(parsed.message, username, password)
                    )
                    else -> parsed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            LoginResult.NetworkError("连接认证网关超时 (5000ms)，请确认是否已连接校园 Wi-Fi", e)
        } catch (e: UnknownHostException) {
            LoginResult.NetworkError("无法连接网关地址 10.255.255.154", e)
        } catch (e: IOException) {
            LoginResult.NetworkError("网络通讯异常，请检查 Wi-Fi 后重试")
        } catch (e: Exception) {
            LoginResult.NetworkError("认证请求失败，请稍后重试")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 探测校园网当前在线状态（通过网关专有接口 /drcom/chkstatus）
     */
    suspend fun checkOnlineStatus(
        wifiInfo: WifiInfo,
        validateSnapshot: () -> Boolean = { true }
    ): OnlineStatus = withCancellableIo { attachConnection ->
        if (!PortalSsidPolicy.isWorkSsid(wifiInfo.ssid)) {
            return@withCancellableIo OnlineStatus.Error("当前 Wi-Fi SSID 未识别或不在校园网白名单中")
        }
        if (!validateSnapshot()) return@withCancellableIo OnlineStatus.Error("Wi-Fi 连接已变化")
        val urlString = "$statusUrl?callback=$CALLBACK_STATUS"
        val url = URL(urlString)

        var connection: HttpURLConnection? = null
        try {
            connection = wifiInfo.network.openConnection(url) as HttpURLConnection
            attachConnection(connection)
            connection.requestMethod = "GET"
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.instanceFollowRedirects = false
            connection.useCaches = false

            val responseCode = connection.responseCode
            if (responseCode == 301 || responseCode == 302 || responseCode == 307) {
                // 网关未认证时通常重定向至 Portal 认证页面，判定为未认证离线
                return@withCancellableIo OnlineStatus.Offline
            }
            if (responseCode !in 200..299) {
                return@withCancellableIo OnlineStatus.Error("网关响应异常: HTTP $responseCode")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (!validateSnapshot()) return@withCancellableIo OnlineStatus.Error("Wi-Fi 连接已变化")
            parseChkStatusResponse(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            OnlineStatus.Error("探测网关状态超时 (4s)")
        } catch (e: IOException) {
            OnlineStatus.Error("网络探测失败，请检查 Wi-Fi 后重试")
        } catch (e: Exception) {
            OnlineStatus.Error("网关状态查询失败")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 解析 /drcom/chkstatus 返回的 JSONP
     */
    fun parseChkStatusResponse(rawResponse: String): OnlineStatus {
        val trimmed = rawResponse.trim()
        val pattern = Regex("""^[a-zA-Z0-9_]+\s*\((.*)\)\s*;?$""", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(trimmed)
        val jsonStr = if (match != null) {
            match.groupValues[1].trim()
        } else {
            trimmed.removePrefix("$CALLBACK_STATUS(").removeSuffix(");").removeSuffix(")")
        }

        return try {
            val json = JSONObject(jsonStr)
            val result = json.optInt("result", -1)
            if (result == 1) {
                val uid = json.optString("uid", "")
                val ip = json.optString("v4ip", "")
                val time = json.optLong("time", 0L)
                val flow = json.optLong("flow", 0L)
                OnlineStatus.Online(uid = uid, ip = ip, time = time, flow = flow)
            } else if (result == 0) {
                OnlineStatus.Offline
            } else {
                OnlineStatus.Error("未知状态返回码: $result")
            }
        } catch (e: Exception) {
            OnlineStatus.Error("网关状态响应解析失败")
        }
    }

    /**
     * 发送注销请求
     */
    suspend fun logout(
        wifiInfo: WifiInfo,
        validateSnapshot: () -> Boolean = { true }
    ): LoginResult = withCancellableIo { attachConnection ->
        if (!PortalSsidPolicy.isWorkSsid(wifiInfo.ssid)) {
            return@withCancellableIo LoginResult.NetworkError("当前 Wi-Fi SSID 未识别或不在校园网白名单中")
        }
        if (!validateSnapshot()) return@withCancellableIo LoginResult.NetworkError("Wi-Fi 连接已变化")
        val urlString = "$logoutUrl?callback=$CALLBACK_LOGOUT&login_method=1&wlan_user_ip=${wifiInfo.ipv4Address}"
        val url = URL(urlString)

        var connection: HttpURLConnection? = null
        try {
            connection = wifiInfo.network.openConnection(url) as HttpURLConnection
            attachConnection(connection)
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.instanceFollowRedirects = false
            connection.useCaches = false

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (!validateSnapshot()) return@withCancellableIo LoginResult.NetworkError("Wi-Fi 连接已变化")
            val parsed = parseJsonpResponse(body)
            if (parsed is LoginResult.Success) {
                LoginResult.Success("已成功注销校园网登录")
            } else {
                parsed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoginResult.NetworkError("注销请求失败，请检查 Wi-Fi 后重试")
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun <T> withCancellableIo(
        block: (attachConnection: (HttpURLConnection) -> Unit) -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        val workerThread = AtomicReference<Thread?>(null)
        continuation.invokeOnCancellation {
            try {
                activeConnection.getAndSet(null)?.disconnect()
            } catch (_: Exception) {
                // Disconnect is best-effort during cancellation.
            }
            workerThread.get()?.interrupt()
        }

        Dispatchers.IO.dispatch(continuation.context, Runnable {
            workerThread.set(Thread.currentThread())
            try {
                if (!continuation.isActive) return@Runnable
                val result = block { connection ->
                    activeConnection.set(connection)
                    if (!continuation.isActive) {
                        connection.disconnect()
                        throw CancellationException("Portal request cancelled")
                    }
                }
                if (continuation.isActive) continuation.resumeWith(Result.success(result))
            } catch (failure: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
            } finally {
                try {
                    activeConnection.getAndSet(null)?.disconnect()
                } catch (_: Exception) {
                    // Ignore final cleanup failures.
                }
                workerThread.set(null)
                Thread.interrupted()
            }
        })
    }

    /**
     * 解析 ePortal 返回的通用 JSONP 字符串，例如：
     * dr1003({"result":1,"msg":"Portal协议认证成功！"});
     */
    fun parseJsonpResponse(rawResponse: String): LoginResult {
        val trimmed = rawResponse.trim()
        val pattern = Regex("""^[a-zA-Z0-9_]+\s*\((.*)\)\s*;?$""", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(trimmed)
        val jsonStr = if (match != null) {
            match.groupValues[1].trim()
        } else {
            trimmed.removePrefix("$CALLBACK_LOGIN(").removeSuffix(");").removeSuffix(")")
        }

        return try {
            val json = JSONObject(jsonStr)
            val result = json.optInt("result", -1)
            val msg = json.optString("msg", "")
            if (result == 1) {
                LoginResult.Success(if (msg.isNotEmpty()) msg else "Portal协议认证成功！")
            } else {
                LoginResult.PortalError(if (msg.isNotEmpty()) msg else "认证失败 (result=$result)", result)
            }
        } catch (e: Exception) {
            LoginResult.ParseError("", e)
        }
    }

    private fun redactCredentials(message: String, username: String, password: String): String {
        var safe = message
        for (secret in listOf(username, password).filter { it.isNotEmpty() }) {
            safe = safe.replace(secret, "[已隐藏]", ignoreCase = false)
            val encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8.name())
            if (encoded != secret) safe = safe.replace(encoded, "[已隐藏]", ignoreCase = true)
        }
        return safe
    }
}
