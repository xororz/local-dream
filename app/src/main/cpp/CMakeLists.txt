cmake_minimum_required(VERSION 3.18)
project(libstable_diffusion_core.so C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# QNN SDK PATH, MUST BE 2.29.0.241129
set(QNN_SDK_ROOT /data/qairt/2.29.0.241129)

file(COPY ${QNN_SDK_ROOT}/examples/QNN/SampleApp/
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
file(COPY ${QNN_SDK_ROOT}/lib/aarch64-android/libQnnHtp.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/aarch64-android/libQnnSystem.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/aarch64-android/libQnnHtpV69Stub.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/aarch64-android/libQnnHtpV73Stub.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/aarch64-android/libQnnHtpV75Stub.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/aarch64-android/libQnnHtpV79Stub.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/hexagon-v69/unsigned/libQnnHtpV69.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/hexagon-v69/unsigned/libQnnHtpV69Skel.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/hexagon-v73/unsigned/libQnnHtpV73.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/hexagon-v75/unsigned/libQnnHtpV75.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/hexagon-v75/unsigned/libQnnHtpV75Skel.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/hexagon-v79/unsigned/libQnnHtpV79.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)
file(COPY ${QNN_SDK_ROOT}/lib/hexagon-v79/unsigned/libQnnHtpV79Skel.so
    DESTINATION ${CMAKE_CURRENT_BINARY_DIR}/qnnlibs)

if(ANDROID)
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
        # -Werror
        -fvisibility=hidden
    )

    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-error=for-loop-analysis")
else()
    if(WIN32)
        set(PLATFORM_NAME "windows")
        set(OUTPUT_SUFFIX "x86_64-windows-msvc")
        set(PLATFORM_LIBS)
    else()
        set(PLATFORM_NAME "linux")
        set(OUTPUT_SUFFIX "x86_64-linux-clang")
        set(PLATFORM_LIBS dl pthread)
        set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -fuse-ld=lld-17")
    endif()
endif()

# MNN
set(MNN_ROOT_DIR 3rdparty/MNN)
set(MNN_BUILD_SHARED_LIBS OFF CACHE bool "" FORCE)
set(MNN_LOW_MEMORY ON CACHE bool "" FORCE)
set(MNN_CPU_WEIGHT_DEQUANT_GEMM ON CACHE bool "" FORCE)
set(MNN_BUILD_FOR_ANDROID ON CACHE bool "" FORCE)
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE bool "" FORCE)
set(MNN_USE_LOGCAT OFF CACHE bool "" FORCE)
add_subdirectory(3rdparty/MNN)

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

    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/CachingUtil
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/Log
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/PAL/include
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/Utils
    ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/SampleApp/src/WrapperUtils
)

# tokenizers-cpp
set(TOKENZIER_CPP_PATH ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/tokenizers-cpp)

if(ANDROID)
    if(NOT DEFINED CMAKE_ANDROID_ARCH_ABI)
        set(CMAKE_ANDROID_ARCH_ABI "arm64-v8a" CACHE STRING "Android ABI" FORCE)
    endif()
    if(NOT DEFINED ANDROID_ABI)
        set(ANDROID_ABI ${CMAKE_ANDROID_ARCH_ABI} CACHE STRING "Android ABI" FORCE)
    endif()
endif()

add_subdirectory(${TOKENZIER_CPP_PATH} tokenizers EXCLUDE_FROM_ALL)
# target_compile_options(tokenizers_cpp PRIVATE -Wno-for-loop-analysis)
# get_directory_property(targets DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/tokenizers-cpp BUILDSYSTEM_TARGETS)
# if(targets)
#     foreach(target ${targets})
#         get_target_property(type ${target} TYPE)
#         if(NOT type STREQUAL "INTERFACE_LIBRARY")
#             target_compile_options(${target} PRIVATE -Wno-for-loop-analysis)
#         endif()
#     endforeach()
# endif()


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

set(COMMON_COMPILE_OPTIONS
    -fno-rtti
    -fPIC
    -Wall
    # -Werror
)

if(NOT ANDROID)
    list(APPEND COMMON_COMPILE_OPTIONS
        -pthread
        -march=x86-64
    )
endif()

add_compile_options(${COMMON_COMPILE_OPTIONS})

if(QNN_DEBUG_ENABLE)
    add_compile_options(-O0 -g)
    add_definitions(-DQNN_API=)
else()
    add_definitions(-DQNN_API=__attribute__\(\(visibility\(\"default\"\)\)\))
endif()

# executable
add_executable(${PROJECT_NAME} ${SOURCES})
target_link_options(${PROJECT_NAME} PRIVATE "-Wl,-s")
target_include_directories(${PROJECT_NAME} PRIVATE ${PACKAGE_INCLUDES})

set_target_properties(${PROJECT_NAME}
    PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin/${OUTPUT_SUFFIX}"
)

# link
target_link_libraries(${PROJECT_NAME}
    PRIVATE
    ${PLATFORM_LIBS}
    MNN
    tokenizers_cpp
)
