# 首次公开快照检查 · 2026-09-05

检查在独立的公开源码副本内完成，没有复用原工程的构建目录。

| 检查 | 结果 |
| --- | --- |
| CMake C++ 原生测试 | 8 / 8 通过 |
| Node 后端测试 | 33 / 33 通过；未访问真实高德服务 |
| Swift Package 核心测试 | 10 / 10 通过 |
| C++ / Swift 济南演示夹具一致性 | `generate_jinan_demo_fixture.mjs --check` 通过 |
| OSM SQLite 完整性 | 通过；47,468 条道路、26,702 个建筑轮廓 |
| ESP32 完整构建 | ESP-IDF 5.5.5 成功生成 bootloader、分区表、应用；未刷写设备 |
| iOS App 完整构建 | Xcode 26.6，iOS Simulator，关闭签名；构建成功，未安装到手机 |
| Web 完整构建 | Emscripten / CMake 成功生成共享 LVGL Wasm 运行时 |
| 发布内容扫描 | Gitleaks 8.30.1：未发现密钥；另核对本机路径、签名、私有服务地址和文件范围 |

编译有第三方头文件等警告，不等于零警告构建。以上结果只适用于该公开快照，
不表示已通过真实道路、iPhone 锁屏保活、跨城路线、功耗或防水验收。
最新已知问题见 `KNOWN_ISSUES.md`；复现命令见 `TESTING.md`。
