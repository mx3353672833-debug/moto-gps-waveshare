#pragma once

#include <stdint.h>

#define MOTO_UI_ROUTE_POINT_CAPACITY 24
#define MOTO_UI_ROAD_POINT_CAPACITY 192
#define MOTO_UI_ROAD_POLYLINE_CAPACITY 24
#define MOTO_UI_BUILDING_POINT_CAPACITY 128
#define MOTO_UI_BUILDING_FOOTPRINT_CAPACITY 16
#define MOTO_UI_CANVAS_WIDTH 466
#define MOTO_UI_CANVAS_HEIGHT 466

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    MOTO_UI_ACQUIRING_FIX = 0,
    MOTO_UI_NAVIGATING,
    MOTO_UI_REROUTING,
    MOTO_UI_OFFLINE,
    MOTO_UI_ARRIVED,
} moto_ui_mode_t;

/**
 * Phone transport lifecycle is deliberately independent from internet
 * availability.  A phone can remain paired while its mobile network is
 * offline, so the empty navigation page must never infer BLE state from the
 * route provider's online flag.
 */
typedef enum {
    MOTO_UI_PHONE_OFFLINE = 0,
    MOTO_UI_PHONE_CONNECTING,
    MOTO_UI_PHONE_ONLINE,
} moto_ui_phone_connection_t;

typedef enum {
    MOTO_MANEUVER_STRAIGHT = 0,
    MOTO_MANEUVER_LEFT,
    MOTO_MANEUVER_RIGHT,
    MOTO_MANEUVER_SLIGHT_LEFT,
    MOTO_MANEUVER_SLIGHT_RIGHT,
    MOTO_MANEUVER_UTURN,
    MOTO_MANEUVER_ROUNDABOUT,
    MOTO_MANEUVER_ARRIVE,
} moto_maneuver_t;

typedef enum {
    MOTO_TRAFFIC_UNKNOWN = 0,
    MOTO_TRAFFIC_CLEAR,
    MOTO_TRAFFIC_SLOW,
    MOTO_TRAFFIC_CONGESTED,
    MOTO_TRAFFIC_SEVERE,
} moto_traffic_t;

typedef enum {
    MOTO_UI_PAGE_NAVIGATION = 0,
    MOTO_UI_PAGE_SPEED,
    MOTO_UI_PAGE_COMPASS,
    MOTO_UI_PAGE_MUSIC,
    MOTO_UI_PAGE_COUNT,
} moto_ui_page_t;

typedef enum {
    MOTO_MUSIC_PREVIOUS = 0,
    MOTO_MUSIC_TOGGLE_PLAYBACK,
    MOTO_MUSIC_NEXT,
    MOTO_MUSIC_LIKE,
} moto_music_command_t;

typedef struct {
    /* Native pixel coordinates in the single 466 x 466 shared canvas. */
    int16_t x;
    int16_t y;
} moto_ui_point_t;

/**
 * A span inside moto_ui_state_t::road_points. Background roads are kept as
 * separate polylines so unrelated streets are never joined by a fake line.
 */
typedef struct {
    uint8_t first_point_index;
    uint8_t point_count;
    uint8_t road_class;
} moto_ui_polyline_span_t;

typedef struct {
    uint8_t first_point_index;
    uint8_t point_count;
    uint8_t building_class;
} moto_ui_building_span_t;

typedef struct {
    const char *source_name;
    const char *track_title;
    const char *artist_name;
    uint8_t connected;
    uint8_t playing;
    uint8_t like_available;
    uint8_t liked;
} moto_music_state_t;

typedef void (*moto_music_command_callback_t)(moto_music_command_t command,
                                               void *context);
typedef void (*moto_page_change_callback_t)(moto_ui_page_t page,
                                             void *context);
typedef void (*moto_demo_change_callback_t)(uint8_t enabled,
                                             void *context);

typedef struct {
    moto_ui_page_t page;
    moto_ui_mode_t mode;
    moto_maneuver_t maneuver;
    moto_traffic_t traffic;
    uint32_t distance_to_maneuver_m;
    uint32_t remaining_distance_m;
    uint32_t remaining_time_s;
    uint8_t route_progress_percent;
    uint8_t gps_accuracy_m;
    uint8_t online;
    uint8_t has_destination;
    uint8_t route_request_in_flight;
    /* Geometry identity, used only to distinguish a reroute/map replacement
       from ordinary high-rate heading and position motion. */
    uint32_t route_identity;
    uint32_t route_generation;
    uint32_t map_scene_revision;
    uint16_t speed_kph;
    uint16_t speed_limit_kph;
    uint16_t heading_deg;
    uint8_t route_point_count;
    moto_ui_point_t route_points[MOTO_UI_ROUTE_POINT_CAPACITY];
    uint8_t road_point_count;
    moto_ui_point_t road_points[MOTO_UI_ROAD_POINT_CAPACITY];
    uint8_t road_polyline_count;
    moto_ui_polyline_span_t
        road_polylines[MOTO_UI_ROAD_POLYLINE_CAPACITY];
    uint8_t building_point_count;
    moto_ui_point_t building_points[MOTO_UI_BUILDING_POINT_CAPACITY];
    uint8_t building_footprint_count;
    moto_ui_building_span_t
        building_footprints[MOTO_UI_BUILDING_FOOTPRINT_CAPACITY];
    const char *road_name;
    const char *next_road_name;
} moto_ui_state_t;

void moto_nav_ui_create(void);
/** Draw the monochrome power-on wordmark before the full UI is created. */
void moto_nav_ui_show_boot_screen(void);
/** Draw the final monochrome frame before the PMIC removes power. */
void moto_nav_ui_show_power_off_screen(void);
void moto_nav_ui_set_state(const moto_ui_state_t *state);
/** Apply only high-rate route/heading motion to the currently visible page. */
void moto_nav_ui_set_motion_state(const moto_ui_state_t *state);
/** Drive the full-screen connection/ready lifecycle on the navigation page. */
void moto_nav_ui_set_phone_connection(
    moto_ui_phone_connection_t connection);
void moto_nav_ui_set_reduce_motion(uint8_t reduce_motion);
void moto_nav_ui_set_page(moto_ui_page_t page);
moto_ui_page_t moto_nav_ui_get_page(void);
void moto_nav_ui_set_page_change_callback(
    moto_page_change_callback_t callback,
    void *context);
void moto_nav_ui_set_music_state(const moto_music_state_t *state);
void moto_nav_ui_set_music_page_enabled(uint8_t enabled);
void moto_nav_ui_set_music_command_callback(
    moto_music_command_callback_t callback,
    void *context);
/** Mark the production navigation renderer as being fed by demo snapshots. */
void moto_nav_ui_set_demo_active(uint8_t enabled);
/** Long-pressing the navigation page toggles this test-build demo source. */
void moto_nav_ui_set_demo_change_callback(
    moto_demo_change_callback_t callback,
    void *context);

#ifdef __cplusplus
}
#endif
