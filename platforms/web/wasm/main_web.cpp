#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <utility>

#define SDL_MAIN_HANDLED
#include <SDL2/SDL.h>
#include <emscripten.h>

#include "lvgl.h"
#include "demo_fixture/jinan_big_data_demo.hpp"
#include "moto_nav_presenter.hpp"
#include "moto_nav_ui.h"
#include "nav_app/nav_app.hpp"

namespace {

using namespace moto::nav;

constexpr Wgs84Point kStart =
    moto::demo::jinan_big_data::kRouteWgs84.front();
constexpr Wgs84Point kDestination =
    moto::demo::jinan_big_data::kRouteWgs84.back();
constexpr double kPi = 3.14159265358979323846;
constexpr double kFixtureEarthRadiusM = 6'371'000.0;
constexpr const auto& kFixtureGeometry =
    moto::demo::jinan_big_data::kRouteWgs84;

NavApp app;
NavPresenter presenter;
TimestampMs now_ms = 1'000;
TrafficLevel desired_traffic = TrafficLevel::FreeFlow;
bool desired_online = true;
std::uint32_t pending_route_request_id = 0;
int current_progress = 0;
int heading_offset_deg = 0;
bool live_mode = false;
RouteBundle live_route_builder;
std::uint32_t live_route_request_id = 0;
TrafficSnapshot live_traffic_builder;
std::uint32_t live_traffic_request_id = 0;

double fixture_route_distance();
double fixture_maneuver_offset(
    const moto::demo::jinan_big_data::ManeuverFixture& maneuver);
bool lifecycle_preview_active = false;
NavSnapshot lifecycle_preview_snapshot;

EM_JS(void, emit_live_route_request,
      (std::uint32_t request_id, double origin_latitude,
       double origin_longitude, double destination_latitude,
       double destination_longitude, int is_reroute), {
  const bridge = globalThis.MotoNavLiveBridge;
  if (bridge && typeof bridge.onRouteRequest === "function") {
    bridge.onRouteRequest({
      requestId: request_id,
      origin: { latitude: origin_latitude, longitude: origin_longitude },
      destination: { latitude: destination_latitude, longitude: destination_longitude },
      isReroute: Boolean(is_reroute),
    });
  }
});

EM_JS(void, emit_live_traffic_request,
      (std::uint32_t request_id, const char* route_id), {
  const bridge = globalThis.MotoNavLiveBridge;
  if (bridge && typeof bridge.onTrafficRequest === "function") {
    bridge.onTrafficRequest({
      requestId: request_id,
      routeId: UTF8ToString(route_id),
    });
  }
});

void frame(void*) { lv_timer_handler(); }

void present_snapshot(const NavSnapshot& snapshot, void* context) {
  auto* const target = static_cast<NavPresenter*>(context);
  NavSnapshot presented = lifecycle_preview_active
                              ? lifecycle_preview_snapshot
                              : snapshot;
  // The browser demo renders the same traceable OSM fixture in a web-only
  // canvas layer, including road classes and building footprints. Suppress
  // this legacy, unclassified copy so streets are not double-brightened.
  if (presented.route_id == moto::demo::jinan_big_data::kRouteId) {
    presented.has_road_context = false;
    presented.road_context_point_count = 0;
    presented.road_context_polyline_count = 0;
  }
  target->update(presented);
  target->apply_to_lvgl();
}

DisplayPage display_page_from_ui(moto_ui_page_t page) {
  switch(page) {
    case MOTO_UI_PAGE_SPEED: return DisplayPage::Speed;
    case MOTO_UI_PAGE_COMPASS: return DisplayPage::Compass;
    case MOTO_UI_PAGE_MUSIC: return DisplayPage::Music;
    case MOTO_UI_PAGE_NAVIGATION:
    case MOTO_UI_PAGE_COUNT:
      return DisplayPage::Navigation;
  }
  return DisplayPage::Navigation;
}

void page_change_requested(moto_ui_page_t page, void*) {
  app.handle(DisplayPageSelected{display_page_from_ui(page)});
}

GnssFix fixture_fix(Wgs84Point point, float speed_mps, float heading_deg) {
  now_ms += 500;
  return {point, 4.0F, speed_mps, heading_deg, now_ms};
}

RouteBundle fixture_route() {
  RouteBundle route;
  route.route_id = moto::demo::jinan_big_data::kRouteId;
  route.polyline.assign(moto::demo::jinan_big_data::kRoute.begin(),
                        moto::demo::jinan_big_data::kRoute.end());
  for(const auto& maneuver : moto::demo::jinan_big_data::kManeuvers) {
    route.maneuvers.push_back({
        maneuver.id,
        maneuver.type,
        fixture_maneuver_offset(maneuver),
        maneuver.road_name,
        maneuver.instruction,
        0,
    });
  }
  route.traffic = {{0.0, fixture_route_distance(), desired_traffic}};
  route.total_distance_m = fixture_route_distance();
  route.total_duration_s =
      moto::demo::jinan_big_data::kFallbackDurationS;
  route.speed_limit_kph = 50;
  route.generated_at_ms = now_ms;
  return route;
}

void deliver_route(std::uint32_t request_id) {
  if (request_id == 0) {
    return;
  }
  ++now_ms;
  app.handle(RouteReady{request_id, fixture_route(), now_ms});
  pending_route_request_id = 0;
}

void deliver_traffic(std::uint32_t request_id, const char* route_id) {
  TrafficSnapshot traffic;
  traffic.route_id = route_id;
  traffic.remaining_duration_s = app.snapshot().remaining_duration_s;
  traffic.segments = {{0.0, fixture_route_distance(), desired_traffic}};
  traffic.observed_at_ms = ++now_ms;
  app.handle(TrafficUpdated{request_id, std::move(traffic)});
}

void fulfill_commands(const NavCommands& commands, bool hold_route = false) {
  for (const NavCommand& command : commands) {
    if (command.type == CommandType::RequestRoute) {
      if (hold_route) {
        pending_route_request_id = command.request_id;
      } else {
        deliver_route(command.request_id);
      }
    } else if (command.type == CommandType::RequestTraffic) {
      deliver_traffic(command.request_id, command.route_id.c_str());
    }
  }
}

void dispatch_live_commands(const NavCommands& commands) {
  for (const NavCommand& command : commands) {
    if (command.type == CommandType::RequestRoute) {
      emit_live_route_request(
          command.request_id,
          command.route.origin.latitude_deg,
          command.route.origin.longitude_deg,
          command.route.destination.latitude_deg,
          command.route.destination.longitude_deg,
          command.route.is_reroute ? 1 : 0);
    } else if (command.type == CommandType::RequestTraffic) {
      emit_live_traffic_request(command.request_id, command.route_id.c_str());
    }
  }
}

TimestampMs live_timestamp(double value) {
  if (!std::isfinite(value) || value <= 0.0) {
    return ++now_ms;
  }
  const auto timestamp = static_cast<TimestampMs>(value);
  now_ms = std::max(now_ms, timestamp);
  return timestamp;
}

ManeuverType live_maneuver(int value) {
  if (value < static_cast<int>(ManeuverType::Unknown) ||
      value > static_cast<int>(ManeuverType::Arrive)) {
    return ManeuverType::Unknown;
  }
  return static_cast<ManeuverType>(value);
}

TrafficLevel live_traffic(int value) {
  if (value < static_cast<int>(TrafficLevel::Unknown) ||
      value > static_cast<int>(TrafficLevel::Severe)) {
    return TrafficLevel::Unknown;
  }
  return static_cast<TrafficLevel>(value);
}

Wgs84Point interpolate(Wgs84Point start, Wgs84Point end, double fraction) {
  return {
      start.latitude_deg + (end.latitude_deg - start.latitude_deg) * fraction,
      start.longitude_deg +
          (end.longitude_deg - start.longitude_deg) * fraction,
  };
}

struct FixtureSample {
  Wgs84Point position;
  float speed_mps;
  float heading_deg;
};

double radians(double degrees) { return degrees * kPi / 180.0; }
double degrees(double radians_value) { return radians_value * 180.0 / kPi; }

float segment_heading(Wgs84Point start, Wgs84Point end) {
  const double latitude_a = radians(start.latitude_deg);
  const double latitude_b = radians(end.latitude_deg);
  const double delta_longitude =
      radians(end.longitude_deg - start.longitude_deg);
  const double y = std::sin(delta_longitude) * std::cos(latitude_b);
  const double x = std::cos(latitude_a) * std::sin(latitude_b) -
                   std::sin(latitude_a) * std::cos(latitude_b) *
                       std::cos(delta_longitude);
  double heading = degrees(std::atan2(y, x));
  if(heading < 0.0) heading += 360.0;
  return static_cast<float>(heading);
}

double fixture_distance(Wgs84Point start, Wgs84Point end) {
  const double latitude =
      radians((start.latitude_deg + end.latitude_deg) * 0.5);
  const double north = radians(end.latitude_deg - start.latitude_deg) *
                       kFixtureEarthRadiusM;
  const double east = radians(end.longitude_deg - start.longitude_deg) *
                      std::cos(latitude) * kFixtureEarthRadiusM;
  return std::hypot(east, north);
}

const std::array<double, kFixtureGeometry.size()>& fixture_cumulative() {
  static const auto cumulative = [] {
    std::array<double, kFixtureGeometry.size()> values{};
    for(std::size_t index = 1; index < values.size(); ++index) {
      values[index] = values[index - 1] +
                      fixture_distance(kFixtureGeometry[index - 1],
                                       kFixtureGeometry[index]);
    }
    return values;
  }();
  return cumulative;
}

double fixture_route_distance() {
  return fixture_cumulative().back();
}

double fixture_maneuver_offset(
    const moto::demo::jinan_big_data::ManeuverFixture& maneuver) {
  const auto& cumulative = fixture_cumulative();
  const std::size_t index = std::min<std::size_t>(
      maneuver.route_point_index, cumulative.size() - 1);
  return cumulative[index];
}

Wgs84Point fixture_point(double route_offset_m) {
  const auto& cumulative = fixture_cumulative();
  const double offset = std::clamp(route_offset_m, 0.0, cumulative.back());
  auto upper = std::upper_bound(cumulative.begin(), cumulative.end(), offset);
  if(upper == cumulative.begin()) return kFixtureGeometry.front();
  if(upper == cumulative.end()) return kFixtureGeometry.back();
  const std::size_t end_index = static_cast<std::size_t>(
      upper - cumulative.begin());
  const std::size_t start_index = end_index - 1;
  const double segment = cumulative[end_index] - cumulative[start_index];
  const double fraction = segment > 0.0
                              ? (offset - cumulative[start_index]) / segment
                              : 0.0;
  return interpolate(kFixtureGeometry[start_index],
                     kFixtureGeometry[end_index], fraction);
}

FixtureSample fixture_sample(int progress) {
  const int clamped = std::clamp(progress, 0, 100);
  const double normalized = static_cast<double>(clamped) / 100.0;
  const double route_offset_m = normalized * fixture_route_distance();
  float heading = segment_heading(fixture_point(route_offset_m - 11.0),
                                  fixture_point(route_offset_m + 11.0));
  heading += static_cast<float>(heading_offset_deg) +
             static_cast<float>(std::sin(normalized * kPi * 8.0) * 1.8);
  while(heading < 0.0F) heading += 360.0F;
  while(heading >= 360.0F) heading -= 360.0F;
  const float speed = clamped >= 99
                          ? 0.0F
                          : static_cast<float>(14.0 +
                                               5.0 * std::sin(normalized * kPi));
  return {
      fixture_point(route_offset_m),
      speed,
      heading,
  };
}

void establish_fixture_route() {
  pending_route_request_id = 0;
  app.handle(Reset{});
  app.handle(NetworkChanged{NetworkState::Online});
  app.handle(BeginNavigation{kDestination});
  const FixtureSample start = fixture_sample(0);
  fulfill_commands(app.handle(GnssFixReceived{
      fixture_fix(start.position, start.speed_mps, start.heading_deg)}));
  if (!desired_online) {
    app.handle(NetworkChanged{NetworkState::Offline});
  }
}

void feed_progress(int progress) {
  const int clamped = std::clamp(progress, 0, 100);
  current_progress = clamped;
  const FixtureSample sample = fixture_sample(clamped);
  app.handle(GnssFixReceived{
      fixture_fix(sample.position, sample.speed_mps, sample.heading_deg)});
  if (clamped == 100) {
    // Arrival confirmation intentionally follows the firmware's two-fix rule.
    app.handle(GnssFixReceived{
        fixture_fix(kDestination, 0.0F, sample.heading_deg)});
  }
}

}  // namespace

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_initialize(void) {
  live_mode = true;
  pending_route_request_id = 0;
  live_route_builder = {};
  live_route_request_id = 0;
  live_traffic_builder = {};
  live_traffic_request_id = 0;
  app.handle(Reset{});
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_start(
    double destination_latitude, double destination_longitude) {
  live_mode = true;
  app.handle(BeginNavigation{{destination_latitude, destination_longitude}});
  app.handle(DisplayPageSelected{DisplayPage::Navigation});
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_cancel(void) {
  live_mode = true;
  app.handle(CancelNavigation{});
  live_route_builder = {};
  live_route_request_id = 0;
  live_traffic_builder = {};
  live_traffic_request_id = 0;
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_set_network(int online) {
  live_mode = true;
  dispatch_live_commands(app.handle(NetworkChanged{
      online != 0 ? NetworkState::Online : NetworkState::Offline}));
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_fix(
    double latitude, double longitude, double accuracy_m, double speed_mps,
    double heading_deg, double timestamp_ms) {
  live_mode = true;
  GnssFix fix;
  fix.position = {latitude, longitude};
  fix.accuracy_m = std::isfinite(accuracy_m)
                       ? static_cast<float>(std::max(0.0, accuracy_m))
                       : 999.0F;
  fix.speed_mps = std::isfinite(speed_mps)
                      ? static_cast<float>(std::max(0.0, speed_mps))
                      : 0.0F;
  fix.heading_deg = std::isfinite(heading_deg)
                        ? static_cast<float>(heading_deg)
                        : app.snapshot().heading_deg;
  fix.timestamp_ms = live_timestamp(timestamp_ms);
  dispatch_live_commands(app.handle(GnssFixReceived{fix}));
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_tick(
    double timestamp_ms) {
  live_mode = true;
  dispatch_live_commands(app.handle(Tick{live_timestamp(timestamp_ms)}));
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_route_begin(
    std::uint32_t request_id, const char* route_id,
    double total_distance_m, std::uint32_t total_duration_s,
    double generated_at_ms) {
  live_route_request_id = request_id;
  live_route_builder = {};
  live_route_builder.route_id = route_id == nullptr ? "" : route_id;
  live_route_builder.total_distance_m = total_distance_m;
  live_route_builder.total_duration_s = total_duration_s;
  live_route_builder.generated_at_ms = live_timestamp(generated_at_ms);
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_route_add_point(
    double latitude, double longitude) {
  if (live_route_request_id == 0 ||
      live_route_builder.polyline.size() >= 8192) {
    return;
  }
  live_route_builder.polyline.push_back({latitude, longitude});
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_route_add_maneuver(
    std::uint32_t id, int type, double route_offset_m,
    const char* road_name, const char* instruction, int roundabout_exit) {
  if (live_route_request_id == 0 ||
      live_route_builder.maneuvers.size() >= 512) {
    return;
  }
  Maneuver maneuver;
  maneuver.id = id;
  maneuver.type = live_maneuver(type);
  maneuver.route_offset_m = route_offset_m;
  maneuver.road_name = road_name == nullptr ? "" : road_name;
  maneuver.instruction = instruction == nullptr ? "" : instruction;
  maneuver.roundabout_exit = static_cast<std::uint8_t>(
      std::clamp(roundabout_exit, 0, 32));
  live_route_builder.maneuvers.push_back(std::move(maneuver));
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_route_add_traffic(
    double start_offset_m, double end_offset_m, int level) {
  if (live_route_request_id == 0 ||
      live_route_builder.traffic.size() >= 2048) {
    return;
  }
  live_route_builder.traffic.push_back(
      {start_offset_m, end_offset_m, live_traffic(level)});
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_route_commit(
    double received_at_ms) {
  if (live_route_request_id == 0) return;
  const std::uint32_t request_id = live_route_request_id;
  live_route_request_id = 0;
  app.handle(RouteReady{request_id, std::move(live_route_builder),
                        live_timestamp(received_at_ms)});
  live_route_builder = {};
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_route_failed(
    std::uint32_t request_id, int retryable, double received_at_ms) {
  live_route_request_id = 0;
  live_route_builder = {};
  app.handle(RouteFailed{request_id, retryable != 0,
                         live_timestamp(received_at_ms)});
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_traffic_begin(
    std::uint32_t request_id, const char* route_id,
    std::uint32_t remaining_duration_s, double observed_at_ms) {
  live_traffic_request_id = request_id;
  live_traffic_builder = {};
  live_traffic_builder.route_id = route_id == nullptr ? "" : route_id;
  live_traffic_builder.remaining_duration_s = remaining_duration_s;
  live_traffic_builder.observed_at_ms = live_timestamp(observed_at_ms);
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_traffic_add_segment(
    double start_offset_m, double end_offset_m, int level) {
  if (live_traffic_request_id == 0 ||
      live_traffic_builder.segments.size() >= 2048) {
    return;
  }
  live_traffic_builder.segments.push_back(
      {start_offset_m, end_offset_m, live_traffic(level)});
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_traffic_commit(void) {
  if (live_traffic_request_id == 0) return;
  const std::uint32_t request_id = live_traffic_request_id;
  live_traffic_request_id = 0;
  app.handle(TrafficUpdated{request_id, std::move(live_traffic_builder)});
  live_traffic_builder = {};
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_live_traffic_failed(
    std::uint32_t request_id, double received_at_ms) {
  live_traffic_request_id = 0;
  live_traffic_builder = {};
  app.handle(TrafficUpdateFailed{request_id,
                                 live_timestamp(received_at_ms)});
}

extern "C" EMSCRIPTEN_KEEPALIVE int moto_web_live_get_nav_state(void) {
  return static_cast<int>(app.snapshot().state);
}

extern "C" EMSCRIPTEN_KEEPALIVE double
moto_web_live_get_remaining_distance(void) {
  return app.snapshot().remaining_distance_m;
}

extern "C" EMSCRIPTEN_KEEPALIVE int
moto_web_live_get_remaining_duration(void) {
  return static_cast<int>(app.snapshot().remaining_duration_s);
}

extern "C" EMSCRIPTEN_KEEPALIVE int moto_web_live_get_route_generation(void) {
  return static_cast<int>(app.snapshot().route_generation);
}

extern "C" EMSCRIPTEN_KEEPALIVE double moto_web_live_get_route_progress(void) {
  return app.snapshot().route_progress_m;
}

extern "C" EMSCRIPTEN_KEEPALIVE int moto_web_live_get_off_route(void) {
  return app.snapshot().off_route ? 1 : 0;
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_preview_phone_lifecycle(
    int scenario) {
  lifecycle_preview_active = true;
  lifecycle_preview_snapshot = {};
  lifecycle_preview_snapshot.display_page = DisplayPage::Navigation;
  lifecycle_preview_snapshot.now_ms = now_ms;

  moto_ui_phone_connection_t connection = MOTO_UI_PHONE_OFFLINE;
  switch (scenario) {
    case 1:
      connection = MOTO_UI_PHONE_CONNECTING;
      lifecycle_preview_snapshot.network = NetworkState::Connecting;
      break;
    case 2:
    case 3:
      connection = MOTO_UI_PHONE_ONLINE;
      lifecycle_preview_snapshot.network = NetworkState::Online;
      break;
    case 4:
      connection = MOTO_UI_PHONE_ONLINE;
      lifecycle_preview_snapshot.network = NetworkState::Online;
      lifecycle_preview_snapshot.has_destination = true;
      lifecycle_preview_snapshot.route_request_in_flight = true;
      lifecycle_preview_snapshot.state = NavState::Planning;
      break;
    case 0:
    default:
      lifecycle_preview_snapshot.network = NetworkState::Offline;
      break;
  }
  moto_nav_ui_set_phone_connection(connection);
  presenter.update(lifecycle_preview_snapshot);
  presenter.apply_to_lvgl();
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_set_phone_connection(
    int connection) {
  const int normalized = std::clamp(
      connection, static_cast<int>(MOTO_UI_PHONE_OFFLINE),
      static_cast<int>(MOTO_UI_PHONE_ONLINE));
  moto_nav_ui_set_phone_connection(
      static_cast<moto_ui_phone_connection_t>(normalized));
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_set_scenario(int scenario) {
  lifecycle_preview_active = false;
  establish_fixture_route();
  switch (scenario) {
    case 1:
      feed_progress(58);
      break;
    case 2:
      feed_progress(75);
      break;
    case 3:
      feed_progress(100);
      break;
    default:
      feed_progress(24);
      break;
  }
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_set_network(int online) {
  desired_online = online != 0;
  const NavCommands commands = app.handle(NetworkChanged{
      desired_online ? NetworkState::Online : NetworkState::Offline});
  if (desired_online) {
    fulfill_commands(commands);
  }
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_set_traffic(int traffic) {
  const int normalized = std::clamp(
      traffic, static_cast<int>(MOTO_TRAFFIC_UNKNOWN),
      static_cast<int>(MOTO_TRAFFIC_SEVERE));
  switch (normalized) {
    case MOTO_TRAFFIC_CLEAR:
      desired_traffic = TrafficLevel::FreeFlow;
      break;
    case MOTO_TRAFFIC_SLOW:
      desired_traffic = TrafficLevel::Slow;
      break;
    case MOTO_TRAFFIC_CONGESTED:
      desired_traffic = TrafficLevel::Congested;
      break;
    case MOTO_TRAFFIC_SEVERE:
      desired_traffic = TrafficLevel::Severe;
      break;
    default:
      desired_traffic = TrafficLevel::Unknown;
      break;
  }

  if (desired_online && app.snapshot().state == NavState::Navigating) {
    now_ms += 60'001;
    fulfill_commands(app.handle(Tick{now_ms}));
  }
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_set_off_route(int off_route) {
  if (off_route != 0) {
    if (app.snapshot().state == NavState::Navigating) {
      fulfill_commands(app.handle(SimulateDeviation{}), true);
    }
    return;
  }

  if (pending_route_request_id != 0) {
    deliver_route(pending_route_request_id);
  } else if (desired_online && app.snapshot().state == NavState::Rerouting) {
    fulfill_commands(app.handle(Tick{++now_ms}));
  }
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_set_progress(int progress) {
  if (app.snapshot().state == NavState::Arrived ||
      app.snapshot().state == NavState::Rerouting) {
    return;
  }

  const int clamped = std::clamp(progress, 0, 100);
  const NavSnapshot snapshot = app.snapshot();
  const double current_percent =
      snapshot.total_distance_m > 0.0
          ? snapshot.route_progress_m / snapshot.total_distance_m * 100.0
          : 0.0;
  if (static_cast<double>(clamped) + 0.5 < current_percent) {
    establish_fixture_route();
  }
  feed_progress(clamped);
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_set_page(int page) {
  const auto ui_page = static_cast<moto_ui_page_t>(std::clamp(
      page, static_cast<int>(MOTO_UI_PAGE_NAVIGATION),
      static_cast<int>(MOTO_UI_PAGE_COUNT) - 1));
  app.handle(DisplayPageSelected{display_page_from_ui(ui_page)});
}

extern "C" EMSCRIPTEN_KEEPALIVE void moto_web_set_heading_offset(int degrees) {
  heading_offset_deg = std::clamp(degrees, -90, 90);
  if(app.snapshot().state != NavState::Arrived) {
    feed_progress(current_progress);
  }
}

extern "C" EMSCRIPTEN_KEEPALIVE int moto_web_get_page(void) {
  switch(app.snapshot().display_page) {
    case DisplayPage::Speed: return MOTO_UI_PAGE_SPEED;
    case DisplayPage::Compass: return MOTO_UI_PAGE_COMPASS;
    case DisplayPage::Music: return MOTO_UI_PAGE_MUSIC;
    case DisplayPage::Navigation: return MOTO_UI_PAGE_NAVIGATION;
  }
  return MOTO_UI_PAGE_NAVIGATION;
}

int main() {
  lv_init();
  // Register the browser scheduler before SDL configures vsync. SDL's
  // Emscripten backend adjusts main-loop timing while creating the window.
  emscripten_set_main_loop_arg(frame, nullptr, 0, false);
  lv_display_t* display = lv_sdl_window_create(
      MOTO_UI_CANVAS_WIDTH, MOTO_UI_CANVAS_HEIGHT);
  (void)display;
  lv_sdl_mouse_create();
  moto_nav_ui_create();

  app.set_presenter(present_snapshot, &presenter);
  moto_nav_ui_set_page_change_callback(page_change_requested, nullptr);
  app.present();

  emscripten_set_main_loop_timing(EM_TIMING_RAF, 1);
  return EXIT_SUCCESS;
}
