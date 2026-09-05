#include "ble_nav_transport_nimble.h"

#include <algorithm>
#include <cstring>

#include "ble_event_filter.hpp"
#include "connection_epoch_gate.hpp"
#include "esp_log.h"
#include "esp_timer.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "nvs_flash.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

extern "C" void ble_store_config_init(void);

namespace {
constexpr char kTag[] = "moto_ble";
constexpr char kDeviceName[] = "MOTO GPS";

// BLE_UUID128_INIT takes the Bluetooth little-endian byte representation.
const ble_uuid128_t kServiceUuid = BLE_UUID128_INIT(
    0x00, 0x10, 0x8e, 0x4e, 0xa5, 0x40, 0x57, 0x9c,
    0x6a, 0x4b, 0x0c, 0xb5, 0x00, 0xa0, 0x57, 0x7e);
const ble_uuid128_t kRxUuid = BLE_UUID128_INIT(
    0x00, 0x10, 0x8e, 0x4e, 0xa5, 0x40, 0x57, 0x9c,
    0x6a, 0x4b, 0x0c, 0xb5, 0x01, 0xa0, 0x57, 0x7e);
const ble_uuid128_t kTxUuid = BLE_UUID128_INIT(
    0x00, 0x10, 0x8e, 0x4e, 0xa5, 0x40, 0x57, 0x9c,
    0x6a, 0x4b, 0x0c, 0xb5, 0x02, 0xa0, 0x57, 0x7e);

ble_gatt_chr_def g_characteristics[3]{};
ble_gatt_svc_def g_services[2]{};
std::uint16_t g_rx_value_handle = 0;

std::uint64_t monotonic_ms() {
  return static_cast<std::uint64_t>(esp_timer_get_time()) / 1'000U;
}

}  // namespace

BleNavTransport* BleNavTransport::active_instance_ = nullptr;

BleNavTransport::BleNavTransport() = default;

void BleNavTransport::set_callbacks(MessageCallback message_callback,
                                    LinkCallback link_callback,
                                    void* context) noexcept {
  message_callback_ = message_callback;
  link_callback_ = link_callback;
  callback_context_ = context;
}

esp_err_t BleNavTransport::start() {
  if (started_) {
    return ESP_OK;
  }
  if (active_instance_ != nullptr && active_instance_ != this) {
    ESP_LOGE(kTag, "only one BLE navigation peripheral is supported");
    return ESP_ERR_INVALID_STATE;
  }

  esp_err_t result = nvs_flash_init();
  if (result == ESP_ERR_NVS_NO_FREE_PAGES ||
      result == ESP_ERR_NVS_NEW_VERSION_FOUND) {
    ESP_LOGW(kTag, "NVS layout changed; rebuilding this firmware's NVS");
    result = nvs_flash_erase();
    if (result == ESP_OK) {
      result = nvs_flash_init();
    }
  }
  if (result != ESP_OK) {
    ESP_LOGE(kTag, "NVS initialization failed: %s", esp_err_to_name(result));
    return result;
  }

  rx_queue_ = xQueueCreate(kRxQueueDepth, sizeof(RxPacket));
  tx_mutex_ = xSemaphoreCreateMutex();
  if (rx_queue_ == nullptr || tx_mutex_ == nullptr) {
    ESP_LOGE(kTag, "could not allocate BLE queues");
    return ESP_ERR_NO_MEM;
  }

  result = nimble_port_init();
  if (result != ESP_OK) {
    ESP_LOGE(kTag, "NimBLE initialization failed: %s",
             esp_err_to_name(result));
    return result;
  }

  ble_hs_cfg.reset_cb = host_reset;
  ble_hs_cfg.sync_cb = host_sync;
  ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
  // iOS triggers Just Works pairing when it first writes RX or enables TX
  // notifications. LESC + bonding gives Security Mode 1 Level 2 without a
  // keyboard/display passkey flow on this tiny instrument.
  ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
  ble_hs_cfg.sm_bonding = 1;
  ble_hs_cfg.sm_mitm = 0;
  ble_hs_cfg.sm_sc = 1;
  ble_hs_cfg.sm_our_key_dist =
      BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
  ble_hs_cfg.sm_their_key_dist =
      BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;

  const int gatt_result = register_gatt_service();
  if (gatt_result != 0) {
    ESP_LOGE(kTag, "GATT service registration failed: rc=%d", gatt_result);
    return ESP_FAIL;
  }
  const int name_result = ble_svc_gap_device_name_set(kDeviceName);
  if (name_result != 0) {
    ESP_LOGE(kTag, "device name setup failed: rc=%d", name_result);
    return ESP_FAIL;
  }
  ble_store_config_init();

  if (xTaskCreate(rx_task, "moto_ble_rx", 8192, this, 5,
                  &rx_task_handle_) != pdPASS) {
    ESP_LOGE(kTag, "could not start BLE protocol worker");
    return ESP_ERR_NO_MEM;
  }

  active_instance_ = this;
  started_ = true;
  nimble_port_freertos_init(host_task);
  ESP_LOGI(kTag, "BLE peripheral started; service=%s", moto::ble::kServiceUuid);
  return ESP_OK;
}

