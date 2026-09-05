# 济南离线小地图包 v1

`jinan-v1.sqlite` 是给 MOTO GPS iPhone 端使用的全济南离线场景库。它不是预先
画好的演示线，也不是缓存高德或 OSM 在线瓦片，而是从真实 OpenStreetMap 几何生成的
可查询矢量数据：

- 按 OSM 济南市行政边界关系 `3486449` 精确裁剪；
- 保留机动车可用道路，排除明确标记为 `access/private/no` 的道路；
- 保留 OSM 中实际存在的建筑轮廓，不生成或补画建筑；
- WGS84 坐标统一转换为导航链路使用的 GCJ-02 E6；
- 道路和建筑分别建立 SQLite R-tree，手机只查询车辆周围 500–800 m；
- 终端仍只接收当前窗口，不需要把 9.73 MiB 全市数据放进 ESP32。

## 2026-09-03 数据实测

| 指标 | 数值 |
| --- | ---: |
| SQLite 文件 | 9.73 MiB / 10,199,040 bytes |
| 道路折线 | 47,468 条 |
| 道路点 | 266,985 个 |
| 建筑轮廓 | 26,702 个 |
| 建筑点 | 148,140 个 |
| D 栋—浪潮演示区域候选道路 | 291 条 |
| D 栋—浪潮演示区域候选建筑 | 283 个 |
| SHA-256 | `d4ce2ebb0a9be68b060964e063858bc2b9a127d68b9796412feec6dc5b9fa2c3` |

哈希以 `jinan-v1.manifest.json` 为准；重新生成且输入版本不变时结果是确定的。

## 文件格式

数据库 schema 见 `jinan-v1.sql`。核心约定：

- `metadata`：版本、坐标系、来源、署名、数据日期和统计量；
- `roads` / `road_rtree`：道路几何和范围索引；
- `buildings` / `building_rtree`：建筑外轮廓和范围索引；
- `points` BLOB：重复的小端 `[int32 lat_e6, int32 lon_e6]`；
- 建筑首点不在末尾重复，渲染器负责闭合；
- 由 OSM relation 生成的多面建筑用负数 `osm_way_id` 保留来源类型；
- 建筑内洞在 v1 中省略。466×466 的灰色背景块不需要内洞信息。

道路 class：`0 motorway`、`1 primary`、`2 secondary`、`3 residential`、
`4 service`、`5 other`。建筑 class：`0 generic`、`1 landmark`、`2 parking`。

## 可行性边界

这套方案可以让济南市内任意导航位置都调用同一份高密度离线道路库；但“有数据”不等于
屏幕会同时画出全部数据。iPhone 会先从几百条候选要素中按距离和道路等级筛选，BLE v1
每个窗口最多发送 24 条道路、192 个道路点、16 个建筑、128 个建筑点，适配 466×466
圆屏。如果实机仍显得过稀，下一步应调整窗口选择和 BLE 容量，而不是伪造背景线。

OSM 的建筑覆盖度并不均匀：中心城区通常比较密，郊区或新建园区可能缺少轮廓。缺失建筑
只能以后用合法数据源补充或实测采集，当前包不会猜测建筑位置。

## 授权与署名

数据来自 OpenStreetMap，使用 ODbL 1.0。产品界面或“关于/地图数据”页面必须提供可发现的
`© OpenStreetMap contributors` 署名和许可链接。禁止通过批量下载
`tile.openstreetmap.org` 瓦片来制作离线包；本项目使用 Geofabrik 提供的 PBF 数据。

- 数据来源：https://download.geofabrik.de/asia/china/shandong.html
- 署名说明：https://osmfoundation.org/wiki/Licence/Attribution_Guidelines
- ODbL：https://opendatacommons.org/licenses/odbl/1-0/

## 重新生成

依赖：`osmium-tool`、`jq`、Node.js 22+（需要内置 `node:sqlite`）。

```bash
mkdir -p tmp/offline_map_source
curl -L https://download.geofabrik.de/asia/china/shandong-latest.osm.pbf \
  -o tmp/offline_map_source/shandong-latest.osm.pbf
./scripts/offline_map/extract_jinan.sh
node scripts/offline_map/build_jinan_sqlite.mjs
node scripts/offline_map/validate_jinan_sqlite.mjs
```

第一步生成边界、济南 PBF 和 GeoJSONSeq 中间文件；第二步生成 SQLite 和 manifest；
第三步逐条解码所有几何并校验边界、点数、R-tree、版本、坐标范围和 D 栋区域密度。
