cmake_minimum_required(VERSION 3.20)

# This is an ESP-IDF project, intentionally separate from the host/Web CMake
# entry point at the repository root.
get_filename_component(MOTO_REPOSITORY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(MOTO_LVGL_COMPONENT_DIR "${MOTO_REPOSITORY_ROOT}/third_party/lvgl")
set(MOTO_LV_CONF_PATH "${MOTO_REPOSITORY_ROOT}/config/lv_conf.h")

# esp_lvgl_adapter 0.6.4 cannot discover the version of an override_path LVGL
# component during ESP-IDF's early requirements pass.  The component supports
# LVGL_VERSION as its explicit, inherited override; keep it pinned to the same
# checkout declared in main/idf_component.yml.
set(ENV{LVGL_VERSION} "9.5.0")

if(NOT EXISTS "${MOTO_LVGL_COMPONENT_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "LVGL submodule is missing. Run: git submodule update --init --recursive")
endif()

if(NOT EXISTS "${MOTO_LV_CONF_PATH}")
    message(FATAL_ERROR "Shared LVGL configuration not found: ${MOTO_LV_CONF_PATH}")
endif()

if(NOT DEFINED ENV{IDF_PATH} OR
   NOT EXISTS "$ENV{IDF_PATH}/tools/cmake/project.cmake")
    message(FATAL_ERROR
        "ESP-IDF is not exported. Source ESP-IDF's export.sh before configuring.")
endif()

# main/idf_component.yml overrides lvgl/lvgl to this exact checkout. Keeping
# the source selection in the Component Manager prevents the Waveshare BSP
# from resolving a second LVGL component while Web and firmware still share
# one lv_conf.h.
add_compile_definitions(LV_CONF_PATH="${MOTO_LV_CONF_PATH}")

if(NOT DEFINED IDF_TARGET)
    set(IDF_TARGET "esp32s3" CACHE STRING "ESP-IDF chip target")
endif()

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(moto_gps_esp32)
