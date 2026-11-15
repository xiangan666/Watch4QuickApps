# 验证记录

日期：2026-11-16，时区 Asia/Shanghai。本记录区分自动检查、实际设备观察和未覆盖范围。

## 已执行

- 协议单元与双向流会话测试：17 项。覆盖 HKDF、独立加密向量、消息认证失败、帧碎片/截断、protobuf、分片 CRC、握手、列表、安装与卸载、拒绝后保持鉴权和继续读取列表。
- Android 本机测试：19 项。覆盖 RPK 元信息与边界、Keystore、旧蓝牙兼容权限、权限诊断、日志开关、持久化、UTF-8 导出、脱敏、异常链和记录上限。
- APK 与 Android 测试 APK 构建；Android Lint 无错误。保留 3 条警告：已有可用依赖/Gradle 有新版本，以及一处字符串本地化建议，未关闭检查。
- Android 12 / MIUI 真机确认 `BLUETOOTH_CONNECT` 已授权；修复前的连接调用明确抛出缺少旧 `BLUETOOTH` 权限，修复后 RFCOMM 连接、SPP V1 鉴权和读取列表成功。
- Root 固定读取从运动健康得到一条 Watch 4 配对记录，未向电脑输出密钥。
- 0.1.2 本机日志记录到预检拒绝后保留连接，以及随后象棋 RPK 安装确认；使用者明确确认可以安装。

## 命令

PowerShell，项目根目录：

```powershell
.\scripts\build.ps1
adb install -r '.\app\build\outputs\apk\debug\app-debug.apk'
adb install -r '.\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
adb shell am instrument -w com.xiangan.watch4manager.test/com.xiangan.watch4manager.ManagerSmokeTest
```

Runner 使用 Android Instrumentation 原生返回格式，ADB 的进程退出码不充分；基础验证应包含 `Android smoke checks: 19/19 passed`，失败会输出 `Failed after ...`。

仅在安排实际设备联调时启用额外选项：

| 参数 | 行为与前置条件 |
| --- | --- |
| `-e root true` | 调用固定只读 Root 诊断与导入，要求手机已授权本应用；只输出记录数与数据库可读状态 |
| `-e bluetooth true` | 检查蓝牙、当前已保存设备的配对状态和 RFCOMM 套接字创建 |
| `-e bluetooth true -e connect true` | 实际建立并关闭一次 RFCOMM 连接，不执行安装 |
| `-e service true` | 执行管理器实际连接与鉴权、读取列表，会开启详细日志 |
| `-e service true -e install true` | 会安装私有测试缓存中固定的象棋包，属于有状态联调，不应作为日常基础测试自动运行 |

## 边界

蓝牙联调期间也观察到过短时 `read failed ... -1` 和 `Connection reset by peer`，详细日志保留了阶段与异常链，不能据单次成功宣称所有连接环境稳定。

本轮确认了导出内容生成、UTF-8 编码、持久化和脱敏；系统保存器的提供方界面由手机系统控制，不将这些自动测试写成完整的跨提供方 UI 验收。Shizuku Root 用户服务分支尚未在这台手机独立实测；普通 ADB 模式无法读取私有库的限制已在界面说明。手表上的棋盘触控、存档和长时间运行不属于管理器本轮自动测试范围。

本地 APK 使用调试证书；没有声称通过应用商店认证或生产签名发布。源码归档不包括本机配置、APK 研究样本、用户日志、配对数据库或密钥。
