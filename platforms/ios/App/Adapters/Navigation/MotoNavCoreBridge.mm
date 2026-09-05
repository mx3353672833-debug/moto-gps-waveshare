#import "MotoNavCoreBridge.h"

#include <memory>
#include <string>
#include <utility>

#include "nav_app/nav_app.hpp"

namespace {

moto::nav::ManeuverType ManeuverFromName(NSString *name) {
  if ([name isEqualToString:@"continue"]) return moto::nav::ManeuverType::Continue;
  if ([name isEqualToString:@"slight_left"]) return moto::nav::ManeuverType::SlightLeft;
  if ([name isEqualToString:@"left"]) return moto::nav::ManeuverType::Left;
  if ([name isEqualToString:@"sharp_left"]) return moto::nav::ManeuverType::SharpLeft;
  if ([name isEqualToString:@"u_turn_left"]) return moto::nav::ManeuverType::UTurnLeft;
  if ([name isEqualToString:@"slight_right"]) return moto::nav::ManeuverType::SlightRight;
  if ([name isEqualToString:@"right"]) return moto::nav::ManeuverType::Right;
  if ([name isEqualToString:@"sharp_right"]) return moto::nav::ManeuverType::SharpRight;
  if ([name isEqualToString:@"u_turn_right"]) return moto::nav::ManeuverType::UTurnRight;
  if ([name isEqualToString:@"roundabout"]) return moto::nav::ManeuverType::Roundabout;
  if ([name isEqualToString:@"exit"]) return moto::nav::ManeuverType::Exit;
  if ([name isEqualToString:@"arrive"]) return moto::nav::ManeuverType::Arrive;
  return moto::nav::ManeuverType::Unknown;
}

moto::nav::TrafficLevel TrafficFromName(NSString *name) {
  if ([name isEqualToString:@"free_flow"]) return moto::nav::TrafficLevel::FreeFlow;
  if ([name isEqualToString:@"slow"]) return moto::nav::TrafficLevel::Slow;
  if ([name isEqualToString:@"congested"]) return moto::nav::TrafficLevel::Congested;
  if ([name isEqualToString:@"severe"]) return moto::nav::TrafficLevel::Severe;
  return moto::nav::TrafficLevel::Unknown;
}

NSString *ManeuverName(moto::nav::ManeuverType type) {
  switch (type) {
    case moto::nav::ManeuverType::Continue: return @"continue";
    case moto::nav::ManeuverType::SlightLeft: return @"slight_left";
    case moto::nav::ManeuverType::Left: return @"left";
    case moto::nav::ManeuverType::SharpLeft: return @"sharp_left";
    case moto::nav::ManeuverType::UTurnLeft: return @"u_turn_left";
    case moto::nav::ManeuverType::SlightRight: return @"slight_right";
    case moto::nav::ManeuverType::Right: return @"right";
    case moto::nav::ManeuverType::SharpRight: return @"sharp_right";
    case moto::nav::ManeuverType::UTurnRight: return @"u_turn_right";
    case moto::nav::ManeuverType::Roundabout: return @"roundabout";
    case moto::nav::ManeuverType::Exit: return @"exit";
    case moto::nav::ManeuverType::Arrive: return @"arrive";
    case moto::nav::ManeuverType::Unknown: return @"unknown";
  }
}

NSString *TrafficName(moto::nav::TrafficLevel level) {
  switch (level) {
    case moto::nav::TrafficLevel::FreeFlow: return @"free_flow";
    case moto::nav::TrafficLevel::Slow: return @"slow";
    case moto::nav::TrafficLevel::Congested: return @"congested";
    case moto::nav::TrafficLevel::Severe: return @"severe";
    case moto::nav::TrafficLevel::Unknown: return @"unknown";
  }
}

NSString *DisplayPageName(moto::nav::DisplayPage page) {
  switch (page) {
    case moto::nav::DisplayPage::Navigation: return @"navigation";
    case moto::nav::DisplayPage::Speed: return @"speed";
    case moto::nav::DisplayPage::Compass: return @"compass";
    case moto::nav::DisplayPage::Music: return @"music";
  }
}

NSString *StateName(moto::nav::NavState state) {
  switch (state) {
    case moto::nav::NavState::Idle: return @"idle";
    case moto::nav::NavState::Acquiring: return @"acquiring";
    case moto::nav::NavState::Planning: return @"planning";
    case moto::nav::NavState::Navigating: return @"navigating";
    case moto::nav::NavState::Rerouting: return @"rerouting";
    case moto::nav::NavState::Arrived: return @"arrived";
  }
}

NSString *NetworkName(moto::nav::NetworkState state) {
  switch (state) {
    case moto::nav::NetworkState::Offline: return @"offline";
    case moto::nav::NetworkState::Connecting: return @"connecting";
    case moto::nav::NetworkState::Online: return @"online";
  }
}

NSString *CommandName(moto::nav::CommandType type) {
  switch (type) {
    case moto::nav::CommandType::RequestRoute: return @"request_route";
    case moto::nav::CommandType::RequestTraffic: return @"request_traffic";
  }
}

}  // namespace

