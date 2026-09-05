#include "motion_heading_sensor.h"

#include <algorithm>
#include <cmath>

#include "bsp/esp-bsp.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// qmi8658.h defines M_PI globally. This adapter does not use that macro and
// removes it after the official component header to keep C++ <cmath> clean.
#include "qmi8658.h"
#ifdef M_PI
#undef M_PI
#endif

namespace {
constexpr char kTag[] = "motion_heading";
constexpr TickType_t kSampleDelay = pdMS_TO_TICKS(8);  // 125 Hz target.
constexpr int kCalibrationSamples = 120;
constexpr std::uint64_t kCalibrationDeadlineMs = 4'000;
constexpr float kMinimumGravity = 6.8F;
constexpr float kMaximumGravity = 12.8F;

struct GravityEstimate {
  float x = 0.0F;
  float y = 0.0F;
  float z = 1.0F;
  bool initialized = false;
};

std::uint64_t monotonic_ms() {
  return static_cast<std::uint64_t>(esp_timer_get_time()) / 1'000U;
}

float magnitude(float x, float y, float z) {
  return std::sqrt(x * x + y * y + z * z);
}

bool update_gravity(GravityEstimate& gravity, const qmi8658_data_t& sample,
                    float blend) {
  const float length = magnitude(sample.accelX, sample.accelY, sample.accelZ);
  if (!std::isfinite(length) || length < kMinimumGravity ||
      length > kMaximumGravity) {
    return gravity.initialized;
  }
  if (!gravity.initialized) {
    gravity.x = sample.accelX;
    gravity.y = sample.accelY;
    gravity.z = sample.accelZ;
    gravity.initialized = true;
  } else {
    gravity.x += (sample.accelX - gravity.x) * blend;
    gravity.y += (sample.accelY - gravity.y) * blend;
    gravity.z += (sample.accelZ - gravity.z) * blend;
  }
  return true;
}

// Project angular velocity onto the board's gravity axis so a moderately
// tilted handlebar mount still responds to a turn around vertical. Normalize
// the axis toward the display face, then negate it because compass heading
// increases clockwise while gyroscope right-hand rotation increases CCW.
float clockwise_heading_rate(const GravityEstimate& gravity,
                             const qmi8658_data_t& sample) {
  float gx = gravity.x;
  float gy = gravity.y;
  float gz = gravity.z;
  const float length = magnitude(gx, gy, gz);
  if (!gravity.initialized || length < 0.01F) {
    return -sample.gyroZ;
  }
  gx /= length;
  gy /= length;
  gz /= length;
  if (gz < 0.0F) {
    gx = -gx;
    gy = -gy;
    gz = -gz;
  }
  return -(sample.gyroX * gx + sample.gyroY * gy + sample.gyroZ * gz);
}
}  // namespace

esp_err_t MotionHeadingSensor::start(SampleCallback callback, void* context) {
  if (callback == nullptr) return ESP_ERR_INVALID_ARG;
  if (started_) return ESP_ERR_INVALID_STATE;
  callback_ = callback;
  callback_context_ = context;
  // Sensor callbacks reach the presenter and LVGL adapter. Keep enough margin
  // for those nested frames even though the large map snapshot now remains in
  // PhoneNavBridge member storage.
  if (xTaskCreate(task_entry, "moto_qmi_heading", 8'192, this, 3, nullptr) !=
      pdPASS) {
    callback_ = nullptr;
    callback_context_ = nullptr;
    return ESP_ERR_NO_MEM;
  }
  started_ = true;
  return ESP_OK;
}

void MotionHeadingSensor::task_entry(void* context) {
  static_cast<MotionHeadingSensor*>(context)->run();
  vTaskDelete(nullptr);
}

void MotionHeadingSensor::run() {
  qmi8658_dev_t device{};
  const esp_err_t init = qmi8658_init(
      &device, bsp_i2c_get_handle(), QMI8658_ADDRESS_HIGH);
  if (init != ESP_OK) {
    ESP_LOGW(kTag, "QMI8658 unavailable; using phone course only: %s",
             esp_err_to_name(init));
    return;
  }
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_accel_range(&device, QMI8658_ACCEL_RANGE_4G));
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_accel_odr(&device, QMI8658_ACCEL_ODR_125HZ));
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_gyro_range(&device, QMI8658_GYRO_RANGE_512DPS));
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_gyro_odr(&device, QMI8658_GYRO_ODR_125HZ));
  qmi8658_set_accel_unit_mps2(&device, true);
  qmi8658_set_gyro_unit_dps(&device, true);

  GravityEstimate gravity;
  float bias_sum = 0.0F;
  int stable_samples = 0;
  const std::uint64_t calibration_start = monotonic_ms();
  qmi8658_data_t data{};
  while (stable_samples < kCalibrationSamples &&
         monotonic_ms() - calibration_start < kCalibrationDeadlineMs) {
    bool ready = false;
    if (qmi8658_is_data_ready(&device, &ready) == ESP_OK && ready &&
        qmi8658_read_sensor_data(&device, &data) == ESP_OK) {
      update_gravity(gravity, data, 0.08F);
      const float projected = clockwise_heading_rate(gravity, data);
      const float angular = magnitude(data.gyroX, data.gyroY, data.gyroZ);
      if (std::isfinite(projected) && angular < 4.0F) {
        bias_sum += projected;
        ++stable_samples;
      } else {
        bias_sum = 0.0F;
        stable_samples = 0;
      }
    }
    vTaskDelay(kSampleDelay);
  }

  float bias_dps = stable_samples > 0 ? bias_sum / stable_samples : 0.0F;
  ESP_LOGI(kTag,
           "QMI8658 yaw assist ready (bias %.3f dps, %d stable samples)",
           static_cast<double>(bias_dps), stable_samples);

  int consecutive_failures = 0;
  while (true) {
    bool ready = false;
    esp_err_t result = qmi8658_is_data_ready(&device, &ready);
    if (result == ESP_OK && ready) {
      result = qmi8658_read_sensor_data(&device, &data);
      if (result == ESP_OK) {
        consecutive_failures = 0;
        update_gravity(gravity, data, 0.025F);
        const float raw_rate = clockwise_heading_rate(gravity, data);
        const float corrected_rate = raw_rate - bias_dps;
        const float angular = magnitude(data.gyroX, data.gyroY, data.gyroZ);
        const float accel = magnitude(data.accelX, data.accelY, data.accelZ);
        // Learn residual bias only during convincingly quiet intervals.
        if (std::abs(corrected_rate) < 1.1F && angular < 2.0F &&
            accel > 8.2F && accel < 11.4F) {
          bias_dps += (raw_rate - bias_dps) * 0.0008F;
        }
        callback_(corrected_rate, monotonic_ms(), callback_context_);
      }
    }
    if (result != ESP_OK && ++consecutive_failures == 25) {
      ESP_LOGW(kTag, "QMI8658 read errors continue: %s",
               esp_err_to_name(result));
    }
    vTaskDelay(kSampleDelay);
  }
}
