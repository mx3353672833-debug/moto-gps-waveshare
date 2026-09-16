> **语言 / Language:** 中文 · [English](README.en.md)

# 路线与周边地图网关

Node.js 20+；以下启动示例建议 Node.js 24+，支持 `--env-file`。
高德 Key 只放在服务端，不能写到 iOS App、网页或固件。地图解码依赖锁定版本的
`pmtiles`、`@mapbox/vector-tile` 和 `pbf`，部署前必须安装依赖。

## 先跑本地测试

```sh
cd backend
npm ci
npm test
MOTO_PROVIDER=fixture WEB_ORIGIN=http://127.0.0.1:4173 npm start
```

服务监听 `127.0.0.1:8787`。fixture 返回固定的合成测试路线，搜索结果为空，
不是实时导航。未设置 `MOTO_PROVIDER` 时默认 fixture，部署时务必显式配置。
fixture/disabled 模式默认关闭在线地图，测试不需要外网；可以独立覆盖地图源设置。

## 配置真实服务

```sh
cp .env.example .env
chmod 600 .env
# 在本地编辑 .env：填写自己的 AMAP_WEB_SERVICE_KEY，设置 MOTO_PROVIDER=amap。
# WEB_ORIGIN 改为自己的网页实际 Origin；不要把 .env 提交到 Git。
node --env-file=.env src/server.js
```

`npm start` 不会自动读取 `.env`；也可以由自己的服务管理器注入环境变量。
Key 未准备好时保留 `MOTO_PROVIDER=disabled`，不要把 fixture 当作生产降级结果。
生产安装依赖可用 `npm ci --omit=dev`，并保留仓库的 `package-lock.json`。

用自己的 HTTPS 反向代理把 `/moto-gps/api/` 转发到 `http://127.0.0.1:8787/`。
注意末尾 `/` 与前缀重写：客户端 `/moto-gps/api/v1/routes` 应到后端 `/v1/routes`。
使用自己的域名证书，在 iPhone 配置相同的 HTTPS 根地址；不能使用电脑的 localhost。
完整部署步骤见[网关教程](../docs/GATEWAY_SETUP.md)。

```sh
curl --fail-with-body https://YOUR-DOMAIN/moto-gps/api/healthz
```

检查 `provider=amap`、`ready_for_live_navigation=true`、`capabilities.surrounding_map`
和 `capabilities.map_city_search`。能力标记仅表示配置已启用，**不能替代真实搜索、路线、
城市与未缓存瓦片请求**。地图状态 `map_source` 提供版本、缓存计数、最近成功时间和错误。
`road_speed_limits` 与 `traffic_light_countdown` 当前均为 `false`。

本仓库与官网不提供免费公共网关。基本限流和 CORS 不能替代认证；公网部署需自行配置
访问控制、TLS、配额上限、日志处理和告警。新增认证方案时必须同步适配客户端。
GET 搜索 URL 可能含搜索词和精确位置，瓦片路径可推断地区；反向代理默认访问日志
可能保存这些内容。核实日志轮转、备份、保留期与隐私说明，不要宣称零采集或记录密钥。

## API

| 接口 | 用途 |
| --- | --- |
| `GET /healthz` | 服务模式、配置能力与地图源/缓存状态 |
| `GET /v1/places?keywords=...&longitude_deg=...&latitude_deg=...&region=...` | POI 搜索；可选位置为 WGS84，经纬度必须成对传入 |
| `POST /v1/route-options` | 最多三条候选普通驾车路线，供 App 全览和选择 |
| `POST /v1/routes` | 单条路线、偏航重算与周期路线/路况刷新 |
| `GET /v1/map/cities?keywords=...` | 城市、区县及四个直辖市的下载范围；不接受普通省或国家作为下载结果 |
| `GET /v1/map/tiles/15/{x}/{y}` | 一块道路/建筑 JSON；仅支持 z15，x/y 为 0–32767 的整数，不接受查询参数 |

路线请求与响应 schema 在 `shared/protocol`，请求样例在 `fixtures/route-request-v1.json`。
路线输入位置为 WGS84，路线几何输出为 GCJ-02；地点搜索结果位置为 WGS84。
`request_id` 原样返回，由导航核心拒绝旧响应。路线和高德响应不进入 OSM 地图磁盘缓存。
普通驾车规划不是摩托车专属路线，不能保证避开摩托车禁行路段。路况权限、频率与配额
由自己的高德账号和协议决定；上线或用于智能硬件前，需核对相应场景授权。

### 地图坐标与下载边界

- 分块编号按 **WGS84 Web Mercator** 计算；GCJ-02 位置需先逆变换，再选择分块。
- 响应的 `coordinate_system` 为 `GCJ-02`，道路与建筑 `points_e6` 为
  **`[纬度 × 1e6, 经度 × 1e6]` 整数**，不要再次偏移。
- `source` 含提供方、许可、署名链接、`source_revision`、`retrieved_at` 和 WGS84 范围，
  描述数据版本与取得时间，不是实时街景或实时路况。
