// SPDX-License-Identifier: Apache-2.0
// CO5300 initialization sequence and board integration are derived from
// Waveshare's esp32_s3_touch_amoled_1_75c BSP 3.0.0 (Apache-2.0).
// Source: https://github.com/waveshareteam/Waveshare-ESP32-components
// Modifications (2026): Maler X — hidden black startup, PSRAM DMA buffers,
// TE edge synchronization and AXP2101 shutdown integration.
// This file remains Apache-2.0; see LICENSES/Waveshare-Apache-2.0.txt.
#include "board_port.h"

#include <cstdint>

#include "bsp/display.h"
#include "bsp/esp-bsp.h"
#include "bsp/touch.h"
#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "driver/spi_master.h"
#include "esp_attr.h"
#include "esp_check.h"
#include "esp_err.h"
#include "esp_intr_alloc.h"
#include "esp_lcd_co5300.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_log.h"
#include "esp_lv_adapter.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "sdkconfig.h"

namespace {
constexpr char kTag[] = "board_waveshare";
// 32 rows is deliberately aligned to the ESP32-S3 external-DMA cache line:
// 466 * 32 * RGB565 = 29,824 bytes, an exact multiple of 128.  Two of these
// PSRAM buffers let LVGL render the next strip while QSPI transmits the current
// one, and reduce a 466-line refresh from 47 tiny transactions to 15.
constexpr std::uint16_t kDrawBufferHeight = 32;
constexpr gpio_num_t kPowerButtonGpio = GPIO_NUM_3;
// J3 pin 11 in Waveshare's public schematic connects the CO5300 TE output
// directly to ESP32-S3 GPIO13.  DCS 0x35 (sent below) enables a short vertical
// blanking pulse on this line.
constexpr gpio_num_t kLcdTeGpio = GPIO_NUM_13;
constexpr std::uint32_t kTeMinimumPeriodUs = 8'000;
constexpr std::uint32_t kTeMaximumPeriodUs = 40'000;
constexpr std::uint32_t kTeMaximumEdgeIntervalUs = 100'000;
constexpr std::uint32_t kTePeriodToleranceFloorUs = 500;
constexpr std::uint8_t kTeStablePeriodCount = 8;
constexpr std::uint32_t kTeProbeReportDelayUs = 2'000'000;
constexpr std::uint32_t kTeWaitTimeoutUs = 25'000;
// Starting a transfer long after active scan began can make the following
// scan catch the QSPI writer.  A just-seen edge may be reused, but older edges
// are discarded and the first flush waits for the next one.
constexpr std::uint32_t kTeMaximumAcceptedEdgeAgeUs = 2'000;
constexpr std::uint8_t kTeMaximumConsecutiveTimeouts = 3;
constexpr std::uint16_t kAxp2101Address = 0x34;
constexpr std::uint8_t kAxp2101CommonConfigRegister = 0x10;
constexpr std::uint8_t kAxp2101SoftwarePowerOffMask = 0x01;
constexpr std::uint8_t kAxp2101PowerOffEnableRegister = 0x22;
constexpr std::uint8_t kAxp2101LongPressShutdownMask = 0x02;
constexpr std::uint8_t kAxp2101LongPressRestartMask = 0x01;
constexpr std::uint8_t kAxp2101KeyTimingRegister = 0x27;
constexpr std::uint8_t kAxp2101PowerOffTimingMask = 0x0C;
lv_display_t* display = nullptr;
esp_lcd_panel_handle_t panel = nullptr;
i2c_master_dev_handle_t pmic = nullptr;
bool display_revealed = false;

struct TeSyncState {
  std::int64_t last_edge_us = 0;
  std::int64_t last_active_edge_us = 0;
  std::int64_t reveal_us = 0;
  std::uint32_t high_duration_us = 0;
  std::uint32_t low_duration_us = 0;
  std::uint32_t estimated_period_us = 0;
  std::uint32_t estimated_pulse_us = 0;
  std::uint32_t edge_count = 0;
  std::int8_t last_level = -1;
  std::int8_t candidate_active_level = -1;
  std::int8_t active_level = -1;
  std::uint8_t stable_periods = 0;
  std::uint8_t consecutive_timeouts = 0;
  bool panel_revealed = false;
  bool probe_stable = false;
  bool probe_reported = false;
  bool probe_timeout_reported = false;
  bool gate_enabled = false;
  bool gate_permanently_disabled = false;
  bool wait_first_flush = false;
};

portMUX_TYPE te_sync_lock = portMUX_INITIALIZER_UNLOCKED;
TeSyncState te_sync_state{};
SemaphoreHandle_t te_active_edge_sem = nullptr;

// Waveshare's public bsp_display_new() currently sends both 0x51=0xff and
// DISPON as part of its private vendor sequence, then sends DISPON once more
// before returning.  Consequently, calling DISPOFF immediately after that API
// can only shorten the unwanted white frame; it cannot prevent it.  Keep the
// supplier's sequence here, but initialize at zero brightness and deliberately
// omit DISPON.  The panel is revealed only after LVGL has committed our black
// boot frame.
constexpr std::uint8_t kPage20[] = {0x20};
constexpr std::uint8_t kGateVoltage[] = {0x10};
constexpr std::uint8_t kSourceVoltage[] = {0xA0};
constexpr std::uint8_t kPage00[] = {0x00};
constexpr std::uint8_t kGammaControl[] = {0x80};
constexpr std::uint8_t kRgb565[] = {0x55};
constexpr std::uint8_t kTearingEffect[] = {0x00};
constexpr std::uint8_t kWriteControl[] = {0x20};
constexpr std::uint8_t kBrightnessOff[] = {0x00};
constexpr std::uint8_t kRegister63Value[] = {0xFF};
constexpr std::uint8_t kColumnRange[] = {0x00, 0x06, 0x01, 0xD7};
constexpr std::uint8_t kRowRange[] = {0x00, 0x00, 0x01, 0xD1};

const co5300_lcd_init_cmd_t kHiddenPanelInitCommands[] = {
    {0xFE, kPage20, sizeof(kPage20), 0},
    {0x19, kGateVoltage, sizeof(kGateVoltage), 0},
    {0x1C, kSourceVoltage, sizeof(kSourceVoltage), 0},
    {0xFE, kPage00, sizeof(kPage00), 0},
    {0xC4, kGammaControl, sizeof(kGammaControl), 0},
    {0x3A, kRgb565, sizeof(kRgb565), 0},
    {0x35, kTearingEffect, sizeof(kTearingEffect), 0},
    {0x53, kWriteControl, sizeof(kWriteControl), 0},
    {0x51, kBrightnessOff, sizeof(kBrightnessOff), 0},
    {0x63, kRegister63Value, sizeof(kRegister63Value), 0},
    {0x2A, kColumnRange, sizeof(kColumnRange), 0},
    {0x2B, kRowRange, sizeof(kRowRange), 600},
    {0x11, nullptr, 0, 600},
};

esp_err_t initialize_hidden_panel(std::size_t maximum_transfer_size,
                                  esp_lcd_panel_handle_t* panel_out,
                                  esp_lcd_panel_io_handle_t* panel_io_out) {
  // Assign fields explicitly because the vendor's C99 designated-initializer
  // macro does not follow spi_bus_config_t's declaration order and is rejected
  // when this board port is compiled as C++17.
  spi_bus_config_t bus_config{};
  bus_config.data0_io_num = BSP_LCD_DATA0;
  bus_config.data1_io_num = BSP_LCD_DATA1;
  bus_config.sclk_io_num = BSP_LCD_PCLK;
  bus_config.data2_io_num = BSP_LCD_DATA2;
  bus_config.data3_io_num = BSP_LCD_DATA3;
  bus_config.data4_io_num = -1;
  bus_config.data5_io_num = -1;
  bus_config.data6_io_num = -1;
  bus_config.data7_io_num = -1;
  bus_config.max_transfer_sz = maximum_transfer_size;
  ESP_RETURN_ON_ERROR(
      spi_bus_initialize(BSP_LCD_SPI_NUM, &bus_config, SPI_DMA_CH_AUTO), kTag,
      "CO5300 QSPI bus initialization failed");

  esp_lcd_panel_io_spi_config_t io_config{};
  io_config.cs_gpio_num = BSP_LCD_CS;
  io_config.dc_gpio_num = -1;
  io_config.spi_mode = 0;
  io_config.pclk_hz = 40 * 1'000 * 1'000;
  io_config.trans_queue_depth = CONFIG_BSP_LCD_TRANS_QUEUE_DEPTH;
  io_config.lcd_cmd_bits = 32;
  io_config.lcd_param_bits = 8;
  io_config.flags.quad_mode = true;
  // ESP32-S3 GPSPI can read an aligned PSRAM color buffer directly through
  // GDMA. Without this flag esp_lcd allocates and copies into a same-sized
  // internal bounce buffer for every flush, which caused the old large-buffer
  // configuration to fail once NimBLE was active.
  io_config.flags.psram_dma_direct = true;
  esp_lcd_panel_io_handle_t new_panel_io = nullptr;
  ESP_RETURN_ON_ERROR(
      esp_lcd_new_panel_io_spi(
          static_cast<esp_lcd_spi_bus_handle_t>(BSP_LCD_SPI_NUM),
          &io_config, &new_panel_io),
      kTag, "CO5300 QSPI panel IO initialization failed");

  co5300_vendor_config_t vendor_config{};
  vendor_config.init_cmds = kHiddenPanelInitCommands;
  vendor_config.init_cmds_size =
      sizeof(kHiddenPanelInitCommands) / sizeof(kHiddenPanelInitCommands[0]);
  vendor_config.flags.use_qspi_interface = 1;

  esp_lcd_panel_dev_config_t device_config{};
  device_config.reset_gpio_num = BSP_LCD_RST;
  device_config.rgb_ele_order = LCD_RGB_ELEMENT_ORDER_RGB;
  device_config.bits_per_pixel = BSP_LCD_BITS_PER_PIXEL;
  device_config.vendor_config = &vendor_config;

  esp_lcd_panel_handle_t new_panel = nullptr;
  ESP_RETURN_ON_ERROR(
      esp_lcd_new_panel_co5300(new_panel_io, &device_config, &new_panel), kTag,
      "CO5300 panel creation failed");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_set_gap(new_panel, 0x06, 0), kTag,
                      "CO5300 panel gap setup failed");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_reset(new_panel), kTag,
                      "CO5300 panel reset failed");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_init(new_panel), kTag,
                      "CO5300 hidden initialization failed");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_disp_on_off(new_panel, false), kTag,
                      "CO5300 could not remain hidden after initialization");

  *panel_out = new_panel;
  *panel_io_out = new_panel_io;
  return ESP_OK;
}

