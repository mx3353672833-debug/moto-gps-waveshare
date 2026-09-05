#include <cstdint>

#include "ble_nav_transport_nimble.h"
#include "board_port.h"
#include "esp_err.h"
#include "esp_log.h"
#include "esp_sleep.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "moto_nav_presenter.hpp"
#include "moto_nav_ui.h"
#include "motion_heading_sensor.h"
#include "phone_nav_bridge.h"

namespace {
constexpr char kTag[] = "moto_gps";
constexpr std::uint64_t kPowerHoldMs = 3'000;

moto::ble::AckStatus receive_phone_message(
    const moto::ble::ReassembledMessage& message, void* context) {
  return static_cast<PhoneNavBridge*>(context)->on_message(message);
}

void update_phone_link(bool active, void* context) {
  static_cast<PhoneNavBridge*>(context)->on_link_state(active);
}

void update_motion_heading(float heading_rate_dps, std::uint64_t sample_ms,
                           void* context) {
  static_cast<PhoneNavBridge*>(context)->on_imu_sample(heading_rate_dps,
                                                       sample_ms);
}

void demo_tick_task(void* context) {
  auto* bridge = static_cast<PhoneNavBridge*>(context);
  while (true) {
    const auto now_ms = static_cast<std::uint64_t>(esp_timer_get_time()) /
                        1'000U;
    bridge->update_demo(now_ms);
    // Match the display and route interpolation at 40 Hz. Keeping all three
    // clocks phase-compatible avoids periodic long frame gaps.
    vTaskDelay(pdMS_TO_TICKS(25));
  }
}

void power_button_task(void*) {
  std::uint64_t pressed_since_ms = 0;
  while (true) {
    const std::uint64_t now_ms =
        static_cast<std::uint64_t>(esp_timer_get_time()) / 1'000U;
    if (!board_port_power_button_pressed()) {
      pressed_since_ms = 0;
      vTaskDelay(pdMS_TO_TICKS(20));
      continue;
    }
    if (pressed_since_ms == 0) {
      pressed_since_ms = now_ms;
    } else if (now_ms - pressed_since_ms >= kPowerHoldMs) {
      ESP_LOGI(kTag, "PWR held for 3 seconds; requesting shutdown");
      if (board_port_lock(UINT32_MAX)) {
        moto_nav_ui_show_power_off_screen();
        board_port_unlock();
      }
      vTaskDelay(pdMS_TO_TICKS(300));
      const esp_err_t result = board_port_power_off();
      if (result != ESP_OK) {
        ESP_LOGE(kTag, "AXP2101 software power-off failed: %s",
                 esp_err_to_name(result));
      }

      // AXP2101 should remove the switched rails here. If this particular
      // board remains alive while USB is attached, blank the panel and enter
      // deep sleep after key release. GPIO3 high wakes it on the next press.
      vTaskDelay(pdMS_TO_TICKS(500));
      while (board_port_power_button_pressed()) {
        vTaskDelay(pdMS_TO_TICKS(20));
      }
      ESP_ERROR_CHECK(esp_sleep_enable_ext1_wakeup_io(
          1ULL << 3, ESP_EXT1_WAKEUP_ANY_HIGH));
      esp_deep_sleep_start();
    }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
}
} // namespace

extern "C" void app_main(void) {
  static_assert(MOTO_DISPLAY_WIDTH == MOTO_UI_CANVAS_WIDTH &&
                    MOTO_DISPLAY_HEIGHT == MOTO_UI_CANVAS_HEIGHT,
                "The board and shared UI canvas must have one resolution");
  static_assert(LV_COLOR_DEPTH == MOTO_DISPLAY_BITS_PER_PIXEL,
                "Firmware and Web must both build the shared UI as RGB565");

  ESP_LOGI(kTag, "starting %dx%d RGB565 firmware target",
           MOTO_DISPLAY_WIDTH, MOTO_DISPLAY_HEIGHT);

  const esp_err_t init_result = board_port_init();
  if (init_result != ESP_OK) {
    ESP_LOGE(kTag, "board initialization stopped before shared UI startup: %s",
             esp_err_to_name(init_result));
    vTaskDelete(nullptr);
    return;
  }

  lv_display_t *const display = board_port_get_display();
  if (display == nullptr) {
    ESP_LOGE(kTag, "board port returned no LVGL display");
    vTaskDelete(nullptr);
    return;
  }

  if (lv_display_get_horizontal_resolution(display) != MOTO_DISPLAY_WIDTH ||
      lv_display_get_vertical_resolution(display) != MOTO_DISPLAY_HEIGHT ||
      lv_display_get_color_format(display) != LV_COLOR_FORMAT_RGB565) {
    ESP_LOGE(kTag, "board display violates the shared RGB565 portability "
                   "contract");
    vTaskDelete(nullptr);
    return;
  }

  if (!board_port_lock(UINT32_MAX)) {
    ESP_LOGE(kTag, "could not acquire LVGL lock");
    vTaskDelete(nullptr);
    return;
  }

  moto_nav_ui_show_boot_screen();
  // Commit the deliberately black first animation frame while the physical
  // panel is still hidden, then reveal it.  This removes the white frame that
  // used to leak from LVGL's default startup screen.
  lv_refr_now(display);
  const esp_err_t reveal_result = board_port_reveal_display();
  if (reveal_result != ESP_OK) {
    ESP_LOGE(kTag, "could not reveal boot animation: %s",
             esp_err_to_name(reveal_result));
  }
  board_port_unlock();

  // Give the display worker enough time to commit the monochrome power-on
  // frame before constructing the full production UI.
  vTaskDelay(pdMS_TO_TICKS(1'250));

  if (!board_port_lock(UINT32_MAX)) {
    ESP_LOGE(kTag, "could not reacquire LVGL lock after boot screen");
    vTaskDelete(nullptr);
    return;
  }

  moto_nav_ui_create();
  static moto::nav::NavPresenter presenter;
  static PhoneNavBridge phone_bridge(presenter);
  static BleNavTransport transport;
  // NavSnapshot contains the complete bounded roads/buildings window and is
  // several kilobytes.  A temporary here inflates app_main's stack frame for
  // the entire display/BSP startup call chain, which can trip the FreeRTOS
  // main-task canary before this line is even reached.  Keep the immutable
  // initial state in static storage just like the bridge's retained snapshots.
  static const moto::nav::NavSnapshot initial_snapshot{};
  presenter.update(initial_snapshot);
  presenter.apply_to_lvgl();
  moto_nav_ui_set_music_page_enabled(0);
  phone_bridge.install_ui_callbacks();
  board_port_unlock();

  if (!phone_bridge.start_renderer()) {
    ESP_LOGE(kTag, "UI renderer startup failed; BLE was not started");
    vTaskDelete(nullptr);
    return;
  }

  phone_bridge.set_sender(BleNavTransport::send_from_bridge, &transport);
  transport.set_callbacks(receive_phone_message, update_phone_link,
                          &phone_bridge);
  const esp_err_t ble_result = transport.start();
  if (ble_result != ESP_OK) {
    ESP_LOGE(kTag, "BLE startup failed; display remains in offline mode: %s",
             esp_err_to_name(ble_result));
  }

  static MotionHeadingSensor motion_sensor;
  const esp_err_t motion_result = motion_sensor.start(update_motion_heading,
                                                      &phone_bridge);
  if (motion_result != ESP_OK) {
    ESP_LOGW(kTag, "QMI8658 heading assist could not start: %s",
             esp_err_to_name(motion_result));
  }

  // Demo generation fills the retained snapshot in place, but geometry and
  // LVGL projection still use deeper C++ call frames than a trivial task.
  if (xTaskCreate(demo_tick_task, "moto_demo", 6'144, &phone_bridge, 2,
                  nullptr) != pdPASS) {
    ESP_LOGW(kTag, "navigation demo task could not start");
  }
  if (xTaskCreate(power_button_task, "moto_power", 3'072, nullptr, 3,
                  nullptr) != pdPASS) {
    ESP_LOGW(kTag, "PWR long-hold task could not start");
  }

  ESP_LOGI(kTag,
           "iPhone BLE + QMI heading -> NavPresenter -> shared LVGL running");
  vTaskDelete(nullptr);
}
