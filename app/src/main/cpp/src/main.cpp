#include <algorithm>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Config.hpp"
#include "MnnUtils.hpp"
#include "OpenAiCompat.hpp"
#include "Pipeline.hpp"
#include "PipelineAnima.hpp"
#include "PipelineSd15Cpu.hpp"
#include "PipelineSd15Npu.hpp"
#include "PipelineSdxl.hpp"
#include "QnnRuntime.hpp"
#include "RequestParser.hpp"
#include "SDUtils.hpp"
#include "SafeTensor2MNN.hpp"
#include "TextEncoder.hpp"
#include "Upscaler.hpp"

// QNN Headers
#include "BuildId.hpp"
#include "Logger.hpp"
#include "PAL/GetOpt.hpp"
#include "QnnSampleAppUtils.hpp"

// External Libraries
#include "httplib.h"
#include "json.hpp"

// The server runs exactly one of three fixed model formats, selected by
// --type. Each format implies the full file layout under --model_dir, the
// diffusion backend (MNN vs QNN), and the CLIP pipeline; nothing else is
// configurable per component.
//   sd15cpu: tokenizer.json clip_v2.mnn pos_emb.bin token_emb.bin
//            unet.mnn vae_encoder.mnn vae_decoder.mnn
//   sd15npu: tokenizer.json clip_v2.mnn pos_emb.bin token_emb.bin
//            unet.bin vae_encoder.bin vae_decoder.bin [+resolution patches]
//   sdxl:    tokenizer.json clip.mnn pos_emb.bin token_emb.bin clip_2.mnn
//            pos_emb_2.bin token_emb_2.bin unet.bin vae_encoder.bin
//            vae_decoder.bin
//   anima:   tokenizer.json tokenizer_t5.json token_emb.bin clip.bin
//            unet_part1.bin unet_part2.bin vae_decoder.bin
//            [vae_encoder.bin] (optional; enables img2img/inpaint)
// SD15/SDXL CLIP runs on MNN (CPU); Anima's CLIP (clip.bin) runs on QNN/HTP
// (the C++ side still does the qwen token_emb lookup -> input_embedding).
struct ServerOptions {
  enum class ModelType { kSd15Cpu, kSd15Npu, kSdxl, kAnima };

  int port = 8081;
  std::string listen_address = "127.0.0.1";
  ModelType type = ModelType::kSd15Npu;
  std::string model_dir;
  std::string lib_dir;
  std::string patch_path;
  std::string safety_checker_path;
  float nsfw_threshold = 0.5f;
  bool use_v_pred = false;
  bool no_img2img = false;  // skip the VAE encoder entirely
  bool lowram = false;
  bool anima_seq_dit = false;  // (anima+lowram) never co-resident DiT halves
  bool openai_api_enabled = true;
  std::string api_key;
  bool upscaler_mode = false;
  bool convert_mode = false;
  bool convert_clip_skip_2 = false;

  bool isSdxl() const { return type == ModelType::kSdxl; }
  bool isAnima() const { return type == ModelType::kAnima; }
  bool isMnn() const { return type == ModelType::kSd15Cpu; }
};

static void showHelp() {
  std::cout
      << "Usage:\n"
         "  stable_diffusion_core --type <sd15cpu|sd15npu|sdxl> "
         "--model_dir <dir> [--lib_dir <dir>] [options]\n"
         "  stable_diffusion_core --upscaler_mode [--lib_dir <dir>] "
         "[options]\n"
         "  stable_diffusion_core --convert <dir> [--clip_skip_2]\n"
         "\n"
         "Modes:\n"
         "  --type <type>          Model format: sd15cpu (MNN), sd15npu "
         "(QNN), sdxl (QNN), anima (QNN)\n"
         "  --upscaler_mode        Upscale-only server, no diffusion model\n"
         "  --convert <dir>        Convert model.safetensors in <dir> to MNN "
         "and exit\n"
         "\n"
         "Paths:\n"
         "  --model_dir <dir>      Directory with the fixed per-type model "
         "files\n"
         "  --lib_dir <dir>        Directory with libQnnHtp.so / "
         "libQnnSystem.so (QNN types)\n"
         "  --patch <file>         zstd resolution patch for unet.bin "
         "(sd15npu)\n"
         "  --safety_checker <f>   NSFW checker MNN model\n"
         "\n"
         "Options:\n"
         "  --port <n>             HTTP port (default 8081)\n"
         "  --listen_all           Listen on 0.0.0.0 instead of 127.0.0.1\n"
         "  --no_img2img           Do not load the VAE encoder\n"
         "  --use_v_pred           v-prediction model\n"
         "  --lowram               (sdxl/anima) load/release models per stage\n"
         "  --anima_seq_dit        (anima+lowram) never keep both DiT halves "
         "resident; for 12GB devices\n"
         "  --no_openai_api        Disable OpenAI-compatible image routes\n"
         "  --api_key <key>        Require Bearer auth for OpenAI-compatible "
         "routes\n"
         "  --clip_skip_2          (convert) export CLIP with skip 2\n"
         "  --log_level <n>        QNN log level\n"
         "  --version              Print QNN SDK build id\n"
         "  --help                 Show this help\n";
}

