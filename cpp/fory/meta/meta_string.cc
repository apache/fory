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

#include "fory/meta/meta_string.h"

#include "fory/util/buffer.h"

#include <algorithm>
#include <cctype>
#include <string>
#include <vector>

namespace fory {
namespace meta {

namespace {

bool is_number_string(const std::string &input) {
  if (input.empty()) {
    return false;
  }
  size_t start = 0;
  if (input[0] == '-') {
    if (input.size() == 1) {
      return false;
    }
    start = 1;
  }
  for (size_t i = start; i < input.size(); ++i) {
    if (!std::isdigit(static_cast<unsigned char>(input[i]))) {
      return false;
    }
  }
  return true;
}

bool is_all_zero(const std::vector<uint8_t> &bytes) {
  for (uint8_t b : bytes) {
    if (b != 0) {
      return false;
    }
  }
  return true;
}

std::vector<uint8_t> encode_decimal_to_bytes(const std::string &digits) {
  std::vector<uint8_t> magnitude(1, 0);
  for (char c : digits) {
    uint16_t carry = static_cast<uint16_t>(c - '0');
    for (size_t i = magnitude.size(); i-- > 0;) {
      uint16_t value = static_cast<uint16_t>(magnitude[i]) * 10 + carry;
      magnitude[i] = static_cast<uint8_t>(value & 0xFF);
      carry = value >> 8;
    }
    while (carry != 0) {
      magnitude.insert(magnitude.begin(), static_cast<uint8_t>(carry & 0xFF));
      carry >>= 8;
    }
  }
  while (magnitude.size() > 1 && magnitude[0] == 0) {
    magnitude.erase(magnitude.begin());
  }
  return magnitude;
}

std::vector<uint8_t> encode_number_string(const std::string &input) {
  bool negative = !input.empty() && input[0] == '-';
  std::string digits = negative ? input.substr(1) : input;
  std::vector<uint8_t> magnitude = encode_decimal_to_bytes(digits);
  if (is_all_zero(magnitude)) {
    negative = false;
  }

  if (negative) {
    for (uint8_t &b : magnitude) {
      b = static_cast<uint8_t>(~b);
    }
    int carry = 1;
    for (size_t i = magnitude.size(); i-- > 0;) {
      int sum = static_cast<int>(magnitude[i]) + carry;
      magnitude[i] = static_cast<uint8_t>(sum & 0xFF);
      carry = sum >> 8;
    }
    if (carry != 0) {
      magnitude.insert(magnitude.begin(), 0xFF);
    }
    while (magnitude.size() > 1 &&
           magnitude[0] == 0xFF &&
           (magnitude[1] & 0x80) != 0) {
      magnitude.erase(magnitude.begin());
    }
  } else if ((magnitude[0] & 0x80) != 0) {
    magnitude.insert(magnitude.begin(), 0x00);
  }

  std::vector<uint8_t> bytes;
  bytes.reserve(magnitude.size() + 1);
  bytes.push_back(static_cast<uint8_t>(MetaExtendedEncoding::NUMBER_STRING));
  bytes.insert(bytes.end(), magnitude.begin(), magnitude.end());
  return bytes;
}

std::string decode_number_string(const uint8_t *data, size_t len) {
  if (len == 0) {
    return "";
  }
  std::vector<uint8_t> bytes(data, data + len);
  bool negative = (bytes[0] & 0x80) != 0;
  if (negative) {
    for (uint8_t &b : bytes) {
      b = static_cast<uint8_t>(~b);
    }
    int carry = 1;
    for (size_t i = bytes.size(); i-- > 0;) {
      int sum = static_cast<int>(bytes[i]) + carry;
      bytes[i] = static_cast<uint8_t>(sum & 0xFF);
      carry = sum >> 8;
    }
    while (bytes.size() > 1 && bytes[0] == 0) {
      bytes.erase(bytes.begin());
    }
  } else {
    while (bytes.size() > 1 && bytes[0] == 0) {
      bytes.erase(bytes.begin());
    }
  }

  if (is_all_zero(bytes)) {
    return "0";
  }

  std::string digits;
  std::vector<uint8_t> temp = bytes;
  while (!temp.empty()) {
    uint32_t remainder = 0;
    for (size_t i = 0; i < temp.size(); ++i) {
      uint32_t value = (remainder << 8) | temp[i];
      temp[i] = static_cast<uint8_t>(value / 10);
      remainder = value % 10;
    }
    digits.push_back(static_cast<char>('0' + remainder));
    while (!temp.empty() && temp[0] == 0) {
      temp.erase(temp.begin());
    }
  }
  std::reverse(digits.begin(), digits.end());
  if (negative) {
    digits.insert(digits.begin(), '-');
  }
  return digits;
}

std::vector<uint8_t> encode_extended_utf8(const std::string &input) {
  std::vector<uint8_t> bytes;
  bytes.reserve(input.size() + 1);
  bytes.push_back(static_cast<uint8_t>(MetaExtendedEncoding::UTF8));
  bytes.insert(bytes.end(), input.begin(), input.end());
  return bytes;
}

} // namespace

MetaStringDecoder::MetaStringDecoder(char special_char1, char special_char2)
    : special_char1_(special_char1), special_char2_(special_char2) {}

Result<std::string, Error>
MetaStringDecoder::decode(const uint8_t *data, size_t len,
                          MetaEncoding encoding) const {
  std::string decoded;
  if (len == 0) {
    decoded = "";
  } else {
    switch (encoding) {
    case MetaEncoding::LOWER_SPECIAL: {
      auto res = decode_lower_special(data, len);
      if (!res.ok()) {
        return Unexpected(res.error());
      }
      decoded = std::move(res.value());
      break;
    }
    case MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL: {
      auto res = decode_lower_upper_digit_special(data, len);
      if (!res.ok()) {
        return Unexpected(res.error());
      }
      decoded = std::move(res.value());
      break;
    }
    case MetaEncoding::FIRST_TO_LOWER_SPECIAL: {
      auto res = decode_rep_first_lower_special(data, len);
      if (!res.ok()) {
        return Unexpected(res.error());
      }
      decoded = std::move(res.value());
      break;
    }
    case MetaEncoding::ALL_TO_LOWER_SPECIAL: {
      auto res = decode_rep_all_to_lower_special(data, len);
      if (!res.ok()) {
        return Unexpected(res.error());
      }
      decoded = std::move(res.value());
      break;
    }
    case MetaEncoding::EXTENDED: {
      if (len == 0) {
        decoded = "";
        break;
      }
      uint8_t actual = data[0];
      const uint8_t *payload = data + 1;
      size_t payload_len = len - 1;
      switch (static_cast<MetaExtendedEncoding>(actual)) {
      case MetaExtendedEncoding::UTF8:
        decoded.assign(reinterpret_cast<const char *>(payload), payload_len);
        break;
      case MetaExtendedEncoding::NUMBER_STRING:
        decoded = decode_number_string(payload, payload_len);
        break;
      default:
        return Unexpected(Error::encoding_error(
            "Unsupported extended meta string encoding value: " +
            std::to_string(static_cast<int>(actual))));
      }
      break;
    }
    default:
      decoded.assign(reinterpret_cast<const char *>(data), len);
      break;
    }
  }
  return decoded;
}

Result<std::string, Error>
MetaStringDecoder::decode_lower_special(const uint8_t *data, size_t len) const {
  std::string decoded;
  if (len == 0) {
    return decoded;
  }
  const size_t total_bits = len * 8;
  const bool strip_last_char = (data[0] & 0x80) != 0;
  const size_t bit_mask = 0b11111;
  size_t bit_index = 1;

  while (bit_index + 5 <= total_bits &&
         !(strip_last_char && (bit_index + 2 * 5 > total_bits))) {
    const size_t byte_index = bit_index / 8;
    const size_t intra_byte_index = bit_index % 8;
    size_t char_value;
    if (intra_byte_index > 3) {
      uint16_t two_bytes = static_cast<uint16_t>(data[byte_index]) << 8;
      if (byte_index + 1 < len) {
        two_bytes |= data[byte_index + 1];
      }
      char_value = (static_cast<size_t>(two_bytes) >> (11 - intra_byte_index)) &
                   bit_mask;
    } else {
      char_value =
          (static_cast<size_t>(data[byte_index]) >> (3 - intra_byte_index)) &
          bit_mask;
    }
    bit_index += 5;
    FORY_TRY(ch, decode_lower_special_char(static_cast<uint8_t>(char_value)));
    decoded.push_back(ch);
  }
  return decoded;
}

Result<std::string, Error>
MetaStringDecoder::decode_lower_upper_digit_special(const uint8_t *data,
                                                    size_t len) const {
  std::string decoded;
  if (len == 0) {
    return decoded;
  }
  const size_t total_bits = len * 8;
  const bool strip_last_char = (data[0] & 0x80) != 0;
  const size_t bit_mask = 0b111111;
  size_t bit_index = 1;

  while (bit_index + 6 <= total_bits &&
         !(strip_last_char && (bit_index + 2 * 6 > total_bits))) {
    const size_t byte_index = bit_index / 8;
    const size_t intra_byte_index = bit_index % 8;
    size_t char_value;
    if (intra_byte_index > 2) {
      uint16_t two_bytes = static_cast<uint16_t>(data[byte_index]) << 8;
      if (byte_index + 1 < len) {
        two_bytes |= data[byte_index + 1];
      }
      char_value = (static_cast<size_t>(two_bytes) >> (10 - intra_byte_index)) &
                   bit_mask;
    } else {
      char_value =
          (static_cast<size_t>(data[byte_index]) >> (2 - intra_byte_index)) &
          bit_mask;
    }
    bit_index += 6;
    FORY_TRY(ch, decode_lower_upper_digit_special_char(
                     static_cast<uint8_t>(char_value)));
    decoded.push_back(ch);
  }
  return decoded;
}

Result<std::string, Error>
MetaStringDecoder::decode_rep_first_lower_special(const uint8_t *data,
                                                  size_t len) const {
  FORY_TRY(base, decode_lower_special(data, len));
  if (base.empty()) {
    return base;
  }
  std::string result;
  result.reserve(base.size());
  auto it = base.begin();
  result.push_back(static_cast<char>(std::toupper(*it)));
  ++it;
  result.append(it, base.end());
  return result;
}

Result<std::string, Error>
MetaStringDecoder::decode_rep_all_to_lower_special(const uint8_t *data,
                                                   size_t len) const {
  FORY_TRY(base, decode_lower_special(data, len));
  std::string result;
  result.reserve(base.size());
  bool skip = false;
  for (size_t i = 0; i < base.size(); ++i) {
    char c = base[i];
    if (skip) {
      skip = false;
      continue;
    }
    if (c == '|') {
      if (i + 1 < base.size()) {
        result.push_back(static_cast<char>(std::toupper(base[i + 1])));
      }
      skip = true;
    } else {
      result.push_back(c);
    }
  }
  return result;
}

Result<char, Error>
MetaStringDecoder::decode_lower_special_char(uint8_t value) const {
  if (value <= 25) {
    return static_cast<char>('a' + value);
  }
  switch (value) {
  case 26:
    return '.';
  case 27:
    return '_';
  case 28:
    return '$';
  case 29:
    return '|';
  default:
    return Unexpected(Error::encode_error(
        "Invalid character value for LOWER_SPECIAL decoding: " +
        std::to_string(static_cast<int>(value))));
  }
}

Result<char, Error>
MetaStringDecoder::decode_lower_upper_digit_special_char(uint8_t value) const {
  if (value <= 25) {
    return static_cast<char>('a' + value);
  } else if (value <= 51) {
    return static_cast<char>('A' + (value - 26));
  } else if (value <= 61) {
    return static_cast<char>('0' + (value - 52));
  }
  switch (value) {
  case 62:
    return special_char1_;
  case 63:
    return special_char2_;
  default:
    return Unexpected(Error::encode_error(
        "Invalid character value for LOWER_UPPER_DIGIT_SPECIAL decoding: " +
        std::to_string(static_cast<int>(value))));
  }
}

MetaStringTable::MetaStringTable() = default;

Result<std::string, Error>
MetaStringTable::read_string(Buffer &buffer, const MetaStringDecoder &decoder) {
  Error error;
  // Header is encoded with VarUint32Small7 on Java side, but wire
  // format is still standard varuint32.
  uint32_t header = buffer.read_var_uint32(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return Unexpected(std::move(error));
  }
  uint32_t len_or_id = header >> 1;
  bool is_ref = (header & 0x1u) != 0;

  if (is_ref) {
    if (len_or_id == 0 || len_or_id > entries_.size()) {
      return Unexpected(Error::invalid_data(
          "Invalid meta string reference id: " + std::to_string(len_or_id)));
    }
    return entries_[len_or_id - 1].decoded;
  }

  constexpr uint32_t k_small_threshold = 16;
  uint32_t len = len_or_id;

  std::vector<uint8_t> bytes;
  MetaEncoding encoding = MetaEncoding::EXTENDED;

  if (len > k_small_threshold) {
    // Big string layout in Java MetaStringResolver:
    //   header (len<<1 | flags) + hash_code(int64) + data[len]
    int64_t hash_code = buffer.read_int64(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    uint8_t encoding_byte = static_cast<uint8_t>(hash_code & 0xFF);
    FORY_TRY(enc, to_meta_encoding(encoding_byte));
    encoding = enc;
    bytes.resize(len);
    if (len > 0) {
      buffer.read_bytes(bytes.data(), len, error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
    }
  } else {
    // Small string layout: encoding(byte) + data[len]
    if (len == 0) {
      encoding = MetaEncoding::EXTENDED;
    } else {
      int8_t enc_byte_res = buffer.read_int8(error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
      uint8_t enc_byte = static_cast<uint8_t>(enc_byte_res);
      FORY_TRY(enc, to_meta_encoding(enc_byte));
      encoding = enc;
      bytes.resize(len);
      buffer.read_bytes(bytes.data(), len, error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return Unexpected(std::move(error));
      }
    }
  }

  std::string decoded;
  if (len == 0) {
    decoded = "";
  } else {
    FORY_TRY(tmp, decoder.decode(bytes.data(), bytes.size(), encoding));
    decoded = std::move(tmp);
  }

  entries_.push_back(Entry{decoded});
  return decoded;
}

void MetaStringTable::reset() { entries_.clear(); }

Result<MetaEncoding, Error> to_meta_encoding(uint8_t value) {
  switch (value) {
  case 0x00:
    return MetaEncoding::EXTENDED;
  case 0x01:
    return MetaEncoding::LOWER_SPECIAL;
  case 0x02:
    return MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL;
  case 0x03:
    return MetaEncoding::FIRST_TO_LOWER_SPECIAL;
  case 0x04:
    return MetaEncoding::ALL_TO_LOWER_SPECIAL;
  default:
    return Unexpected(
        Error::encoding_error("Unsupported meta string encoding value: " +
                              std::to_string(static_cast<int>(value))));
  }
}

// MetaStringEncoder implementation

MetaStringEncoder::MetaStringEncoder(char special_char1, char special_char2)
    : special_char1_(special_char1), special_char2_(special_char2) {}

MetaStringEncoder::StringStatistics
MetaStringEncoder::compute_statistics(const std::string &input) const {
  StringStatistics stats{0, 0, true, true};
  for (char c : input) {
    // Check if can_lower_upper_digit_special_encoded
    if (stats.can_lower_upper_digit_special_encoded) {
      bool is_valid = std::islower(static_cast<unsigned char>(c)) ||
                      std::isupper(static_cast<unsigned char>(c)) ||
                      std::isdigit(static_cast<unsigned char>(c)) ||
                      c == special_char1_ || c == special_char2_;
      if (!is_valid) {
        stats.can_lower_upper_digit_special_encoded = false;
      }
    }
    // Check if can_lower_special_encoded
    if (stats.can_lower_special_encoded) {
      bool is_valid = std::islower(static_cast<unsigned char>(c)) || c == '.' ||
                      c == '_' || c == '$' || c == '|';
      if (!is_valid) {
        stats.can_lower_special_encoded = false;
      }
    }
    if (std::isdigit(static_cast<unsigned char>(c))) {
      stats.digit_count++;
    }
    if (std::isupper(static_cast<unsigned char>(c))) {
      stats.upper_count++;
    }
  }
  return stats;
}

MetaEncoding MetaStringEncoder::compute_encoding(
    const std::string &input,
    const std::vector<MetaEncoding> &encodings) const {
  if (is_number_string(input)) {
    return MetaEncoding::EXTENDED;
  }
  auto allow = [&encodings](MetaEncoding e) {
    return encodings.empty() ||
           std::find(encodings.begin(), encodings.end(), e) != encodings.end();
  };

  StringStatistics stats = compute_statistics(input);

  if (stats.can_lower_special_encoded && allow(MetaEncoding::LOWER_SPECIAL)) {
    return MetaEncoding::LOWER_SPECIAL;
  }

  if (stats.can_lower_upper_digit_special_encoded) {
    if (stats.digit_count != 0 &&
        allow(MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL)) {
      return MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL;
    }

    int upper_count = stats.upper_count;
    if (upper_count == 1 && !input.empty() &&
        std::isupper(static_cast<unsigned char>(input[0])) &&
        allow(MetaEncoding::FIRST_TO_LOWER_SPECIAL)) {
      return MetaEncoding::FIRST_TO_LOWER_SPECIAL;
    }

    // Check if ALL_TO_LOWER_SPECIAL is more efficient
    // (input.len() + upper_count) * 5 < input.len() * 6
    if ((input.size() + upper_count) * 5 < input.size() * 6 &&
        allow(MetaEncoding::ALL_TO_LOWER_SPECIAL)) {
      return MetaEncoding::ALL_TO_LOWER_SPECIAL;
    }

    if (allow(MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL)) {
      return MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL;
    }
  }

  return MetaEncoding::EXTENDED;
}

int MetaStringEncoder::lower_special_char_value(char c) const {
  if (c >= 'a' && c <= 'z') {
    return c - 'a';
  }
  switch (c) {
  case '.':
    return 26;
  case '_':
    return 27;
  case '$':
    return 28;
  case '|':
    return 29;
  default:
    return -1; // Invalid
  }
}

int MetaStringEncoder::lower_upper_digit_special_char_value(char c) const {
  if (c >= 'a' && c <= 'z') {
    return c - 'a';
  }
  if (c >= 'A' && c <= 'Z') {
    return c - 'A' + 26;
  }
  if (c >= '0' && c <= '9') {
    return c - '0' + 52;
  }
  if (c == special_char1_) {
    return 62;
  }
  if (c == special_char2_) {
    return 63;
  }
  return -1; // Invalid
}

std::vector<uint8_t>
MetaStringEncoder::encode_lower_special(const std::string &input) const {
  const int bits_per_char = 5;
  size_t total_bits = input.size() * bits_per_char + 1;
  size_t byte_length = (total_bits + 7) / 8;
  std::vector<uint8_t> bytes(byte_length, 0);

  size_t current_bit = 1;
  for (char c : input) {
    int value = lower_special_char_value(c);
    for (int i = bits_per_char - 1; i >= 0; --i) {
      if ((value & (1 << i)) != 0) {
        size_t byte_pos = current_bit / 8;
        size_t bit_pos = current_bit % 8;
        bytes[byte_pos] |= static_cast<uint8_t>(1 << (7 - bit_pos));
      }
      current_bit++;
    }
  }

  // Set strip_last_char flag if there's room for an extra character
  if (byte_length * 8 >= total_bits + bits_per_char) {
    bytes[0] |= 0x80;
  }

  return bytes;
}

std::vector<uint8_t> MetaStringEncoder::encode_lower_upper_digit_special(
    const std::string &input) const {
  const int bits_per_char = 6;
  size_t total_bits = input.size() * bits_per_char + 1;
  size_t byte_length = (total_bits + 7) / 8;
  std::vector<uint8_t> bytes(byte_length, 0);

  size_t current_bit = 1;
  for (char c : input) {
    int value = lower_upper_digit_special_char_value(c);
    for (int i = bits_per_char - 1; i >= 0; --i) {
      if ((value & (1 << i)) != 0) {
        size_t byte_pos = current_bit / 8;
        size_t bit_pos = current_bit % 8;
        bytes[byte_pos] |= static_cast<uint8_t>(1 << (7 - bit_pos));
      }
      current_bit++;
    }
  }

  // Set strip_last_char flag if there's room for an extra character
  if (byte_length * 8 >= total_bits + bits_per_char) {
    bytes[0] |= 0x80;
  }

  return bytes;
}

std::vector<uint8_t> MetaStringEncoder::encode_first_to_lower_special(
    const std::string &input) const {
  if (input.empty()) {
    return encode_lower_special("");
  }

  std::string modified = input;
  modified[0] =
      static_cast<char>(std::tolower(static_cast<unsigned char>(modified[0])));
  return encode_lower_special(modified);
}

std::vector<uint8_t>
MetaStringEncoder::encode_all_to_lower_special(const std::string &input) const {
  std::string modified;
  modified.reserve(input.size() * 2); // Worst case: all uppercase
  for (char c : input) {
    if (std::isupper(static_cast<unsigned char>(c))) {
      modified.push_back('|');
      modified.push_back(
          static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
    } else {
      modified.push_back(c);
    }
  }
  return encode_lower_special(modified);
}

Result<EncodedMetaString, Error>
MetaStringEncoder::encode(const std::string &input,
                          const std::vector<MetaEncoding> &encodings) const {
  EncodedMetaString result;

  if (input.empty()) {
    result.encoding = MetaEncoding::EXTENDED;
    result.bytes.clear();
    return result;
  }

  if (is_number_string(input)) {
    result.encoding = MetaEncoding::EXTENDED;
    result.bytes = encode_number_string(input);
    return result;
  }

  // Check for non-ASCII characters - use UTF8 for those
  for (char c : input) {
    if (static_cast<unsigned char>(c) > 127) {
      result.encoding = MetaEncoding::EXTENDED;
      result.bytes = encode_extended_utf8(input);
      return result;
    }
  }

  MetaEncoding encoding = compute_encoding(input, encodings);
  result.encoding = encoding;

  switch (encoding) {
  case MetaEncoding::LOWER_SPECIAL:
    result.bytes = encode_lower_special(input);
    break;
  case MetaEncoding::LOWER_UPPER_DIGIT_SPECIAL:
    result.bytes = encode_lower_upper_digit_special(input);
    break;
  case MetaEncoding::FIRST_TO_LOWER_SPECIAL:
    result.bytes = encode_first_to_lower_special(input);
    break;
  case MetaEncoding::ALL_TO_LOWER_SPECIAL:
    result.bytes = encode_all_to_lower_special(input);
    break;
  case MetaEncoding::EXTENDED:
  default:
    result.bytes = encode_extended_utf8(input);
    break;
  }

  return result;
}

} // namespace meta
} // namespace fory
