# 研究与证据记录

查阅及核对日期：2026-11-16。网页、注释与反编译结果作为资料，最终以协议应答和真机观察确认行为。

| 来源 | 核对内容 |
| --- | --- |
| 用户提供的米坛 Watch 4 板块：`https://www.bandbbs.cn/forums/242/` | 设备范围与社区实践 |
| Watch 4 Debug 安装讨论：`https://www.bandbbs.cn/threads/9893/` | 蓝牙安装与签名存在性的要求；未采用表盘自定义工具 |
| Gadgetbridge 的 Codeberg 上游：`https://codeberg.org/Freeyourgadget/Gadgetbridge` | SPP V1 帧、鉴权、RPK 指令、mass 分片与 CRC，区分 Watch 5 的 V2 |
| Shizuku API：`https://github.com/RikkaApps/Shizuku-API` | UserService 的 Root/shell 身份边界及 MIT 许可证 |
| 本机运动健康已安装 APK（3.61.0） | 安装预检参数与状态码处理，样本只保存在忽略的研究缓存中 |

## E1：蓝牙权限误报

修复前实测：`BLUETOOTH_CONNECT=0`（已授予），旧版 `BLUETOOTH=-1`（未声明到当前系统），实际 `BluetoothSocket.connect()` 抛出 `lacks permission android.permission.BLUETOOTH`。修复 Manifest 后旧版普通权限自动授予，同一调用成功。没有通过关闭安全检查或使用 Root 绕过运行时附近设备授权。

## E2：安装预检字段

Gadgetbridge 的 `wear_thirdparty_app.proto` 把 `RpkInfo` 字段 2 暂称为 `unknown2`，注释为 0；但实际 `XiaomiRpkService.installRpk()` 填入 `getVersionCode()`。本机运动健康 `DeviceModelExtKt.prepareInstallApp()` 同样发送包名、版本和大小。管理器保留版本字段，未仅凭注释把它改成 0。

## E3：状态码 3

本机客户端 `AppInstallManager.InstallTask.prepareInstall()` 的错误回调将 3 映射到 `bwk.H`。该资源 ID 为十进制 `2132095184` / 十六进制 `0x7f1530d0`，对应 `xms_thirdapp_low_storage`，英文内容为 `Storage is full. Can't download.`，中文资源说明设备内存已满、无法下载 App。

这一映射支持提示「设备报告存储空间不足」，不提供真实剩余容量数值，也不证明每个固件上所有状态码的含义相同。管理器不会在此阶段发送 RPK 内容或把它归因为签名校验失败。

## E4：拒绝不应关闭会话

旧版 `WatchService.submit()` 对所有异常调用 `failed(..., true)`，即使预检明确拒绝也会主动关闭传输。当前通过 `DeviceRejectedException` 区分设备正常拒绝与连接故障。回归用例核对拒绝后仍能列出应用，且未发起 mass 传输。
