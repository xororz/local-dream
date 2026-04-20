# Model Conversion Guide (SDXL Experimental)

## Overview

This guide outlines an experimental host-side workflow for converting **Stable Diffusion XL** models into assets that can be used for NPU evaluation on supported Snapdragon devices.

Please use **Linux** or **WSL** for the conversion process.

Download the conversion scripts [convertsdxl.zip](https://chino.icu/local-dream/convertsdxl.zip)

## Prerequisites

- [QNN_SDK_2.28](https://apigwx-aws.qualcomm.com/qsc/public/v1/api/download/software/qualcomm_neural_processing_sdk/v2.28.0.241029.zip) - **Please use v2.28 to avoid potential issues**
- [uv](https://github.com/astral-sh/uv) - Python environment manager
- Enough RAM+swap(~128GB) for large-resolution conversion

> [!IMPORTANT]
> SDXL conversion is substantially heavier than SD1.5 conversion. High-resolution workflows may require significantly more memory, much more disk space, and much longer context generation times.

## Environment Setup

1. CD to the npuconvert directory and run:
   ```bash
   uv venv -p 3.10.17
   source .venv/bin/activate
   uv sync
   ```
2. Set the `QNN_SDK_ROOT` path in `convert_all_sdxl.sh` to your QNN SDK path.

## Usage

This is an example `export_sdxl.sh` script to convert a model with extra resolutions.

```bash
set -e

model_path=~/Downloads/anythingxl.safetensors # Path to your model
model_name=anythingxl # Name used for output files
realistic=false  # Set to true to enable --realistic mode. It will use prompts for realistic images.

# Define SOC version list. 8gen3 models works for 8e and 8e5
soc_versions=("8gen3")

uv venv -p 3.10.17 --clear
source .venv/bin/activate
uv sync

# Set realistic flag based on realistic variable
realistic_flag=""
if [ "$realistic" = true ]; then
    realistic_flag="--realistic"
fi

# ======== 1024x1024 ========
echo "Processing base resolution: 1024x1024"
python prepare_data_sdxl.py --model_path $model_path $realistic_flag
python gen_quant_data_sdxl.py
python export_onnx_sdxl.py --model_path $model_path

for soc in "${soc_versions[@]}"; do
    bash scripts/convert_all_sdxl.sh --min_soc $soc
done

# ======== Package outputs ========
echo "Packaging output files..."
for soc in "${soc_versions[@]}"; do
    touch output/qnn_models_sdxl_${soc}/SDXL
    zip -r ${model_name}_qnn2.28_${soc}.zip output/qnn_models_sdxl_${soc}
done
```

The conversion process may take a long time (at least several hours) depending on your hardware.
