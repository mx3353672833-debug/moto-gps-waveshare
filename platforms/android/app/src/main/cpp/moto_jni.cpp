// JNI adapter over the shared C++ navigation core and BLE v1 codec.
//
// Kotlin owns platform effects (GATT, location, HTTP, UI). Route matching,
// rerouting, arrival, wire constants and binary payload layouts stay in
// shared/nav_core and shared/ble_protocol, exactly as on iOS and the ESP32.
// Strings cross the boundary as UTF-8 byte arrays because JNI's "modified
// UTF-8" differs from the UTF-8 the protocol and route IDs are defined over.

#include <jni.h>

#include <algorithm>
#include <cinttypes>
#include <climits>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "moto/ble_protocol/ble_protocol.hpp"
#include "nav_app/nav_app.hpp"

namespace {

namespace ble = moto::ble;
namespace nav = moto::nav;

// ---------------------------------------------------------------------------
// JNI conversion helpers

std::string Utf8(JNIEnv* env, jbyteArray bytes) {
  if (bytes == nullptr) return {};
  const jsize size = env->GetArrayLength(bytes);
  std::string value(static_cast<std::size_t>(size), '\0');
  if (size > 0) {
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(value.data()));
  }
  return value;
}

std::vector<std::string> Utf8Array(JNIEnv* env, jobjectArray values) {
  std::vector<std::string> result;
  if (values == nullptr) return result;
  const jsize size = env->GetArrayLength(values);
  result.reserve(static_cast<std::size_t>(size));
  for (jsize index = 0; index < size; ++index) {
    auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(values, index));
    result.push_back(Utf8(env, item));
    env->DeleteLocalRef(item);
  }
  return result;
}

template <typename Array, typename Element>
std::vector<Element> Primitive(JNIEnv* env, Array values,
                               void (JNIEnv::*read)(Array, jsize, jsize, Element*)) {
  std::vector<Element> result;
  if (values == nullptr) return result;
  const jsize size = env->GetArrayLength(values);
  result.resize(static_cast<std::size_t>(size));
  if (size > 0) (env->*read)(values, 0, size, result.data());
  return result;
}

std::vector<jdouble> Doubles(JNIEnv* env, jdoubleArray values) {
  return Primitive<jdoubleArray, jdouble>(env, values, &JNIEnv::GetDoubleArrayRegion);
}

std::vector<jint> Ints(JNIEnv* env, jintArray values) {
  return Primitive<jintArray, jint>(env, values, &JNIEnv::GetIntArrayRegion);
}

std::vector<jlong> Longs(JNIEnv* env, jlongArray values) {
  return Primitive<jlongArray, jlong>(env, values, &JNIEnv::GetLongArrayRegion);
}

std::vector<jboolean> Booleans(JNIEnv* env, jbooleanArray values) {
  return Primitive<jbooleanArray, jboolean>(env, values, &JNIEnv::GetBooleanArrayRegion);
}

jbyteArray ByteArray(JNIEnv* env, const std::uint8_t* data, std::size_t size) {
  jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
  if (result != nullptr && size > 0) {
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(size),
                            reinterpret_cast<const jbyte*>(data));
  }
  return result;
}

jbyteArray ByteArray(JNIEnv* env, const std::string& value) {
  return ByteArray(env, reinterpret_cast<const std::uint8_t*>(value.data()), value.size());
}

jobjectArray FrameArray(JNIEnv* env, const std::vector<ble::Bytes>& frames) {
  jclass byteArrayClass = env->FindClass("[B");
  jobjectArray result =
      env->NewObjectArray(static_cast<jsize>(frames.size()), byteArrayClass, nullptr);
  for (std::size_t index = 0; index < frames.size(); ++index) {
    jbyteArray frame = ByteArray(env, frames[index].data(), frames[index].size());
    env->SetObjectArrayElement(result, static_cast<jsize>(index), frame);
    env->DeleteLocalRef(frame);
  }
  env->DeleteLocalRef(byteArrayClass);
  return result;
}

jlongArray LongArray(JNIEnv* env, const std::vector<jlong>& values) {
  jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
  if (result != nullptr && !values.empty()) {
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
  }
  return result;
}

std::uint32_t U32(jlong value) {
  return static_cast<std::uint32_t>(std::clamp<jlong>(value, 0, UINT32_MAX));
}

std::uint16_t U16(jlong value) {
  return static_cast<std::uint16_t>(std::clamp<jlong>(value, 0, UINT16_MAX));
}

std::uint8_t U8(jlong value) {
  return static_cast<std::uint8_t>(std::clamp<jlong>(value, 0, UINT8_MAX));
}

std::int32_t CoordinateE6(double value) {
  constexpr double minimum = static_cast<double>(INT32_MIN);
  constexpr double maximum = static_cast<double>(INT32_MAX);
  if (!std::isfinite(value)) return 0;
  return static_cast<std::int32_t>(std::clamp(std::round(value * 1'000'000.0), minimum, maximum));
}

// ---------------------------------------------------------------------------
// Minimal JSON writer for NavCore output. Input never contains JSON; only
// numbers, booleans and UTF-8 strings from the shared core are written.

class JsonWriter {
 public:
  void BeginObject() { Separator(); out_ += '{'; first_ = true; }
  void EndObject() { out_ += '}'; first_ = false; }
  void BeginArray() { Separator(); out_ += '['; first_ = true; }
  void EndArray() { out_ += ']'; first_ = false; }

  void Key(const char* key) {
    Separator();
    WriteString(key);
    out_ += ':';
    first_ = true;
  }

  void String(std::string_view value) { Separator(); WriteString(value); }
  void Bool(bool value) { Separator(); out_ += value ? "true" : "false"; }

  void Number(double value) {
    Separator();
    if (!std::isfinite(value)) value = 0.0;
    char buffer[40];
    std::snprintf(buffer, sizeof(buffer), "%.17g", value);
    out_ += buffer;
  }

  void Unsigned(std::uint64_t value) {
    Separator();
    char buffer[24];
    std::snprintf(buffer, sizeof(buffer), "%" PRIu64, value);
    out_ += buffer;
  }

  template <typename T>
  void Field(const char* key, T value) {
    Key(key);
    if constexpr (std::is_same_v<T, bool>) {
      Bool(value);
    } else if constexpr (std::is_floating_point_v<T>) {
      Number(static_cast<double>(value));
    } else {
      Unsigned(static_cast<std::uint64_t>(value));
    }
  }

