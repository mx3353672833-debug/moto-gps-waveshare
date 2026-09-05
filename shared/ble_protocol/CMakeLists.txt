cmake_minimum_required(VERSION 3.16)

if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
  project(moto_ble_protocol LANGUAGES CXX)
endif()

add_library(moto_ble_protocol STATIC
  src/ble_protocol.cpp
)

target_include_directories(moto_ble_protocol
  PUBLIC
    "${CMAKE_CURRENT_LIST_DIR}/include"
)

target_compile_features(moto_ble_protocol PUBLIC cxx_std_17)

if(MSVC)
  target_compile_options(moto_ble_protocol PRIVATE /W4 /GR-)
else()
  target_compile_options(moto_ble_protocol PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fno-exceptions
    -fno-rtti
  )
endif()
