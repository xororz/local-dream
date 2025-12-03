#ifndef SDUTILS_HPP
#define SDUTILS_HPP

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STB_IMAGE_RESIZE_IMPLEMENTATION

#include <MNN/MNNDefine.h>

#include <MNN/Interpreter.hpp>

#include "stb_image.h"
#include "stb_image_resize2.h"
#include "stb_image_write.h"

struct GenerationResult {
  std::vector<uint8_t> image_data;
  int width;
  int height;
  int channels;
  int generation_time_ms;
  int first_step_time_ms;
};

inline std::string base64_encode(const std::string &in) {
  static const auto lookup =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string out;
  out.reserve(in.size());
  auto val = 0;
  auto valb = -6;
  for (auto c : in) {
    val = (val << 8) + static_cast<uint8_t>(c);
    valb += 8;
    while (valb >= 0) {
      out.push_back(lookup[(val >> valb) & 0x3F]);
      valb -= 6;
    }
  }
  if (valb > -6) {
    out.push_back(lookup[((val << 8) >> (valb + 8)) & 0x3F]);
  }
  while (out.size() % 4) {
    out.push_back('=');
  }
  return out;
}
inline std::string base64_decode(const std::string &in) {
  static const std::string base64_chars =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  static std::array<int, 256> lookup;
  static bool initialized = false;

  if (!initialized) {
    lookup.fill(-1);
    for (int i = 0; i < 64; i++) {
      lookup[static_cast<unsigned char>(base64_chars[i])] = i;
    }
    initialized = true;
  }

  size_t in_len = in.size();
  if (in_len % 4 != 0) {
    throw std::runtime_error("Invalid base64 length");
  }

  size_t out_len = in_len / 4 * 3;
  if (in_len > 0 && in[in_len - 1] == '=') out_len--;
  if (in_len > 1 && in[in_len - 2] == '=') out_len--;

  std::string out;
  out.reserve(out_len);

  int val = 0, valb = -8;
  for (char c : in) {
    if (c == '=') {
      continue;
    }

    int idx = lookup[static_cast<unsigned char>(c)];
    if (idx == -1) {
      continue;
    }

    val = (val << 6) + idx;
    valb += 6;

    if (valb >= 0) {
      out.push_back(static_cast<char>((val >> valb) & 0xFF));
      valb -= 8;
    }
  }

  return out;
}

inline unsigned hashSeed(unsigned long long seed) {
  seed = ((seed >> 16) ^ seed) * 0x45d9f3b;
  seed = ((seed >> 16) ^ seed) * 0x45d9f3b;
  seed = (seed >> 16) ^ seed;
  return seed;
}

inline std::string LoadBytesFromFile(const std::string &path) {
  std::ifstream fs(path, std::ios::in | std::ios::binary);
  if (fs.fail()) {
    std::cerr << "Cannot open " << path << std::endl;
    throw std::runtime_error("Failed to open file: " + path);
  }
  std::string data;
  fs.seekg(0, std::ios::end);
  size_t size = static_cast<size_t>(fs.tellg());
  fs.seekg(0, std::ios::beg);
  data.resize(size);
  fs.read(data.data(), size);
  return data;
}
template <typename T>
void saveVectorToFile(const std::vector<T> &vec, const std::string &filename) {
  static_assert(
      std::is_trivially_copyable<T>::value,
      "Type T must be trivially copyable to be saved directly to file");
  std::ofstream file(filename, std::ios::binary);
  if (!file) {
    throw std::runtime_error("Cannot open file for writing");
  }
  if (!vec.empty()) {
    file.write(reinterpret_cast<const char *>(vec.data()),
               vec.size() * sizeof(T));
  }
  if (!file) {
    throw std::runtime_error("Error writing to file");
  }
  file.close();
}
template <typename T>
std::vector<T> loadVectorFromFile(const std::string &filename) {
  static_assert(
      std::is_trivially_copyable<T>::value,
      "Type T must be trivially copyable to be loaded directly from file");
  std::ifstream file(filename, std::ios::binary);
  if (!file) {
    throw std::runtime_error("Cannot open file for reading");
  }
  file.seekg(0, std::ios::end);
  size_t fileSize = file.tellg();
  file.seekg(0, std::ios::beg);
  size_t elementCount = fileSize / sizeof(T);
  std::vector<T> vec(elementCount);
  if (elementCount > 0) {
    file.read(reinterpret_cast<char *>(vec.data()), fileSize);
    if (!file) {
      throw std::runtime_error("Error reading vector data");
    }
  }
  file.close();
  return vec;
}