  void Field(const char* key, std::string_view value) { Key(key); String(value); }
  void Field(const char* key, const char* value) { Key(key); String(value); }
  void Field(const char* key, const std::string& value) { Key(key); String(value); }

  const std::string& str() const { return out_; }

 private:
  void Separator() {
    if (!first_) out_ += ',';
    first_ = false;
  }

  void WriteString(std::string_view value) {
    out_ += '"';
    for (const char raw : value) {
      const auto c = static_cast<unsigned char>(raw);
      switch (c) {
        case '"': out_ += "\\\""; break;
        case '\\': out_ += "\\\\"; break;
        case '\n': out_ += "\\n"; break;
        case '\r': out_ += "\\r"; break;
        case '\t': out_ += "\\t"; break;
        default:
          if (c < 0x20) {
            char buffer[8];
            std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
            out_ += buffer;
          } else {
            out_ += raw;
          }
      }
    }
    out_ += '"';
  }

  std::string out_;
  bool first_ = true;
};

// ---------------------------------------------------------------------------
// NavCore names. These mirror platforms/ios/App/Adapters/Navigation/MotoNavCoreBridge.mm.

const char* StateName(nav::NavState state) {
  switch (state) {
    case nav::NavState::Idle: return "idle";
    case nav::NavState::Acquiring: return "acquiring";
    case nav::NavState::Planning: return "planning";
    case nav::NavState::Navigating: return "navigating";
    case nav::NavState::Rerouting: return "rerouting";
    case nav::NavState::Arrived: return "arrived";
  }
  return "idle";
}

const char* NetworkName(nav::NetworkState state) {
  switch (state) {
    case nav::NetworkState::Offline: return "offline";
    case nav::NetworkState::Connecting: return "connecting";
    case nav::NetworkState::Online: return "online";
  }
  return "offline";
}

const char* DisplayPageName(nav::DisplayPage page) {
  switch (page) {
    case nav::DisplayPage::Navigation: return "navigation";
    case nav::DisplayPage::Speed: return "speed";
    case nav::DisplayPage::Compass: return "compass";
    case nav::DisplayPage::Music: return "music";
  }
  return "navigation";
}

const char* ManeuverName(nav::ManeuverType type) {
  switch (type) {
    case nav::ManeuverType::Continue: return "continue";
    case nav::ManeuverType::SlightLeft: return "slight_left";
    case nav::ManeuverType::Left: return "left";
    case nav::ManeuverType::SharpLeft: return "sharp_left";
    case nav::ManeuverType::UTurnLeft: return "u_turn_left";
    case nav::ManeuverType::SlightRight: return "slight_right";
    case nav::ManeuverType::Right: return "right";
    case nav::ManeuverType::SharpRight: return "sharp_right";
    case nav::ManeuverType::UTurnRight: return "u_turn_right";
    case nav::ManeuverType::Roundabout: return "roundabout";
    case nav::ManeuverType::Exit: return "exit";
    case nav::ManeuverType::Arrive: return "arrive";
    case nav::ManeuverType::Unknown: return "unknown";
  }
  return "unknown";
}

const char* TrafficName(nav::TrafficLevel level) {
  switch (level) {
    case nav::TrafficLevel::FreeFlow: return "free_flow";
    case nav::TrafficLevel::Slow: return "slow";
    case nav::TrafficLevel::Congested: return "congested";
    case nav::TrafficLevel::Severe: return "severe";
    case nav::TrafficLevel::Unknown: return "unknown";
  }
  return "unknown";
}

nav::ManeuverType ManeuverFromName(std::string_view name) {
  if (name == "continue") return nav::ManeuverType::Continue;
  if (name == "slight_left") return nav::ManeuverType::SlightLeft;
  if (name == "left") return nav::ManeuverType::Left;
  if (name == "sharp_left") return nav::ManeuverType::SharpLeft;
  if (name == "u_turn_left") return nav::ManeuverType::UTurnLeft;
  if (name == "slight_right") return nav::ManeuverType::SlightRight;
  if (name == "right") return nav::ManeuverType::Right;
  if (name == "sharp_right") return nav::ManeuverType::SharpRight;
  if (name == "u_turn_right") return nav::ManeuverType::UTurnRight;
  if (name == "roundabout") return nav::ManeuverType::Roundabout;
  if (name == "exit") return nav::ManeuverType::Exit;
  if (name == "arrive") return nav::ManeuverType::Arrive;
  return nav::ManeuverType::Unknown;
}

nav::TrafficLevel TrafficFromName(std::string_view name) {
  if (name == "free_flow") return nav::TrafficLevel::FreeFlow;
  if (name == "slow") return nav::TrafficLevel::Slow;
  if (name == "congested") return nav::TrafficLevel::Congested;
  if (name == "severe") return nav::TrafficLevel::Severe;
  return nav::TrafficLevel::Unknown;
}

std::vector<nav::TrafficSegment> TrafficSegments(JNIEnv* env, jdoubleArray starts,
                                                 jdoubleArray ends, jobjectArray levels) {
  const auto start_values = Doubles(env, starts);
  const auto end_values = Doubles(env, ends);
  const auto level_values = Utf8Array(env, levels);
  const std::size_t count =
      std::min({start_values.size(), end_values.size(), level_values.size()});
  std::vector<nav::TrafficSegment> segments;
  segments.reserve(count);
  for (std::size_t index = 0; index < count; ++index) {
    segments.push_back({start_values[index], end_values[index],
                        TrafficFromName(level_values[index])});
  }
  return segments;
}

