#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>
#include <xtensor-blas/xlinalg.hpp>
#include <xtensor/xadapt.hpp>
#include <xtensor/xarray.hpp>
#include <xtensor/xeval.hpp>

#include "FloatConversion.hpp"
#include "LoraMapping.hpp"
#include "SDStructure.hpp"
#include "SafeTensorReader.hpp"

struct Shape {
  std::vector<int> dims;
  Shape(const std::string &shape_str) {
    if (shape_str.empty()) return;
    int pos = 0;
    int next = 0;
    while ((next = shape_str.find('x', pos)) != std::string::npos) {
      dims.push_back(std::stoi(shape_str.substr(pos, next - pos)));
      pos = next + 1;
    }
    dims.push_back(std::stoi(shape_str.substr(pos)));
  }
};

std::vector<uint8_t> fillBuffer(const std::vector<uint32_t> &values,
                                int need_bits) {
  if (need_bits == 8) {
    std::vector<uint8_t> result(values.size());
    for (int i = 0; i < values.size(); ++i) {
      result[i] = static_cast<uint8_t>(values[i]);
    }
    return result;
  }

  int total_bits = values.size() * need_bits;
  int buf_len = (total_bits + 7) / 8;
  std::vector<uint8_t> buffer(buf_len, 0);

  uint32_t mask = (1U << need_bits) - 1;
  int bit_offset = 0;

  for (uint32_t value : values) {
    value &= mask;
    int byte_pos = bit_offset / 8;
    int bit_pos_in_byte = bit_offset % 8;
    int bits_in_current_byte = 8 - bit_pos_in_byte;

    if (need_bits <= bits_in_current_byte) {
      int shift = bits_in_current_byte - need_bits;
      buffer[byte_pos] |= static_cast<uint8_t>(value << shift);
    } else {
      uint32_t high_bits = value >> (need_bits - bits_in_current_byte);
      buffer[byte_pos] |= static_cast<uint8_t>(high_bits);
      int remaining_bits = need_bits - bits_in_current_byte;
      uint32_t low_bits = value & ((1U << remaining_bits) - 1);
      int shift = 8 - remaining_bits;
      buffer[byte_pos + 1] |= static_cast<uint8_t>(low_bits << shift);
    }

    bit_offset += need_bits;
  }

  return buffer;
}

std::vector<uint8_t> quantizeWeights(const std::vector<float> &weights,
                                     const Shape &shape) {
  if (shape.dims.size() != 4) return {};

  int oc = shape.dims[0], ic = shape.dims[1], h = shape.dims[2],
      w = shape.dims[3];
  int kxky = h * w;
  int kernel_size = ic * kxky;
  int block_size = 32;
  float threshold = 127.0f;

  int block_num = 1;
  int actual_block_size = kernel_size;

  if (block_size > 0 && (ic % block_size == 0) && block_size >= 16 &&
      (block_size % 16 == 0)) {
    block_num = ic / block_size;
    actual_block_size = block_size * kxky;
  }

  std::vector<float> scales(oc * block_num);
  for (int k = 0; k < oc; ++k) {
    for (int b = 0; b < block_num; ++b) {
      int begin_index = b * actual_block_size;
      int end_index = begin_index + actual_block_size;

      float abs_max = 0.0f;
      for (int idx = begin_index; idx < end_index; ++idx) {
        int weight_idx = k * kernel_size + idx;
        abs_max = std::max(abs_max, std::abs(weights[weight_idx]));
      }

      scales[k * block_num + b] = abs_max / threshold;
    }
  }

  int offset = 128;
  int min_value = -128;
  int max_value = 127;
  int value_count = 256;
  int need_bits = 8;

  std::vector<uint32_t> indices;
  indices.reserve(weights.size());

  for (int k = 0; k < oc; ++k) {
    for (int b = 0; b < block_num; ++b) {
      int begin_index = b * actual_block_size;
      int end_index = begin_index + actual_block_size;
      float scale = scales[k * block_num + b];

      for (int idx = begin_index; idx < end_index; ++idx) {
        int weight_idx = k * kernel_size + idx;
        float ratio = (scale > 1e-6f) ? weights[weight_idx] / scale : 0.0f;
        int value = static_cast<int>(std::round(ratio));
        value = std::max(min_value, std::min(max_value, value));
        indices.push_back(static_cast<uint32_t>(value + offset));
      }
    }
  }

  std::vector<uint8_t> result;

  std::vector<int> blob_dims = {oc * block_num, actual_block_size};
  result.push_back(static_cast<uint8_t>(blob_dims.size()));
  bool use_int32 = std::any_of(blob_dims.begin(), blob_dims.end(),
                               [](int d) { return d > 65535; });

  if (use_int32) {
    for (int dim : blob_dims) {
      uint32_t d = static_cast<uint32_t>(dim);
      const uint8_t *bytes = reinterpret_cast<const uint8_t *>(&d);
      result.insert(result.end(), bytes, bytes + 4);
    }
  } else {
    for (int dim : blob_dims) {
      uint16_t d = static_cast<uint16_t>(dim);
      const uint8_t *bytes = reinterpret_cast<const uint8_t *>(&d);
      result.insert(result.end(), bytes, bytes + 2);
    }
  }

  result.push_back(static_cast<uint8_t>(value_count));
  for (int value = min_value; value <= max_value; ++value) {
    result.push_back(static_cast<uint8_t>(value));
  }

  std::vector<uint8_t> compressed = fillBuffer(indices, need_bits);
  result.insert(result.end(), compressed.begin(), compressed.end());

  const uint8_t *scale_bytes = reinterpret_cast<const uint8_t *>(scales.data());
  result.insert(result.end(), scale_bytes,
                scale_bytes + scales.size() * sizeof(float));

  return result;
}

