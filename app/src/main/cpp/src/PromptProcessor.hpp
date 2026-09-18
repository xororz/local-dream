#include <algorithm>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <map>
#include <memory>
#include <sstream>
#include <stack>
#include <stdexcept>
#include <string>
#include <vector>

#include "Logger.hpp"
#include "SafeTensorReader.hpp"

struct PromptToken {
  std::string text;
  float weight;
  bool is_embedding;
  std::vector<float> embedding_data;    // 768-dim (SD1.5 / SDXL encoder 1)
  std::vector<float> embedding_data_2;  // 1280-dim (SDXL encoder 2)
  // Byte offset of this token's text in the original prompt. Lets callers map
  // a token-count limit back to a character position (e.g. greying overflow).
  size_t source_start = 0;
  // Original-prompt byte offset of each byte of `text` (size == text.size()).
  // `text` is the cleaned form (whitespace collapsed, escapes resolved), so a
  // sub-word offset into it must be translated through this map to address the
  // matching character in the user's untouched prompt.
  std::vector<size_t> char_src;
};

class PromptProcessor {
 private:
  std::map<std::string, std::vector<float>> embeddings_;    // last-dim 768
  std::map<std::string, std::vector<float>> embeddings_2_;  // last-dim 1280
  std::string embeddings_dir_;
  bool sdxl_mode_ = false;
  bool chunking_ = false;

  static std::string toLowerCase(const std::string &str) {
    std::string result = str;
    std::transform(result.begin(), result.end(), result.begin(),
                   [](unsigned char c) { return std::tolower(c); });
    return result;
  }

