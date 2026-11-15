# 版本打包与发布

公开仓库：<https://github.com/xiangan666/Watch4QuickApps>。发布入口：[GitHub Releases](https://github.com/xiangan666/Watch4QuickApps/releases)。版本事实源为根目录 `version.properties`，标签格式为 `vX.Y.Z`。

## 签名与升级约束

当前发布的是开发测试 APK，沿用维护者本机已经安装到测试手机的 Android 调试证书。签名私钥留在本机，不上传源码仓库、源码归档或 CI。CI 只执行构建、测试与 Lint，不分发可覆盖现有安装的 APK。

其他电脑构建的 APK 通常使用不同的调试证书，不能直接覆盖当前发布版本。不要为绕过签名冲突指导用户随意卸载；卸载会删除本机配对配置与日志。未来改用生产签名时，需要发布单独的迁移说明。当前发布签名只适用于开发验证。

## 维护者流程

PowerShell 7，项目根目录；需要已登录的 GitHub CLI、推送与发布权限，以及当前签名私钥：

```powershell
# 先更新 version.properties、CHANGELOG.md 和对应版本发布说明。
.\scripts\build.ps1

# 审阅差异，显式暂存本次文件，提交并推送。不要提交 dist 或私钥。
git push origin main

# 从已验证提交建立标签，再推送；用实际版本替换占位符。
git tag -a vX.Y.Z -m '发布 X.Y.Z'
git push origin vX.Y.Z

# 等待该提交的 Android CI 成功后打包和发布。
.\scripts\package.ps1 -SkipBuild
.\scripts\publish.ps1
```

`package.ps1` 使用源码白名单，在 `dist/` 生成版本化 APK、源码 ZIP 与 `SHA256SUMS.txt`。APK 元数据必须与版本事实源一致；已有同名文件先备份到忽略的 `.local/backups/`。归档包含文档、构建脚本、Gradle Wrapper、锁文件、完整性校验和 CI，不包含本机配置、依赖缓存、研究样本、私钥或用户数据。

`publish.ps1` 检查工作区、标签、远程提交、成功的 CI 和本地产物摘要。然后创建或继续同版本草稿，上传全部资产，重新下载并比对 SHA-256；比对一致后才发布。脚本不会覆盖已正式发布的版本。当前 `0.y.z` 默认标记为预发布，`1.0.0` 起默认正式发布；不强制设置 latest。

GitHub CLI 不在 PATH 时可用 `-GhPath '完整路径/gh.exe'` 指定；它应来自 GitHub CLI 官方分发。脚本不安装全局工具，也不读取或输出认证令牌。失败草稿可在排除问题后再次运行同一入口；不要删除已发布标签后尝试复用版本号。

## 发布后核对

核对仓库仍为 Public、远程标签指向候选提交、Release 已离开草稿状态，以及 APK、源码 ZIP、摘要文件的名称、大小与下载摘要一致。GitHub Release 的签名证明与 SHA-256 是不同机制：摘要证明下载内容一致；只在平台实际提供证明且验证成功时宣称发布证明有效。
