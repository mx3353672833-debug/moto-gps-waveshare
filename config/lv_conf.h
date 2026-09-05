#ifndef LV_CONF_H
#define LV_CONF_H

/* Keep this file shared by WebAssembly and ESP32 builds. Platform-specific
 * drivers belong outside LVGL and outside the shared UI. */
#define LV_COLOR_DEPTH 16
#define LV_DEF_REFR_PERIOD 33
#define LV_DPI_DEF 130

#define LV_USE_LOG 1
#define LV_LOG_LEVEL LV_LOG_LEVEL_WARN

#define LV_USE_OS LV_OS_NONE
#define LV_USE_STDLIB_MALLOC LV_STDLIB_BUILTIN
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
