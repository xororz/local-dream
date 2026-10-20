#ifndef PIPELINEDIT_HPP
#define PIPELINEDIT_HPP

#include <dlfcn.h>

#include <chrono>
#include <string>
#include <vector>

#include "DitEngine.h"
#include "Pipeline.hpp"

// Z-Image Turbo, FLUX.2/Klein and Qwen Image 2.1, run by libdit_engine.so.
//
// Unlike every other pipeline here, this one owns no graphs and no scheduler:
// the engine does the whole txt2img/img2img/inpaint round trip behind the DitEngine
// ABI, so generate() is overridden wholesale and the per-stage hooks are never
// reached. The engine ships as a separate APK native library and is dlopened
// at startup; its FastRPC skels are copied from assets to the DSP search path.
//
// These are DiT models with RoPE, with none of the fixed-canvas padding that
// SDXL and Anima need. Android limits dimensions to the HTP/VAE-safe grid.
class PipelineDit : public Pipeline {
 public:
  PipelineDit(TextEncoder &text_encoder, const std::string &model_dir,
              std::string engine_path, std::string diffusion_model_path,
              std::string llm_path, std::string llm_vision_path,
              std::string vae_path, dit_model_kind kind, std::string backend,
              std::string params_backend, int n_threads, int vae_tile_size,
              bool img2img_enabled)
      : Pipeline(text_encoder, model_dir, /*sdxl=*/false, /*use_v_pred=*/false),
        engine_path_(std::move(engine_path)),
        diffusion_model_path_(std::move(diffusion_model_path)),
        llm_path_(std::move(llm_path)),
        llm_vision_path_(std::move(llm_vision_path)),
        vae_path_(std::move(vae_path)),
        kind_(kind),
        backend_(std::move(backend)),
        params_backend_(std::move(params_backend)),
        n_threads_(n_threads),
        vae_tile_size_(vae_tile_size),
        img2img_enabled_(img2img_enabled) {}

  ~PipelineDit() override {
    if (ctx_ && api_) api_->destroy(ctx_);
    if (handle_) dlclose(handle_);
  }

  bool initialize() override {
    handle_ = dlopen(engine_path_.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle_) {
      QNN_ERROR("dlopen %s failed: %s", engine_path_.c_str(), dlerror());
      return false;
    }
    auto get_api = reinterpret_cast<dit_engine_get_api_fn>(
        dlsym(handle_, DIT_ENGINE_ENTRY_SYMBOL));
    if (!get_api) {
      QNN_ERROR("engine entry point missing: %s", dlerror());
      return false;
    }
    api_ = get_api(DIT_ENGINE_ABI_VERSION);
    if (!api_) {
      QNN_ERROR("engine ABI mismatch; core wants v%d", DIT_ENGINE_ABI_VERSION);
      return false;
    }
    api_->set_log_callback(&PipelineDit::forwardLog, nullptr);

    dit_ctx_params params{};
    params.kind = kind_;
    params.diffusion_model_path = diffusion_model_path_.c_str();
    params.llm_path = llm_path_.c_str();
    params.llm_vision_path =
        llm_vision_path_.empty() ? nullptr : llm_vision_path_.c_str();
    params.vae_path = vae_path_.c_str();
    params.backend = backend_.c_str();
    params.params_backend = params_backend_.c_str();
    params.n_threads = n_threads_;
    params.flash_attn = true;
    // Required, not just an optimization: the Hexagon backend repacks every
    // F16 1x1/3x3 conv weight into the HMX tile layout on upload, and only the
    // direct-conv kernel reads that layout. Without it every VAE conv falls
    // back to im2col + mul_mat over the repacked weights.
    params.vae_conv_direct = true;

    ctx_ = api_->create(&params);
    if (!ctx_) {
      QNN_ERROR("engine create failed: %s", api_->last_error(nullptr));
      return false;
    }
    QNN_INFO("DiT engine loaded from %s", engine_path_.c_str());
    return true;
  }

