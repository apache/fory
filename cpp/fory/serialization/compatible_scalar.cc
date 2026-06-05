/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#include "fory/serialization/compatible_scalar.h"

#include "fory/serialization/context.h"
#include "fory/serialization/decimal_serializers.h"
#include "fory/serialization/string_serializer.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <sstream>
#include <vector>

namespace fory {
namespace serialization {
namespace {

constexpr int32_t MAX_COMPATIBLE_DECIMAL_DIGITS = 256;
constexpr size_t MAX_COMPATIBLE_NUMERIC_TEXT_LENGTH = 320;

enum class ScalarKind {
  Bool,
  Signed,
  Unsigned,
  Float16,
  BFloat16,
  Float32,
  Float64,
  Decimal,
  String
};

struct BigUInt {
  std::vector<uint32_t> limbs;

  bool is_zero() const { return limbs.empty(); }

  void normalize() {
    while (!limbs.empty() && limbs.back() == 0) {
      limbs.pop_back();
    }
  }

  void multiply(uint32_t factor) {
    if (factor == 0 || is_zero()) {
      limbs.clear();
      return;
    }
    uint64_t carry = 0;
    for (uint32_t &limb : limbs) {
      uint64_t value = static_cast<uint64_t>(limb) * factor + carry;
      limb = static_cast<uint32_t>(value);
      carry = value >> 32;
    }
    if (carry != 0) {
      limbs.push_back(static_cast<uint32_t>(carry));
    }
  }

  void add(uint32_t value) {
    uint64_t carry = value;
    for (uint32_t &limb : limbs) {
      uint64_t sum = static_cast<uint64_t>(limb) + carry;
      limb = static_cast<uint32_t>(sum);
      carry = sum >> 32;
      if (carry == 0) {
        return;
      }
    }
    if (carry != 0) {
      limbs.push_back(static_cast<uint32_t>(carry));
    }
  }

  uint32_t divmod(uint32_t divisor) {
    uint64_t rem = 0;
    for (size_t i = limbs.size(); i > 0; --i) {
      uint64_t cur = (rem << 32) | limbs[i - 1];
      limbs[i - 1] = static_cast<uint32_t>(cur / divisor);
      rem = cur % divisor;
    }
    normalize();
    return static_cast<uint32_t>(rem);
  }

  void multiply_pow10(uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) {
      multiply(10);
    }
  }

  bool divide_by_10_if_divisible() {
    BigUInt copy = *this;
    if (copy.divmod(10) != 0) {
      return false;
    }
    *this = std::move(copy);
    return true;
  }

  bool to_uint64(uint64_t &value) const {
    if (limbs.size() > 2) {
      return false;
    }
    value = 0;
    if (!limbs.empty()) {
      value = limbs[0];
    }
    if (limbs.size() == 2) {
      value |= static_cast<uint64_t>(limbs[1]) << 32;
    }
    return true;
  }

  std::string to_decimal_string() const {
    if (is_zero()) {
      return "0";
    }
    BigUInt copy = *this;
    std::string digits;
    while (!copy.is_zero()) {
      digits.push_back(static_cast<char>('0' + copy.divmod(10)));
    }
    std::reverse(digits.begin(), digits.end());
    return digits;
  }

  int64_t decimal_digit_count_bounded(int64_t limit) const {
    if (is_zero()) {
      return 1;
    }
    BigUInt copy = *this;
    int64_t digits = 0;
    while (!copy.is_zero()) {
      copy.divmod(10);
      ++digits;
      if (digits > limit) {
        return digits;
      }
    }
    return digits;
  }

  std::vector<uint8_t> to_bytes_le() const {
    std::vector<uint8_t> bytes;
    bytes.reserve(limbs.size() * sizeof(uint32_t));
    for (uint32_t limb : limbs) {
      bytes.push_back(static_cast<uint8_t>(limb));
      bytes.push_back(static_cast<uint8_t>(limb >> 8));
      bytes.push_back(static_cast<uint8_t>(limb >> 16));
      bytes.push_back(static_cast<uint8_t>(limb >> 24));
    }
    while (!bytes.empty() && bytes.back() == 0) {
      bytes.pop_back();
    }
    return bytes;
  }

  static BigUInt from_uint64(uint64_t value) {
    BigUInt out;
    if (value != 0) {
      out.limbs.push_back(static_cast<uint32_t>(value));
      uint32_t hi = static_cast<uint32_t>(value >> 32);
      if (hi != 0) {
        out.limbs.push_back(hi);
      }
    }
    return out;
  }

  static BigUInt from_decimal_digits(std::string_view digits) {
    BigUInt out;
    for (char c : digits) {
      out.multiply(10);
      out.add(static_cast<uint32_t>(c - '0'));
    }
    return out;
  }

