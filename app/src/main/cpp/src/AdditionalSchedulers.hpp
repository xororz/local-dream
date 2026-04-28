#ifndef ADDITIONAL_SCHEDULERS_HPP
#define ADDITIONAL_SCHEDULERS_HPP

#include <algorithm>
#include <cmath>
#include <optional>
#include <string>
#include <vector>
#include <xtensor/xadapt.hpp>
#include <xtensor/xarray.hpp>
#include <xtensor/xbuilder.hpp>
#include <xtensor/xmath.hpp>
#include <xtensor/xrandom.hpp>
#include <xtensor/xview.hpp>

#include "Scheduler.hpp"

class EulerDiscreteScheduler : public Scheduler {
 public:
  EulerDiscreteScheduler(int num_train_timesteps, float beta_start,
                         float beta_end, const std::string &beta_schedule,
                         const std::string &prediction_type,
                         const std::string &timestep_spacing,
                         bool use_second_order = false,
                         bool add_churn_noise = false)
      : num_train_timesteps_(num_train_timesteps),
        beta_start_(beta_start),
        beta_end_(beta_end),
        beta_schedule_(beta_schedule),
        prediction_type_(prediction_type),
        timestep_spacing_(timestep_spacing),
        use_second_order_(use_second_order),
        add_churn_noise_(add_churn_noise) {
    if (beta_schedule == "linear") {
      betas_ = xt::linspace<float>(beta_start_, beta_end_, num_train_timesteps_);
    } else if (beta_schedule == "scaled_linear") {
      float beta_start_sqrt = std::sqrt(beta_start_);
      float beta_end_sqrt = std::sqrt(beta_end_);
      betas_ = xt::pow(xt::linspace<float>(beta_start_sqrt, beta_end_sqrt,
                                           num_train_timesteps_),
                       2.0f);
    } else {
      throw std::runtime_error(beta_schedule + " is not implemented");
    }

    alphas_ = 1.0f - betas_;
    alphas_cumprod_ = xt::cumprod(alphas_);
    sigmas_ = xt::sqrt((1.0f - alphas_cumprod_) / alphas_cumprod_);
    timesteps_ = xt::zeros<float>({1});
  }

  void set_timesteps(int num_inference_steps) override {
    num_inference_steps_ = num_inference_steps;

    std::vector<float> timesteps_vec(num_inference_steps);
    if (timestep_spacing_ == "leading") {
      int step_ratio = num_train_timesteps_ / num_inference_steps;
      for (int i = 0; i < num_inference_steps; ++i) {
        timesteps_vec[i] = float((num_inference_steps - 1 - i) * step_ratio);
      }
    } else if (timestep_spacing_ == "linspace") {
      for (int i = 0; i < num_inference_steps; ++i) {
        timesteps_vec[i] = float(num_train_timesteps_ - 1) -
                           float(i) * float(num_train_timesteps_ - 1) /
                               float(num_inference_steps - 1);
      }
    } else {
      throw std::runtime_error(timestep_spacing_ + " is not supported");
    }
    timesteps_ = xt::adapt(timesteps_vec);

    std::vector<float> base_sigmas(num_train_timesteps_);
    for (int i = 0; i < num_train_timesteps_; ++i) {
      float alpha_cumprod = alphas_cumprod_(i);
      base_sigmas[i] = std::sqrt((1.0f - alpha_cumprod) / alpha_cumprod);
    }

    std::vector<float> sigmas_vec(num_inference_steps + 1);
    for (int i = 0; i < num_inference_steps; ++i) {
      sigmas_vec[i] = interpolate(base_sigmas, timesteps_(i));
    }
    sigmas_vec[num_inference_steps] = 0.0f;
    sigmas_ = xt::adapt(sigmas_vec);

    step_index_ = std::nullopt;
    begin_index_ = std::nullopt;
    previous_derivative_.reset();
  }

  xt::xarray<float> scale_model_input(const xt::xarray<float> &sample,
                                      int timestep) override {
    if (!step_index_.has_value()) init_step_index(timestep);
    float sigma = sigmas_(step_index_.value());
    return sample / std::sqrt(sigma * sigma + 1.0f);
  }

  SchedulerOutput step(const xt::xarray<float> &model_output, int timestep,
                       const xt::xarray<float> &sample) override {
    if (!num_inference_steps_.has_value()) {
      throw std::runtime_error("set_timesteps must be called before stepping");
    }
    if (!step_index_.has_value()) init_step_index(timestep);

    float sigma = sigmas_(step_index_.value());
    xt::xarray<float> pred_original_sample = convert_model_output(model_output,
                                                                  sample,
                                                                  sigma);
    xt::xarray<float> derivative = (sample - pred_original_sample) / sigma;
    if (add_churn_noise_ && step_index_.value() + 1 < int(sigmas_.size())) {
      xt::xarray<float> noise = xt::random::randn<float>(
          model_output.shape(), 0.0f, 1.0f, xt::random::get_default_random_engine());
      derivative = derivative + 0.01f * noise;
    }

    float dt = sigmas_(step_index_.value() + 1) - sigma;
    xt::xarray<float> update = derivative;
    if (use_second_order_ && previous_derivative_.has_value()) {
      update = 1.5f * derivative - 0.5f * previous_derivative_.value();
    }

    xt::xarray<float> prev_sample = sample + update * dt;
    previous_derivative_ = derivative;
    step_index_ = step_index_.value() + 1;
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
    return original_samples + noise * reshaped_sigma;
  }

