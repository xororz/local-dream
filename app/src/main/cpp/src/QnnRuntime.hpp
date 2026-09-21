#ifndef QNNRUNTIME_HPP
#define QNNRUNTIME_HPP

#include <cstdint>
#include <filesystem>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "DynamicLoadUtil.hpp"
#include "Logger.hpp"
#include "MemUtils.hpp"
#include "PAL/DynamicLoading.hpp"
#include "QnnModel.hpp"
#include "QnnSampleAppUtils.hpp"
#include "zstd.h"

// Owns the process-wide QNN backend state (system function pointers and the
// HTP backend stub path) and provides creation/initialization of QnnModel
// instances on top of it. init() must succeed before any model is created.
namespace qnn_runtime {

inline QnnFunctionPointers g_systemFuncs;
inline std::string g_backendPath;
inline bool g_initialized = false;

// Resolves libQnnHtp.so / libQnnSystem.so inside `lib_dir`.
inline bool init(const std::string &lib_dir) {
  using namespace qnn::tools;
  std::filesystem::path lib(lib_dir);
  g_backendPath = (lib / "libQnnHtp.so").string();
  dynamicloadutil::StatusCode status =
      dynamicloadutil::getQnnSystemFunctionPointers(
          (lib / "libQnnSystem.so").string(), &g_systemFuncs);
  g_initialized = (status == dynamicloadutil::StatusCode::SUCCESS);
  return g_initialized;
}

inline bool isInitialized() { return g_initialized; }

inline std::unique_ptr<QnnModel> createModel(const std::string &modelPath,
                                             const std::string &modelName) {
  using namespace qnn::tools;
  ::QnnFunctionPointers funcs = g_systemFuncs;
  void *backendHandle = nullptr;
  void *modelHandle = nullptr;
  dynamicloadutil::StatusCode drvStatus =
      dynamicloadutil::getQnnFunctionPointers(g_backendPath, modelPath, &funcs,
                                              &backendHandle, false,
                                              &modelHandle);
  if (drvStatus != dynamicloadutil::StatusCode::SUCCESS) {
    QNN_ERROR("Failed get QNN func ptrs for %s.", modelName.c_str());
    if (modelHandle) dlclose(modelHandle);
    return nullptr;
  }
  std::string inputListPaths, opPackagePaths, outputPath, saveBinaryName;
  bool debug = false;
  bool dumpOutputs = false;
  iotensor::OutputDataType outputDataType =
      iotensor::OutputDataType::FLOAT_ONLY;
  iotensor::InputDataType inputDataType = iotensor::InputDataType::FLOAT;
  sample_app::ProfilingLevel profilingLevel = sample_app::ProfilingLevel::OFF;
  auto app = std::make_unique<QnnModel>(
      funcs, inputListPaths, opPackagePaths, backendHandle, outputPath, debug,
      outputDataType, inputDataType, profilingLevel, dumpOutputs, modelPath,
      saveBinaryName);
  // Hand off the model library handle so the QnnModel destructor can dlclose
  // it. Otherwise lowram mode leaks one .so handle per load cycle.
  if (app) app->m_modelHandle = modelHandle;
  return app;
}

// Runs the full QnnModel bring-up sequence. When `buffer` is non-null the
// context is created from that in-memory binary instead of the model file.
inline int initializeApp(const std::string &modelName,
                         std::unique_ptr<QnnModel> &app,
                         const uint8_t *buffer = nullptr,
                         uint64_t bufferSize = 0) {
  using qnn::tools::sample_app::StatusCode;
  if (!app) return EXIT_FAILURE;

  if (buffer && bufferSize > 0) {
    QNN_INFO("Initializing QNN App from Buffer: %s (size: %llu bytes)",
             modelName.c_str(), bufferSize);
  } else {
    QNN_INFO("Initializing QNN App from Cache: %s", modelName.c_str());
  }

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

  if (buffer && bufferSize > 0) {
    if (StatusCode::SUCCESS != app->createFromBuffer(buffer, bufferSize))
      return app->reportError(modelName + " Create From Buffer failure");
  } else {
    if (StatusCode::SUCCESS != app->createFromBinary())
      return app->reportError(modelName + " Create From Binary failure");
  }

  if (StatusCode::SUCCESS != app->enablePerformaceMode())
    return app->reportError(modelName + " Enable Performance Mode failure");

  if (buffer && bufferSize > 0) {
    QNN_INFO("QNN App Initialized from Buffer: %s", modelName.c_str());
  } else {
    QNN_INFO("QNN App Initialized from Cache: %s", modelName.c_str());
  }
  return EXIT_SUCCESS;
}

// Convenience wrapper used by lazy (lowram) loading: create + initialize in
// one call, throwing on failure.
inline std::unique_ptr<QnnModel> createAndInitModel(
    const std::string &modelPath, const std::string &modelName) {
  auto app = createModel(modelPath, modelName);
  if (!app) throw std::runtime_error("Failed create QNN model: " + modelName);
  if (initializeApp(modelName, app) != EXIT_SUCCESS)
    throw std::runtime_error("Failed init QNN model: " + modelName);
  return app;
}

struct PatchedModelBuffer {
  std::shared_ptr<uint8_t> buffer;
  uint64_t size;

