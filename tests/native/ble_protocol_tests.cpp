#include "moto/ble_protocol/ble_protocol.hpp"

#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

using namespace moto::ble;

int failures = 0;

#define CHECK(condition)                                                   \
  do {                                                                     \
    if (!(condition)) {                                                    \
      std::cerr << __FILE__ << ':' << __LINE__                             \
                << " CHECK failed: " #condition << '\n';                 \
      ++failures;                                                          \
    }                                                                      \
  } while (false)

ConnectionStatus connection_fixture() {
  ConnectionStatus value;
  value.role = EndpointRole::Phone;
  value.state = ConnectionState::Ready;
  value.capabilities =
      CapabilityNavigation | CapabilityRouteGeometry | CapabilityTraffic |
      CapabilityMediaState | CapabilityTouchCommands |
      CapabilityMusicCommands | CapabilityCommandAck;
  value.session_id = 0x12345678U;
  value.max_frame_size = 185;
  value.heartbeat_interval_ms = 1'000;
  return value;
}

Heartbeat heartbeat_fixture() {
  return Heartbeat{0x12345678U, 0x10203040U, 0};
}

Ack ack_fixture() {
  return Ack{0x2345U, AckStatus::Duplicate, 0x3456U};
}

NavigationSnapshot navigation_fixture() {
  NavigationSnapshot value;
  value.state = NavigationState::Navigating;
  value.network = NetworkState::Online;
  value.display_page = DisplayPage::Navigation;
  value.maneuver = Maneuver::Right;
  value.traffic = TrafficLevel::Slow;
  value.flags = NavigationHasDestination | NavigationHasFix |
                NavigationHasNextManeuver | NavigationHasRouteView;
  value.route_token = route_token("web-beijing-fixture");
  value.route_generation = 0x01020304U;
  value.maneuver_id = 42;
  value.distance_to_maneuver_m = 376;
  value.remaining_distance_m = 12'345;
  value.remaining_duration_s = 1'020;
  value.route_progress_m = 2'100;
  value.total_distance_m = 14'445;
  value.speed_deci_kph = 483;
  value.speed_limit_kph = 50;
  value.heading_cdeg = 9'123;
  value.accuracy_dm = 38;
  value.cross_track_dm = 125;
  value.road_name = "东长安街";
  value.instruction = "前方路口右转";
  return value;
}

RouteGeometry geometry_fixture() {
  RouteGeometry value;
  value.route_token = route_token("web-beijing-fixture");
  value.route_generation = 0x01020304U;
  value.total_point_count = 4;
  value.view_origin = {39'922'596, 116'416'932};
  value.points = {
      {39'922'590, 116'416'920},
      {39'922'702, 116'417'285},
      {39'922'650, 116'417'200},
      {39'923'205, 116'418'160},
  };
  return value;
}