void WriteSnapshot(JsonWriter& json, const nav::NavSnapshot& source) {
  json.BeginObject();
  json.Field("stateName", StateName(source.state));
  json.Field("networkName", NetworkName(source.network));
  json.Field("displayPageName", DisplayPageName(source.display_page));
  json.Field("hasDestination", source.has_destination);
  json.Field("hasUsableFix", source.has_usable_fix);
  json.Field("gnssStale", source.gnss_stale);
  json.Field("offRoute", source.off_route);
  json.Field("routeRequestInFlight", source.route_request_in_flight);
  json.Field("trafficRequestInFlight", source.traffic_request_in_flight);
  json.Field("positionLatitudeDeg", source.position.latitude_deg);
  json.Field("positionLongitudeDeg", source.position.longitude_deg);
  json.Field("speedMps", source.speed_mps);
  json.Field("headingDeg", source.heading_deg);
  json.Field("horizontalAccuracyM", source.horizontal_accuracy_m);
  json.Field("crossTrackDistanceM", source.cross_track_distance_m);
  json.Field("speedLimitKph", source.speed_limit_kph);
  json.Field("hasRouteView", source.has_route_view);
  json.Field("routeViewOriginLatitudeDeg", source.route_view_origin.latitude_deg);
  json.Field("routeViewOriginLongitudeDeg", source.route_view_origin.longitude_deg);
  json.Key("routeViewPoints");
  json.BeginArray();
  for (std::size_t index = 0; index < source.route_view_point_count; ++index) {
    json.BeginArray();
    json.Number(source.route_view_points[index].latitude_deg);
    json.Number(source.route_view_points[index].longitude_deg);
    json.EndArray();
  }
  json.EndArray();
  json.Field("routeId", source.route_id);
  json.Field("routeProgressM", source.route_progress_m);
  json.Field("totalDistanceM", source.total_distance_m);
  json.Field("remainingDistanceM", source.remaining_distance_m);
  json.Field("remainingDurationS", source.remaining_duration_s);
  json.Field("hasNextManeuver", source.has_next_maneuver);
  json.Field("maneuverId", source.next_maneuver.id);
  json.Field("maneuverTypeName", ManeuverName(source.next_maneuver.type));
  json.Field("distanceToManeuverM", source.distance_to_next_maneuver_m);
  json.Field("roadName", source.next_maneuver.road_name);
  json.Field("instructionText", source.next_maneuver.instruction);
  json.Field("roundaboutExit", source.next_maneuver.roundabout_exit);
  json.Field("trafficName", TrafficName(source.traffic_ahead));
  json.Field("nowMs", source.now_ms);
  json.Field("routeGeneration", source.route_generation);
  json.EndObject();
}

jbyteArray NavResult(JNIEnv* env, const nav::NavApp& app, const nav::NavCommands& commands) {
  JsonWriter json;
  json.BeginObject();
  json.Key("snapshot");
  WriteSnapshot(json, app.snapshot());
  json.Key("commands");
  json.BeginArray();
  for (const auto& command : commands) {
    json.BeginObject();
    json.Field("typeName", command.type == nav::CommandType::RequestRoute
                               ? "request_route"
                               : "request_traffic");
    json.Field("requestId", command.request_id);
    json.Field("originLatitudeDeg", command.route.origin.latitude_deg);
    json.Field("originLongitudeDeg", command.route.origin.longitude_deg);
    json.Field("destinationLatitudeDeg", command.route.destination.latitude_deg);
    json.Field("destinationLongitudeDeg", command.route.destination.longitude_deg);
    json.Field("reroute", command.route.is_reroute);
    json.Field("routeId", command.route_id);
    json.EndObject();
  }
  json.EndArray();
  json.EndObject();
  return ByteArray(env, json.str());
}

nav::NavApp* App(jlong handle) { return reinterpret_cast<nav::NavApp*>(handle); }

jbyteArray Handle(JNIEnv* env, jlong handle, nav::NavEvent event) {
  auto* app = App(handle);
  if (app == nullptr) return nullptr;
  const auto commands = app->handle(std::move(event));
  return NavResult(env, *app, commands);
}

// ---------------------------------------------------------------------------
// BLE codec. Mirrors platforms/ios/App/Adapters/BLE/MotoBLEProtocolBridge.mm.

struct CodecStorage {
  explicit CodecStorage(std::size_t frame_size)
      : maximum_frame_size(std::clamp<std::size_t>(frame_size, ble::kFrameOverhead + 1,
                                                   ble::kMaxBleAttributeValueSize)),
        reassembler(ble::ReassemblerConfig{}) {}

  std::size_t maximum_frame_size;
  ble::SequenceGenerator sequence;
  std::uint16_t last_encoded_sequence = 0;
  ble::Reassembler reassembler;
  std::string last_error;
};

CodecStorage* Codec(jlong handle) { return reinterpret_cast<CodecStorage*>(handle); }

void SetError(CodecStorage* storage, ble::Error error, std::size_t offset) {
  char buffer[96];
  std::snprintf(buffer, sizeof(buffer), "BLE v1: %s (offset %zu)", ble::to_string(error), offset);
  storage->last_error = buffer;
}

jobjectArray EncodeMessage(JNIEnv* env, CodecStorage* storage, const ble::Message& message,
                           std::uint8_t flags) {
  auto payload = ble::encode_message(message);
  if (!payload.ok()) {
    SetError(storage, payload.error, payload.offset);
    return nullptr;
  }
  const auto sequence = storage->sequence.next();
  auto frames = ble::fragment_message(ble::message_type(message), sequence,
                                      ble::ByteView(payload.value),
                                      storage->maximum_frame_size, flags);
  if (!frames.ok()) {
    SetError(storage, frames.error, frames.offset);
    return nullptr;
  }
  storage->last_encoded_sequence = sequence;
  return FrameArray(env, frames.value);
}

ble::NavigationState NavigationStateFromName(std::string_view name) {
  if (name == "acquiring") return ble::NavigationState::Acquiring;
  if (name == "planning") return ble::NavigationState::Planning;
  if (name == "navigating") return ble::NavigationState::Navigating;
  if (name == "rerouting") return ble::NavigationState::Rerouting;
  if (name == "arrived") return ble::NavigationState::Arrived;
  return ble::NavigationState::Idle;
}

ble::NetworkState NetworkStateFromName(std::string_view name) {
  if (name == "connecting") return ble::NetworkState::Connecting;
  if (name == "online") return ble::NetworkState::Online;
  return ble::NetworkState::Offline;
}

ble::DisplayPage DisplayPageFromName(std::string_view name) {
  if (name == "speed") return ble::DisplayPage::Speed;
  if (name == "compass") return ble::DisplayPage::Compass;
  if (name == "music") return ble::DisplayPage::Music;
  return ble::DisplayPage::Navigation;
}

ble::Maneuver BleManeuverFromName(std::string_view name) {
  if (name == "continue") return ble::Maneuver::Continue;
  if (name == "slight_left") return ble::Maneuver::SlightLeft;
  if (name == "left") return ble::Maneuver::Left;
  if (name == "sharp_left") return ble::Maneuver::SharpLeft;
  if (name == "u_turn_left") return ble::Maneuver::UTurnLeft;
  if (name == "slight_right") return ble::Maneuver::SlightRight;
  if (name == "right") return ble::Maneuver::Right;
  if (name == "sharp_right") return ble::Maneuver::SharpRight;
  if (name == "u_turn_right") return ble::Maneuver::UTurnRight;
  if (name == "roundabout") return ble::Maneuver::Roundabout;
  if (name == "exit") return ble::Maneuver::Exit;
  if (name == "arrive") return ble::Maneuver::Arrive;
  return ble::Maneuver::Unknown;
}

