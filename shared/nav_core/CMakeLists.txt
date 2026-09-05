add_library(moto_nav_core STATIC
  nav_core.cpp
)

if(NOT TARGET moto_coordinates)
  add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../coordinates"
    "${CMAKE_CURRENT_BINARY_DIR}/coordinates"
  )
endif()

target_include_directories(moto_nav_core
  PUBLIC
    "${CMAKE_CURRENT_LIST_DIR}/.."
)

target_compile_features(moto_nav_core PUBLIC cxx_std_17)
target_link_libraries(moto_nav_core PRIVATE moto_coordinates)

if(MSVC)
  target_compile_options(moto_nav_core PRIVATE /W4)
else()
  target_compile_options(moto_nav_core PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fno-exceptions
    -fno-rtti
  )
endif()
