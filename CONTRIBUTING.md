# 开发与贡献

环境、安装和标准构建命令见 [README](README.md#开发与构建)。目前使用 Windows、PowerShell 7、JDK 17 或 21、Android SDK 36、Gradle Wrapper 8.14.1。运行 `scripts/build.ps1` 执行协议测试、两个 APK 构建和 Lint；GitHub Actions 执行相同入口。真实手机的仪器测试另见 [验证记录](docs/verification.md)。

## 变更流程

1. 从 `main` 创建用途明确的分支，如 `fix/bluetooth-permission`。
2. 只修改与任务有关的实现和文档；缺陷修复尽量增加可观察行为的回归验证。
3. 维护 `version.properties` 和 `CHANGELOG.md`，同一轮连续修改合并为一个版本。当前 `0.y.z` 为开发期；兼容修复与文档维护递增 PATCH，新增公共功能递增 MINOR。
4. 执行标准验证，记录未覆盖的真机条件；不要把构建成功当作手表安装成功。
5. 提交遵循 Conventional Commits，例如 `fix(bluetooth): 兼容 MIUI 的旧蓝牙权限检查`。PR 说明问题、实际行为、验证与文档影响。

本项目尚未授予原作代码的开放许可证，提交贡献前请先通过 Issue 或维护者邮箱确认贡献和再分发安排，见 [版权说明](COPYRIGHT.md)。保留第三方作者与许可，不提交研究 APK、反编译文件或未获授权的上游代码。

## 依赖与敏感文件

仅在有意更新依赖时运行 `scripts/build.ps1 -UpdateLocks`，审阅锁文件和 Gradle 校验元数据的变化。不要手写校验哈希或关闭校验来解决下载异常。

Root 与 Shizuku 仅调用固定操作；不要增加任意 Shell 执行接口。配对密钥、数据库、用户日志、签名私钥、本机 SDK 路径和 `.local/` 研究缓存不进入仓库、CI 报告或发布资产。公开问题中请先检查日志是否包含个人信息。

版本发布见 [发布说明](docs/releasing.md)。CI 不保存维护者的私钥，也不使用其临时生成的调试证书作为用户升级签名。
