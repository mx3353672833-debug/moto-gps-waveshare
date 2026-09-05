# Moto BLE Navigation Protocol v1

状态：实现基线。规范中的常量、编码器、解码器和重组器位于
`shared/ble_protocol`，黄金字节位于
`shared/protocol/fixtures/ble-navigation-v1.golden.txt`。

## 1. 目标与职责边界

手机负责定位、高德路线、路线匹配、交通刷新与偏航重算。ESP32 只接收一个
确定性的显示投影，适配为共享 `moto::nav::NavSnapshot` 后交给
`NavPresenter`。ESP32 不再次运行高德、路线匹配或偏航状态机，也不得把 BLE
字段直接写入 LVGL。

推荐的数据路径是：

```text
iOS 定位/高德/偏航状态机
  -> NavigationSnapshot + RouteGeometry + TrafficDeviation
  -> MapScene（离线道路/建筑滚动窗口，可选）
  -> PhoneToDevice 特征
  -> v1 帧解码与重组
  -> Display Snapshot adapter
  -> moto::nav::NavSnapshot
  -> NavPresenter
  -> moto_nav_ui / LVGL

触摸 / 音乐按键
  -> DeviceCommand
  -> DeviceToPhone 特征
  -> iOS 导航页面或媒体动作
```

`NavigationSnapshot` 是显示快照，不是 `NavEvent`。路线几何只包含当前
heading-up 画面所需的局部窗口，最多 24 点，与
`nav::kRouteViewPointCapacity` 一致。手机仍保留完整 Provider 路线。

## 2. GATT 契约

ESP32 是 peripheral/GATT server，手机是 central/GATT client。UUID 不随硬件
版本、固件构建或用户变化。

| 对象 | UUID | 属性 | 权限/用途 |
| --- | --- | --- | --- |
| Navigation Service | `7e57a000-b50c-4b6a-9c57-40a54e8e1000` | primary service | 可发现 |
| PhoneToDevice / RX | `7e57a001-b50c-4b6a-9c57-40a54e8e1000` | Write、Write Without Response | encrypted write；手机下发状态、导航、路线、交通、媒体及 ACK |
| DeviceToPhone / TX | `7e57a002-b50c-4b6a-9c57-40a54e8e1000` | Notify | 只允许在加密连接上通知；设备上报命令、ACK、连接状态及心跳 |
| TX CCCD | 标准 `0x2902` | read/write | 写订阅位必须加密；读只暴露当前订阅位，不含任何业务载荷；手机写 `0x0001` 开启 Notify |

RX 不提供 Read/Notify，TX 不提供 Read/Write/Indicate。手机必须先订阅 TX，再发
握手。CoreBluetooth 用 `setNotifyValue(true)` 管理 CCCD；不要把 CCCD 当成另一条
应用特征。

EVT 的一致安全基线是 LE Secure Connections + bonding + Just Works，即 LE
Security Mode 1 Level 2：RX 使用 encrypted-write 权限，TX 只在加密连接上 Notify，
CCCD 的写操作也要求加密。首次 `setNotifyValue(true)` 或首次 RX 写会触发系统配对；
之后使用 bond 恢复加密，不在每次重连时重新做临时配对。未加密时不能订阅 TX、
接收导航内容或发送媒体命令。

若 EVT 阶段出现手机与设备的 bond 不同步，恢复动作是同时删除 iOS 侧配对记录并
擦除设备侧对应 bond 后重新配对，不能临时移除 characteristic 的加密权限来绕过。
Just Works 不提供 MITM 认证；量产边界仍需设备所有权流程，并在交互能力允许时升级
为 authenticated pairing。CCCD 的可读订阅位不是身份凭据，也不会泄露协议 payload。

每次 GATT 操作传一个完整 v1 frame。`max_frame_size` 指 characteristic value 的
最大字节数，不是 ATT MTU：

```text
max_frame_size = min(local ATT_MTU - 3, peer advertised max_frame_size, 512)
```

