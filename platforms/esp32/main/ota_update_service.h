#pragma once

#include <stdbool.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Starts the Wi-Fi SoftAP (SSID: MOTO-GPS-OTA) and HTTP OTA server on POST /ota.
 * Safe to call multiple times; returns ESP_OK if already running.
 */
esp_err_t ota_update_service_start(void);

/**
 * Returns true if the OTA update service is currently running.
 */
bool ota_update_service_is_running(void);

#ifdef __cplusplus
}
#endif
