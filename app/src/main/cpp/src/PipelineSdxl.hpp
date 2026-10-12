#ifndef PIPELINESDXL_HPP
#define PIPELINESDXL_HPP

#include <MNN/Interpreter.hpp>
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <stdexcept>
#include <string>

#include "Config.hpp"
#include "MnnUtils.hpp"
#include "PipelineQnn.hpp"

// sdxl: QNN (HTP) UNet/VAE at a fixed 1024x1024, dual MNN CLIP encoders
// (CLIP-L + CLIP-G) with pooled output, plus SDXL micro-conditioning
// (time_ids). In lowram mode every stage model is loaded right before use and
// released right after, trading latency for peak memory.
class PipelineSdxl : public PipelineQnn {
 public:
  PipelineSdxl(TextEncoder &text_encoder, const std::string &model_dir,
               std::string clip_path, std::string clip2_path,
               std::string unet_path, std::string vae_decoder_path,
               std::string vae_encoder_path, bool use_v_pred, bool lowram)
      : PipelineQnn(text_encoder, model_dir, /*sdxl=*/true, use_v_pred),
        clip_path_(std::move(clip_path)),
        clip2_path_(std::move(clip2_path)),
        unet_path_(std::move(unet_path)),
        vae_decoder_path_(std::move(vae_decoder_path)),
        vae_encoder_path_(std::move(vae_encoder_path)),
        lowram_(lowram) {}

  ~PipelineSdxl() override { releaseClips(); }