  bool supportsImg2Img() const override { return img2img_enabled_; }
  bool supportsReferenceEditing() const override {
    return img2img_enabled_ && isNativeEditModel();
  }
  bool supportsUltrafix() const override { return false; }

  GenerationResult generate(GenerationRequest &req,
                            const ProgressCallback &progress_callback) override {
    if (!ctx_ || !api_) throw std::runtime_error("DiT engine not initialized");
    if (req.prompt.empty()) throw std::invalid_argument("Prompt empty");
    if (safety_interpreter_ && !safety_session_)
      throw std::runtime_error("SafetyChecker missing");
    if (req.img2img && !supportsImg2Img())
      throw std::runtime_error("img2img not available (disabled)");
    const size_t pixel_count =
        static_cast<size_t>(req.width) * static_cast<size_t>(req.height);
    if (req.img2img && req.img_data.size() != 3 * pixel_count)
      throw std::invalid_argument("Invalid img_data");
    if (req.has_mask &&
        (!req.img2img || req.mask_data_full.size() != 3 * pixel_count))
      throw std::invalid_argument("Invalid mask_data");
    if (!req.reference_images.empty() && !isNativeEditModel())
      throw std::invalid_argument(
          "native reference editing is not supported by this DiT model");

    api_->set_preview_interval(
        ctx_, req.show_diffusion_process ? req.show_diffusion_stride : 0);

    // img2img/inpaint arrives as planar float CHW in [-1,1]; the engine takes
    // packed RGB8, the same layout it hands back.
    std::vector<uint8_t> init_rgb;
    if (req.img2img && !req.img_data.empty())
      init_rgb = planarFloatToRgb(req.img_data, req.width, req.height);
    // RequestParser keeps the full-resolution mask as three identical CHW
    // planes for the built-in pipelines. stable-diffusion.cpp wants one
    // packed grayscale plane and performs its own model-specific latent
    // downsampling, which is important because these DiTs do not use the
    // built-in pipelines' four-channel latent layout.
    std::vector<uint8_t> mask_gray;
    if (req.has_mask)
      mask_gray = planarMaskToGray(req.mask_data_full, req.width, req.height);

    // Unified generation/edit models consume clean, separately VAE-encoded
    // references (and Qwen also sends them through its VLM). The cropped base
    // is reference 1, followed by the user's extra references. A mask or a
    // denoise strength below 1 additionally starts from the base as an init
    // latent. At strength 1 without a mask, the base stays reference-only and
    // the complete schedule runs.
    const bool native_edit =
        isNativeEditModel() && (!req.reference_images.empty() || req.img2img);
    const bool edit_from_base =
        native_edit && req.img2img &&
        (req.has_mask || req.denoise_strength < 1.0f);
    std::vector<const uint8_t *> reference_ptrs;
    std::vector<int> reference_widths;
    std::vector<int> reference_heights;
    if (native_edit) {
      if (!init_rgb.empty()) {
        reference_ptrs.push_back(init_rgb.data());
        reference_widths.push_back(req.width);
        reference_heights.push_back(req.height);
      }
      for (const auto &ref : req.reference_images) {
        if (ref.rgb.empty() || ref.width <= 0 || ref.height <= 0)
          throw std::invalid_argument("Invalid reference image");
        reference_ptrs.push_back(ref.rgb.data());
        reference_widths.push_back(ref.width);
        reference_heights.push_back(ref.height);
      }
    }

    dit_gen_params params{};
    params.prompt = req.prompt.c_str();
    params.negative_prompt = req.negative_prompt.c_str();
    params.width = req.width;
    params.height = req.height;
    params.steps = req.steps;
    params.cfg_scale = req.cfg;
    params.guidance = kDistilledGuidance;
    params.seed = static_cast<int64_t>(req.seed);
    params.sample_method = "euler";
    params.denoise_strength = req.denoise_strength;
    if ((!native_edit || edit_from_base) && !init_rgb.empty()) {
      params.init_image_rgb = init_rgb.data();
      params.init_width = req.width;
      params.init_height = req.height;
    }
    if (!mask_gray.empty()) {
      params.mask_image = mask_gray.data();
      params.mask_width = req.width;
      params.mask_height = req.height;
    }
    if (!reference_ptrs.empty()) {
      params.reference_images_rgb = reference_ptrs.data();
      params.reference_widths = reference_widths.data();
      params.reference_heights = reference_heights.data();
      params.reference_image_count = static_cast<int>(reference_ptrs.size());
    }
    // Full-frame decode needs latent-sized scratch that grows with the square
    // of the resolution; tile once past the point where it stops fitting.
    if (vae_tile_size_ > 0 &&
        static_cast<long>(req.width) * req.height > kTileAbovePixels) {
      params.vae_tile_size = vae_tile_size_;
      params.vae_tile_overlap = 0.25f;
    }

    // One slot per VAE encode before sampling: every reference, plus the
    // init latent for img2img/inpaint.
    const int pre_sample_steps =
        static_cast<int>(reference_ptrs.size()) +
        (params.init_image_rgb != nullptr ? 1 : 0);
    Callbacks callbacks{&req, &progress_callback, nullptr, {}, 0, 0, 0, false,
                        native_edit && params.init_image_rgb == nullptr,
                        pre_sample_steps};
    const auto start = std::chrono::high_resolution_clock::now();

    uint8_t *out_pixels = nullptr;
    int out_width = 0;
    int out_height = 0;
    int out_channels = 0;
    const bool ok = api_->generate(ctx_, &params, &PipelineDit::forwardProgress,
                                   &PipelineDit::forwardPreview, &callbacks,
                                   &out_pixels, &out_width, &out_height,
                                   &out_channels);
    if (!ok) {
      // A progress callback that threw (client hung up) is reported by the
      // engine as a cancellation; rethrow the original so /generate answers
      // with the real reason.
      if (callbacks.pending) std::rethrow_exception(callbacks.pending);
      throw std::runtime_error(std::string("DiT generation failed: ") +
                               api_->last_error(ctx_));
    }

    const auto total = std::chrono::duration_cast<std::chrono::milliseconds>(
                           std::chrono::high_resolution_clock::now() - start)
                           .count();

    if (out_channels != 3 && out_channels != 4) {
      api_->free_image(out_pixels);
      throw std::runtime_error("DiT returned unsupported channel count: " +
                               std::to_string(out_channels));
    }
    GenerationResult result;
    result.image_data.assign(
        out_pixels, out_pixels + static_cast<size_t>(out_width) * out_height *
                                     out_channels);
    api_->free_image(out_pixels);
    if (req.has_mask && req.user_supplied_mask) {
      if (out_width != req.width || out_height != req.height)
        throw std::runtime_error("DiT inpaint output size mismatch");
      blendInpaintResult(result.image_data, out_channels, req);
    }
    // generate() is overridden wholesale here, so the base class's safety pass
    // never runs on its own; the filter build depends on this call.
    applySafetyChecker(result.image_data, out_width, out_height, out_channels);
    result.width = out_width;
    result.height = out_height;
    result.channels = out_channels;
    result.generation_time_ms = static_cast<int>(total);
    result.first_step_time_ms = callbacks.first_step_ms;
    return result;
  }

