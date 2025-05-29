#include <chrono>
#include <functional>
#include <iostream>
#include <memory>
#include <numeric>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

#include "Config.hpp"
#include "DPMSolverMultistepScheduler.hpp"
#include "QnnModel.hpp"
#include "SDUtils.hpp"

// QNN Headers
#include "BuildId.hpp"
#include "DynamicLoadUtil.hpp"
#include "Logger.hpp"
#include "PAL/DynamicLoading.hpp"
#include "PAL/GetOpt.hpp"
#include "QnnSampleAppUtils.hpp"

// External Libraries
#include "httplib.h"
#include "json.hpp"
#include "tokenizers_cpp.h"

// MNN
#include <MNN/MNNDefine.h>

#include <MNN/Interpreter.hpp>

// Xtensor
#include <xtensor/xadapt.hpp>
#include <xtensor/xarray.hpp>
#include <xtensor/xbuilder.hpp>
#include <xtensor/xeval.hpp>
#include <xtensor/xindex_view.hpp>
#include <xtensor/xio.hpp>
#include <xtensor/xmanipulation.hpp>
#include <xtensor/xmath.hpp>
#include <xtensor/xoperation.hpp>
#include <xtensor/xrandom.hpp>
#include <xtensor/xview.hpp>

int port = 8081;
std::string listen_address = "127.0.0.1";
bool ponyv55 = false;
bool use_mnn = false;
bool use_safety_checker = false;
bool use_mnn_clip = false;
float nsfw_threshold = 0.5f;
std::string clipPath, unetPath, vaeDecoderPath, vaeEncoderPath,
    safetyCheckerPath, tokenizerPath;
std::shared_ptr<tokenizers::Tokenizer> tokenizer;
std::unique_ptr<QnnModel> clipApp = nullptr;
std::unique_ptr<QnnModel> unetApp = nullptr;
std::unique_ptr<QnnModel> vaeDecoderApp = nullptr;
std::unique_ptr<QnnModel> vaeEncoderApp = nullptr;
MNN::Interpreter *clipInterpreter = nullptr;
MNN::Interpreter *unetInterpreter = nullptr;
MNN::Interpreter *vaeDecoderInterpreter = nullptr;
MNN::Interpreter *vaeEncoderInterpreter = nullptr;
MNN::Interpreter *safetyCheckerInterpreter = nullptr;

// MNN Session Pointers
MNN::Session *clipSession = nullptr;
MNN::Session *unetSession = nullptr;
MNN::Session *vaeDecoderSession = nullptr;
MNN::Session *vaeEncoderSession = nullptr;
MNN::Session *safetyCheckerSession = nullptr;

std::string prompt;
std::string negative_prompt;
int steps;
float cfg;
unsigned seed;
std::vector<float> img_data;
std::vector<float> mask_data;
std::vector<float> mask_data_full;
float denoise_strength;
bool request_img2img;
bool request_has_mask;

