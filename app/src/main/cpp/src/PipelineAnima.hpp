#ifndef PIPELINEANIMA_HPP
#define PIPELINEANIMA_HPP

#include <cstdint>
#include <cstdlib>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "Config.hpp"
#include "FlowMatchScheduler.hpp"
#include "PipelineQnn.hpp"

// Anima: a DiT (Cosmos-Predict2 family) text-to-image stack on QNN/HTP at a
// fixed 1024x1024. ALL models are QNN context binaries:
//   * text encoder: Qwen3-0.6B + LLM adapter as one QNN graph (clip.bin),
//       input_embedding[1,512,1024] + t5_ids/t5_mask/qwen_mask ->
//       context[1,512,1024]
//   * UNet (split DiT):
//       unet_part1 : (sample, timestamp, encoder_hidden_states) -> (hidden,
//       emb) unet_part2 : (hidden, timestamp, emb, context) -> velocity sample
//         (part2 recomputes adaln+rope internally; only hidden+emb cross the
//         cut)
//   * VAE decoder: 16-ch latent -> 3-ch pixels.
//   * VAE encoder: 3-ch pixels -> 16-ch latent (mean, std) for img2img/inpaint.
//   * latents: 16-channel, normalized by the Wan 2.1 per-channel mean/std.
//   * scheduler: rectified-flow euler-ancestral (FlowMatchScheduler).
//
// Feature parity with SDXL: prompt cache, aspect-ratio padded inpaint, img2img
// and inpaint all work. Ultrafix is intentionally NOT supported (the DiT is far
// too slow to tile). Like SDXL, lowram mode loads/releases each stage model so
// peak memory stays low; UNLIKE the other formats the UNet is two QNN contexts,
// so in lowram mode the two parts are loaded TOGETHER for the whole denoising
// loop and share a single HTP spill-fill scratch buffer (they run strictly in
// sequence, part1 -> part2, every step). The CLIP and VAE stages stay
// load-on-use / release-after as usual.
//
// In non-lowram mode all resident QNN contexts (clip + unet_part1/2 + vae
// decoder + optional vae encoder) join one spill-fill group: they execute
// strictly in sequence and never concurrently, so one shared scratch buffer
// replaces a multi-GB allocation per context.
class PipelineAnima : public PipelineQnn {
 public:
  PipelineAnima(TextEncoder &text_encoder, const std::string &model_dir,
                std::string clip_path, std::string unet_part1_path,
                std::string unet_part2_path, std::string vae_decoder_path,
                std::string vae_encoder_path, bool lowram,
                bool anima_seq_dit = false)
      : PipelineQnn(text_encoder, model_dir, /*sdxl=*/false,
                    /*use_v_pred=*/false),
        clip_path_(std::move(clip_path)),
        unet_part1_path_(std::move(unet_part1_path)),
        unet_part2_path_(std::move(unet_part2_path)),
        vae_decoder_path_(std::move(vae_decoder_path)),
        vae_encoder_path_(std::move(vae_encoder_path)),
        lowram_(lowram),
        // The sequential-DiT split only makes sense when we are already
        // releasing models per stage; ignore it in resident (non-lowram) mode.
        seq_dit_(lowram && anima_seq_dit) {}

