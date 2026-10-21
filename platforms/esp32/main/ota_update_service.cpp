#include "ota_update_service.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

#include "board_port.h"
#include "esp_app_desc.h"
#include "esp_event.h"
#include "esp_http_server.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_ota_ops.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "moto_nav_ui.h"

namespace {
constexpr char kTag[] = "ota_service";
static bool s_service_running = false;
static httpd_handle_t s_server = nullptr;

void reboot_task(void *) {
  vTaskDelay(pdMS_TO_TICKS(1000));
  esp_restart();
}

esp_err_t ota_post_handler(httpd_req_t *req) {
  char buf[1024];
  int received = 0;
  int remaining = req->content_len;
  esp_ota_handle_t ota_handle = 0;
  const esp_partition_t *update_partition =
      esp_ota_get_next_update_partition(nullptr);

  if (update_partition == nullptr) {
    ESP_LOGE(kTag, "No OTA partition found");
    httpd_resp_send_500(req);
    return ESP_FAIL;
  }

  ESP_LOGI(kTag,
           "Writing OTA to partition subtype %d at offset 0x%lx, content len: "
           "%d",
           update_partition->subtype,
           (unsigned long)update_partition->address, remaining);

  bool is_first_chunk = true;
  std::uint64_t last_ui_update_ms = 0;
  int total_len = req->content_len;

  while (remaining > 0) {
    int recv_len =
        httpd_req_recv(req, buf, std::min((std::size_t)remaining, sizeof(buf)));
    if (recv_len <= 0) {
      if (recv_len == HTTPD_SOCK_ERR_TIMEOUT) {
        continue;
      }
      ESP_LOGE(kTag, "File receive failed or closed unexpectedly");
      if (ota_handle != 0) esp_ota_abort(ota_handle);
      httpd_resp_send_500(req);
      return ESP_FAIL;
    }

    if (is_first_chunk) {
      if (static_cast<std::size_t>(recv_len) >= sizeof(esp_app_desc_t)) {
        esp_app_desc_t new_app_desc;
        std::memcpy(&new_app_desc, buf, sizeof(esp_app_desc_t));
        if (new_app_desc.magic_word != ESP_APP_DESC_MAGIC_WORD) {
          ESP_LOGE(kTag, "Invalid app image magic word: 0x%08lx",
                   (unsigned long)new_app_desc.magic_word);
          httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST,
                              "Invalid image header");
          return ESP_FAIL;
        }
        ESP_LOGI(kTag, "New firmware version: %s, project: %s",
                 new_app_desc.version, new_app_desc.project_name);
      }

      esp_err_t err = esp_ota_begin(update_partition,
                                    OTA_WITH_SEQUENTIAL_WRITES, &ota_handle);
      if (err != ESP_OK) {
        ESP_LOGE(kTag, "esp_ota_begin failed (%s)", esp_err_to_name(err));
        httpd_resp_send_500(req);
        return ESP_FAIL;
      }
      is_first_chunk = false;
    }

    esp_err_t err = esp_ota_write(ota_handle, buf, recv_len);
    if (err != ESP_OK) {
      ESP_LOGE(kTag, "esp_ota_write failed (%s)", esp_err_to_name(err));
      esp_ota_abort(ota_handle);
      httpd_resp_send_500(req);
      return ESP_FAIL;
    }

    received += recv_len;
    remaining -= recv_len;

    std::uint64_t now_ms =
        static_cast<std::uint64_t>(esp_timer_get_time()) / 1000U;
    if (now_ms - last_ui_update_ms >= 250 || remaining == 0) {
      last_ui_update_ms = now_ms;
      std::uint8_t percent =
          total_len > 0 ? static_cast<std::uint8_t>(received * 100 / total_len)
                        : 0;
      char msg[48];
      std::snprintf(msg, sizeof(msg), "Caricamento: %u%%", percent);
      if (board_port_lock(UINT32_MAX)) {
        moto_nav_ui_set_ota_progress(percent, msg);
        board_port_unlock();
      }
    }
  }

  esp_err_t err = esp_ota_end(ota_handle);
  if (err != ESP_OK) {
    ESP_LOGE(kTag, "esp_ota_end failed (%s)", esp_err_to_name(err));
    httpd_resp_send_500(req);
    return ESP_FAIL;
  }

