#include "moto/ble_protocol/ble_protocol.hpp"

#include <algorithm>
#include <cstring>
#include <limits>
#include <tuple>
#include <type_traits>
#include <utility>

namespace moto::ble {
namespace {

constexpr std::uint32_t kMaximumU24 = 0x00FF'FFFFU;
constexpr std::size_t kMaxRoadNameBytes = 63;
constexpr std::size_t kMaxInstructionBytes = 95;
constexpr std::size_t kMaxMediaSourceBytes = 31;
constexpr std::size_t kMaxTrackTitleBytes = 63;
constexpr std::size_t kMaxArtistNameBytes = 47;
constexpr std::int64_t kMaxMapSceneCoordinateDeltaE6 = 100'000;

class Writer {
 public:
  void u8(std::uint8_t value) { bytes_.push_back(value); }

  void u16(std::uint16_t value) {
    u8(static_cast<std::uint8_t>(value & 0xFFU));
    u8(static_cast<std::uint8_t>((value >> 8U) & 0xFFU));
  }

  void u24(std::uint32_t value) {
    u8(static_cast<std::uint8_t>(value & 0xFFU));
    u8(static_cast<std::uint8_t>((value >> 8U) & 0xFFU));
    u8(static_cast<std::uint8_t>((value >> 16U) & 0xFFU));
  }

  void u32(std::uint32_t value) {
    u16(static_cast<std::uint16_t>(value & 0xFFFFU));
    u16(static_cast<std::uint16_t>((value >> 16U) & 0xFFFFU));
  }

  void s32(std::int32_t value) {
    u32(static_cast<std::uint32_t>(value));
  }

  void signed_varint(std::int32_t value) {
    const std::int64_t wide = value;
    std::uint32_t encoded = wide >= 0
                                ? static_cast<std::uint32_t>(wide * 2)
                                : static_cast<std::uint32_t>((-wide * 2) - 1);
    do {
      std::uint8_t byte = static_cast<std::uint8_t>(encoded & 0x7FU);
      encoded >>= 7U;
      if (encoded != 0U) {
        byte = static_cast<std::uint8_t>(byte | 0x80U);
      }
      u8(byte);
    } while (encoded != 0U);
  }

  void string_u8(const std::string& value) {
    u8(static_cast<std::uint8_t>(value.size()));
    bytes_.insert(bytes_.end(), value.begin(), value.end());
  }

  [[nodiscard]] Bytes take() { return std::move(bytes_); }

 private:
  Bytes bytes_;
};

class Reader {
 public:
  explicit Reader(ByteView bytes) : bytes_(bytes) {
    if (bytes.size > 0 && bytes.data == nullptr) {
      fail(Error::InvalidArgument);
    }
  }

  [[nodiscard]] bool ok() const noexcept { return error_ == Error::None; }
  [[nodiscard]] Error error() const noexcept { return error_; }
  [[nodiscard]] std::size_t offset() const noexcept { return error_offset_; }
  [[nodiscard]] std::size_t remaining() const noexcept {
    return position_ <= bytes_.size ? bytes_.size - position_ : 0;
  }

  std::uint8_t u8() {
    if (!need(1)) {
      return 0;
    }
    return bytes_.data[position_++];
  }

  std::uint16_t u16() {
    const std::uint16_t low = u8();
    const std::uint16_t high = u8();
    return static_cast<std::uint16_t>(low | (high << 8U));
  }

  std::uint32_t u24() {
    const std::uint32_t low = u8();
    const std::uint32_t middle = u8();
    const std::uint32_t high = u8();
    return low | (middle << 8U) | (high << 16U);
  }

  std::uint32_t u32() {
    const std::uint32_t low = u16();
    const std::uint32_t high = u16();
    return low | (high << 16U);
  }

  std::int32_t s32() { return static_cast<std::int32_t>(u32()); }

  std::int32_t signed_varint() {
    std::uint32_t encoded = 0;
    std::size_t byte_count = 0;
    for (; byte_count < 5; ++byte_count) {
      if (!need(1)) {
        return 0;
      }
      const std::uint8_t byte = bytes_.data[position_++];
      if (byte_count == 4 && (byte & 0xF0U) != 0U) {
        fail(Error::InvalidVarint);
        return 0;
      }
      encoded |= static_cast<std::uint32_t>(byte & 0x7FU)
                 << static_cast<unsigned>(byte_count * 7U);
      if ((byte & 0x80U) == 0U) {
        if (byte_count > 0) {
          const unsigned prior_bits =
              static_cast<unsigned>(byte_count * 7U);
          if (encoded < (std::uint32_t{1} << prior_bits)) {
            fail(Error::InvalidVarint);
            return 0;
          }
        }
        const std::int64_t decoded =
            (encoded & 1U) == 0U
                ? static_cast<std::int64_t>(encoded >> 1U)
                : -static_cast<std::int64_t>((encoded >> 1U) + 1U);
        if (decoded < std::numeric_limits<std::int32_t>::min() ||
            decoded > std::numeric_limits<std::int32_t>::max()) {
          fail(Error::InvalidVarint);
          return 0;
        }
        return static_cast<std::int32_t>(decoded);
      }
    }
    fail(Error::InvalidVarint);
    return 0;
  }

  std::string string_u8(std::size_t maximum_size) {
    const std::size_t length = u8();
    if (!ok()) {
      return {};
    }
    if (length > maximum_size) {
      fail(Error::StringTooLong);
      return {};
    }
    if (!need(length)) {
      return {};
    }
    const auto* begin = reinterpret_cast<const char*>(bytes_.data + position_);
    position_ += length;
    return std::string(begin, length);
  }

  void require_end() {
    if (ok() && position_ != bytes_.size) {
      fail(Error::TrailingPayload);
    }
  }

  void fail(Error error) {
    if (error_ == Error::None) {
      error_ = error;
      error_offset_ = position_;
    }
  }

 private:
  bool need(std::size_t count) {
    if (!ok()) {
      return false;
    }
    if (count > remaining()) {
      fail(Error::TruncatedPayload);
      return false;
    }
    return true;
  }

