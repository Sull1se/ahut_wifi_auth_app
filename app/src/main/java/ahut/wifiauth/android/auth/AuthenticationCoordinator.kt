package ahut.wifiauth.android.auth

import android.content.Context
import android.provider.Settings
import ahut.wifiauth.android.model.LoginResult
import ahut.wifiauth.android.model.OnlineStatus
import ahut.wifiauth.android.model.UserCredentials
import ahut.wifiauth.android.model.WifiInfo
import ahut.wifiauth.android.storage.CredentialReadResult
import ahut.wifiauth.android.network.EPortalClient
import ahut.wifiauth.android.network.WifiNetworkProvider
import ahut.wifiauth.android.storage.CredentialStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthOperation { CHECKING, LOGIN, LOGOUT }

data class AuthenticationUiState(
    val wifiInfo: WifiInfo? = null,
    val gatewayStatus: OnlineStatus? = null,
    val operation: AuthOperation? = null,
    val operationToken: Long? = null,
    val message: String? = null,
    val userPaused: Boolean = false
)

/** Process-wide owner for snapshots, gateway state, automatic attempts, and shared credentials. */
class AuthenticationCoordinator private constructor(context: Context) {
    val wifiProvider = WifiNetworkProvider(context.applicationContext)
    val credentialStore = CredentialStore(context.applicationContext)
    private val ePortalClient = EPortalClient()
    private val appContext = context.applicationContext
    private val sessionPrefs = appContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
    // Network blocking is isolated by EPortalClient; keep lifecycle/state transitions serialized on Main.
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operationSerializer = AuthOperationSerializer()
    private val sequence = AtomicLong(0L)
    private val operationSequence = AtomicLong(0L)
    private val manualIntentGate = AuthIntentGate()
    private val queryLock = Any()
    private val queries = mutableMapOf<RequestIdentity, Deferred<OnlineStatus>>()
    private val requestJobs = ConcurrentHashMap<RequestIdentity, MutableSet<Job>>()
    private val credentialJobs = ConcurrentHashMap.newKeySet<Job>()
    private var autoJob: Job? = null
    private var suppressAutoRestart = false
    @Volatile private var callbackEpoch = 0L
    private val observers = mutableSetOf<Any>()

    private val _uiState = MutableStateFlow(AuthenticationUiState())
    val uiState: StateFlow<AuthenticationUiState> = _uiState.asStateFlow()

    private val sessionPolicy = AuthSessionPolicy(readPersistedState())

    @Synchronized
    fun startObserving(owner: Any) {
        if (observers.add(owner) && observers.size == 1) registerProviderCallback()
        refreshCurrentNetwork()
    }

    @Synchronized
    fun stopObserving(owner: Any) {
        if (!observers.remove(owner)) return
        if (observers.isEmpty()) {
            callbackEpoch += 1
            wifiProvider.unregisterWifiCallback()
            suppressAutoRestart = true
            autoJob?.cancel()
            requestJobs.values.flatten().toList().forEach { it.cancel() }
            synchronized(queryLock) {
                queries.values.toList().forEach { it.cancel() }
                queries.clear()
            }
            sequence.incrementAndGet()
            sessionPolicy.observe(null)
            _uiState.update {
                it.copy(wifiInfo = null, gatewayStatus = null, operation = null, operationToken = null)
            }
        }
    }

    /** Re-register after a permission/location settings return so callbacks regain location data. */
    @Synchronized
    fun refreshLocationAwareCallback(owner: Any) {
        if (owner !in observers) return
        callbackEpoch += 1
        wifiProvider.unregisterWifiCallback()
        sessionPolicy.observe(null)
        sequence.incrementAndGet()
        cancelStaleRequests(null)
        registerProviderCallback()
        refreshCurrentNetwork()
    }

    private fun registerProviderCallback() {
        val epoch = ++callbackEpoch
        wifiProvider.registerWifiCallback(
            onWifiChanged = { _ ->
                processScope.launch(Dispatchers.Main) {
                    if (isCallbackActive(epoch)) acceptSnapshot(wifiProvider.getCurrentWifiInfo())
                }
            },
            onConfirmedNetworkLost = { connectionId ->
                processScope.launch(Dispatchers.Main) {
                    if (isCallbackActive(epoch)) acceptConfirmedLoss(connectionId)
                }
            }
        )
    }

