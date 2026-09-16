> **Language:** English · [中文](ble-navigation-v1.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Moto BLE Navigation Protocol v1

Status: implementation baseline. The constants, encoders, decoders and reassembler of the
specification are in `shared/ble_protocol`, and the golden bytes are in
`shared/protocol/fixtures/ble-navigation-v1.golden.txt`.

## 1. Goals and responsibility boundary

The phone handles positioning, AMap routes, route matching, traffic refresh and off-route
rerouting. The ESP32 only receives a deterministic display projection, adapts it to the shared
`moto::nav::NavSnapshot` and hands it to `NavPresenter`. The ESP32 does not run AMap, route
matching or the off-route state machine a second time, and must not write BLE fields directly
into LVGL.

The recommended data path is:

```text
iOS positioning / AMap / off-route state machine
  -> NavigationSnapshot + RouteGeometry + TrafficDeviation
  -> MapScene (offline road/building rolling window, optional)
  -> PhoneToDevice characteristic
  -> v1 frame decode and reassembly
  -> Display Snapshot adapter
  -> moto::nav::NavSnapshot
  -> NavPresenter
  -> moto_nav_ui / LVGL

touch / music buttons
  -> DeviceCommand
  -> DeviceToPhone characteristic
  -> iOS navigation page or media action
```

`NavigationSnapshot` is a display snapshot, not a `NavEvent`. Route geometry contains only the
local window needed for the current heading-up view, at most 24 points, consistent with
`nav::kRouteViewPointCapacity`. The phone still keeps the complete Provider route.

## 2. GATT contract

The ESP32 is the peripheral/GATT server and the phone is the central/GATT client. The UUIDs do
not change with the hardware version, the firmware build or the user.

| Object | UUID | Properties | Permission / purpose |
| --- | --- | --- | --- |
| Navigation Service | `7e57a000-b50c-4b6a-9c57-40a54e8e1000` | primary service | Discoverable |
| PhoneToDevice / RX | `7e57a001-b50c-4b6a-9c57-40a54e8e1000` | Write, Write Without Response | encrypted write; the phone delivers state, navigation, routes, traffic, media and ACK |
| DeviceToPhone / TX | `7e57a002-b50c-4b6a-9c57-40a54e8e1000` | Notify | notification is only allowed on an encrypted connection; the device reports commands, ACK, connection state and heartbeat |
| TX CCCD | standard `0x2902` | read/write | writing the subscription bit must be encrypted; a read exposes only the current subscription bit and carries no service payload; the phone writes `0x0001` to enable Notify |

RX does not provide Read/Notify and TX does not provide Read/Write/Indicate. The phone must
subscribe to TX first and then send the handshake. CoreBluetooth manages the CCCD with
`setNotifyValue(true)`; do not treat the CCCD as another application characteristic.

The consistent security baseline for EVT is LE Secure Connections + bonding + Just Works, that
is LE Security Mode 1 Level 2: RX uses the encrypted-write permission, TX only notifies on an
encrypted connection, and the CCCD write also requires encryption. The first
`setNotifyValue(true)` or the first RX write triggers system pairing; afterwards the bond
restores encryption and a temporary pairing is not repeated on every reconnection. Without
encryption TX cannot be subscribed to, navigation content cannot be received and media commands
cannot be sent.

If the bond between the phone and the device falls out of sync during EVT, the recovery action
is to delete the pairing record on the iOS side and erase the corresponding bond on the device
side at the same time and then pair again; you must not work around it by temporarily removing
the encryption permission from a characteristic. Just Works provides no MITM authentication;
the production boundary still needs a device ownership flow, upgraded to authenticated pairing
when the interaction capabilities allow. The CCCD's readable subscription bit is not an identity
credential and does not leak protocol payload.

One complete v1 frame is transferred per GATT operation. `max_frame_size` is the maximum byte
count of the characteristic value, not the ATT MTU:

```text
max_frame_size = min(local ATT_MTU - 3, peer advertised max_frame_size, 512)
```

With an ATT MTU of 23 that value is 20 and the frame net payload is only 8 bytes. Relying on
Prepare Write/Long Write is forbidden; the protocol's own fragmentation behaves consistently at
every MTU. Snapshots, heartbeats and media state normally use Write Without Response; the
handshake or low-frequency control that needs link-layer confirmation may use Write. The sender
must send all fragments of a logical message serially and respect the system's send
back-pressure.

## 3. Session and handshake

1. The BLE connection is established, encryption, service discovery and the TX Notify
   subscription complete.
2. The phone generates a random, non-zero 32-bit `session_id`, clears its send and receive
   sequence numbers and reassembly state, and then sends
   `ConnectionStatus(role=Phone, state=Starting)` on RX.
3. The ESP32 validates the version range, takes the capability intersection and the smaller
   `max_frame_size` of the two sides, and returns
   `ConnectionStatus(role=Device, state=Ready)` on TX with the same `session_id`.
4. The phone sends `ConnectionStatus(role=Phone, state=Ready)`; only after that may it send
   display data.
5. When either end receives a different `session_id` it must clear the old route window, the
   command deduplication table, unfinished fragments and the sequence base, and then handshake
   again.

With no v1 version intersection, reply `state=Closing` and disconnect. Capability bits only
indicate the features that may be used; a sender may only use messages in the intersection of
both sides' capabilities.

The recommended heartbeat interval is 1000 ms. When no frame with a correct CRC has been
received for 3000 ms in a row the protocol link is considered dead, and the ESP32 should show
the offline state and clear unfinished fragments, but the physical BLE connection state is still
managed by the platform layer.

## 4. Frame format

All multi-byte integers are little-endian. Every fragment has its own CRC.

| Offset | Size | Field | v1 rule |
| ---: | ---: | --- | --- |
| 0 | 1 | magic | fixed `0xB7` |
| 1 | 1 | protocol_version | fixed `1` |
| 2 | 1 | message_type | see the message table |
| 3 | 1 | flags | bit0 START, bit1 END, bit2 ACK_REQUESTED, bit3 URGENT; the rest must be 0 |
| 4 | 2 | sequence | non-zero logical message sequence number, independent per direction |
| 6 | 2 | fragment_offset | byte offset of this fragment's net payload within the complete payload |
| 8 | 2 | message_length | complete payload byte count, excluding the frame header and CRC |
| 10 | N | fragment_payload | length derived from the total GATT value length minus 12 |
| 10+N | 2 | CRC16 | CRC-16/CCITT-FALSE over offsets `0..9+N`, low byte sent first |

CRC parameters: poly `0x1021`, init `0xFFFF`, refin=false, refout=false, xorout `0x0000`. The
standard check string `123456789` gives `0x29B1`.

START if and only if `fragment_offset == 0`. END if and only if
`fragment_offset + N == message_length`. A single-frame message sets START and END together.
`ACK_REQUESTED` and `URGENT` are logical message properties and must have the same value on
every fragment.

### 4.1 Fragmentation and reassembly

- Only one unfinished logical message is allowed per direction at a time; interleaving fragments
  between two logical messages is forbidden.
- BLE preserves order, so a receiver only accepts the immediately following
  `fragment_offset`. Gaps, overlaps with differing bytes, and changes of type, total length or
  flags are all rejected.
- An exactly identical already-received fragment is a duplicate fragment; it is not appended a
  second time and does not refresh the timeout.
- An unfinished message is discarded 1000 ms after the last valid new fragment. A continuation
  after the timeout returns `ReassemblyTimeout` and a new START must be awaited.
- A START with a newer sequence number may atomically replace an unfinished message, which stops
  packet loss from wedging the link.
- The reassembly limit defaults to 4096 bytes; v1 service messages should be far below it.

### 4.2 Sequence numbers and deduplication

The receive and send directions each maintain a `uint16` sequence number, with `0` reserved. The
send order is `1..65535,1..`. The half-range rule modulo 65536 decides old and new, so 32767
unobserved sequence numbers cannot be crossed between two valid messages. A new `session_id` or
a BLE reconnection resets the basis for sequence comparison.

When an already completed sequence number appears again the result is `DuplicateMessage` and
application side effects must not run a second time. If that message requested an ACK, the
receiver resends the original ACK from the small "most recently completed message -> ACK"
cache.

### 4.3 Application ACK

`ACK_REQUESTED` is used for the `DeviceCommand` that must be confirmed and may also be used for
the atomic commit of a route window. The receiver replies with `Ack` after the complete payload
has been decoded and committed. When the sender sees no matching ACK for 750 ms it resends with
the same sequence number and exactly the same fragments, up to 2 retries. If it still times out
it reports degraded and must not invent a new `command_id` to pretend success.

An ordinary `NavigationSnapshot` does not request an ACK: a new snapshot naturally supersedes an
old one. The link-layer response to `Write` cannot replace an application ACK, because it only
confirms the characteristic write, not reassembly and the service-level commit.

## 5. Message table

Byte 0 of every logical payload is `payload_revision=1`.

| type | Name | Direction | Suggested rate |
| ---: | --- | --- | --- |
| `0x01` | ConnectionStatus | both ways | handshake or state change |
| `0x02` | Heartbeat | both ways | every 1000 ms when idle |
| `0x03` | Ack | both ways | response to ACK_REQUESTED |
| `0x10` | NavigationSnapshot | phone→device | on value change, at most 5 Hz; key state changes sent immediately |
| `0x11` | RouteGeometry | phone→device | when the route / local window changes |
| `0x12` | TrafficDeviation | phone→device | when traffic or off-route state changes |
| `0x13` | MediaState | phone→device | track / playback change, progress at most 1 Hz |
| `0x14` | MapScene | phone→device | after about 100 m of travel, on crossing an offline tile, or on a route change |
| `0x20` | DeviceCommand | device→phone | user action, ACK_REQUESTED+URGENT |

## 6. Payload encoding

### 6.1 ConnectionStatus (`0x01`, fixed 17 bytes)

```text
u8  revision
u8  role                 1 Phone, 2 Device
u8  state                0 Starting, 1 Ready, 2 Degraded, 3 Closing
u8  minimum_version
u8  maximum_version
u32 capabilities
u32 session_id           non-zero
u16 max_frame_size       13..512, complete GATT value size
u16 heartbeat_interval_ms  >=250
```

Capability bits: bit0 navigation, bit1 route geometry, bit2 traffic, bit3 media state, bit4
touch commands, bit5 music commands, bit6 command ACK, bit7 map scene.

### 6.2 Heartbeat (`0x02`, fixed 11 bytes)

```text
u8  revision
u32 session_id
u32 monotonic_ms         low 32 bits of this endpoint's monotonic clock in this session
u16 status_flags         bits undefined in v1 must be sent as 0
```

`monotonic_ms` is used only for diagnostics and stall detection, not for time alignment between
devices.

### 6.3 Ack (`0x03`, fixed 6 bytes)

```text
u8  revision
u16 acknowledged_sequence
u8  status               0 Ok, 1 Unsupported, 2 InvalidState,
                         3 Failed, 4 Duplicate
u16 command_id           0 for an ACK that is not a DeviceCommand
```

The ACK itself uses a new `sequence` in its own direction and no longer requests an ACK.

### 6.4 NavigationSnapshot (`0x10`)

```text
u8  revision
u8  state                 0 Idle, 1 Acquiring, 2 Planning,
                          3 Navigating, 4 Rerouting, 5 Arrived
u8  network               0 Offline, 1 Connecting, 2 Online
u8  display_page          0 Navigation, 1 Speed, 2 Compass, 3 Music
u8  maneuver              0..12, in the same order as the shared ManeuverType
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
u16 speed_limit_kph       0 means unknown
u16 heading_cdeg          0..35999, 0.01 degree
u16 accuracy_dm           0.1 m
u16 cross_track_dm        0.1 m, saturating at 6553.5 m
u8  roundabout_exit       0 unknown, otherwise 1..32
u8  road_name_bytes       0..63
u8[] road_name            UTF-8, no NUL
u8  instruction_bytes     0..95
u8[] instruction          UTF-8, no NUL
```

The fixed part together with the two string length bytes is 53 bytes. flags:

| bit | Name | Corresponds to `nav::NavSnapshot` |
| ---: | --- | --- |
| 0 | HasDestination | `has_destination` |
| 1 | HasFix | `has_usable_fix` |
| 2 | GnssStale | `gnss_stale` |
| 3 | OffRoute | `off_route` |
| 4 | HasNextManeuver | `has_next_maneuver` |
| 5 | RouteRequestInFlight | `route_request_in_flight` |
| 6 | TrafficRequestInFlight | `traffic_request_in_flight` |
| 7 | HasRouteView | a matching committed RouteGeometry may be used |

`route_token` is the FNV-1a 32-bit hash of the complete UTF-8 `RouteBundle::route_id`: offset
basis `2166136261`, xor per byte and then multiply by `16777619`, overflowing naturally as
uint32; if the result is 0 it becomes 1. With no route the token/generation are 0; with
HasRouteView both must be non-zero.

### 6.5 RouteGeometry (`0x11`)

```text
u8  revision
u8  coordinate_system     fixed 2 = GCJ-02
u32 route_token
u32 route_generation
u16 chunk_index            starting from 0
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

E6 means degree × 1,000,000. The first point is an absolute GCJ-02 coordinate and later points
are relative to the previous point using ZigZag + canonical unsigned LEB128:
`0 -> 0, -1 -> 1, +1 -> 2`. A varint that is non-canonical, exceeds 5 bytes or overflows during
decoding must be rejected.

The same window may be split into several logical `RouteGeometry` messages because of a very
small negotiated frame/message budget, but the total point count still does not exceed 24.
Chunks must arrive in order, the first chunk has `first_point_index=0`, and the last chunk ends
exactly at `total_point_count`. The receiver puts them into a staging buffer first; it atomically
replaces the committed window only after all chunks agree on token, generation, origin and count
and have arrived completely. A chunk 0 with a higher generation may abandon the old staging.

A normal implementation should send the whole 24-point window as one logical payload and then
hand it to the generic BLE frame fragmentation; do not use both layers of chunking at the same
time without need.

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
  u24 length_m            must be >0
  u8  traffic_level
```

flags: bit0 TrafficChanged, bit1 OffRoute, bit2 Rerouting, bit3 RouteInvalidated. Segments are
in ascending `start_offset_m`, must not overlap, and their offsets are relative to the start of
the complete route. When token/generation do not match the current snapshot the whole message is
discarded. RouteInvalidated immediately clears the committed geometry; Rerouting switches the
display state to rerouting until a later complete `NavigationSnapshot` confirms the new state.

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

`track_token` is a track identifier that is stable within this phone session and must change
when the track changes; it is not a global media ID.

The current iOS adapter projects this message onto the public
`MPMusicPlayerController.systemMusicPlayer`, so it only controls previous track, play/pause and
next track of the system "Music" app (including its Apple Music playback queue).
`MPRemoteCommandCenter` is the interface that lets the current Now Playing app **receive**
headset/system commands; it is not an interface for injecting commands into other apps. v1
therefore does not claim that an ordinary iOS app can reliably control third-party players such
as NetEase Cloud Music. Apple has also not exposed a public interface on the system music player
for changing the "like" state of the current song, so iOS must send `like available = 0` and the
device hides LIKE instead of a local fake success.

### 6.8 MapScene (`0x14`)

`MapScene` is an optional, fully replacing local mini-map scene, not a city-wide database. The
iPhone queries 500–800 m around the current position in the offline package and sends it after
clipping and simplification; the ESP32 keeps only the latest frame. Coordinates are fixed to
GCJ-02, aligned with the AMap planned route.

```text
u8  revision
u8  coordinate_system       fixed 2 = GCJ-02
u32 scene_revision          non-zero; strictly increasing for a new window
i32 view_origin_lat_e6
i32 view_origin_lon_e6
u16 radius_m                100..1500
u8  road_count              0..24
u8  building_count          0..16

repeat road_count:
  u8 road_class             0 motorway, 1 primary, 2 secondary,
                            3 residential, 4 service, 5 other
  u8 point_count            2..255; total road points in the frame <=192
  repeat point_count:
    svarint lat_delta_e6     relative to view_origin
    svarint lon_delta_e6

repeat building_count:
  u8 building_class         0 generic, 1 landmark, 2 parking
  u8 point_count            3..255; total building points in the frame <=128
  repeat point_count:
    svarint lat_delta_e6     relative to view_origin
    svarint lon_delta_e6
```

The last building point must not repeat the first point; the renderer adds the closing edge. All
points must be inside the safety bound of `±100000 E6` in latitude and longitude from the
origin; the app should still do the actual circular/rectangular clipping by `radius_m`. The
complete payload should carry `ACK_REQUESTED` and is Acked only after validation, decoding and a
successful commit. A higher `scene_revision` atomically replaces the old window; an old
revision, a partial packet and a CRC error all leave the screen unchanged.

This encoding is usually about 1.3–2.2 KiB for 192 road points + 128 building points; under a
185-byte GATT value that is about 8–14 protocol fragments. It must not be sent at the
positioning frame rate: refreshing once after about 100 m of travel, when approaching the window
edge or when crossing a 500 m offline tile is enough. The current shared C++ codec already
implements this message, but the iOS offline-package query layer, the ESP32 staging/commit and
the building LVGL layer are still later wiring work.

### 6.9 DeviceCommand (`0x20`, fixed 13 bytes)

```text
u8  revision
u8  kind
u16 command_id             non-zero and monotonic within this session; the side-effect deduplication key
u8  page
u16 x                      touch coordinate; 0xFFFF when unknown
u16 y                      touch coordinate; 0xFFFF when unknown
u32 event_time_ms           low 32 bits of the device monotonic clock
```

kind: 0 PageSelected, 1 Tap, 2 LongPress, 3 SwipeLeft, 4 SwipeRight, 5 SwipeUp, 6 SwipeDown,
16 MusicPrevious, 17 MusicTogglePlayback, 18 MusicNext, 19 MusicLike. The music commands follow
the four actions in `shared/nav_ui`. Touch coordinates are screen pixels; the swipe direction is
already normalised, so the phone must not infer the direction from the coordinates again.

The phone deduplicates by `(session_id, command_id)`; success, failure and duplicates all get an
Ack reply. A UI action must be ACKed Ok only after a successful commit.

## 7. Display Snapshot to the shared NavSnapshot

The adapter must do value conversion and must not operate on `moto_ui_state_t` directly. The
recommended mapping is:

| BLE field | `moto::nav::NavSnapshot` |
| --- | --- |
| state/network/display_page | mapped item by item to the same-named enums, not relying on a bare `static_cast` |
| flags bit0..6 | the corresponding seven bool fields |
| `speed_deci_kph` | `speed_mps = value / 36.0F` |
| `heading_cdeg` | `heading_deg = value / 100.0F` |
| `accuracy_dm` | `horizontal_accuracy_m = value / 10.0F` |
| `cross_track_dm` | `cross_track_distance_m = value / 10.0F` |
| distance/duration/progress/total | converted to the corresponding double/u32 fields |
| maneuver + text | `next_maneuver`; `route_offset_m = route_progress_m + distance_to_maneuver_m` |
| traffic | `traffic_ahead` |
| route_generation | copied unchanged as u32 |

Set `has_route_view=true` only when all of the following hold: snapshot bit7 is set, the geometry
has been committed completely, the geometry token/generation match the snapshot, and the point
count is at least 2. Then divide the E6 origin/points by 1,000,000 and write them into
`route_view_origin` and `route_view_points`. When they do not match, keep the navigation numbers
and the turn information but hide the route line; never fall back to the old route.

`NavSnapshot.position/destination/now_ms/last_fix_ms/last_traffic_update_ms` are not consumed by
the current `NavPresenter`, so the display-side adapter may zero them or maintain local
diagnostic values. The phone remains the owner of these facts; a second NavCore state machine
must not be started on the ESP32 because of that.

## 8. Errors, versions and resource bounds

- On a magic, version, CRC, reserved-flags, length or range error the whole frame/message is
  rejected; there is no partial update.
- String lengths are counted in UTF-8 bytes; the rendering layer may replace invalid UTF-8, but
  the protocol layer must not read out of bounds.
- The v1 decoder rejects unknown payload revisions, unknown message types and trailing bytes.
- Adding an optional message type may keep frame v1; changing the field order, units, coordinate
  system, enum values or the CRC must raise the frame protocol version or that message's payload
  revision.
- Endpoints must check the upper bounds for receive buffers, point counts, segment counts and
  strings before allocating.
- The CRC only detects transmission errors and provides no authentication; identity and
  confidentiality rely on BLE encryption/bonding.

## 9. Golden frames

All of the following are lowercase hex and multi-byte fields are little-endian.

Connection payload:

```text
01010101017f00000078563412b900e803
```

Single-frame encoding of that payload (sequence=1):

```text
b701010301000000110001010101017f00000078563412b900e8035469
```

MusicNext command payload:

```text
0112341203ffffffff40302010
```

The two fragments under a 20-byte characteristic value with sequence `0x1234` and
ACK_REQUESTED+URGENT:

```text
b701200d341200000d000112341203ffffff6793
b701200e341208000d00ff40302010ef35
```

The machine-readable complete vectors are authoritative in the fixture. Changing any v1 byte
should first explain the compatibility impact, and both ends should update the fixture together.

## 10. Local verification

```sh
cmake -S . -B build/native -DMOTO_BUILD_WEB=OFF -DMOTO_BUILD_TESTS=ON
cmake --build build/native --target ble_protocol_tests
./build/native/tests/native/ble_protocol_tests
```

The tests cover round-trip for all messages, the fixed UUIDs/properties, CRC error detection,
20-byte fragmentation, reassembly, duplicate and conflicting fragments, gaps, timeouts,
replacement by a newer START, sequence wrap-around, input bounds and the golden bytes.