namespace qnn {
namespace tools {
namespace sample_app {

// QnnModel Initialization
template <typename AppType>
int initializeQnnApp(const std::string &modelName,
                     std::unique_ptr<AppType> &app) {
  if (!app) return EXIT_FAILURE;
  QNN_INFO("Initializing QNN App from Cache: %s", modelName.c_str());

  if (StatusCode::SUCCESS != app->initialize())
    return app->reportError(modelName + " Init failure");
  if (StatusCode::SUCCESS != app->initializeBackend())
    return app->reportError(modelName + " Backend Init failure");
  auto devPropStat = app->isDevicePropertySupported();
  if (StatusCode::FAILURE != devPropStat) {
    if (StatusCode::SUCCESS != app->createDevice())
      return app->reportError(modelName + " Device Creation failure");
  }
  if (StatusCode::SUCCESS != app->initializeProfiling())
    return app->reportError(modelName + " Profiling Init failure");
  if (StatusCode::SUCCESS != app->registerOpPackages())
    return app->reportError(modelName + " Register Op Packages failure");
  if (StatusCode::SUCCESS != app->createFromBinary())
    return app->reportError(modelName + " Create From Binary failure");

  if (StatusCode::SUCCESS != app->enablePerformaceMode())
    return app->reportError(modelName + " Enable Performance Mode failure");
  QNN_INFO("QNN App Initialized from Cache: %s", modelName.c_str());
  return EXIT_SUCCESS;
}

void showHelp() {}

void showHelpAndExit(std::string &&error) {
  std::cerr << "ERROR: " << error << "\n";
  showHelp();
  std::exit(EXIT_FAILURE);
}

// Command line processing
void processCommandLine(int argc, char **argv) {
  enum OPTIONS {
    OPT_HELP = 0,
    OPT_CLIP = 21,
    OPT_UNET = 22,
    OPT_VAE_DECODER = 23,
    OPT_TEXT_EMBEDDING_SIZE = 24,
    OPT_USE_MNN = 25,
    OPT_PONYV55 = 26,
    OPT_SAFETY_CHECKER = 27,
    OPT_USE_MNN_CLIP = 28,
    OPT_VAE_ENCODER_ARG = 29,
    OPT_BACKEND = 3,
    OPT_LOG_LEVEL = 10,
    OPT_VERSION = 13,
    OPT_SYSTEM_LIBRARY = 14,
    OPT_PORT = 15,
    OPT_TOKENIZER = 16
  };
  static struct pal::Option s_longOptions[] = {
      {"help", pal::no_argument, NULL, OPT_HELP},
      {"port", pal::required_argument, NULL, OPT_PORT},
      {"text_embedding_size", pal::required_argument, NULL,
       OPT_TEXT_EMBEDDING_SIZE},
      {"cpu", pal::no_argument, NULL, OPT_USE_MNN},
      {"ponyv55", pal::no_argument, NULL, OPT_PONYV55},
      {"safety_checker", pal::required_argument, NULL, OPT_SAFETY_CHECKER},
      {"use_cpu_clip", pal::no_argument, NULL, OPT_USE_MNN_CLIP},
      {"vae_encoder", pal::required_argument, NULL, OPT_VAE_ENCODER_ARG},
      {"tokenizer", pal::required_argument, NULL, OPT_TOKENIZER},
      {"clip", pal::required_argument, NULL, OPT_CLIP},
      {"unet", pal::required_argument, NULL, OPT_UNET},
      {"vae_decoder", pal::required_argument, NULL, OPT_VAE_DECODER},
      {"backend", pal::required_argument, NULL, OPT_BACKEND},
      {"log_level", pal::required_argument, NULL, OPT_LOG_LEVEL},
      {"system_library", pal::required_argument, NULL, OPT_SYSTEM_LIBRARY},
      {"version", pal::no_argument, NULL, OPT_VERSION},
      {NULL, 0, NULL, 0}};
  std::string backendPathCmd, systemLibraryPathCmd;
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
      case OPT_CLIP:
        clipPath = pal::g_optArg;
        break;
      case OPT_UNET:
        unetPath = pal::g_optArg;
        break;
      case OPT_VAE_DECODER:
        vaeDecoderPath = pal::g_optArg;
        break;
      case OPT_BACKEND:
        backendPathCmd = pal::g_optArg;
        break;
      case OPT_TEXT_EMBEDDING_SIZE:
        text_embedding_size = std::stoi(pal::g_optArg);
        break;
      case OPT_USE_MNN:
        use_mnn = true;
        break;
      case OPT_PONYV55:
        ponyv55 = true;
        break;
      case OPT_SAFETY_CHECKER:
        use_safety_checker = true;
        safetyCheckerPath = pal::g_optArg;
        break;
      case OPT_USE_MNN_CLIP:
        use_mnn_clip = true;
        break;
      case OPT_VAE_ENCODER_ARG:
        vaeEncoderPath = pal::g_optArg;
        break;
      case OPT_LOG_LEVEL:
        logLevel = sample_app::parseLogLevel(pal::g_optArg);
        if (logLevel != QNN_LOG_LEVEL_MAX) {
          if (!log::setLogLevel(logLevel))
            showHelpAndExit("Unable to set log level.");
        }
        break;
      case OPT_SYSTEM_LIBRARY:
        systemLibraryPathCmd = pal::g_optArg;
        break;
      case OPT_PORT:
        port = std::stoi(pal::g_optArg);
        break;
      case OPT_TOKENIZER:
        tokenizerPath = pal::g_optArg;
        break;
      default:
        showHelpAndExit("Invalid argument passed.");
    }
  }
  if (clipPath.empty() || unetPath.empty() || vaeDecoderPath.empty())
    showHelpAndExit("Missing required model paths");
  if (tokenizerPath.empty()) showHelpAndExit("Missing --tokenizer");
  if (use_safety_checker && safetyCheckerPath.empty())
    showHelpAndExit("Missing safety checker path");
  if (vaeEncoderPath.empty())
    QNN_WARN("VAE Encoder path missing. img2img disabled unless --cpu");

  if (use_safety_checker) {
    safetyCheckerInterpreter =
        MNN::Interpreter::createFromFile(safetyCheckerPath.c_str());
    if (!safetyCheckerInterpreter)
      showHelpAndExit("Failed load Safety MNN: " + safetyCheckerPath);
  }

  if (use_mnn_clip) {
    clipInterpreter = MNN::Interpreter::createFromFile(clipPath.c_str());
    if (!clipInterpreter) showHelpAndExit("Failed load CLIP MNN: " + clipPath);
  }

  if (use_mnn) {
    return;
  }

  if (systemLibraryPathCmd.empty())
    showHelpAndExit("Requires --system_library for QNN");
  if (backendPathCmd.empty()) showHelpAndExit("Requires --backend for QNN");

  QnnFunctionPointers qnnSystemFuncs;
  dynamicloadutil::StatusCode sysStatus =
      dynamicloadutil::getQnnSystemFunctionPointers(systemLibraryPathCmd,
                                                    &qnnSystemFuncs);
  if (sysStatus != dynamicloadutil::StatusCode::SUCCESS)
    showHelpAndExit("Failed get QNN system func ptrs.");

  auto createQnnModel =
      [&](std::string modelPath,
          const std::string &modelName) -> std::unique_ptr<QnnModel> {
    QnnFunctionPointers funcs = qnnSystemFuncs;
    void *backendHandle = nullptr;
    void *modelHandle = nullptr;
    dynamicloadutil::StatusCode drvStatus =
        dynamicloadutil::getQnnFunctionPointers(backendPathCmd, modelPath,
                                                &funcs, &backendHandle, false,
                                                &modelHandle);
    if (drvStatus != dynamicloadutil::StatusCode::SUCCESS) {
      QNN_ERROR("Failed get QNN func ptrs for %s.", modelName.c_str());
      return nullptr;
    }
    std::string inputListPaths, opPackagePaths, outputPath, saveBinaryName;
    bool debug = false;
    bool dumpOutputs = false;
    iotensor::OutputDataType outputDataType =
        iotensor::OutputDataType::FLOAT_ONLY;
    iotensor::InputDataType inputDataType = iotensor::InputDataType::FLOAT;
    sample_app::ProfilingLevel profilingLevel = ProfilingLevel::OFF;
    return std::make_unique<QnnModel>(
        funcs, inputListPaths, opPackagePaths, backendHandle, outputPath, debug,
        outputDataType, inputDataType, profilingLevel, dumpOutputs, modelPath,
        saveBinaryName);
  };

  if (!use_mnn_clip) {
    clipApp = createQnnModel(clipPath, "clip");
    if (!clipApp) showHelpAndExit("Failed create QNN CLIP model.");
  }

