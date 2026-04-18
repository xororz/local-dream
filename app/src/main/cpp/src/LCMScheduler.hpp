// LCMScheduler implementation
// Based on HuggingFace diffusers LCMScheduler (scheduling_lcm.py)
#ifndef LCMSCHEDULER_HPP
#define LCMSCHEDULER_HPP

#include <algorithm>
#include <cmath>
#include <optional>
#include <string>
#include <vector>
#include <xtensor/xadapt.hpp>
#include <xtensor/xarray.hpp>
#include <xtensor/xio.hpp>
#include <xtensor/xmanipulation.hpp>
#include <xtensor/xmath.hpp>
#include <xtensor/xrandom.hpp>
#include <xtensor/xview.hpp>

#include "Scheduler.hpp"

class LCMScheduler : public Scheduler {
 public:
  LCMScheduler(int num_train_timesteps, float beta_start, float beta_end,
               const std::string &beta_schedule,
               const std::string &prediction_type, int original_inference_steps,
               float timestep_scaling = 10.0f, bool set_alpha_to_one = true,
               bool rescale_betas_zero_snr = false)
      : num_train_timesteps_(num_train_timesteps),
        beta_start_(beta_start),
        beta_end_(beta_end),
        beta_schedule_(beta_schedule),
        prediction_type_(prediction_type),
        original_inference_steps_(original_inference_steps),
        timestep_scaling_(timestep_scaling),
        set_alpha_to_one_(set_alpha_to_one),
        rescale_betas_zero_snr_(rescale_betas_zero_snr) {
    if (beta_schedule == "linear") {
      betas_ = xt::linspace<float>(beta_start_, beta_end_, num_train_timesteps);
    } else if (beta_schedule == "scaled_linear") {
      float beta_start_sqrt = std::sqrt(beta_start_);
      float beta_end_sqrt = std::sqrt(beta_end_);
      betas_ = xt::pow(xt::linspace<float>(beta_start_sqrt, beta_end_sqrt,
                                           num_train_timesteps),
                       2.0f);
    } else if (beta_schedule == "squaredcos_cap_v2") {
      betas_ = betas_for_alpha_bar(num_train_timesteps);
    } else {
      throw std::runtime_error(beta_schedule + " is not implemented");
    }

    if (rescale_betas_zero_snr_) {
      betas_ = rescale_zero_terminal_snr(betas_);
    }

    alphas_ = 1.0f - betas_;
    alphas_cumprod_ = xt::cumprod(alphas_);

    final_alpha_cumprod_ = set_alpha_to_one_ ? 1.0f : alphas_cumprod_(0);

    init_noise_sigma_ = 1.0f;

    num_inference_steps_ = std::nullopt;
    step_index_ = std::nullopt;
    begin_index_ = std::nullopt;

    // Initialize timesteps as descending range [num_train_timesteps-1, ..., 0]
    auto timesteps_vec = std::vector<float>(num_train_timesteps);
    for (int i = 0; i < num_train_timesteps; ++i) {
      timesteps_vec[i] = float(num_train_timesteps - 1 - i);
    }
    timesteps_ = xt::adapt(timesteps_vec);
  }

  void set_timesteps(int num_inference_steps) override {
    if (num_inference_steps > num_train_timesteps_) {
      throw std::runtime_error(
          "num_inference_steps cannot be larger than num_train_timesteps");
    }
    if (num_inference_steps > original_inference_steps_) {
      throw std::runtime_error(
          "num_inference_steps cannot be larger than original_inference_steps");
    }

    num_inference_steps_ = num_inference_steps;

    // LCM Training/Distillation Steps Schedule
    // k = num_train_timesteps // original_inference_steps
    int k = num_train_timesteps_ / original_inference_steps_;
    // lcm_origin_timesteps = (1, 2, ..., original_inference_steps) * k - 1
    std::vector<int> lcm_origin_timesteps(original_inference_steps_);
    for (int i = 0; i < original_inference_steps_; ++i) {
      lcm_origin_timesteps[i] = (i + 1) * k - 1;
    }

    // Reverse to descending order
    std::reverse(lcm_origin_timesteps.begin(), lcm_origin_timesteps.end());

    // Select approximately evenly spaced indices using
    // np.linspace(0, len, num_inference_steps, endpoint=False) and floor
    std::vector<float> inference_timesteps(num_inference_steps);
    for (int i = 0; i < num_inference_steps; ++i) {
      float idx_f = float(i) * float(lcm_origin_timesteps.size()) /
                    float(num_inference_steps);
      int idx = int(std::floor(idx_f));
      if (idx >= int(lcm_origin_timesteps.size())) {
        idx = int(lcm_origin_timesteps.size()) - 1;
      }
      inference_timesteps[i] = float(lcm_origin_timesteps[idx]);
    }
    timesteps_ = xt::adapt(inference_timesteps);

    step_index_ = std::nullopt;
    begin_index_ = std::nullopt;
  }