esp_err_t read_pmic_register(std::uint8_t reg, std::uint8_t* value) {
  return i2c_master_transmit_receive(pmic, &reg, 1, value, 1, 100);
}

esp_err_t write_pmic_register(std::uint8_t reg, std::uint8_t value) {
  const std::uint8_t command[2] = {reg, value};
  return i2c_master_transmit(pmic, command, sizeof(command), 100);
}

// Keep one transfer in flight. Double buffering overlaps CPU drawing with the
// active transfer; a deeper esp_lcd transaction queue does not add another
// LVGL buffer and only increases memory/latency under continuous animation.
static_assert(CONFIG_BSP_LCD_TRANS_QUEUE_DEPTH == 1,
              "CO5300 transfers must be serialized to bound DMA memory");

void round_draw_area(lv_area_t* area, void*) {
  // CO5300 QSPI writes must begin/end on two-pixel boundaries. Keeping the
  // buffer height even guarantees a rounded strip still fits the draw buffer.
  area->x1 = (area->x1 >> 1) << 1;
  area->y1 = (area->y1 >> 1) << 1;
  area->x2 = ((area->x2 >> 1) << 1) + 1;
  area->y2 = ((area->y2 >> 1) << 1) + 1;
}

void IRAM_ATTR lcd_te_gpio_isr(void*) {
  const int current_level = gpio_get_level(kLcdTeGpio);
  const std::int64_t now_us = esp_timer_get_time();
  BaseType_t task_woken = pdFALSE;
  bool signal_active_edge = false;

  portENTER_CRITICAL_ISR(&te_sync_lock);
  TeSyncState& state = te_sync_state;
  if (!state.panel_revealed) {
    portEXIT_CRITICAL_ISR(&te_sync_lock);
    return;
  }

  ++state.edge_count;
  if (state.last_level < 0 || state.last_edge_us == 0) {
    state.last_level = static_cast<std::int8_t>(current_level);
    state.last_edge_us = now_us;
    portEXIT_CRITICAL_ISR(&te_sync_lock);
    return;
  }

  // ANYEDGE should alternate levels. Ignore a duplicate interrupt without
  // moving the timestamp; the next real edge will still measure the complete
  // preceding level duration.
  if (current_level == state.last_level) {
    portEXIT_CRITICAL_ISR(&te_sync_lock);
    return;
  }

  const std::int64_t elapsed_us = now_us - state.last_edge_us;
  state.last_level = static_cast<std::int8_t>(current_level);
  state.last_edge_us = now_us;
  if (elapsed_us <= 0 ||
      elapsed_us > static_cast<std::int64_t>(kTeMaximumEdgeIntervalUs)) {
    state.high_duration_us = 0;
    state.low_duration_us = 0;
    state.last_active_edge_us = 0;
    if (!state.probe_stable) {
      state.estimated_period_us = 0;
      state.estimated_pulse_us = 0;
      state.candidate_active_level = -1;
      state.stable_periods = 0;
    }
    portEXIT_CRITICAL_ISR(&te_sync_lock);
    return;
  }

  // At an edge, elapsed_us is the duration of the level that just ended.
  if (current_level != 0) {
    state.low_duration_us = static_cast<std::uint32_t>(elapsed_us);
  } else {
    state.high_duration_us = static_cast<std::uint32_t>(elapsed_us);
  }

  if (state.probe_stable) {
    if (current_level == state.active_level) {
      const std::int64_t measured_period_us =
          now_us - state.last_active_edge_us;
      if (measured_period_us >=
              static_cast<std::int64_t>(kTeMinimumPeriodUs) &&
          measured_period_us <=
              static_cast<std::int64_t>(kTeMaximumPeriodUs)) {
        if (state.estimated_period_us == 0) {
          state.estimated_period_us =
              static_cast<std::uint32_t>(measured_period_us);
        } else {
          state.estimated_period_us = static_cast<std::uint32_t>(
              (static_cast<std::uint64_t>(state.estimated_period_us) * 7U +
               static_cast<std::uint32_t>(measured_period_us)) /
              8U);
        }
      }
      state.last_active_edge_us = now_us;
      signal_active_edge = true;
    }
    portEXIT_CRITICAL_ISR(&te_sync_lock);
    if (signal_active_edge && te_active_edge_sem != nullptr) {
      xSemaphoreGiveFromISR(te_active_edge_sem, &task_woken);
      if (task_woken == pdTRUE) {
        portYIELD_FROM_ISR();
      }
    }
    return;
  }

  if (state.high_duration_us == 0 || state.low_duration_us == 0) {
    portEXIT_CRITICAL_ISR(&te_sync_lock);
    return;
  }

  const std::uint32_t short_duration_us =
      state.high_duration_us < state.low_duration_us
          ? state.high_duration_us
          : state.low_duration_us;
  const std::uint32_t long_duration_us =
      state.high_duration_us < state.low_duration_us
          ? state.low_duration_us
          : state.high_duration_us;
  const std::uint32_t candidate_period_us =
      short_duration_us + long_duration_us;
  // 0x35 mode 0 is a pulse rather than a square wave. Requiring the short
  // level to occupy less than one third of a plausible frame rejects noise
  // and prevents choosing an arbitrary edge solely from the boot-time level.
  const bool pulse_shape_is_valid =
      candidate_period_us >= kTeMinimumPeriodUs &&
      candidate_period_us <= kTeMaximumPeriodUs &&
      static_cast<std::uint64_t>(short_duration_us) * 3U <
          candidate_period_us;
  if (!pulse_shape_is_valid) {
    state.estimated_period_us = 0;
    state.estimated_pulse_us = 0;
    state.last_active_edge_us = 0;
    state.candidate_active_level = -1;
    state.stable_periods = 0;
    portEXIT_CRITICAL_ISR(&te_sync_lock);
    return;
  }

  // The edge that ends the short VBlank pulse and returns to the long idle
  // level is the start of active scan.  If high is short this is falling;
  // if low is short this is rising.
  const std::int8_t candidate_active_level =
      state.high_duration_us < state.low_duration_us ? 0 : 1;
  if (current_level != candidate_active_level) {
    portEXIT_CRITICAL_ISR(&te_sync_lock);
    return;
  }

  if (state.candidate_active_level != candidate_active_level) {
    state.candidate_active_level = candidate_active_level;
    state.last_active_edge_us = now_us;
    state.estimated_period_us = 0;
    state.estimated_pulse_us = short_duration_us;
    state.stable_periods = 0;
    portEXIT_CRITICAL_ISR(&te_sync_lock);
    return;
  }

  const std::int64_t measured_period_us = now_us - state.last_active_edge_us;
  state.last_active_edge_us = now_us;
  if (measured_period_us <
          static_cast<std::int64_t>(kTeMinimumPeriodUs) ||
      measured_period_us >
          static_cast<std::int64_t>(kTeMaximumPeriodUs)) {
    state.estimated_period_us = 0;
    state.stable_periods = 0;
    portEXIT_CRITICAL_ISR(&te_sync_lock);
    return;
  }

  const std::uint32_t measured_period =
      static_cast<std::uint32_t>(measured_period_us);
  if (state.estimated_period_us == 0) {
    state.estimated_period_us = measured_period;
    state.estimated_pulse_us = short_duration_us;
    state.stable_periods = 1;
  } else {
    const std::uint32_t period_delta =
        measured_period > state.estimated_period_us
            ? measured_period - state.estimated_period_us
            : state.estimated_period_us - measured_period;
    const std::uint32_t period_tolerance =
        state.estimated_period_us / 20U > kTePeriodToleranceFloorUs
            ? state.estimated_period_us / 20U
            : kTePeriodToleranceFloorUs;
    if (period_delta > period_tolerance) {
      state.estimated_period_us = measured_period;
      state.estimated_pulse_us = short_duration_us;
      state.stable_periods = 1;
    } else {
      state.estimated_period_us = static_cast<std::uint32_t>(
          (static_cast<std::uint64_t>(state.estimated_period_us) * 7U +
           measured_period) /
          8U);
      state.estimated_pulse_us = static_cast<std::uint32_t>(
          (static_cast<std::uint64_t>(state.estimated_pulse_us) * 7U +
           short_duration_us) /
          8U);
      if (state.stable_periods < kTeStablePeriodCount) {
        ++state.stable_periods;
      }
    }
  }

  if (state.stable_periods >= kTeStablePeriodCount) {
    state.probe_stable = true;
    state.active_level = candidate_active_level;
    signal_active_edge = true;
  }
  portEXIT_CRITICAL_ISR(&te_sync_lock);

  if (signal_active_edge && te_active_edge_sem != nullptr) {
    xSemaphoreGiveFromISR(te_active_edge_sem, &task_woken);
    if (task_woken == pdTRUE) {
      portYIELD_FROM_ISR();
    }
  }
}

