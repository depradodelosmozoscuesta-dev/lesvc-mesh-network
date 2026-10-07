# LESVC Mesh – native layer: ggwave (vendored, pinned) + small JNI bridge.
# Used by the Android build (externalNativeBuild) and by the host test build
# (tools/build-host-jni.sh) so both run exactly the same C/C++ code.
cmake_minimum_required(VERSION 3.18)
# (find_path REQUIRED needs CMake >= 3.18)
project(lesvc_ggwave CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(ggwave STATIC ggwave/ggwave.cpp)
target_include_directories(ggwave PUBLIC ggwave/include ggwave)
if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(ggwave PRIVATE -O3)
endif()

add_library(lesvc_ggwave SHARED ggwave_jni.cpp)
target_link_libraries(lesvc_ggwave PRIVATE ggwave)

if (ANDROID)
    find_library(log-lib log)
    target_link_libraries(lesvc_ggwave PRIVATE ${log-lib})
    # 16 KB page-size compatibility (Android 15+ devices)
    target_link_options(lesvc_ggwave PRIVATE "-Wl,-z,max-page-size=16384")
else()
    # Host build for JVM unit tests: needs the JDK's JNI headers (jni.h).
    if (NOT LESVC_JAVA_HOME)
        set(LESVC_JAVA_HOME "$ENV{JAVA_HOME}")
    endif()
    find_path(LESVC_JNI_H jni.h HINTS "${LESVC_JAVA_HOME}/include" REQUIRED)
    file(GLOB LESVC_JNI_MD_DIRS "${LESVC_JNI_H}/linux" "${LESVC_JNI_H}/darwin")
    target_include_directories(lesvc_ggwave PRIVATE ${LESVC_JNI_H} ${LESVC_JNI_MD_DIRS})
endif()
