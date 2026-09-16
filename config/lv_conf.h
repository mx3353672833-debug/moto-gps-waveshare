#ifndef LV_CONF_H
#define LV_CONF_H

/* Keep this file shared by WebAssembly and ESP32 builds. Platform-specific
 * drivers belong outside LVGL and outside the shared UI. */
#define LV_COLOR_DEPTH 16
/* This definition overrides CONFIG_LV_DEF_REFR_PERIOD on ESP32 as well.
 * Match the shared map-motion timer; do not silently cap a 40 Hz UI at 30 Hz. */
#define LV_DEF_REFR_PERIOD 25
#define LV_DPI_DEF 130

#define LV_USE_LOG 1
#define LV_LOG_LEVEL LV_LOG_LEVEL_WARN

#define LV_USE_OS LV_OS_NONE
#define LV_USE_STDLIB_MALLOC LV_STDLIB_BUILTIN
/* Permit the board to attach a 2 MiB PSRAM pool without allocating that space
 * in internal BSS. LVGL's TLSF maximum block size includes this expansion
 * budget; leaving it at zero silently rejects a large lv_mem_add_pool(). */
#define LV_MEM_POOL_EXPAND_SIZE (2U * 1024U * 1024U)
#define LV_USE_STDLIB_STRING LV_STDLIB_BUILTIN
#define LV_USE_STDLIB_SPRINTF LV_STDLIB_BUILTIN

#define LV_FONT_MONTSERRAT_16 1
#define LV_FONT_MONTSERRAT_20 1
#define LV_FONT_MONTSERRAT_28 1
#define LV_FONT_MONTSERRAT_48 1
#define LV_FONT_SOURCE_HAN_SANS_SC_16_CJK 0

#define LV_USE_ARC 1
#define LV_USE_LABEL 1
#define LV_USE_LINE 1
#define LV_USE_BUTTON 1
#define LV_USE_ANIMIMG 0
#define LV_USE_CANVAS 0
#define LV_USE_LOTTIE 0
#define LV_USE_GIF 0

#if defined(__EMSCRIPTEN__)
#define LV_USE_SDL 1
#define LV_SDL_INCLUDE_PATH <SDL2/SDL.h>
#else
#define LV_USE_SDL 0
#endif

#define LV_BUILD_EXAMPLES 0
#define LV_BUILD_DEMOS 0

#endif /* LV_CONF_H */