bool safety_check(const std::vector<uint8_t> &image_data, int width, int height,
                  float &nsfw_score, MNN::Interpreter *interpreter,
                  MNN::Session *session) {
  try {
    std::vector<uint8_t> resized_256(256 * 256 * 3);
    if (!stbir_resize_uint8_linear(image_data.data(), width, height, 0,
                                   resized_256.data(), 256, 256, 0,
                                   STBIR_RGB)) {
      throw std::runtime_error("Resize failed");
    }
    std::vector<unsigned char> jpeg_buffer;
    if (!stbi_write_jpg_to_func(
            [](void *context, void *data, int size) {
              auto &buffer =
                  *static_cast<std::vector<unsigned char> *>(context);
              buffer.insert(buffer.end(), static_cast<unsigned char *>(data),
                            static_cast<unsigned char *>(data) + size);
            },
            &jpeg_buffer, 256, 256, 3, resized_256.data(), 95)) {
      throw std::runtime_error("JPEG encoding failed");
    }
    int jpeg_width, jpeg_height, jpeg_channels;
    uint8_t *decoded_data =
        stbi_load_from_memory(jpeg_buffer.data(), jpeg_buffer.size(),
                              &jpeg_width, &jpeg_height, &jpeg_channels, 3);
    if (!decoded_data) {
      throw std::runtime_error("JPEG decoding failed");
    }
    std::vector<float> processed_data(224 * 224 * 3);
    int crop_x = (256 - 224) / 2;
    int crop_y = (256 - 224) / 2;
    float vgg_mean[] = {104.0f, 117.0f, 123.0f};
    for (int y = 0; y < 224; y++) {
      for (int x = 0; x < 224; x++) {
        for (int c = 0; c < 3; c++) {
          int src_idx = ((y + crop_y) * 256 + (x + crop_x)) * 3 + c;
          int dst_idx = (y * 224 + x) * 3 + c;
          processed_data[dst_idx] =
              static_cast<float>(decoded_data[src_idx]) - vgg_mean[c];
        }
      }
    }
    stbi_image_free(decoded_data);
    auto input_tensor = interpreter->getSessionInput(session, nullptr);
    // std::vector<int> dims = {1, 224, 224, 3};
    // interpreter->resizeTensor(input_tensor, dims);
    // interpreter->resizeSession(session);
    auto inputHost = input_tensor->host<float>();
    memcpy(inputHost, processed_data.data(), 224 * 224 * 3 * sizeof(float));
    interpreter->runSession(session);
    auto output_tensor = interpreter->getSessionOutput(session, nullptr);
    auto outputHost = output_tensor->host<float>();
    nsfw_score = outputHost[1];
    std::cout << "NSFW Score: " << nsfw_score << std::endl;
    return true;
  } catch (const std::exception &e) {
    std::cerr << "Safety check error: " << e.what() << std::endl;
    return false;
  }
}

void decode_image(const std::vector<uint8_t> &image_binary,
                  std::vector<uint8_t> &output_pixels, int output_width,
                  int output_height) {
  int width, height, channels;
  uint8_t *decoded_data =
      stbi_load_from_memory(image_binary.data(), image_binary.size(), &width,
                            &height, &channels, 3);  // Force 3 channels (RGB)

  if (!decoded_data) {
    std::string error_msg = stbi_failure_reason();
    std::cout << "Error decoding image: " << error_msg << std::endl;
    output_pixels.clear();
    return;
    // throw std::runtime_error("Failed to decode image: " + error_msg);
  }

  // Determine the scale and crop dimensions to maintain aspect ratio
  float scale = std::max(static_cast<float>(output_width) / width,
                         static_cast<float>(output_height) / height);

  int scaled_width = static_cast<int>(width * scale);
  int scaled_height = static_cast<int>(height * scale);

  // Calculate crop positions (center crop)
  int crop_x = (scaled_width - output_width) / 2;
  int crop_y = (scaled_height - output_height) / 2;

  // Resize the image with stb_image_resize
  std::vector<uint8_t> resized_image(scaled_width * scaled_height * 3);
  if (!stbir_resize_uint8_linear(decoded_data, width, height, 0,
                                 resized_image.data(), scaled_width,
                                 scaled_height, 0, STBIR_RGB)) {
    stbi_image_free(decoded_data);
    throw std::runtime_error("Failed to resize image");
  }

  // Free the original decoded data
  stbi_image_free(decoded_data);

  // Perform center crop
  output_pixels.resize(output_width * output_height * 3);
  for (int y = 0; y < output_height; y++) {
    for (int x = 0; x < output_width; x++) {
      for (int c = 0; c < 3; c++) {
        int src_idx = ((y + crop_y) * scaled_width + (x + crop_x)) * 3 + c;
        int dst_idx = (y * output_width + x) * 3 + c;

        // Ensure we're not accessing out of bounds
        if (src_idx >= 0 && src_idx < scaled_width * scaled_height * 3) {
          output_pixels[dst_idx] = resized_image[src_idx];
        } else {
          output_pixels[dst_idx] = 0;  // Black for out of bounds
        }
      }
    }
  }
}

