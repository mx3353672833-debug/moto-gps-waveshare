#pragma once

#include <atomic>
#include <cstdint>

#include "esp_err.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "moto/ble_protocol/ble_protocol.hpp"

struct ble_gap_event;
struct ble_gatt_access_ctxt;

// ESP32 peripheral/GATT-server transport for the versioned MOTO BLE protocol.
// NimBLE callbacks only copy characteristic writes into a bounded queue; all
// decoding and UI-facing callbacks execute on the dedicated RX worker.
class BleNavTransport {
 public:
  using MessageCallback = moto::ble::AckStatus (*)(
      const moto::ble::ReassembledMessage& message, void* context);
  using LinkCallback = void (*)(bool active, void* context);

  BleNavTransport();
  BleNavTransport(const BleNavTransport&) = delete;
  BleNavTransport& operator=(const BleNavTransport&) = delete;

  void set_callbacks(MessageCallback message_callback,
                     LinkCallback link_callback,
                     void* context) noexcept;
  esp_err_t start();
  bool send_message(const moto::ble::Message& message,
                    std::uint8_t frame_flags = 0);

  // Signature-compatible adapter for PhoneNavBridge::set_sender().
  static bool send_from_bridge(const moto::ble::Message& message,
                               std::uint8_t frame_flags,
                               void* context);

 private:
  static constexpr std::uint16_t kNoConnection = 0xFFFFU;
  // 185-byte ATT MTU minus the 3-byte ATT notification header. This stays
  // within the standard NimBLE mbuf pool while remaining efficient on iOS.
  static constexpr std::size_t kMaximumFrameSize = 182;
  static constexpr std::size_t kMaximumFramePayload =
      kMaximumFrameSize - moto::ble::kFrameOverhead;
  static constexpr std::size_t kMaximumFramesPerMessage =
      (moto::ble::kDefaultMaxMessageSize + kMaximumFramePayload - 1U) /
      kMaximumFramePayload;
  // CoreBluetooth is allowed to submit a complete write-without-response
  // burst while canSendWriteWithoutResponse remains true. Hold one maximum
  // protocol message plus a few session/navigation frames so MapScene cannot
  // overflow the queue merely because its decoder has not run yet.
  static constexpr std::size_t kRxQueueDepth =
      kMaximumFramesPerMessage + 4U;

  struct RxPacket {
    std::uint32_t connection_epoch = 0;
    std::uint32_t loss_epoch = 0;
    std::uint16_t length = 0;
    std::uint8_t bytes[kMaximumFrameSize]{};
  };

  struct PendingAck {
    bool active = false;
    std::uint16_t sequence = 0;
    std::uint16_t command_id = 0;
    std::uint8_t retries = 0;
    std::uint64_t deadline_ms = 0;
    std::vector<moto::ble::Bytes> frames;
  };

  static int gatt_access(std::uint16_t connection_handle,
                         std::uint16_t attribute_handle,
                         ble_gatt_access_ctxt* context,
                         void* argument);
  static int gap_event(ble_gap_event* event, void* argument);
  static void host_reset(int reason);
  static void host_sync();
  static void host_task(void* argument);
  static void rx_task(void* argument);

  int register_gatt_service();
  void advertise();
  void run_rx_worker();
  void note_link(bool active);
  void send_connection_status(moto::ble::ConnectionState state);
  void send_ack(std::uint16_t sequence,
                moto::ble::AckStatus status,
                std::uint16_t command_id = 0);
  void accept_ack(const moto::ble::Ack& ack);
  void service_pending_ack(std::uint64_t now_ms);
  bool notify_frames(const std::vector<moto::ble::Bytes>& frames);
  std::size_t negotiated_frame_size() const noexcept;

  static BleNavTransport* active_instance_;

  MessageCallback message_callback_ = nullptr;
  LinkCallback link_callback_ = nullptr;
  void* callback_context_ = nullptr;
  QueueHandle_t rx_queue_ = nullptr;
  SemaphoreHandle_t tx_mutex_ = nullptr;
  TaskHandle_t rx_task_handle_ = nullptr;
  moto::ble::Reassembler reassembler_{};
  moto::ble::SequenceGenerator tx_sequence_{};
  moto::ble::LinkWatchdog watchdog_{3'500};
  PendingAck pending_ack_{};
  std::atomic<std::uint16_t> connection_handle_{kNoConnection};
  // Written by NimBLE while registering the static GATT table, before the
  // host starts; immutable for the lifetime of the service afterwards.
  std::uint16_t tx_value_handle_ = 0;
  std::atomic<std::uint16_t> max_frame_size_{20};
  std::atomic<std::uint16_t> peer_max_frame_size_{20};
  std::atomic<std::uint32_t> connection_epoch_{0};
  // Incremented by the NimBLE callback whenever an inbound frame cannot be
  // queued. The RX worker uses the marker to abandon only the damaged message
  // and resume at the next START frame instead of emitting missing_start for
  // every remaining continuation.
  std::atomic<std::uint32_t> rx_loss_epoch_{0};
  std::atomic<std::uint32_t> session_id_{0};
  std::atomic<std::uint32_t> last_tx_ms_{0};
  std::atomic<bool> connected_{false};
  std::atomic<bool> encrypted_{false};
  std::atomic<bool> subscribed_{false};
  std::uint8_t own_address_type_ = 0;
  std::uint16_t last_rx_ack_sequence_ = 0;
  std::uint16_t last_rx_ack_command_id_ = 0;
  moto::ble::AckStatus last_rx_ack_status_ = moto::ble::AckStatus::Failed;
  bool has_last_rx_ack_ = false;
  bool notified_link_active_ = false;
  bool started_ = false;
};
