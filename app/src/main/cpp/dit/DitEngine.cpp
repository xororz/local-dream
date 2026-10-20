// libdit_engine.so: stable-diffusion.cpp behind the narrow DitEngine ABI.
//
// Everything specific to stable-diffusion.cpp and ggml stays on this side of
// the boundary, so the core never includes their headers and never links their
// objects. See include/DitEngine.h for the contract.

#include "DitEngine.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#include "stable-diffusion.h"

namespace {

// stable-diffusion.cpp installs progress, preview and log callbacks globally
// rather than per context, so the engine routes them through the generation
// that is currently running. Generations are serialized by g_gen_mutex, which
// makes "currently running" unambiguous.
std::mutex g_gen_mutex;

struct ActiveGeneration {
  dit_progress_cb progress = nullptr;
  dit_preview_cb preview = nullptr;
  void *user_data = nullptr;
  dit_ctx *ctx = nullptr;
  bool cancelled = false;
  bool sampling = false;
  int sampling_steps = 0;
};

ActiveGeneration g_active;

dit_log_cb g_log_cb = nullptr;
void *g_log_user_data = nullptr;

std::string g_create_error;

bool image_pixel_count(const sd_image_t &image, size_t *pixel_count) {
  if (!pixel_count || image.width == 0 || image.height == 0) return false;
  const size_t width = static_cast<size_t>(image.width);
  const size_t height = static_cast<size_t>(image.height);
  if (height > std::numeric_limits<size_t>::max() / width) return false;
  const size_t pixels = width * height;
  if (pixels > std::numeric_limits<size_t>::max() / 4) return false;
  *pixel_count = pixels;
  return true;
}

// Qwen Image 2.1 has a native RGBA VAE. Per-step preview callbacks still carry
// RGB, so composite straight alpha over white for previews only. The final
// result keeps the original RGBA allocation and channel count.
void image_to_rgb(const sd_image_t &image, size_t pixel_count, uint8_t *rgb) {
  if (image.channel == 3) {
    std::memcpy(rgb, image.data, pixel_count * 3);
    return;
  }

  for (size_t i = 0; i < pixel_count; ++i) {
    const uint8_t *rgba = image.data + i * 4;
    uint8_t *dst = rgb + i * 3;
    const unsigned alpha = rgba[3];
    for (size_t channel = 0; channel < 3; ++channel) {
      dst[channel] = static_cast<uint8_t>(
          (static_cast<unsigned>(rgba[channel]) * alpha +
           255u * (255u - alpha) + 127u) /
          255u);
    }
  }
}

}  // namespace

struct dit_ctx {
  sd_ctx_t *sd = nullptr;
  dit_model_kind kind = DIT_MODEL_Z_IMAGE;
  std::string last_error;
  int preview_interval = 0;
};

