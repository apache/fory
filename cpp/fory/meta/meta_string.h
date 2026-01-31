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

#pragma once

#include "fory/util/buffer.h"
#include "fory/util/error.h"
#include "fory/util/result.h"

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace fory {
namespace meta {

// Encoding for meta strings, aligned with Rust/Java Encoding
// definitions in fory-core.
enum class MetaEncoding : uint8_t {
  EXTENDED = 0x00,
  LOWER_SPECIAL = 0x01,
  LOWER_UPPER_DIGIT_SPECIAL = 0x02,
  FIRST_TO_LOWER_SPECIAL = 0x03,
  ALL_TO_LOWER_SPECIAL = 0x04,
};

enum class MetaExtendedEncoding : uint8_t {
  UTF8 = 0x00,
  NUMBER_STRING = 0x01,
  NEGATIVE_NUMBER_STRING = 0x02,
};

// Decoder for meta strings used by xlang type metadata and
// Java's MetaStringResolver. This mirrors the behavior of
// rust/fory-core/src/meta/meta_string.rs (MetaStringDecoder).
class MetaStringDecoder {
public:
  MetaStringDecoder(char special_char1, char special_char2);

  Result<std::string, Error> decode(const uint8_t *data, size_t len,
                                    MetaEncoding encoding) const;

private:
  char special_char1_;
  char special_char2_;

  Result<std::string, Error> decode_lower_special(const uint8_t *data,
                                                  size_t len) const;

  Result<std::string, Error>
  decode_lower_upper_digit_special(const uint8_t *data, size_t len) const;

  Result<std::string, Error> decode_rep_first_lower_special(const uint8_t *data,
                                                            size_t len) const;

  Result<std::string, Error>
  decode_rep_all_to_lower_special(const uint8_t *data, size_t len) const;

  Result<char, Error> decode_lower_special_char(uint8_t value) const;

  Result<char, Error>
  decode_lower_upper_digit_special_char(uint8_t value) const;
};

// Dynamic meta string table used to decode strings written by
// Java's MetaStringResolver (used for named type/class info).
//
// The wire format is documented in
// java/fory-core/src/main/java/org/apache/fory/resolver/MetaStringResolver.java.
class MetaStringTable {
public:
  MetaStringTable();

  // Decode a meta string from buffer using the provided decoder.
  // This mirrors MetaStringResolver#read_meta_string_bytes.
  Result<std::string, Error> read_string(Buffer &buffer,
                                         const MetaStringDecoder &decoder);

  void reset();

private:
  struct Entry {
    std::string decoded;
  };

  std::vector<Entry> entries_;
};

// Helper to map raw encoding byte to MetaEncoding.
Result<MetaEncoding, Error> to_meta_encoding(uint8_t value);

// Result of encoding a string to meta string format.
struct EncodedMetaString {
  MetaEncoding encoding;
  std::vector<uint8_t> bytes;
};

// Encoder for meta strings used by xlang type metadata.
// This mirrors the behavior of Java's MetaStringEncoder.
class MetaStringEncoder {
public:
  MetaStringEncoder(char special_char1, char special_char2);

  // encode a string using the best available encoding from the given options.
  // If encodings is empty, uses all available encodings.
  Result<EncodedMetaString, Error>
  encode(const std::string &input,
         const std::vector<MetaEncoding> &encodings = {}) const;

private:
  char special_char1_;
  char special_char2_;

  struct StringStatistics {
    int digit_count;
    int upper_count;
    bool can_lower_special_encoded;
    bool can_lower_upper_digit_special_encoded;
  };

  StringStatistics compute_statistics(const std::string &input) const;
  MetaEncoding
  compute_encoding(const std::string &input,
                   const std::vector<MetaEncoding> &encodings) const;

  std::vector<uint8_t> encode_lower_special(const std::string &input) const;
  std::vector<uint8_t>
  encode_all_to_lower_special(const std::string &input) const;
  std::vector<uint8_t>
  encode_lower_upper_digit_special(const std::string &input) const;
  std::vector<uint8_t>
  encode_first_to_lower_special(const std::string &input) const;

  int lower_special_char_value(char c) const;
  int lower_upper_digit_special_char_value(char c) const;
};

} // namespace meta
} // namespace fory