 protected:
  bool canSkipUncond() const override { return false; }
  bool previewSupported() const override { return true; }

  // The base class drives these from its own denoising loop, which generate()
  // replaces entirely. Reaching one means a caller went around the override,
  // so fail loudly rather than pretend to encode or decode anything.
  void encodeText(const ProcessedPromptPair &, bool, bool,
                  Conditioning &) override {
    throw std::logic_error("DiT engine owns text encoding");
  }
  void vaeEncode(const GenerationRequest &, const float *, float *,
                 float *) override {
    throw std::logic_error("DiT engine owns the VAE encoder");
  }
  void vaeDecode(const GenerationRequest &, const float *, float *) override {
    throw std::logic_error("DiT engine owns the VAE decoder");
  }
  void runUnetStep(const GenerationRequest &, const float *, float, bool,
                   Conditioning &, float *) override {
    throw std::logic_error("DiT engine owns the denoising loop");
  }

 private:
  // Used by guidance-distilled DiTs; Qwen ignores this field. Qwen's default
  // CFG scale is 1, which keeps sampling conditional-only.
  static constexpr float kDistilledGuidance = 3.5f;
  // 1536x1536 still decodes whole on the devices this runs on; 2048 does not.
  static constexpr long kTileAbovePixels = 1536L * 1536L;

