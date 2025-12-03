#ifndef FLOATCONVERSION_HPP
#define FLOATCONVERSION_HPP

#include <cstdint>

// FP16 to FP32 conversion
inline float fp16_to_fp32(uint16_t fp16_val) {
  uint32_t sign = (fp16_val & 0x8000) << 16;
  uint32_t exponent = (fp16_val & 0x7C00) >> 10;
  uint32_t mantissa = fp16_val & 0x03FF;

  if (exponent == 0) {
    if (mantissa == 0) {
      return *reinterpret_cast<float *>(&sign);
    } else {
      exponent = 127 - 15 + 1;
      while ((mantissa & 0x400) == 0) {
        mantissa <<= 1;
        exponent--;
      }
      mantissa &= 0x3FF;
      uint32_t fp32_bits = sign | (exponent << 23) | (mantissa << 13);
      return *reinterpret_cast<float *>(&fp32_bits);
    }
  } else if (exponent == 0x1F) {
    uint32_t fp32_bits = sign | 0x7F800000 | (mantissa << 13);
    return *reinterpret_cast<float *>(&fp32_bits);
  } else {
    exponent = exponent - 15 + 127;
    uint32_t fp32_bits = sign | (exponent << 23) | (mantissa << 13);
    return *reinterpret_cast<float *>(&fp32_bits);
  }
}

// FP32 to FP16 conversion
inline uint16_t fp32_to_fp16(float fp32_val) {
  uint32_t fp32_bits = *reinterpret_cast<uint32_t *>(&fp32_val);
  uint32_t sign = (fp32_bits & 0x80000000) >> 16;
  uint32_t exponent = (fp32_bits & 0x7F800000) >> 23;
  uint32_t mantissa = fp32_bits & 0x007FFFFF;

  if (exponent == 0) {
    return static_cast<uint16_t>(sign);
  } else if (exponent == 0xFF) {
    return static_cast<uint16_t>(sign | 0x7C00 | (mantissa >> 13));
  } else {
    int32_t new_exponent = static_cast<int32_t>(exponent) - 127 + 15;
    if (new_exponent <= 0) {
      return static_cast<uint16_t>(sign);
    } else if (new_exponent >= 0x1F) {
      return static_cast<uint16_t>(sign | 0x7C00);
    } else {
      return static_cast<uint16_t>(sign | (new_exponent << 10) |
                                   (mantissa >> 13));
    }
  }
}

// BF16 to FP32 conversion
inline float bf16_to_fp32(uint16_t bf16_val) {
  // BF16 to FP32: just shift left by 16 bits (add zeros to mantissa)
  uint32_t fp32_bits = static_cast<uint32_t>(bf16_val) << 16;
  return *reinterpret_cast<float *>(&fp32_bits);
}

// FP32 to BF16 conversion
inline uint16_t fp32_to_bf16(float fp32_val) {
  // FP32 to BF16: just take the upper 16 bits (truncate mantissa)
  uint32_t fp32_bits = *reinterpret_cast<uint32_t *>(&fp32_val);
  return static_cast<uint16_t>(fp32_bits >> 16);
}

#endif  // FLOATCONVERSION_HPP