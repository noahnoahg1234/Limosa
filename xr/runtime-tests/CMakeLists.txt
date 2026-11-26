cmake_minimum_required(VERSION 3.18)
project(limosa_xr_runtime_tests CXX)

# Builds the runtime for the desktop (LIMOSA_HOST_TEST) plus a test program that drives it
# with Vulkan and OpenGL ES. Needs Mesa (EGL surfaceless + llvmpipe) and a Vulkan driver such
# as lavapipe.
set(CMAKE_CXX_STANDARD 17)
add_subdirectory(../src/main/cpp runtime)

add_executable(test_runtime test_runtime.cpp)
target_include_directories(test_runtime PRIVATE ../src/main/cpp/include ../src/main/cpp/host_include)
target_link_libraries(test_runtime EGL GLESv2 dl)