TrafficDeviation traffic_fixture() {
  TrafficDeviation value;
  value.route_token = route_token("web-beijing-fixture");
  value.route_generation = 0x01020304U;
  value.flags = TrafficChanged | OffRoute;
  value.observed_at_ms = 45'678;
  value.remaining_duration_s = 1'120;
  value.cross_track_dm = 476;
  value.segments = {
      {0, 850, TrafficLevel::FreeFlow},
      {850, 425, TrafficLevel::Congested},
      {1'275, 300, TrafficLevel::Severe},
  };
  return value;
}

MediaState media_fixture() {
  MediaState value;
  value.flags = MediaConnected | MediaPlaying | MediaLikeAvailable;
  value.track_token = 0x89ABCDEFU;
  value.position_s = 88;
  value.duration_s = 241;
  value.source_name = "Apple Music";
  value.track_title = "Midnight City";
  value.artist_name = "M83";
  return value;
}

MapScene map_scene_fixture() {
  MapScene value;
  value.scene_revision = 7;
  value.view_origin = {36'674'804, 117'122'449};
  value.radius_m = 650;
  value.roads = {
      {MapRoadClass::Residential,
       {{36'674'610, 117'122'105},
        {36'674'420, 117'122'260},
        {36'674'190, 117'122'515}}},
      {MapRoadClass::Service,
       {{36'674'950, 117'122'330}, {36'674'780, 117'122'690}}},
  };
  value.buildings = {
      {MapBuildingClass::Generic,
       {{36'674'840, 117'122'350},
        {36'674'910, 117'122'350},
        {36'674'910, 117'122'455},
        {36'674'840, 117'122'455}}},
  };
  return value;
}

MapScene full_map_scene_fixture() {
  MapScene value;
  value.scene_revision = 0x10203040U;
  value.view_origin = {36'674'804, 117'122'449};
  value.radius_m = 800;
  for (std::size_t road_index = 0;
       road_index < kMaxMapSceneRoads; ++road_index) {
    MapRoadPolyline road;
    road.road_class = static_cast<MapRoadClass>(
        road_index % (static_cast<std::size_t>(MapRoadClass::Other) + 1));
    for (std::size_t point_index = 0; point_index < 8; ++point_index) {
      road.points.push_back({
          value.view_origin.latitude_e6 +
              static_cast<std::int32_t>(road_index * 75 + point_index * 21),
          value.view_origin.longitude_e6 +
              static_cast<std::int32_t>(road_index * 61) -
              static_cast<std::int32_t>(point_index * 37),
      });
    }
    value.roads.push_back(std::move(road));
  }
  for (std::size_t building_index = 0;
       building_index < kMaxMapSceneBuildings; ++building_index) {
    const std::int32_t y = static_cast<std::int32_t>(building_index * 95);
    const std::int32_t x = static_cast<std::int32_t>(building_index * 70);
    MapBuildingFootprint building;
    building.building_class = static_cast<MapBuildingClass>(
        building_index %
        (static_cast<std::size_t>(MapBuildingClass::Parking) + 1));
    building.points = {
        {value.view_origin.latitude_e6 + y,
         value.view_origin.longitude_e6 + x},
        {value.view_origin.latitude_e6 + y + 35,
         value.view_origin.longitude_e6 + x},
        {value.view_origin.latitude_e6 + y + 52,
         value.view_origin.longitude_e6 + x + 16},
        {value.view_origin.latitude_e6 + y + 52,
         value.view_origin.longitude_e6 + x + 50},
        {value.view_origin.latitude_e6 + y + 35,
         value.view_origin.longitude_e6 + x + 66},
        {value.view_origin.latitude_e6 + y,
         value.view_origin.longitude_e6 + x + 66},
        {value.view_origin.latitude_e6 + y - 17,
         value.view_origin.longitude_e6 + x + 50},
        {value.view_origin.latitude_e6 + y - 17,
         value.view_origin.longitude_e6 + x + 16},
    };
    value.buildings.push_back(std::move(building));
  }
  return value;
}

DeviceCommand command_fixture() {
  DeviceCommand value;
  value.kind = DeviceCommandKind::MusicNext;
  value.command_id = 0x1234U;
  value.page = DisplayPage::Music;
  value.event_time_ms = 0x10203040U;
  return value;
}

std::string hex(ByteView bytes) {
  std::ostringstream output;
  output << std::hex << std::setfill('0');
  for (std::size_t i = 0; i < bytes.size; ++i) {
    output << std::setw(2) << static_cast<unsigned>(bytes.data[i]);
  }
  return output.str();
}

std::string frame_hex(const std::vector<Bytes>& frames) {
  std::string output;
  for (std::size_t i = 0; i < frames.size(); ++i) {
    if (i != 0) {
      output.push_back(';');
    }
    output += hex(ByteView(frames[i]));
  }
  return output;
}

Bytes encode_or_fail(const Message& message) {
  const BytesResult encoded = encode_message(message);
  CHECK(encoded.ok());
  return encoded.value;
}

std::vector<Bytes> fragment_or_fail(MessageType type,
                                    std::uint16_t sequence,
                                    const Bytes& payload,
                                    std::size_t maximum_size,
                                    std::uint8_t flags = 0) {
  const FramesResult result = fragment_message(
      type, sequence, ByteView(payload), maximum_size, flags);
  CHECK(result.ok());
  return result.value;
}

std::unordered_map<std::string, std::string> read_golden() {
  std::ifstream input(MOTO_BLE_GOLDEN_FIXTURE);
  CHECK(input.good());
  std::unordered_map<std::string, std::string> values;
  std::string line;
  while (std::getline(input, line)) {
    if (line.empty() || line[0] == '#') {
      continue;
    }
    const std::size_t separator = line.find('=');
    CHECK(separator != std::string::npos);
    if (separator != std::string::npos) {
      values.emplace(line.substr(0, separator),
                     line.substr(separator + 1));
    }
  }
  return values;
}

void dump_golden() {
  const Bytes connection = encode_or_fail(Message{connection_fixture()});
  const Bytes navigation = encode_or_fail(Message{navigation_fixture()});
  const Bytes geometry = encode_or_fail(Message{geometry_fixture()});
  const Bytes command = encode_or_fail(Message{command_fixture()});
  std::cout << "connection.payload=" << hex(ByteView(connection)) << '\n';
  std::cout << "connection.frame="
            << frame_hex(fragment_or_fail(MessageType::ConnectionStatus, 1,
                                          connection, 512))
            << '\n';
  std::cout << "navigation.payload=" << hex(ByteView(navigation)) << '\n';
  std::cout << "geometry.payload=" << hex(ByteView(geometry)) << '\n';
  std::cout << "command.payload=" << hex(ByteView(command)) << '\n';
  std::cout << "command.frames20="
            << frame_hex(fragment_or_fail(MessageType::DeviceCommand,
                                          0x1234U, command, 20,
                                          AckRequested | Urgent))
            << '\n';
}

void test_uuid_contract() {
  CHECK(std::string(kServiceUuid) ==
        "7e57a000-b50c-4b6a-9c57-40a54e8e1000");
  CHECK(std::string(kPhoneToDeviceUuid) ==
        "7e57a001-b50c-4b6a-9c57-40a54e8e1000");
  CHECK(std::string(kDeviceToPhoneUuid) ==
        "7e57a002-b50c-4b6a-9c57-40a54e8e1000");
  CHECK(std::string(kServiceUuid) != std::string(kPhoneToDeviceUuid));
  CHECK(std::string(kPhoneToDeviceUuid) !=
        std::string(kDeviceToPhoneUuid));
  CHECK((kPhoneToDeviceGattRequirements & GattWrite) != 0U);
  CHECK((kPhoneToDeviceGattRequirements & GattWriteWithoutResponse) != 0U);
  CHECK((kPhoneToDeviceGattRequirements & GattNotify) == 0U);
  CHECK((kDeviceToPhoneGattRequirements & GattNotify) != 0U);
  CHECK((kDeviceToPhoneGattRequirements & GattCccd) != 0U);
  CHECK((kDeviceToPhoneGattRequirements & GattEncryptedPermission) != 0U);
}

void test_crc_and_sequence_primitives() {
  const std::string check = "123456789";
  CHECK(crc16_ccitt_false(ByteView(
            reinterpret_cast<const std::uint8_t*>(check.data()),
            check.size())) == 0x29B1U);
  CHECK(sequence_is_newer(2, 1));
  CHECK(!sequence_is_newer(1, 1));
  CHECK(sequence_is_newer(1, 0xFFFFU));
  CHECK(!sequence_is_newer(0xFFFFU, 1));
  SequenceGenerator sequence(0xFFFFU);
  CHECK(sequence.next() == 0xFFFFU);
  CHECK(sequence.next() == 1);
  sequence.reset(0);
  CHECK(sequence.next() == 1);
  CHECK(route_token("web-beijing-fixture") != 0);
  CHECK(route_token("web-beijing-fixture") ==
        route_token("web-beijing-fixture"));
}

void test_all_message_round_trips() {
  const std::vector<Message> messages = {
      Message{connection_fixture()},
      Message{heartbeat_fixture()},
      Message{ack_fixture()},
      Message{navigation_fixture()},
      Message{geometry_fixture()},
      Message{traffic_fixture()},
      Message{media_fixture()},
      Message{map_scene_fixture()},
      Message{command_fixture()},
  };
  for (const Message& message : messages) {
    const BytesResult encoded = encode_message(message);
    CHECK(encoded.ok());
    if (!encoded.ok()) {
      continue;
    }
    const MessageResult decoded =
        decode_message(message_type(message), ByteView(encoded.value));
    CHECK(decoded.ok());
    if (decoded.ok()) {
      CHECK(decoded.value == message);
    }
  }
}

void test_golden_vectors() {
  const auto expected = read_golden();
  const Bytes connection = encode_or_fail(Message{connection_fixture()});
  const Bytes navigation = encode_or_fail(Message{navigation_fixture()});
  const Bytes geometry = encode_or_fail(Message{geometry_fixture()});
  const Bytes command = encode_or_fail(Message{command_fixture()});

  CHECK(expected.at("connection.payload") == hex(ByteView(connection)));
  CHECK(expected.at("connection.frame") ==
        frame_hex(fragment_or_fail(MessageType::ConnectionStatus, 1,
                                   connection, 512)));
  CHECK(expected.at("navigation.payload") == hex(ByteView(navigation)));
  CHECK(expected.at("geometry.payload") == hex(ByteView(geometry)));
  CHECK(expected.at("command.payload") == hex(ByteView(command)));
  CHECK(expected.at("command.frames20") ==
        frame_hex(fragment_or_fail(MessageType::DeviceCommand, 0x1234U,
                                   command, 20,
                                   AckRequested | Urgent)));
}

void test_fragmentation_and_reassembly() {
  const Message original{navigation_fixture()};
  const Bytes payload = encode_or_fail(original);
  const std::vector<Bytes> frames = fragment_or_fail(
      MessageType::NavigationSnapshot, 7, payload, 20);
  CHECK(frames.size() > 2);

  std::size_t expected_offset = 0;
  Reassembler reassembler;
  for (std::size_t i = 0; i < frames.size(); ++i) {
    CHECK(frames[i].size() <= 20);
    const FrameResult decoded = decode_frame(ByteView(frames[i]));
    CHECK(decoded.ok());
    if (decoded.ok()) {
      CHECK(decoded.value.fragment_offset == expected_offset);
      CHECK(((decoded.value.flags & FrameStart) != 0U) == (i == 0));
      CHECK(((decoded.value.flags & FrameEnd) != 0U) ==
            (i + 1 == frames.size()));
      expected_offset += decoded.value.payload.size();
    }
    const ReassemblyResult assembled =
        reassembler.push(ByteView(frames[i]), 100 + i);
    CHECK(assembled.error == Error::None);
    if (i + 1 == frames.size()) {
      CHECK(assembled.state == ReassemblyState::Complete);
      CHECK(assembled.message.payload == payload);
      const MessageResult decoded_message = decode_message(
          assembled.message.type, ByteView(assembled.message.payload));
      CHECK(decoded_message.ok());
      if (decoded_message.ok()) {
        CHECK(decoded_message.value == original);
      }
    } else {
      CHECK(assembled.state == ReassemblyState::InProgress);
    }
  }
}

void test_full_map_scene_budget_and_reassembly() {
  const Message original{full_map_scene_fixture()};
  const Bytes payload = encode_or_fail(original);
  CHECK(payload.size() <= kDefaultMaxMessageSize);
  CHECK(payload.size() <= 2'200);
  // iOS commonly reports 182 bytes for Write Without Response after an
  // ATT_MTU of 185.  This is the complete characteristic value limit passed
  // to fragment_message, including the 12-byte MOTO frame overhead.
  const std::vector<Bytes> frames = fragment_or_fail(
      MessageType::MapScene, 0x4321U, payload, 182, AckRequested);
  CHECK(frames.size() > 1);
  Reassembler reassembler;
  ReassemblyResult result;
  for (std::size_t i = 0; i < frames.size(); ++i) {
    CHECK(frames[i].size() <= 182);
    result = reassembler.push(ByteView(frames[i]), 1'000 + i);
  }
  CHECK(result.complete());
  CHECK(result.message.type == MessageType::MapScene);
  CHECK((result.message.flags & AckRequested) != 0U);
  const MessageResult decoded =
      decode_message(result.message.type, ByteView(result.message.payload));
  CHECK(decoded.ok());
  if (decoded.ok()) {
    CHECK(decoded.value == original);
  }

  // The same logical payload must remain valid on the 20-byte legacy path;
  // it is inefficient but proves the generic offset/reassembly path does not
  // depend on a large MTU.
  const std::vector<Bytes> legacy_frames = fragment_or_fail(
      MessageType::MapScene, 0x4322U, payload, 20, AckRequested);
  CHECK(legacy_frames.size() > frames.size());
}

void test_crc_duplicate_gap_conflict_and_timeout() {
  const Bytes payload = encode_or_fail(Message{navigation_fixture()});
  const std::vector<Bytes> frames = fragment_or_fail(
      MessageType::NavigationSnapshot, 9, payload, 20);

  Bytes corrupt = frames.front();
  corrupt[kFrameHeaderSize] ^= 0x80U;
  CHECK(decode_frame(ByteView(corrupt)).error == Error::CrcMismatch);

  Reassembler duplicate;
  CHECK(duplicate.push(ByteView(frames[0]), 100).state ==
        ReassemblyState::InProgress);
  CHECK(duplicate.push(ByteView(frames[0]), 110).state ==
        ReassemblyState::DuplicateFragment);
  CHECK(duplicate.push(ByteView(frames[2]), 120).error ==
        Error::UnexpectedFragment);

  const FrameResult first = decode_frame(ByteView(frames[0]));
  CHECK(first.ok());
  Frame changed = first.value;
  changed.payload[0] ^= 1U;
  const BytesResult changed_bytes = encode_frame(changed);
  CHECK(changed_bytes.ok());
  CHECK(duplicate.push(ByteView(changed_bytes.value), 130).error ==
        Error::FragmentConflict);

  Reassembler missing_start;
  CHECK(missing_start.push(ByteView(frames[1]), 500).error ==
        Error::MissingStart);

  Reassembler timeout({kDefaultMaxMessageSize, 1'000, true});
  CHECK(timeout.push(ByteView(frames[0]), 1'000).state ==
        ReassemblyState::InProgress);
  CHECK(timeout.push(ByteView(frames[1]), 2'000).error ==
        Error::ReassemblyTimeout);
}

void test_supersession_duplicate_message_and_wrap() {
  const Bytes navigation = encode_or_fail(Message{navigation_fixture()});
  const std::vector<Bytes> old_frames = fragment_or_fail(
      MessageType::NavigationSnapshot, 10, navigation, 20);
  const Bytes heartbeat = encode_or_fail(Message{heartbeat_fixture()});
  const std::vector<Bytes> new_frames = fragment_or_fail(
      MessageType::Heartbeat, 11, heartbeat, 64);

  Reassembler reassembler;
  CHECK(reassembler.push(ByteView(old_frames[0]), 0).state ==
        ReassemblyState::InProgress);
  const ReassemblyResult newer =
      reassembler.push(ByteView(new_frames[0]), 1);
  CHECK(newer.state == ReassemblyState::Complete);
  CHECK(newer.dropped_incomplete);
  CHECK(reassembler.push(ByteView(new_frames[0]), 2).state ==
        ReassemblyState::DuplicateMessage);

  const std::vector<Bytes> final_sequence = fragment_or_fail(
      MessageType::Heartbeat, 0xFFFFU, heartbeat, 64);
  const std::vector<Bytes> wrapped_sequence = fragment_or_fail(
      MessageType::Heartbeat, 1, heartbeat, 64);
  Reassembler wrap;
  CHECK(wrap.push(ByteView(final_sequence[0]), 10).complete());
  CHECK(wrap.push(ByteView(wrapped_sequence[0]), 11).complete());
  CHECK(wrap.push(ByteView(final_sequence[0]), 12).error ==
        Error::StaleSequence);

  Reassembler multi_frame_duplicate;
  for (const Bytes& frame : old_frames) {
    (void)multi_frame_duplicate.push(ByteView(frame), 20);
  }
  for (const Bytes& frame : old_frames) {
    CHECK(multi_frame_duplicate.push(ByteView(frame), 21).state ==
          ReassemblyState::DuplicateMessage);
  }
}

void test_validation_failures() {
  Bytes revision = encode_or_fail(Message{heartbeat_fixture()});
  revision[0] = 2;
  CHECK(decode_message(MessageType::Heartbeat, ByteView(revision)).error ==
        Error::UnsupportedPayloadRevision);

  Bytes trailing = encode_or_fail(Message{heartbeat_fixture()});
  trailing.push_back(0);
  CHECK(decode_message(MessageType::Heartbeat, ByteView(trailing)).error ==
        Error::TrailingPayload);
  trailing.pop_back();
  trailing.pop_back();
  CHECK(decode_message(MessageType::Heartbeat, ByteView(trailing)).error ==
        Error::TruncatedPayload);

  NavigationSnapshot invalid_navigation = navigation_fixture();
  invalid_navigation.heading_cdeg = 36'000;
  CHECK(encode_message(Message{invalid_navigation}).error ==
        Error::OutOfRange);

  RouteGeometry invalid_geometry = geometry_fixture();
  invalid_geometry.view_origin.latitude_e6 = 91'000'000;
  CHECK(encode_message(Message{invalid_geometry}).error ==
        Error::OutOfRange);

  MapScene repeated_building_close = map_scene_fixture();
  repeated_building_close.buildings[0].points.push_back(
      repeated_building_close.buildings[0].points.front());
  CHECK(encode_message(Message{repeated_building_close}).error ==
        Error::OutOfRange);

  MapScene too_many_map_points = map_scene_fixture();
  too_many_map_points.roads[0].points.resize(
      kMaxMapSceneRoadPoints + 1,
      too_many_map_points.roads[0].points.front());
  CHECK(encode_message(Message{too_many_map_points}).error ==
        Error::TooManyItems);

  const Bytes map_scene = encode_or_fail(Message{map_scene_fixture()});
  CHECK(map_scene.size() < 185);
  CHECK(fragment_or_fail(MessageType::MapScene, 21, map_scene, 185,
                         AckRequested).size() >= 1);

  TrafficDeviation overlap = traffic_fixture();
  overlap.segments[1].start_offset_m = 849;
  CHECK(encode_message(Message{overlap}).error == Error::OutOfRange);

  DeviceCommand zero_command = command_fixture();
  zero_command.command_id = 0;
  CHECK(encode_message(Message{zero_command}).error == Error::OutOfRange);

  CHECK(decode_message(static_cast<MessageType>(0xFF),
                       ByteView(trailing)).error ==
        Error::UnknownMessageType);

  Heartbeat reserved_heartbeat = heartbeat_fixture();
  reserved_heartbeat.status_flags = 1;
  CHECK(encode_message(Message{reserved_heartbeat}).error ==
        Error::OutOfRange);
  CHECK(fragment_message(MessageType::Heartbeat, 0, ByteView(trailing),
                         20).error == Error::SequenceZero);
  CHECK(fragment_message(MessageType::Heartbeat, 1, ByteView(trailing),
                         kFrameOverhead).error == Error::InvalidArgument);
}

void test_link_watchdog() {
  LinkWatchdog watchdog(3'000);
  CHECK(!watchdog.armed());
  CHECK(!watchdog.expired(10'000));
  watchdog.note_valid_frame(5'000);
  CHECK(watchdog.armed());
  CHECK(watchdog.deadline_ms() == 8'000);
  CHECK(!watchdog.expired(7'999));
  CHECK(watchdog.expired(8'000));
  watchdog.reset();
  CHECK(!watchdog.armed());
}

}  // namespace

int main(int argc, char** argv) {
  if (argc == 2 && std::string(argv[1]) == "--dump-golden") {
    dump_golden();
    return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
  }

  test_uuid_contract();
  test_crc_and_sequence_primitives();
  test_all_message_round_trips();
  test_golden_vectors();
  test_fragmentation_and_reassembly();
  test_full_map_scene_budget_and_reassembly();
  test_crc_duplicate_gap_conflict_and_timeout();
  test_supersession_duplicate_message_and_wrap();
  test_validation_failures();
  test_link_watchdog();

  if (failures != 0) {
    std::cerr << failures << " BLE protocol checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All BLE protocol checks passed\n";
  return EXIT_SUCCESS;
}
