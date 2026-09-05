#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

namespace moto::ble {

using Bytes = std::vector<std::uint8_t>;
using TimestampMs = std::uint64_t;

constexpr std::uint8_t kFrameMagic = 0xB7;
constexpr std::uint8_t kProtocolVersion = 1;
constexpr std::uint8_t kPayloadRevision = 1;
// ESP32 is the GATT server/peripheral. The phone writes PhoneToDevice and
// subscribes to DeviceToPhone notifications.
inline constexpr char kServiceUuid[] =
    "7e57a000-b50c-4b6a-9c57-40a54e8e1000";
inline constexpr char kPhoneToDeviceUuid[] =
    "7e57a001-b50c-4b6a-9c57-40a54e8e1000";
inline constexpr char kDeviceToPhoneUuid[] =
    "7e57a002-b50c-4b6a-9c57-40a54e8e1000";
inline constexpr char kCccdUuid[] =
    "00002902-0000-1000-8000-00805f9b34fb";

enum GattRequirement : std::uint8_t {
  GattWrite = 1U << 0U,
  GattWriteWithoutResponse = 1U << 1U,
  GattNotify = 1U << 2U,
  GattCccd = 1U << 3U,
  GattEncryptedPermission = 1U << 4U,
};

constexpr std::uint8_t kPhoneToDeviceGattRequirements =
    GattWrite | GattWriteWithoutResponse | GattEncryptedPermission;
constexpr std::uint8_t kDeviceToPhoneGattRequirements =
    GattNotify | GattCccd | GattEncryptedPermission;
constexpr std::size_t kFrameHeaderSize = 10;
constexpr std::size_t kFrameCrcSize = 2;
constexpr std::size_t kFrameOverhead =
    kFrameHeaderSize + kFrameCrcSize;
constexpr std::size_t kMaxBleAttributeValueSize = 512;
constexpr std::size_t kDefaultMaxMessageSize = 4'096;
// Matches nav::kRouteViewPointCapacity. RouteGeometry is the bounded local
// display window, not the phone's complete provider route.
constexpr std::size_t kMaxRoutePointsPerChunk = 24;
constexpr std::size_t kMaxTrafficSegments = 64;
// One replaceable, rider-centred minimap window.  The complete Jinan database
// remains on the phone; these limits bound ESP32 RAM and LVGL object counts.
constexpr std::size_t kMaxMapSceneRoads = 24;
constexpr std::size_t kMaxMapSceneRoadPoints = 192;
constexpr std::size_t kMaxMapSceneBuildings = 16;
constexpr std::size_t kMaxMapSceneBuildingPoints = 128;

struct ByteView {
  const std::uint8_t* data = nullptr;
  std::size_t size = 0;

  constexpr ByteView() = default;
  constexpr ByteView(const std::uint8_t* bytes, std::size_t byte_count)
      : data(bytes), size(byte_count) {}
  explicit ByteView(const Bytes& bytes) : data(bytes.data()), size(bytes.size()) {}
};

enum class Error : std::uint8_t {
  None = 0,
  InvalidArgument,
  FrameTooShort,
  FrameTooLarge,
  BadMagic,
  UnsupportedVersion,
  ReservedFlags,
  SequenceZero,
  InvalidFragment,
  LengthMismatch,
  CrcMismatch,
  MessageTooLarge,
  MissingStart,
  UnexpectedFragment,
  FragmentConflict,
  StaleSequence,
  ReassemblyTimeout,
  UnknownMessageType,
  UnsupportedPayloadRevision,
  TruncatedPayload,
  TrailingPayload,
  InvalidEnum,
  OutOfRange,
  StringTooLong,
  TooManyItems,
  InvalidVarint,
};

const char* to_string(Error error) noexcept;

template <typename T>
struct Result {
  T value{};
  Error error = Error::None;
  std::size_t offset = 0;

