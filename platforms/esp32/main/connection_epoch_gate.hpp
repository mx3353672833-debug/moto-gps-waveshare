#pragma once

#include <cstdint>

namespace moto::esp32 {

// Tracks the physical BLE connection generation seen by the RX worker. The
// worker must synchronize this gate both before and after a blocking queue
// receive: a CONNECT event and its first write can otherwise arrive while the
// worker is asleep, making a valid first packet look stale.
class ConnectionEpochGate {
 public:
  ConnectionEpochGate(std::uint32_t epoch, bool connected) noexcept
      : epoch_(epoch), connected_(connected) {}

  [[nodiscard]] bool synchronize(std::uint32_t epoch,
                                 bool connected) noexcept {
    if (epoch == epoch_) {
      return false;
    }
    epoch_ = epoch;
    connected_ = connected;
    return true;
  }

  [[nodiscard]] bool accepts(std::uint32_t packet_epoch) const noexcept {
    return connected_ && packet_epoch == epoch_;
  }

  [[nodiscard]] bool connected() const noexcept { return connected_; }

 private:
  std::uint32_t epoch_ = 0;
  bool connected_ = false;
};

}  // namespace moto::esp32
