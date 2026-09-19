#ifndef DITENGINE_H
#define DITENGINE_H

// Stable C ABI between stable_diffusion_core and libdit_engine.so.
//
// The engine carries stable-diffusion.cpp plus the ggml Hexagon backend, which
// only build for HTP v73+ and want a newer -march than the core. Keeping them
// in a separate shared object lets the core stay buildable for every device we
// support and lets the engine ship next to the models it needs, loaded with
// dlopen from the runtime directory (the same place the QNN libraries and
// their FastRPC skels already live).
//
// Both sides must agree on DIT_ENGINE_ABI_VERSION: the core refuses an engine
// that reports a different one instead of calling through mismatched structs.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DIT_ENGINE_ABI_VERSION 3

// Name of the single symbol the core resolves after dlopen.
#define DIT_ENGINE_ENTRY_SYMBOL "dit_engine_get_api"

typedef struct dit_ctx dit_ctx;

typedef enum {
  DIT_MODEL_Z_IMAGE = 0,
  DIT_MODEL_FLUX2_KLEIN = 1,
} dit_model_kind;

typedef struct {
  dit_model_kind kind;
  // Weight files. The text encoder (llm) is shared between both models.
  const char *diffusion_model_path;
  const char *llm_path;
  const char *vae_path;
  // ggml device spec, e.g. "HTP0" for the Hexagon NPU or "CPU".
  const char *backend;
  // Where to keep model parameters, e.g. "te=disk" to stream the text encoder
  // instead of holding it resident. Empty or NULL leaves the engine default.
  const char *params_backend;
  int n_threads;
  bool flash_attn;
  bool vae_conv_direct;
} dit_ctx_params;

typedef struct {
  const char *prompt;
  const char *negative_prompt;
  int width;
  int height;
  int steps;
  float cfg_scale;
  // Distilled guidance; both shipped models are guidance-distilled turbo
  // variants, so this is not the same knob as cfg_scale.
  float guidance;
  int64_t seed;
  const char *sample_method;
  // img2img: RGB8, init_width * init_height * 3 bytes, NULL for txt2img.
  const uint8_t *init_image_rgb;
  int init_width;
  int init_height;
  float denoise_strength;
  // inpaint: single-channel mask, mask_width * mask_height bytes. White
  // pixels are regenerated and black pixels retain the init image. NULL for
  // plain txt2img/img2img.
  const uint8_t *mask_image;
  int mask_width;
  int mask_height;
  // Native DiT edit conditioning. Unlike init_image_rgb these images remain
  // clean reference latents and do not shorten the sampling schedule. Any
  // count is accepted; each one lengthens the DiT sequence.
  const uint8_t *const *reference_images_rgb;
  const int *reference_widths;
  const int *reference_heights;
  int reference_image_count;
  // VAE tiling for resolutions whose full decode does not fit; 0 disables.
  int vae_tile_size;
  float vae_tile_overlap;
} dit_gen_params;

// Called for sampling and for long auxiliary work so callers can still cancel
// while weights/VAE tiles are running. total_steps is the effective sampling
// step count during sampling, and 0 during auxiliary work. Returning false
// asks the engine to cancel the generation.
typedef bool (*dit_progress_cb)(int step, int total_steps, float step_seconds,
                                void *user_data);

// Per-step preview, RGB8, only when the engine was asked for previews.
typedef void (*dit_preview_cb)(int step, const uint8_t *rgb, int width,
                               int height, void *user_data);

typedef void (*dit_log_cb)(int level, const char *text, void *user_data);

typedef struct {
  int abi_version;

  // Returns NULL on failure; the reason is available from last_error(NULL).
  dit_ctx *(*create)(const dit_ctx_params *params);
  void (*destroy)(dit_ctx *ctx);

  // Writes an RGB8 image of width * height * 3 bytes into *out_rgb, owned by
  // the engine until free_image(). Returns false on failure or cancellation.
  bool (*generate)(dit_ctx *ctx, const dit_gen_params *params,
                   dit_progress_cb progress, dit_preview_cb preview,
                   void *user_data, uint8_t **out_rgb, int *out_width,
                   int *out_height);
  void (*free_image)(uint8_t *rgb);

  // Last failure on this context, or the last create() failure when ctx is
  // NULL. Valid until the next call on the same context.
  const char *(*last_error)(const dit_ctx *ctx);

  void (*set_log_callback)(dit_log_cb cb, void *user_data);

  // Preview cadence: 0 disables previews entirely.
  void (*set_preview_interval)(dit_ctx *ctx, int interval);
} dit_engine_api;

// The engine's only exported symbol. Returns NULL when the engine cannot serve
// the requested ABI version.
const dit_engine_api *dit_engine_get_api(int requested_abi_version);

typedef const dit_engine_api *(*dit_engine_get_api_fn)(int);

#ifdef __cplusplus
}
#endif

#endif  // DITENGINE_H
