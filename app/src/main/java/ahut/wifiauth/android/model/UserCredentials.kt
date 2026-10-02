package ahut.wifiauth.android.model

/**
 * 封装用户保存的学号/工号及认证密码
 */
data class UserCredentials(
    val username: String,
    val password: String
)