  [[nodiscard]] constexpr bool ok() const noexcept {
    return error == Error::None;
  }
};

enum class MessageType : std::uint8_t {
  ConnectionStatus = 0x01,
  Heartbeat = 0x02,
  Ack = 0x03,
  NavigationSnapshot = 0x10,
  RouteGeometry = 0x11,
  TrafficDeviation = 0x12,
  MediaState = 0x13,
  MapScene = 0x14,
  DeviceCommand = 0x20,
};

enum FrameFlag : std::uint8_t {
  FrameStart = 1U << 0U,
  FrameEnd = 1U << 1U,
  AckRequested = 1U << 2U,
  Urgent = 1U << 3U,
};

constexpr std::uint8_t kKnownFrameFlags =
    FrameStart | FrameEnd | AckRequested | Urgent;
constexpr std::uint8_t kApplicationFrameFlags = AckRequested | Urgent;

struct Frame {
  std::uint8_t version = kProtocolVersion;
  MessageType type = MessageType::Heartbeat;
  std::uint8_t flags = 0;
  std::uint16_t sequence = 0;
  std::uint16_t fragment_offset = 0;
  std::uint16_t message_length = 0;
  Bytes payload;
};

using BytesResult = Result<Bytes>;
using FrameResult = Result<Frame>;
using FramesResult = Result<std::vector<Bytes>>;

[[nodiscard]] std::uint16_t crc16_ccitt_false(ByteView bytes) noexcept;
[[nodiscard]] BytesResult encode_frame(const Frame& frame);
[[nodiscard]] FrameResult decode_frame(ByteView bytes);

// max_frame_size is the complete GATT characteristic value size, not the ATT
// MTU. For an ATT MTU of 23, pass 20. Start/End are assigned by this function;
// application_flags may contain only AckRequested and Urgent.
[[nodiscard]] FramesResult fragment_message(
    MessageType type,
    std::uint16_t sequence,
    ByteView payload,
    std::size_t max_frame_size,
    std::uint8_t application_flags = 0,
    std::uint8_t version = kProtocolVersion);

[[nodiscard]] bool sequence_is_newer(std::uint16_t candidate,
                                     std::uint16_t reference) noexcept;

class SequenceGenerator {
 public:
  explicit SequenceGenerator(std::uint16_t first = 1) noexcept;
  [[nodiscard]] std::uint16_t next() noexcept;
  void reset(std::uint16_t first = 1) noexcept;

 private:
  std::uint16_t next_ = 1;
};

enum class ReassemblyState : std::uint8_t {
  InProgress,
  Complete,
  DuplicateFragment,
  DuplicateMessage,
  Error,
};

struct ReassembledMessage {
  MessageType type = MessageType::Heartbeat;
  std::uint8_t version = kProtocolVersion;
  std::uint8_t flags = 0;
  std::uint16_t sequence = 0;
  Bytes payload;
};

struct ReassemblyResult {
  ReassemblyState state = ReassemblyState::Error;
  Error error = Error::None;
  std::size_t error_offset = 0;
  bool dropped_incomplete = false;
  ReassembledMessage message;

  [[nodiscard]] bool complete() const noexcept {
    return state == ReassemblyState::Complete;
  }
};

struct ReassemblerConfig {
  std::size_t max_message_size = kDefaultMaxMessageSize;
  TimestampMs fragment_timeout_ms = 1'000;
  bool enforce_monotonic_sequence = true;
};

// BLE preserves notification/write order. Reassembler therefore accepts one
// in-flight logical message per direction and rejects gaps. A newer START may
// supersede an incomplete message so a lost fragment cannot wedge the link.
class Reassembler {
 public:
  explicit Reassembler(ReassemblerConfig config = {});

  [[nodiscard]] ReassemblyResult push(ByteView encoded_frame,
                                      TimestampMs now_ms);
  [[nodiscard]] bool expire(TimestampMs now_ms) noexcept;
  void reset() noexcept;

 private:
  void clear_active() noexcept;