bool wait_for_fresh_te_active_edge() {
  const std::int64_t deadline_us =
      esp_timer_get_time() + static_cast<std::int64_t>(kTeWaitTimeoutUs);
  while (true) {
    const std::int64_t now_us = esp_timer_get_time();
    const std::int64_t remaining_us = deadline_us - now_us;
    if (remaining_us <= 0) {
      return false;
    }

    const std::uint32_t remaining_ms =
        static_cast<std::uint32_t>((remaining_us + 999) / 1'000);
    TickType_t wait_ticks = pdMS_TO_TICKS(remaining_ms);
    if (wait_ticks == 0) {
      wait_ticks = 1;
    }
    if (xSemaphoreTake(te_active_edge_sem, wait_ticks) != pdTRUE) {
      return false;
    }

    std::int64_t active_edge_us = 0;
    portENTER_CRITICAL(&te_sync_lock);
    active_edge_us = te_sync_state.last_active_edge_us;
    portEXIT_CRITICAL(&te_sync_lock);
    const std::int64_t edge_age_us = esp_timer_get_time() - active_edge_us;
    if (active_edge_us > 0 && edge_age_us >= 0 &&
        edge_age_us <=
            static_cast<std::int64_t>(kTeMaximumAcceptedEdgeAgeUs)) {
      return true;
    }
    // A TE edge can arrive while LVGL is rendering the first strip. If that
    // work took too long, consuming the stale token and waiting for the next
    // active-scan edge is safer than beginning near the end of the frame.
  }
}

void display_te_event(lv_event_t* event) {
  const lv_event_code_t code = lv_event_get_code(event);
  if (code == LV_EVENT_RENDER_START) {
    while (xSemaphoreTake(te_active_edge_sem, 0) == pdTRUE) {
    }

    const std::int64_t now_us = esp_timer_get_time();
    bool report_lock = false;
    bool report_probe_timeout = false;
    bool gate_enabled_for_lock = false;
    std::uint32_t period_us = 0;
    std::uint32_t pulse_us = 0;
    std::uint32_t edge_count = 0;
    std::int8_t active_level = -1;

    portENTER_CRITICAL(&te_sync_lock);
    TeSyncState& state = te_sync_state;
    if (state.panel_revealed && state.probe_stable &&
        !state.probe_reported) {
      state.probe_reported = true;
      period_us = state.estimated_period_us;
      pulse_us = state.estimated_pulse_us;
      active_level = state.active_level;
      // A fixed 25 ms fail-open timeout cannot reliably gate a panel whose
      // own period is almost that long. Keep rendering unsynchronized rather
      // than introducing three guaranteed startup stalls in that case.
      state.gate_enabled =
          !state.gate_permanently_disabled &&
          state.estimated_period_us + 1'000U < kTeWaitTimeoutUs;
      gate_enabled_for_lock = state.gate_enabled;
      report_lock = true;
    }
    if (state.panel_revealed && !state.probe_stable &&
        !state.probe_timeout_reported && state.reveal_us > 0 &&
        now_us - state.reveal_us >=
            static_cast<std::int64_t>(kTeProbeReportDelayUs)) {
      state.probe_timeout_reported = true;
      edge_count = state.edge_count;
      report_probe_timeout = true;
    }
    state.wait_first_flush = state.gate_enabled;
    portEXIT_CRITICAL(&te_sync_lock);

    if (report_lock) {
      ESP_LOGI(kTag,
               "CO5300 TE locked: GPIO%d period=%lu us pulse=%lu us, "
               "active-scan %s edge; first-flush gate %s",
               static_cast<int>(kLcdTeGpio),
               static_cast<unsigned long>(period_us),
               static_cast<unsigned long>(pulse_us),
               active_level != 0 ? "rising" : "falling",
               gate_enabled_for_lock ? "enabled" : "not enabled");
      if (!gate_enabled_for_lock) {
        ESP_LOGW(kTag,
                 "TE period leaves insufficient margin for the 25 ms "
                 "fail-open timeout; keeping unsynchronized partial mode");
      }
    }
    if (report_probe_timeout) {
      ESP_LOGW(kTag,
               "CO5300 TE probe has not found a stable pulse after 2 s "
               "(%lu edges); keeping unsynchronized partial mode",
               static_cast<unsigned long>(edge_count));
    }
    return;
  }

  if (code != LV_EVENT_FLUSH_START) {
    return;
  }

  bool should_wait = false;
  portENTER_CRITICAL(&te_sync_lock);
  should_wait = te_sync_state.wait_first_flush;
  te_sync_state.wait_first_flush = false;
  portEXIT_CRITICAL(&te_sync_lock);
  if (!should_wait) {
    return;
  }

  const bool synchronized = wait_for_fresh_te_active_edge();
  std::uint8_t timeout_count = 0;
  bool gate_disabled = false;
  portENTER_CRITICAL(&te_sync_lock);
  TeSyncState& state = te_sync_state;
  if (synchronized) {
    state.consecutive_timeouts = 0;
  } else if (state.gate_enabled) {
    if (state.consecutive_timeouts < kTeMaximumConsecutiveTimeouts) {
      ++state.consecutive_timeouts;
    }
    timeout_count = state.consecutive_timeouts;
    if (state.consecutive_timeouts >= kTeMaximumConsecutiveTimeouts) {
      state.gate_enabled = false;
      state.gate_permanently_disabled = true;
      state.wait_first_flush = false;
      gate_disabled = true;
    }
  }
  portEXIT_CRITICAL(&te_sync_lock);

  if (!synchronized) {
    if (gate_disabled) {
      ESP_LOGW(kTag,
               "TE wait timed out 3 consecutive times; gate disabled for "
               "this boot and frame sent fail-open");
    } else {
      ESP_LOGW(kTag, "TE wait timeout %u/%u; frame sent fail-open",
               static_cast<unsigned>(timeout_count),
               static_cast<unsigned>(kTeMaximumConsecutiveTimeouts));
    }
  }
}

esp_err_t initialize_te_probe(lv_display_t* target_display) {
  te_active_edge_sem = xSemaphoreCreateBinary();
  if (te_active_edge_sem == nullptr) {
    ESP_LOGE(kTag, "CO5300 TE semaphore allocation failed");
    return ESP_ERR_NO_MEM;
  }

  gpio_config_t te_config{};
  te_config.pin_bit_mask = 1ULL << kLcdTeGpio;
  te_config.mode = GPIO_MODE_INPUT;
  te_config.pull_up_en = GPIO_PULLUP_DISABLE;
  te_config.pull_down_en = GPIO_PULLDOWN_DISABLE;
  te_config.intr_type = GPIO_INTR_ANYEDGE;
  esp_err_t result = gpio_config(&te_config);
  if (result != ESP_OK) {
    vSemaphoreDelete(te_active_edge_sem);
    te_active_edge_sem = nullptr;
    return result;
  }

  result = gpio_install_isr_service(ESP_INTR_FLAG_LOWMED);
  if (result != ESP_OK && result != ESP_ERR_INVALID_STATE) {
    vSemaphoreDelete(te_active_edge_sem);
    te_active_edge_sem = nullptr;
    return result;
  }
  result = gpio_isr_handler_add(kLcdTeGpio, lcd_te_gpio_isr, nullptr);
  if (result != ESP_OK) {
    vSemaphoreDelete(te_active_edge_sem);
    te_active_edge_sem = nullptr;
    return result;
  }

  lv_display_add_event_cb(target_display, display_te_event,
                          LV_EVENT_RENDER_START, nullptr);
  lv_display_add_event_cb(target_display, display_te_event,
                          LV_EVENT_FLUSH_START, nullptr);
  ESP_LOGI(kTag,
           "CO5300 TE probe armed on GPIO%d (ANYEDGE; starts after reveal)",
           static_cast<int>(kLcdTeGpio));
  return ESP_OK;
}

void start_te_probe_after_reveal() {
  while (xSemaphoreTake(te_active_edge_sem, 0) == pdTRUE) {
  }
  const int initial_level = gpio_get_level(kLcdTeGpio);
  const std::int64_t now_us = esp_timer_get_time();
  portENTER_CRITICAL(&te_sync_lock);
  te_sync_state = {};
  te_sync_state.panel_revealed = true;
  te_sync_state.reveal_us = now_us;
  te_sync_state.last_edge_us = now_us;
  te_sync_state.last_level = static_cast<std::int8_t>(initial_level);
  portEXIT_CRITICAL(&te_sync_lock);
  ESP_LOGI(kTag, "CO5300 TE startup probe started: GPIO%d initial level=%d",
           static_cast<int>(kLcdTeGpio), initial_level);
}

esp_err_t initialize_power_control() {
  gpio_config_t button_config{};
  button_config.pin_bit_mask = 1ULL << kPowerButtonGpio;
  button_config.mode = GPIO_MODE_INPUT;
  button_config.pull_up_en = GPIO_PULLUP_DISABLE;
  button_config.pull_down_en = GPIO_PULLDOWN_DISABLE;
  button_config.intr_type = GPIO_INTR_DISABLE;
  ESP_RETURN_ON_ERROR(gpio_config(&button_config), kTag,
                      "PWR SYS_OUT input configuration failed");

  i2c_device_config_t pmic_config{};
  pmic_config.dev_addr_length = I2C_ADDR_BIT_LEN_7;
  pmic_config.device_address = kAxp2101Address;
  pmic_config.scl_speed_hz = 400'000;
  ESP_RETURN_ON_ERROR(
      i2c_master_bus_add_device(bsp_i2c_get_handle(), &pmic_config, &pmic),
      kTag, "AXP2101 I2C device registration failed");

  // Normal operation shows the shutdown frame and requests software power-off
  // after three seconds. Configure the PMIC itself as a four-second fail-safe:
  // if the ESP task is wedged, continuing to hold PWR still switches the unit
  // off instead of restarting it. All writes are read/modify/write so unrelated
  // factory power and charging policy bits are preserved.
  // Program the duration before enabling the hardware action, so a press that
  // overlaps startup can never use a stale/default long-press policy.
  std::uint8_t key_timing = 0;
  ESP_RETURN_ON_ERROR(read_pmic_register(kAxp2101KeyTimingRegister,
                                         &key_timing),
                      kTag, "AXP2101 PWR timing read failed");
  key_timing = static_cast<std::uint8_t>(
      key_timing & ~kAxp2101PowerOffTimingMask);  // 00 = four seconds.
  ESP_RETURN_ON_ERROR(write_pmic_register(kAxp2101KeyTimingRegister,
                                          key_timing),
                      kTag, "AXP2101 PWR timing write failed");

  std::uint8_t key_timing_check = 0;
  ESP_RETURN_ON_ERROR(read_pmic_register(kAxp2101KeyTimingRegister,
                                         &key_timing_check),
                      kTag, "AXP2101 PWR timing readback failed");
  if ((key_timing_check & kAxp2101PowerOffTimingMask) != 0) {
    ESP_LOGE(kTag, "AXP2101 rejected four-second PWR timing: reg27=0x%02x",
             key_timing_check);
    return ESP_ERR_INVALID_RESPONSE;
  }

  std::uint8_t power_off_enable = 0;
  ESP_RETURN_ON_ERROR(
      read_pmic_register(kAxp2101PowerOffEnableRegister, &power_off_enable),
      kTag, "AXP2101 power-off policy read failed");
  power_off_enable = static_cast<std::uint8_t>(
      (power_off_enable | kAxp2101LongPressShutdownMask) &
      ~kAxp2101LongPressRestartMask);
  ESP_RETURN_ON_ERROR(
      write_pmic_register(kAxp2101PowerOffEnableRegister, power_off_enable),
      kTag, "AXP2101 power-off policy write failed");

  std::uint8_t power_off_enable_check = 0;
  ESP_RETURN_ON_ERROR(
      read_pmic_register(kAxp2101PowerOffEnableRegister,
                         &power_off_enable_check),
      kTag, "AXP2101 power-off policy readback failed");
  const std::uint8_t expected_power_off_policy =
      kAxp2101LongPressShutdownMask;
  if ((power_off_enable_check &
       (kAxp2101LongPressShutdownMask | kAxp2101LongPressRestartMask)) !=
      expected_power_off_policy) {
    ESP_LOGE(kTag, "AXP2101 rejected long-press shutdown policy: reg22=0x%02x",
             power_off_enable_check);
    return ESP_ERR_INVALID_RESPONSE;
  }

  ESP_LOGI(kTag,
           "PWR ready: GPIO3 hold 3 s software off, AXP2101 hold 4 s hard off");
  return ESP_OK;
}
}  // namespace

