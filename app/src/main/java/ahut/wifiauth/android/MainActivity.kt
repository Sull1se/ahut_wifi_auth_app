package ahut.wifiauth.android

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.ComponentActivity
import ahut.wifiauth.android.service.AuthTileService
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import ahut.wifiauth.android.databinding.ActivityMainBinding
import ahut.wifiauth.android.auth.AuthenticationCoordinator
import ahut.wifiauth.android.auth.AuthOperation
import ahut.wifiauth.android.model.LoginResult
import ahut.wifiauth.android.model.OnlineStatus
import ahut.wifiauth.android.model.WifiInfo
import ahut.wifiauth.android.auth.PortalSsidPolicy
import ahut.wifiauth.android.network.WifiNetworkProvider
import ahut.wifiauth.android.storage.CredentialStore
import ahut.wifiauth.android.storage.CredentialReadResult
import ahut.wifiauth.android.ui.WindowUi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private companion object {
        const val PREFS_UI = "ui_state"
        const val KEY_FINE_LOCATION_REQUESTED = "fine_location_requested"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiProvider: WifiNetworkProvider
    private lateinit var credentialStore: CredentialStore
    private lateinit var authenticationCoordinator: AuthenticationCoordinator
    private var syncingAutoLoginControls = false

    @Volatile
    private var currentWifiInfo: WifiInfo? = null

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        authenticationCoordinator.refreshLocationAwareCallback(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowUi.enableEdgeToEdge(window, binding.rootScrollView)

        authenticationCoordinator = AuthenticationCoordinator.get(this)
        wifiProvider = authenticationCoordinator.wifiProvider
        credentialStore = authenticationCoordinator.credentialStore
        authenticationCoordinator.startObserving(this)

        checkLocationPermission()
        updateUiMode()
        setupListeners()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authenticationCoordinator.uiState.collect { state ->
                    currentWifiInfo = state.wifiInfo
                    updateWifiUi(state.wifiInfo)
                    renderGatewayStatus(state)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        authenticationCoordinator.refreshLocationAwareCallback(this)
    }

    override fun onDestroy() {
        authenticationCoordinator.stopObserving(this)
        super.onDestroy()
    }

    private fun checkLocationPermission() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val alreadyRequested = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .getBoolean(KEY_FINE_LOCATION_REQUESTED, false)
        if (!fineGranted && !alreadyRequested) {
            getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit()
                .putBoolean(KEY_FINE_LOCATION_REQUESTED, true).apply()
            requestLocationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun updateUiMode() {
        val credentialRead = credentialStore.readCredentials()
        val credentials = (credentialRead as? CredentialReadResult.Available)?.credentials
        val isAutoLogin = credentialStore.isAutoLoginEnabled()
        binding.cbAutoLogin.isChecked = isAutoLogin
        binding.cbAutoLoginInput.isChecked = isAutoLogin

        if (credentials != null) {
            binding.layoutOneClick.visibility = View.VISIBLE
            binding.layoutInput.visibility = View.GONE
            binding.tvSavedAccount.text = "已绑定账号：${credentials.username}"
            binding.btnCancelEdit.visibility = View.GONE
        } else {
            binding.layoutOneClick.visibility = View.GONE
            binding.layoutInput.visibility = View.VISIBLE
            binding.btnCancelEdit.visibility = View.GONE
        }
        binding.btnDeleteCredentials.visibility = when (credentialRead) {
            is CredentialReadResult.Available, CredentialReadResult.Unreadable -> View.VISIBLE
            CredentialReadResult.Missing -> View.GONE
        }
        if (credentialRead is CredentialReadResult.Unreadable) {
            updateStatus("状态：已保存凭据无法解密，请删除后重新输入", Color.parseColor("#D32F2F"))
        }

        updateTargetSsidUi(currentWifiInfo)
    }

    private fun setupListeners() {
        binding.btnRefreshIp.setOnClickListener {
            refreshWifiInfo()
        }

        binding.btnLocationSettings.setOnClickListener {
            val fineGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            val intent = if (fineGranted && backgroundGranted) {
                android.content.Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            } else {
                android.content.Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            }
            startActivity(intent)
        }

        binding.btnSetTargetSsid.setOnClickListener {
            val ssid = currentWifiInfo?.ssid
            if (PortalSsidPolicy.isWorkSsid(ssid) && ssid != null) {
                credentialStore.addTargetSsid(ssid)
                authenticationCoordinator.onAutomaticConfigurationChanged()
                updateTargetSsidUi(currentWifiInfo)
                Toast.makeText(this, "已将「$ssid」保存为自动登录目标", Toast.LENGTH_SHORT).show()
                refreshWifiInfo()
            }
        }

        binding.btnRemoveTargetSsid.setOnClickListener {
            val ssid = currentWifiInfo?.ssid
            if (PortalSsidPolicy.isWorkSsid(ssid) && ssid != null) {
                credentialStore.removeTargetSsid(ssid)
                authenticationCoordinator.onAutomaticConfigurationChanged()
                updateTargetSsidUi(currentWifiInfo)
                Toast.makeText(this, "已移除目标「$ssid」", Toast.LENGTH_SHORT).show()
            }
        }

        binding.cbAutoLogin.setOnCheckedChangeListener { _, isChecked ->
            if (syncingAutoLoginControls) return@setOnCheckedChangeListener
            credentialStore.setAutoLoginEnabled(isChecked)
            authenticationCoordinator.onAutomaticConfigurationChanged()
            syncingAutoLoginControls = true
            binding.cbAutoLoginInput.isChecked = isChecked
            syncingAutoLoginControls = false
        }

        binding.cbAutoLoginInput.setOnCheckedChangeListener { _, isChecked ->
            if (syncingAutoLoginControls) return@setOnCheckedChangeListener
            credentialStore.setAutoLoginEnabled(isChecked)
            authenticationCoordinator.onAutomaticConfigurationChanged()
            syncingAutoLoginControls = true
            binding.cbAutoLogin.isChecked = isChecked
            syncingAutoLoginControls = false
        }

        // 一键登录（使用已保存凭据）
        binding.btnQuickLogin.setOnClickListener {
            val read = credentialStore.readCredentials()
            val credentials = (read as? CredentialReadResult.Available)?.credentials
            if (credentials == null) {
                updateUiMode()
                if (read is CredentialReadResult.Unreadable) {
                    Toast.makeText(this, "已保存凭据无法解密，请删除后重新输入", Toast.LENGTH_LONG).show()
                }
                return@setOnClickListener
            }
            prepareAndLogin(credentials.username, credentials.password)
        }

        // 注销退出
        binding.btnLogout.setOnClickListener {
            val wifiInfo = currentWifiInfo ?: authenticationCoordinator.refreshCurrentNetwork()
            if (wifiInfo == null) {
                Toast.makeText(this, "未连接 Wi-Fi，无法注销", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performLogout(wifiInfo)
        }

        // 切换至修改凭据模式
        binding.btnSwitchToEdit.setOnClickListener {
            val credentials = credentialStore.getCredentials()
            binding.layoutOneClick.visibility = View.GONE
            binding.layoutInput.visibility = View.VISIBLE
            binding.btnCancelEdit.visibility = View.VISIBLE
            if (credentials != null) {
                binding.etUsername.setText(credentials.username)
                binding.etPassword.setText(credentials.password)
            }
        }

        // 取消修改，返回一键登录模式
        binding.btnCancelEdit.setOnClickListener {
            updateUiMode()
        }

        // 保存新凭据并登录
        binding.btnSaveAndLogin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString()

            if (username.isEmpty() || password.isEmpty()) {
                updateStatus("状态：学号与密码不能为空", Color.parseColor("#D32F2F"))
                return@setOnClickListener
            }

            if (!credentialStore.saveCredentials(username, password)) {
                updateStatus("状态：凭据保存失败，未发起登录", Color.parseColor("#D32F2F"))
                Toast.makeText(this, "凭据保存失败，请检查设备安全存储后重试", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            authenticationCoordinator.onCredentialsChanged()
            prepareAndLogin(username, password)
        }

        binding.btnDeleteCredentials.setOnClickListener {
            authenticationCoordinator.onCredentialsChanged()
            val keyRemoved = credentialStore.clearCredentials()
            if (credentialStore.readCredentials() is CredentialReadResult.Missing) {
                updateUiMode()
                binding.etUsername.text?.clear()
                binding.etPassword.text?.clear()
                updateStatus(
                    if (keyRemoved) "状态：已删除凭据与本地密钥" else "状态：凭据已删除，但本地密钥清理失败",
                    if (keyRemoved) Color.parseColor("#555555") else Color.parseColor("#D32F2F")
                )
            } else {
                Toast.makeText(this, "删除凭据失败，请稍后重试", Toast.LENGTH_LONG).show()
            }
        }

        binding.etUsername.setOnFocusChangeListener { view, focused ->
            if (focused) WindowUi.scrollIntoView(binding.rootScrollView, view)
        }
        binding.etPassword.setOnFocusChangeListener { view, focused ->
            if (focused) WindowUi.scrollIntoView(binding.rootScrollView, view)
        }
        binding.etPassword.setOnEditorActionListener { view, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (done) {
                if (!binding.btnSaveAndLogin.isEnabled) return@setOnEditorActionListener true
                WindowUi.hideKeyboard(view)
                binding.btnSaveAndLogin.performClick()
            }
            done
        }
    }

    private fun prepareAndLogin(username: String, password: String) {
        val wifiInfo = currentWifiInfo ?: authenticationCoordinator.refreshCurrentNetwork()
        if (wifiInfo == null) {
            updateWifiUi(null)
            updateStatus("状态：未检测到有效 Wi-Fi 连接，请先连接校园 Wi-Fi", Color.parseColor("#D32F2F"))
            return
        }

        performLogin(username, password, wifiInfo)
    }

    private fun refreshWifiInfo() {
        authenticationCoordinator.refreshCurrentNetwork()
    }

    private fun updateWifiUi(info: WifiInfo?) {
        currentWifiInfo = info
        updateTargetSsidUi(info)
        if (info != null) {
            val ssidDisplay = info.ssid ?: "校园 Wi-Fi (已连接)"
            binding.tvWifiSsid.text = "Wi-Fi：$ssidDisplay"
            binding.tvWifiSsid.setTextColor(Color.parseColor("#1B5E20"))

            binding.tvWifiIp.text = "内网 IP：${info.ipv4Address}"
            binding.tvWifiIp.setTextColor(Color.parseColor("#333333"))

        } else {
            binding.tvWifiSsid.text = "Wi-Fi：未连接"
            binding.tvWifiSsid.setTextColor(Color.parseColor("#D32F2F"))

            binding.tvWifiIp.text = "内网 IP：未获取 (请先连接校园 Wi-Fi)"
            binding.tvWifiIp.setTextColor(Color.parseColor("#999999"))

            binding.tvAuthStatus.text = "网关状态：未连 Wi-Fi"
            binding.tvAuthStatus.setTextColor(Color.parseColor("#999999"))
            binding.btnQuickLogin.text = "一 键 登 录"
        }
    }

    private fun updateTargetSsidUi(info: WifiInfo?) {
        val targetSsids = credentialStore.getTargetSsids()
        val currentSsid = info?.ssid
        binding.btnLocationSettings.visibility = View.GONE

        if (currentSsid != null) {
            val isWorkSsid = PortalSsidPolicy.isWorkSsid(currentSsid)
            val isTarget = isWorkSsid && credentialStore.isTargetSsid(currentSsid)
            if (isTarget) {
                binding.tvTargetSsidInfo.text = "自动登录目标：$currentSsid (已设为目标)"
                binding.tvTargetSsidInfo.setTextColor(Color.parseColor("#1B5E20"))
                binding.btnSetTargetSsid.visibility = View.GONE
                binding.btnRemoveTargetSsid.visibility = View.VISIBLE
            } else if (isWorkSsid) {
                val targetsDesc = if (targetSsids.isEmpty()) "未设置" else targetSsids.joinToString("、")
                binding.tvTargetSsidInfo.text = "自动登录目标：$targetsDesc"
                binding.tvTargetSsidInfo.setTextColor(Color.parseColor("#555555"))
                binding.btnSetTargetSsid.visibility = View.VISIBLE
                binding.btnRemoveTargetSsid.visibility = View.GONE
            } else {
                binding.tvTargetSsidInfo.text = "当前 Wi-Fi 不在认证白名单（AHUT-FREE、AHUT-wifi6）"
                binding.tvTargetSsidInfo.setTextColor(Color.parseColor("#888888"))
                binding.btnSetTargetSsid.visibility = View.GONE
                binding.btnRemoveTargetSsid.visibility = View.GONE
            }
        } else {
            val targetsDesc = if (targetSsids.isEmpty()) "未设置" else targetSsids.joinToString("、")
            val fineGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            binding.tvTargetSsidInfo.text = when {
                info == null -> "自动登录目标：$targetsDesc"
                !fineGranted -> "无法识别 Wi-Fi 名称；请授予精确位置权限"
                !wifiProvider.isSsidReadingAvailable() -> "无法识别 Wi-Fi 名称；请开启系统定位服务"
                !backgroundGranted -> "磁贴后台读取 Wi-Fi 名称需要将位置权限设为“始终允许”"
                else -> "无法识别当前 Wi-Fi 名称；请检查应用位置权限"
            }
            binding.tvTargetSsidInfo.setTextColor(Color.parseColor("#888888"))
            binding.btnSetTargetSsid.visibility = View.GONE
            binding.btnRemoveTargetSsid.visibility = View.GONE
            binding.btnLocationSettings.visibility = if (info != null) View.VISIBLE else View.GONE
            binding.btnLocationSettings.text = when {
                !fineGranted -> "应用位置设置"
                !backgroundGranted -> "后台位置权限设置"
                else -> "系统定位设置"
            }
        }
    }

    private fun performLogin(username: String, password: String, wifiInfo: WifiInfo) {
        setLoadingState(true)
        updateStatus("状态：正在强制通过 Wi-Fi 发起认证...", Color.parseColor("#1976D2"))

        lifecycleScope.launch {
            try {
                val result = authenticationCoordinator.loginManually(
                    wifiInfo,
                    ahut.wifiauth.android.model.UserCredentials(username, password)
                ).await()
                setLoadingState(false)
                when (result) {
                    is LoginResult.Success -> {
                        updateStatus("状态：${result.message}", Color.parseColor("#2E7D32"))
                        updateUiMode()
                        refreshWifiInfo()
                        syncTileState()
                    }
                    is LoginResult.PortalError -> {
                        updateStatus("状态：认证失败 - ${result.message}", Color.parseColor("#D32F2F"))
                    }
                    is LoginResult.NetworkError -> {
                        updateStatus("状态：网络异常 - ${result.message}", Color.parseColor("#D32F2F"))
                    }
                    is LoginResult.ParseError -> {
                        updateStatus("状态：响应解析异常", Color.parseColor("#F57C00"))
                    }
                }
            } finally {
                if (!isDestroyed) setLoadingState(false)
            }
        }
    }

    private fun performLogout(wifiInfo: WifiInfo) {
        setLoadingState(true)
        updateStatus("状态：正在向网关请求注销...", Color.parseColor("#1976D2"))

        lifecycleScope.launch {
            try {
                val result = authenticationCoordinator.logout(wifiInfo).await()
                setLoadingState(false)
                when (result) {
                    is LoginResult.Success -> {
                        updateStatus("状态：${result.message}", Color.parseColor("#2E7D32"))
                        refreshWifiInfo()
                        syncTileState()
                    }
                    else -> {
                        updateStatus("状态：注销失败", Color.parseColor("#D32F2F"))
                    }
                }
            } finally {
                if (!isDestroyed) setLoadingState(false)
            }
        }
    }

    private fun renderGatewayStatus(state: ahut.wifiauth.android.auth.AuthenticationUiState) {
        setLoadingState(state.operation == AuthOperation.LOGIN || state.operation == AuthOperation.LOGOUT)
        if (state.wifiInfo == null) {
            binding.tvAuthStatus.text = "网关状态：未连 Wi-Fi"
            binding.tvAuthStatus.setTextColor(Color.parseColor("#999999"))
            binding.btnQuickLogin.text = "一 键 登 录"
            return
        }
        when (val status = state.gatewayStatus) {
            is OnlineStatus.Online -> {
                binding.tvAuthStatus.text = "网关状态：已认证在线 (账号: ${status.uid})"
                binding.tvAuthStatus.setTextColor(Color.parseColor("#2E7D32"))
                binding.btnQuickLogin.text = "已认证在线 (点击重登)"
            }
            is OnlineStatus.Offline -> {
                binding.tvAuthStatus.text = "网关状态：未认证 (待登录)"
                binding.tvAuthStatus.setTextColor(Color.parseColor("#E65100"))
                binding.btnQuickLogin.text = "一 键 登 录"
            }
            is OnlineStatus.Error -> {
                binding.tvAuthStatus.text = "网关状态：${status.message}"
                binding.tvAuthStatus.setTextColor(Color.parseColor("#757575"))
                binding.btnQuickLogin.text = "一 键 登 录"
            }
            null -> {
                binding.tvAuthStatus.text = "网关状态：检测中..."
                binding.tvAuthStatus.setTextColor(Color.parseColor("#F57C00"))
            }
        }
        if (state.userPaused) {
            binding.tvAuthStatus.text = "网关状态：本次 Wi-Fi 连接已暂停自动认证"
            binding.tvAuthStatus.setTextColor(Color.parseColor("#757575"))
        }
        state.message?.takeIf { it.isNotBlank() }?.let { message ->
            updateStatus("状态：$message", if (state.operation != null) {
                Color.parseColor("#1976D2")
            } else Color.parseColor("#555555"))
        }
    }

    private fun syncTileState() {
        try {
            TileService.requestListeningState(
                this,
                ComponentName(this, AuthTileService::class.java)
            )
        } catch (_: Exception) {
            // 忽略磁贴同步异常
        }
    }

    private fun setLoadingState(isLoading: Boolean) {
        binding.btnQuickLogin.isEnabled = !isLoading
        binding.btnSaveAndLogin.isEnabled = !isLoading
        binding.btnRefreshIp.isEnabled = !isLoading
        binding.btnLogout.isEnabled = !isLoading
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun updateStatus(text: String, color: Int) {
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(color)
    }
}
