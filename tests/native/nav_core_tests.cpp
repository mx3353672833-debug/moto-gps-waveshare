#include "nav_core/nav_core.hpp"

#include "moto_coordinates.hpp"

#include <cmath>
#include <cstdlib>
#include <iostream>
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
  CHECK(view.route_view_point_count == kRouteViewPointCapacity);
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

  core.handle(GnssFixReceived{fix(returning_leg_mid, 2'000)});
  const double progress_before_ambiguous_fix =
      core.snapshot().route_progress_m;
  CHECK(progress_before_ambiguous_fix > 500.0);

  core.handle(GnssFixReceived{fix(ambiguous_later_fix, 2'100)});
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

  if (failures != 0) {
    std::cerr << failures << " nav_core checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All nav_core fixture checks passed\n";
  return EXIT_SUCCESS;
}
