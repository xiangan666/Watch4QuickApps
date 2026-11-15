# Watch 4 快应用管理

面向 **Redmi Watch 4** 的独立 Android 管理器。当前公开开发预览版为 **0.1.2**，版本事实源为 [`version.properties`](version.properties)。[公开仓库](https://github.com/xiangan666/Watch4QuickApps) · [版本下载](https://github.com/xiangan666/Watch4QuickApps/releases/tag/v0.1.2) · [构建检查](https://github.com/xiangan666/Watch4QuickApps/actions/workflows/ci.yml)。

支持读取快应用列表、查看设备签名指纹、导入本地 RPK、安装或更新、发送启动指令和卸载。通过 Root 或 Root 模式 Shizuku 自动读取本机小米运动健康的 Watch 4 配对信息，使用 Classic Bluetooth / SPP V1 与手表通信。

- 手机最低 Android 8.0；当前实测手机为 Android 12 / MIUI。
- 目标设备是 Redmi Watch 4，不包含 Watch 4 Active 或使用 SPP V2 的其他型号。
- 读取私有配对数据库需要现有 Root 权限，或以 Root 身份运行的 Shizuku。软件不会替手机获取 Root。
- 普通 ADB 模式 Shizuku 无权读取运动健康的私有数据库。
- 软件不申请互联网权限；配对密钥由 Android Keystore 加密保存在本机。
- 当前 APK 使用 Android 调试签名，是开发预览版本。更新应保持包名和签名一致；其他电脑自行构建的 APK 可能无法覆盖本版本，见 [签名与发布说明](docs/releasing.md)。

## 安装与使用

从 [0.1.2 发布页](https://github.com/xiangan666/Watch4QuickApps/releases/tag/v0.1.2) 下载 `Watch4QuickApps-0.1.2-android-debug.apk`；与原测试版本同签名，可覆盖安装。页面同时提供源码 ZIP 与 `SHA256SUMS.txt`。本地打包后 APK 位于 `dist/`。使用 PowerShell 与已授权 USB 调试的手机安装：

```powershell
adb install -r '.\dist\Watch4QuickApps-0.1.2-android-debug.apk'
```

1. 先在小米运动健康中正常配对手表，并完成同步。
2. 打开管理器的「连接」页，允许附近设备权限，点击「Root 导入」或「Shizuku 导入」。
3. 核对设备后点击「连接手表」。若运动健康占用连接，可自行勾选「连接前临时停止运动健康」后重试；管理完成后重新打开运动健康恢复同步。
4. 在「快应用」页选择 RPK，核对包名、版本和大小，再确认安装。安装完成会同时核对完成事件与设备列表。
5. 卸载会显示确认框，并使用设备实际返回的签名指纹。卸载可能删除该应用的存档。

不依赖 AstroBox、表盘自定义工具或修改版运动健康。

## 详细日志与导出

打开「日志」页，勾选「详细日志」，再复现需要分析的操作。详细模式记录时间和时区、蓝牙权限与连接阶段、SPP 帧元信息、指令编号、预检状态、分片进度、异常类型与异常链；不会记录配对密钥、鉴权载荷、RPK 文件内容或健康数据。

点击「导出日志文件」，在系统文件保存器中选择位置，即可生成 UTF-8 `.txt`。导出文件包含应用版本、Android API、手机型号、日志开关状态与记录。蓝牙地址以及连续的长十六进制密钥或摘要会自动隐藏。导出只保存到用户选定的位置，不自动上传。

日志在本机持久保存，最多保留最近 2000 条且总字符数受限；断开连接、关闭或重启应用后仍可导出。点击「清空」清除旧记录。日志文件导入不在当前功能范围内。

## 安装状态码 3

当前运动健康版本将安装预检状态码 3 映射为「设备存储空间已满，无法下载 App」。这是传输前的设备应答，不等于已经进行签名验证。管理器显示明确原因并保留连接；可继续刷新列表或重试。需要释放空间时，由使用者在官方管理界面选择清理内容，软件不会自动删除其他应用或表盘。

其他故障与证据范围见 [排错说明](docs/troubleshooting.md) 和 [验证记录](docs/verification.md)。

## 开发与构建

需要 Windows、PowerShell 7、JDK 17 或 21、Android SDK Platform 36 和 Build Tools 36.0.0。已验证工具链：JDK 21、AGP 8.13.2、Gradle 8.14.1。首次构建会从 Google Maven、Maven Central 与 Gradle 官方分发站获取依赖。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
.\scripts\build.ps1
.\scripts\package.ps1 -SkipBuild
```

构建脚本在工程路径含中文时创建临时盘符，并在 `finally` 中移除，不移动源码。SDK 默认为 `ANDROID_HOME` 或 `%LOCALAPPDATA%\Android\Sdk`，可使用 `-SdkRoot` 指定。标准构建运行协议测试、APK 构建、Android 测试 APK 构建和 Android Lint。Gradle 锁文件与校验元数据用于固定已解析的依赖；有意更新依赖后使用 `build.ps1 -UpdateLocks` 重新生成并审阅变化。

Android 仪器测试需要连接手机，不随构建自动操作手表：

```powershell
adb install -r '.\app\build\outputs\apk\debug\app-debug.apk'
adb install -r '.\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
adb shell am instrument -w com.xiangan.watch4manager.test/com.xiangan.watch4manager.ManagerSmokeTest
```

必须核对输出中的 `Android smoke checks: 19/19 passed`，不能只依赖 ADB 进程退出码。额外的真实 Root / 蓝牙联调开关见 [验证记录](docs/verification.md)。

协议层在 `protocol/`；Android 界面、前台服务、权限读取、加密保存和日志在 `app/`。本地配置、研究缓存、配对信息、签名私钥与构建目录不进入源码归档。GitHub Actions 使用相同脚本执行协议测试、构建与 Lint，仅保存验证报告。版本分发由维护者使用现有签名在本机打包，并通过 `scripts/publish.ps1` 校验候选提交与成功 CI、核对下载摘要后发布，见 [发布流程](docs/releasing.md)。

## 作者与维护

- 作者姓名：相安
- 作者邮箱：3199912548@qq.com
- 维护者姓名：相安
- 维护者邮箱：3199912548@qq.com

使用问题可提交 [Issue](https://github.com/xiangan666/Watch4QuickApps/issues)，安全问题请按 [安全反馈](SECURITY.md) 联系维护者。开发约定见 [贡献说明](CONTRIBUTING.md)。

原作代码公开可见，尚未指定开放许可证，权利状态见 [版权说明](COPYRIGHT.md)；依赖的许可证与来源见 [第三方声明](THIRD_PARTY_NOTICES.md)，历史变更见 [CHANGELOG](CHANGELOG.md)。
