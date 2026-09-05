# 路线网关

Node.js 20+，无第三方运行依赖；建议使用支持 `--env-file` 的 Node.js 24+。
高德 Key 只放在这里，不能写到 iOS App、网页或固件。

## 先跑本地测试

```sh
cd backend
npm test
MOTO_PROVIDER=fixture WEB_ORIGIN=http://127.0.0.1:4173 npm start
```

服务监听 `127.0.0.1:8787`。fixture 返回固定的合成测试路线，搜索结果为空，
不是实时导航。未设置 `MOTO_PROVIDER` 时也默认 fixture，部署时务必显式配置。

## 配置真实服务

```sh
cp .env.example .env
# 在本地编辑 .env：填写自己的 AMAP_WEB_SERVICE_KEY，设置 MOTO_PROVIDER=amap。
# WEB_ORIGIN 改为自己的网页实际 Origin；不要把 .env 提交到 Git。
node --env-file=.env src/server.js
```

`npm start` 不会自动读取 `.env`；也可以由自己的服务管理器注入环境变量。
Key 未准备好时保留 `MOTO_PROVIDER=disabled`，不要把 fixture 当作生产降级结果。

再用自己的 HTTPS 反向代理把 `/moto-gps/api/` 转发到 `http://127.0.0.1:8787/`。
注意末尾 `/` 与前缀重写。例如客户端 `/moto-gps/api/v1/routes` 应转发到后端 `/v1/routes`。
用自己的域名证书，iPhone 配置相同的 HTTPS 根地址；不要直接使用电脑的 localhost。

```sh
curl https://YOUR-DOMAIN/moto-gps/api/healthz
```

检查 `provider` 为 `amap`、`ready_for_live_navigation` 为 `true`。
健康检查表示配置就绪，**不替代一次真实搜索和路线请求**来验证 Key 权限、配额与网络。
本仓库不提供公共网关。基本限流和 CORS 不能替代认证；对公网发布前请自行添加访问控制、
TLS、配额上限、日志脱敏和告警。不要记录完整出行轨迹或密钥。

## API

| 接口 | 用途 |
| --- | --- |
| `GET /healthz` | 服务模式与配置就绪状态 |
| `GET /v1/places?keywords=...&longitude_deg=...&latitude_deg=...&region=...` | 有当前位置偏置的 POI 搜索；输入坐标为 WGS84，必须成对传入 |
| `POST /v1/route-options` | 最多三条候选普通驾车路线，供 App 全览和选择 |
| `POST /v1/routes` | 单条路线、偏航重算与周期路线/路况刷新 |

请求与响应 schema 在 `shared/protocol`；路线请求样例在 `fixtures/route-request-v1.json`。
输入位置为 WGS84，路线几何输出为 GCJ-02，网关负责边界转换。
`request_id` 原样返回，由导航核心拒绝旧响应。

普通驾车规划不是摩托车专属路线，不能保证满足摩托车禁行规则。路况刷新不是无限制
免费实时交通流，实际服务权限、频率与配额由你的高德账号和协议决定。
周边灰路/建筑使用随仓库提供的 OSM 数据，未缓存高德地图瓦片或 API 原始响应。
上线或用于智能硬件前，须自行核对高德对相应使用场景的授权。