  bool initialize() override {
    if (lowram_) {
      QNN_INFO(
          "[lowram] SDXL low-RAM mode: skipping pre-load of CLIP/UNET/VAE "
          "models");
      return true;
    }

    try {
      loadClipsIfNeeded();
    } catch (const std::exception &e) {
      QNN_ERROR("%s", e.what());
      return false;
    }
    QNN_INFO("Persistent SDXL MNN CLIP1/CLIP2 sessions created.");

    unet_ = qnn_runtime::createModel(unet_path_, "unet");
    if (!unet_) {
      QNN_ERROR("Failed create QNN UNET model.");
      return false;
    }
    vae_decoder_ = qnn_runtime::createModel(vae_decoder_path_, "vae_decoder");
    if (!vae_decoder_) {
      QNN_ERROR("Failed create QNN VAE Decoder model.");
      return false;
    }
    if (!vae_encoder_path_.empty()) {
      vae_encoder_ = qnn_runtime::createModel(vae_encoder_path_, "vae_encoder");
      if (!vae_encoder_) QNN_WARN("Failed create QNN VAE Enc model.");
    } else {
      QNN_INFO("img2img disabled: VAE encoder not loaded");
    }

    // Probe mode: getProperty(MAX_SPILLFILL_BUFFER_SIZE) returns 0 on this HTP
    // version and pre-2.35 binaries carry no metadata, so the only way to learn
    // each model's real spill-fill need is the backend's own validation error.
    // Register each context as its own group head with a 1-byte budget:
    // creation fails and the backend logs "...is smaller than required
    // spill-fill size N" for each. Read those N from logcat, take the max, then
    // set LOCALDREAM_SDXL_SPILL_FILL_BYTES to it and unset the probe var.
    if (getenv("LOCALDREAM_SDXL_SPILL_FILL_PROBE")) {
      QNN_INFO(
          "[spill-fill] PROBE: forcing each context to log its required size; "
          "generation will NOT run this launch");
      unet_->setSpillFillGroup(1, nullptr);
      qnn_runtime::initializeApp("UNET", unet_);
      vae_decoder_->setSpillFillGroup(1, nullptr);
      qnn_runtime::initializeApp("VAEDecoder", vae_decoder_);
      if (vae_encoder_) {
        vae_encoder_->setSpillFillGroup(1, nullptr);
        qnn_runtime::initializeApp("VAEEncoder", vae_encoder_);
      }
      QNN_INFO(
          "[spill-fill] PROBE done; grep logcat for 'required spill-fill "
          "size'");
      return false;
    }

    // Non-lowram keeps UNet + VAE decoder (+ encoder) resident together but
    // they execute strictly in sequence, never concurrently. Register all three
    // QNN contexts into one group so they share a single HTP spill-fill scratch
    // buffer instead of each reserving its own multi-GB allocation. UNet is the
    // group head (created first, destroyed last); the VAEs reference its
    // handle. The size must cover every member: it comes from the binaries'
    // own metadata when they declare one (QNN >= 2.35), falling back to the
    // hardcoded default for pre-2.35 packages, and the env var overrides both.
    // ⚠ A context that refuses the group is retried on its own instead of
    // failing the load: sharing is a memory optimization, never a requirement
    // (a checkpoint converted by another toolchain can declare a larger
    // spill-fill need than this package's default, and used to make the whole
    // backend refuse to start).
    const uint64_t sf_bytes = spillFillGroupBytes();
    if (sf_bytes)
      QNN_INFO("[spill-fill] SDXL context group sharing enabled: %llu bytes",
               (unsigned long long)sf_bytes);

    bool group_head_active = false;
    unet_ = qnn_runtime::createModelWithOptionalGroup(
        unet_path_, "unet", sf_bytes, nullptr, nullptr, 0, &group_head_active);
    if (!unet_) {
      QNN_ERROR("Failed init QNN UNET model.");
      return false;
    }
    group_head_active_ = group_head_active;
    // Record the context length this binary already carries: fixed-chunk
    // packages ship one max_chunks-wide context, patched ones ship 77 and grow
    // via .patch. Without it the first generate() on a fixed-chunk package sees
    // a token mismatch and tears the whole group down to reload the same UNet.
    unet_tokens_ = preloadedContextLength();
    Qnn_ContextHandle_t group_head =
        group_head_active_ ? unet_->getContextHandle() : nullptr;
    logSpillFill("UNET", unet_);

    // A context can only join a live group head; when the UNet had to fall back
    // to a standalone context the VAEs run standalone too.
    const uint64_t member_sf_bytes = group_head ? sf_bytes : 0;

    vae_decoder_ = qnn_runtime::createModelWithOptionalGroup(
        vae_decoder_path_, "vae_decoder", member_sf_bytes, group_head);
    if (!vae_decoder_) {
      QNN_ERROR("Failed init QNN VAE Decoder model.");
      return false;
    }
    logSpillFill("VAEDecoder", vae_decoder_);

    if (vae_encoder_) {
      vae_encoder_ = qnn_runtime::createModelWithOptionalGroup(
          vae_encoder_path_, "vae_encoder", member_sf_bytes, group_head);
      if (!vae_encoder_) QNN_WARN("Failed init QNN VAE Enc model.");
      logSpillFill("VAEEncoder", vae_encoder_);
    }
    return true;
  }

  bool supportsImg2Img() const override {
    return !vae_encoder_path_.empty();
  }

 protected:
  // Per-stage decode previews would force a VAE decoder load/release per
  // step in lowram mode; disable them there.
  bool previewSupported() const override { return !lowram_; }
  // Normal SDXL generation is exactly 1024 so tiling never triggers there;
  // only ultrafix inputs exceed the fixed graph size.
  bool vaeTilingSupported() const override { return true; }
  int vaeTilePixelSize() const override { return 1024; }

