// EulerAncestralDiscreteScheduler implementation
// Based on HuggingFace diffusers EulerAncestralDiscreteScheduler
#include <cmath>
#include <optional>
#include <random>
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

class EulerAncestralDiscreteScheduler : public Scheduler {
 public:
  EulerAncestralDiscreteScheduler(int num_train_timesteps, float beta_start,
                                  float beta_end,
                                  const std::string &beta_schedule,
                                  const std::string &prediction_type,
                                  const std::string &timestep_spacing,
                                  int steps_offset = 0,
                                  bool rescale_betas_zero_snr = false)
      : num_train_timesteps_(num_train_timesteps),
        beta_start_(beta_start),
        beta_end_(beta_end),
        beta_schedule_(beta_schedule),
        prediction_type_(prediction_type),
        timestep_spacing_(timestep_spacing),
        steps_offset_(steps_offset),
        rescale_betas_zero_snr_(rescale_betas_zero_snr),
        is_scale_input_called_(false) {
    // Initialize betas
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

    if (rescale_betas_zero_snr_) {
      alphas_cumprod_(alphas_cumprod_.size() - 1) = std::pow(2.0f, -24.0f);
    }

    // Calculate sigmas
    sigmas_ = xt::sqrt((1.0f - alphas_cumprod_) / alphas_cumprod_);

    // Reverse sigmas and append 0
    auto sigmas_vec = std::vector<float>(sigmas_.size() + 1);
    for (size_t i = 0; i < sigmas_.size(); ++i) {
      sigmas_vec[i] = sigmas_(sigmas_.size() - 1 - i);
    }
    sigmas_vec[sigmas_.size()] = 0.0f;
    sigmas_ = xt::adapt(sigmas_vec);

    // Initialize timesteps
    auto timesteps_vec = std::vector<float>(num_train_timesteps);
    for (int i = 0; i < num_train_timesteps; ++i) {
      timesteps_vec[i] = float(num_train_timesteps - 1 - i);
    }
    timesteps_ = xt::adapt(timesteps_vec);

    num_inference_steps_ = std::nullopt;
    step_index_ = std::nullopt;
    begin_index_ = std::nullopt;
  }

  void set_timesteps(int num_inference_steps) override {
    num_inference_steps_ = num_inference_steps;

    if (timestep_spacing_ == "linspace") {
      auto timesteps_vec = std::vector<float>(num_inference_steps);
      for (int i = 0; i < num_inference_steps; ++i) {
        timesteps_vec[i] = float(num_train_timesteps_ - 1) -
                           float(i) * float(num_train_timesteps_ - 1) /
                               float(num_inference_steps - 1);
      }
      timesteps_ = xt::adapt(timesteps_vec);
    } else if (timestep_spacing_ == "leading") {
      int step_ratio = num_train_timesteps_ / num_inference_steps;
      auto timesteps_vec = std::vector<float>(num_inference_steps);
      for (int i = 0; i < num_inference_steps; ++i) {
        timesteps_vec[i] =
            float((num_inference_steps - 1 - i) * step_ratio + steps_offset_);
      }
      timesteps_ = xt::adapt(timesteps_vec);
    } else if (timestep_spacing_ == "trailing") {
      float step_ratio =
          float(num_train_timesteps_) / float(num_inference_steps);
      auto timesteps_vec = std::vector<float>(num_inference_steps);
      for (int i = 0; i < num_inference_steps; ++i) {
        timesteps_vec[i] = std::round(float(num_train_timesteps_) -
                                      float(i + 1) * step_ratio - 1.0f);
      }
      timesteps_ = xt::adapt(timesteps_vec);
    } else {
      throw std::runtime_error(timestep_spacing_ + " is not supported");
    }

    // Calculate sigmas from alphas_cumprod (match PyTorch exactly)
    // sigmas = np.array(((1 - alphas_cumprod) / alphas_cumprod) ** 0.5)
    auto base_sigmas_vec = std::vector<float>(num_train_timesteps_);
    for (int i = 0; i < num_train_timesteps_; ++i) {
      float alpha_cumprod = alphas_cumprod_(i);
      base_sigmas_vec[i] = std::sqrt((1.0f - alpha_cumprod) / alpha_cumprod);
    }

    // Interpolate sigmas using np.interp logic
    // sigmas = np.interp(timesteps, np.arange(0, len(sigmas)), sigmas)
    auto sigmas_vec = std::vector<float>(num_inference_steps + 1);
    for (int i = 0; i < num_inference_steps; ++i) {
      float t = timesteps_(i);

      // Linear interpolation
      if (t <= 0.0f) {
        sigmas_vec[i] = base_sigmas_vec[0];
      } else if (t >= float(num_train_timesteps_ - 1)) {
        sigmas_vec[i] = base_sigmas_vec[num_train_timesteps_ - 1];
      } else {
        int t_floor = int(std::floor(t));
        int t_ceil = int(std::ceil(t));
        float weight = t - float(t_floor);
        sigmas_vec[i] = base_sigmas_vec[t_floor] * (1.0f - weight) +
                        base_sigmas_vec[t_ceil] * weight;
      }
    }
    sigmas_vec[num_inference_steps] = 0.0f;
    sigmas_ = xt::adapt(sigmas_vec);

    step_index_ = std::nullopt;
    begin_index_ = std::nullopt;
    is_scale_input_called_ = false;
  }

