cmake_minimum_required(VERSION 3.18)
project(limosa_xr_runtime CXX)

# The OpenXR runtime that the Khronos loader loads into VR games (see runtime.h).
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(SOURCES runtime.cpp input.cpp compositor.cpp gfx_gles.cpp gfx_vulkan.cpp)

if(ANDROID)
    add_library(limosaxr_runtime SHARED ${SOURCES} platform_android.cpp)
    target_include_directories(limosaxr_runtime PRIVATE include)
    target_link_libraries(limosaxr_runtime EGL GLESv3 android nativewindow log dl)
    # Games load us into their own process: keep our C++ runtime and symbols to ourselves.
    target_link_options(limosaxr_runtime PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--no-undefined")
else()
    # Desktop test build (Mesa EGL + any Vulkan driver).
    add_library(limosaxr_runtime SHARED ${SOURCES} platform_host.cpp)
    target_compile_definitions(limosaxr_runtime PRIVATE LIMOSA_HOST_TEST=1)
    target_include_directories(limosaxr_runtime PRIVATE include host_include)
    target_link_libraries(limosaxr_runtime EGL GLESv2 dl)
endif()
target_compile_options(limosaxr_runtime PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers)