  void encodeText(const ProcessedPromptPair &prompts, bool need_negative,
                  bool need_positive, Conditioning &cond) override {
    if (lowram_) loadClipsIfNeeded();
    if (!clip_interpreter_ || !clip2_interpreter_)
      throw std::runtime_error("SDXL CLIP interpreters not initialized!");

    std::vector<float> pooled(text_embedding_size_2);
    for (int chunk = 0; chunk < cond.seq_len / 77; ++chunk) {
      const int offset = chunk * 77;
      if (need_negative) {
        runDualClip(prompts.negative_embeddings.data() + offset * text_embedding_size,
                    prompts.negative_embeddings_2.data() + offset * text_embedding_size_2,
                    prompts.eos_positions[chunk],
                    cond.negHidden() + offset * cond.hidden_dim, pooled.data());
        // Take the pooled vector from the last chunk that holds actual prompt
        // text, not the last padded one: the two sides can need a different
        // number of chunks, and a short negative next to a long positive would
        // otherwise be pooled from an empty chunk.
        if (chunk + 1 == cond.negative_chunks)
          std::copy(pooled.begin(), pooled.end(), cond.negPooled());
      }
      if (need_positive) {
        runDualClip(prompts.positive_embeddings.data() + offset * text_embedding_size,
                    prompts.positive_embeddings_2.data() + offset * text_embedding_size_2,
                    prompts.eos_positions[cond.seq_len / 77 + chunk],
                    cond.posHidden() + offset * cond.hidden_dim, pooled.data());
        if (chunk + 1 == cond.positive_chunks)
          std::copy(pooled.begin(), pooled.end(), cond.posPooled());
      }
    }

    if (lowram_) releaseClips();
  }

  // Lowram model lifetimes are stage-scoped, not call-scoped: a stage model
  // loads on first use and is released only when the next stage needs the
  // memory (or by releaseTransientModels on exit). Tiled ultrafix passes
  // call vaeEncode/vaeDecode/runUnetStep dozens of times per stage, so a
  // per-call load/release would reload a multi-GB model once per tile.
  void vaeEncode(const GenerationRequest &, const float *image, float *mean,
                 float *std_dev) override {
    loadVaeEncoderIfNeeded();
    if (!vae_encoder_) throw std::runtime_error("QNN VAE Enc missing");
    if (StatusCode::SUCCESS != vae_encoder_->executeVaeEncoderGraphsSDXL(
                                   const_cast<float *>(image), mean, std_dev))
      throw std::runtime_error("QNN VAE enc SDXL exec failed");
  }

  void beginDenoise(const GenerationRequest &req) override {
    const int tokens = text_encoder_.contextLength(req.prompt, req.negative_prompt);
    if (unet_ && unet_tokens_ == tokens) return;
    // The UNet is the spill-fill group head, so release its dependents first.
    vae_encoder_.reset();
    vae_decoder_.reset();
    unet_.reset();
    group_head_active_ = false;
    // Build into a local first: patching and bring-up can both throw, and a
    // half-initialized unet_ left behind would be reused by the next request
    // with the same token count and executed on empty graph info.
    std::unique_ptr<qnn_runtime::PatchedModelBuffer> patched;
    if (tokens > 77 && !text_encoder_.fixed_chunks_) {
      patched = qnn_runtime::applyZstdPatchToBuffer(
          unet_path_, model_dir_ + "/" + std::to_string(tokens) + ".patch");
      if (!patched) throw std::runtime_error(unet_path_);
    }

    uint64_t sf_bytes = lowram_ ? 0 : spillFillGroupBytes();
    // The group buffer must cover every member; a patched context is wider
    // than the one on disk and can declare a larger spill-fill size, so raise
    // the group size to match it. If the context still refuses the group, the
    // fallback below brings it up standalone instead of failing the run.
    if (patched) {
      const uint64_t patched_sf = qnn_runtime::contextSpillFillBytes(
          patched->buffer.get(), patched->size);
      if (patched_sf > sf_bytes) {
        sf_bytes = patched_sf;
        noteDeclaredSpillFill(patched_sf);
        QNN_INFO(
            "[spill-fill] patched UNET context declares %llu bytes (%.1f MB)",
            (unsigned long long)patched_sf, patched_sf / (1024.0 * 1024.0));
      }
    }

    unet_ = qnn_runtime::createModelWithOptionalGroup(
        unet_path_, "unet", sf_bytes, nullptr,
        patched ? patched->buffer.get() : nullptr,
        patched ? patched->size : 0, &group_head_active_);
    if (!unet_) throw std::runtime_error("Failed init QNN UNET: " + unet_path_);
    unet_tokens_ = tokens;
    QNN_INFO("[lowram] SDXL UNET loaded");
  }

