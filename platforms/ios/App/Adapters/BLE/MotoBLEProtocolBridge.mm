#import "MotoBLEProtocolBridge.h"
#import "../Navigation/MotoNavCoreBridge.h"

#include <algorithm>
#include <cmath>
#include <memory>
#include <string>

#include "moto/ble_protocol/ble_protocol.hpp"

namespace {

NSString *const kMotoBLEErrorDomain = @"org.example.motogps.ble-protocol";

NSError *ProtocolError(moto::ble::Error error, std::size_t offset = 0) {
  NSString *message = [NSString stringWithFormat:@"BLE v1: %s (offset %zu)",
                                                moto::ble::to_string(error),
                                                offset];
  return [NSError errorWithDomain:kMotoBLEErrorDomain
                             code:static_cast<NSInteger>(error)
                         userInfo:@{NSLocalizedDescriptionKey : message}];
}

std::int32_t CoordinateE6(double value) {
  constexpr double minimum = static_cast<double>(INT32_MIN);
  constexpr double maximum = static_cast<double>(INT32_MAX);
  return static_cast<std::int32_t>(
      std::clamp(std::round(value * 1'000'000.0), minimum, maximum));
}

struct CodecStorage {
  explicit CodecStorage(std::size_t frame_size)
      : maximum_frame_size(std::clamp<std::size_t>(
            frame_size, moto::ble::kFrameOverhead + 1,
            moto::ble::kMaxBleAttributeValueSize)),
        reassembler(moto::ble::ReassemblerConfig{}) {}

