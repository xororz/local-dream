// self-implemented DPMSolverMultistepScheduler class
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

class DPMSolverMultistepScheduler : public Scheduler {
 public:
  DPMSolverMultistepScheduler(int num_train_timesteps, float beta_start,
                              float beta_end, const std::string &beta_schedule,
                              int solver_order,
                              const std::string &prediction_type,
                              const std::string &timestep_spacing)
      : num_train_timesteps_(num_train_timesteps),
        beta_start_(beta_start),
        beta_end_(beta_end),
        beta_schedule_(beta_schedule),
        solver_order_(solver_order),
        prediction_type_(prediction_type),
        timestep_spacing_(timestep_spacing),
        lower_order_final_(true) {
    if (beta_schedule == "scaled_linear") {
      float beta_start_sqrt = std::sqrt(beta_start_);
      float beta_end_sqrt = std::sqrt(beta_end_);
      betas_ = xt::pow(xt::linspace<float>(beta_start_sqrt, beta_end_sqrt,
                                           num_train_timesteps),
                       2.0f);
    } else {
      throw std::runtime_error(beta_schedule + " is not implemented");
    }

    alphas_ = 1.0f - betas_;
    alphas_cumprod_ = xt::cumprod(alphas_);

    alpha_t_ = xt::sqrt(alphas_cumprod_);
    sigma_t_ = xt::sqrt(1.0f - alphas_cumprod_);
    lambda_t_ = xt::log(alpha_t_) - xt::log(sigma_t_);
    sigmas_ = xt::pow((1.0f - alphas_cumprod_) / alphas_cumprod_, 0.5f);

    model_outputs_.resize(solver_order_);
    std::fill(model_outputs_.begin(), model_outputs_.end(),
              xt::xarray<float>());

    lower_order_nums_ = 0;
    step_index_ = std::nullopt;
    begin_index_ = std::nullopt;
  }

  void set_timesteps(int num_inference_steps) override {
    num_inference_steps_ = num_inference_steps;

    if (timestep_spacing_ == "leading") {
      int step_ratio = num_train_timesteps_ / (num_inference_steps + 1);
      xt::xarray<int> steps = xt::cast<int>(xt::round(
          xt::arange<float>(0, num_inference_steps + 1) * float(step_ratio)));
      timesteps_ = xt::view(xt::flip(steps, 0), xt::range(0, steps.size() - 1));
    } else {
      throw std::runtime_error(timestep_spacing_ + " is not supported");
    }

    xt::xarray<float> selected_sigmas = xt::zeros<float>({timesteps_.size()});
    for (size_t i = 0; i < timesteps_.size(); ++i) {
      size_t idx = size_t(timesteps_(i));
      selected_sigmas(i) = sigmas_(idx);
    }
    sigmas_ = xt::concatenate(
        std::make_tuple(selected_sigmas, xt::zeros<float>({1})));

    model_outputs_.clear();
    model_outputs_.resize(solver_order_);
    std::fill(model_outputs_.begin(), model_outputs_.end(),
              xt::xarray<float>());

    lower_order_nums_ = 0;
    step_index_ = std::nullopt;
    begin_index_ = std::nullopt;
  }

  std::tuple<float, float> _sigma_to_alpha_sigma_t(float sigma) const {
    float alpha_t = 1.0f / std::sqrt(sigma * sigma + 1.0f);
    float sigma_t = sigma * alpha_t;
    return {alpha_t, sigma_t};
  }

  void set_prediction_type(const std::string &prediction_type) override {
    prediction_type_ = prediction_type;
  }

  xt::xarray<float> scale_model_input(const xt::xarray<float> &sample,
                                      int timestep) override {
    // DPM solver does not require input scaling
    return sample;
  }

  xt::xarray<float> convert_model_output(const xt::xarray<float> &model_output,
                                         const xt::xarray<float> &sample) {
    float sigma = sigmas_(step_index_.value());
    auto [alpha_t, sigma_t_val] = _sigma_to_alpha_sigma_t(sigma);
    if (prediction_type_ == "epsilon") {
      return (sample - sigma_t_val * model_output) / alpha_t;
    } else if (prediction_type_ == "v_prediction") {
      return alpha_t * sample - sigma_t_val * model_output;
    } else if (prediction_type_ == "sample") {
      return model_output;
    } else {
      throw std::runtime_error(
          prediction_type_ +
          " is not implemented for DPMSolverMultistepScheduler");
    }
  }