  bool isNativeEditModel() const {
    return kind_ == DIT_MODEL_FLUX2_KLEIN ||
           kind_ == DIT_MODEL_QWEN_IMAGE_2_1;
  }

  struct Callbacks {
    GenerationRequest *req;
    const ProgressCallback *progress;
    std::exception_ptr pending;
    std::string preview_b64;
    int first_step_ms = 0;
    // Highest effective sampling step seen so far. The engine marks model
    // loading and VAE work with total_steps=0 so those counters cannot move
    // the generation bar.
    int sampled_steps = 0;
    int sample_steps = 0;
    bool sampling_started = false;
    bool full_sampling_schedule = false;
    int pre_sample_steps = 0;
  };

  static std::vector<uint8_t> planarFloatToRgb(const std::vector<float> &planar,
                                               int width, int height) {
    const size_t plane = static_cast<size_t>(width) * height;
    if (planar.size() < plane * 3) return {};
    std::vector<uint8_t> rgb(plane * 3);
    for (size_t i = 0; i < plane; ++i) {
      for (int c = 0; c < 3; ++c) {
        const float v = (planar[c * plane + i] + 1.0f) / 2.0f * 255.0f;
        rgb[i * 3 + c] = static_cast<uint8_t>(std::clamp(v, 0.0f, 255.0f));
      }
    }
    return rgb;
  }

  static std::vector<uint8_t> planarMaskToGray(
      const std::vector<float> &planar, int width, int height) {
    const size_t plane = static_cast<size_t>(width) * height;
    if (planar.size() < plane) return {};
    std::vector<uint8_t> gray(plane);
    for (size_t i = 0; i < plane; ++i) {
      const float v = std::clamp(planar[i], 0.0f, 1.0f) * 255.0f;
      gray[i] = static_cast<uint8_t>(std::lround(v));
    }
    return gray;
  }

  // Latent masking keeps the unpainted region structurally fixed, but a VAE
  // encode/decode round trip still changes its pixels slightly. Match the
  // built-in pipelines by blending the decoded result against the exact input
  // image before returning it to previews/history/export.
  static void blendInpaintResult(std::vector<uint8_t> &pixels, int channels,
                                 const GenerationRequest &req) {
    const int width = req.width;
    const int height = req.height;
    const size_t plane = static_cast<size_t>(width) * height;
    if ((channels != 3 && channels != 4) ||
        pixels.size() != static_cast<size_t>(channels) * plane ||
        req.img_data.size() != 3 * plane ||
        req.mask_data_full.size() != 3 * plane)
      throw std::invalid_argument("Invalid DiT inpaint buffers");

    std::vector<int> shape = {3, height, width};
    xt::xarray<float> generated = xt::zeros<float>(shape);
    xt::xarray<float> original = xt::zeros<float>(shape);
    xt::xarray<float> mask = xt::zeros<float>(shape);
    std::copy(req.img_data.begin(), req.img_data.end(), original.begin());
    std::copy(req.mask_data_full.begin(), req.mask_data_full.end(),
              mask.begin());
    for (size_t i = 0; i < plane; ++i) {
      const int y = static_cast<int>(i / width);
      const int x = static_cast<int>(i % width);
      for (int c = 0; c < 3; ++c)
        generated(c, y, x) =
            static_cast<float>(pixels[i * channels + c]) / 127.5f - 1.0f;
    }

    auto blended = laplacianPyramidBlend(original, generated, mask);

    for (size_t i = 0; i < plane; ++i) {
      const int y = static_cast<int>(i / width);
      const int x = static_cast<int>(i % width);
      for (int c = 0; c < 3; ++c) {
        const float v = (blended(c, y, x) + 1.0f) * 127.5f;
        pixels[i * channels + c] =
            static_cast<uint8_t>(std::clamp(v, 0.0f, 255.0f));
      }
      if (channels == 4) {
        const float mask_value =
            std::clamp(req.mask_data_full[i], 0.0f, 1.0f);
        const float alpha = mask_value * pixels[i * 4 + 3] +
                            (1.0f - mask_value) * 255.0f;
        pixels[i * 4 + 3] =
            static_cast<uint8_t>(std::clamp(alpha, 0.0f, 255.0f));
      }
    }
  }