  ReassemblerConfig config_;
  bool active_ = false;
  bool has_last_sequence_ = false;
  std::uint8_t active_version_ = kProtocolVersion;
  MessageType active_type_ = MessageType::Heartbeat;
  std::uint8_t active_application_flags_ = 0;
  std::uint16_t active_sequence_ = 0;
  std::uint16_t active_message_length_ = 0;
  std::size_t expected_offset_ = 0;
  TimestampMs last_fragment_at_ms_ = 0;
  std::uint16_t last_sequence_ = 0;
  Bytes buffer_;
};

class LinkWatchdog {
 public:
  explicit LinkWatchdog(TimestampMs timeout_ms = 3'000) noexcept;

  void set_timeout(TimestampMs timeout_ms) noexcept;
  void note_valid_frame(TimestampMs now_ms) noexcept;
  void reset() noexcept;
  [[nodiscard]] bool armed() const noexcept;
  [[nodiscard]] bool expired(TimestampMs now_ms) const noexcept;
  [[nodiscard]] TimestampMs deadline_ms() const noexcept;

 private:
  TimestampMs timeout_ms_ = 3'000;
  TimestampMs last_rx_ms_ = 0;
  bool armed_ = false;
};

enum class EndpointRole : std::uint8_t {
  Phone = 1,
  Device = 2,
};

enum class ConnectionState : std::uint8_t {
  Starting = 0,
  Ready = 1,
  Degraded = 2,
  Closing = 3,
};

enum Capability : std::uint32_t {
  CapabilityNavigation = 1U << 0U,
  CapabilityRouteGeometry = 1U << 1U,
  CapabilityTraffic = 1U << 2U,
  CapabilityMediaState = 1U << 3U,
  CapabilityTouchCommands = 1U << 4U,
  CapabilityMusicCommands = 1U << 5U,
  CapabilityCommandAck = 1U << 6U,
  CapabilityMapScene = 1U << 7U,
};

struct ConnectionStatus {
  EndpointRole role = EndpointRole::Phone;
  ConnectionState state = ConnectionState::Starting;
  std::uint8_t minimum_version = kProtocolVersion;
  std::uint8_t maximum_version = kProtocolVersion;
  std::uint32_t capabilities = 0;
  std::uint32_t session_id = 0;
  std::uint16_t max_frame_size = 20;
  std::uint16_t heartbeat_interval_ms = 1'000;

  bool operator==(const ConnectionStatus& rhs) const noexcept;
};

struct Heartbeat {
  std::uint32_t session_id = 0;
  std::uint32_t monotonic_ms = 0;
  std::uint16_t status_flags = 0;

  bool operator==(const Heartbeat& rhs) const noexcept;
};

enum class AckStatus : std::uint8_t {
  Ok = 0,
  Unsupported = 1,
  InvalidState = 2,
  Failed = 3,
  Duplicate = 4,
};

struct Ack {
  std::uint16_t acknowledged_sequence = 0;
  AckStatus status = AckStatus::Ok;
  std::uint16_t command_id = 0;

