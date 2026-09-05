# 济南离线路网与建筑轮廓 MVP

> 历史设计笔记。济南 SQLite 离线库和 iPhone 场景查询现已实现；当前格式、容量与
> 生成方式以 `../offline_map/README.md` 为准，下文保留方案选择过程，非发布验收记录。

## 结论

可行，但推荐把“济南离线地图”下载到 **iPhone**，ESP32 只接收当前位置周围的
小窗口。不要把整套省级原始地图或高德离线包直接写进终端，也不要从高德 SDK
私自提取瓦片或矢量数据。

这条路线的当前演示夹具已经是纯离线、可核验的最小样本：24 条真实 OSM 道路、
192 个真实源节点、约 9.98 km 折线。它把圆屏当前道路层容量用满了，但它不是完整
济南地图。

## 推荐架构

1. iPhone 下载济南 OSM 矢量包，使用 SQLite + R-tree 或固定 500 m 分块保存。
2. 每次定位变化后，App 查询车头周围 500–800 m，只取屏幕能看到的道路和较大
   建筑轮廓。
3. 道路按 2–3 m 误差简化，坐标转换为 GCJ-02，与高德规划路线对齐。
4. App 通过新的 BLE `MapScene (0x14)` 消息发送最多 24 条/192 道路点，外加
   16 个/128 点建筑轮廓的窗口；车辆
   前进约 100 m 或跨分块时才更新，不需要每帧传图。
5. ESP32 继续使用现在的 heading-up 投影：车头固定，白色规划路线和灰色道路一起
   平移、旋转。断网后已下载窗口仍可显示。

这比在 ESP32 内做全市空间查询简单、可靠，而且地图升级不需要重刷固件。

## 建筑怎么做

建筑不能复用道路数组硬画。`MapScene` 已经给建筑保留独立的 16 个 footprint /
128 点协议预算；终端 LVGL 建筑图层仍未接入。实际查询应只显示距离车头约 300 m
内、面积大于约 250 m² 的真实轮廓，并用 1 px 深灰描边。这样不会和 3 px 道路、
6 px 白色路线混在一起。

OSM 在济南的建筑覆盖并不完整。在本路线南半段约 0.86 km² 的实测查询中只有
53 个 building way、366 个原始轮廓点；因此可以显示已有真实建筑，但不能承诺达到
高德底图的建筑密度。缺失处必须留空，不能用随机矩形补齐。若商业版必须达到高德
级别，需要购买明确允许终端自定义渲染/离线分发的中国合规矢量数据授权。

高德 iOS 3D 地图 SDK 本身支持按城市下载离线地图，适合 App 的路线全览页面；其
公开接口没有提供把离线包中的任意道路/建筑矢量抽出并发给 ESP32 的能力。因此
“App 内高德预览”和“终端 OSM 灰色背景”应视为两个数据层，不把高德离线包当作
自研固件数据库。参考：

- <https://lbs.amap.com/api/maps-sdk-for-ios/guide/create-map/use-offlinemap>
- <https://lbs.amap.com/api/ios-sdk/download/>

## 容量估算

以下是工程估算，不是供应商承诺，正式打包后还要以生成物为准：

| 数据范围 | 建议格式 | 估算大小 |
| --- | --- | ---: |
| 当前 D 栋 → 浪潮演示道路 | C++ double 常量，24 条/192 点 | 约 4–6 KB Flash |
| 济南市可行车道路，不含建筑和名称全文 | 500 m 分块、0.5 m 量化、2–3 m 简化 | 约 4–8 MB |
| 济南道路 + OSM 已有建筑轮廓 | 同上，建筑按面积/层级裁剪 | 约 7–15 MB |
| 再加入道路名称/POI/搜索索引 | 字符串表 + 空间/文本索引 | 约 15–25 MB |
| 山东原始 OSM PBF（未裁剪） | Geofabrik 当前省级源文件 | 约 67 MB，不能直接放入终端 |

估算依据：济南行政区在 OSM 当前约有 55,475 个 `highway` way 和 25,918 个
building way/relation；路线周围
约 1.68 km² 的代表性样本中，可行车道路以 2 m 误差简化后约为 2.6 KB/km² 的
纯几何数据。完整包还必须加分块目录、道路级别、校验和、版本和文件系统开销，所
以不能只按坐标裸数据外推。