ble::TrafficLevel BleTrafficFromName(std::string_view name) {
  if (name == "free_flow") return ble::TrafficLevel::FreeFlow;
  if (name == "slow") return ble::TrafficLevel::Slow;
  if (name == "congested") return ble::TrafficLevel::Congested;
  if (name == "severe") return ble::TrafficLevel::Severe;
  return ble::TrafficLevel::Unknown;
}

// names: state, network, page, maneuver, traffic
// flags: hasDestination, hasFix, gnssStale, offRoute, routeRequestInFlight,
//        trafficRequestInFlight, hasRouteView
// numbers: routeToken, routeGeneration, maneuverId, distanceToManeuverM,
//          remainingDistanceM, remainingDurationS, routeProgressM, totalDistanceM,
//          speedDeciKph, speedLimitKph, headingCdeg, accuracyDm, crossTrackDm,
//          roundaboutExit
ble::NavigationSnapshot SnapshotFromInput(JNIEnv* env, jobjectArray names_array,
                                          jbooleanArray flags_array, jlongArray numbers_array,
                                          jbyteArray road_name, jbyteArray instruction) {
  const auto names = Utf8Array(env, names_array);
  const auto flags = Booleans(env, flags_array);
  const auto numbers = Longs(env, numbers_array);
  auto name = [&](std::size_t index) -> std::string_view {
    return index < names.size() ? std::string_view(names[index]) : std::string_view();
  };
  auto flag = [&](std::size_t index) { return index < flags.size() && flags[index] != JNI_FALSE; };
  auto number = [&](std::size_t index) -> jlong {
    return index < numbers.size() ? numbers[index] : 0;
  };

  ble::NavigationSnapshot snapshot;
  snapshot.state = NavigationStateFromName(name(0));
  snapshot.network = NetworkStateFromName(name(1));
  snapshot.display_page = DisplayPageFromName(name(2));
  snapshot.maneuver = BleManeuverFromName(name(3));
  snapshot.traffic = BleTrafficFromName(name(4));
  if (flag(0)) snapshot.flags |= ble::NavigationHasDestination;
  if (flag(1)) snapshot.flags |= ble::NavigationHasFix;
  if (flag(2)) snapshot.flags |= ble::NavigationGnssStale;
  if (flag(3)) snapshot.flags |= ble::NavigationOffRoute;
  if (flag(4)) snapshot.flags |= ble::NavigationRouteRequestInFlight;
  if (flag(5)) snapshot.flags |= ble::NavigationTrafficRequestInFlight;
  if (flag(6)) snapshot.flags |= ble::NavigationHasRouteView;
  if (!name(3).empty() && name(3) != "unknown") {
    snapshot.flags |= ble::NavigationHasNextManeuver;
  }
  snapshot.route_token = U32(number(0));
  snapshot.route_generation = U32(number(1));
  snapshot.maneuver_id = U32(number(2));
  snapshot.distance_to_maneuver_m = U32(number(3));
  snapshot.remaining_distance_m = U32(number(4));
  snapshot.remaining_duration_s = U32(number(5));
  snapshot.route_progress_m = U32(number(6));
  snapshot.total_distance_m = U32(number(7));
  snapshot.speed_deci_kph = U16(number(8));
  snapshot.speed_limit_kph = U16(number(9));
  snapshot.heading_cdeg = U16(number(10));
  snapshot.accuracy_dm = U16(number(11));
  snapshot.cross_track_dm = U16(number(12));
  snapshot.roundabout_exit = U8(number(13));
  snapshot.road_name = Utf8(env, road_name);
  snapshot.instruction = Utf8(env, instruction);
  return snapshot;
}

std::string Utf8Prefix(std::string bytes, std::size_t maximum_bytes) {
  if (bytes.size() <= maximum_bytes) return bytes;
  // Byte-sized UTF-8 lengths: trim on a code-point boundary so a long Chinese
  // title cannot make the shared decoder reject an otherwise valid MediaState.
  std::size_t end = maximum_bytes;
  while (end > 0 && (static_cast<unsigned char>(bytes[end]) & 0xC0U) == 0x80U) --end;
  bytes.resize(end);
  return bytes;
}

ble::MapRoadClass MapRoadClassFromName(std::string_view name) {
  if (name == "motorway") return ble::MapRoadClass::Motorway;
  if (name == "primary") return ble::MapRoadClass::Primary;
  if (name == "secondary") return ble::MapRoadClass::Secondary;
  if (name == "residential") return ble::MapRoadClass::Residential;
  if (name == "service") return ble::MapRoadClass::Service;
  return ble::MapRoadClass::Other;
}

ble::MapBuildingClass MapBuildingClassFromName(std::string_view name) {
  if (name == "landmark") return ble::MapBuildingClass::Landmark;
  if (name == "parking") return ble::MapBuildingClass::Parking;
  return ble::MapBuildingClass::Generic;
}

// Polylines are flattened: counts[i] points, then lat/lon E6 pairs in order.
template <typename Shape, typename Class>
std::vector<Shape> MapShapes(JNIEnv* env, jobjectArray class_names, jintArray point_counts,
                             jintArray points_e6, Class (*class_from_name)(std::string_view)) {
  const auto classes = Utf8Array(env, class_names);
  const auto counts = Ints(env, point_counts);
  const auto points = Ints(env, points_e6);
  std::vector<Shape> shapes;
  std::size_t cursor = 0;
  const std::size_t count = std::min(classes.size(), counts.size());
  shapes.reserve(count);
  for (std::size_t index = 0; index < count; ++index) {
    Shape shape;
    shape.points.clear();
    if constexpr (std::is_same_v<Shape, ble::MapRoadPolyline>) {
      shape.road_class = class_from_name(classes[index]);
    } else {
      shape.building_class = class_from_name(classes[index]);
    }
    const auto point_count = static_cast<std::size_t>(std::max<jint>(0, counts[index]));
    for (std::size_t point = 0; point < point_count && cursor + 1 < points.size(); ++point) {
      shape.points.push_back({points[cursor], points[cursor + 1]});
      cursor += 2;
    }
    shapes.push_back(std::move(shape));
  }
  return shapes;
}

