cmake_minimum_required(VERSION 3.18)
project(dit_engine C CXX)

# Built as its own project, not a subdirectory of the core: stable-diffusion.cpp
# and the ggml Hexagon backend want a newer -march and a higher API level than
# the core, which still has to run on every device we support. The two only
# share include/DitEngine.h.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(SDCPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../3rdparty/stable-diffusion.cpp")

set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# The skels are cross-compiled for the DSP by the Hexagon toolchain; keep the
# prebuilt FastRPC libraries for the Android host side.
set(PREBUILT_LIB_DIR "android_aarch64" CACHE STRING "" FORCE)

add_subdirectory("${SDCPP_DIR}" sdcpp EXCLUDE_FROM_ALL)

add_library(dit_engine SHARED DitEngine.cpp)

target_include_directories(dit_engine PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../include"
    "${SDCPP_DIR}/include"
)

target_link_libraries(dit_engine PRIVATE stable-diffusion log)

# One exported symbol; everything else stays internal so the core cannot drift
# into calling stable-diffusion.cpp directly. -fvisibility only covers this
# file's own objects, so the version script is what actually hides the symbols
# pulled in from libstable-diffusion.a and ggml.
target_compile_options(dit_engine PRIVATE -fvisibility=hidden)
target_link_options(dit_engine PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/dit_engine.map"
    "-Wl,-s"
)

set_target_properties(dit_engine PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib/${CMAKE_ANDROID_ARCH_ABI}"
)

# The DSP-side skels are loaded by FastRPC at run time, so nothing links them
# and EXCLUDE_FROM_ALL would otherwise leave them unbuilt. They ship in the
# same package as the engine, so tie them to this target. The Android catalog
# starts at SM8750 (HTP v79), so older skels would only add APK size.
foreach(htp_version v79 v81)
    if(TARGET htp-${htp_version})
        add_dependencies(dit_engine htp-${htp_version})
    endif()
endforeach()