  std::size_t maximum_frame_size;
  moto::ble::SequenceGenerator sequence;
  moto::ble::Reassembler reassembler;
};

moto::ble::NavigationState NavigationStateFromName(NSString *name) {
  if ([name isEqualToString:@"acquiring"]) return moto::ble::NavigationState::Acquiring;
  if ([name isEqualToString:@"planning"]) return moto::ble::NavigationState::Planning;
  if ([name isEqualToString:@"navigating"]) return moto::ble::NavigationState::Navigating;
  if ([name isEqualToString:@"rerouting"]) return moto::ble::NavigationState::Rerouting;
  if ([name isEqualToString:@"arrived"]) return moto::ble::NavigationState::Arrived;
  return moto::ble::NavigationState::Idle;
}

moto::ble::NetworkState NetworkStateFromName(NSString *name) {
  if ([name isEqualToString:@"connecting"]) return moto::ble::NetworkState::Connecting;
  if ([name isEqualToString:@"online"]) return moto::ble::NetworkState::Online;
  return moto::ble::NetworkState::Offline;
}

moto::ble::DisplayPage DisplayPageFromName(NSString *name) {
  if ([name isEqualToString:@"speed"]) return moto::ble::DisplayPage::Speed;
  if ([name isEqualToString:@"compass"]) return moto::ble::DisplayPage::Compass;
  if ([name isEqualToString:@"music"]) return moto::ble::DisplayPage::Music;
  return moto::ble::DisplayPage::Navigation;
}

moto::ble::Maneuver ManeuverFromName(NSString *name) {
  if ([name isEqualToString:@"continue"]) return moto::ble::Maneuver::Continue;
  if ([name isEqualToString:@"slight_left"]) return moto::ble::Maneuver::SlightLeft;
  if ([name isEqualToString:@"left"]) return moto::ble::Maneuver::Left;
  if ([name isEqualToString:@"sharp_left"]) return moto::ble::Maneuver::SharpLeft;
  if ([name isEqualToString:@"u_turn_left"]) return moto::ble::Maneuver::UTurnLeft;
  if ([name isEqualToString:@"slight_right"]) return moto::ble::Maneuver::SlightRight;
  if ([name isEqualToString:@"right"]) return moto::ble::Maneuver::Right;
  if ([name isEqualToString:@"sharp_right"]) return moto::ble::Maneuver::SharpRight;
  if ([name isEqualToString:@"u_turn_right"]) return moto::ble::Maneuver::UTurnRight;
  if ([name isEqualToString:@"roundabout"]) return moto::ble::Maneuver::Roundabout;
  if ([name isEqualToString:@"exit"]) return moto::ble::Maneuver::Exit;
  if ([name isEqualToString:@"arrive"]) return moto::ble::Maneuver::Arrive;
  return moto::ble::Maneuver::Unknown;
}

moto::ble::TrafficLevel TrafficFromName(NSString *name) {
  if ([name isEqualToString:@"free_flow"]) return moto::ble::TrafficLevel::FreeFlow;
  if ([name isEqualToString:@"slow"]) return moto::ble::TrafficLevel::Slow;
  if ([name isEqualToString:@"congested"]) return moto::ble::TrafficLevel::Congested;
  if ([name isEqualToString:@"severe"]) return moto::ble::TrafficLevel::Severe;
  return moto::ble::TrafficLevel::Unknown;
}

moto::ble::NavigationSnapshot SnapshotFromInput(
    MotoBLENavigationSnapshotInput *input) {
  moto::ble::NavigationSnapshot snapshot;
  snapshot.state = NavigationStateFromName(input.stateName);
  snapshot.network = NetworkStateFromName(input.networkName);
  snapshot.display_page = DisplayPageFromName(input.displayPageName);
  snapshot.maneuver = ManeuverFromName(input.maneuverName);
  snapshot.traffic = TrafficFromName(input.trafficName);
  if (input.hasDestination) {
    snapshot.flags |= moto::ble::NavigationHasDestination;
  }
  if (input.hasFix) snapshot.flags |= moto::ble::NavigationHasFix;
  if (input.gnssStale) snapshot.flags |= moto::ble::NavigationGnssStale;
  if (input.offRoute) snapshot.flags |= moto::ble::NavigationOffRoute;
  if (input.routeRequestInFlight) {
    snapshot.flags |= moto::ble::NavigationRouteRequestInFlight;
  }
  if (input.trafficRequestInFlight) {
    snapshot.flags |= moto::ble::NavigationTrafficRequestInFlight;
  }
  if (input.hasRouteView) snapshot.flags |= moto::ble::NavigationHasRouteView;
  if (input.maneuverName.length > 0 &&
      ![input.maneuverName isEqualToString:@"unknown"]) {
    snapshot.flags |= moto::ble::NavigationHasNextManeuver;
  }
  snapshot.route_token = input.routeToken;
  snapshot.route_generation = input.routeGeneration;
  snapshot.maneuver_id = input.maneuverID;
  snapshot.distance_to_maneuver_m = input.distanceToManeuverM;
  snapshot.remaining_distance_m = input.remainingDistanceM;
  snapshot.remaining_duration_s = input.remainingDurationS;
  snapshot.route_progress_m = input.routeProgressM;
  snapshot.total_distance_m = input.totalDistanceM;
  snapshot.speed_deci_kph = input.speedDeciKPH;
  snapshot.speed_limit_kph = input.speedLimitKPH;
  snapshot.heading_cdeg = input.headingCentiDegrees;
  snapshot.accuracy_dm = input.accuracyDecimeters;
  snapshot.cross_track_dm = input.crossTrackDecimeters;
  snapshot.roundabout_exit = input.roundaboutExit;
  snapshot.road_name = input.roadName.UTF8String ?: "";
  snapshot.instruction = input.instructionText.UTF8String ?: "";
  return snapshot;
}

std::string Utf8Prefix(NSString *value, std::size_t maximum_bytes) {
  std::string bytes = value.UTF8String ?: "";
  if (bytes.size() <= maximum_bytes) return bytes;

  // The BLE payload uses byte-sized UTF-8 lengths.  Trim on a code-point
  // boundary so long Chinese titles cannot make the shared decoder reject an
  // otherwise valid MediaState.
  std::size_t end = maximum_bytes;
  while (end > 0 &&
         (static_cast<unsigned char>(bytes[end]) & 0xC0U) == 0x80U) {
    --end;
  }
  bytes.resize(end);
  return bytes;
}

moto::ble::MediaState MediaStateFromInput(MotoBLEMediaStateInput *input) {
  moto::ble::MediaState state;
  if (input.connected) state.flags |= moto::ble::MediaConnected;
  if (input.playing) state.flags |= moto::ble::MediaPlaying;
  if (input.likeAvailable) state.flags |= moto::ble::MediaLikeAvailable;
  if (input.liked) state.flags |= moto::ble::MediaLiked;
  state.track_token = input.trackToken;
  state.position_s = input.positionSeconds;
  state.duration_s = input.durationSeconds;
  state.source_name = Utf8Prefix(input.sourceName, 31);
  state.track_title = Utf8Prefix(input.trackTitle, 63);
  state.artist_name = Utf8Prefix(input.artistName, 47);
  return state;
}

moto::ble::MapRoadClass MapRoadClassFromName(NSString *name) {
  if ([name isEqualToString:@"motorway"]) return moto::ble::MapRoadClass::Motorway;
  if ([name isEqualToString:@"primary"]) return moto::ble::MapRoadClass::Primary;
  if ([name isEqualToString:@"secondary"]) return moto::ble::MapRoadClass::Secondary;
  if ([name isEqualToString:@"residential"]) return moto::ble::MapRoadClass::Residential;
  if ([name isEqualToString:@"service"]) return moto::ble::MapRoadClass::Service;
  return moto::ble::MapRoadClass::Other;
}

moto::ble::MapBuildingClass MapBuildingClassFromName(NSString *name) {
  if ([name isEqualToString:@"landmark"]) return moto::ble::MapBuildingClass::Landmark;
  if ([name isEqualToString:@"parking"]) return moto::ble::MapBuildingClass::Parking;
  return moto::ble::MapBuildingClass::Generic;
}

moto::ble::MapScene MapSceneFromInput(MotoBLEMapSceneInput *input) {
  moto::ble::MapScene scene;
  scene.scene_revision = input.sceneRevision;
  scene.view_origin = {input.originLatitudeE6, input.originLongitudeE6};
  scene.radius_m = input.radiusM;
  scene.roads.reserve(input.roads.count);
  for (MotoBLEMapRoadInput *source in input.roads) {
    moto::ble::MapRoadPolyline road;
    road.road_class = MapRoadClassFromName(source.className);
    road.points.reserve(source.points.count);
    for (MotoBLEMapPointInput *point in source.points) {
      road.points.push_back({point.latitudeE6, point.longitudeE6});
    }
    scene.roads.push_back(std::move(road));
  }
  scene.buildings.reserve(input.buildings.count);
  for (MotoBLEMapBuildingInput *source in input.buildings) {
    moto::ble::MapBuildingFootprint building;
    building.building_class = MapBuildingClassFromName(source.className);
    building.points.reserve(source.points.count);
    for (MotoBLEMapPointInput *point in source.points) {
      building.points.push_back({point.latitudeE6, point.longitudeE6});
    }
    scene.buildings.push_back(std::move(building));
  }
  return scene;
}

NSArray<NSData *> *EncodeMessage(CodecStorage *storage,
                                 const moto::ble::Message &message,
                                 std::uint8_t flags,
                                 NSError **error) {
  auto payload = moto::ble::encode_message(message);
  if (!payload.ok()) {
    if (error != nullptr) *error = ProtocolError(payload.error, payload.offset);
    return nil;
  }
  auto frames = moto::ble::fragment_message(
      moto::ble::message_type(message), storage->sequence.next(),
      moto::ble::ByteView(payload.value), storage->maximum_frame_size, flags);
  if (!frames.ok()) {
    if (error != nullptr) *error = ProtocolError(frames.error, frames.offset);
    return nil;
  }

  NSMutableArray<NSData *> *result =
      [NSMutableArray arrayWithCapacity:frames.value.size()];
  for (const auto &frame : frames.value) {
    [result addObject:[NSData dataWithBytes:frame.data() length:frame.size()]];
  }
  return result;
}

moto::ble::ConnectionStatus PhoneConnectionStatus(
    CodecStorage *storage,
    moto::ble::ConnectionState state,
    std::uint32_t session_id) {
  moto::ble::ConnectionStatus status;
  status.role = moto::ble::EndpointRole::Phone;
  status.state = state;
  status.capabilities = moto::ble::CapabilityNavigation |
                        moto::ble::CapabilityRouteGeometry |
                        moto::ble::CapabilityTraffic |
                        moto::ble::CapabilityMediaState |
                        moto::ble::CapabilityTouchCommands |
                        moto::ble::CapabilityMusicCommands |
                        moto::ble::CapabilityCommandAck |
                        moto::ble::CapabilityMapScene;
  status.session_id = session_id;
  status.max_frame_size =
      static_cast<std::uint16_t>(storage->maximum_frame_size);
  status.heartbeat_interval_ms = 1'000;
  return status;
}

}  // namespace

