# Model Conversion Guide (SD1.5 Only)

## Overview

This guide will help you convert your models to be compatible with the NPU for optimized performance on supported devices.

Since I have implemented on-device CPU model conversion. We focus on NPU conversion here. I have made a easy-to-follow script to automate the conversion process.

Please use **Linux** or WSL environment for the conversion process.

Download the conversion scripts from [here](https://chino.icu/local-dream/npuconvert.zip)

## Prerequisites

- [QNN_SDK_2.28](https://apigwx-aws.qualcomm.com/qsc/public/v1/api/download/software/qualcomm_neural_processing_sdk/v2.28.0.241029.zip) - Please use v2.28 to avoid potential issues
- [uv](https://github.com/astral-sh/uv) - Python environment manager

## Environment Setup

1. CD to the npuconvert directory and run:
   ```bash
   uv venv -p 3.10
   source .venv/bin/activate
   uv sync
   ```
2. Set the `QNN_SDK_ROOT` path in `convert_all.sh` to your QNN SDK path.

## Usage

1. Run the data preparation script:

   ```bash
   python prepare_data.py --model_path <your_safetensors_path> --clip_skip <1 or 2>
   ```

   You can modify the prompts to fit your model in `prepare_data.py`. We recommend using 10+ prompts for better results.

2. Generate data for quantization:

   ```bash
   python gen_quant_data.py
   ```

3. Export the model to ONNX:

   ```bash
   python export_onnx.py --model_path <your_safetensors_path> --clip_skip <1 or 2>
   ```

4. Convert the ONNX model to QNN format:

   ```bash
   bash scripts/convert_all.sh --min_soc <min or 8gen1 or 8gen2>
   ```

   **--min_soc**: Choose the minimum SOC you want to support.

   - Use `min` for the broadest compatibility, which can run on non-flagship chips like `7gen1,8sgen3,etc`.
   - Use `8gen1` if you want to support Snapdragon 8gen1 and above (8gen1/2/3/4). If you have a 8gen1 device, this is recommended.
   - Use `8gen2` if you want to support Snapdragon 8gen2 and above (8gen2/3/4). This will be much faster on 8gen2+ devices.

   If you have successfully run the script, you will find the converted models in `output/qnn_models_xxx`. **And if you have executed the script once, set a different `--min_soc` will be much faster(minutes) as it will skip some steps.**

5. Zip the `output/qnn_models_xxx` folder and import it in the Local Dream app.

The conversion process may take a long time (up to several hours) depending on your hardware.