extern "C" esp_err_t board_port_init(void) {
  if (display != nullptr) {
    return ESP_OK;
  }

  static_assert(MOTO_DISPLAY_WIDTH == BSP_LCD_H_RES,
                "Shared UI width must match the Waveshare panel");
  static_assert(MOTO_DISPLAY_HEIGHT == BSP_LCD_V_RES,
                "Shared UI height must match the Waveshare panel");
  static_assert(MOTO_DISPLAY_BITS_PER_PIXEL == BSP_LCD_BITS_PER_PIXEL,
                "Shared UI and Waveshare panel must both use RGB565");

  // Keep the official BSP's touch driver and pin map plus the supplier's
  // CO5300 command values. Panel creation itself uses the public esp_lcd APIs
  // so DISPON can be deferred. The aligned 32-row buffers live in PSRAM and
  // use ESP32-S3's direct external-memory GDMA path; this avoids the large
  // internal bounce allocations that made the vendor's old setup unstable.
  const esp_lv_adapter_config_t adapter_config =
      ESP_LV_ADAPTER_DEFAULT_CONFIG();
  ESP_RETURN_ON_ERROR(esp_lv_adapter_init(&adapter_config), kTag,
                      "LVGL adapter initialization failed");

  esp_lcd_panel_io_handle_t panel_io = nullptr;
  ESP_RETURN_ON_ERROR(
      initialize_hidden_panel(MOTO_DISPLAY_WIDTH * kDrawBufferHeight *
                                  MOTO_DISPLAY_BITS_PER_PIXEL / 8,
                              &panel, &panel_io),
      kTag, "hidden Waveshare CO5300 initialization failed");

  esp_lv_adapter_display_config_t display_config =
      ESP_LV_ADAPTER_DISPLAY_SPI_WITH_PSRAM_DEFAULT_CONFIG(
          panel, panel_io, MOTO_DISPLAY_WIDTH, MOTO_DISPLAY_HEIGHT,
          ESP_LV_ADAPTER_ROTATE_0);
  display_config.profile.buffer_height = kDrawBufferHeight;
  display_config.profile.require_double_buffer = true;
  display = esp_lv_adapter_register_display(&display_config);
  if (display == nullptr) {
    ESP_LOGE(kTag, "LVGL could not register the CO5300 display");
    return ESP_FAIL;
  }
  ESP_RETURN_ON_ERROR(
      esp_lv_adapter_set_area_rounder_cb(display, round_draw_area, nullptr),
      kTag, "CO5300 area rounder registration failed");
  ESP_RETURN_ON_ERROR(initialize_te_probe(display), kTag,
                      "CO5300 TE probe initialization failed");

  bsp_display_cfg_t touch_board_config = {
      .lv_adapter_cfg = ESP_LV_ADAPTER_DEFAULT_CONFIG(),
      .rotation = ESP_LV_ADAPTER_ROTATE_0,
      .tear_avoid_mode = ESP_LV_ADAPTER_TEAR_AVOID_MODE_NONE,
      .touch_flags = {
          .swap_xy = 0,
          .mirror_x = 1,
          .mirror_y = 1,
      },
  };
  esp_lcd_touch_handle_t touch = nullptr;
  ESP_RETURN_ON_ERROR(bsp_touch_new(&touch_board_config, &touch), kTag,
                      "official Waveshare CST9217 initialization failed");
  const esp_lv_adapter_touch_config_t touch_config =
      ESP_LV_ADAPTER_TOUCH_DEFAULT_CONFIG(display, touch);
  if (esp_lv_adapter_register_touch(&touch_config) == nullptr) {
    ESP_LOGE(kTag, "LVGL could not register the CST9217 touch device");
    return ESP_FAIL;
  }
  ESP_RETURN_ON_ERROR(initialize_power_control(), kTag,
                      "PWR/AXP2101 control initialization failed");

  // Establish black before the display worker is allowed to perform its first
  // refresh.  The app replaces this with the animated boot scene while the
  // physical panel is still hidden.
  lv_obj_t* const startup_screen = lv_display_get_screen_active(display);
  lv_obj_set_style_bg_color(startup_screen, lv_color_black(), 0);
  lv_obj_set_style_bg_opa(startup_screen, LV_OPA_COVER, 0);
  lv_obj_remove_flag(startup_screen, LV_OBJ_FLAG_SCROLLABLE);
  ESP_RETURN_ON_ERROR(esp_lv_adapter_start(), kTag,
                      "LVGL worker task could not start");

  if (lv_display_get_horizontal_resolution(display) != MOTO_DISPLAY_WIDTH ||
      lv_display_get_vertical_resolution(display) != MOTO_DISPLAY_HEIGHT ||
      lv_display_get_color_format(display) != LV_COLOR_FORMAT_RGB565) {
    ESP_LOGE(kTag, "BSP display is %ldx%ld format=%d, expected %dx%d RGB565",
             static_cast<long>(lv_display_get_horizontal_resolution(display)),
             static_cast<long>(lv_display_get_vertical_resolution(display)),
             static_cast<int>(lv_display_get_color_format(display)),
             MOTO_DISPLAY_WIDTH, MOTO_DISPLAY_HEIGHT);
    display = nullptr;
    return ESP_ERR_INVALID_SIZE;
  }

  ESP_LOGI(
      kTag,
      "CO5300 + CST9217 ready at %dx%d RGB565 (dual PSRAM %u-row buffers, "
      "direct DMA, QSPI queue depth %d)",
      MOTO_DISPLAY_WIDTH, MOTO_DISPLAY_HEIGHT,
      static_cast<unsigned>(kDrawBufferHeight),
      CONFIG_BSP_LCD_TRANS_QUEUE_DEPTH);
  return ESP_OK;
}

