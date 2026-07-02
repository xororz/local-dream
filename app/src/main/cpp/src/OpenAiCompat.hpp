#ifndef OPENAI_COMPAT_HPP
#define OPENAI_COMPAT_HPP

#include <algorithm>
#include <cctype>
#include <chrono>
#include <ctime>
#include <map>
#include <set>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "SDUtils.hpp"
#include "httplib.h"
#include "json.hpp"

struct OpenAiHttpError : public std::runtime_error {
  int status;
  std::string type;
  std::string param;

  OpenAiHttpError(int s, std::string message,
                  std::string error_type = "invalid_request_error",
                  std::string error_param = "")
      : std::runtime_error(std::move(message)),
        status(s),
        type(std::move(error_type)),
        param(std::move(error_param)) {}
};

struct OpenAiRouteOptions {
  bool fixed_canvas = false;
  bool supports_img2img = false;
  std::string api_key;
  std::string active_model;
};

struct OpenAiPreparedRequest {
  std::vector<nlohmann::json> items;
};

struct MultipartPart {
  std::string name;
  std::string filename;
  std::string content_type;
  std::string content;
};

inline void setOpenAiError(httplib::Response &res, int status,
                           const std::string &message, const std::string &type,
                           const std::string &param = "") {
  nlohmann::json param_value = nullptr;
  if (!param.empty()) {
    param_value = param;
  }
  nlohmann::json err = {
      {"error",
       {{"message", message},
        {"type", type},
        {"param", param_value},
        {"code", nullptr}}}};
  res.status = status;
  res.set_content(err.dump(), "application/json");
}

inline std::string trimAscii(std::string s) {
  auto is_space = [](unsigned char ch) { return std::isspace(ch) != 0; };
  auto begin = std::find_if_not(s.begin(), s.end(), is_space);
  auto end = std::find_if_not(s.rbegin(), s.rend(), is_space).base();
  if (begin >= end) return "";
  return std::string(begin, end);
}

inline std::string toLowerAscii(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(),
                 [](unsigned char ch) { return std::tolower(ch); });
  return s;
}

inline std::string unquote(std::string s) {
  s = trimAscii(std::move(s));
  if (s.size() >= 2 && s.front() == '"' && s.back() == '"') {
    return s.substr(1, s.size() - 2);
  }
  return s;
}

inline int gcdPositive(int a, int b) {
  while (b != 0) {
    int t = b;
    b = a % b;
    a = t;
  }
  return std::max(a, 1);
}

inline std::string makeAspectRatioString(int width, int height) {
  int d = gcdPositive(width, height);
  return std::to_string(width / d) + ":" + std::to_string(height / d);
}

inline void requireOpenAiAuthorization(const httplib::Request &req,
                                       const OpenAiRouteOptions &opts) {
  if (opts.api_key.empty()) return;
  if (!req.has_header("Authorization")) {
    throw OpenAiHttpError(401, "Missing Authorization: Bearer <key> header",
                          "authentication_error");
  }

  std::string auth = trimAscii(req.get_header_value("Authorization"));
  if (auth.size() <= 7 ||
      toLowerAscii(auth.substr(0, 7)) != std::string("bearer ")) {
    throw OpenAiHttpError(401, "Invalid Authorization header",
                          "authentication_error");
  }

  std::string token = auth.substr(7);
  if (token != opts.api_key) {
    throw OpenAiHttpError(401, "Invalid API key", "authentication_error");
  }
}

inline void validateModelField(const nlohmann::json &json,
                               const OpenAiRouteOptions &opts) {
  if (!json.contains("model")) return;
  std::string model = json["model"].get<std::string>();
  if (model.empty() || opts.active_model.empty()) return;
  if (model == opts.active_model || model == "default" ||
      model == "local-dream")
    return;
  throw OpenAiHttpError(
      400,
      "This backend is already serving '" + opts.active_model +
          "' and cannot switch to '" + model + "' via the OpenAI API",
      "invalid_request_error", "model");
}

inline void validateKnownFields(const nlohmann::json &json,
                                const std::set<std::string> &allowed) {
  for (auto it = json.begin(); it != json.end(); ++it) {
    if (allowed.count(it.key()) != 0) continue;
    throw OpenAiHttpError(400, "Unsupported field '" + it.key() + "'",
                          "invalid_request_error", it.key());
  }
}

inline int parseStrictPositiveIntString(const std::string &value,
                                        const std::string &field_name) {
  try {
    std::string trimmed = trimAscii(value);
    size_t idx = 0;
    int parsed = std::stoi(trimmed, &idx);
    if (idx != trimmed.size() || parsed <= 0) throw std::invalid_argument("");
    return parsed;
  } catch (...) {
    throw OpenAiHttpError(400, "Invalid '" + field_name + "' value",
                          "invalid_request_error", field_name);
  }
}

