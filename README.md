# 安徽工业大学校园网一键认证

面向安徽工业大学校园网的原生 Android 客户端。在校园 Wi-Fi 与移动数据同时开启时，通过 Android `Network.openConnection()` 将认证请求绑定到物理 Wi-Fi，使用该连接的 IPv4 地址访问 ePortal 网关。

当前版本：**v1.3.2**（`versionCode = 7`）。支持 Android 7.0 及以上（API 24），使用 Kotlin、Android SDK 与 AndroidX，无需 Root、Shizuku 或无障碍服务。本项目由社区维护，与学校官方应用无隶属关系。

## 功能

- 保存账号与密码，一键登录、查询在线状态或注销校园网。
- 精确识别 `AHUT-FREE`、`AHUT-wifi6`；其他或未知 SSID 不访问认证网关。
- 快捷设置磁贴与主界面共享认证状态，锁屏操作先解锁。
- 可选自动认证：首次默认目标为 `AHUT-FREE`，`AHUT-wifi6` 需手动加入目标。
- 主动注销后暂停当前连接周期的自动认证；临时网络错误最多额外重试两次。
- 使用 AndroidKeyStore 支持的 AES-GCM 保护本地凭据，可删除无法解密的凭据。
- 支持系统安全区域、键盘、窄屏与可滚动布局。

自动认证受组件观察生命周期、位置权限和 Android 后台策略限制。进程结束后不保证继续认证；没有常驻后台服务或定时唤醒任务。

## 安装与使用

1. 在 GitHub 仓库的 **Releases** 中下载 `ahut-auth-v1.3.2-release.apk`，在 Android 设备上安装。
2. 连接 `AHUT-FREE` 或 `AHUT-wifi6`，打开系统定位服务，并按应用提示授予精确位置权限。Android 需要这些条件才能读取准确 SSID。
3. 打开应用，输入自己的学号/工号与校园网密码，点击“保存并登录”。移动数据可以保持开启。
4. 按需启用自动登录、保存/移除自动目标，或在系统快捷设置编辑页添加“校园网认证”磁贴。
5. Android 10 及以上，磁贴在后台读取 SSID 还需要将位置权限设为“始终允许”；通过应用提供的设置入口调整。

“注销网络”保留已保存凭据。“删除已保存凭据”会清除凭据密文及对应本地密钥。权限不足、定位关闭或 SSID 无法识别时，应用停止网关操作并显示提示。

## 源码与构建

| 配置 | 当前值 |
| --- | --- |
| applicationId / namespace | `ahut.wifiauth.android` |
| minSdk / targetSdk / compileSdk | 24 / 36 / 36 |
| Android Gradle Plugin | 9.0.1，使用内置 Kotlin 支持 |
| Gradle distribution | 9.1.0，由 Wrapper 下载 |
| JDK / Android SDK Build Tools | 17 / 36.0.0 |
| Release 优化 | R8 混淆、代码与资源裁剪 |

安装支持 AGP 9.0 的 Android Studio，或准备上述 JDK 和 SDK。构建版本兼容性参见 [Android 官方说明](https://developer.android.com/build/releases/agp-9-0-0-release-notes)。所有依赖版本在 `gradle/libs.versions.toml` 中声明。

在仓库根目录创建仅限本机使用的 `local.properties`，配置 `sdk.dir` 为已安装 Android SDK 的路径；Windows 路径建议使用正斜杠。该文件被 Git 忽略，应由每个开发者自行配置。PowerShell 脚本要求 `java.exe` 与 `keytool.exe` 可从 PATH 调用，并要求 SDK 组件已安装；不会自动安装 SDK。

```powershell
# 单元测试、Release lint 与 Release 编译；不生成 APK、不需要签名材料
.\scripts\Build-Release.ps1 -VerifyOnly

# 独立开发者首次创建自己的签名密钥；已持有发布密钥的维护者跳过此步
.\scripts\Initialize-ReleaseSigning.ps1

# 单元测试、Release lint、R8 优化和正式签名打包
.\scripts\Build-Release.ps1
```

Release APK 自动归档为 `apk_output/ahut-auth-v<versionName>-release.apk`。签名配置通过 `AHUT_RELEASE_*` 环境变量提供；缺少配置时 Release 打包失败。正式维护者需要保留原发布密钥，保证覆盖升级兼容。自行签名的构建具有不同签名身份。

`testDebugUnitTest` 是 AGP 提供的 JVM 测试任务，执行它不会打包 Debug APK。完整的签名流程、环境隔离与跨平台环境变量接口见 [Release 签名说明](docs/release_signing.md)。

## 目录

```text
app/
  src/main/         Android 入口、认证、网络、凭据存储与界面资源
  src/test/         协议解析、SSID 策略与连接周期策略的 JVM 测试
gradle/             Wrapper 与依赖版本目录
scripts/            Release 签名、构建与开源隐私检查脚本
docs/               协议、架构、界面、签名和发布文档
licenses/           随源码分发的第三方许可证
```

- [需求、协议与版本记录](docs/ahut_wifi_auth_app.md)
- [系统架构与保护边界](docs/architecture.md)
- [主界面与窗口适配](docs/ui_adaptation.md)
- [GitHub 仓库绑定与 v1.3.2 Release 发布](docs/github_release.md)
- [开源隐私检查记录](docs/privacy_review.md)

## 隐私与协议边界

仓库不包含真实用户账号、密码、设备数据或发布私钥。测试使用虚构账号和 `192.0.2.0/24` 文档地址。`10.255.255.154` 与两个校园 SSID 是应用所需的协议配置，不是个人设备标识。

运行时账号密码由用户录入，密文保存在应用私有偏好中；云备份和设备迁移排除这些偏好。AndroidKeyStore 是否提供硬件保护取决于设备。网关使用 **HTTP GET**，不提供传输加密，认证 URL 包含账号密码；提交 Issue 或分享日志时应删除完整认证 URL、真实账号和密码。本地加密不能保护 HTTP 传输过程。

## 验证与贡献

现有 v1.3.2 正式构建记录：17 个 JVM 测试通过，Release lint 为 0 个错误、93 个警告；签名 APK 已静态验收。真机及校园网交互尚待验证，详见 [签名验收记录](docs/release_signing.md) 与 [设备待测项](docs/ui_adaptation.md)。本次开源准备没有重新构建 APK。

欢迎通过 Issues 报告问题或提交 Pull Request。请描述 Android 版本、权限状态、SSID 和可复现步骤，并删除个人信息。应用行为或版本变更需同步更新文档；`versionCode` 必须递增，界面版本标识从 `versionName` 自动生成。

## 许可证

项目原创代码与文档采用 [MIT License](LICENSE)，版权署名为 AHUT Wi-Fi Auth Contributors。第三方组件保留各自许可证，见 [第三方声明](THIRD_PARTY_NOTICES.md)。