@implementation MotoBLENavigationSnapshotInput

- (instancetype)init {
  self = [super init];
  if (self) {
    _stateName = @"idle";
    _networkName = @"offline";
    _displayPageName = @"navigation";
    _maneuverName = @"unknown";
    _trafficName = @"unknown";
    _roadName = @"";
    _instructionText = @"";
  }
  return self;
}

@end

@implementation MotoBLEMediaStateInput

- (instancetype)init {
  self = [super init];
  if (self) {
    _sourceName = @"APPLE MUSIC";
    _trackTitle = @"";
    _artistName = @"";
  }
  return self;
}

@end

@implementation MotoBLEMapPointInput
@end

@implementation MotoBLEMapRoadInput
- (instancetype)init {
  self = [super init];
  if (self) {
    _className = @"other";
    _points = @[];
  }
  return self;
}
@end

@implementation MotoBLEMapBuildingInput
- (instancetype)init {
  self = [super init];
  if (self) {
    _className = @"generic";
    _points = @[];
  }
  return self;
}
@end

@implementation MotoBLEMapSceneInput
- (instancetype)init {
  self = [super init];
  if (self) {
    _roads = @[];
    _buildings = @[];
  }
  return self;
}
@end

@interface MotoBLEDeviceCommand ()
@property(nonatomic, readwrite) uint16_t sequence;
@property(nonatomic, readwrite) BOOL ackRequested;
@property(nonatomic, readwrite) uint8_t kind;
@property(nonatomic, readwrite) uint16_t commandID;
@property(nonatomic, readwrite) uint8_t page;
@property(nonatomic, readwrite) uint16_t x;
@property(nonatomic, readwrite) uint16_t y;
@property(nonatomic, readwrite) uint32_t eventTimeMs;
@end