  static BigUInt from_bytes_le(const std::vector<uint8_t> &bytes) {
    BigUInt out;
    for (size_t i = bytes.size(); i > 0; --i) {
      out.multiply(256);
      out.add(bytes[i - 1]);
    }
    return out;
  }
};

struct ParsedDecimal {
  Decimal decimal;
  bool negative_zero = false;
};

struct ScalarValue {
  ScalarKind kind;
  bool bool_value = false;
  int64_t signed_value = 0;
  uint64_t unsigned_value = 0;
  float16_t float16_value = float16_t::from_bits(0);
  bfloat16_t bfloat16_value = bfloat16_t::from_bits(0);
  float float32_value = 0.0f;
  double float64_value = 0.0;
  Decimal decimal_value;
  std::string string_value;
};

void count_significant_digit(char digit, bool &seen_nonzero,
                             int64_t &significant_digits) {
  if (digit != '0' || seen_nonzero) {
    seen_nonzero = true;
    ++significant_digits;
  }
}

bool decimal_shape_fits(int64_t significant_digits, int64_t scale) {
  if (scale > MAX_COMPATIBLE_DECIMAL_DIGITS) {
    return false;
  }
  return scale >= 0 ||
         significant_digits + (-scale) <= MAX_COMPATIBLE_DECIMAL_DIGITS;
}

bool scalar_kind(uint32_t type_id, ScalarKind &kind) {
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::BOOL:
    kind = ScalarKind::Bool;
    return true;
  case TypeId::INT8:
  case TypeId::INT16:
  case TypeId::INT32:
  case TypeId::VARINT32:
  case TypeId::INT64:
  case TypeId::VARINT64:
  case TypeId::TAGGED_INT64:
    kind = ScalarKind::Signed;
    return true;
  case TypeId::UINT8:
  case TypeId::UINT16:
  case TypeId::UINT32:
  case TypeId::VAR_UINT32:
  case TypeId::UINT64:
  case TypeId::VAR_UINT64:
  case TypeId::TAGGED_UINT64:
    kind = ScalarKind::Unsigned;
    return true;
  case TypeId::FLOAT16:
    kind = ScalarKind::Float16;
    return true;
  case TypeId::BFLOAT16:
    kind = ScalarKind::BFloat16;
    return true;
  case TypeId::FLOAT32:
    kind = ScalarKind::Float32;
    return true;
  case TypeId::FLOAT64:
    kind = ScalarKind::Float64;
    return true;
  case TypeId::DECIMAL:
    kind = ScalarKind::Decimal;
    return true;
  case TypeId::STRING:
    kind = ScalarKind::String;
    return true;
  default:
    return false;
  }
}

bool is_numeric(ScalarKind kind) {
  return kind == ScalarKind::Signed || kind == ScalarKind::Unsigned ||
         kind == ScalarKind::Float16 || kind == ScalarKind::BFloat16 ||
         kind == ScalarKind::Float32 || kind == ScalarKind::Float64 ||
         kind == ScalarKind::Decimal;
}

std::string type_name(uint32_t type_id) {
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::BOOL:
    return "bool";
  case TypeId::INT8:
    return "int8";
  case TypeId::INT16:
    return "int16";
  case TypeId::INT32:
    return "int32";
  case TypeId::VARINT32:
    return "varint32";
  case TypeId::INT64:
    return "int64";
  case TypeId::VARINT64:
    return "varint64";
  case TypeId::TAGGED_INT64:
    return "tagged_int64";
  case TypeId::UINT8:
    return "uint8";
  case TypeId::UINT16:
    return "uint16";
  case TypeId::UINT32:
    return "uint32";
  case TypeId::VAR_UINT32:
    return "var_uint32";
  case TypeId::UINT64:
    return "uint64";
  case TypeId::VAR_UINT64:
    return "var_uint64";
  case TypeId::TAGGED_UINT64:
    return "tagged_uint64";
  case TypeId::FLOAT16:
    return "float16";
  case TypeId::BFLOAT16:
    return "bfloat16";
  case TypeId::FLOAT32:
    return "float32";
  case TypeId::FLOAT64:
    return "float64";
  case TypeId::STRING:
    return "string";
  case TypeId::DECIMAL:
    return "decimal";
  default:
    return "type_id " + std::to_string(type_id);
  }
}

void conversion_error(ReadContext &ctx, uint32_t remote_type_id,
                      uint32_t local_type_id, std::string_view field,
                      std::string_view reason) {
  ctx.set_error(Error::invalid_data(
      "Cannot convert compatible field `" + std::string(field) + "` from " +
      type_name(remote_type_id) + " to " + type_name(local_type_id) + ": " +
      std::string(reason)));
}

bool canonical_decimal(BigUInt magnitude, bool negative, int64_t scale64,
                       Decimal &out) {
  if (magnitude.is_zero()) {
    out = Decimal(0, false, {});
    return true;
  }
  while (scale64 > 0 && magnitude.divide_by_10_if_divisible()) {
    --scale64;
  }
  int64_t digit_count =
      magnitude.decimal_digit_count_bounded(MAX_COMPATIBLE_DECIMAL_DIGITS + 1);
  if (scale64 < 0) {
    if (scale64 < -static_cast<int64_t>(MAX_COMPATIBLE_DECIMAL_DIGITS)) {
      return false;
    }
    int64_t extra_digits = -scale64;
    if (digit_count >
        static_cast<int64_t>(MAX_COMPATIBLE_DECIMAL_DIGITS) - extra_digits) {
      return false;
    }
    digit_count += extra_digits;
  }
  if (digit_count > MAX_COMPATIBLE_DECIMAL_DIGITS ||
      scale64 > MAX_COMPATIBLE_DECIMAL_DIGITS) {
    return false;
  }
  while (scale64 < 0) {
    magnitude.multiply(10);
    ++scale64;
  }
  if (scale64 > std::numeric_limits<int32_t>::max()) {
    return false;
  }
  out =
      Decimal(static_cast<int32_t>(scale64), negative, magnitude.to_bytes_le());
  return true;
}

BigUInt decimal_magnitude(const Decimal &decimal) {
  return BigUInt::from_bytes_le(decimal.magnitude_le());
}

bool canonical_decimal(const Decimal &decimal, Decimal &out) {
  return canonical_decimal(decimal_magnitude(decimal), decimal.negative(),
                           decimal.scale(), out);
}

bool decimal_equal_value(const Decimal &left, const Decimal &right) {
  Decimal a;
  Decimal b;
  if (!canonical_decimal(left, a) || !canonical_decimal(right, b)) {
    return false;
  }
  return a == b;
}

bool decimal_to_integral_magnitude(const Decimal &decimal, BigUInt &magnitude,
                                   bool &negative) {
  Decimal canonical;
  if (!canonical_decimal(decimal, canonical)) {
    return false;
  }
  magnitude = decimal_magnitude(canonical);
  negative = canonical.negative();
  if (canonical.scale() == 0) {
    return true;
  }
  if (canonical.scale() < 0) {
    magnitude.multiply_pow10(static_cast<uint32_t>(-canonical.scale()));
    return true;
  }
  for (int32_t i = 0; i < canonical.scale(); ++i) {
    if (!magnitude.divide_by_10_if_divisible()) {
      return false;
    }
  }
  return true;
}

