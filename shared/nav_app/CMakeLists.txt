add_library(moto_nav_app STATIC
  nav_app.cpp
)

target_include_directories(moto_nav_app
  PUBLIC
    "${CMAKE_CURRENT_LIST_DIR}/include"
)

target_link_libraries(moto_nav_app PUBLIC moto_nav_core)
target_compile_features(moto_nav_app PUBLIC cxx_std_17)

if(MSVC)
  target_compile_options(moto_nav_app PRIVATE /W4)
else()
  target_compile_options(moto_nav_app PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fno-exceptions
    -fno-rtti
  )
endif()
