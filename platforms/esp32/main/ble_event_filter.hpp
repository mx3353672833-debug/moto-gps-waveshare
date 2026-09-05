#pragma once

#include <cstdint>

namespace moto::esp32 {

// NimBLE reports subscription changes for every characteristic. Only the
// Device-to-Phone TX value handle is allowed to control notification state.
[[nodiscard]] constexpr bool is_tx_subscription_event(
    std::uint16_t event_attribute_handle,
    std::uint16_t tx_value_handle) noexcept {
  return tx_value_handle != 0 && event_attribute_handle == tx_value_handle;
}

}  // namespace moto::esp32
