cmake_minimum_required(VERSION 3.22.1)

project(moto_android LANGUAGES CXX)

if(NOT MOTO_REPOSITORY_ROOT)
  message(FATAL_ERROR "MOTO_REPOSITORY_ROOT must point at the moto-gps-waveshare checkout")
endif()

set(MOTO_SHARED "${MOTO_REPOSITORY_ROOT}/shared")

# Reuse the exact shared C++ targets the iOS bridge, ESP32 firmware and host
# tests build. Only the JNI adapter below is Android-specific.
add_subdirectory("${MOTO_SHARED}/coordinates" "${CMAKE_BINARY_DIR}/shared/coordinates")
add_subdirectory("${MOTO_SHARED}/nav_core" "${CMAKE_BINARY_DIR}/shared/nav_core")
add_subdirectory("${MOTO_SHARED}/nav_app" "${CMAKE_BINARY_DIR}/shared/nav_app")
add_subdirectory("${MOTO_SHARED}/ble_protocol" "${CMAKE_BINARY_DIR}/shared/ble_protocol")

add_library(moto_jni SHARED moto_jni.cpp)

target_compile_features(moto_jni PRIVATE cxx_std_17)
target_compile_options(moto_jni PRIVATE -Wall -Wextra -fno-exceptions -fno-rtti)
target_link_libraries(moto_jni PRIVATE
  moto_nav_app
  moto_nav_core
  moto_ble_protocol
  moto_coordinates
  log
)
