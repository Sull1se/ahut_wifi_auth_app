package ahut.wifiauth.android.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import ahut.wifiauth.android.auth.AuthenticationCoordinator
import ahut.wifiauth.android.auth.AuthenticationUiState
import ahut.wifiauth.android.auth.PortalSsidPolicy
import ahut.wifiauth.android.model.LoginResult
import ahut.wifiauth.android.model.OnlineStatus
import ahut.wifiauth.android.storage.CredentialReadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Quick Settings entry point backed by the same process coordinator as MainActivity. */
class AuthTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var coordinator: AuthenticationCoordinator
    private var stateCollection: Job? = null
    private var tileAction: Job? = null
    private var actionOwner: Any? = null

    override fun onCreate() {
        super.onCreate()
        coordinator = AuthenticationCoordinator.get(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        coordinator.startObserving(this)
        if (stateCollection?.isActive != true) {
            stateCollection = serviceScope.launch {
                coordinator.uiState.collect(::renderTileState)
            }
        }
        coordinator.refreshCurrentNetwork()
    }

    override fun onStopListening() {
        stateCollection?.cancel()
        stateCollection = null
        coordinator.stopObserving(this)
        super.onStopListening()
    }

    override fun onDestroy() {
        tileAction?.cancel()
        stateCollection?.cancel()
        actionOwner?.let(::releaseActionOwner)
        if (::coordinator.isInitialized) coordinator.stopObserving(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { performUnlockedClick() }
            return
        }
        performUnlockedClick()
    }

    private fun performUnlockedClick() {
        if (tileAction?.isActive == true) {
            Toast.makeText(this, "认证操作正在进行，请稍候", Toast.LENGTH_SHORT).show()
            return
        }

        val owner = Any()
        actionOwner = owner
        coordinator.startObserving(owner)
        var handedOffToAction = false
        try {
            val snapshot = coordinator.refreshCurrentNetwork()
            if (snapshot == null) {
                Toast.makeText(this, "请先连接校园 Wi-Fi", Toast.LENGTH_SHORT).show()
                return
            }
            if (!PortalSsidPolicy.isWorkSsid(snapshot.ssid)) {
                Toast.makeText(
                    this,
                    "请打开校园网认证 App，授予精确位置权限并将后台位置设为始终允许",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            tileAction = serviceScope.launch {
                try {
                    val status = coordinator.checkStatusForManualAction(snapshot)
                    if (!coordinator.isSnapshotCurrent(snapshot)) {
                        Toast.makeText(this@AuthTileService, "Wi-Fi连接已变化，请重新点击磁贴", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    if (coordinator.uiState.value.operation != null) {
                        Toast.makeText(this@AuthTileService, "认证操作正在进行，请稍候", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    if (status is OnlineStatus.Online) {
                        showLoginResult(coordinator.logout(snapshot).await(), logout = true)
                        return@launch
                    }
                    val credentials = when (val read = coordinator.credentialStore.readCredentials()) {
                        is CredentialReadResult.Available -> read.credentials
                        CredentialReadResult.Unreadable -> {
                            Toast.makeText(
                                this@AuthTileService,
                                "已保存凭据无法解密，请打开 App 删除并重新输入",
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        CredentialReadResult.Missing -> {
                            Toast.makeText(this@AuthTileService, "请先打开 App 保存学号密码", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                    }
                    showLoginResult(coordinator.loginManually(snapshot, credentials).await(), logout = false)
                } finally {
                    releaseActionOwner(owner)
                }
            }
            handedOffToAction = true
        } finally {
            if (!handedOffToAction) releaseActionOwner(owner)
        }
    }

    private fun releaseActionOwner(owner: Any) {
        coordinator.stopObserving(owner)
        if (actionOwner === owner) {
            actionOwner = null
        }
    }

    private fun renderTileState(state: AuthenticationUiState) {
        val tile = qsTile ?: return
        val info = state.wifiInfo
        tile.label = info?.ssid ?: if (info == null) "校园网认证" else "校园 Wi-Fi"
        tile.state = if (state.gatewayStatus is OnlineStatus.Online) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = null
        tile.updateTile()
    }

    private fun showLoginResult(result: LoginResult, logout: Boolean) {
        when (result) {
            is LoginResult.Success -> Toast.makeText(
                this,
                if (logout) "已成功注销校园网登录" else "校园网认证成功！",
                Toast.LENGTH_SHORT
            ).show()
            is LoginResult.PortalError -> Toast.makeText(
                this,
                if (logout) "注销失败" else "认证失败：网关拒绝请求",
                Toast.LENGTH_LONG
            ).show()
            is LoginResult.NetworkError -> Toast.makeText(this, "网络异常，请检查校园 Wi-Fi", Toast.LENGTH_LONG).show()
            is LoginResult.ParseError -> Toast.makeText(this, "网关响应异常", Toast.LENGTH_SHORT).show()
        }
    }
}