inline long long parseLongLongString(const std::string &value,
                                     const std::string &field_name) {
  try {
    std::string trimmed = trimAscii(value);
    size_t idx = 0;
    long long parsed = std::stoll(trimmed, &idx);
    if (idx != trimmed.size() || parsed < 0) throw std::invalid_argument("");
    return parsed;
  } catch (...) {
    throw OpenAiHttpError(400, "Invalid '" + field_name + "' value",
                          "invalid_request_error", field_name);
  }
}

inline double parseDoubleString(const std::string &value,
                                const std::string &field_name) {
  try {
    std::string trimmed = trimAscii(value);
    size_t idx = 0;
    double parsed = std::stod(trimmed, &idx);
    if (idx != trimmed.size()) throw std::invalid_argument("");
    return parsed;
  } catch (...) {
    throw OpenAiHttpError(400, "Invalid '" + field_name + "' value",
                          "invalid_request_error", field_name);
  }
}

inline bool parseBoolString(const std::string &value,
                            const std::string &field_name) {
  std::string trimmed = toLowerAscii(trimAscii(value));
  if (trimmed == "true" || trimmed == "1") return true;
  if (trimmed == "false" || trimmed == "0") return false;
  throw OpenAiHttpError(400, "Invalid '" + field_name + "' value",
                        "invalid_request_error", field_name);
}

inline void applySizeField(const std::string &size,
                           const OpenAiRouteOptions &opts,
                           nlohmann::json &internal_json) {
  std::string trimmed = trimAscii(size);
  auto x_pos = trimmed.find('x');
  if (x_pos == std::string::npos) x_pos = trimmed.find('X');
  if (x_pos == std::string::npos || x_pos == 0 || x_pos + 1 >= trimmed.size()) {
    throw OpenAiHttpError(400, "Invalid 'size' value; expected WIDTHxHEIGHT",
                          "invalid_request_error", "size");
  }

  int width = parseStrictPositiveIntString(trimmed.substr(0, x_pos), "size");
  int height =
      parseStrictPositiveIntString(trimmed.substr(x_pos + 1), "size");
  if (width % 8 != 0 || height % 8 != 0) {
    throw OpenAiHttpError(400, "Image size must be a multiple of 8",
                          "invalid_request_error", "size");
  }

  if (opts.fixed_canvas) {
    if (width != height) {
      internal_json["aspect_ratio"] = makeAspectRatioString(width, height);
    }
  } else {
    internal_json["width"] = width;
    internal_json["height"] = height;
  }
}

inline int parseImageCount(const nlohmann::json &json) {
  int n = json.value("n", 1);
  if (n <= 0) {
    throw OpenAiHttpError(400, "'n' must be greater than 0",
                          "invalid_request_error", "n");
  }
  return n;
}

inline nlohmann::json buildInternalGenerationJson(
    const nlohmann::json &json, const OpenAiRouteOptions &opts) {
  if (!json.is_object()) {
    throw OpenAiHttpError(400, "Request body must be a JSON object",
                          "invalid_request_error");
  }

  static const std::set<std::string> kAllowedFields = {
      "prompt",         "model",        "n",
      "size",           "response_format",
      "user",           "negative_prompt",
      "seed",           "steps",        "cfg",
      "scheduler",      "use_opencl",   "denoise_strength",
      "show_diffusion_process",         "show_diffusion_stride",
      "aspect_ratio"};
  validateKnownFields(json, kAllowedFields);
  validateModelField(json, opts);

  std::string prompt = json.value("prompt", std::string());
  if (prompt.empty()) {
    throw OpenAiHttpError(400, "Missing 'prompt'", "invalid_request_error",
                          "prompt");
  }

  std::string response_format = json.value("response_format", "b64_json");
  if (response_format != "b64_json") {
    throw OpenAiHttpError(400, "Only response_format='b64_json' is supported",
                          "invalid_request_error", "response_format");
  }

  nlohmann::json internal_json = {{"prompt", prompt},
                                  {"output_format", "png"},
                                  {"show_diffusion_process", false}};
  if (json.contains("negative_prompt")) {
    internal_json["negative_prompt"] = json["negative_prompt"];
  }
  if (json.contains("steps")) internal_json["steps"] = json["steps"];
  if (json.contains("cfg")) internal_json["cfg"] = json["cfg"];
  if (json.contains("scheduler"))
    internal_json["scheduler"] = json["scheduler"];
  if (json.contains("use_opencl"))
    internal_json["use_opencl"] = json["use_opencl"];
  if (json.contains("denoise_strength"))
    internal_json["denoise_strength"] = json["denoise_strength"];
  if (json.contains("show_diffusion_stride"))
    internal_json["show_diffusion_stride"] = json["show_diffusion_stride"];
  if (json.contains("aspect_ratio") && !json.contains("size")) {
    internal_json["aspect_ratio"] = json["aspect_ratio"];
  }
  if (json.contains("size")) {
    applySizeField(json["size"].get<std::string>(), opts, internal_json);
  }
  return internal_json;
}

