# SPDX-License-Identifier: GPL-3.0-or-later
include(FetchContent)
FetchContent_Declare(
  googletest
  GIT_REPOSITORY https://github.com/google/googletest.git
  GIT_TAG v1.17.0
  GIT_SHALLOW TRUE
)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

add_executable(daybreak_engine_tests engine_test.cpp)
target_link_libraries(daybreak_engine_tests PRIVATE daybreak_core GTest::gtest_main)
target_compile_options(daybreak_engine_tests PRIVATE -Wall -Wextra)

include(GoogleTest)
gtest_discover_tests(daybreak_engine_tests)

# Compile-only check of the JNI bridge against stub Android headers.
find_package(JNI QUIET)
if(JNI_FOUND)
  add_library(daybreak_jni_syntax_check OBJECT ../src/jni/engine_jni.cpp)
  target_include_directories(daybreak_jni_syntax_check PRIVATE stubs ${JNI_INCLUDE_DIRS})
  target_link_libraries(daybreak_jni_syntax_check PRIVATE daybreak_core)
  target_compile_options(daybreak_jni_syntax_check PRIVATE -Wall -Wextra)
endif()