  ByteView bytes_;
  std::size_t position_ = 0;
  Error error_ = Error::None;
  std::size_t error_offset_ = 0;
};

template <typename Enum>
bool enum_at_most(Enum value, Enum maximum) {
  using Raw = std::underlying_type_t<Enum>;
  return static_cast<Raw>(value) <= static_cast<Raw>(maximum);
}

bool valid_role(EndpointRole role) {
  return role == EndpointRole::Phone || role == EndpointRole::Device;
}

bool valid_connection_state(ConnectionState state) {
  return enum_at_most(state, ConnectionState::Closing);
}

bool valid_ack_status(AckStatus status) {
  return enum_at_most(status, AckStatus::Duplicate);
}

bool valid_navigation_state(NavigationState state) {
  return enum_at_most(state, NavigationState::Arrived);
}

bool valid_maneuver(Maneuver maneuver) {
  return enum_at_most(maneuver, Maneuver::Arrive);
}

bool valid_traffic_level(TrafficLevel level) {
  return enum_at_most(level, TrafficLevel::Severe);
}

bool valid_network_state(NetworkState state) {
  return enum_at_most(state, NetworkState::Online);
}

bool valid_coordinate_system(CoordinateSystem system) {
  return system == CoordinateSystem::Wgs84 ||
         system == CoordinateSystem::Gcj02;
}

bool valid_display_page(DisplayPage page) {
  return enum_at_most(page, DisplayPage::Music);
}

bool valid_map_road_class(MapRoadClass road_class) {
  return enum_at_most(road_class, MapRoadClass::Other);
}

bool valid_map_building_class(MapBuildingClass building_class) {
  return enum_at_most(building_class, MapBuildingClass::Parking);
}

bool valid_command_kind(DeviceCommandKind kind) {
  switch (kind) {
    case DeviceCommandKind::PageSelected:
    case DeviceCommandKind::Tap:
    case DeviceCommandKind::LongPress:
    case DeviceCommandKind::SwipeLeft:
    case DeviceCommandKind::SwipeRight:
    case DeviceCommandKind::SwipeUp:
    case DeviceCommandKind::SwipeDown:
    case DeviceCommandKind::MusicPrevious:
    case DeviceCommandKind::MusicTogglePlayback:
    case DeviceCommandKind::MusicNext:
    case DeviceCommandKind::MusicLike:
      return true;
  }
  return false;
}

bool valid_geo_point(const GeoPointE6& point) {
  return point.latitude_e6 >= -90'000'000 &&
         point.latitude_e6 <= 90'000'000 &&
         point.longitude_e6 >= -180'000'000 &&
         point.longitude_e6 <= 180'000'000;
}

Error validate(const ConnectionStatus& value) {
  if (!valid_role(value.role) || !valid_connection_state(value.state)) {
    return Error::InvalidEnum;
  }
  if (value.minimum_version == 0 ||
      value.minimum_version > value.maximum_version ||
      value.session_id == 0 || value.max_frame_size <= kFrameOverhead ||
      value.max_frame_size > kMaxBleAttributeValueSize ||
      value.heartbeat_interval_ms < 250) {
    return Error::OutOfRange;
  }
  return Error::None;
}

Error validate(const Heartbeat& value) {
  return value.session_id == 0 || value.status_flags != 0
             ? Error::OutOfRange
             : Error::None;
}

Error validate(const Ack& value) {
  if (value.acknowledged_sequence == 0) {
    return Error::SequenceZero;
  }
  return valid_ack_status(value.status) ? Error::None : Error::InvalidEnum;
}

Error validate(const NavigationSnapshot& value) {
  if (!valid_navigation_state(value.state) ||
      !valid_network_state(value.network) ||
      !valid_display_page(value.display_page) ||
      !valid_maneuver(value.maneuver) ||
      !valid_traffic_level(value.traffic)) {
    return Error::InvalidEnum;
  }
  if ((value.flags & ~kKnownNavigationFlags) != 0U ||
      value.heading_cdeg >= 36'000 || value.roundabout_exit > 32) {
    return Error::OutOfRange;
  }
  if ((value.flags & NavigationHasRouteView) != 0U &&
      (value.route_token == 0 || value.route_generation == 0)) {
    return Error::OutOfRange;
  }
  if (value.road_name.size() > kMaxRoadNameBytes ||
      value.instruction.size() > kMaxInstructionBytes) {
    return Error::StringTooLong;
  }
  return Error::None;
}

Error validate(const RouteGeometry& value) {
  if (!valid_coordinate_system(value.coordinate_system)) {
    return Error::InvalidEnum;
  }
  // Existing RouteBundle geometry is explicitly GCJ-02. WGS84 remains a
  // defined enum value for other future point messages, not route geometry.
  if (value.coordinate_system != CoordinateSystem::Gcj02 ||
      value.route_token == 0 || value.route_generation == 0 ||
      value.chunk_count == 0 || value.chunk_index >= value.chunk_count ||
      value.total_point_count == 0 || value.points.empty() ||
      value.total_point_count > kMaxRoutePointsPerChunk ||
      value.chunk_count > value.total_point_count ||
      value.first_point_index > value.total_point_count ||
      value.points.size() > kMaxRoutePointsPerChunk ||
      value.first_point_index + value.points.size() >
          value.total_point_count) {
    return Error::OutOfRange;
  }
  if ((value.chunk_index == 0 && value.first_point_index != 0) ||
      (value.chunk_index != 0 && value.first_point_index == 0) ||
      (value.chunk_index + 1 != value.chunk_count &&
       value.first_point_index + value.points.size() >=
           value.total_point_count) ||
      (value.chunk_index + 1 == value.chunk_count &&
       value.first_point_index + value.points.size() !=
           value.total_point_count)) {
    return Error::OutOfRange;
  }
  for (const GeoPointE6& point : value.points) {
    if (!valid_geo_point(point)) {
      return Error::OutOfRange;
    }
  }
  if (!valid_geo_point(value.view_origin)) {
    return Error::OutOfRange;
  }
  return Error::None;
}

Error validate(const TrafficDeviation& value) {
  if ((value.flags & ~kKnownTrafficDeviationFlags) != 0U) {
    return Error::OutOfRange;
  }
  if (value.segments.size() > kMaxTrafficSegments) {
    return Error::TooManyItems;
  }
  if (value.route_token == 0 || value.route_generation == 0) {
    return Error::OutOfRange;
  }
  std::uint64_t previous_end = 0;
  for (const TrafficSegment& segment : value.segments) {
    if (!valid_traffic_level(segment.level)) {
      return Error::InvalidEnum;
    }
    if (segment.length_m == 0 || segment.start_offset_m > kMaximumU24 ||
        segment.length_m > kMaximumU24 ||
        segment.start_offset_m < previous_end) {
      return Error::OutOfRange;
    }
    previous_end = static_cast<std::uint64_t>(segment.start_offset_m) +
                   segment.length_m;
    if (previous_end > kMaximumU24) {
      return Error::OutOfRange;
    }
  }
  return Error::None;
}

Error validate(const MediaState& value) {
  if ((value.flags & ~kKnownMediaFlags) != 0U) {
    return Error::OutOfRange;
  }
  if (value.source_name.size() > kMaxMediaSourceBytes ||
      value.track_title.size() > kMaxTrackTitleBytes ||
      value.artist_name.size() > kMaxArtistNameBytes) {
    return Error::StringTooLong;
  }
  return Error::None;
}

Error validate(const MapScene& value) {
  if (value.coordinate_system != CoordinateSystem::Gcj02) {
    return valid_coordinate_system(value.coordinate_system)
               ? Error::OutOfRange
               : Error::InvalidEnum;
  }
  if (value.scene_revision == 0 || value.radius_m < 100 ||
      value.radius_m > 1'500 || !valid_geo_point(value.view_origin)) {
    return Error::OutOfRange;
  }
  if (value.roads.size() > kMaxMapSceneRoads ||
      value.buildings.size() > kMaxMapSceneBuildings) {
    return Error::TooManyItems;
  }

  std::size_t road_points = 0;
  std::size_t building_points = 0;
  const auto validate_point = [&value](const GeoPointE6& point) {
    if (!valid_geo_point(point)) {
      return false;
    }
    const std::int64_t latitude_delta =
        static_cast<std::int64_t>(point.latitude_e6) -
        value.view_origin.latitude_e6;
    const std::int64_t longitude_delta =
        static_cast<std::int64_t>(point.longitude_e6) -
        value.view_origin.longitude_e6;
    return latitude_delta >= -kMaxMapSceneCoordinateDeltaE6 &&
           latitude_delta <= kMaxMapSceneCoordinateDeltaE6 &&
           longitude_delta >= -kMaxMapSceneCoordinateDeltaE6 &&
           longitude_delta <= kMaxMapSceneCoordinateDeltaE6;
  };
  for (const MapRoadPolyline& road : value.roads) {
    if (!valid_map_road_class(road.road_class)) {
      return Error::InvalidEnum;
    }
    if (road.points.size() < 2 || road.points.size() > 255) {
      return Error::OutOfRange;
    }
    road_points += road.points.size();
    if (road_points > kMaxMapSceneRoadPoints) {
      return Error::TooManyItems;
    }
    for (const GeoPointE6& point : road.points) {
      if (!validate_point(point)) {
        return Error::OutOfRange;
      }
    }
  }
  for (const MapBuildingFootprint& building : value.buildings) {
    if (!valid_map_building_class(building.building_class)) {
      return Error::InvalidEnum;
    }
    if (building.points.size() < 3 || building.points.size() > 255 ||
        building.points.front() == building.points.back()) {
      return Error::OutOfRange;
    }
    building_points += building.points.size();
    if (building_points > kMaxMapSceneBuildingPoints) {
      return Error::TooManyItems;
    }
    for (const GeoPointE6& point : building.points) {
      if (!validate_point(point)) {
        return Error::OutOfRange;
      }
    }
  }
  return Error::None;
}

Error validate(const DeviceCommand& value) {
  if (!valid_command_kind(value.kind) || !valid_display_page(value.page)) {
    return Error::InvalidEnum;
  }
  return value.command_id == 0 ? Error::OutOfRange : Error::None;
}

template <typename T>
BytesResult encode_payload(const T& value) {
  const Error validation = validate(value);
  if (validation != Error::None) {
    return {{}, validation, 0};
  }

  Writer writer;
  writer.u8(kPayloadRevision);
  if constexpr (std::is_same_v<T, ConnectionStatus>) {
    writer.u8(static_cast<std::uint8_t>(value.role));
    writer.u8(static_cast<std::uint8_t>(value.state));
    writer.u8(value.minimum_version);
    writer.u8(value.maximum_version);
    writer.u32(value.capabilities);
    writer.u32(value.session_id);
    writer.u16(value.max_frame_size);
    writer.u16(value.heartbeat_interval_ms);
  } else if constexpr (std::is_same_v<T, Heartbeat>) {
    writer.u32(value.session_id);
    writer.u32(value.monotonic_ms);
    writer.u16(value.status_flags);
  } else if constexpr (std::is_same_v<T, Ack>) {
    writer.u16(value.acknowledged_sequence);
    writer.u8(static_cast<std::uint8_t>(value.status));
    writer.u16(value.command_id);
  } else if constexpr (std::is_same_v<T, NavigationSnapshot>) {
    writer.u8(static_cast<std::uint8_t>(value.state));
    writer.u8(static_cast<std::uint8_t>(value.network));
    writer.u8(static_cast<std::uint8_t>(value.display_page));
    writer.u8(static_cast<std::uint8_t>(value.maneuver));
    writer.u8(static_cast<std::uint8_t>(value.traffic));
    writer.u16(value.flags);
    writer.u32(value.route_token);
    writer.u32(value.route_generation);
    writer.u32(value.maneuver_id);
    writer.u32(value.distance_to_maneuver_m);
    writer.u32(value.remaining_distance_m);
    writer.u32(value.remaining_duration_s);
    writer.u32(value.route_progress_m);
    writer.u32(value.total_distance_m);
    writer.u16(value.speed_deci_kph);
    writer.u16(value.speed_limit_kph);
    writer.u16(value.heading_cdeg);
    writer.u16(value.accuracy_dm);
    writer.u16(value.cross_track_dm);
    writer.u8(value.roundabout_exit);
    writer.string_u8(value.road_name);
    writer.string_u8(value.instruction);
  } else if constexpr (std::is_same_v<T, RouteGeometry>) {
    writer.u8(static_cast<std::uint8_t>(value.coordinate_system));
    writer.u32(value.route_token);
    writer.u32(value.route_generation);
    writer.u16(value.chunk_index);
    writer.u16(value.chunk_count);
    writer.u16(value.first_point_index);
    writer.u16(value.total_point_count);
    writer.s32(value.view_origin.latitude_e6);
    writer.s32(value.view_origin.longitude_e6);
    writer.u8(static_cast<std::uint8_t>(value.points.size()));
    writer.s32(value.points.front().latitude_e6);
    writer.s32(value.points.front().longitude_e6);
    GeoPointE6 previous = value.points.front();
    for (std::size_t i = 1; i < value.points.size(); ++i) {
      const std::int64_t latitude_delta =
          static_cast<std::int64_t>(value.points[i].latitude_e6) -
          previous.latitude_e6;
      const std::int64_t longitude_delta =
          static_cast<std::int64_t>(value.points[i].longitude_e6) -
          previous.longitude_e6;
      if (latitude_delta < std::numeric_limits<std::int32_t>::min() ||
          latitude_delta > std::numeric_limits<std::int32_t>::max() ||
          longitude_delta < std::numeric_limits<std::int32_t>::min() ||
          longitude_delta > std::numeric_limits<std::int32_t>::max()) {
        return {{}, Error::OutOfRange, i};
      }
      writer.signed_varint(static_cast<std::int32_t>(latitude_delta));
      writer.signed_varint(static_cast<std::int32_t>(longitude_delta));
      previous = value.points[i];
    }
  } else if constexpr (std::is_same_v<T, TrafficDeviation>) {
    writer.u32(value.route_token);
    writer.u32(value.route_generation);
    writer.u16(value.flags);
    writer.u32(value.observed_at_ms);
    writer.u32(value.remaining_duration_s);
    writer.u16(value.cross_track_dm);
    writer.u8(static_cast<std::uint8_t>(value.segments.size()));
    for (const TrafficSegment& segment : value.segments) {
      writer.u24(segment.start_offset_m);
      writer.u24(segment.length_m);
      writer.u8(static_cast<std::uint8_t>(segment.level));
    }
  } else if constexpr (std::is_same_v<T, MediaState>) {
    writer.u8(value.flags);
    writer.u32(value.track_token);
    writer.u16(value.position_s);
    writer.u16(value.duration_s);
    writer.string_u8(value.source_name);
    writer.string_u8(value.track_title);
    writer.string_u8(value.artist_name);
  } else if constexpr (std::is_same_v<T, MapScene>) {
    writer.u8(static_cast<std::uint8_t>(value.coordinate_system));
    writer.u32(value.scene_revision);
    writer.s32(value.view_origin.latitude_e6);
    writer.s32(value.view_origin.longitude_e6);
    writer.u16(value.radius_m);
    writer.u8(static_cast<std::uint8_t>(value.roads.size()));
    writer.u8(static_cast<std::uint8_t>(value.buildings.size()));
    const auto write_points = [&writer, &value](const auto& points) {
      writer.u8(static_cast<std::uint8_t>(points.size()));
      for (const GeoPointE6& point : points) {
        writer.signed_varint(point.latitude_e6 -
                             value.view_origin.latitude_e6);
        writer.signed_varint(point.longitude_e6 -
                             value.view_origin.longitude_e6);
      }
    };
    for (const MapRoadPolyline& road : value.roads) {
      writer.u8(static_cast<std::uint8_t>(road.road_class));
      write_points(road.points);
    }
    for (const MapBuildingFootprint& building : value.buildings) {
      writer.u8(static_cast<std::uint8_t>(building.building_class));
      write_points(building.points);
    }
  } else if constexpr (std::is_same_v<T, DeviceCommand>) {
    writer.u8(static_cast<std::uint8_t>(value.kind));
    writer.u16(value.command_id);
    writer.u8(static_cast<std::uint8_t>(value.page));
    writer.u16(value.x);
    writer.u16(value.y);
    writer.u32(value.event_time_ms);
  }
  return {writer.take(), Error::None, 0};
}

template <typename T>
MessageResult decoded_result(T value, const Reader& reader) {
  if (!reader.ok()) {
    return {{}, reader.error(), reader.offset()};
  }
  const Error validation = validate(value);
  if (validation != Error::None) {
    return {{}, validation, reader.offset()};
  }
  return {Message{std::move(value)}, Error::None, 0};
}

bool begin_payload(Reader& reader) {
  const std::uint8_t revision = reader.u8();
  if (reader.ok() && revision != kPayloadRevision) {
    reader.fail(Error::UnsupportedPayloadRevision);
  }
  return reader.ok();
}

MessageResult decode_connection_status(ByteView payload) {
  Reader reader(payload);
  ConnectionStatus value;
  if (begin_payload(reader)) {
    value.role = static_cast<EndpointRole>(reader.u8());
    value.state = static_cast<ConnectionState>(reader.u8());
    value.minimum_version = reader.u8();
    value.maximum_version = reader.u8();
    value.capabilities = reader.u32();
    value.session_id = reader.u32();
    value.max_frame_size = reader.u16();
    value.heartbeat_interval_ms = reader.u16();
    reader.require_end();
  }
  return decoded_result(std::move(value), reader);
}

MessageResult decode_heartbeat(ByteView payload) {
  Reader reader(payload);
  Heartbeat value;
  if (begin_payload(reader)) {
    value.session_id = reader.u32();
    value.monotonic_ms = reader.u32();
    value.status_flags = reader.u16();
    reader.require_end();
  }
  return decoded_result(std::move(value), reader);
}

MessageResult decode_ack(ByteView payload) {
  Reader reader(payload);
  Ack value;
  if (begin_payload(reader)) {
    value.acknowledged_sequence = reader.u16();
    value.status = static_cast<AckStatus>(reader.u8());
    value.command_id = reader.u16();
    reader.require_end();
  }
  return decoded_result(std::move(value), reader);
}

MessageResult decode_navigation_snapshot(ByteView payload) {
  Reader reader(payload);
  NavigationSnapshot value;
  if (begin_payload(reader)) {
    value.state = static_cast<NavigationState>(reader.u8());
    value.network = static_cast<NetworkState>(reader.u8());
    value.display_page = static_cast<DisplayPage>(reader.u8());
    value.maneuver = static_cast<Maneuver>(reader.u8());
    value.traffic = static_cast<TrafficLevel>(reader.u8());
    value.flags = reader.u16();
    value.route_token = reader.u32();
    value.route_generation = reader.u32();
    value.maneuver_id = reader.u32();
    value.distance_to_maneuver_m = reader.u32();
    value.remaining_distance_m = reader.u32();
    value.remaining_duration_s = reader.u32();
    value.route_progress_m = reader.u32();
    value.total_distance_m = reader.u32();
    value.speed_deci_kph = reader.u16();
    value.speed_limit_kph = reader.u16();
    value.heading_cdeg = reader.u16();
    value.accuracy_dm = reader.u16();
    value.cross_track_dm = reader.u16();
    value.roundabout_exit = reader.u8();
    value.road_name = reader.string_u8(kMaxRoadNameBytes);
    value.instruction = reader.string_u8(kMaxInstructionBytes);
    reader.require_end();
  }
  return decoded_result(std::move(value), reader);
}

MessageResult decode_route_geometry(ByteView payload) {
  Reader reader(payload);
  RouteGeometry value;
  if (begin_payload(reader)) {
    value.coordinate_system =
        static_cast<CoordinateSystem>(reader.u8());
    value.route_token = reader.u32();
    value.route_generation = reader.u32();
    value.chunk_index = reader.u16();
    value.chunk_count = reader.u16();
    value.first_point_index = reader.u16();
    value.total_point_count = reader.u16();
    value.view_origin = {reader.s32(), reader.s32()};
    const std::size_t point_count = reader.u8();
    if (reader.ok() &&
        (point_count == 0 || point_count > kMaxRoutePointsPerChunk)) {
      reader.fail(point_count == 0 ? Error::OutOfRange
                                   : Error::TooManyItems);
    }
    if (reader.ok()) {
      value.points.reserve(point_count);
      GeoPointE6 point{reader.s32(), reader.s32()};
      if (reader.ok()) {
        value.points.push_back(point);
      }
      for (std::size_t i = 1; i < point_count && reader.ok(); ++i) {
        const std::int32_t latitude_delta = reader.signed_varint();
        const std::int32_t longitude_delta = reader.signed_varint();
        const std::int64_t latitude =
            static_cast<std::int64_t>(point.latitude_e6) + latitude_delta;
        const std::int64_t longitude =
            static_cast<std::int64_t>(point.longitude_e6) + longitude_delta;
        if (latitude < std::numeric_limits<std::int32_t>::min() ||
            latitude > std::numeric_limits<std::int32_t>::max() ||
            longitude < std::numeric_limits<std::int32_t>::min() ||
            longitude > std::numeric_limits<std::int32_t>::max()) {
          reader.fail(Error::OutOfRange);
          break;
        }
        point = {static_cast<std::int32_t>(latitude),
                 static_cast<std::int32_t>(longitude)};
        if (!valid_geo_point(point)) {
          reader.fail(Error::OutOfRange);
          break;
        }
        value.points.push_back(point);
      }
      reader.require_end();
    }
  }
  return decoded_result(std::move(value), reader);
}

MessageResult decode_traffic_deviation(ByteView payload) {
  Reader reader(payload);
  TrafficDeviation value;
  if (begin_payload(reader)) {
    value.route_token = reader.u32();
    value.route_generation = reader.u32();
    value.flags = reader.u16();
    value.observed_at_ms = reader.u32();
    value.remaining_duration_s = reader.u32();
    value.cross_track_dm = reader.u16();
    const std::size_t segment_count = reader.u8();
    if (reader.ok() && segment_count > kMaxTrafficSegments) {
      reader.fail(Error::TooManyItems);
    }
    if (reader.ok()) {
      value.segments.reserve(segment_count);
      for (std::size_t i = 0; i < segment_count; ++i) {
        TrafficSegment segment;
        segment.start_offset_m = reader.u24();
        segment.length_m = reader.u24();
        segment.level = static_cast<TrafficLevel>(reader.u8());
        if (!reader.ok()) {
          break;
        }
        value.segments.push_back(segment);
      }
      reader.require_end();
    }
  }
  return decoded_result(std::move(value), reader);
}

MessageResult decode_media_state(ByteView payload) {
  Reader reader(payload);
  MediaState value;
  if (begin_payload(reader)) {
    value.flags = reader.u8();
    value.track_token = reader.u32();
    value.position_s = reader.u16();
    value.duration_s = reader.u16();
    value.source_name = reader.string_u8(kMaxMediaSourceBytes);
    value.track_title = reader.string_u8(kMaxTrackTitleBytes);
    value.artist_name = reader.string_u8(kMaxArtistNameBytes);
    reader.require_end();
  }
  return decoded_result(std::move(value), reader);
}

MessageResult decode_map_scene(ByteView payload) {
  Reader reader(payload);
  MapScene value;
  if (begin_payload(reader)) {
    value.coordinate_system = static_cast<CoordinateSystem>(reader.u8());
    value.scene_revision = reader.u32();
    value.view_origin = {reader.s32(), reader.s32()};
    value.radius_m = reader.u16();
    const std::size_t road_count = reader.u8();
    const std::size_t building_count = reader.u8();
    if (reader.ok() && (road_count > kMaxMapSceneRoads ||
                        building_count > kMaxMapSceneBuildings)) {
      reader.fail(Error::TooManyItems);
    }
    std::size_t road_point_count = 0;
    std::size_t building_point_count = 0;
    const auto read_points = [&reader, &value](std::size_t count,
                                                auto& points) {
      points.reserve(count);
      for (std::size_t i = 0; i < count && reader.ok(); ++i) {
        const std::int32_t latitude_delta = reader.signed_varint();
        const std::int32_t longitude_delta = reader.signed_varint();
        const std::int64_t latitude =
            static_cast<std::int64_t>(value.view_origin.latitude_e6) +
            latitude_delta;
        const std::int64_t longitude =
            static_cast<std::int64_t>(value.view_origin.longitude_e6) +
            longitude_delta;
        if (latitude < std::numeric_limits<std::int32_t>::min() ||
            latitude > std::numeric_limits<std::int32_t>::max() ||
            longitude < std::numeric_limits<std::int32_t>::min() ||
            longitude > std::numeric_limits<std::int32_t>::max()) {
          reader.fail(Error::OutOfRange);
          break;
        }
        points.push_back({static_cast<std::int32_t>(latitude),
                          static_cast<std::int32_t>(longitude)});
      }
    };
    if (reader.ok()) {
      value.roads.reserve(road_count);
      for (std::size_t i = 0; i < road_count && reader.ok(); ++i) {
        MapRoadPolyline road;
        road.road_class = static_cast<MapRoadClass>(reader.u8());
        const std::size_t point_count = reader.u8();
        road_point_count += point_count;
        if (point_count < 2 || road_point_count > kMaxMapSceneRoadPoints) {
          reader.fail(road_point_count > kMaxMapSceneRoadPoints
                          ? Error::TooManyItems
                          : Error::OutOfRange);
          break;
        }
        read_points(point_count, road.points);
        value.roads.push_back(std::move(road));
      }
      value.buildings.reserve(building_count);
      for (std::size_t i = 0; i < building_count && reader.ok(); ++i) {
        MapBuildingFootprint building;
        building.building_class =
            static_cast<MapBuildingClass>(reader.u8());
        const std::size_t point_count = reader.u8();
        building_point_count += point_count;
        if (point_count < 3 ||
            building_point_count > kMaxMapSceneBuildingPoints) {
          reader.fail(building_point_count > kMaxMapSceneBuildingPoints
                          ? Error::TooManyItems
                          : Error::OutOfRange);
          break;
        }
        read_points(point_count, building.points);
        value.buildings.push_back(std::move(building));
      }
      reader.require_end();
    }
  }
  return decoded_result(std::move(value), reader);
}

MessageResult decode_device_command(ByteView payload) {
  Reader reader(payload);
  DeviceCommand value;
  if (begin_payload(reader)) {
    value.kind = static_cast<DeviceCommandKind>(reader.u8());
    value.command_id = reader.u16();
    value.page = static_cast<DisplayPage>(reader.u8());
    value.x = reader.u16();
    value.y = reader.u16();
    value.event_time_ms = reader.u32();
    reader.require_end();
  }
  return decoded_result(std::move(value), reader);
}

ReassemblyResult reassembly_error(Error error,
                                  std::size_t offset = 0) {
  ReassemblyResult result;
  result.state = ReassemblyState::Error;
  result.error = error;
  result.error_offset = offset;
  return result;
}

}  // namespace

const char* to_string(Error error) noexcept {
  switch (error) {
    case Error::None: return "none";
    case Error::InvalidArgument: return "invalid_argument";
    case Error::FrameTooShort: return "frame_too_short";
    case Error::FrameTooLarge: return "frame_too_large";
    case Error::BadMagic: return "bad_magic";
    case Error::UnsupportedVersion: return "unsupported_version";
    case Error::ReservedFlags: return "reserved_flags";
    case Error::SequenceZero: return "sequence_zero";
    case Error::InvalidFragment: return "invalid_fragment";
    case Error::LengthMismatch: return "length_mismatch";
    case Error::CrcMismatch: return "crc_mismatch";
    case Error::MessageTooLarge: return "message_too_large";
    case Error::MissingStart: return "missing_start";
    case Error::UnexpectedFragment: return "unexpected_fragment";
    case Error::FragmentConflict: return "fragment_conflict";
    case Error::StaleSequence: return "stale_sequence";
    case Error::ReassemblyTimeout: return "reassembly_timeout";
    case Error::UnknownMessageType: return "unknown_message_type";
    case Error::UnsupportedPayloadRevision:
      return "unsupported_payload_revision";
    case Error::TruncatedPayload: return "truncated_payload";
    case Error::TrailingPayload: return "trailing_payload";
    case Error::InvalidEnum: return "invalid_enum";
    case Error::OutOfRange: return "out_of_range";
    case Error::StringTooLong: return "string_too_long";
    case Error::TooManyItems: return "too_many_items";
    case Error::InvalidVarint: return "invalid_varint";
  }
  return "unknown_error";
}

std::uint16_t crc16_ccitt_false(ByteView bytes) noexcept {
  if (bytes.size > 0 && bytes.data == nullptr) {
    return 0;
  }
  std::uint16_t crc = 0xFFFFU;
  for (std::size_t i = 0; i < bytes.size; ++i) {
    crc ^= static_cast<std::uint16_t>(bytes.data[i]) << 8U;
    for (int bit = 0; bit < 8; ++bit) {
      crc = (crc & 0x8000U) != 0U
                ? static_cast<std::uint16_t>((crc << 1U) ^ 0x1021U)
                : static_cast<std::uint16_t>(crc << 1U);
    }
  }
  return crc;
}

BytesResult encode_frame(const Frame& frame) {
  if (frame.version != kProtocolVersion) {
    return {{}, Error::UnsupportedVersion, 1};
  }
  if ((frame.flags & ~kKnownFrameFlags) != 0U) {
    return {{}, Error::ReservedFlags, 3};
  }
  if (frame.sequence == 0) {
    return {{}, Error::SequenceZero, 4};
  }
  const std::size_t offset = frame.fragment_offset;
  const std::size_t message_length = frame.message_length;
  const std::size_t fragment_end = offset + frame.payload.size();
  const bool starts = (frame.flags & FrameStart) != 0U;
  const bool ends = (frame.flags & FrameEnd) != 0U;
  if (fragment_end > message_length || starts != (offset == 0) ||
      ends != (fragment_end == message_length)) {
    return {{}, Error::InvalidFragment, 6};
  }
  if (kFrameOverhead + frame.payload.size() >
      kMaxBleAttributeValueSize) {
    return {{}, Error::FrameTooLarge, 0};
  }

  Bytes encoded;
  encoded.reserve(kFrameOverhead + frame.payload.size());
  encoded.push_back(kFrameMagic);
  encoded.push_back(frame.version);
  encoded.push_back(static_cast<std::uint8_t>(frame.type));
  encoded.push_back(frame.flags);
  encoded.push_back(static_cast<std::uint8_t>(frame.sequence & 0xFFU));
  encoded.push_back(
      static_cast<std::uint8_t>((frame.sequence >> 8U) & 0xFFU));
  encoded.push_back(
      static_cast<std::uint8_t>(frame.fragment_offset & 0xFFU));
  encoded.push_back(static_cast<std::uint8_t>(
      (frame.fragment_offset >> 8U) & 0xFFU));
  encoded.push_back(
      static_cast<std::uint8_t>(frame.message_length & 0xFFU));
  encoded.push_back(static_cast<std::uint8_t>(
      (frame.message_length >> 8U) & 0xFFU));
  encoded.insert(encoded.end(), frame.payload.begin(), frame.payload.end());
  const std::uint16_t crc = crc16_ccitt_false(ByteView(encoded));
  encoded.push_back(static_cast<std::uint8_t>(crc & 0xFFU));
  encoded.push_back(static_cast<std::uint8_t>((crc >> 8U) & 0xFFU));
  return {std::move(encoded), Error::None, 0};
}

FrameResult decode_frame(ByteView bytes) {
  if (bytes.size > 0 && bytes.data == nullptr) {
    return {{}, Error::InvalidArgument, 0};
  }
  if (bytes.size < kFrameOverhead) {
    return {{}, Error::FrameTooShort, bytes.size};
  }
  if (bytes.size > kMaxBleAttributeValueSize) {
    return {{}, Error::FrameTooLarge, bytes.size};
  }
  if (bytes.data[0] != kFrameMagic) {
    return {{}, Error::BadMagic, 0};
  }

  const std::size_t crc_offset = bytes.size - kFrameCrcSize;
  const std::uint16_t expected_crc =
      static_cast<std::uint16_t>(bytes.data[crc_offset]) |
      (static_cast<std::uint16_t>(bytes.data[crc_offset + 1]) << 8U);
  const std::uint16_t actual_crc =
      crc16_ccitt_false(ByteView(bytes.data, crc_offset));
  if (actual_crc != expected_crc) {
    return {{}, Error::CrcMismatch, crc_offset};
  }
  if (bytes.data[1] != kProtocolVersion) {
    return {{}, Error::UnsupportedVersion, 1};
  }

  Frame frame;
  frame.version = bytes.data[1];
  frame.type = static_cast<MessageType>(bytes.data[2]);
  frame.flags = bytes.data[3];
  frame.sequence = static_cast<std::uint16_t>(bytes.data[4]) |
                   (static_cast<std::uint16_t>(bytes.data[5]) << 8U);
  frame.fragment_offset =
      static_cast<std::uint16_t>(bytes.data[6]) |
      (static_cast<std::uint16_t>(bytes.data[7]) << 8U);
  frame.message_length =
      static_cast<std::uint16_t>(bytes.data[8]) |
      (static_cast<std::uint16_t>(bytes.data[9]) << 8U);

  if ((frame.flags & ~kKnownFrameFlags) != 0U) {
    return {{}, Error::ReservedFlags, 3};
  }
  if (frame.sequence == 0) {
    return {{}, Error::SequenceZero, 4};
  }
  const std::size_t payload_size = bytes.size - kFrameOverhead;
  const std::size_t fragment_end =
      static_cast<std::size_t>(frame.fragment_offset) + payload_size;
  const bool starts = (frame.flags & FrameStart) != 0U;
  const bool ends = (frame.flags & FrameEnd) != 0U;
  if (fragment_end > frame.message_length ||
      starts != (frame.fragment_offset == 0) ||
      ends != (fragment_end == frame.message_length)) {
    return {{}, Error::InvalidFragment, 6};
  }
  frame.payload.assign(bytes.data + kFrameHeaderSize,
                       bytes.data + crc_offset);
  return {std::move(frame), Error::None, 0};
}

FramesResult fragment_message(MessageType type,
                              std::uint16_t sequence,
                              ByteView payload,
                              std::size_t max_frame_size,
                              std::uint8_t application_flags,
                              std::uint8_t version) {
  if (payload.size > 0 && payload.data == nullptr) {
    return {{}, Error::InvalidArgument, 0};
  }
  if (sequence == 0) {
    return {{}, Error::SequenceZero, 0};
  }
  if (version != kProtocolVersion) {
    return {{}, Error::UnsupportedVersion, 0};
  }
  if ((application_flags & ~kApplicationFrameFlags) != 0U) {
    return {{}, Error::ReservedFlags, 0};
  }
  if (payload.size > std::numeric_limits<std::uint16_t>::max()) {
    return {{}, Error::MessageTooLarge, payload.size};
  }
  if (max_frame_size < kFrameOverhead ||
      max_frame_size > kMaxBleAttributeValueSize ||
      (payload.size > 0 && max_frame_size == kFrameOverhead)) {
    return {{}, Error::InvalidArgument, max_frame_size};
  }

  const std::size_t fragment_capacity = max_frame_size - kFrameOverhead;
  const std::size_t frame_count =
      payload.size == 0
          ? 1
          : (payload.size + fragment_capacity - 1) / fragment_capacity;
  std::vector<Bytes> frames;
  frames.reserve(frame_count);
  std::size_t offset = 0;
  do {
    const std::size_t count =
        payload.size == 0
            ? 0
            : std::min(fragment_capacity, payload.size - offset);
    Frame frame;
    frame.version = version;
    frame.type = type;
    frame.flags = application_flags;
    if (offset == 0) {
      frame.flags = static_cast<std::uint8_t>(frame.flags | FrameStart);
    }
    if (offset + count == payload.size) {
      frame.flags = static_cast<std::uint8_t>(frame.flags | FrameEnd);
    }
    frame.sequence = sequence;
    frame.fragment_offset = static_cast<std::uint16_t>(offset);
    frame.message_length = static_cast<std::uint16_t>(payload.size);
    if (count > 0) {
      frame.payload.assign(payload.data + offset,
                           payload.data + offset + count);
    }
    BytesResult encoded = encode_frame(frame);
    if (!encoded.ok()) {
      return {{}, encoded.error, encoded.offset};
    }
    frames.push_back(std::move(encoded.value));
    offset += count;
  } while (offset < payload.size);
  return {std::move(frames), Error::None, 0};
}

bool sequence_is_newer(std::uint16_t candidate,
                       std::uint16_t reference) noexcept {
  if (candidate == 0 || reference == 0 || candidate == reference) {
    return false;
  }
  const std::uint16_t distance =
      static_cast<std::uint16_t>(candidate - reference);
  return distance < 0x8000U;
}

SequenceGenerator::SequenceGenerator(std::uint16_t first) noexcept {
  reset(first);
}

std::uint16_t SequenceGenerator::next() noexcept {
  const std::uint16_t value = next_;
  ++next_;
  if (next_ == 0) {
    next_ = 1;
  }
  return value;
}

void SequenceGenerator::reset(std::uint16_t first) noexcept {
  next_ = first == 0 ? 1 : first;
}

Reassembler::Reassembler(ReassemblerConfig config) : config_(config) {
  if (config_.max_message_size == 0 ||
      config_.max_message_size >
          std::numeric_limits<std::uint16_t>::max()) {
    config_.max_message_size = kDefaultMaxMessageSize;
  }
  if (config_.fragment_timeout_ms == 0) {
    config_.fragment_timeout_ms = 1'000;
  }
  buffer_.reserve(std::min<std::size_t>(config_.max_message_size, 512));
}

ReassemblyResult Reassembler::push(ByteView encoded_frame,
                                   TimestampMs now_ms) {
  const bool timed_out = expire(now_ms);
  FrameResult decoded = decode_frame(encoded_frame);
  if (!decoded.ok()) {
    return reassembly_error(decoded.error, decoded.offset);
  }
  Frame& frame = decoded.value;
  const bool starts = (frame.flags & FrameStart) != 0U;

  ReassemblyResult result;
  result.message.type = frame.type;
  result.message.version = frame.version;
  result.message.flags =
      static_cast<std::uint8_t>(frame.flags & kApplicationFrameFlags);
  result.message.sequence = frame.sequence;
  result.dropped_incomplete = timed_out;

  if (active_ && frame.sequence != active_sequence_) {
    if (!starts) {
      return reassembly_error(Error::UnexpectedFragment,
                              frame.fragment_offset);
    }
    if (config_.enforce_monotonic_sequence &&
        !sequence_is_newer(frame.sequence, active_sequence_)) {
      return reassembly_error(Error::StaleSequence, frame.sequence);
    }
    clear_active();
    result.dropped_incomplete = true;
  }

  if (!active_) {
    // A retransmitted multi-frame message produces a START followed by
    // continuations. Once its sequence has completed, consume each frame as
    // duplicate instead of reporting MissingStart for the continuations.
    if (has_last_sequence_ && frame.sequence == last_sequence_) {
      result.state = ReassemblyState::DuplicateMessage;
      return result;
    }
    if (!starts) {
      return reassembly_error(timed_out ? Error::ReassemblyTimeout
                                        : Error::MissingStart,
                              frame.fragment_offset);
    }
    if (has_last_sequence_) {
      if (config_.enforce_monotonic_sequence &&
          !sequence_is_newer(frame.sequence, last_sequence_)) {
        return reassembly_error(Error::StaleSequence, frame.sequence);
      }
    }
    if (frame.message_length > config_.max_message_size) {
      return reassembly_error(Error::MessageTooLarge,
                              frame.message_length);
    }
    active_ = true;
    active_version_ = frame.version;
    active_type_ = frame.type;
    active_application_flags_ =
        static_cast<std::uint8_t>(frame.flags & kApplicationFrameFlags);
    active_sequence_ = frame.sequence;
    active_message_length_ = frame.message_length;
    expected_offset_ = 0;
    buffer_.clear();
    buffer_.reserve(frame.message_length);
  }

  if (frame.version != active_version_ || frame.type != active_type_ ||
      frame.message_length != active_message_length_ ||
      (frame.flags & kApplicationFrameFlags) !=
          active_application_flags_) {
    return reassembly_error(Error::FragmentConflict,
                            frame.fragment_offset);
  }

  const std::size_t offset = frame.fragment_offset;
  if (offset < expected_offset_) {
    if (offset + frame.payload.size() > buffer_.size() ||
        !std::equal(frame.payload.begin(), frame.payload.end(),
                    buffer_.begin() + static_cast<std::ptrdiff_t>(offset))) {
      return reassembly_error(Error::FragmentConflict, offset);
    }
    result.state = ReassemblyState::DuplicateFragment;
    return result;
  }
  if (offset != expected_offset_) {
    return reassembly_error(Error::UnexpectedFragment, offset);
  }

  buffer_.insert(buffer_.end(), frame.payload.begin(), frame.payload.end());
  expected_offset_ += frame.payload.size();
  last_fragment_at_ms_ = now_ms;

  const bool ends = (frame.flags & FrameEnd) != 0U;
  if (!ends) {
    result.state = ReassemblyState::InProgress;
    return result;
  }
  if (expected_offset_ != active_message_length_) {
    return reassembly_error(Error::LengthMismatch, expected_offset_);
  }

  result.state = ReassemblyState::Complete;
  result.message.type = active_type_;
  result.message.version = active_version_;
  result.message.flags = active_application_flags_;
  result.message.sequence = active_sequence_;
  result.message.payload = std::move(buffer_);
  last_sequence_ = active_sequence_;
  has_last_sequence_ = true;
  clear_active();
  return result;
}

bool Reassembler::expire(TimestampMs now_ms) noexcept {
  if (!active_ || now_ms < last_fragment_at_ms_ ||
      now_ms - last_fragment_at_ms_ < config_.fragment_timeout_ms) {
    return false;
  }
  clear_active();
  return true;
}

void Reassembler::reset() noexcept {
  clear_active();
  has_last_sequence_ = false;
  last_sequence_ = 0;
}

void Reassembler::clear_active() noexcept {
  active_ = false;
  active_version_ = kProtocolVersion;
  active_type_ = MessageType::Heartbeat;
  active_application_flags_ = 0;
  active_sequence_ = 0;
  active_message_length_ = 0;
  expected_offset_ = 0;
  last_fragment_at_ms_ = 0;
  buffer_.clear();
}

LinkWatchdog::LinkWatchdog(TimestampMs timeout_ms) noexcept {
  set_timeout(timeout_ms);
}

void LinkWatchdog::set_timeout(TimestampMs timeout_ms) noexcept {
  timeout_ms_ = timeout_ms == 0 ? 1 : timeout_ms;
}

void LinkWatchdog::note_valid_frame(TimestampMs now_ms) noexcept {
  last_rx_ms_ = now_ms;
  armed_ = true;
}

void LinkWatchdog::reset() noexcept {
  last_rx_ms_ = 0;
  armed_ = false;
}

bool LinkWatchdog::armed() const noexcept { return armed_; }

bool LinkWatchdog::expired(TimestampMs now_ms) const noexcept {
  return armed_ && now_ms >= deadline_ms();
}

TimestampMs LinkWatchdog::deadline_ms() const noexcept {
  if (!armed_) {
    return 0;
  }
  if (timeout_ms_ >
      std::numeric_limits<TimestampMs>::max() - last_rx_ms_) {
    return std::numeric_limits<TimestampMs>::max();
  }
  return last_rx_ms_ + timeout_ms_;
}

bool ConnectionStatus::operator==(const ConnectionStatus& rhs) const noexcept {
  return std::tie(role, state, minimum_version, maximum_version,
                  capabilities, session_id, max_frame_size,
                  heartbeat_interval_ms) ==
         std::tie(rhs.role, rhs.state, rhs.minimum_version,
                  rhs.maximum_version, rhs.capabilities, rhs.session_id,
                  rhs.max_frame_size, rhs.heartbeat_interval_ms);
}

bool Heartbeat::operator==(const Heartbeat& rhs) const noexcept {
  return std::tie(session_id, monotonic_ms, status_flags) ==
         std::tie(rhs.session_id, rhs.monotonic_ms, rhs.status_flags);
}

bool Ack::operator==(const Ack& rhs) const noexcept {
  return std::tie(acknowledged_sequence, status, command_id) ==
         std::tie(rhs.acknowledged_sequence, rhs.status, rhs.command_id);
}

bool NavigationSnapshot::operator==(
    const NavigationSnapshot& rhs) const noexcept {
  return std::tie(state, network, display_page, maneuver, traffic, flags,
                  route_token, route_generation, maneuver_id,
                  distance_to_maneuver_m,
                  remaining_distance_m, remaining_duration_s,
                  route_progress_m, total_distance_m, speed_deci_kph,
                  speed_limit_kph,
                  heading_cdeg, accuracy_dm, cross_track_dm,
                  roundabout_exit, road_name, instruction) ==
         std::tie(rhs.state, rhs.network, rhs.display_page, rhs.maneuver,
                  rhs.traffic, rhs.flags, rhs.route_token,
                  rhs.route_generation, rhs.maneuver_id,
                  rhs.distance_to_maneuver_m, rhs.remaining_distance_m,
                  rhs.remaining_duration_s, rhs.route_progress_m,
                  rhs.total_distance_m, rhs.speed_deci_kph,
                  rhs.speed_limit_kph,
                  rhs.heading_cdeg, rhs.accuracy_dm, rhs.cross_track_dm,
                  rhs.roundabout_exit, rhs.road_name, rhs.instruction);
}

bool GeoPointE6::operator==(const GeoPointE6& rhs) const noexcept {
  return latitude_e6 == rhs.latitude_e6 &&
         longitude_e6 == rhs.longitude_e6;
}

bool RouteGeometry::operator==(const RouteGeometry& rhs) const noexcept {
  return std::tie(coordinate_system, route_token, route_generation,
                  chunk_index, chunk_count, first_point_index,
                  total_point_count, view_origin, points) ==
         std::tie(rhs.coordinate_system, rhs.route_token,
                  rhs.route_generation, rhs.chunk_index, rhs.chunk_count,
                  rhs.first_point_index, rhs.total_point_count,
                  rhs.view_origin, rhs.points);
}

bool TrafficSegment::operator==(const TrafficSegment& rhs) const noexcept {
  return std::tie(start_offset_m, length_m, level) ==
         std::tie(rhs.start_offset_m, rhs.length_m, rhs.level);
}

bool TrafficDeviation::operator==(
    const TrafficDeviation& rhs) const noexcept {
  return std::tie(route_token, route_generation, flags, observed_at_ms,
                  remaining_duration_s, cross_track_dm, segments) ==
         std::tie(rhs.route_token, rhs.route_generation, rhs.flags,
                  rhs.observed_at_ms, rhs.remaining_duration_s,
                  rhs.cross_track_dm, rhs.segments);
}

bool MediaState::operator==(const MediaState& rhs) const noexcept {
  return std::tie(flags, track_token, position_s, duration_s, source_name,
                  track_title, artist_name) ==
         std::tie(rhs.flags, rhs.track_token, rhs.position_s,
                  rhs.duration_s, rhs.source_name, rhs.track_title,
                  rhs.artist_name);
}

bool MapRoadPolyline::operator==(
    const MapRoadPolyline& rhs) const noexcept {
  return road_class == rhs.road_class && points == rhs.points;
}

bool MapBuildingFootprint::operator==(
    const MapBuildingFootprint& rhs) const noexcept {
  return building_class == rhs.building_class && points == rhs.points;
}

bool MapScene::operator==(const MapScene& rhs) const noexcept {
  return std::tie(coordinate_system, scene_revision, view_origin, radius_m,
                  roads, buildings) ==
         std::tie(rhs.coordinate_system, rhs.scene_revision,
                  rhs.view_origin, rhs.radius_m, rhs.roads, rhs.buildings);
}

bool DeviceCommand::operator==(const DeviceCommand& rhs) const noexcept {
  return std::tie(kind, command_id, page, x, y, event_time_ms) ==
         std::tie(rhs.kind, rhs.command_id, rhs.page, rhs.x, rhs.y,
                  rhs.event_time_ms);
}

MessageType message_type(const Message& message) noexcept {
  return std::visit(
      [](const auto& value) {
        using T = std::decay_t<decltype(value)>;
        if constexpr (std::is_same_v<T, ConnectionStatus>) {
          return MessageType::ConnectionStatus;
        } else if constexpr (std::is_same_v<T, Heartbeat>) {
          return MessageType::Heartbeat;
        } else if constexpr (std::is_same_v<T, Ack>) {
          return MessageType::Ack;
        } else if constexpr (std::is_same_v<T, NavigationSnapshot>) {
          return MessageType::NavigationSnapshot;
        } else if constexpr (std::is_same_v<T, RouteGeometry>) {
          return MessageType::RouteGeometry;
        } else if constexpr (std::is_same_v<T, TrafficDeviation>) {
          return MessageType::TrafficDeviation;
        } else if constexpr (std::is_same_v<T, MediaState>) {
          return MessageType::MediaState;
        } else if constexpr (std::is_same_v<T, MapScene>) {
          return MessageType::MapScene;
        } else {
          return MessageType::DeviceCommand;
        }
      },
      message);
}

BytesResult encode_message(const Message& message) {
  return std::visit(
      [](const auto& value) { return encode_payload(value); }, message);
}

MessageResult decode_message(MessageType type, ByteView payload) {
  switch (type) {
    case MessageType::ConnectionStatus:
      return decode_connection_status(payload);
    case MessageType::Heartbeat: return decode_heartbeat(payload);
    case MessageType::Ack: return decode_ack(payload);
    case MessageType::NavigationSnapshot:
      return decode_navigation_snapshot(payload);
    case MessageType::RouteGeometry:
      return decode_route_geometry(payload);
    case MessageType::TrafficDeviation:
      return decode_traffic_deviation(payload);
    case MessageType::MediaState: return decode_media_state(payload);
    case MessageType::MapScene: return decode_map_scene(payload);
    case MessageType::DeviceCommand:
      return decode_device_command(payload);
  }
  return {{}, Error::UnknownMessageType, 0};
}

std::uint32_t route_token(std::string_view route_id) noexcept {
  std::uint32_t hash = 2'166'136'261U;
  for (const char character : route_id) {
    hash ^= static_cast<std::uint8_t>(character);
    hash *= 16'777'619U;
  }
  return hash == 0 ? 1 : hash;
}

}  // namespace moto::ble
