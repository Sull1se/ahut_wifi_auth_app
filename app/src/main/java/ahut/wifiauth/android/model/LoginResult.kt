package ahut.wifiauth.android.model

/**
 * 校园网认证结果封装
 */
sealed class LoginResult {
    /**
     * 认证成功 (result == 1)
     */
    data class Success(val message: String) : LoginResult()

    /**
     * 网关返回认证错误 (result != 1)
     */
    data class PortalError(val message: String, val rawResult: Int? = null) : LoginResult()

    /**
     * 网络连接异常（如超时、无法连接网关、未连接 Wi-Fi 等）
     */
    data class NetworkError(val message: String, val cause: Throwable? = null) : LoginResult()

    /**
     * 响应解析错误（非预期 JSONP 格式）
     */
    data class ParseError(val rawResponse: String, val cause: Throwable? = null) : LoginResult()
}
