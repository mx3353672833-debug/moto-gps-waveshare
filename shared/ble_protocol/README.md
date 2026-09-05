# Shared BLE protocol

`moto_ble_protocol` 是手机与 ESP32 共用的 v1 二进制 codec。公共入口是
`include/moto/ble_protocol/ble_protocol.hpp`，包括固定 GATT UUID、消息 DTO、CRC、
frame 分片/重组、序号和 link watchdog。

协议语义、GATT 权限、ACK/超时规则及 `NavigationSnapshot -> nav::NavSnapshot ->
NavPresenter` 映射见 [`../protocol/ble-navigation-v1.md`](../protocol/ble-navigation-v1.md)。
跨语言实现应以黄金 fixture 为字节兼容基准，不能只依赖本语言 round-trip。

可选的济南离线小地图使用 `MapScene (0x14)`：手机裁剪并发送一个完整替换的
道路/建筑滚动窗口，ESP32 不保存或查询全市数据库。容量、生成样本和当前尚未接线
的边界见 [`../demo_fixture/JINAN_OFFLINE_MAP_MVP.md`](../demo_fixture/JINAN_OFFLINE_MAP_MVP.md)。

独立构建 codec：

```sh
cmake -S shared/ble_protocol -B build/ble-protocol
cmake --build build/ble-protocol
```