  err = esp_ota_set_boot_partition(update_partition);
  if (err != ESP_OK) {
    ESP_LOGE(kTag, "esp_ota_set_boot_partition failed (%s)",
             esp_err_to_name(err));
    httpd_resp_send_500(req);
    return ESP_FAIL;
  }

  ESP_LOGI(kTag, "OTA update successful! Rebooting in 1 second...");
  if (board_port_lock(UINT32_MAX)) {
    moto_nav_ui_set_ota_progress(100, "Aggiornamento OK! Riavvio...");
    board_port_unlock();
  }

  httpd_resp_sendstr(req, "OTA update successful. Device is restarting...");

  xTaskCreate(reboot_task, "reboot_task", 2048, nullptr, 5, nullptr);
  return ESP_OK;
}
}  // namespace

extern "C" esp_err_t ota_update_service_start(void) {
  if (s_service_running) {
    return ESP_OK;
  }

  ESP_LOGI(kTag, "Starting SoftAP MOTO-GPS-OTA and HTTP OTA Server...");

  // Initialize network interfaces if not already initialized
  esp_err_t ret = esp_netif_init();
  if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
    ESP_LOGE(kTag, "esp_netif_init failed: %s", esp_err_to_name(ret));
    return ret;
  }

  ret = esp_event_loop_create_default();
  if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
    ESP_LOGE(kTag, "esp_event_loop_create_default failed: %s",
             esp_err_to_name(ret));
    return ret;
  }

  esp_netif_create_default_wifi_ap();

  wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
  ret = esp_wifi_init(&cfg);
  if (ret != ESP_OK) {
    ESP_LOGE(kTag, "esp_wifi_init failed: %s", esp_err_to_name(ret));
    return ret;
  }

  wifi_config_t wifi_config = {};
  std::strcpy(reinterpret_cast<char *>(wifi_config.ap.ssid), "MOTO-GPS-OTA");
  wifi_config.ap.ssid_len = std::strlen("MOTO-GPS-OTA");
  std::strcpy(reinterpret_cast<char *>(wifi_config.ap.password), "motogps1234");
  wifi_config.ap.channel = 1;
  wifi_config.ap.max_connection = 4;
  wifi_config.ap.authmode = WIFI_AUTH_WPA2_PSK;

  if (std::strlen("motogps1234") == 0) {
    wifi_config.ap.authmode = WIFI_AUTH_OPEN;
  }

  ret = esp_wifi_set_mode(WIFI_MODE_AP);
  if (ret != ESP_OK) {
    ESP_LOGE(kTag, "esp_wifi_set_mode failed: %s", esp_err_to_name(ret));
    return ret;
  }

  ret = esp_wifi_set_config(WIFI_IF_AP, &wifi_config);
  if (ret != ESP_OK) {
    ESP_LOGE(kTag, "esp_wifi_set_config failed: %s", esp_err_to_name(ret));
    return ret;
  }

  ret = esp_wifi_start();
  if (ret != ESP_OK) {
    ESP_LOGE(kTag, "esp_wifi_start failed: %s", esp_err_to_name(ret));
    return ret;
  }

  // Start HTTP server
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;

  ret = httpd_start(&s_server, &config);
  if (ret != ESP_OK) {
    ESP_LOGE(kTag, "httpd_start failed: %s", esp_err_to_name(ret));
    return ret;
  }

  httpd_uri_t ota_uri = {};
  ota_uri.uri = "/ota";
  ota_uri.method = HTTP_POST;
  ota_uri.handler = ota_post_handler;
  ota_uri.user_ctx = nullptr;

  httpd_register_uri_handler(s_server, &ota_uri);

  s_service_running = true;

  // Display OTA screen on UI
  if (board_port_lock(UINT32_MAX)) {
    moto_nav_ui_show_ota_screen();
    board_port_unlock();
  }

  ESP_LOGI(kTag, "SoftAP MOTO-GPS-OTA started, HTTP server listening on POST http://192.168.4.1/ota");
  return ESP_OK;
}

extern "C" bool ota_update_service_is_running(void) {
  return s_service_running;
}
