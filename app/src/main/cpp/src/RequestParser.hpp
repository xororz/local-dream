#ifndef REQUESTPARSER_HPP
#define REQUESTPARSER_HPP

#include <algorithm>
#include <chrono>
#include <string>
#include <vector>
#include <xtensor/xadapt.hpp>
#include <xtensor/xarray.hpp>
#include <xtensor/xeval.hpp>
#include <xtensor/xmanipulation.hpp>
#include <xtensor/xmath.hpp>
#include <xtensor/xview.hpp>

#include "Pipeline.hpp"
#include "SDUtils.hpp"
#include "json.hpp"

// Builds a GenerationRequest from the /generate JSON body: scalar params,
// base64 image/mask decoding, the SDXL aspect-ratio padding setup
// (synthetic canvas, paint-rectangle mask synthesis / intersection), and the
// ultrafix (tiled img2img) validation.
inline GenerationRequest parseGenerationRequest(const nlohmann::json &json,
                                                bool sdxl, bool anima,
                                                bool img2img_available,
                                                bool reference_edit_available,
                                                bool ultrafix_supported) {
  GenerationRequest req;

  if (!json.contains("prompt")) throw std::invalid_argument("Missing 'prompt'");
  req.prompt = json["prompt"].get<std::string>();
  req.negative_prompt = json.value("negative_prompt", "");
  req.steps = json.value("steps", 20);
  req.cfg = json.value("cfg", 7.5f);
  req.scheduler_type = json.value("scheduler", "dpm");
  req.use_opencl = json.value("use_opencl", false);
  req.show_diffusion_process = json.value("show_diffusion_process", false);
  req.show_diffusion_stride = json.value("show_diffusion_stride", 1);
  req.seed = json.value(
      "seed", (unsigned)hashSeed(
                  std::chrono::system_clock::now().time_since_epoch().count()));
  req.width = json.value("width", 512);
  req.height = json.value("height", 512);
  if (json.contains("size")) {
    int size = json.value("size", 512);
    req.width = size;
    req.height = size;
  }

  // --- Ultrafix: tiled img2img over a large upscaled image ----------------
  // width/height stay at the full image size; the UNet/VAE graphs run at the
  // fixed tile size and the loop blends overlapping tiles, so the only hard
  // requirements are 8-alignment and that every fixed-size tile fits.
  req.ultrafix = json.value("ultrafix", false);
  if (req.ultrafix) {
    if (!ultrafix_supported)
      throw std::invalid_argument("ultrafix not supported by this backend");
    if (!json.contains("image"))
      throw std::invalid_argument("ultrafix requires 'image'");
    if (json.contains("mask"))
      throw std::invalid_argument("ultrafix does not support 'mask'");
    req.ultrafix_tile = sdxl ? 1024 : json.value("tile_size", 512);
    if (req.ultrafix_tile <= 0 || req.ultrafix_tile % 8 != 0)
      throw std::invalid_argument("Invalid ultrafix tile_size");
    if (req.width % 8 != 0 || req.height % 8 != 0)
      throw std::invalid_argument("ultrafix size must be a multiple of 8");
    // Both the UNet tile and the 512px (SD1.5) / 1024px (SDXL) VAE tile must
    // fit inside the image.
    int min_dim = std::max(req.ultrafix_tile, sdxl ? 1024 : 512);
    if (std::min(req.width, req.height) < min_dim)
      throw std::invalid_argument("ultrafix image shorter edge must be >= " +
                                  std::to_string(min_dim));
    if (std::max(req.width, req.height) > 8192)
      throw std::invalid_argument("ultrafix image too large (max 8192)");
    // A per-step preview would tile-decode the whole image every step.
    req.show_diffusion_process = false;
  }

  // SDXL and Anima both run fixed-1024 graphs; force the canvas regardless of
  // what the client sent so a stale/wrong size can't reach the QNN graphs.
  // (Anima has no ultrafix path, so the !ultrafix guard is moot there.)
  if ((sdxl || anima) && !req.ultrafix) {
    req.width = 1024;
    req.height = 1024;
  }
  req.denoise_strength = json.value("denoise_strength", 0.6f);

  auto sanitize_format = [](std::string f) {
    return (f == "jpeg" || f == "png") ? f : std::string("raw");
  };
  req.preview_format = sanitize_format(json.value("preview_format", "raw"));
  req.output_format = sanitize_format(json.value("output_format", "raw"));

  // Native multimodal edit inputs. Keep their aspect ratios: FLUX.2 encodes
  // them as independent clean reference latents and positions them with its
  // reference-token RoPE scheme. No count limit: each reference lengthens the
  // DiT sequence, so memory, not the parser, bounds how many fit.
  if (json.contains("reference_images")) {
    if (!reference_edit_available)
      throw std::invalid_argument("reference editing not available (disabled)");
    const auto &refs = json["reference_images"];
    if (!refs.is_array())
      throw std::invalid_argument("'reference_images' must be an array");
    if (refs.empty())
      throw std::invalid_argument("reference_images must not be empty");
    req.reference_images.reserve(refs.size());
    for (const auto &encoded : refs) {
      if (!encoded.is_string())
        throw std::invalid_argument("reference image must be base64 text");
      try {
        const std::string dec_str = base64_decode(encoded.get<std::string>());
        const std::vector<uint8_t> dec_buf(dec_str.begin(), dec_str.end());
        ReferenceImage ref;
        decode_reference_image(dec_buf, ref.rgb, ref.width, ref.height);
        if (ref.rgb.empty() || ref.width <= 0 || ref.height <= 0)
          throw std::runtime_error("Reference image decode failed");
        req.reference_images.push_back(std::move(ref));
      } catch (const std::exception &e) {
        throw std::invalid_argument("Err proc reference image: " +
                                    std::string(e.what()));
      }
    }
  }

  const int sample_w = req.width / 8;
  const int sample_h = req.height / 8;
  // Latent channel count of the target format: SD/SDXL = 4, Anima = 16. The
  // latent-space inpaint mask is replicated across every channel, so it must be
  // sized to match (Pipeline reads it as {1, latent_ch, h, w}).
  const int latent_ch = anima ? anima_latent_channels : 4;

  // --- Fixed-1024 aspect ratio: parse target dims first ------------------
  // SDXL and Anima both render on a fixed 1024 canvas and reach non-1:1
  // outputs by inpainting a centered crop. Resolve target_crop_w/h from
  // aspect_ratio independently of img/mask presence so all three modes
  // (txt2img / img2img / inpaint) share the same downstream crop-after-decode
  // behavior. Requires a VAE encoder so the synthetic black canvas can be
  // encoded as the inpaint base latent; if the build was started without one,
  // fall through to plain 1024x1024 generation.
  if ((sdxl || anima) && json.contains("aspect_ratio") && img2img_available &&
      !req.ultrafix) {
    std::string ar = json["aspect_ratio"].get<std::string>();
    auto colon = ar.find(':');
    if (colon != std::string::npos) {
      try {
        int rw = std::stoi(ar.substr(0, colon));
        int rh = std::stoi(ar.substr(colon + 1));
        if (rw > 0 && rh > 0 && !(rw == rh)) {
          int tw, th;
          if (rw >= rh) {
            tw = 1024;
            th = (int)((1024.0 * rh) / rw);
            th = (th / 8) * 8;
            if (th < 8) th = 8;
          } else {
            th = 1024;
            tw = (int)((1024.0 * rw) / rh);
            tw = (tw / 8) * 8;
            if (tw < 8) tw = 8;
          }
          req.target_crop_width = tw;
          req.target_crop_height = th;
          req.aspect_pad_inpaint = true;
        }
      } catch (...) {
        // Bad aspect_ratio string, ignore and proceed with 1:1.
      }
    }
  }

  // Paint rectangle = target + short-axis pad. Shared by the synthetic
  // white-on-black base image and the aspect padding mask so both stay
  // strictly aligned. Only computed when aspect padding is in effect.
  const int kAspectPadPx = 8;
  int paint_w = req.target_crop_width;
  int paint_h = req.target_crop_height;
  int paint_x0 = 0, paint_y0 = 0;
  if (req.aspect_pad_inpaint) {
    if (req.target_crop_width < req.width)
      paint_w = std::min(req.width, req.target_crop_width + 2 * kAspectPadPx);
    if (req.target_crop_height < req.height)
      paint_h = std::min(req.height, req.target_crop_height + 2 * kAspectPadPx);
    paint_x0 = (req.width - paint_w) / 2;
    paint_y0 = (req.height - paint_h) / 2;
  }

  // --- Base image: user-supplied or synthetic ----------------------------
  if (json.contains("image")) {
    req.img2img = true;
    std::string img_b64 = json["image"].get<std::string>();
    try {
      std::string dec_str = base64_decode(img_b64);
      std::vector<uint8_t> dec_buf(dec_str.begin(), dec_str.end());
      std::vector<uint8_t> dec_pix;
      decode_image(dec_buf, dec_pix, req.width, req.height);
      if (dec_pix.size() != (size_t)3 * req.width * req.height)
        throw std::runtime_error("Img size mismatch");
      std::vector<int> img_shape = {1, req.height, req.width, 3};
      xt::xarray<uint8_t> xt_u8 = xt::adapt(dec_pix, img_shape);
      xt::xarray<float> xt_f = xt::cast<float>(xt_u8);
      xt_f = xt::eval(xt_f / 127.5f - 1.0f);
      xt_f = xt::transpose(xt_f, {0, 3, 1, 2});
      req.img_data.assign(xt_f.begin(), xt_f.end());
    } catch (const std::exception &e) {
      throw std::invalid_argument("Err proc img: " + std::string(e.what()));
    }
  } else if (req.aspect_pad_inpaint) {
    // No user image but aspect padding requested: synthesise the
    // white-on-black canvas as the inpaint base. Outer ring = black
    // (value -1) to signal "edge"; center paint region = white (value
    // +1) to hint "content". The white region extends `kAspectPadPx`
    // pixels past the crop along the short axis so the mask boundary
    // never coincides with the latent's black->white transition; the
    // pad area gets generated but is cropped away on output.
    // This is also the only path eligible for the per-target
    // VAE-encoder cache.
    req.aspect_pad_synthetic_base = true;
    size_t img_total = 3 * (size_t)req.width * req.height;
    req.img_data.assign(img_total, -1.0f);
    for (int c = 0; c < 3; ++c) {
      for (int y = paint_y0; y < paint_y0 + paint_h; ++y) {
        float *row =
            req.img_data.data() + ((size_t)c * req.height + y) * req.width;
        for (int x = paint_x0; x < paint_x0 + paint_w; ++x) row[x] = 1.0f;
      }
    }
    req.img2img = true;
    // Pure txt2img through the inpaint pipeline: fully renoise.
    req.denoise_strength = 1.0f;
  }

  // --- Mask: user-supplied, possibly intersected with aspect mask --------
  if (json.contains("mask")) {
    try {
      if (!req.img2img) throw std::runtime_error("mask requires image");
      req.has_mask = true;
      req.user_supplied_mask = true;
      std::string mask_b64 = json["mask"].get<std::string>();
      std::string dec_mask_str = base64_decode(mask_b64);
      std::vector<uint8_t> dec_mask_buf(dec_mask_str.begin(),
                                        dec_mask_str.end());
      std::vector<uint8_t> mask_pix_lat_rgb, mask_pix_full_rgb;
      decode_image(dec_mask_buf, mask_pix_lat_rgb, sample_w, sample_h);
      decode_image(dec_mask_buf, mask_pix_full_rgb, req.width, req.height);
      if (mask_pix_lat_rgb.empty() || mask_pix_full_rgb.empty())
        throw std::runtime_error("Mask decode empty");
      std::vector<int> mlat_shape = {sample_h, sample_w, 3};
      xt::xarray<uint8_t> xmlat_u8 = xt::adapt(mask_pix_lat_rgb, mlat_shape);
      xt::xarray<float> xmlat_f = xt::mean(xt::cast<float>(xmlat_u8), {2});
      xmlat_f = xt::eval(xmlat_f / 255.0f);
      // Replicate the single-channel spatial mask across all latent channels.
      const size_t plane = (size_t)sample_h * sample_w;
      req.mask_data.resize((size_t)latent_ch * plane);
      for (int c = 0; c < latent_ch; ++c)
        std::copy(xmlat_f.begin(), xmlat_f.end(),
                  req.mask_data.begin() + (size_t)c * plane);

      std::vector<int> mfull_shape = {req.height, req.width, 3};
      xt::xarray<uint8_t> xmfull_u8 = xt::adapt(mask_pix_full_rgb, mfull_shape);
      xt::xarray<float> xmfull_f = xt::mean(xt::cast<float>(xmfull_u8), {2});
      xmfull_f = xt::eval(xmfull_f / 255.0f);
      xmfull_f = xt::reshape_view(xmfull_f, {1, 1, req.height, req.width});
      xt::xarray<float> xmfull_f_3 =
          xt::concatenate(xt::xtuple(xmfull_f, xmfull_f, xmfull_f), 1);
      req.mask_data_full.assign(xmfull_f_3.begin(), xmfull_f_3.end());
    } catch (const std::exception &e) {
      throw std::invalid_argument("Err proc mask: " + std::string(e.what()));
    }
  }

  // --- Aspect padding mask ------------------------------------------------
  // Install or intersect with the centered paint rectangle (computed
  // above). If a user mask was supplied we zero out everything outside
  // it so the user can never paint outside the visible crop area;
  // otherwise we install the paint rect directly so the outer black
  // border is preserved through every diffusion step. Latent (1/8)
  // bounds use floor(origin) and ceil(end) to fully cover the
  // pixel-space paint rect.
  if (req.aspect_pad_inpaint) {
    int lx0 = paint_x0 / 8;
    int ly0 = paint_y0 / 8;
    int lx1 = std::min(sample_w, (paint_x0 + paint_w + 7) / 8);
    int ly1 = std::min(sample_h, (paint_y0 + paint_h + 7) / 8);

    if (req.has_mask) {
      // Zero out everything outside the paint rectangle.
      for (int c = 0; c < latent_ch; ++c) {
        for (int y = 0; y < sample_h; ++y) {
          float *row =
              req.mask_data.data() + ((size_t)c * sample_h + y) * sample_w;
          if (y < ly0 || y >= ly1) {
            std::fill(row, row + sample_w, 0.0f);
          } else {
            std::fill(row, row + lx0, 0.0f);
            std::fill(row + lx1, row + sample_w, 0.0f);
          }
        }
      }
      for (int c = 0; c < 3; ++c) {
        for (int y = 0; y < req.height; ++y) {
          float *row = req.mask_data_full.data() +
                       ((size_t)c * req.height + y) * req.width;
          if (y < paint_y0 || y >= paint_y0 + paint_h) {
            std::fill(row, row + req.width, 0.0f);
          } else {
            std::fill(row, row + paint_x0, 0.0f);
            std::fill(row + paint_x0 + paint_w, row + req.width, 0.0f);
          }
        }
      }
    } else {
      // No user mask: aspect mask alone, full opacity in the paint rect.
      req.mask_data.assign((size_t)latent_ch * sample_w * sample_h, 0.0f);
      for (int c = 0; c < latent_ch; ++c) {
        for (int y = ly0; y < ly1; ++y) {
          float *row =
              req.mask_data.data() + ((size_t)c * sample_h + y) * sample_w;
          for (int x = lx0; x < lx1; ++x) row[x] = 1.0f;
        }
      }
      req.mask_data_full.assign((size_t)3 * req.width * req.height, 0.0f);
      for (int c = 0; c < 3; ++c) {
        for (int y = paint_y0; y < paint_y0 + paint_h; ++y) {
          float *row = req.mask_data_full.data() +
                       ((size_t)c * req.height + y) * req.width;
          for (int x = paint_x0; x < paint_x0 + paint_w; ++x) row[x] = 1.0f;
        }
      }
      req.has_mask = true;
    }
  }

  return req;
}

#endif  // REQUESTPARSER_HPP
