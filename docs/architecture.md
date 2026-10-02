# Android 客户端架构

应用包名为 `ahut.wifiauth.android`。本说明记录网络身份、ePortal 请求、认证协调与凭据存储的当前边界。协议、界面、构建方式和版本历史见 [实现说明](ahut_wifi_auth_app.md)。

## 模块

```text
app/src/main/java/ahut/wifiauth/android/
├── MainActivity.kt                         # Activity UI 与用户操作
├── ui/WindowUi.kt                          # edge-to-edge inset、输入框滚动与键盘收起
├── auth/
│   ├── PortalSsidPolicy.kt                 # 精确工作白名单与目标过滤
│   ├── AuthSessionPolicy.kt                # 纯策略：连接周期、注销暂停、自动重试预算
│   └── AuthenticationCoordinator.kt       # 进程共享状态、查询去重、操作互斥与协调
├── model/WifiInfo.kt                       # Network、IPv4、SSID、连接代次
├── network/
│   ├── WifiNetworkProvider.kt              # 同一 Network 的 SSID/IP 快照与监听
│   └── EPortalClient.kt                    # 绑定 Network 的 HTTP GET 与响应解析
├── service/AuthTileService.kt              # 与 Activity 共用协调器的磁贴入口
└── storage/CredentialStore.kt              # AndroidKeyStore 支持的 AES-GCM 凭据存取
```

Android backup 配置还包含 `app/src/main/res/xml/backup_rules.xml` 和 `app/src/main/res/xml/data_extraction_rules.xml`，用于排除敏感应用偏好。Release 签名初始化与构建脚本位于 `scripts/Initialize-ReleaseSigning.ps1`、`scripts/Build-Release.ps1`；构建环境与签名接口见 [实现说明](ahut_wifi_auth_app.md#构建与release签名)。

`MainActivity` 和 `AuthTileService` 都只把操作交给进程级 `AuthenticationCoordinator`。协调器共享实时 Wi-Fi 快照、网关状态、认证/注销互斥锁和查询结果；回调观察者按组件身份成对管理。Activity 从创建到销毁期间保持观察，磁贴仅在系统要求监听时观察。最后一个观察者离开时，协调器停止监听并取消查询、登录及重试任务，但保留持久化的连接周期、用户暂停和重试预算。组件恢复观察后重新读取当前快照。Android 后台位置权限、系统省电策略和进程回收仍可能限制后台 SSID 读取；进程被终止后没有常驻服务保证继续工作。

## 网络身份与请求有效性

- 每个 `WifiInfo` 同时包含 Android `Network`、该 Network 的 IPv4、该 Network 的 SSID 和连接代次。认证请求不使用进程默认路由，而通过 `Network.openConnection()` 发送。
- 未认证 Wi-Fi 不要求 `NET_CAPABILITY_VALIDATED`；只接受物理、非 VPN Wi-Fi。Android 10+ 从对应 `NetworkCapabilities.transportInfo` 获取 SSID。Android 12+ 注册包含 `FLAG_INCLUDE_LOCATION_INFO` 的回调；位置权限或系统定位不可用时不沿用缓存 SSID。
- Android 7–9 没有 per-Network SSID 接口。仅当唯一候选物理 Wi-Fi 的 IPv4 与 legacy `WifiManager` IPv4 相同，且读取前后 Network、IP、SSID 均稳定时才接受 SSID。
- 只在 Android 读取边界剥离一次包装双引号。规范化后的白名单为精确区分大小写集合 `AHUT-FREE`、`AHUT-wifi6`；未知或非白名单 SSID 不发送查询、登录或注销。
- 请求捕获完整身份快照，并在建立连接前及处理响应前复核。网络、SSID 或 IPv4 快照变化会使旧任务失效，旧响应不得更新当前状态。取消时关闭活动 `HttpURLConnection` 并中断阻塞工作线程。
- HTTP GET、现有网关地址和参数保持不变。请求不跟随重定向。

## 自动认证连接周期

`AuthSessionPolicy` 为不依赖 Android 的 JVM 可测策略。一个连接周期由确认的 Android `Network` 身份代表；暂时未知 SSID、缺少 IPv4、观察者重注册、同 Network IP 变化不会结束周期。仅确认 `onLost` 或观察到不同 Network 才重置用户暂停和有限重试预算。重启时优先用系统 `BOOT_COUNT` 识别新周期；无法读取时保守保留持久状态。

自动请求只会在白名单 SSID、IPv4 有效、自动开关开启、SSID 被显式选为目标、凭据可读取、网关明确返回 Offline 且本连接没有用户暂停时启动。网关 Error 不作为 Offline。登录和注销共用互斥锁；同一身份的并发状态查询合并，查询序号与快照校验阻止过期结果生效。主动注销先持久化暂停、撤销待执行自动请求和查询，再发送注销；请求失败也不解除暂停。手动登录仅在成功且身份仍然有效时解除暂停。

网络错误自动重试最多额外两次，间隔 5 秒和 15 秒。每次重试前重新确认同一快照并确认网关 Offline；明确业务拒绝停止本轮。预算按连接周期持久化，成功后只有后续再次确认 Offline 才能开启新一轮。应用没有新增常驻后台服务或定时唤醒任务。

## 网关协议

- 状态：`GET http://10.255.255.154/drcom/chkstatus?callback=dr1002`；JSONP `result == 1` 为 Online、`result == 0` 为 Offline，其他内容为 Error。
- 登录：`GET http://10.255.255.154:801/eportal/portal/login`，callback `dr1003`，参数继续使用现有协议。
- 注销：`GET http://10.255.255.154:801/eportal/portal/logout`，callback `dr1004`。

## 凭据保护边界

凭据密文保存在应用私有偏好，使用 AndroidKeyStore 中 AES-256-GCM 密钥保护。设备可能提供硬件保护，但硬件支持取决于设备实现，应用不承诺密钥必然位于 TEE。GCM 认证标签可检测密文篡改；随机 IV 是加密格式的一部分，不应描述为独立的重放保护机制。密钥不可用或解密失败时，凭据必须视作无法读取并要求恢复，不得静默改用空值。

现有网关通过 HTTP GET 接收账号密码，不提供传输加密；本地凭据加密不能保护网络传输过程。请求固定到当前 Wi-Fi 且不跟随重定向，但完整认证 URL 仍包含凭据，不应放入日志或公开 Issue。