  bool initialize() override {
    if (lowram_) {
      QNN_INFO(
          "[lowram] Anima low-RAM mode: skipping pre-load of "
          "CLIP/UNET/VAE models");
      return true;
    }

    clip_ = qnn_runtime::createModel(clip_path_, "clip");
    unet_part1_ = qnn_runtime::createModel(unet_part1_path_, "unet_part1");
    unet_part2_ = qnn_runtime::createModel(unet_part2_path_, "unet_part2");
    vae_decoder_ = qnn_runtime::createModel(vae_decoder_path_, "vae_decoder");
    if (!clip_ || !unet_part1_ || !unet_part2_ || !vae_decoder_) {
      QNN_ERROR("Failed to create Anima QNN models.");
      return false;
    }
    if (!vae_encoder_path_.empty()) {
      vae_encoder_ = qnn_runtime::createModel(vae_encoder_path_, "vae_encoder");
      if (!vae_encoder_) QNN_WARN("Failed create Anima QNN VAE Enc model.");
    } else {
      QNN_INFO("img2img disabled: Anima VAE encoder not loaded");
    }

    // Probe mode: pre-2.35 binaries carry no spill-fill metadata and the HTP
    // getProperty returns 0, so to learn each context's real requirement, force
    // each as its own 1-byte group head — creation fails and the backend logs
    // "...smaller than required spill-fill size N". Read N from logcat, take
    // the max, set LOCALDREAM_ANIMA_SPILL_FILL_BYTES to it.
    if (getenv("LOCALDREAM_ANIMA_SPILL_FILL_PROBE")) {
      QNN_INFO(
          "[spill-fill] PROBE: forcing each context to log its size; "
          "generation will NOT run this launch");
      clip_->setSpillFillGroup(1, nullptr);
      qnn_runtime::initializeApp("CLIP", clip_);
      unet_part1_->setSpillFillGroup(1, nullptr);
      qnn_runtime::initializeApp("UNET_PART1", unet_part1_);
      unet_part2_->setSpillFillGroup(1, nullptr);
      qnn_runtime::initializeApp("UNET_PART2", unet_part2_);
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

    // All resident QNN contexts stay together but execute strictly in sequence
    // (clip -> part1 -> part2 -> vae), never concurrently, so they can share
    // one HTP spill-fill scratch buffer. The group HEAD owns the buffer and
    // must be destroyed LAST. vae_decoder_ is a base-class (PipelineQnn)
    // member, which destructs AFTER this class's derived members (clip_,
    // unet_part1_, unet_part2_) and after the other base member vae_encoder_
    // (declared later, so destroyed first), making it the safe head — every
    // group reference dies before the buffer. Size comes from the env (pre-2.35
    // binaries don't carry it); 0 disables sharing.
    const uint64_t sf_bytes = spillFillGroupBytes();
    Qnn_ContextHandle_t head = nullptr;
    if (sf_bytes)
      QNN_INFO("[spill-fill] Anima context group sharing enabled: %llu bytes",
               (unsigned long long)sf_bytes);

    vae_decoder_->setSpillFillGroup(sf_bytes, nullptr);
    if (qnn_runtime::initializeApp("VAEDecoder", vae_decoder_) != EXIT_SUCCESS)
      return false;
    if (sf_bytes) head = vae_decoder_->getContextHandle();
    logSpillFill("VAEDecoder", vae_decoder_);

    clip_->setSpillFillGroup(sf_bytes, head);
    if (qnn_runtime::initializeApp("CLIP", clip_) != EXIT_SUCCESS) return false;
    logSpillFill("CLIP", clip_);

    unet_part1_->setSpillFillGroup(sf_bytes, head);
    if (qnn_runtime::initializeApp("UNET_PART1", unet_part1_) != EXIT_SUCCESS)
      return false;
    logSpillFill("UNET_PART1", unet_part1_);

    unet_part2_->setSpillFillGroup(sf_bytes, head);
    if (qnn_runtime::initializeApp("UNET_PART2", unet_part2_) != EXIT_SUCCESS)
      return false;
    logSpillFill("UNET_PART2", unet_part2_);

    if (vae_encoder_) {
      vae_encoder_->setSpillFillGroup(sf_bytes, head);
      if (qnn_runtime::initializeApp("VAEEncoder", vae_encoder_) !=
          EXIT_SUCCESS)
        return false;
      logSpillFill("VAEEncoder", vae_encoder_);
    }

    QNN_INFO("Anima QNN pipeline initialized (clip+unet_part1/2+vae).");
    return true;
  }

  bool supportsImg2Img() const override {
    return lowram_ ? !vae_encoder_path_.empty() : vae_encoder_ != nullptr;
  }
  bool isAnima() const override { return true; }

 protected:
  // Per-stage decode previews would force a VAE decoder load/release per step
  // in lowram mode; disable them there (as SDXL does).
  bool previewSupported() const override { return !lowram_; }
  bool vaeTilingSupported() const override { return false; }
  int vaeTilePixelSize() const override { return 1024; }

  // --- generalization hooks ---
  int latentChannels() const override { return anima_latent_channels; }
  int textHiddenDim() const override { return anima_text_embedding_size; }
  int textPooledDim() const override { return 0; }
  // Anima caches its 512-token Qwen context like SDXL caches its 77-token CLIP;
  // the cache file carries seq_len/mode so the formats never collide.
  bool promptCacheSupported() const override { return true; }

  std::unique_ptr<Scheduler> makeScheduler(const GenerationRequest &req,
                                           const char *) override {
    // "euler" -> deterministic (eta=0); anything else -> ancestral (eta=1).
    float eta = (req.scheduler_type == "euler") ? 0.0f : 1.0f;
    return std::make_unique<FlowMatchScheduler>(/*shift=*/3.0f,
                                                /*multiplier=*/1.0f, eta);
  }

  // model-latent -> VAE latent: x * std + mean, per channel (Wan 2.1).
  void latentsToVae(xt::xarray<float> &latents) const override {
    applyChannelAffine(latents, /*invert=*/false);
  }
  // VAE latent -> model-latent: (x - mean) / std (img2img).
  void vaeToLatents(xt::xarray<float> &latents) const override {
    applyChannelAffine(latents, /*invert=*/true);
  }

  void encodeText(const ProcessedPromptPair &prompts, bool need_negative,
                  bool need_positive, Conditioning &cond) override {
    if (lowram_) loadClipIfNeeded();
    if (!clip_) throw std::runtime_error("Anima text encoder not initialized!");
    if (need_negative)
      runClip(prompts.negative_embeddings, prompts.negative_t5_ids,
              prompts.negative_t5_mask, prompts.negative_qwen_mask,
              cond.negHidden());
    if (need_positive)
      runClip(prompts.positive_embeddings, prompts.positive_t5_ids,
              prompts.positive_t5_mask, prompts.positive_qwen_mask,
              cond.posHidden());
    if (lowram_) releaseClip();
  }

  void vaeEncode(const GenerationRequest &, const float *image, float *mean,
                 float *std_dev) override {
    if (lowram_) loadVaeEncoderIfNeeded();
    if (!vae_encoder_) throw std::runtime_error("Anima VAE encoder missing");
    if (StatusCode::SUCCESS != vae_encoder_->executeAnimaVaeEncoder(
                                   const_cast<float *>(image), mean, std_dev))
      throw std::runtime_error("Anima VAE encode failed");
  }

  void beginDenoise(const GenerationRequest &) override {
    if (!lowram_) return;
    // The encode stage is over once the UNet is needed; never hold both.
    releaseVaeEncoder();
    // Sequential-DiT loads/releases each half inside every step, so there is
    // nothing to pre-load here.
    if (!seq_dit_) loadUnetPartsIfNeeded();
  }

  void runUnetStep(const GenerationRequest &, const float *latents_batch2,
                   float timestep, bool skip_uncond, Conditioning &cond,
                   float *out_batch2) override {
    const int single = anima_latent_channels * sample_width * sample_height;
    float *in = const_cast<float *>(latents_batch2);

    if (seq_dit_) {
      runUnetStepSeqDit(in, single, timestep, skip_uncond, cond, out_batch2);
      return;
    }

    if (!unet_part1_ || !unet_part2_)
      throw std::runtime_error("Anima UNet parts missing");
    if (!skip_uncond) runUnetHalf(in, timestep, cond.negHidden(), out_batch2);
    runUnetHalf(in + single, timestep, cond.posHidden(), out_batch2 + single);
  }

  void endDenoise() override {
    if (!lowram_) return;
    releaseUnetParts();
  }

  void vaeDecode(const GenerationRequest &, const float *latents,
                 float *pixels) override {
    if (lowram_ && !vae_decoder_) {
      vae_decoder_ =
          qnn_runtime::createAndInitModel(vae_decoder_path_, "vae_decoder");
      QNN_INFO("[lowram] Anima VAE Decoder loaded");
    }
    if (!vae_decoder_) throw std::runtime_error("Anima VAE decoder missing");
    if (StatusCode::SUCCESS != vae_decoder_->executeAnimaVaeDecoder(
                                   const_cast<float *>(latents), pixels))
      throw std::runtime_error("Anima VAE decode failed");
    // Lowram: stays loaded for the rest of the decode stage; released by
    // releaseTransientModels when generate() exits.
  }

  // Catch-all for lowram: release whatever stage model is still loaded when
  // generate() exits (normal return or exception).
  void releaseTransientModels() override {
    if (!lowram_) return;
    releaseClip();
    releaseUnetParts();
    if (vae_decoder_) {
      vae_decoder_.reset();
      QNN_INFO("[lowram] Anima VAE Decoder released");
    }
    releaseVaeEncoder();
  }

 private:
  // part1 -> (hidden, emb); part2 recomputes adaln+rope from timestamp, and the
  // app re-supplies timestep + context (it already has both) so they cross the
  // split as graph inputs in the compiler-preferred layout (big HTP speedup).
  void runUnetPart1(float *sample, float timestep, float *context,
                    std::vector<std::vector<float>> &inter) {
    if (StatusCode::SUCCESS !=
        unet_part1_->executeAnimaUnetPart1(sample, timestep, context, inter))
      throw std::runtime_error("Anima UNet part1 failed");
  }
  void runUnetPart2(std::vector<std::vector<float>> &inter, float timestep,
                    float *context, float *out) {
    if (StatusCode::SUCCESS !=
        unet_part2_->executeAnimaUnetPart2(inter, timestep, context, out))
      throw std::runtime_error("Anima UNet part2 failed");
  }

  void runUnetHalf(float *sample, float timestep, float *context, float *out) {
    runUnetPart1(sample, timestep, context, split_intermediates_);
    runUnetPart2(split_intermediates_, timestep, context, out);
  }

  // Sequential-DiT step: hold at most one DiT half resident. Run part1 for both
  // CFG branches and buffer its outputs, release part1, then load part2 and run
  // both branches. This loads/releases each ~GB half once per denoising step
  // (much slower), but the two halves never co-reside, so 12GB devices fit.
  void runUnetStepSeqDit(float *in, int single, float timestep,
                         bool skip_uncond, Conditioning &cond,
                         float *out_batch2) {
    loadUnetPart1Alone();
    if (!skip_uncond)
      runUnetPart1(in, timestep, cond.negHidden(), seq_intermediates_neg_);
    runUnetPart1(in + single, timestep, cond.posHidden(),
                 seq_intermediates_pos_);
    releaseUnetPart1();

    loadUnetPart2Alone();
    if (!skip_uncond)
      runUnetPart2(seq_intermediates_neg_, timestep, cond.negHidden(),
                   out_batch2);
    runUnetPart2(seq_intermediates_pos_, timestep, cond.posHidden(),
                 out_batch2 + single);
    releaseUnetPart2();
  }

  // Merged qwen+adapter QNN graph. Qwen-side (input_embedding, qwen_mask) is
  // anima_qwen_seq_len (512); T5-side (t5_ids, t5_mask) and the context output
  // are anima_text_seq_len (512). -> context [1,512,1024].
  void runClip(const std::vector<float> &input_embedding,
               const std::vector<int> &t5_ids,
               const std::vector<float> &t5_mask,
               const std::vector<float> &qwen_mask, float *out_hidden) {
    if (StatusCode::SUCCESS !=
        clip_->executeAnimaClip(
            input_embedding.data(),
            reinterpret_cast<const int32_t *>(t5_ids.data()), t5_mask.data(),
            qwen_mask.data(), out_hidden))
      throw std::runtime_error("Anima clip (text encoder) failed");
  }

  // ---- lowram stage (un)loading -------------------------------------------
  void loadClipIfNeeded() {
    if (clip_) return;
    clip_ = qnn_runtime::createAndInitModel(clip_path_, "clip");
    QNN_INFO("[lowram] Anima CLIP loaded");
  }
  void releaseClip() {
    if (!clip_) return;
    clip_.reset();
    if (lowram_) QNN_INFO("[lowram] Anima CLIP released");
  }

  // The two DiT halves run strictly in sequence (part1 -> part2) every step and
  // stay resident for the whole denoising loop, so they share one spill-fill
  // buffer: part1 is the group head, part2 references it. part1 is created
  // first and (on release) destroyed last.
  void loadUnetPartsIfNeeded() {
    if (unet_part1_ && unet_part2_) return;
    const uint64_t sf_bytes = spillFillGroupBytes();
    if (sf_bytes)
      QNN_INFO("[lowram] Anima UNet part group sharing enabled: %llu bytes",
               (unsigned long long)sf_bytes);

    unet_part1_ = qnn_runtime::createModel(unet_part1_path_, "unet_part1");
    if (!unet_part1_)
      throw std::runtime_error("[lowram] Failed create Anima unet_part1");
    unet_part1_->setSpillFillGroup(sf_bytes, nullptr);
    if (qnn_runtime::initializeApp("UNET_PART1", unet_part1_) != EXIT_SUCCESS)
      throw std::runtime_error("[lowram] Failed init Anima unet_part1");
    Qnn_ContextHandle_t head =
        sf_bytes ? unet_part1_->getContextHandle() : nullptr;

    unet_part2_ = qnn_runtime::createModel(unet_part2_path_, "unet_part2");
    if (!unet_part2_)
      throw std::runtime_error("[lowram] Failed create Anima unet_part2");
    unet_part2_->setSpillFillGroup(sf_bytes, head);
    if (qnn_runtime::initializeApp("UNET_PART2", unet_part2_) != EXIT_SUCCESS)
      throw std::runtime_error("[lowram] Failed init Anima unet_part2");
    QNN_INFO("[lowram] Anima UNet parts loaded");
  }
  void releaseUnetParts() {
    if (!unet_part1_ && !unet_part2_) return;
    // Release the group reference (part2) before its head (part1).
    unet_part2_.reset();
    unet_part1_.reset();
    if (lowram_) QNN_INFO("[lowram] Anima UNet parts released");
  }

  // Sequential-DiT load/release: each half is loaded standalone (its own
  // spill-fill buffer, no group sharing) because the two are never co-resident.
  void loadUnetPart1Alone() {
    if (unet_part1_) return;
    unet_part1_ =
        qnn_runtime::createAndInitModel(unet_part1_path_, "unet_part1");
    if (!unet_part1_)
      throw std::runtime_error("[seq_dit] Failed to load Anima unet_part1");
  }
  void releaseUnetPart1() {
    if (!unet_part1_) return;
    unet_part1_.reset();
  }
  void loadUnetPart2Alone() {
    if (unet_part2_) return;
    unet_part2_ =
        qnn_runtime::createAndInitModel(unet_part2_path_, "unet_part2");
    if (!unet_part2_)
      throw std::runtime_error("[seq_dit] Failed to load Anima unet_part2");
  }
  void releaseUnetPart2() {
    if (!unet_part2_) return;
    unet_part2_.reset();
  }

  void loadVaeEncoderIfNeeded() {
    if (vae_encoder_) return;
    if (vae_encoder_path_.empty())
      throw std::runtime_error("[lowram] Anima VAE Encoder path missing");
    vae_encoder_ =
        qnn_runtime::createAndInitModel(vae_encoder_path_, "vae_encoder");
    QNN_INFO("[lowram] Anima VAE Encoder loaded");
  }
  void releaseVaeEncoder() {
    if (!vae_encoder_) return;
    vae_encoder_.reset();
    if (lowram_) QNN_INFO("[lowram] Anima VAE Encoder released");
  }

  static void applyChannelAffine(xt::xarray<float> &latents, bool invert) {
    const auto &sh = latents.shape();
    const size_t batch = sh.size() >= 4 ? sh[0] : 1;
    for (size_t nb = 0; nb < batch; ++nb) {
      for (int c = 0; c < anima_latent_channels; ++c) {
        auto v = xt::view(latents, nb, c);
        if (invert)
          v = (v - anima_latent_mean[c]) / anima_latent_std[c];
        else
          v = v * anima_latent_std[c] + anima_latent_mean[c];
      }
    }
  }

  // Max shared spill-fill buffer (bytes) for the Anima context group. The DiT
  // at 1024 may need more than SDXL's UNet, so the default is generous;
  // override with LOCALDREAM_ANIMA_SPILL_FILL_BYTES (run once with
  // LOCALDREAM_ANIMA_SPILL_FILL_PROBE=1 to read each model's real requirement
  // from logcat, take the max). Set to 0 to disable sharing.
  static uint64_t spillFillGroupBytes() {
    const char *e = getenv("LOCALDREAM_ANIMA_SPILL_FILL_BYTES");
    if (e && *e) return strtoull(e, nullptr, 10);
    return 601096192ULL;  // tune via PROBE if creation fails
  }

  static void logSpillFill(const char *name,
                           const std::unique_ptr<QnnModel> &model) {
    if (!model) return;
    uint64_t bytes = model->querySpillFillSize();
    QNN_INFO("[spill-fill] %s requires %llu bytes (%.1f MB)", name,
             (unsigned long long)bytes, bytes / (1024.0 * 1024.0));
  }

  const std::string clip_path_;
  const std::string unet_part1_path_;
  const std::string unet_part2_path_;
  const std::string vae_decoder_path_;
  const std::string vae_encoder_path_;
  const bool lowram_;
  const bool seq_dit_;

  std::unique_ptr<QnnModel> clip_;
  std::unique_ptr<QnnModel> unet_part1_;
  std::unique_ptr<QnnModel> unet_part2_;
  std::vector<std::vector<float>>
      split_intermediates_;  // {hidden, emb}, reused per step (pos half)
  // Sequential-DiT mode buffers part1's output for both halves so part1 can be
  // released before part2 is loaded; one buffer per CFG branch.
  std::vector<std::vector<float>> seq_intermediates_neg_;
  std::vector<std::vector<float>> seq_intermediates_pos_;
};

#endif  // PIPELINEANIMA_HPP