  void runUnetStep(const GenerationRequest &, const float *latents_batch2,
                   float timestep, bool skip_uncond, Conditioning &cond,
                   float *out_batch2) override {
    if (!unet_) throw std::runtime_error("QNN UNET missing");

    const int single_latent_size = 1 * 4 * sample_width * sample_height;
    const int ts = static_cast<int>(timestep);
    float *latents_in = const_cast<float *>(latents_batch2);
    float *time_ids = cond.time_ids.data();

    if (!skip_uncond &&
        StatusCode::SUCCESS != unet_->executeUnetGraphsSDXL(
                                   latents_in, ts, cond.negHidden(),
                                   cond.negPooled(), time_ids, out_batch2, cond.seq_len, cond.negative_chunks))
      throw std::runtime_error("QNN UNET SDXL exec failed (uncond)");

    if (StatusCode::SUCCESS !=
        unet_->executeUnetGraphsSDXL(
            latents_in + single_latent_size, ts, cond.posHidden(),
            cond.posPooled(), time_ids + 6, out_batch2 + single_latent_size, cond.seq_len, cond.positive_chunks))
      throw std::runtime_error("QNN UNET SDXL exec failed (cond)");
  }

  void endDenoise() override {
    if (!lowram_ || !unet_) return;
    unet_.reset();
    QNN_INFO("[lowram] SDXL UNET released");
  }

  void vaeDecode(const GenerationRequest &, const float *latents,
                 float *pixels) override {
    if (!vae_decoder_) {
      vae_decoder_ = createVaeModel(vae_decoder_path_, "vae_decoder");
      QNN_INFO("[lowram] SDXL VAE Decoder loaded");
    }
    if (!vae_decoder_) throw std::runtime_error("QNN VAE Dec missing");
    if (StatusCode::SUCCESS != vae_decoder_->executeVaeDecoderGraphsSDXL(
                                   const_cast<float *>(latents), pixels))
      throw std::runtime_error("QNN VAE dec SDXL exec failed");
    // Lowram: stays loaded for the rest of the decode stage; released by
    // releaseTransientModels when generate() exits.
  }

  // Catch-all for lowram: release whatever stage model is still loaded when
  // generate() exits (normal return or exception).
  void releaseTransientModels() override {
    if (!lowram_) return;
    if (clip_interpreter_ || clip2_interpreter_) releaseClips();
    if (unet_) {
      unet_.reset();
      QNN_INFO("[lowram] SDXL UNET released");
    }
    if (vae_decoder_) {
      vae_decoder_.reset();
      QNN_INFO("[lowram] SDXL VAE Decoder released");
    }
    if (vae_encoder_) releaseVaeEncoder();
  }

 private:
  // Context length of the unpatched unet binary on disk.
  int preloadedContextLength() const {
    return text_encoder_.fixed_chunks_ ? 77 * text_encoder_.max_chunks_ : 77;
  }

  std::unique_ptr<QnnModel> createVaeModel(const std::string &path,
                                          const std::string &name) {
    // Only join the group while the resident UNet is its head; a standalone
    // context is not a group reference the backend would accept.
    const bool join_group = !lowram_ && group_head_active_ && unet_ != nullptr;
    const uint64_t sf_bytes = join_group ? spillFillGroupBytes() : 0;
    auto model = qnn_runtime::createModelWithOptionalGroup(
        path, name, sf_bytes,
        join_group ? unet_->getContextHandle() : nullptr);
    if (!model) throw std::runtime_error("Failed init QNN model: " + name);
    return model;
  }