  bool operator==(const Ack& rhs) const noexcept;
};

enum class NavigationState : std::uint8_t {
  Idle = 0,
  Acquiring = 1,
  Planning = 2,
  Navigating = 3,
  Rerouting = 4,
  Arrived = 5,
};

enum class Maneuver : std::uint8_t {
  Unknown = 0,
  Continue = 1,
  SlightLeft = 2,
  Left = 3,
  SharpLeft = 4,
  UTurnLeft = 5,
  SlightRight = 6,
  Right = 7,
  SharpRight = 8,
  UTurnRight = 9,
  Roundabout = 10,
  Exit = 11,
  Arrive = 12,
};

enum class TrafficLevel : std::uint8_t {
  Unknown = 0,
  FreeFlow = 1,
  Slow = 2,
  Congested = 3,
  Severe = 4,
};

enum class NetworkState : std::uint8_t {
  Offline = 0,
  Connecting = 1,
  Online = 2,
};

enum class DisplayPage : std::uint8_t {
  Navigation = 0,
  Speed = 1,
  Compass = 2,
  Music = 3,
};

enum NavigationFlag : std::uint16_t {
  NavigationHasDestination = 1U << 0U,
  NavigationHasFix = 1U << 1U,
  NavigationGnssStale = 1U << 2U,
  NavigationOffRoute = 1U << 3U,
  NavigationHasNextManeuver = 1U << 4U,
  NavigationRouteRequestInFlight = 1U << 5U,
  NavigationTrafficRequestInFlight = 1U << 6U,
  NavigationHasRouteView = 1U << 7U,
};

constexpr std::uint16_t kKnownNavigationFlags =
    NavigationHasDestination | NavigationHasFix | NavigationGnssStale |
    NavigationOffRoute | NavigationHasNextManeuver |
    NavigationRouteRequestInFlight | NavigationTrafficRequestInFlight |
    NavigationHasRouteView;

struct NavigationSnapshot {
  NavigationState state = NavigationState::Idle;
  NetworkState network = NetworkState::Offline;
  DisplayPage display_page = DisplayPage::Navigation;
  Maneuver maneuver = Maneuver::Unknown;
  TrafficLevel traffic = TrafficLevel::Unknown;
  std::uint16_t flags = 0;
  std::uint32_t route_token = 0;
  std::uint32_t route_generation = 0;
  std::uint32_t maneuver_id = 0;
  std::uint32_t distance_to_maneuver_m = 0;
  std::uint32_t remaining_distance_m = 0;
  std::uint32_t remaining_duration_s = 0;
  std::uint32_t route_progress_m = 0;
  std::uint32_t total_distance_m = 0;
  std::uint16_t speed_deci_kph = 0;
  std::uint16_t speed_limit_kph = 0;
  std::uint16_t heading_cdeg = 0;
  std::uint16_t accuracy_dm = 0;
  std::uint16_t cross_track_dm = 0;
  std::uint8_t roundabout_exit = 0;
  std::string road_name;
  std::string instruction;

  bool operator==(const NavigationSnapshot& rhs) const noexcept;
};

enum class CoordinateSystem : std::uint8_t {
  Wgs84 = 1,
  Gcj02 = 2,
};

struct GeoPointE6 {
  std::int32_t latitude_e6 = 0;
  std::int32_t longitude_e6 = 0;

  bool operator==(const GeoPointE6& rhs) const noexcept;
};

struct RouteGeometry {
  CoordinateSystem coordinate_system = CoordinateSystem::Gcj02;
  std::uint32_t route_token = 0;
  std::uint32_t route_generation = 0;
  std::uint16_t chunk_index = 0;
  std::uint16_t chunk_count = 1;
  std::uint16_t first_point_index = 0;
  std::uint16_t total_point_count = 0;
  GeoPointE6 view_origin;
  std::vector<GeoPointE6> points;

  bool operator==(const RouteGeometry& rhs) const noexcept;
};

enum TrafficDeviationFlag : std::uint16_t {
  TrafficChanged = 1U << 0U,
  OffRoute = 1U << 1U,
  Rerouting = 1U << 2U,
  RouteInvalidated = 1U << 3U,
};

constexpr std::uint16_t kKnownTrafficDeviationFlags =
    TrafficChanged | OffRoute | Rerouting | RouteInvalidated;

struct TrafficSegment {
  std::uint32_t start_offset_m = 0;
  std::uint32_t length_m = 0;
  TrafficLevel level = TrafficLevel::Unknown;

  bool operator==(const TrafficSegment& rhs) const noexcept;
};

struct TrafficDeviation {
  std::uint32_t route_token = 0;
  std::uint32_t route_generation = 0;
  std::uint16_t flags = 0;
  std::uint32_t observed_at_ms = 0;
  std::uint32_t remaining_duration_s = 0;
  std::uint16_t cross_track_dm = 0;
  std::vector<TrafficSegment> segments;

