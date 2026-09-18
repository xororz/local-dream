#ifndef TEXTENCODER_HPP
#define TEXTENCODER_HPP

#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include "Config.hpp"
#include "FloatConversion.hpp"
#include "Logger.hpp"
#include "MemUtils.hpp"
#include "PromptProcessor.hpp"
#include "SDUtils.hpp"
#include "tokenizers_cpp.h"

// Count the UTF-16 code units in the first byteOffset bytes of a UTF-8 string.
// The prompt is a Kotlin String on the client, indexed in UTF-16 units, so a
// raw byte offset must be converted before it can address a character there.
inline int utf8ByteOffsetToUtf16(const std::string &s, size_t byteOffset) {
  int units = 0;
  size_t i = 0;
  size_t limit = std::min(byteOffset, s.size());
  while (i < limit) {
    unsigned char c = static_cast<unsigned char>(s[i]);
    int len;
    if (c < 0x80)
      len = 1;
    else if ((c >> 5) == 0x6)
      len = 2;
    else if ((c >> 4) == 0xE)
      len = 3;
    else if ((c >> 3) == 0x1E)
      len = 4;
    else
      len = 1;  // invalid lead byte; advance one to stay in sync
    units += (len == 4) ? 2 : 1;  // astral planes need a surrogate pair
    i += len;
  }
  return units;
}

