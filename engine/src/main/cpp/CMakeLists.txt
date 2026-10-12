cmake_minimum_required(VERSION 3.16)
# AGP passes Debug or RelWithDebInfo. The encoder must behave identically in every variant (a
# resumed checkpoint primes a fresh encoder and continues the same stream), so libopus and the JNI
# shim always use the Release flags.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
project(cassini_audio LANGUAGES C CXX)
if(NOT OPUS_SOURCE OR NOT EXISTS "${OPUS_SOURCE}/CMakeLists.txt")
  message(FATAL_ERROR "OPUS_SOURCE does not point at the libopus source. Run scripts/setup.sh.")
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
add_subdirectory(${OPUS_SOURCE} opus)
add_library(cassini_opus SHARED opus-jni.cpp)
target_link_libraries(cassini_opus PRIVATE opus)
target_compile_features(cassini_opus PRIVATE cxx_std_17)
target_link_options(cassini_opus PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--no-undefined" "-Wl,--exclude-libs,ALL")
