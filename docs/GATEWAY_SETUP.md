> **语言 / Language:** 中文 · [English](GATEWAY_SETUP.en.md)

# 微雪版真实导航与周边地图：网关配置

本页是[DIY 教程](WAVESHARE_DIY_GUIDE.md)的配套步骤。只看 App 演示可以稍后配置；
搜索真实目的地、候选路线、偏航重算、路况、在线周边地图与离线地图下载需要本页的服务。
截至 2026-09-16，TestFlight 尚未开放；源码安装仍需自己的签名和服务。
仓库与官网不提供免费公共网关，官网入口不代表获得导航服务授权。

```text
iPhone → 你自己的 HTTPS 网关 → 高德 Web 服务（搜索、路线、城市范围）
              保存 Key       → Protomaps / OSM（道路、建筑；服务端缓存）
```

## 1. 准备 Key 和服务环境

在[高德开放平台 Web 服务 Key 指南](https://lbs.amap.com/api/webservice/guide/create-project/get-key)中，
按当前控制台流程创建应用和 **Web 服务** 类型 Key，确认 POI 搜索、驾车路线与行政区域查询权限/配额。
这里不是 iOS SDK Key，也不是网页 JavaScript Key。

需要一台可持续运行 Node.js 的服务器和一个自己的域名。以下示例按 Linux + Node.js 24+ + Caddy 2 编写；
本地 Mac 可先测后端，但电脑休眠、关机或手机离开局域网后不能把它当持续可用的公网服务。
GitHub Pages 只托管静态内容，不能运行本 Node 网关。

在服务器按 [Node.js 官方下载](https://nodejs.org/en/download)和
[Caddy 官方安装说明](https://caddyserver.com/docs/install)安装工具，并准备 Git。
服务器应能访问高德 API；在线地图还需访问所选 PMTiles HTTPS 源及自动模式的官方构建清单。
地图解码新增 `pmtiles`、`@mapbox/vector-tile`、`pbf` 依赖，不能再跳过安装。
下面使用 `nav.example.com` 作为占位域名，必须换成自己的地址。

## 2. 下载代码并验证后端

在服务器当前用户目录执行，后端测试不需要初始化 LVGL：

```sh
git clone https://github.com/mx3353672833-debug/moto-gps-waveshare.git
cd moto-gps-waveshare
node --version
npm --prefix backend ci
npm --prefix backend test
cp backend/.env.example backend/.env
chmod 600 backend/.env
```

用编辑器填写 `backend/.env`，不要把真实 Key 发到 Issues：

```dotenv
MOTO_PROVIDER=amap
AMAP_WEB_SERVICE_KEY=REPLACE_WITH_YOUR_WEB_SERVICE_KEY
PORT=8787
WEB_ORIGIN=https://nav.example.com
MOTO_MAP_PMTILES_URL=auto
MOTO_MAP_CACHE_DIR=/YOUR_WRITABLE_STATE_DIRECTORY/maps
MOTO_MAP_CACHE_MAX_BYTES=1073741824
```

将 `MOTO_MAP_CACHE_DIR` 替换为自己的绝对路径，创建一个由运行 Node 的普通用户可写的
持久目录；不要放在会随每次发布删除的临时目录。默认最多保存 1 GiB JSON、100,000 个
瓦片文件，按最近使用情况淘汰，实际磁盘占用可能略大。`npm ci --omit=dev` 可用于生产安装。

`amap` 模式默认启用 `auto` 周边地图；`fixture` / `disabled` 默认关闭地图，避免测试联网。
`auto` 从官方清单选择兼容的最新 Protomaps v4 归档并按需读取，公共归档只适合过渡验证，
不承诺永久保留或可用性。稳定部署应自行托管兼容的区域 `.pmtiles` 文件，设置
`MOTO_MAP_PMTILES_URL=https://YOUR-STORAGE/region-version.pmtiles`，存储必须支持 HTTP Range。
切换服务端源不需要用户更改 App；也可以设 `disabled` 关闭周边瓦片。
来源、许可、缓存刷新与请求限制见[后端 README](../backend/README.md#地图源与持久缓存)。

`WEB_ORIGIN` 是使用 Web 工具时允许的浏览器来源，不是用户认证。只用原生 iOS 也要知道
公开网关目前没有 App 登录/令牌认证；限流不能代替访问控制。个人部署应限制使用范围，
可放在自己手机可达的受控网络中。添加 Basic Auth 或交互式登录会要求客户端同步适配，
不能直接加上后期待当前 App 自动登录。Key 始终保留在服务端。

启动：

```sh
node --env-file=backend/.env backend/src/server.js
```

应看到服务监听 `127.0.0.1:8787`。此时保持终端运行，在另一个服务器终端检查：

```sh
curl --fail-with-body http://127.0.0.1:8787/healthz
```

`provider: amap` 和 `ready_for_live_navigation: true` 表示配置已启用，
不代表实际 Key 和上游网络已验证。地图还需检查 `capabilities.surrounding_map`、
`capabilities.map_city_search` 和 `map_source`。后面仍需要真实请求。

## 3. 给手机提供 HTTPS 地址

将自己的域名 A/AAAA 记录指向服务器，在使用的防火墙/安全组允许该部署所需的 80/443，
确保端口未被其他服务占用。只为反向代理暴露 HTTPS，Node 保持监听本机。
按安装方式打开 Caddyfile（Linux 服务通常在 `/etc/caddy/Caddyfile`），将以下站点块合并进去：

```caddyfile
nav.example.com {
    handle_path /moto-gps/api/* {
        reverse_proxy 127.0.0.1:8787 {
            header_up X-Real-IP {remote_host}
        }
    }
}
```

`handle_path` 会移除 `/moto-gps/api` 前缀，让后端收到 `/healthz`、`/v1/places` 等实际路由。
Caddy 在域名、端口和存储条件满足时申请并维护 HTTPS 证书。依据：
[路径处理](https://caddyserver.com/docs/caddyfile/directives/handle_path)、
[自动 HTTPS](https://caddyserver.com/docs/automatic-https)。

此示例假定 Caddy 直接面向手机，覆盖 `X-Real-IP` 后供本机 Node 做来源限流，
不能透传任意客户端自报地址。依据：[请求头设置](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#headers)、
[`remote_host` 占位符](https://caddyserver.com/docs/caddyfile/concepts#placeholders)。
若另有 CDN 或代理，需另行配置可信代理边界。
检查自己的访问日志：地点搜索 URL 可含关键词和精确位置，城市关键词与瓦片路径也会透露
出行区域。配置合适的日志脱敏、保留期限与备份策略，并同步实际隐私说明；不要记录密钥。

使用 Linux Caddy 服务时，先验证配置，再加载：

```sh
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

然后在 iPhone 的 Safari 打开自己的地址：

```text
https://nav.example.com/moto-gps/api/healthz
```

手机通过蜂窝网络也应能访问（若使用受控网络，则先连接它）。不能用服务器的 `localhost` 代替域名。
404 通常先查路径前缀，502 先查 Node 是否运行，证书错误先查域名解析和 TLS 配置。

## 4. 保持后端运行

前台 Node 进程会随会话退出而停止。桌面验证结束后，Linux 可用 systemd 托管。
先用 `pwd` 确认仓库绝对路径，用 `command -v node` 确认 Node 的绝对路径。
用 `sudoedit /etc/systemd/system/moto-gps.service` 创建服务，替换所有 `YOUR_*`：

```ini
[Unit]
Description=MOTO GPS route gateway
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=YOUR_LINUX_USER
WorkingDirectory=/YOUR_ABSOLUTE_PATH/moto-gps-waveshare
EnvironmentFile=/YOUR_ABSOLUTE_PATH/moto-gps-waveshare/backend/.env
ExecStart=/YOUR_ABSOLUTE_NODE_PATH /YOUR_ABSOLUTE_PATH/moto-gps-waveshare/backend/src/server.js
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
```

`User` 使用能读取这份代码的普通用户，Node 路径填 `command -v node` 的完整结果。
该用户也必须能写入 `MOTO_MAP_CACHE_DIR`；若另加 systemd 沙箱，允许此目录写入。
先在前台 Node 的终端按 Ctrl+C 释放 8787，再启用服务：

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now moto-gps
sudo systemctl status moto-gps
```

如启动失败，用 `sudo journalctl -u moto-gps -n 50 --no-pager` 看错误。
此示例适用于有 systemd 的 Linux；Mac 和托管平台请用各自的进程管理方式。

## 5. 验证真实请求并配置 App

在终端将域名替换后，测试真实搜索：

```sh
curl --fail-with-body --get 'https://nav.example.com/moto-gps/api/v1/places' \
  --data-urlencode 'keywords=济南西站'
```

应返回地点结果或有明确原因的服务错误；无权限、配额不足、超时均不能用 fixture 当作修复。
`fixture` 只供协议测试；`disabled` 会明确拒绝线上导航。

继续验证城市范围和一个真实周边瓦片：

```sh
curl --fail-with-body --get 'https://nav.example.com/moto-gps/api/v1/map/cities' \
  --data-urlencode 'keywords=历下区'
curl --fail-with-body 'https://nav.example.com/moto-gps/api/v1/map/tiles/15/27044/12791' \
  --output /dev/null --write-out 'HTTP %{http_code}\n'
```

城市结果应有有效 `bounds_wgs84`；瓦片应返回 HTTP 200。再次读取检查缓存命中，并查看
`map_source.last_success_at` 与错误字段。配置能力为 true 但未缓存瓦片始终失败，仍不能验收。
测试时还应验证一次上游不可用但旧缓存可读的情况，避免只验证联网成功路径。

打开 `platforms/ios/project.yml`，设置：

```yaml
MOTOGPSGatewayBaseURL: https://nav.example.com/moto-gps/api/
```

回到[DIY 教程的 iPhone 安装步骤](WAVESHARE_DIY_GUIDE.md#4-把-app-安装到-iphone)，重新生成工程并 Run。
在 App 搜索附近终点，取得至少一条候选路线，再开始和结束一次导航。
这样才能确认手机定位、公开网关、实际 Key、路线请求和 App 地址全部配合正常。

首页“地图与离线下载”可搜索城市/区县，选好路线后也可下载沿途。下载需要 App 保持运行，
中断后可继续。断网时使用下载与缓存，内置济南基础地图仍保留；这些只提供道路与建筑背景，
不替代离线搜索、路线重算、实时路况、限速或读秒。

后续更换 Key 只需要更新服务端环境并重启服务；更换 App 使用的域名/路径需要更新项目配置并重新安装。
接口字段与请求约束见[后端 README](../backend/README.md)，日常操作见[功能说明书](USER_MANUAL.md)。
