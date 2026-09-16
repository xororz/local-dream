#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
bash patches/apply.sh
mkdir -p build/tests
qnn_sdk="${QNN_SDK_ROOT:?Set QNN_SDK_ROOT to QAIRT 2.50.0.260828}"
sample="$qnn_sdk/examples/QNN/SampleApp/SampleApp/src"
g++ -std=c++17 -O0 -g -fsanitize=address,undefined -fno-omit-frame-pointer -ffunction-sections -fdata-sections -Wl,--gc-sections \
  -I src -I 3rdparty/MNN/include -I 3rdparty/stb \
  -I 3rdparty/json/include -I 3rdparty/json/include/nlohmann -I 3rdparty/tokenizers-cpp/include \
  -I 3rdparty/xtensor/include -I 3rdparty/xtl/include -I 3rdparty/xsimd/include \
  -I "$qnn_sdk/include/QNN" -I "$sample/Log" \
  tests/SdxlPromptTest.cpp "$sample/Log/Logger.cpp" "$sample/Log/LogUtils.cpp" \
  -o build/tests/sdxl-prompt-test
build/tests/sdxl-prompt-test build/tests/fixtures
g++ -std=c++17 -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -DXTENSOR_USE_XSIMD \
  -I src -I 3rdparty/xtensor/include -I 3rdparty/xtl/include -I 3rdparty/xsimd/include \
  tests/SchedulerTensorTest.cpp -o build/tests/scheduler-tensor-test
build/tests/scheduler-tensor-test
bash tests/run-vae-io.sh