  unetApp = createQnnModel(unetPath, "unet");
  if (!unetApp) showHelpAndExit("Failed create QNN UNET model.");

  vaeDecoderApp = createQnnModel(vaeDecoderPath, "vae_decoder");
  if (!vaeDecoderApp) showHelpAndExit("Failed create QNN VAE Decoder model.");

  if (!vaeEncoderPath.empty()) {
    vaeEncoderApp = createQnnModel(vaeEncoderPath, "vae_encoder");
    if (!vaeEncoderApp) QNN_WARN("Failed create QNN VAE Enc model.");
  } else
    QNN_WARN("VAE Enc QNN path missing.");
}

}  // namespace sample_app
}  // namespace tools
}  // namespace qnn

// --- Text Processing ---
std::vector<int> EncodeText(const std::string &text, int bos, int pad,
                            int max_length) {
  int sd21_pad = 0;
  std::vector<int> ids = tokenizer->Encode(text);
  ids.insert(ids.begin(), bos);
  if (ids.size() > max_length - 1) ids.resize(max_length - 1);
  int pad_len = max_length - ids.size();
  ids.push_back(pad);
  for (int i = 0; i < pad_len - 1; i++)
    ids.push_back((text_embedding_size == 1024) ? sd21_pad : pad);
  return ids;
}

std::vector<int> processPrompt(const std::string &prompt_in,
                               const std::string &neg = "", int max_len = 77) {
  std::vector<int> p_ids = EncodeText(prompt_in, 49406, 49407, max_len);
  std::vector<int> n_ids = EncodeText(neg, 49406, 49407, max_len);
  std::vector<int> ids;
  ids.reserve(2 * max_len);
  ids.insert(ids.end(), n_ids.begin(), n_ids.end());
  ids.insert(ids.end(), p_ids.begin(), p_ids.end());
  return ids;
}