@implementation MotoBLEDeviceCommand
@end

@interface MotoBLEConnectionStatus ()
@property(nonatomic, readwrite) uint8_t role;
@property(nonatomic, readwrite) uint8_t state;
@property(nonatomic, readwrite) uint8_t minimumVersion;
@property(nonatomic, readwrite) uint8_t maximumVersion;
@property(nonatomic, readwrite) uint32_t capabilities;
@property(nonatomic, readwrite) uint32_t sessionID;
@property(nonatomic, readwrite) uint16_t maximumFrameSize;
@property(nonatomic, readwrite) uint16_t heartbeatIntervalMs;
@end

@implementation MotoBLEConnectionStatus
@end

@interface MotoBLEHeartbeat ()
@property(nonatomic, readwrite) uint32_t sessionID;
@end

@implementation MotoBLEHeartbeat
@end

@interface MotoBLEInboundMessage ()
@property(nonatomic, readwrite) uint16_t sequence;
@property(nonatomic, readwrite) BOOL ackRequested;
@property(nonatomic, readwrite) BOOL complete;
@property(nonatomic, readwrite) BOOL duplicate;
@property(nonatomic, readwrite, nullable) MotoBLEConnectionStatus *connectionStatus;
@property(nonatomic, readwrite, nullable) MotoBLEHeartbeat *heartbeat;
@property(nonatomic, readwrite, nullable) MotoBLEDeviceCommand *deviceCommand;
@end

@implementation MotoBLEInboundMessage
@end

@implementation MotoBLEProtocolCodec {
  void *_storage;
}

+ (NSString *)serviceUUIDString {
  return [NSString stringWithUTF8String:moto::ble::kServiceUuid];
}

+ (NSString *)phoneToDeviceUUIDString {
  return [NSString stringWithUTF8String:moto::ble::kPhoneToDeviceUuid];
}

+ (NSString *)deviceToPhoneUUIDString {
  return [NSString stringWithUTF8String:moto::ble::kDeviceToPhoneUuid];
}

- (instancetype)initWithMaximumFrameSize:(NSUInteger)maximumFrameSize {
  self = [super init];
  if (self) {
    _storage = new CodecStorage(maximumFrameSize);
  }
  return self;
}

- (void)setMaximumFrameSize:(NSUInteger)maximumFrameSize {
  auto *storage = static_cast<CodecStorage *>(_storage);
  storage->maximum_frame_size = std::clamp<std::size_t>(
      maximumFrameSize, moto::ble::kFrameOverhead + 1,
      moto::ble::kMaxBleAttributeValueSize);
}

- (void)resetInboundState {
  auto *storage = static_cast<CodecStorage *>(_storage);
  storage->reassembler.reset();
}

- (void)dealloc {
  delete static_cast<CodecStorage *>(_storage);
}