ble::MapScene MapSceneFromInput(JNIEnv* env, jlong revision, jint origin_lat_e6,
                                jint origin_lon_e6, jint radius_m, jobjectArray road_classes,
                                jintArray road_counts, jintArray road_points,
                                jobjectArray building_classes, jintArray building_counts,
                                jintArray building_points) {
  ble::MapScene scene;
  scene.scene_revision = U32(revision);
  scene.view_origin = {origin_lat_e6, origin_lon_e6};
  scene.radius_m = U16(radius_m);
  scene.roads = MapShapes<ble::MapRoadPolyline, ble::MapRoadClass>(
      env, road_classes, road_counts, road_points, &MapRoadClassFromName);
  scene.buildings = MapShapes<ble::MapBuildingFootprint, ble::MapBuildingClass>(
      env, building_classes, building_counts, building_points, &MapBuildingClassFromName);
  return scene;
}

ble::ConnectionStatus PhoneConnectionStatus(const CodecStorage* storage,
                                            ble::ConnectionState state,
                                            std::uint32_t session_id) {
  ble::ConnectionStatus status;
  status.role = ble::EndpointRole::Phone;
  status.state = state;
  status.capabilities = ble::CapabilityNavigation | ble::CapabilityRouteGeometry |
                        ble::CapabilityTraffic | ble::CapabilityMediaState |
                        ble::CapabilityTouchCommands | ble::CapabilityMusicCommands |
                        ble::CapabilityCommandAck | ble::CapabilityMapScene;
  status.session_id = session_id;
  status.max_frame_size = static_cast<std::uint16_t>(storage->maximum_frame_size);
  status.heartbeat_interval_ms = 1'000;
  return status;
}

// Inbound layout shared with Kotlin's InboundMessage parser.
constexpr jlong kInboundPartial = 0;
constexpr jlong kInboundDuplicate = 1;
constexpr jlong kInboundConnectionStatus = 2;
constexpr jlong kInboundHeartbeat = 3;
constexpr jlong kInboundAck = 4;
constexpr jlong kInboundDeviceCommand = 5;
constexpr jlong kInboundOther = 6;
constexpr jlong kInboundNavigationSnapshot = 7;
constexpr jlong kInboundRouteGeometry = 8;
constexpr jlong kInboundMediaState = 9;
constexpr jlong kInboundMapScene = 10;

jlongArray Inbound(JNIEnv* env, CodecStorage* storage, jbyteArray frame, jlong now_ms) {
  const std::string bytes = Utf8(env, frame);
  auto result = storage->reassembler.push(
      ble::ByteView(reinterpret_cast<const std::uint8_t*>(bytes.data()), bytes.size()),
      static_cast<ble::TimestampMs>(std::max<jlong>(0, now_ms)));
  const jlong ack_requested = (result.message.flags & ble::AckRequested) != 0U ? 1 : 0;
  if (!result.complete()) {
    if (result.state == ble::ReassemblyState::InProgress ||
        result.state == ble::ReassemblyState::DuplicateFragment) {
      return LongArray(env, {kInboundPartial, result.message.sequence, ack_requested});
    }
    if (result.state == ble::ReassemblyState::DuplicateMessage) {
      return LongArray(env, {kInboundDuplicate, result.message.sequence, ack_requested});
    }
    SetError(storage, result.error, result.error_offset);
    return nullptr;
  }

  auto decoded = ble::decode_message(result.message.type, ble::ByteView(result.message.payload));
  if (!decoded.ok()) {
    SetError(storage, decoded.error, decoded.offset);
    return nullptr;
  }
  const jlong sequence = result.message.sequence;
  if (const auto* status = std::get_if<ble::ConnectionStatus>(&decoded.value)) {
    return LongArray(env, {kInboundConnectionStatus, sequence, ack_requested,
                           static_cast<jlong>(status->role), static_cast<jlong>(status->state),
                           status->minimum_version, status->maximum_version,
                           static_cast<jlong>(status->capabilities),
                           static_cast<jlong>(status->session_id), status->max_frame_size,
                           status->heartbeat_interval_ms});
  }
  if (const auto* heartbeat = std::get_if<ble::Heartbeat>(&decoded.value)) {
    return LongArray(env, {kInboundHeartbeat, sequence, ack_requested,
                           static_cast<jlong>(heartbeat->session_id),
                           static_cast<jlong>(heartbeat->monotonic_ms)});
  }
  if (const auto* ack = std::get_if<ble::Ack>(&decoded.value)) {
    return LongArray(env, {kInboundAck, sequence, ack_requested, ack->acknowledged_sequence,
                           static_cast<jlong>(ack->status), ack->command_id});
  }
  if (const auto* command = std::get_if<ble::DeviceCommand>(&decoded.value)) {
    return LongArray(env, {kInboundDeviceCommand, sequence, ack_requested,
                           static_cast<jlong>(command->kind), command->command_id,
                           static_cast<jlong>(command->page), command->x, command->y,
                           static_cast<jlong>(command->event_time_ms)});
  }
  if (const auto* snapshot = std::get_if<ble::NavigationSnapshot>(&decoded.value)) {
    return LongArray(env, {kInboundNavigationSnapshot, sequence, ack_requested,
                           static_cast<jlong>(snapshot->state),
                           static_cast<jlong>(snapshot->route_token),
                           static_cast<jlong>(snapshot->remaining_distance_m)});
  }
  if (const auto* geometry = std::get_if<ble::RouteGeometry>(&decoded.value)) {
    return LongArray(env, {kInboundRouteGeometry, sequence, ack_requested,
                           static_cast<jlong>(geometry->route_token),
                           static_cast<jlong>(geometry->points.size())});
  }
  if (const auto* media = std::get_if<ble::MediaState>(&decoded.value)) {
    return LongArray(env, {kInboundMediaState, sequence, ack_requested, media->flags,
                           static_cast<jlong>(media->track_token)});
  }
  if (const auto* scene = std::get_if<ble::MapScene>(&decoded.value)) {
    return LongArray(env, {kInboundMapScene, sequence, ack_requested,
                           static_cast<jlong>(scene->scene_revision),
                           static_cast<jlong>(scene->roads.size()),
                           static_cast<jlong>(scene->buildings.size())});
  }
  return LongArray(env, {kInboundOther, sequence, ack_requested});
}

}  // namespace

#define MOTO_JNI(return_type, name) \
  extern "C" JNIEXPORT return_type JNICALL Java_top_maler_motogps_core_MotoNative_##name

// ---------------------------------------------------------------------------
// NavApp