namespace {

void forward_log(enum sd_log_level_t level, const char *text, void *) {
  // stable-diffusion.cpp uses the same global progress callback for model
  // loading, tiled VAE work and sampling. Its image pipeline emits these
  // messages immediately around sd->sample(), so use them to preserve the
  // stage across the otherwise phase-less callback ABI.
  if (g_active.ctx && text) {
    if (std::strstr(text, "generating image:")) {
      g_active.sampling = true;
    } else if (std::strstr(text, "sampling completed") ||
               std::strstr(text, "Diffusion model sampling failed")) {
      g_active.sampling = false;
    }
  }
  if (g_log_cb) g_log_cb(static_cast<int>(level), text ? text : "", g_log_user_data);
}

void forward_progress(int step, int steps, float time, void *) {
  if (!g_active.progress) return;
  // A zero total marks non-sampling work. Still forward it so a disconnected
  // client can cancel a long VAE encode/decode or lazy parameter load, but do
  // not let those unrelated counters drive the UI progress bar.
  const int routed_steps =
      g_active.sampling && steps == g_active.sampling_steps ? steps : 0;
  if (!g_active.progress(step, routed_steps, time, g_active.user_data)) {
    // The callback asked to stop. sd_cancel_generation only takes effect at
    // the next step boundary, so record it for the generate() return path.
    g_active.cancelled = true;
    if (g_active.ctx && g_active.ctx->sd) sd_cancel_generation(g_active.ctx->sd, SD_CANCEL_ALL);
  }
}

void forward_preview(int step, int frame_count, sd_image_t *frames, bool, void *) {
  if (!g_active.preview || frame_count < 1 || !frames || !frames[0].data) return;
  const sd_image_t &frame = frames[0];
  size_t pixel_count = 0;
  if (!image_pixel_count(frame, &pixel_count)) return;
  if (frame.channel == 3) {
    g_active.preview(step, frame.data, static_cast<int>(frame.width),
                     static_cast<int>(frame.height), g_active.user_data);
    return;
  }
  if (frame.channel != 4) return;

  try {
    std::vector<uint8_t> rgb(pixel_count * 3);
    image_to_rgb(frame, pixel_count, rgb.data());
    g_active.preview(step, rgb.data(), static_cast<int>(frame.width),
                     static_cast<int>(frame.height), g_active.user_data);
  } catch (...) {
    // A preview is optional; allocation failure must not abort generation.
  }
}

dit_ctx *engine_create(const dit_ctx_params *params) {
  g_create_error.clear();
  if (!params || !params->diffusion_model_path || !params->llm_path || !params->vae_path) {
    g_create_error = "missing model paths";
    return nullptr;
  }

  sd_ctx_params_t sd_params;
  sd_ctx_params_init(&sd_params);
  sd_params.diffusion_model_path = params->diffusion_model_path;
  sd_params.llm_path = params->llm_path;
  sd_params.llm_vision_path = params->llm_vision_path;
  sd_params.vae_path = params->vae_path;
  sd_params.n_threads = params->n_threads > 0 ? params->n_threads : 4;
  sd_params.flash_attn = params->flash_attn;
  sd_params.diffusion_flash_attn = params->flash_attn;
  sd_params.vae_conv_direct = params->vae_conv_direct;
  // Keep the upstream segment prefetch enabled: it overlaps loading/repacking
  // the next graph segment with HTP execution. Component-level residency is
  // controlled separately by params_backend=all=disk.
  sd_params.disable_prefetch = false;
  if (params->backend && params->backend[0]) sd_params.backend = params->backend;
  if (params->params_backend && params->params_backend[0])
    sd_params.params_backend = params->params_backend;

  auto *ctx = new dit_ctx();
  ctx->kind = params->kind;
  ctx->sd = new_sd_ctx(&sd_params);
  if (!ctx->sd) {
    g_create_error = "new_sd_ctx failed";
    delete ctx;
    return nullptr;
  }
  return ctx;
}

void engine_destroy(dit_ctx *ctx) {
  if (!ctx) return;
  if (ctx->sd) free_sd_ctx(ctx->sd);
  delete ctx;
}

bool engine_generate(dit_ctx *ctx, const dit_gen_params *params, dit_progress_cb progress,
                     dit_preview_cb preview, void *user_data, uint8_t **out_pixels,
                     int *out_width, int *out_height, int *out_channels) {
  if (!ctx || !ctx->sd || !params || !out_pixels || !out_channels) return false;
  ctx->last_error.clear();

  std::lock_guard<std::mutex> lock(g_gen_mutex);

  sd_img_gen_params_t gen;
  sd_img_gen_params_init(&gen);
  gen.prompt = params->prompt ? params->prompt : "";
  gen.negative_prompt = params->negative_prompt ? params->negative_prompt : "";
  gen.width = params->width;
  gen.height = params->height;
  gen.seed = params->seed;
  gen.batch_count = 1;
  gen.sample_params.sample_steps = params->steps;
  gen.sample_params.guidance.txt_cfg = params->cfg_scale;
  gen.sample_params.guidance.distilled_guidance = params->guidance;
  if (params->sample_method && params->sample_method[0])
    gen.sample_params.sample_method = str_to_sample_method(params->sample_method);

  if (params->init_image_rgb && params->init_width > 0 && params->init_height > 0) {
    gen.init_image.width = static_cast<uint32_t>(params->init_width);
    gen.init_image.height = static_cast<uint32_t>(params->init_height);
    gen.init_image.channel = 3;
    gen.init_image.data = const_cast<uint8_t *>(params->init_image_rgb);
    gen.strength = params->denoise_strength;
  }

  if (params->mask_image && params->mask_width > 0 && params->mask_height > 0) {
    if (!gen.init_image.data) {
      ctx->last_error = "inpaint mask requires an init image";
      return false;
    }
    if (params->mask_width != params->init_width ||
        params->mask_height != params->init_height) {
      ctx->last_error = "inpaint mask dimensions do not match init image";
      return false;
    }
    gen.mask_image.width = static_cast<uint32_t>(params->mask_width);
    gen.mask_image.height = static_cast<uint32_t>(params->mask_height);
    gen.mask_image.channel = 1;
    gen.mask_image.data = const_cast<uint8_t *>(params->mask_image);
  }

  std::vector<sd_image_t> reference_images;
  if (params->reference_image_count > 0) {
    if (!params->reference_images_rgb ||
        !params->reference_widths || !params->reference_heights) {
      ctx->last_error = "invalid reference images";
      return false;
    }
    reference_images.reserve(params->reference_image_count);
    for (int i = 0; i < params->reference_image_count; ++i) {
      if (!params->reference_images_rgb[i] || params->reference_widths[i] <= 0 ||
          params->reference_heights[i] <= 0) {
        ctx->last_error = "invalid reference image";
        return false;
      }
      reference_images.push_back(
          {static_cast<uint32_t>(params->reference_widths[i]),
           static_cast<uint32_t>(params->reference_heights[i]), 3,
           const_cast<uint8_t *>(params->reference_images_rgb[i])});
    }
    gen.ref_images = reference_images.data();
    gen.ref_images_count = static_cast<int>(reference_images.size());
    gen.ref_image_args =
        ctx->kind == DIT_MODEL_QWEN_IMAGE_2_1 ? "preset=qwen" : "preset=flux2";
  }

  if (params->vae_tile_size > 0) {
    gen.vae_tiling_params.enabled = true;
    gen.vae_tiling_params.tile_size_x = params->vae_tile_size;
    gen.vae_tiling_params.tile_size_y = params->vae_tile_size;
    gen.vae_tiling_params.target_overlap = params->vae_tile_overlap;
  }

  int sampling_steps = std::max(1, params->steps);
  if (gen.init_image.data && gen.strength < 1.0f) {
    // stable-diffusion.cpp retains t_enc + 1 intervals after trimming.
    const int t_enc = static_cast<int>(params->steps * gen.strength);
    sampling_steps = std::clamp(t_enc + 1, 1, std::max(1, params->steps));
  }
  g_active = ActiveGeneration{progress, preview, user_data, ctx, false, false,
                              sampling_steps};
  sd_set_progress_callback(progress ? forward_progress : nullptr, nullptr);
  if (preview && ctx->preview_interval > 0) {
    sd_set_preview_callback(forward_preview, PREVIEW_PROJ, ctx->preview_interval,
                            /*denoised=*/true, /*noisy=*/false, nullptr);
  } else {
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 0, false, false, nullptr);
  }