  xt::xarray<float> dpm_solver_first_order_update(
      const xt::xarray<float> &model_output, const xt::xarray<float> &sample) {
    float sigma_next = sigmas_(step_index_.value() + 1);
    float sigma_curr = sigmas_(step_index_.value());
    auto [alpha_t, sigma_t_val] = _sigma_to_alpha_sigma_t(sigma_next);
    auto [alpha_s, sigma_s_val] = _sigma_to_alpha_sigma_t(sigma_curr);

    float lambda_t = std::log(alpha_t) - std::log(sigma_t_val);
    float lambda_s = std::log(alpha_s) - std::log(sigma_s_val);
    float h = lambda_t - lambda_s;

    return (sigma_t_val / sigma_s_val) * sample -
           alpha_t * (std::exp(-h) - 1.0f) * model_output;
  }

  xt::xarray<float> multistep_dpm_solver_second_order_update(
      const std::vector<xt::xarray<float>> &model_output_list,
      const xt::xarray<float> &sample) {
    float sigma_next = sigmas_(step_index_.value() + 1);
    float sigma_s0 = sigmas_(step_index_.value());
    float sigma_s1 = sigmas_(step_index_.value() - 1);

    auto [alpha_t, sigma_t_val] = _sigma_to_alpha_sigma_t(sigma_next);
    auto [alpha_s0, sigma_s0_val] = _sigma_to_alpha_sigma_t(sigma_s0);
    auto [alpha_s1, sigma_s1_val] = _sigma_to_alpha_sigma_t(sigma_s1);

    float lambda_t = std::log(alpha_t) - std::log(sigma_t_val);
    float lambda_s0_ = std::log(alpha_s0) - std::log(sigma_s0_val);
    float lambda_s1_ = std::log(alpha_s1) - std::log(sigma_s1_val);

    const auto &m0 = model_output_list.back();
    const auto &m1 = model_output_list[model_output_list.size() - 2];

    float h = lambda_t - lambda_s0_;
    float h_0 = lambda_s0_ - lambda_s1_;
    float r0 = h_0 / h;

    xt::xarray<float> D0 = m0;
    xt::xarray<float> D1 = (1.0f / r0) * (m0 - m1);

    return (sigma_t_val / sigma_s0_val) * sample -
           (alpha_t * (std::exp(-h) - 1.0f)) * D0 -
           0.5f * (alpha_t * (std::exp(-h) - 1.0f)) * D1;
  }

  xt::xarray<float> multistep_dpm_solver_third_order_update(
      const std::vector<xt::xarray<float>> &model_output_list,
      const xt::xarray<float> &sample) {
    float sigma_next = sigmas_(step_index_.value() + 1);
    float sigma_s0 = sigmas_(step_index_.value());
    float sigma_s1 = sigmas_(step_index_.value() - 1);
    float sigma_s2 = sigmas_(step_index_.value() - 2);

    auto [alpha_t, sigma_t_val] = _sigma_to_alpha_sigma_t(sigma_next);
    auto [alpha_s0, sigma_s0_val] = _sigma_to_alpha_sigma_t(sigma_s0);
    auto [alpha_s1, sigma_s1_val] = _sigma_to_alpha_sigma_t(sigma_s1);
    auto [alpha_s2, sigma_s2_val] = _sigma_to_alpha_sigma_t(sigma_s2);

    float lambda_t = std::log(alpha_t) - std::log(sigma_t_val);
    float lambda_s0_ = std::log(alpha_s0) - std::log(sigma_s0_val);
    float lambda_s1_ = std::log(alpha_s1) - std::log(sigma_s1_val);
    float lambda_s2_ = std::log(alpha_s2) - std::log(sigma_s2_val);

    const auto &m0 = model_output_list.back();
    const auto &m1 = model_output_list[model_output_list.size() - 2];
    const auto &m2 = model_output_list[model_output_list.size() - 3];

    float h = lambda_t - lambda_s0_;
    float h_0 = lambda_s0_ - lambda_s1_;
    float h_1 = lambda_s1_ - lambda_s2_;
    float r0 = h_0 / h;
    float r1 = h_1 / h;

    xt::xarray<float> D0 = m0;
    xt::xarray<float> D1_0 = (1.0f / r0) * (m0 - m1);
    xt::xarray<float> D1_1 = (1.0f / r1) * (m1 - m2);
    xt::xarray<float> D1 = D1_0 + (r0 / (r0 + r1)) * (D1_0 - D1_1);
    xt::xarray<float> D2 = (1.0f / (r0 + r1)) * (D1_0 - D1_1);

    return (sigma_t_val / sigma_s0_val) * sample -
           (alpha_t * (std::exp(-h) - 1.0f)) * D0 +
           (alpha_t * ((std::exp(-h) - 1.0f) / h + 1.0f)) * D1 -
           (alpha_t * ((std::exp(-h) - 1.0f + h) / (h * h) - 0.5f)) * D2;
  }

