#include "moto_coordinates.hpp"

#include <cmath>
#include <cstdio>
#include <limits>

namespace {

using moto::coordinates::ConversionStatus;
using moto::coordinates::Coordinate;
using moto::coordinates::is_outside_gcj02_coverage;
using moto::coordinates::wgs84_to_gcj02;

int failures = 0;

void expect_true(bool condition, const char* message) {
  if (!condition) {
    std::fprintf(stderr, "FAIL: %s\n", message);
    ++failures;
  }
}

void expect_near(double actual,
                 double expected,
                 double tolerance,
                 const char* message) {
  if (!std::isfinite(actual) || std::fabs(actual - expected) > tolerance) {
    std::fprintf(stderr,
                 "FAIL: %s (actual %.10f, expected %.10f)\n",
                 message,
                 actual,
                 expected);
    ++failures;
  }
}

void test_beijing_reference_point() {
  const auto converted = wgs84_to_gcj02({116.397389, 39.908722});

  expect_true(converted.status == ConversionStatus::Ok,
              "Beijing coordinate converts successfully");
  expect_near(converted.coordinate.longitude_deg,
              116.4036326,
              0.00000001,
              "Beijing longitude matches backend formula at 1e-7 precision");
  expect_near(converted.coordinate.latitude_deg,
              39.9101255,
              0.00000001,
              "Beijing latitude matches backend formula at 1e-7 precision");
}

void test_outside_coverage_passes_through() {
  constexpr Coordinate paris{2.3522, 48.8566};
  expect_true(is_outside_gcj02_coverage(paris),
              "Paris is outside GCJ-02 coverage");

  const auto converted = wgs84_to_gcj02(paris);
  expect_true(converted.ok(), "outside coordinate is a successful conversion");
  expect_near(converted.coordinate.longitude_deg,
              paris.longitude_deg,
              0.0,
              "outside longitude is unchanged");
  expect_near(converted.coordinate.latitude_deg,
              paris.latitude_deg,
              0.0,
              "outside latitude is unchanged");
}

void test_invalid_input_returns_status() {
  const double nan = std::numeric_limits<double>::quiet_NaN();
  const double infinity = std::numeric_limits<double>::infinity();

  const auto invalid_longitude = wgs84_to_gcj02({nan, 39.908722});
  expect_true(invalid_longitude.status == ConversionStatus::InvalidInput,
              "NaN longitude is rejected without throwing");

  const auto invalid_latitude = wgs84_to_gcj02({116.397389, infinity});
  expect_true(invalid_latitude.status == ConversionStatus::InvalidInput,
              "infinite latitude is rejected without throwing");

  const auto negative_infinity = wgs84_to_gcj02({-infinity, -infinity});
  expect_true(negative_infinity.status == ConversionStatus::InvalidInput,
              "negative infinite coordinate is rejected without throwing");
}

}  // namespace

int main() {
  test_beijing_reference_point();
  test_outside_coverage_passes_through();
  test_invalid_input_returns_status();

  if (failures != 0) {
    std::fprintf(stderr, "%d coordinate test(s) failed\n", failures);
    return 1;
  }

  std::puts("All coordinate conversion tests passed");
  return 0;
}
