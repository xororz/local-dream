# Local Dream <span><img src="./assets/icon.png" width="28"></span>

Android Stable Diffusion with Snapdragon NPU acceleration. Also supports CPU inference.

![](./assets/demo.jpg)

## About this Repo

This project is **now open sourced and completely free**. Hope you enjoy it!

If you like it, please consider [sponsor](https://github.com/xororz/local-dream?tab=readme-ov-file#sponsorship) this project.

## Usage

- Download the APK from the [Releases](https://github.com/xororz/local-dream/releases) page or [Google Play](https://play.google.com/store/apps/details?id=io.github.xororz.localdream).
- Open the app and download the model(s) you want to use

## Build

It is recommended to build it on linux/wsl. Other platforms are not verified.

**Rust is needed for building tokenizer-cpp. You should first install rustup, then "rustup default stable".**

### Clone this repo recursively

```bash
git clone --recursive https://github.com/xororz/local-dream.git
```

### Prepare SDKs

1. Download [QNN_SDK_2.29](https://apigwx-aws.qualcomm.com/qsc/public/v1/api/download/software/qualcomm_neural_processing_sdk/v2.29.0.241129.zip) and extract
2. Download [Android NDK](https://developer.android.com/ndk/downloads) and extract
3. Modify the QNN_SDK_ROOT in app/src/main/cpp/CMakeLists.txt
4. Modify the ANDROID_NDK_ROOT in app/src/main/cpp/CMakePresets.json

### Build and prepare libraries

#### Linux

```bash
cd app/src/main/cpp/
bash ./build.sh
```

#### Windows

```powershell
# winget install Kitware.CMake (install CMake if you don't have it)
# winget install Ninja-build.Ninja (install Ninja if you don't have it)
# winget install Rustlang.Rustup (install Rust if you don't have it)
cd app\src\main\cpp\
# Convert patch file to Unix format (if you don't have dos2unix, install with: winget install -e --id waterlan.dos2unix)
dos2unix SampleApp.patch
.\build.bat
```

### Build apk in android studio

open this project in android studio and click Build/Generate App Bundles or APKs/Generate APKs

## Technical Implementation

### NPU Acceleration

- Utilizes Qualcomm QNN SDK to leverage Hexagon NPU
- W8A16 static quantization for optimal performance
- Fixed model shape at 512x512
- Extremely fast inference speed

### CPU Inference

- Powered by MNN framework
- W8 dynamic quantization
- Flexible output sizes: 128x128, 256x256, 384x384, 512x512
- Relatively slower processing speed with slightly lower accuracy

## Supported Devices

### NPU Acceleration Support

- Devices with Snapdragon 8 Gen 1
- Devices with Snapdragon 8+Gen 1
- Devices with Snapdragon 8 Gen 2
- Devices with Snapdragon 8 Gen 3
- Devices with Snapdragon 8 Elite

Other devices are not able to download the npu models.

Note: Different phones have different default frequency limits for their NPUs, and the same chip from different manufacturers may have varying inference speeds.

### CPU Support

- Requires approximately 2GB RAM
- Compatible with most Android phones from recent years

## Available Models

1. Anything V5.0

   - Specialized for anime/manga style image generation

2. Stable Diffusion 2.1

   - General-purpose image generation

3. Pony V5.5

   - Pony Diffusion series

4. ChilloutMix

   - ChilloutMix series

5. Absolute Reality
   - Realistic image generation

## Seed Settings

The application supports custom seed settings for reproducible results:

- CPU Mode: Seeds guarantee identical results across different devices with the same parameters
- NPU Mode: Seeds ensure consistent results only on devices with identical chipsets

## Sponsorship

If you like this project, please consider sponsoring this it. Your support will help me implement:

- Additional models
- New features
- Enhanced capabilities

<!-- ![Donation Option 1](./assets/donate1.png)
![Donation Option 2](./assets/donate2.png) -->
<a href="https://ko-fi.com/xororz">
    <img height="36" style="border:0px;height:36px;" src="https://storage.ko-fi.com/cdn/kofi2.png?v=3" border="0" alt="Buy Me a Coffee at ko-fi.com" />
</a>
<a href="https://afdian.com/a/xororz">
    <img height="36" style="border-radius:12px;height:36px;" src="https://pic1.afdiancdn.com/static/img/welcome/button-sponsorme.jpg" alt="在爱发电支持我" />
</a>

Your sponsorship helps maintain and improve Local Dream for everyone!
