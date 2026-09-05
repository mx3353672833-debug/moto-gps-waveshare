# iPhone 伴侣 App

iOS 17+；源码工程由 XcodeGen 生成。Swift Package 使用 tools-version 6.0，
需要能提供 Swift 6 工具链的 Xcode（建议 Xcode 16 或更新）。
本仓库不包含作者的 Apple Team、证书、设备标识或可直接安装的签名 IPA。

## 设置并生成工程

1. 先部署自己的 [HTTPS 网关](../../backend/README.md)。
2. 在 `project.yml` 修改 `MOTOGPSGatewayBaseURL` 为自己的 HTTPS 地址，保留结尾 `/`。
   默认 `https://example.invalid/moto-gps/api/` 不可用，不能用它验收真实搜索/导航。
3. 将三个 `PRODUCT_BUNDLE_IDENTIFIER` 改为自己可用且唯一的标识，并设置自己的
   `DEVELOPMENT_TEAM`（也可以生成后在 Xcode 的 Signing & Capabilities 里选择）。
4. 安装 XcodeGen，在此目录执行：

```sh
xcodegen generate
open MotoGPS.xcodeproj
```

`project.yml` 是配置来源，生成时会更新 `App/Info.plist`；只改 plist 后重新生成会被覆盖。
上面的 `open` 只由使用者在准备安装时执行；自动构建无需启动 Xcode 图形界面。

## 安装自己的手机

用数据线连接并信任 iPhone，按 Xcode 提示启用开发者模式，选择 MotoGPS scheme 和
自己的设备，检查签名后点击 Run。个人免费签名的能力和有效期受 Apple 规则限制，
到期需重签；本项目不会绕过签名，也不承诺所有后台能力在所有账号下均可用。

首次运行允许定位、精确位置、蓝牙；后台骑行需要相应定位权限和系统设置。
配对圆屏后应从连接中进入已连接/准备出发，再搜索、查看路线、选择候选并开始导航。
锁屏和断线重连仍有已知问题，请先在安全静止环境测试。

Apple Music 功能需用户授权，并按系统播放器状态工作。只支持上一首、播放/暂停、
下一首；歌曲可用性取决于账号、音乐来源与系统状态，不包含“喜欢”或网易云适配。
程序不下载或随仓库分发歌曲。

## 开发测试

在仓库根目录：

```sh
swift test --package-path platforms/ios
```

在本目录，生成工程后可以进行不签名、不安装的模拟器构建：

```sh
xcodebuild -project MotoGPS.xcodeproj -scheme MotoGPS \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath DerivedData CODE_SIGNING_ALLOWED=NO build
```

模拟器不能验收真实 BLE 外设、手机锁屏持续定位或音乐授权链路。
`UITests` 包含演示/网络相关流程，需要按测试环境配置；普通离线 CI 不运行这些现场流程。
App 内置 OSM 济南数据库，地图数据署名和完整许可证见根目录第三方说明。