  int index_for_timestep(int timestep) const {
    std::vector<size_t> indices;
    for (size_t i = 0; i < timesteps_.size(); ++i) {
      if (timesteps_(i) == timestep) {
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

  SchedulerOutput step(const xt::xarray<float> &model_output, int timestep,
                       const xt::xarray<float> &sample) override {
    if (!num_inference_steps_) {
      throw std::runtime_error("set_timesteps must be called before stepping");
    }

    if (!step_index_) {
      step_index_ = index_for_timestep(timestep);
    }

    xt::xarray<float> converted_output =
        convert_model_output(model_output, sample);

    for (int i = 0; i < solver_order_ - 1; ++i) {
      model_outputs_[i] = model_outputs_[i + 1];
    }
    model_outputs_.back() = converted_output;

    bool lower_order_final =
        (step_index_.value() == int(timesteps_.size()) - 1) ||
        (lower_order_final_ && timesteps_.size() < 15);
    bool lower_order_second =
        (step_index_.value() == int(timesteps_.size()) - 2) &&
        lower_order_final_ && timesteps_.size() < 15;

    xt::xarray<float> prev_sample;
    if (solver_order_ == 1 || lower_order_nums_ < 1 || lower_order_final) {
      prev_sample = dpm_solver_first_order_update(converted_output, sample);
    } else if (solver_order_ == 2 || lower_order_nums_ < 2 ||
               lower_order_second) {
      prev_sample =
          multistep_dpm_solver_second_order_update(model_outputs_, sample);
    } else {
      prev_sample =
          multistep_dpm_solver_third_order_update(model_outputs_, sample);
    }

    if (lower_order_nums_ < solver_order_) {
      lower_order_nums_++;
    }

    step_index_ = step_index_.value() + 1;
    return {prev_sample, xt::xarray<float>()};
  }

  void set_begin_index(int begin_index) override { begin_index_ = begin_index; }

  xt::xarray<float> add_noise(const xt::xarray<float> &original_samples,
                              const xt::xarray<float> &noise,
                              const xt::xarray<int> &timesteps) const override {
    std::vector<int> step_indices;

    if (!begin_index_) {
      for (size_t i = 0; i < timesteps.size(); ++i) {
        step_indices.push_back(index_for_timestep(timesteps(i)));
      }
    } else if (step_index_) {
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

    xt::xarray<float> alpha_t =
        xt::ones_like(reshaped_sigma) /
        xt::sqrt(reshaped_sigma * reshaped_sigma + 1.0f);
    xt::xarray<float> sigma_t = reshaped_sigma * alpha_t;

    return alpha_t * original_samples + sigma_t * noise;
  }

  const xt::xarray<float> &get_timesteps() const override { return timesteps_; }
  size_t get_step_index() const override { return step_index_.value_or(0); }

  const xt::xarray<float> &get_betas() const { return betas_; }
  const xt::xarray<float> &get_alphas() const { return alphas_; }
  const xt::xarray<float> &get_alphas_cumprod() const {
    return alphas_cumprod_;
  }
  const xt::xarray<float> &get_alpha_t() const { return alpha_t_; }
  const xt::xarray<float> &get_sigma_t() const { return sigma_t_; }
  const xt::xarray<float> &get_lambda_t() const { return lambda_t_; }
  const xt::xarray<float> &get_sigmas() const { return sigmas_; }

  float get_current_sigma() const override {
    if (!step_index_) {
      return sigmas_(0);
    }
    return sigmas_(std::min<int>(step_index_.value(), int(sigmas_.size()) - 1));
  }

  float get_init_noise_sigma() const override {
    // DPM solver does not require special initial noise scaling
    return 1.0f;
  }

 private:
  int num_train_timesteps_;
  float beta_start_;
  float beta_end_;
  std::string beta_schedule_;
  int solver_order_;
  std::string prediction_type_;
  std::string timestep_spacing_;
  bool lower_order_final_;

  xt::xarray<float> betas_;
  xt::xarray<float> alphas_;
  xt::xarray<float> alphas_cumprod_;
  xt::xarray<float> alpha_t_;
  xt::xarray<float> sigma_t_;
  xt::xarray<float> lambda_t_;
  xt::xarray<float> sigmas_;

  std::optional<int> num_inference_steps_;
  xt::xarray<float> timesteps_;
  std::vector<xt::xarray<float>> model_outputs_;
  int lower_order_nums_;
  std::optional<int> step_index_;
  std::optional<int> begin_index_;
};
