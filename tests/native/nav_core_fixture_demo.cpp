#include "nav_core/nav_core.hpp"

#include "moto_coordinates.hpp"

#include <iomanip>
#include <iostream>

namespace {

using namespace moto::nav;

void print_view(const char* event, const NavCore& core) {
  const NavSnapshot view = core.snapshot();
  std::cout << std::left << std::setw(18) << event << " state="
            << std::setw(11) << to_string(view.state)
            << " network=" << std::setw(7) << to_string(view.network)
            << " remaining=" << std::setw(7)
            << static_cast<int>(view.remaining_distance_m)
            << " off_route=" << (view.off_route ? "yes" : "no") << '\n';
}

}  // namespace

int main() {
  using namespace moto::nav;

  const Wgs84Point start{31.230400, 121.473700};
  const Wgs84Point middle{31.230400, 121.474700};
  const Wgs84Point destination{31.230400, 121.475700};
  NavCore core;

  print_view("power on", core);
  core.handle(NetworkChanged{NetworkState::Online});
  core.handle(BeginNavigation{destination});
  print_view("destination", core);

  const NavCommands planning = core.handle(GnssFixReceived{
      GnssFix{start, 4.0F, 0.0F, 90.0F, 1'000}});
  print_view("GNSS fix", core);

  RouteBundle route;
  route.route_id = "demo-route";
  const auto to_gcj02 = [](Wgs84Point point) {
    const auto converted = moto::coordinates::wgs84_to_gcj02(
        {point.longitude_deg, point.latitude_deg});
    return Gcj02Point{converted.coordinate.latitude_deg,
                      converted.coordinate.longitude_deg};
  };
  route.polyline = {to_gcj02(start), to_gcj02(middle),
                    to_gcj02(destination)};
  route.maneuvers = {
      Maneuver{1, ManeuverType::Right, 100.0, "西藏中路", "右转", 0},
      Maneuver{2, ManeuverType::Arrive, 190.0, "", "到达", 0},
  };
  route.total_distance_m = 190.0;
  route.total_duration_s = 120;
  core.handle(RouteReady{planning.front().request_id, std::move(route),
                         1'010});
  print_view("route ready", core);

  core.handle(SimulateDeviation{});
  print_view("fixture deviation", core);
  return 0;
}