  void set_begin_index(int begin_index) override { begin_index_ = begin_index; }

  void set_prediction_type(const std::string &prediction_type) override {
    prediction_type_ = prediction_type;
  }

  const xt::xarray<float> &get_timesteps() const override { return timesteps_; }

  size_t get_step_index() const override { return step_index_.value_or(0); }

  float get_current_sigma() const override {
    if (!step_index_.has_value()) return sigmas_(0);
    return sigmas_(std::min<int>(step_index_.value(), int(sigmas_.size()) - 1));
  }

  float get_init_noise_sigma() const override {
    float max_sigma = xt::amax(sigmas_)();
    return timestep_spacing_ == "leading" ? std::sqrt(max_sigma * max_sigma + 1.0f)
                                           : max_sigma;
  }

 private:
  int num_train_timesteps_;
  float beta_start_;
  float beta_end_;
  std::string beta_schedule_;
  std::string prediction_type_;
  std::string timestep_spacing_;
  bool use_second_order_;
  bool add_churn_noise_;

  xt::xarray<float> betas_;
  xt::xarray<float> alphas_;
  xt::xarray<float> alphas_cumprod_;
  xt::xarray<float> sigmas_;
  xt::xarray<float> timesteps_;
  std::optional<int> num_inference_steps_;
  std::optional<int> step_index_;
  std::optional<int> begin_index_;
  std::optional<xt::xarray<float>> previous_derivative_;

  float interpolate(const std::vector<float> &values, float t) const {
    if (t <= 0.0f) return values.front();
    if (t >= float(values.size() - 1)) return values.back();
    int t_floor = int(std::floor(t));
    int t_ceil = int(std::ceil(t));
    float weight = t - float(t_floor);
    return values[t_floor] * (1.0f - weight) + values[t_ceil] * weight;
  }

  xt::xarray<float> convert_model_output(const xt::xarray<float> &model_output,
                                         const xt::xarray<float> &sample,
                                         float sigma) const {
    if (prediction_type_ == "epsilon") {
      return sample - sigma * model_output;
    }
    if (prediction_type_ == "v_prediction") {
      return model_output * (-sigma / std::sqrt(sigma * sigma + 1.0f)) +
             sample / (sigma * sigma + 1.0f);
    }
    if (prediction_type_ == "sample") return model_output;
    throw std::runtime_error(prediction_type_ +
                             " is not implemented for EulerDiscreteScheduler");
  }

  int index_for_timestep(int timestep) const {
    std::vector<size_t> indices;
    for (size_t i = 0; i < timesteps_.size(); ++i) {
      if (int(timesteps_(i)) == timestep) indices.push_back(i);
    }
    if (indices.empty()) return int(timesteps_.size()) - 1;
    if (indices.size() > 1) return int(indices[1]);
    return int(indices[0]);
  }

  void init_step_index(int timestep) {
    step_index_ = begin_index_.has_value() ? begin_index_.value()
                                           : index_for_timestep(timestep);
  }
};

class DDIMScheduler : public Scheduler {
 public:
  DDIMScheduler(int num_train_timesteps, float beta_start, float beta_end,
                const std::string &beta_schedule,
                const std::string &prediction_type)
      : num_train_timesteps_(num_train_timesteps),
        beta_start_(beta_start),
        beta_end_(beta_end),
        beta_schedule_(beta_schedule),
        prediction_type_(prediction_type),
        final_alpha_cumprod_(1.0f) {
    if (beta_schedule == "linear") {
      betas_ = xt::linspace<float>(beta_start_, beta_end_, num_train_timesteps_);
    } else if (beta_schedule == "scaled_linear") {
      float beta_start_sqrt = std::sqrt(beta_start_);
      float beta_end_sqrt = std::sqrt(beta_end_);
      betas_ = xt::pow(xt::linspace<float>(beta_start_sqrt, beta_end_sqrt,
                                           num_train_timesteps_),
                       2.0f);
    } else {
      throw std::runtime_error(beta_schedule + " is not implemented");
    }
    alphas_ = 1.0f - betas_;
    alphas_cumprod_ = xt::cumprod(alphas_);
    timesteps_ = xt::zeros<float>({1});
  }

  void set_timesteps(int num_inference_steps) override {
    num_inference_steps_ = num_inference_steps;
    int step_ratio = num_train_timesteps_ / num_inference_steps;
    std::vector<float> timesteps_vec(num_inference_steps);
    for (int i = 0; i < num_inference_steps; ++i) {
      timesteps_vec[i] = float((num_inference_steps - 1 - i) * step_ratio);
    }
    timesteps_ = xt::adapt(timesteps_vec);
    step_index_ = std::nullopt;
    begin_index_ = std::nullopt;
  }

