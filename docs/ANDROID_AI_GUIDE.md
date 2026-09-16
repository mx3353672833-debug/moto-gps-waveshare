> **语言 / Language:** 中文 · [English](ANDROID_AI_GUIDE.en.md)

# 用 AI 开发 Glimpse 安卓版

想用安卓手机连接这块圆屏，可以从现有源码开始做。目前仓库还没有可安装的安卓 App，
这份指南提供开发路线和可直接交给 AI 编程工具的提示词，不是下载入口。

目标是做一个能与现有微雪固件通信的原生 Android App：手机定位、搜索和规划路线，
通过蓝牙把导航画面所需的数据发到圆屏。现有 iOS App 可以作为行为参考。

## 一起开发安卓版本

欢迎参与 Glimpse 项目共创。如果你用这份指南做出了安卓 App，希望你愿意把源码也放到 GitHub，
让其他安卓用户可以编译、使用和继续改进。哪怕只完成了蓝牙连接、一个界面或某款手机的适配，
也欢迎分享进展，不必等到全部功能做完。

可以先在 [Issues](https://github.com/mx3353672833-debug/moto-gps-waveshare/issues) 说说准备做什么，
或贴出你的公开仓库；之后通过 Pull Request 把功能、修复和文档贡献回来。
分享时请附上构建步骤、测试机型、对应固件和已知问题，方便别人接着开发。
我们会保留实际贡献者的署名，也欢迎愿意长期参与的人一起维护安卓版本。

这是共创邀请，是否参与由你决定；现有许可证和第三方许可仍需遵守。
贡献要求见 [参与开发](../CONTRIBUTING.md)。

## 先准备什么

- 一台能运行 Android Studio 的 Windows、macOS 或 Linux 电脑，以及能读取、修改项目文件的 AI 编程工具。
- 一部支持 BLE 的安卓手机。模拟器可检查界面和部分逻辑，蓝牙、锁屏导航要用真机验证。
- 微雪 **ESP32-S3-Touch-AMOLED-1.75C**，安装与仓库版本匹配的固件。
  板型、备份及固件说明见 [DIY 教程](WAVESHARE_DIY_GUIDE.md)；其中 iPhone / Xcode 部分不适用于安卓。
- 自己部署的 [导航网关](GATEWAY_SETUP.md)。高德 Web 服务 Key 放在后端，不放进 APK。
  官网不是供任意第三方 App 使用的免费公共网关。

### 把项目放到自己的 GitHub

1. 打开 [项目仓库](https://github.com/mx3353672833-debug/moto-gps-waveshare)，点击 **Fork**，复制到自己的账号。
2. 从你的 Fork 页面复制仓库地址，用 `git clone --recurse-submodules 仓库地址` 下载。
   已经下载但缺少子模块时，在仓库根目录执行 `git submodule update --init --recursive`。
3. 在 AI 编程工具中打开整个仓库，把下面的提示词完整贴进去。只给一个网页链接时，AI 未必能访问全部源码。

Fork、提交改动和 Pull Request 的操作可参考 [GitHub 官方教程](https://docs.github.com/en/pull-requests/how-tos/work-with-forks/fork-a-repo)。

## 可直接复制给 AI 的提示词

下面的代码块可以整体复制。手机型号和开发环境不知道怎么填，可以先不填，让 AI 检查工程后再问。

```text
请在我打开的 Glimpse / MOTO GPS 项目中开发原生 Android 配套 App。
项目上游：https://github.com/mx3353672833-debug/moto-gps-waveshare
目标硬件：Waveshare ESP32-S3-Touch-AMOLED-1.75C，使用现有微雪固件。
我的安卓手机型号 / 系统版本：（填写；未填时需要真机测试再向我确认）
我的 GitHub Fork：（填写；已有 origin 时先核对，不要推送到上游）

请实际创建工程、实现代码并执行可运行的检查，不要只给我方案或一套静态页面。
按下面的阶段推进，每阶段给出已实现内容、执行过的测试、未验证项和下阶段任务。
若受硬件、权限、SDK 或密钥阻碍，说明具体缺少什么，先完成不依赖它的工作，不要伪造通过记录。

一、先读源码，确定真实接口
阅读仓库 AGENTS.md（如有）、README.md、LICENSE.md、NOTICE、THIRD_PARTY_NOTICES.md，
以及以下文件和目录：
- docs/ANDROID_AI_GUIDE.md、docs/GATEWAY_SETUP.md、backend/README.md
- shared/protocol/ble-navigation-v1.md 和 shared/protocol/fixtures/ble-navigation-v1.golden.txt
- shared/ble_protocol、shared/nav_core、shared/nav_app、shared/coordinates
- shared/protocol 下的 route-request、route-options、route-bundle、map-scene schema
- platforms/ios/App/Adapters/BLE、Navigation、Location、OfflineMap
- platforms/ios/App/AppModel.swift 与相关 AppTests
- platforms/esp32/main/phone_nav_bridge.cpp、ble_nav_transport_nimble.cpp
- tests/native、backend/test 和 .github/workflows/checks.yml
把文档与当前实现对照，标出不一致处；不要根据界面截图猜协议和字段。

二、工程与复用方式
在 platforms/android/ 新建 Kotlin + Jetpack Compose 工程，界面使用 Android 原生交互。
分离 UI、导航会话、定位、网关、BLE 和地图存储，不让 Activity 直接管理全部连接状态。
优先通过 NDK + CMake + JNI 复用 shared 的 C++ 导航核心、坐标转换和 BLE 编解码。
JNI 是新写的安卓适配层；SwiftUI、CoreBluetooth、CoreLocation、MapKit 和 Objective-C++
桥接代码不能直接搬到安卓。不要把 ESP32 的显示驱动或 LVGL UI 塞进手机导航引擎。
如需用 Kotlin 重写某个纯逻辑模块，先说明理由，并用原有黄金字节/夹具做一致性验证。
保留 iOS、ESP32 和后端现有功能，默认不修改圆屏协议或要求大家重刷专用安卓固件。
工具链采用官方支持的稳定组合，记录 JDK、Gradle、AGP、Kotlin、SDK、NDK、CMake 版本，
提交 Gradle Wrapper；minSdk 可先评估 API 26，最终按所选库和实际设备决定，不宣称全机型兼容。

三、先让手机连上圆屏
实现蓝牙可用性检查、按服务 UUID 扫描、明确选择设备、配对/加密、发现服务、订阅 TX Notify。
沿用现有 UUID、版本协商、能力交集和完整握手；等设备最终确认 Ready 后再发送业务数据。
遵守 MTU 协商结果，不假设永远是 512；协议帧包含头和 CRC，不能直接把 JSON 写到蓝牙特征。
GATT 操作串行排队，完整消息的分片不能交织；处理发送背压、CRC、序号、重组超时、应用 ACK、
重试上限、断连清队列、重新握手和最新状态补发。GATT 写成功不等于应用 ACK。
心跳使用当前应用会话的已用时间，不传手机系统启动时长。
用协议黄金字节验证编解码，再用手机和真实圆屏验证连接状态、断连和恢复。
不得关闭固件的加密权限来绕过配对失败。

四、接入真实导航
实现搜索目的地、位置偏置、候选路线、时间/距离预览、开始与结束导航。
网关地址可配置，空配置时提示用户配置；沿用后端现有 HTTP 接口和 schema，不虚构接口。
定位接入按设备环境选择，不能假定所有国内安卓手机都有 Google Play 服务。
保留精度、时间戳、速度和行进方向；陈旧或精度差的位置要明确处理。
逐个边界标注 WGS84 与 GCJ-02：定位来源先确认坐标系，网关路线输入为 WGS84，
路线几何为 GCJ-02；不能重复转换，也不能直接把两种坐标叠画。
把位置与路线交给共享导航核心，发送快照、路线窗口和偏航/路况状态。
演示模式明确标记模拟；真实定位或网络失败时不能自动切到假位置、假路线。
不虚构红绿灯读秒、道路限速或摩托车禁限行能力，缺数据时按协议表达未知。

五、导航期间保持定位和蓝牙
查阅当前 Android 官方文档，按系统与 targetSdk 处理蓝牙扫描/连接、定位、通知和前台服务。
区分 BLE 扫描权限与导航定位权限；不要因扫描使用 neverForLocation 就删掉导航定位权限。
用户在前台点击“开始导航”后启动符合 location / connectedDevice 用途的前台服务，
显示持续通知和结束按钮；结束导航后释放定位、连接/服务和不必要的唤醒资源。
对确需后台位置授权的场景单独说明，不能把“永远允许定位”作为一刀切前提。
处理拒绝权限、只给模糊位置、蓝牙关闭、息屏、任务切换、进程回收和重连。
遵守后台启动限制，不承诺“绝不被杀后台”；用实机记录锁屏导航的可用范围和耗电。

六、再实现地图下载与可选音乐控制
复用现有地图/城市接口，实现默认在线周边地图、城市或区县下载、沿途下载、暂停继续、
删除和缓存预算。分块编号按 WGS84 Web Mercator 计算；响应几何为 GCJ-02，按 schema 读取。
保存数据来源、时间和署名，尊重 MapScene 数量/点数限制，优先保证路线正确与传输稳定。
离线包只包含背景道路和建筑，不把它说成完整离线路线规划。
手机预览地图选择可在目标设备使用的 Android 地图库/SDK，核对许可、Key 限制与坐标系。
音乐放在后续阶段：按 Android 支持的媒体会话接口和用户授予的权限评估能力；
不照搬 Apple Music iOS API，不假定任意音乐 App 都可被控制，也不使用无障碍权限绕过限制。

七、测试与交付
第一阶段先交付能构建、安装、连上圆屏并完成协议自检的版本；再加入真实导航，最后加入地图下载。
每个阶段更新 platforms/android/README.md 和开发进度，区分“已实现/自动测试通过/真机通过/尚未验证”。
覆盖黄金字节、坐标边界、JNI 生命周期、GATT 排队与重连、定位失效、过期响应、错误网关、
断网及下载恢复。保留上游 C++、后端和 Swift 核心测试通过，不破坏原有 CI。
Android CI 从干净检出安装固定工具链，拉取子模块并运行实际存在的 Gradle 构建、单元测试和 lint 任务。
模拟器 UI 检查和真实 BLE/锁屏测试分开记录；没有真机时不要标记硬件验收完成。
给我可复现的安装步骤、构建命令、测试结果、权限说明与当前限制。

八、把成果分享到 GitHub
在我的 Fork 中开发分支，保留上游署名、LICENSE、NOTICE、第三方与地图许可。
当前上游原创代码采用 PolyForm Noncommercial 1.0.0；不擅自把整仓改成 MIT/Apache 或删除非商业限制。
README 链接回上游并说明这是社区 Android 移植，不冒充已经获得上游官方支持的版本。
README 中说明愿意参与 Glimpse 共创，并保留实际贡献者署名；准备贡献说明和适合回馈上游的改动清单。
不要提交 .env、local.properties、keystore、签名密码、token、真实地址/轨迹和原始设备日志。
高德 Web 服务密钥留服务端；必须进入客户端的 SDK Key 按 SDK 规则限制用途，不当作可保密凭据。
通过测试后把改动提交并推送到我的 Fork，给出提交链接；可准备面向上游的 Pull Request。
用 GitHub Releases 发布经过测试的 APK，标注版本、源码提交、适配固件、已测机型、限制与 SHA-256。
对外 APK 使用维护者自己的稳定发布签名并备份密钥；把临时 debug 包明确标成测试用。
签名材料只放本地受保护位置或合适的 CI Secret，外部 PR 工作流不接触发布密钥。
没有完成真实测试时保持草稿/预发布状态，不宣传为完成的安卓正式版。

现在先检查仓库与开发环境，列出可复用接口和第一个阶段的具体改动，然后开始实现。
```

## 做到什么程度，可以给别人试用

| 阶段 | 要拿出的结果 |
| --- | --- |
| 工程与蓝牙 | 从干净检出构建并安装；真机完成配对、完整握手、协议黄金字节检查、断连恢复 |
| 真实导航 | 真实定位与网关选路，手机所选路线与圆屏一致；弱网、位置失效有明确状态 |
| 锁屏与地图 | 有持续通知及停止入口；记录锁屏测试时长；城市/沿途下载可中断恢复，离线边界写清楚 |
| 社区发布 | Fork 源码、可复现说明、CI 结果、签名 APK、SHA-256、实测机型与问题列表 |

不要以“APK 能安装”代替蓝牙和骑行验证。第一次实机检查可以停车或步行进行，记录系统版本、
固件提交和复现步骤；公开日志时去掉个人地址、轨迹和设备标识。

## 分享代码与许可证

欢迎 Fork、学习、DIY 和贡献改动。当前上游原创部分按
[PolyForm Noncommercial 1.0.0](../LICENSE.md) 公开，转载或发布衍生版本时保留
[NOTICE](../NOTICE) 和 [第三方说明](../THIRD_PARTY_NOTICES.md)。这是现有项目的许可条件，
本指南不把它改成允许任意商业用途的许可证。

初次分享建议发布标明测试范围的预发布版本，提供 APK、对应源码和校验值。
上传方式见 [GitHub Releases 官方说明](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)，
自动签名配置见 [GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。
愿意把改动带回主项目时，从你的开发分支创建 Pull Request，并附上协议兼容和实机验证结果。

## 让 AI 查阅的官方资料

以下资料于 2026-09-16 核对；实现时仍应按实际系统版本和 targetSdk 重新确认。

- [Android BLE 权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)：扫描与连接权限、旧系统兼容。
- [Android 后台 BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/background)：后台通信方式及限制。
- [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)：导航定位与设备连接对应的服务类型及前置条件。
- [NDK 入门](https://developer.android.com/ndk/guides)、[CMake 集成](https://developer.android.com/ndk/guides/cmake)：把已有 C++ 库接入 Android。

协议字节、坐标和网关字段以本仓库当前实现及测试为依据，不以 AI 记忆中的示例为依据。