bool decimal_to_int64(const Decimal &decimal, int64_t min_value,
                      int64_t max_value, int64_t &out) {
  BigUInt magnitude;
  bool negative = false;
  if (!decimal_to_integral_magnitude(decimal, magnitude, negative)) {
    return false;
  }
  uint64_t value = 0;
  if (!magnitude.to_uint64(value)) {
    return false;
  }
  if (!negative) {
    if (value > static_cast<uint64_t>(max_value)) {
      return false;
    }
    out = static_cast<int64_t>(value);
    return true;
  }
  uint64_t limit = max_value == std::numeric_limits<int64_t>::max()
                       ? (uint64_t{1} << 63)
                       : static_cast<uint64_t>(-(min_value + 1)) + 1;
  if (value > limit) {
    return false;
  }
  out = value == (uint64_t{1} << 63) ? std::numeric_limits<int64_t>::min()
                                     : -static_cast<int64_t>(value);
  return out >= min_value;
}

bool decimal_to_uint64(const Decimal &decimal, uint64_t max_value,
                       uint64_t &out) {
  BigUInt magnitude;
  bool negative = false;
  if (!decimal_to_integral_magnitude(decimal, magnitude, negative)) {
    return false;
  }
  if (negative && !magnitude.is_zero()) {
    return false;
  }
  if (!magnitude.to_uint64(out)) {
    return false;
  }
  return out <= max_value;
}

bool parsed_decimal(std::string_view text, ParsedDecimal &out) {
  if (text.empty() || text.size() > MAX_COMPATIBLE_NUMERIC_TEXT_LENGTH) {
    return false;
  }
  size_t pos = 0;
  bool negative = false;
  if (text[pos] == '-') {
    negative = true;
    ++pos;
    if (pos == text.size()) {
      return false;
    }
  } else if (text[pos] == '+') {
    return false;
  }

  const size_t int_start = pos;
  int64_t significant_digits = 0;
  bool seen_nonzero = false;
  if (text[pos] == '0') {
    count_significant_digit(text[pos], seen_nonzero, significant_digits);
    ++pos;
    if (pos < text.size() && text[pos] >= '0' && text[pos] <= '9') {
      return false;
    }
  } else if (text[pos] >= '1' && text[pos] <= '9') {
    while (pos < text.size() && text[pos] >= '0' && text[pos] <= '9') {
      count_significant_digit(text[pos], seen_nonzero, significant_digits);
      ++pos;
    }
  } else {
    return false;
  }
  const size_t int_end = pos;

  size_t frac_start = pos;
  size_t frac_end = pos;
  if (pos < text.size() && text[pos] == '.') {
    ++pos;
    frac_start = pos;
    if (pos == text.size() || text[pos] < '0' || text[pos] > '9') {
      return false;
    }
    while (pos < text.size() && text[pos] >= '0' && text[pos] <= '9') {
      count_significant_digit(text[pos], seen_nonzero, significant_digits);
      ++pos;
    }
    frac_end = pos;
  }

  int64_t exponent = 0;
  if (pos < text.size() && (text[pos] == 'e' || text[pos] == 'E')) {
    ++pos;
    bool exp_negative = false;
    if (pos < text.size() && text[pos] == '-') {
      exp_negative = true;
      ++pos;
    } else if (pos < text.size() && text[pos] == '+') {
      return false;
    }
    if (pos == text.size()) {
      return false;
    }
    if (text[pos] == '0') {
      ++pos;
      if (pos < text.size() && text[pos] >= '0' && text[pos] <= '9') {
        return false;
      }
    } else if (text[pos] >= '1' && text[pos] <= '9') {
      while (pos < text.size() && text[pos] >= '0' && text[pos] <= '9') {
        exponent = exponent * 10 + (text[pos] - '0');
        if (exponent > MAX_COMPATIBLE_DECIMAL_DIGITS) {
          return false;
        }
        ++pos;
      }
    } else {
      return false;
    }
    if (exp_negative) {
      exponent = -exponent;
    }
  }
  if (pos != text.size()) {
    return false;
  }
  int64_t scale = static_cast<int64_t>(frac_end - frac_start) - exponent;
  if (significant_digits > MAX_COMPATIBLE_DECIMAL_DIGITS ||
      !decimal_shape_fits(significant_digits, scale)) {
    return false;
  }

  std::string digits;
  digits.reserve((int_end - int_start) + (frac_end - frac_start));
  digits.append(text.substr(int_start, int_end - int_start));
  if (frac_end > frac_start) {
    digits.append(text.substr(frac_start, frac_end - frac_start));
  }
  BigUInt magnitude = BigUInt::from_decimal_digits(digits);
  out.negative_zero = negative && magnitude.is_zero();
  return canonical_decimal(std::move(magnitude), negative, scale, out.decimal);
}

Decimal decimal_from_signed(int64_t value) {
  return Decimal::from_int64(value, 0);
}

Decimal decimal_from_unsigned(uint64_t value) {
  Decimal out;
  canonical_decimal(BigUInt::from_uint64(value), false, 0, out);
  return out;
}

bool decimal_from_float_bits(bool negative, uint64_t significand,
                             int32_t exponent, Decimal &out) {
  BigUInt magnitude = BigUInt::from_uint64(significand);
  if (exponent >= 0) {
    for (int32_t i = 0; i < exponent; ++i) {
      magnitude.multiply(2);
    }
    return canonical_decimal(std::move(magnitude), negative, 0, out);
  }
  const int32_t scale = -exponent;
  if (scale > MAX_COMPATIBLE_DECIMAL_DIGITS) {
    return false;
  }
  for (int32_t i = 0; i < scale; ++i) {
    magnitude.multiply(5);
  }
  return canonical_decimal(std::move(magnitude), negative, scale, out);
}

