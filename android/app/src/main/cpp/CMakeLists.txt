# The on-device transcriber: whisper.cpp (vendored, CPU only) plus a small
# JNI wrapper the app calls from LocalEngine.kt.
cmake_minimum_required(VERSION 3.22.1)
project(scribewhisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# The static whisper and ggml libraries end up inside a shared library.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if (NOT CMAKE_BUILD_TYPE OR CMAKE_BUILD_TYPE STREQUAL "Debug")
    # The app ships as a debug APK, but an unoptimised whisper.cpp is many
    # times slower; always build this part optimised.
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

if (ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    # Dot-product and half-precision instructions roughly double the speed.
    # Every phone that runs a current Android has them; the app checks the
    # CPU before loading this library, so an older one simply goes without
    # the offline engine instead of crashing.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(whisper.cpp)

add_library(scribewhisper SHARED scribe_jni.cpp)
target_link_libraries(scribewhisper PRIVATE whisper)
if (ANDROID)
    target_link_libraries(scribewhisper PRIVATE log)
    # Smaller library, and a dead-code pass over the static parts.
    target_link_options(scribewhisper PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
else()
    find_package(JNI REQUIRED)
    target_include_directories(scribewhisper PRIVATE ${JNI_INCLUDE_DIRS})
endif()
