#pragma once

#include "port_api/nav_types.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace moto::nav {

constexpr std::size_t kRouteViewPointCapacity = 24;
// One local 466 px map window: enough for a dense urban road graph without
// turning the ESP32 snapshot into a city-wide map database. The bundled Jinan
// verification fixture intentionally exercises both limits.
constexpr std::size_t kRoadContextPointCapacity = 192;
constexpr std::size_t kRoadContextPolylineCapacity = 24;
constexpr std::size_t kBuildingContextPointCapacity = 128;
constexpr std::size_t kBuildingContextFootprintCapacity = 16;

enum class RoadContextClass : std::uint8_t {
  Motorway = 0,
  Primary = 1,
  Secondary = 2,
  Residential = 3,
  Service = 4,
  Other = 5,
};

enum class BuildingContextClass : std::uint8_t {
  Generic = 0,
  Landmark = 1,
  Parking = 2,
};

struct RoadContextPolylineSpan {
  std::uint8_t first_point_index = 0;
  std::uint8_t point_count = 0;
  RoadContextClass road_class = RoadContextClass::Residential;
};

struct BuildingContextFootprintSpan {
  std::uint8_t first_point_index = 0;
  std::uint8_t point_count = 0;
  BuildingContextClass building_class = BuildingContextClass::Generic;
};

struct NavCoreConfig {
  float maximum_usable_accuracy_m = 50.0F;
  float off_route_threshold_m = 45.0F;
  float on_route_threshold_m = 25.0F;
  std::uint8_t off_route_confirmations = 3;
  float arrival_radius_m = 20.0F;
  std::uint8_t arrival_confirmations = 2;
  TimestampMs gnss_stale_after_ms = 5'000;
  TimestampMs route_retry_after_ms = 5'000;
  TimestampMs traffic_refresh_interval_ms = 60'000;
  TimestampMs traffic_retry_after_ms = 10'000;
};

struct NavSnapshot {
  NavState state = NavState::Idle;
  NetworkState network = NetworkState::Offline;
  DisplayPage display_page = DisplayPage::Navigation;

  bool has_destination = false;
  bool has_usable_fix = false;
  bool gnss_stale = true;
  bool off_route = false;
  bool route_request_in_flight = false;
  bool traffic_request_in_flight = false;

  Wgs84Point position;
  Wgs84Point destination;
  float speed_mps = 0.0F;
  float heading_deg = 0.0F;
  float horizontal_accuracy_m = 0.0F;
  float cross_track_distance_m = 0.0F;
  std::uint16_t speed_limit_kph = 0;

  // A small, bounded route window for the heading-up instrument view.  It is
  // intentionally part of the shared snapshot so Web and firmware render the
  // same geometry without retaining a second full route in the UI layer.
  bool has_route_view = false;
  Gcj02Point route_view_origin;
  std::array<Gcj02Point, kRouteViewPointCapacity> route_view_points{};
  std::uint8_t route_view_point_count = 0;

  // Optional, bounded street context for a GTA-style heading-up minimap.
  // Points stay in provider coordinates; NavPresenter applies the exact same
  // rider-centred transform as the selected route. Separate spans prevent
  // unrelated real streets from being connected by an invented segment.
  bool has_road_context = false;
  std::array<Gcj02Point, kRoadContextPointCapacity> road_context_points{};
  std::array<RoadContextPolylineSpan, kRoadContextPolylineCapacity>
      road_context_polylines{};
  std::uint8_t road_context_point_count = 0;
  std::uint8_t road_context_polyline_count = 0;

  // MapScene buildings share the exact rider-centred transform used by the
  // selected route and streets. The polygon closing edge is implicit here;
  // the UI renderer closes it without duplicating BLE/storage points.
  bool has_building_context = false;
  std::array<Gcj02Point, kBuildingContextPointCapacity>
      building_context_points{};
  std::array<BuildingContextFootprintSpan,
             kBuildingContextFootprintCapacity>
      building_context_footprints{};
  std::uint8_t building_context_point_count = 0;
  std::uint8_t building_context_footprint_count = 0;
  std::uint32_t map_scene_revision = 0;

  std::string route_id;
  double route_progress_m = 0.0;
  double total_distance_m = 0.0;
  double remaining_distance_m = 0.0;
  std::uint32_t remaining_duration_s = 0;

  bool has_next_maneuver = false;
  Maneuver next_maneuver;
  double distance_to_next_maneuver_m = 0.0;
  TrafficLevel traffic_ahead = TrafficLevel::Unknown;

  TimestampMs now_ms = 0;
  TimestampMs last_fix_ms = 0;
  TimestampMs last_traffic_update_ms = 0;
  std::uint32_t route_generation = 0;
};

class NavCore {
 public:
  explicit NavCore(NavCoreConfig config = {});

  // All external input enters here. Returned commands are the only requested
  // side effects and can be dispatched by Web or ESP32 platform adapters.
  NavCommands handle(NavEvent event);

  [[nodiscard]] NavSnapshot snapshot() const;

 private:
  struct Projection {
    bool valid = false;
    double along_route_m = 0.0;
    double cross_track_m = 0.0;
    std::size_t segment_index = 0;
  };

  void reset_session(bool preserve_network);
  void accept_fix(const GnssFix& fix, NavCommands& commands);
  void accept_route(RouteReady event);
  void accept_traffic(TrafficUpdated event);
  void on_tick(TimestampMs timestamp_ms, NavCommands& commands);
  void enter_planning(NavCommands& commands);
  void enter_rerouting(NavCommands& commands);
  void request_route_if_possible(NavCommands& commands);
  void request_traffic_if_due(NavCommands& commands, bool force = false);
  void update_route_match(const Gcj02Point& position,
                          NavCommands& commands);
  void update_route_view(const Gcj02Point& position,
                         double route_progress_m);
  void update_derived_route_fields();
  [[nodiscard]] std::uint32_t allocate_request_id() noexcept;

  [[nodiscard]] bool valid_point(const Wgs84Point& point) const;
  [[nodiscard]] bool valid_point(const Gcj02Point& point) const;
  [[nodiscard]] bool usable_fix(const GnssFix& fix) const;
  [[nodiscard]] bool valid_route(const RouteBundle& route) const;
  [[nodiscard]] Projection project_onto_route(
      const Gcj02Point& point,
      double minimum_route_progress_m = 0.0) const;
  [[nodiscard]] double distance_m(const Gcj02Point& a,
                                  const Gcj02Point& b) const;

  NavCoreConfig config_;
  NavSnapshot view_;
  RouteBundle route_;
  std::vector<double> cumulative_distance_m_;
  std::vector<TrafficSegment> traffic_;
  std::optional<GnssFix> last_gnss_fix_wgs84_;
  std::optional<Gcj02Point> last_match_fix_gcj02_;

  std::uint32_t next_request_id_ = 1;
  std::uint32_t active_route_request_id_ = 0;
  std::uint32_t active_traffic_request_id_ = 0;
  std::uint8_t off_route_count_ = 0;
  std::uint8_t arrival_count_ = 0;
  TimestampMs route_retry_at_ms_ = 0;
  TimestampMs traffic_retry_at_ms_ = 0;
  double eta_reference_remaining_distance_m_ = 0.0;
  std::uint32_t eta_reference_duration_s_ = 0;
};

const char* to_string(NavState state);
const char* to_string(NetworkState state);
const char* to_string(TrafficLevel level);

}  // namespace moto::nav
