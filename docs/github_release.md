# GitHub 公开仓库绑定与 v1.3.2 发布

本地开源目录已准备为独立 Git 仓库：分支 `main`，只含一次干净的源码快照提交。初始提交采用项目贡献者名称及 `contributors@example.invalid` 占位邮箱，不携带原项目的 Git 历史、个人邮箱或远程地址。以下远程操作由维护者执行。

## 1. 创建空的公开仓库

1. 登录 GitHub，打开 [创建仓库页面](https://github.com/new)。
2. 选择 Owner，仓库名建议为 `ahut_wifi_auth_app`，可填写“安徽工业大学校园网一键认证 Android 客户端”。
3. 选择 **Public**。README、`.gitignore` 与许可证的初始化选项均保持关闭，因为本地已经包含这些文件。
4. 点击 **Create repository**，复制 HTTPS 仓库地址。

此流程参见 [GitHub 创建仓库文档](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-new-repository)。

## 2. 绑定独立开源目录

在原项目根目录打开 PowerShell，进入 `opensource`；如果已直接打开开源目录或克隆的仓库，跳过第一行。

```powershell
Set-Location .\opensource
git rev-parse --show-toplevel
git status --short
git branch --show-current
```

本机整理的目录应以 `opensource` 结尾，分支应为 `main`，工作区应为空。必须在此独立仓库内执行后续命令。

在 GitHub **Settings → Emails** 开启邮箱隐私，并复制页面提供的完整 `noreply` 邮箱。将下面两个占位值替换为公开用户名与该邮箱，只修改此仓库的 Git 设置。

```powershell
git config --local user.name "YOUR_GITHUB_USERNAME"
git config --local user.email "YOUR_GITHUB_NOREPLY_EMAIL"
.\scripts\Test-OpenSourcePrivacy.ps1
```

初始提交已使用通用署名；新设置作用于后续提交。邮箱处理参见 [GitHub 提交邮箱说明](https://docs.github.com/en/account-and-profile/how-tos/email-preferences/setting-your-commit-email-address)。

把 `OWNER` 换成自己的 GitHub 用户名或组织名，然后绑定并推送。HTTPS 认证可使用 Git Credential Manager 或先执行 `gh auth login`，不把令牌写进 remote URL。

```powershell
git remote add origin https://github.com/OWNER/ahut_wifi_auth_app.git
git remote -v
git push -u origin main
```

若已经存在 `origin`，检查地址后使用 `git remote set-url origin <仓库地址>`。推送后在 GitHub 检查 README、MIT 许可证和文件目录。更多说明见 [GitHub 导入本地代码](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)。

## 3. 准备 v1.3.2 附件

当前代码已经是 `versionName = "1.3.2"`、`versionCode = 7`，UI 使用构建生成的 `app_version`。发布已存在的 APK 不改变代码或版本号。

本次准备保留原项目已验收的 `apk_output/ahut-auth-v1.3.2-release.apk`，没有把 APK 复制到开源源码树。下面命令适用于仍在原项目的 `opensource` 目录中操作的维护者：

```powershell
$releaseApk = Resolve-Path -LiteralPath ..\apk_output\ahut-auth-v1.3.2-release.apk
Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256
```

预期文件大小为 **225,287 字节**，预期 SHA-256 为：

```text
6F1B5DC7EB5393971398EF7DBE7CF4BBECCB1BF8C872F0B479FA951A06A7CB72
```

克隆仓库的维护者应从自己私有保存的正式产物目录选取同一 APK；源码仓库不提供原发布私钥。官方 APK 的签名身份需要持续保留，以支持升级安装。独立开发者可以按 [签名文档](release_signing.md) 生成自己的密钥，但自行签名的 APK 无法覆盖不同签名的安装。

现有 APK 已完成编译、17 个单元测试、Release lint 和签名静态验证。校园网真机交互仍待验证，发布说明应保留此状态。修改应用或另行生成新版本 APK 时，使用下一个版本（如 `1.3.3` / `versionCode = 8`），同步版本历史后按签名文档构建。

## 4. 推送版本标签

确认源码和文档提交已经推送，再在独立开源仓库创建轻量标签。它不额外记录 tagger 邮箱。

```powershell
git status --short
.\scripts\Test-OpenSourcePrivacy.ps1
git tag v1.3.2
git push origin v1.3.2
```

若同名标签已经存在，先检查它指向的源码，不覆盖或强制推送已有发布标签。

## 5. 在 GitHub 创建 Release

1. 打开仓库的 **Releases → Draft a new release**。
2. 选择已经推送的 **v1.3.2** 标签；标题填写 **v1.3.2**。
3. 复制 [release_notes_v1.3.2.md](release_notes_v1.3.2.md) 的内容作为发布说明。
4. 在附件区上传 `ahut-auth-v1.3.2-release.apk`。源码 ZIP/TAR 由 GitHub 自动提供；不用上传签名目录、私钥、密码文件或本机构建缓存。
5. 先保存草稿，检查附件名、下载后的 SHA-256 和说明。若尚未完成真机验收且希望明确标注测试状态，可启用 **This is a pre-release**。
6. 准备对外分发后点击 **Publish release**；作为正式版本发布时可选 **Set as latest release**。

流程参见 [GitHub Release 管理文档](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)。如果启用了不可变 Release，务必先在草稿阶段添加全部附件。

已安装 GitHub CLI 的维护者也可以创建相同的草稿：

```powershell
gh release create v1.3.2 $releaseApk.Path --verify-tag --draft --title "v1.3.2" --notes-file .\docs\release_notes_v1.3.2.md
```

`$releaseApk` 来自第 3 步，并应指向同一已验收 APK。随后在网页检查草稿并发布。CLI 参数参见 [gh release create](https://cli.github.com/manual/gh_release_create)。
