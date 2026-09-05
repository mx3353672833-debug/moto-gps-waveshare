#include "phone_nav_bridge.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <type_traits>
#include <utility>

#include "board_port.h"
#include "demo_fixture/jinan_big_data_demo.hpp"
#include "esp_log.h"
#include "esp_timer.h"
#include "moto_nav_ui.h"

namespace {
constexpr char kTag[] = "phone_nav";
constexpr double kDemoEarthRadiusM = 6'371'000.0;
constexpr double kPi = 3.14159265358979323846;
// The built-in ride begins at the real mapped driveway beside the D building.
// At 12 m/s the first geometry-derived turn arrives after about 6.6 seconds,
// so a physical-device check sees both the road context and a maneuver quickly.
constexpr double kDemoRouteStartM = 0.0;
constexpr double kDemoSpeedMps = 12.0;

struct DemoPose {
  moto::nav::Gcj02Point point{};
  float heading_deg = 0.0F;
};

double demo_geo_distance(const moto::nav::Gcj02Point& from,
                         const moto::nav::Gcj02Point& to) {
  const double latitude =
      (from.latitude_deg + to.latitude_deg) * 0.5 * kPi / 180.0;
  const double north =
      (to.latitude_deg - from.latitude_deg) * kPi / 180.0 *
      kDemoEarthRadiusM;
  const double east =
      (to.longitude_deg - from.longitude_deg) * kPi / 180.0 *
      std::cos(latitude) * kDemoEarthRadiusM;
  return std::hypot(east, north);
}

const std::array<double, moto::demo::jinan_big_data::kRoute.size()>&
demo_cumulative_distance() {
  static const auto cumulative = [] {
    std::array<double, moto::demo::jinan_big_data::kRoute.size()> values{};
    for(std::size_t index = 1; index < values.size(); ++index) {
      values[index] = values[index - 1] + demo_geo_distance(
          moto::demo::jinan_big_data::kRoute[index - 1],
          moto::demo::jinan_big_data::kRoute[index]);
    }
    return values;
  }();
  return cumulative;
}

moto::nav::Gcj02Point demo_route_point(double route_offset_m) {
  const auto& cumulative = demo_cumulative_distance();
  const double geometry_offset = std::clamp(
      route_offset_m, 0.0, cumulative.back());
  auto upper = std::upper_bound(cumulative.begin(), cumulative.end(),
                                geometry_offset);
  if(upper == cumulative.begin()) {
    return moto::demo::jinan_big_data::kRoute.front();
  }
  if(upper == cumulative.end()) {
    return moto::demo::jinan_big_data::kRoute.back();
  }
  const std::size_t end_index =
      static_cast<std::size_t>(upper - cumulative.begin());
  const std::size_t start_index = end_index - 1;
  const double segment = cumulative[end_index] - cumulative[start_index];
  const double fraction = segment > 0.0
                              ? (geometry_offset - cumulative[start_index]) /
                                    segment
                              : 0.0;
  const auto& start = moto::demo::jinan_big_data::kRoute[start_index];
  const auto& end = moto::demo::jinan_big_data::kRoute[end_index];
  return {
      start.latitude_deg + (end.latitude_deg - start.latitude_deg) * fraction,
      start.longitude_deg +
          (end.longitude_deg - start.longitude_deg) * fraction,
  };
}

double demo_route_distance() {
  return demo_cumulative_distance().back();
}

double demo_maneuver_offset(
    const moto::demo::jinan_big_data::ManeuverFixture& maneuver) {
  const auto& cumulative = demo_cumulative_distance();
  const std::size_t index = std::min<std::size_t>(
      maneuver.route_point_index, cumulative.size() - 1);
  return cumulative[index];
}

float demo_bearing(const moto::nav::Gcj02Point& from,
                   const moto::nav::Gcj02Point& to) {
  const double latitude =
      (from.latitude_deg + to.latitude_deg) * 0.5 * kPi / 180.0;
  const double north = to.latitude_deg - from.latitude_deg;
  const double east = (to.longitude_deg - from.longitude_deg) *
                      std::cos(latitude);
  double value = std::atan2(east, north) * 180.0 / kPi;
  if(value < 0.0) value += 360.0;
  return static_cast<float>(value);
}

DemoPose demo_pose(double route_offset_m) {
  constexpr double kTangentHalfWindowM = 11.0;
  const auto before = demo_route_point(route_offset_m - kTangentHalfWindowM);
  const auto after = demo_route_point(route_offset_m + kTangentHalfWindowM);
  return {
      demo_route_point(route_offset_m),
      demo_bearing(before, after),
  };
}

void add_demo_road_context(moto::nav::NavSnapshot& output) {
  static_assert(moto::demo::jinan_big_data::kRoadPoints.size() <=
                moto::nav::kRoadContextPointCapacity);
  static_assert(moto::demo::jinan_big_data::kRoads.size() <=
                moto::nav::kRoadContextPolylineCapacity);
  output.road_context_point_count = static_cast<std::uint8_t>(
      moto::demo::jinan_big_data::kRoadPoints.size());
  std::copy(moto::demo::jinan_big_data::kRoadPoints.begin(),
            moto::demo::jinan_big_data::kRoadPoints.end(),
            output.road_context_points.begin());
  output.road_context_polyline_count = static_cast<std::uint8_t>(
      moto::demo::jinan_big_data::kRoads.size());
  for(std::size_t index = 0;
      index < moto::demo::jinan_big_data::kRoads.size(); ++index) {
    const auto& road = moto::demo::jinan_big_data::kRoads[index];
    output.road_context_polylines[index] = {
        road.first_point_index,
        road.point_count,
        moto::nav::RoadContextClass::Residential,
    };
  }
  output.has_road_context = true;
  output.has_building_context = false;
  output.building_context_point_count = 0;
  output.building_context_footprint_count = 0;
  output.map_scene_revision = 0;
}

void clear_road_context(moto::nav::NavSnapshot& output) {
  output.has_road_context = false;
  output.road_context_point_count = 0;
  output.road_context_polyline_count = 0;
}

void clear_building_context(moto::nav::NavSnapshot& output) {
  output.has_building_context = false;
  output.building_context_point_count = 0;
  output.building_context_footprint_count = 0;
}

void clear_map_context(moto::nav::NavSnapshot& output) {
  clear_road_context(output);
  clear_building_context(output);
  output.map_scene_revision = 0;
}

moto::nav::RoadContextClass map_road_class(
    moto::ble::MapRoadClass road_class) {
  return static_cast<moto::nav::RoadContextClass>(road_class);
}

moto::nav::BuildingContextClass map_building_class(
    moto::ble::MapBuildingClass building_class) {
  return static_cast<moto::nav::BuildingContextClass>(building_class);
}

bool is_ios_demo_route_token(std::uint32_t route_token) {
  static const std::uint32_t kDemoRouteToken = moto::ble::route_token(
      moto::demo::jinan_big_data::kRouteId);
  static const std::uint32_t kReroutedDemoRouteToken =
      moto::ble::route_token(moto::demo::jinan_big_data::kReroutedRouteId);
  return route_token == kDemoRouteToken ||
         route_token == kReroutedDemoRouteToken;
}

moto::nav::NavState map_state(moto::ble::NavigationState state) {
  using In = moto::ble::NavigationState;
  using Out = moto::nav::NavState;
  switch (state) {
    case In::Idle: return Out::Idle;
    case In::Acquiring: return Out::Acquiring;
    case In::Planning: return Out::Planning;
    case In::Navigating: return Out::Navigating;
    case In::Rerouting: return Out::Rerouting;
    case In::Arrived: return Out::Arrived;
  }
  return Out::Idle;
}

moto::nav::NetworkState map_network(moto::ble::NetworkState state) {
  using In = moto::ble::NetworkState;
  using Out = moto::nav::NetworkState;
  switch (state) {
    case In::Offline: return Out::Offline;
    case In::Connecting: return Out::Connecting;
    case In::Online: return Out::Online;
  }
  return Out::Offline;
}

moto::nav::DisplayPage map_page(moto::ble::DisplayPage page) {
  using In = moto::ble::DisplayPage;
  using Out = moto::nav::DisplayPage;
  switch (page) {
    case In::Navigation: return Out::Navigation;
    case In::Speed: return Out::Speed;
    case In::Compass: return Out::Compass;
    case In::Music: return Out::Music;
  }
  return Out::Navigation;
}

moto::ble::DisplayPage map_page(moto_ui_page_t page) {
  switch (page) {
    case MOTO_UI_PAGE_SPEED: return moto::ble::DisplayPage::Speed;
    case MOTO_UI_PAGE_COMPASS: return moto::ble::DisplayPage::Compass;
    case MOTO_UI_PAGE_MUSIC: return moto::ble::DisplayPage::Music;
    case MOTO_UI_PAGE_NAVIGATION:
    case MOTO_UI_PAGE_COUNT:
    default: return moto::ble::DisplayPage::Navigation;
  }
}

moto::nav::DisplayPage map_nav_page(moto_ui_page_t page) {
  return map_page(map_page(page));
}

moto::nav::ManeuverType map_maneuver(moto::ble::Maneuver maneuver) {
  using In = moto::ble::Maneuver;
  using Out = moto::nav::ManeuverType;
  switch (maneuver) {
    case In::Continue: return Out::Continue;
    case In::SlightLeft: return Out::SlightLeft;
    case In::Left: return Out::Left;
    case In::SharpLeft: return Out::SharpLeft;
    case In::UTurnLeft: return Out::UTurnLeft;
    case In::SlightRight: return Out::SlightRight;
    case In::Right: return Out::Right;
    case In::SharpRight: return Out::SharpRight;
    case In::UTurnRight: return Out::UTurnRight;
    case In::Roundabout: return Out::Roundabout;
    case In::Exit: return Out::Exit;
    case In::Arrive: return Out::Arrive;
    case In::Unknown: return Out::Unknown;
  }
  return Out::Unknown;
}

moto::nav::TrafficLevel map_traffic(moto::ble::TrafficLevel level) {
  using In = moto::ble::TrafficLevel;
  using Out = moto::nav::TrafficLevel;
  switch (level) {
    case In::FreeFlow: return Out::FreeFlow;
    case In::Slow: return Out::Slow;
    case In::Congested: return Out::Congested;
    case In::Severe: return Out::Severe;
    case In::Unknown: return Out::Unknown;
  }
  return Out::Unknown;
}

std::uint64_t monotonic_ms() {
  return static_cast<std::uint64_t>(esp_timer_get_time()) / 1'000U;
}

bool has_flag(std::uint16_t flags, moto::ble::NavigationFlag flag) {
  return (flags & static_cast<std::uint16_t>(flag)) != 0U;
}
}  // namespace

