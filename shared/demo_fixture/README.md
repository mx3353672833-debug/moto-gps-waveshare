# 济南真实道路演示夹具

> 本页主要描述固定演示夹具。当前 iPhone 已随包加载全济南 OSM SQLite 背景数据，
> 正式导航在济南范围内可使用该离线场景，见 `../offline_map/README.md`。
> 下文关于另建在线道路 Provider 的讨论不表示必须申请高德道路接口才能使用现有 OSM 层。

这份夹具让 Web、iOS 离线回退和 ESP32 本机长按演示使用同一条真实道路：
**山东省大数据产业基地 D 栋附近 → 浪潮集团（总部）附近**。白色线是所选
路线，灰色线是周边道路；车头固定，二者使用相同的位移、航向旋转和缩放变换。

唯一人工维护的数据源是 `jinan_big_data_to_inspur.json`。运行
`node scripts/generate_jinan_demo_fixture.mjs` 会生成 C++ 与 Swift 常量；CI 可用
`node scripts/generate_jinan_demo_fixture.mjs --check` 检查两端是否仍完全一致。
动作位置存为路线节点索引，距离和路线进度均由累计几何距离计算，并非按时间手绘。

## 白色规划路线（离线回退）

夹具只固化 OpenStreetMap 上可核验、方向合法且物理连通的道路节点，全长约
1.49 km：园区车道 → 新泺大街 → 崇华路 → 浪潮路。

- [大数据产业基地园区道路，way 1296826090](https://www.openstreetmap.org/way/1296826090)
- [新泺大街，way 1222956187](https://www.openstreetmap.org/way/1222956187)
- [新泺大街连接段，way 562686625](https://www.openstreetmap.org/way/562686625)
- [崇华路，way 562686626](https://www.openstreetmap.org/way/562686626)
- [浪潮路北段，way 790389631](https://www.openstreetmap.org/way/790389631)
- [浪潮路南段，way 136622031](https://www.openstreetmap.org/way/136622031)

OSM 在两个 POI 门口缺少完整的最后连接，因此离线演示从 D 栋附近的真实园区
车道开始（距 POI 约 70 m），到浪潮集团入口附近的浪潮路结束（距入口约 53 m）。
界面和文档均称“附近”，不会把未测绘直线伪装成道路。

## 灰色周边道路

密集背景使用 **24 条独立 OSM way、192 个点（当前固件容量已用满）**，折线总长
约 9.98 km。沿演示路线抽取的 8 个位置中，每个位置 250 m 内有 4–12 条背景路，
覆盖起点园区、新泺大街两侧、路线中段园区环路、伯乐路、坤顺路双向车道和浪潮
园区内部道路。相比旧版 8 条/59 点，局部视野不再只剩一两根孤立灰线。

每条道路保留独立 span，不会把不相连的道路硬连成线。完整 way ID、版本、编辑
时间、道路类型、源节点范围和简化误差都记录在 canonical JSON 内。运行：

```sh
node scripts/update_jinan_road_context.mjs
node scripts/generate_jinan_demo_fixture.mjs
node scripts/generate_jinan_demo_fixture.mjs --check
```

第一步直接读取 OpenStreetMap 官方 API 的原始 way/node。简化算法只删除冗余源节点，
不会新造坐标；不同道路仍保持独立。保留节点的最大折线误差为 2–3 m。原始 WGS84
坐标再通过项目已测试的 WGS84 → GCJ-02 转换生成，与在线高德路线使用同一显示
坐标系。

`jinan_map_scene_sample.json` 是离线滚动窗口协议的独立样本：沿用上述 24 条
真实道路，并从 OSM 矢量实体加入 D 栋起点附近 16 个真实建筑 footprint。它用于
验证 MapScene 容量与建筑数据形状，当前演示固件不会自动加载：

```sh
node scripts/update_jinan_map_scene_sample.mjs
```

路线与背景道路数据：© OpenStreetMap contributors，ODbL。产品公开显示地图时仍须
提供可读署名及 `https://www.openstreetmap.org/copyright` 许可入口；不能因离线存储
而移除署名。

## 济南离线地图与建筑

完整济南离线图可行，但不应把未经裁剪的 OSM/高德瓦片直接塞进 ESP32。建议由
iPhone 保存按区域下载的矢量分块，导航时只把当前位置周围约 500–800 m 的简化
道路/建筑轮廓发给圆屏；ESP32 只维护一个滚动窗口。建筑必须来自合法数据源的真实
footprint，不能用随机矩形冒充。容量估算、分块格式、Flash 边界和许可处理见
[`JINAN_OFFLINE_MAP_MVP.md`](JINAN_OFFLINE_MAP_MVP.md)。

## 在线演示与数据边界

iOS“演示导航”每次优先通过现有网关实时请求固定 POI
`B0JAAZDW32`（山东省大数据产业基地 D 栋）到 `B02130VXRE`（浪潮集团总部）
的高德驾车路线，并沿该次返回的 polyline 连续模拟定位。高德响应不写入源码或
夹具；断网时才回退到上述 OSM 路线。稳定 demo route token 只用于让 ESP32 加载
同地点的 OSM 灰色道路背景。

高德普通驾车路线 v5 只返回所选路线，不返回完整周边路网。正式在线背景路网需
独立 `RoadContextProvider`；高德“交通态势查询”高级 Web 服务可在
`extensions=all` 时返回 `roads[].polyline`，但需要商务申请相应权限。在没有该
权限或其他合规道路数据源时，正式模式只显示真实规划路线，不伪造周边道路。