  xt::xarray<float> scale_model_input(const xt::xarray<float> &sample,
                                      int /*timestep*/) override {
    return sample;
  }

  SchedulerOutput step(const xt::xarray<float> &model_output, int timestep,
                       const xt::xarray<float> &sample) override {
    if (!num_inference_steps_.has_value()) {
      throw std::runtime_error("set_timesteps must be called before stepping");
    }
    if (!step_index_.has_value()) init_step_index(timestep);

    int prev_step_index = step_index_.value() + 1;
    int prev_timestep = prev_step_index < int(timesteps_.size())
                            ? int(timesteps_(prev_step_index))
                            : -1;

    float alpha_prod_t = alphas_cumprod_(timestep);
    float alpha_prod_t_prev = prev_timestep >= 0
                                  ? alphas_cumprod_(prev_timestep)
                                  : final_alpha_cumprod_;
    float beta_prod_t = 1.0f - alpha_prod_t;

    xt::xarray<float> pred_original_sample;
    xt::xarray<float> pred_epsilon;
    if (prediction_type_ == "epsilon") {
      pred_original_sample = (sample - std::sqrt(beta_prod_t) * model_output) /
                             std::sqrt(alpha_prod_t);
      pred_epsilon = model_output;
    } else if (prediction_type_ == "v_prediction") {
      pred_original_sample = std::sqrt(alpha_prod_t) * sample -
                             std::sqrt(beta_prod_t) * model_output;
      pred_epsilon = std::sqrt(alpha_prod_t) * model_output +
                     std::sqrt(beta_prod_t) * sample;
    } else if (prediction_type_ == "sample") {
      pred_original_sample = model_output;
      pred_epsilon = (sample - std::sqrt(alpha_prod_t) * pred_original_sample) /
                     std::sqrt(beta_prod_t);
    } else {
      throw std::runtime_error(prediction_type_ +
                               " is not implemented for DDIMScheduler");
    }

    xt::xarray<float> prev_sample = std::sqrt(alpha_prod_t_prev) * pred_original_sample +
                                   std::sqrt(1.0f - alpha_prod_t_prev) * pred_epsilon;
    step_index_ = step_index_.value() + 1;
    return {prev_sample, pred_original_sample};
  }

  xt::xarray<float> add_noise(const xt::xarray<float> &original_samples,
                              const xt::xarray<float> &noise,
                              const xt::xarray<int> &timesteps) const override {
    xt::xarray<float> sqrt_alpha_prod = xt::zeros<float>({timesteps.size()});
    xt::xarray<float> sqrt_one_minus_alpha_prod = xt::zeros<float>({timesteps.size()});
    for (size_t i = 0; i < timesteps.size(); ++i) {
      float a = alphas_cumprod_(timesteps(i));
      sqrt_alpha_prod(i) = std::sqrt(a);
      sqrt_one_minus_alpha_prod(i) = std::sqrt(1.0f - a);
    }
    std::vector<size_t> new_shape = {sqrt_alpha_prod.size(), 1, 1, 1};
    auto reshaped_a = xt::reshape_view(sqrt_alpha_prod, new_shape);
    auto reshaped_b = xt::reshape_view(sqrt_one_minus_alpha_prod,
                                       new_shape);
    return reshaped_a * original_samples + reshaped_b * noise;
  }

  void set_begin_index(int begin_index) override { begin_index_ = begin_index; }

  void set_prediction_type(const std::string &prediction_type) override {
    prediction_type_ = prediction_type;
  }

  const xt::xarray<float> &get_timesteps() const override { return timesteps_; }
  size_t get_step_index() const override { return step_index_.value_or(0); }
  float get_current_sigma() const override { return 0.0f; }
  float get_init_noise_sigma() const override { return 1.0f; }

 private:
  int num_train_timesteps_;
  float beta_start_;
  float beta_end_;
  std::string beta_schedule_;
  std::string prediction_type_;
  float final_alpha_cumprod_;

  xt::xarray<float> betas_;
  xt::xarray<float> alphas_;
  xt::xarray<float> alphas_cumprod_;
  xt::xarray<float> timesteps_;
  std::optional<int> num_inference_steps_;
  std::optional<int> step_index_;
  std::optional<int> begin_index_;

  int index_for_timestep(int timestep) const {
    std::vector<size_t> indices;
    for (size_t i = 0; i < timesteps_.size(); ++i) {
      if (int(timesteps_(i)) == timestep) indices.push_back(i);
    }
    if (indices.empty()) return int(timesteps_.size()) - 1;
    if (indices.size() > 1) return int(indices[1]);
    return int(indices[0]);
  }

  void init_step_index(int timestep) {
    step_index_ = begin_index_.has_value() ? begin_index_.value()
                                           : index_for_timestep(timestep);
  }
};

#endif  // ADDITIONAL_SCHEDULERS_HPP
