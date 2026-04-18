# Model Conversion Guide (SDXL Experimental)

## Overview

This guide outlines an experimental host-side workflow for converting **Stable Diffusion XL** models into assets that can be used for NPU evaluation on supported Snapdragon devices.

Unlike the SD1.5 guide, this document should be treated as a **reference workflow**, not as a statement that upstream `local-dream` currently supports SDXL end-to-end in the app.

Please use **Linux** or **WSL** for the conversion process.

## Prerequisites

- [QNN_SDK_2.39](https://www.qualcomm.com/developer/software/neural-processing-sdk-for-ai)
- A Python 3.10 environment
- [zstd](https://github.com/facebook/zstd) - for example `sudo apt-get install zstd`
- A diffusers-compatible SDXL export workflow
- A workflow capable of generating representative SDXL calibration inputs
- Enough RAM and swap for large-resolution conversion

> [!IMPORTANT]
> SDXL conversion is substantially heavier than SD1.5 conversion. High-resolution workflows may require significantly more memory, much more disk space, and much longer context generation times.

## Environment Setup

1. Create and activate a Python environment:
   ```bash
   python3.10 -m venv .venv
   source .venv/bin/activate
   ```
2. Set `QNN_SDK_ROOT` to a QNN 2.39 installation:
   ```bash
   export QNN_SDK_ROOT=/path/to/QNN_SDK_2.39/qairt/2.39.0.250926
   export PYTHONPATH="$QNN_SDK_ROOT/lib/python:${PYTHONPATH:-}"
   export LD_LIBRARY_PATH="$QNN_SDK_ROOT/lib/x86_64-linux-clang:${LD_LIBRARY_PATH:-}"
   ```
3. Prepare your SDXL assets:
   - base checkpoint
   - optional refiner or turbo checkpoint
   - optional custom VAE

## Notes Before You Start

- SDXL UNet typically uses **5 inputs**:
  - `sample`
  - `timestep`
  - `encoder_hidden_states`
  - `text_embeds`
  - `time_ids`
- SDXL uses **two text encoders**. In addition to sequence embeddings, the UNet also consumes pooled text embeddings and `time_ids`.
- Depending on the export path, `vae_decoder.onnx` may already apply `latents / scaling_factor` internally. Do not assume it behaves exactly like an SD1.5 VAE decoder.
- Before spending time on QNN conversion, validate the exported ONNX models on CPU or GPU first.

## Usage

The SDXL workflow is usually easier to reason about if it is split into four separate stages.

### 1. Export ONNX Models

Export the SDXL components needed by your workflow. A typical base pipeline export includes:

- `text_encoder.onnx`
- `text_encoder_2.onnx`
- `unet.onnx`
- `vae_decoder.onnx`

Optional components such as a refiner may be exported separately.

Use a single, fixed target resolution for each export pass. If you plan to evaluate both 512 and 1024, export them as separate workflows and validate them independently.

### 2. Prepare Calibration Data

For SDXL, calibration data is usually more reliable when captured from real prompt runs than when synthesized entirely from random tensors.

At minimum, collect representative raw inputs for the UNet:

- `sample`
- `timestep`
- `encoder_hidden_states`
- `text_embeds`
- `time_ids`

The input list used by `qnn-onnx-converter` should contain one line per sample in the form:

```text
sample:=/abs/path/sample_0.raw timestep:=/abs/path/timestep_0.raw encoder_hidden_states:=/abs/path/encoder_hidden_states_0.raw text_embeds:=/abs/path/text_embeds_0.raw time_ids:=/abs/path/time_ids_0.raw
```

### 3. Convert the UNet with QNN

An example UNet conversion command looks like this:

```bash
"$QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-onnx-converter" \
    --input_network /path/to/unet.onnx \
    -o /path/to/out/qnn_sdxl_unet \
    --input_list /path/to/calib/input_list.txt \
    --weights_bitwidth 8 \
    --act_bitwidth 16 \
    --disable_batchnorm_folding
```

The exact bitwidths and converter options will depend on your target device and quality/performance goals.

### 4. Generate a Model Library and Context Binary

After conversion, generate a model library and, if needed, a context binary.

```bash
"$QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-model-lib-generator" \
    -c /path/to/qnn_sdxl_unet.cpp \
    -b /path/to/qnn_sdxl_unet.bin \
    -t x86_64-linux-clang \
    -o /path/to/model_lib

"$QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-context-binary-generator" \
    --model /path/to/model_lib/x86_64-linux-clang/libqnn_sdxl_unet.so \
    --backend "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so" \
    --binary_file sdxl_unet_ctx.bin \
    --output_dir /path/to/context \
    --input_output_tensor_mem_type memhandle
```

For large SDXL graphs, context generation can take a long time and may require substantially more memory than the ONNX export stage.

## Packaging

For app-side experiments, a typical SDXL asset set may include:

- UNet asset
- VAE decoder asset
- first text encoder asset
- second text encoder asset
- tokenizer
- second tokenizer

Some workflows may choose to package multiple resolutions independently. Others may prefer a patch-based strategy for UNet-only variants. Either approach should be validated carefully because SDXL is far less forgiving than SD1.5 in both memory use and graph size.

## Scope and Limitations

- This guide is focused on **host-side conversion** only.
- It does **not** cover all runtime changes required for full SDXL support in the app.
- It is intentionally conservative and does not prescribe a single exporter or calibration toolchain.
- Base SDXL is the primary target. Refiner and turbo workflows may require additional handling and should be treated as experimental.

The full conversion process may take several hours or longer depending on your hardware and selected resolution.