static void showHelpAndExit(std::string &&error) {
  std::cerr << "ERROR: " << error << "\n";
  showHelp();
  std::exit(EXIT_FAILURE);
}

static ServerOptions processCommandLine(int argc, char **argv) {
  using namespace qnn::tools;
  enum OPTIONS {
    OPT_HELP = 0,
    OPT_VERSION,
    OPT_TYPE,
    OPT_MODEL_DIR,
    OPT_LIB_DIR,
    OPT_PORT,
    OPT_LISTEN_ALL,
    OPT_NO_IMG2IMG,
    OPT_USE_V_PRED,
    OPT_SAFETY_CHECKER,
    OPT_CONVERT,
    OPT_CONVERT_CLIP_SKIP_2,
    OPT_PATCH,
    OPT_UPSCALER_MODE,
    OPT_LOWRAM,
    OPT_ANIMA_SEQ_DIT,
    OPT_NO_OPENAI_API,
    OPT_API_KEY,
    OPT_LOG_LEVEL
  };
  static struct pal::Option s_longOptions[] = {
      {"help", pal::no_argument, NULL, OPT_HELP},
      {"version", pal::no_argument, NULL, OPT_VERSION},
      {"type", pal::required_argument, NULL, OPT_TYPE},
      {"model_dir", pal::required_argument, NULL, OPT_MODEL_DIR},
      {"lib_dir", pal::required_argument, NULL, OPT_LIB_DIR},
      {"port", pal::required_argument, NULL, OPT_PORT},
      {"listen_all", pal::no_argument, NULL, OPT_LISTEN_ALL},
      {"no_img2img", pal::no_argument, NULL, OPT_NO_IMG2IMG},
      {"use_v_pred", pal::no_argument, NULL, OPT_USE_V_PRED},
      {"safety_checker", pal::required_argument, NULL, OPT_SAFETY_CHECKER},
      {"convert", pal::required_argument, NULL, OPT_CONVERT},
      {"clip_skip_2", pal::no_argument, NULL, OPT_CONVERT_CLIP_SKIP_2},
      {"patch", pal::required_argument, NULL, OPT_PATCH},
      {"upscaler_mode", pal::no_argument, NULL, OPT_UPSCALER_MODE},
      {"lowram", pal::no_argument, NULL, OPT_LOWRAM},
      {"anima_seq_dit", pal::no_argument, NULL, OPT_ANIMA_SEQ_DIT},
      {"no_openai_api", pal::no_argument, NULL, OPT_NO_OPENAI_API},
      {"api_key", pal::required_argument, NULL, OPT_API_KEY},
      {"log_level", pal::required_argument, NULL, OPT_LOG_LEVEL},
      {NULL, 0, NULL, 0}};

  ServerOptions opts;
  std::string typeStr;
  QnnLog_Level_t logLevel = QNN_LOG_LEVEL_ERROR;
  int longIndex = 0, opt = 0;
  while ((opt = pal::getOptLongOnly(argc, argv, "", s_longOptions,
                                    &longIndex)) != -1) {
    switch (opt) {
      case OPT_HELP:
        showHelp();
        std::exit(EXIT_SUCCESS);
        break;
      case OPT_VERSION:
        std::cout << "QNN SDK " << qnn::tools::getBuildId() << "\n";
        std::exit(EXIT_SUCCESS);
        break;
      case OPT_TYPE:
        typeStr = pal::g_optArg;
        break;
      case OPT_MODEL_DIR:
        opts.model_dir = pal::g_optArg;
        break;
      case OPT_LIB_DIR:
        opts.lib_dir = pal::g_optArg;
        break;
      case OPT_PORT:
        opts.port = std::stoi(pal::g_optArg);
        break;
      case OPT_LISTEN_ALL:
        opts.listen_address = "0.0.0.0";
        break;
      case OPT_NO_IMG2IMG:
        opts.no_img2img = true;
        break;
      case OPT_USE_V_PRED:
        opts.use_v_pred = true;
        break;
      case OPT_SAFETY_CHECKER:
        opts.safety_checker_path = pal::g_optArg;
        break;
      case OPT_CONVERT:
        opts.convert_mode = true;
        opts.model_dir = pal::g_optArg;
        break;
      case OPT_CONVERT_CLIP_SKIP_2:
        opts.convert_clip_skip_2 = true;
        break;
      case OPT_PATCH:
        opts.patch_path = pal::g_optArg;
        break;
      case OPT_UPSCALER_MODE:
        opts.upscaler_mode = true;
        break;
      case OPT_LOWRAM:
        opts.lowram = true;
        break;
      case OPT_ANIMA_SEQ_DIT:
        opts.anima_seq_dit = true;
        break;
      case OPT_NO_OPENAI_API:
        opts.openai_api_enabled = false;
        break;
      case OPT_API_KEY:
        opts.api_key = pal::g_optArg;
        break;
      case OPT_LOG_LEVEL:
        logLevel = sample_app::parseLogLevel(pal::g_optArg);
        if (logLevel != QNN_LOG_LEVEL_MAX) {
          if (!qnn::log::setLogLevel(logLevel))
            showHelpAndExit("Unable to set log level.");
        }
        break;
      default:
        showHelpAndExit("Invalid argument passed.");
    }
  }

  if (opts.upscaler_mode || opts.convert_mode) return opts;

  if (typeStr == "sd15cpu")
    opts.type = ServerOptions::ModelType::kSd15Cpu;
  else if (typeStr == "sdxl")
    opts.type = ServerOptions::ModelType::kSdxl;
  else if (typeStr == "sd15npu")
    opts.type = ServerOptions::ModelType::kSd15Npu;
  else if (typeStr == "anima")
    opts.type = ServerOptions::ModelType::kAnima;
  else
    showHelpAndExit(typeStr.empty() ? "Missing --type"
                                    : "Invalid --type: " + typeStr);
  if (opts.model_dir.empty()) showHelpAndExit("Missing --model_dir");
  return opts;
}

