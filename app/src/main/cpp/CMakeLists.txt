cmake_minimum_required(VERSION 3.18)
project(libstable_diffusion_core.so C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build and package against the same QNN SDK revision.
set(QNN_SDK_ROOT "$ENV{QNN_SDK_ROOT}" CACHE PATH "Qualcomm AI Runtime SDK 2.50.0.260828")
set(SAMPLE_APP_DIR "${CMAKE_CURRENT_BINARY_DIR}/3rdparty/SampleApp")
file(COPY "${QNN_SDK_ROOT}/examples/QNN/SampleApp/SampleApp/"
    DESTINATION "${SAMPLE_APP_DIR}")
execute_process(
    COMMAND git apply --unsafe-paths --directory=${CMAKE_CURRENT_BINARY_DIR}
        "${CMAKE_CURRENT_SOURCE_DIR}/SampleApp.patch"
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../../../.."
    RESULT_VARIABLE patch_result
    ERROR_VARIABLE patch_error
)
if(NOT patch_result EQUAL 0)
    message(FATAL_ERROR "${patch_error}")
endif()

file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/qnnlibs")
file(COPY "${QNN_SDK_ROOT}/LICENSE.pdf" "${QNN_SDK_ROOT}/NOTICE.txt"
    "${QNN_SDK_ROOT}/QNN_NOTICE.txt"
    DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/qnn-notices")
foreach(lib QnnHtp QnnSystem)
    file(COPY "${QNN_SDK_ROOT}/lib/aarch64-android/lib${lib}.so"
        DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/qnnlibs")
endforeach()
foreach(arch 68 69 73 75 79 81)
    file(COPY "${QNN_SDK_ROOT}/lib/aarch64-android/libQnnHtpV${arch}Stub.so"
        "${QNN_SDK_ROOT}/lib/hexagon-v${arch}/unsigned/libQnnHtpV${arch}.so"
        "${QNN_SDK_ROOT}/lib/hexagon-v${arch}/unsigned/libQnnHtpV${arch}Skel.so"
        DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/qnnlibs")
endforeach()

if(WIN32)
    set(PLATFORM_NAME "windows")
    set(HOST_TAG "windows-x86_64")
else()
    set(PLATFORM_NAME "linux")
    set(HOST_TAG "linux-x86_64")
endif()

set(OUTPUT_SUFFIX ${CMAKE_ANDROID_ARCH_ABI})
set(PLATFORM_LIBS log android dl m c GLESv2 EGL)
set(ANDROID_TOOLCHAIN_ROOT ${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${HOST_TAG} CACHE PATH "Android NDK root path")

add_compile_options(
    -O3
    -Wall
    -fno-rtti
    -fPIC
    # -Werror
    -fvisibility=hidden
)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-error=for-loop-analysis")

# ZSTD
set(ZSTD_ROOT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/zstd)

set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "Build zstd programs" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "Build static zstd library" FORCE)
set(ZSTD_MULTITHREAD_SUPPORT OFF CACHE BOOL "Enable multithreading support" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "Build zstd tests" FORCE)

add_subdirectory(${ZSTD_ROOT_DIR}/build/cmake zstd EXCLUDE_FROM_ALL)

set(ZSTD_INCLUDE_DIR ${ZSTD_ROOT_DIR}/lib)

# MNN
set(MNN_ROOT_DIR 3rdparty/MNN)
set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID ON CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL ON CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
add_subdirectory(3rdparty/MNN)

set(XTENSOR_USE_XSIMD ON)

set(PACKAGE_INCLUDES
    ${MNN_ROOT_DIR}/include
    ${QNN_SDK_ROOT}/include/QNN
    ${CMAKE_CURRENT_SOURCE_DIR}/include/
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/cpp-httplib
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/json/include/nlohmann
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/json/include
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/stb
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/xtensor/include
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/xtl/include
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/xsimd/include
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/xtensor-blas/include
    ${ZSTD_INCLUDE_DIR}

    ${SAMPLE_APP_DIR}/src/
    ${SAMPLE_APP_DIR}/src/CachingUtil
    ${SAMPLE_APP_DIR}/src/Log
    ${SAMPLE_APP_DIR}/src/PAL/include
    ${SAMPLE_APP_DIR}/src/Utils
    ${SAMPLE_APP_DIR}/src/WrapperUtils
)

# tokenizers-cpp
set(TOKENZIER_CPP_PATH ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/tokenizers-cpp)
set(MSGPACK_BUILD_DOCS OFF CACHE BOOL "Build msgpack documentation" FORCE)

if(NOT DEFINED CMAKE_ANDROID_ARCH_ABI)
    set(CMAKE_ANDROID_ARCH_ABI "arm64-v8a" CACHE STRING "Android ABI" FORCE)
endif()
if(NOT DEFINED ANDROID_ABI)
    set(ANDROID_ABI ${CMAKE_ANDROID_ARCH_ABI} CACHE STRING "Android ABI" FORCE)
endif()

add_subdirectory(${TOKENZIER_CPP_PATH} tokenizers EXCLUDE_FROM_ALL)

# set(SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src)
set(SRC_DIR ${SAMPLE_APP_DIR}/src)
message(STATUS "SRC_DIR: ${SRC_DIR}")
set(SRC_DIR_LOG ${SRC_DIR}/Log)
set(SRC_DIR_PAL_LINUX ${SRC_DIR}/PAL/src/linux)
set(SRC_DIR_PAL_COMMON ${SRC_DIR}/PAL/src/common)
set(SRC_DIR_UTILS ${SRC_DIR}/Utils)
set(SRC_DIR_WRAPPER_UTILS ${SRC_DIR}/WrapperUtils)

# collect source files
file(GLOB SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main.cpp"
    # "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp"
    "${SRC_DIR}/QnnSampleApp.cpp"
    "${SRC_DIR_LOG}/*.cpp"
    "${SRC_DIR_PAL_LINUX}/*.cpp"
    "${SRC_DIR_PAL_COMMON}/*.cpp"
    "${SRC_DIR_UTILS}/*.cpp"
    "${SRC_DIR_WRAPPER_UTILS}/*.cpp"
)

if(QNN_DEBUG_ENABLE)
    add_compile_options(-O0 -g)
    add_definitions(-DQNN_API=)
else()
    add_definitions(-DQNN_API=__attribute__\(\(visibility\(\"default\"\)\)\))
endif()

# executable
add_executable(${PROJECT_NAME} ${SOURCES})
if(NOT QNN_DEBUG_ENABLE)
    target_link_options(${PROJECT_NAME} PRIVATE "-Wl,-s")
endif()
target_include_directories(${PROJECT_NAME} PRIVATE ${PACKAGE_INCLUDES})

set_target_properties(${PROJECT_NAME}
    PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin/${OUTPUT_SUFFIX}"
)

target_compile_definitions(${PROJECT_NAME}
    PRIVATE
    XTENSOR_USE_XSIMD
)

# link
target_link_libraries(${PROJECT_NAME}
    PRIVATE
    ${PLATFORM_LIBS}
    MNN
    tokenizers_cpp
    libzstd
)