void gaussianBlur(std::vector<uint8_t> &imageData, int width, int height,
                  int radius) {
  if (width <= 0 || height <= 0 || radius <= 0 || imageData.empty()) {
    return;
  }
  int channels = imageData.size() / (width * height);
  if (channels != 3 && channels != 4) {
    return;
  }
  int kernelSize = 2 * radius + 1;
  std::vector<float> kernel(kernelSize);
  float sigma = radius / 2.0f;
  float sum = 0.0f;

  for (int i = 0; i < kernelSize; ++i) {
    int x = i - radius;
    kernel[i] = std::exp(-(x * x) / (2 * sigma * sigma));
    sum += kernel[i];
  }

  for (int i = 0; i < kernelSize; ++i) {
    kernel[i] /= sum;
  }

  std::vector<uint8_t> tempBuffer(imageData.size());

  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      for (int c = 0; c < channels; ++c) {
        float sum = 0.0f;

        for (int kx = -radius; kx <= radius; ++kx) {
          int px = std::max(0, std::min(width - 1, x + kx));
          sum +=
              imageData[(y * width + px) * channels + c] * kernel[kx + radius];
        }

        tempBuffer[(y * width + x) * channels + c] = static_cast<uint8_t>(sum);
      }
    }
  }

  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      for (int c = 0; c < channels; ++c) {
        float sum = 0.0f;

        for (int ky = -radius; ky <= radius; ++ky) {
          int py = std::max(0, std::min(height - 1, y + ky));
          sum +=
              tempBuffer[(py * width + x) * channels + c] * kernel[ky + radius];
        }

        imageData[(y * width + x) * channels + c] = static_cast<uint8_t>(sum);
      }
    }
  }
}

inline void PrintEncodeResult(const std::vector<int> &ids) {
  std::cout << "tokens=[";
  for (size_t i = 0; i < ids.size(); ++i) {
    if (i != 0) std::cout << ", ";
    std::cout << ids[i];
  }
  std::cout << "]" << std::endl;
}

// Encodes an RGB byte array into PNG format
inline std::vector<uint8_t> encodePNG(const std::vector<uint8_t> &rgb_data,
                                      int width, int height) {
  std::vector<uint8_t> png_buffer;

  stbi_write_png_to_func(
      [](void *context, void *data, int size) {
        auto &buffer = *static_cast<std::vector<uint8_t> *>(context);
        buffer.insert(buffer.end(), static_cast<uint8_t *>(data),
                      static_cast<uint8_t *>(data) + size);
      },
      &png_buffer, width, height, 3, rgb_data.data(), width * 3);

  return png_buffer;
}

// Encodes an RGB byte array into JPEG format (quality adjustable)
inline std::vector<uint8_t> encodeJPEG(const std::vector<uint8_t> &rgb_data,
                                       int width, int height,
                                       int quality = 95) {
  std::vector<uint8_t> jpeg_buffer;

  stbi_write_jpg_to_func(
      [](void *context, void *data, int size) {
        auto &buffer = *static_cast<std::vector<uint8_t> *>(context);
        buffer.insert(buffer.end(), static_cast<uint8_t *>(data),
                      static_cast<uint8_t *>(data) + size);
      },
      &jpeg_buffer, width, height, 3, rgb_data.data(), quality);

  return jpeg_buffer;
}

// Decodes a PNG byte array to RGB
inline std::vector<uint8_t> decodePNG(const std::vector<uint8_t> &png_data,
                                      int &width, int &height) {
  int channels;
  uint8_t *decoded_data = stbi_load_from_memory(
      png_data.data(), png_data.size(), &width, &height, &channels, 3);

  if (!decoded_data) {
    std::string error_msg = stbi_failure_reason();
    throw std::runtime_error("Failed to decode PNG: " + error_msg);
  }

  std::vector<uint8_t> rgb_data(width * height * 3);
  std::memcpy(rgb_data.data(), decoded_data, width * height * 3);
  stbi_image_free(decoded_data);

  return rgb_data;
}

// Reads an RGB byte array directly (no decoding needed)
inline std::vector<uint8_t> readRGBFromBinary(const std::string &binary_data) {
  return std::vector<uint8_t>(binary_data.begin(), binary_data.end());
}

// Resize image to ensure shortest edge is at least target_size
// Returns resized image data and new dimensions
inline std::vector<uint8_t> resizeImageToMinSize(
    const std::vector<uint8_t> &input_image, int width, int height,
    int target_size, int &new_width, int &new_height) {
  int min_dim = std::min(width, height);

  if (min_dim >= target_size) {
    // No resize needed
    new_width = width;
    new_height = height;
    return input_image;
  }

  float scale = static_cast<float>(target_size) / min_dim;
  new_width = static_cast<int>(width * scale);
  new_height = static_cast<int>(height * scale);

  std::vector<uint8_t> resized_image(new_width * new_height * 3);

  stbir_resize_uint8_linear(input_image.data(), width, height, 0,
                            resized_image.data(), new_width, new_height, 0,
                            STBIR_RGB);

  return resized_image;
}

// Resize image back to target dimensions
inline std::vector<uint8_t> resizeImageToTarget(
    const std::vector<uint8_t> &input_image, int width, int height,
    int target_width, int target_height) {
  if (width == target_width && height == target_height) {
    return input_image;
  }

  std::vector<uint8_t> resized_image(target_width * target_height * 3);

  stbir_resize_uint8_linear(input_image.data(), width, height, 0,
                            resized_image.data(), target_width, target_height,
                            0, STBIR_RGB);

  return resized_image;
}

#endif