// Converts model.safetensors (plus any lora.N.safetensors next to it) in the
// model directory to MNN format, then exits.
static void runConvertMode(const ServerOptions &opts) {
  if (!std::filesystem::exists(opts.model_dir)) {
    showHelpAndExit("Model directory does not exist: " + opts.model_dir);
  }
  std::string model_name = "model.safetensors";
  auto model_path = std::filesystem::path(opts.model_dir) / model_name;
  if (!std::filesystem::exists(model_path)) {
    showHelpAndExit("Model file does not exist");
  }

  std::vector<std::string> loras;
  std::vector<float> lora_weights;
  for (int i = 1;; ++i) {
    std::string lora_filename = "lora." + std::to_string(i) + ".safetensors";
    auto lora_path = std::filesystem::path(opts.model_dir) / lora_filename;
    if (!std::filesystem::exists(lora_path)) {
      break;
    }
    loras.push_back(lora_filename);

    std::string weight_filename = "lora." + std::to_string(i) + ".weight";
    auto weight_path = std::filesystem::path(opts.model_dir) / weight_filename;
    float weight = 1.0f;

    if (std::filesystem::exists(weight_path)) {
      std::ifstream weight_file(weight_path);
      if (weight_file.is_open()) {
        weight_file >> weight;
        weight_file.close();
      }
    }
    lora_weights.push_back(weight);
  }

  generateMNNModels(opts.model_dir, model_name, opts.convert_clip_skip_2, loras,
                    lora_weights);
}

