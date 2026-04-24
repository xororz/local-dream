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
};

class PromptProcessor {
 private:
  std::map<std::string, std::vector<float>> embeddings_;    // last-dim 768
  std::map<std::string, std::vector<float>> embeddings_2_;  // last-dim 1280
  std::string embeddings_dir_;
  bool sdxl_mode_ = false;

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

    TokenNode() : weight(1.0f), is_group(false) {}
  };

  TokenNode parsePromptTree(const std::string &prompt) {
    TokenNode root;
    root.is_group = true;
    root.weight = 1.0f;
    std::stack<TokenNode *> node_stack;
    node_stack.push(&root);

    std::string current_text;
    size_t i = 0;

    while (i < prompt.length()) {
      char c = prompt[i];

      if (c == '\\' && i + 1 < prompt.length()) {
        char next = prompt[i + 1];
        if (next == '(' || next == ')' || next == '[' || next == ']' ||
            next == '\\' || next == ',' || next == ':') {
          current_text += next;
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
          }
        }
        i++;
        continue;
      }

      if (c == '(') {
        if (!current_text.empty()) {
          TokenNode text_node;
          text_node.text = trim(current_text);
          text_node.weight = 1.0f;
          text_node.is_group = false;
          if (!text_node.text.empty()) {
            node_stack.top()->children.push_back(text_node);
          }
          current_text.clear();
        }

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
            std::string text_part = trim(current_text.substr(0, colon_pos));

            try {
              float weight = std::stof(weight_str);
              TokenNode text_node;
              text_node.text = text_part;
              text_node.weight = weight;
              text_node.is_group = false;
              if (!text_node.text.empty()) {
                node_stack.top()->children.push_back(text_node);
              }
              has_weight = true;
            } catch (...) {
              // failed to parse weight
            }
          }

          if (!has_weight) {
            TokenNode text_node;
            text_node.text = trim(current_text);
            text_node.weight = 1.0f;
            text_node.is_group = false;
            if (!text_node.text.empty()) {
              node_stack.top()->children.push_back(text_node);
            }
          }
          current_text.clear();
        }

        if (node_stack.size() > 1) {
          node_stack.pop();
        }
        i++;

      } else if (c == '[') {
        if (!current_text.empty()) {
          TokenNode text_node;
          text_node.text = trim(current_text);
          text_node.weight = 1.0f;
          text_node.is_group = false;
          if (!text_node.text.empty()) {
            node_stack.top()->children.push_back(text_node);
          }
          current_text.clear();
        }

        TokenNode *parent = node_stack.top();
        parent->children.push_back(TokenNode());
        TokenNode *new_node = &parent->children.back();
        new_node->is_group = true;
        new_node->weight = 0.9f;
        node_stack.push(new_node);
        i++;

      } else if (c == ']') {
        if (!current_text.empty()) {
          TokenNode text_node;
          text_node.text = trim(current_text);
          text_node.weight = 1.0f;
          text_node.is_group = false;
          if (!text_node.text.empty()) {
            node_stack.top()->children.push_back(text_node);
          }
          current_text.clear();
        }

        if (node_stack.size() > 1) {
          node_stack.pop();
        }
        i++;

      } else if (c == ',') {
        if (!current_text.empty()) {
          TokenNode text_node;
          text_node.text = trim(current_text);
          text_node.weight = 1.0f;
          text_node.is_group = false;
          if (!text_node.text.empty()) {
            node_stack.top()->children.push_back(text_node);
          }
          current_text.clear();
        }
        TokenNode comma_node;
        comma_node.text = ",";
        comma_node.weight = 1.0f;
        comma_node.is_group = false;
        node_stack.top()->children.push_back(comma_node);
        i++;

      } else {
        current_text += c;
        i++;
      }
    }

    if (!current_text.empty()) {
      TokenNode text_node;
      text_node.text = trim(current_text);
      text_node.weight = 1.0f;
      text_node.is_group = false;
      if (!text_node.text.empty()) {
        node_stack.top()->children.push_back(text_node);
      }
    }

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
          tokens.push_back(std::move(t));
        } else {
          tokens.push_back({node.text, current_weight, false, {}, {}});
        }
      }
    }
  }

 public:
  PromptProcessor() = default;

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

    return tokens;
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