ATT MTU 23 时该值为 20，帧净载荷只有 8 字节。禁止依赖 Prepare Write/Long
Write；协议自己的分片在所有 MTU 下行为一致。快照、心跳与媒体状态通常使用
Write Without Response；握手或需要链路层确认的低频控制可使用 Write。发送方
必须串行发送逻辑消息的全部分片，并遵守系统的发送背压。

## 3. 会话与握手

1. BLE 连接建立，完成加密、服务发现和 TX Notify 订阅。
2. 手机生成随机、非零的 32 位 `session_id`，清空收发序号和重组状态，然后在
   RX 发送 `ConnectionStatus(role=Phone, state=Starting)`。
3. ESP32 验证版本区间，取能力交集及双方较小的 `max_frame_size`，在 TX 回送相同
   `session_id` 的 `ConnectionStatus(role=Device, state=Ready)`。
4. 手机发送 `ConnectionStatus(role=Phone, state=Ready)`；之后才能发送显示数据。
5. 任一端收到不同 `session_id`，都必须清除旧的路线窗口、命令去重表、未完成
   分片及序号基准，再重新握手。

没有 v1 版本交集时回 `state=Closing` 并断开。能力位只表示可使用的功能；发送方
只能使用双方能力交集中的消息。

建议心跳间隔为 1000 ms。连续 3000 ms 没有收到任何 CRC 正确的帧即认为协议链路
失活，ESP32 应展示离线状态并清除未完成分片，但 BLE 的物理连接状态仍由平台层
管理。

## 4. 帧格式

所有多字节整数均为 little-endian。每个分片都有独立 CRC。

| 偏移 | 大小 | 字段 | v1 规则 |
| ---: | ---: | --- | --- |
| 0 | 1 | magic | 固定 `0xB7` |
| 1 | 1 | protocol_version | 固定 `1` |
| 2 | 1 | message_type | 见消息表 |
| 3 | 1 | flags | bit0 START、bit1 END、bit2 ACK_REQUESTED、bit3 URGENT；其余必须为 0 |
| 4 | 2 | sequence | 每个方向独立的非零逻辑消息序号 |
| 6 | 2 | fragment_offset | 本分片净载荷在完整 payload 中的字节偏移 |
| 8 | 2 | message_length | 完整 payload 字节数，不含帧头和 CRC |
| 10 | N | fragment_payload | 长度由 GATT value 总长度减 12 得出 |
| 10+N | 2 | CRC16 | 对偏移 `0..9+N` 的 CRC-16/CCITT-FALSE，低字节先发 |

CRC 参数：poly `0x1021`、init `0xFFFF`、refin=false、refout=false、xorout
`0x0000`。标准检查串 `123456789` 的结果为 `0x29B1`。

START 当且仅当 `fragment_offset == 0`。END 当且仅当
`fragment_offset + N == message_length`。单帧消息同时置 START 和 END。
`ACK_REQUESTED` 与 `URGENT` 是逻辑消息属性，所有分片上的值必须相同。

### 4.1 分片与重组

- 一个方向同一时刻只允许一个未完成逻辑消息；禁止在两个逻辑消息之间交织分片。
- BLE 保序，因此接收方只接受紧邻的 `fragment_offset`。缺口、重叠但字节不同、
  类型/总长/标志变化均拒绝。
- 完全相同的已接收分片是重复分片，不追加第二次，也不刷新超时。
- 从最后一个有效新分片起 1000 ms 未完成即丢弃。超时后的 continuation 返回
  `ReassemblyTimeout`，必须等待新的 START。
- 较新序号的 START 可原子取代未完成消息，防止丢包把链路卡死。
- 重组上限默认 4096 字节；v1 业务消息应远小于此值。

### 4.2 序号与去重

收、发方向各维护一个 `uint16` 序号，`0` 保留。发送顺序
`1..65535,1..`。模 65536 的前半区规则判断新旧，因此两次有效消息之间不能跨越
32767 个未观察序号。新 `session_id` 或 BLE 重连会重置序号比较基准。

