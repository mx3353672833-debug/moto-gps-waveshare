#include "nav_core/nav_core.hpp"

#include "moto_coordinates.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <type_traits>
#include <utility>

namespace moto::nav {
namespace {

constexpr double kEarthRadiusM = 6'371'000.0;
constexpr double kPi = 3.14159265358979323846;
// Once navigation has an accepted progress position, never let a nearby
// parallel/returning leg pull the matcher arbitrarily far back through the
// route.  A small backward window still tolerates ordinary GNSS jitter and a
// rider briefly reversing direction.
constexpr double kRouteMatchBackwardWindowM = 100.0;

double radians(double degrees) { return degrees * kPi / 180.0; }

TimestampMs saturating_add(TimestampMs lhs, TimestampMs rhs) {
  if (rhs > std::numeric_limits<TimestampMs>::max() - lhs) {
    return std::numeric_limits<TimestampMs>::max();
  }
  return lhs + rhs;
}

TrafficLevel worse_traffic(TrafficLevel lhs, TrafficLevel rhs) {
  const auto severity = [](TrafficLevel level) {
    switch (level) {
      case TrafficLevel::FreeFlow:
        return 1;
      case TrafficLevel::Slow:
        return 2;
      case TrafficLevel::Congested:
        return 3;
      case TrafficLevel::Severe:
        return 4;
      case TrafficLevel::Unknown:
      default:
        return 0;
    }
  };
  return severity(rhs) > severity(lhs) ? rhs : lhs;
}

}  // namespace

NavCore::NavCore(NavCoreConfig config) : config_(config) {
  if (config_.off_route_confirmations == 0) {
    config_.off_route_confirmations = 1;
  }
  if (config_.arrival_confirmations == 0) {
    config_.arrival_confirmations = 1;
  }
  reset_session(false);
}

NavCommands NavCore::handle(NavEvent event) {
  NavCommands commands;
  commands.reserve(2);

  std::visit(
      [this, &commands](auto&& value) {
        using T = std::decay_t<decltype(value)>;
        if constexpr (std::is_same_v<T, Reset> ||
                      std::is_same_v<T, CancelNavigation>) {
          reset_session(true);
        } else if constexpr (std::is_same_v<T, BeginNavigation>) {
          const NetworkState network = view_.network;
          reset_session(false);
          view_.network = network;
          if (valid_point(value.destination)) {
            view_.destination = value.destination;
            view_.has_destination = true;
            view_.state = NavState::Acquiring;
          }
        } else if constexpr (std::is_same_v<T, GnssFixReceived>) {
          accept_fix(value.fix, commands);
        } else if constexpr (std::is_same_v<T, NetworkChanged>) {
          view_.network = value.state;
          if (value.state != NetworkState::Online) {
            // Any response from the interrupted adapter operation is stale.
            view_.route_request_in_flight = false;
            view_.traffic_request_in_flight = false;
            active_route_request_id_ = 0;
            active_traffic_request_id_ = 0;
          } else {
            request_route_if_possible(commands);
            request_traffic_if_due(commands);
          }
        } else if constexpr (std::is_same_v<T, RouteReady>) {
          view_.now_ms = std::max(view_.now_ms, value.received_at_ms);
          accept_route(std::move(value));
        } else if constexpr (std::is_same_v<T, RouteFailed>) {
          view_.now_ms = std::max(view_.now_ms, value.received_at_ms);
          if (value.request_id == active_route_request_id_) {
            view_.route_request_in_flight = false;
            active_route_request_id_ = 0;
            route_retry_at_ms_ = value.retryable
                                     ? saturating_add(
                                           view_.now_ms,
                                           config_.route_retry_after_ms)
                                     : std::numeric_limits<TimestampMs>::max();
          }
        } else if constexpr (std::is_same_v<T, TrafficUpdated>) {
          accept_traffic(std::move(value));
        } else if constexpr (std::is_same_v<T, TrafficUpdateFailed>) {
          view_.now_ms = std::max(view_.now_ms, value.received_at_ms);
          if (value.request_id == active_traffic_request_id_) {
            view_.traffic_request_in_flight = false;
            active_traffic_request_id_ = 0;
            traffic_retry_at_ms_ = saturating_add(
                view_.now_ms, config_.traffic_retry_after_ms);
          }
        } else if constexpr (std::is_same_v<T, Tick>) {
          on_tick(value.timestamp_ms, commands);
        } else if constexpr (std::is_same_v<T, SimulateDeviation>) {
          if (view_.state == NavState::Navigating &&
              last_match_fix_gcj02_.has_value()) {
            off_route_count_ = config_.off_route_confirmations;
            enter_rerouting(commands);
          }
        } else if constexpr (std::is_same_v<T, DisplayPageSelected>) {
          view_.display_page = value.page;
        }
      },
      std::move(event));

  return commands;
}

NavSnapshot NavCore::snapshot() const { return view_; }

void NavCore::reset_session(bool preserve_network) {
  const NetworkState network =
      preserve_network ? view_.network : NetworkState::Offline;
  const DisplayPage display_page = view_.display_page;
  view_ = {};
  view_.state = NavState::Idle;
  view_.network = network;
  view_.display_page = display_page;
  view_.gnss_stale = true;
  route_ = {};
  cumulative_distance_m_.clear();
  traffic_.clear();
  last_gnss_fix_wgs84_.reset();
  last_match_fix_gcj02_.reset();
  active_route_request_id_ = 0;
  active_traffic_request_id_ = 0;
  off_route_count_ = 0;
  arrival_count_ = 0;
  route_retry_at_ms_ = 0;
  traffic_retry_at_ms_ = 0;
  eta_reference_remaining_distance_m_ = 0.0;
  eta_reference_duration_s_ = 0;
}

void NavCore::accept_fix(const GnssFix& fix, NavCommands& commands) {
  if (last_gnss_fix_wgs84_.has_value() &&
      fix.timestamp_ms < last_gnss_fix_wgs84_->timestamp_ms) {
    return;
  }

  view_.now_ms = std::max(view_.now_ms, fix.timestamp_ms);
  view_.horizontal_accuracy_m = fix.accuracy_m;
  if (!usable_fix(fix)) {
    return;
  }

  const auto converted = moto::coordinates::wgs84_to_gcj02(
      {fix.position.longitude_deg, fix.position.latitude_deg});
  if (!converted.ok()) {
    return;
  }

  const Gcj02Point match_position{
      converted.coordinate.latitude_deg,
      converted.coordinate.longitude_deg,
  };
  last_gnss_fix_wgs84_ = fix;
  last_match_fix_gcj02_ = match_position;
  view_.has_usable_fix = true;
  view_.gnss_stale = false;
  view_.position = fix.position;
  view_.speed_mps = fix.speed_mps;
  view_.heading_deg = fix.heading_deg;
  view_.last_fix_ms = fix.timestamp_ms;

  if (view_.state == NavState::Acquiring) {
    enter_planning(commands);
  } else if (view_.state == NavState::Navigating) {
    update_route_match(match_position, commands);
  } else if (view_.state == NavState::Rerouting) {
    // Keep location and old-route progress alive while the replacement route
    // is fetched, but do not recursively declare another deviation.
    const Projection projection = project_onto_route(
        match_position,
        std::max(0.0,
                 view_.route_progress_m - kRouteMatchBackwardWindowM));
    if (projection.valid) {
      view_.cross_track_distance_m =
          static_cast<float>(projection.cross_track_m);
      update_route_view(
          match_position,
          std::max(view_.route_progress_m, projection.along_route_m));
    }
  }
}

void NavCore::accept_route(RouteReady event) {
  if (event.request_id == 0 ||
      event.request_id != active_route_request_id_) {
    return;
  }

  view_.route_request_in_flight = false;
  active_route_request_id_ = 0;

  if (!valid_route(event.route)) {
    route_retry_at_ms_ =
        saturating_add(view_.now_ms, config_.route_retry_after_ms);
    return;
  }

  route_ = std::move(event.route);
  std::sort(route_.maneuvers.begin(), route_.maneuvers.end(),
            [](const Maneuver& lhs, const Maneuver& rhs) {
              return lhs.route_offset_m < rhs.route_offset_m;
            });

  cumulative_distance_m_.assign(route_.polyline.size(), 0.0);
  for (std::size_t i = 1; i < route_.polyline.size(); ++i) {
    cumulative_distance_m_[i] = cumulative_distance_m_[i - 1] +
                                distance_m(route_.polyline[i - 1],
                                           route_.polyline[i]);
  }
  if (route_.total_distance_m <= 0.0) {
    route_.total_distance_m = cumulative_distance_m_.back();
  }

  traffic_ = route_.traffic;
  view_.route_id = route_.route_id;
  view_.speed_limit_kph = route_.speed_limit_kph;
  view_.route_progress_m = 0.0;
  view_.total_distance_m = route_.total_distance_m;
  view_.remaining_distance_m = route_.total_distance_m;
  view_.route_generation += 1;
  view_.off_route = false;
  view_.state = NavState::Navigating;
  off_route_count_ = 0;
  arrival_count_ = 0;
  eta_reference_remaining_distance_m_ = route_.total_distance_m;
  eta_reference_duration_s_ = route_.total_duration_s;
  view_.last_traffic_update_ms = event.received_at_ms;
  traffic_retry_at_ms_ = saturating_add(
      event.received_at_ms, config_.traffic_refresh_interval_ms);

  if (last_match_fix_gcj02_.has_value()) {
    const Projection projection =
        project_onto_route(*last_match_fix_gcj02_);
    if (projection.valid) {
      view_.route_progress_m = std::clamp(
          projection.along_route_m, 0.0, route_.total_distance_m);
      view_.cross_track_distance_m =
          static_cast<float>(projection.cross_track_m);
      update_route_view(*last_match_fix_gcj02_, view_.route_progress_m);
    }
  }
  update_derived_route_fields();
}

void NavCore::accept_traffic(TrafficUpdated event) {
  const bool expected_request = event.request_id != 0 &&
                                event.request_id == active_traffic_request_id_;
  if (!expected_request || event.traffic.route_id != route_.route_id ||
      route_.route_id.empty()) {
    return;
  }

  active_traffic_request_id_ = 0;
  view_.traffic_request_in_flight = false;
  traffic_ = std::move(event.traffic.segments);
  view_.last_traffic_update_ms = event.traffic.observed_at_ms;
  view_.now_ms = std::max(view_.now_ms, event.traffic.observed_at_ms);
  traffic_retry_at_ms_ = saturating_add(
      view_.now_ms, config_.traffic_refresh_interval_ms);
  eta_reference_remaining_distance_m_ = view_.remaining_distance_m;
  eta_reference_duration_s_ = event.traffic.remaining_duration_s;
  update_derived_route_fields();
}

void NavCore::on_tick(TimestampMs timestamp_ms, NavCommands& commands) {
  view_.now_ms = std::max(view_.now_ms, timestamp_ms);
  view_.gnss_stale = !last_gnss_fix_wgs84_.has_value() ||
                     view_.now_ms > last_gnss_fix_wgs84_->timestamp_ms +
                                        config_.gnss_stale_after_ms;
  request_route_if_possible(commands);
  request_traffic_if_due(commands);
}

void NavCore::enter_planning(NavCommands& commands) {
  view_.state = NavState::Planning;
  view_.off_route = false;
  route_retry_at_ms_ = view_.now_ms;
  request_route_if_possible(commands);
}

void NavCore::enter_rerouting(NavCommands& commands) {
  view_.state = NavState::Rerouting;
  view_.off_route = true;
  view_.traffic_request_in_flight = false;
  active_traffic_request_id_ = 0;
  route_retry_at_ms_ = view_.now_ms;
  request_route_if_possible(commands);
}

void NavCore::request_route_if_possible(NavCommands& commands) {
  const bool state_needs_route = view_.state == NavState::Planning ||
                                 view_.state == NavState::Rerouting;
  if (!state_needs_route || view_.network != NetworkState::Online ||
      !last_gnss_fix_wgs84_.has_value() || !view_.has_destination ||
      view_.route_request_in_flight || view_.now_ms < route_retry_at_ms_) {
    return;
  }

  NavCommand command;
  command.type = CommandType::RequestRoute;
  command.request_id = allocate_request_id();
  command.route.origin = last_gnss_fix_wgs84_->position;
  command.route.destination = view_.destination;
  command.route.is_reroute = view_.state == NavState::Rerouting;
  active_route_request_id_ = command.request_id;
  view_.route_request_in_flight = true;
  commands.push_back(std::move(command));
}

void NavCore::request_traffic_if_due(NavCommands& commands, bool force) {
  if (view_.state != NavState::Navigating ||
      view_.network != NetworkState::Online || route_.route_id.empty() ||
      view_.traffic_request_in_flight ||
      (!force && view_.now_ms < traffic_retry_at_ms_)) {
    return;
  }

  NavCommand command;
  command.type = CommandType::RequestTraffic;
  command.request_id = allocate_request_id();
  command.route_id = route_.route_id;
  active_traffic_request_id_ = command.request_id;
  view_.traffic_request_in_flight = true;
  commands.push_back(std::move(command));
}

void NavCore::update_route_match(const Gcj02Point& position,
                                 NavCommands& commands) {
  const Projection projection = project_onto_route(
      position,
      std::max(0.0,
               view_.route_progress_m - kRouteMatchBackwardWindowM));
  if (!projection.valid) {
    return;
  }

  view_.cross_track_distance_m =
      static_cast<float>(projection.cross_track_m);
  // Progress is monotonic for display and maneuver advancement. A later
  // map-matcher can replace this policy without changing the public contract.
  view_.route_progress_m = std::max(
      view_.route_progress_m,
      std::clamp(projection.along_route_m, 0.0, route_.total_distance_m));
  update_route_view(position, view_.route_progress_m);
  update_derived_route_fields();

  const double final_distance =
      distance_m(position, route_.polyline.back());
  const bool at_destination =
      final_distance <= config_.arrival_radius_m &&
      view_.remaining_distance_m <= config_.arrival_radius_m * 2.0F;
  if (at_destination) {
    arrival_count_ = static_cast<std::uint8_t>(std::min<int>(
        255, static_cast<int>(arrival_count_) + 1));
  } else {
    arrival_count_ = 0;
  }
  if (arrival_count_ >= config_.arrival_confirmations) {
    view_.state = NavState::Arrived;
    view_.off_route = false;
    view_.route_progress_m = route_.total_distance_m;
    update_derived_route_fields();
    return;
  }

  if (projection.cross_track_m > config_.off_route_threshold_m) {
    off_route_count_ = static_cast<std::uint8_t>(std::min<int>(
        255, static_cast<int>(off_route_count_) + 1));
  } else if (projection.cross_track_m < config_.on_route_threshold_m) {
    off_route_count_ = 0;
  }

  if (off_route_count_ >= config_.off_route_confirmations) {
    enter_rerouting(commands);
  }
}

void NavCore::update_route_view(const Gcj02Point& position,
                                double route_progress_m) {
  view_.has_route_view = false;
  view_.route_view_point_count = 0;
  if (route_.polyline.size() < 2 ||
      cumulative_distance_m_.size() != route_.polyline.size() ||
      cumulative_distance_m_.back() <= 0.0) {
    return;
  }

  // Keep every array slot tied to a fixed distance relative to the rider.
  // Copying a raw 24-point window made all slots shift by one whenever the
  // matched segment advanced; the UI then interpolated unrelated vertices
  // and briefly drew false chords or loops. Distance resampling makes the
  // geometry continuous even when provider vertices are unevenly spaced.
  constexpr double kBehindRiderM = 55.0;
  constexpr double kSampleSpacingM = 25.0;
  const double geometry_total_m = cumulative_distance_m_.back();
  const double route_total_m = route_.total_distance_m > 0.0
                                   ? route_.total_distance_m
                                   : geometry_total_m;
  const double route_to_geometry_scale = geometry_total_m / route_total_m;
  const double first_route_distance_m = std::max(
      0.0, std::clamp(route_progress_m, 0.0, route_total_m) -
               kBehindRiderM);

  for (std::size_t i = 0; i < kRouteViewPointCapacity; ++i) {
    const double route_distance_m = std::min(
        route_total_m,
        first_route_distance_m + static_cast<double>(i) * kSampleSpacingM);
    const double geometry_distance_m =
        route_distance_m * route_to_geometry_scale;
    const auto upper = std::upper_bound(
        cumulative_distance_m_.begin(), cumulative_distance_m_.end(),
        geometry_distance_m);
    if (upper == cumulative_distance_m_.begin()) {
      view_.route_view_points[i] = route_.polyline.front();
      continue;
    }
    if (upper == cumulative_distance_m_.end()) {
      view_.route_view_points[i] = route_.polyline.back();
      continue;
    }
    const std::size_t next_index = static_cast<std::size_t>(
        std::distance(cumulative_distance_m_.begin(), upper));
    const std::size_t previous_index = next_index - 1;
    const double segment_start_m = cumulative_distance_m_[previous_index];
    const double segment_length_m =
        cumulative_distance_m_[next_index] - segment_start_m;
    const double t = segment_length_m > 0.0
                         ? std::clamp(
                               (geometry_distance_m - segment_start_m) /
                                   segment_length_m,
                               0.0, 1.0)
                         : 0.0;
    const Gcj02Point& a = route_.polyline[previous_index];
    const Gcj02Point& b = route_.polyline[next_index];
    view_.route_view_points[i] = {
        a.latitude_deg + (b.latitude_deg - a.latitude_deg) * t,
        a.longitude_deg + (b.longitude_deg - a.longitude_deg) * t,
    };
  }
  view_.route_view_origin = position;
  view_.route_view_point_count =
      static_cast<std::uint8_t>(kRouteViewPointCapacity);
  view_.has_route_view = true;
}

void NavCore::update_derived_route_fields() {
  if (route_.polyline.empty()) {
    return;
  }

  view_.total_distance_m = route_.total_distance_m;
  view_.remaining_distance_m =
      std::max(0.0, route_.total_distance_m - view_.route_progress_m);

  if (eta_reference_duration_s_ > 0 &&
      eta_reference_remaining_distance_m_ > 0.0) {
    const double fraction = std::clamp(
        view_.remaining_distance_m / eta_reference_remaining_distance_m_,
        0.0, 1.0);
    view_.remaining_duration_s = static_cast<std::uint32_t>(
        std::llround(static_cast<double>(eta_reference_duration_s_) *
                     fraction));
  } else {
    view_.remaining_duration_s = 0;
  }

  view_.has_next_maneuver = false;
  view_.distance_to_next_maneuver_m = 0.0;
  for (const Maneuver& maneuver : route_.maneuvers) {
    if (maneuver.route_offset_m + 1.0 >= view_.route_progress_m) {
      view_.has_next_maneuver = true;
      view_.next_maneuver = maneuver;
      view_.distance_to_next_maneuver_m =
          std::max(0.0,
                   maneuver.route_offset_m - view_.route_progress_m);
      break;
    }
  }

  view_.traffic_ahead = TrafficLevel::Unknown;
  constexpr double kTrafficLookAheadM = 5'000.0;
  const double look_ahead_end =
      view_.route_progress_m + kTrafficLookAheadM;
  for (const TrafficSegment& segment : traffic_) {
    if (segment.end_offset_m >= view_.route_progress_m &&
        segment.start_offset_m <= look_ahead_end) {
      view_.traffic_ahead =
          worse_traffic(view_.traffic_ahead, segment.level);
    }
  }
}

std::uint32_t NavCore::allocate_request_id() noexcept {
  const std::uint32_t request_id = next_request_id_;
  ++next_request_id_;
  if (next_request_id_ == 0) {
    next_request_id_ = 1;
  }
  return request_id == 0 ? allocate_request_id() : request_id;
}

namespace {
template <typename Point>
bool valid_coordinate(const Point& point) {
  return std::isfinite(point.latitude_deg) &&
         std::isfinite(point.longitude_deg) &&
         point.latitude_deg >= -90.0 && point.latitude_deg <= 90.0 &&
         point.longitude_deg >= -180.0 && point.longitude_deg <= 180.0;
}
}  // namespace

bool NavCore::valid_point(const Wgs84Point& point) const {
  return valid_coordinate(point);
}

bool NavCore::valid_point(const Gcj02Point& point) const {
  return valid_coordinate(point);
}

bool NavCore::usable_fix(const GnssFix& fix) const {
  return valid_point(fix.position) && std::isfinite(fix.accuracy_m) &&
         fix.accuracy_m >= 0.0F &&
         fix.accuracy_m <= config_.maximum_usable_accuracy_m;
}

bool NavCore::valid_route(const RouteBundle& route) const {
  if (route.route_id.empty() || route.polyline.size() < 2) {
    return false;
  }
  return std::all_of(route.polyline.begin(), route.polyline.end(),
                     [this](const Gcj02Point& point) {
                       return valid_point(point);
                     });
}

NavCore::Projection NavCore::project_onto_route(
    const Gcj02Point& point,
    double minimum_route_progress_m) const {
  Projection best;
  if (route_.polyline.size() < 2 ||
      cumulative_distance_m_.size() != route_.polyline.size()) {
    return best;
  }

  const double geometry_total = cumulative_distance_m_.back();
  if (geometry_total <= 0.0) {
    return best;
  }
  const double route_total = route_.total_distance_m > 0.0
                                 ? route_.total_distance_m
                                 : geometry_total;
  const double route_per_geometry = route_total / geometry_total;
  const double minimum_geometry_progress_m =
      std::clamp(minimum_route_progress_m, 0.0, route_total) /
      route_per_geometry;

  double best_distance = std::numeric_limits<double>::infinity();
  for (std::size_t i = 0; i + 1 < route_.polyline.size(); ++i) {
    const Gcj02Point& a = route_.polyline[i];
    const Gcj02Point& b = route_.polyline[i + 1];
    const double reference_lat =
        radians((a.latitude_deg + b.latitude_deg + point.latitude_deg) /
                3.0);
    const double cos_lat = std::cos(reference_lat);
    const double ax = radians(a.longitude_deg - point.longitude_deg) *
                      cos_lat * kEarthRadiusM;
    const double ay = radians(a.latitude_deg - point.latitude_deg) *
                      kEarthRadiusM;
    const double bx = radians(b.longitude_deg - point.longitude_deg) *
                      cos_lat * kEarthRadiusM;
    const double by = radians(b.latitude_deg - point.latitude_deg) *
                      kEarthRadiusM;
    const double dx = bx - ax;
    const double dy = by - ay;
    const double length_squared = dx * dx + dy * dy;
    if (length_squared <= 1e-6) {
      continue;
    }
    const double segment_start_m = cumulative_distance_m_[i];
    const double segment_end_m = cumulative_distance_m_[i + 1];
    if (segment_end_m < minimum_geometry_progress_m) {
      continue;
    }
    const double segment_length_m = segment_end_m - segment_start_m;
    const double minimum_t = segment_length_m > 0.0
                                 ? std::clamp(
                                       (minimum_geometry_progress_m -
                                        segment_start_m) /
                                           segment_length_m,
                                       0.0, 1.0)
                                 : 0.0;
    const double t = std::clamp(-(ax * dx + ay * dy) / length_squared,
                                minimum_t, 1.0);
    const double projected_x = ax + t * dx;
    const double projected_y = ay + t * dy;
    const double cross_track = std::hypot(projected_x, projected_y);
    if (cross_track < best_distance) {
      best_distance = cross_track;
      const double geometry_along =
          segment_start_m + t * segment_length_m;
      best.valid = true;
      best.along_route_m = geometry_along * route_per_geometry;
      best.cross_track_m = cross_track;
      best.segment_index = i;
    }
  }
  return best;
}

double NavCore::distance_m(const Gcj02Point& a,
                           const Gcj02Point& b) const {
  const double latitude_delta =
      radians(b.latitude_deg - a.latitude_deg);
  const double longitude_delta =
      radians(b.longitude_deg - a.longitude_deg);
  const double latitude_a = radians(a.latitude_deg);
  const double latitude_b = radians(b.latitude_deg);
  const double haversine =
      std::sin(latitude_delta / 2.0) *
          std::sin(latitude_delta / 2.0) +
      std::cos(latitude_a) * std::cos(latitude_b) *
          std::sin(longitude_delta / 2.0) *
          std::sin(longitude_delta / 2.0);
  return 2.0 * kEarthRadiusM *
         std::asin(std::sqrt(std::clamp(haversine, 0.0, 1.0)));
}

const char* to_string(NavState state) {
  switch (state) {
    case NavState::Idle:
      return "IDLE";
    case NavState::Acquiring:
      return "ACQUIRING";
    case NavState::Planning:
      return "PLANNING";
    case NavState::Navigating:
      return "NAVIGATING";
    case NavState::Rerouting:
      return "REROUTING";
    case NavState::Arrived:
      return "ARRIVED";
  }
  return "UNKNOWN";
}

const char* to_string(NetworkState state) {
  switch (state) {
    case NetworkState::Offline:
      return "OFFLINE";
    case NetworkState::Connecting:
      return "CONNECTING";
    case NetworkState::Online:
      return "ONLINE";
  }
  return "UNKNOWN";
}

const char* to_string(TrafficLevel level) {
  switch (level) {
    case TrafficLevel::Unknown:
      return "UNKNOWN";
    case TrafficLevel::FreeFlow:
      return "FREE_FLOW";
    case TrafficLevel::Slow:
      return "SLOW";
    case TrafficLevel::Congested:
      return "CONGESTED";
    case TrafficLevel::Severe:
      return "SEVERE";
  }
  return "UNKNOWN";
}

}  // namespace moto::nav
