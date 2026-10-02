# 安徽工业大学校园网认证 App 实现说明

模块分层、网络身份与凭据保护边界见 [架构说明](architecture.md)。本文记录协议、界面和构建实现。

## 产品范围

原生 Android 客户端在双网并发时把每个 ePortal 请求绑定到当前物理 Wi-Fi `Network`，使用该连接的 IPv4 地址完成认证、在线查询和注销。未认证 Wi-Fi 不要求 `NET_CAPABILITY_VALIDATED`，VPN 网络必须排除。认证协议继续使用现有 HTTP GET 与 JSONP 网关接口。

## 校园网 SSID 策略

- 工作白名单只有大小写精确的 `AHUT-FREE` 和 `AHUT-wifi6`。仅去掉 Android 加在 SSID 外层的成对双引号；不 trim、不忽略大小写。
- 登录、注销和在线查询都必须具备来自同一个 Wi-Fi `Network` 的白名单 SSID。SSID 未知或不匹配时，不访问网关。
- 自动登录目标独立于工作白名单。首次安装的默认目标只有 `AHUT-FREE`；`AHUT-wifi6` 仅在用户显式加入目标后自动认证。用户可移除目标，空目标列表会保留为空。升级时清理无效历史目标并保留白名单内已有选择。
- 获取 SSID 需要位置权限及系统定位服务。权限不足、定位关闭或系统隐藏 SSID 时，应用提示用户检查设置并停止网关操作。

## 网关协议

- 在线查询：`GET http://10.255.255.154/drcom/chkstatus?callback=dr1002`。`result == 1` 为在线，`result == 0` 为离线；其他响应为错误。
- 登录：`GET http://10.255.255.154:801/eportal/portal/login`，JSONP callback 为 `dr1003`，`result == 1` 表示成功。
- 注销：`GET http://10.255.255.154:801/eportal/portal/logout`，JSONP callback 为 `dr1004`。
- 请求固定在快照对应的 Wi-Fi Network，使用同一快照的 IPv4。禁止跟随 HTTP 重定向。

## 版本迭代历史

| 版本 | versionCode | 记录 |
| --- | ---: | --- |
| 1.3.2 | 7 | 增加精确 Wi-Fi 工作白名单与独立自动目标策略；SSID 按网络读取并失败关闭；Activity 联合申请位置权限；Activity 与磁贴共用认证协调器，旧网络响应失效，主动注销暂停和有限自动重试按连接周期管理；可删除无法解密的已存凭据，备份排除敏感偏好；补齐 edge-to-edge/inset 与输入窗口处理、磁贴锁屏解锁和运行时位置权限流程；正式 Release 签名验收完成，真机验证待完成。 |

## 认证状态与自动操作

- Activity 和快捷磁贴共享进程级认证协调器。登录与注销互斥；同一 Network/SSID/IP 快照的状态查询合并。Activity 旋转、磁贴重建或暂时无法读取 SSID/IP，不解除本连接的主动注销暂停。
- 请求使用固定的 `Network`、IPv4、SSID 和连接代次。请求发送前和读取响应后都会校验当前快照；网络身份变化后，旧请求不得带凭据发出或更新当前页面/磁贴状态。取消会关闭阻塞中的 HTTP 连接。
- 自动认证必须满足工作白名单、明确 Offline、启用自动登录、用户目标、可用凭据及当前连接未暂停。首次目标仅 `AHUT-FREE`。查询 Error 不会触发自动登录。
- 主动注销立即记录本连接暂停，再执行网关注销；即使请求失败，暂停仍保留。手动登录成功才解除暂停。只有确认网络断开、切换到不同 Network，或设备重启且系统 `BOOT_COUNT` 证明新周期，才重新开放本周期自动认证。
- 临时网络错误最多额外重试两次，等待 5 秒和 15 秒；每次重试都要求原快照仍有效且网关再次确认 Offline。明确认证拒绝停止重试。状态按连接周期持久化；关闭所有 Activity/磁贴观察者后不保留后台认证任务或定时唤醒。
- Activity 从创建到销毁期间保持网络观察；磁贴只在系统要求监听时观察。Android 后台位置权限、省电策略和进程回收可能限制后台 SSID 读取；进程结束后不保证后台继续运行。

## 凭据、磁贴与界面

- 登录密码按用户输入原样保存和发送，不做首尾空格清理。凭据保存失败时显示错误并停止登录请求。
- 凭据缺失与已有密文无法解密分别处理。无法解密时提示用户重新录入或删除；删除操作取消使用旧凭据的认证任务、清除密文并删除对应 AndroidKeyStore alias。
- 凭据使用 AndroidKeyStore 中 AES-GCM 密钥保护，设备是否提供硬件支持由系统决定。认证标签用于检测密文篡改，不承诺密钥必然由 TEE 托管或具有独立防重放能力。
- 云备份和设备到设备迁移都排除应用 SharedPreferences，其中包括加密凭据、SSID 目标和连接暂停状态。
- 快捷磁贴仅显示当前 SSID 标题、系统图标和在线高亮状态，不展示账号、额外状态字段或 subtitle。锁屏点击认证/注销会先解锁，再基于最新连接与网关状态继续操作。
- 主界面支持系统安全区域、可滚动窄屏布局与软键盘遮挡；密码输入框 Done 复用同一保存并登录操作。界面版本文字由 `versionName` 构建生成。

