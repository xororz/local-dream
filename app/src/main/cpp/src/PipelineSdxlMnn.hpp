#ifndef PIPELINESDXLMNN_HPP
#define PIPELINESDXLMNN_HPP

#include <array>
#include <fstream>
#include "PipelineSdxl.hpp"

// FancyAi SDXL MNN package: dynamic UNet context, dual CLIP, and fixed
// 640px (default) or 1024px VAEs. The shared Pipeline owns tiling/scheduling.
class PipelineSdxlMnn : public PipelineSdxl {
 public:
  using PipelineSdxl::PipelineSdxl;

  bool initialize() override {
    std::ifstream tile_file(model_dir_ + "/vae_tile_size.txt");
    if (tile_file.is_open()) tile_file >> tile_pixels_;
    if (!lowram_) loadClipsIfNeeded();
    return true;
  }

  bool supportsImg2Img() const override { return !vae_encoder_path_.empty(); }

 protected:
  int vaeTilePixelSize() const override { return tile_pixels_; }

  void beginDenoise(const GenerationRequest &req) override {
    nets_[0].reset();
    auto &net = loadStage(0, unet_path_, req);
    auto context = net.getSessionInput(sessions_[0], "encoder_hidden_states");
    const int tokens = text_encoder_.contextLength(req.prompt, req.negative_prompt);
    net.resizeTensor(context, {1, tokens, 2048});
    net.resizeSession(sessions_[0]);
    if (req.use_opencl) net.updateCacheFile(sessions_[0]);
    net.releaseModel();
  }

  void runUnetStep(const GenerationRequest &, const float *latents_batch2,
                   float timestep, bool skip_uncond, Conditioning &cond,
                   float *out_batch2) override {
    auto &net = *nets_[0];
    auto session = sessions_[0];
    const int latent_count = 4 * sample_width * sample_height;
    for (int side = skip_uncond ? 1 : 0; side < 2; ++side) {
      for (const auto &[name, input] : net.getSessionInputAll(session)) {
        MNN::Tensor host(input, MNN::Tensor::CAFFE);
        if (name == "timestamp") {
          host.host<int32_t>()[0] = static_cast<int32_t>(timestep);
        } else {
          const float *source = nullptr;
          if (name == "sample") source = latents_batch2 + side * latent_count;
          else if (name == "encoder_hidden_states") source = side ? cond.posHidden() : cond.negHidden();
          else if (name == "text_embeds") source = side ? cond.posPooled() : cond.negPooled();
          else if (name == "time_ids") source = cond.time_ids.data() + side * 6;
          else throw std::runtime_error(name);
          memcpy(host.host<float>(), source, host.size());
        }
        input->copyFromHostTensor(&host);
      }
      const auto code = net.runSession(session);
      if (code != MNN::NO_ERROR) throw std::runtime_error(std::to_string(code));
      auto output = net.getSessionOutput(session, "output");
      MNN::Tensor host(output, MNN::Tensor::CAFFE);
      output->copyToHostTensor(&host);
      memcpy(out_batch2 + side * latent_count, host.host<float>(), latent_count * sizeof(float));
    }
  }

  void endDenoise() override {
    if (lowram_) nets_[0].reset();
  }

  void vaeEncode(const GenerationRequest &req, const float *image, float *mean,
                 float *std_dev) override {
    auto &net = loadStage(1, vae_encoder_path_, req);
    auto input = net.getSessionInput(sessions_[1], "input");
    MNN::Tensor host(input, MNN::Tensor::CAFFE);
    memcpy(host.host<float>(), image, host.size());
    input->copyFromHostTensor(&host);
    const auto code = net.runSession(sessions_[1]);
    if (code != MNN::NO_ERROR) throw std::runtime_error(std::to_string(code));
    for (const auto &[name, destination] : std::array<std::pair<const char *, float *>, 2>{{
             {"mean", mean}, {"std", std_dev}}}) {
      auto output = net.getSessionOutput(sessions_[1], name);
      MNN::Tensor output_host(output, MNN::Tensor::CAFFE);
      output->copyToHostTensor(&output_host);
      memcpy(destination, output_host.host<float>(), output_host.size());
    }
  }

  void vaeDecode(const GenerationRequest &req, const float *latents,
                 float *pixels) override {
    auto &net = loadStage(2, vae_decoder_path_, req);
    auto input = net.getSessionInput(sessions_[2], "input");
    MNN::Tensor host(input, MNN::Tensor::CAFFE);
    memcpy(host.host<float>(), latents, host.size());
    input->copyFromHostTensor(&host);
    const auto code = net.runSession(sessions_[2]);
    if (code != MNN::NO_ERROR) throw std::runtime_error(std::to_string(code));
    auto output = net.getSessionOutput(sessions_[2], "output");
    MNN::Tensor output_host(output, MNN::Tensor::CAFFE);
    output->copyToHostTensor(&output_host);
    memcpy(pixels, output_host.host<float>(), output_host.size());
  }

  void releaseTransientModels() override {
    if (!lowram_) return;
    releaseClips();
    for (auto &net : nets_) net.reset();
  }

 private:
  MNN::Interpreter &loadStage(size_t stage, const std::string &path,
                              const GenerationRequest &req) {
    if (nets_[stage]) return *nets_[stage];
    if (lowram_) {
      releaseClips();
      for (size_t i = 0; i < nets_.size(); ++i)
        if (i != stage) nets_[i].reset();
    }
    nets_[stage].reset(createMnnInterpreterMmap(path.c_str()));
    if (!nets_[stage]) throw std::runtime_error(path);
    auto &net = *nets_[stage];
    MNN::ScheduleConfig config;
    MNN::BackendConfig backend;
    backend.memory = MNN::BackendConfig::Memory_Low;
    backend.precision = MNN::BackendConfig::Precision_Low;
    backend.power = MNN::BackendConfig::Power_High;
    config.backendConfig = &backend;
    config.type = stage == 0 && req.use_opencl ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;
    config.numThread = 4;
    if (config.type == MNN_FORWARD_OPENCL) {
      config.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
      net.setCacheFile((model_dir_ + "/unet.mnnc").c_str());
    }
    if (stage == 0) net.setSessionMode(MNN::Interpreter::Session_Resize_Defer);
    sessions_[stage] = net.createSession(config);
    if (!sessions_[stage]) throw std::runtime_error(path);
    if (stage != 0) net.releaseModel();
    return net;
  }

  int tile_pixels_ = 640;
  std::array<std::unique_ptr<MNN::Interpreter>, 3> nets_;
  std::array<MNN::Session *, 3> sessions_{};
};

#endif  // PIPELINESDXLMNN_HPP