// Byte length of the longest character-aligned prefix of `text` whose CLIP
// encoding fits within `budget` tokens. Lets the overflow boundary land on a
// sub-word edge inside a long token (e.g. a run of digits) instead of greying
// the whole token. BPE token counts are non-decreasing as the prefix grows, so
// a binary search over character boundaries is valid.
inline size_t prefixBytesWithinBudget(const std::string &text, int budget,
                                      tokenizers::Tokenizer *tok) {
  if (budget <= 0 || text.empty()) return 0;
  std::vector<size_t> bounds;  // byte offset of each character start, plus end
  for (size_t i = 0; i < text.size();) {
    bounds.push_back(i);
    unsigned char c = static_cast<unsigned char>(text[i]);
    int len;
    if (c < 0x80)
      len = 1;
    else if ((c >> 5) == 0x6)
      len = 2;
    else if ((c >> 4) == 0xE)
      len = 3;
    else if ((c >> 3) == 0x1E)
      len = 4;
    else
      len = 1;
    i += len;
  }
  bounds.push_back(text.size());

  int lo = 0, hi = static_cast<int>(bounds.size()) - 1, best = 0;
  while (lo <= hi) {
    int mid = (lo + hi) / 2;
    int n = static_cast<int>(tok->Encode(text.substr(0, bounds[mid])).size());
    if (n <= budget) {
      best = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return bounds[best];
}

struct ProcessedPrompt {
  int eos_position = 76;
  std::vector<int> ids;                    // CLIP (pad 49407)
  std::vector<int> ids_2;                  // SDXL encoder 2 (pad 0)
  std::vector<float> weighted_embeddings;  // 77*768 (Anima: 512*1024 qwen emb)
  std::vector<float> weighted_embeddings_2;  // SDXL: 77*1280
  // Anima merged text encoder extra inputs (512-length):
  std::vector<float> qwen_mask;  // 1=real qwen token, 0=pad (cross-attn mask)
  std::vector<int> t5_ids;       // T5 token ids (adapter query grid)
  std::vector<float> t5_mask;  // 1=real t5 token, 0=pad (self-attn + zero rows)
};

struct ProcessedPromptPair {
  std::vector<int> eos_positions;  // Negative chunks followed by positive chunks.
  std::vector<int> ids;                      // negative + positive (2*77)
  std::vector<float> negative_embeddings;    // 77*768
  std::vector<float> positive_embeddings;    // 77*768
  std::vector<float> negative_embeddings_2;  // SDXL (77*1280)
  std::vector<float> positive_embeddings_2;  // SDXL (77*1280)
  // Anima (512-length each):
  std::vector<float> negative_qwen_mask, positive_qwen_mask;
  std::vector<int> negative_t5_ids, positive_t5_ids;
  std::vector<float> negative_t5_mask, positive_t5_mask;
};

// Result of token counting for the /tokenize endpoint.
struct TokenizeInfo {
  int count = 2;  // BOS + EOS
  // UTF-16 index of the first character whose tokens exceed the limit, or
  // -1 when the prompt fits.
  int overflow_offset = -1;
};

// Everything between raw prompt text and CLIP-ready input embeddings: the
// tokenizer, textual-inversion embeddings, and the precomputed token/position
// embedding tables (one set for SD1.5 / SDXL encoder 1, a second set for SDXL
// encoder 2).
class TextEncoder {
 public:
  explicit TextEncoder(bool sdxl, bool anima = false, int max_chunks = 1,
                       bool fixed_chunks = false)
      : max_chunks_(max_chunks), fixed_chunks_(fixed_chunks), sdxl_(sdxl), anima_(anima), promptProcessor_(sdxl, max_chunks != 1) {}

  int contextLength(const std::string &positive, const std::string &negative = "") {
    if (anima_) return anima_text_seq_len;
    const int content = std::max(tokenizeInfo(positive).count, tokenizeInfo(negative).count) - 2;
    const int chunks = std::max(1, (content + 74) / 75);
    return 77 * (fixed_chunks_ ? max_chunks_ : max_chunks_ == 0 ? chunks : std::min(chunks, max_chunks_));
  }

  int max_chunks_;
  bool fixed_chunks_;

  bool isSdxl() const { return sdxl_; }
  bool isAnima() const { return anima_; }
  tokenizers::Tokenizer *tokenizer() { return tokenizer_.get(); }

  void loadTokenizer(const std::string &path) {
    auto blob = LoadBytesFromFile(path);
    tokenizer_ = tokenizers::Tokenizer::FromBlobJSON(blob);
    if (!tokenizer_) throw std::runtime_error("Tokenizer creation failed.");
  }

  // Anima only: the T5 tokenizer that drives the LLM adapter's query grid.
  void loadT5Tokenizer(const std::string &path) {
    auto blob = LoadBytesFromFile(path);
    t5_tokenizer_ = tokenizers::Tokenizer::FromBlobJSON(blob);
    if (!t5_tokenizer_)
      throw std::runtime_error("T5 tokenizer creation failed.");
  }

  // Loads pos_emb/token_emb (and the encoder-2 tables for SDXL) from the
  // model directory. SD1.5 token_emb may still be legacy FP32 (detected by
  // file size); SDXL tables are always FP16.
  void loadEmbeddingTables(const std::filesystem::path &dir) {
    // Anima/Qwen uses RoPE inside the transformer -> there is no positional
    // table; only the fp16 token-embedding table (vocab x 1024) is needed.
    if (anima_) {
      loadTokenEmb(dir / "token_emb.bin", token_emb_, /*force_fp16=*/true);
      return;
    }
    loadPosEmb(dir / "pos_emb.bin", pos_emb_);
    loadTokenEmb(dir / "token_emb.bin", token_emb_, /*force_fp16=*/sdxl_);
    if (sdxl_) {
      loadPosEmb(dir / "pos_emb_2.bin", pos_emb_2_);
      loadTokenEmb(dir / "token_emb_2.bin", token_emb_2_, /*force_fp16=*/true);
    }
  }

  void loadTextualInversions(const std::string &embeddings_dir) {
    promptProcessor_.loadEmbeddings(embeddings_dir, sdxl_);
  }

  size_t embeddingCount() const { return promptProcessor_.getEmbeddingCount(); }

  // True if any token of `prompt_text` resolves to a textual-inversion
  // embedding. Used to opt that side out of the persistent prompt cache.
  bool promptHasEmbedding(const std::string &prompt_text) {
    auto tokens = promptProcessor_.process(prompt_text);
    for (const auto &t : tokens) {
      if (t.is_embedding) return true;
    }
    return false;
  }

  std::string decode(const std::vector<int> &ids) {
    return tokenizer_->Decode(ids);
  }

  ProcessedPrompt processWeightedPrompt(const std::string &prompt_text,
                                        int max_len = 77, int chunk = 0) {
    ProcessedPrompt result;
    const int dim1 = text_embedding_size, dim2 = text_embedding_size_2;
    std::vector<float> embeddings(max_len * dim1, 0.0f), embeddings_2;
    if (sdxl_) embeddings_2.assign(max_len * dim2, 0.0f);
    std::vector<int> ids(max_len, 49407);
    std::vector<float> weights(max_len, 1.0f);
    ids[0] = 49406;
    int current_pos = 1, content_pos = 0, segment_start = 0;
    for (const auto &token : promptProcessor_.process(prompt_text)) {
      if ((sdxl_ || max_chunks_ != 1) && token.text == "BREAK" && !token.is_embedding) {
        content_pos = segment_start + std::max(1, (content_pos - segment_start + 74) / 75) * 75;
        segment_start = content_pos;
        continue;
      }
      if (current_pos >= max_len - 1) break;
      const auto token_ids = token.is_embedding ? std::vector<int>() : tokenizer_->Encode(token.text);
      const int count = token.is_embedding
          ? (token.embedding_data.empty() ? token.embedding_data_2.size() / dim2
                                          : token.embedding_data.size() / dim1)
          : token_ids.size();
      for (int i = 0; i < count; ++i, ++content_pos) {
        if (content_pos < chunk * 75) continue;
        if (content_pos >= chunk * 75 + max_len - 2) break;
        ids[current_pos] = token.is_embedding ? 49407 : token_ids[i];
        weights[sdxl_ ? current_pos : current_pos - 1] = token.weight;
        if (token.is_embedding) {
          for (int j = 0; j < dim1 && !token.embedding_data.empty(); ++j)
            embeddings[current_pos * dim1 + j] = token.embedding_data[i * dim1 + j] * token.weight;
          for (int j = 0; j < dim2 && sdxl_ && !token.embedding_data_2.empty(); ++j)
            embeddings_2[current_pos * dim2 + j] = token.embedding_data_2[i * dim2 + j] * token.weight;
        }
        ++current_pos;
      }
    }
    result.ids = ids;
    result.eos_position = current_pos;
    if (sdxl_) {
      result.ids_2 = ids;
      std::fill(result.ids_2.begin() + current_pos + 1, result.ids_2.end(), 0);
    }
    if (!token_emb_.empty() && !pos_emb_.empty())
      applyTokenAndPosEmb(ids, weights, token_emb_, pos_emb_, dim1, max_len, embeddings);
    if (sdxl_ && !token_emb_2_.empty() && !pos_emb_2_.empty())
      applyTokenAndPosEmb(result.ids_2, weights, token_emb_2_, pos_emb_2_, dim2, max_len, embeddings_2);
    result.weighted_embeddings = std::move(embeddings);
    result.weighted_embeddings_2 = std::move(embeddings_2);
    return result;
  }

  // Anima/Qwen: BPE ids (no BOS/EOS), padded to qwen_len with the Qwen pad id,
  // then looked up in the fp16 token-embedding table. No position embedding
  // (the Qwen transformer applies RoPE internally). The result's
  // weighted_embeddings is the [qwen_len, 1024] input the merged clip.bin
  // (Qwen + adapter, QNN) consumes.
  //
  // Prompt weighting IS supported: the prompt is parsed by promptProcessor_ so
  // "(word:1.2)" / "[word]" syntax is stripped (neither tokenizer ever sees the
  // markers) and each token's weight scales its Qwen input_embedding row. The
  // T5 side is discrete adapter-query ids, so it gets the same cleaned text but
  // no weighting. Textual-inversion embeddings are NOT supported (Anima's Qwen
  // space differs from CLIP's); an embedding trigger degrades to its literal
  // text via promptProcessor_'s token.text.
  ProcessedPrompt processAnimaPrompt(const std::string &prompt_text,
                                     int /*max_len*/) {
    ProcessedPrompt result;
    const int dim = anima_text_embedding_size;  // 1024
    // Qwen input and the T5/adapter context are independent fixed lengths
    // (currently both 512, kept separate in case an export changes one).
    const int qwen_len = anima_qwen_seq_len;  // 512
    const int t5_len = anima_text_seq_len;    // 512

    auto tokens = promptProcessor_.process(prompt_text);

    // ---- Qwen: weighted input_embedding (token_emb lookup) + attn mask ----
    std::vector<int> ids;
    std::vector<float> weights;
    ids.reserve(qwen_len);
    weights.reserve(qwen_len);
    for (const auto &token : tokens) {
      if ((int)ids.size() >= qwen_len) break;
      for (int id : tokenizer_->Encode(token.text)) {
        if ((int)ids.size() >= qwen_len) break;
        ids.push_back(id);
        weights.push_back(token.weight);
      }
    }

    std::vector<float> embeddings((size_t)qwen_len * dim, 0.0f);
    std::vector<int> padded_ids(qwen_len, kQwenPadId);
    std::vector<float> qwen_mask(qwen_len, 0.0f);
    for (int pos = 0; pos < qwen_len; ++pos) {
      bool real = pos < (int)ids.size();
      int id = real ? ids[pos] : kQwenPadId;
      float w = real ? weights[pos] : 1.0f;
      padded_ids[pos] = id;
      qwen_mask[pos] = real ? 1.0f : 0.0f;
      const size_t base = (size_t)id * dim;
      for (int j = 0; j < dim; ++j)
        embeddings[(size_t)pos * dim + j] =
            fp16_to_fp32(token_emb_[base + j]) * w;
    }

    // ---- T5: adapter query ids + self-attn / row mask (cleaned, unweighted)
    // -- Mirrors AnimaTokenizer.tokenize: T5 ids of the weight-stripped text,
    // ensure trailing EOS(=1), pad with 0. The merged graph embeds t5_ids.
    std::vector<int> t5;
    if (t5_tokenizer_) {
      for (const auto &token : tokens)
        for (int id : t5_tokenizer_->Encode(token.text)) t5.push_back(id);
    }
    if (t5.empty() || t5.back() != kT5EosId) t5.push_back(kT5EosId);
    if ((int)t5.size() > t5_len) t5.resize(t5_len);
    std::vector<int> t5_ids(t5_len, kT5PadId);
    std::vector<float> t5_mask(t5_len, 0.0f);
    for (int pos = 0; pos < (int)t5.size(); ++pos) {
      t5_ids[pos] = t5[pos];
      t5_mask[pos] = 1.0f;
    }

    result.ids = std::move(padded_ids);
    result.weighted_embeddings = std::move(embeddings);
    result.qwen_mask = std::move(qwen_mask);
    result.t5_ids = std::move(t5_ids);
    result.t5_mask = std::move(t5_mask);
    return result;
  }

  ProcessedPromptPair processPromptPair(const std::string &positive,
                                        const std::string &negative,
                                        int max_len = 77) {
    ProcessedPromptPair result;

    const int chunks = anima_ ? 1 : max_len / 77;
    result.ids.resize(2 * max_len);
    result.eos_positions.resize(2 * chunks);
    for (int chunk = 0; chunk < chunks; ++chunk) {
      const int clip_len = anima_ ? max_len : 77;
      auto pos = anima_ ? processAnimaPrompt(positive, max_len)
                        : processWeightedPrompt(positive, clip_len, chunk);
      auto neg = anima_ ? processAnimaPrompt(negative, max_len)
                        : processWeightedPrompt(negative, clip_len, chunk);
      result.eos_positions[chunk] = neg.eos_position;
      result.eos_positions[chunks + chunk] = pos.eos_position;
      std::copy(neg.ids.begin(), neg.ids.end(), result.ids.begin() + chunk * clip_len);
      std::copy(pos.ids.begin(), pos.ids.end(), result.ids.begin() + max_len + chunk * clip_len);
      result.negative_embeddings.insert(
          result.negative_embeddings.end(), neg.weighted_embeddings.begin(),
          neg.weighted_embeddings.end());
      result.positive_embeddings.insert(
          result.positive_embeddings.end(), pos.weighted_embeddings.begin(),
          pos.weighted_embeddings.end());
      result.negative_embeddings_2.insert(
          result.negative_embeddings_2.end(), neg.weighted_embeddings_2.begin(),
          neg.weighted_embeddings_2.end());
      result.positive_embeddings_2.insert(
          result.positive_embeddings_2.end(), pos.weighted_embeddings_2.begin(),
          pos.weighted_embeddings_2.end());
      if (anima_) {
        result.negative_qwen_mask = std::move(neg.qwen_mask);
        result.positive_qwen_mask = std::move(pos.qwen_mask);
        result.negative_t5_ids = std::move(neg.t5_ids);
        result.positive_t5_ids = std::move(pos.t5_ids);
        result.negative_t5_mask = std::move(neg.t5_mask);
        result.positive_t5_mask = std::move(pos.t5_mask);
      }
    }

    return result;
  }

  // Token counting for the /tokenize endpoint. For a text token the overflow
  // boundary is found at the sub-word edge inside it; an embedding is an
  // indivisible vector block, so it is flagged from its first character.
  TokenizeInfo tokenizeInfo(const std::string &text, int max_len = 77) {
    TokenizeInfo info;
    if (!tokenizer_) return info;

    // Anima: the UNet context length follows the T5 tokenization (the LLM
    // adapter's query grid), NOT the Qwen embedding side, so the prompt budget
    // is measured with the T5 tokenizer. processAnimaPrompt parses the same
    // weighting syntax (so the count reflects the weight-stripped text, not the
    // literal "(...)" markers), then appends a trailing EOS — one slot is
    // reserved for it (count = T5 tokens + 1, budget = max_len - 1). No BOS, no
    // TI embeddings. Falls back to the Qwen tokenizer only if T5 is missing.
    if (anima_) {
      tokenizers::Tokenizer *tok =
          t5_tokenizer_ ? t5_tokenizer_.get() : tokenizer_.get();
      const int budget = max_len - 1;
      auto tokens = promptProcessor_.process(text);
      int content = 0;
      for (const auto &token : tokens) {
        int tc = static_cast<int>(tok->Encode(token.text).size());
        if (info.overflow_offset < 0 && content + tc > budget) {
          size_t prefix =
              prefixBytesWithinBudget(token.text, budget - content, tok);
          size_t byte_off = (prefix < token.char_src.size())
                                ? token.char_src[prefix]
                                : token.source_start;
          info.overflow_offset = utf8ByteOffsetToUtf16(text, byte_off);
        }
        content += tc;
      }
      info.count = content + 1;  // + trailing EOS
      return info;
    }

    // Tokens that fit alongside the implicit BOS/EOS markers.
    const int budget = max_len - 2;
    if (text.empty()) return info;

    auto tokens = promptProcessor_.process(text);
    const int dim1 = 768;
    const int dim2 = text_embedding_size_2;
    int content = 0, segment_start = 0;
    for (const auto &token : tokens) {
      if ((sdxl_ || max_chunks_ != 1) && token.text == "BREAK" && !token.is_embedding) {
        content = segment_start + std::max(1, (content - segment_start + 74) / 75) * 75;
        segment_start = content;
        continue;
      }
      int tc = 0;
      if (token.is_embedding) {
        if (!token.embedding_data.empty())
          tc = token.embedding_data.size() / dim1;
        else if (sdxl_ && !token.embedding_data_2.empty())
          tc = token.embedding_data_2.size() / dim2;
      } else {
        tc = (int)tokenizer_->Encode(token.text).size();
      }
      if (info.overflow_offset < 0 && content + tc > budget) {
        size_t byte_off = token.source_start;
        if (!token.is_embedding) {
          // Boundary inside a text token: find the sub-word edge in the
          // cleaned text, then map it back to the user's prompt so the grey
          // region lines up with the actual characters they typed.
          size_t prefix = prefixBytesWithinBudget(token.text, budget - content,
                                                  tokenizer_.get());
          byte_off = (prefix < token.char_src.size()) ? token.char_src[prefix]
                                                      : token.source_start;
        }
        info.overflow_offset = utf8ByteOffsetToUtf16(text, byte_off);
      }
      content += tc;
    }
    info.count = (sdxl_ || max_chunks_ != 1) ? segment_start + std::max(1, content - segment_start) + 2
                       : content + 2;  // Include an empty trailing BREAK chunk.
    return info;
  }

 private:
  // Adds the (weighted) token-table rows plus position embeddings to the
  // slots that were not already filled by a textual-inversion embedding.
  static void applyTokenAndPosEmb(const std::vector<int> &ids,
                                  const std::vector<float> &weights,
                                  const TokenEmbTable &token_emb,
                                  const std::vector<float> &pos_emb, int dim,
                                  int max_len, std::vector<float> &embeddings) {
    for (int i = 0; i < max_len; i++) {
      int token_id = ids[i];
      float weight = (i < (int)weights.size()) ? weights[i] : 1.0f;

      bool has_emb = false;
      for (int j = 0; j < dim; j++) {
        if (embeddings[i * dim + j] != 0.0f) {
          has_emb = true;
          break;
        }
      }

      if (!has_emb) {
        for (int j = 0; j < dim; j++) {
          float token_val = fp16_to_fp32(token_emb[token_id * dim + j]);
          embeddings[i * dim + j] = token_val * weight + pos_emb[i * dim + j];
        }
      } else {
        for (int j = 0; j < dim; j++) {
          embeddings[i * dim + j] += pos_emb[i * dim + j];
        }
      }
    }
  }

  static void loadPosEmb(const std::filesystem::path &posEmbPath,
                         std::vector<float> &dst) {
    std::ifstream posFile(posEmbPath, std::ios::binary);
    posFile.seekg(0, std::ios::end);
    size_t posSize = posFile.tellg() / sizeof(float);
    posFile.seekg(0, std::ios::beg);
    dst.resize(posSize);
    posFile.read(reinterpret_cast<char *>(dst.data()), posSize * sizeof(float));
    posFile.close();
    QNN_INFO("Loaded %s: %zu floats", posEmbPath.filename().string().c_str(),
             posSize);
  }

  static void loadTokenEmb(const std::filesystem::path &tokenEmbPath,
                           TokenEmbTable &dst, bool force_fp16) {
    std::ifstream tokenFile(tokenEmbPath, std::ios::binary);
    tokenFile.seekg(0, std::ios::end);
    size_t fileSize = tokenFile.tellg();
    tokenFile.seekg(0, std::ios::beg);

    const size_t SIZE_THRESHOLD = 100 * 1024 * 1024;  // 100MB
    if (!force_fp16 && fileSize > SIZE_THRESHOLD) {
      // FP32 on disk: narrow to FP16 in an owned buffer (cannot be mapped as
      // uint16 directly). This branch is the legacy SD1.5 large-table path.
      size_t tokenSize = fileSize / sizeof(float);
      std::vector<float> tempBuffer(tokenSize);
      tokenFile.read(reinterpret_cast<char *>(tempBuffer.data()), fileSize);
      std::vector<uint16_t> converted(tokenSize);
      for (size_t i = 0; i < tokenSize; i++) {
        converted[i] = fp32_to_fp16(tempBuffer[i]);
      }
      dst.setOwned(std::move(converted));
      QNN_INFO("Loaded %s: %zu floats (converted FP32->FP16)",
               tokenEmbPath.filename().string().c_str(), tokenSize);
      return;
    }

    // FP16 on disk: map read-only and look up lazily. Token lookups are
    // sparse, so MADV_RANDOM avoids pointless readahead of untouched rows.
    tokenFile.close();
    size_t tokenSize = fileSize / sizeof(uint16_t);
    int fd = open(tokenEmbPath.c_str(), O_RDONLY);
    void *mapped = MAP_FAILED;
    if (fd >= 0) {
      mapped = mmap(nullptr, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
      close(fd);
    }
    if (mapped != MAP_FAILED) {
      madvise(mapped, fileSize, MADV_RANDOM);
      dst.setMapped(mapped, fileSize);
      QNN_INFO("Mapped %s: %zu elements (FP16, mmap)",
               tokenEmbPath.filename().string().c_str(), tokenSize);
      return;
    }

    // Fallback: read into an owned buffer if the mapping failed.
    std::ifstream fallback(tokenEmbPath, std::ios::binary);
    std::vector<uint16_t> owned(tokenSize);
    fallback.read(reinterpret_cast<char *>(owned.data()), fileSize);
    dst.setOwned(std::move(owned));
    QNN_INFO("Loaded %s: %zu elements (FP16)",
             tokenEmbPath.filename().string().c_str(), tokenSize);
  }

  // Qwen pad token id (AnimaTokenizer.QWEN_PAD_ID) used to fill the 512-token
  // context past the prompt's real tokens. T5 uses EOS=1, pad=0.
  static constexpr int kQwenPadId = 151643;
  static constexpr int kT5EosId = 1;
  static constexpr int kT5PadId = 0;

  bool sdxl_ = false;
  bool anima_ = false;
  std::shared_ptr<tokenizers::Tokenizer> tokenizer_;
  std::shared_ptr<tokenizers::Tokenizer> t5_tokenizer_;
  PromptProcessor promptProcessor_;
  std::vector<float> pos_emb_;
  TokenEmbTable token_emb_;  // FP16, mmap-backed when stored as FP16 on disk
  std::vector<float> pos_emb_2_;
  TokenEmbTable token_emb_2_;  // SDXL encoder 2 token embeddings (FP16)
};

#endif  // TEXTENCODER_HPP