int BleNavTransport::register_gatt_service() {
  ble_svc_gap_init();
  ble_svc_gatt_init();

  std::memset(g_characteristics, 0, sizeof(g_characteristics));
  g_characteristics[0].uuid = &kRxUuid.u;
  g_characteristics[0].access_cb = gatt_access;
  g_characteristics[0].arg = this;
  g_characteristics[0].flags =
      BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP |
      BLE_GATT_CHR_F_WRITE_ENC;
  g_characteristics[0].val_handle = &g_rx_value_handle;

  g_characteristics[1].uuid = &kTxUuid.u;
  g_characteristics[1].access_cb = gatt_access;
  g_characteristics[1].arg = this;
  g_characteristics[1].flags =
      BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_NOTIFY_INDICATE_ENC;
  // The handle is written during GATT registration, before host sync.
  g_characteristics[1].val_handle = &tx_value_handle_;

  std::memset(g_services, 0, sizeof(g_services));
  g_services[0].type = BLE_GATT_SVC_TYPE_PRIMARY;
  g_services[0].uuid = &kServiceUuid.u;
  g_services[0].characteristics = g_characteristics;

  int result = ble_gatts_count_cfg(g_services);
  if (result == 0) {
    result = ble_gatts_add_svcs(g_services);
  }
  return result;
}

int BleNavTransport::gatt_access(std::uint16_t,
                                 std::uint16_t attribute_handle,
                                 ble_gatt_access_ctxt* context,
                                 void* argument) {
  auto* self = static_cast<BleNavTransport*>(argument);
  if (self == nullptr || context == nullptr ||
      context->op != BLE_GATT_ACCESS_OP_WRITE_CHR ||
      attribute_handle != g_rx_value_handle) {
    return BLE_ATT_ERR_UNLIKELY;
  }

  const std::uint16_t length = OS_MBUF_PKTLEN(context->om);
  if (length < moto::ble::kFrameOverhead ||
      length > kMaximumFrameSize) {
    return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
  }

  RxPacket packet;
  packet.connection_epoch = self->connection_epoch_.load();
  packet.loss_epoch = self->rx_loss_epoch_.load();
  packet.length = length;
  std::uint16_t copied = 0;
  const int flatten_result = ble_hs_mbuf_to_flat(
      context->om, packet.bytes, sizeof(packet.bytes), &copied);
  if (flatten_result != 0 || copied != length) {
    return BLE_ATT_ERR_UNLIKELY;
  }
  if (xQueueSend(self->rx_queue_, &packet, 0) != pdTRUE) {
    self->rx_loss_epoch_.fetch_add(1);
    ESP_LOGW(kTag, "RX queue full; rejecting %u-byte frame", length);
    return BLE_ATT_ERR_INSUFFICIENT_RES;
  }
  return 0;
}