  xt::xarray<float> scale_model_input(const xt::xarray<float> &sample,
                                      int /*timestep*/) override {
    // LCM does not require input scaling
    return sample;
  }

  void set_prediction_type(const std::string &prediction_type) override {
    prediction_type_ = prediction_type;
  }

  SchedulerOutput step(const xt::xarray<float> &model_output, int timestep,
                       const xt::xarray<float> &sample) override {
    if (!num_inference_steps_.has_value()) {
      throw std::runtime_error("set_timesteps must be called before stepping");
    }

    if (!step_index_.has_value()) {
      init_step_index(timestep);
    }

    // 1. get previous step value
    int prev_step_index = step_index_.value() + 1;
    int prev_timestep;
    if (prev_step_index < int(timesteps_.size())) {
      prev_timestep = int(timesteps_(prev_step_index));
    } else {
      prev_timestep = timestep;
    }

    // 2. compute alphas, betas
    float alpha_prod_t = alphas_cumprod_(timestep);
    float alpha_prod_t_prev = (prev_timestep >= 0)
                                  ? alphas_cumprod_(prev_timestep)
                                  : final_alpha_cumprod_;

    float beta_prod_t = 1.0f - alpha_prod_t;
    float beta_prod_t_prev = 1.0f - alpha_prod_t_prev;

    // 3. Get scalings for boundary conditions
    auto [c_skip, c_out] =
        get_scalings_for_boundary_condition_discrete(timestep);

    // 4. Compute the predicted original sample x_0 based on the model
    // parameterization
    xt::xarray<float> predicted_original_sample;
    if (prediction_type_ == "epsilon") {
      predicted_original_sample =
          (sample - std::sqrt(beta_prod_t) * model_output) /
          std::sqrt(alpha_prod_t);
    } else if (prediction_type_ == "sample") {
      predicted_original_sample = model_output;
    } else if (prediction_type_ == "v_prediction") {
      predicted_original_sample = std::sqrt(alpha_prod_t) * sample -
                                  std::sqrt(beta_prod_t) * model_output;
    } else {
      throw std::runtime_error(prediction_type_ +
                               " is not implemented for LCMScheduler");
    }

    // 5. (Skip clipping/thresholding - off by default for LCM with SD models)

    // 6. Denoise model output using boundary conditions
    xt::xarray<float> denoised =
        c_out * predicted_original_sample + c_skip * sample;

    // 7. Sample and inject noise z ~ N(0, I) for MultiStep Inference
    // Noise is not used on the final timestep of the timestep schedule.
    xt::xarray<float> prev_sample;
    if (step_index_.value() != int(timesteps_.size()) - 1) {
      xt::xarray<float> noise =
          xt::random::randn<float>(model_output.shape(), 0.0f, 1.0f,
                                   xt::random::get_default_random_engine());
      prev_sample = std::sqrt(alpha_prod_t_prev) * denoised +
                    std::sqrt(beta_prod_t_prev) * noise;
    } else {
      prev_sample = denoised;
    }

    step_index_ = step_index_.value() + 1;

    return {prev_sample, denoised};
  }

  xt::xarray<float> add_noise(const xt::xarray<float> &original_samples,
                              const xt::xarray<float> &noise,
                              const xt::xarray<int> &timesteps) const override {
    xt::xarray<float> sqrt_alpha_prod = xt::zeros<float>({timesteps.size()});
    xt::xarray<float> sqrt_one_minus_alpha_prod =
        xt::zeros<float>({timesteps.size()});
    for (size_t i = 0; i < timesteps.size(); ++i) {
      float a = alphas_cumprod_(timesteps(i));
      sqrt_alpha_prod(i) = std::sqrt(a);
      sqrt_one_minus_alpha_prod(i) = std::sqrt(1.0f - a);
    }

    std::vector<size_t> new_shape = {sqrt_alpha_prod.size(), 1, 1, 1};
    auto reshaped_a = xt::reshape_view(sqrt_alpha_prod, new_shape);
    auto reshaped_b = xt::reshape_view(sqrt_one_minus_alpha_prod, new_shape);

    return reshaped_a * original_samples + reshaped_b * noise;
  }