bool decimal_from_float32(float value, Decimal &out) {
  uint32_t bits = 0;
  std::memcpy(&bits, &value, sizeof(bits));
  const bool negative = (bits & 0x80000000u) != 0;
  const uint32_t exp_bits = (bits >> 23) & 0xffu;
  uint32_t frac = bits & 0x7fffffu;
  if (exp_bits == 0 && frac == 0) {
    out = Decimal();
    return true;
  }
  uint64_t significand = frac;
  int32_t exponent = -149;
  if (exp_bits != 0) {
    significand |= uint64_t{1} << 23;
    exponent = static_cast<int32_t>(exp_bits) - 127 - 23;
  }
  return decimal_from_float_bits(negative, significand, exponent, out);
}

bool decimal_from_float64(double value, Decimal &out) {
  uint64_t bits = 0;
  std::memcpy(&bits, &value, sizeof(bits));
  const bool negative = (bits & 0x8000000000000000ULL) != 0;
  const uint64_t exp_bits = (bits >> 52) & 0x7ffULL;
  uint64_t frac = bits & 0xfffffffffffffULL;
  if (exp_bits == 0 && frac == 0) {
    out = Decimal();
    return true;
  }
  uint64_t significand = frac;
  int32_t exponent = -1074;
  if (exp_bits != 0) {
    significand |= uint64_t{1} << 52;
    exponent = static_cast<int32_t>(exp_bits) - 1023 - 52;
  }
  return decimal_from_float_bits(negative, significand, exponent, out);
}

bool decimal_from_float16(float16_t value, Decimal &out) {
  uint16_t bits = value.to_bits();
  const bool negative = (bits & 0x8000u) != 0;
  const uint16_t exp_bits = (bits >> 10) & 0x1fu;
  uint16_t frac = bits & 0x03ffu;
  if (exp_bits == 0 && frac == 0) {
    out = Decimal();
    return true;
  }
  uint64_t significand = frac;
  int32_t exponent = -24;
  if (exp_bits != 0) {
    significand |= uint64_t{1} << 10;
    exponent = static_cast<int32_t>(exp_bits) - 15 - 10;
  }
  return decimal_from_float_bits(negative, significand, exponent, out);
}

bool decimal_from_bfloat16(bfloat16_t value, Decimal &out) {
  uint16_t bits = value.to_bits();
  const bool negative = (bits & 0x8000u) != 0;
  const uint16_t exp_bits = (bits >> 7) & 0xffu;
  uint16_t frac = bits & 0x007fu;
  if (exp_bits == 0 && frac == 0) {
    out = Decimal();
    return true;
  }
  uint64_t significand = frac;
  int32_t exponent = -133;
  if (exp_bits != 0) {
    significand |= uint64_t{1} << 7;
    exponent = static_cast<int32_t>(exp_bits) - 127 - 7;
  }
  return decimal_from_float_bits(negative, significand, exponent, out);
}

bool decimal_plain_string(const Decimal &decimal, std::string &out) {
  Decimal canonical;
  if (!canonical_decimal(decimal, canonical)) {
    return false;
  }
  BigUInt magnitude = decimal_magnitude(canonical);
  std::string digits = magnitude.to_decimal_string();
  if (canonical.scale() <= 0) {
    if (canonical.scale() < 0 && !magnitude.is_zero()) {
      digits.append(static_cast<size_t>(-canonical.scale()), '0');
    }
    out = (canonical.negative() && digits != "0" ? "-" : "") + digits;
    return true;
  }
  size_t scale = static_cast<size_t>(canonical.scale());
  if (digits.size() <= scale) {
    out = "0.";
    out.append(scale - digits.size(), '0');
    out += digits;
  } else {
    out = digits.substr(0, digits.size() - scale);
    out.push_back('.');
    out += digits.substr(digits.size() - scale);
  }
  while (!out.empty() && out.back() == '0') {
    out.pop_back();
  }
  if (!out.empty() && out.back() == '.') {
    out.pop_back();
  }
  if (canonical.negative() && out != "0") {
    out.insert(out.begin(), '-');
  }
  return true;
}

bool floating_plain_string(const Decimal &decimal, bool negative_zero,
                           std::string &out) {
  if (negative_zero) {
    out = "-0.0";
    return true;
  }
  if (!decimal_plain_string(decimal, out)) {
    return false;
  }
  if (out.find('.') == std::string::npos) {
    out += ".0";
  }
  return true;
}

