> **语言 / Language:** 中文 · [English](README.en.md)

# iPhone 伴侣 App

面向首次安装的逐步图形界面操作见[DIY 教程：安装到 iPhone](../../docs/WAVESHARE_DIY_GUIDE.md#4-把-app-安装到-iphone)。
日常功能见[使用说明书](../../docs/USER_MANUAL.md)，自建服务见[网关教程](../../docs/GATEWAY_SETUP.md)。

iOS 17+；源码工程由 XcodeGen 生成。Swift Package 使用 tools-version 6.0，
需要能提供 Swift 6 工具链的 Xcode（建议 Xcode 16 或更新）。
本仓库不包含作者的 Apple Team、证书、设备标识或可直接安装的签名 IPA。

## 当前体验与发布状态

截至 2026-09-16，原生界面使用 `NavigationStack`、系统列表与表单组织“出发 → 路线 → 导航中”
流程；首页集中展示搜索、最近地点、圆屏连接、地图下载、演示和隐私入口。可预览最多三条
高德普通驾车路线，再明确选择并开始导航。普通驾车路线不保证避开摩托车禁限行。

- 周边道路与建筑默认在线加载，通过 BLE 发送到配套微雪圆屏；换城市无需改程序。
- 首页“地图与离线下载”可搜索城市/区县并预览下载范围，路线页可下载沿途约一公里地图。
  正在导航时使用当前活动路线，包括已接受的偏航重算结果。
- 断网优先使用下载包和缓存，保留内置济南基础地图。离线包只有道路/建筑背景，搜索、
  新路线、偏航重算与实时路况仍需要网络；数据完整程度取决于当地 OSM 覆盖。
- 当前未接通可信道路限速或红绿灯读秒；完整分段红黄绿路况路线仍在完善。

TestFlight 发布准备已开展，但**尚未上传 Apple、提交外测审核或开放邀请，也未在 App Store 发布**。
Release 归档、单测通过不等于公开发布或完成实车验收。自研 B1 主板暂缓，优先完善微雪版本。

## 设置并生成工程

1. 先部署自己的 [HTTPS 网关](../../backend/README.md)。
   新地图接口依赖服务端安装依赖和配置地图源；仓库与官网不提供免费公共网关。
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
日常使用 BLE，不要求手机与圆屏处于同一个 Wi-Fi。锁屏与断线重连仍需实测，
请先在安全静止环境验证；不要把演示中的模拟移动当作真实骑行验收。

Apple Music 功能需用户授权，并按系统播放器状态工作。只支持上一首、播放/暂停、
下一首；歌曲可用性取决于账号、音乐来源与系统状态，不包含“喜欢”或网易云适配。
程序不下载或随仓库分发歌曲。

## 地图存储与下载

`MapTilePlanner` 按 WGS84 Web Mercator 分块；路线 GCJ-02 坐标先逆变换再选择分块，
网关返回的 `points_e6` 已是 GCJ-02，不应再次转换。`SurroundingMapStore` 负责在线请求、
磁盘缓存与下载包；地图仍经过原有 BLE 窗口筛选，不代表圆屏绘制每一条道路或每栋建筑。

自动缓存上限 128 MiB，手动下载合计上限 512 MiB，城市和路线共享分块。城市范围为行政区域
外接矩形，可能包含邻区；过大范围需选择区县。下载需要 App 保持运行，可暂停、重启后继续
和删除，不能承诺后台下载持续运行。“清理自动缓存”保留手动下载包，内置济南包随 App 保留。
详细用户步骤见[使用说明书](../../docs/USER_MANUAL.md#10-在线周边地图与离线下载)。

## 隐私与分发前检查

`App/PrivacyInfo.xcprivacy` 声明当前使用的 required-reason API 与数据类别，首页
`DataUseView` 提供无需连接即可阅读的“隐私与数据”说明。地图下载目录排除系统备份；
最近地点和已保存外设标识等本地设置可能随系统备份保留。“断开”不等于删除外设记录。

位置和搜索经网关发送到高德，在线瓦片请求可透露区域；代理日志可能保留 IP、查询词及位置
参数。手机路线预览使用 MapKit，音乐使用系统播放器，周边背景来自 OSM / Protomaps。
不能将无广告、无账号登录写成零采集。更换网关后，应同步修改 `DataUseView` 中的服务说明，
按真实部署核对日志与保留策略，并复核隐私清单和 App Store Connect 的隐私回答。

公开分发前还需完成有效分发签名、发布主体与联系方式、正式 HTTPS 隐私/支持页面、服务
适用授权及真机验收。仓库中的说明和归档准备不代表这些外部步骤已完成，也不能公开占位资料。

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
新增地图测试覆盖分块规划、下载持久化、缓存、坐标边界及地图到 BLE 的编码；这些不能替代
实车连续行驶、长时间锁屏、弱网或跨城全量下载验收。App 保留 OSM 济南基础库并使用
在线 OSM / Protomaps 数据，地图署名和完整许可证见根目录第三方说明。