// Verifies the fixed per-type file layout under --model_dir and constructs
// the matching pipeline (not yet initialized).
static std::unique_ptr<Pipeline> createPipeline(const ServerOptions &opts,
                                                TextEncoder &text_encoder) {
  const std::filesystem::path dir(opts.model_dir);
  const bool sdxl = opts.isSdxl();
  const bool anima = opts.isAnima();

  // Anima: Qwen "CLIP" (clip.bin, QNN) + split DiT (unet_part1/2.bin) + 16-ch
  // VAE. The Qwen text encoder uses RoPE internally, so there is no
  // pos_emb.bin.
  if (anima) {
    std::string clip_path = (dir / "clip.bin").string();
    std::string unet_part1_path = (dir / "unet_part1.bin").string();
    std::string unet_part2_path = (dir / "unet_part2.bin").string();
    std::string vae_decoder_path = (dir / "vae_decoder.bin").string();
    std::string vae_encoder_path =
        opts.no_img2img ? "" : (dir / "vae_encoder.bin").string();

    std::vector<std::string> required = {
        (dir / "tokenizer.json").string(),
        (dir / "tokenizer_t5.json").string(),
        clip_path,
        unet_part1_path,
        unet_part2_path,
        vae_decoder_path,
        (dir / "token_emb.bin").string(),
    };
    if (!vae_encoder_path.empty()) required.push_back(vae_encoder_path);
    for (const auto &p : required) {
      if (!std::filesystem::exists(p)) showHelpAndExit("File not found: " + p);
    }
    return std::make_unique<PipelineAnima>(
        text_encoder, opts.model_dir, clip_path, unet_part1_path,
        unet_part2_path, vae_decoder_path, vae_encoder_path, opts.lowram,
        opts.anima_seq_dit);
  }

  const std::string ext = opts.isMnn() ? ".mnn" : ".bin";
  std::string clip_path = (dir / (sdxl ? "clip.mnn" : "clip_v2.mnn")).string();
  std::string clip2_path = sdxl ? (dir / "clip_2.mnn").string() : "";
  std::string unet_path = (dir / ("unet" + ext)).string();
  std::string vae_decoder_path = (dir / ("vae_decoder" + ext)).string();
  std::string vae_encoder_path =
      opts.no_img2img ? "" : (dir / ("vae_encoder" + ext)).string();

  std::vector<std::string> required = {
      (dir / "tokenizer.json").string(),
      clip_path,
      unet_path,
      vae_decoder_path,
      (dir / "pos_emb.bin").string(),
      (dir / "token_emb.bin").string(),
  };
  if (!vae_encoder_path.empty()) required.push_back(vae_encoder_path);
  if (sdxl) {
    required.push_back(clip2_path);
    required.push_back((dir / "pos_emb_2.bin").string());
    required.push_back((dir / "token_emb_2.bin").string());
  }
  for (const auto &p : required) {
    if (!std::filesystem::exists(p)) showHelpAndExit("File not found: " + p);
  }

  switch (opts.type) {
    case ServerOptions::ModelType::kSd15Cpu:
      return std::make_unique<PipelineSd15Cpu>(
          text_encoder, opts.model_dir, clip_path, unet_path, vae_decoder_path,
          vae_encoder_path, opts.use_v_pred);
    case ServerOptions::ModelType::kSd15Npu:
      return std::make_unique<PipelineSd15Npu>(
          text_encoder, opts.model_dir, clip_path, unet_path, vae_decoder_path,
          vae_encoder_path, opts.patch_path, opts.use_v_pred);
    case ServerOptions::ModelType::kSdxl:
    default:
      return std::make_unique<PipelineSdxl>(
          text_encoder, opts.model_dir, clip_path, clip2_path, unet_path,
          vae_decoder_path, vae_encoder_path, opts.use_v_pred, opts.lowram);
  }
}

// Encodes the final image per the requested wire format and wraps it base64.
static std::string encodeResultImage(const GenerationResult &result,
                                     const std::string &format) {
  if (format == "jpeg") {
    auto jpeg = encodeJPEG(result.image_data, result.width, result.height, 95);
    return base64_encode(std::string(jpeg.begin(), jpeg.end()));
  }
  if (format == "png") {
    auto png = encodePNG(result.image_data, result.width, result.height);
    return base64_encode(std::string(png.begin(), png.end()));
  }
  return base64_encode(
      std::string(result.image_data.begin(), result.image_data.end()));
}

// Serializes generations: the pipelines share MNN sessions and the global IO
// dimensions, so two requests must never run generate() concurrently (e.g. a
// new request arriving while an aborted one is still winding down).
static std::mutex g_generation_mutex;

static GenerationResult runGenerationLocked(Pipeline *pipeline,
                                            GenerationRequest &req,
                                            const ProgressCallback &cb) {
  std::lock_guard<std::mutex> generation_lock(g_generation_mutex);
  return pipeline->generate(req, cb);
}