已完成序号再次出现时返回 `DuplicateMessage`，应用副作用不能再执行。若该消息
请求 ACK，接收方从“最近完成消息 -> ACK”小缓存中重发原 ACK。

### 4.3 应用 ACK

`ACK_REQUESTED` 用于必须确认的 `DeviceCommand`，也可用于路线窗口的原子提交。
接收方在完整 payload 解码并提交后回复 `Ack`。发送方 750 ms 未见匹配 ACK 时，用
同一序号和完全相同的分片重发，最多重试 2 次。仍超时则报告 degraded，不能生成
新 `command_id` 假装成功。

普通 `NavigationSnapshot` 不请求 ACK：新快照自然替代旧快照。`Write` 的链路层
响应不能代替应用 ACK，因为它只确认 characteristic write，不确认重组和业务提交。

## 5. 消息总表

每种逻辑 payload 的第 0 字节都是 `payload_revision=1`。

| type | 名称 | 方向 | 建议频率 |
| ---: | --- | --- | --- |
| `0x01` | ConnectionStatus | 双向 | 握手或状态改变 |
| `0x02` | Heartbeat | 双向 | 空闲时每 1000 ms |
| `0x03` | Ack | 双向 | 对 ACK_REQUESTED 的响应 |
| `0x10` | NavigationSnapshot | 手机→设备 | 值变化时，最高 5 Hz；关键状态立即发 |
| `0x11` | RouteGeometry | 手机→设备 | 路线/局部窗口变化时 |
| `0x12` | TrafficDeviation | 手机→设备 | 交通或偏航变化时 |
| `0x13` | MediaState | 手机→设备 | 曲目/播放变化，进度最高 1 Hz |
| `0x14` | MapScene | 手机→设备 | 前进约 100 m、跨离线分块或路线改变时 |
| `0x20` | DeviceCommand | 设备→手机 | 用户动作，ACK_REQUESTED+URGENT |

## 6. Payload 编码

### 6.1 ConnectionStatus (`0x01`, 固定 17 字节)

```text
u8  revision
u8  role                 1 Phone, 2 Device
u8  state                0 Starting, 1 Ready, 2 Degraded, 3 Closing
u8  minimum_version
u8  maximum_version
u32 capabilities
u32 session_id           non-zero
u16 max_frame_size       13..512，完整 GATT value 大小
u16 heartbeat_interval_ms  >=250
```

能力位：bit0 navigation、bit1 route geometry、bit2 traffic、bit3 media state、
bit4 touch commands、bit5 music commands、bit6 command ACK、bit7 map scene。

### 6.2 Heartbeat (`0x02`, 固定 11 字节)

```text
u8  revision
u32 session_id
u32 monotonic_ms         本会话本端单调时钟低 32 位
u16 status_flags         v1 未定义的位必须发送 0
```

`monotonic_ms` 只用于诊断和停滞检测，不用于跨设备对时。

### 6.3 Ack (`0x03`, 固定 6 字节)

```text
u8  revision
u16 acknowledged_sequence
u8  status               0 Ok, 1 Unsupported, 2 InvalidState,
                         3 Failed, 4 Duplicate
u16 command_id           非 DeviceCommand 的 ACK 为 0
```

ACK 自己使用新的本方向 `sequence`，且不再请求 ACK。

### 6.4 NavigationSnapshot (`0x10`)

