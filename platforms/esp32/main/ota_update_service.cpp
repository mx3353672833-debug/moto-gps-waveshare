#include "ota_update_service.h"

#include <esp_err.h>
#include <esp_log.h>
#include <esp_ota_ops.h>
#include <esp_system.h>
#include <esp_wifi.h>
#include <esp_http_server.h>
#include <esp_timer.h>
#include <nvs_flash.h>

#include <algorithm>
#include <cstring>
#include <cstdio>

#include "moto_nav_ui.h"

static const char *TAG = "ota_service";
static httpd_handle_t server_handle = NULL;

namespace {

esp_err_t ota_post_handler(httpd_req_t *req) {
  char buf[1024];
  int remaining = req->content_len;
  
  const esp_partition_t *update_partition = esp_ota_get_next_update_partition(NULL);
  if (update_partition == NULL) {
    ESP_LOGE(TAG, "Nessuna partizione OTA disponibile per l'aggiornamento");
    httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA partition not found");
    return ESP_FAIL;
  }

  esp_ota_handle_t update_handle = 0;
  esp_err_t err = esp_ota_begin(update_partition, OTA_SIZE_UNKNOWN, &update_handle);
  if (err != ESP_OK) {
    ESP_LOGE(TAG, "esp_ota_begin fallito (%s)", esp_err_to_name(err));
    httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA begin failed");
    return ESP_FAIL;
  }

  ESP_LOGI(TAG, "Inizio scrittura sulla partizione OTA: %s", update_partition->label);

  int total_len = req->content_len;
  uint64_t last_ui_update_ms = 0;

  while (remaining > 0) {
    int recv_len = httpd_req_recv(req, buf, std::min(remaining, (int)sizeof(buf)));
    if (recv_len <= 0) {
      if (recv_len == HTTPD_SOCK_ERR_TIMEOUT) {
        continue;
      }
      esp_ota_end(update_handle);
      httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Socket receive error");
      return ESP_FAIL;
    }

    err = esp_ota_write(update_handle, (const void *)buf, recv_len);
    if (err != ESP_OK) {
      esp_ota_end(update_handle);
      httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA write failed");
      return ESP_FAIL;
    }

    remaining -= recv_len;

    // Aggiornamento UI throttled a 4 Hz (~250 ms)
    uint64_t now_ms = esp_timer_get_time() / 1000ULL;
    if (now_ms - last_ui_update_ms >= 250 || remaining == 0) {
      last_ui_update_ms = now_ms;
      if (total_len > 0) {
        uint8_t percent = (uint8_t)(((total_len - remaining) * 100) / total_len);
        char msg[48];
        snprintf(msg, sizeof(msg), "Aggiornamento: %u%%", percent);
        ESP_LOGI(TAG, "%s", msg);
      }
    }
  }

  if (esp_ota_end(update_handle) != ESP_OK) {
    ESP_LOGE(TAG, "esp_ota_end fallito!");
    httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA end failed");
    return ESP_FAIL;
  }

  err = esp_ota_set_boot_partition(update_partition);
  if (err != ESP_OK) {
    ESP_LOGE(TAG, "esp_ota_set_boot_partition fallito (%s)!", esp_err_to_name(err));
    httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Set boot partition failed");
    return ESP_FAIL;
  }

  ESP_LOGI(TAG, "Aggiornamento OTA completato con successo! Riavvio in corso...");
  httpd_resp_sendstr(req, "OTA Success! Rebooting...");

  vTaskDelay(pdMS_TO_TICKS(1000));
  esp_restart();

  return ESP_OK;
}

} // namespace

esp_err_t ota_update_service_start(void) {
  wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
  ESP_ERROR_CHECK(esp_wifi_init(&cfg));

  wifi_config_t wifi_config = {};
  strcpy((char *)wifi_config.ap.ssid, "MOTO-GPS-OTA");
  wifi_config.ap.ssid_len = strlen("MOTO-GPS-OTA");
  wifi_config.ap.max_connection = 4;
  wifi_config.ap.authmode = WIFI_AUTH_OPEN;

  ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
  ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &wifi_config));
  ESP_ERROR_CHECK(esp_wifi_start());

  ESP_LOGI(TAG, "Wi-Fi SoftAP avviato. SSID: MOTO-GPS-OTA, IP: 192.168.4.1");

  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  if (httpd_start(&server_handle, &config) == ESP_OK) {
    httpd_uri_t ota_uri = {
        .uri      = "/ota",
        .method   = HTTP_POST,
        .handler  = ota_post_handler,
        .user_ctx = NULL
    };
    httpd_register_uri_handler(server_handle, &ota_uri);
    ESP_LOGI(TAG, "Server HTTP OTA registrato su POST /ota");
    return ESP_OK;
  }

  ESP_LOGE(TAG, "Avvio server HTTP OTA fallito");
  return ESP_FAIL;
}
