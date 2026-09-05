#include "ble_event_filter.hpp"
#include "connection_epoch_gate.hpp"

#include <cstdlib>
#include <iostream>

namespace {

int failures = 0;

#define CHECK(condition)                                                   \
  do {                                                                     \
    if (!(condition)) {                                                    \
      std::cerr << __FILE__ << ':' << __LINE__                             \
                << " CHECK failed: " #condition << '\n';                  \
      ++failures;                                                          \
    }                                                                      \
  } while (false)

void test_first_packet_after_connect_is_accepted() {
  moto::esp32::ConnectionEpochGate gate(7, false);

  // The RX worker entered its blocking receive while disconnected. NimBLE
  // then connected and queued Phone Ready with the incremented epoch.
  constexpr std::uint32_t connected_epoch = 8;
  CHECK(gate.synchronize(connected_epoch, true));
  CHECK(gate.accepts(connected_epoch));
}

void test_packets_from_previous_connection_are_rejected() {
  moto::esp32::ConnectionEpochGate gate(8, true);
  CHECK(gate.accepts(8));
  CHECK(gate.synchronize(9, false));
  CHECK(!gate.accepts(8));
  CHECK(!gate.accepts(9));

  CHECK(gate.synchronize(10, true));
  CHECK(!gate.accepts(8));
  CHECK(!gate.accepts(9));
  CHECK(gate.accepts(10));
}

void test_same_epoch_does_not_reset_session_state() {
  moto::esp32::ConnectionEpochGate gate(42, true);
  CHECK(!gate.synchronize(42, true));
  CHECK(gate.accepts(42));
}

void test_only_tx_subscription_events_change_notify_state() {
  constexpr std::uint16_t tx_value_handle = 0x0042;
  CHECK(moto::esp32::is_tx_subscription_event(0x0042, tx_value_handle));
  CHECK(!moto::esp32::is_tx_subscription_event(0x0001, tx_value_handle));
  CHECK(!moto::esp32::is_tx_subscription_event(0x0042, 0));
}

}  // namespace

int main() {
  test_first_packet_after_connect_is_accepted();
  test_packets_from_previous_connection_are_rejected();
  test_same_epoch_does_not_reset_session_state();
  test_only_tx_subscription_events_change_notify_state();

  if (failures != 0) {
    std::cerr << failures << " connection epoch checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All connection epoch checks passed\n";
  return EXIT_SUCCESS;
}