PhoneNavBridge::PhoneNavBridge(moto::nav::NavPresenter& presenter)
    : presenter_(presenter) {}

void PhoneNavBridge::set_sender(SendCallback callback,
                                void* context) noexcept {
  const std::lock_guard<std::mutex> lock(state_mutex_);
  sender_ = callback;
  sender_context_ = context;
}

void PhoneNavBridge::install_ui_callbacks() {
  moto_nav_ui_set_page_change_callback(page_changed, this);
  moto_nav_ui_set_music_command_callback(music_command, this);
  moto_nav_ui_set_demo_change_callback(demo_changed, this);
}

bool PhoneNavBridge::start_renderer() {
#ifdef ESP_PLATFORM
  if (render_task_handle_.load(std::memory_order_acquire) != nullptr) {
    return true;
  }
  TaskHandle_t created = nullptr;
  if (xTaskCreate(render_task, "moto_ui_render", 8'192, this, 2,
                  &created) != pdPASS) {
    ESP_LOGE(kTag, "could not start coalescing UI renderer");
    return false;
  }
  render_task_handle_.store(created, std::memory_order_release);
  // Also covers a render request submitted between task creation and handle
  // publication.
  xTaskNotifyGive(created);
#endif
  return true;
}

#ifndef ESP_PLATFORM
void PhoneNavBridge::render_pending_for_test() {
  render_pending();
}
#endif