    @Synchronized
    private fun isCallbackActive(epoch: Long): Boolean = callbackEpoch == epoch && observers.isNotEmpty()

    fun refreshCurrentNetwork(): WifiInfo? {
        if (synchronized(this) { observers.isEmpty() }) return null
        val snapshot = wifiProvider.getCurrentWifiInfo()
        acceptSnapshot(snapshot)
        return snapshot
    }

    fun isSnapshotCurrent(snapshot: WifiInfo): Boolean = isCurrent(snapshot)

    fun refreshStatus(snapshot: WifiInfo, allowAutomaticLogin: Boolean = true): Deferred<OnlineStatus> {
        if (synchronized(this) { observers.isEmpty() }) {
            val status = OnlineStatus.Error("认证入口当前未监听网络")
            return CompletableDeferred<OnlineStatus>().also { it.complete(status) }
        }
        val identity = snapshot.identity()
        if (!PortalSsidPolicy.isWorkSsid(snapshot.ssid)) {
            val status = OnlineStatus.Error("当前 Wi-Fi SSID 未识别或不在校园网白名单中")
            _uiState.update {
                if (it.wifiInfo?.identity() == identity && sessionPolicy.isCurrent(identity)) {
                    it.copy(gatewayStatus = status, operation = null, operationToken = null)
                } else it
            }
            return CompletableDeferred<OnlineStatus>().also { it.complete(status) }
        }

        synchronized(queryLock) {
            queries[identity]?.takeIf { it.isActive }?.let { return it }
            val querySequence = sequence.incrementAndGet()
            val deferred = processScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                if (!isCurrent(snapshot)) return@async OnlineStatus.Error("Wi-Fi 连接已变化")
                _uiState.update { state ->
                    if (state.operation == null && state.wifiInfo?.identity() == identity &&
                        sequence.get() == querySequence && sessionPolicy.isCurrent(identity)
                    ) state.copy(
                        operation = AuthOperation.CHECKING,
                        operationToken = querySequence
                    )
                    else state
                }
                try {
                    val result = ePortalClient.checkOnlineStatus(snapshot) { isCurrent(snapshot) }
                    if (isCurrent(snapshot) && sequence.get() == querySequence) {
                        _uiState.update {
                            if (sequence.get() != querySequence || it.wifiInfo?.identity() != identity ||
                                !sessionPolicy.isCurrent(identity)
                            ) it else it.copy(
                                wifiInfo = snapshot,
                                gatewayStatus = result,
                                operation = if (it.operationToken == querySequence) null else it.operation,
                                operationToken = if (it.operationToken == querySequence) null else it.operationToken,
                                userPaused = sessionPolicy.isUserPaused(),
                                message = null
                            )
                        }
                        if (allowAutomaticLogin && result is OnlineStatus.Offline) {
                            maybeStartAutomaticLogin(snapshot, querySequence)
                        }
                    }
                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    _uiState.update {
                        if (sequence.get() == querySequence && it.operationToken == querySequence &&
                            it.wifiInfo?.identity() == identity
                        ) {
                            it.copy(operation = null, operationToken = null)
                        } else it
                    }
                }
            }
            queries[identity] = deferred
            trackRequest(identity, deferred)
            deferred.invokeOnCompletion {
                synchronized(queryLock) {
                    if (queries[identity] === deferred) queries.remove(identity)
                }
                untrackRequest(identity, deferred)
            }
            deferred.start()
            return deferred
        }
    }

    /** A manual tile action needs a fresh gateway decision but must not race an automatic login. */
    suspend fun checkStatusForManualAction(snapshot: WifiInfo): OnlineStatus {
        val previousAuto = synchronized(this) { autoJob }
        cancelAutomaticJob()
        previousAuto?.cancelAndJoin()
        invalidateGatewayQueries()
        return refreshStatus(snapshot, allowAutomaticLogin = false).await()
    }

    /** Cancel work that could use replaced credentials and invalidate older state queries. */
    fun onCredentialsChanged() {
        manualIntentGate.beginIntent()
        cancelAutomaticJob()
        credentialJobs.toList().forEach { it.cancel() }
        invalidateGatewayQueries()
    }

    /** Settings which gate automatic authentication changed. Re-query current status if possible. */
    fun onAutomaticConfigurationChanged() {
        cancelAutomaticJob()
        val snapshot = _uiState.value.wifiInfo ?: return
        if (PortalSsidPolicy.isWorkSsid(snapshot.ssid)) refreshStatus(snapshot)
    }

    fun loginManually(snapshot: WifiInfo, credentials: UserCredentials): Deferred<LoginResult> {
        if (!isCurrent(snapshot)) return completedLoginError("Wi-Fi 连接已变化，请刷新后重试")
        val intentSequence = manualIntentGate.beginIntent()
        credentialJobs.toList().forEach { it.cancel() }
        cancelAutomaticJob()
        invalidateGatewayQueries()
        val identity = snapshot.identity()
        val deferred = processScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            operationSerializer.withLock {
                runAuthenticationOperation(identity, snapshot, AuthOperation.LOGIN) {
                    ePortalClient.login(snapshot, credentials.username, credentials.password) { isCurrent(snapshot) }
                }.also { result ->
                    if (result is LoginResult.Success) {
                        manualIntentGate.commitIfCurrent(intentSequence) {
                            if (isCurrent(snapshot) && sessionPolicy.manualLoginSucceeded(identity)) {
                                persistSessionState()
                                sequence.incrementAndGet()
                                _uiState.update {
                                    if (it.wifiInfo?.identity() == identity && sessionPolicy.isCurrent(identity)) it.copy(
                                        gatewayStatus = OnlineStatus.Online(credentials.username, snapshot.ipv4Address),
                                        userPaused = false,
                                        message = result.message
                                    ) else it
                                }
                            }
                        }
                    }
                }
            }
        }
        credentialJobs.add(deferred)
        trackRequest(identity, deferred)
        deferred.invokeOnCompletion {
            credentialJobs.remove(deferred)
            untrackRequest(identity, deferred)
        }
        deferred.start()
        return deferred
    }

    /** Persist the pause before cancelling any automatic work or waiting for the shared lock. */
    fun logout(snapshot: WifiInfo): Deferred<LoginResult> {
        val identity = snapshot.identity()
        if (!isCurrent(snapshot)) return completedLoginError("Wi-Fi 连接已变化，请刷新后重试")
        manualIntentGate.beginIntent()
        credentialJobs.toList().forEach { it.cancel() }
        sessionPolicy.pauseForLogout()
        persistSessionState()
        _uiState.update {
            if (it.wifiInfo?.identity() == identity && sessionPolicy.isCurrent(identity)) {
                it.copy(userPaused = true, message = "本次 Wi-Fi 连接已暂停自动认证")
            } else it
        }
        cancelAutomaticJob()
        invalidateGatewayQueries()
        val deferred = processScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            operationSerializer.withLock {
                runAuthenticationOperation(identity, snapshot, AuthOperation.LOGOUT) {
                    ePortalClient.logout(snapshot) { isCurrent(snapshot) }
                }
            }
        }
        trackRequest(identity, deferred)
        deferred.invokeOnCompletion { untrackRequest(identity, deferred) }
        deferred.start()
        return deferred
    }

    private suspend fun runAuthenticationOperation(
        identity: RequestIdentity,
        snapshot: WifiInfo,
        operation: AuthOperation,
        action: suspend () -> LoginResult
    ): LoginResult {
        if (!isCurrent(snapshot)) return LoginResult.NetworkError("Wi-Fi 连接已变化，请刷新后重试")
        sequence.incrementAndGet()
        val operationToken = operationSequence.incrementAndGet()
        _uiState.update {
            if (it.wifiInfo?.identity() == identity && sessionPolicy.isCurrent(identity)) {
                it.copy(
                    wifiInfo = snapshot,
                    gatewayStatus = null,
                    operation = operation,
                    operationToken = operationToken,
                    message = null
                )
            } else it
        }
        return try {
            val result = action()
            _uiState.update {
                if (it.wifiInfo?.identity() == identity && it.operationToken == operationToken &&
                    isCurrent(snapshot) && sessionPolicy.isCurrent(identity)
                ) it.copy(
                        operation = null,
                        operationToken = null,
                        message = when (result) {
                            is LoginResult.Success -> result.message
                            is LoginResult.PortalError -> result.message
                            is LoginResult.NetworkError -> result.message
                            is LoginResult.ParseError -> "网关响应解析失败"
                        }
                    ) else it
            }
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            _uiState.update {
                if (it.operationToken == operationToken) it.copy(operation = null, operationToken = null) else it
            }
        }
    }

    private suspend fun maybeStartAutomaticLogin(snapshot: WifiInfo, querySequence: Long) {
        if (!isCurrent(snapshot) || sequence.get() != querySequence) return
        val revision = credentialStore.credentialsRevision
        val credentials = (credentialStore.readCredentials() as? CredentialReadResult.Available)?.credentials ?: return
        if (credentialStore.credentialsRevision != revision) return
        val identity = snapshot.identity()
        if (!sessionPolicy.canAutoLogin(
                identity,
                autoEnabled = credentialStore.isAutoLoginEnabled(),
                isTarget = credentialStore.isTargetSsid(snapshot.ssid),
                hasCredentials = true,
                gatewayConfirmedOffline = true
            )
        ) return

        synchronized(this) {
            if (autoJob?.isActive == true) return
            val job = processScope.launch {
                runAutomaticLogin(snapshot, identity, credentials, revision)
            }
            autoJob = job
            trackRequest(identity, job)
            job.invokeOnCompletion {
                untrackRequest(identity, job)
                val shouldRestart = synchronized(this) {
                    if (autoJob === job) {
                        autoJob = null
                        val suppress = suppressAutoRestart
                        suppressAutoRestart = false
                        !suppress
                    } else false
                }
                val current = _uiState.value
                if (shouldRestart && current.gatewayStatus is OnlineStatus.Offline && current.wifiInfo != null) {
                    processScope.launch { maybeStartAutomaticLogin(current.wifiInfo, sequence.get()) }
                }
            }
        }
    }

    private suspend fun runAutomaticLogin(
        snapshot: WifiInfo,
        identity: RequestIdentity,
        credentials: UserCredentials,
        credentialsRevision: Long
    ) {
        operationSerializer.withLock {
            var firstAttempt = true
            var activeOperationToken: Long? = null
            try {
                while (isCurrent(snapshot) && !sessionPolicy.isUserPaused()) {
                    if (!firstAttempt) {
                        val status = refreshStatus(snapshot).await()
                        if (!isCurrent(snapshot) || status !is OnlineStatus.Offline) return@withLock
                    }
                    firstAttempt = false
                    if (!isCurrent(snapshot) || sessionPolicy.isUserPaused()) return@withLock
                    if (!automaticAttemptStillValid(snapshot, identity, credentials, credentialsRevision)) {
                        return@withLock
                    }
                    val attemptToken = operationSequence.incrementAndGet()
                    activeOperationToken = attemptToken
                    _uiState.update {
                        if (it.wifiInfo?.identity() == identity && sessionPolicy.isCurrent(identity)) {
                            it.copy(
                                operation = AuthOperation.LOGIN,
                                operationToken = attemptToken,
                                message = "正在自动认证…"
                            )
                        } else it
                    }
                    val result = ePortalClient.login(snapshot, credentials.username, credentials.password) {
                        automaticAttemptStillValid(snapshot, identity, credentials, credentialsRevision)
                    }
                    if (!isCurrent(snapshot)) return@withLock
                    when (result) {
                        is LoginResult.Success -> {
                            sessionPolicy.recordAutoSuccess(identity)
                            persistSessionState()
                            sequence.incrementAndGet()
                            _uiState.update {
                                if (it.wifiInfo?.identity() == identity &&
                                    it.operationToken == attemptToken && isCurrent(snapshot) &&
                                    sessionPolicy.isCurrent(identity)
                                ) it.copy(
                                    gatewayStatus = OnlineStatus.Online(credentials.username, snapshot.ipv4Address),
                                    operation = null,
                                    operationToken = null,
                                    message = result.message,
                                    userPaused = false
                                ) else it
                            }
                            activeOperationToken = null
                            return@withLock
                        }
                        is LoginResult.NetworkError -> {
                            val waitMs = sessionPolicy.recordTemporaryNetworkError(identity)
                            persistSessionState()
                            if (waitMs == null) {
                                _uiState.update {
                                    if (it.wifiInfo?.identity() == identity && it.operationToken == attemptToken) {
                                        it.copy(operation = null, operationToken = null, message = result.message)
                                    } else it
                                }
                                activeOperationToken = null
                                return@withLock
                            }
                            _uiState.update {
                                if (it.wifiInfo?.identity() == identity && it.operationToken == attemptToken) {
                                    it.copy(operation = null, operationToken = null, message = "网络暂时异常，将在稍后重试")
                                } else it
                            }
                            activeOperationToken = null
                            delay(waitMs)
                        }
                        is LoginResult.PortalError, is LoginResult.ParseError -> {
                            sessionPolicy.recordTerminalAutoFailure(identity)
                            persistSessionState()
                            _uiState.update {
                                if (it.wifiInfo?.identity() == identity && it.operationToken == attemptToken) it.copy(
                                    operation = null,
                                    operationToken = null,
                                    message = when (result) {
                                    is LoginResult.PortalError -> result.message
                                    else -> "网关响应解析失败"
                                    }
                                ) else it
                            }
                            activeOperationToken = null
                            return@withLock
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                activeOperationToken?.let { token ->
                    _uiState.update {
                        if (it.operationToken == token) it.copy(operation = null, operationToken = null) else it
                    }
                }
            }
        }
    }

    private fun acceptSnapshot(snapshot: WifiInfo?) {
        if (synchronized(this) { observers.isEmpty() }) return
        val identity = snapshot?.identity()
        val previousPersisted = sessionPolicy.persistedState()
        val changedCycle = sessionPolicy.observe(identity)
        if (sessionPolicy.persistedState() != previousPersisted) persistSessionState()
        if (snapshot == null) {
            cancelStaleRequests(null)
            _uiState.update {
                it.copy(
                    wifiInfo = null,
                    gatewayStatus = null,
                    operation = null,
                    operationToken = null,
                    userPaused = sessionPolicy.isUserPaused()
                )
            }
            return
        }
        cancelStaleRequests(identity)
        _uiState.update {
            val identityChanged = it.wifiInfo?.identity() != identity
            it.copy(
                wifiInfo = snapshot,
                gatewayStatus = if (changedCycle || identityChanged) null else it.gatewayStatus,
                operation = if (identityChanged) null else it.operation,
                operationToken = if (identityChanged) null else it.operationToken,
                message = if (identityChanged) null else it.message,
                userPaused = sessionPolicy.isUserPaused()
            )
        }
        if (PortalSsidPolicy.isWorkSsid(snapshot.ssid)) refreshStatus(snapshot)
        else _uiState.update {
            if (it.wifiInfo?.identity() == identity) it.copy(
                gatewayStatus = OnlineStatus.Error("当前 Wi-Fi SSID 未识别或不在校园网白名单中"),
                operation = null,
                operationToken = null
            ) else it
        }
    }

    private fun acceptConfirmedLoss(connectionId: String) {
        val currentNetwork = wifiProvider.getCurrentWifiInfo()
        if (currentNetwork != null && currentNetwork.connectionId != connectionId) {
            acceptSnapshot(currentNetwork)
            return
        }
        if (!sessionPolicy.confirmNetworkLost(connectionId)) return
        persistSessionState()
        cancelStaleRequests(null)
        _uiState.update {
            if (it.wifiInfo?.connectionId == connectionId) it.copy(
                gatewayStatus = null,
                operation = null,
                operationToken = null,
                userPaused = sessionPolicy.isUserPaused()
            ) else it
        }
    }

    private fun isCurrent(snapshot: WifiInfo): Boolean {
        val expected = snapshot.identity()
        if (!sessionPolicy.isCurrent(expected)) return false
        val current = wifiProvider.getCurrentWifiInfo() ?: return false
        return current.identity() == expected
    }

    private fun automaticAttemptStillValid(
        snapshot: WifiInfo,
        identity: RequestIdentity,
        expectedCredentials: ahut.wifiauth.android.model.UserCredentials,
        expectedRevision: Long
    ): Boolean {
        if (!isCurrent(snapshot) || credentialStore.credentialsRevision != expectedRevision) return false
        val credentials = (credentialStore.readCredentials() as? CredentialReadResult.Available)?.credentials
            ?: return false
        val gatewayOffline = _uiState.value.gatewayStatus is OnlineStatus.Offline
        return credentials == expectedCredentials && sessionPolicy.canAutoLogin(
            identity,
            autoEnabled = credentialStore.isAutoLoginEnabled(),
            isTarget = credentialStore.isTargetSsid(snapshot.ssid),
            hasCredentials = true,
            gatewayConfirmedOffline = gatewayOffline
        )
    }

    private fun cancelAutomaticJob() {
        synchronized(this) {
            if (autoJob?.isActive == true) {
                suppressAutoRestart = true
                autoJob?.cancel()
            }
        }
    }

    private fun cancelStaleRequests(current: RequestIdentity?) {
        requestJobs.forEach { (identity, jobs) ->
            if (identity != current) jobs.toList().forEach { it.cancel() }
        }
        if (current == null) autoJob?.cancel()
    }

    private fun invalidateGatewayQueries() {
        sequence.incrementAndGet()
        synchronized(queryLock) {
            queries.values.toList().forEach { it.cancel() }
            queries.clear()
        }
    }

    private fun trackRequest(identity: RequestIdentity, job: Job) {
        requestJobs.computeIfAbsent(identity) { ConcurrentHashMap.newKeySet() }.add(job)
    }

    private fun untrackRequest(identity: RequestIdentity, job: Job) {
        requestJobs[identity]?.let { jobs ->
            jobs.remove(job)
            if (jobs.isEmpty()) requestJobs.remove(identity, jobs)
        }
    }

    private fun completedLoginError(message: String): Deferred<LoginResult> =
        CompletableDeferred<LoginResult>().also { it.complete(LoginResult.NetworkError(message)) }

    private fun readPersistedState(): PersistedSessionState {
        val currentBootCount = try {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (_: Exception) {
            -1
        }
        val savedBootCount = sessionPrefs.getInt(KEY_BOOT_COUNT, -1)
        if (currentBootCount >= 0 && savedBootCount >= 0 && currentBootCount != savedBootCount) {
            return PersistedSessionState(null, false, 0, false)
        }
        return PersistedSessionState(
            connectionId = sessionPrefs.getString(KEY_CONNECTION_ID, null),
            userPaused = sessionPrefs.getBoolean(KEY_USER_PAUSED, false),
            retryCount = sessionPrefs.getInt(KEY_RETRY_COUNT, 0),
            retryExhausted = sessionPrefs.getBoolean(KEY_RETRY_EXHAUSTED, false)
        )
    }

    private fun persistSessionState() {
        val state = sessionPolicy.persistedState()
        sessionPrefs.edit()
            .putString(KEY_CONNECTION_ID, state.connectionId)
            .putBoolean(KEY_USER_PAUSED, state.userPaused)
            .putInt(KEY_RETRY_COUNT, state.retryCount)
            .putBoolean(KEY_RETRY_EXHAUSTED, state.retryExhausted)
            .putInt(KEY_BOOT_COUNT, currentBootCount())
            .commit()
    }

    private fun currentBootCount(): Int = try {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Exception) {
        -1
    }

    private fun WifiInfo.identity() = RequestIdentity(
        connectionId = connectionId,
        generation = connectionGeneration,
        ssid = ssid,
        ipv4Address = ipv4Address
    )

    companion object {
        private const val SESSION_PREFS = "auth_session_state"
        private const val KEY_CONNECTION_ID = "connection_id"
        private const val KEY_USER_PAUSED = "user_paused"
        private const val KEY_RETRY_COUNT = "retry_count"
        private const val KEY_RETRY_EXHAUSTED = "retry_exhausted"
        private const val KEY_BOOT_COUNT = "boot_count"

        @Volatile private var instance: AuthenticationCoordinator? = null

        fun get(context: Context): AuthenticationCoordinator =
            instance ?: synchronized(this) {
                instance ?: AuthenticationCoordinator(context.applicationContext).also { instance = it }
            }
    }
}
