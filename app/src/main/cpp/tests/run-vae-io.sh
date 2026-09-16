#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
qnn_sdk="${QNN_SDK_ROOT:?Set QNN_SDK_ROOT to QAIRT 2.50.0.260828}"
test_build="$PWD/build/tests/vae-io"
sample="$test_build/3rdparty/SampleApp/src"
mkdir -p "$sample"
cp -R "$qnn_sdk/examples/QNN/SampleApp/SampleApp/src/." "$sample/"
git -C "$(git rev-parse --show-toplevel)" apply --unsafe-paths \
  --directory="$test_build" "$PWD/SampleApp.patch"
g++ -std=c++17 -O0 -g -fsanitize=address,undefined -fno-omit-frame-pointer \
  -ffunction-sections -fdata-sections -Wl,--gc-sections \
  -I src -I 3rdparty/MNN/include -I 3rdparty/stb \
  -I "$qnn_sdk/include/QNN" -I "$sample" -I "$sample/Log" \
  -I "$sample/PAL/include" -I "$sample/Utils" -I "$sample/WrapperUtils" \
  -I "$sample/CachingUtil" \
  tests/VaeTensorIoTest.cpp "$sample/QnnSampleApp.cpp" \
  "$sample"/Log/*.cpp "$sample"/PAL/src/linux/*.cpp \
  "$sample"/PAL/src/common/*.cpp "$sample"/Utils/*.cpp \
  "$sample"/WrapperUtils/*.cpp -ldl -pthread -o "$test_build/vae-tensor-io-test"
"$test_build/vae-tensor-io-test"
