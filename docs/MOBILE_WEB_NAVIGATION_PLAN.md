# 手机网页版真实导航验证方案（非正式 App）

> 这条线只保留为前台网页和共享 UI 的开发验证工具。正式产品使用原生 iOS App
> 连接 ESP32；当前 App 测试版另有明确标注的内置导航演示，但正常/生产导航始终使用
> 真实 Core Location 和高德路线。手机锁屏后的可靠定位与 BLE 由原生能力承担。

## 1. 交付定义

主入口 `https://maler.top/moto-gps/` 不再是导航演示器，而是手机直接使用的前台导航：

```text
打开网页
  → 允许精确位置
  → 搜索并选择目的地
  → 请求高德普通驾车路线
  → 查看路线距离、预计时间和实时路况
  → 开始导航并申请方向权限/屏幕常亮
  → 连续定位、车头朝上显示、转向推进
  → 连续偏航确认后从当前位置重算
  → 每 60 秒刷新路线分段路况
  → 到达或手动结束
```

手机负责 Web 平台能力，导航业务仍进入共享核心。网页不能用自己的第二套路由匹配或偏航逻辑取代固件代码。

## 2. 当前完成状态

| 能力 | 状态 | 迁移等级 | 说明 |
| --- | --- | --- | --- |
| 手机目的地搜索与结果列表 | 已完成 | C/B | Web 外层和原生 iOS 均已实现；iOS 使用独立平台适配器，不复制导航核心 |
| 高德 POI 2.0 服务端接入 | 已完成并已配置 | B | 网关已部署；Key 仅放服务端环境变量 |
| POI GCJ-02 → WGS84 反算 | 已完成并测试 | B | 避免目的地进入路线 Provider 后二次偏移 |
| 高德普通驾车 Route v2 | 已完成并已配置 | B | `strategy=32`，读取 `cost/polyline/navi/tmcs` |
| 浏览器实时定位 | 已完成 | B | `watchPosition` → 标准 `GnssFix` |
| iPhone 低速方向 | 已完成浏览器适配 | B | 运动时 GPS heading；低速尝试 `webkitCompassHeading` |
| 真实 RouteBundle/GNSS 输入 WASM | 已完成 | B/A 边界 | 输入桥是 B；之后的核心、投影和 LVGL 是 A |
| 转向推进与车头朝上画面 | 已完成 | A | 共享 `NavCore → Presenter → LVGL` |
| 偏航自动重算 | 已完成 | A+B | 共享核心连续确认；平台执行新路线请求 |
| 周期路况刷新 | 已完成首版 | A+B | 核心每 60 秒调度；平台 fresh route 获取最新 `tmcs` |
| Screen Wake Lock | 已完成 | C | 只改善手机前台使用，不进入固件 |
| 后台/锁屏持续定位 | Web 不可实现 | 不适用 | iOS/Web 平台限制，不能写成待修复 Bug |
| 线上真实高德数据 | 路线请求已验证，待 iPhone 实机 | B | 健康检查就绪，生产 `/v1/routes` 已返回真实 `amap` 路线；附近 POI 和完整骑行流程仍待手机验收 |

## 3. 数据与控制链

```text
手机网页（C/B）
  ├─ Geolocation / DeviceOrientation / Wake Lock
  ├─ POI 搜索与路线确认 UI
  └─ HTTPS 平台适配
          ↓ WGS84 Fix / RouteBundle
WebAssembly 共享层（A）
  NavApp → NavCore → NavPresenter → LVGL 466×466 RGB565
          ↓ NavCommand
手机网页执行路线/路况请求（B）
          ↓
maler.top 同域 Node 网关
  ├─ Key 隔离、验证、限流、超时
  ├─ POI GCJ-02 → WGS84
  └─ AMap Route v2 → RouteBundle v1
```

当前正式样机不让 ESP32 直接替换浏览器定位，也不安装 GNSS。原生 iOS App 使用 Core Location 和 HTTPS 驱动共享导航核心，再通过 BLE 把完整显示快照发送给 ESP32。共享导航核心、偏航策略、路线投影、四页圆屏 UI 和 RGB565 资源继续复用。

原生 iOS 当前会先取得一份手机位置用于地点排序，把 WGS84 经纬度随搜索请求发给网关。网关优先返回当前位置 50 km 内的结果，并在近场无结果时回退全国/区域文本搜索；定位未授权或暂不可用时也可直接使用回退搜索。App 测试版的“演示导航”在线时逐次请求 D 栋到浪潮总部的实时高德路线，失败时回退到同地点的 canonical OSM 夹具；该夹具的白色选中路线与灰色周边道路均来自 OpenStreetMap way（`© OpenStreetMap contributors`，ODbL），高德响应不固化。实际“开始导航”仍调用真实定位与线上 Provider，两条路径在界面上明确区分。

## 4. 手机端状态机

### 4.1 设置页

- 后端、WASM 和网络启动时并行检查。
- 用户点击“启用定位”后才申请精确位置。
- 只有真实 Provider、WASM 和当前定位同时就绪时开放搜索框。
- 搜索至少两个字，480 ms 防抖，取消旧请求；服务端另有限流。
- 服务缺 Key、定位拒绝、精度不足、离线和接口超时必须分别显示，不能统一伪装为“无结果”。