static void registerGenerateEndpoint(httplib::Server &svr, Pipeline *pipeline) {
  svr.Post("/generate", [pipeline](const httplib::Request &request,
                                   httplib::Response &res) {
    try {
      auto json = nlohmann::json::parse(request.body);
      auto req = std::make_shared<GenerationRequest>(parseGenerationRequest(
          json, pipeline->isSdxl(), pipeline->isAnima(),
          pipeline->supportsImg2Img(), pipeline->supportsUltrafix()));

      std::cout << "Req Rcvd: P:" << req->prompt
                << " NP:" << req->negative_prompt << " S:" << req->steps
                << " CFG:" << req->cfg << " Seed:" << req->seed
                << " Size:" << req->width << "x" << req->height
                << " Img2Img:" << req->img2img << " Mask:" << req->has_mask
                << " Ultrafix:" << req->ultrafix
                << " Denoise:" << req->denoise_strength
                << " ShowProcess:" << req->show_diffusion_process
                << " Stride:" << req->show_diffusion_stride << std::endl;
      res.set_header("Content-Type", "text/event-stream");
      res.set_header("Cache-Control", "no-cache");
      res.set_header("Connection", "keep-alive");
      res.set_chunked_content_provider(
          "text/event-stream",
          [pipeline, req](intptr_t, httplib::DataSink &sink) -> bool {
            try {
              auto result = runGenerationLocked(
                  pipeline, *req, [&sink, &req](int s, int t,
                                                const std::string &img) {
                    nlohmann::json p = {
                        {"type", "progress"}, {"step", s}, {"total_steps", t}};
                    if (!img.empty()) {
                      p["image"] = img;
                      p["format"] = req->preview_format;
                    }
                    std::string ev =
                        "event: progress\ndata: " + p.dump() + "\n\n";
                    // A failed write means the client hung up (cancelled).
                    // Abort the generation right away instead of burning
                    // NPU/CPU on a result nobody will receive.
                    if (!sink.is_writable() ||
                        !sink.write(ev.c_str(), ev.size()))
                      throw std::runtime_error(
                          "Client disconnected, generation aborted");
                  });
              auto enc_start = std::chrono::high_resolution_clock::now();
              std::string enc_img =
                  encodeResultImage(result, req->output_format);
              auto enc_end = std::chrono::high_resolution_clock::now();
              std::cout
                  << "Enc time: "
                  << std::chrono::duration_cast<std::chrono::milliseconds>(
                         enc_end - enc_start)
                         .count()
                  << "ms\n";
              nlohmann::json c = {
                  {"type", "complete"},
                  {"image", enc_img},
                  {"format", req->output_format},
                  {"seed", req->seed},
                  {"width", result.width},
                  {"height", result.height},
                  {"channels", result.channels},
                  {"generation_time_ms", result.generation_time_ms},
                  {"first_step_time_ms", result.first_step_time_ms}};
              std::string ev = "event: complete\ndata: " + c.dump() + "\n\n";
              auto send_start = std::chrono::high_resolution_clock::now();
              sink.write(ev.c_str(), ev.size());
              auto send_end = std::chrono::high_resolution_clock::now();
              std::cout
                  << "Image send time: "
                  << std::chrono::duration_cast<std::chrono::milliseconds>(
                         send_end - send_start)
                         .count()
                  << "ms, size: " << ev.size() << " bytes\n";
              sink.done();
              return true;
            } catch (const std::exception &e) {
              nlohmann::json err = {{"type", "error"}, {"message", e.what()}};
              std::string ev = "event: error\ndata: " + err.dump() + "\n\n";
              sink.write(ev.c_str(), ev.size());
              sink.done();
              return false;
            }
          });
    } catch (const nlohmann::json::parse_error &e) {
      nlohmann::json err = {
          {"error",
           {{"message", "Invalid JSON: " + std::string(e.what())},
            {"type", "request_error"}}}};
      res.status = 400;
      res.set_content(err.dump(), "application/json");
    } catch (const std::invalid_argument &e) {
      nlohmann::json err = {
          {"error",
           {{"message", "Invalid Arg: " + std::string(e.what())},
            {"type", "request_error"}}}};
      res.status = 400;
      res.set_content(err.dump(), "application/json");
    } catch (const std::exception &e) {
      nlohmann::json err = {
          {"error",
           {{"message", "Server Err: " + std::string(e.what())},
            {"type", "server_error"}}}};
      res.status = 500;
      res.set_content(err.dump(), "application/json");
    }
  });
}