int BleNavTransport::gap_event(ble_gap_event* event, void* argument) {
  auto* self = static_cast<BleNavTransport*>(argument);
  if (self == nullptr || event == nullptr) {
    return 0;
  }

  switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
      if (event->connect.status == 0) {
        self->connection_handle_.store(event->connect.conn_handle);
        self->connected_.store(true);
        // With a restored iOS bond NimBLE can report encryption and the TX
        // subscription before it delivers CONNECT to this callback. DISCONNECT
        // already clears both flags, so do not erase those valid early events
        // here or the first Device Ready response will be suppressed forever.
        self->max_frame_size_.store(20);
        self->peer_max_frame_size_.store(20);
        self->session_id_.store(0);
        self->connection_epoch_.fetch_add(1);
        ESP_LOGI(kTag,
                 "iPhone connected; handle=%u encrypted=%u subscribed=%u",
                 event->connect.conn_handle,
                 self->encrypted_.load() ? 1U : 0U,
                 self->subscribed_.load() ? 1U : 0U);
      } else {
        ESP_LOGW(kTag, "connection failed: status=%d",
                 event->connect.status);
        self->advertise();
      }
      return 0;

    case BLE_GAP_EVENT_DISCONNECT:
      ESP_LOGI(kTag, "phone disconnected: reason=%d",
               event->disconnect.reason);
      self->connected_.store(false);
      self->encrypted_.store(false);
      self->subscribed_.store(false);
      self->connection_handle_.store(kNoConnection);
      self->peer_max_frame_size_.store(20);
      self->session_id_.store(0);
      self->connection_epoch_.fetch_add(1);
      self->advertise();
      return 0;

    case BLE_GAP_EVENT_SUBSCRIBE: {
      if (!moto::esp32::is_tx_subscription_event(
              event->subscribe.attr_handle, self->tx_value_handle_)) {
        return 0;
      }
      const bool notify = event->subscribe.cur_notify != 0;
      self->subscribed_.store(notify);
      ESP_LOGI(kTag, "TX notifications %s", notify ? "enabled" : "disabled");
      return 0;
    }

    case BLE_GAP_EVENT_MTU: {
      const std::uint16_t value = event->mtu.value;
      const std::uint16_t frame_size = value > 3
          ? static_cast<std::uint16_t>(std::min<std::size_t>(
                value - 3U, kMaximumFrameSize))
          : 20U;
      self->max_frame_size_.store(frame_size);
      ESP_LOGI(kTag, "ATT MTU=%u, protocol frame=%u", value, frame_size);
      return 0;
    }

    case BLE_GAP_EVENT_ADV_COMPLETE:
      self->advertise();
      return 0;

    case BLE_GAP_EVENT_REPEAT_PAIRING: {
      struct ble_gap_conn_desc description{};
      if (ble_gap_conn_find(event->repeat_pairing.conn_handle,
                            &description) == 0) {
        ble_store_util_delete_peer(&description.peer_id_addr);
      }
      return BLE_GAP_REPEAT_PAIRING_RETRY;
    }

    case BLE_GAP_EVENT_ENC_CHANGE: {
      struct ble_gap_conn_desc description{};
      const bool encrypted =
          event->enc_change.status == 0 &&
          ble_gap_conn_find(event->enc_change.conn_handle, &description) == 0 &&
          description.sec_state.encrypted != 0;
      self->encrypted_.store(encrypted);
      ESP_LOGI(kTag, "link encryption %s: status=%d",
               encrypted ? "ready" : "not ready", event->enc_change.status);
      return 0;
    }

    default:
      return 0;
  }
}

void BleNavTransport::host_reset(int reason) {
  ESP_LOGE(kTag, "NimBLE host reset: reason=%d", reason);
}

void BleNavTransport::host_sync() {
  BleNavTransport* self = active_instance_;
  if (self == nullptr) {
    return;
  }
  int result = ble_hs_util_ensure_addr(0);
  if (result == 0) {
    result = ble_hs_id_infer_auto(0, &self->own_address_type_);
  }
  if (result != 0) {
    ESP_LOGE(kTag, "could not select BLE identity address: rc=%d", result);
    return;
  }
  self->advertise();
}

void BleNavTransport::advertise() {
  ble_hs_adv_fields fields{};
  fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
  fields.name = reinterpret_cast<std::uint8_t*>(
      const_cast<char*>(kDeviceName));
  fields.name_len = sizeof(kDeviceName) - 1U;
  fields.name_is_complete = 1;
  fields.uuids128 = const_cast<ble_uuid128_t*>(&kServiceUuid);
  fields.num_uuids128 = 1;
  fields.uuids128_is_complete = 1;

  int result = ble_gap_adv_set_fields(&fields);
  if (result != 0) {
    ESP_LOGE(kTag, "advertising payload rejected: rc=%d", result);
    return;
  }

  ble_gap_adv_params parameters{};
  parameters.conn_mode = BLE_GAP_CONN_MODE_UND;
  parameters.disc_mode = BLE_GAP_DISC_MODE_GEN;
  result = ble_gap_adv_start(own_address_type_, nullptr, BLE_HS_FOREVER,
                             &parameters, gap_event, this);
  if (result != 0 && result != BLE_HS_EALREADY) {
    ESP_LOGE(kTag, "advertising start failed: rc=%d", result);
  }
}

