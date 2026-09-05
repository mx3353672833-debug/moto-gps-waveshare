if(NOT TARGET moto_nav_core)
  add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../nav_core"
    "${CMAKE_CURRENT_BINARY_DIR}/nav_core"
  )
endif()

if(NOT TARGET moto_nav_ui)
  add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../nav_ui"
    "${CMAKE_CURRENT_BINARY_DIR}/nav_ui"
  )
endif()

add_library(moto_nav_presenter STATIC
  src/moto_nav_presenter.cpp
)

target_include_directories(moto_nav_presenter PUBLIC include)
target_link_libraries(moto_nav_presenter PUBLIC moto_nav_core moto_nav_ui)
target_compile_features(moto_nav_presenter PUBLIC cxx_std_17)

if(MSVC)
  target_compile_options(moto_nav_presenter PRIVATE /W4)
else()
  target_compile_options(moto_nav_presenter PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fno-exceptions
    -fno-rtti
  )
endif()
