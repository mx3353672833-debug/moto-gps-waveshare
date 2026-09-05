# MOTO GPS · Waveshare Edition

![MOTO GPS](assets/brand/moto-gps-wordmark.png)

**作者：Maler X · 署名 / 非商业使用 · 实验性样机**

一块装在车把上的圆屏，一个放在包里的 iPhone。手机负责定位、搜索与路线计算，
圆屏通过蓝牙显示简洁导航、速度、相对航向，并遥控 Apple Music。

本仓库只发布 **Waveshare ESP32-S3-Touch-AMOLED-1.75C 成品开发板版本**：
包含固件、iOS App、路线网关和网页调试工具。不包含自研 PCB、嘉立创生产文件、
外壳或佳明卡口设计，也不是 CarPlay 协议实现。

> 这是开发快照，不是成熟车载导航产品。离家后蓝牙掉线、后台重连、跨城路线选项
> 和路线一致性仍需排查。不要把它作为道路行驶的唯一导航依据；调试和操作请停车后进行。

## 目前有什么

| 模块 | 当前实现与边界 |
| --- | --- |
| 圆屏导航 | 白色路线、下一动作图标与距离、可信限速提示；无数据时不虚构限速 |
| iPhone | 位置偏置搜索、搜索历史、路线预览与候选选择；受服务响应及已知问题影响 |
| 导航逻辑 | 共享 C++ 核心，路线进度、偏航检测、联网重算与周期路线/路况刷新 |
| 灰色道路 / 建筑 | iPhone 自带 OSM 济南离线矢量库，按当前位置裁剪后传给圆屏；不是全国离线导航 |
| 马表 / 航向 | 速度来自手机定位；板载 QMI8658 辅助相对转向，不等同于带磁力计的真指南针 |
| 音乐 | Apple Music 上一首、播放/暂停、下一首；不包含网易云或“喜欢”承诺 |
| 动效 / 触摸 | 黑白 Logo 淡入淡出、连接状态过渡、滑动切页、指示点自动隐藏、PWR 长按关机 |
| 演示 | 济南大数据产业基地 D 栋附近 → 浪潮总部附近；在线请求与 OSM 离线回退明确区分 |

手机通过自己的网络访问网关，圆屏通过 **BLE** 连接手机；该版本不依赖家里的 Wi-Fi，
不需要给圆屏另接 GNSS。但当前后台连接可靠性还没验收通过。

## 硬件与开发环境

- Waveshare **ESP32-S3-Touch-AMOLED-1.75C**，466×466 AMOLED / CO5300 / CST9217，32 MB Flash、8 MB PSRAM。
- 支持数据传输的 USB-C 线；使用电池时按微雪对该型号的电池规格及极性要求选择。
- iOS 17+ iPhone；Mac、Xcode、XcodeGen 用于自行签名安装。
- 固件基线 ESP-IDF **5.5.5**，LVGL 固定为 **9.5.0 对应提交**。
- 网关 Node.js 20+；离线地图生成/校验建议 Node.js 24+；原生测试需要 CMake 和 C++17。

不要将本固件直接刷入相似的 1.75、1.75B、1.75D、C3 或其他圆屏板。
本仓库不提供作者的高德 Key、服务器或 Apple 开发者签名。

## 开始使用

```sh
git clone --recurse-submodules https://github.com/mx3353672833-debug/moto-gps-waveshare.git
cd moto-gps-waveshare
```

请优先使用上述 clone 命令。GitHub 的 Download ZIP 不包含 LVGL 子模块，单独解压后
不能直接编译；不要用任意最新版 LVGL 替代这里固定的提交。

1. [配置自己的路线网关](backend/README.md)：先用 fixture 测试，再配置自己的高德 Web 服务 Key 和 HTTPS。
2. [构建并安装 iOS App](platforms/ios/README.md)：设置网关、自己的 Bundle ID 和签名团队。
3. [构建并刷入圆屏固件](platforms/esp32/README.md)：先备份原厂 Flash，再刷写正确型号。
4. 打开 App，允许定位和蓝牙、完成配对；选目的地 → 查看/选择路线 → 开始导航。

只想看界面，可先用 [Web 调试工具](platforms/web/shell/README.md)。网页不是 iPhone
后台 BLE 伴侣 App 的替代品，锁屏后不保证持续定位。

默认 App 网关是 `https://example.invalid/moto-gps/api/`，故意不可联网。
**没有配置自己的服务时，不能用真实搜索/导航；演示回退不代表真实导航已配置完成。**

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

**本许可不授权商业使用**，例如销售预装本项目的设备、收费分发固件，或将其用于
商业产品/收费服务。需要商业授权，请在本仓库联系作者；不能仅删除 Logo 或署名来规避。
具体授权范围以 LICENSE.md 正文为准。

由于限制商用，这属于 **source-available（公开源码、非商业授权）**，不是 OSI
定义的开放源代码许可证。第三方库、字体、OSM 数据分别保留原许可证，项目的
非商业限制不覆盖它们；数据来源与地图服务使用权限也不会随本仓库许可自动授予。

© 2026 Maler X · 地图数据 © OpenStreetMap contributors（ODbL 1.0）。
