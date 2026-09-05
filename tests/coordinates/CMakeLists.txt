cmake_minimum_required(VERSION 3.16)

if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
  project(moto_coordinates_tests LANGUAGES CXX)
endif()

enable_testing()

if(NOT TARGET moto_coordinates)
  add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../../shared/coordinates"
    "${CMAKE_CURRENT_BINARY_DIR}/moto_coordinates"
  )
endif()

add_executable(moto_coordinates_tests coordinates_tests.cpp)
target_link_libraries(moto_coordinates_tests PRIVATE moto_coordinates)
target_compile_features(moto_coordinates_tests PRIVATE cxx_std_17)

if(MSVC)
  target_compile_options(moto_coordinates_tests PRIVATE /W4)
else()
  target_compile_options(moto_coordinates_tests PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fno-exceptions
    -fno-rtti
  )
endif()

add_test(NAME moto_coordinates_tests COMMAND moto_coordinates_tests)
