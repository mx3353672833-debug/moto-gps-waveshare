cmake_minimum_required(VERSION 3.16)

if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
  project(moto_nav_native_tests LANGUAGES CXX)
endif()

enable_testing()

if(NOT TARGET moto_nav_core)
  add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../../shared/nav_core"
    "${CMAKE_CURRENT_BINARY_DIR}/nav_core"
  )
endif()

if(NOT TARGET moto_coordinates)
  add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../../shared/coordinates"
    "${CMAKE_CURRENT_BINARY_DIR}/coordinates"
  )
endif()

if(NOT TARGET moto_nav_app)
  add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../../shared/nav_app"
    "${CMAKE_CURRENT_BINARY_DIR}/nav_app"
  )
endif()

set(MOTO_USING_NAV_UI_TEST_STUB OFF)
if(NOT TARGET moto_nav_ui)
  # Mapping tests do not need an LVGL display. This ABI-compatible stub keeps
  # the standalone native test build small and lets apply_to_lvgl() be probed.
  add_library(moto_nav_ui STATIC moto_nav_ui_stub.cpp)
  target_include_directories(
    moto_nav_ui PUBLIC
    "${CMAKE_CURRENT_LIST_DIR}/../../shared/nav_ui/include"
  )
  target_compile_features(moto_nav_ui PUBLIC cxx_std_17)
  set(MOTO_USING_NAV_UI_TEST_STUB ON)
endif()

if(NOT TARGET moto_nav_presenter)
  add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../../shared/nav_presenter"
    "${CMAKE_CURRENT_BINARY_DIR}/nav_presenter"
  )
endif()

if(NOT TARGET moto_ble_protocol)
  add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../../shared/ble_protocol"
    "${CMAKE_CURRENT_BINARY_DIR}/ble_protocol"
  )
endif()

add_executable(nav_core_tests nav_core_tests.cpp)
target_link_libraries(nav_core_tests PRIVATE moto_nav_core moto_coordinates)
target_compile_features(nav_core_tests PRIVATE cxx_std_17)

add_executable(nav_core_fixture_demo nav_core_fixture_demo.cpp)
target_link_libraries(nav_core_fixture_demo PRIVATE moto_nav_core moto_coordinates)
target_compile_features(nav_core_fixture_demo PRIVATE cxx_std_17)

add_executable(nav_app_tests nav_app_tests.cpp)
target_link_libraries(nav_app_tests PRIVATE moto_nav_app)
target_compile_features(nav_app_tests PRIVATE cxx_std_17)

add_executable(nav_presenter_tests nav_presenter_tests.cpp)
target_link_libraries(nav_presenter_tests PRIVATE moto_nav_presenter)
target_compile_features(nav_presenter_tests PRIVATE cxx_std_17)
if(MOTO_USING_NAV_UI_TEST_STUB)
  target_compile_definitions(nav_presenter_tests PRIVATE
    MOTO_NAV_UI_TEST_STUB=1
  )
endif()

add_executable(ble_protocol_tests ble_protocol_tests.cpp)
target_link_libraries(ble_protocol_tests PRIVATE moto_ble_protocol)
target_compile_features(ble_protocol_tests PRIVATE cxx_std_17)
target_compile_definitions(ble_protocol_tests PRIVATE
  "MOTO_BLE_GOLDEN_FIXTURE=\"${CMAKE_CURRENT_LIST_DIR}/../../shared/protocol/fixtures/ble-navigation-v1.golden.txt\""
)

add_executable(connection_epoch_gate_tests connection_epoch_gate_tests.cpp)
target_include_directories(connection_epoch_gate_tests PRIVATE
  "${CMAKE_CURRENT_LIST_DIR}/../../platforms/esp32/main"
)
target_compile_features(connection_epoch_gate_tests PRIVATE cxx_std_17)

add_executable(motion_heading_fusion_tests motion_heading_fusion_tests.cpp)
target_include_directories(motion_heading_fusion_tests PRIVATE
  "${CMAKE_CURRENT_LIST_DIR}/../../platforms/esp32/main"
)
target_compile_features(motion_heading_fusion_tests PRIVATE cxx_std_17)

find_package(Threads REQUIRED)
add_executable(phone_nav_bridge_tests
  phone_nav_bridge_tests.cpp
  esp32_host_stubs/phone_nav_bridge_platform_stubs.cpp
  "${CMAKE_CURRENT_LIST_DIR}/../../platforms/esp32/main/phone_nav_bridge.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../shared/nav_presenter/src/moto_nav_presenter.cpp"
)
target_include_directories(phone_nav_bridge_tests PRIVATE
  "${CMAKE_CURRENT_LIST_DIR}/esp32_host_stubs"
  "${CMAKE_CURRENT_LIST_DIR}"
  "${CMAKE_CURRENT_LIST_DIR}/../../platforms/esp32/main"
  "${CMAKE_CURRENT_LIST_DIR}/../../shared/nav_presenter/include"
  "${CMAKE_CURRENT_LIST_DIR}/../../shared/nav_ui/include"
)
target_link_libraries(phone_nav_bridge_tests PRIVATE
  moto_ble_protocol
  moto_nav_core
  Threads::Threads
)
target_compile_features(phone_nav_bridge_tests PRIVATE cxx_std_17)

add_test(NAME nav_core_tests COMMAND nav_core_tests)
add_test(NAME nav_app_tests COMMAND nav_app_tests)
add_test(NAME nav_presenter_tests COMMAND nav_presenter_tests)
add_test(NAME ble_protocol_tests COMMAND ble_protocol_tests)
add_test(NAME connection_epoch_gate_tests COMMAND connection_epoch_gate_tests)
add_test(NAME motion_heading_fusion_tests COMMAND motion_heading_fusion_tests)
add_test(NAME phone_nav_bridge_tests COMMAND phone_nav_bridge_tests)
