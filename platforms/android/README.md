> **语言 / Language:** 中文 · [English](README.en.md)

# Android 伴侣 App

原生 Kotlin + Jetpack Compose 工程，与 iPhone App 连接同一块微雪
**ESP32-S3-Touch-AMOLED-1.75C** 圆屏，使用现有固件，不需要重刷安卓专用固件。

流程与 iOS 一致：搜索目的地 → 比较最多三条高德驾车路线 → 开始导航，路口指引、速度、
周边道路与建筑通过 BLE 同步到圆屏。网关、蓝牙协议和导航状态机与 iOS 共用同一套实现。

## 与 iOS 共用的部分

| 模块 | 安卓实现 |
| --- | --- |
| 导航状态机（路线匹配、偏航重算、到达判定、路况刷新） | `shared/nav_core`、`shared/nav_app`，经 NDK + JNI 编进 APK |
| BLE v1 编解码、分片、CRC、重组 | `shared/ble_protocol`，经 JNI 调用；Kotlin 不重写任何字节布局 |
| 黄金字节 | `shared/protocol/fixtures/ble-navigation-v1.golden.txt`，构建时复制进 APK，调试版握手前自检 |
| 济南演示路线、内置离线地图 | `shared/demo_fixture`、`shared/offline_map/jinan-v1.sqlite`，构建时复制 |
| 网关接口 | `/healthz`、`/v1/places`、`/v1/route-options`（旧网关回退 `/v1/routes`）、`/v1/map/cities`、`/v1/map/tiles` |

JNI 适配层在 `app/src/main/cpp/moto_jni.cpp`。握手、发送节流、心跳、ACK、命令去重、
5 Hz 快照合并、地图 ACK 等策略按 `ESP32BLECentral.swift` 逐项移植到
`ble/BleProtocolEngine.kt`；GATT 连接部分在 `ble/Esp32BleCentral.kt`。

## 安卓特有的处理

- **不依赖 Google Play 服务**：导航定位直接使用系统 `LocationManager` 的 GPS（WGS84），
  搜索排序用网络/GPS 单次定位，适合没有 GMS 的国内手机。
- **坐标边界**：定位 WGS84 → 网关请求 WGS84 → 路线几何 GCJ-02 → 圆屏 GCJ-02；
  只有手机路线全览绘制和 Web Mercator 分块编号时做一次 GCJ-02 ↔ WGS84 转换。
- **配对**：首次连接会弹出系统配对请求（LE Secure Connections，Just Works），点“配对”即可。
  固件特征值要求加密，App 不会绕过加密。配对异常时在系统蓝牙设置中删除 MOTO GPS，
  并在圆屏上清除配对后重试；圆屏页面也提供“忘记这块圆屏并重新搜索”。
- **MTU**：连接后请求 517，按协商结果 `min(MTU-3, 512)` 分帧，ATT MTU 23 时同样可用。
- **锁屏导航**：点“开始导航”后启动 `location|connectedDevice` 前台服务，通知栏显示剩余
  距离与“结束导航”按钮，并在导航期间持有有上限的 partial wake lock；结束导航即释放。
  不需要“始终允许”后台定位。部分国产系统的省电策略仍可能限制后台，必要时把 MOTO GPS
  设为“不限制后台/允许自启动”。
- **音乐**：授予“通知使用权”后，通过系统媒体会话读取正在播放的曲目，并执行圆屏上的上一首、
  播放/暂停、下一首。支持网易云音乐、QQ 音乐、Spotify 等提供媒体会话的播放器；
  “喜欢”不可用（协议中标记为不可用，圆屏会隐藏）。不读取通知内容，不使用无障碍服务。
- **手机路线全览**：本地绘制路线、起终点和比例尺，不接入第三方地图 SDK，也不需要地图 Key。

## 构建

需要 JDK 17+（本机用 Homebrew `openjdk@21`）和 Android SDK。工具链版本：

| 组件 | 版本 |
| --- | --- |
| Gradle Wrapper | 8.14.3 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26（Android 8.0） |
| NDK | 28.2.13676358（默认 16 KB 页对齐） |
| CMake | 3.22.1 |
| ABI | arm64-v8a、armeabi-v7a、x86_64 |

```sh
cd platforms/android
./gradlew :app:assembleDebug
```

调试包输出在 `app/build/outputs/apk/debug/app-debug.apk`（包名带 `.debug` 后缀，可与正式包共存）。

可选：私人构建可预置网关地址，公开发布的包请保持为空，让使用者在 App 内填写：

```sh
./gradlew :app:assembleRelease -PmotoGatewayBaseUrl=https://你的网关/moto-gps/api/
```

### 正式签名

签名材料不进仓库。准备一个本地 `keystore.properties`（`storeFile`、`storePassword`、
`keyAlias`、`keyPassword`），再用环境变量指向它：

```sh
MOTO_KEYSTORE_PROPERTIES=/path/to/keystore.properties ./gradlew :app:assembleRelease
```

之后的更新必须用同一把密钥签名，否则手机会拒绝覆盖安装，请备份密钥文件和密码。

## 测试

```sh
./gradlew :app:testDebugUnitTest          # JVM 单元测试
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest  # 需要已连接的手机或模拟器
```

- 单元测试：网关地址规则、网关请求/回退/错误、分块规划（含济南—青岛走廊）、
  离线地图裁剪与容量、地图缓存（共享请求、续传、删除、损坏恢复）、握手/写入节流/地图 ACK、
  路线候选与路况文案、演示路线。用例移植自 iOS 测试，并读取同一批共享夹具。
- 设备测试：JNI 黄金字节（导航快照、路线几何、连接帧、20 字节分片命令、CRC 错误）、
  共享 NavCore 完整一程（请求路线、行进、到达）、协议引擎对**模拟圆屏**的完整联调
  （模拟端使用与固件相同的共享 C++ 编解码：握手、快照、媒体、命令 ACK 与重复 ACK、
  地图 ACK、心跳、静默断线恢复、能力不足拒绝、MTU 20），以及离线界面冒烟测试（演示导航全程）。

## 当前状态

| 项目 | 状态 |
| --- | --- |
| 构建、单元测试、lint | 通过 |
| 模拟器（Android 16 / API 36）设备测试与演示导航 | 通过 |
| 与模拟圆屏的协议联调 | 通过 |
| 真实安卓手机 + 真实圆屏的配对、导航与锁屏 | **尚未验证** |
| 真实道路骑行、续航 | **尚未验证** |

模拟器没有蓝牙硬件，所以 GATT 扫描、配对、MTU 协商只能在真机上确认。第一次真机测试建议
停车或步行进行，记录手机型号、系统版本和固件提交。

与 iOS 相同：普通驾车路线不保证避开摩托车禁限行；没有可信道路限速与红绿灯读秒；离线包
只有道路/建筑背景，搜索与新路线仍需要网络。

这是社区 Android 移植，沿用仓库的 [PolyForm Noncommercial 1.0.0](../../LICENSE.md) 许可、
[NOTICE](../../NOTICE) 与 [第三方说明](../../THIRD_PARTY_NOTICES.md)。