// --- Image Generation ---
GenerationResult generateImage(
    std::function<void(int step, int total_steps)> progress_callback) {
  using namespace qnn::tools::sample_app;
  if (prompt.empty()) throw std::invalid_argument("Global prompt empty");
  if (use_safety_checker && !safetyCheckerInterpreter)
    throw std::runtime_error("SafetyChecker missing");
  if (!use_mnn) {
    if (!use_mnn_clip && !clipApp) throw std::runtime_error("QNN CLIP missing");
    if (use_mnn_clip && !clipInterpreter)
      throw std::runtime_error("MNN CLIP missing(hybrid)");
    if (!unetApp) throw std::runtime_error("QNN UNET missing");
    if (!vaeDecoderApp) throw std::runtime_error("QNN VAE Dec missing");
    if (request_img2img && !vaeEncoderApp)
      throw std::runtime_error("QNN VAE Enc missing");
  }
  if (request_img2img && img_data.size() != 3 * output_size * output_size)
    throw std::invalid_argument("Invalid global img_data");
  if (request_has_mask &&
      (mask_data.size() != 4 * sample_size * sample_size ||
       mask_data_full.size() != 3 * output_size * output_size))
    throw std::invalid_argument("Invalid global mask_data*");

  try {
    auto start_time = std::chrono::high_resolution_clock::now();
    int first_step_time_ms = 0;
    int total_run_steps = steps + (request_img2img ? 1 : 0) + 2;
    int current_step = 0;
    const int batch_size = 2;

    // --- CLIP ---
    std::vector<int> clip_input_ids =
        processPrompt(prompt, negative_prompt, 77);
    std::vector<float> text_embedding_float(batch_size * 77 *
                                            text_embedding_size);
    auto clip_start = std::chrono::high_resolution_clock::now();
    int32_t *input_ids_ptr = clip_input_ids.data();
    float *embed_ptr = text_embedding_float.data();

    if (use_mnn || use_mnn_clip) {
      MNN::Interpreter *currentClipInterpreter = nullptr;
      MNN::Session *currentClipSession = nullptr;
      bool dynamicCreated = false;

      if (use_mnn_clip) {
        currentClipInterpreter = clipInterpreter;
        currentClipSession = clipSession;
        if (!currentClipInterpreter)
          throw std::runtime_error(
              "Global clipInterpreter (hybrid) not initialized!");
      } else {
        currentClipInterpreter =
            MNN::Interpreter::createFromFile(clipPath.c_str());
        if (!currentClipInterpreter)
          throw std::runtime_error(
              "Failed to create temporary MNN CLIP interpreter!");
        dynamicCreated = true;
      }

      bool sessionCreated = false;
      if (!currentClipSession) {
        MNN::ScheduleConfig cfg_clip;
        cfg_clip.type = MNN_FORWARD_CPU;
        cfg_clip.numThread = 4;
        MNN::BackendConfig bkCfg_clip;
        bkCfg_clip.memory = MNN::BackendConfig::Memory_Low;
        bkCfg_clip.power = MNN::BackendConfig::Power_High;
        cfg_clip.backendConfig = &bkCfg_clip;
        currentClipSession = currentClipInterpreter->createSession(cfg_clip);
        if (!currentClipSession)
          throw std::runtime_error(
              "Failed to create temporary MNN CLIP session!");
        sessionCreated = true;
      }

      auto input = currentClipInterpreter->getSessionInput(currentClipSession,
                                                           "input_ids");
      currentClipInterpreter->resizeTensor(input, {1, 77});
      currentClipInterpreter->resizeSession(currentClipSession);

      if (dynamicCreated) currentClipInterpreter->releaseModel();

      memcpy(input->host<int>(), input_ids_ptr, 77 * sizeof(int32_t));
      currentClipInterpreter->runSession(currentClipSession);
      auto out = currentClipInterpreter->getSessionOutput(currentClipSession,
                                                          "last_hidden_state");
      memcpy(embed_ptr, out->host<float>(),
             77 * text_embedding_size * sizeof(float));

      memcpy(input->host<int>(), input_ids_ptr + 77, 77 * sizeof(int32_t));
      currentClipInterpreter->runSession(currentClipSession);
      out = currentClipInterpreter->getSessionOutput(currentClipSession,
                                                     "last_hidden_state");
      memcpy(embed_ptr + 77 * text_embedding_size, out->host<float>(),
             77 * text_embedding_size * sizeof(float));

      if (sessionCreated)
        currentClipInterpreter->releaseSession(currentClipSession);
      if (dynamicCreated) delete currentClipInterpreter;
    } else {
      if (!clipApp) throw std::runtime_error("Global clipApp not initialized!");
      if (StatusCode::SUCCESS !=
          clipApp->executeClipGraphs(input_ids_ptr, embed_ptr))
        throw std::runtime_error("QNN CLIP exec failed (neg)");
      if (StatusCode::SUCCESS !=
          clipApp->executeClipGraphs(input_ids_ptr + 77,
                                     embed_ptr + 77 * text_embedding_size))
        throw std::runtime_error("QNN CLIP exec failed (pos)");
    }

    auto clip_end = std::chrono::high_resolution_clock::now();
    std::cout << "CLIP dur: "
              << std::chrono::duration_cast<std::chrono::milliseconds>(
                     clip_end - clip_start)
                     .count()
              << "ms\n";
    current_step++;
    progress_callback(current_step, total_run_steps);

    // --- Scheduler & Latents ---
    DPMSolverMultistepScheduler scheduler(
        1000, 0.00085f, 0.012f, "scaled_linear", 2, "epsilon", "leading");
    if (ponyv55) scheduler.set_prediction_type("v_prediction");
    scheduler.set_timesteps(steps);
    xt::xarray<float> timesteps = scheduler.get_timesteps();
    std::vector<int> shape = {1, 4, sample_size, sample_size};
    std::vector<int> shape_batch2 = {batch_size, 4, sample_size, sample_size};
    xt::random::seed(seed);
    xt::xarray<float> latents = xt::random::randn<float>(shape);
    xt::xarray<float> latents_noise = xt::random::randn<float>(shape);
    xt::xarray<float> original_latents, original_image, mask, mask_full;
    int start_step = 0;

    // --- Img2Img / VAE Encode ---
    if (request_img2img) {
      auto vae_enc_start = std::chrono::high_resolution_clock::now();
      std::vector<int> img_shape = {1, 3, output_size, output_size};
      original_image = xt::adapt(img_data, img_shape);
      std::vector<float> vae_enc_mean(1 * 4 * sample_size * sample_size);
      std::vector<float> vae_enc_std(1 * 4 * sample_size * sample_size);

      if (use_mnn) {
        MNN::Interpreter *currentVaeEncoderInterpreter =
            MNN::Interpreter::createFromFile(vaeEncoderPath.c_str());

        if (!currentVaeEncoderInterpreter)
          throw std::runtime_error(
              "Failed to create temporary MNN VAE Encoder interpreter!");

        MNN::ScheduleConfig cfg_vae;
        cfg_vae.type = MNN_FORWARD_CPU;
        cfg_vae.numThread = 4;
        MNN::BackendConfig bkCfg_vae;
        bkCfg_vae.memory = MNN::BackendConfig::Memory_Low;
        bkCfg_vae.power = MNN::BackendConfig::Power_High;
        cfg_vae.backendConfig = &bkCfg_vae;

        MNN::Session *currentVaeEncSession =
            currentVaeEncoderInterpreter->createSession(cfg_vae);

        if (!currentVaeEncSession)
          throw std::runtime_error("Failed create temp MNN VAE Enc session!");

        auto input = currentVaeEncoderInterpreter->getSessionInput(
            currentVaeEncSession, "input");

        currentVaeEncoderInterpreter->resizeTensor(
            input, {1, 3, output_size, output_size});
        currentVaeEncoderInterpreter->resizeSession(currentVaeEncSession);

        currentVaeEncoderInterpreter->releaseModel();

        memcpy(input->host<float>(), img_data.data(),
               img_data.size() * sizeof(float));

        currentVaeEncoderInterpreter->runSession(currentVaeEncSession);

        auto mean_t = currentVaeEncoderInterpreter->getSessionOutput(
            currentVaeEncSession, "mean");
        auto std_t = currentVaeEncoderInterpreter->getSessionOutput(
            currentVaeEncSession, "std");

        memcpy(vae_enc_mean.data(), mean_t->host<float>(),
               vae_enc_mean.size() * sizeof(float));
        memcpy(vae_enc_std.data(), std_t->host<float>(),
               vae_enc_std.size() * sizeof(float));

        currentVaeEncoderInterpreter->releaseSession(currentVaeEncSession);
        delete currentVaeEncoderInterpreter;
      } else {
        if (!vaeEncoderApp)
          throw std::runtime_error("Global vaeEncoderApp not init!");
        if (StatusCode::SUCCESS !=
            vaeEncoderApp->executeVaeEncoderGraphs(
                img_data.data(), vae_enc_mean.data(), vae_enc_std.data()))
          throw std::runtime_error("QNN VAE enc exec failed");
      }

      auto vae_enc_end = std::chrono::high_resolution_clock::now();
      std::cout << "VAE Enc dur: "
                << std::chrono::duration_cast<std::chrono::milliseconds>(
                       vae_enc_end - vae_enc_start)
                       .count()
                << "ms\n";

      auto mean = xt::adapt(vae_enc_mean, shape);
      auto std_dev = xt::adapt(vae_enc_std, shape);
      xt::xarray<float> noise_0 = xt::random::randn<float>(shape);
      xt::xarray<float> img_lat = xt::eval(mean + std_dev * noise_0);
      xt::xarray<float> img_lat_scaled = xt::eval(0.18215 * img_lat);
      start_step = steps * (1.0f - denoise_strength);
      total_run_steps -= start_step;
      scheduler.set_begin_index(start_step);
      xt::xarray<int> t = {(int)(timesteps(start_step))};
      latents = scheduler.add_noise(img_lat_scaled, latents_noise, t);

      if (request_has_mask) {
        original_latents = img_lat_scaled;
        mask = xt::adapt(mask_data, {1, 4, sample_size, sample_size});
        mask_full = xt::adapt(mask_data_full, {1, 3, output_size, output_size});
      }

      current_step++;
      progress_callback(current_step, total_run_steps);
    }

    // --- UNET Denoising Loop ---
    int single_latent_size = 1 * 4 * sample_size * sample_size;

    MNN::Interpreter *currentUnetInterpreter = nullptr;
    MNN::Session *currentUnetSession = nullptr;

    if (use_mnn) {
      currentUnetInterpreter =
          MNN::Interpreter::createFromFile(unetPath.c_str());
      if (!currentUnetInterpreter)
        throw std::runtime_error(
            "Failed to create temporary MNN UNET interpreter!");

      MNN::ScheduleConfig cfg_unet;
      cfg_unet.type = MNN_FORWARD_CPU;
      cfg_unet.numThread = 4;
      MNN::BackendConfig bkCfg_unet;
      bkCfg_unet.memory = MNN::BackendConfig::Memory_Low;
      bkCfg_unet.power = MNN::BackendConfig::Power_High;
      cfg_unet.backendConfig = &bkCfg_unet;

      currentUnetSession = currentUnetInterpreter->createSession(cfg_unet);
      if (!currentUnetSession)
        throw std::runtime_error(
            "Failed to create temporary MNN UNET session!");

      auto samp =
          currentUnetInterpreter->getSessionInput(currentUnetSession, "sample");
      auto ts = currentUnetInterpreter->getSessionInput(currentUnetSession,
                                                        "timestep");
      auto enc = currentUnetInterpreter->getSessionInput(
          currentUnetSession, "encoder_hidden_states");

      currentUnetInterpreter->resizeTensor(samp, shape);
      currentUnetInterpreter->resizeTensor(ts, {1});
      currentUnetInterpreter->resizeTensor(enc, {1, 77, text_embedding_size});
      currentUnetInterpreter->resizeSession(currentUnetSession);

      currentUnetInterpreter->releaseModel();
    }

    for (int i = start_step; i < timesteps.size(); ++i) {
      auto step_start_time = std::chrono::high_resolution_clock::now();
      std::vector<float> latents_in_vec;
      latents_in_vec.reserve(batch_size * single_latent_size);
      latents_in_vec.insert(latents_in_vec.end(), latents.begin(),
                            latents.end());
      latents_in_vec.insert(latents_in_vec.end(), latents.begin(),
                            latents.end());
      float current_ts = timesteps(i);
      std::vector<float> unet_out_latents(batch_size * single_latent_size);

      if (use_mnn) {
        auto samp = currentUnetInterpreter->getSessionInput(currentUnetSession,
                                                            "sample");
        auto ts = currentUnetInterpreter->getSessionInput(currentUnetSession,
                                                          "timestep");
        auto enc = currentUnetInterpreter->getSessionInput(
            currentUnetSession, "encoder_hidden_states");

        int current_ts_int = (int)(current_ts);

        memcpy(samp->host<float>(), latents_in_vec.data(),
               latents_in_vec.size() / 2 * sizeof(float));
        memcpy(ts->host<int>(), &current_ts_int, sizeof(int));
        memcpy(enc->host<float>(), text_embedding_float.data(),
               text_embedding_float.size() / 2 * sizeof(float));

        currentUnetInterpreter->runSession(currentUnetSession);

        auto output = currentUnetInterpreter->getSessionOutput(
            currentUnetSession, "out_sample");
        memcpy(unet_out_latents.data(), output->host<float>(),
               unet_out_latents.size() / 2 * sizeof(float));

        memcpy(samp->host<float>(),
               latents_in_vec.data() + latents_in_vec.size() / 2,
               latents_in_vec.size() / 2 * sizeof(float));
        memcpy(ts->host<int>(), &current_ts_int, sizeof(int));
        memcpy(enc->host<float>(),
               text_embedding_float.data() + text_embedding_float.size() / 2,
               text_embedding_float.size() / 2 * sizeof(float));

        currentUnetInterpreter->runSession(currentUnetSession);

        output = currentUnetInterpreter->getSessionOutput(currentUnetSession,
                                                          "out_sample");
        memcpy(unet_out_latents.data() + unet_out_latents.size() / 2,
               output->host<float>(),
               unet_out_latents.size() / 2 * sizeof(float));
      } else {
        if (!unetApp)
          throw std::runtime_error("Global unetApp not initialized!");

        float *latents_in_ptr = latents_in_vec.data();
        float *embed_ptr = text_embedding_float.data();
        float *latents_out_ptr = unet_out_latents.data();

        if (StatusCode::SUCCESS !=
            unetApp->executeUnetGraphs(latents_in_ptr,
                                       static_cast<int>(current_ts), embed_ptr,
                                       latents_out_ptr))
          throw std::runtime_error("QNN UNET exec failed (uncond)");

        if (StatusCode::SUCCESS !=
            unetApp->executeUnetGraphs(latents_in_ptr + single_latent_size,
                                       static_cast<int>(current_ts),
                                       embed_ptr + 77 * text_embedding_size,
                                       latents_out_ptr + single_latent_size))
          throw std::runtime_error("QNN UNET exec failed (cond)");
      }

      auto step_end_time = std::chrono::high_resolution_clock::now();
      auto step_dur = std::chrono::duration_cast<std::chrono::milliseconds>(
          step_end_time - step_start_time);

      if (i == start_step) first_step_time_ms = step_dur.count();
      std::cout << "UNET step " << i << " dur: " << step_dur.count() << "ms\n";

      xt::xarray<float> noise_pred_batch =
          xt::adapt(unet_out_latents, shape_batch2);
      xt::xarray<float> uncond = xt::view(noise_pred_batch, 0);
      xt::xarray<float> txt = xt::view(noise_pred_batch, 1);
      xt::xarray<float> noise_pred = uncond + cfg * (txt - uncond);
      noise_pred = xt::eval(noise_pred);
      latents = scheduler.step(noise_pred, timesteps(i), latents).prev_sample;

      if (request_has_mask) {
        xt::xarray<int> t_xt = {(int)(timesteps(i))};
        xt::xarray<float> orig_noised =
            scheduler.add_noise(original_latents, latents_noise, t_xt);
        latents = xt::eval(orig_noised * (1.0f - mask) + latents * mask);
      }

      current_step++;
      progress_callback(current_step, total_run_steps);
    }

    if (use_mnn) {
      if (currentUnetSession)
        currentUnetInterpreter->releaseSession(currentUnetSession);
      if (currentUnetInterpreter) delete currentUnetInterpreter;
    }

    // --- VAE Decode ---
    auto vae_dec_start = std::chrono::high_resolution_clock::now();
    latents = xt::eval((1.0 / 0.18215) * latents);
    std::vector<float> vae_dec_in_vec(latents.begin(), latents.end());
    std::vector<float> vae_dec_out_pixels(1 * 3 * output_size * output_size);

    if (use_mnn) {
      MNN::Interpreter *currentVaeDecoderInterpreter =
          MNN::Interpreter::createFromFile(vaeDecoderPath.c_str());

      if (!currentVaeDecoderInterpreter)
        throw std::runtime_error(
            "Failed to create temporary MNN VAE Decoder interpreter!");

      MNN::ScheduleConfig cfg_vae;
      cfg_vae.type = MNN_FORWARD_CPU;
      cfg_vae.numThread = 4;
      MNN::BackendConfig bkCfg_vae;
      bkCfg_vae.memory = MNN::BackendConfig::Memory_Low;
      bkCfg_vae.power = MNN::BackendConfig::Power_High;
      cfg_vae.backendConfig = &bkCfg_vae;

      MNN::Session *currentVaeDecSession =
          currentVaeDecoderInterpreter->createSession(cfg_vae);

      if (!currentVaeDecSession)
        throw std::runtime_error("Failed create temp MNN VAE Dec session!");

      auto input = currentVaeDecoderInterpreter->getSessionInput(
          currentVaeDecSession, "latent_sample");

      currentVaeDecoderInterpreter->resizeTensor(
          input, {1, 4, sample_size, sample_size});
      currentVaeDecoderInterpreter->resizeSession(currentVaeDecSession);

      currentVaeDecoderInterpreter->releaseModel();

      memcpy(input->host<float>(), vae_dec_in_vec.data(),
             vae_dec_in_vec.size() * sizeof(float));

      currentVaeDecoderInterpreter->runSession(currentVaeDecSession);

      auto output = currentVaeDecoderInterpreter->getSessionOutput(
          currentVaeDecSession, "sample");

      memcpy(vae_dec_out_pixels.data(), output->host<float>(),
             vae_dec_out_pixels.size() * sizeof(float));

      currentVaeDecoderInterpreter->releaseSession(currentVaeDecSession);
      delete currentVaeDecoderInterpreter;
    } else {
      if (!vaeDecoderApp)
        throw std::runtime_error("Global vaeDecoderApp not init!");

      if (StatusCode::SUCCESS !=
          vaeDecoderApp->executeVaeDecoderGraphs(vae_dec_in_vec.data(),
                                                 vae_dec_out_pixels.data()))
        throw std::runtime_error("QNN VAE dec exec failed");
    }

    auto vae_dec_end = std::chrono::high_resolution_clock::now();
    std::cout << "VAE Dec dur: "
              << std::chrono::duration_cast<std::chrono::milliseconds>(
                     vae_dec_end - vae_dec_start)
                     .count()
              << "ms\n";

    // --- Post-process Image ---
    std::vector<int> pixel_shape = {1, 3, output_size, output_size};
    xt::xarray<float> pixels = xt::adapt(vae_dec_out_pixels, pixel_shape);
    if (request_has_mask)
      pixels =
          xt::eval(original_image * (1.0f - mask_full) + pixels * mask_full);
    auto img = xt::view(pixels, 0);
    auto transp = xt::transpose(img, {1, 2, 0});
    auto norm = xt::clip(((transp + 1.0) / 2.0) * 255.0, 0.0, 255.0);
    xt::xarray<uint8_t> u8_img = xt::cast<uint8_t>(norm);
    std::vector<uint8_t> out_data(u8_img.begin(), u8_img.end());

    // --- Safety Checker ---
    if (use_safety_checker) {
      auto safety_start = std::chrono::high_resolution_clock::now();
      float score = 0.0f;

      if (safety_check(out_data, output_size, output_size, score,
                       safetyCheckerInterpreter, safetyCheckerSession)) {
        std::cout << "NSFW Score: " << score << std::endl;
        if (score > nsfw_threshold) {
          QNN_WARN("NSFW detected (%.2f>%.2f).", score, nsfw_threshold);
          std::fill(out_data.begin(), out_data.end(), 255);
        }
      } else {
        QNN_WARN("Safety check failed.");
      }

      auto safety_end = std::chrono::high_resolution_clock::now();
      std::cout << "Safety check dur: "
                << std::chrono::duration_cast<std::chrono::milliseconds>(
                       safety_end - safety_start)
                       .count()
                << "ms\n";
    }

    current_step++;
    progress_callback(current_step, total_run_steps);
    auto end_time = std::chrono::high_resolution_clock::now();
    auto total_time = std::chrono::duration_cast<std::chrono::milliseconds>(
                          end_time - start_time)
                          .count();

    return GenerationResult{out_data,
                            output_size,
                            output_size,
                            3,
                            static_cast<int>(total_time),
                            first_step_time_ms};
  } catch (const std::exception &e) {
    QNN_ERROR("Image generation error: %s", e.what());
    throw;
  }
}

