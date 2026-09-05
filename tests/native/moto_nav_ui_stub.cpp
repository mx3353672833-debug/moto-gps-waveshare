#include "moto_nav_ui_test_probe.hpp"

#include <string>

namespace {

int apply_count = 0;
moto_ui_state_t applied_state{};
std::string applied_road_name;
std::string applied_next_road_name;

}  // namespace

extern "C" void moto_nav_ui_create(void) {}
extern "C" void moto_nav_ui_show_boot_screen(void) {}

extern "C" void moto_nav_ui_set_state(const moto_ui_state_t* state) {
  if (state == nullptr) {
    return;
  }
  ++apply_count;
  applied_state = *state;
  applied_road_name = state->road_name == nullptr ? "" : state->road_name;
  applied_next_road_name =
      state->next_road_name == nullptr ? "" : state->next_road_name;
  applied_state.road_name =
      state->road_name == nullptr ? nullptr : applied_road_name.c_str();
  applied_state.next_road_name = state->next_road_name == nullptr
                                     ? nullptr
                                     : applied_next_road_name.c_str();
}

extern "C" void moto_nav_ui_set_motion_state(const moto_ui_state_t*) {}
extern "C" void moto_nav_ui_set_phone_connection(
    moto_ui_phone_connection_t) {}

extern "C" void moto_nav_ui_set_reduce_motion(uint8_t) {}
extern "C" void moto_nav_ui_set_page(moto_ui_page_t) {}
extern "C" moto_ui_page_t moto_nav_ui_get_page(void) {
  return MOTO_UI_PAGE_NAVIGATION;
}
extern "C" void moto_nav_ui_set_page_change_callback(
    moto_page_change_callback_t,
    void*) {}
extern "C" void moto_nav_ui_set_music_state(const moto_music_state_t*) {}
extern "C" void moto_nav_ui_set_music_page_enabled(uint8_t) {}
extern "C" void moto_nav_ui_set_music_command_callback(
    moto_music_command_callback_t,
    void*) {}
extern "C" void moto_nav_ui_set_demo_active(uint8_t) {}
extern "C" void moto_nav_ui_set_demo_change_callback(
    moto_demo_change_callback_t,
    void*) {}

namespace moto::test {

void reset_nav_ui_probe() {
  apply_count = 0;
  applied_state = {};
  applied_road_name.clear();
  applied_next_road_name.clear();
}

int nav_ui_apply_count() { return apply_count; }

const moto_ui_state_t& last_applied_nav_ui_state() {
  return applied_state;
}

}  // namespace moto::test