extern "C" lv_display_t* board_port_get_display(void) { return display; }

extern "C" esp_err_t board_port_reveal_display(void) {
  if (panel == nullptr || display == nullptr) {
    return ESP_ERR_INVALID_STATE;
  }
  if (display_revealed) {
    return ESP_OK;
  }

  // The first LVGL refresh has already queued the black boot background.  The
  // panel command and brightness write share that QSPI queue, so the visible
  // enable cannot overtake the framebuffer transfer.
  ESP_RETURN_ON_ERROR(esp_lcd_panel_disp_on_off(panel, true), kTag,
                      "could not reveal AMOLED");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_co5300_set_brightness(panel, 100), kTag,
                      "could not restore AMOLED brightness");
  display_revealed = true;
  start_te_probe_after_reveal();
  return ESP_OK;
}

extern "C" bool board_port_lock(uint32_t timeout_ms) {
  if (display == nullptr) {
    return false;
  }
  return bsp_display_lock(timeout_ms) == ESP_OK;
}

extern "C" void board_port_unlock(void) {
  if (display != nullptr) {
    bsp_display_unlock();
  }
}

extern "C" bool board_port_power_button_pressed(void) {
  // Official schematic: PWRON -> R12/BSS138/R9 -> SYS_OUT -> GPIO3. The
  // inverter stage makes SYS_OUT high for the duration of a physical press.
  return gpio_get_level(kPowerButtonGpio) != 0;
}

extern "C" esp_err_t board_port_power_off(void) {
  if (pmic == nullptr) {
    return ESP_ERR_INVALID_STATE;
  }

  if (panel != nullptr) {
    const esp_err_t display_result = esp_lcd_panel_disp_on_off(panel, false);
    if (display_result != ESP_OK) {
      ESP_LOGW(kTag, "could not blank AMOLED before power-off: %s",
               esp_err_to_name(display_result));
    }
  }

  std::uint8_t common_config = 0;
  ESP_RETURN_ON_ERROR(
      read_pmic_register(kAxp2101CommonConfigRegister, &common_config), kTag,
      "AXP2101 common config read failed");
  return write_pmic_register(
      kAxp2101CommonConfigRegister,
      static_cast<std::uint8_t>(common_config |
                                kAxp2101SoftwarePowerOffMask));
}