void BleNavTransport::host_task(void*) {
  nimble_port_run();
  nimble_port_freertos_deinit();
}

void BleNavTransport::rx_task(void* argument) {
  static_cast<BleNavTransport*>(argument)->run_rx_worker();
}

void BleNavTransport::run_rx_worker() {
  moto::esp32::ConnectionEpochGate epoch_gate(connection_epoch_.load(),
                                               connected_.load());
  bool protocol_ready = false;
  bool has_observed_loss_epoch = false;
  bool resynchronizing_after_loss = false;
  std::uint32_t observed_loss_epoch = 0;
  if (epoch_gate.connected()) {
    watchdog_.note_valid_frame(monotonic_ms());
    note_link(true);
  }

  RxPacket packet;
  const auto synchronize_connection_epoch =
      [this, &epoch_gate, &protocol_ready, &has_observed_loss_epoch,
       &resynchronizing_after_loss]() {
    const std::uint32_t current_epoch = connection_epoch_.load();
    if (!epoch_gate.synchronize(current_epoch, connected_.load())) {
      return;
    }
    reassembler_.reset();
    watchdog_.reset();
    protocol_ready = false;
    has_observed_loss_epoch = false;
    resynchronizing_after_loss = false;
    has_last_rx_ack_ = false;
    if (xSemaphoreTake(tx_mutex_, pdMS_TO_TICKS(100)) == pdTRUE) {
      pending_ack_ = {};
      tx_sequence_.reset();
      last_tx_ms_.store(static_cast<std::uint32_t>(monotonic_ms()));
      xSemaphoreGive(tx_mutex_);
    }
    if (epoch_gate.connected()) {
      watchdog_.note_valid_frame(monotonic_ms());
    }
    note_link(epoch_gate.connected());
  };
  while (true) {
    synchronize_connection_epoch();

    if (xQueueReceive(rx_queue_, &packet, pdMS_TO_TICKS(200)) == pdTRUE) {
      // CONNECT/DISCONNECT and the first write run on NimBLE's task. If that
      // write wakes this worker while it was blocked in xQueueReceive, refresh
      // the epoch again before deciding whether the packet is stale.
      synchronize_connection_epoch();
      if (!epoch_gate.accepts(packet.connection_epoch)) {
        continue;
      }
      const std::uint64_t now = monotonic_ms();

      if (!has_observed_loss_epoch) {
        observed_loss_epoch = packet.loss_epoch;
        has_observed_loss_epoch = true;
      } else if (packet.loss_epoch != observed_loss_epoch) {
        observed_loss_epoch = packet.loss_epoch;
        reassembler_.reset();
        resynchronizing_after_loss = true;
        ESP_LOGW(kTag,
                 "RX overflow damaged a fragmented message; awaiting START");
      }

      // ConnectionStatus establishes (or replaces) the protocol session, so
      // it must not be rejected against the previous phone process's sequence
      // baseline. This matters when iOS restores the same physical BLE link:
      // the new app process starts its TX sequence at 1 while the ESP32 still
      // remembers a much newer sequence from the old process. Reset on the
      // START fragment before normal reassembly; subsequent fragments, if
      // any, are then assembled normally and the decoded session ID below
      // decides whether the protocol state itself must be replaced.
      const moto::ble::FrameResult control_frame = moto::ble::decode_frame(
          moto::ble::ByteView(packet.bytes, packet.length));
      if (resynchronizing_after_loss && control_frame.ok()) {
        if ((control_frame.value.flags & moto::ble::FrameStart) == 0U) {
          continue;
        }
        resynchronizing_after_loss = false;
      }
      if (control_frame.ok() &&
          control_frame.value.type == moto::ble::MessageType::ConnectionStatus &&
          (control_frame.value.flags & moto::ble::FrameStart) != 0U) {
        reassembler_.reset();
      }
      const moto::ble::ReassemblyResult assembled = reassembler_.push(
          moto::ble::ByteView(packet.bytes, packet.length), now);
      if (assembled.error != moto::ble::Error::None) {
        ESP_LOGW(kTag, "frame rejected: %s at %u",
                 moto::ble::to_string(assembled.error),
                 static_cast<unsigned>(assembled.error_offset));
        continue;
      }

      watchdog_.note_valid_frame(now);
      note_link(true);
      if (assembled.state == moto::ble::ReassemblyState::DuplicateMessage) {
        if ((assembled.message.flags & moto::ble::AckRequested) != 0U) {
          if (has_last_rx_ack_ &&
              last_rx_ack_sequence_ == assembled.message.sequence) {
            send_ack(last_rx_ack_sequence_, last_rx_ack_status_,
                     last_rx_ack_command_id_);
          } else {
            send_ack(assembled.message.sequence,
                     moto::ble::AckStatus::Duplicate);
          }
        }
        continue;
      }
      if (!assembled.complete()) {
        continue;
      }

      // Decode only the control messages the transport itself must inspect.
      // PhoneNavBridge owns application-message decoding; decoding a large
      // MapScene here as well used twice the transient heap and kept the RX
      // worker away from its queue for longer during the largest burst.
      moto::ble::MessageResult decoded;
      decoded.error = moto::ble::Error::InvalidArgument;
      if (assembled.message.type == moto::ble::MessageType::ConnectionStatus ||
          assembled.message.type == moto::ble::MessageType::Ack ||
          assembled.message.type == moto::ble::MessageType::DeviceCommand) {
        decoded = moto::ble::decode_message(
            assembled.message.type,
            moto::ble::ByteView(assembled.message.payload));
      }
      const moto::ble::ConnectionStatus* phone_status =
          decoded.ok()
              ? std::get_if<moto::ble::ConnectionStatus>(&decoded.value)
              : nullptr;
      const moto::ble::Ack* received_ack =
          decoded.ok() ? std::get_if<moto::ble::Ack>(&decoded.value) : nullptr;

      if (received_ack != nullptr) {
        accept_ack(*received_ack);
      }

      bool reset_for_new_session = false;
      if (phone_status != nullptr &&
          phone_status->role == moto::ble::EndpointRole::Phone) {
        const std::uint32_t previous_session = session_id_.load();
        reset_for_new_session =
            previous_session != 0 &&
            previous_session != phone_status->session_id;
        if (reset_for_new_session) {
          note_link(false);
          note_link(true);
          protocol_ready = false;
          has_last_rx_ack_ = false;
          reassembler_.reset();
          if (xSemaphoreTake(tx_mutex_, pdMS_TO_TICKS(100)) == pdTRUE) {
            pending_ack_ = {};
            tx_sequence_.reset();
            xSemaphoreGive(tx_mutex_);
          }
        }
        session_id_.store(phone_status->session_id);
        peer_max_frame_size_.store(phone_status->max_frame_size);
        last_tx_ms_.store(static_cast<std::uint32_t>(now));
        const std::uint64_t timeout = std::clamp<std::uint64_t>(
            static_cast<std::uint64_t>(
                phone_status->heartbeat_interval_ms) * 3U,
            1'500U, 15'000U);
        watchdog_.set_timeout(timeout);
      }

      const bool is_session_message =
          assembled.message.type == moto::ble::MessageType::ConnectionStatus ||
          assembled.message.type == moto::ble::MessageType::Heartbeat ||
          assembled.message.type == moto::ble::MessageType::Ack;
      moto::ble::AckStatus status = moto::ble::AckStatus::InvalidState;
      if ((protocol_ready || is_session_message) &&
          message_callback_ != nullptr) {
        status = message_callback_(assembled.message, callback_context_);
      }

      std::uint16_t ack_command_id = 0;
      if (decoded.ok()) {
        if (const auto* command =
                std::get_if<moto::ble::DeviceCommand>(&decoded.value)) {
          ack_command_id = command->command_id;
        }
      }
      if ((assembled.message.flags & moto::ble::AckRequested) != 0U &&
          assembled.message.type != moto::ble::MessageType::Ack) {
        send_ack(assembled.message.sequence, status, ack_command_id);
        has_last_rx_ack_ = true;
        last_rx_ack_sequence_ = assembled.message.sequence;
        last_rx_ack_status_ = status;
        last_rx_ack_command_id_ = ack_command_id;
      }

      if (phone_status != nullptr) {
        if (status == moto::ble::AckStatus::Unsupported) {
          send_connection_status(moto::ble::ConnectionState::Closing);
        } else if (status == moto::ble::AckStatus::Ok) {
          if (phone_status->state == moto::ble::ConnectionState::Starting) {
            // Canonical v1 handshake: advertise device capabilities first;
            // display messages remain gated until Phone subsequently says
            // Ready.
            send_connection_status(moto::ble::ConnectionState::Ready);
          } else if (phone_status->state ==
                     moto::ble::ConnectionState::Ready) {
            // CoreBluetooth may restore an encrypted/subscribed bond and send
            // Phone Ready as its first session frame. Always answer with the
            // device capability/status message before opening the display-data
            // gate, so the iOS side cannot wait forever for our status.
            send_connection_status(moto::ble::ConnectionState::Ready);
            protocol_ready = true;
          }
        }
      }
    }

    const std::uint64_t now = monotonic_ms();
    service_pending_ack(now);

    bool awaiting_ack = false;
    if (xSemaphoreTake(tx_mutex_, 0) == pdTRUE) {
      awaiting_ack = pending_ack_.active;
      xSemaphoreGive(tx_mutex_);
    }
    if (!awaiting_ack && protocol_ready && session_id_.load() != 0 &&
        connected_.load() && encrypted_.load() && subscribed_.load() &&
        static_cast<std::uint32_t>(now) - last_tx_ms_.load() >= 1'000U) {
      moto::ble::Heartbeat heartbeat;
      heartbeat.session_id = session_id_.load();
      heartbeat.monotonic_ms = static_cast<std::uint32_t>(now);
      heartbeat.status_flags = 0;
      send_message(moto::ble::Message{heartbeat});
    }

    if (epoch_gate.connected() && watchdog_.expired(now)) {
      // Keep the physical BLE connection, but mark navigation stale until a
      // valid frame resumes. This separates radio link from app liveness.
      note_link(false);
    }
  }
}