// --- Main Function ---
int main(int argc, char **argv) {
  using namespace qnn::tools;
  if (!qnn::log::initializeLogging()) {
    std::cerr << "ERROR: Init logging failed!\n";
    return EXIT_FAILURE;
  }
  sample_app::processCommandLine(argc, argv);
  try {
    auto blob = LoadBytesFromFile(tokenizerPath);
    tokenizer = tokenizers::Tokenizer::FromBlobJSON(blob);
    if (!tokenizer) throw std::runtime_error("Tokenizer creation failed.");
  } catch (const std::exception &e) {
    std::cerr << "Failed load tokenizer: " << e.what() << std::endl;
    return EXIT_FAILURE;
  }

  MNN::ScheduleConfig cfg_common;
  cfg_common.type = MNN_FORWARD_CPU;
  cfg_common.numThread = 1;
  MNN::BackendConfig bkCfg_common;
  bkCfg_common.memory = MNN::BackendConfig::Memory_Low;
  bkCfg_common.power = MNN::BackendConfig::Power_High;
  cfg_common.backendConfig = &bkCfg_common;
  MNN::ScheduleConfig cfg_mnn_clip = cfg_common;
  cfg_mnn_clip.numThread = 4;

  if (use_mnn_clip && clipInterpreter) {
    clipSession = clipInterpreter->createSession(cfg_mnn_clip);
    if (!clipSession)
      QNN_ERROR("Failed create persistent MNN CLIP session (hybrid)!");
    else {
      QNN_INFO("Persistent MNN CLIP session (hybrid) created.");
      auto input = clipInterpreter->getSessionInput(clipSession, "input_ids");
      clipInterpreter->resizeTensor(input, {1, 77});
      clipInterpreter->resizeSession(clipSession);
      clipInterpreter->releaseModel();
    }
  }

  if (safetyCheckerInterpreter) {
    safetyCheckerSession = safetyCheckerInterpreter->createSession(cfg_common);
    if (!safetyCheckerSession)
      QNN_ERROR("Failed create persistent MNN Safety session!");
    else {
      QNN_INFO("Persistent MNN Safety session created.");
      auto input = safetyCheckerInterpreter->getSessionInput(
          safetyCheckerSession, nullptr);
      safetyCheckerInterpreter->resizeTensor(input, {1, 224, 224, 3});
      safetyCheckerInterpreter->resizeSession(safetyCheckerSession);
      safetyCheckerInterpreter->releaseModel();
    }
  }

  // --- Initialize QNN Models ---
  if (!use_mnn) {
    int status = EXIT_SUCCESS;
    if (!use_mnn_clip && clipApp) {
      status = sample_app::initializeQnnApp("CLIP", clipApp);
      if (status != EXIT_SUCCESS) return status;
    }
    if (unetApp) {
      status = sample_app::initializeQnnApp("UNET", unetApp);
      if (status != EXIT_SUCCESS) return status;
    }
    if (vaeDecoderApp) {
      status = sample_app::initializeQnnApp("VAEDecoder", vaeDecoderApp);
      if (status != EXIT_SUCCESS) return status;
    }
    if (vaeEncoderApp) {
      status = sample_app::initializeQnnApp("VAEEncoder", vaeEncoderApp);
      if (status != EXIT_SUCCESS) return status;
    }
  }

  // --- HTTP Server ---
  httplib::Server svr;
  svr.Get("/health", [](const httplib::Request &, httplib::Response &res) {
    res.status = 200;
  });
  svr.Post("/generate", [&](const httplib::Request &req,
                            httplib::Response &res) {
    try {
      auto json = nlohmann::json::parse(req.body);
      if (!json.contains("prompt"))
        throw std::invalid_argument("Missing 'prompt'");
      prompt = json["prompt"].get<std::string>();
      negative_prompt = json.value("negative_prompt", "");
      steps = json.value("steps", 20);
      cfg = json.value("cfg", 7.5f);
      seed = json.value(
          "seed",
          (unsigned)hashSeed(
              std::chrono::system_clock::now().time_since_epoch().count()));
      int req_size = json.value("size", 512);
      denoise_strength = json.value("denoise_strength", 0.6f);
      request_img2img = false;
      request_has_mask = false;
      img_data.clear();
      mask_data.clear();
      mask_data_full.clear();
      output_size = req_size;
      sample_size = req_size / 8;

      if (json.contains("image")) {
        request_img2img = true;
        std::string img_b64 = json["image"].get<std::string>();
        try {
          std::string dec_str = base64_decode(img_b64);
          std::vector<uint8_t> dec_buf(dec_str.begin(), dec_str.end());
          std::vector<uint8_t> dec_pix;
          decode_image(dec_buf, dec_pix, output_size);
          if (dec_pix.size() != 3 * output_size * output_size)
            throw std::runtime_error("Img size mismatch");
          std::vector<int> img_shape = {1, output_size, output_size, 3};
          xt::xarray<uint8_t> xt_u8 = xt::adapt(dec_pix, img_shape);
          xt::xarray<float> xt_f = xt::cast<float>(xt_u8);
          xt_f = xt::eval(xt_f / 127.5f - 1.0f);
          xt_f = xt::transpose(xt_f, {0, 3, 1, 2});
          img_data.assign(xt_f.begin(), xt_f.end());
          if (json.contains("mask")) {
            request_has_mask = true;
            std::string mask_b64 = json["mask"].get<std::string>();
            std::string dec_mask_str = base64_decode(mask_b64);
            std::vector<uint8_t> dec_mask_buf(dec_mask_str.begin(),
                                              dec_mask_str.end());
            std::vector<uint8_t> mask_pix_lat_rgb, mask_pix_full_rgb;
            decode_image(dec_mask_buf, mask_pix_lat_rgb, sample_size);
            decode_image(dec_mask_buf, mask_pix_full_rgb, output_size);
            if (mask_pix_lat_rgb.empty() || mask_pix_full_rgb.empty())
              throw std::runtime_error("Mask decode empty");
            std::vector<int> mlat_shape = {sample_size, sample_size, 3};
            xt::xarray<uint8_t> xmlat_u8 =
                xt::adapt(mask_pix_lat_rgb, mlat_shape);
            xt::xarray<float> xmlat_f =
                xt::mean(xt::cast<float>(xmlat_u8), {2});
            xmlat_f = xt::eval(xmlat_f / 255.0f);
            xmlat_f =
                xt::reshape_view(xmlat_f, {1, 1, sample_size, sample_size});
            xt::xarray<float> xmlat_f_4 = xt::concatenate(
                xt::xtuple(xmlat_f, xmlat_f, xmlat_f, xmlat_f), 1);
            mask_data.assign(xmlat_f_4.begin(), xmlat_f_4.end());

            gaussianBlur(mask_pix_full_rgb, output_size, output_size,
                         sample_size / 8);
            std::vector<int> mfull_shape = {output_size, output_size, 3};
            xt::xarray<uint8_t> xmfull_u8 =
                xt::adapt(mask_pix_full_rgb, mfull_shape);
            xt::xarray<float> xmfull_f =
                xt::mean(xt::cast<float>(xmfull_u8), {2});
            xmfull_f = xt::eval(xmfull_f / 255.0f);
            xmfull_f =
                xt::reshape_view(xmfull_f, {1, 1, output_size, output_size});
            xt::xarray<float> xmfull_f_3 =
                xt::concatenate(xt::xtuple(xmfull_f, xmfull_f, xmfull_f), 1);
            mask_data_full.assign(xmfull_f_3.begin(), xmfull_f_3.end());
          }
        } catch (const std::exception &e) {
          throw std::invalid_argument("Err proc img/mask: " +
                                      std::string(e.what()));
        }
      }
      std::cout << "Req Rcvd (globals): P:" << prompt
                << " NP:" << negative_prompt << " S:" << steps << " CFG:" << cfg
                << " Seed:" << seed << " Size:" << output_size
                << " Img2Img:" << request_img2img
                << " Mask:" << request_has_mask
                << " Denoise:" << denoise_strength << std::endl;
      res.set_header("Content-Type", "text/event-stream");
      res.set_header("Cache-Control", "no-cache");
      res.set_header("Connection", "keep-alive");
      res.set_header("Access-Control-Allow-Origin", "*");
      res.set_chunked_content_provider(
          "text/event-stream", [&](intptr_t, httplib::DataSink &sink) -> bool {
            try {
              auto result = generateImage([&sink](int s, int t) {
                nlohmann::json p = {
                    {"type", "progress"}, {"step", s}, {"total_steps", t}};
                std::string ev = "event: progress\ndata: " + p.dump() + "\n\n";
                sink.write(ev.c_str(), ev.size());
              });
              auto enc_start = std::chrono::high_resolution_clock::now();
                
              std::vector<uint8_t> image_data_png = encode_rgb_to_png(result.image_data, result.width, result.height);

              std::string image_str_result(image_data_png.begin(),
              image_data_png.end());
                
              std::string enc_img = base64_encode(image_str_result);
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
                  {"seed", seed},
                  {"width", result.width},
                  {"height", result.height},
                  {"channels", result.channels},
                  {"generation_time_ms", result.generation_time_ms},
                  {"first_step_time_ms", result.first_step_time_ms}};
              std::string ev = "event: complete\ndata: " + c.dump() + "\n\n";
              sink.write(ev.c_str(), ev.size());
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
      res.set_header("Access-Control-Allow-Origin", "*");
    } catch (const std::invalid_argument &e) {
      nlohmann::json err = {
          {"error",
           {{"message", "Invalid Arg: " + std::string(e.what())},
            {"type", "request_error"}}}};
      res.status = 400;
      res.set_content(err.dump(), "application/json");
      res.set_header("Access-Control-Allow-Origin", "*");
    } catch (const std::exception &e) {
      nlohmann::json err = {
          {"error",
           {{"message", "Server Err: " + std::string(e.what())},
            {"type", "server_error"}}}};
      res.status = 500;
      res.set_content(err.dump(), "application/json");
      res.set_header("Access-Control-Allow-Origin", "*");
    }
  });

  std::cout << "Server listening on " << listen_address << ":" << port
            << std::endl;
  svr.listen(listen_address.c_str(), port);

  // --- Cleanup ---
  if (clipSession) clipInterpreter->releaseSession(clipSession);
  clipSession = nullptr;
  if (unetSession) unetInterpreter->releaseSession(unetSession);
  unetSession = nullptr;
  if (safetyCheckerSession)
    safetyCheckerInterpreter->releaseSession(safetyCheckerSession);
  safetyCheckerSession = nullptr;
  delete clipInterpreter;
  delete unetInterpreter;
  delete vaeDecoderInterpreter;
  delete vaeEncoderInterpreter;
  delete safetyCheckerInterpreter;
  clipApp.reset();
  unetApp.reset();
  vaeDecoderApp.reset();
  vaeEncoderApp.reset();

  return EXIT_SUCCESS;
}
