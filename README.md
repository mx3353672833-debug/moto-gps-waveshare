# MOTO GPS · Waveshare Edition

![MOTO GPS](assets/brand/moto-gps-wordmark.png)

**作者：Maler X · 署名 / 非商业使用 · 实验性样机**

一块装在车把上的圆屏，一个放在包里的 iPhone。手机负责定位、搜索与路线计算，
圆屏通过蓝牙显示简洁导航、速度、相对航向，并遥控 Apple Music。

项目基于 **Waveshare ESP32-S3-Touch-AMOLED-1.75C**，
包含圆屏固件、iOS App、路线网关和网页调试工具。

> 当前处于开发验证阶段。蓝牙掉线、后台重连、跨城路线选项和路线一致性仍在排查。
> 调试和操作请停车后进行，行驶时请结合成熟导航软件核对路线。

## 目前有什么

| 模块 | 功能 |
| --- | --- |
| 圆屏导航 | 白色路线、下一动作图标与距离；有可信限速数据时显示限速牌 |
| iPhone | 位置偏置搜索、搜索历史、路线预览与候选选择 |
| 导航逻辑 | 共享 C++ 核心，路线进度、偏航检测、联网重算与周期路线/路况刷新 |
| 灰色道路 / 建筑 | iPhone 内置 OSM 济南离线矢量库，按当前位置裁剪后传给圆屏 |
| 马表 / 航向 | 手机定位提供速度与行驶方向，板载 QMI8658 辅助相对转向 |
| 音乐 | Apple Music 上一首、播放/暂停、下一首 |
| 动效 / 触摸 | 黑白 Logo 淡入淡出、连接状态过渡、滑动切页、指示点自动隐藏、PWR 长按关机 |
| 演示 | 济南大数据产业基地 D 栋附近 → 浪潮总部附近；在线请求与 OSM 离线回退明确区分 |

iPhone 提供网络和定位，圆屏通过 **BLE** 连接手机。后台连接可靠性仍在验证中。

## 硬件与开发环境

- Waveshare **ESP32-S3-Touch-AMOLED-1.75C**，466×466 AMOLED / CO5300 / CST9217，32 MB Flash、8 MB PSRAM。
- 支持数据传输的 USB-C 线；使用电池时按微雪对该型号的电池规格及极性要求选择。
- iOS 17+ iPhone；Mac、Xcode、XcodeGen 用于自行签名安装。
- 固件基线 ESP-IDF **5.5.5**，LVGL 固定为 **9.5.0 对应提交**。
- 网关 Node.js 20+；离线地图生成/校验建议 Node.js 24+；原生测试需要 CMake 和 C++17。

刷写前请核对板型为 **ESP32-S3-Touch-AMOLED-1.75C**。
真实导航需要配置自己的高德 Key、HTTPS 网关和 Apple 开发者签名。

## 开始使用

```sh
git clone --recurse-submodules https://github.com/mx3353672833-debug/moto-gps-waveshare.git
cd moto-gps-waveshare
```

上述命令会同时获取固定版本的 LVGL。使用 GitHub 的 Download ZIP 时，
还需将 LVGL 提交 `85aa60d18b3d5e5588d7b247abf90198f07c8a63` 的源码放入 `third_party/lvgl/`。

1. [配置自己的路线网关](backend/README.md)：先用 fixture 测试，再配置自己的高德 Web 服务 Key 和 HTTPS。
2. [构建并安装 iOS App](platforms/ios/README.md)：设置网关、自己的 Bundle ID 和签名团队。
3. [构建并刷入圆屏固件](platforms/esp32/README.md)：先备份原厂 Flash，再刷写正确型号。
4. 打开 App，允许定位和蓝牙、完成配对；选目的地 → 查看/选择路线 → 开始导航。

查看和调试界面可用 [Web 调试工具](platforms/web/shell/README.md)，使用时保持网页前台亮屏。
手机后台定位和 BLE 连接由 iOS App 负责，当前验证进度见[已知问题](docs/KNOWN_ISSUES.md)。

App 网关默认填写示例地址 `https://example.invalid/moto-gps/api/`。
启用真实搜索和导航前，请替换为自己的 HTTPS 服务地址，并完成一次搜索和路线请求验证。

## 源码结构

```text
platforms/ios       iPhone 定位、路线预览、BLE、Apple Music、离线场景查询
backend            Node.js 高德 Web 服务网关（Key 只在这里）
platforms/esp32     Waveshare 板级适配、BLE、QMI8658、显示与电源
shared             共享导航核心、协议、LVGL UI、OSM 演示及地图数据
platforms/web      LVGL / Wasm 调试外壳
tests              C++ 原生测试
scripts            Web 构建、字体与 OSM 地图工具
```

详见 [架构与数据边界](docs/ARCHITECTURE.md)、[测试方式](docs/TESTING.md)、
[已知问题](docs/KNOWN_ISSUES.md)、[第三方许可证](THIRD_PARTY_NOTICES.md)。

## 使用许可与署名

项目原创代码和设计素材使用 **[PolyForm Noncommercial 1.0.0](LICENSE.md)**。
允许许可范围内的个人学习、研究、非商业使用和修改；分发源码、修改版、固件或 App
时必须携带许可证及 [NOTICE](NOTICE) 中的作者声明，注明 **Maler X** 和本仓库来源。
App 待机界面和 Web 状态页也提供可见署名入口。

**仅限非商业使用。** 销售预装设备、收费分发固件、用于商业产品或收费服务等用途，
须另行联系作者取得商业授权。具体授权范围以 LICENSE.md 正文为准。

授权类型为 **source-available（公开源码、非商业授权）**。
第三方库、字体和 OSM 数据分别遵循原许可证；地图服务须按服务商协议使用。

© 2026 Maler X · 地图数据 © OpenStreetMap contributors（ODbL 1.0）。