ScalarValue read_scalar(ReadContext &ctx, uint32_t remote_type_id) {
  ScalarValue value{};
  switch (static_cast<TypeId>(remote_type_id)) {
  case TypeId::BOOL:
    value.kind = ScalarKind::Bool;
    {
      uint8_t raw = ctx.read_uint8(ctx.error());
      if (raw != 0 && raw != 1) {
        conversion_error(ctx, remote_type_id, remote_type_id, "<bool>",
                         "invalid bool payload");
        return value;
      }
      value.bool_value = raw != 0;
    }
    return value;
  case TypeId::INT8:
    value.kind = ScalarKind::Signed;
    value.signed_value = ctx.read_int8(ctx.error());
    return value;
  case TypeId::INT16:
    value.kind = ScalarKind::Signed;
    value.signed_value = ctx.read_int16(ctx.error());
    return value;
  case TypeId::INT32:
    value.kind = ScalarKind::Signed;
    value.signed_value = ctx.read_int32(ctx.error());
    return value;
  case TypeId::VARINT32:
    value.kind = ScalarKind::Signed;
    value.signed_value = ctx.read_var_int32(ctx.error());
    return value;
  case TypeId::INT64:
    value.kind = ScalarKind::Signed;
    value.signed_value = ctx.read_int64(ctx.error());
    return value;
  case TypeId::VARINT64:
    value.kind = ScalarKind::Signed;
    value.signed_value = ctx.read_var_int64(ctx.error());
    return value;
  case TypeId::TAGGED_INT64:
    value.kind = ScalarKind::Signed;
    value.signed_value = ctx.read_tagged_int64(ctx.error());
    return value;
  case TypeId::UINT8:
    value.kind = ScalarKind::Unsigned;
    value.unsigned_value = ctx.read_uint8(ctx.error());
    return value;
  case TypeId::UINT16:
    value.kind = ScalarKind::Unsigned;
    value.unsigned_value = static_cast<uint16_t>(ctx.read_int16(ctx.error()));
    return value;
  case TypeId::UINT32:
    value.kind = ScalarKind::Unsigned;
    value.unsigned_value = static_cast<uint32_t>(ctx.read_int32(ctx.error()));
    return value;
  case TypeId::VAR_UINT32:
    value.kind = ScalarKind::Unsigned;
    value.unsigned_value = ctx.read_var_uint32(ctx.error());
    return value;
  case TypeId::UINT64:
    value.kind = ScalarKind::Unsigned;
    value.unsigned_value = static_cast<uint64_t>(ctx.read_int64(ctx.error()));
    return value;
  case TypeId::VAR_UINT64:
    value.kind = ScalarKind::Unsigned;
    value.unsigned_value = ctx.read_var_uint64(ctx.error());
    return value;
  case TypeId::TAGGED_UINT64:
    value.kind = ScalarKind::Unsigned;
    value.unsigned_value = ctx.read_tagged_uint64(ctx.error());
    return value;
  case TypeId::FLOAT16:
    value.kind = ScalarKind::Float16;
    value.float16_value = ctx.read_f16(ctx.error());
    return value;
  case TypeId::BFLOAT16:
    value.kind = ScalarKind::BFloat16;
    value.bfloat16_value = ctx.read_bf16(ctx.error());
    return value;
  case TypeId::FLOAT32:
    value.kind = ScalarKind::Float32;
    value.float32_value = ctx.read_float(ctx.error());
    return value;
  case TypeId::FLOAT64:
    value.kind = ScalarKind::Float64;
    value.float64_value = ctx.read_double(ctx.error());
    return value;
  case TypeId::DECIMAL:
    value.kind = ScalarKind::Decimal;
    value.decimal_value = Serializer<Decimal>::read_data(ctx);
    return value;
  case TypeId::STRING:
    value.kind = ScalarKind::String;
    value.string_value = Serializer<std::string>::read_data(ctx);
    return value;
  default:
    ctx.set_error(Error::type_error("Unsupported compatible scalar type: " +
                                    std::to_string(remote_type_id)));
    return value;
  }
}

bool scalar_to_decimal(const ScalarValue &value, Decimal &out,
                       bool &negative_zero) {
  negative_zero = false;
  switch (value.kind) {
  case ScalarKind::Bool:
    out = Decimal::from_int64(value.bool_value ? 1 : 0);
    return true;
  case ScalarKind::Signed:
    out = decimal_from_signed(value.signed_value);
    return true;
  case ScalarKind::Unsigned:
    out = decimal_from_unsigned(value.unsigned_value);
    return true;
  case ScalarKind::Float16:
    if (!float16_t::is_finite(value.float16_value)) {
      return false;
    }
    negative_zero = float16_t::is_zero(value.float16_value) &&
                    float16_t::signbit(value.float16_value);
    return decimal_from_float16(value.float16_value, out);
  case ScalarKind::BFloat16:
    if (!bfloat16_t::is_finite(value.bfloat16_value)) {
      return false;
    }
    negative_zero = bfloat16_t::is_zero(value.bfloat16_value) &&
                    bfloat16_t::signbit(value.bfloat16_value);
    return decimal_from_bfloat16(value.bfloat16_value, out);
  case ScalarKind::Float32:
    if (!std::isfinite(value.float32_value)) {
      return false;
    }
    negative_zero =
        value.float32_value == 0.0f && std::signbit(value.float32_value);
    return decimal_from_float32(value.float32_value, out);
  case ScalarKind::Float64:
    if (!std::isfinite(value.float64_value)) {
      return false;
    }
    negative_zero =
        value.float64_value == 0.0 && std::signbit(value.float64_value);
    return decimal_from_float64(value.float64_value, out);
  case ScalarKind::Decimal:
    return canonical_decimal(value.decimal_value, out);
  case ScalarKind::String: {
    ParsedDecimal parsed;
    if (!parsed_decimal(value.string_value, parsed)) {
      return false;
    }
    out = parsed.decimal;
    negative_zero = parsed.negative_zero;
    return true;
  }
  }
  return false;
}

bool scalar_to_int64(const ScalarValue &value, int64_t min_value,
                     int64_t max_value, int64_t &out) {
  if (value.kind == ScalarKind::Bool) {
    out = value.bool_value ? 1 : 0;
    return out >= min_value && out <= max_value;
  }
  if (value.kind == ScalarKind::Signed) {
    out = value.signed_value;
    return out >= min_value && out <= max_value;
  }
  if (value.kind == ScalarKind::Unsigned) {
    if (value.unsigned_value > static_cast<uint64_t>(max_value)) {
      return false;
    }
    out = static_cast<int64_t>(value.unsigned_value);
    return true;
  }
  Decimal decimal;
  bool negative_zero = false;
  return scalar_to_decimal(value, decimal, negative_zero) &&
         decimal_to_int64(decimal, min_value, max_value, out);
}

bool scalar_to_uint64(const ScalarValue &value, uint64_t max_value,
                      uint64_t &out) {
  if (value.kind == ScalarKind::Bool) {
    out = value.bool_value ? 1 : 0;
    return out <= max_value;
  }
  if (value.kind == ScalarKind::Signed) {
    if (value.signed_value < 0) {
      return false;
    }
    out = static_cast<uint64_t>(value.signed_value);
    return out <= max_value;
  }
  if (value.kind == ScalarKind::Unsigned) {
    out = value.unsigned_value;
    return out <= max_value;
  }
  Decimal decimal;
  bool negative_zero = false;
  return scalar_to_decimal(value, decimal, negative_zero) &&
         decimal_to_uint64(decimal, max_value, out);
}

bool decimal_to_float16(const Decimal &decimal, float16_t &out) {
  std::string text;
  if (!decimal_plain_string(decimal, text)) {
    return false;
  }
  float parsed = std::strtof(text.c_str(), nullptr);
  out = float16_t::from_float(parsed);
  if (!float16_t::is_finite(out)) {
    return false;
  }
  Decimal actual;
  return decimal_from_float16(out, actual) &&
         decimal_equal_value(decimal, actual);
}

