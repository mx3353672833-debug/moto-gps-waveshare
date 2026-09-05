# Third-party notices / 第三方材料

根目录 PolyForm Noncommercial 许可仅覆盖本项目原创部分。下列材料保持原许可，
不得解释为给这些材料增加非商业限制。使用它们时仍需遵循原作者的版权与署名要求。

| 材料 | 位置 / 来源 | 许可 |
| --- | --- | --- |
| LVGL | `third_party/lvgl` 子模块，提交 `85aa60d18b3d5e5588d7b247abf90198f07c8a63` | MIT；见子模块 `LICENCE.txt` 及 `LICENSES/LVGL-MIT.txt` |
| Waveshare BSP / CO5300 初始化 | Component Registry `waveshare/esp32_s3_touch_amoled_1_75c` 3.0.0；板级派生文件 `platforms/esp32/main/board_port_waveshare_1_75c.cpp` | Apache-2.0；见 `LICENSES/Waveshare-Apache-2.0.txt`，该派生文件保留 Apache-2.0 |
| Source Han Sans SC 字体子集 | `shared/nav_ui/assets/moto_font_nav_16.c`，生成自 LVGL 工具目录的字体 | SIL OFL 1.1；见 `LICENSES/SourceHanSansSC-OFL.txt` |
| Montserrat 内置字体 | LVGL 的内置字体资源 | SIL OFL 1.1；见 `LICENSES/Montserrat-OFL.txt` |
| OpenStreetMap 数据 / 派生数据库 | `shared/offline_map/jinan-v1.sqlite`、其 SQL/manifest；`shared/demo_fixture` 中的路线/道路/建筑几何；生成 C++ / Swift 常量中的地图数据 | ODbL 1.0，© OpenStreetMap contributors |

OSM 署名与许可：<https://www.openstreetmap.org/copyright>。
ODbL 正文：<https://opendatacommons.org/licenses/odbl/1-0/>。
完整派生济南数据库随本仓库提供，生成过程见 `shared/offline_map/README.md`。
所有地图数据层的许可保持 ODbL，生成器代码的原创逻辑则使用本项目许可。
公开展示地图及再分发时请保留 `shared/offline_map/ATTRIBUTION.txt` 中的署名和链接。

ESP-IDF、NimBLE 及 Component Manager 下载的组件各自遵循其分发包中的 LICENSE/NOTICE；
它们不是本项目自创代码，也不随本仓库改为 PolyForm。构建二进制再分发前，应汇总实际
使用的 SDK/组件、字体和链接库的许可证，不仅复制根目录 LICENSE.md。
ESP-IDF 依赖版本记录在 `platforms/esp32/dependencies.lock`。

Web 构建使用 Emscripten / SDL，相关工具链及运行时亦保留其原始许可证。
重新生成字体所需 `lv_font_conv` 由其发布方许可；本仓库不重新授权该工具。

高德、Apple Music、Apple MapKit 是外部服务/SDK，不包含在项目许可证授权范围内。
请使用自己的合法账号、Key、配额并核对服务协议及硬件展示场景的授权要求。
本仓库不附带高德 API 原始响应缓存、地图瓦片、Apple Music 音乐文件或专有 SDK 副本。
`backend/fixtures` 是自动测试样例，不是可用于导航的服务数据。

MOTO GPS 是独立开发项目，不代表 Waveshare、Garmin、Apple 或高德的官方产品、认证或背书。