static void registerOpenAiImageEndpoints(httplib::Server &svr,
                                         Pipeline *pipeline,
                                         const ServerOptions &opts) {
  if (!opts.openai_api_enabled) return;

  OpenAiRouteOptions route_opts = {
      pipeline->isSdxl() || pipeline->isAnima(),
      pipeline->supportsImg2Img(),
      opts.api_key,
      std::filesystem::path(opts.model_dir).filename().string(),
  };

  auto handle_openai_request =
      [pipeline](const OpenAiPreparedRequest &prepared, httplib::Response &res) {
        std::vector<std::string> images_b64;
        images_b64.reserve(prepared.items.size());
        for (const auto &item_json : prepared.items) {
          GenerationRequest req = parseGenerationRequest(
              item_json, pipeline->isSdxl(), pipeline->isAnima(),
              pipeline->supportsImg2Img(), pipeline->supportsUltrafix());
          auto result = runGenerationLocked(
              pipeline, req,
              [](int, int, const std::string &) {
                // OpenAI-compatible routes return only the final image.
              });
          images_b64.push_back(encodeResultImage(result, "png"));
        }
        res.status = 200;
        res.set_content(buildOpenAiImagesResponse(images_b64).dump(),
                        "application/json");
      };

  svr.Post("/v1/images/generations",
           [route_opts, handle_openai_request](const httplib::Request &request,
                                               httplib::Response &res) {
             try {
               requireOpenAiAuthorization(request, route_opts);
               auto json = nlohmann::json::parse(request.body);
               auto prepared = parseOpenAiGenerationRequest(json, route_opts);
               handle_openai_request(prepared, res);
             } catch (const OpenAiHttpError &e) {
               setOpenAiError(res, e.status, e.what(), e.type, e.param);
             } catch (const nlohmann::json::parse_error &e) {
               setOpenAiError(res, 400,
                              "Invalid JSON: " + std::string(e.what()),
                              "invalid_request_error");
             } catch (const nlohmann::json::exception &e) {
               setOpenAiError(res, 400,
                              "Invalid request field type: " +
                                  std::string(e.what()),
                              "invalid_request_error");
             } catch (const std::invalid_argument &e) {
               setOpenAiError(res, 400,
                              "Invalid Arg: " + std::string(e.what()),
                              "invalid_request_error");
             } catch (const std::exception &e) {
               setOpenAiError(res, 500,
                              "Server Err: " + std::string(e.what()),
                              "server_error");
             }
           });

  svr.Post("/v1/images/edits",
           [route_opts, handle_openai_request](const httplib::Request &request,
                                               httplib::Response &res) {
             try {
               requireOpenAiAuthorization(request, route_opts);
               auto prepared = parseOpenAiEditRequest(request, route_opts);
               handle_openai_request(prepared, res);
             } catch (const OpenAiHttpError &e) {
               setOpenAiError(res, e.status, e.what(), e.type, e.param);
             } catch (const nlohmann::json::exception &e) {
               setOpenAiError(res, 400,
                              "Invalid request field type: " +
                                  std::string(e.what()),
                              "invalid_request_error");
             } catch (const std::invalid_argument &e) {
               setOpenAiError(res, 400,
                              "Invalid Arg: " + std::string(e.what()),
                              "invalid_request_error");
             } catch (const std::exception &e) {
               setOpenAiError(res, 500,
                              "Server Err: " + std::string(e.what()),
                              "server_error");
             }
           });
}