std::vector<float> applyLoRA(
    const std::vector<float> &original_weights, const std::string &weight_name,
    const std::vector<SafeTensorReader *> &lora_readers,
    const std::vector<float> &lora_weights = {}) {
  std::vector<float> final_weights = original_weights;

  auto it = lora_mapping.find(weight_name);
  if (it != lora_mapping.end()) {
    const std::string &lora_key = it->second;

    for (size_t lora_idx = 0; lora_idx < lora_readers.size(); ++lora_idx) {
      auto *lora_reader = lora_readers[lora_idx];
      float lora_weight =
          (lora_idx < lora_weights.size()) ? lora_weights[lora_idx] : 1.0f;

      std::string alpha_key = lora_key + ".alpha";
      std::string lora_down_key = lora_key + ".lora_down.weight";
      std::string lora_up_key = lora_key + ".lora_up.weight";

      if (!lora_reader->has_tensor(lora_down_key) ||
          !lora_reader->has_tensor(lora_up_key)) {
        // std::cout << "Missing LoRA tensors for weight: " << weight_name
        //           << std::endl;
        continue;
      }

      float alpha = 1.0f;
      if (lora_reader->has_tensor(alpha_key)) {
        lora_reader->read(alpha_key);
        alpha = lora_reader->data.empty() ? 1.0f : lora_reader->data[0];
      }

      lora_reader->read(lora_down_key);
      std::vector<float> lora_down = lora_reader->data;

      lora_reader->read(lora_up_key);
      std::vector<float> lora_up = lora_reader->data;

      if (!lora_down.empty() && !lora_up.empty()) {
        int total_elements = original_weights.size();
        int rank = static_cast<int>(
            sqrt(lora_down.size() / (total_elements / lora_up.size())));
        int out_features = lora_up.size() / rank;
        int in_features = lora_down.size() / rank;

        xt::xarray<float> lora_up_tensor =
            xt::adapt(lora_up, {out_features, rank});
        xt::xarray<float> lora_down_tensor =
            xt::adapt(lora_down, {rank, in_features});
        xt::xarray<float> original_tensor =
            xt::adapt(final_weights, {out_features, in_features});

        xt::xarray<float> lora_delta =
            xt::eval(lora_weight * alpha / rank *
                     xt::linalg::dot(lora_up_tensor, lora_down_tensor));

        original_tensor += lora_delta;

        std::copy(original_tensor.begin(), original_tensor.end(),
                  final_weights.begin());
      }
    }
  }

  return final_weights;
}

void generateModel(const std::string &dir, const std::string &safetensor_file,
                   const std::string &model_name,
                   const std::vector<std::vector<std::string>> &structure,
                   const std::vector<std::string> &loras = {},
                   const std::vector<float> &lora_weights = {}) {
  SafeTensorReader reader(dir + "/" + safetensor_file);
  std::ofstream weight_file(dir + "/model.mnn.weight", std::ios::binary);

  std::vector<SafeTensorReader *> lora_readers;
  std::vector<std::unique_ptr<SafeTensorReader>> lora_reader_holders;
  for (const auto &lora_file : loras) {
    auto lora_reader =
        std::make_unique<SafeTensorReader>(dir + "/" + lora_file);
    lora_readers.push_back(lora_reader.get());
    lora_reader_holders.push_back(std::move(lora_reader));
  }

  for (const auto &weight_info : structure) {
    const std::string &weight_name = weight_info[0];
    const std::string &data_type = weight_info[1];

    if (data_type == "fp32") {
      reader.read(weight_name);
      std::vector<float> final_weights =
          applyLoRA(reader.data, weight_name, lora_readers, lora_weights);
      weight_file.write(reinterpret_cast<const char *>(final_weights.data()),
                        final_weights.size() * sizeof(float));
    } else if (data_type == "fp16") {
      reader.read(weight_name);
      std::vector<float> final_weights =
          applyLoRA(reader.data, weight_name, lora_readers, lora_weights);

      std::vector<uint16_t> fp16_result(final_weights.size());
      for (size_t i = 0; i < final_weights.size(); ++i) {
        fp16_result[i] = fp32_to_fp16(final_weights[i]);
      }
      weight_file.write(reinterpret_cast<const char *>(fp16_result.data()),
                        fp16_result.size() * sizeof(uint16_t));
    } else if (data_type == "const") {
      int zero_length = std::stoi(weight_info[2]);
      std::vector<float> const_data(zero_length, 0.0f);
      std::vector<float> final_weights =
          applyLoRA(const_data, weight_name, lora_readers, lora_weights);
      weight_file.write(reinterpret_cast<const char *>(final_weights.data()),
                        final_weights.size() * sizeof(float));
    } else if (data_type == "block_quant") {
      reader.read(weight_name);
      std::vector<float> final_weights =
          applyLoRA(reader.data, weight_name, lora_readers, lora_weights);
      Shape shape(weight_info[2]);
      auto quantized = quantizeWeights(final_weights, shape);
      weight_file.write(reinterpret_cast<const char *>(quantized.data()),
                        quantized.size());
    }
  }
  weight_file.close();

  std::string final_name = dir + "/" + model_name + ".mnn.weight";
  std::rename((dir + "/model.mnn.weight").c_str(), final_name.c_str());
}