  xt::xarray<float> scale_model_input(const xt::xarray<float> &sample,
                                      int timestep) override {
    if (!step_index_.has_value()) {
      init_step_index(timestep);
    }

    float sigma = sigmas_(step_index_.value());
    xt::xarray<float> scaled_sample = sample / std::sqrt(sigma * sigma + 1.0f);
    is_scale_input_called_ = true;
    return scaled_sample;
  }

  SchedulerOutput step(const xt::xarray<float> &model_output, int timestep,
                       const xt::xarray<float> &sample) override {
    if (!num_inference_steps_.has_value()) {
      throw std::runtime_error("set_timesteps must be called before stepping");
    }

    if (!step_index_.has_value()) {
      init_step_index(timestep);
    }

    float sigma = sigmas_(step_index_.value());

    // Compute predicted original sample (x_0)
    xt::xarray<float> pred_original_sample;
    if (prediction_type_ == "epsilon") {
      pred_original_sample = sample - sigma * model_output;
    } else if (prediction_type_ == "v_prediction") {
      pred_original_sample =
          model_output * (-sigma / std::sqrt(sigma * sigma + 1.0f)) +
          (sample / (sigma * sigma + 1.0f));
    } else if (prediction_type_ == "sample") {
      pred_original_sample = model_output;
    } else {
      throw std::runtime_error(prediction_type_ +
                               " is not implemented for "
                               "EulerAncestralDiscreteScheduler");
    }

    float sigma_from = sigmas_(step_index_.value());
    float sigma_to = sigmas_(step_index_.value() + 1);
    float sigma_up = std::sqrt(sigma_to * sigma_to *
                               (sigma_from * sigma_from - sigma_to * sigma_to) /
                               (sigma_from * sigma_from));
    float sigma_down = std::sqrt(sigma_to * sigma_to - sigma_up * sigma_up);

    // Compute derivative
    xt::xarray<float> derivative = (sample - pred_original_sample) / sigma;

    float dt = sigma_down - sigma;

    xt::xarray<float> prev_sample = sample + derivative * dt;

    // Add noise (ancestral sampling) - always add noise like PyTorch version
    xt::xarray<float> noise =
        xt::random::randn<float>(model_output.shape(), 0.0f, 1.0f,
                                 xt::random::get_default_random_engine());
    prev_sample = prev_sample + noise * sigma_up;

    step_index_ = step_index_.value() + 1;
    is_scale_input_called_ = false;

    return {prev_sample, pred_original_sample};
  }

