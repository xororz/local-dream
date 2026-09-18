#!/usr/bin/env bash
# Builds libdit_engine.so and stages it plus its HTP skels for APK packaging.
# Separate from the core build because this one also needs the Hexagon SDK.
#
#   HEXAGON_SDK_ROOT=/path/to/hexagon/6.6.0.0 \
#   ANDROID_NDK_ROOT=/path/to/ndk/29.0.14206865 \
#   bash dit/build.sh
#
# The SDK is also available inside the public toolchain image used by
# llama.cpp, ghcr.io/snapdragon-toolchain/arm64-android, if you would rather
# not install it: extract /opt/hexagon from there.
#
# On NixOS the SDK's prebuilt qaic (the FastRPC IDL compiler) needs libgmp:
#   LD_LIBRARY_PATH=$(nix eval --raw nixpkgs#gmp)/lib bash dit/build.sh
set -e

: "${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}"
: "${HEXAGON_SDK_ROOT:?set HEXAGON_SDK_ROOT}"

cd "$(dirname "$0")"

# ABI version the engine reports; must match DIT_ENGINE_ABI_VERSION.
ABI_VERSION=$(sed -n 's/^#define DIT_ENGINE_ABI_VERSION \([0-9]*\).*/\1/p' ../include/DitEngine.h)
: "${ABI_VERSION:?could not read DIT_ENGINE_ABI_VERSION}"

BUILD_DIR=build/android
# armv8.7a and API 28 are the engine's alone: the core still builds for every
# device the app supports, and the two only meet across the DitEngine ABI.
cmake -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="-march=armv8.7a+fp16+dotprod+i8mm -D_GNU_SOURCE" \
    -DCMAKE_CXX_FLAGS="-march=armv8.7a+fp16+dotprod+i8mm -D_GNU_SOURCE" \
    -DHEXAGON_SDK_ROOT="$HEXAGON_SDK_ROOT" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.10 \
    "$@"
cmake --build "$BUILD_DIR" -j "$(nproc)"

# The engine is an arm64 library the core dlopens, so it belongs in jniLibs and
# lands in nativeLibraryDir.
JNI_DIR="$(cd ../.. && pwd)/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
cp "$BUILD_DIR/lib/arm64-v8a/libdit_engine.so" "$JNI_DIR/"

# The skels are Hexagon DSP binaries (ELF32, QUALCOMM DSP6), not Android
# libraries: nothing on the CPU side ever loads them, FastRPC hands them to the
# DSP. They go through assets and are copied to the runtime directory at
# startup, the same path the QNN skels take, so packaging never depends on the
# installer ignoring their architecture. The v79/v81 pair covers SM8750 and
# the newer devices accepted by the app.
ASSET_DIR="$(cd ../.. && pwd)/assets/ditlibs"
mkdir -p "$ASSET_DIR"
rm -f "$ASSET_DIR/libggml-htp-v73.so" "$ASSET_DIR/libggml-htp-v75.so"
cp "$BUILD_DIR"/sdcpp/ggml/src/ggml-hexagon/libggml-htp-v79.so \
   "$BUILD_DIR"/sdcpp/ggml/src/ggml-hexagon/libggml-htp-v81.so \
   "$ASSET_DIR/"
ls -la "$JNI_DIR" "$ASSET_DIR"
