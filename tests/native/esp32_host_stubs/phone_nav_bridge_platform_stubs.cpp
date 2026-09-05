#include "board_port.h"
#include "phone_nav_bridge_test_probe.hpp"

#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>

namespace {

std::recursive_mutex board_mutex;
std::mutex probe_mutex;
moto_page_change_callback_t page_callback = nullptr;
void* page_callback_context = nullptr;
moto_music_command_callback_t music_callback = nullptr;
void* music_callback_context = nullptr;
moto_demo_change_callback_t demo_callback = nullptr;
void* demo_callback_context = nullptr;
int apply_count = 0;
int board_lock_count = 0;
bool board_lock_available = true;
std::string last_road_name;
uint16_t last_heading_deg = 0;
uint8_t last_route_point_count = 0;
uint8_t last_road_point_count = 0;
uint8_t last_road_polyline_count = 0;
uint8_t last_road_class = 0;
uint8_t last_building_point_count = 0;
uint8_t last_building_footprint_count = 0;
uint8_t last_building_class = 0;
uint8_t last_demo_active = 0;
moto_maneuver_t last_maneuver = MOTO_MANEUVER_STRAIGHT;
uint32_t last_distance_to_maneuver_m = 0;
moto_ui_phone_connection_t last_phone_connection = MOTO_UI_PHONE_OFFLINE;
bool music_page_enabled = false;
bool media_connected = false;
bool media_playing = false;
bool media_like_available = false;
std::string media_source;
std::string media_title;
std::string media_artist;

}  // namespace

extern "C" int64_t esp_timer_get_time(void) {
  const auto elapsed = std::chrono::steady_clock::now().time_since_epoch();
  return std::chrono::duration_cast<std::chrono::microseconds>(elapsed).count();
}

extern "C" esp_err_t board_port_init(void) { return ESP_OK; }
extern "C" lv_display_t* board_port_get_display(void) { return nullptr; }

extern "C" bool board_port_lock(uint32_t) {
  {
    const std::lock_guard<std::mutex> lock(probe_mutex);
    ++board_lock_count;
    if (!board_lock_available) return false;
  }
  board_mutex.lock();
  return true;
}

extern "C" void board_port_unlock(void) { board_mutex.unlock(); }

extern "C" void moto_nav_ui_create(void) {}
extern "C" void moto_nav_ui_show_boot_screen(void) {}
extern "C" void moto_nav_ui_show_power_off_screen(void) {}

extern "C" void moto_nav_ui_set_state(const moto_ui_state_t* state) {
  if (state == nullptr) {
    return;
  }
  const std::lock_guard<std::mutex> lock(probe_mutex);
  ++apply_count;
  last_road_name = state->next_road_name == nullptr
                       ? ""
                       : state->next_road_name;
  last_heading_deg = state->heading_deg;
  last_route_point_count = state->route_point_count;
  last_road_point_count = state->road_point_count;
  last_road_polyline_count = state->road_polyline_count;
  last_road_class = state->road_polyline_count == 0
                        ? 0
                        : state->road_polylines[0].road_class;
  last_building_point_count = state->building_point_count;
  last_building_footprint_count = state->building_footprint_count;
  last_building_class = state->building_footprint_count == 0
                            ? 0
                            : state->building_footprints[0].building_class;
  last_maneuver = state->maneuver;
  last_distance_to_maneuver_m = state->distance_to_maneuver_m;
}

extern "C" void moto_nav_ui_set_motion_state(const moto_ui_state_t* state) {
  moto_nav_ui_set_state(state);
}

extern "C" void moto_nav_ui_set_phone_connection(
    moto_ui_phone_connection_t connection) {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  last_phone_connection = connection;
}

extern "C" void moto_nav_ui_set_reduce_motion(uint8_t) {}
extern "C" void moto_nav_ui_set_page(moto_ui_page_t) {}
extern "C" moto_ui_page_t moto_nav_ui_get_page(void) {
  return MOTO_UI_PAGE_NAVIGATION;
}

extern "C" void moto_nav_ui_set_page_change_callback(
    moto_page_change_callback_t callback,
    void* context) {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  page_callback = callback;
  page_callback_context = context;
}

extern "C" void moto_nav_ui_set_music_state(const moto_music_state_t* state) {
  if (state == nullptr) return;
  const std::lock_guard<std::mutex> lock(probe_mutex);
  media_connected = state->connected != 0;
  media_playing = state->playing != 0;
  media_like_available = state->like_available != 0;
  media_source = state->source_name == nullptr ? "" : state->source_name;
  media_title = state->track_title == nullptr ? "" : state->track_title;
  media_artist = state->artist_name == nullptr ? "" : state->artist_name;
}
extern "C" void moto_nav_ui_set_music_page_enabled(uint8_t enabled) {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  music_page_enabled = enabled != 0;
}

