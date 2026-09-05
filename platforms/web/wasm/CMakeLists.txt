add_executable(moto_gps_web
    main_web.cpp
)

target_link_libraries(moto_gps_web PRIVATE
    moto_coordinates
    moto_nav_app
    moto_nav_presenter
    moto_nav_ui
)
target_compile_features(moto_gps_web PRIVATE cxx_std_17)

target_compile_options(moto_gps_web PRIVATE
    "-sUSE_SDL=2"
    "-O2"
)

target_link_options(moto_gps_web PRIVATE
    "-sUSE_SDL=2"
    "-sALLOW_MEMORY_GROWTH=1"
    "-sINITIAL_MEMORY=33554432"
    "-sASSERTIONS=1"
    "-sEXPORTED_RUNTIME_METHODS=['ccall','cwrap']"
    "-sEXPORTED_FUNCTIONS=['_main','_moto_web_live_initialize','_moto_web_live_start','_moto_web_live_cancel','_moto_web_live_set_network','_moto_web_live_fix','_moto_web_live_tick','_moto_web_live_route_begin','_moto_web_live_route_add_point','_moto_web_live_route_add_maneuver','_moto_web_live_route_add_traffic','_moto_web_live_route_commit','_moto_web_live_route_failed','_moto_web_live_traffic_begin','_moto_web_live_traffic_add_segment','_moto_web_live_traffic_commit','_moto_web_live_traffic_failed','_moto_web_live_get_nav_state','_moto_web_live_get_remaining_distance','_moto_web_live_get_remaining_duration','_moto_web_live_get_route_generation','_moto_web_live_get_route_progress','_moto_web_live_get_off_route','_moto_web_set_scenario','_moto_web_set_network','_moto_web_set_traffic','_moto_web_set_off_route','_moto_web_set_progress','_moto_web_set_page','_moto_web_set_heading_offset','_moto_web_get_page']"
)

set_target_properties(moto_gps_web PROPERTIES
    OUTPUT_NAME "moto-gps-device"
    SUFFIX ".js"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/platforms/web/shell/runtime"
)
