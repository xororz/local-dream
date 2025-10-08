#include <chrono>
#include <filesystem>
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
#include "LaplacianBlend.hpp"
#include "QnnModel.hpp"
#include "SDUtils.hpp"
#include "SafeTensor2MNN.hpp"

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

#include "zstd.h"

int port = 8081;
std::string listen_address = "127.0.0.1";
bool ponyv55 = false;
bool use_mnn = false;
bool use_safety_checker = false;
bool use_mnn_clip = false;
float nsfw_threshold = 0.5f;
std::string clipPath, unetPath, vaeDecoderPath, vaeEncoderPath,
    safetyCheckerPath, tokenizerPath, patchPath, modelDir;
int resolution = 512;
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
bool use_opencl;

bool cvt_model = false;
std::string model_dir;
bool clip_skip_2 = false;

namespace qnn {
namespace tools {
namespace sample_app {

std::vector<char> readFileForPatch(const std::string &filePath) {
  std::ifstream file(filePath, std::ios::binary | std::ios::ate);
  if (!file.is_open()) {
    throw std::runtime_error("Failed to open file: " + filePath);
  }
  std::streamsize size = file.tellg();
  file.seekg(0, std::ios::beg);
  std::vector<char> buffer(size);
  if (size > 0) {
    if (!file.read(buffer.data(), size)) {
      throw std::runtime_error("Failed to read file: " + filePath);
    }
  }
  return buffer;
}

void writeFileForPatch(const std::string &filePath,
                       const std::vector<char> &data) {
  std::ofstream file(filePath, std::ios::binary);
  if (!file.is_open()) {
    throw std::runtime_error("Failed to open file for writing: " + filePath);
  }
  if (!data.empty()) {
    if (!file.write(data.data(), data.size())) {
      throw std::runtime_error("Failed to write file: " + filePath);
    }
  }
}

int applyZstdPatch(const std::string &oldFilePath,
                   const std::string &patchFilePath,
                   const std::string &newFilePath) {
  try {
    std::vector<char> oldFileBuffer = readFileForPatch(oldFilePath);
    QNN_INFO("Read old file (%s): %zu bytes.", oldFilePath.c_str(),
             oldFileBuffer.size());

    std::vector<char> patchFileBuffer = readFileForPatch(patchFilePath);
    QNN_INFO("Read patch file (%s): %zu bytes.", patchFilePath.c_str(),
             patchFileBuffer.size());

    if (patchFileBuffer.empty()) {
      throw std::runtime_error("Patch file (" + patchFilePath +
                               ") is empty or could not be read.");
    }

    unsigned long long const decompressedSize = ZSTD_getFrameContentSize(
        patchFileBuffer.data(), patchFileBuffer.size());

    if (decompressedSize == ZSTD_CONTENTSIZE_ERROR) {
      throw std::runtime_error("Patch file (" + patchFilePath +
                               ") is not a valid zstd frame.");
    }
    if (decompressedSize == ZSTD_CONTENTSIZE_UNKNOWN) {
      throw std::runtime_error(
          "Decompressed size is unknown. Cannot proceed with this simple "
          "implementation.");
    }

    std::vector<char> newFileBuffer;
    if (decompressedSize > 0) {
      newFileBuffer.resize(decompressedSize);
    } else {
      writeFileForPatch(newFilePath, newFileBuffer);
      QNN_INFO(
          "Successfully applied patch (resulting in an empty file). New file "
          "saved to: %s",
          newFilePath.c_str());
      return 0;
    }

    ZSTD_DCtx *const dctx = ZSTD_createDCtx();
    if (dctx == nullptr) {
      throw std::runtime_error("ZSTD_createDCtx() failed!");
    }

    size_t const actualDecompressedSize = ZSTD_decompress_usingDict(
        dctx, newFileBuffer.data(), newFileBuffer.size(),
        patchFileBuffer.data(), patchFileBuffer.size(), oldFileBuffer.data(),
        oldFileBuffer.size());

    ZSTD_freeDCtx(dctx);

    if (ZSTD_isError(actualDecompressedSize)) {
      throw std::runtime_error(
          "ZSTD_decompress_usingDict() failed: " +
          std::string(ZSTD_getErrorName(actualDecompressedSize)));
    }

    if (actualDecompressedSize != decompressedSize) {
      if (actualDecompressedSize < newFileBuffer.size()) {
        newFileBuffer.resize(actualDecompressedSize);
      }
    }

    QNN_INFO("Decompressed %zu bytes into new file buffer.",
             actualDecompressedSize);

    writeFileForPatch(newFilePath, newFileBuffer);
    QNN_INFO("Successfully applied patch. New file saved to: %s",
             newFilePath.c_str());

  } catch (const std::exception &e) {
    QNN_ERROR("Error applying patch: %s", e.what());
    return 1;
  }
  return 0;
}

std::string processPatchLogic(const std::string &originalUnetPath,
                              const std::string &patchPath, int resolution) {
  if (patchPath.empty()) {
    return originalUnetPath;
  }

  try {
    std::filesystem::path patchFile(patchPath);
    std::filesystem::path patchDir = patchFile.parent_path();

    std::string targetFileName = "unet.bin." + std::to_string(resolution);
    std::filesystem::path targetPath = patchDir / targetFileName;

    if (std::filesystem::exists(targetPath)) {
      QNN_INFO("Target file %s already exists, using it directly.",
               targetPath.string().c_str());
      return targetPath.string();
    }

    QNN_INFO("Target file %s does not exist, applying patch...",
             targetPath.string().c_str());

    std::filesystem::path tempPath = patchDir / "unet.bin.tmp";

    int result = applyZstdPatch(originalUnetPath, patchPath, tempPath.string());
    if (result != 0) {
      QNN_ERROR("Failed to apply patch");
      return originalUnetPath;
    }

    try {
      std::filesystem::rename(tempPath, targetPath);
      QNN_INFO("Successfully renamed %s to %s", tempPath.string().c_str(),
               targetPath.string().c_str());
    } catch (const std::filesystem::filesystem_error &e) {
      QNN_ERROR("Failed to rename file: %s", e.what());
      return originalUnetPath;
    }

    return targetPath.string();

  } catch (const std::exception &e) {
    QNN_ERROR("Error in patch processing: %s", e.what());
    return originalUnetPath;
  }
}

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
    OPT_CONVERT = 30,
    OPT_CONVERT_CLIP_SKIP_2 = 31,
    OPT_BACKEND = 3,
    OPT_LOG_LEVEL = 10,
    OPT_VERSION = 13,
    OPT_SYSTEM_LIBRARY = 14,
    OPT_PORT = 15,
    OPT_TOKENIZER = 16,
    OPT_PATCH = 17
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
      {"convert", pal::required_argument, NULL, OPT_CONVERT},
      {"clip_skip_2", pal::no_argument, NULL, OPT_CONVERT_CLIP_SKIP_2},
      {"tokenizer", pal::required_argument, NULL, OPT_TOKENIZER},
      {"clip", pal::required_argument, NULL, OPT_CLIP},
      {"unet", pal::required_argument, NULL, OPT_UNET},
      {"vae_decoder", pal::required_argument, NULL, OPT_VAE_DECODER},
      {"backend", pal::required_argument, NULL, OPT_BACKEND},
      {"log_level", pal::required_argument, NULL, OPT_LOG_LEVEL},
      {"system_library", pal::required_argument, NULL, OPT_SYSTEM_LIBRARY},
      {"version", pal::no_argument, NULL, OPT_VERSION},
      {"patch", pal::required_argument, NULL, OPT_PATCH},
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
        modelDir = std::filesystem::path(clipPath).parent_path().string();
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
      case OPT_CONVERT:
        cvt_model = true;
        model_dir = pal::g_optArg;
        break;
      case OPT_CONVERT_CLIP_SKIP_2:
        clip_skip_2 = true;
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
      case OPT_PATCH:
        patchPath = pal::g_optArg;
        if (patchPath.find("1024.patch") != std::string::npos) {
          resolution = 1024;
        } else if (patchPath.find("768.patch") != std::string::npos) {
          resolution = 768;
        } else {
          QNN_WARN("Unknown patch type, using default resolution: %d",
                   resolution);
        }
        break;
      default:
        showHelpAndExit("Invalid argument passed.");
    }
  }
  if (cvt_model) {
    if (!std::filesystem::exists(model_dir)) {
      showHelpAndExit("Model directory does not exist: " + model_dir);
    }
    std::string model_name = "model.safetensors";
    auto model_path = std::filesystem::path(model_dir) / model_name;
    if (!std::filesystem::exists(model_path)) {
      showHelpAndExit("Model file does not exist");
    }

    std::vector<std::string> loras;
    std::vector<float> lora_weights;
    for (int i = 1;; ++i) {
      std::string lora_filename = "lora." + std::to_string(i) + ".safetensors";
      auto lora_path = std::filesystem::path(model_dir) / lora_filename;
      if (!std::filesystem::exists(lora_path)) {
        break;
      }
      loras.push_back(lora_filename);

      std::string weight_filename = "lora." + std::to_string(i) + ".weight";
      auto weight_path = std::filesystem::path(model_dir) / weight_filename;
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

    generateMNNModels(model_dir, model_name, clip_skip_2, loras, lora_weights);
    exit(EXIT_SUCCESS);
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

  std::string finalUnetPath =
      processPatchLogic(unetPath, patchPath, resolution);
  if (finalUnetPath != unetPath) {
    QNN_INFO("Using patched unet path: %s", finalUnetPath.c_str());
    unetPath = finalUnetPath;
  }

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
xt::xarray<float> blend_vae_encoder_tiles(
    const std::vector<std::pair<xt::xarray<float>, xt::xarray<float>>>
        &tiles_mean_std,
    const std::vector<std::pair<int, int>> &positions, int latent_h,
    int latent_w, int tile_size, int overlap) {
  if (tiles_mean_std.empty()) {
    throw std::runtime_error(
        "Tile list cannot be empty for VAE encoder blending.");
  }

  std::vector<int> accumulated_shape = {1, 4, latent_h, latent_w};
  xt::xarray<float> accumulated_mean = xt::zeros<float>(accumulated_shape);
  xt::xarray<float> accumulated_std = xt::zeros<float>(accumulated_shape);
  xt::xarray<float> weight_map = xt::zeros<float>({latent_h, latent_w});

  int fade_size = overlap / 2;
  xt::xarray<float> tile_weight = xt::ones<float>({tile_size, tile_size});

  if (fade_size > 0) {
    for (int i = 0; i < fade_size; ++i) {
      float alpha = (float)(i + 1) / fade_size;
      xt::view(tile_weight, i, xt::all()) *= alpha;
      xt::view(tile_weight, tile_size - 1 - i, xt::all()) *= alpha;
      xt::view(tile_weight, xt::all(), i) *= alpha;
      xt::view(tile_weight, xt::all(), tile_size - 1 - i) *= alpha;
    }
  }

  for (size_t idx = 0; idx < tiles_mean_std.size(); ++idx) {
    int x = positions[idx].first;
    int y = positions[idx].second;

    const auto &mean_tile =
        tiles_mean_std[idx].first;  // (1, 4, tile_size, tile_size)
    const auto &std_tile =
        tiles_mean_std[idx].second;  // (1, 4, tile_size, tile_size)

    for (int c = 0; c < 4; ++c) {
      auto acc_mean_slice =
          xt::view(accumulated_mean, 0, c, xt::range(y, y + tile_size),
                   xt::range(x, x + tile_size));
      auto mean_slice = xt::view(mean_tile, 0, c, xt::all(), xt::all());
      acc_mean_slice += mean_slice * tile_weight;

      auto acc_std_slice =
          xt::view(accumulated_std, 0, c, xt::range(y, y + tile_size),
                   xt::range(x, x + tile_size));
      auto std_slice = xt::view(std_tile, 0, c, xt::all(), xt::all());
      acc_std_slice += std_slice * tile_weight;
    }

    auto weight_slice = xt::view(weight_map, xt::range(y, y + tile_size),
                                 xt::range(x, x + tile_size));
    weight_slice += tile_weight;
  }

  weight_map = xt::maximum(weight_map, 1e-8f);
  xt::xarray<float> weight_expanded =
      xt::reshape_view(weight_map, {1, 1, latent_h, latent_w});

  xt::xarray<float> final_mean = accumulated_mean / weight_expanded;
  xt::xarray<float> final_std = accumulated_std / weight_expanded;

  xt::xarray<float> noise =
      xt::random::randn<float>({1, 4, latent_h, latent_w});
  xt::xarray<float> latent = xt::eval(final_mean + final_std * noise);

  return latent;
}
xt::xarray<float> blend_vae_output_tiles(
    const std::vector<xt::xarray<float>> &tiles,
    const std::vector<std::pair<int, int>> &positions, int output_h,
    int output_w, int tile_size, int overlap) {
  if (tiles.empty()) {
    throw std::runtime_error(
        "Tile list cannot be empty for VAE output blending.");
  }

  std::vector<int> accumulated_shape = {1, 3, output_h, output_w};
  xt::xarray<float> accumulated = xt::zeros<float>(accumulated_shape);
  xt::xarray<float> weight_map = xt::zeros<float>({output_h, output_w});

  int fade_size = overlap / 2;
  xt::xarray<float> tile_weight = xt::ones<float>({tile_size, tile_size});

  if (fade_size > 0) {
    for (int i = 0; i < fade_size; ++i) {
      float alpha = (float)(i + 1) / fade_size;
      xt::view(tile_weight, i, xt::all()) *= alpha;
      xt::view(tile_weight, tile_size - 1 - i, xt::all()) *= alpha;
      xt::view(tile_weight, xt::all(), i) *= alpha;
      xt::view(tile_weight, xt::all(), tile_size - 1 - i) *= alpha;
    }
  }

  for (size_t idx = 0; idx < tiles.size(); ++idx) {
    int x = positions[idx].first;
    int y = positions[idx].second;

    for (int c = 0; c < 3; ++c) {
      auto acc_slice = xt::view(accumulated, 0, c, xt::range(y, y + tile_size),
                                xt::range(x, x + tile_size));
      auto tile_slice = xt::view(tiles[idx], 0, c, xt::all(), xt::all());
      acc_slice += tile_slice * tile_weight;
    }

    auto weight_slice = xt::view(weight_map, xt::range(y, y + tile_size),
                                 xt::range(x, x + tile_size));
    weight_slice += tile_weight;
  }

  weight_map = xt::maximum(weight_map, 1e-8f);
  xt::xarray<float> weight_expanded =
      xt::reshape_view(weight_map, {1, 1, output_h, output_w});

  return accumulated / weight_expanded;
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

      bool need_vae_enc_tiling =
          (output_size >= 768 && !use_mnn && vaeEncoderApp);

      xt::xarray<float> img_lat_scaled;

      if (!need_vae_enc_tiling) {
        std::vector<float> vae_enc_mean(1 * 4 * sample_size * sample_size);
        std::vector<float> vae_enc_std(1 * 4 * sample_size * sample_size);

        if (use_mnn) {
          MNN::Interpreter *currentVaeEncoderInterpreter =
              MNN::Interpreter::createFromFile(vaeEncoderPath.c_str());
          if (!currentVaeEncoderInterpreter)
            throw std::runtime_error("Failed MNN VAE Enc create");

          MNN::ScheduleConfig cfg_vae_enc;
          MNN::BackendConfig bkCfg_vae_enc;
          if (use_opencl) {
            auto cache_file =
                modelDir + "/vae_enc_cache.mnnc." + std::to_string(output_size);
            currentVaeEncoderInterpreter->setCacheFile(cache_file.c_str());
            cfg_vae_enc.type = MNN_FORWARD_OPENCL;
            cfg_vae_enc.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
            bkCfg_vae_enc.precision = MNN::BackendConfig::Precision_Low;
          } else {
            cfg_vae_enc.type = MNN_FORWARD_CPU;
            cfg_vae_enc.numThread = 4;
            bkCfg_vae_enc.memory = MNN::BackendConfig::Memory_Low;
          }
          bkCfg_vae_enc.power = MNN::BackendConfig::Power_High;
          cfg_vae_enc.backendConfig = &bkCfg_vae_enc;

          MNN::Session *currentVaeEncSession =
              currentVaeEncoderInterpreter->createSession(cfg_vae_enc);
          if (!currentVaeEncSession)
            throw std::runtime_error("Failed create temp MNN VAE Enc session!");

          auto input = currentVaeEncoderInterpreter->getSessionInput(
              currentVaeEncSession, "input");
          currentVaeEncoderInterpreter->resizeTensor(
              input, {1, 3, output_size, output_size});
          currentVaeEncoderInterpreter->resizeSession(currentVaeEncSession);
          if (use_opencl) {
            currentVaeEncoderInterpreter->updateCacheFile(currentVaeEncSession);
          }
          currentVaeEncoderInterpreter->releaseModel();

          auto input_nchw_tensor = new MNN::Tensor(input, MNN::Tensor::CAFFE);
          auto mean_t = currentVaeEncoderInterpreter->getSessionOutput(
              currentVaeEncSession, "mean");
          auto std_t = currentVaeEncoderInterpreter->getSessionOutput(
              currentVaeEncSession, "std");
          auto mean_nchw_tensor = new MNN::Tensor(mean_t, MNN::Tensor::CAFFE);
          auto std_nchw_tensor = new MNN::Tensor(std_t, MNN::Tensor::CAFFE);

          memcpy(input_nchw_tensor->host<float>(), img_data.data(),
                 img_data.size() * sizeof(float));
          input->copyFromHostTensor(input_nchw_tensor);
          currentVaeEncoderInterpreter->runSession(currentVaeEncSession);

          mean_t->copyToHostTensor(mean_nchw_tensor);
          std_t->copyToHostTensor(std_nchw_tensor);
          memcpy(vae_enc_mean.data(), mean_nchw_tensor->host<float>(),
                 vae_enc_mean.size() * sizeof(float));
          memcpy(vae_enc_std.data(), std_nchw_tensor->host<float>(),
                 vae_enc_std.size() * sizeof(float));

          delete input_nchw_tensor;
          delete mean_nchw_tensor;
          delete std_nchw_tensor;

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

        auto mean = xt::adapt(vae_enc_mean, shape);
        auto std_dev = xt::adapt(vae_enc_std, shape);
        xt::xarray<float> noise_0 = xt::random::randn<float>(shape);
        xt::xarray<float> img_lat = xt::eval(mean + std_dev * noise_0);
        img_lat_scaled = xt::eval(0.18215 * img_lat);

      } else {
        std::cout << "Using VAE encoder tiling for " << output_size << "x"
                  << output_size << " input..." << std::endl;

        const int vae_enc_tile_size = 512;
        const int vae_enc_latent_tile_size = 64;

        std::vector<std::pair<int, int>> img_positions;
        std::vector<std::pair<int, int>> latent_positions;
        int latent_overlap;
        int num_tiles_per_side;

        if (output_size == 768) {
          num_tiles_per_side = 2;
          latent_overlap = 32;
        } else if (output_size == 1024) {
          num_tiles_per_side = 3;
          latent_overlap = 32;
        } else {
          throw std::runtime_error("Unsupported size for VAE encoder tiling");
        }

        int img_stride =
            (output_size - vae_enc_tile_size) / (num_tiles_per_side - 1);
        int latent_stride =
            (sample_size - vae_enc_latent_tile_size) / (num_tiles_per_side - 1);

        for (int row = 0; row < num_tiles_per_side; ++row) {
          for (int col = 0; col < num_tiles_per_side; ++col) {
            int x = col * img_stride;
            int y = row * img_stride;
            int lat_x = col * latent_stride;
            int lat_y = row * latent_stride;

            x = std::min(x, output_size - vae_enc_tile_size);
            y = std::min(y, output_size - vae_enc_tile_size);
            lat_x = std::min(lat_x, sample_size - vae_enc_latent_tile_size);
            lat_y = std::min(lat_y, sample_size - vae_enc_latent_tile_size);

            img_positions.push_back({x, y});
            latent_positions.push_back({lat_x, lat_y});
          }
        }

        int original_output_size = output_size;
        int original_sample_size = sample_size;

        output_size = vae_enc_tile_size;
        sample_size = vae_enc_latent_tile_size;

        std::vector<std::pair<xt::xarray<float>, xt::xarray<float>>>
            encoded_tiles_mean_std;
        encoded_tiles_mean_std.reserve(img_positions.size());

        for (size_t i = 0; i < img_positions.size(); ++i) {
          auto img_pos = img_positions[i];
          xt::xarray<float> img_tile = xt::view(
              original_image, 0, xt::all(),
              xt::range(img_pos.second, img_pos.second + vae_enc_tile_size),
              xt::range(img_pos.first, img_pos.first + vae_enc_tile_size));

          std::vector<float> tile_img_vec(img_tile.begin(), img_tile.end());
          std::vector<float> tile_mean_vec(1 * 4 * vae_enc_latent_tile_size *
                                           vae_enc_latent_tile_size);
          std::vector<float> tile_std_vec(1 * 4 * vae_enc_latent_tile_size *
                                          vae_enc_latent_tile_size);

          if (!vaeEncoderApp)
            throw std::runtime_error("Global vaeEncoderApp not init!");

          if (StatusCode::SUCCESS !=
              vaeEncoderApp->executeVaeEncoderGraphs(tile_img_vec.data(),
                                                     tile_mean_vec.data(),
                                                     tile_std_vec.data()))
            throw std::runtime_error("QNN VAE enc exec failed for tile");

          std::vector<int> tile_shape = {1, 4, vae_enc_latent_tile_size,
                                         vae_enc_latent_tile_size};
          encoded_tiles_mean_std.push_back(
              {xt::adapt(tile_mean_vec, tile_shape),
               xt::adapt(tile_std_vec, tile_shape)});
          std::cout << "Processed VAE encoder tile " << i + 1 << "/"
                    << img_positions.size() << std::endl;
        }

        output_size = original_output_size;
        sample_size = original_sample_size;

        xt::xarray<float> img_lat = blend_vae_encoder_tiles(
            encoded_tiles_mean_std, latent_positions, sample_size, sample_size,
            vae_enc_latent_tile_size, latent_overlap);

        img_lat_scaled = xt::eval(0.18215 * img_lat);

        std::cout << "VAE encoder tiling completed: "
                  << encoded_tiles_mean_std.size()
                  << " tiles processed and blended" << std::endl;
      }

      auto vae_enc_end = std::chrono::high_resolution_clock::now();
      std::cout << "VAE Enc dur: "
                << std::chrono::duration_cast<std::chrono::milliseconds>(
                       vae_enc_end - vae_enc_start)
                       .count()
                << "ms\n";

      original_latents = img_lat_scaled;
      start_step = steps * (1.0f - denoise_strength);
      total_run_steps -= start_step;
      scheduler.set_begin_index(start_step);
      xt::xarray<int> t = {(int)(timesteps(start_step))};
      latents = scheduler.add_noise(original_latents, latents_noise, t);

      if (request_has_mask) {
        mask = xt::adapt(mask_data, {1, 4, sample_size, sample_size});
        mask_full = xt::adapt(mask_data_full, {1, 3, output_size, output_size});
      }

      current_step++;
      progress_callback(current_step, total_run_steps);
    }  // --- UNET Denoising Loop ---
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
      MNN::BackendConfig bkCfg_unet;
      if (use_opencl) {
        auto cache_file =
            modelDir + "/unet_cache.mnnc." + std::to_string(output_size);
        currentUnetInterpreter->setCacheFile(cache_file.c_str());
        cfg_unet.type = MNN_FORWARD_OPENCL;
        cfg_unet.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
        bkCfg_unet.precision = MNN::BackendConfig::Precision_Low;
      } else {
        cfg_unet.type = MNN_FORWARD_CPU;
        cfg_unet.numThread = 4;
        bkCfg_unet.memory = MNN::BackendConfig::Memory_Low;
      }
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

      currentUnetInterpreter->resizeTensor(
          samp, {batch_size, 4, sample_size, sample_size});
      currentUnetInterpreter->resizeTensor(ts, {1});
      currentUnetInterpreter->resizeTensor(
          enc, {batch_size, 77, text_embedding_size});
      currentUnetInterpreter->resizeSession(currentUnetSession);
      if (use_opencl) {
        currentUnetInterpreter->updateCacheFile(currentUnetSession);
      }

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

        auto samp_nchw_tensor = new MNN::Tensor(samp, MNN::Tensor::CAFFE);
        auto ts_nchw_tensor = new MNN::Tensor(ts, MNN::Tensor::CAFFE);
        auto enc_nchw_tensor = new MNN::Tensor(enc, MNN::Tensor::CAFFE);

        // Copy both batches (negative and positive) at once
        memcpy(samp_nchw_tensor->host<float>(), latents_in_vec.data(),
               latents_in_vec.size() * sizeof(float));
        memcpy(ts_nchw_tensor->host<int>(), &current_ts_int, sizeof(int));
        memcpy(enc_nchw_tensor->host<float>(), text_embedding_float.data(),
               text_embedding_float.size() * sizeof(float));

        samp->copyFromHostTensor(samp_nchw_tensor);
        ts->copyFromHostTensor(ts_nchw_tensor);
        enc->copyFromHostTensor(enc_nchw_tensor);

        // Single batch inference for both negative and positive conditions
        currentUnetInterpreter->runSession(currentUnetSession);

        auto output = currentUnetInterpreter->getSessionOutput(
            currentUnetSession, "out_sample");
        output->copyToHostTensor(samp_nchw_tensor);
        memcpy(unet_out_latents.data(), samp_nchw_tensor->host<float>(),
               unet_out_latents.size() * sizeof(float));

        delete samp_nchw_tensor;
        delete ts_nchw_tensor;
        delete enc_nchw_tensor;
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

    bool need_vae_tiling =
        ((output_size == 768 || output_size == 1024) && !use_mnn);
    if (need_vae_tiling) {
      std::cout << "Using VAE tiling for " << output_size << "x" << output_size
                << " output..." << std::endl;
    }

    latents = xt::eval((1.0 / 0.18215) * latents);

    xt::xarray<float> pixels;

    if (!need_vae_tiling) {
      std::vector<float> vae_dec_in_vec(latents.begin(), latents.end());
      std::vector<float> vae_dec_out_pixels(1 * 3 * output_size * output_size);

      if (use_mnn) {
        MNN::Interpreter *currentVaeDecoderInterpreter =
            MNN::Interpreter::createFromFile(vaeDecoderPath.c_str());

        if (!currentVaeDecoderInterpreter)
          throw std::runtime_error(
              "Failed to create temporary MNN VAE Decoder interpreter!");

        MNN::ScheduleConfig cfg_vae;
        MNN::BackendConfig bkCfg_vae;
        if (use_opencl) {
          auto cache_file =
              modelDir + "/vae_dec_cache.mnnc." + std::to_string(output_size);
          currentVaeDecoderInterpreter->setCacheFile(cache_file.c_str());
          cfg_vae.type = MNN_FORWARD_OPENCL;
          cfg_vae.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
          bkCfg_vae.precision = MNN::BackendConfig::Precision_Low;
        } else {
          cfg_vae.type = MNN_FORWARD_CPU;
          cfg_vae.numThread = 4;
          bkCfg_vae.memory = MNN::BackendConfig::Memory_Low;
        }
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
        if (use_opencl) {
          currentVaeDecoderInterpreter->updateCacheFile(currentVaeDecSession);
        }

        currentVaeDecoderInterpreter->releaseModel();

        auto input_nchw_tensor = new MNN::Tensor(input, MNN::Tensor::CAFFE);
        auto output = currentVaeDecoderInterpreter->getSessionOutput(
            currentVaeDecSession, "sample");
        auto output_nchw_tensor = new MNN::Tensor(output, MNN::Tensor::CAFFE);

        memcpy(input_nchw_tensor->host<float>(), vae_dec_in_vec.data(),
               vae_dec_in_vec.size() * sizeof(float));
        input->copyFromHostTensor(input_nchw_tensor);

        currentVaeDecoderInterpreter->runSession(currentVaeDecSession);

        output->copyToHostTensor(output_nchw_tensor);
        memcpy(vae_dec_out_pixels.data(), output_nchw_tensor->host<float>(),
               vae_dec_out_pixels.size() * sizeof(float));

        delete input_nchw_tensor;
        delete output_nchw_tensor;

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

      std::vector<int> pixel_shape = {1, 3, output_size, output_size};
      pixels = xt::adapt(vae_dec_out_pixels, pixel_shape);

    } else {
      const int vae_tile_size = 512;
      const int vae_latent_tile_size = 64;

      std::vector<std::pair<int, int>> latent_positions;
      std::vector<std::pair<int, int>> output_positions;
      int overlap, latent_overlap;

      if (output_size == 768) {
        overlap = 256;
        latent_overlap = 32;
        int output_stride = vae_tile_size - overlap;
        int latent_stride = vae_latent_tile_size - latent_overlap;

        for (int row = 0; row < 2; ++row) {
          for (int col = 0; col < 2; ++col) {
            int x = col * output_stride;
            int y = row * output_stride;
            int lat_x = col * latent_stride;
            int lat_y = row * latent_stride;

            if (col == 1) {
              x = output_size - vae_tile_size;
              lat_x = sample_size - vae_latent_tile_size;
            }
            if (row == 1) {
              y = output_size - vae_tile_size;
              lat_y = sample_size - vae_latent_tile_size;
            }

            latent_positions.push_back({lat_x, lat_y});
            output_positions.push_back({x, y});
          }
        }
      } else if (output_size == 1024) {
        overlap = 256;
        latent_overlap = 32;
        int output_stride = (output_size - vae_tile_size) / 2;
        int latent_stride = (sample_size - vae_latent_tile_size) / 2;

        for (int row = 0; row < 3; ++row) {
          for (int col = 0; col < 3; ++col) {
            int x = col * output_stride;
            int y = row * output_stride;
            int lat_x = col * latent_stride;
            int lat_y = row * latent_stride;

            if (col == 2) {
              x = output_size - vae_tile_size;
              lat_x = sample_size - vae_latent_tile_size;
            }
            if (row == 2) {
              y = output_size - vae_tile_size;
              lat_y = sample_size - vae_latent_tile_size;
            }

            latent_positions.push_back({lat_x, lat_y});
            output_positions.push_back({x, y});
          }
        }
      } else {
        throw std::runtime_error("Unsupported size for VAE decoder tiling");
      }

      int original_output_size = output_size;
      int original_sample_size = sample_size;

      output_size = vae_tile_size;
      sample_size = vae_latent_tile_size;

      std::vector<xt::xarray<float>> decoded_tiles;
      decoded_tiles.reserve(latent_positions.size());

      for (size_t i = 0; i < latent_positions.size(); ++i) {
        auto lat_pos = latent_positions[i];
        xt::xarray<float> latent_tile = xt::view(
            latents, 0, xt::all(),
            xt::range(lat_pos.second, lat_pos.second + vae_latent_tile_size),
            xt::range(lat_pos.first, lat_pos.first + vae_latent_tile_size));

        std::vector<float> tile_latent_vec(latent_tile.begin(),
                                           latent_tile.end());
        std::vector<float> tile_output_vec(1 * 3 * vae_tile_size *
                                           vae_tile_size);

        if (!vaeDecoderApp)
          throw std::runtime_error("Global vaeDecoderApp not init!");

        if (StatusCode::SUCCESS !=
            vaeDecoderApp->executeVaeDecoderGraphs(tile_latent_vec.data(),
                                                   tile_output_vec.data()))
          throw std::runtime_error("QNN VAE dec exec failed for tile");

        std::vector<int> tile_shape = {1, 3, vae_tile_size, vae_tile_size};
        decoded_tiles.push_back(xt::adapt(tile_output_vec, tile_shape));

        std::cout << "Processed VAE tile " << i + 1 << "/"
                  << latent_positions.size() << std::endl;
      }

      output_size = original_output_size;
      sample_size = original_sample_size;

      pixels =
          blend_vae_output_tiles(decoded_tiles, output_positions, output_size,
                                 output_size, vae_tile_size, overlap);

      std::cout << "VAE tiling completed: " << decoded_tiles.size()
                << " tiles processed and blended" << std::endl;
    }

    auto vae_dec_end = std::chrono::high_resolution_clock::now();
    std::cout << "VAE Dec dur: "
              << std::chrono::duration_cast<std::chrono::milliseconds>(
                     vae_dec_end - vae_dec_start)
                     .count()
              << "ms\n";

    // --- Post-process Image ---
    if (request_has_mask) {
      auto orig_img_view = xt::view(original_image, 0);  // (3, H, W)
      auto gen_img_view = xt::view(pixels, 0);           // (3, H, W)
      auto mask_view = xt::view(mask_full, 0);           // (1, H, W)

      auto blended =
          laplacianPyramidBlend(orig_img_view, gen_img_view, mask_view);
      pixels = xt::reshape_view(blended, {1, 3, output_size, output_size});
    }
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
      use_opencl = json.value("use_opencl", false);
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
              std::string image_str_result(result.image_data.begin(),
                                           result.image_data.end());
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