void patchModel(const std::string &dir, const std::string &safetensor_file,
                const std::string &model_name,
                const std::unordered_map<std::string, int> &small_weights,
                bool fp16 = false) {
  std::string mnn_filepath = dir + "/" + model_name + ".mnn";

  std::fstream mnn_file(mnn_filepath,
                        std::ios::in | std::ios::out | std::ios::binary);

  SafeTensorReader reader(dir + "/" + safetensor_file);

  for (const auto &pair : small_weights) {
    const std::string &weight_name = pair.first;
    int offset = pair.second;

    if (fp16) {
      reader.read(weight_name, false);
    } else {
      reader.read(weight_name);
    }

    int data_size_bytes;
    if (fp16) {
      data_size_bytes = reader.fp16_data.size() * sizeof(uint16_t);
    } else {
      data_size_bytes = reader.data.size() * sizeof(float);
    }

    mnn_file.seekp(offset, std::ios::beg);
    if (!mnn_file) {
      mnn_file.close();
      return;
    }

    if (fp16) {
      mnn_file.write(reinterpret_cast<const char *>(reader.fp16_data.data()),
                     data_size_bytes);
    } else {
      mnn_file.write(reinterpret_cast<const char *>(reader.data.data()),
                     data_size_bytes);
    }
  }
  mnn_file.close();
}

void generateClipModel(const std::string &dir,
                       const std::string &safetensor_file,
                       bool clip_skip_2 = false,
                       const std::vector<std::string> &loras = {},
                       const std::vector<float> &lora_weights = {}) {
  if (clip_skip_2) {
    generateModel(dir, safetensor_file, "clip_v2", clip_skip_2_structure, loras,
                  lora_weights);
  } else {
    generateModel(dir, safetensor_file, "clip_v2", clip_structure, loras,
                  lora_weights);
  }

  SafeTensorReader reader(dir + "/" + safetensor_file);

  reader.read(
      "cond_stage_model.transformer.text_model.embeddings.position_embedding."
      "weight",
      true);
  std::ofstream pos_emb_file(dir + "/pos_emb.bin", std::ios::binary);
  pos_emb_file.write(reinterpret_cast<const char *>(reader.data.data()),
                     reader.data.size() * sizeof(float));
  pos_emb_file.close();

  reader.read(
      "cond_stage_model.transformer.text_model.embeddings.token_embedding."
      "weight",
      true);
  std::ofstream token_emb_file(dir + "/token_emb.bin", std::ios::binary);
  token_emb_file.write(reinterpret_cast<const char *>(reader.fp16_data.data()),
                       reader.fp16_data.size() * sizeof(uint16_t));
  token_emb_file.close();
}

void generateMNNModels(const std::string &dir,
                       const std::string &safetensor_file,
                       bool clip_skip_2 = false,
                       const std::vector<std::string> &loras = {},
                       const std::vector<float> &lora_weights = {}) {
  std::cout << "Generating CLIP model..." << std::endl;
  generateClipModel(dir, safetensor_file, clip_skip_2, loras, lora_weights);

  std::cout << "Generating UNet model..." << std::endl;
  generateModel(dir, safetensor_file, "unet", unet_structure, loras,
                lora_weights);
  patchModel(dir, safetensor_file, "unet", unet_small_weights);

  std::cout << "Generating VAE Decoder model..." << std::endl;
  generateModel(dir, safetensor_file, "vae_decoder", vae_decoder_structure);
  patchModel(dir, safetensor_file, "vae_decoder", vae_decoder_small_weights,
             true);

  std::cout << "Generating VAE Encoder model..." << std::endl;
  generateModel(dir, safetensor_file, "vae_encoder", vae_encoder_structure);
  patchModel(dir, safetensor_file, "vae_encoder", vae_encoder_small_weights,
             true);

  std::ofstream finished_file(dir + "/finished");
  finished_file.close();

  std::cout << "All models generated successfully!" << std::endl;
}
