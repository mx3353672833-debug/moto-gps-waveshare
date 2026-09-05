cmake_minimum_required(VERSION 3.16)

if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
  project(moto_coordinates LANGUAGES CXX)
endif()

add_library(moto_coordinates STATIC
  coordinates.cpp
)

target_include_directories(moto_coordinates
  PUBLIC
    "${CMAKE_CURRENT_LIST_DIR}/include"
)

target_compile_features(moto_coordinates PUBLIC cxx_std_17)

if(MSVC)
  target_compile_options(moto_coordinates PRIVATE /W4 /GR-)
else()
  target_compile_options(moto_coordinates PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fno-exceptions
    -fno-rtti
  )
endif()
