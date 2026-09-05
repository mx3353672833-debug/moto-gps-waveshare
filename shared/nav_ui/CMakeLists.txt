if(NOT TARGET lvgl)
    add_subdirectory(${CMAKE_SOURCE_DIR}/third_party/lvgl ${CMAKE_BINARY_DIR}/third_party/lvgl)
endif()

if(EMSCRIPTEN)
    # The SDL headers are supplied by Emscripten only when the port is enabled
    # for every translation unit that includes the LVGL SDL driver.
    target_compile_options(lvgl PUBLIC "-sUSE_SDL=2")
endif()

add_library(moto_nav_ui STATIC
    assets/moto_font_nav_16.c
    src/moto_nav_ui.cpp
)

target_include_directories(moto_nav_ui PUBLIC include)
target_link_libraries(moto_nav_ui PUBLIC lvgl)
target_compile_features(moto_nav_ui PUBLIC cxx_std_17)
target_compile_options(moto_nav_ui PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:-Wall>
    $<$<COMPILE_LANGUAGE:CXX>:-Wextra>
    $<$<COMPILE_LANGUAGE:CXX>:-Wpedantic>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>
)