### 4.2 路线确认页

- 选中 POI 后立即把目的地和当前 Fix 送入共享 `NavApp`。
- 共享核心发出 `RequestRoute`；网页只执行命令并回填 `RouteReady/RouteFailed`。
- 显示同一 LVGL 圆屏预览，以及预计时间、总距离和最差分段路况。
- 路线成功前禁用“开始导航”；不显示合成路线兜底。

### 4.3 导航页

- “开始导航”点击内申请 iOS 方向权限和 Screen Wake Lock。
- 圆屏占据主要视区，外层只保留结束、剩余距离、到达时间、连接和精度。
- `visibilitychange → hidden` 视为导航数据可能暂停；恢复前台后重新申请 Wake Lock、强制取得当前位置并立即触发核心检查。
- 网络断开时沿用内存中的旧路线，恢复后处理核心积压的重算/路况命令。

## 5. 偏航与路况

### 偏航

- 默认要求定位精度不差于 50 m。
- 距路线超过 45 m 且连续 3 个可靠 Fix 才进入重算；低于 25 m 清除确认计数。
- 重算请求使用最新 WGS84 位置、原目的地和新 `request_id`。
- 旧响应由共享核心按活动编号丢弃；重算失败继续显示旧路线并退避重试。

### 路况

- 首版每 60 秒由共享核心发出刷新命令。
- 网页从当前位置重新调用高德驾车路线，读取 fresh `tmcs` 和剩余耗时。
- 首版只更新旧路线上的路况提示与 ETA，不因一次刷新立即切换几何；偏航时才原子替换整条路线。
- 后续若做“拥堵收益足够才自动换路”，收益阈值和换路策略必须进入共享核心，不能只写在网页。

## 6. iPhone 明确边界

- HTTPS 前台页面可以用 `watchPosition` 连续导航。
- Screen Wake Lock 只能请求保持屏幕不自动熄灭；用户锁屏、切后台、低电量或系统策略仍可释放。
- 页面 hidden 后，浏览器不会可靠交付定位更新，定时器也可能被挂起；PWA/添加到主屏幕不会获得原生后台定位权限。
- 因此网页只承诺“保持前台亮屏时直接导航”，不能承诺“锁屏放包里仍由网页持续导航”。后一个目标已经转由原生 iOS App 的后台定位与 BLE 状态恢复实现，并必须通过真机长时间测试。

官方依据：

- [W3C Geolocation](https://www.w3.org/TR/geolocation/)
- [W3C Screen Wake Lock](https://www.w3.org/TR/screen-wake-lock/)
- [Safari 16.4 Release Notes](https://developer.apple.com/documentation/safari-release-notes/safari-16_4-release-notes)
- [WebKit 页面挂起与功耗](https://webkit.org/blog/8970/how-web-content-can-affect-power-usage/)

## 7. 高德配置与授权门槛

线上服务运行在 `127.0.0.1:3023`，由 Nginx 暴露为 `/moto-gps/api/`。截至 2026-09-04 已配置真实高德 Provider，`/moto-gps/api/healthz` 返回 `ready_for_live_navigation: true`。若以后移除 Key，服务仍会回到 `disabled` Provider，绝不使用 fixture 冒充真实路线。

启用需要：

1. 创建“Web 服务 API”类型 Key；
2. 建议绑定服务器出口 IP 白名单；
3. 把 Key 写入服务器 `/etc/moto-gps-api.env`，权限保持 `600`；
4. 设置 `MOTO_PROVIDER=amap` 并重启服务；
5. 用手机实测 POI、真实路线、偏航、60 秒路况刷新和配额错误。

Key 不得进入 Git、HTML、JavaScript、WASM 或 ESP32 固件。

普通 Web Service Key 只解决技术调用，不自动获得车载产品授权。高德当前协议对车载设备/投射及数据缓存有限制；进入车把实体设备或公开产品前必须取得书面许可：[高德开放平台服务协议](https://lbs.amap.com/pages/terms/)。普通驾车路线也不保证规避摩托车禁限行。

## 8. 验收顺序

1. 已完成：原生导航核心 4/4 测试。
2. 已完成：后端转换、坐标、验证与网关 15/15 测试。
3. 已完成：WASM Release 构建及手机尺寸 mock 全流程。
4. 已完成：线上静态页、WASM MIME、位置/常亮权限头、Node 服务、Nginx 反代检查。
5. 已完成服务端：生产 `/v1/routes` 真实高德请求冒烟；待 iPhone 实机完成附近 POI 与整段路线验收。
6. 待实机：一台 iPhone 前台步行/骑行短途验证偏航重算和恢复前台。
7. 待实机：持续 1–2 小时亮屏导航、弱网、切网、配额和热量测试。
8. 后续：扩展任意中文道路名字库、长路线匹配性能和交通收益换路策略。
9. 后续：屏幕、ESP32-S3、电源与实体一致性测试；GNSS 只在未来恢复完全独立终端路线时另行评估。
10. 最后：外壳制作方案，继续搁置到所有硬件尺寸和热设计稳定。
