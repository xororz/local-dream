# Building Local Dream

The Android app builds and packages its native backend through Gradle. The
backend remains a separate executable, installed as `libstable_diffusion_core.so`.

## Prerequisites

These commands target a Linux build host.

- Android SDK platform 37 and Android NDK `29.0.14206865`.
- JDK 17 or newer supported by the Gradle wrapper.
- CMake 3.21 or newer, Ninja, Git, and a Bash environment.
- Rust/Cargo with the `aarch64-linux-android` target.
- Qualcomm AI Runtime SDK **2.50.0.260828**, installed separately.

```sh
git clone --recurse-submodules https://github.com/xororz/local-dream.git
cd local-dream
rustup target add aarch64-linux-android
```

Set the installed SDK paths in the untracked `local.properties` file:

```properties
sdk.dir=/path/to/Android/Sdk
qnn.sdk.dir=/path/to/qairt/2.50.0.260828
cmake.dir=/path/to/cmake
```

`cmake.dir` is optional when CMake and Ninja are on `PATH`. `QNN_SDK_ROOT`
can supply the Qualcomm SDK path instead of `qnn.sdk.dir`. Cargo is resolved
from `~/.cargo/bin` or `PATH`. Gradle resolves the pinned NDK from the Android SDK.

```sh
./gradlew :app:assembleBasicDebug :app:lintBasicDebug
```

The build applies the small dependency patches in
`app/src/main/cpp/patches/` to the pinned submodules. They cover CMake compatibility,
Rust explicit borrows, and xtensor dynamic-shape selection with Clang. The patch
script accepts both clean and already-patched checkouts.

QNN SampleApp sources are copied into the native build directory and patched
there. QNN runtime libraries and SDK notices are copied into generated Android
assets. QNN SDK sources and runtime binaries are excluded from version control.
The packaged runtime covers HTP V68, V69, V73, V75, V79 and V81;
individual model contexts still need to support the device.

For a standalone native build:

```sh
export ANDROID_NDK_ROOT=/path/to/Android/Sdk/ndk/29.0.14206865
export QNN_SDK_ROOT=/path/to/qairt/2.50.0.260828
cd app/src/main/cpp
bash build.sh
```

## Host regression tests

Install `g++`, set `QNN_SDK_ROOT`, and run from the repository root:

```sh
bash app/src/main/cpp/tests/run.sh
```

The tests use AddressSanitizer and UndefinedBehaviorSanitizer. They cover prompt
chunking and weighting, model context selection, VAE tile geometry, scheduler
tensor ranks, and QNN FP32/FP16/quantized input/output handling. The QNN tests use
the actual runtime methods and SDK tensor allocation with a simulated graph
execution function. They do not require an NPU or prove model image quality.

Builds and host tests do not install or launch the app on a device.