void PhoneNavBridge::on_link_state(bool active) {
  {
    const std::lock_guard<std::mutex> lock(state_mutex_);
    if (link_active_ == active) {
      return;
    }
    link_active_ = active;
    ui_phone_connection_ = active ? MOTO_UI_PHONE_CONNECTING
                                  : MOTO_UI_PHONE_OFFLINE;
    auto& phone_snapshot = phone_snapshot_locked();
    if (active) {
      phone_snapshot.network = moto::nav::NetworkState::Connecting;
    } else {
      phone_session_id_ = 0;
      phone_snapshot.network = moto::nav::NetworkState::Offline;
      phone_snapshot.gnss_stale = true;
      phone_snapshot.has_usable_fix = false;
      phone_snapshot.route_request_in_flight = false;
      phone_snapshot.traffic_request_in_flight = false;
      phone_snapshot.speed_mps = 0.0F;
      geometry_ = {};
      phone_snapshot.has_route_view = false;
      clear_map_context(phone_snapshot);
      heading_fusion_.reset();
      last_motion_present_ms_ = 0;
      music_page_enabled_ = false;
      media_state_ = {};
      media_state_.source_name = "iPhone";
      media_state_.track_title = "等待连接";
    }
  }
  const std::uint32_t flags = static_cast<std::uint32_t>(RenderNavigation) |
      (active ? 0U : static_cast<std::uint32_t>(RenderMedia));
  request_render(flags);
}

void PhoneNavBridge::on_imu_sample(float heading_rate_dps,
                                   std::uint64_t sample_ms) {
  bool should_present = false;
  {
    const std::lock_guard<std::mutex> lock(state_mutex_);
    if (demo_active_ ||
        !heading_fusion_.integrate(heading_rate_dps, sample_ms)) {
      return;
    }
    snapshot_.heading_deg = heading_fusion_.heading_deg();
    // The QMI8658 runs at 125 Hz. Reproject at the display's unified 40 Hz
    // cadence; requests still coalesce if one physical refresh runs long.
    if (sample_ms - last_motion_present_ms_ >= 25 &&
        (snapshot_.has_route_view ||
         snapshot_.display_page == moto::nav::DisplayPage::Compass)) {
      last_motion_present_ms_ = sample_ms;
      should_present = true;
    }
  }
  if (should_present) present_motion();
}

