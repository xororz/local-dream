#ifndef LAPLACIAN_BLEND_HPP
#define LAPLACIAN_BLEND_HPP

#include <algorithm>
#include <cmath>
#include <vector>
#include <xtensor/xadapt.hpp>
#include <xtensor/xarray.hpp>
#include <xtensor/xview.hpp>

inline xt::xarray<float> pyrDown(const xt::xarray<float> &img) {
  auto shape = img.shape();
  int h = shape[1];
  int w = shape[2];
  int new_h = h / 2;
  int new_w = w / 2;

  std::vector<float> kernel = {1.0f / 16, 4.0f / 16, 6.0f / 16, 4.0f / 16,
                               1.0f / 16};

  int channels = shape[0];
  std::vector<int> result_shape = {channels, new_h, new_w};
  xt::xarray<float> result = xt::zeros<float>(result_shape);

  for (int c = 0; c < channels; ++c) {
    for (int y = 0; y < new_h; ++y) {
      for (int x = 0; x < new_w; ++x) {
        float val = 0.0f;
        for (int ky = -2; ky <= 2; ++ky) {
          for (int kx = -2; kx <= 2; ++kx) {
            int src_y = std::min(std::max(y * 2 + ky, 0), h - 1);
            int src_x = std::min(std::max(x * 2 + kx, 0), w - 1);
            val += img(c, src_y, src_x) * kernel[ky + 2] * kernel[kx + 2];
          }
        }
        result(c, y, x) = val;
      }
    }
  }
  return result;
}

inline xt::xarray<float> pyrUp(const xt::xarray<float> &img, int target_h,
                               int target_w) {
  auto shape = img.shape();
  int h = shape[1];
  int w = shape[2];

  std::vector<float> kernel = {1.0f / 16, 4.0f / 16, 6.0f / 16, 4.0f / 16,
                               1.0f / 16};

  int channels = shape[0];
  std::vector<int> result_shape = {channels, target_h, target_w};
  xt::xarray<float> result = xt::zeros<float>(result_shape);

  for (int c = 0; c < channels; ++c) {
    for (int y = 0; y < target_h; ++y) {
      for (int x = 0; x < target_w; ++x) {
        float val = 0.0f;
        for (int ky = -2; ky <= 2; ++ky) {
          for (int kx = -2; kx <= 2; ++kx) {
            int src_y = (y - ky) / 2;
            int src_x = (x - kx) / 2;

            if ((y - ky) % 2 == 0 && (x - kx) % 2 == 0 && src_y >= 0 &&
                src_y < h && src_x >= 0 && src_x < w) {
              val +=
                  img(c, src_y, src_x) * kernel[ky + 2] * kernel[kx + 2] * 4.0f;
            }
          }
        }
        result(c, y, x) = val;
      }
    }
  }
  return result;
}

inline xt::xarray<float> laplacianPyramidBlend(const xt::xarray<float> &img1,
                                               const xt::xarray<float> &img2,
                                               const xt::xarray<float> &mask) {
  auto shape = img1.shape();
  int height = shape[1];
  int width = shape[2];

  int min_size = std::min(height, width);
  int num_levels = std::floor(std::log2(min_size)) - 3;
  num_levels = std::max(num_levels, 2);

  while ((min_size >> num_levels) < 4) {
    num_levels--;
  }
  num_levels = std::max(num_levels, 1);

  std::vector<xt::xarray<float>> gauss_pyr1, gauss_pyr2, gauss_pyr_mask;

  gauss_pyr1.push_back(img1);
  gauss_pyr2.push_back(img2);
  gauss_pyr_mask.push_back(mask);

  for (int i = 1; i < num_levels; ++i) {
    gauss_pyr1.push_back(pyrDown(gauss_pyr1[i - 1]));
    gauss_pyr2.push_back(pyrDown(gauss_pyr2[i - 1]));
    gauss_pyr_mask.push_back(pyrDown(gauss_pyr_mask[i - 1]));
  }

  std::vector<xt::xarray<float>> laplace_pyr1, laplace_pyr2;

  for (int i = 0; i < num_levels - 1; ++i) {
    auto up = pyrUp(gauss_pyr1[i + 1], gauss_pyr1[i].shape()[1],
                    gauss_pyr1[i].shape()[2]);
    laplace_pyr1.push_back(gauss_pyr1[i] - up);

    up = pyrUp(gauss_pyr2[i + 1], gauss_pyr2[i].shape()[1],
               gauss_pyr2[i].shape()[2]);
    laplace_pyr2.push_back(gauss_pyr2[i] - up);
  }
  laplace_pyr1.push_back(gauss_pyr1[num_levels - 1]);
  laplace_pyr2.push_back(gauss_pyr2[num_levels - 1]);

  std::vector<xt::xarray<float>> blended_pyr;
  for (int i = 0; i < num_levels; ++i) {
    auto mask_expanded =
        xt::broadcast(gauss_pyr_mask[i], laplace_pyr1[i].shape());
    blended_pyr.push_back(laplace_pyr1[i] * (1.0f - mask_expanded) +
                          laplace_pyr2[i] * mask_expanded);
  }

  xt::xarray<float> result = blended_pyr[num_levels - 1];
  for (int i = num_levels - 2; i >= 0; --i) {
    result =
        pyrUp(result, blended_pyr[i].shape()[1], blended_pyr[i].shape()[2]) +
        blended_pyr[i];
  }

  return result;
}

#endif  // LAPLACIAN_BLEND_HPP
