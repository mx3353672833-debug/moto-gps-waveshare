# 架构与数据边界

```text
iPhone CoreLocation (WGS84) ── HTTPS ── Node gateway ── AMap
             │                              │           │
             │       RouteBundle (GCJ-02) ◀──┴───────────┘
             ▼
共享 C++ NavCore / NavApp（iPhone 中执行进度与偏航逻辑）
             │
       BLE snapshot + route + map window
             ▼
ESP32 PhoneNavBridge + QMI8658 相对角速度
             ▼
共享 NavPresenter → LVGL → CO5300 466×466
```

iPhone 是路线与位置的权威端，圆屏不是另一个独立路线规划器。
BLE 服务与帧格式见 `shared/protocol/ble-navigation-v1.md`。
触摸切页和音乐按键沿 BLE 反向传给 iPhone，后者调用 Apple Music 播放控制。

济南 OSM SQLite 库只存放在 iPhone。手机查询车辆周围的道路、建筑，再将有限容量的
窗口传给圆屏；它是背景场景数据，不提供离线搜索、全国算路或实时路况。
显示前统一坐标系，不能把 WGS84 和 GCJ-02 的点直接混绘。

Web 使用同一份 C++ Presenter 与 LVGL 编译成 Wasm；HTML 只做平台控制外壳。
但 iOS 原生路线全览、系统权限、蓝牙和 Apple Music 控制是平台功能，不会原样运行在
浏览器或 ESP32 上。共享 UI 不代表硬件刷新率或色彩已经像素级验收。

## 隐私与部署

- 高德 Key 只在自己部署的后端，不写入 App、网页或圆屏固件。
- 真实请求需要将起点/目的地发送给自己的网关与高德；并非完全离线私密导航。
- 不上传作者服务器配置、签名、已配对设备记录和原厂整片 Flash 备份。
- 默认网关被替换成不可用示例，避免第三方构建消耗作者的服务配额。
- 网关当前只有基础输入校验、限流和 CORS；**CORS 不是认证**。公开部署前必须自行
  配置访问控制、TLS、上游配额限制与监控，不要无保护公开高德代理。
- BLE Just Works + bonding 不提供 MITM 身份验证。设备所有权绑定、清除配对与
  换手机流程仍需进一步产品化。
