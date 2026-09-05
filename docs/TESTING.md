# 测试与复现

以下均为本地测试/构建，不会刷板或向手机安装 App，也不会访问真实高德服务。
先初始化 `third_party/lvgl` 子模块。

## C++ / 共享核心

```sh
cmake -S . -B build/native -DMOTO_BUILD_WEB=OFF -DMOTO_BUILD_TESTS=ON
cmake --build build/native --parallel 4
ctest --test-dir build/native --output-on-failure
```

覆盖坐标、路线状态机、协议、Presenter、连接 epoch、航向融合和 PhoneNavBridge。
测试中使用 Host Stub，不代表真实 LVGL 绘制/蓝牙时序已验证。

## Node 网关

```sh
npm --prefix backend test
```

使用 mock fetch / fixture 检查请求、响应、重试、POI、路线候选与错误边界，不需要 Key。

## Swift

```sh
swift test --package-path platforms/ios
```

SPM 仅覆盖可移植 Swift 核心，不能替代完整 App 编译或设备测试。
完整 App 模拟器构建见 `platforms/ios/README.md`。

## 地图数据

```sh
node scripts/generate_jinan_demo_fixture.mjs --check
node scripts/offline_map/validate_jinan_sqlite.mjs
```

建议 Node.js 24+。只检查已随仓库提供的数据，不拉取高德响应。
修改地图应重跑生成器，并按 ODbL 保留数据来源和署名。

## Web 和 ESP32

Web 需要 Emscripten，执行 `./scripts/build_web.sh`。
ESP32 需要 ESP-IDF 5.5.5，执行 `idf.py -C platforms/esp32 build`。
初次构建会下载公开 SDK/组件；这些网络访问不需要作者的服务凭证。

CI 不烧录、不部署、不使用账号密钥。真实验收还需覆盖：手机断网/锁屏/蜂窝切换、
BLE 断连恢复、预览路线与圆屏路线一致性、跨城路线、快速转向、供电和长时间运行。