@interface MotoNavCoreCommand ()
@property(nonatomic, readwrite, copy) NSString *typeName;
@property(nonatomic, readwrite) uint32_t requestID;
@property(nonatomic, readwrite) double originLongitudeDeg;
@property(nonatomic, readwrite) double originLatitudeDeg;
@property(nonatomic, readwrite) double destinationLongitudeDeg;
@property(nonatomic, readwrite) double destinationLatitudeDeg;
@property(nonatomic, readwrite) BOOL reroute;
@property(nonatomic, readwrite, copy) NSString *routeID;
@end

@interface MotoNavCoreSnapshot ()
@property(nonatomic, readwrite, copy) NSString *stateName;
@property(nonatomic, readwrite, copy) NSString *networkName;
@property(nonatomic, readwrite, copy) NSString *displayPageName;
@property(nonatomic, readwrite) BOOL hasDestination;
@property(nonatomic, readwrite) BOOL hasUsableFix;
@property(nonatomic, readwrite) BOOL gnssStale;
@property(nonatomic, readwrite) BOOL offRoute;
@property(nonatomic, readwrite) BOOL routeRequestInFlight;
@property(nonatomic, readwrite) BOOL trafficRequestInFlight;
@property(nonatomic, readwrite) double speedMPS;
@property(nonatomic, readwrite) double headingDeg;
@property(nonatomic, readwrite) double horizontalAccuracyM;
@property(nonatomic, readwrite) double crossTrackDistanceM;
@property(nonatomic, readwrite) uint16_t speedLimitKPH;
@property(nonatomic, readwrite) BOOL hasRouteView;
@property(nonatomic, readwrite) double routeViewOriginLongitudeDeg;
@property(nonatomic, readwrite) double routeViewOriginLatitudeDeg;
@property(nonatomic, readwrite, copy) NSArray<MotoNavPointValue *> *routeViewPoints;
@property(nonatomic, readwrite, copy) NSString *routeID;
@property(nonatomic, readwrite) double routeProgressM;
@property(nonatomic, readwrite) double totalDistanceM;
@property(nonatomic, readwrite) double remainingDistanceM;
@property(nonatomic, readwrite) uint32_t remainingDurationS;
@property(nonatomic, readwrite) BOOL hasNextManeuver;
@property(nonatomic, readwrite) uint32_t maneuverID;
@property(nonatomic, readwrite, copy) NSString *maneuverTypeName;
@property(nonatomic, readwrite) double distanceToManeuverM;
@property(nonatomic, readwrite, copy) NSString *roadName;
@property(nonatomic, readwrite, copy) NSString *instructionText;
@property(nonatomic, readwrite) uint8_t roundaboutExit;
@property(nonatomic, readwrite, copy) NSString *trafficName;
@property(nonatomic, readwrite) uint64_t nowMs;
@property(nonatomic, readwrite) uint32_t routeGeneration;
@end