```text
u8  revision
u8  state                 0 Idle, 1 Acquiring, 2 Planning,
                          3 Navigating, 4 Rerouting, 5 Arrived
u8  network               0 Offline, 1 Connecting, 2 Online
u8  display_page          0 Navigation, 1 Speed, 2 Compass, 3 Music
u8  maneuver              0..12，与 shared ManeuverType 顺序一致
u8  traffic               0 Unknown, 1 FreeFlow, 2 Slow,
                          3 Congested, 4 Severe
u16 flags
u32 route_token
u32 route_generation
u32 maneuver_id
u32 distance_to_maneuver_m
u32 remaining_distance_m
u32 remaining_duration_s
u32 route_progress_m
u32 total_distance_m
u16 speed_deci_kph        0.1 km/h
u16 speed_limit_kph       0 表示未知
u16 heading_cdeg          0..35999，0.01 degree
u16 accuracy_dm           0.1 m
u16 cross_track_dm        0.1 m，饱和到 6553.5 m
u8  roundabout_exit       0 未知，否则 1..32
u8  road_name_bytes       0..63
u8[] road_name            UTF-8，无 NUL
u8  instruction_bytes     0..95
u8[] instruction          UTF-8，无 NUL
```

固定部分连同两个字符串长度字节为 53 字节。flags：

| bit | 名称 | 对应 `nav::NavSnapshot` |
| ---: | --- | --- |
| 0 | HasDestination | `has_destination` |
| 1 | HasFix | `has_usable_fix` |
| 2 | GnssStale | `gnss_stale` |
| 3 | OffRoute | `off_route` |
| 4 | HasNextManeuver | `has_next_maneuver` |
| 5 | RouteRequestInFlight | `route_request_in_flight` |
| 6 | TrafficRequestInFlight | `traffic_request_in_flight` |
| 7 | HasRouteView | 可使用匹配的已提交 RouteGeometry |

`route_token` 是完整 UTF-8 `RouteBundle::route_id` 的 FNV-1a 32-bit：offset basis
`2166136261`，逐字节 xor 后乘 `16777619`，自然按 uint32 溢出；若结果为 0 则改为
1。无路线时 token/generation 为 0；HasRouteView 时两者必须非零。

### 6.5 RouteGeometry (`0x11`)

```text
u8  revision
u8  coordinate_system     固定 2 = GCJ-02
u32 route_token
u32 route_generation
u16 chunk_index            从 0 开始
u16 chunk_count            >=1
u16 first_point_index
u16 total_point_count      1..24
i32 view_origin_lat_e6
i32 view_origin_lon_e6
u8  point_count            1..24
i32 first_lat_e6
i32 first_lon_e6
svarint remaining_lat_delta_e6[]
svarint remaining_lon_delta_e6[]
```

E6 表示 degree × 1,000,000。首点是绝对 GCJ-02 坐标，后续点相对前一点使用
ZigZag + canonical unsigned LEB128：`0 -> 0, -1 -> 1, +1 -> 2`。非 canonical、
超过 5 字节或解码溢出的 varint 必须拒绝。

同一窗口可因很小的 negotiated frame/message budget 拆成多个逻辑
`RouteGeometry`，但总点数仍不超过 24。chunk 必须按顺序到达，第一块的
`first_point_index=0`，最后一块正好结束在 `total_point_count`。接收方先放入 staging
buffer；仅在所有 chunk 的 token、generation、origin、count 都一致并完整到达后才
原子替换已提交窗口。更高 generation 的 chunk 0 可放弃旧 staging。

正常实现建议把整个 24 点窗口作为一个逻辑 payload，再交给通用 BLE frame 分片；
不要无必要地同时使用两层分块。

### 6.6 TrafficDeviation (`0x12`)

```text
u8  revision
u32 route_token
u32 route_generation
u16 flags
u32 observed_at_ms
u32 remaining_duration_s
u16 cross_track_dm
u8  segment_count         0..64
repeat segment_count:
  u24 start_offset_m
  u24 length_m            必须 >0
  u8  traffic_level
```

flags：bit0 TrafficChanged、bit1 OffRoute、bit2 Rerouting、bit3 RouteInvalidated。
segment 按 `start_offset_m` 升序、不可重叠，偏移相对完整路线起点。token/generation
不匹配当前快照时整条消息丢弃。RouteInvalidated 会立即清除已提交几何；Rerouting
会把显示状态切到 rerouting，直到后续完整 `NavigationSnapshot` 确认新状态。

