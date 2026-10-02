package ahut.wifiauth.android.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ahut.wifiauth.android.model.UserCredentials
import ahut.wifiauth.android.auth.PortalSsidPolicy
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 安全凭据存储管理器。
 *
 * 核心设计：
 * 1. 使用 AndroidKeyStore 托管 AES-256 主密钥；设备是否提供硬件保护取决于系统实现；
 * 2. 对学号与认证密码实施 AES/GCM/NoPadding 认证加密；
 * 3. 凭据明文不写入 SharedPreferences；备份规则排除应用私有 SharedPreferences。
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }
    private val revision = AtomicLong(0L)

    val credentialsRevision: Long get() = revision.get()

    companion object {
        private const val PREFS_NAME = "ahut_secure_credentials"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "ahut_portal_auth_key"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        private const val KEY_USERNAME = "enc_username"
        private const val KEY_PASSWORD = "enc_password"
        private const val KEY_TARGET_SSIDS = "pref_target_ssids"
        const val DEFAULT_TARGET_SSID = "AHUT-FREE"
        val DEFAULT_TARGET_SSIDS = PortalSsidPolicy.DEFAULT_TARGET_SSIDS
    }

    init {
        // 预置默认自动登录目标 SSID，首次安装或全新环境下默认生效
        if (!prefs.contains(KEY_TARGET_SSIDS)) {
            prefs.edit().putStringSet(KEY_TARGET_SSIDS, DEFAULT_TARGET_SSIDS).apply()
        }
    }

    /**
     * 获取或生成 AndroidKeyStore 中的 AES 密钥。
     */
    private fun getOrCreateSecretKey(): SecretKey {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    /**
     * 加密明文字符串，返回 Base64 编码的 (IV + 密文)
     */
    private fun encrypt(plainText: String): String {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // 拼接 IV 与密文
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密 Base64 字符串，还原明文字符串
     */
    private fun decrypt(encryptedBase64: String): String {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        if (combined.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException("密文长度非法")
        }

        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)

        val cipherBytes = ByteArray(combined.size - GCM_IV_LENGTH)
        System.arraycopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.size)

        if (!keyStore.containsAlias(KEY_ALIAS)) throw IllegalStateException("凭据密钥不可用")
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("凭据密钥不可用")
        val secretKey = entry.secretKey
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, Charsets.UTF_8)
    }

    /**
     * 加密保存账号与密码
     */
    fun saveCredentials(username: String, password: String): Boolean {
        val saved = try {
            val encUser = encrypt(username)
            val encPass = encrypt(password)
            prefs.edit()
                .putString(KEY_USERNAME, encUser)
                .putString(KEY_PASSWORD, encPass)
                .commit()
        } catch (_: Exception) {
            false
        }
        if (saved) revision.incrementAndGet()
        return saved
    }

    /**
     * 读取并解密已保存的用户凭据
     */
    fun getCredentials(): UserCredentials? {
        return (readCredentials() as? CredentialReadResult.Available)?.credentials
    }

    fun readCredentials(): CredentialReadResult {
        val hasUsername = prefs.contains(KEY_USERNAME)
        val hasPassword = prefs.contains(KEY_PASSWORD)
        if (!hasUsername && !hasPassword) return CredentialReadResult.Missing
        if (!hasUsername || !hasPassword) return CredentialReadResult.Unreadable
        val encUser = prefs.getString(KEY_USERNAME, null) ?: return CredentialReadResult.Unreadable
        val encPass = prefs.getString(KEY_PASSWORD, null) ?: return CredentialReadResult.Unreadable

        return try {
            val username = decrypt(encUser)
            val password = decrypt(encPass)
            CredentialReadResult.Available(UserCredentials(username, password))
        } catch (_: Exception) {
            CredentialReadResult.Unreadable
        }
    }

    /**
     * 判断当前是否已有保存的有效凭据
     */
    fun hasCredentials(): Boolean {
        return prefs.contains(KEY_USERNAME) && prefs.contains(KEY_PASSWORD)
    }

    /**
     * 清空已保存的凭据
     */
    fun clearCredentials(): Boolean {
        val removed = prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).commit()
        if (!removed) return false
        revision.incrementAndGet()
        return try {
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            !keyStore.containsAlias(KEY_ALIAS)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 是否开启打开 App 自动认证（默认开启）
     */
    fun isAutoLoginEnabled(): Boolean {
        return prefs.getBoolean("pref_auto_login", true)
    }

    /**
     * 设置是否开启自动认证
     */
    fun setAutoLoginEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("pref_auto_login", enabled).apply()
    }

    /**
     * 获取所有已保存的自动登录目标 SSID
     */
    fun getTargetSsids(): Set<String> {
        if (!prefs.contains(KEY_TARGET_SSIDS)) {
            prefs.edit().putStringSet(KEY_TARGET_SSIDS, HashSet(DEFAULT_TARGET_SSIDS)).commit()
            return DEFAULT_TARGET_SSIDS
        }
        val stored = prefs.getStringSet(KEY_TARGET_SSIDS, emptySet()).orEmpty()
        val validTargets = PortalSsidPolicy.sanitizeTargets(stored)
        if (stored != validTargets) {
            prefs.edit().putStringSet(KEY_TARGET_SSIDS, HashSet(validTargets)).commit()
        }
        return validTargets
    }

    /**
     * 添加自动登录目标 SSID
     */
    fun addTargetSsid(ssid: String): Boolean {
        if (ssid !in PortalSsidPolicy.WORK_SSIDS) return false
        val clean = ssid
        val current = getTargetSsids().toMutableSet()
        current.add(clean)
        return prefs.edit().putStringSet(KEY_TARGET_SSIDS, HashSet(current)).commit()
    }

    /**
     * 移除指定的自动登录目标 SSID
     */
    fun removeTargetSsid(ssid: String): Boolean {
        if (ssid !in PortalSsidPolicy.WORK_SSIDS) return false
        val clean = ssid
        val current = getTargetSsids().toMutableSet()
        val removed = current.remove(clean)
        if (removed) {
            prefs.edit().putStringSet(KEY_TARGET_SSIDS, HashSet(current)).commit()
        }
        return removed
    }

    /**
     * 判断指定 SSID 是否在目标自动登录列表中
     */
    fun isTargetSsid(ssid: String?): Boolean {
        return ssid in PortalSsidPolicy.WORK_SSIDS && getTargetSsids().contains(ssid)
    }

    /**
     * 清空所有保存的目标 SSID
     */
    fun clearTargetSsids() {
        prefs.edit().putStringSet(KEY_TARGET_SSIDS, emptySet()).apply()
    }
}

sealed class CredentialReadResult {
    object Missing : CredentialReadResult()
    data class Available(val credentials: UserCredentials) : CredentialReadResult()
    object Unreadable : CredentialReadResult()
}