  // Max shared spill-fill buffer (bytes) for the SDXL non-lowram context group.
  // The backend honors the group head's value, so it has to be >= the largest
  // requirement among UNet/VAE-dec/VAE-enc. QNN >= 2.35 context binaries
  // declare their real requirement in their metadata; the hardcoded default
  // (920 MiB, covering the measured qnn2.28 requirements: UNet 89,063,424 /
  // VAE-dec 884,801,536 / VAE-enc 575,668,224) only applies to metadata-less
  // packages. Sharing one buffer instead of three saves ~600 MB. The
  // LOCALDREAM_SDXL_SPILL_FILL_BYTES env var overrides both; set it to 0 to
  // disable sharing entirely. A context that still refuses the group falls
  // back to its own buffer, so a mismatch can slow a load but never fail it.
  uint64_t spillFillGroupBytes() const {
    const char *e = getenv("LOCALDREAM_SDXL_SPILL_FILL_BYTES");
    if (e && *e) return strtoull(e, nullptr, 10);
    if (!declared_sf_measured_) {
      declared_sf_measured_ = true;
      declared_sf_ = qnn_runtime::declaredSpillFillBytes(
          {unet_path_, vae_decoder_path_, vae_encoder_path_});
      if (declared_sf_ == 0) {
        QNN_INFO(
            "[spill-fill] SDXL package carries no spill-fill metadata "
            "(pre-2.35 binaries); using the default group size");
      }
    }
    return declared_sf_ > 0 ? declared_sf_ : 964689920ULL;  // 920 MiB
  }

  // Raises the cached group size when a wider (patched) context declares more
  // than the on-disk models do.
  void noteDeclaredSpillFill(uint64_t bytes) const {
    declared_sf_measured_ = true;
    if (bytes > declared_sf_) declared_sf_ = bytes;
  }

  // Diagnostic: log a model's real HTP spill-fill requirement so the right
  // group buffer size can be chosen. Run once with sharing disabled
  // (LOCALDREAM_SDXL_SPILL_FILL_BYTES=0) to read all three clean values.
  static void logSpillFill(const char *name,
                           const std::unique_ptr<QnnModel> &model) {
    if (!model) return;
    uint64_t bytes = model->querySpillFillSize();
    QNN_INFO("[spill-fill] %s requires %llu bytes (%.1f MB)", name,
             (unsigned long long)bytes, bytes / (1024.0 * 1024.0));
  }

 protected:
  void loadClipsIfNeeded() {
    if (!clip_interpreter_) {
      clip_interpreter_ = createMnnInterpreterMmap(clip_path_.c_str());
      if (!clip_interpreter_)
        throw std::runtime_error("Failed load SDXL CLIP1 MNN");
    }
    if (!clip2_interpreter_) {
      clip2_interpreter_ = createMnnInterpreterMmap(clip2_path_.c_str());
      if (!clip2_interpreter_)
        throw std::runtime_error("Failed load SDXL CLIP2 MNN");
    }
    MnnSessionOptions opts;  // CLIP always runs on CPU
    if (!clip_session_) {
      clip_session_ = createMnnSession(clip_interpreter_, opts);
      if (!clip_session_)
        throw std::runtime_error("Failed create SDXL CLIP1 session");
      auto in1 =
          clip_interpreter_->getSessionInput(clip_session_, "input_embedding");
      clip_interpreter_->resizeTensor(in1, {1, 77, text_embedding_size});
      clip_interpreter_->resizeSession(clip_session_);
      clip_interpreter_->releaseModel();
    }
    if (!clip2_session_) {
      clip2_session_ = createMnnSession(clip2_interpreter_, opts);
      if (!clip2_session_)
        throw std::runtime_error("Failed create SDXL CLIP2 session");
      auto in2 = clip2_interpreter_->getSessionInput(clip2_session_,
                                                     "input_embedding");
      clip2_interpreter_->resizeTensor(in2, {1, 77, text_embedding_size_2});
      clip2_interpreter_->resizeSession(clip2_session_);
      clip2_interpreter_->releaseModel();
    }
    if (lowram_) QNN_INFO("[lowram] SDXL CLIP MNN loaded");
  }