inline OpenAiPreparedRequest parseOpenAiGenerationRequest(
    const nlohmann::json &json, const OpenAiRouteOptions &opts) {
  int count = parseImageCount(json);
  nlohmann::json internal_base = buildInternalGenerationJson(json, opts);

  bool has_seed = json.contains("seed");
  long long base_seed = static_cast<long long>(
      std::chrono::high_resolution_clock::now().time_since_epoch().count());
  if (has_seed) {
    base_seed = json["seed"].get<long long>();
    if (base_seed < 0) {
      throw OpenAiHttpError(400, "Invalid 'seed' value",
                            "invalid_request_error", "seed");
    }
  }

  OpenAiPreparedRequest prepared;
  prepared.items.reserve(count);
  for (int i = 0; i < count; ++i) {
    auto item = internal_base;
    item["seed"] = base_seed + i;
    prepared.items.push_back(std::move(item));
  }
  return prepared;
}

inline std::string extractBoundary(const httplib::Request &req) {
  if (!req.has_header("Content-Type")) {
    throw OpenAiHttpError(400, "Missing Content-Type header",
                          "invalid_request_error");
  }
  std::string content_type = req.get_header_value("Content-Type");
  std::string lower = toLowerAscii(content_type);
  if (lower.find("multipart/form-data") == std::string::npos) {
    throw OpenAiHttpError(400, "Expected multipart/form-data request",
                          "invalid_request_error");
  }

  auto boundary_pos = lower.find("boundary=");
  if (boundary_pos == std::string::npos) {
    throw OpenAiHttpError(400, "Missing multipart boundary",
                          "invalid_request_error");
  }
  std::string boundary = content_type.substr(boundary_pos + 9);
  auto semicolon = boundary.find(';');
  if (semicolon != std::string::npos) boundary = boundary.substr(0, semicolon);
  boundary = unquote(boundary);
  if (boundary.empty()) {
    throw OpenAiHttpError(400, "Invalid multipart boundary",
                          "invalid_request_error");
  }
  return boundary;
}

inline MultipartPart parseMultipartPart(const std::string &header_block,
                                        std::string content) {
  MultipartPart part;
  size_t line_start = 0;
  while (line_start < header_block.size()) {
    size_t line_end = header_block.find("\r\n", line_start);
    if (line_end == std::string::npos) line_end = header_block.size();
    std::string line = header_block.substr(line_start, line_end - line_start);
    auto colon = line.find(':');
    if (colon != std::string::npos) {
      std::string key = toLowerAscii(trimAscii(line.substr(0, colon)));
      std::string value = trimAscii(line.substr(colon + 1));
      if (key == "content-type") {
        part.content_type = value;
      } else if (key == "content-disposition") {
        size_t seg_start = 0;
        while (seg_start < value.size()) {
          size_t seg_end = value.find(';', seg_start);
          if (seg_end == std::string::npos) seg_end = value.size();
          std::string seg = trimAscii(value.substr(seg_start, seg_end - seg_start));
          auto eq = seg.find('=');
          if (eq != std::string::npos) {
            std::string attr = toLowerAscii(trimAscii(seg.substr(0, eq)));
            std::string attr_val = unquote(seg.substr(eq + 1));
            if (attr == "name") part.name = attr_val;
            if (attr == "filename") part.filename = attr_val;
          }
          seg_start = seg_end + 1;
        }
      }
    }
    line_start = line_end + 2;
  }
  part.content = std::move(content);
  return part;
}

