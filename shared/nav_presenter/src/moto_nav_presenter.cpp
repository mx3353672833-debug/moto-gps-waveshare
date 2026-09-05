#include "moto_nav_presenter.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>

namespace moto::nav {
namespace {

constexpr double kEarthRadiusM = 6'371'000.0;
constexpr double kPi = 3.14159265358979323846;
constexpr double kLegacyCanvasSize = 360.0;
constexpr double kUiScale =
    static_cast<double>(MOTO_UI_CANVAS_WIDTH) / kLegacyCanvasSize;
constexpr double kVehicleX = MOTO_UI_CANVAS_WIDTH / 2.0;
constexpr double kVehicleY = 196.0 * kUiScale;
// A navigation minimap is a spatial frame of reference, so it must not jump
// between zoom levels as a maneuver counter crosses an arbitrary threshold.
// This fixed street-scale view shows roughly 400 m ahead on the 466 px round
// display and is shared by the selected route and all context roads.
constexpr double kMapPixelsPerMeter = 0.44 * kUiScale;

double radians(double degrees) { return degrees * kPi / 180.0; }

moto_ui_mode_t map_mode(const NavSnapshot& snapshot) {
  switch (snapshot.state) {
    case NavState::Idle:
    case NavState::Acquiring:
    case NavState::Planning:
      return MOTO_UI_ACQUIRING_FIX;
    case NavState::Navigating:
      return snapshot.network == NetworkState::Offline
                 ? MOTO_UI_OFFLINE
                 : MOTO_UI_NAVIGATING;
    case NavState::Rerouting:
      return MOTO_UI_REROUTING;
    case NavState::Arrived:
      return MOTO_UI_ARRIVED;
  }
  return MOTO_UI_ACQUIRING_FIX;
}

moto_ui_page_t map_page(DisplayPage page) {
  switch(page) {
    case DisplayPage::Navigation: return MOTO_UI_PAGE_NAVIGATION;
    case DisplayPage::Speed: return MOTO_UI_PAGE_SPEED;
    case DisplayPage::Compass: return MOTO_UI_PAGE_COMPASS;
    case DisplayPage::Music: return MOTO_UI_PAGE_MUSIC;
  }
  return MOTO_UI_PAGE_NAVIGATION;
}

moto_maneuver_t map_maneuver(ManeuverType maneuver) {
  switch (maneuver) {
    case ManeuverType::SlightLeft:
      return MOTO_MANEUVER_SLIGHT_LEFT;
    case ManeuverType::Left:
    case ManeuverType::SharpLeft:
      return MOTO_MANEUVER_LEFT;
    case ManeuverType::SlightRight:
      return MOTO_MANEUVER_SLIGHT_RIGHT;
    case ManeuverType::Right:
    case ManeuverType::SharpRight:
    case ManeuverType::Exit:
      return MOTO_MANEUVER_RIGHT;
    case ManeuverType::UTurnLeft:
    case ManeuverType::UTurnRight:
      return MOTO_MANEUVER_UTURN;
    case ManeuverType::Roundabout:
      return MOTO_MANEUVER_ROUNDABOUT;
    case ManeuverType::Arrive:
      return MOTO_MANEUVER_ARRIVE;
    case ManeuverType::Unknown:
    case ManeuverType::Continue:
      return MOTO_MANEUVER_STRAIGHT;
  }
  return MOTO_MANEUVER_STRAIGHT;
}

moto_traffic_t map_traffic(TrafficLevel traffic) {
  switch (traffic) {
    case TrafficLevel::FreeFlow:
      return MOTO_TRAFFIC_CLEAR;
    case TrafficLevel::Slow:
      return MOTO_TRAFFIC_SLOW;
    case TrafficLevel::Congested:
      return MOTO_TRAFFIC_CONGESTED;
    case TrafficLevel::Severe:
      return MOTO_TRAFFIC_SEVERE;
    case TrafficLevel::Unknown:
      return MOTO_TRAFFIC_UNKNOWN;
  }
  return MOTO_TRAFFIC_UNKNOWN;
}

template <typename Integer>
Integer rounded_saturated(double value) {
  static_assert(std::numeric_limits<Integer>::is_integer,
                "destination must be an integer");
  if (!std::isfinite(value) || value <= 0.0) {
    return 0;
  }
  const double maximum =
      static_cast<double>(std::numeric_limits<Integer>::max());
  if (value >= maximum) {
    return std::numeric_limits<Integer>::max();
  }
  return static_cast<Integer>(std::floor(value + 0.5));
}

std::uint8_t progress_percent(const NavSnapshot& snapshot) {
  if (snapshot.state == NavState::Arrived) {
    return 100;
  }
  if (!std::isfinite(snapshot.total_distance_m) ||
      snapshot.total_distance_m <= 0.0 ||
      !std::isfinite(snapshot.route_progress_m)) {
    return 0;
  }
  const double percent = std::clamp(
      snapshot.route_progress_m / snapshot.total_distance_m * 100.0,
      0.0, 100.0);
  return rounded_saturated<std::uint8_t>(percent);
}

std::uint16_t normalized_heading(float value) {
  if (!std::isfinite(value)) {
    return 0;
  }
  double normalized = std::fmod(static_cast<double>(value), 360.0);
  if (normalized < 0.0) {
    normalized += 360.0;
  }
  return static_cast<std::uint16_t>(std::lround(normalized)) % 360;
}

std::uint32_t route_identity(const std::string& route_id) {
  if (route_id.empty()) return 0;
  std::uint32_t hash = 2'166'136'261U;
  for (const unsigned char byte : route_id) {
    hash ^= byte;
    hash *= 16'777'619U;
  }
  // Zero is reserved for the no-route state.
  return hash == 0 ? 1 : hash;
}

struct MapTransform {
  double origin_latitude_deg = 0.0;
  double origin_longitude_deg = 0.0;
  double cos_reference_latitude = 1.0;
  double sin_heading = 0.0;
  double cos_heading = 1.0;
};

MapTransform map_transform(const NavSnapshot& snapshot) {
  const double heading = radians(snapshot.heading_deg);
  return {
      snapshot.route_view_origin.latitude_deg,
      snapshot.route_view_origin.longitude_deg,
      std::cos(radians(snapshot.route_view_origin.latitude_deg)),
      std::sin(heading),
      std::cos(heading),
  };
}

moto_ui_point_t project_map_point(const Gcj02Point& point,
                                  const MapTransform& transform) {
  const double east =
      radians(point.longitude_deg - transform.origin_longitude_deg) *
      transform.cos_reference_latitude * kEarthRadiusM;
  const double north =
      radians(point.latitude_deg - transform.origin_latitude_deg) *
      kEarthRadiusM;

  // Heading-up transform: the rider remains fixed and every real street
  // translates/rotates together under it, as on an automotive minimap.
  const double right = east * transform.cos_heading -
                       north * transform.sin_heading;
  const double forward = east * transform.sin_heading +
                         north * transform.cos_heading;
  const double x = kVehicleX + right * kMapPixelsPerMeter;
  const double y = kVehicleY - forward * kMapPixelsPerMeter;
  return {
      static_cast<std::int16_t>(
          std::clamp(std::lround(x), -2'000L, 2'000L)),
      static_cast<std::int16_t>(
          std::clamp(std::lround(y), -2'000L, 2'000L)),
  };
}

void project_route_view(const NavSnapshot& snapshot,
                        const MapTransform& transform,
                        moto_ui_state_t& output) {
  output.route_point_count = 0;
  if (!snapshot.has_route_view || snapshot.route_view_point_count < 2) {
    return;
  }

  const std::size_t count = std::min<std::size_t>(
      snapshot.route_view_point_count, MOTO_UI_ROUTE_POINT_CAPACITY);
  for (std::size_t i = 0; i < count; ++i) {
    output.route_points[i] =
        project_map_point(snapshot.route_view_points[i], transform);
  }
  output.route_point_count = static_cast<std::uint8_t>(count);
}

void project_road_context(const NavSnapshot& snapshot,
                          const MapTransform& transform,
                          moto_ui_state_t& output) {
  output.road_point_count = 0;
  output.road_polyline_count = 0;
  if (!snapshot.has_route_view || !snapshot.has_road_context ||
      snapshot.road_context_point_count < 2 ||
      snapshot.road_context_polyline_count == 0) {
    return;
  }

  const std::size_t point_count = std::min<std::size_t>(
      snapshot.road_context_point_count, MOTO_UI_ROAD_POINT_CAPACITY);
  const std::size_t polyline_count = std::min<std::size_t>(
      snapshot.road_context_polyline_count,
      MOTO_UI_ROAD_POLYLINE_CAPACITY);
  for (std::size_t index = 0; index < point_count; ++index) {
    output.road_points[index] =
        project_map_point(snapshot.road_context_points[index], transform);
  }

  std::size_t accepted_polylines = 0;
  for (std::size_t index = 0; index < polyline_count; ++index) {
    const RoadContextPolylineSpan& span =
        snapshot.road_context_polylines[index];
    const std::size_t first = span.first_point_index;
    const std::size_t count = span.point_count;
    if (count < 2 || first >= point_count || count > point_count - first) {
      continue;
    }
    output.road_polylines[accepted_polylines] = {
        static_cast<std::uint8_t>(first),
        static_cast<std::uint8_t>(count),
        static_cast<std::uint8_t>(span.road_class),
    };
    ++accepted_polylines;
  }
  output.road_point_count = static_cast<std::uint8_t>(point_count);
  output.road_polyline_count =
      static_cast<std::uint8_t>(accepted_polylines);
}

void project_building_context(const NavSnapshot& snapshot,
                              const MapTransform& transform,
                              moto_ui_state_t& output) {
  output.building_point_count = 0;
  output.building_footprint_count = 0;
  if (!snapshot.has_route_view || !snapshot.has_building_context ||
      snapshot.building_context_point_count < 3 ||
      snapshot.building_context_footprint_count == 0) {
    return;
  }

  const std::size_t point_count = std::min<std::size_t>(
      snapshot.building_context_point_count,
      MOTO_UI_BUILDING_POINT_CAPACITY);
  const std::size_t footprint_count = std::min<std::size_t>(
      snapshot.building_context_footprint_count,
      MOTO_UI_BUILDING_FOOTPRINT_CAPACITY);
  for (std::size_t index = 0; index < point_count; ++index) {
    output.building_points[index] = project_map_point(
        snapshot.building_context_points[index], transform);
  }

  std::size_t accepted_footprints = 0;
  for (std::size_t index = 0; index < footprint_count; ++index) {
    const BuildingContextFootprintSpan& span =
        snapshot.building_context_footprints[index];
    const std::size_t first = span.first_point_index;
    const std::size_t count = span.point_count;
    if (count < 3 || first >= point_count || count > point_count - first) {
      continue;
    }
    output.building_footprints[accepted_footprints] = {
        static_cast<std::uint8_t>(first),
        static_cast<std::uint8_t>(count),
        static_cast<std::uint8_t>(span.building_class),
    };
    ++accepted_footprints;
  }
  output.building_point_count = static_cast<std::uint8_t>(point_count);
  output.building_footprint_count =
      static_cast<std::uint8_t>(accepted_footprints);
}

}  // namespace

NavPresenter::NavPresenter() { update(NavSnapshot{}); }

void NavPresenter::update(const NavSnapshot& snapshot) {
  road_name_.clear();
  next_road_name_.clear();
  if (snapshot.has_next_maneuver) {
    // NavSnapshot currently exposes the road associated with the next
    // maneuver, but no separate current-road field. Keep two owned copies so
    // both C pointers have an explicit, stable lifetime. A future current-road
    // field can replace road_name_ without changing moto_ui_state_t.
    road_name_ = snapshot.next_maneuver.road_name;
    next_road_name_ = snapshot.next_maneuver.road_name.empty()
                          ? snapshot.next_maneuver.instruction
                          : snapshot.next_maneuver.road_name;
  }

  ui_state_ = {};
  ui_state_.page = map_page(snapshot.display_page);
  ui_state_.mode = map_mode(snapshot);
  ui_state_.maneuver =
      snapshot.has_next_maneuver
          ? map_maneuver(snapshot.next_maneuver.type)
          : (snapshot.state == NavState::Arrived ? MOTO_MANEUVER_ARRIVE
                                                 : MOTO_MANEUVER_STRAIGHT);
  ui_state_.traffic = map_traffic(snapshot.traffic_ahead);
  ui_state_.distance_to_maneuver_m =
      rounded_saturated<std::uint32_t>(
          snapshot.distance_to_next_maneuver_m);
  ui_state_.remaining_distance_m =
      rounded_saturated<std::uint32_t>(snapshot.remaining_distance_m);
  ui_state_.remaining_time_s = snapshot.remaining_duration_s;
  ui_state_.route_progress_percent = progress_percent(snapshot);
  ui_state_.gps_accuracy_m =
      snapshot.has_usable_fix
          ? rounded_saturated<std::uint8_t>(snapshot.horizontal_accuracy_m)
          : 0;
  ui_state_.online = snapshot.network == NetworkState::Online ? 1 : 0;
  ui_state_.has_destination = snapshot.has_destination ? 1 : 0;
  ui_state_.route_request_in_flight =
      snapshot.route_request_in_flight ? 1 : 0;
  ui_state_.route_identity = route_identity(snapshot.route_id);
  ui_state_.route_generation = snapshot.route_generation;
  ui_state_.map_scene_revision = snapshot.map_scene_revision;
  ui_state_.speed_kph = rounded_saturated<std::uint16_t>(
      static_cast<double>(snapshot.speed_mps) * 3.6);
  ui_state_.speed_limit_kph = snapshot.speed_limit_kph;
  ui_state_.heading_deg = normalized_heading(snapshot.heading_deg);
  // Route, roads and buildings share one rider-centred transform. Besides
  // guaranteeing exact layer alignment, this avoids repeating trigonometry
  // hundreds of times on every high-rate IMU frame.
  const MapTransform transform = map_transform(snapshot);
  project_route_view(snapshot, transform, ui_state_);
  project_road_context(snapshot, transform, ui_state_);
  project_building_context(snapshot, transform, ui_state_);
  ui_state_.road_name = road_name_.empty() ? nullptr : road_name_.c_str();
  ui_state_.next_road_name =
      next_road_name_.empty() ? nullptr : next_road_name_.c_str();
}

const moto_ui_state_t& NavPresenter::ui_state() const { return ui_state_; }

void NavPresenter::apply_to_lvgl() const {
  moto_nav_ui_set_state(&ui_state_);
}

}  // namespace moto::nav