  void releaseClips() {
    if (clip_session_ && clip_interpreter_) {
      clip_interpreter_->releaseSession(clip_session_);
    }
    clip_session_ = nullptr;
    if (clip2_session_ && clip2_interpreter_) {
      clip2_interpreter_->releaseSession(clip2_session_);
    }
    clip2_session_ = nullptr;
    delete clip_interpreter_;
    clip_interpreter_ = nullptr;
    delete clip2_interpreter_;
    clip2_interpreter_ = nullptr;
    if (lowram_) QNN_INFO("[lowram] SDXL CLIP MNN released");
  }

  void loadVaeEncoderIfNeeded() {
    if (vae_encoder_) return;
    if (vae_encoder_path_.empty())
      throw std::runtime_error("[lowram] SDXL VAE Encoder path missing");
    vae_encoder_ = createVaeModel(vae_encoder_path_, "vae_encoder");
    QNN_INFO("[lowram] SDXL VAE Encoder loaded");
  }

  void releaseVaeEncoder() {
    if (!vae_encoder_) return;
    vae_encoder_.reset();
    QNN_INFO("[lowram] SDXL VAE Encoder released");
  }

  // Encoder 1 (CLIP-L): 77x768 -> last_hidden_state 77x768.
  // Encoder 2 (CLIP-G): 77x1280 -> last_hidden_state 77x1280 + pooled_output
  // 77x1280 (exported without pooling; we select the EOS row here as the true
  // pooled embedding). Hidden states are concatenated along the feature dim:
  // [77, 768] + [77, 1280] = [77, 2048].
  void runDualClip(const float *emb1,
                   const float *emb2, int eos_pos,
                   float *out_hidden_concat, float *out_pooled) {
    const int concat_dim = text_embedding_size + text_embedding_size_2;

    auto in1 =
        clip_interpreter_->getSessionInput(clip_session_, "input_embedding");
    memcpy(in1->host<float>(), emb1,
           77 * text_embedding_size * sizeof(float));
    clip_interpreter_->runSession(clip_session_);
    auto out1 =
        clip_interpreter_->getSessionOutput(clip_session_, "last_hidden_state");
    const float *out1_data = out1->host<float>();

    auto in2 =
        clip2_interpreter_->getSessionInput(clip2_session_, "input_embedding");
    memcpy(in2->host<float>(), emb2,
           77 * text_embedding_size_2 * sizeof(float));
    clip2_interpreter_->runSession(clip2_session_);
    auto out2_hidden = clip2_interpreter_->getSessionOutput(
        clip2_session_, "last_hidden_state");
    auto out2_pool =
        clip2_interpreter_->getSessionOutput(clip2_session_, "pooled_output");
    const float *out2_hidden_data = out2_hidden->host<float>();
    const float *out2_pool_data = out2_pool->host<float>();

    for (int t = 0; t < 77; t++) {
      memcpy(out_hidden_concat + t * concat_dim,
             out1_data + t * text_embedding_size,
             text_embedding_size * sizeof(float));
      memcpy(out_hidden_concat + t * concat_dim + text_embedding_size,
             out2_hidden_data + t * text_embedding_size_2,
             text_embedding_size_2 * sizeof(float));
    }
    // Use the true EOS row; textual-inversion slots also carry ID 49407.
    memcpy(out_pooled, out2_pool_data + eos_pos * text_embedding_size_2,
           text_embedding_size_2 * sizeof(float));
  }

  const std::string clip_path_;
  const std::string clip2_path_;
  const std::string unet_path_;
  const std::string vae_decoder_path_;
  const std::string vae_encoder_path_;
  const bool lowram_;
  int unet_tokens_ = 77;
  // True while unet_ is registered as the head of a live spill-fill group: the
  // VAEs may only join the group while it is.
  bool group_head_active_ = false;
  // Group size read from the model metadata, measured once per process.
  mutable uint64_t declared_sf_ = 0;
  mutable bool declared_sf_measured_ = false;

  MNN::Interpreter *clip_interpreter_ = nullptr;
  MNN::Interpreter *clip2_interpreter_ = nullptr;
  MNN::Session *clip_session_ = nullptr;
  MNN::Session *clip2_session_ = nullptr;
};

#endif  // PIPELINESDXL_HPP
