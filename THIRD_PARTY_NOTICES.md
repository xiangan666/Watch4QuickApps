# 第三方声明

原作权利状态见 [COPYRIGHT.md](COPYRIGHT.md)。下列第三方组件保留各自的作者与许可证；它们不因项目署名而改变权利归属。

| 组件 | 当前版本 / 范围 | 许可 |
| --- | --- | --- |
| Shizuku API 与 Provider | 13.1.5；运行时依赖；Copyright (c) 2021 RikkaW | MIT；[完整文本](app/src/main/assets/third_party/shizuku-MIT.txt) |
| AndroidX Annotation | 1.3.0；编译及运行依赖 | Apache-2.0；[完整文本](app/src/main/assets/third_party/Apache-2.0.txt) |
| Gradle Wrapper | 8.14.1；构建工具 | Apache-2.0；保留 Wrapper 文件中的上游声明及完整许可文本 |
| JUnit | 4.13.2；仅测试依赖，不打入管理器 APK | EPL-1.0；从 Maven Central 按锁文件获取 |
| Hamcrest Core | 1.3；JUnit 的测试依赖，不打入管理器 APK | BSD-3-Clause；从 Maven Central 按锁文件获取 |

APK 的 `assets/third_party/` 内包含运行依赖的完整 MIT 与 Apache-2.0 许可文本。源码包包含构建清单、锁文件与依赖完整性校验元数据，不打包依赖缓存或测试库二进制。

协议研究参考 Gadgetbridge（AGPL-3.0-or-later）的公开协议 schema、SPP V1、鉴权与 RPK 服务，以及本机运动健康客户端的行为。独立实现协议事实，不分发研究样本、反编译产物或上游实现源码。Wearable-Debug 社区项目仅作为兼容性和安装原理参考，没有将其未明确许可的代码复制到项目。

参考来源与证据位置见 [研究记录](docs/research.md)。