// Binary protocol upscale endpoint - optimized for performance.
static void registerUpscaleEndpoint(httplib::Server &svr) {
  svr.Post("/upscale", [](const httplib::Request &req, httplib::Response &res) {
    std::unique_ptr<QnnModel> tempUpscalerApp = nullptr;

    try {
      if (!req.has_header("X-Image-Width")) {
        throw std::invalid_argument("Missing 'X-Image-Width' header");
      }
      if (!req.has_header("X-Image-Height")) {
        throw std::invalid_argument("Missing 'X-Image-Height' header");
      }
      if (!req.has_header("X-Upscaler-Path")) {
        throw std::invalid_argument("Missing 'X-Upscaler-Path' header");
      }

      int original_width = std::stoi(req.get_header_value("X-Image-Width"));
      int original_height = std::stoi(req.get_header_value("X-Image-Height"));
      std::string upscaler_path = req.get_header_value("X-Upscaler-Path");

      // Check if use_opencl header is present (for MNN models).
      bool use_opencl = false;
      if (req.has_header("X-Use-OpenCL")) {
        std::string opencl_str = req.get_header_value("X-Use-OpenCL");
        use_opencl = (opencl_str == "true" || opencl_str == "1");
      }

      // Determine model type based on file extension.
      bool is_mnn_model = false;
      if (upscaler_path.size() >= 4) {
        std::string ext = upscaler_path.substr(upscaler_path.size() - 4);
        std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
        is_mnn_model = (ext == ".mnn");
      }

      QNN_INFO("Binary upscale request: %dx%d, upscaler: %s, type: %s%s",
               original_width, original_height, upscaler_path.c_str(),
               is_mnn_model ? "MNN" : "QNN",
               is_mnn_model && use_opencl ? " (OpenCL)" : "");

      std::vector<uint8_t> image_data(req.body.begin(), req.body.end());

      if (image_data.size() != (size_t)original_width * original_height * 3) {
        throw std::invalid_argument(
            "Image data size mismatch. Expected " +
            std::to_string(original_width * original_height * 3) +
            " bytes, got " + std::to_string(image_data.size()) + " bytes");
      }

      // Pre-process: resize if shortest edge < 192.
      const int min_size = 192;
      int process_width = original_width;
      int process_height = original_height;
      std::vector<uint8_t> process_image = image_data;

      if (std::min(original_width, original_height) < min_size) {
        QNN_INFO("Image too small (%dx%d), resizing to min edge %d",
                 original_width, original_height, min_size);
        process_image =
            resizeImageToMinSize(image_data, original_width, original_height,
                                 min_size, process_width, process_height);
        QNN_INFO("Resized to %dx%d for processing", process_width,
                 process_height);
      }

      auto start_time = std::chrono::high_resolution_clock::now();

      xt::xarray<uint8_t> upscaled;

      if (is_mnn_model) {
        upscaled =
            upscaler::upscaleWithMnn(process_image, process_width,
                                     process_height, upscaler_path, use_opencl);
      } else {
        tempUpscalerApp = qnn_runtime::createModel(upscaler_path, "upscaler");
        if (!tempUpscalerApp) {
          throw std::runtime_error("Failed to create upscaler model from: " +
                                   upscaler_path);
        }

        auto status = qnn_runtime::initializeApp("Upscaler", tempUpscalerApp);
        if (status != EXIT_SUCCESS) {
          throw std::runtime_error("Failed to initialize upscaler model");
        }

        upscaled = upscaler::upscaleWithQnn(process_image, process_width,
                                            process_height, tempUpscalerApp);
      }

      auto end_time = std::chrono::high_resolution_clock::now();
      int duration = std::chrono::duration_cast<std::chrono::milliseconds>(
                         end_time - start_time)
                         .count();

      int upscaled_width = process_width * 4;
      int upscaled_height = process_height * 4;

      // Post-process: resize back to target dimensions if needed.
      int final_width = original_width * 4;
      int final_height = original_height * 4;
      std::vector<uint8_t> final_rgb(upscaled.begin(), upscaled.end());

      if (upscaled_width != final_width || upscaled_height != final_height) {
        QNN_INFO("Resizing output from %dx%d to %dx%d", upscaled_width,
                 upscaled_height, final_width, final_height);
        final_rgb =
            resizeImageToTarget(final_rgb, upscaled_width, upscaled_height,
                                final_width, final_height);
      }

      auto encode_start = std::chrono::high_resolution_clock::now();
      std::vector<uint8_t> output_jpeg =
          encodeJPEG(final_rgb, final_width, final_height, 95);
      auto encode_end = std::chrono::high_resolution_clock::now();
      int encode_duration =
          std::chrono::duration_cast<std::chrono::milliseconds>(encode_end -
                                                                encode_start)
              .count();

      QNN_INFO("Upscaling completed in %d ms: %dx%d -> %dx%d", duration,
               original_width, original_height, final_width, final_height);
      QNN_INFO("JPEG encoding time: %d ms, size: %zu KB", encode_duration,
               output_jpeg.size() / 1024);

      res.status = 200;
      res.set_content(std::string(output_jpeg.begin(), output_jpeg.end()),
                      "image/jpeg");
      res.set_header("X-Output-Width", std::to_string(final_width));
      res.set_header("X-Output-Height", std::to_string(final_height));
      res.set_header("X-Duration-Ms", std::to_string(duration));
      res.set_header("Access-Control-Expose-Headers",
                     "X-Output-Width,X-Output-Height,X-Duration-Ms");

      if (tempUpscalerApp) {
        tempUpscalerApp.reset();
        QNN_INFO("Upscaler model released");
      }

    } catch (const std::invalid_argument &e) {
      tempUpscalerApp.reset();
      nlohmann::json err = {
          {"error",
           {{"message", "Invalid Arg: " + std::string(e.what())},
            {"type", "request_error"}}}};
      res.status = 400;
      res.set_content(err.dump(), "application/json");
    } catch (const std::exception &e) {
      tempUpscalerApp.reset();
      nlohmann::json err = {
          {"error",
           {{"message", "Server Err: " + std::string(e.what())},
            {"type", "server_error"}}}};
      res.status = 500;
      res.set_content(err.dump(), "application/json");
    }
  });
}

static void registerTokenizeEndpoint(httplib::Server &svr,
                                     TextEncoder *text_encoder) {
  svr.Post("/tokenize", [text_encoder](const httplib::Request &req,
                                       httplib::Response &res) {
    try {
      auto json = nlohmann::json::parse(req.body);
      std::string text = json.value("prompt", std::string());
      // Anima counts with the T5 tokenizer against the context length (512),
      // far longer than CLIP's 77.
      const int max_len = text_encoder->isAnima() ? anima_text_seq_len : 77;

      TokenizeInfo info = text_encoder->tokenizeInfo(text, max_len);

      nlohmann::json resp = {{"count", info.count},
                             {"max_length", max_len},
                             {"overflow_offset", info.overflow_offset}};
      res.status = 200;
      res.set_content(resp.dump(), "application/json");
    } catch (const std::exception &e) {
      nlohmann::json err = {
          {"error",
           {{"message", std::string(e.what())}, {"type", "tokenize_error"}}}};
      res.status = 400;
      res.set_content(err.dump(), "application/json");
    }
  });
}

