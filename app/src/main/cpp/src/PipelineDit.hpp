#ifndef PIPELINEDIT_HPP
#define PIPELINEDIT_HPP

#include <dlfcn.h>

#include <chrono>
#include <string>
#include <vector>

#include "DitEngine.h"
#include "Pipeline.hpp"

// Z-Image Turbo and FLUX.2/Klein 4B, run by libdit_engine.so.
//
// Unlike every other pipeline here, this one owns no graphs and no scheduler:
// the engine does the whole txt2img/img2img round trip behind the DitEngine
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
              std::string llm_path, std::string vae_path, dit_model_kind kind,
              std::string backend, std::string params_backend, int n_threads,
              int vae_tile_size)
      : Pipeline(text_encoder, model_dir, /*sdxl=*/false, /*use_v_pred=*/false),
        engine_path_(std::move(engine_path)),
        diffusion_model_path_(std::move(diffusion_model_path)),
        llm_path_(std::move(llm_path)),
        vae_path_(std::move(vae_path)),
        kind_(kind),
        backend_(std::move(backend)),
        params_backend_(std::move(params_backend)),
        n_threads_(n_threads),
        vae_tile_size_(vae_tile_size) {}

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
    params.vae_path = vae_path_.c_str();
    params.backend = backend_.c_str();
    params.params_backend = params_backend_.c_str();
    params.n_threads = n_threads_;
    params.flash_attn = true;
    params.vae_conv_direct = true;

    ctx_ = api_->create(&params);
    if (!ctx_) {
      QNN_ERROR("engine create failed: %s", api_->last_error(nullptr));
      return false;
    }
    QNN_INFO("DiT engine loaded from %s", engine_path_.c_str());
    return true;
  }

  bool supportsImg2Img() const override { return true; }
  bool supportsUltrafix() const override { return false; }

  GenerationResult generate(GenerationRequest &req,
                            const ProgressCallback &progress_callback) override {
    if (!ctx_ || !api_) throw std::runtime_error("DiT engine not initialized");
    if (req.prompt.empty()) throw std::invalid_argument("Prompt empty");

    api_->set_preview_interval(
        ctx_, req.show_diffusion_process ? req.show_diffusion_stride : 0);

    // img2img arrives as planar float CHW in [-1,1]; the engine takes packed
    // RGB8, the same layout it hands back.
    std::vector<uint8_t> init_rgb;
    if (req.img2img && !req.img_data.empty())
      init_rgb = planarFloatToRgb(req.img_data, req.width, req.height);

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
    if (!init_rgb.empty()) {
      params.init_image_rgb = init_rgb.data();
      params.init_width = req.width;
      params.init_height = req.height;
    }
    // Full-frame decode needs latent-sized scratch that grows with the square
    // of the resolution; tile once past the point where it stops fitting.
    if (vae_tile_size_ > 0 &&
        static_cast<long>(req.width) * req.height > kTileAbovePixels) {
      params.vae_tile_size = vae_tile_size_;
      params.vae_tile_overlap = 0.25f;
    }

    Callbacks callbacks{&req, &progress_callback};
    const auto start = std::chrono::high_resolution_clock::now();

    uint8_t *out_rgb = nullptr;
    int out_width = 0;
    int out_height = 0;
    const bool ok = api_->generate(ctx_, &params, &PipelineDit::forwardProgress,
                                   &PipelineDit::forwardPreview, &callbacks,
                                   &out_rgb, &out_width, &out_height);
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

    GenerationResult result;
    result.image_data.assign(
        out_rgb, out_rgb + static_cast<size_t>(out_width) * out_height * 3);
    api_->free_image(out_rgb);
    result.width = out_width;
    result.height = out_height;
    result.channels = 3;
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
  // Both shipped models are guidance-distilled turbo variants and expect this
  // fixed distilled-guidance value; cfg_scale stays the user-facing knob.
  static constexpr float kDistilledGuidance = 3.5f;
  // 1536x1536 still decodes whole on the devices this runs on; 2048 does not.
  static constexpr long kTileAbovePixels = 1536L * 1536L;

  struct Callbacks {
    GenerationRequest *req;
    const ProgressCallback *progress;
    std::exception_ptr pending;
    std::string preview_b64;
    int first_step_ms = 0;
    // Highest sampling step seen so far; stages that are not the sampling
    // loop report this rather than their own counts. See forwardProgress.
    int sampled_steps = 0;
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

  static bool forwardProgress(int step, int total_steps, float step_seconds,
                              void *user_data) {
    auto *cb = static_cast<Callbacks *>(user_data);
    if (step == 1) cb->first_step_ms = static_cast<int>(step_seconds * 1000.0f);

    // The engine drives one progress callback from several places: the
    // sampling loop, the tiled VAE, and lazy weight loading, which reports one
    // step per tensor (hundreds of them). Forwarding all of that as-is runs the
    // bar to 100 once per stage. Only the sampling loop counts to the requested
    // step count, so everything else holds the last sampling position instead
    // of drawing its own bar.
    const int sample_steps = std::max(1, cb->req->steps);
    const int scale = sample_steps + 1;  // last slot covers the VAE decode
    if (total_steps == sample_steps) cb->sampled_steps = std::max(cb->sampled_steps, step);
    const int reported = cb->sampled_steps;

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
  const std::string vae_path_;
  const dit_model_kind kind_;
  const std::string backend_;
  const std::string params_backend_;
  const int n_threads_;
  const int vae_tile_size_;

  void *handle_ = nullptr;
  const dit_engine_api *api_ = nullptr;
  dit_ctx *ctx_ = nullptr;
};

#endif  // PIPELINEDIT_HPP