bool decimal_to_bfloat16(const Decimal &decimal, bfloat16_t &out) {
  std::string text;
  if (!decimal_plain_string(decimal, text)) {
    return false;
  }
  float parsed = std::strtof(text.c_str(), nullptr);
  out = bfloat16_t::from_float(parsed);
  if (!bfloat16_t::is_finite(out)) {
    return false;
  }
  Decimal actual;
  return decimal_from_bfloat16(out, actual) &&
         decimal_equal_value(decimal, actual);
}

bool decimal_to_float32(const Decimal &decimal, bool negative_zero,
                        float &out) {
  std::string text;
  if (!decimal_plain_string(decimal, text)) {
    return false;
  }
  out = std::strtof(text.c_str(), nullptr);
  if (negative_zero && decimal.is_zero()) {
    out = -0.0f;
  }
  if (!std::isfinite(out)) {
    return false;
  }
  Decimal actual;
  return decimal_from_float32(out, actual) &&
         decimal_equal_value(decimal, actual);
}

bool decimal_to_float64(const Decimal &decimal, bool negative_zero,
                        double &out) {
  std::string text;
  if (!decimal_plain_string(decimal, text)) {
    return false;
  }
  out = std::strtod(text.c_str(), nullptr);
  if (negative_zero && decimal.is_zero()) {
    out = -0.0;
  }
  if (!std::isfinite(out)) {
    return false;
  }
  Decimal actual;
  return decimal_from_float64(out, actual) &&
         decimal_equal_value(decimal, actual);
}

bool scalar_to_bool(const ScalarValue &value, bool &out) {
  if (value.kind == ScalarKind::Bool) {
    out = value.bool_value;
    return true;
  }
  if (value.kind == ScalarKind::String) {
    if (value.string_value == "0" || value.string_value == "false") {
      out = false;
      return true;
    }
    if (value.string_value == "1" || value.string_value == "true") {
      out = true;
      return true;
    }
    return false;
  }
  Decimal decimal;
  bool negative_zero = false;
  if (!scalar_to_decimal(value, decimal, negative_zero)) {
    return false;
  }
  int64_t integral = 0;
  if (!decimal_to_int64(decimal, 0, 1, integral)) {
    return false;
  }
  out = integral == 1;
  return true;
}

bool scalar_to_string(const ScalarValue &value, std::string &out) {
  switch (value.kind) {
  case ScalarKind::Bool:
    out = value.bool_value ? "true" : "false";
    return true;
  case ScalarKind::Signed:
    out = std::to_string(value.signed_value);
    return true;
  case ScalarKind::Unsigned:
    out = std::to_string(value.unsigned_value);
    return true;
  case ScalarKind::String:
    out = value.string_value;
    return true;
  case ScalarKind::Float16:
    if (!float16_t::is_finite(value.float16_value)) {
      return false;
    }
    {
      Decimal decimal;
      return decimal_from_float16(value.float16_value, decimal) &&
             floating_plain_string(decimal,
                                   float16_t::is_zero(value.float16_value) &&
                                       float16_t::signbit(value.float16_value),
                                   out);
    }
  case ScalarKind::BFloat16:
    if (!bfloat16_t::is_finite(value.bfloat16_value)) {
      return false;
    }
    {
      Decimal decimal;
      return decimal_from_bfloat16(value.bfloat16_value, decimal) &&
             floating_plain_string(
                 decimal,
                 bfloat16_t::is_zero(value.bfloat16_value) &&
                     bfloat16_t::signbit(value.bfloat16_value),
                 out);
    }
  case ScalarKind::Float32:
    if (!std::isfinite(value.float32_value)) {
      return false;
    }
    {
      Decimal decimal;
      return decimal_from_float32(value.float32_value, decimal) &&
             floating_plain_string(decimal,
                                   value.float32_value == 0.0f &&
                                       std::signbit(value.float32_value),
                                   out);
    }
  case ScalarKind::Float64:
    if (!std::isfinite(value.float64_value)) {
      return false;
    }
    {
      Decimal decimal;
      return decimal_from_float64(value.float64_value, decimal) &&
             floating_plain_string(decimal,
                                   value.float64_value == 0.0 &&
                                       std::signbit(value.float64_value),
                                   out);
    }
  case ScalarKind::Decimal:
    return decimal_plain_string(value.decimal_value, out);
  }
  return false;
}

bool scalar_to_float16(const ScalarValue &value, uint32_t remote_type_id,
                       float16_t &out) {
  if (remote_type_id == static_cast<uint32_t>(TypeId::FLOAT16)) {
    out = value.float16_value;
    return true;
  }
  if (value.kind == ScalarKind::BFloat16 &&
      bfloat16_t::is_inf(value.bfloat16_value)) {
    out = float16_t::from_bits(
        bfloat16_t::signbit(value.bfloat16_value) ? 0xFC00u : 0x7C00u);
    return true;
  }
  if (value.kind == ScalarKind::Float32 && std::isinf(value.float32_value)) {
    out = float16_t::from_float(value.float32_value);
    return float16_t::is_inf(out, std::signbit(value.float32_value) ? -1 : 1);
  }
  if (value.kind == ScalarKind::Float64 && std::isinf(value.float64_value)) {
    out = float16_t::from_float(static_cast<float>(value.float64_value));
    return float16_t::is_inf(out, std::signbit(value.float64_value) ? -1 : 1);
  }
  Decimal decimal;
  bool negative_zero = false;
  if (!scalar_to_decimal(value, decimal, negative_zero)) {
    return false;
  }
  if (negative_zero && decimal.is_zero()) {
    out = float16_t::from_bits(0x8000u);
    return true;
  }
  return decimal_to_float16(decimal, out);
}

