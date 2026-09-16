#include "nav_core/nav_core.hpp"

#include "moto_coordinates.hpp"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <string>

namespace {

using namespace moto::nav;

int failures = 0;

#define CHECK(condition)                                                   \
  do {                                                                     \
    if (!(condition)) {                                                    \
      std::cerr << __FILE__ << ':' << __LINE__                             \
                << " CHECK failed: " #condition << '\n';                  \
      ++failures;                                                          \
    }                                                                      \
  } while (false)

constexpr Wgs84Point kStart{31.230400, 121.473700};
constexpr Wgs84Point kMiddle{31.230400, 121.474700};
constexpr Wgs84Point kEnd{31.230400, 121.475700};

Gcj02Point gcj02(Wgs84Point point) {
  const auto converted = moto::coordinates::wgs84_to_gcj02(
      {point.longitude_deg, point.latitude_deg});
  CHECK(converted.ok());
  return {converted.coordinate.latitude_deg,
          converted.coordinate.longitude_deg};
}

GnssFix fix(Wgs84Point point, TimestampMs timestamp_ms,
            float accuracy_m = 4.0F) {
  return GnssFix{point, accuracy_m, 8.0F, 90.0F, timestamp_ms};
}

RouteBundle route_fixture(const std::string& id = "route-1") {
  RouteBundle route;
  route.route_id = id;
  route.polyline = {gcj02(kStart), gcj02(kMiddle), gcj02(kEnd)};
  route.maneuvers = {
      Maneuver{1, ManeuverType::Continue, 20.0, "人民大道", "直行", 0},
      Maneuver{2, ManeuverType::Right, 100.0, "西藏中路", "右转", 0},
      Maneuver{3, ManeuverType::Arrive, 190.0, "", "到达目的地", 0},
  };
  route.traffic = {
      TrafficSegment{0.0, 90.0, TrafficLevel::FreeFlow},
      TrafficSegment{90.0, 190.0, TrafficLevel::Slow},
  };
  route.total_distance_m = 190.0;
  route.total_duration_s = 120;
  route.speed_limit_kph = 50;
  route.generated_at_ms = 1'000;
  return route;
}

RouteBundle antimeridian_route_fixture() {
  RouteBundle route;
  route.route_id = "antimeridian-route";
  route.polyline = {
      Gcj02Point{0.0, 179.9990},
      Gcj02Point{0.0, -179.9990},
  };
  route.total_distance_m = 0.0;
  route.total_duration_s = 30;
  route.generated_at_ms = 1'000;
  return route;
}

RouteBundle hairpin_route_fixture(const std::string& id = "hairpin-route") {
  // Three long, closely spaced legs.  A fix on the returning middle leg can
  // intentionally be closer to the already-travelled first leg, reproducing
  // the ambiguity seen around parallel ramps and looped interchanges.
  constexpr Wgs84Point a{31.230400, 121.473700};
  constexpr Wgs84Point b{31.230400, 121.477700};
  constexpr Wgs84Point c{31.230580, 121.477700};
  constexpr Wgs84Point d{31.230580, 121.473700};
  constexpr Wgs84Point e{31.230760, 121.473700};
  constexpr Wgs84Point f{31.230760, 121.477700};

  RouteBundle route;
  route.route_id = id;
  route.polyline = {
      gcj02(a), gcj02(b), gcj02(c),
      gcj02(d), gcj02(e), gcj02(f),
  };
  // Let NavCore derive a route distance in exactly the same coordinate space
  // used by the matcher, avoiding a synthetic provider/geometry scale error.
  route.total_distance_m = 0.0;
  route.total_duration_s = 300;
  route.generated_at_ms = 1'000;
  return route;
}

struct RunningFixture {
  explicit RunningFixture(NavCoreConfig config = {}) : core(config) {
    core.handle(NetworkChanged{NetworkState::Online});
    core.handle(BeginNavigation{kEnd});
    NavCommands commands = core.handle(GnssFixReceived{fix(kStart, 1'000)});
    CHECK(commands.size() == 1);
    CHECK(commands[0].type == CommandType::RequestRoute);
    route_request_id = commands[0].request_id;
    core.handle(RouteReady{route_request_id, route_fixture(), 1'010});
    CHECK(core.snapshot().state == NavState::Navigating);
  }

  NavCore core;
  std::uint32_t route_request_id = 0;
};

void test_lifecycle_and_arrival() {
  NavCore core;
  CHECK(core.snapshot().state == NavState::Idle);

  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{kEnd});
  CHECK(core.snapshot().state == NavState::Acquiring);

  NavCommands commands =
      core.handle(GnssFixReceived{fix(kStart, 100)});
  CHECK(core.snapshot().state == NavState::Planning);
  CHECK(commands.size() == 1);
  CHECK(!commands[0].route.is_reroute);
  CHECK(commands[0].route.origin.latitude_deg == kStart.latitude_deg);
  CHECK(commands[0].route.origin.longitude_deg == kStart.longitude_deg);

  core.handle(RouteReady{commands[0].request_id, route_fixture(), 120});
  NavSnapshot view = core.snapshot();
  CHECK(view.state == NavState::Navigating);
  CHECK(view.route_generation == 1);
  CHECK(view.total_distance_m == 190.0);
  CHECK(view.has_next_maneuver);
  CHECK(view.cross_track_distance_m < 1.0F);
  CHECK(view.speed_limit_kph == 50);
  CHECK(view.has_route_view);
  CHECK(view.route_view_point_count >= 2);
  CHECK(view.route_view_point_count <= kRouteViewPointCapacity);
  CHECK(view.route_view_origin.latitude_deg == gcj02(kStart).latitude_deg);

  core.handle(GnssFixReceived{fix(kMiddle, 200)});
  view = core.snapshot();
  CHECK(view.route_progress_m > 80.0);
  CHECK(view.route_progress_m < 110.0);
  CHECK(view.remaining_distance_m < 110.0);
  CHECK(view.has_route_view);
  CHECK(view.route_view_point_count >= 2);

  core.handle(GnssFixReceived{fix(kEnd, 300)});
  CHECK(core.snapshot().state == NavState::Navigating);
  core.handle(GnssFixReceived{fix(kEnd, 400)});
  CHECK(core.snapshot().state == NavState::Arrived);
  CHECK(core.snapshot().remaining_distance_m == 0.0);
}

void test_offline_planning_resumes_on_network() {
  NavCore core;
  core.handle(BeginNavigation{kEnd});
  const NavCommands offline_commands =
      core.handle(GnssFixReceived{fix(kStart, 1'000)});
  CHECK(core.snapshot().state == NavState::Planning);
  CHECK(offline_commands.empty());

  const NavCommands online_commands =
      core.handle(NetworkChanged{NetworkState::Online});
  CHECK(online_commands.size() == 1);
  CHECK(online_commands[0].type == CommandType::RequestRoute);
}

void test_deviation_hysteresis_and_reroute() {
  RunningFixture fixture;
  const Wgs84Point far_from_route{31.231400, 121.474700};

  CHECK(fixture.core.handle(
            GnssFixReceived{fix(far_from_route, 2'000)})
            .empty());
  CHECK(fixture.core.snapshot().state == NavState::Navigating);
  CHECK(fixture.core.handle(
            GnssFixReceived{fix(far_from_route, 2'100)})
            .empty());
  const NavCommands commands = fixture.core.handle(
      GnssFixReceived{fix(far_from_route, 2'200)});
  CHECK(fixture.core.snapshot().state == NavState::Rerouting);
  CHECK(fixture.core.snapshot().off_route);
  CHECK(commands.size() == 1);
  CHECK(commands[0].type == CommandType::RequestRoute);
  CHECK(commands[0].route.is_reroute);
  CHECK(commands[0].route.origin.latitude_deg ==
        far_from_route.latitude_deg);
  CHECK(commands[0].route.origin.longitude_deg ==
        far_from_route.longitude_deg);
}

void test_explicit_web_deviation_fixture_while_offline() {
  RunningFixture fixture;
  fixture.core.handle(NetworkChanged{NetworkState::Offline});
  const NavCommands offline = fixture.core.handle(SimulateDeviation{});
  CHECK(offline.empty());
  CHECK(fixture.core.snapshot().state == NavState::Rerouting);

  const NavCommands resumed =
      fixture.core.handle(NetworkChanged{NetworkState::Online});
  CHECK(resumed.size() == 1);
  CHECK(resumed[0].route.is_reroute);
}

void test_tick_drives_staleness_and_traffic_refresh() {
  NavCoreConfig config;
  config.gnss_stale_after_ms = 100;
  config.traffic_refresh_interval_ms = 200;
  RunningFixture fixture(config);

  CHECK(fixture.core.handle(Tick{1'209}).empty());
  CHECK(fixture.core.snapshot().gnss_stale);

  const NavCommands traffic_command = fixture.core.handle(Tick{1'210});
  CHECK(traffic_command.size() == 1);
  CHECK(traffic_command[0].type == CommandType::RequestTraffic);

  TrafficSnapshot update;
  update.route_id = "route-1";
  update.remaining_duration_s = 333;
  update.observed_at_ms = 1'220;
  update.segments = {
      TrafficSegment{0.0, 190.0, TrafficLevel::Severe},
  };
  fixture.core.handle(
      TrafficUpdated{traffic_command[0].request_id, std::move(update)});
  CHECK(fixture.core.snapshot().traffic_ahead == TrafficLevel::Severe);
  CHECK(fixture.core.snapshot().remaining_duration_s == 333);
  CHECK(!fixture.core.snapshot().traffic_request_in_flight);
}

void test_stale_route_response_is_ignored_after_disconnect() {
  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{kEnd});
  const NavCommands request =
      core.handle(GnssFixReceived{fix(kStart, 1'000)});
  CHECK(request.size() == 1);

  core.handle(NetworkChanged{NetworkState::Offline});
  core.handle(RouteReady{request[0].request_id, route_fixture(), 1'100});
  CHECK(core.snapshot().state == NavState::Planning);
  CHECK(core.snapshot().route_id.empty());
}

void test_matcher_does_not_jump_to_a_distant_old_parallel_leg() {
  constexpr Wgs84Point start{31.230400, 121.473700};
  constexpr Wgs84Point destination{31.230760, 121.477700};
  constexpr Wgs84Point returning_leg_mid{31.230580, 121.475700};
  // This fix is farther along the returning leg, but GPS jitter places it
  // about 4 m from the old first leg and about 16 m from the active leg.
  constexpr Wgs84Point ambiguous_later_fix{31.230440, 121.474700};

  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{destination});
  const NavCommands request =
      core.handle(GnssFixReceived{fix(start, 1'000)});
  CHECK(request.size() == 1);
  core.handle(RouteReady{
      request[0].request_id, hairpin_route_fixture(), 1'010});

  // Allow enough elapsed time to ride the first leg and make the hairpin;
  // an instantaneous 600 m progress jump now correctly fails continuity.
  core.handle(GnssFixReceived{fix(returning_leg_mid, 80'000)});
  const double progress_before_ambiguous_fix =
      core.snapshot().route_progress_m;
  CHECK(progress_before_ambiguous_fix > 500.0);

  core.handle(GnssFixReceived{fix(ambiguous_later_fix, 92'000)});
  const NavSnapshot after = core.snapshot();
  CHECK(after.route_progress_m > progress_before_ambiguous_fix + 70.0);
  CHECK(after.cross_track_distance_m > 10.0F);
  CHECK(after.cross_track_distance_m < 25.0F);
  CHECK(after.state == NavState::Navigating);
}

void test_initial_route_match_remains_global() {
  constexpr Wgs84Point current_position{31.230580, 121.475700};
  constexpr Wgs84Point destination{31.230760, 121.477700};

  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{destination});
  const NavCommands request = core.handle(
      GnssFixReceived{fix(current_position, 1'000)});
  CHECK(request.size() == 1);
  core.handle(RouteReady{
      request[0].request_id, hairpin_route_fixture(), 1'010});

  // The first accepted route has no continuity history, so it must search the
  // full route and locate this fix on the distant returning leg.
  CHECK(core.snapshot().route_progress_m > 500.0);
  CHECK(core.snapshot().cross_track_distance_m < 1.0F);
}

void test_start_jitter_cannot_jump_to_a_nearby_returning_destination() {
  constexpr Wgs84Point start{31.230400, 121.473700};
  constexpr Wgs84Point turn{31.235000, 121.473700};
  constexpr Wgs84Point return_turn{31.235000, 121.473820};
  constexpr Wgs84Point destination{31.230400, 121.473820};
  constexpr Wgs84Point jitter{31.230400, 121.473790};

  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{destination});
  const auto request = core.handle(GnssFixReceived{fix(start, 1'000)});
  RouteBundle route;
  route.route_id = "returning-destination";
  route.polyline = {gcj02(start), gcj02(turn), gcj02(return_turn),
                    gcj02(destination)};
  route.total_duration_s = 200;
  core.handle(RouteReady{request[0].request_id, route, 1'010});

  // The destination is only 11 m across the road, but reaching it requires
  // following a kilometre-long loop. A small lateral GNSS error must not
  // consume the whole route and collapse the map to its final few metres.
  core.handle(GnssFixReceived{fix(jitter, 2'000)});
  core.handle(GnssFixReceived{fix(jitter, 3'000)});
  CHECK(core.snapshot().state == NavState::Navigating);
  CHECK(core.snapshot().route_progress_m < 20.0);
  CHECK(core.snapshot().remaining_distance_m > 1'000.0);
}

void test_route_match_recovers_after_a_long_fix_gap() {
  constexpr Wgs84Point start{31.230400, 121.473700};
  constexpr Wgs84Point later{31.238000, 121.473700};
  constexpr Wgs84Point destination{31.250000, 121.473700};
  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{destination});
  const auto request = core.handle(GnssFixReceived{fix(start, 1'000)});
  RouteBundle route;
  route.route_id = "long-fix-gap";
  route.polyline = {gcj02(start), gcj02(destination)};
  core.handle(RouteReady{request[0].request_id, route, 1'010});
  core.handle(GnssFixReceived{fix(later, 121'000)});
  CHECK(core.snapshot().route_progress_m > 800.0);
  CHECK(core.snapshot().cross_track_distance_m < 1.0F);
}

void test_route_match_recovers_loop_after_gap_with_stopped_endpoints() {
  const Wgs84Point start{31.230400,121.473700};
  const Wgs84Point turn{31.235000,121.473700};
  const Wgs84Point return_turn{31.235000,121.473820};
  const Wgs84Point returned{31.230500,121.473820};
  const Wgs84Point destination{31.229000,121.473820};
  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{destination});
  auto first = fix(start,1'000);
  first.speed_mps = 0;
  const auto request = core.handle(GnssFixReceived{first});
  RouteBundle route;
  route.route_id = "stopped-gap-recovery";
  route.polyline = {gcj02(start),gcj02(turn),gcj02(return_turn),gcj02(destination)};
  core.handle(RouteReady{request[0].request_id,route,1'010});
  auto recovered = fix(returned,121'000);
  recovered.speed_mps = 0;
  core.handle(GnssFixReceived{recovered});
  CHECK(core.snapshot().route_progress_m > 1'000.0);
  CHECK(core.snapshot().cross_track_distance_m < 1.0F);
}

void test_route_window_uses_geometry_metres_and_survives_dense_vertices() {
  constexpr Wgs84Point start{31.230400, 121.473700};
  constexpr Wgs84Point destination{31.230400, 121.493700};
  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{destination});
  const auto request = core.handle(GnssFixReceived{fix(start, 1'000)});
  RouteBundle route;
  route.route_id = "dense-origin-geometry";
  // Provider distance and polyline length can differ. The physical extent of
  // the map window must not shrink with that ratio or vertex density.
  route.total_distance_m = 3'800.0;
  for (int i = 0; i <= 2'000; ++i) {
    route.polyline.push_back(gcj02({start.latitude_deg,
                                    start.longitude_deg + i * 0.00001}));
  }
  core.handle(RouteReady{request[0].request_id, route, 1'010});
  const auto view = core.snapshot();
  const auto first = view.route_view_points.front();
  const auto last = view.route_view_points[view.route_view_point_count - 1];
  const double longitude_metres = 111'000.0 *
      std::cos(start.latitude_deg * 3.14159265358979323846 / 180.0);
  CHECK(view.route_view_point_count >= 2);
  CHECK(view.route_view_point_count <= kRouteViewPointCapacity);
  CHECK((last.longitude_deg - first.longitude_deg) * longitude_metres >
        490.0);
}

void test_residential_exit_retains_close_corners_after_a_long_detour() {
  const Wgs84Point points[] = {
      {31.2304,121.4737}, {31.2313,121.4737},
      {31.2313,121.4747}, {31.2322,121.4747},
      {31.2322,121.4727}, {31.2314,121.4727},
      {31.2314,121.4723}, {31.2390,121.4723},
  };
  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{points[7]});
  const auto request = core.handle(GnssFixReceived{fix(points[0],1'000)});
  RouteBundle route;
  route.route_id = "residential-corners";
  for (const auto point : points) route.polyline.push_back(gcj02(point));
  core.handle(RouteReady{request[0].request_id,route,1'010});
  const auto view = core.snapshot();
  CHECK(view.route_view_point_count == route.polyline.size());
  for (std::size_t i = 0; i < view.route_view_point_count; ++i) {
    CHECK(std::abs(view.route_view_points[i].latitude_deg -
                   route.polyline[i].latitude_deg) < 1e-7);
    CHECK(std::abs(view.route_view_points[i].longitude_deg -
                   route.polyline[i].longitude_deg) < 1e-7);
  }
}

void test_route_corner_capacity_reduces_horizon_without_skipping_turns() {
  RouteBundle route;
  route.route_id = "many-close-corners";
  for (int i = 0; i < 60; ++i) {
    route.polyline.push_back(gcj02({kStart.latitude_deg + i * 0.000135,
                                    kStart.longitude_deg + (i % 2) * 0.00011}));
  }
  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{kEnd});
  const auto request = core.handle(GnssFixReceived{fix(kStart,1'000)});
  core.handle(RouteReady{request[0].request_id,route,1'010});
  const auto view = core.snapshot();
  CHECK(view.route_view_point_count == kRouteViewPointCapacity);
  for (std::size_t i = 0; i < view.route_view_point_count; ++i) {
    CHECK(std::abs(view.route_view_points[i].latitude_deg -
                   route.polyline[i].latitude_deg) < 1e-7);
    CHECK(std::abs(view.route_view_points[i].longitude_deg -
                   route.polyline[i].longitude_deg) < 1e-7);
  }
}

void test_missing_heading_preserves_the_last_valid_course() {
  RunningFixture fixture;
  GnssFix missing_heading = fix(kMiddle, 2'000);
  missing_heading.heading_deg = std::numeric_limits<float>::quiet_NaN();
  fixture.core.handle(GnssFixReceived{missing_heading});
  CHECK(fixture.core.snapshot().heading_deg == 90.0F);
}

void test_invalid_fix_channels_do_not_poison_snapshot() {
  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{kEnd});

  const float nan = std::numeric_limits<float>::quiet_NaN();
  const float inf = std::numeric_limits<float>::infinity();
  core.handle(GnssFixReceived{GnssFix{kStart, nan, 8.0F, 90.0F, 1'000}});
  core.handle(GnssFixReceived{GnssFix{kStart, -1.0F, 8.0F, 90.0F, 1'100}});
  core.handle(GnssFixReceived{GnssFix{kStart, 9'999.0F, 8.0F, 90.0F, 1'200}});
  CHECK(!core.snapshot().has_usable_fix);
  CHECK(std::isfinite(core.snapshot().horizontal_accuracy_m));
  CHECK(core.snapshot().horizontal_accuracy_m >= 0.0F);

  const NavCommands request = core.handle(
      GnssFixReceived{GnssFix{kStart, 4.0F, nan, inf, 1'300}});
  CHECK(request.size() == 1);
  CHECK(core.snapshot().has_usable_fix);
  CHECK(core.snapshot().speed_mps == 0.0F);
  CHECK(core.snapshot().heading_deg == 0.0F);
}

void test_antimeridian_route_uses_local_geometry() {
  constexpr Wgs84Point start{0.0, 179.9996};
  constexpr Wgs84Point destination{0.0, -179.9990};
  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{destination});
  const NavCommands request =
      core.handle(GnssFixReceived{fix(start, 1'000)});
  CHECK(request.size() == 1);
  core.handle(RouteReady{
      request[0].request_id, antimeridian_route_fixture(), 1'010});

  core.handle(GnssFixReceived{fix(start, 2'000)});
  core.handle(GnssFixReceived{fix(start, 2'100)});
  const NavSnapshot view = core.snapshot();
  CHECK(view.state == NavState::Navigating);
  CHECK(std::isfinite(view.cross_track_distance_m));
  CHECK(view.cross_track_distance_m < 100.0F);
  CHECK(view.route_progress_m > 50.0);
  CHECK(view.route_progress_m < 90.0);
  CHECK(view.has_route_view);
  for (int i = 0; i < view.route_view_point_count; ++i) {
    CHECK(std::fabs(view.route_view_points[static_cast<std::size_t>(i)]
                        .longitude_deg) >= 179.9);
  }
}

void test_malformed_route_numbers_are_contained() {
  for (const double total : {
           -1.0,
           std::numeric_limits<double>::quiet_NaN(),
           std::numeric_limits<double>::infinity(),
       }) {
    NavCore core;
    core.handle(NetworkChanged{NetworkState::Online});
    core.handle(BeginNavigation{kEnd});
    const NavCommands request =
        core.handle(GnssFixReceived{fix(kStart, 1'000)});
    RouteBundle route = route_fixture("bad-total");
    route.total_distance_m = total;
    core.handle(RouteReady{request[0].request_id, std::move(route), 1'010});
    CHECK(core.snapshot().state != NavState::Navigating);
    CHECK(core.snapshot().route_id.empty());
  }

  NavCore core;
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{kEnd});
  const NavCommands request =
      core.handle(GnssFixReceived{fix(kStart, 2'000)});
  RouteBundle route = route_fixture("bad-maneuvers");
  route.maneuvers = {
      Maneuver{1, ManeuverType::Left,
               std::numeric_limits<double>::quiet_NaN(), "", "", 0},
      Maneuver{2, ManeuverType::Continue, -50.0, "", "", 0},
      Maneuver{3, ManeuverType::Arrive, 10'000.0, "", "", 0},
  };
  core.handle(RouteReady{request[0].request_id, std::move(route), 2'010});
  CHECK(core.snapshot().state == NavState::Navigating);
  CHECK(core.snapshot().has_next_maneuver);
  CHECK(std::isfinite(core.snapshot().distance_to_next_maneuver_m));
  CHECK(core.snapshot().distance_to_next_maneuver_m >= 0.0);
  CHECK(core.snapshot().distance_to_next_maneuver_m <=
        core.snapshot().total_distance_m);
}

void test_stationary_fix_zeroes_speed_and_holds_heading() {
  RunningFixture fixture;
  // Moving fix establishes a trustworthy speed and course.
  CHECK(fixture.core.snapshot().speed_mps == 8.0F);
  CHECK(fixture.core.snapshot().heading_deg == 90.0F);

  // A parked receiver reports sub-walking speed with random courses: the
  // gauge must drop to zero and the heading must stay on the last course.
  fixture.core.handle(
      GnssFixReceived{GnssFix{kMiddle, 3.0F, 0.3F, 217.0F, 2'000}});
  CHECK(fixture.core.snapshot().speed_mps == 0.0F);
  CHECK(fixture.core.snapshot().heading_deg == 90.0F);

  fixture.core.handle(
      GnssFixReceived{GnssFix{kMiddle, 3.0F, 0.0F, 5.0F, 3'000}});
  CHECK(fixture.core.snapshot().speed_mps == 0.0F);
  CHECK(fixture.core.snapshot().heading_deg == 90.0F);

  // Speeding up again restores live speed and course tracking.
  fixture.core.handle(
      GnssFixReceived{GnssFix{kMiddle, 3.0F, 6.0F, 120.0F, 4'000}});
  CHECK(fixture.core.snapshot().speed_mps == 6.0F);
  CHECK(fixture.core.snapshot().heading_deg == 120.0F);
}

void test_stale_location_stream_clears_displayed_speed() {
  NavCoreConfig config;
  config.gnss_stale_after_ms = 100;
  RunningFixture fixture(config);
  CHECK(fixture.core.snapshot().speed_mps == 8.0F);

  // The phone stops reporting (e.g. iOS distance filter while parked): the
  // last accepted speed must not linger once the fix stream is stale.
  fixture.core.handle(Tick{2'000});
  CHECK(fixture.core.snapshot().gnss_stale);
  CHECK(fixture.core.snapshot().speed_mps == 0.0F);
}

}  // namespace

int main() {
  test_lifecycle_and_arrival();
  test_offline_planning_resumes_on_network();
  test_deviation_hysteresis_and_reroute();
  test_explicit_web_deviation_fixture_while_offline();
  test_tick_drives_staleness_and_traffic_refresh();
  test_stale_route_response_is_ignored_after_disconnect();
  test_matcher_does_not_jump_to_a_distant_old_parallel_leg();
  test_initial_route_match_remains_global();
  test_start_jitter_cannot_jump_to_a_nearby_returning_destination();
  test_route_match_recovers_after_a_long_fix_gap();
  test_route_match_recovers_loop_after_gap_with_stopped_endpoints();
  test_route_window_uses_geometry_metres_and_survives_dense_vertices();
  test_residential_exit_retains_close_corners_after_a_long_detour();
  test_route_corner_capacity_reduces_horizon_without_skipping_turns();
  test_missing_heading_preserves_the_last_valid_course();
  test_invalid_fix_channels_do_not_poison_snapshot();
  test_antimeridian_route_uses_local_geometry();
  test_malformed_route_numbers_are_contained();
  test_stationary_fix_zeroes_speed_and_holds_heading();
  test_stale_location_stream_clears_displayed_speed();

  if (failures != 0) {
    std::cerr << failures << " nav_core checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All nav_core fixture checks passed\n";
  return EXIT_SUCCESS;
}
