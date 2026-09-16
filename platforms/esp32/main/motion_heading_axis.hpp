#pragma once

#include <cmath>

// At rest the accelerometer measures specific force upward. Its signed axis
// is the reference for compass yaw regardless of the display's mounting tilt.
// Do not force gravity.z positive: on a forward-facing/vertical screen it
// crosses zero with tiny vibrations and would reverse every left/right turn.
inline float clockwise_gravity_heading_rate(float accel_x, float accel_y,
                                           float accel_z, float gyro_x,
                                           float gyro_y, float gyro_z) noexcept {
  const float length = std::sqrt(accel_x * accel_x + accel_y * accel_y +
                                 accel_z * accel_z);
  if (!std::isfinite(length) || length < 0.01F) return 0.0F;
  return -(gyro_x * accel_x + gyro_y * accel_y + gyro_z * accel_z) / length;
}