- 城市返回 `cities` 数组，条目含 `id`、`name`、`detail`、`bounds_wgs84`；范围顺序为
  `[west, south, east, north]`。来源是高德行政区域边界逆变换后的外接矩形，可能包含邻区。
- App 据此下载城市/区县，或只下载所选路线周边约一公里。离线包只存道路与建筑背景，
  不提供离线搜索、路线计算、实时路况、道路限速或信号灯读秒。内置济南数据仍作为兜底。
- 数据完整程度取决于当地 OSM 覆盖。API 不按圆屏的要素数量上限截断；手机另行筛选
  BLE 窗口。过大的瓦片明确返回错误，不把截断数据作为成功结果。

### 限流与错误

每个来源地址、每分钟独立额度：地点搜索 60 次、路线 30 次、城市搜索 30 次、地图瓦片
600 次。HTTP 429 带 `Retry-After: 60`。下载客户端应控制在约 3 次/秒并遵守重试延迟。
仅当连接来自本机回环地址时才读取 `X-Real-IP`；可信反向代理应覆盖此头，不能直接透传
客户端自报的值。Node 应保持仅监听回环地址。

地图同一瓦片的并发请求合并；上游瓦片任务和 Range 请求各最多 4 个并发，待处理键最多
128 个。过载返回 `MAP_BUSY` / HTTP 503 与 `Retry-After: 5`。其他地图源错误也会返回
明确错误，不能把错误响应保存为已下载地图。

## 地图源与持久缓存

| 环境变量 | 默认值与用途 |
| --- | --- |
| `MOTO_MAP_PMTILES_URL` | `amap` 模式默认 `auto`；fixture/disabled 默认 `disabled`。也可指定自己的 HTTPS `.pmtiles` URL |
| `MOTO_MAP_CACHE_DIR` | 服务工作目录下 `.cache/map-tiles`；生产应改为专用、持久、可写目录 |
| `MOTO_MAP_CACHE_MAX_BYTES` | `1073741824`，即 1 GiB JSON 内容；允许 1 KiB–2 GiB 整数值 |

```dotenv
MOTO_MAP_PMTILES_URL=auto
MOTO_MAP_CACHE_DIR=/YOUR_WRITABLE_STATE_DIRECTORY/maps
MOTO_MAP_CACHE_MAX_BYTES=1073741824
```

`disabled` 只关闭周边瓦片；城市搜索仍取决于高德 Provider。缓存另有固定 100,000 个文件
上限，按最近使用情况淘汰；文件系统实际占用可能超过 JSON 字节数。目录应属于运行 Node
的普通用户，服务重启后继续保留。若使用 systemd 沙箱，还需允许此目录写入。

`auto` 从官方 [builds.json](https://build-metadata.protomaps.dev/builds.json) 选择已上传且
兼容解码器的最新 v4 归档，启动时及每 24 小时检查更新；来源仅构造为
`https://build.protomaps.com/YYYYMMDD.pmtiles`。归档 404 时会额外检查清单并重试，
清单失败至少退避 60 秒。元数据持久化，断网重启时可尝试上次源。

缓存命中立即返回。访问时如发现版本变化或缓存超过 7 天，在后台刷新；刷新失败保留旧瓦片，
源更新也不会清空 auto 缓存。显式 HTTPS 源跳过公共清单，推荐使用不可变版本路径；
服务端 URL 可带存储鉴权查询参数，不会向客户端公开。客户端不能指定任意上游 URL。

上游存储必须支持 HTTP Range。每次 Range 最长 10 秒、最多 8 MiB，忽略 Range 并返回
整文件的 HTTP 200 会被拒绝；gzip 解压也有限额，不会整包下载全球归档。JSON 最大 8 MiB，
总计最多 12,000 个要素、每要素 2,048 点、全瓦片 120,000 点。可在 HTTPS 反向代理启用
JSON gzip，iOS URLSession 会处理 HTTP 解压。

**公共每日归档是过渡数据源，不是稳定生产托管承诺。**
[Protomaps 官方下载说明](https://docs.protomaps.com/basemaps/downloads)建议复制到自己的
存储，公共托管只保留近期及版本归档。面向更多用户前，应将兼容的区域 PMTiles 归档放到
自有、支持 Range 的 HTTPS 存储，再修改服务端环境变量，无需用户改 App。
不使用公共 OSM 标准瓦片或 Overpass 批量下载作为兜底。
保留 `© OpenStreetMap contributors`、来源与[数据许可入口](https://www.openstreetmap.org/copyright)。

## 发布前验证

分别验证真实搜索、候选路线、城市/区县范围、一个未缓存瓦片和重复缓存读取；再验证
上游不可用时旧缓存仍可读取，查看 `map_source.last_success_at`、`last_error`、
`last_metadata_error` 与缓存统计。程序测试和两地接口抽查不能替代圆屏实车、弱网、
跨城下载与长时间运行验收。当前 TestFlight 尚未上线，也未接通真实限速或读秒。