- (NSArray<NSData *> *)encodePhoneStartingWithSessionID:(uint32_t)sessionID
                                                   error:(NSError **)error {
  auto *storage = static_cast<CodecStorage *>(_storage);
  const auto status = PhoneConnectionStatus(
      storage, moto::ble::ConnectionState::Starting, sessionID);
  return EncodeMessage(storage, moto::ble::Message{status}, 0, error);
}

- (NSArray<NSData *> *)encodePhoneReadyWithSessionID:(uint32_t)sessionID
                                                error:(NSError **)error {
  auto *storage = static_cast<CodecStorage *>(_storage);
  const auto status = PhoneConnectionStatus(
      storage, moto::ble::ConnectionState::Ready, sessionID);
  return EncodeMessage(storage, moto::ble::Message{status}, 0, error);
}

- (NSArray<NSData *> *)encodeHeartbeatWithSessionID:(uint32_t)sessionID
                                         monotonicMs:(uint32_t)monotonicMs
                                               error:(NSError **)error {
  auto *storage = static_cast<CodecStorage *>(_storage);
  moto::ble::Heartbeat heartbeat;
  heartbeat.session_id = sessionID;
  heartbeat.monotonic_ms = monotonicMs;
  heartbeat.status_flags = 0;
  return EncodeMessage(storage, moto::ble::Message{heartbeat}, 0, error);
}

- (NSArray<NSData *> *)encodeAckForSequence:(uint16_t)sequence
                                     status:(uint8_t)status
                                  commandID:(uint16_t)commandID
                                      error:(NSError **)error {
  auto *storage = static_cast<CodecStorage *>(_storage);
  moto::ble::Ack ack;
  ack.acknowledged_sequence = sequence;
  ack.status = static_cast<moto::ble::AckStatus>(status);
  ack.command_id = commandID;
  return EncodeMessage(storage, moto::ble::Message{ack}, 0, error);
}

- (NSArray<NSData *> *)encodeNavigationSnapshot:(MotoBLENavigationSnapshotInput *)input
                                            error:(NSError **)error {
  auto *storage = static_cast<CodecStorage *>(_storage);
  const moto::ble::NavigationSnapshot snapshot = SnapshotFromInput(input);
  return EncodeMessage(storage, moto::ble::Message{snapshot}, 0, error);
}

- (NSArray<NSData *> *)encodeRouteGeometryFromSnapshot:(MotoNavCoreSnapshot *)input
                                                   error:(NSError **)error {
  if (!input.hasRouteView || input.routeViewPoints.count == 0 ||
      input.routeID.length == 0) {
    return @[];
  }
  auto *storage = static_cast<CodecStorage *>(_storage);
  moto::ble::RouteGeometry geometry;
  geometry.route_token = moto::ble::route_token(input.routeID.UTF8String ?: "");
  geometry.route_generation = input.routeGeneration;
  geometry.total_point_count = static_cast<std::uint16_t>(
      std::min<NSUInteger>(input.routeViewPoints.count,
                           moto::ble::kMaxRoutePointsPerChunk));
  geometry.view_origin = {
      CoordinateE6(input.routeViewOriginLatitudeDeg),
      CoordinateE6(input.routeViewOriginLongitudeDeg)};
  geometry.points.reserve(geometry.total_point_count);
  for (NSUInteger index = 0; index < geometry.total_point_count; ++index) {
    MotoNavPointValue *point = input.routeViewPoints[index];
    geometry.points.push_back(
        {CoordinateE6(point.latitudeDeg), CoordinateE6(point.longitudeDeg)});
  }
  return EncodeMessage(storage, moto::ble::Message{geometry}, 0, error);
}

- (NSArray<NSData *> *)encodeMediaState:(MotoBLEMediaStateInput *)input
                                  error:(NSError **)error {
  auto *storage = static_cast<CodecStorage *>(_storage);
  return EncodeMessage(storage,
                       moto::ble::Message{MediaStateFromInput(input)},
                       0, error);
}

- (NSArray<NSData *> *)encodeMapScene:(MotoBLEMapSceneInput *)input
                                 error:(NSError **)error {
  auto *storage = static_cast<CodecStorage *>(_storage);
  return EncodeMessage(storage,
                       moto::ble::Message{MapSceneFromInput(input)},
                       moto::ble::AckRequested, error);
}