  // Overload of step() that accepts external noise for reproducibility
  SchedulerOutput step_with_noise(const xt::xarray<float> &model_output,
                                  int timestep, const xt::xarray<float> &sample,
                                  const xt::xarray<float> &noise) {
    if (!num_inference_steps_.has_value()) {
      throw std::runtime_error("set_timesteps must be called before stepping");
    }

    if (!step_index_.has_value()) {
      init_step_index(timestep);
    }

    float sigma = sigmas_(step_index_.value());

    // Compute predicted original sample (x_0)
    xt::xarray<float> pred_original_sample;
    if (prediction_type_ == "epsilon") {
      pred_original_sample = sample - sigma * model_output;
    } else if (prediction_type_ == "v_prediction") {
      pred_original_sample =
          model_output * (-sigma / std::sqrt(sigma * sigma + 1.0f)) +
          (sample / (sigma * sigma + 1.0f));
    } else if (prediction_type_ == "sample") {
      pred_original_sample = model_output;
    } else {
      throw std::runtime_error(prediction_type_ +
                               " is not implemented for "
                               "EulerAncestralDiscreteScheduler");
    }

    float sigma_from = sigmas_(step_index_.value());
    float sigma_to = sigmas_(step_index_.value() + 1);
    float sigma_up = std::sqrt(sigma_to * sigma_to *
                               (sigma_from * sigma_from - sigma_to * sigma_to) /
                               (sigma_from * sigma_from));
    float sigma_down = std::sqrt(sigma_to * sigma_to - sigma_up * sigma_up);

    // Compute derivative
    xt::xarray<float> derivative = (sample - pred_original_sample) / sigma;

    float dt = sigma_down - sigma;

    xt::xarray<float> prev_sample = sample + derivative * dt;

    // Add noise (ancestral sampling) - use provided noise for reproducibility
    prev_sample = prev_sample + noise * sigma_up;

    step_index_ = step_index_.value() + 1;
    is_scale_input_called_ = false;

    return {prev_sample, pred_original_sample};
  }

  xt::xarray<float> add_noise(const xt::xarray<float> &original_samples,
                              const xt::xarray<float> &noise,
                              const xt::xarray<int> &timesteps) const override {
    std::vector<int> step_indices;

    if (!begin_index_.has_value()) {
      for (size_t i = 0; i < timesteps.size(); ++i) {
        step_indices.push_back(index_for_timestep(timesteps(i)));
      }
    } else if (step_index_.has_value()) {
      step_indices.resize(timesteps.size(), step_index_.value());
    } else {
      step_indices.resize(timesteps.size(), begin_index_.value());
    }

    xt::xarray<float> sigma = xt::zeros<float>({step_indices.size()});
    for (size_t i = 0; i < step_indices.size(); ++i) {
      sigma(i) = sigmas_(step_indices[i]);
    }

    std::vector<size_t> new_shape = {sigma.size(), 1, 1, 1};
    auto reshaped_sigma = xt::reshape_view(sigma, new_shape);

    xt::xarray<float> noisy_samples = original_samples + noise * reshaped_sigma;
    return noisy_samples;
  }

  void set_begin_index(int begin_index) override { begin_index_ = begin_index; }

  void set_prediction_type(const std::string &prediction_type) override {
    prediction_type_ = prediction_type;
  }

  const xt::xarray<float> &get_timesteps() const override { return timesteps_; }

  size_t get_step_index() const override { return step_index_.value_or(0); }

  float get_current_sigma() const override {
    if (!step_index_.has_value()) {
      return sigmas_(0);
    }
    return sigmas_(std::min<int>(step_index_.value(), int(sigmas_.size()) - 1));
  }

  float get_init_noise_sigma() const override {
    // Standard deviation of the initial noise distribution
    if (timestep_spacing_ == "linspace" || timestep_spacing_ == "trailing") {
      return xt::amax(sigmas_)();
    }
    // For "leading" spacing
    float max_sigma = xt::amax(sigmas_)();
    return std::sqrt(max_sigma * max_sigma + 1.0f);
  }

 private:
  int num_train_timesteps_;
  float beta_start_;
  float beta_end_;
  std::string beta_schedule_;
  std::string prediction_type_;
  std::string timestep_spacing_;
  int steps_offset_;
  bool rescale_betas_zero_snr_;
  bool is_scale_input_called_;

  xt::xarray<float> betas_;
  xt::xarray<float> alphas_;
  xt::xarray<float> alphas_cumprod_;
  xt::xarray<float> sigmas_;

  std::optional<int> num_inference_steps_;
  xt::xarray<float> timesteps_;
  std::optional<int> step_index_;
  std::optional<int> begin_index_;

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
      return std::cos((t + 0.008f) / 1.008f * M_PI / 2.0f) *
             std::cos((t + 0.008f) / 1.008f * M_PI / 2.0f);
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