bool scalar_to_bfloat16(const ScalarValue &value, uint32_t remote_type_id,
                        bfloat16_t &out) {
  if (remote_type_id == static_cast<uint32_t>(TypeId::BFLOAT16)) {
    out = value.bfloat16_value;
    return true;
  }
  if (value.kind == ScalarKind::Float16 &&
      float16_t::is_inf(value.float16_value)) {
    out = bfloat16_t::from_bits(
        float16_t::signbit(value.float16_value) ? 0xFF80u : 0x7F80u);
    return true;
  }
  if (value.kind == ScalarKind::Float32 && std::isinf(value.float32_value)) {
    out = bfloat16_t::from_float(value.float32_value);
    return bfloat16_t::is_inf(out, std::signbit(value.float32_value) ? -1 : 1);
  }
  if (value.kind == ScalarKind::Float64 && std::isinf(value.float64_value)) {
    out = bfloat16_t::from_float(static_cast<float>(value.float64_value));
    return bfloat16_t::is_inf(out, std::signbit(value.float64_value) ? -1 : 1);
  }
  Decimal decimal;
  bool negative_zero = false;
  if (!scalar_to_decimal(value, decimal, negative_zero)) {
    return false;
  }
  if (negative_zero && decimal.is_zero()) {
    out = bfloat16_t::from_bits(0x8000u);
    return true;
  }
  return decimal_to_bfloat16(decimal, out);
}

bool scalar_to_float32(const ScalarValue &value, uint32_t remote_type_id,
                       float &out) {
  if (remote_type_id == static_cast<uint32_t>(TypeId::FLOAT32)) {
    out = value.float32_value;
    return true;
  }
  if (value.kind == ScalarKind::Float16) {
    if (float16_t::is_nan(value.float16_value)) {
      return false;
    }
    out = value.float16_value.to_float();
    return true;
  }
  if (value.kind == ScalarKind::BFloat16) {
    if (bfloat16_t::is_nan(value.bfloat16_value)) {
      return false;
    }
    out = value.bfloat16_value.to_float();
    return true;
  }
  if (value.kind == ScalarKind::Float64 && std::isinf(value.float64_value)) {
    out = static_cast<float>(value.float64_value);
    return std::isinf(out) &&
           std::signbit(out) == std::signbit(value.float64_value);
  }
  Decimal decimal;
  bool negative_zero = false;
  return scalar_to_decimal(value, decimal, negative_zero) &&
         decimal_to_float32(decimal, negative_zero, out);
}

bool scalar_to_float64(const ScalarValue &value, uint32_t remote_type_id,
                       double &out) {
  if (remote_type_id == static_cast<uint32_t>(TypeId::FLOAT64)) {
    out = value.float64_value;
    return true;
  }
  if (value.kind == ScalarKind::Float16) {
    if (float16_t::is_nan(value.float16_value)) {
      return false;
    }
    out = static_cast<double>(value.float16_value.to_float());
    return true;
  }
  if (value.kind == ScalarKind::BFloat16) {
    if (bfloat16_t::is_nan(value.bfloat16_value)) {
      return false;
    }
    out = static_cast<double>(value.bfloat16_value.to_float());
    return true;
  }
  if (value.kind == ScalarKind::Float32) {
    if (std::isnan(value.float32_value)) {
      return false;
    }
    out = static_cast<double>(value.float32_value);
    return true;
  }
  Decimal decimal;
  bool negative_zero = false;
  return scalar_to_decimal(value, decimal, negative_zero) &&
         decimal_to_float64(decimal, negative_zero, out);
}

template <typename ResultType, typename Convert>
ResultType read_converted(ReadContext &ctx, uint32_t remote_type_id,
                          uint32_t local_type_id, std::string_view field,
                          Convert convert, ResultType fallback = ResultType{}) {
  ScalarValue value = read_scalar(ctx, remote_type_id);
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return fallback;
  }
  ResultType out{};
  if (!convert(value, out)) {
    conversion_error(ctx, remote_type_id, local_type_id, field,
                     "value is not lossless");
    return fallback;
  }
  return out;
}

} // namespace

bool compatible_scalar_field_types(uint32_t local_type_id,
                                   uint32_t remote_type_id) {
  ScalarKind local;
  ScalarKind remote;
  if (!scalar_kind(local_type_id, local) ||
      !scalar_kind(remote_type_id, remote)) {
    return false;
  }
  if (local == remote) {
    return true;
  }
  if (local == ScalarKind::Bool) {
    return remote == ScalarKind::String || is_numeric(remote);
  }
  if (remote == ScalarKind::Bool) {
    return local == ScalarKind::String || is_numeric(local);
  }
  if (local == ScalarKind::String) {
    return is_numeric(remote);
  }
  if (remote == ScalarKind::String) {
    return is_numeric(local);
  }
  return is_numeric(local) && is_numeric(remote);
}

FORY_NOINLINE bool read_compatible_scalar_present(ReadContext &ctx,
                                                  RefMode ref_mode) {
  if (ref_mode == RefMode::None) {
    return true;
  }
  int8_t flag = ctx.read_int8(ctx.error());
  if (FORY_PREDICT_FALSE(ctx.has_error())) {
    return false;
  }
  if (flag == NULL_FLAG) {
    return false;
  }
  if (flag == NOT_NULL_VALUE_FLAG) {
    return true;
  }
  ctx.set_error(Error::invalid_data("Invalid compatible scalar null flag: " +
                                    std::to_string(static_cast<int>(flag))));
  return false;
}

FORY_NOINLINE bool read_compatible_bool(ReadContext &ctx,
                                        uint32_t remote_type_id,
                                        std::string_view field) {
  return read_converted<bool>(ctx, remote_type_id,
                              static_cast<uint32_t>(TypeId::BOOL), field,
                              [](const ScalarValue &value, bool &out) {
                                return scalar_to_bool(value, out);
                              });
}

FORY_NOINLINE int8_t read_compatible_int8(ReadContext &ctx,
                                          uint32_t remote_type_id,
                                          std::string_view field) {
  return read_converted<int8_t>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::INT8), field,
      [](const ScalarValue &value, int8_t &out) {
        int64_t tmp = 0;
        if (!scalar_to_int64(value, std::numeric_limits<int8_t>::min(),
                             std::numeric_limits<int8_t>::max(), tmp)) {
          return false;
        }
        out = static_cast<int8_t>(tmp);
        return true;
      });
}