void PhoneNavBridge::fill_demo_snapshot(moto::nav::NavSnapshot& output,
                                        std::uint64_t elapsed_ms) {
  constexpr std::uint64_t kArrivalHoldMs = 2'200;
  const double route_distance_m = demo_route_distance();
  const std::uint64_t moving_ms = static_cast<std::uint64_t>(
      (route_distance_m - kDemoRouteStartM) /
      kDemoSpeedMps * 1'000.0);
  const std::uint64_t cycle_ms = moving_ms + kArrivalHoldMs;
  const std::uint64_t phase_ms = elapsed_ms % cycle_ms;
  const bool arrived = phase_ms >= moving_ms;
  const double progress = arrived
                              ? route_distance_m
                              : std::min(
                                    route_distance_m,
                                    kDemoRouteStartM +
                                        phase_ms / 1'000.0 * kDemoSpeedMps);
  const DemoPose rider = demo_pose(progress);

  output = {};
  output.state = arrived ? moto::nav::NavState::Arrived
                         : moto::nav::NavState::Navigating;
  output.network = moto::nav::NetworkState::Online;
  output.display_page = moto::nav::DisplayPage::Navigation;
  output.has_destination = true;
  output.has_usable_fix = true;
  output.gnss_stale = false;
  output.speed_mps = arrived ? 0.0F : static_cast<float>(
      kDemoSpeedMps + std::sin(phase_ms / 850.0) * 1.6);
  output.heading_deg = rider.heading_deg;
  output.horizontal_accuracy_m = 3.2F;
  // A clearly visible demo-only value exercises the sign treatment. OSM road
  // geometry does not include a verified speed-limit value for this fixture.
  output.speed_limit_kph = 50;
  output.route_id = moto::demo::jinan_big_data::kRouteId;
  output.route_progress_m = progress;
  output.total_distance_m = route_distance_m;
  output.remaining_distance_m = std::max(
      0.0, route_distance_m - progress);
  output.remaining_duration_s = static_cast<std::uint32_t>(
      std::ceil(output.remaining_distance_m / kDemoSpeedMps));
  output.traffic_ahead = phase_ms % 11'000U > 8'500U
                             ? moto::nav::TrafficLevel::Slow
                             : moto::nav::TrafficLevel::FreeFlow;
  output.route_generation = 1;
  output.now_ms = elapsed_ms;
  output.last_fix_ms = elapsed_ms;
  output.last_traffic_update_ms = elapsed_ms;

  output.has_next_maneuver = !arrived;
  if (!arrived) {
    // Select the first not-yet-passed instruction from the verified OSM
    // route. A maneuver at the route origin only describes the initial road;
    // skip it so the round display opens on the first actionable turn rather
    // than a misleading straight-ahead instruction at 0 m.
    const moto::demo::jinan_big_data::ManeuverFixture* next =
        &moto::demo::jinan_big_data::kManeuvers.back();
    for(std::size_t index = 1;
        index < moto::demo::jinan_big_data::kManeuvers.size(); ++index) {
      const auto& maneuver = moto::demo::jinan_big_data::kManeuvers[index];
      if(demo_maneuver_offset(maneuver) > progress + 0.5) {
        next = &maneuver;
        break;
      }
    }
    output.next_maneuver.id = next->id;
    output.next_maneuver.type = next->type;
    output.next_maneuver.route_offset_m = demo_maneuver_offset(*next);
    output.next_maneuver.road_name = next->road_name;
    output.next_maneuver.instruction = next->instruction;
    output.distance_to_next_maneuver_m = std::max(
        0.0, output.next_maneuver.route_offset_m - progress);
  }

  output.route_view_origin = rider.point;
  output.route_view_point_count =
      static_cast<std::uint8_t>(moto::nav::kRouteViewPointCapacity);
  for (std::size_t i = 0; i < moto::nav::kRouteViewPointCapacity; ++i) {
    // A constant-size 600 m real route window keeps the white selected route
    // stable while the fixed rider advances and the entire map rotates.
    const double sample_distance = std::clamp(
        progress - 52.0 + static_cast<double>(i) * 27.0,
        0.0, route_distance_m);
    output.route_view_points[i] = demo_route_point(sample_distance);
  }
  output.has_route_view = true;
  add_demo_road_context(output);
}

void PhoneNavBridge::update_demo(std::uint64_t now_ms) {
  {
    const std::lock_guard<std::mutex> lock(state_mutex_);
    if (!demo_active_) return;
    const std::uint64_t elapsed_ms = now_ms >= demo_started_ms_
                                         ? now_ms - demo_started_ms_
                                         : 0;
    fill_demo_snapshot(snapshot_, elapsed_ms);
  }
  present_navigation();
}

void PhoneNavBridge::set_demo_active(bool active) {
  {
    const std::lock_guard<std::mutex> lock(state_mutex_);
    if (active == demo_active_) return;
    demo_active_ = active;
    if (active) {
      snapshot_before_demo_ = snapshot_;
      demo_started_ms_ = monotonic_ms();
      fill_demo_snapshot(snapshot_, 0);
    } else {
      snapshot_ = snapshot_before_demo_;
      demo_started_ms_ = 0;
    }
  }
  present_navigation();
}

moto::ble::AckStatus PhoneNavBridge::on_message(
    const moto::ble::ReassembledMessage& message) {
  const auto decoded = moto::ble::decode_message(
      message.type, moto::ble::ByteView(message.payload));
  if (!decoded.ok()) {
    ESP_LOGW(kTag, "drop %u payload: %s at %u",
             static_cast<unsigned>(message.type),
             moto::ble::to_string(decoded.error),
             static_cast<unsigned>(decoded.offset));
    return moto::ble::AckStatus::Failed;
  }

  return std::visit(
      [this](const auto& value) -> moto::ble::AckStatus {
        using T = std::decay_t<decltype(value)>;
        if constexpr (std::is_same_v<T, moto::ble::ConnectionStatus>) {
          if (value.role != moto::ble::EndpointRole::Phone ||
              value.minimum_version > moto::ble::kProtocolVersion ||
              value.maximum_version < moto::ble::kProtocolVersion) {
            return moto::ble::AckStatus::Unsupported;
          }
          const std::uint32_t required_media =
              moto::ble::CapabilityMediaState |
              moto::ble::CapabilityMusicCommands;
          const bool media = value.state == moto::ble::ConnectionState::Ready &&
              (value.capabilities & required_media) == required_media;
          {
            const std::lock_guard<std::mutex> lock(state_mutex_);
            phone_session_id_ = value.session_id;
            music_page_enabled_ = media;
            switch (value.state) {
              case moto::ble::ConnectionState::Ready:
                ui_phone_connection_ = MOTO_UI_PHONE_ONLINE;
                break;
              case moto::ble::ConnectionState::Closing:
                ui_phone_connection_ = MOTO_UI_PHONE_OFFLINE;
                break;
              case moto::ble::ConnectionState::Starting:
              case moto::ble::ConnectionState::Degraded:
                ui_phone_connection_ = MOTO_UI_PHONE_CONNECTING;
              break;
            }
          }
          present_navigation();
        } else if constexpr (std::is_same_v<T, moto::ble::Heartbeat>) {
          std::uint32_t phone_session_id = 0;
          {
            const std::lock_guard<std::mutex> lock(state_mutex_);
            phone_session_id = phone_session_id_;
          }
          if (phone_session_id != 0 && value.session_id != phone_session_id) {
            return moto::ble::AckStatus::InvalidState;
          }
        } else if constexpr (
            std::is_same_v<T, moto::ble::NavigationSnapshot>) {
          consume_navigation(value);
        } else if constexpr (std::is_same_v<T, moto::ble::RouteGeometry>) {
          return consume_geometry(value);
        } else if constexpr (
            std::is_same_v<T, moto::ble::TrafficDeviation>) {
          consume_traffic(value);
        } else if constexpr (std::is_same_v<T, moto::ble::MediaState>) {
          consume_media(value);
        } else if constexpr (std::is_same_v<T, moto::ble::MapScene>) {
          return consume_map_scene(value);
        } else if constexpr (std::is_same_v<T, moto::ble::Ack>) {
          ESP_LOGD(kTag, "phone ack sequence=%u command=%u status=%u",
                   value.acknowledged_sequence, value.command_id,
                   static_cast<unsigned>(value.status));
        } else if constexpr (std::is_same_v<T, moto::ble::DeviceCommand>) {
          return moto::ble::AckStatus::Unsupported;
        }
        return moto::ble::AckStatus::Ok;
      },
      decoded.value);
}

void PhoneNavBridge::consume_navigation(
    const moto::ble::NavigationSnapshot& input) {
  {
    const std::lock_guard<std::mutex> lock(state_mutex_);
    // A valid phone snapshot is always authoritative. Older firmware allowed
    // an accidental long press to leave the visible display in its built-in
    // demo forever while real phone geometry was written to a hidden backup.
    if (demo_active_) {
      snapshot_ = snapshot_before_demo_;
      demo_active_ = false;
      demo_started_ms_ = 0;
    }
    auto& phone_snapshot = phone_snapshot_locked();
    // Receiving a valid navigation snapshot proves that the two-phase
    // protocol session is usable even if a reordered Ready frame was not
    // observed by this bridge callback.
    ui_phone_connection_ = MOTO_UI_PHONE_ONLINE;
    // A phone course is absolute while moving; use it as the low-frequency
    // anchor and preserve the local gyroscope's much faster response between
    // Core Location updates. At walking/standstill speeds Core Location course
    // is commonly stale, so MotionHeadingFusion deliberately does not pull the
    // display back toward it.
    const float phone_speed_mps =
        static_cast<float>(input.speed_deci_kph) / 36.0F;
    const float phone_heading_deg =
        static_cast<float>(input.heading_cdeg) / 100.0F;
    const bool has_usable_fix = has_flag(
        input.flags, moto::ble::NavigationHasFix);
    heading_fusion_.anchor(phone_heading_deg, phone_speed_mps,
                           has_usable_fix);

    phone_snapshot.state = map_state(input.state);
    phone_snapshot.network = map_network(input.network);
    phone_snapshot.display_page = map_page(input.display_page);
    phone_snapshot.has_destination = has_flag(
        input.flags, moto::ble::NavigationHasDestination);
    phone_snapshot.has_usable_fix = has_usable_fix;
    phone_snapshot.gnss_stale = has_flag(
        input.flags, moto::ble::NavigationGnssStale);
    phone_snapshot.off_route = has_flag(
        input.flags, moto::ble::NavigationOffRoute);
    phone_snapshot.route_request_in_flight = has_flag(
        input.flags, moto::ble::NavigationRouteRequestInFlight);
    phone_snapshot.traffic_request_in_flight = has_flag(
        input.flags, moto::ble::NavigationTrafficRequestInFlight);
    phone_snapshot.has_next_maneuver = has_flag(
        input.flags, moto::ble::NavigationHasNextManeuver);
    phone_snapshot.speed_mps = phone_speed_mps;
    phone_snapshot.heading_deg = heading_fusion_.initialized()
                                     ? heading_fusion_.heading_deg()
                                     : phone_heading_deg;
    phone_snapshot.horizontal_accuracy_m =
        static_cast<float>(input.accuracy_dm) / 10.0F;
    phone_snapshot.cross_track_distance_m =
        static_cast<float>(input.cross_track_dm) / 10.0F;
    phone_snapshot.speed_limit_kph = input.speed_limit_kph;
    phone_snapshot.route_progress_m = input.route_progress_m;
    phone_snapshot.total_distance_m = input.total_distance_m;
    phone_snapshot.remaining_distance_m = input.remaining_distance_m;
    phone_snapshot.remaining_duration_s = input.remaining_duration_s;
    phone_snapshot.traffic_ahead = map_traffic(input.traffic);
    phone_snapshot.route_generation = input.route_generation;
    phone_snapshot.now_ms = monotonic_ms();
    phone_snapshot.last_fix_ms =
        phone_snapshot.has_usable_fix ? phone_snapshot.now_ms : 0;

    char route_id[20]{};
    std::snprintf(route_id, sizeof(route_id), "ble-%08lx",
                  static_cast<unsigned long>(input.route_token));
    phone_snapshot.route_id = route_id;

    // The built-in demo predates MapScene and still uses its matching,
    // attributed OSM fixture. Once a live MapScene revision has arrived,
    // ordinary navigation snapshots retain that complete phone-supplied
    // window until it is atomically replaced or the session disconnects.
    if (is_ios_demo_route_token(input.route_token) &&
        phone_snapshot.map_scene_revision == 0) {
      add_demo_road_context(phone_snapshot);
    } else if (phone_snapshot.map_scene_revision == 0) {
      clear_map_context(phone_snapshot);
    }

    phone_snapshot.next_maneuver = {};
    if (phone_snapshot.has_next_maneuver) {
      phone_snapshot.next_maneuver.id = input.maneuver_id;
      phone_snapshot.next_maneuver.type = map_maneuver(input.maneuver);
      phone_snapshot.next_maneuver.route_offset_m =
          static_cast<double>(input.route_progress_m) +
          input.distance_to_maneuver_m;
      phone_snapshot.next_maneuver.road_name = input.road_name;
      phone_snapshot.next_maneuver.instruction = input.instruction;
      phone_snapshot.next_maneuver.roundabout_exit = input.roundabout_exit;
      phone_snapshot.distance_to_next_maneuver_m =
          input.distance_to_maneuver_m;
    } else {
      phone_snapshot.distance_to_next_maneuver_m = 0.0;
    }

    const bool expects_geometry = has_flag(
        input.flags, moto::ble::NavigationHasRouteView);
    if (!expects_geometry || !geometry_.complete ||
        geometry_.route_token != input.route_token ||
        geometry_.route_generation != input.route_generation) {
      phone_snapshot.has_route_view = false;
    } else {
      apply_geometry_locked(phone_snapshot);
    }
  }
  present_navigation();
}

moto::ble::AckStatus PhoneNavBridge::consume_geometry(
    const moto::ble::RouteGeometry& input) {
  if (input.total_point_count > moto::nav::kRouteViewPointCapacity) {
    return moto::ble::AckStatus::Unsupported;
  }

  {
    const std::lock_guard<std::mutex> lock(state_mutex_);
    if (input.chunk_index == 0) {
      geometry_ = {};
      geometry_.active = true;
      geometry_.route_token = input.route_token;
      geometry_.route_generation = input.route_generation;
      geometry_.chunk_count = input.chunk_count;
      geometry_.total_point_count = input.total_point_count;
      geometry_.origin_e6 = input.view_origin;
      geometry_.origin = {
          static_cast<double>(input.view_origin.latitude_e6) / 1'000'000.0,
          static_cast<double>(input.view_origin.longitude_e6) / 1'000'000.0,
      };
    }

    if (!geometry_.active || input.route_token != geometry_.route_token ||
        input.route_generation != geometry_.route_generation ||
        input.chunk_count != geometry_.chunk_count ||
        input.total_point_count != geometry_.total_point_count ||
        input.chunk_index != geometry_.next_chunk ||
        input.first_point_index != geometry_.point_count ||
        !(input.view_origin == geometry_.origin_e6)) {
      geometry_ = {};
      return moto::ble::AckStatus::InvalidState;
    }

    for (const auto& point : input.points) {
      if (geometry_.point_count >= moto::nav::kRouteViewPointCapacity) {
        geometry_ = {};
        return moto::ble::AckStatus::Unsupported;
      }
      geometry_.points[geometry_.point_count++] = {
          static_cast<double>(point.latitude_e6) / 1'000'000.0,
          static_cast<double>(point.longitude_e6) / 1'000'000.0,
      };
    }
    ++geometry_.next_chunk;

    if (geometry_.next_chunk == geometry_.chunk_count) {
      if (geometry_.point_count != geometry_.total_point_count) {
        geometry_ = {};
        return moto::ble::AckStatus::InvalidState;
      }
      geometry_.complete = true;
      geometry_.active = false;
      apply_geometry_locked(phone_snapshot_locked());
    }
  }
  // RouteGeometry is queued immediately before its matching
  // NavigationSnapshot by the iPhone.  Retain the completed geometry here and
  // let that snapshot perform the single full LVGL update for the pair.  A
  // second blocking redraw in the BLE worker halved receive throughput and
  // could eventually fill the bounded RX queue during continuous navigation.
  // The next (5 Hz) snapshot is therefore the presentation boundary.
  return moto::ble::AckStatus::Ok;
}

void PhoneNavBridge::consume_traffic(
    const moto::ble::TrafficDeviation& input) {
  {
    const std::lock_guard<std::mutex> lock(state_mutex_);
    auto& phone_snapshot = phone_snapshot_locked();
    char expected[20]{};
    std::snprintf(expected, sizeof(expected), "ble-%08lx",
                  static_cast<unsigned long>(input.route_token));
    if (phone_snapshot.route_id != expected ||
        phone_snapshot.route_generation != input.route_generation) {
      return;
    }
    phone_snapshot.last_traffic_update_ms = input.observed_at_ms;
    phone_snapshot.remaining_duration_s = input.remaining_duration_s;
    phone_snapshot.cross_track_distance_m =
        static_cast<float>(input.cross_track_dm) / 10.0F;
    phone_snapshot.off_route = (input.flags & moto::ble::OffRoute) != 0U;
    if ((input.flags & moto::ble::Rerouting) != 0U) {
      phone_snapshot.state = moto::nav::NavState::Rerouting;
    }
    if ((input.flags & moto::ble::RouteInvalidated) != 0U) {
      geometry_ = {};
      phone_snapshot.has_route_view = false;
    }
  }
  present_navigation();
}

void PhoneNavBridge::consume_media(const moto::ble::MediaState& input) {
  {
    const std::lock_guard<std::mutex> lock(state_mutex_);
    media_state_ = input;
  }
  request_render(RenderMedia);
}

moto::ble::AckStatus PhoneNavBridge::consume_map_scene(
    const moto::ble::MapScene& input) {
  std::size_t road_point_count = 0;
  for (const auto& road : input.roads) {
    road_point_count += road.points.size();
  }
  std::size_t building_point_count = 0;
  for (const auto& building : input.buildings) {
    building_point_count += building.points.size();
  }
  if (input.roads.size() > moto::nav::kRoadContextPolylineCapacity ||
      road_point_count > moto::nav::kRoadContextPointCapacity ||
      input.buildings.size() >
          moto::nav::kBuildingContextFootprintCapacity ||
      building_point_count > moto::nav::kBuildingContextPointCapacity) {
    return moto::ble::AckStatus::Unsupported;
  }

  {
    const std::lock_guard<std::mutex> lock(state_mutex_);
    auto& target = phone_snapshot_locked();

    // A MapScene is a complete window replacement. Retransmitted or delayed
    // older windows must not roll the display back after the rider has crossed
    // into a newer offline-map tile.
    if (target.map_scene_revision != 0 &&
        input.scene_revision <= target.map_scene_revision) {
      return moto::ble::AckStatus::Duplicate;
    }

    // The decoder has already validated every class, point count and GCJ-02
    // coordinate. Populate hidden storage first and publish counts/flags last,
    // so observers under state_mutex_ see either the old complete scene or the
    // new complete scene, never a mixture of revisions.
    std::size_t next_road_point = 0;
    std::size_t next_road = 0;
    for (const auto& road : input.roads) {
      const std::size_t first = next_road_point;
      for (const auto& point : road.points) {
        target.road_context_points[next_road_point++] = {
            static_cast<double>(point.latitude_e6) / 1'000'000.0,
            static_cast<double>(point.longitude_e6) / 1'000'000.0,
        };
      }
      target.road_context_polylines[next_road++] = {
          static_cast<std::uint8_t>(first),
          static_cast<std::uint8_t>(road.points.size()),
          map_road_class(road.road_class),
      };
    }

    std::size_t next_building_point = 0;
    std::size_t next_building = 0;
    for (const auto& building : input.buildings) {
      const std::size_t first = next_building_point;
      for (const auto& point : building.points) {
        target.building_context_points[next_building_point++] = {
            static_cast<double>(point.latitude_e6) / 1'000'000.0,
            static_cast<double>(point.longitude_e6) / 1'000'000.0,
        };
      }
      target.building_context_footprints[next_building++] = {
          static_cast<std::uint8_t>(first),
          static_cast<std::uint8_t>(building.points.size()),
          map_building_class(building.building_class),
      };
    }

    target.road_context_point_count =
        static_cast<std::uint8_t>(next_road_point);
    target.road_context_polyline_count =
        static_cast<std::uint8_t>(next_road);
    target.has_road_context = next_road > 0;
    target.building_context_point_count =
        static_cast<std::uint8_t>(next_building_point);
    target.building_context_footprint_count =
        static_cast<std::uint8_t>(next_building);
    target.has_building_context = next_building > 0;
    target.map_scene_revision = input.scene_revision;
  }
  ESP_LOGI(kTag,
           "map scene committed: revision=%lu roads=%u/%u buildings=%u/%u",
           static_cast<unsigned long>(input.scene_revision),
           static_cast<unsigned>(input.roads.size()),
           static_cast<unsigned>(road_point_count),
           static_cast<unsigned>(input.buildings.size()),
           static_cast<unsigned>(building_point_count));
  present_navigation();
  return moto::ble::AckStatus::Ok;
}

void PhoneNavBridge::apply_geometry_locked(moto::nav::NavSnapshot& target) {
  if (!geometry_.complete || target.route_id.empty()) {
    target.has_route_view = false;
    return;
  }
  char expected[20]{};
  std::snprintf(expected, sizeof(expected), "ble-%08lx",
                static_cast<unsigned long>(geometry_.route_token));
  if (target.route_id != expected ||
      target.route_generation != geometry_.route_generation) {
    target.has_route_view = false;
    return;
  }
  target.route_view_origin = geometry_.origin;
  target.route_view_point_count =
      static_cast<std::uint8_t>(geometry_.point_count);
  std::copy_n(geometry_.points.begin(), geometry_.point_count,
              target.route_view_points.begin());
  target.has_route_view = geometry_.point_count >= 2;
}

moto::nav::NavSnapshot& PhoneNavBridge::phone_snapshot_locked() noexcept {
  return demo_active_ ? snapshot_before_demo_ : snapshot_;
}

void PhoneNavBridge::present_navigation() {
  request_render(RenderNavigation);
}

void PhoneNavBridge::present_motion() {
  request_render(RenderMotion);
}

void PhoneNavBridge::request_render(std::uint32_t flags) noexcept {
  pending_render_flags_.fetch_or(flags, std::memory_order_release);
#ifdef ESP_PLATFORM
  const TaskHandle_t task =
      render_task_handle_.load(std::memory_order_acquire);
  if (task != nullptr) {
    xTaskNotifyGive(task);
  }
#endif
}

void PhoneNavBridge::render_pending() {
  const std::uint32_t requested =
      pending_render_flags_.exchange(0, std::memory_order_acq_rel);
  if (requested == 0U) {
    return;
  }

  const bool navigation = (requested & RenderNavigation) != 0U;
  const bool motion = !navigation && (requested & RenderMotion) != 0U;
  const bool media = (requested & RenderMedia) != 0U;
  {
    const std::lock_guard<std::mutex> lock(state_mutex_);
    if (navigation || motion) {
      render_snapshot_ = snapshot_;
    }
    if (navigation) {
      render_phone_connection_ = ui_phone_connection_;
      render_demo_active_ = demo_active_;
      render_music_page_enabled_ = music_page_enabled_;
    }
    if (media) {
      render_media_state_ = media_state_;
    }
  }

  // Projection is the expensive part of a motion frame (hundreds of map
  // points and trigonometry), but it does not call LVGL. Keep it outside the
  // display mutex so the LVGL worker can finish the previous flush in parallel.
  if (navigation || motion) {
    presenter_.update(render_snapshot_);
  }

  // The BLE worker has already returned before this point in firmware. The
  // dedicated low-priority renderer is allowed to wait for an in-flight panel
  // flush without applying backpressure to CoreBluetooth writes.
  if (!board_port_lock(UINT32_MAX)) {
#ifdef ESP_PLATFORM
    // An abnormal BSP failure must not turn into a tight self-notification
    // loop. Retain the request and retry shortly from this task only.
    vTaskDelay(pdMS_TO_TICKS(10));
#endif
    request_render(requested);
    return;
  }
  if (navigation) {
    moto_nav_ui_set_phone_connection(render_phone_connection_);
    moto_nav_ui_set_demo_active(render_demo_active_ ? 1U : 0U);
    moto_nav_ui_set_music_page_enabled(
        render_music_page_enabled_ ? 1U : 0U);
    presenter_.apply_to_lvgl();
  } else if (motion) {
    moto_nav_ui_set_motion_state(&presenter_.ui_state());
  }
  if (media) {
    moto_music_state_t state{};
    state.source_name = render_media_state_.source_name.c_str();
    state.track_title = render_media_state_.track_title.c_str();
    state.artist_name = render_media_state_.artist_name.c_str();
    state.connected =
        (render_media_state_.flags & moto::ble::MediaConnected) != 0U;
    state.playing =
        (render_media_state_.flags & moto::ble::MediaPlaying) != 0U;
    state.like_available =
        (render_media_state_.flags & moto::ble::MediaLikeAvailable) != 0U;
    state.liked =
        (render_media_state_.flags & moto::ble::MediaLiked) != 0U;
    moto_nav_ui_set_music_state(&state);
  }
  board_port_unlock();
}

#ifdef ESP_PLATFORM
void PhoneNavBridge::render_task(void* context) {
  static_cast<PhoneNavBridge*>(context)->run_renderer();
}

void PhoneNavBridge::run_renderer() {
  while (true) {
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    render_pending();
  }
}
#endif

void PhoneNavBridge::page_changed(moto_ui_page_t page, void* context) {
  auto* self = static_cast<PhoneNavBridge*>(context);
  {
    const std::lock_guard<std::mutex> lock(self->state_mutex_);
    self->snapshot_.display_page = map_nav_page(page);
  }
  self->present_navigation();
  self->send_page_command(page);
}

void PhoneNavBridge::music_command(moto_music_command_t command,
                                   void* context) {
  static_cast<PhoneNavBridge*>(context)->send_music_command(command);
}

void PhoneNavBridge::demo_changed(uint8_t enabled, void* context) {
  static_cast<PhoneNavBridge*>(context)->set_demo_active(enabled != 0U);
}

void PhoneNavBridge::send_page_command(moto_ui_page_t page) {
  moto::ble::DeviceCommand command;
  command.kind = moto::ble::DeviceCommandKind::PageSelected;
  command.page = map_page(page);
  send_device_command(command);
}

void PhoneNavBridge::send_music_command(moto_music_command_t command) {
  moto::ble::DeviceCommand output;
  switch (command) {
    case MOTO_MUSIC_PREVIOUS:
      output.kind = moto::ble::DeviceCommandKind::MusicPrevious;
      break;
    case MOTO_MUSIC_TOGGLE_PLAYBACK:
      output.kind = moto::ble::DeviceCommandKind::MusicTogglePlayback;
      break;
    case MOTO_MUSIC_NEXT:
      output.kind = moto::ble::DeviceCommandKind::MusicNext;
      break;
    case MOTO_MUSIC_LIKE:
      output.kind = moto::ble::DeviceCommandKind::MusicLike;
      break;
  }
  output.page = moto::ble::DisplayPage::Music;
  send_device_command(output);
}

void PhoneNavBridge::send_device_command(moto::ble::DeviceCommand command) {
  SendCallback sender = nullptr;
  void* sender_context = nullptr;
  {
    const std::lock_guard<std::mutex> lock(state_mutex_);
    if (sender_ == nullptr || !link_active_) {
      return;
    }
    sender = sender_;
    sender_context = sender_context_;
    command.command_id = next_command_id_;
    ++next_command_id_;
    if (next_command_id_ == 0) {
      next_command_id_ = 1;
    }
  }
  command.event_time_ms = static_cast<std::uint32_t>(monotonic_ms());
  const moto::ble::Message message = command;
  // Sending can block briefly and can cause Core/NimBLE callbacks. Never keep
  // state_mutex_ held across this boundary.
  sender(message, moto::ble::AckRequested | moto::ble::Urgent,
         sender_context);
}