微雪板是 32 MiB Flash，当前分区只有 7 MiB `storage`；分区表还留有约
16.9 MiB 未分配空间。在保持 8 MiB factory app、且不做 OTA 双分区时，理论上可把
地图分区扩大到约 23.9 MiB。高度裁剪的道路 + OSM 建筑包有机会装下，但加入
名称/POI/搜索索引后余量会迅速缩小，而且全市索引和地图更新会变复杂。因此仍建议
把全市包放在 iPhone；只有在实测生成包小于约 15 MiB 后，才考虑把副本写入终端。

## 离线包格式（建议）

- 分块：500 m × 500 m；每块有 bbox、版本、CRC32 和道路/建筑偏移表。
- 坐标：块内 `uint16` X/Y，0.5 m 量化；每点 4 byte。
- 道路头：class、flags、point count、数据 offset；只保留渲染所需属性。
- 建筑头：闭合 ring、面积等级、point count；不保存无用 POI 文本。
- App 侧：SQLite/R-tree 或 mmap 索引；终端侧只解析一个 24/192 滚动窗口。
- 更新：按块版本增量替换，不把地图版本绑死在固件版本中。

## 本轮落地边界

已完成：共享 C++ BLE codec 的 `MapScene (0x14)`、能力位 bit7、范围校验、
182-byte characteristic value 和 20-byte 兼容路径的分片/重组测试，以及
`map-scene.v1.schema.json`。`jinan_map_scene_sample.json` 是从 OSM 矢量实体生成的
可追溯样本，含 24 条/192 点道路和起点附近 16 个/94 点真实建筑；每个对象保留
OSM way ID。可用下列命令重新获取：

```sh
node scripts/update_jinan_map_scene_sample.mjs
```

iOS 已经能把该真实样本作为本地场景仓库载入，按当前位置裁出 500–800 m 窗口，
并在前进约 100 m 后生成新 revision。查询结果严格执行 24 条/192 道路点和
16 个/128 建筑点的协议上限；样本覆盖范围之外返回空窗口，不会补随机道路或建筑。
App 会把窗口交给共享 C++ codec，按协商的 182-byte 帧长分片，并且只在终端握手
声明 bit7 能力后发送。断线期间只保留最新完整窗口，重连后会再次发送。

当前 iOS 已有两种 `OfflineMapSceneQuerying` 仓库：内存索引用于 EVT 小样验证，
`SQLiteOfflineMapSceneIndex` 用于全市包。后者按 `shared/offline_map/jinan-v1.sql`
读取 GCJ-02 E6 几何，利用 `road_rtree` / `building_rtree` 预选，再用与小样相同的
精确裁剪、道路等级优先和建筑距离/面积排序。默认文件路径是
`Application Support/OfflineMaps/jinan-v1.sqlite`，也可把同名种子数据库直接放在
App Bundle 根目录。全济南 JSON 不会一次性读进内存。

全济南 SQLite 生成物已落在 `shared/offline_map/jinan-v1.sqlite` 并加入 iOS App Bundle：
约 9.63 MiB，含 47,468 条道路/266,985 个道路点、26,702 个建筑/148,140 个建筑点。
数据库全量几何解码和 SQLite `integrity_check` 均通过，D 栋区域也有实际道路和建筑
查询结果。因此 iPhone 侧已经不是固定演示路线，而是可随当前位置查询全市包。

尚未完成的是离线包在线下载/版本增量更新，以及真实骑行中的 OSM 覆盖密度验收。
终端接收、双缓冲原子提交与 LVGL 建筑填充/描边属于 ESP32 显示链路，应在独立改动
中验收；不能仅凭 iPhone 包已完成就宣称现有实机已经显示全济南建筑。

## 数据来源与许可

道路/建筑数据可以来自 OpenStreetMap 或取得书面授权的商业数据源。OSM 数据采用
ODbL 1.0：公开产品必须清晰署名 OpenStreetMap，并提供许可入口；分发离线数据库或
派生数据库时还要检查 ODbL 的数据库署名/共享条件。官方要求见：

- <https://osmfoundation.org/wiki/Licence/Attribution_Guidelines>
- <https://www.openstreetmap.org/copyright>

不要批量缓存 `tile.openstreetmap.org` 公共瓦片；官方瓦片政策明确要求离线使用
自建瓦片或选择明确允许离线下载的供应商：

- <https://operations.osmfoundation.org/policies/tiles/>

可从 Geofabrik 的山东 OSM PBF 开始，在电脑端按济南行政边界裁剪和生成自有矢量
分块。其当前省级下载页为：

- <https://download.geofabrik.de/asia/china/shandong.html>

商用前还需单独核对中国地图展示、审图号和数据分发合规要求；本文件只是工程方案，
不替代法律意见。