FORY_NOINLINE uint8_t read_compatible_uint8(ReadContext &ctx,
                                            uint32_t remote_type_id,
                                            std::string_view field) {
  return read_converted<uint8_t>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::UINT8), field,
      [](const ScalarValue &value, uint8_t &out) {
        uint64_t tmp = 0;
        if (!scalar_to_uint64(value, std::numeric_limits<uint8_t>::max(),
                              tmp)) {
          return false;
        }
        out = static_cast<uint8_t>(tmp);
        return true;
      });
}

FORY_NOINLINE int16_t read_compatible_int16(ReadContext &ctx,
                                            uint32_t remote_type_id,
                                            std::string_view field) {
  return read_converted<int16_t>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::INT16), field,
      [](const ScalarValue &value, int16_t &out) {
        int64_t tmp = 0;
        if (!scalar_to_int64(value, std::numeric_limits<int16_t>::min(),
                             std::numeric_limits<int16_t>::max(), tmp)) {
          return false;
        }
        out = static_cast<int16_t>(tmp);
        return true;
      });
}

FORY_NOINLINE uint16_t read_compatible_uint16(ReadContext &ctx,
                                              uint32_t remote_type_id,
                                              std::string_view field) {
  return read_converted<uint16_t>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::UINT16), field,
      [](const ScalarValue &value, uint16_t &out) {
        uint64_t tmp = 0;
        if (!scalar_to_uint64(value, std::numeric_limits<uint16_t>::max(),
                              tmp)) {
          return false;
        }
        out = static_cast<uint16_t>(tmp);
        return true;
      });
}

FORY_NOINLINE int32_t read_compatible_int32(ReadContext &ctx,
                                            uint32_t remote_type_id,
                                            std::string_view field) {
  return read_converted<int32_t>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::VARINT32), field,
      [](const ScalarValue &value, int32_t &out) {
        int64_t tmp = 0;
        if (!scalar_to_int64(value, std::numeric_limits<int32_t>::min(),
                             std::numeric_limits<int32_t>::max(), tmp)) {
          return false;
        }
        out = static_cast<int32_t>(tmp);
        return true;
      });
}

FORY_NOINLINE uint32_t read_compatible_uint32(ReadContext &ctx,
                                              uint32_t remote_type_id,
                                              std::string_view field) {
  return read_converted<uint32_t>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::UINT32), field,
      [](const ScalarValue &value, uint32_t &out) {
        uint64_t tmp = 0;
        if (!scalar_to_uint64(value, std::numeric_limits<uint32_t>::max(),
                              tmp)) {
          return false;
        }
        out = static_cast<uint32_t>(tmp);
        return true;
      });
}

FORY_NOINLINE int64_t read_compatible_int64(ReadContext &ctx,
                                            uint32_t remote_type_id,
                                            std::string_view field) {
  return read_converted<int64_t>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::VARINT64), field,
      [](const ScalarValue &value, int64_t &out) {
        return scalar_to_int64(value, std::numeric_limits<int64_t>::min(),
                               std::numeric_limits<int64_t>::max(), out);
      });
}

FORY_NOINLINE uint64_t read_compatible_uint64(ReadContext &ctx,
                                              uint32_t remote_type_id,
                                              std::string_view field) {
  return read_converted<uint64_t>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::UINT64), field,
      [](const ScalarValue &value, uint64_t &out) {
        return scalar_to_uint64(value, std::numeric_limits<uint64_t>::max(),
                                out);
      });
}

FORY_NOINLINE float16_t read_compatible_float16(ReadContext &ctx,
                                                uint32_t remote_type_id,
                                                std::string_view field) {
  return read_converted<float16_t>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::FLOAT16), field,
      [remote_type_id](const ScalarValue &value, float16_t &out) {
        return scalar_to_float16(value, remote_type_id, out);
      });
}

FORY_NOINLINE bfloat16_t read_compatible_bfloat16(ReadContext &ctx,
                                                  uint32_t remote_type_id,
                                                  std::string_view field) {
  return read_converted<bfloat16_t>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::BFLOAT16), field,
      [remote_type_id](const ScalarValue &value, bfloat16_t &out) {
        return scalar_to_bfloat16(value, remote_type_id, out);
      });
}

FORY_NOINLINE float read_compatible_float32(ReadContext &ctx,
                                            uint32_t remote_type_id,
                                            std::string_view field) {
  return read_converted<float>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::FLOAT32), field,
      [remote_type_id](const ScalarValue &value, float &out) {
        return scalar_to_float32(value, remote_type_id, out);
      });
}

FORY_NOINLINE double read_compatible_float64(ReadContext &ctx,
                                             uint32_t remote_type_id,
                                             std::string_view field) {
  return read_converted<double>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::FLOAT64), field,
      [remote_type_id](const ScalarValue &value, double &out) {
        return scalar_to_float64(value, remote_type_id, out);
      });
}

FORY_NOINLINE Decimal read_compatible_decimal(ReadContext &ctx,
                                              uint32_t remote_type_id,
                                              std::string_view field) {
  if (remote_type_id == static_cast<uint32_t>(TypeId::DECIMAL)) {
    return Serializer<Decimal>::read_data(ctx);
  }
  return read_converted<Decimal>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::DECIMAL), field,
      [](const ScalarValue &value, Decimal &out) {
        bool negative_zero = false;
        return scalar_to_decimal(value, out, negative_zero);
      });
}

FORY_NOINLINE std::string read_compatible_string(ReadContext &ctx,
                                                 uint32_t remote_type_id,
                                                 std::string_view field) {
  return read_converted<std::string>(
      ctx, remote_type_id, static_cast<uint32_t>(TypeId::STRING), field,
      [](const ScalarValue &value, std::string &out) {
        return scalar_to_string(value, out);
      });
}

} // namespace serialization
} // namespace fory