  bool operator==(const TrafficDeviation& rhs) const noexcept;
};

enum MediaFlag : std::uint8_t {
  MediaConnected = 1U << 0U,
  MediaPlaying = 1U << 1U,
  MediaLikeAvailable = 1U << 2U,
  MediaLiked = 1U << 3U,
};

constexpr std::uint8_t kKnownMediaFlags =
    MediaConnected | MediaPlaying | MediaLikeAvailable | MediaLiked;

struct MediaState {
  std::uint8_t flags = 0;
  std::uint32_t track_token = 0;
  std::uint16_t position_s = 0;
  std::uint16_t duration_s = 0;
  std::string source_name;
  std::string track_title;
  std::string artist_name;

  bool operator==(const MediaState& rhs) const noexcept;
};

// A low-frequency, atomically replaceable GTA-style local map window.  Points
// use GCJ-02 so they align with the AMap route already rendered by the device.
// The phone clips and simplifies its offline database before encoding this
// message; the ESP32 never performs a city-wide spatial query.
enum class MapRoadClass : std::uint8_t {
  Motorway = 0,
  Primary = 1,
  Secondary = 2,
  Residential = 3,
  Service = 4,
  Other = 5,
};

enum class MapBuildingClass : std::uint8_t {
  Generic = 0,
  Landmark = 1,
  Parking = 2,
};

struct MapRoadPolyline {
  MapRoadClass road_class = MapRoadClass::Other;
  std::vector<GeoPointE6> points;

  bool operator==(const MapRoadPolyline& rhs) const noexcept;
};

struct MapBuildingFootprint {
  MapBuildingClass building_class = MapBuildingClass::Generic;
  // The closing point is implicit and must not repeat points.front().
  std::vector<GeoPointE6> points;

  bool operator==(const MapBuildingFootprint& rhs) const noexcept;
};

struct MapScene {
  CoordinateSystem coordinate_system = CoordinateSystem::Gcj02;
  std::uint32_t scene_revision = 0;
  GeoPointE6 view_origin;
  std::uint16_t radius_m = 0;
  std::vector<MapRoadPolyline> roads;
  std::vector<MapBuildingFootprint> buildings;

  bool operator==(const MapScene& rhs) const noexcept;
};

enum class DeviceCommandKind : std::uint8_t {
  PageSelected = 0,
  Tap = 1,
  LongPress = 2,
  SwipeLeft = 3,
  SwipeRight = 4,
  SwipeUp = 5,
  SwipeDown = 6,
  MusicPrevious = 16,
  MusicTogglePlayback = 17,
  MusicNext = 18,
  MusicLike = 19,
};

constexpr std::uint16_t kUnknownTouchCoordinate = 0xFFFFU;

struct DeviceCommand {
  DeviceCommandKind kind = DeviceCommandKind::PageSelected;
  std::uint16_t command_id = 0;
  DisplayPage page = DisplayPage::Navigation;
  std::uint16_t x = kUnknownTouchCoordinate;
  std::uint16_t y = kUnknownTouchCoordinate;
  std::uint32_t event_time_ms = 0;

  bool operator==(const DeviceCommand& rhs) const noexcept;
};

using Message = std::variant<ConnectionStatus,
                             Heartbeat,
                             Ack,
                             NavigationSnapshot,
                             RouteGeometry,
                             TrafficDeviation,
                             MediaState,
                             MapScene,
                             DeviceCommand>;

using MessageResult = Result<Message>;

[[nodiscard]] MessageType message_type(const Message& message) noexcept;
[[nodiscard]] BytesResult encode_message(const Message& message);
[[nodiscard]] MessageResult decode_message(MessageType type,
                                           ByteView payload);

// Stable non-cryptographic association token for the existing string
// RouteBundle::route_id. Both endpoints hash the exact UTF-8 bytes.
[[nodiscard]] std::uint32_t route_token(std::string_view route_id) noexcept;

}  // namespace moto::ble