namespace {

NSArray<MotoNavCoreCommand *> *CommandsFrom(const moto::nav::NavCommands &commands) {
  NSMutableArray<MotoNavCoreCommand *> *values =
      [NSMutableArray arrayWithCapacity:commands.size()];
  for (const auto &command : commands) {
    MotoNavCoreCommand *value = [[MotoNavCoreCommand alloc] init];
    value.typeName = CommandName(command.type);
    value.requestID = command.request_id;
    value.originLongitudeDeg = command.route.origin.longitude_deg;
    value.originLatitudeDeg = command.route.origin.latitude_deg;
    value.destinationLongitudeDeg = command.route.destination.longitude_deg;
    value.destinationLatitudeDeg = command.route.destination.latitude_deg;
    value.reroute = command.route.is_reroute;
    value.routeID = [NSString stringWithUTF8String:command.route_id.c_str()];
    [values addObject:value];
  }
  return values;
}

MotoNavCoreSnapshot *SnapshotFrom(const moto::nav::NavSnapshot &source) {
  MotoNavCoreSnapshot *value = [[MotoNavCoreSnapshot alloc] init];
  value.stateName = StateName(source.state);
  value.networkName = NetworkName(source.network);
  value.displayPageName = DisplayPageName(source.display_page);
  value.hasDestination = source.has_destination;
  value.hasUsableFix = source.has_usable_fix;
  value.gnssStale = source.gnss_stale;
  value.offRoute = source.off_route;
  value.routeRequestInFlight = source.route_request_in_flight;
  value.trafficRequestInFlight = source.traffic_request_in_flight;
  value.speedMPS = source.speed_mps;
  value.headingDeg = source.heading_deg;
  value.horizontalAccuracyM = source.horizontal_accuracy_m;
  value.crossTrackDistanceM = source.cross_track_distance_m;
  value.speedLimitKPH = source.speed_limit_kph;
  value.hasRouteView = source.has_route_view;
  value.routeViewOriginLongitudeDeg = source.route_view_origin.longitude_deg;
  value.routeViewOriginLatitudeDeg = source.route_view_origin.latitude_deg;
  NSMutableArray<MotoNavPointValue *> *points =
      [NSMutableArray arrayWithCapacity:source.route_view_point_count];
  for (std::size_t index = 0; index < source.route_view_point_count; ++index) {
    MotoNavPointValue *point = [[MotoNavPointValue alloc] init];
    point.longitudeDeg = source.route_view_points[index].longitude_deg;
    point.latitudeDeg = source.route_view_points[index].latitude_deg;
    [points addObject:point];
  }
  value.routeViewPoints = points;
  value.routeID = [NSString stringWithUTF8String:source.route_id.c_str()];
  value.routeProgressM = source.route_progress_m;
  value.totalDistanceM = source.total_distance_m;
  value.remainingDistanceM = source.remaining_distance_m;
  value.remainingDurationS = source.remaining_duration_s;
  value.hasNextManeuver = source.has_next_maneuver;
  value.maneuverID = source.next_maneuver.id;
  value.maneuverTypeName = ManeuverName(source.next_maneuver.type);
  value.distanceToManeuverM = source.distance_to_next_maneuver_m;
  value.roadName = [NSString stringWithUTF8String:source.next_maneuver.road_name.c_str()];
  value.instructionText =
      [NSString stringWithUTF8String:source.next_maneuver.instruction.c_str()];
  value.roundaboutExit = source.next_maneuver.roundabout_exit;
  value.trafficName = TrafficName(source.traffic_ahead);
  value.nowMs = source.now_ms;
  value.routeGeneration = source.route_generation;
  return value;
}

struct BridgeStorage {
  BridgeStorage() : app(moto::nav::NavCoreConfig{}) {}
  moto::nav::NavApp app;
};

}  // namespace

@implementation MotoNavPointValue
@end

@implementation MotoNavManeuverValue
- (instancetype)init {
  self = [super init];
  if (self) {
    _typeName = @"unknown";
    _roadName = @"";
    _instructionText = @"";
  }
  return self;
}
@end

@implementation MotoNavTrafficValue
- (instancetype)init {
  self = [super init];
  if (self) _levelName = @"unknown";
  return self;
}
@end

@implementation MotoNavRouteValue
- (instancetype)init {
  self = [super init];
  if (self) {
    _routeID = @"";
    _polyline = @[];
    _maneuvers = @[];
    _traffic = @[];
  }
  return self;
}
@end

@implementation MotoNavCoreCommand
@end

@implementation MotoNavCoreSnapshot
@end

@implementation MotoNavCoreBridge {
  void *_storage;
  MotoNavCoreSnapshot *_snapshot;
}

- (instancetype)init {
  self = [super init];
  if (self) {
    _storage = new BridgeStorage{};
    _snapshot = SnapshotFrom(static_cast<BridgeStorage *>(_storage)->app.snapshot());
  }
  return self;
}

- (void)dealloc {
  delete static_cast<BridgeStorage *>(_storage);
}

- (MotoNavCoreSnapshot *)snapshot { return _snapshot; }

- (NSArray<MotoNavCoreCommand *> *)handle:(moto::nav::NavEvent)event {
  auto *storage = static_cast<BridgeStorage *>(_storage);
  const auto commands = storage->app.handle(std::move(event));
  _snapshot = SnapshotFrom(storage->app.snapshot());
  return CommandsFrom(commands);
}

- (NSArray<MotoNavCoreCommand *> *)reset {
  return [self handle:moto::nav::Reset{}];
}

- (NSArray<MotoNavCoreCommand *> *)beginNavigationToLongitude:(double)longitudeDeg
                                                      latitude:(double)latitudeDeg {
  return [self handle:moto::nav::BeginNavigation{{latitudeDeg, longitudeDeg}}];
}

- (NSArray<MotoNavCoreCommand *> *)cancelNavigation {
  return [self handle:moto::nav::CancelNavigation{}];
}

- (NSArray<MotoNavCoreCommand *> *)setNetworkStateName:(NSString *)stateName {
  auto state = moto::nav::NetworkState::Offline;
  if ([stateName isEqualToString:@"connecting"]) state = moto::nav::NetworkState::Connecting;
  if ([stateName isEqualToString:@"online"]) state = moto::nav::NetworkState::Online;
  return [self handle:moto::nav::NetworkChanged{state}];
}