extern "C" void moto_nav_ui_set_music_command_callback(
    moto_music_command_callback_t callback,
    void* context) {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  music_callback = callback;
  music_callback_context = context;
}

extern "C" void moto_nav_ui_set_demo_active(uint8_t enabled) {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  last_demo_active = enabled;
}

extern "C" void moto_nav_ui_set_demo_change_callback(
    moto_demo_change_callback_t callback,
    void* context) {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  demo_callback = callback;
  demo_callback_context = context;
}

namespace moto::test {

void reset_phone_nav_bridge_probe() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  apply_count = 0;
  board_lock_count = 0;
  board_lock_available = true;
  last_road_name.clear();
  last_heading_deg = 0;
  last_route_point_count = 0;
  last_road_point_count = 0;
  last_road_polyline_count = 0;
  last_road_class = 0;
  last_building_point_count = 0;
  last_building_footprint_count = 0;
  last_building_class = 0;
  last_demo_active = 0;
  last_maneuver = MOTO_MANEUVER_STRAIGHT;
  last_distance_to_maneuver_m = 0;
  last_phone_connection = MOTO_UI_PHONE_OFFLINE;
  music_page_enabled = false;
  media_connected = false;
  media_playing = false;
  media_like_available = false;
  media_source.clear();
  media_title.clear();
  media_artist.clear();
}

void emit_page_change(moto_ui_page_t page) {
  moto_page_change_callback_t callback = nullptr;
  void* context = nullptr;
  {
    const std::lock_guard<std::mutex> lock(probe_mutex);
    callback = page_callback;
    context = page_callback_context;
  }
  if (callback == nullptr) {
    return;
  }

  // LVGL invokes input callbacks while its recursive board lock is held.
  const std::lock_guard<std::recursive_mutex> lock(board_mutex);
  callback(page, context);
}

void emit_music_command(moto_music_command_t command) {
  moto_music_command_callback_t callback = nullptr;
  void* context = nullptr;
  {
    const std::lock_guard<std::mutex> lock(probe_mutex);
    callback = music_callback;
    context = music_callback_context;
  }
  if (callback == nullptr) return;
  const std::lock_guard<std::recursive_mutex> lock(board_mutex);
  callback(command, context);
}

void emit_demo_change(bool enabled) {
  moto_demo_change_callback_t callback = nullptr;
  void* context = nullptr;
  {
    const std::lock_guard<std::mutex> lock(probe_mutex);
    callback = demo_callback;
    context = demo_callback_context;
  }
  if (callback == nullptr) return;
  const std::lock_guard<std::recursive_mutex> lock(board_mutex);
  callback(enabled ? 1U : 0U, context);
}

int phone_nav_bridge_apply_count() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return apply_count;
}

int phone_nav_bridge_board_lock_count() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return board_lock_count;
}

void phone_nav_bridge_set_board_lock_available(bool available) {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  board_lock_available = available;
}

std::string phone_nav_bridge_last_road_name() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_road_name;
}

uint16_t phone_nav_bridge_last_heading_deg() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_heading_deg;
}

uint8_t phone_nav_bridge_last_route_point_count() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_route_point_count;
}

uint8_t phone_nav_bridge_last_road_point_count() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_road_point_count;
}

uint8_t phone_nav_bridge_last_road_polyline_count() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_road_polyline_count;
}

uint8_t phone_nav_bridge_last_road_class() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_road_class;
}

uint8_t phone_nav_bridge_last_building_point_count() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_building_point_count;
}

uint8_t phone_nav_bridge_last_building_footprint_count() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_building_footprint_count;
}

uint8_t phone_nav_bridge_last_building_class() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_building_class;
}

moto_ui_phone_connection_t phone_nav_bridge_last_phone_connection() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_phone_connection;
}

bool phone_nav_bridge_music_page_enabled() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return music_page_enabled;
}

bool phone_nav_bridge_media_connected() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return media_connected;
}

bool phone_nav_bridge_media_playing() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return media_playing;
}

bool phone_nav_bridge_media_like_available() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return media_like_available;
}

std::string phone_nav_bridge_media_source() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return media_source;
}

std::string phone_nav_bridge_media_title() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return media_title;
}

std::string phone_nav_bridge_media_artist() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return media_artist;
}

bool phone_nav_bridge_last_demo_active() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_demo_active != 0;
}

moto_maneuver_t phone_nav_bridge_last_maneuver() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_maneuver;
}

uint32_t phone_nav_bridge_last_distance_to_maneuver_m() {
  const std::lock_guard<std::mutex> lock(probe_mutex);
  return last_distance_to_maneuver_m;
}

}  // namespace moto::test
