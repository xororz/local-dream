#include <cassert>
#include <cmath>
#include <iostream>
#include <memory>
#include <type_traits>
#include <vector>

#include "DPMSolverMultistepScheduler.hpp"
#include "EulerAncestralDiscreteScheduler.hpp"
#include "EulerDiscreteScheduler.hpp"
#include "LCMScheduler.hpp"
#include <xtensor/xeval.hpp>

int main() {
  using Array = xt::xarray<float>;
  using Expression = decltype(std::declval<Array &>() +
                             7.0f * (std::declval<Array &>() - std::declval<Array &>()));
  // svector's inline capacity (4) is not a tensor rank. Clang 21 previously
  // selected xtensor<float, 4> here, causing an invalid allocation for CFG.
  static_assert(std::is_same_v<xt::temporary_type_t<Expression>, Array>);

  for (int rank : {1, 2, 3, 4, 5}) {
    std::vector<size_t> shape(rank, 2);
    Array a = xt::ones<float>(shape);
    Array b = 2.0f * a;
    Array result = xt::eval(a + 7.0f * (b - a));
    assert(result.shape() == a.shape());
    for (float value : result) assert(value == 8.0f);
  }

  for (int edge : {64, 128}) {
    const int elements = 4 * edge * edge;
    std::vector<int> shape{1, 4, edge, edge};
    std::vector<int> batch_shape{2, 4, edge, edge};
    std::vector<float> unet_output(elements * 2);
    std::fill_n(unet_output.begin(), elements, 0.125f);
    std::fill_n(unet_output.begin() + elements, elements, 0.25f);
    for (float cfg : {1.0f, 7.0f}) {
      Array prediction;
      if (cfg == 1.0f) {
        std::vector<float> positive(unet_output.begin() + elements, unet_output.end());
        prediction = xt::adapt(positive, shape);
      } else {
        Array batch = xt::adapt(unet_output, batch_shape);
        Array negative = xt::view(batch, 0);
        Array positive = xt::view(batch, 1);
        prediction = xt::eval(negative + cfg * (positive - negative));
      }
      assert(prediction.size() == static_cast<size_t>(elements));
      for (float value : prediction) assert(value == 0.125f + cfg * 0.125f);

      // Exercise the actual scheduler classes with the same rank-3/rank-4
      // predictions produced by the CFG branches, for SD1.5 and SDXL shapes.
      for (bool karras : {false, true}) {
        std::vector<std::unique_ptr<Scheduler>> schedulers;
        for (const char *algorithm : {"dpmsolver++", "sde-dpmsolver++"}) {
          schedulers.push_back(std::make_unique<DPMSolverMultistepScheduler>(
              1000, 0.00085f, 0.012f, "scaled_linear", 2, "epsilon", "trailing",
              karras, algorithm));
        }
        schedulers.push_back(std::make_unique<EulerAncestralDiscreteScheduler>(
            1000, 0.00085f, 0.012f, "scaled_linear", "epsilon", "trailing", 0,
            false, karras));
        schedulers.push_back(std::make_unique<EulerDiscreteScheduler>(
            1000, 0.00085f, 0.012f, "scaled_linear", "epsilon", "trailing", 0,
            false, karras));
        if (!karras) {
          schedulers.push_back(std::make_unique<LCMScheduler>(
              1000, 0.00085f, 0.012f, "scaled_linear", "epsilon", 50));
        }
        for (auto &scheduler : schedulers) {
          scheduler->set_timesteps(20);
          Array sample = xt::ones<float>(shape);
          const auto expected_shape = sample.shape();
          for (float timestep : scheduler->get_timesteps()) {
            auto input = scheduler->scale_model_input(sample, static_cast<int>(timestep));
            assert(input.shape() == expected_shape);
            sample = scheduler->step(prediction, static_cast<int>(timestep), sample).prev_sample;
            assert(sample.shape() == expected_shape);
            for (float value : sample) assert(std::isfinite(value));
          }
        }
      }
    }
  }
  std::cout << "Tensor rank, CFG, and scheduler regression tests passed\n";
}