- (NSArray<MotoNavCoreCommand *> *)selectDisplayPageName:(NSString *)pageName {
  auto page = moto::nav::DisplayPage::Navigation;
  if ([pageName isEqualToString:@"speed"]) page = moto::nav::DisplayPage::Speed;
  if ([pageName isEqualToString:@"compass"]) page = moto::nav::DisplayPage::Compass;
  if ([pageName isEqualToString:@"music"]) page = moto::nav::DisplayPage::Music;
  return [self handle:moto::nav::DisplayPageSelected{page}];
}

- (NSArray<MotoNavCoreCommand *> *)pushFixLongitude:(double)longitudeDeg
                                           latitude:(double)latitudeDeg
                                          accuracyM:(double)accuracyM
                                           speedMPS:(double)speedMPS
                                         headingDeg:(double)headingDeg
                                        timestampMs:(uint64_t)timestampMs {
  moto::nav::GnssFix fix;
  fix.position = {latitudeDeg, longitudeDeg};
  fix.accuracy_m = static_cast<float>(accuracyM);
  fix.speed_mps = static_cast<float>(speedMPS);
  fix.heading_deg = static_cast<float>(headingDeg);
  fix.timestamp_ms = timestampMs;
  return [self handle:moto::nav::GnssFixReceived{fix}];
}

- (NSArray<MotoNavCoreCommand *> *)acceptRoute:(MotoNavRouteValue *)input
                                     requestID:(uint32_t)requestID
                                  receivedAtMs:(uint64_t)receivedAtMs {
  moto::nav::RouteBundle route;
  route.route_id = input.routeID.UTF8String ?: "";
  route.total_distance_m = input.totalDistanceM;
  route.total_duration_s = input.totalDurationS;
  route.speed_limit_kph = input.speedLimitKPH;
  route.generated_at_ms = input.generatedAtMs;
  route.polyline.reserve(input.polyline.count);
  for (MotoNavPointValue *point in input.polyline) {
    route.polyline.push_back({point.latitudeDeg, point.longitudeDeg});
  }
  route.maneuvers.reserve(input.maneuvers.count);
  for (MotoNavManeuverValue *item in input.maneuvers) {
    moto::nav::Maneuver maneuver;
    maneuver.id = item.identifier;
    maneuver.type = ManeuverFromName(item.typeName);
    maneuver.route_offset_m = item.routeOffsetM;
    maneuver.road_name = item.roadName.UTF8String ?: "";
    maneuver.instruction = item.instructionText.UTF8String ?: "";
    maneuver.roundabout_exit = item.roundaboutExit;
    route.maneuvers.push_back(std::move(maneuver));
  }
  route.traffic.reserve(input.traffic.count);
  for (MotoNavTrafficValue *item in input.traffic) {
    route.traffic.push_back(
        {item.startOffsetM, item.endOffsetM, TrafficFromName(item.levelName)});
  }
  return [self handle:moto::nav::RouteReady{
      requestID, std::move(route), receivedAtMs}];
}

- (NSArray<MotoNavCoreCommand *> *)rejectRouteRequestID:(uint32_t)requestID
                                               retryable:(BOOL)retryable
                                             receivedAtMs:(uint64_t)receivedAtMs {
  return [self handle:moto::nav::RouteFailed{
      requestID, static_cast<bool>(retryable), receivedAtMs}];
}

- (NSArray<MotoNavCoreCommand *> *)acceptTrafficForRouteID:(NSString *)routeID
                                                  segments:(NSArray<MotoNavTrafficValue *> *)segments
                                        remainingDurationS:(uint32_t)remainingDurationS
                                                 requestID:(uint32_t)requestID
                                              observedAtMs:(uint64_t)observedAtMs {
  moto::nav::TrafficSnapshot traffic;
  traffic.route_id = routeID.UTF8String ?: "";
  traffic.remaining_duration_s = remainingDurationS;
  traffic.observed_at_ms = observedAtMs;
  traffic.segments.reserve(segments.count);
  for (MotoNavTrafficValue *item in segments) {
    traffic.segments.push_back(
        {item.startOffsetM, item.endOffsetM, TrafficFromName(item.levelName)});
  }
  return [self handle:moto::nav::TrafficUpdated{
      requestID, std::move(traffic)}];
}

- (NSArray<MotoNavCoreCommand *> *)rejectTrafficRequestID:(uint32_t)requestID
                                              receivedAtMs:(uint64_t)receivedAtMs {
  return [self handle:moto::nav::TrafficUpdateFailed{requestID, receivedAtMs}];
}

- (NSArray<MotoNavCoreCommand *> *)tickAtMs:(uint64_t)timestampMs {
  return [self handle:moto::nav::Tick{timestampMs}];
}

@end