inline std::vector<MultipartPart> parseMultipartFormData(
    const httplib::Request &req) {
  std::string boundary = extractBoundary(req);
  std::string delimiter = "--" + boundary;
  std::string search = "\r\n" + delimiter;
  const std::string &body = req.body;
  if (body.compare(0, delimiter.size(), delimiter) != 0) {
    throw OpenAiHttpError(400, "Malformed multipart body",
                          "invalid_request_error");
  }

  std::vector<MultipartPart> parts;
  size_t pos = 0;
  while (true) {
    if (body.compare(pos, delimiter.size(), delimiter) != 0) {
      throw OpenAiHttpError(400, "Malformed multipart boundary",
                            "invalid_request_error");
    }
    pos += delimiter.size();
    if (body.compare(pos, 2, "--") == 0) break;
    if (body.compare(pos, 2, "\r\n") != 0) {
      throw OpenAiHttpError(400, "Malformed multipart separator",
                            "invalid_request_error");
    }
    pos += 2;

    size_t header_end = body.find("\r\n\r\n", pos);
    if (header_end == std::string::npos) {
      throw OpenAiHttpError(400, "Malformed multipart headers",
                            "invalid_request_error");
    }
    std::string header_block = body.substr(pos, header_end - pos);
    pos = header_end + 4;

    size_t next_delim = body.find(search, pos);
    if (next_delim == std::string::npos) {
      throw OpenAiHttpError(400, "Malformed multipart content",
                            "invalid_request_error");
    }
    std::string content = body.substr(pos, next_delim - pos);
    pos = next_delim + 2;

    MultipartPart part = parseMultipartPart(header_block, std::move(content));
    if (part.name.empty()) {
      throw OpenAiHttpError(400, "Multipart part missing name",
                            "invalid_request_error");
    }
    parts.push_back(std::move(part));
  }

  return parts;
}

inline bool extractMultipartFormDataFromRequest(
    const httplib::Request &req, std::map<std::string, std::string> &fields,
    std::map<std::string, MultipartPart> &files) {
  bool found_any = false;

  for (const auto &entry : req.files) {
    if (entry.second.filename.empty()) {
      fields[entry.first] = entry.second.content;
      found_any = true;
      continue;
    }

    MultipartPart part;
    part.name = entry.second.name.empty() ? entry.first : entry.second.name;
    part.filename = entry.second.filename;
    part.content_type = entry.second.content_type;
    part.content = entry.second.content;
    files[entry.first] = std::move(part);
    found_any = true;
  }

  for (const auto &entry : req.params) {
    if (fields.count(entry.first) == 0) {
      fields[entry.first] = entry.second;
      found_any = true;
    }
  }

  return found_any;
}

inline nlohmann::json multipartFieldsToJson(
    const std::map<std::string, std::string> &fields) {
  nlohmann::json json = nlohmann::json::object();
  for (const auto &[key, value] : fields) {
    if (key == "n" || key == "steps" || key == "show_diffusion_stride") {
      json[key] = parseStrictPositiveIntString(value, key);
    } else if (key == "seed") {
      json[key] = parseLongLongString(value, key);
    } else if (key == "cfg" || key == "denoise_strength") {
      json[key] = parseDoubleString(value, key);
    } else if (key == "use_opencl" || key == "show_diffusion_process") {
      json[key] = parseBoolString(value, key);
    } else {
      json[key] = value;
    }
  }
  return json;
}

inline OpenAiPreparedRequest parseOpenAiEditRequest(
    const httplib::Request &req, const OpenAiRouteOptions &opts) {
  if (!opts.supports_img2img) {
    throw OpenAiHttpError(
        400,
        "Image edits are unavailable because this backend was started without img2img support",
        "invalid_request_error");
  }

  std::map<std::string, std::string> fields;
  std::map<std::string, MultipartPart> files;
  if (!extractMultipartFormDataFromRequest(req, fields, files)) {
    std::vector<MultipartPart> parts = parseMultipartFormData(req);
    for (auto &part : parts) {
      if (part.filename.empty() && part.name != "image" &&
          part.name != "mask") {
        fields[part.name] = part.content;
      } else {
        files[part.name] = std::move(part);
      }
    }
  }

  if (files.count("image") == 0 || files["image"].content.empty()) {
    throw OpenAiHttpError(400, "Missing 'image' file", "invalid_request_error",
                          "image");
  }
  if (fields.count("prompt") == 0 || trimAscii(fields["prompt"]).empty()) {
    throw OpenAiHttpError(400, "Missing 'prompt'", "invalid_request_error",
                          "prompt");
  }

  nlohmann::json json = multipartFieldsToJson(fields);
  OpenAiPreparedRequest prepared = parseOpenAiGenerationRequest(json, opts);
  std::string image_b64 = base64_encode(files["image"].content);
  std::string mask_b64;
  bool has_mask = files.count("mask") != 0 && !files["mask"].content.empty();
  if (has_mask) mask_b64 = base64_encode(files["mask"].content);

  for (auto &item : prepared.items) {
    item["image"] = image_b64;
    if (has_mask) item["mask"] = mask_b64;
  }
  return prepared;
}

inline nlohmann::json buildOpenAiImagesResponse(
    const std::vector<std::string> &images_b64) {
  nlohmann::json data = nlohmann::json::array();
  for (const auto &image : images_b64) {
    data.push_back({{"b64_json", image}});
  }
  return {{"created", static_cast<long long>(std::time(nullptr))},
          {"data", std::move(data)}};
}

#endif  // OPENAI_COMPAT_HPP