  static std::string trim(const std::string &str) {
    size_t start = str.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) return "";
    size_t end = str.find_last_not_of(" \t\r\n");
    return str.substr(start, end - start + 1);
  }

  struct TokenNode {
    std::string text;
    float weight;
    std::vector<TokenNode> children;
    bool is_group;
    size_t source_start;  // byte offset of text in the original prompt
    std::vector<size_t>
        char_src;  // per-byte original offsets (see PromptToken)

    TokenNode() : weight(1.0f), is_group(false), source_start(0) {}
  };

  TokenNode parsePromptTree(const std::string &prompt) {
    TokenNode root;
    root.is_group = true;
    root.weight = 1.0f;
    std::stack<TokenNode *> node_stack;
    node_stack.push(&root);

    std::string current_text;
    // current_src[k] is the original-prompt byte offset of current_text[k], so
    // the cleaned text stays mappable back to the user's untouched prompt.
    std::vector<size_t> current_src;
    size_t i = 0;

    auto is_ws = [](char ch) {
      return ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n';
    };

    // Emit a leaf node for current_text[b, e), carrying the matching slice of
    // current_src. Callers pass the trimmed bounds so the node's text and its
    // source map stay aligned.
    auto emit_text = [&](size_t b, size_t e, float weight) {
      while (b < e && is_ws(current_text[b])) b++;
      while (e > b && is_ws(current_text[e - 1])) e--;
      if (b >= e) return;
      TokenNode text_node;
      text_node.text = current_text.substr(b, e - b);
      text_node.weight = weight;
      text_node.is_group = false;
      text_node.source_start = current_src[b];
      text_node.char_src.assign(current_src.begin() + b,
                                current_src.begin() + e);
      node_stack.top()->children.push_back(text_node);
    };

    auto flush_text = [&](float weight) {
      if (!current_text.empty()) emit_text(0, current_text.size(), weight);
      current_text.clear();
      current_src.clear();
    };

    while (i < prompt.length()) {
      char c = prompt[i];

      if (c == '\\' && i + 1 < prompt.length()) {
        char next = prompt[i + 1];
        if (next == '(' || next == ')' || next == '[' || next == ']' ||
            next == '\\' || next == ',' || next == ':') {
          current_text += next;
          current_src.push_back(i);  // map the escaped char to the backslash
          i += 2;
          continue;
        }
      }

      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
        if (!current_text.empty() && i + 1 < prompt.length()) {
          char next = prompt[i + 1];
          if (next != '(' && next != ')' && next != '[' && next != ']' &&
              next != ',' && next != ' ' && next != '\t') {
            current_text += ' ';
            current_src.push_back(i);
          }
        }
        i++;
        continue;
      }

      if (c == '(') {
        flush_text(1.0f);

        TokenNode *parent = node_stack.top();
        parent->children.push_back(TokenNode());
        TokenNode *new_node = &parent->children.back();
        new_node->is_group = true;
        new_node->weight = 1.1f;
        node_stack.push(new_node);
        i++;

      } else if (c == ')') {
        if (!current_text.empty()) {
          size_t colon_pos = current_text.rfind(':');
          bool has_weight = false;

          if (colon_pos != std::string::npos && node_stack.size() > 1 &&
              node_stack.top()->is_group) {
            std::string weight_str = trim(current_text.substr(colon_pos + 1));

            try {
              float weight = std::stof(weight_str);
              emit_text(0, colon_pos, weight);
              has_weight = true;
              current_text.clear();
              current_src.clear();
            } catch (...) {
              // failed to parse weight
            }
          }

          if (!has_weight) {
            flush_text(1.0f);
          }
        }

        if (node_stack.size() > 1) {
          node_stack.pop();
        }
        i++;

      } else if (c == '[') {
        flush_text(1.0f);

        TokenNode *parent = node_stack.top();
        parent->children.push_back(TokenNode());
        TokenNode *new_node = &parent->children.back();
        new_node->is_group = true;
        new_node->weight = 0.9f;
        node_stack.push(new_node);
        i++;

      } else if (c == ']') {
        flush_text(1.0f);

        if (node_stack.size() > 1) {
          node_stack.pop();
        }
        i++;

      } else if (c == ',') {
        flush_text(1.0f);
        TokenNode comma_node;
        comma_node.text = ",";
        comma_node.weight = 1.0f;
        comma_node.is_group = false;
        comma_node.source_start = i;
        comma_node.char_src.push_back(i);
        node_stack.top()->children.push_back(comma_node);
        i++;

      } else {
        current_text += c;
        current_src.push_back(i);
        i++;
      }
    }

    flush_text(1.0f);

    return root;
  }

  void flattenTree(const TokenNode &node, float parent_weight,
                   std::vector<PromptToken> &tokens) {
    float current_weight = parent_weight * node.weight;

    if (node.is_group) {
      for (const auto &child : node.children) {
        flattenTree(child, current_weight, tokens);
      }
    } else {
      if (!node.text.empty()) {
        std::string text_lower = toLowerCase(node.text);

        auto it1 = embeddings_.find(text_lower);
        auto it2 = embeddings_2_.find(text_lower);
        bool found1 = it1 != embeddings_.end();
        bool found2 = it2 != embeddings_2_.end();
        // For SDXL the loader keeps both maps in sync; for SD1.5 only
        // embeddings_ is populated. If a token only matches embeddings_2_
        // the data isn't usable in the current mode, so fall through to
        // text tokenization instead of silently dropping the word.
        if (found1 || (sdxl_mode_ && found2)) {
          PromptToken t;
          t.text = node.text;
          t.weight = current_weight;
          t.is_embedding = true;
          if (found1) t.embedding_data = it1->second;
          if (found2) t.embedding_data_2 = it2->second;
          // Embeddings are indivisible vector blocks, so only the start offset
          // is needed (the whole word is flagged on overflow); char_src unused.
          t.source_start = node.source_start;
          tokens.push_back(std::move(t));
        } else {
          tokens.push_back({node.text,
                            current_weight,
                            false,
                            {},
                            {},
                            node.source_start,
                            node.char_src});
        }
      }
    }
  }

 public:
  explicit PromptProcessor(bool sdxl = false, bool chunking = false)
      : sdxl_mode_(sdxl), chunking_(sdxl || chunking) {}

  void loadEmbeddings(const std::string &embeddings_dir, bool sdxl_mode) {
    embeddings_dir_ = embeddings_dir;
    sdxl_mode_ = sdxl_mode;
    embeddings_.clear();
    embeddings_2_.clear();

    if (!std::filesystem::exists(embeddings_dir)) {
      return;
    }

    for (const auto &entry :
         std::filesystem::directory_iterator(embeddings_dir)) {
      if (entry.path().extension() != ".safetensors") continue;

      try {
        SafeTensorReader reader(entry.path().string());
        std::string name = entry.path().stem().string();
        std::string name_lower = toLowerCase(name);

        auto tensor_names = reader.get_tensor_names();

        // Prefer well-known SDXL TI key names (clip_l / clip_g) over blind
        // dimension matching. Falls back to first matching shape if naming
        // is non-standard.
        std::string key_768, key_1280;
        for (const auto &tn : tensor_names) {
          std::string tn_lower = toLowerCase(tn);
          auto shape = reader.get_tensor_shape(tn);
          if (shape.empty()) continue;
          int last_dim = shape.back();
          if (last_dim == 768 && key_768.empty() &&
              (tn_lower.find("clip_l") != std::string::npos ||
               tn_lower.find("emb_params") != std::string::npos ||
               tn_lower.find("string_to_param") != std::string::npos)) {
            key_768 = tn;
          }
          if (last_dim == 1280 && key_1280.empty() &&
              tn_lower.find("clip_g") != std::string::npos) {
            key_1280 = tn;
          }
        }
        for (const auto &tn : tensor_names) {
          auto shape = reader.get_tensor_shape(tn);
          if (shape.empty()) continue;
          int last_dim = shape.back();
          if (last_dim == 768 && key_768.empty()) key_768 = tn;
          if (last_dim == 1280 && key_1280.empty()) key_1280 = tn;
        }

        if (sdxl_mode) {
          // SDXL TIs always carry both 768 (clip_l) and 1280 (clip_g).
          // Files missing either side would pollute the corresponding
          // encoder slot with a pad token, so skip them.
          if (key_768.empty() || key_1280.empty()) {
            QNN_WARN(
                "Skip embedding '%s': SDXL requires both 768 and 1280 dim "
                "tensors",
                name.c_str());
            continue;
          }
          reader.read(key_1280, true);
          embeddings_2_[name_lower] = reader.data;
          reader.read(key_768, true);
          embeddings_[name_lower] = reader.data;
        } else {
          if (key_768.empty()) {
            QNN_WARN("Skip embedding '%s': SD1.5 requires a 768-dim tensor",
                     name.c_str());
            continue;
          }
          reader.read(key_768, true);
          embeddings_[name_lower] = reader.data;
        }
      } catch (const std::exception &e) {
        QNN_WARN("Failed to load embedding %s: %s",
                 entry.path().string().c_str(), e.what());
      }
    }
  }

  std::vector<PromptToken> process(const std::string &prompt) {
    std::vector<PromptToken> tokens;

    TokenNode tree = parsePromptTree(prompt);

    flattenTree(tree, 1.0f, tokens);
    if (!chunking_) return tokens;

    // BREAK is a CLIP chunk boundary, including inside weighted groups.
    auto isWord = [](unsigned char c) {
      return std::isalnum(c) || c == '_' || c >= 0x80;
    };
    std::vector<PromptToken> chunks;
    for (const auto &token : tokens) {
      if (token.is_embedding) {
        chunks.push_back(token);
        continue;
      }
      size_t start = 0;
      auto append = [&](size_t begin, size_t end) {
        if (begin == end) return;
        auto part = token;
        part.text = token.text.substr(begin, end - begin);
        part.char_src.assign(token.char_src.begin() + begin, token.char_src.begin() + end);
        part.source_start = part.char_src.front();
        chunks.push_back(std::move(part));
      };
      for (size_t pos = 0; pos + 5 <= token.text.size(); ++pos) {
        if (token.text.compare(pos, 5, "BREAK") != 0 ||
            (pos > 0 && isWord(token.text[pos - 1])) ||
            (pos + 5 < token.text.size() && isWord(token.text[pos + 5]))) continue;
        append(start, pos);
        append(pos, pos + 5);
        start = pos + 5;
        pos += 4;
      }
      append(start, token.text.size());
    }
    return chunks;
  }

  size_t getEmbeddingCount() const { return embeddings_.size(); }
  size_t getEmbedding2Count() const { return embeddings_2_.size(); }

  bool hasEmbedding(const std::string &name) const {
    return embeddings_.find(toLowerCase(name)) != embeddings_.end();
  }
  bool hasEmbedding2(const std::string &name) const {
    return embeddings_2_.find(toLowerCase(name)) != embeddings_2_.end();
  }
};