MOTO_JNI(jlong, navCreate)(JNIEnv*, jclass) {
  return reinterpret_cast<jlong>(new nav::NavApp(nav::NavCoreConfig{}));
}

MOTO_JNI(void, navDestroy)(JNIEnv*, jclass, jlong handle) { delete App(handle); }

MOTO_JNI(jbyteArray, navSnapshot)(JNIEnv* env, jclass, jlong handle) {
  auto* app = App(handle);
  if (app == nullptr) return nullptr;
  return NavResult(env, *app, {});
}

MOTO_JNI(jbyteArray, navReset)(JNIEnv* env, jclass, jlong handle) {
  return Handle(env, handle, nav::Reset{});
}

MOTO_JNI(jbyteArray, navBegin)(JNIEnv* env, jclass, jlong handle, jdouble longitude,
                               jdouble latitude) {
  return Handle(env, handle, nav::BeginNavigation{{latitude, longitude}});
}

MOTO_JNI(jbyteArray, navCancel)(JNIEnv* env, jclass, jlong handle) {
  return Handle(env, handle, nav::CancelNavigation{});
}

MOTO_JNI(jbyteArray, navSetNetwork)(JNIEnv* env, jclass, jlong handle, jbyteArray name_bytes) {
  const std::string name = Utf8(env, name_bytes);
  auto state = nav::NetworkState::Offline;
  if (name == "connecting") state = nav::NetworkState::Connecting;
  if (name == "online") state = nav::NetworkState::Online;
  return Handle(env, handle, nav::NetworkChanged{state});
}

MOTO_JNI(jbyteArray, navSelectPage)(JNIEnv* env, jclass, jlong handle, jbyteArray name_bytes) {
  const std::string name = Utf8(env, name_bytes);
  auto page = nav::DisplayPage::Navigation;
  if (name == "speed") page = nav::DisplayPage::Speed;
  if (name == "compass") page = nav::DisplayPage::Compass;
  if (name == "music") page = nav::DisplayPage::Music;
  return Handle(env, handle, nav::DisplayPageSelected{page});
}

MOTO_JNI(jbyteArray, navPushFix)(JNIEnv* env, jclass, jlong handle, jdouble longitude,
                                 jdouble latitude, jdouble accuracy_m, jdouble speed_mps,
                                 jdouble heading_deg, jlong timestamp_ms) {
  nav::GnssFix fix;
  fix.position = {latitude, longitude};
  fix.accuracy_m = static_cast<float>(accuracy_m);
  fix.speed_mps = static_cast<float>(speed_mps);
  fix.heading_deg = static_cast<float>(heading_deg);
  fix.timestamp_ms = static_cast<nav::TimestampMs>(std::max<jlong>(0, timestamp_ms));
  return Handle(env, handle, nav::GnssFixReceived{fix});
}

MOTO_JNI(jbyteArray, navAcceptRoute)(JNIEnv* env, jclass, jlong handle, jlong request_id,
                                     jbyteArray route_id, jdoubleArray polyline_lat_lon,
                                     jlongArray maneuver_ids, jobjectArray maneuver_types,
                                     jdoubleArray maneuver_offsets, jobjectArray maneuver_roads,
                                     jobjectArray maneuver_instructions,
                                     jintArray maneuver_roundabout_exits,
                                     jdoubleArray traffic_starts, jdoubleArray traffic_ends,
                                     jobjectArray traffic_levels, jdouble total_distance_m,
                                     jlong total_duration_s, jint speed_limit_kph,
                                     jlong generated_at_ms, jlong received_at_ms) {
  nav::RouteBundle route;
  route.route_id = Utf8(env, route_id);
  route.total_distance_m = total_distance_m;
  route.total_duration_s = U32(total_duration_s);
  route.speed_limit_kph = U16(speed_limit_kph);
  route.generated_at_ms = static_cast<nav::TimestampMs>(std::max<jlong>(0, generated_at_ms));

  const auto points = Doubles(env, polyline_lat_lon);
  route.polyline.reserve(points.size() / 2);
  for (std::size_t index = 0; index + 1 < points.size(); index += 2) {
    route.polyline.push_back({points[index], points[index + 1]});
  }

  const auto ids = Longs(env, maneuver_ids);
  const auto types = Utf8Array(env, maneuver_types);
  const auto offsets = Doubles(env, maneuver_offsets);
  const auto roads = Utf8Array(env, maneuver_roads);
  const auto instructions = Utf8Array(env, maneuver_instructions);
  const auto exits = Ints(env, maneuver_roundabout_exits);
  const std::size_t maneuver_count = std::min({ids.size(), types.size(), offsets.size(),
                                               roads.size(), instructions.size(), exits.size()});
  route.maneuvers.reserve(maneuver_count);
  for (std::size_t index = 0; index < maneuver_count; ++index) {
    nav::Maneuver maneuver;
    maneuver.id = U32(ids[index]);
    maneuver.type = ManeuverFromName(types[index]);
    maneuver.route_offset_m = offsets[index];
    maneuver.road_name = roads[index];
    maneuver.instruction = instructions[index];
    maneuver.roundabout_exit = U8(exits[index]);
    route.maneuvers.push_back(std::move(maneuver));
  }
  route.traffic = TrafficSegments(env, traffic_starts, traffic_ends, traffic_levels);
  return Handle(env, handle,
                nav::RouteReady{U32(request_id), std::move(route),
                                static_cast<nav::TimestampMs>(std::max<jlong>(0, received_at_ms))});
}

MOTO_JNI(jbyteArray, navRejectRoute)(JNIEnv* env, jclass, jlong handle, jlong request_id,
                                     jboolean retryable, jlong received_at_ms) {
  return Handle(env, handle,
                nav::RouteFailed{U32(request_id), retryable != JNI_FALSE,
                                 static_cast<nav::TimestampMs>(std::max<jlong>(0, received_at_ms))});
}

MOTO_JNI(jbyteArray, navAcceptTraffic)(JNIEnv* env, jclass, jlong handle, jbyteArray route_id,
                                       jdoubleArray starts, jdoubleArray ends,
                                       jobjectArray levels, jlong remaining_duration_s,
                                       jlong request_id, jlong observed_at_ms) {
  nav::TrafficSnapshot traffic;
  traffic.route_id = Utf8(env, route_id);
  traffic.remaining_duration_s = U32(remaining_duration_s);
  traffic.observed_at_ms = static_cast<nav::TimestampMs>(std::max<jlong>(0, observed_at_ms));
  traffic.segments = TrafficSegments(env, starts, ends, levels);
  return Handle(env, handle, nav::TrafficUpdated{U32(request_id), std::move(traffic)});
}

