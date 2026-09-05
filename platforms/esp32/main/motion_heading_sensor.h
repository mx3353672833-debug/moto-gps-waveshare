#pragma once

#include <cstdint>

#include "esp_err.h"

class MotionHeadingSensor {
 public:
  using SampleCallback = void (*)(float heading_rate_dps,
                                  std::uint64_t sample_ms,
                                  void* context);

  MotionHeadingSensor() = default;
  MotionHeadingSensor(const MotionHeadingSensor&) = delete;
  MotionHeadingSensor& operator=(const MotionHeadingSensor&) = delete;

  // Starts one low-priority 125 Hz sensor task. Failure is non-fatal: phone
  // course navigation continues to work without the local latency filler.
  esp_err_t start(SampleCallback callback, void* context);

 private:
  static void task_entry(void* context);
  void run();

  SampleCallback callback_ = nullptr;
  void* callback_context_ = nullptr;
  bool started_ = false;
};