### 6.7 MediaState (`0x13`)

```text
u8  revision
u8  flags                  bit0 connected, bit1 playing,
                           bit2 like available, bit3 liked
u32 track_token
u16 position_s
u16 duration_s
u8 + UTF-8 source_name     <=31 bytes
u8 + UTF-8 track_title     <=63 bytes
u8 + UTF-8 artist_name     <=47 bytes
```

`track_token` 是手机本会话内稳定的曲目标识，换曲时必须变化；它不是全局媒体 ID。

当前 iOS 适配器把本消息投影到公开的
`MPMusicPlayerController.systemMusicPlayer`，因此只控制系统“音乐”App（含其
Apple Music 播放队列）的上一首、播放/暂停和下一首。`MPRemoteCommandCenter` 是让
当前 Now Playing App **接收**耳机/系统命令的接口，不是向其他 App 注入命令的接口；
所以 v1 不宣称能从普通 iOS App 稳定控制网易云音乐等第三方播放器。Apple 也未给
system music player 暴露修改当前歌曲“喜欢”的公共接口，iOS 必须发送
`like available = 0`，设备隐藏 LIKE，而不是本地假成功。

### 6.8 MapScene (`0x14`)

`MapScene` 是可选的、完整替换的本地小地图场景，不是全市数据库。iPhone 在离线包
中查询当前位置周围 500–800 m，裁剪和简化以后发送；ESP32 只保留最新一帧。坐标
固定使用 GCJ-02，与高德规划路线对齐。

```text
u8  revision
u8  coordinate_system       固定 2 = GCJ-02
u32 scene_revision          非零；新窗口严格递增
i32 view_origin_lat_e6
i32 view_origin_lon_e6
u16 radius_m                100..1500
u8  road_count              0..24
u8  building_count          0..16

repeat road_count:
  u8 road_class             0 motorway, 1 primary, 2 secondary,
                            3 residential, 4 service, 5 other
  u8 point_count            2..255；全帧道路点合计 <=192
  repeat point_count:
    svarint lat_delta_e6     相对 view_origin
    svarint lon_delta_e6

repeat building_count:
  u8 building_class         0 generic, 1 landmark, 2 parking
  u8 point_count            3..255；全帧建筑点合计 <=128
  repeat point_count:
    svarint lat_delta_e6     相对 view_origin
    svarint lon_delta_e6
```

建筑最后一点不得重复第一点，闭合边由渲染器补上。所有点必须在 origin 的经纬各
`±100000 E6` 安全界内；App 仍应按 `radius_m` 做实际圆/矩形裁剪。完整 payload
建议带 `ACK_REQUESTED`，在校验、解码并提交成功后才回复 Ack。更高
`scene_revision` 原子替换旧窗口；旧 revision、半包和 CRC 错误均不改变屏幕。

本编码对 192 个道路点 + 128 个建筑点通常约 1.3–2.2 KiB；在 185-byte GATT value
下约 8–14 个协议分片。它不应按定位帧率发送：前进约 100 m、靠近窗口边缘或跨
500 m 离线分块时刷新一次即可。当前共享 C++ codec 已实现本消息，但 iOS 离线包
查询器、ESP32 staging/提交及建筑 LVGL 图层仍属于后续接线工作。

### 6.9 DeviceCommand (`0x20`, 固定 13 字节)

```text
u8  revision
u8  kind
u16 command_id             本 session 内非零、单调；副作用去重键
u8  page
u16 x                      触摸坐标；未知为 0xFFFF
u16 y                      触摸坐标；未知为 0xFFFF
u32 event_time_ms           设备单调时钟低 32 位
```

kind：0 PageSelected、1 Tap、2 LongPress、3 SwipeLeft、4 SwipeRight、5 SwipeUp、
6 SwipeDown、16 MusicPrevious、17 MusicTogglePlayback、18 MusicNext、19 MusicLike。
音乐命令沿用 `shared/nav_ui` 的四个动作。触摸坐标是屏幕像素；滑动方向已经归一化，
手机不应再次用坐标推断方向。

