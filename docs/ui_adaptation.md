# 主界面与窗口适配

## 页面行为

主界面把网络状态、凭据操作、输入表单和版本信息放在启用 `fillViewport` 的 `ScrollView` 中。窄屏下目标网络操作与凭据辅助操作采用竖排，主要按钮使用 `wrap_content` 和最小高度，文本可随系统字号增高。短屏、横屏和分屏可以纵向滚动；这些窗口形态和大字体的最终表现仍需真机检查。

保留的目标操作包括“保存目标”“移除目标”和“位置设置”。工作白名单为 AHUT-FREE 与 AHUT-wifi6；AHUT-FREE 是默认自动目标，AHUT-wifi6 只有用户明确保存后才参与自动认证。无法读取 Wi-Fi 名称时，主界面会提示所需权限或系统定位服务状态。读取精确 SSID 需要前台精确位置权限并开启系统定位；Android 10 及以上，快捷磁贴在后台读取 SSID 还需要把位置权限设为“始终允许”。相应设置入口在缺少应用权限时打开应用设置，权限具备后打开系统定位设置。

“注销网络”只请求网关注销并保留凭据。“删除已保存凭据”是单独操作，成功后清除凭据、本地密钥和输入框内容。删除入口在凭据有效或无法解密时显示，在没有保存凭据时隐藏；因此无法解密的凭据可以通过该入口清除后重新输入。

## 窗口与输入适配

MainActivity 在设置内容视图后调用 `WindowUi.enableEdgeToEdge(window, binding.rootScrollView)`。helper 开启 edge-to-edge，将 `systemBars` 和 `displayCutout` inset 加到根视图原有 padding 上；底部使用系统栏与 IME inset 的较大值，并保留布局原有的 24dp 基础间距。浅色页面使用深色状态栏和导航栏图标，横屏安全区依据系统 inset 处理，不硬编码系统栏尺寸。

MainActivity 的用户名和密码输入框在获得焦点时调用 `WindowUi.scrollIntoView`。密码框的 IME“完成”动作先收起键盘，再调用 `btnSaveAndLogin.performClick()`，与按钮共用非空校验、保存和登录流程。凭据校验不对密码执行 trim。Manifest 中 MainActivity 配置了 `windowSoftInputMode="adjustResize"`。键盘与窗口 inset 配合后的实际遮挡表现仍需真机确认。

认证协调器的连接状态订阅会把连接暂停信息显示在网关状态区，并将自动认证过程、成功、错误和延迟重试消息显示在状态区。用户注销后当前 Wi-Fi 连接会暂停自动认证；网络暂时错误时，界面显示稍后重试提示。

## 相关文件

- `app/src/main/res/layout/activity_main.xml`：可滚动页面、原有 View ID、目标与凭据操作入口、IME 动作声明。
- `app/src/main/java/ahut/wifiauth/android/ui/WindowUi.kt`：edge-to-edge inset、滚动到指定视图、收起键盘的 helper API。
- `app/src/main/java/ahut/wifiauth/android/MainActivity.kt`：窗口 helper 接入、凭据入口状态、焦点滚动、IME 完成和认证状态呈现。
- `app/src/main/AndroidManifest.xml`：MainActivity 的 `adjustResize` 和位置权限声明。

## 验证状态

静态检查及正式 Release 构建通过；构建和静态检查摘要见 [release_signing.md](release_signing.md)。尚未完成真机或校园网实际交互验证。待设备检查 Wi-Fi 切换、注销后暂停自动认证、锁屏磁贴解锁流程，以及 IME、窄屏、横屏刘海和大字体下的布局表现。