## 界面与窗口实现

- `activity_main.xml` 使用启用 `fillViewport` 的 `ScrollView`，窄屏操作竖排，按钮按内容与系统字号增高；短屏、横屏和分屏允许纵向滚动。
- `WindowUi.enableEdgeToEdge` 将系统栏和刘海 inset 加到页面原始 padding 上，底部取系统栏与 IME inset 的较大值，并保留基础间距。浅色界面使用深色系统栏图标。
- 输入框获得焦点时调用 `WindowUi.scrollIntoView`。密码框的 IME“完成”动作先收起键盘，再触发“保存并登录”按钮，与按钮共用校验和保存流程。Manifest 配置 `adjustResize`。
- “位置设置”在应用权限缺失时打开应用设置，权限具备后打开系统定位设置。Android 10 及以上，磁贴在后台读取 SSID 还需要将位置权限设为“始终允许”。
- “注销网络”保留凭据并暂停当前连接周期自动认证；“删除已保存凭据”清除密文、本地密钥和输入内容。无法解密时仍显示删除入口。

## 构建与Release签名

| 配置 | 当前值 |
| --- | --- |
| applicationId / namespace | `ahut.wifiauth.android` |
| minSdk / targetSdk / compileSdk | 24 / 36 / 36 |
| Android Gradle Plugin / Gradle | 9.0.1（内置 Kotlin）/ 9.1.0 |
| JDK / Android SDK Build Tools | 17 / 36.0.0 |
| Release 优化 | R8 混淆、代码与资源裁剪 |

依赖版本位于 `gradle/libs.versions.toml`。安装对应 JDK 与 Android SDK，在仓库根目录创建被 Git 忽略的 `local.properties`，配置 `sdk.dir`。PowerShell 构建脚本要求 `java.exe`、`keytool.exe` 可从 PATH 调用，SDK 组件已安装；不会自动安装或更新 SDK。

```powershell
# 单元测试、Release lint 与编译，不生成 APK、不需要签名材料
.\scripts\Build-Release.ps1 -VerifyOnly

# 独立开发者首次初始化自己的密钥，已有正式发布密钥时跳过
.\scripts\Initialize-ReleaseSigning.ps1

# 单元测试、Release lint、R8 优化和正式签名打包
.\scripts\Build-Release.ps1
```

初始化脚本通过隐藏输入读取密码，创建 `signing/ahut-release.p12`（alias `ahut-release`，RSA-3072），并将密码保存为当前 Windows 用户绑定的 `signing/release-password.dpapi`。已有文件时拒绝覆盖。DPAPI 文件不能作为跨机器密码备份；保留发布密钥及其密码，才能维持后续安装升级的签名身份。独立开发者使用自己的密钥时，APK 无法覆盖不同签名的安装。

Release 签名接口由以下进程环境变量提供，便于非 Windows 构建环境调用 Gradle：

| 环境变量 | 用途 |
| --- | --- |
| `AHUT_RELEASE_STORE_FILE` | Keystore 文件路径 |
| `AHUT_RELEASE_KEY_ALIAS` | 签名密钥 alias |
| `AHUT_RELEASE_STORE_PASSWORD` | Keystore 密码 |
| `AHUT_RELEASE_KEY_PASSWORD` | 密钥密码 |

缺少任何签名配置时，Release 打包任务失败。Windows 构建脚本从本地文件读取签名配置，短暂通过进程环境传入工具并在退出时恢复环境；密钥、密码文件和本机路径均不提交到源码仓库。

`testDebugUnitTest` 是 JVM 测试任务，不生成 Debug APK。默认构建执行该任务、`lintRelease` 与 `assembleRelease`，产物归档为 `apk_output/ahut-auth-v<versionName>-release.apk`。`-VerifyOnly` 不读取签名材料；Gradle 用户目录、Android 用户目录与临时文件保存在项目 `.gradle/` 下，配置缓存和持久 daemon 关闭。应用版本由 `app/build.gradle.kts` 定义，界面版本文字自动从 `versionName` 生成。

## 验证范围

JVM 测试覆盖协议 URL 编码、JSONP 状态解析、精确 SSID 与自动目标、连接周期暂停、重试预算、旧响应失效和操作互斥。校园网双网认证、Wi-Fi 切换、锁屏磁贴及 IME、窄屏、刘海和大字体界面表现仍需真机检查。