MOTO_JNI(jbyteArray, navRejectTraffic)(JNIEnv* env, jclass, jlong handle, jlong request_id,
                                       jlong received_at_ms) {
  return Handle(env, handle,
                nav::TrafficUpdateFailed{U32(request_id), static_cast<nav::TimestampMs>(
                                                              std::max<jlong>(0, received_at_ms))});
}

MOTO_JNI(jbyteArray, navTick)(JNIEnv* env, jclass, jlong handle, jlong now_ms) {
  return Handle(env, handle,
                nav::Tick{static_cast<nav::TimestampMs>(std::max<jlong>(0, now_ms))});
}

// ---------------------------------------------------------------------------
// BLE v1 codec (phone role)

MOTO_JNI(jstring, bleServiceUuid)(JNIEnv* env, jclass) { return env->NewStringUTF(ble::kServiceUuid); }
MOTO_JNI(jstring, blePhoneToDeviceUuid)(JNIEnv* env, jclass) {
  return env->NewStringUTF(ble::kPhoneToDeviceUuid);
}
MOTO_JNI(jstring, bleDeviceToPhoneUuid)(JNIEnv* env, jclass) {
  return env->NewStringUTF(ble::kDeviceToPhoneUuid);
}

MOTO_JNI(jlong, bleCreate)(JNIEnv*, jclass, jint maximum_frame_size) {
  return reinterpret_cast<jlong>(new CodecStorage(static_cast<std::size_t>(std::max(0, maximum_frame_size))));
}

MOTO_JNI(void, bleDestroy)(JNIEnv*, jclass, jlong handle) { delete Codec(handle); }

MOTO_JNI(void, bleSetMaximumFrameSize)(JNIEnv*, jclass, jlong handle, jint maximum_frame_size) {
  auto* storage = Codec(handle);
  storage->maximum_frame_size = std::clamp<std::size_t>(
      static_cast<std::size_t>(std::max(0, maximum_frame_size)), ble::kFrameOverhead + 1,
      ble::kMaxBleAttributeValueSize);
}

MOTO_JNI(jint, bleMaximumFrameSize)(JNIEnv*, jclass, jlong handle) {
  return static_cast<jint>(Codec(handle)->maximum_frame_size);
}

MOTO_JNI(void, bleResetInbound)(JNIEnv*, jclass, jlong handle) { Codec(handle)->reassembler.reset(); }

MOTO_JNI(jint, bleLastEncodedSequence)(JNIEnv*, jclass, jlong handle) {
  return Codec(handle)->last_encoded_sequence;
}

MOTO_JNI(jstring, bleLastError)(JNIEnv* env, jclass, jlong handle) {
  return env->NewStringUTF(Codec(handle)->last_error.c_str());
}

MOTO_JNI(jlong, bleRouteToken)(JNIEnv* env, jclass, jbyteArray route_id) {
  return static_cast<jlong>(ble::route_token(Utf8(env, route_id)));
}

MOTO_JNI(jobjectArray, bleEncodePhoneStatus)(JNIEnv* env, jclass, jlong handle, jboolean ready,
                                             jlong session_id) {
  auto* storage = Codec(handle);
  const auto status = PhoneConnectionStatus(
      storage, ready != JNI_FALSE ? ble::ConnectionState::Ready : ble::ConnectionState::Starting,
      U32(session_id));
  return EncodeMessage(env, storage, ble::Message{status}, 0);
}

MOTO_JNI(jobjectArray, bleEncodeHeartbeat)(JNIEnv* env, jclass, jlong handle, jlong session_id,
                                           jlong monotonic_ms) {
  auto* storage = Codec(handle);
  ble::Heartbeat heartbeat;
  heartbeat.session_id = U32(session_id);
  heartbeat.monotonic_ms = static_cast<std::uint32_t>(monotonic_ms);
  heartbeat.status_flags = 0;
  return EncodeMessage(env, storage, ble::Message{heartbeat}, 0);
}

MOTO_JNI(jobjectArray, bleEncodeAck)(JNIEnv* env, jclass, jlong handle, jint sequence, jint status,
                                     jint command_id) {
  auto* storage = Codec(handle);
  ble::Ack ack;
  ack.acknowledged_sequence = U16(sequence);
  ack.status = static_cast<ble::AckStatus>(U8(status));
  ack.command_id = U16(command_id);
  return EncodeMessage(env, storage, ble::Message{ack}, 0);
}

MOTO_JNI(jobjectArray, bleEncodeNavigationSnapshot)(JNIEnv* env, jclass, jlong handle,
                                                    jobjectArray names, jbooleanArray flags,
                                                    jlongArray numbers, jbyteArray road_name,
                                                    jbyteArray instruction) {
  auto* storage = Codec(handle);
  const auto snapshot = SnapshotFromInput(env, names, flags, numbers, road_name, instruction);
  return EncodeMessage(env, storage, ble::Message{snapshot}, 0);
}

MOTO_JNI(jbyteArray, bleEncodeNavigationPayload)(JNIEnv* env, jclass, jlong handle,
                                                 jobjectArray names, jbooleanArray flags,
                                                 jlongArray numbers, jbyteArray road_name,
                                                 jbyteArray instruction) {
  auto* storage = Codec(handle);
  const auto payload = ble::encode_message(
      ble::Message{SnapshotFromInput(env, names, flags, numbers, road_name, instruction)});
  if (!payload.ok()) {
    SetError(storage, payload.error, payload.offset);
    return nullptr;
  }
  return ByteArray(env, payload.value.data(), payload.value.size());
}

MOTO_JNI(jobjectArray, bleEncodeRouteGeometry)(JNIEnv* env, jclass, jlong handle,
                                               jbyteArray route_id, jlong route_generation,
                                               jdouble origin_latitude, jdouble origin_longitude,
                                               jdoubleArray points_lat_lon) {
  auto* storage = Codec(handle);
  const auto points = Doubles(env, points_lat_lon);
  const std::string id = Utf8(env, route_id);
  if (points.size() < 2 || id.empty()) {
    return FrameArray(env, {});
  }
  ble::RouteGeometry geometry;
  geometry.route_token = ble::route_token(id);
  geometry.route_generation = U32(route_generation);
  geometry.total_point_count =
      static_cast<std::uint16_t>(std::min<std::size_t>(points.size() / 2, ble::kMaxRoutePointsPerChunk));
  geometry.view_origin = {CoordinateE6(origin_latitude), CoordinateE6(origin_longitude)};
  geometry.points.reserve(geometry.total_point_count);
  for (std::size_t index = 0; index < geometry.total_point_count; ++index) {
    geometry.points.push_back(
        {CoordinateE6(points[index * 2]), CoordinateE6(points[index * 2 + 1])});
  }
  return EncodeMessage(env, storage, ble::Message{geometry}, 0);
}

