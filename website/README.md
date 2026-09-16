> **语言 / Language:** 中文 · [English](README.en.md)

# Glimpse 官网

官网地址：<https://maler.top/>。官网品牌使用 Glimpse；仓库、App 工程和固件中的 MOTO GPS 技术名称保持不变。

前端为静态 HTML、CSS 和原生 JavaScript，无前端安装依赖、分析脚本或远程字体。页面包含
圆屏与 App、动态使用流程、地图下载、自研 B1 主板、9 个可搜索问答和邮件提问表单。
文字与问答展开不依赖 JavaScript；动态演示、问题搜索和表单发送需要 JavaScript。
邮件提问使用独立 Node.js 服务，不是纯静态功能，见[服务说明](server/README.md)。

## 本地预览

```sh
python3 -m http.server 4187 --directory website
```

打开 `http://127.0.0.1:4187/` 可检查静态内容；此命令不会启动邮件服务，表单不能据此验收。
对外只发布 `index.html`、`site.css`、`site.js`、`demos.css`、`demos.js` 和 `assets/`。
不要将 `server/`、环境文件、README 或安装依赖复制到公开静态目录。

## 域名迁移与旧工具

新官网使用独立的 `site-current/` 静态发布目录，部署时将站点根路径指向它。该目录与
原导航工具分开管理，不向整个域名目录使用删除式同步。路径分工如下：

| 路径 | 部署行为 |
| --- | --- |
| `/` | Glimpse 官网；canonical 为 `https://maler.top/` |
| `/moto-gps/` 首页 | HTTP 301 到 `/`，保留旧入口兼容 |
| `/glimpse`、`/glimpse/` 及其子路径 | 撤下旧站，返回 HTTP 410；不继续展示旧内容 |
| `/moto-gps/ride.html` | 原网页调试工具，技术文档保留入口，官网不展示 |
| `/moto-gps/api/` | 原导航网关代理，保持不变 |
| 原 `/moto-gps/` 下的脚本、样式、WASM、运行时与其他调试资源 | 保留原路径和内容 |
| `/api/contact` | 独立邮件 API，本次部署原样代理到本机 `127.0.0.1:3024` |

旧工具的 `app.js`、`styles.css`、`wasm-loader.js`、`manifest.webmanifest` 与 `runtime/`
仍从原路径加载。官网 `site.js` 对带 `demo`、`deviceState` 或 `api` 的旧查询入口，保留
查询和 hash 转到绝对路径 `/moto-gps/ride.html`。该兼容行为不增加旧工具能力；网页调试
仍需前台亮屏，不能替代原生 App 的后台定位和 BLE。

从零搭建调试工具时，按[Web 工具说明](../platforms/web/shell/README.md)单独部署。
官网静态目录本身不包含调试工具，也不提供免费公共导航网关。

## 邮件提问服务

表单提交 JSON 到同源 `/api/contact`。邮件服务仅监听本机回环地址，与导航网关独立。
源码默认端口为 8788，本次部署通过 `CONTACT_PORT=3024` 覆盖；代理目标需同步使用 3024。
服务依赖安装、测试、环境字段、TLS 和代理要求见[服务 README](server/README.md)。
真实 SMTP 凭据和部署配置仅存服务端，不进入网页、源码仓库或公开日志。

接口只有在 SMTP 服务接受发送后才返回成功，前端据此清空表单；失败时保留内容。
HTTP 成功表示已交给邮件服务，不保证收件箱最终投递。单纯静态预览或健康检查不能代替
一次经授权的实际投递检查；不要用重复发信轮询结果。访客邮箱选填，仅用于回复。

## 图片与动态演示来源

- `assets/glimpse-device.png`：基于原有 `assets/concept.png`，使用内置 `imagegen` 编辑为
  透明背景，并移除旧标志与限速标牌。它是外观设计示意，不是量产硬件实拍。
- `assets/videos/`：沿用旧站的 9 段圆屏录屏和 9 张海报，共 18 个文件、927,742 字节
  （约 906 KiB）。保留录屏内容，画面中的路线和数值属于演示数据。
- `demos.js` / `demos.css`：将录屏用于圆屏页面切换和连接／选路／导航流程。仅加载当前
  可见模式的视频，离屏或页面隐藏时暂停；提供播放／暂停，尊重减少动态效果与节流设置，
  播放失败时保留海报。无需把 9 段视频同时下载。
- `assets/board-b1.svg`：由实际 B1 PCB 工程直接导出的顶层布线图。展示的是工程候选，
  不代表已经打样、上电验证或投产；自研板、外壳及安装结构仍暂停。
- App 图片来自开发验证。保留 OpenStreetMap、Protomaps 等数据提供方署名。

官网使用简洁项目说明，已移除旧口号、网页调试入口，以及“没有圆屏也能体验”、读秒／
限速／拥堵进度和 TestFlight 收费相关问答。成品问答保留真实发布状态，并链接 GitHub DIY 教程。
公开源码采用 PolyForm Noncommercial 1.0.0，不标成无限制开源或提供额外商用授权。

不添加尚不存在的 App Store / TestFlight 下载按钮，不发布私人签名、设备记录、测试日志
或待补身份信息的政策草稿。官网不代替 App 正式隐私政策。

## 部署检查

1. 备份现有静态文件与代理配置；上传新的静态发布目录，邮件服务按独立说明部署。
2. 验证代理配置后切换根首页，分别配置旧首页 301、旧 `/glimpse` 410 和两套 API 路由。
3. 检查首页、静态资源、动态演示、FAQ 搜索及表单失败／成功状态；检查手机布局、键盘操作
   和减少动态效果模式。实际邮件验收限一次明确标记的授权测试。
4. 验证旧 `ride.html`、脚本、WASM 与导航 API 仍可用，再确认重定向与 410 响应。

本地语法检查：

```sh
node --check website/site.js
node --check website/demos.js
```

以上是部署检查步骤；文件存在或语法通过不等于线上迁移和邮件投递已经验收。