  sd_image_t *images = nullptr;
  int image_count = 0;
  const bool ok = generate_image(ctx->sd, &gen, &images, &image_count);

  sd_set_progress_callback(nullptr, nullptr);
  sd_set_preview_callback(nullptr, PREVIEW_NONE, 0, false, false, nullptr);
  const bool cancelled = g_active.cancelled;
  g_active = ActiveGeneration{};
  // Clear the sticky cancel flag so the context stays usable afterwards.
  if (cancelled) sd_cancel_generation(ctx->sd, SD_CANCEL_RESET);

  if (!ok || image_count < 1 || !images || !images[0].data) {
    ctx->last_error = cancelled ? "cancelled" : "generate_image failed";
    if (images) free_sd_images(images, std::max(0, image_count));
    return false;
  }

  sd_image_t &image = images[0];
  size_t pixel_count = 0;
  if (!image_pixel_count(image, &pixel_count) ||
      (image.channel != 3 && image.channel != 4)) {
    ctx->last_error = "unsupported generated image dimensions/channels: " +
                      std::to_string(image.width) + "x" +
                      std::to_string(image.height) + "x" +
                      std::to_string(image.channel);
    free_sd_images(images, image_count);
    return false;
  }

  // Transfer the original interleaved allocation. In particular, preserve
  // Qwen Image 2.1's native alpha channel for the core/Kotlin receiver.
  *out_pixels = image.data;
  *out_channels = static_cast<int>(image.channel);
  image.data = nullptr;
  if (out_width) *out_width = static_cast<int>(image.width);
  if (out_height) *out_height = static_cast<int>(image.height);
  free_sd_images(images, image_count);
  return true;
}

void engine_free_image(uint8_t *pixels) { free(pixels); }

const char *engine_last_error(const dit_ctx *ctx) {
  if (!ctx) return g_create_error.c_str();
  return ctx->last_error.c_str();
}

void engine_set_log_callback(dit_log_cb cb, void *user_data) {
  g_log_cb = cb;
  g_log_user_data = user_data;
  sd_set_log_callback(cb ? forward_log : nullptr, nullptr);
}

void engine_set_preview_interval(dit_ctx *ctx, int interval) {
  if (ctx) ctx->preview_interval = interval;
}

const dit_engine_api g_api = {
    DIT_ENGINE_ABI_VERSION,
    engine_create,
    engine_destroy,
    engine_generate,
    engine_free_image,
    engine_last_error,
    engine_set_log_callback,
    engine_set_preview_interval,
};

}  // namespace

extern "C" __attribute__((visibility("default"))) const dit_engine_api *dit_engine_get_api(
    int requested_abi_version) {
  if (requested_abi_version != DIT_ENGINE_ABI_VERSION) return nullptr;
  return &g_api;
}