  static bool forwardProgress(int step, int total_steps, float step_seconds,
                              void *user_data) {
    auto *cb = static_cast<Callbacks *>(user_data);
    if (total_steps > 0) {
      cb->sampling_started = true;
      cb->sample_steps = total_steps;
      cb->sampled_steps =
          std::max(cb->sampled_steps, std::clamp(step, 0, total_steps));
      if (step == 1)
        cb->first_step_ms = static_cast<int>(step_seconds * 1000.0f);
    }

    // img2img/inpaint first encodes the source image, so reserve one slot
    // before sampling. Every mode reserves the last slot for VAE decode. Most
    // importantly, use the *effective* step count reported by the engine:
    // denoise strength trims a 4-step Klein request to only 1-3 sampling steps.
    const int expected_steps =
        cb->full_sampling_schedule || !cb->req->img2img ||
                cb->req->denoise_strength >= 1.0f
            ? std::max(1, cb->req->steps)
            : std::clamp(
                  static_cast<int>(cb->req->steps *
                                   cb->req->denoise_strength) +
                      1,
                  1, std::max(1, cb->req->steps));
    const int sample_steps =
        cb->sample_steps > 0 ? cb->sample_steps : expected_steps;
    const int pre_sample = cb->pre_sample_steps;
    const int scale = pre_sample + sample_steps + 1;
    const int reported = cb->sampling_started
                             ? pre_sample + cb->sampled_steps
                             : 0;

    try {
      (*cb->progress)(reported, scale, cb->preview_b64);
      cb->preview_b64.clear();
      return true;
    } catch (...) {
      // The core signals a dropped client by throwing out of the callback;
      // that cannot cross the C ABI, so park it and ask the engine to stop.
      cb->pending = std::current_exception();
      return false;
    }
  }

  static void forwardPreview(int, const uint8_t *rgb, int width, int height,
                             void *user_data) {
    auto *cb = static_cast<Callbacks *>(user_data);
    if (!rgb || width <= 0 || height <= 0) return;
    std::vector<uint8_t> data(
        rgb, rgb + static_cast<size_t>(width) * height * 3);
    if (cb->req->preview_format == "jpeg") {
      data = encodeJPEG(data, width, height, 75);
    } else if (cb->req->preview_format == "png") {
      data = encodePNG(data, width, height);
    }
    cb->preview_b64 = base64_encode(std::string(data.begin(), data.end()));
  }

  static void forwardLog(int level, const char *text, void *) {
    if (level >= 3) {
      QNN_ERROR("[dit] %s", text);
    } else {
      QNN_INFO("[dit] %s", text);
    }
  }

  const std::string engine_path_;
  const std::string diffusion_model_path_;
  const std::string llm_path_;
  const std::string llm_vision_path_;
  const std::string vae_path_;
  const dit_model_kind kind_;
  const std::string backend_;
  const std::string params_backend_;
  const int n_threads_;
  const int vae_tile_size_;
  const bool img2img_enabled_;

  void *handle_ = nullptr;
  const dit_engine_api *api_ = nullptr;
  dit_ctx *ctx_ = nullptr;
};

#endif  // PIPELINEDIT_HPP
