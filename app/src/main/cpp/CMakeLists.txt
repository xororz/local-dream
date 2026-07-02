cmake_minimum_required(VERSION 3.18)
project(libstable_diffusion_core.so C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# QNN SDK PATH
set(QNN_SDK_ROOT /data/qairt/2.39.0.250926)

function(first_existing_path out_var)
    foreach(candidate IN LISTS ARGN)
        if(EXISTS "${candidate}")
            set(${out_var} "${candidate}" PARENT_SCOPE)
            return()
        endif()
    endforeach()
    set(${out_var} "" PARENT_SCOPE)
endfunction()

first_existing_path(QNN_ROOT_CANDIDATE
    ${QNN_SDK_ROOT}/2.39.0.250926
    ${QNN_SDK_ROOT}
    ${QNN_SDK_ROOT}/QAIRT
    ${QNN_SDK_ROOT}/qairt
)
if(NOT QNN_ROOT_CANDIDATE)
    set(QNN_ROOT_CANDIDATE ${QNN_SDK_ROOT})
endif()

first_existing_path(QNN_INCLUDE_DIR
    ${QNN_ROOT_CANDIDATE}/include/QNN
    ${QNN_SDK_ROOT}/include/QNN
    ${QNN_ROOT_CANDIDATE}/QNN/include/QNN
    ${QNN_SDK_ROOT}/QNN/include/QNN
)
if(NOT QNN_INCLUDE_DIR)
    find_path(QNN_INCLUDE_DIR
        NAMES QnnTypes.h
        PATHS
            ${QNN_ROOT_CANDIDATE}
            ${QNN_SDK_ROOT}
        PATH_SUFFIXES
            include/QNN
            QNN/include/QNN
        NO_DEFAULT_PATH
    )
endif()
if(NOT QNN_INCLUDE_DIR)
    message(FATAL_ERROR "Could not locate QNN include directory under ${QNN_SDK_ROOT}")
endif()
get_filename_component(QNN_RESOLVED_SDK_ROOT "${QNN_INCLUDE_DIR}/../.." ABSOLUTE)
message(STATUS "Resolved QNN SDK root: ${QNN_RESOLVED_SDK_ROOT}")

first_existing_path(QNN_SAMPLEAPP_SOURCE
    ${QNN_RESOLVED_SDK_ROOT}/examples/QNN/SampleApp/SampleApp
    ${QNN_RESOLVED_SDK_ROOT}/examples/SampleApp/SampleApp
    ${QNN_RESOLVED_SDK_ROOT}/examples/QNN/SampleApp
    ${QNN_RESOLVED_SDK_ROOT}/SampleApp/SampleApp
    ${QNN_RESOLVED_SDK_ROOT}/SampleApp
)
if(NOT QNN_SAMPLEAPP_SOURCE)
    file(GLOB_RECURSE QNN_SAMPLEAPP_CANDIDATES LIST_DIRECTORIES true
        ${QNN_RESOLVED_SDK_ROOT}/*SampleApp
        ${QNN_RESOLVED_SDK_ROOT}/*/*SampleApp
        ${QNN_RESOLVED_SDK_ROOT}/*/*/*SampleApp)
    foreach(candidate IN LISTS QNN_SAMPLEAPP_CANDIDATES)
        if(EXISTS "${candidate}/src/QnnSampleApp.cpp")
            set(QNN_SAMPLEAPP_SOURCE "${candidate}")
            break()
        endif()
    endforeach()
endif()
if(NOT QNN_SAMPLEAPP_SOURCE)
    message(FATAL_ERROR "Could not locate QNN SampleApp under ${QNN_RESOLVED_SDK_ROOT}")
endif()

file(COPY ${QNN_SAMPLEAPP_SOURCE}/
    DESTINATION ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp)
execute_process(COMMAND git apply --directory=app/src/main/cpp/ --verbose ${CMAKE_CURRENT_SOURCE_DIR}/SampleApp.patch
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    RESULT_VARIABLE patch_result
    OUTPUT_VARIABLE patch_output
    ERROR_VARIABLE patch_error
)
message(STATUS "Patch: ${patch_result}")
message(STATUS "Patch: ${patch_output}")
message(STATUS "Patch: ${patch_error}")

make_directory(${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
set(QNN_ANDROID_LIB_DIR "")
foreach(candidate
    ${QNN_RESOLVED_SDK_ROOT}/lib/aarch64-android
    ${QNN_RESOLVED_SDK_ROOT}/lib/android
    ${QNN_RESOLVED_SDK_ROOT}/libs/aarch64-android
    ${QNN_RESOLVED_SDK_ROOT}/lib)
    if(EXISTS "${candidate}/libQnnHtp.so")
        set(QNN_ANDROID_LIB_DIR "${candidate}")
        break()
    endif()
endforeach()
if(NOT QNN_ANDROID_LIB_DIR)
    file(GLOB_RECURSE QNN_HTP_LIB_CANDIDATES
        ${QNN_RESOLVED_SDK_ROOT}/*/libQnnHtp.so
        ${QNN_RESOLVED_SDK_ROOT}/*/*/libQnnHtp.so
        ${QNN_RESOLVED_SDK_ROOT}/*/*/*/libQnnHtp.so)
    list(LENGTH QNN_HTP_LIB_CANDIDATES QNN_HTP_LIB_COUNT)
    if(QNN_HTP_LIB_COUNT GREATER 0)
        list(GET QNN_HTP_LIB_CANDIDATES 0 QNN_HTP_LIB_PATH)
        get_filename_component(QNN_ANDROID_LIB_DIR "${QNN_HTP_LIB_PATH}" DIRECTORY)
    endif()
endif()
if(NOT QNN_ANDROID_LIB_DIR)
    message(FATAL_ERROR "Could not locate Android QNN libraries under ${QNN_RESOLVED_SDK_ROOT}")
endif()

foreach(lib_name
    libQnnHtp.so
    libQnnSystem.so
    libQnnHtpV68Stub.so
    libQnnHtpV69Stub.so
    libQnnHtpV73Stub.so
    libQnnHtpV75Stub.so
    libQnnHtpV79Stub.so
    libQnnHtpV81Stub.so)
    if(NOT EXISTS "${QNN_ANDROID_LIB_DIR}/${lib_name}")
        message(FATAL_ERROR
            "Could not locate required Android QNN library ${lib_name} in ${QNN_ANDROID_LIB_DIR}")
    endif()
    file(COPY ${QNN_ANDROID_LIB_DIR}/${lib_name}
        DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
endforeach()

foreach(hex_ver v68 v69 v73 v75 v79 v81)
    string(TOUPPER "${hex_ver}" hex_ver_upper)
    foreach(lib_name
        libQnnHtp${hex_ver_upper}.so
        libQnnHtp${hex_ver_upper}Skel.so)
        first_existing_path(QNN_HEXAGON_LIB
            ${QNN_RESOLVED_SDK_ROOT}/lib/hexagon-${hex_ver}/unsigned/${lib_name}
            ${QNN_RESOLVED_SDK_ROOT}/lib/hexagon-${hex_ver}/${lib_name}
            ${QNN_RESOLVED_SDK_ROOT}/hexagon-${hex_ver}/unsigned/${lib_name}
        )
        if(NOT QNN_HEXAGON_LIB)
            file(GLOB_RECURSE QNN_HEXAGON_CANDIDATES
                ${QNN_RESOLVED_SDK_ROOT}/*/${lib_name}
                ${QNN_RESOLVED_SDK_ROOT}/*/*/${lib_name}
                ${QNN_RESOLVED_SDK_ROOT}/*/*/*/${lib_name})
            list(LENGTH QNN_HEXAGON_CANDIDATES QNN_HEXAGON_COUNT)
            if(QNN_HEXAGON_COUNT GREATER 0)
                list(GET QNN_HEXAGON_CANDIDATES 0 QNN_HEXAGON_LIB)
            endif()
        endif()
        if(NOT QNN_HEXAGON_LIB)
            message(FATAL_ERROR "Could not locate required Hexagon library ${lib_name}")
        endif()
        file(COPY ${QNN_HEXAGON_LIB}
            DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
    endforeach()
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
set(MNN_CPU_WEIGHT_DEQUANT_GEMM ON CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID ON CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL ON CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
add_subdirectory(3rdparty/MNN)

set(XTENSOR_USE_XSIMD ON)

set(PACKAGE_INCLUDES
    ${MNN_ROOT_DIR}/include
    ${QNN_INCLUDE_DIR}
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

    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/CachingUtil
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/Log
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/PAL/include
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/Utils
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/WrapperUtils
)

# tokenizers-cpp
set(TOKENZIER_CPP_PATH ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/tokenizers-cpp)

if(NOT DEFINED CMAKE_ANDROID_ARCH_ABI)
    set(CMAKE_ANDROID_ARCH_ABI "arm64-v8a" CACHE STRING "Android ABI" FORCE)
endif()
if(NOT DEFINED ANDROID_ABI)
    set(ANDROID_ABI ${CMAKE_ANDROID_ARCH_ABI} CACHE STRING "Android ABI" FORCE)
endif()

add_subdirectory(${TOKENZIER_CPP_PATH} tokenizers EXCLUDE_FROM_ALL)

# set(SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src)
set(SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src)
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