- (NSData *)encodeMapScenePayloadForTesting:(MotoBLEMapSceneInput *)input
                                       error:(NSError **)error {
  const auto payload =
      moto::ble::encode_message(moto::ble::Message{MapSceneFromInput(input)});
  if (!payload.ok()) {
    if (error != nullptr) *error = ProtocolError(payload.error, payload.offset);
    return nil;
  }
  return [NSData dataWithBytes:payload.value.data() length:payload.value.size()];
}

- (NSData *)encodeNavigationPayloadForGoldenCheck:(MotoBLENavigationSnapshotInput *)input
                                              error:(NSError **)error {
  const auto payload =
      moto::ble::encode_message(moto::ble::Message{SnapshotFromInput(input)});
  if (!payload.ok()) {
    if (error != nullptr) *error = ProtocolError(payload.error, payload.offset);
    return nil;
  }
  return [NSData dataWithBytes:payload.value.data() length:payload.value.size()];
}

- (MotoBLEInboundMessage *)pushDeviceFrame:(NSData *)frame
                              receivedAtMs:(uint64_t)receivedAtMs
                                     error:(NSError **)error {
  auto *storage = static_cast<CodecStorage *>(_storage);
  auto result = storage->reassembler.push(
      moto::ble::ByteView(static_cast<const std::uint8_t *>(frame.bytes), frame.length),
      receivedAtMs);
  if (!result.complete()) {
    if (result.state == moto::ble::ReassemblyState::InProgress ||
        result.state == moto::ble::ReassemblyState::DuplicateFragment ||
        result.state == moto::ble::ReassemblyState::DuplicateMessage) {
      MotoBLEInboundMessage *value = [[MotoBLEInboundMessage alloc] init];
      value.sequence = result.message.sequence;
      value.ackRequested =
          (result.message.flags & moto::ble::AckRequested) != 0U;
      value.complete = NO;
      value.duplicate =
          result.state == moto::ble::ReassemblyState::DuplicateMessage;
      return value;
    }
    if (error != nullptr) *error = ProtocolError(result.error, result.error_offset);
    return nil;
  }

  auto decoded = moto::ble::decode_message(
      result.message.type, moto::ble::ByteView(result.message.payload));
  if (!decoded.ok()) {
    if (error != nullptr) *error = ProtocolError(decoded.error, decoded.offset);
    return nil;
  }
  MotoBLEInboundMessage *inbound = [[MotoBLEInboundMessage alloc] init];
  inbound.sequence = result.message.sequence;
  inbound.ackRequested =
      (result.message.flags & moto::ble::AckRequested) != 0U;
  inbound.complete = YES;
  inbound.duplicate = NO;

  if (const auto *status =
          std::get_if<moto::ble::ConnectionStatus>(&decoded.value)) {
    MotoBLEConnectionStatus *value = [[MotoBLEConnectionStatus alloc] init];
    value.role = static_cast<uint8_t>(status->role);
    value.state = static_cast<uint8_t>(status->state);
    value.minimumVersion = status->minimum_version;
    value.maximumVersion = status->maximum_version;
    value.capabilities = status->capabilities;
    value.sessionID = status->session_id;
    value.maximumFrameSize = status->max_frame_size;
    value.heartbeatIntervalMs = status->heartbeat_interval_ms;
    inbound.connectionStatus = value;
    return inbound;
  }

  if (const auto *heartbeat =
          std::get_if<moto::ble::Heartbeat>(&decoded.value)) {
    MotoBLEHeartbeat *value = [[MotoBLEHeartbeat alloc] init];
    value.sessionID = heartbeat->session_id;
    inbound.heartbeat = value;
    return inbound;
  }

  const auto *command = std::get_if<moto::ble::DeviceCommand>(&decoded.value);
  if (command == nullptr) return inbound;

  MotoBLEDeviceCommand *value = [[MotoBLEDeviceCommand alloc] init];
  value.sequence = result.message.sequence;
  value.ackRequested = inbound.ackRequested;
  value.kind = static_cast<uint8_t>(command->kind);
  value.commandID = command->command_id;
  value.page = static_cast<uint8_t>(command->page);
  value.x = command->x;
  value.y = command->y;
  value.eventTimeMs = command->event_time_ms;
  inbound.deviceCommand = value;
  return inbound;
}

- (uint32_t)routeTokenForRouteID:(NSString *)routeID {
  const char *bytes = routeID.UTF8String ?: "";
  return moto::ble::route_token(std::string_view(bytes));
}

@end
