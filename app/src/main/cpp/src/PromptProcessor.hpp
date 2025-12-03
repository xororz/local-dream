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

#include "SafeTensorReader.hpp"

struct PromptToken {
  std::string text;
  float weight;
  bool is_embedding;
  std::vector<float> embedding_data;
};

class PromptProcessor {
 private:
  std::map<std::string, std::vector<float>> embeddings_;
  std::string embeddings_dir_;

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

        if (embeddings_.find(text_lower) != embeddings_.end()) {
          tokens.push_back(
              {node.text, current_weight, true, embeddings_[text_lower]});
        } else {
          tokens.push_back({node.text, current_weight, false, {}});
        }
      }
    }
  }

 public:
  PromptProcessor() = default;

  void loadEmbeddings(const std::string &embeddings_dir) {
    embeddings_dir_ = embeddings_dir;
    embeddings_.clear();

    if (!std::filesystem::exists(embeddings_dir)) {
      return;
    }

    for (const auto &entry :
         std::filesystem::directory_iterator(embeddings_dir)) {
      if (entry.path().extension() == ".safetensors") {
        try {
          SafeTensorReader reader(entry.path().string());
          std::string name = entry.path().stem().string();
          std::string name_lower = toLowerCase(name);

          auto tensor_names = reader.get_tensor_names();
          if (!tensor_names.empty()) {
            reader.read(tensor_names[0], true);
            embeddings_[name_lower] = reader.data;
          }
        } catch (const std::exception &e) {
          // could not load this embedding
        }
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

  bool hasEmbedding(const std::string &name) const {
    return embeddings_.find(toLowerCase(name)) != embeddings_.end();
  }
};