MOTO_JNI(jbyteArray, bleEncodeRouteGeometryPayload)(JNIEnv* env, jclass, jlong handle,
                                                    jbyteArray route_id, jlong route_generation,
                                                    jdouble origin_latitude,
                                                    jdouble origin_longitude,
                                                    jdoubleArray points_lat_lon) {
  auto* storage = Codec(handle);
  const auto points = Doubles(env, points_lat_lon);
  ble::RouteGeometry geometry;
  geometry.route_token = ble::route_token(Utf8(env, route_id));
  geometry.route_generation = U32(route_generation);
  geometry.total_point_count =
      static_cast<std::uint16_t>(std::min<std::size_t>(points.size() / 2, ble::kMaxRoutePointsPerChunk));
  geometry.view_origin = {CoordinateE6(origin_latitude), CoordinateE6(origin_longitude)};
  for (std::size_t index = 0; index < geometry.total_point_count; ++index) {
    geometry.points.push_back(
        {CoordinateE6(points[index * 2]), CoordinateE6(points[index * 2 + 1])});
  }
  const auto payload = ble::encode_message(ble::Message{geometry});
  if (!payload.ok()) {
    SetError(storage, payload.error, payload.offset);
    return nullptr;
  }
  return ByteArray(env, payload.value.data(), payload.value.size());
}

MOTO_JNI(jobjectArray, bleEncodeMediaState)(JNIEnv* env, jclass, jlong handle, jbooleanArray flags,
                                            jlong track_token, jint position_s, jint duration_s,
                                            jbyteArray source_name, jbyteArray track_title,
                                            jbyteArray artist_name) {
  auto* storage = Codec(handle);
  const auto flag_values = Booleans(env, flags);
  auto flag = [&](std::size_t index) {
    return index < flag_values.size() && flag_values[index] != JNI_FALSE;
  };
  ble::MediaState state;
  if (flag(0)) state.flags |= ble::MediaConnected;
  if (flag(1)) state.flags |= ble::MediaPlaying;
  if (flag(2)) state.flags |= ble::MediaLikeAvailable;
  if (flag(3)) state.flags |= ble::MediaLiked;
  state.track_token = U32(track_token);
  state.position_s = U16(position_s);
  state.duration_s = U16(duration_s);
  state.source_name = Utf8Prefix(Utf8(env, source_name), 31);
  state.track_title = Utf8Prefix(Utf8(env, track_title), 63);
  state.artist_name = Utf8Prefix(Utf8(env, artist_name), 47);
  return EncodeMessage(env, storage, ble::Message{state}, 0);
}

MOTO_JNI(jobjectArray, bleEncodeMapScene)(JNIEnv* env, jclass, jlong handle, jlong revision,
                                          jint origin_lat_e6, jint origin_lon_e6, jint radius_m,
                                          jobjectArray road_classes, jintArray road_counts,
                                          jintArray road_points, jobjectArray building_classes,
                                          jintArray building_counts, jintArray building_points) {
  auto* storage = Codec(handle);
  const auto scene = MapSceneFromInput(env, revision, origin_lat_e6, origin_lon_e6, radius_m,
                                       road_classes, road_counts, road_points, building_classes,
                                       building_counts, building_points);
  return EncodeMessage(env, storage, ble::Message{scene}, ble::AckRequested);
}

MOTO_JNI(jbyteArray, bleEncodeMapScenePayload)(JNIEnv* env, jclass, jlong handle, jlong revision,
                                               jint origin_lat_e6, jint origin_lon_e6,
                                               jint radius_m, jobjectArray road_classes,
                                               jintArray road_counts, jintArray road_points,
                                               jobjectArray building_classes,
                                               jintArray building_counts,
                                               jintArray building_points) {
  auto* storage = Codec(handle);
  const auto payload = ble::encode_message(ble::Message{MapSceneFromInput(
      env, revision, origin_lat_e6, origin_lon_e6, radius_m, road_classes, road_counts,
      road_points, building_classes, building_counts, building_points)});
  if (!payload.ok()) {
    SetError(storage, payload.error, payload.offset);
    return nullptr;
  }
  return ByteArray(env, payload.value.data(), payload.value.size());
}

MOTO_JNI(jlongArray, blePushFrame)(JNIEnv* env, jclass, jlong handle, jbyteArray frame,
                                   jlong received_at_ms) {
  return Inbound(env, Codec(handle), frame, received_at_ms);
}

// ---------------------------------------------------------------------------
// Device-role encoders. Used only by the on-device protocol simulator in
// instrumented tests, so the phone central can be exercised against the same
// shared codec the ESP32 firmware runs.

MOTO_JNI(jobjectArray, deviceEncodeStatus)(JNIEnv* env, jclass, jlong handle, jint state,
                                           jlong session_id, jint maximum_frame_size,
                                           jint heartbeat_interval_ms, jlong capabilities) {
  auto* storage = Codec(handle);
  ble::ConnectionStatus status;
  status.role = ble::EndpointRole::Device;
  status.state = static_cast<ble::ConnectionState>(U8(state));
  status.capabilities = U32(capabilities);
  status.session_id = U32(session_id);
  status.max_frame_size = U16(maximum_frame_size);
  status.heartbeat_interval_ms = U16(heartbeat_interval_ms);
  return EncodeMessage(env, storage, ble::Message{status}, 0);
}

MOTO_JNI(jobjectArray, deviceEncodeCommand)(JNIEnv* env, jclass, jlong handle, jint kind,
                                            jint command_id, jint page, jlong event_time_ms) {
  auto* storage = Codec(handle);
  ble::DeviceCommand command;
  command.kind = static_cast<ble::DeviceCommandKind>(U8(kind));
  command.command_id = U16(command_id);
  command.page = static_cast<ble::DisplayPage>(U8(page));
  command.event_time_ms = static_cast<std::uint32_t>(event_time_ms);
  return EncodeMessage(env, storage, ble::Message{command}, ble::AckRequested | ble::Urgent);
}
