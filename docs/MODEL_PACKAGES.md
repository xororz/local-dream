# Imported model packages and long prompts

**Import Model ZIP** is available on both model tabs. The app identifies QNN
and MNN packages by their contents. SD1.5 safetensors conversion remains available;
SDXL safetensors conversion is not added by this runtime update.

## SDXL packages

Each SDXL ZIP contains an `SDXL` marker and the following text encoder files:

```text
SDXL
clip.mnn
clip_2.mnn
tokenizer.json
token_emb.bin
pos_emb.bin
token_emb_2.bin
pos_emb_2.bin
```

Include any external `.mnn.weight` files produced with the MNN graphs.

| Backend | Diffusion files | Prompt context |
| --- | --- | --- |
| MNN CPU/OpenCL | `unet.mnn`, `vae_decoder.mnn`, optional `vae_encoder.mnn` | Dynamic, matching both prompts |
| QNN legacy | `unet.bin`, `vae_decoder.bin`, optional `vae_encoder.bin` | 77 tokens |
| QNN with context patches | QNN files plus `154.patch`, optionally `231.patch` | 154 or 231 tokens when needed |
| QNN with attention mask | QNN files plus nonempty `qnn_context.txt` | Fixed 231 tokens, with inactive chunks masked |

The presence of `SDXL` and `unet.mnn` selects the `sdxlmnn` backend and CPU/GPU
model tab. QNN packages select `sdxl` and the NPU tab. Enable image-to-image when
the package includes an encoder.

MNN packages can include `vae_tile_size.txt` with `640` or `1024`; the default is
640. The VAE runs on CPU, including when the UNet uses OpenCL. A 640px VAE processes
the 1024px canvas in four overlapping tiles. Low-RAM mode releases each stage
before loading the next. The package interfaces match Fancy AI's SDXL MNN exports.

## Prompt chunks

Each CLIP pass encodes 75 content tokens plus start/end tokens. Longer prompts
are split into chunks; `BREAK` starts a new chunk. Both prompts use matching
context lengths, and position embeddings restart in each chunk. Prompt weights
and textual-inversion embeddings continue to apply.

MNN SDXL and SD1.5 CPU/OpenCL UNets resize their text context to fit the chunks.
Their prompt counter displays `∞`; this indicates dynamic context, not unlimited
device memory. SD1.5 QNN retains its fixed 77-token interface. Existing SD1.5 MNN
models can use chunked prompts without reconversion.

For SDXL pooled conditioning, masked QNN packages use the last active chunk;
MNN and context-patched QNN packages use the last padded chunk.

## Checkpoint-owned QNN VAEs

SD1.5 VAE input was previously always written as quantized 16-bit data, and its
output reader rejected floating tensors. This prevented newly converted FP32
VAEs from producing a result. The runtime now uses tensor metadata to read and
write FP32, FP16, and existing quantized tensors for both SD families. SDXL UNet
input and output also use typed conversion.

The SD1.5 rendering fix has been confirmed on a device with an existing converted
model, without reconversion.