int main(int argc, char **argv) {
  if (!qnn::log::initializeLogging()) {
    std::cerr << "ERROR: Init logging failed!\n";
    return EXIT_FAILURE;
  }
  ServerOptions opts = processCommandLine(argc, argv);

  if (opts.convert_mode) {
    runConvertMode(opts);
    return EXIT_SUCCESS;
  }

  std::unique_ptr<TextEncoder> text_encoder;
  std::unique_ptr<Pipeline> pipeline;
  MNN::Interpreter *safety_interpreter = nullptr;
  MNN::Session *safety_session = nullptr;

  if (opts.upscaler_mode) {
    QNN_INFO("Upscaler mode - skipping MNN and QNN model initialization");
    // QNN upscalers need --lib_dir; MNN-only upscaling runs without it.
    if (!opts.lib_dir.empty() && !qnn_runtime::init(opts.lib_dir))
      showHelpAndExit("Failed get QNN system func ptrs.");
  } else {
    text_encoder = std::make_unique<TextEncoder>(opts.isSdxl(), opts.isAnima());
    try {
      const std::filesystem::path mdir(opts.model_dir);
      text_encoder->loadTokenizer((mdir / "tokenizer.json").string());
      // Anima's LLM adapter is fed by a second (T5) tokenizer.
      if (opts.isAnima())
        text_encoder->loadT5Tokenizer((mdir / "tokenizer_t5.json").string());
    } catch (const std::exception &e) {
      std::cerr << "Failed load tokenizer: " << e.what() << std::endl;
      return EXIT_FAILURE;
    }

    pipeline = createPipeline(opts, *text_encoder);
    text_encoder->loadEmbeddingTables(opts.model_dir);

    // Textual-inversion embeddings live two levels above the model dir.
    std::filesystem::path embeddingsPath =
        std::filesystem::path(opts.model_dir).parent_path().parent_path() /
        "embeddings";
    if (std::filesystem::exists(embeddingsPath)) {
      try {
        text_encoder->loadTextualInversions(embeddingsPath.string());
        QNN_INFO("Loaded %zu embeddings (SDXL=%d) from %s",
                 text_encoder->embeddingCount(), opts.isSdxl() ? 1 : 0,
                 embeddingsPath.string().c_str());
      } catch (const std::exception &e) {
        QNN_WARN("Failed to load embeddings: %s", e.what());
      }
    } else {
      QNN_INFO("Embeddings directory not found: %s",
               embeddingsPath.string().c_str());
    }

    if (!opts.safety_checker_path.empty()) {
      safety_interpreter =
          createMnnInterpreterMmap(opts.safety_checker_path.c_str());
      if (!safety_interpreter)
        showHelpAndExit("Failed load Safety MNN: " + opts.safety_checker_path);
      MnnSessionOptions safety_opts;
      safety_opts.num_threads = 1;
      safety_session = createMnnSession(safety_interpreter, safety_opts);
      if (!safety_session) {
        QNN_ERROR("Failed create persistent MNN Safety session!");
      } else {
        QNN_INFO("Persistent MNN Safety session created.");
        auto input =
            safety_interpreter->getSessionInput(safety_session, nullptr);
        safety_interpreter->resizeTensor(input, {1, 224, 224, 3});
        safety_interpreter->resizeSession(safety_session);
        safety_interpreter->releaseModel();
      }
      pipeline->setSafetyChecker(safety_interpreter, safety_session,
                                 opts.nsfw_threshold);
    }

    if (!opts.isMnn()) {
      if (opts.lib_dir.empty()) showHelpAndExit("Missing --lib_dir for QNN");
      if (!qnn_runtime::init(opts.lib_dir))
        showHelpAndExit("Failed get QNN system func ptrs.");
    }

    if (!pipeline->initialize()) {
      std::cerr << "ERROR: Pipeline initialization failed!\n";
      return EXIT_FAILURE;
    }
  }

  // --- HTTP Server ---
  httplib::Server svr;
  svr.set_default_headers({
      {"Access-Control-Allow-Origin", "*"},
      {"Access-Control-Allow-Methods", "GET, POST, OPTIONS"},
      {"Access-Control-Allow-Headers", "Content-Type, Authorization"},
      {"Access-Control-Max-Age", "86400"},
  });
  svr.Options(R"(.*)", [](const httplib::Request &, httplib::Response &res) {
    res.status = 204;
  });
  svr.Get("/health", [](const httplib::Request &, httplib::Response &res) {
    res.status = 200;
  });

  if (pipeline) registerGenerateEndpoint(svr, pipeline.get());
  if (pipeline) registerOpenAiImageEndpoints(svr, pipeline.get(), opts);
  registerUpscaleEndpoint(svr);
  if (text_encoder) registerTokenizeEndpoint(svr, text_encoder.get());

  std::cout << "Server listening on " << opts.listen_address << ":" << opts.port
            << std::endl;
  svr.listen(opts.listen_address.c_str(), opts.port);

  // --- Cleanup ---
  pipeline.reset();
  if (safety_session) safety_interpreter->releaseSession(safety_session);
  delete safety_interpreter;

  return EXIT_SUCCESS;
}