  PatchedModelBuffer() : buffer(nullptr), size(0) {}

  PatchedModelBuffer(uint8_t *buf, uint64_t sz)
      : buffer(buf, std::default_delete<uint8_t[]>()), size(sz) {}

  void reset() {
    buffer.reset();
    size = 0;
  }
};

inline std::vector<char> readFileForPatch(const std::string &filePath) {
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

inline std::unique_ptr<PatchedModelBuffer> applyZstdPatchToBuffer(
    const std::string &oldFilePath, const std::string &patchFilePath) {
  try {
    // The old model is only read (as the zstd dictionary), so map it read-only
    // instead of pulling the whole multi-GB file into an anonymous buffer.
    MmapFile oldFile(oldFilePath);
    if (!oldFile.valid()) {
      throw std::runtime_error("Failed to map old file: " + oldFilePath);
    }
    QNN_INFO("Mapped old file (%s): %zu bytes.", oldFilePath.c_str(),
             oldFile.size);

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

    if (decompressedSize == 0) {
      QNN_ERROR("Patch resulted in empty buffer.");
      return nullptr;
    }

    uint8_t *newBuffer = new uint8_t[decompressedSize];

    ZSTD_DCtx *const dctx = ZSTD_createDCtx();
    if (dctx == nullptr) {
      delete[] newBuffer;
      throw std::runtime_error("ZSTD_createDCtx() failed!");
    }

    size_t const actualDecompressedSize = ZSTD_decompress_usingDict(
        dctx, newBuffer, decompressedSize, patchFileBuffer.data(),
        patchFileBuffer.size(), oldFile.data, oldFile.size);

    ZSTD_freeDCtx(dctx);

    if (ZSTD_isError(actualDecompressedSize)) {
      delete[] newBuffer;
      throw std::runtime_error(
          "ZSTD_decompress_usingDict() failed: " +
          std::string(ZSTD_getErrorName(actualDecompressedSize)));
    }

    QNN_INFO("Successfully applied patch to buffer. Decompressed %zu bytes.",
             actualDecompressedSize);

    return std::make_unique<PatchedModelBuffer>(newBuffer,
                                                actualDecompressedSize);

  } catch (const std::exception &e) {
    QNN_ERROR("Error applying patch to buffer: %s", e.what());
    return nullptr;
  }
}

// Legacy on-disk patched models ("unet.bin.<res>") are superseded by the
// in-memory patch flow; remove any leftovers next to the patch file.
inline void cleanupOldPatchedFiles(const std::string &patchPath) {
  try {
    std::filesystem::path patchFile(patchPath);
    std::filesystem::path patchDir = patchFile.parent_path();

    size_t totalFreed = 0;
    int filesRemoved = 0;

    for (const auto &entry : std::filesystem::directory_iterator(patchDir)) {
      if (entry.is_regular_file()) {
        std::string filename = entry.path().filename().string();

        if (filename.rfind("unet.bin.", 0) == 0 && filename.length() > 9) {
          try {
            auto fileSize = entry.file_size();
            std::filesystem::remove(entry.path());
            totalFreed += fileSize;
            filesRemoved++;
            QNN_INFO("Cleaned up old patched file: %s (%.2f MB)",
                     entry.path().string().c_str(),
                     fileSize / (1024.0 * 1024.0));
          } catch (const std::exception &e) {
            QNN_WARN("Failed to remove file %s: %s",
                     entry.path().string().c_str(), e.what());
          }
        }
      }
    }

    if (filesRemoved > 0) {
      QNN_INFO("Total: cleaned up %d old patched file(s), freed %.2f MB",
               filesRemoved, totalFreed / (1024.0 * 1024.0));
    } else {
      QNN_DEBUG("No old patched files to clean up");
    }
  } catch (const std::exception &e) {
    QNN_WARN("Failed to clean up old patched files: %s", e.what());
  }
}

}  // namespace qnn_runtime

#endif  // QNNRUNTIME_HPP