手机以 `(session_id, command_id)` 去重；成功、失败或重复都回复 Ack。UI 动作必须在
成功提交后才 ACK Ok。

## 7. Display Snapshot 到共享 NavSnapshot

适配器必须做值转换，不得直接操作 `moto_ui_state_t`。推荐映射如下：

| BLE 字段 | `moto::nav::NavSnapshot` |
| --- | --- |
| state/network/display_page | 同名枚举逐项映射，不依赖裸 `static_cast` |
| flags bit0..6 | 对应七个 bool 字段 |
| `speed_deci_kph` | `speed_mps = value / 36.0F` |
| `heading_cdeg` | `heading_deg = value / 100.0F` |
| `accuracy_dm` | `horizontal_accuracy_m = value / 10.0F` |
| `cross_track_dm` | `cross_track_distance_m = value / 10.0F` |
| 距离/时长/进度/总长 | 转为对应 double/u32 字段 |
| maneuver + 文本 | `next_maneuver`；`route_offset_m = route_progress_m + distance_to_maneuver_m` |
| traffic | `traffic_ahead` |
| route_generation | 原值复制为 u32 |

只有以下条件全部满足才置 `has_route_view=true`：快照 bit7 已置位、几何已经完整
提交、几何 token/generation 与快照一致、点数至少 2。之后将 E6 origin/points 除以
1,000,000 写入 `route_view_origin` 与 `route_view_points`。不匹配时保留导航数字和
转向信息，但隐藏路线线条，绝不能沿用旧路线。

`NavSnapshot.position/destination/now_ms/last_fix_ms/last_traffic_update_ms` 不被当前
`NavPresenter` 消费，显示端适配器可置零或维护本地诊断值。手机仍是这些事实的所有
者；不能因此在 ESP32 再启动第二套 NavCore 状态机。

## 8. 错误、版本与资源边界

- magic、版本、CRC、reserved flags、长度或范围错误时整帧/整消息拒绝，不做部分
  更新。
- 字符串长度以 UTF-8 字节计；渲染层可替换无效 UTF-8，但协议层不得越界读取。
- v1 解码器拒绝未知 payload revision、未知消息类型及 trailing bytes。
- 增加可选消息类型可保留 frame v1；改变字段顺序、单位、坐标系、枚举数值或 CRC
  必须升 frame protocol version 或该消息的 payload revision。
- 端点必须对接收缓存、点数、segment 数和字符串先做上限检查再分配。
- CRC 只检测传输错误，不提供身份认证；身份和机密性依赖 BLE 加密/bonding。

## 9. 黄金帧

以下均是 lowercase hex，多字节字段 little-endian。

连接 payload：

```text
01010101017f00000078563412b900e803
```

该 payload 的单帧编码（sequence=1）：

```text
b701010301000000110001010101017f00000078563412b900e8035469
```

MusicNext 命令 payload：

```text
0112341203ffffffff40302010
```

在 20-byte characteristic value 下，使用 sequence `0x1234`、
ACK_REQUESTED+URGENT 的两个分片：

```text
b701200d341200000d000112341203ffffff6793
b701200e341208000d00ff40302010ef35
```

机器可读的完整向量以 fixture 为准。修改任何 v1 字节都应先解释兼容性影响，并由
两端共同更新 fixture。

## 10. 本地验证

```sh
cmake -S . -B build/native -DMOTO_BUILD_WEB=OFF -DMOTO_BUILD_TESTS=ON
cmake --build build/native --target ble_protocol_tests
./build/native/tests/native/ble_protocol_tests
```

测试覆盖全部消息 round-trip、固定 UUID/属性、CRC 检错、20-byte 分片、重组、重复
与冲突分片、缺口、超时、较新 START 取代、序号回绕、输入边界和黄金字节。
