#pragma once

#include <string>

#include "moto_nav_ui.h"

namespace moto::test {

void reset_phone_nav_bridge_probe();
void emit_page_change(moto_ui_page_t page);
void emit_music_command(moto_music_command_t command);
void emit_demo_change(bool enabled);
int phone_nav_bridge_apply_count();
int phone_nav_bridge_board_lock_count();
void phone_nav_bridge_set_board_lock_available(bool available);
std::string phone_nav_bridge_last_road_name();
uint16_t phone_nav_bridge_last_heading_deg();
uint8_t phone_nav_bridge_last_route_point_count();
uint8_t phone_nav_bridge_last_road_point_count();
uint8_t phone_nav_bridge_last_road_polyline_count();
uint8_t phone_nav_bridge_last_road_class();
uint8_t phone_nav_bridge_last_building_point_count();
uint8_t phone_nav_bridge_last_building_footprint_count();
uint8_t phone_nav_bridge_last_building_class();
moto_ui_phone_connection_t phone_nav_bridge_last_phone_connection();
bool phone_nav_bridge_music_page_enabled();
bool phone_nav_bridge_media_connected();
bool phone_nav_bridge_media_playing();
bool phone_nav_bridge_media_like_available();
std::string phone_nav_bridge_media_source();
std::string phone_nav_bridge_media_title();
std::string phone_nav_bridge_media_artist();
bool phone_nav_bridge_last_demo_active();
moto_maneuver_t phone_nav_bridge_last_maneuver();
uint32_t phone_nav_bridge_last_distance_to_maneuver_m();

}  // namespace moto::test
