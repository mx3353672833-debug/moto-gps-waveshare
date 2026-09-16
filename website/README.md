> **语言 / Language:** 中文 · [English](README.en.md)

# MOTO GPS 项目官网

正式地址：<https://maler.top/moto-gps/>。

静态 HTML、CSS 和少量原生 JavaScript，无安装依赖、无分析脚本、无远程字体。包含产品介绍、原生 App 截图、圆屏页面、在线与离线地图说明、三步上手、开发进展和 13 个可搜索的常见问题。没有 JavaScript 时仍可阅读全部内容并展开问题。

```sh
python3 -m http.server 4187 --directory website
```

打开 `http://127.0.0.1:4187/`。发布文件只有 `index.html`、`site.css`、`site.js` 和 `assets/`；README 不必放到公开站点。

## 与网页调试工具共存

官网部署在原 `/moto-gps/` 静态路径。首次部署前将原导航工具的 `index.html` 原样保存为 `ride.html`；保留其 `app.js`、`styles.css`、`wasm-loader.js`、`manifest.webmanifest` 和 `runtime/`。它们仍在同一目录，因此相对的资源与 `./api/` 地址不变。新官网使用独立的 `site.css` / `site.js`，不替换旧工具资源。

从零部署时，先按 `platforms/web/shell/README.md` 构建并部署调试工具，再按上述方式保留 `ride.html`、放入官网。只运行这里的本地静态预览时不会自动包含调试工具；该入口需要完整部署。

旧的 `?demo=...`、`?deviceState=...`、`?api=...` 链接由 `site.js` 保留参数转到 `ride.html`。该跳转只支持旧入口兼容，不扩展调试工具原有功能。网页调试版需前台亮屏，不能替代原生 App 的后台定位或蓝牙圆屏能力。`/moto-gps/api/` 的独立反向代理保持原配置。

部署时先备份现有站点，上传资源和样式，最后替换首页；验证官网、`ride.html`、旧工具脚本、WASM 和 API 健康状态后再结束。不要向站点根目录使用会删除旧工具文件的同步选项。

## 内容与图片

- 外观图来自已有项目品牌资产，明确标为概念示意；图片中的限速不是已经接通的功能。
- App 截图来自开发验证，圆屏数字为演示数据。保留数据提供方署名。
- 不添加尚不存在的 App Store / TestFlight 下载按钮。
- 不把私有签名、设备记录、测试日志、含个人资料的截图或待补身份信息的隐私政策草稿放进公开目录。
- 本页面不代替 App 正式隐私政策；正式分发的主体、联系方式和供应商处理安排仍需补全。

检查：`node --check website/site.js`；本轮已检查所有本地资源、页面锚点、桌面与 390px 手机布局、FAQ 搜索/无结果/清空恢复，以及旧查询入口的跳转。
