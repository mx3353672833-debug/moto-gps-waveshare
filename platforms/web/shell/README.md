# Web 调试外壳

这是仅属于网页平台的场景控制台。圆屏界面由共享 LVGL 代码编译为 WebAssembly 后接管画布；本目录只实现浏览器平台外壳，不是 iPhone 后台 BLE 伴侣。

## 本地预览

先初始化 LVGL 子模块、安装并激活 Emscripten，再在仓库根目录构建共享 LVGL WebAssembly 运行时：

```sh
./scripts/build_web.sh
python3 -m http.server 4173 --directory .
```

浏览器打开本机 HTTP 地址（不要通过 file:// 打开）：

```text
http://127.0.0.1:4173/platforms/web/shell/index.html
```

调试外壳不依赖外部字体或框架。WASM 必须通过 HTTP 加载；未构建时页面保留临时接线画面并明确显示“WASM 未构建”。

本机只看圆屏导航模拟、不启用定位和路线服务时，可打开：

```text
/platforms/web/shell/index.html?demo=1
```

连接生命周期也由同一份 LVGL 固件界面渲染，可用以下验收地址复现：

```text
/platforms/web/shell/index.html?deviceState=offline
/platforms/web/shell/index.html?deviceState=connecting
/platforms/web/shell/index.html?deviceState=success
/platforms/web/shell/index.html?deviceState=ready
/platforms/web/shell/index.html?deviceState=planning
```

`success` 是真实的短暂绿色过渡，约 0.9 秒后自动进入“准备出发”；
`ready` 打开后同样先播放一次连接成功，再停留在“准备出发”。

## WASM 接管约定

固定挂载点为：

```text
#canvas (466 × 466)
```

WASM 初始化之前调用：

```js
const canvas = window.MotoNavShell.claimCanvas();
```

`claimCanvas()` 会用一个干净的新 canvas 替换临时 2D 接线画面，避免旧的 2D context 阻止 Emscripten/SDL 获取 WebGL context。SDL 的 Emscripten 后端固定查找 `#canvas`，因此这个 ID 是平台适配契约的一部分。

外壳会发送以下 `window` 事件；事件名均以 `motonav:` 开头，数据位于 `event.detail`：

- `scene-change`
- `traffic-change`
- `playback-change`
- `progress-change`
- `speed-change`
- `deviation-change`
- `network-change`
- `canvas-claimed`

外部代码也可以读取 `window.MotoNavShell.getState()`，或调用暴露的场景、进度和网络状态方法。调试日志最多保留 40 条。
