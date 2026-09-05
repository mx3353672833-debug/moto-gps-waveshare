#pragma once

#include <cstdint>
#include <string>
#include <variant>
#include <vector>

namespace moto::nav {

using TimestampMs = std::uint64_t;

// Coordinate systems are distinct C++ types on purpose. A raw GNSS point
// cannot be passed to route matching, and a provider polyline cannot leak into
// a route request without an explicit conversion boundary.
struct Wgs84Point {
  double latitude_deg = 0.0;
  double longitude_deg = 0.0;
};

struct Gcj02Point {
  double latitude_deg = 0.0;
  double longitude_deg = 0.0;
};

enum class NavState : std::uint8_t {
  Idle,
  Acquiring,
  Planning,
  Navigating,
  Rerouting,
  Arrived,
};

enum class NetworkState : std::uint8_t {
  Offline,
  Connecting,
  Online,
};

enum class DisplayPage : std::uint8_t {
  Navigation,
  Speed,
  Compass,
  Music,
};

enum class ManeuverType : std::uint8_t {
  Unknown,
  Continue,
  SlightLeft,
  Left,
  SharpLeft,
  UTurnLeft,
  SlightRight,
  Right,
  SharpRight,
  UTurnRight,
  Roundabout,
  Exit,
  Arrive,
};

enum class TrafficLevel : std::uint8_t {
  Unknown,
  FreeFlow,
  Slow,
  Congested,
  Severe,
};

struct Maneuver {
  std::uint32_t id = 0;
  ManeuverType type = ManeuverType::Unknown;
  double route_offset_m = 0.0;
  std::string road_name;
  std::string instruction;
  std::uint8_t roundabout_exit = 0;
};

struct TrafficSegment {
  double start_offset_m = 0.0;
  double end_offset_m = 0.0;
  TrafficLevel level = TrafficLevel::Unknown;
};

// Provider-neutral route format. The backend converts AMap (and later other
// providers) into this structure before either target sees it.
struct RouteBundle {
  std::string route_id;
  std::vector<Gcj02Point> polyline;
  std::vector<Maneuver> maneuvers;
  std::vector<TrafficSegment> traffic;
  double total_distance_m = 0.0;
  std::uint32_t total_duration_s = 0;
  // Zero means the provider did not supply a trustworthy speed limit.
  std::uint16_t speed_limit_kph = 0;
  TimestampMs generated_at_ms = 0;
};

struct TrafficSnapshot {
  std::string route_id;
  std::uint32_t remaining_duration_s = 0;
  std::vector<TrafficSegment> segments;
  TimestampMs observed_at_ms = 0;
};

struct GnssFix {
  Wgs84Point position;
  float accuracy_m = 0.0F;
  float speed_mps = 0.0F;
  float heading_deg = 0.0F;
  TimestampMs timestamp_ms = 0;
};

struct Reset {};

struct BeginNavigation {
  Wgs84Point destination;
};

struct CancelNavigation {};

struct GnssFixReceived {
  GnssFix fix;
};

struct NetworkChanged {
  NetworkState state = NetworkState::Offline;
};

struct RouteReady {
  std::uint32_t request_id = 0;
  RouteBundle route;
  TimestampMs received_at_ms = 0;
};

struct RouteFailed {
  std::uint32_t request_id = 0;
  bool retryable = true;
  TimestampMs received_at_ms = 0;
};

struct TrafficUpdated {
  std::uint32_t request_id = 0;
  TrafficSnapshot traffic;
};

struct TrafficUpdateFailed {
  std::uint32_t request_id = 0;
  TimestampMs received_at_ms = 0;
};

struct Tick {
  TimestampMs timestamp_ms = 0;
};

// Used by the Web fixture console to exercise the exact production rerouting
// transition without inventing a browser-only state path.
struct SimulateDeviation {};

struct DisplayPageSelected {
  DisplayPage page = DisplayPage::Navigation;
};

using NavEvent = std::variant<Reset,
                              BeginNavigation,
                              CancelNavigation,
                              GnssFixReceived,
                              NetworkChanged,
                              RouteReady,
                              RouteFailed,
                              TrafficUpdated,
                              TrafficUpdateFailed,
                              Tick,
                              SimulateDeviation,
                              DisplayPageSelected>;

enum class CommandType : std::uint8_t {
  RequestRoute,
  RequestTraffic,
};

struct RouteRequest {
  Wgs84Point origin;
  Wgs84Point destination;
  bool is_reroute = false;
};

// Commands are effects for a platform adapter to execute. NavCore itself never
// opens sockets, reads a clock, accesses GNSS hardware, or stores credentials.
struct NavCommand {
  CommandType type = CommandType::RequestRoute;
  std::uint32_t request_id = 0;
  RouteRequest route;
  std::string route_id;
};

using NavCommands = std::vector<NavCommand>;

}  // namespace moto::nav
