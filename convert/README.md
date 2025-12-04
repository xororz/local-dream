# Model Conversion Guide (SD1.5 Only)

## Overview

This guide will help you convert your models to be compatible with the NPU for optimized performance on supported devices.

Since I have implemented on-device CPU model conversion. We focus on NPU conversion here. I have made a easy-to-follow script to automate the conversion process.

Please use **Linux** or WSL environment for the conversion process.

Download the conversion scripts [npuconvertv2.zip](https://chino.icu/local-dream/npuconvertv2.zip)

## Prerequisites

- [QNN_SDK_2.28](https://apigwx-aws.qualcomm.com/qsc/public/v1/api/download/software/qualcomm_neural_processing_sdk/v2.28.0.241029.zip) - Please use v2.28 to avoid potential issues
- [uv](https://github.com/astral-sh/uv) - Python environment manager
- [zstd](https://github.com/facebook/zstd) - Install via your package manager (e.g., `sudo apt-get install zstd`)

## Environment Setup

1. CD to the npuconvert directory and run:
   ```bash
   uv venv -p 3.10.17
   source .venv/bin/activate
   uv sync
   ```
2. Set the `QNN_SDK_ROOT` path in `convert_all.sh` and `convert_all_unet_only.sh` to your QNN SDK path.
3. You need at least ~20GB memory to convert the 512x512 model. **Make sure you have 64GB+ memory+swap for the conversion process for higher resolutions. Please ensure your system meets these requirements before proceeding and raising issues.**

## Usage

This is an example `export.sh` script to convert a model with extra resolutions.

```bash
set -e

clip_skip=2 # 1 or 2
model_path=~/Downloads/AnythingXL_v50.safetensors # Path to your model
model_name=AnythingV5 # Name used for output files
realistic=false  # Set to true to enable --realistic mode. It will use prompts for realistic images.
# Define extra resolutions, format: (width height). Leave empty to skip extra resolutions.
extra_resolutions=(
    "512 768"
    "768 512"
)

# Define SOC version list
soc_versions=("8gen2" "8gen1" "min")
# Non-flagship SOC versions can't run higher resolutions
extra_resolution_soc_versions=("8gen2" "8gen1")

uv venv -p 3.10.17 --clear
source .venv/bin/activate
uv sync

# Set realistic flag based on realistic variable
realistic_flag=""
if [ "$realistic" = true ]; then
    realistic_flag="--realistic"
fi

# Function to process extra resolutions
process_extra_resolution() {
    local width=$1
    local height=$2
    local size="${width}x${height}"

    echo "Processing resolution: ${size}"

    # Prepare data and export
    python prepare_data.py --model_path $model_path --clip_skip $clip_skip --height $height --width $width $realistic_flag
    python gen_quant_data.py
    python export_onnx_unet_only.py --model_path $model_path --clip_skip $clip_skip --height $height --width $width

    # Convert for all SOC versions
    for soc in "${extra_resolution_soc_versions[@]}"; do
        bash scripts/convert_all_unet_only.sh --min_soc $soc
    done

    # Move output directory
    mv output output_${size}

    # Generate patch files
    for soc in "${extra_resolution_soc_versions[@]}"; do
        zstd --patch-from ./output_512/qnn_models_${soc}/unet.bin \
             output_${size}/qnn_models_${soc}/unet.bin \
             -o ./output_512/qnn_models_${soc}/${size}.patch
    done
}

# ======== Base resolution 512x512 (must execute) ========
echo "Processing base resolution: 512x512"
python prepare_data.py --model_path $model_path --clip_skip $clip_skip $realistic_flag
python gen_quant_data.py
python export_onnx.py --model_path $model_path --clip_skip $clip_skip

for soc in "${soc_versions[@]}"; do
    bash scripts/convert_all.sh --min_soc $soc
done

mv output output_512

# ======== Process extra resolutions ========
for resolution in "${extra_resolutions[@]}"; do
    read -r width height <<< "$resolution"
    process_extra_resolution $width $height
done

# ======== Package outputs ========
echo "Packaging output files..."
for soc in "${soc_versions[@]}"; do
    zip -r ${model_name}_qnn2.28_${soc}.zip output_512/qnn_models_${soc}
done
```

The conversion process may take a long time (at least several hours) depending on your hardware.
