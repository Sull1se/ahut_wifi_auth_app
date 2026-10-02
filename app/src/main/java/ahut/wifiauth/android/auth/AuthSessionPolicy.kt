package ahut.wifiauth.android.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

data class RequestIdentity(
    val connectionId: String,
    val generation: Long,
    val ssid: String?,
    val ipv4Address: String
)

data class PersistedSessionState(
    val connectionId: String?,
    val userPaused: Boolean,
    val retryCount: Int,
    val retryExhausted: Boolean
)

/** Pure connection-cycle, logout-pause, and bounded retry policy shared by app and tile. */
class AuthSessionPolicy(initial: PersistedSessionState = PersistedSessionState(null, false, 0, false)) {
    private var connectionId: String? = initial.connectionId
    private var userPaused = initial.userPaused
    private var retryCount = initial.retryCount.coerceIn(0, RETRY_DELAYS_MS.size)
    private var retryExhausted = initial.retryExhausted
    private var currentRequest: RequestIdentity? = null

    @Synchronized
    fun observe(identity: RequestIdentity?): Boolean {
        if (identity == null) {
            currentRequest = null
            return false
        }
        val previous = connectionId
        if (previous != null && previous != identity.connectionId) {
            userPaused = false
            retryCount = 0
            retryExhausted = false
        }
        connectionId = identity.connectionId
        currentRequest = identity
        return previous != null && previous != identity.connectionId
    }

    /** Only a confirmed onLost for the active Network ends the cycle. */
    @Synchronized
    fun confirmNetworkLost(lostConnectionId: String): Boolean {
        if (connectionId != lostConnectionId) return false
        connectionId = null
        currentRequest = null
        userPaused = false
        retryCount = 0
        retryExhausted = false
        return true
    }

    @Synchronized
    fun isCurrent(identity: RequestIdentity): Boolean = currentRequest == identity

    @Synchronized
    fun pauseForLogout(): PersistedSessionState {
        if (connectionId != null) userPaused = true
        return persistedState()
    }

    @Synchronized
    fun manualLoginSucceeded(identity: RequestIdentity): Boolean {
        if (currentRequest != identity) return false
        userPaused = false
        retryCount = 0
        retryExhausted = false
        return true
    }

    @Synchronized
    fun canAutoLogin(
        identity: RequestIdentity,
        autoEnabled: Boolean,
        isTarget: Boolean,
        hasCredentials: Boolean,
        gatewayConfirmedOffline: Boolean
    ): Boolean = currentRequest == identity &&
        PortalSsidPolicy.isWorkSsid(identity.ssid) &&
        autoEnabled && isTarget && hasCredentials && gatewayConfirmedOffline &&
        !userPaused && !retryExhausted

    /** Returns the next delay for one of the two additional network-error retries. */
    @Synchronized
    fun recordTemporaryNetworkError(identity: RequestIdentity): Long? {
        if (currentRequest != identity || userPaused || retryExhausted) return null
        if (retryCount >= RETRY_DELAYS_MS.size) {
            retryExhausted = true
            return null
        }
        return RETRY_DELAYS_MS[retryCount++]
    }

    @Synchronized
    fun recordTerminalAutoFailure(identity: RequestIdentity) {
        if (currentRequest == identity) retryExhausted = true
    }

    /** A later confirmed Offline observation after success may start a fresh bounded round. */
    @Synchronized
    fun recordAutoSuccess(identity: RequestIdentity) {
        if (currentRequest == identity && !userPaused) {
            retryCount = 0
            retryExhausted = false
        }
    }

    @Synchronized
    fun isUserPaused(): Boolean = userPaused

    @Synchronized
    fun persistedState(): PersistedSessionState = PersistedSessionState(
        connectionId = connectionId,
        userPaused = userPaused,
        retryCount = retryCount,
        retryExhausted = retryExhausted
    )

    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L)
    }
}

/** Shared login/logout exclusion point, kept separate so its real lock can be tested on the JVM. */
class AuthOperationSerializer {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/** Prevents a late manual-login success from reversing a newer logout intent. */
class AuthIntentGate {
    private val sequence = AtomicLong(0L)

    @Synchronized
    fun beginIntent(): Long = sequence.incrementAndGet()

    @Synchronized
    fun commitIfCurrent(intent: Long, commit: () -> Unit): Boolean {
        if (sequence.get() != intent) return false
        commit()
        return true
    }
}