void BleNavTransport::note_link(bool active) {
  if (notified_link_active_ == active) {
    return;
  }
  notified_link_active_ = active;
  if (link_callback_ != nullptr) {
    link_callback_(active, callback_context_);
  }
}

void BleNavTransport::send_connection_status(
    moto::ble::ConnectionState state) {
  const std::uint32_t session_id = session_id_.load();
  if (session_id == 0) {
    return;
  }
  moto::ble::ConnectionStatus status;
  status.role = moto::ble::EndpointRole::Device;
  status.state = state;
  status.minimum_version = moto::ble::kProtocolVersion;
  status.maximum_version = moto::ble::kProtocolVersion;
  status.capabilities = moto::ble::CapabilityNavigation |
                        moto::ble::CapabilityRouteGeometry |
                        moto::ble::CapabilityTraffic |
                        moto::ble::CapabilityMediaState |
                        moto::ble::CapabilityTouchCommands |
                        moto::ble::CapabilityMusicCommands |
                        moto::ble::CapabilityCommandAck |
                        moto::ble::CapabilityMapScene;
  status.session_id = session_id;
  status.max_frame_size =
      static_cast<std::uint16_t>(negotiated_frame_size());
  status.heartbeat_interval_ms = 1'000;
  send_message(moto::ble::Message{status}, moto::ble::Urgent);
}

