#pragma once

#include <cstdint>

namespace moto::coordinates {

struct Coordinate {
  double longitude_deg = 0.0;
  double latitude_deg = 0.0;
};

enum class ConversionStatus : std::uint8_t {
  Ok = 0,
  InvalidInput,
};

struct ConversionResult {
  Coordinate coordinate;
  ConversionStatus status = ConversionStatus::InvalidInput;

  [[nodiscard]] constexpr bool ok() const noexcept {
    return status == ConversionStatus::Ok;
  }
};

// Conversion accepts finite coordinates, matching backend/src/coordinates.js.
// Geographic range validation belongs at the protocol boundary.
[[nodiscard]] bool is_valid_coordinate(Coordinate coordinate) noexcept;

// GCJ-02's conventional mainland-China coverage rectangle. Callers should
// validate the coordinate before using this predicate.
[[nodiscard]] bool is_outside_gcj02_coverage(Coordinate coordinate) noexcept;

// Converts WGS84 to the coordinate system consumed by AMap. Values outside
// GCJ-02 coverage pass through unchanged. Output is rounded to 1e-7 degrees so
// Web, native tests, and ESP32 produce the same provider request coordinates.
// Invalid input returns InvalidInput and echoes the input without throwing.
[[nodiscard]] ConversionResult wgs84_to_gcj02(Coordinate coordinate) noexcept;

}  // namespace moto::coordinates
