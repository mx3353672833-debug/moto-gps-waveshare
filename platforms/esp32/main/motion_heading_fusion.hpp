#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

// Short-term heading fusion for the handlebar-mounted display.
//
// The phone's Core Location course is an absolute, drift-free reference while
// the motorcycle is moving. The onboard QMI8658 gyroscope fills the latency
// between phone fixes and preserves responsive relative turns at very low
// speed. Since QMI8658 has no magnetometer, this class deliberately never
// claims to create an absolute north reference by itself.
class MotionHeadingFusion {
 public:
  void reset() noexcept {
    initialized_ = false;
    heading_deg_ = 0.0F;
    filtered_rate_dps_ = 0.0F;
    last_sample_ms_ = 0;
  }

  void anchor(float phone_heading_deg, float speed_mps,
              bool usable_fix) noexcept {
    if (!usable_fix || !std::isfinite(phone_heading_deg)) {
      return;
    }
    const float phone = normalize(phone_heading_deg);
    if (!initialized_) {
      heading_deg_ = phone;
      initialized_ = true;
      return;
    }

    // A course is trustworthy only once the receiver is actually moving.
    // Correct drift progressively to avoid a visible snap during a bend.
    if (std::isfinite(speed_mps) && speed_mps >= kCourseAnchorSpeedMps) {
      const float error = shortest_delta(phone, heading_deg_);
      const float correction = std::abs(error) > 80.0F ? 0.65F : 0.28F;
      heading_deg_ = normalize(heading_deg_ + error * correction);
    }
  }

  // yaw_rate_dps is positive for increasing compass heading (clockwise).
  // Returns true when a new display heading is available.
  bool integrate(float yaw_rate_dps, std::uint64_t sample_ms) noexcept {
    if (!std::isfinite(yaw_rate_dps)) {
      return false;
    }
    if (!initialized_) {
      // Relative motion remains useful before the first trustworthy course;
      // zero degrees is explicitly just a temporary local reference.
      initialized_ = true;
      heading_deg_ = 0.0F;
    }
    if (last_sample_ms_ == 0 || sample_ms <= last_sample_ms_) {
      last_sample_ms_ = sample_ms;
      return false;
    }

    const std::uint64_t elapsed_ms = sample_ms - last_sample_ms_;
    last_sample_ms_ = sample_ms;
    if (elapsed_ms > kMaximumIntegrationGapMs) {
      filtered_rate_dps_ = 0.0F;
      return false;
    }

    const float bounded = std::clamp(yaw_rate_dps, -500.0F, 500.0F);
    // About 30 ms of smoothing at the 125 Hz sensor rate: vibration is
    // suppressed without the sluggish response of phone-only course updates.
    filtered_rate_dps_ += (bounded - filtered_rate_dps_) * 0.24F;
    const float active_rate = std::abs(filtered_rate_dps_) < kDeadbandDps
                                  ? 0.0F
                                  : filtered_rate_dps_;
    if (active_rate == 0.0F) {
      return false;
    }
    heading_deg_ = normalize(
        heading_deg_ + active_rate * static_cast<float>(elapsed_ms) / 1'000.0F);
    return true;
  }

  [[nodiscard]] bool initialized() const noexcept { return initialized_; }
  [[nodiscard]] float heading_deg() const noexcept { return heading_deg_; }

 private:
  static constexpr float kCourseAnchorSpeedMps = 1.5F;
  static constexpr float kDeadbandDps = 0.65F;
  static constexpr std::uint64_t kMaximumIntegrationGapMs = 160;

  static float normalize(float value) noexcept {
    float result = std::fmod(value, 360.0F);
    if (result < 0.0F) result += 360.0F;
    return result;
  }

  static float shortest_delta(float target, float current) noexcept {
    float delta = normalize(target) - normalize(current);
    if (delta > 180.0F) delta -= 360.0F;
    if (delta < -180.0F) delta += 360.0F;
    return delta;
  }

  bool initialized_ = false;
  float heading_deg_ = 0.0F;
  float filtered_rate_dps_ = 0.0F;
  std::uint64_t last_sample_ms_ = 0;
};