  void set_begin_index(int begin_index) override { begin_index_ = begin_index; }

  const xt::xarray<float> &get_timesteps() const override { return timesteps_; }

  size_t get_step_index() const override { return step_index_.value_or(0); }

  float get_current_sigma() const override {
    // LCM does not use sigmas; return 0 to keep interface consistent
    return 0.0f;
  }

  float get_init_noise_sigma() const override { return init_noise_sigma_; }

 private:
  int num_train_timesteps_;
  float beta_start_;
  float beta_end_;
  std::string beta_schedule_;
  std::string prediction_type_;
  int original_inference_steps_;
  float timestep_scaling_;
  bool set_alpha_to_one_;
  bool rescale_betas_zero_snr_;

  xt::xarray<float> betas_;
  xt::xarray<float> alphas_;
  xt::xarray<float> alphas_cumprod_;
  float final_alpha_cumprod_;
  float init_noise_sigma_;

  std::optional<int> num_inference_steps_;
  xt::xarray<float> timesteps_;
  std::optional<int> step_index_;
  std::optional<int> begin_index_;

  std::pair<float, float> get_scalings_for_boundary_condition_discrete(
      int timestep) const {
    constexpr float sigma_data = 0.5f;
    float scaled_timestep = float(timestep) * timestep_scaling_;
    float denom = scaled_timestep * scaled_timestep + sigma_data * sigma_data;
    float c_skip = (sigma_data * sigma_data) / denom;
    float c_out = scaled_timestep / std::sqrt(denom);
    return {c_skip, c_out};
  }

  int index_for_timestep(int timestep) const {
    std::vector<size_t> indices;
    for (size_t i = 0; i < timesteps_.size(); ++i) {
      if (int(timesteps_(i)) == timestep) {
        indices.push_back(i);
      }
    }
    if (indices.empty()) {
      return int(timesteps_.size()) - 1;
    } else if (indices.size() > 1) {
      return int(indices[1]);
    } else {
      return int(indices[0]);
    }
  }

  void init_step_index(int timestep) {
    if (begin_index_.has_value()) {
      step_index_ = begin_index_.value();
    } else {
      step_index_ = index_for_timestep(timestep);
    }
  }

  xt::xarray<float> betas_for_alpha_bar(int num_diffusion_timesteps,
                                        float max_beta = 0.999f) const {
    auto alpha_bar_fn = [](float t) -> float {
      float v = std::cos((t + 0.008f) / 1.008f * float(M_PI) / 2.0f);
      return v * v;
    };

    xt::xarray<float> betas = xt::zeros<float>({num_diffusion_timesteps});
    for (int i = 0; i < num_diffusion_timesteps; ++i) {
      float t1 = float(i) / float(num_diffusion_timesteps);
      float t2 = float(i + 1) / float(num_diffusion_timesteps);
      betas(i) = std::min(1.0f - alpha_bar_fn(t2) / alpha_bar_fn(t1), max_beta);
    }
    return betas;
  }

  xt::xarray<float> rescale_zero_terminal_snr(
      const xt::xarray<float> &betas) const {
    xt::xarray<float> alphas = 1.0f - betas;
    xt::xarray<float> alphas_cumprod = xt::cumprod(alphas);
    xt::xarray<float> alphas_bar_sqrt = xt::sqrt(alphas_cumprod);

    float alphas_bar_sqrt_0 = alphas_bar_sqrt(0);
    float alphas_bar_sqrt_T = alphas_bar_sqrt(alphas_bar_sqrt.size() - 1);

    alphas_bar_sqrt = alphas_bar_sqrt - alphas_bar_sqrt_T;
    alphas_bar_sqrt = alphas_bar_sqrt * alphas_bar_sqrt_0 /
                      (alphas_bar_sqrt_0 - alphas_bar_sqrt_T);

    xt::xarray<float> alphas_bar = alphas_bar_sqrt * alphas_bar_sqrt;

    xt::xarray<float> new_alphas = xt::zeros<float>({alphas_bar.size()});
    new_alphas(0) = alphas_bar(0);
    for (size_t i = 1; i < alphas_bar.size(); ++i) {
      new_alphas(i) = alphas_bar(i) / alphas_bar(i - 1);
    }

    xt::xarray<float> new_betas = 1.0f - new_alphas;
    return new_betas;
  }
};

#endif  // LCMSCHEDULER_HPP