void BleNavTransport::send_ack(std::uint16_t sequence,
                               moto::ble::AckStatus status,
                               std::uint16_t command_id) {
  moto::ble::Ack ack;
  ack.acknowledged_sequence = sequence;
  ack.status = status;
  ack.command_id = command_id;
  send_message(moto::ble::Message{ack});
}

void BleNavTransport::accept_ack(const moto::ble::Ack& ack) {
  if (xSemaphoreTake(tx_mutex_, pdMS_TO_TICKS(100)) != pdTRUE) {
    return;
  }
  if (pending_ack_.active &&
      pending_ack_.sequence == ack.acknowledged_sequence &&
      pending_ack_.command_id == ack.command_id) {
    ESP_LOGI(kTag, "command ACK sequence=%u command=%u status=%u",
             ack.acknowledged_sequence, ack.command_id,
             static_cast<unsigned>(ack.status));
    pending_ack_ = {};
  }
  xSemaphoreGive(tx_mutex_);
}

void BleNavTransport::service_pending_ack(std::uint64_t now_ms) {
  bool report_degraded = false;
  if (xSemaphoreTake(tx_mutex_, 0) != pdTRUE) {
    return;
  }
  if (pending_ack_.active && now_ms >= pending_ack_.deadline_ms) {
    if (pending_ack_.retries < 2 && connected_.load() && encrypted_.load() &&
        subscribed_.load()) {
      ESP_LOGW(kTag, "command ACK timeout; retry %u/2",
               static_cast<unsigned>(pending_ack_.retries + 1U));
      notify_frames(pending_ack_.frames);
      ++pending_ack_.retries;
      pending_ack_.deadline_ms = now_ms + 750U;
    } else {
      ESP_LOGE(kTag, "command ACK failed after 2 retries");
      pending_ack_ = {};
      report_degraded = true;
    }
  }
  xSemaphoreGive(tx_mutex_);
  if (report_degraded) {
    send_connection_status(moto::ble::ConnectionState::Degraded);
  }
}

