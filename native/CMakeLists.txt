# Daybreak image engine.
# Builds for Android through the Gradle module :core:engine, and on a desktop for tests:
#   cmake -S native -B build-native -G Ninja -DDAYBREAK_BUILD_TESTS=ON
#   cmake --build build-native && ctest --test-dir build-native
# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(daybreak_engine LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(DAYBREAK_BUILD_TESTS "Build the desktop unit tests" OFF)
option(DAYBREAK_SANITIZE "Build with AddressSanitizer and UBSan (desktop)" OFF)
option(DAYBREAK_WERROR "Treat warnings in Daybreak code as errors" OFF)

if(DAYBREAK_SANITIZE AND NOT ANDROID)
  add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
  add_link_options(-fsanitize=address,undefined)
endif()

include(cmake/libjxl.cmake)

add_library(daybreak_core STATIC
  src/codec/jxl_codec.cpp
  src/image/resize.cpp
  src/meta/exif.cpp
  src/edit/edit.cpp
  src/burst/burst.cpp
)
target_include_directories(daybreak_core PUBLIC include)
target_link_libraries(daybreak_core PUBLIC jxl jxl_threads)
target_compile_options(daybreak_core PRIVATE -Wall -Wextra -Wshadow)
if(DAYBREAK_WERROR)
  target_compile_options(daybreak_core PRIVATE -Werror)
endif()

if(ANDROID)
  add_library(daybreak_engine SHARED src/jni/engine_jni.cpp)
  target_link_libraries(daybreak_engine PRIVATE daybreak_core jnigraphics log)
  target_compile_options(daybreak_engine PRIVATE -Wall -Wextra)
  # Keep the exported surface to the JNI entry points.
  target_link_options(daybreak_engine PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
endif()

if(DAYBREAK_BUILD_TESTS AND NOT ANDROID)
  enable_testing()
  add_subdirectory(tests)
endif()
