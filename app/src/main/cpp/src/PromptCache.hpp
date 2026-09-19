#ifndef PROMPTCACHE_HPP
#define PROMPTCACHE_HPP

#include <cstdint>
#include <cstring>
#include <fstream>
#include <string>

#include "Sha256.hpp"

// Persistent per-prompt CLIP cache lives on disk under
// {modelDir}/cache/prompt_<sha32>.bin. Positive and negative prompts are
// looked up independently: a single side hit still skips half the CLIP work.
// A prompt that uses a textual-inversion embedding is excluded (its CLIP
// output depends on embedding state we don't want baked into a stable file).
namespace prompt_cache {

constexpr char kMagic[4] = {'P', 'C', 'L', 'P'};
constexpr uint32_t kVersion = 1;
constexpr uint32_t kModeSd15 = 0;
constexpr uint32_t kModeSdxl = 1;
// Anima (Qwen text encoder): 512-token context, no pooled output. Its own mode
// keeps its files from ever colliding with a 77-token CLIP cache entry.
constexpr uint32_t kModeAnima = 2;
// SDXL chunked prompts: the cached tensors are seq_len-wide, so a package that
// grows its context via .patch and one that ships a fixed multi-chunk context
// must not read each other's files. kModeSdxl stays reserved for the original
// single-chunk layout.
constexpr uint32_t kModeSdxlChunked = 3;
constexpr uint32_t kModeSdxlFixedChunks = 4;

struct Header {
  char magic[4];
  uint32_t version;
  uint32_t mode;
  uint32_t seq_len;
  uint32_t hidden_dim;
  uint32_t pooled_dim;
};

inline std::string cachePath(const std::string &cache_dir,
                             const std::string &prompt_text) {
  if (cache_dir.empty()) return "";
  return cache_dir + "/prompt_" + Sha256::hashHex(prompt_text, 32) + ".bin";
}

// Reads {hidden_states[, pooled]} from disk for `prompt_text`. Returns true
// on a valid hit; the destination buffers must already be sized for the
// expected layout. The file is silently treated as miss when missing, wrong
// magic/version, or dimension mismatch.
//   - hidden_dst: seq_len * hidden_dim float32
//   - pooled_dst: pooled_dim float32 (nullptr for SD1.5)
inline bool load(const std::string &cache_dir, const std::string &prompt_text,
                 uint32_t mode, uint32_t seq_len, uint32_t hidden_dim,
                 uint32_t pooled_dim, float *hidden_dst, float *pooled_dst) {
  std::string path = cachePath(cache_dir, prompt_text);
  if (path.empty()) return false;
  std::ifstream ifs(path, std::ios::binary);
  if (!ifs) return false;

  Header h{};
  ifs.read(reinterpret_cast<char *>(&h), sizeof(h));
  if (!ifs) return false;
  if (std::memcmp(h.magic, kMagic, 4) != 0) return false;
  if (h.version != kVersion) return false;
  if (h.mode != mode) return false;
  if (h.seq_len != seq_len) return false;
  if (h.hidden_dim != hidden_dim) return false;
  if (h.pooled_dim != pooled_dim) return false;

  size_t hidden_bytes = size_t(h.seq_len) * h.hidden_dim * sizeof(float);
  ifs.read(reinterpret_cast<char *>(hidden_dst), hidden_bytes);
  if (!ifs) return false;
  if (pooled_dim > 0) {
    if (!pooled_dst) return false;
    size_t pooled_bytes = size_t(pooled_dim) * sizeof(float);
    ifs.read(reinterpret_cast<char *>(pooled_dst), pooled_bytes);
    if (!ifs) return false;
  }
  return true;
}

inline void save(const std::string &cache_dir, const std::string &prompt_text,
                 uint32_t mode, uint32_t seq_len, uint32_t hidden_dim,
                 uint32_t pooled_dim, const float *hidden_src,
                 const float *pooled_src) {
  std::string path = cachePath(cache_dir, prompt_text);
  if (path.empty()) return;
  std::ofstream ofs(path, std::ios::binary | std::ios::trunc);
  if (!ofs) return;

  Header h{};
  std::memcpy(h.magic, kMagic, 4);
  h.version = kVersion;
  h.mode = mode;
  h.seq_len = seq_len;
  h.hidden_dim = hidden_dim;
  h.pooled_dim = pooled_dim;
  ofs.write(reinterpret_cast<const char *>(&h), sizeof(h));
  ofs.write(reinterpret_cast<const char *>(hidden_src),
            size_t(h.seq_len) * h.hidden_dim * sizeof(float));
  if (pooled_dim > 0 && pooled_src) {
    ofs.write(reinterpret_cast<const char *>(pooled_src),
              size_t(pooled_dim) * sizeof(float));
  }
}

}  // namespace prompt_cache

#endif  // PROMPTCACHE_HPP