bool BleNavTransport::notify_frames(
    const std::vector<moto::ble::Bytes>& frames) {
  if (!connected_.load() || !encrypted_.load() || !subscribed_.load()) {
    return false;
  }
  for (const auto& frame : frames) {
    struct os_mbuf* buffer = os_msys_get_pkthdr(frame.size(), 0);
    if (buffer == nullptr ||
        os_mbuf_append(buffer, frame.data(), frame.size()) != 0) {
      if (buffer != nullptr) {
        os_mbuf_free_chain(buffer);
      }
      return false;
    }
    const int result = ble_gatts_notify_custom(
        connection_handle_.load(), tx_value_handle_, buffer);
    if (result != 0) {
      ESP_LOGW(kTag, "notification failed: rc=%d", result);
      return false;
    }
  }
  last_tx_ms_.store(static_cast<std::uint32_t>(monotonic_ms()));
  return true;
}

std::size_t BleNavTransport::negotiated_frame_size() const noexcept {
  const std::size_t local = std::min<std::size_t>(max_frame_size_.load(),
                                                  kMaximumFrameSize);
  const std::size_t peer = peer_max_frame_size_.load();
  return std::clamp<std::size_t>(std::min(local, peer),
                                 moto::ble::kFrameOverhead + 1U,
                                 kMaximumFrameSize);
}

bool BleNavTransport::send_message(const moto::ble::Message& message,
                                   std::uint8_t frame_flags) {
  if (!started_ || !connected_.load() || !encrypted_.load() ||
      !subscribed_.load() ||
      tx_value_handle_ == 0 || tx_mutex_ == nullptr) {
    return false;
  }
  if ((frame_flags & ~moto::ble::kApplicationFrameFlags) != 0U ||
      xSemaphoreTake(tx_mutex_, pdMS_TO_TICKS(500)) != pdTRUE) {
    return false;
  }

  if ((frame_flags & moto::ble::AckRequested) != 0U &&
      pending_ack_.active) {
    ESP_LOGW(kTag, "one acknowledged command is already pending");
    xSemaphoreGive(tx_mutex_);
    return false;
  }

  bool success = true;
  const moto::ble::BytesResult encoded = moto::ble::encode_message(message);
  if (!encoded.ok()) {
    ESP_LOGW(kTag, "outbound payload rejected: %s",
             moto::ble::to_string(encoded.error));
    success = false;
  } else {
    const std::uint16_t sequence = tx_sequence_.next();
    moto::ble::FramesResult frames = moto::ble::fragment_message(
        moto::ble::message_type(message), sequence,
        moto::ble::ByteView(encoded.value), negotiated_frame_size(),
        frame_flags);
    if (!frames.ok()) {
      ESP_LOGW(kTag, "outbound fragmentation failed: %s",
               moto::ble::to_string(frames.error));
      success = false;
    } else {
      success = notify_frames(frames.value);
      if (success && (frame_flags & moto::ble::AckRequested) != 0U) {
        pending_ack_ = {};
        pending_ack_.active = true;
        pending_ack_.sequence = sequence;
        if (const auto* command =
                std::get_if<moto::ble::DeviceCommand>(&message)) {
          pending_ack_.command_id = command->command_id;
        }
        pending_ack_.deadline_ms = monotonic_ms() + 750U;
        pending_ack_.frames = std::move(frames.value);
      }
    }
  }
  xSemaphoreGive(tx_mutex_);
  return success;
}

bool BleNavTransport::send_from_bridge(
    const moto::ble::Message& message,
    std::uint8_t frame_flags,
    void* context) {
  return static_cast<BleNavTransport*>(context)->send_message(
      message, frame_flags);
}
