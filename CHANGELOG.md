# 变更记录

采用 Keep a Changelog 的分类方式，使用语义化版本。版本事实源为 `version.properties`。0.1.0 与 0.1.1 为本地交付；0.1.2 是首个公开开发预览版本，后续发布见 [GitHub Releases](https://github.com/xiangan666/Watch4QuickApps/releases)。

## [Unreleased]

## [0.1.2] - 2026-11-16

### Added

- 日志页提供可持久保存的「详细日志」开关，记录协议元信息、状态码、分片进度和异常链。
- 支持通过系统文件保存器导出 UTF-8 文本日志，自动隐藏密钥、长十六进制值和蓝牙地址。
- 日志在断开或重启应用后仍可读取，限制记录数量与总长度，支持清空。
- 增加本地构建、源码打包、SHA-256 校验和开发排错说明。
- 建立公开 GitHub 仓库、构建检查和可重复的草稿发布入口；发布前核对标签、成功 CI 与下载资产摘要。
- 补齐开发约定、安全反馈、签名升级约束与第三方许可说明。

### Fixed

- 安装预检被拒绝时保留仍然有效的蓝牙会话，不再将设备拒绝当作传输故障主动断开。
- 对已核实的预检状态码 3 显示设备报告存储空间不足，不再只显示裸数字。
- 增加安装拒绝后继续读取列表、不发送 RPK 的回归验证，以及日志持久化、导出与脱敏测试。

## [0.1.1] - 2026-11-16

### Fixed

- 在 Android 12 及以上继续声明旧版 `BLUETOOTH` 普通权限，兼容仍检查该权限的 MIUI RFCOMM 实现。
- 区分未授权、厂商系统拒绝与其他连接异常，避免将所有 `SecurityException` 误报为权限被撤销。
- 真机验证蓝牙连接、SPP V1 鉴权与读取应用列表。

## [0.1.0] - 2026-11-16

### Added

- 建立独立 Android 管理器工程，实现 Watch 4 的配对信息导入、SPP V1 鉴权、应用列表和 RPK 管理流程。
- 支持 Root 与 Shizuku 用户服务读取固定配对库，通过 Android Keystore 加密保存密钥。
- 实现协议、加密、分片传输和本机边界验证。

### Fixed

- Root 固定读取操作进入全局挂载命名空间，解决 UID 为 0 仍看不到其他应用数据的问题。
- 分开处理 `app_process` 标准输出与系统警告，避免将 stderr 混入配对 JSON。

[Unreleased]: https://github.com/xiangan666/Watch4QuickApps/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/xiangan666/Watch4QuickApps/releases/tag/v0.1.2
