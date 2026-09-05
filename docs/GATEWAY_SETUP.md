# 微雪版真实导航：路线网关配置

本页是[DIY 教程](WAVESHARE_DIY_GUIDE.md)的配套步骤。只看 App 演示可以稍后配置；
搜索真实目的地、候选路线、偏航重算和路况需要本页的服务。

```text
iPhone → 你自己的 HTTPS 网关 → 高德 Web 服务
              保存 Key
```

## 1. 准备 Key 和服务环境

在[高德开放平台 Web 服务 Key 指南](https://lbs.amap.com/api/webservice/guide/create-project/get-key)中，
按当前控制台流程创建应用和 **Web 服务** 类型 Key，确认 POI 搜索与驾车路线权限/配额。
这里不是 iOS SDK Key，也不是网页 JavaScript Key。

需要一台可持续运行 Node.js 的服务器和一个自己的域名。以下示例按 Linux + Node.js 24+ + Caddy 2 编写；
本地 Mac 可先测后端，但电脑休眠、关机或手机离开局域网后不能把它当持续可用的公网服务。
GitHub Pages 只托管静态内容，不能运行本 Node 网关。

在服务器按 [Node.js 官方下载](https://nodejs.org/en/download)和
[Caddy 官方安装说明](https://caddyserver.com/docs/install)安装工具，并准备 Git。
服务器应能访问高德 API。下面使用 `nav.example.com` 作为占位域名，必须换成自己的地址。

## 2. 下载代码并验证后端

在服务器当前用户目录执行，后端测试不需要初始化 LVGL：

```sh
git clone https://github.com/mx3353672833-debug/moto-gps-waveshare.git
cd moto-gps-waveshare
node --version
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
```

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
不代表实际 Key 和上游网络已验证。后面仍需要搜索和路线请求。

## 3. 给手机提供 HTTPS 地址

将自己的域名 A/AAAA 记录指向服务器，在使用的防火墙/安全组允许该部署所需的 80/443，
确保端口未被其他服务占用。只为反向代理暴露 HTTPS，Node 保持监听本机。
按安装方式打开 Caddyfile（Linux 服务通常在 `/etc/caddy/Caddyfile`），将以下站点块合并进去：

```caddyfile
nav.example.com {
    handle_path /moto-gps/api/* {
        reverse_proxy 127.0.0.1:8787
    }
}
```

`handle_path` 会移除 `/moto-gps/api` 前缀，让后端收到 `/healthz`、`/v1/places` 等实际路由。
Caddy 在域名、端口和存储条件满足时申请并维护 HTTPS 证书。依据：
[路径处理](https://caddyserver.com/docs/caddyfile/directives/handle_path)、
[自动 HTTPS](https://caddyserver.com/docs/automatic-https)。

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

打开 `platforms/ios/project.yml`，设置：

```yaml
MOTOGPSGatewayBaseURL: https://nav.example.com/moto-gps/api/
```

回到[DIY 教程的 iPhone 安装步骤](WAVESHARE_DIY_GUIDE.md#4-把-app-安装到-iphone)，重新生成工程并 Run。
在 App 搜索附近终点，取得至少一条候选路线，再开始和结束一次导航。
这样才能确认手机定位、公开网关、实际 Key、路线请求和 App 地址全部配合正常。

后续更换 Key 只需要更新服务端环境并重启服务；更换 App 使用的域名/路径需要更新项目配置并重新安装。
接口字段与请求约束见[后端 README](../backend/README.md)，日常操作见[功能说明书](USER_MANUAL.md)。
