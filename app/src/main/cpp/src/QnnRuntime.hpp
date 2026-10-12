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

// QNN >= 2.35 serialized context binaries declare, per graph, how large a
// spill-fill scratch buffer that graph needs (HTP graph blob info, exposed
// through the system API). Reading it is what lets a multi-context group be
// sized correctly; build with -DLOCALDREAM_READ_SPILL_FILL_METADATA=0 to turn
// the read off if the SDK headers in use do not expose it. Either way the
// group fallback below keeps every model loading.
#ifndef LOCALDREAM_READ_SPILL_FILL_METADATA
#if defined(__has_include)
#if __has_include(<HTP/QnnHtpSystemContext.h>)
#define LOCALDREAM_READ_SPILL_FILL_METADATA 1
#else
#define LOCALDREAM_READ_SPILL_FILL_METADATA 0
#endif
#else
#define LOCALDREAM_READ_SPILL_FILL_METADATA 1
#endif
#endif

#if LOCALDREAM_READ_SPILL_FILL_METADATA
#include <HTP/QnnHtpSystemContext.h>
#endif
#include <QnnSystemContext.h>

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

// --- HTP spill-fill metadata -------------------------------------------------
// The HTP backend enforces the declared spill-fill size of every context that
// registers into a multi-context group: a context whose declared size is larger
// than the group's shared buffer cannot be created at all. Pre-2.35 binaries
// (the qnn2.28 SDXL packages) carry no declaration, so any group size is
// accepted for them — which is why a hardcoded group size works for those and
// rejects qnn2.50 packages.

// Largest spill-fill size (bytes) declared by the graphs of one serialized
// context binary. Returns 0 when the binary carries no such metadata.
inline uint64_t contextSpillFillBytes(const uint8_t *buffer, uint64_t size) {
#if LOCALDREAM_READ_SPILL_FILL_METADATA
  // QNN >= 2.50 types this field as the versioned implementation struct
  // (QnnSystemInterface_ImplementationV1_14_t) rather than the provider
  // wrapper, so the function pointers are members of the field itself.
  const auto &sys = g_systemFuncs.qnnSystemInterface;
  if (buffer == nullptr || size == 0 || sys.systemContextCreate == nullptr ||
      sys.systemContextGetBinaryInfo == nullptr ||
      sys.systemContextFree == nullptr)
    return 0;

  QnnSystemContext_Handle_t sysCtxHandle = nullptr;
  if (QNN_SUCCESS != sys.systemContextCreate(&sysCtxHandle)) return 0;

  const QnnSystemContext_BinaryInfo_t *binaryInfo = nullptr;
  Qnn_ContextBinarySize_t binaryInfoSize = 0;
  uint64_t maxBytes = 0;
  if (QNN_SUCCESS ==
          sys.systemContextGetBinaryInfo(sysCtxHandle,
                                         const_cast<uint8_t *>(buffer), size,
                                         &binaryInfo, &binaryInfoSize) &&
      binaryInfo != nullptr) {
    uint32_t graphCount = 0;
    QnnSystemContext_GraphInfo_t *graphs = nullptr;
    switch (binaryInfo->version) {
      case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
        graphCount = binaryInfo->contextBinaryInfoV3.numGraphs;
        graphs = binaryInfo->contextBinaryInfoV3.graphs;
        break;
      case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
        graphCount = binaryInfo->contextBinaryInfoV2.numGraphs;
        graphs = binaryInfo->contextBinaryInfoV2.graphs;
        break;
      case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
        graphCount = binaryInfo->contextBinaryInfoV1.numGraphs;
        graphs = binaryInfo->contextBinaryInfoV1.graphs;
        break;
      default:
        break;
    }
    for (uint32_t i = 0; graphs != nullptr && i < graphCount; ++i) {
      // Only graph info v3 carries the HTP blob info with the spill-fill size;
      // v1/v2 binaries simply have none.
      if (graphs[i].version != QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
        continue;
      }
      const auto *htpBlob =
          reinterpret_cast<const QnnHtpSystemContext_GraphBlobInfo_t *>(
              graphs[i].graphInfoV3.graphBlobInfo);
      if (htpBlob == nullptr ||
          htpBlob->version !=
              QNN_SYSTEM_CONTEXT_HTP_GRAPH_INFO_BLOB_VERSION_V1) {
        continue;
      }
      const uint64_t bytes =
          htpBlob->contextBinaryGraphBlobInfoV1.spillFillBufferSize;
      if (bytes > maxBytes) maxBytes = bytes;
    }
  }
  sys.systemContextFree(sysCtxHandle);
  return maxBytes;
#else
  (void)buffer;
  (void)size;
  return 0;
#endif
}

// Same, for a serialized context binary on disk. The file is only mapped
// read-only: these packages are multiple GB and just the metadata pages are
// touched.
inline uint64_t contextSpillFillBytes(const std::string &modelPath) {
  MmapFile file(modelPath);
  if (!file.valid()) {
    QNN_WARN("[spill-fill] cannot read %s to check its metadata",
             modelPath.c_str());
    return 0;
  }
  return contextSpillFillBytes(file.data, file.size);
}

// Largest declared spill-fill size (bytes) among `modelPaths`, logging each
// one that declares a value. 0 means none of them does.
inline uint64_t declaredSpillFillBytes(
    const std::vector<std::string> &modelPaths) {
  uint64_t maxBytes = 0;
  for (const std::string &path : modelPaths) {
    if (path.empty()) continue;
    const uint64_t bytes = contextSpillFillBytes(path);
    if (bytes == 0) continue;
    QNN_INFO("[spill-fill] %s declares %llu bytes (%.1f MB)", path.c_str(),
             (unsigned long long)bytes, bytes / (1024.0 * 1024.0));
    if (bytes > maxBytes) maxBytes = bytes;
  }
  return maxBytes;
}

// createModel() + initializeApp(), optionally registering the context into an
// HTP multi-context spill-fill group. The backend refuses a context whose
// declared requirement is larger than the group's shared buffer; that is a
// failed memory optimization, not a reason to fail the load, so the context is
// retried without group sharing (its own correctly sized buffer).
// `groupUsed` reports whether the returned context actually registered.
inline std::unique_ptr<QnnModel> createModelWithOptionalGroup(
    const std::string &modelPath, const std::string &modelName,
    uint64_t maxSpillFillBytes, Qnn_ContextHandle_t groupHead,
    const uint8_t *buffer = nullptr, uint64_t bufferSize = 0,
    bool *groupUsed = nullptr) {
  if (groupUsed != nullptr) *groupUsed = false;

  if (maxSpillFillBytes > 0) {
    auto grouped = createModel(modelPath, modelName);
    if (grouped) {
      grouped->setSpillFillGroup(maxSpillFillBytes, groupHead);
      if (initializeApp(modelName, grouped, buffer, bufferSize) ==
          EXIT_SUCCESS) {
        if (groupUsed != nullptr) *groupUsed = true;
        return grouped;
      }
      QNN_WARN(
          "[spill-fill] %s refused the shared %llu-byte spill-fill buffer; "
          "retrying it without group sharing",
          modelName.c_str(), (unsigned long long)maxSpillFillBytes);
      grouped.reset();
    }
  }

  auto model = createModel(modelPath, modelName);
  if (!model) return nullptr;
  if (initializeApp(modelName, model, buffer, bufferSize) != EXIT_SUCCESS) {
    return nullptr;
  }
  return model;
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
