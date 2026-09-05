cmake_minimum_required(VERSION 3.20)

project(moto_gps LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(MOTO_BUILD_WEB "Build the Emscripten browser simulator" ${EMSCRIPTEN})
option(MOTO_BUILD_TESTS "Build native navigation-core tests" ON)

set(LV_BUILD_CONF_PATH "${CMAKE_CURRENT_SOURCE_DIR}/config/lv_conf.h" CACHE PATH "LVGL configuration" FORCE)
set(CONFIG_LV_BUILD_DEMOS OFF CACHE BOOL "Do not build LVGL demos" FORCE)
set(CONFIG_LV_BUILD_EXAMPLES OFF CACHE BOOL "Do not build LVGL examples" FORCE)
set(CONFIG_LV_USE_THORVG_INTERNAL OFF CACHE BOOL "No vector animation engine in the navigation target" FORCE)

add_subdirectory(shared/coordinates EXCLUDE_FROM_ALL)
add_subdirectory(shared/nav_core EXCLUDE_FROM_ALL)
add_subdirectory(shared/nav_app EXCLUDE_FROM_ALL)
add_subdirectory(shared/nav_ui)
add_subdirectory(shared/nav_presenter)

if(MOTO_BUILD_WEB)
    if(NOT EMSCRIPTEN)
        message(FATAL_ERROR "MOTO_BUILD_WEB requires the Emscripten toolchain (use emcmake cmake ...)")
    endif()
    add_subdirectory(platforms/web/wasm)
endif()

if(MOTO_BUILD_TESTS AND NOT EMSCRIPTEN)
    enable_testing()
    add_subdirectory(tests/coordinates)
    add_subdirectory(tests/native)
endif()
