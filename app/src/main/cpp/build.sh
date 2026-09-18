#!/usr/bin/env bash
set -e
# The presets set CMAKE_POLICY_VERSION_MINIMUM because MNN, msgpack and
# sentencepiece still declare cmake_minimum_required below what CMake 4
# accepts. Keeps the pinned submodules buildable as-is, with no working-tree
# patches to carry.
cmake --preset android-release "$@"
cmake --build --preset android-release

mkdir -p ../assets/qnnlibs ../jniLibs/arm64-v8a
cp build/android/qnnlibs/*.so ../assets/qnnlibs/
cp build/android/bin/arm64-v8a/libstable_diffusion_core.so ../jniLibs/arm64-v8a/
mkdir -p ../assets/licenses/qnn-2.50.0.260828
cp build/android/qnn-notices/* ../assets/licenses/qnn-2.50.0.260828/
(cd ../assets/qnnlibs && sha256sum *.so) > ../assets/licenses/qnn-2.50.0.260828/SHA256SUMS
