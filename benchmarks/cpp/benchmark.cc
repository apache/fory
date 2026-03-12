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

#include <benchmark/benchmark.h>
#include <cstdint>
#include <msgpack.hpp>
#include <string>
#include <vector>

#include "bench.pb.h"
#include "fory/serialization/context.h"
#include "fory/serialization/fory.h"
#include "fory/serialization/struct_serializer.h"

// ============================================================================
// Fory struct definitions (must match proto messages)
// ============================================================================

struct NumericStruct {
  int32_t f1;
  int32_t f2;
  int32_t f3;
  int32_t f4;
  int32_t f5;
  int32_t f6;
  int32_t f7;
  int32_t f8;

  bool operator==(const NumericStruct &other) const {
    return f1 == other.f1 && f2 == other.f2 && f3 == other.f3 &&
           f4 == other.f4 && f5 == other.f5 && f6 == other.f6 &&
           f7 == other.f7 && f8 == other.f8;
  }
  MSGPACK_DEFINE_MAP(f1, f2, f3, f4, f5, f6, f7, f8);
};
FORY_STRUCT(NumericStruct, f1, f2, f3, f4, f5, f6, f7, f8);
FORY_FIELD_TAGS(NumericStruct, (f1, 1), (f2, 2), (f3, 3), (f4, 4), (f5, 5),
                (f6, 6), (f7, 7), (f8, 8));

struct Sample {
  int32_t int_value;
  int64_t long_value;
  float float_value;
  double double_value;
  int32_t short_value;
  int32_t char_value;
  bool boolean_value;
  int32_t int_value_boxed;
  int64_t long_value_boxed;
  float float_value_boxed;
  double double_value_boxed;
  int32_t short_value_boxed;
  int32_t char_value_boxed;
  bool boolean_value_boxed;
  std::vector<int32_t> int_array;
  std::vector<int64_t> long_array;
  std::vector<float> float_array;
  std::vector<double> double_array;
  std::vector<int32_t> short_array;
  std::vector<int32_t> char_array;
  std::vector<bool> boolean_array;
  std::string string;

  bool operator==(const Sample &other) const {
    return int_value == other.int_value && long_value == other.long_value &&
           float_value == other.float_value &&
           double_value == other.double_value &&
           short_value == other.short_value && char_value == other.char_value &&
           boolean_value == other.boolean_value &&
           int_value_boxed == other.int_value_boxed &&
           long_value_boxed == other.long_value_boxed &&
           float_value_boxed == other.float_value_boxed &&
           double_value_boxed == other.double_value_boxed &&
           short_value_boxed == other.short_value_boxed &&
           char_value_boxed == other.char_value_boxed &&
           boolean_value_boxed == other.boolean_value_boxed &&
           int_array == other.int_array && long_array == other.long_array &&
           float_array == other.float_array &&
           double_array == other.double_array &&
           short_array == other.short_array && char_array == other.char_array &&
           boolean_array == other.boolean_array && string == other.string;
  }
  MSGPACK_DEFINE_MAP(int_value, long_value, float_value, double_value,
                     short_value, char_value, boolean_value, int_value_boxed,
                     long_value_boxed, float_value_boxed, double_value_boxed,
                     short_value_boxed, char_value_boxed, boolean_value_boxed,
                     int_array, long_array, float_array, double_array,
                     short_array, char_array, boolean_array, string);
};
FORY_STRUCT(Sample, int_value, long_value, float_value, double_value,
            short_value, char_value, boolean_value, int_value_boxed,
            long_value_boxed, float_value_boxed, double_value_boxed,
            short_value_boxed, char_value_boxed, boolean_value_boxed, int_array,
            long_array, float_array, double_array, short_array, char_array,
            boolean_array, string);
FORY_FIELD_TAGS(Sample, (int_value, 1), (long_value, 2), (float_value, 3),
                (double_value, 4), (short_value, 5), (char_value, 6),
                (boolean_value, 7), (int_value_boxed, 8), (long_value_boxed, 9),
                (float_value_boxed, 10), (double_value_boxed, 11),
                (short_value_boxed, 12), (char_value_boxed, 13),
                (boolean_value_boxed, 14), (int_array, 15), (long_array, 16),
                (float_array, 17), (double_array, 18), (short_array, 19),
                (char_array, 20), (boolean_array, 21), (string, 22));

// Enums for MediaContent benchmark
enum class Player : int32_t { JAVA = 0, FLASH = 1 };
MSGPACK_ADD_ENUM(Player);

enum class Size : int32_t { SMALL = 0, LARGE = 1 };
MSGPACK_ADD_ENUM(Size);

struct Media {
  std::string uri;
  std::string title; // Can be empty (null equivalent)
  int32_t width;
  int32_t height;
  std::string format;
  int64_t duration;
  int64_t size;
  int32_t bitrate;
  bool has_bitrate;
  std::vector<std::string> persons;
  Player player;
  std::string copyright;

  bool operator==(const Media &other) const {
    return uri == other.uri && title == other.title && width == other.width &&
           height == other.height && format == other.format &&
           duration == other.duration && size == other.size &&
           bitrate == other.bitrate && has_bitrate == other.has_bitrate &&
           persons == other.persons && player == other.player &&
           copyright == other.copyright;
  }
  MSGPACK_DEFINE_MAP(uri, title, width, height, format, duration, size, bitrate,
                     has_bitrate, persons, player, copyright);
};
FORY_STRUCT(Media, uri, title, width, height, format, duration, size, bitrate,
            has_bitrate, persons, player, copyright);
FORY_FIELD_TAGS(Media, (uri, 1), (title, 2), (width, 3), (height, 4),
                (format, 5), (duration, 6), (size, 7), (bitrate, 8),
                (has_bitrate, 9), (persons, 10), (player, 11), (copyright, 12));

struct Image {
  std::string uri;
  std::string title; // Can be empty (null equivalent)
  int32_t width;
  int32_t height;
  Size size;

  bool operator==(const Image &other) const {
    return uri == other.uri && title == other.title && width == other.width &&
           height == other.height && size == other.size;
  }
  MSGPACK_DEFINE_MAP(uri, title, width, height, size);
};
FORY_STRUCT(Image, uri, title, width, height, size);
FORY_FIELD_TAGS(Image, (uri, 1), (title, 2), (width, 3), (height, 4),
                (size, 5));

struct MediaContent {
  Media media;
  std::vector<Image> images;

  bool operator==(const MediaContent &other) const {
    return media == other.media && images == other.images;
  }
  MSGPACK_DEFINE_MAP(media, images);
};
FORY_STRUCT(MediaContent, media, images);
FORY_FIELD_TAGS(MediaContent, (media, 1), (images, 2));

struct StructList {
  std::vector<NumericStruct> struct_list;

  bool operator==(const StructList &other) const {
    return struct_list == other.struct_list;
  }
  MSGPACK_DEFINE_MAP(struct_list);
};
FORY_STRUCT(StructList, struct_list);
FORY_FIELD_TAGS(StructList, (struct_list, 1));

struct SampleList {
  std::vector<Sample> sample_list;

  bool operator==(const SampleList &other) const {
    return sample_list == other.sample_list;
  }
  MSGPACK_DEFINE_MAP(sample_list);
};
FORY_STRUCT(SampleList, sample_list);
FORY_FIELD_TAGS(SampleList, (sample_list, 1));

struct MediaContentList {
  std::vector<MediaContent> media_content_list;

  bool operator==(const MediaContentList &other) const {
    return media_content_list == other.media_content_list;
  }
  MSGPACK_DEFINE_MAP(media_content_list);
};
FORY_STRUCT(MediaContentList, media_content_list);
FORY_FIELD_TAGS(MediaContentList, (media_content_list, 1));

// ============================================================================
// Test data creation
// ============================================================================

NumericStruct create_numeric_struct() {
  // Use mixed positive/negative int32 values for realistic benchmark
  return NumericStruct{
      -12345,     // f1: negative
      987654321,  // f2: large positive
      -31415,     // f3: negative
      27182818,   // f4: positive
      -32000,     // f5: negative (near int16 min)
      1000000,    // f6: medium positive
      -999999999, // f7: large negative
      42          // f8: small positive
  };
}

constexpr int kListSize = 5;

// ============================================================================
// Protobuf conversion functions (like Java benchmark's
// buildPBStruct/fromPBObject)
// ============================================================================

/// Convert plain C++ struct to protobuf message (for serialization)
inline protobuf::Struct to_pb_struct(const NumericStruct &obj) {
  protobuf::Struct pb;
  pb.set_f1(obj.f1);
  pb.set_f2(obj.f2);
  pb.set_f3(obj.f3);
  pb.set_f4(obj.f4);
  pb.set_f5(obj.f5);
  pb.set_f6(obj.f6);
  pb.set_f7(obj.f7);
  pb.set_f8(obj.f8);
  return pb;
}

/// Convert protobuf message to plain C++ struct (for deserialization)
inline NumericStruct from_pb_struct(const protobuf::Struct &pb) {
  NumericStruct obj;
  obj.f1 = pb.f1();
  obj.f2 = pb.f2();
  obj.f3 = pb.f3();
  obj.f4 = pb.f4();
  obj.f5 = pb.f5();
  obj.f6 = pb.f6();
  obj.f7 = pb.f7();
  obj.f8 = pb.f8();
  return obj;
}

protobuf::Struct create_proto_struct() {
  return to_pb_struct(create_numeric_struct());
}

Sample create_sample() {
  // Consistent with Java Sample.populate() for fair cross-language comparison
  Sample sample;
  sample.int_value = 123;
  sample.long_value = 1230000LL;
  sample.float_value = 12.345f;
  sample.double_value = 1.234567;
  sample.short_value = 12345;
  sample.char_value = '!'; // 33
  sample.boolean_value = true;

  sample.int_value_boxed = 321;
  sample.long_value_boxed = 3210000LL;
  sample.float_value_boxed = 54.321f;
  sample.double_value_boxed = 7.654321;
  sample.short_value_boxed = 32100;
  sample.char_value_boxed = '$'; // 36
  sample.boolean_value_boxed = false;

  // Arrays with mixed positive/negative values (same as Java)
  sample.int_array = {-1234, -123, -12, -1, 0, 1, 12, 123, 1234};
  sample.long_array = {-123400, -12300, -1200, -100,  0,
                       100,     1200,   12300, 123400};
  sample.float_array = {-12.34f, -12.3f, -12.0f, -1.0f, 0.0f,
                        1.0f,    12.0f,  12.3f,  12.34f};
  sample.double_array = {-1.234, -1.23, -12.0, -1.0, 0.0,
                         1.0,    12.0,  1.23,  1.234};
  sample.short_array = {-1234, -123, -12, -1, 0, 1, 12, 123, 1234};
  sample.char_array = {'a', 's', 'd', 'f', 'A', 'S', 'D', 'F'}; // "asdfASDF"
  sample.boolean_array = {true, false, false, true};

  sample.string = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  return sample;
}

inline protobuf::Sample to_pb_sample(const Sample &obj) {
  protobuf::Sample sample;
  sample.set_int_value(obj.int_value);
  sample.set_long_value(obj.long_value);
  sample.set_float_value(obj.float_value);
  sample.set_double_value(obj.double_value);
  sample.set_short_value(obj.short_value);
  sample.set_char_value(obj.char_value);
  sample.set_boolean_value(obj.boolean_value);

  sample.set_int_value_boxed(obj.int_value_boxed);
  sample.set_long_value_boxed(obj.long_value_boxed);
  sample.set_float_value_boxed(obj.float_value_boxed);
  sample.set_double_value_boxed(obj.double_value_boxed);
  sample.set_short_value_boxed(obj.short_value_boxed);
  sample.set_char_value_boxed(obj.char_value_boxed);
  sample.set_boolean_value_boxed(obj.boolean_value_boxed);

  for (int32_t v : obj.int_array) {
    sample.add_int_array(v);
  }
  for (int64_t v : obj.long_array) {
    sample.add_long_array(v);
  }
  for (float v : obj.float_array) {
    sample.add_float_array(v);
  }
  for (double v : obj.double_array) {
    sample.add_double_array(v);
  }
  for (int32_t v : obj.short_array) {
    sample.add_short_array(v);
  }
  for (int32_t v : obj.char_array) {
    sample.add_char_array(v);
  }
  for (bool v : obj.boolean_array) {
    sample.add_boolean_array(v);
  }
  sample.set_string(obj.string);
  return sample;
}

inline Sample from_pb_sample(const protobuf::Sample &pb) {
  Sample sample;
  sample.int_value = pb.int_value();
  sample.long_value = pb.long_value();
  sample.float_value = pb.float_value();
  sample.double_value = pb.double_value();
  sample.short_value = pb.short_value();
  sample.char_value = pb.char_value();
  sample.boolean_value = pb.boolean_value();

  sample.int_value_boxed = pb.int_value_boxed();
  sample.long_value_boxed = pb.long_value_boxed();
  sample.float_value_boxed = pb.float_value_boxed();
  sample.double_value_boxed = pb.double_value_boxed();
  sample.short_value_boxed = pb.short_value_boxed();
  sample.char_value_boxed = pb.char_value_boxed();
  sample.boolean_value_boxed = pb.boolean_value_boxed();

  sample.int_array.assign(pb.int_array().begin(), pb.int_array().end());
  sample.long_array.assign(pb.long_array().begin(), pb.long_array().end());
  sample.float_array.assign(pb.float_array().begin(), pb.float_array().end());
  sample.double_array.assign(pb.double_array().begin(),
                             pb.double_array().end());
  sample.short_array.assign(pb.short_array().begin(), pb.short_array().end());
  sample.char_array.assign(pb.char_array().begin(), pb.char_array().end());
  sample.boolean_array.assign(pb.boolean_array().begin(),
                              pb.boolean_array().end());
  sample.string = pb.string();
  return sample;
}

protobuf::Sample create_proto_sample() {
  // Consistent with Java Sample.populate() for fair cross-language comparison
  return to_pb_sample(create_sample());
}

MediaContent create_media_content() {
  // Matches Java MediaContent.populate(false) - no circular reference
  MediaContent content;

  // Media fields matching Java populate()
  content.media.uri = "http://javaone.com/keynote.ogg";
  content.media.title = ""; // null in Java
  content.media.width = 641;
  content.media.height = 481;
  content.media.format = u8"video/theora\u1234"; // UTF-8 encoded unicode
  content.media.duration = 18000001;
  content.media.size = 58982401;
  content.media.bitrate = 0;
  content.media.has_bitrate = false;
  content.media.persons = {"Bill Gates, Jr.", "Steven Jobs"};
  content.media.player = Player::FLASH;
  content.media.copyright = "Copyright (c) 2009, Scooby Dooby Doo";

  // Images matching Java populate(false) - no circular reference
  content.images = {
      Image{"http://javaone.com/keynote_huge.jpg", u8"Javaone Keynote\u1234",
            32000, 24000, Size::LARGE},
      Image{"http://javaone.com/keynote_large.jpg", "", 1024, 768, Size::LARGE},
      Image{"http://javaone.com/keynote_small.jpg", "", 320, 240, Size::SMALL}};

  return content;
}

/// Convert Image to protobuf
inline protobuf::Image to_pb_image(const Image &img) {
  protobuf::Image pb;
  pb.set_uri(img.uri);
  if (!img.title.empty()) {
    pb.set_title(img.title);
  }
  pb.set_width(img.width);
  pb.set_height(img.height);
  pb.set_size(static_cast<protobuf::Size>(img.size));
  return pb;
}

/// Convert Media to protobuf
inline protobuf::Media to_pb_media(const Media &m) {
  protobuf::Media pb;
  pb.set_uri(m.uri);
  if (!m.title.empty()) {
    pb.set_title(m.title);
  }
  pb.set_width(m.width);
  pb.set_height(m.height);
  pb.set_format(m.format);
  pb.set_duration(m.duration);
  pb.set_size(m.size);
  pb.set_bitrate(m.bitrate);
  pb.set_has_bitrate(m.has_bitrate);
  for (const auto &person : m.persons) {
    pb.add_persons(person);
  }
  pb.set_player(static_cast<protobuf::Player>(m.player));
  pb.set_copyright(m.copyright);
  return pb;
}

/// Convert MediaContent to protobuf
inline protobuf::MediaContent to_pb_mediaContent(const MediaContent &mc) {
  protobuf::MediaContent pb;
  *pb.mutable_media() = to_pb_media(mc.media);
  for (const auto &img : mc.images) {
    *pb.add_images() = to_pb_image(img);
  }
  return pb;
}

/// Convert protobuf to Image
inline Image from_pb_image(const protobuf::Image &pb) {
  Image img;
  img.uri = pb.uri();
  img.title = pb.has_title() ? pb.title() : "";
  img.width = pb.width();
  img.height = pb.height();
  img.size = static_cast<Size>(pb.size());
  return img;
}

/// Convert protobuf to Media
inline Media from_pb_media(const protobuf::Media &pb) {
  Media m;
  m.uri = pb.uri();
  m.title = pb.has_title() ? pb.title() : "";
  m.width = pb.width();
  m.height = pb.height();
  m.format = pb.format();
  m.duration = pb.duration();
  m.size = pb.size();
  m.bitrate = pb.bitrate();
  m.has_bitrate = pb.has_bitrate();
  for (const auto &person : pb.persons()) {
    m.persons.push_back(person);
  }
  m.player = static_cast<Player>(pb.player());
  m.copyright = pb.copyright();
  return m;
}

/// Convert protobuf to MediaContent
inline MediaContent from_pb_mediaContent(const protobuf::MediaContent &pb) {
  MediaContent mc;
  mc.media = from_pb_media(pb.media());
  for (const auto &img : pb.images()) {
    mc.images.push_back(from_pb_image(img));
  }
  return mc;
}

protobuf::MediaContent create_proto_media_content() {
  return to_pb_mediaContent(create_media_content());
}

StructList create_struct_list() {
  StructList list;
  list.struct_list.reserve(kListSize);
  for (int i = 0; i < kListSize; ++i) {
    list.struct_list.push_back(create_numeric_struct());
  }
  return list;
}

SampleList create_sample_list() {
  SampleList list;
  list.sample_list.reserve(kListSize);
  for (int i = 0; i < kListSize; ++i) {
    list.sample_list.push_back(create_sample());
  }
  return list;
}

MediaContentList create_media_content_list() {
  MediaContentList list;
  list.media_content_list.reserve(kListSize);
  for (int i = 0; i < kListSize; ++i) {
    list.media_content_list.push_back(create_media_content());
  }
  return list;
}

inline protobuf::StructList to_pb_struct_list(const StructList &obj) {
  protobuf::StructList pb;
  for (const auto &item : obj.struct_list) {
    *pb.add_struct_list() = to_pb_struct(item);
  }
  return pb;
}

inline StructList from_pb_struct_list(const protobuf::StructList &pb) {
  StructList list;
  list.struct_list.reserve(pb.struct_list_size());
  for (const auto &item : pb.struct_list()) {
    list.struct_list.push_back(from_pb_struct(item));
  }
  return list;
}

inline protobuf::SampleList to_pb_sample_list(const SampleList &obj) {
  protobuf::SampleList pb;
  for (const auto &item : obj.sample_list) {
    *pb.add_sample_list() = to_pb_sample(item);
  }
  return pb;
}

inline SampleList from_pb_sample_list(const protobuf::SampleList &pb) {
  SampleList list;
  list.sample_list.reserve(pb.sample_list_size());
  for (const auto &item : pb.sample_list()) {
    list.sample_list.push_back(from_pb_sample(item));
  }
  return list;
}

inline protobuf::MediaContentList
to_pb_media_content_list(const MediaContentList &obj) {
  protobuf::MediaContentList pb;
  for (const auto &item : obj.media_content_list) {
    *pb.add_media_content_list() = to_pb_mediaContent(item);
  }
  return pb;
}

inline MediaContentList
from_pb_media_content_list(const protobuf::MediaContentList &pb) {
  MediaContentList list;
  list.media_content_list.reserve(pb.media_content_list_size());
  for (const auto &item : pb.media_content_list()) {
    list.media_content_list.push_back(from_pb_mediaContent(item));
  }
  return list;
}

protobuf::StructList create_proto_struct_list() {
  return to_pb_struct_list(create_struct_list());
}

protobuf::SampleList create_proto_sample_list() {
  return to_pb_sample_list(create_sample_list());
}

protobuf::MediaContentList create_proto_media_content_list() {
  return to_pb_media_content_list(create_media_content_list());
}

// ============================================================================
// Helper to configure Fory instance
// ============================================================================

void register_fory_types(fory::serialization::Fory &fory) {
  fory.register_struct<NumericStruct>(1);
  fory.register_struct<Sample>(2);
  fory.register_struct<Media>(3);
  fory.register_struct<Image>(4);
  fory.register_struct<MediaContent>(5);
  fory.register_struct<StructList>(6);
  fory.register_struct<SampleList>(7);
  fory.register_struct<MediaContentList>(8);
}

template <typename T, typename Factory>
void run_msgpack_serialize_benchmark(benchmark::State &state, Factory factory) {
  T obj = factory();
  msgpack::sbuffer output;

  for (auto _ : state) {
    output.clear();
    msgpack::pack(output, obj);
    benchmark::DoNotOptimize(output.data());
    benchmark::DoNotOptimize(output.size());
  }
}

template <typename T, typename Factory>
void run_msgpack_deserialize_benchmark(benchmark::State &state,
                                       Factory factory) {
  T obj = factory();
  msgpack::sbuffer output;
  msgpack::pack(output, obj);

  for (auto _ : state) {
    msgpack::object_handle handle =
        msgpack::unpack(output.data(), output.size());
    T result;
    handle.get().convert(result);
    benchmark::DoNotOptimize(result);
  }
}

#define DEFINE_MSGPACK_BENCHMARKS(name, type, create_fn)                       \
  static void BM_Msgpack_##name##_Serialize(benchmark::State &state) {         \
    run_msgpack_serialize_benchmark<type>(state, create_fn);                   \
  }                                                                            \
  BENCHMARK(BM_Msgpack_##name##_Serialize);                                    \
  static void BM_Msgpack_##name##_Deserialize(benchmark::State &state) {       \
    run_msgpack_deserialize_benchmark<type>(state, create_fn);                 \
  }                                                                            \
  BENCHMARK(BM_Msgpack_##name##_Deserialize)

DEFINE_MSGPACK_BENCHMARKS(Struct, NumericStruct, create_numeric_struct);
DEFINE_MSGPACK_BENCHMARKS(Sample, Sample, create_sample);
DEFINE_MSGPACK_BENCHMARKS(MediaContent, MediaContent, create_media_content);
DEFINE_MSGPACK_BENCHMARKS(StructList, StructList, create_struct_list);
DEFINE_MSGPACK_BENCHMARKS(SampleList, SampleList, create_sample_list);
DEFINE_MSGPACK_BENCHMARKS(MediaContentList, MediaContentList,
                          create_media_content_list);

#undef DEFINE_MSGPACK_BENCHMARKS

// ============================================================================
// Struct benchmarks (simple object with 8 int32 fields)
// ============================================================================

static void BM_Fory_Struct_Serialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  NumericStruct obj = create_numeric_struct();

  // Reuse internal buffer
  fory::Buffer buffer;
  buffer.reserve(64);

  for (auto _ : state) {
    buffer.writer_index(0);
    fory.serialize_to(buffer, obj);
    benchmark::DoNotOptimize(buffer.data());
  }
}
BENCHMARK(BM_Fory_Struct_Serialize);

// Fair comparison: convert plain C++ struct to protobuf, then serialize
// (Same pattern as Java benchmark's buildPBStruct().toByteArray())
static void BM_Protobuf_Struct_Serialize(benchmark::State &state) {
  NumericStruct obj = create_numeric_struct();
  protobuf::Struct pb = to_pb_struct(obj);
  std::vector<uint8_t> output;
  output.resize(pb.ByteSizeLong());

  for (auto _ : state) {
    pb = to_pb_struct(obj);
    pb.SerializeToArray(output.data(), static_cast<int>(output.size()));
    benchmark::DoNotOptimize(output);
  }
}
BENCHMARK(BM_Protobuf_Struct_Serialize);

static void BM_Fory_Struct_Deserialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  NumericStruct obj = create_numeric_struct();
  auto serialized = fory.serialize(obj);
  if (!serialized.ok()) {
    state.SkipWithError("Serialization failed");
    return;
  }
  auto &bytes = serialized.value();

  // Verify deserialization works first
  auto test_result =
      fory.deserialize<NumericStruct>(bytes.data(), bytes.size());
  if (!test_result.ok()) {
    state.SkipWithError("Deserialization test failed");
    return;
  }

  for (auto _ : state) {
    auto result = fory.deserialize<NumericStruct>(bytes.data(), bytes.size());
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Fory_Struct_Deserialize);

// Fair comparison: deserialize and convert protobuf to plain C++ struct
// (Same pattern as Java benchmark's fromPBObject())
static void BM_Protobuf_Struct_Deserialize(benchmark::State &state) {
  protobuf::Struct obj = create_proto_struct();
  std::string serialized;
  obj.SerializeToString(&serialized);

  for (auto _ : state) {
    protobuf::Struct pb_result;
    pb_result.ParseFromString(serialized);
    NumericStruct result = from_pb_struct(pb_result);
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Protobuf_Struct_Deserialize);

// ============================================================================
// Sample benchmarks (complex object with various types and arrays)
// ============================================================================

static void BM_Fory_Sample_Serialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  Sample obj = create_sample();

  // Pre-allocate buffer (like Protobuf benchmark does)
  fory::Buffer buffer;
  buffer.reserve(4096);

  for (auto _ : state) {
    buffer.writer_index(0);
    auto result = fory.serialize_to(buffer, obj);
    benchmark::DoNotOptimize(result);
    benchmark::DoNotOptimize(buffer.data());
  }
}
BENCHMARK(BM_Fory_Sample_Serialize);

static void BM_Protobuf_Sample_Serialize(benchmark::State &state) {
  protobuf::Sample obj = create_proto_sample();
  std::vector<uint8_t> output;
  output.resize(obj.ByteSizeLong());

  for (auto _ : state) {
    obj.SerializeToArray(output.data(), static_cast<int>(output.size()));
    benchmark::DoNotOptimize(output);
  }
}
BENCHMARK(BM_Protobuf_Sample_Serialize);

static void BM_Fory_Sample_Deserialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  Sample obj = create_sample();
  auto serialized = fory.serialize(obj);
  if (!serialized.ok()) {
    state.SkipWithError("Serialization failed");
    return;
  }
  auto &bytes = serialized.value();

  // Verify deserialization works first
  auto test_result = fory.deserialize<Sample>(bytes.data(), bytes.size());
  if (!test_result.ok()) {
    state.SkipWithError("Deserialization test failed");
    return;
  }

  for (auto _ : state) {
    auto result = fory.deserialize<Sample>(bytes.data(), bytes.size());
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Fory_Sample_Deserialize);

static void BM_Protobuf_Sample_Deserialize(benchmark::State &state) {
  protobuf::Sample obj = create_proto_sample();
  std::string serialized;
  obj.SerializeToString(&serialized);

  for (auto _ : state) {
    protobuf::Sample result;
    result.ParseFromString(serialized);
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Protobuf_Sample_Deserialize);

// ============================================================================
// MediaContent benchmarks (nested objects with strings and lists)
// ============================================================================

static void BM_Fory_MediaContent_Serialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  MediaContent obj = create_media_content();

  // Pre-allocate buffer
  fory::Buffer buffer;
  buffer.reserve(4096);

  for (auto _ : state) {
    buffer.writer_index(0);
    auto result = fory.serialize_to(buffer, obj);
    benchmark::DoNotOptimize(result);
    benchmark::DoNotOptimize(buffer.data());
  }
}
BENCHMARK(BM_Fory_MediaContent_Serialize);

static void BM_Protobuf_MediaContent_Serialize(benchmark::State &state) {
  MediaContent obj = create_media_content();
  protobuf::MediaContent pb = to_pb_mediaContent(obj);
  std::vector<uint8_t> output;
  output.resize(pb.ByteSizeLong());

  for (auto _ : state) {
    pb = to_pb_mediaContent(obj);
    pb.SerializeToArray(output.data(), static_cast<int>(output.size()));
    benchmark::DoNotOptimize(output);
  }
}
BENCHMARK(BM_Protobuf_MediaContent_Serialize);

static void BM_Fory_MediaContent_Deserialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  MediaContent obj = create_media_content();
  auto serialized = fory.serialize(obj);
  if (!serialized.ok()) {
    state.SkipWithError("Serialization failed");
    return;
  }
  auto &bytes = serialized.value();

  // Verify deserialization works first
  auto test_result = fory.deserialize<MediaContent>(bytes.data(), bytes.size());
  if (!test_result.ok()) {
    state.SkipWithError("Deserialization test failed");
    return;
  }

  for (auto _ : state) {
    auto result = fory.deserialize<MediaContent>(bytes.data(), bytes.size());
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Fory_MediaContent_Deserialize);

static void BM_Protobuf_MediaContent_Deserialize(benchmark::State &state) {
  protobuf::MediaContent obj = create_proto_media_content();
  std::string serialized;
  obj.SerializeToString(&serialized);

  for (auto _ : state) {
    protobuf::MediaContent pb_result;
    pb_result.ParseFromString(serialized);
    MediaContent result = from_pb_mediaContent(pb_result);
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Protobuf_MediaContent_Deserialize);

// ============================================================================
// List benchmarks (StructList, SampleList, MediaContentList)
// ============================================================================

static void BM_Fory_StructList_Serialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  StructList obj = create_struct_list();

  fory::Buffer buffer;
  buffer.reserve(65536);

  for (auto _ : state) {
    buffer.writer_index(0);
    auto result = fory.serialize_to(buffer, obj);
    benchmark::DoNotOptimize(result);
    benchmark::DoNotOptimize(buffer.data());
  }
}
BENCHMARK(BM_Fory_StructList_Serialize);

static void BM_Protobuf_StructList_Serialize(benchmark::State &state) {
  StructList obj = create_struct_list();
  protobuf::StructList pb = to_pb_struct_list(obj);
  std::vector<uint8_t> output;
  output.resize(pb.ByteSizeLong());

  for (auto _ : state) {
    pb = to_pb_struct_list(obj);
    pb.SerializeToArray(output.data(), static_cast<int>(output.size()));
    benchmark::DoNotOptimize(output);
  }
}
BENCHMARK(BM_Protobuf_StructList_Serialize);

static void BM_Fory_StructList_Deserialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  StructList obj = create_struct_list();
  auto serialized = fory.serialize(obj);
  if (!serialized.ok()) {
    state.SkipWithError("Serialization failed");
    return;
  }
  auto &bytes = serialized.value();

  auto test_result = fory.deserialize<StructList>(bytes.data(), bytes.size());
  if (!test_result.ok()) {
    state.SkipWithError("Deserialization test failed");
    return;
  }

  for (auto _ : state) {
    auto result = fory.deserialize<StructList>(bytes.data(), bytes.size());
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Fory_StructList_Deserialize);

static void BM_Protobuf_StructList_Deserialize(benchmark::State &state) {
  protobuf::StructList obj = create_proto_struct_list();
  std::string serialized;
  obj.SerializeToString(&serialized);

  for (auto _ : state) {
    protobuf::StructList pb_result;
    pb_result.ParseFromString(serialized);
    StructList result = from_pb_struct_list(pb_result);
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Protobuf_StructList_Deserialize);

static void BM_Fory_SampleList_Serialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  SampleList obj = create_sample_list();

  fory::Buffer buffer;
  buffer.reserve(131072);

  for (auto _ : state) {
    buffer.writer_index(0);
    auto result = fory.serialize_to(buffer, obj);
    benchmark::DoNotOptimize(result);
    benchmark::DoNotOptimize(buffer.data());
  }
}
BENCHMARK(BM_Fory_SampleList_Serialize);

static void BM_Protobuf_SampleList_Serialize(benchmark::State &state) {
  SampleList obj = create_sample_list();
  protobuf::SampleList pb = to_pb_sample_list(obj);
  std::vector<uint8_t> output;
  output.resize(pb.ByteSizeLong());

  for (auto _ : state) {
    pb = to_pb_sample_list(obj);
    pb.SerializeToArray(output.data(), static_cast<int>(output.size()));
    benchmark::DoNotOptimize(output);
  }
}
BENCHMARK(BM_Protobuf_SampleList_Serialize);

static void BM_Fory_SampleList_Deserialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  SampleList obj = create_sample_list();
  auto serialized = fory.serialize(obj);
  if (!serialized.ok()) {
    state.SkipWithError("Serialization failed");
    return;
  }
  auto &bytes = serialized.value();

  auto test_result = fory.deserialize<SampleList>(bytes.data(), bytes.size());
  if (!test_result.ok()) {
    state.SkipWithError("Deserialization test failed");
    return;
  }

  for (auto _ : state) {
    auto result = fory.deserialize<SampleList>(bytes.data(), bytes.size());
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Fory_SampleList_Deserialize);

static void BM_Protobuf_SampleList_Deserialize(benchmark::State &state) {
  protobuf::SampleList obj = create_proto_sample_list();
  std::string serialized;
  obj.SerializeToString(&serialized);

  for (auto _ : state) {
    protobuf::SampleList pb_result;
    pb_result.ParseFromString(serialized);
    SampleList result = from_pb_sample_list(pb_result);
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Protobuf_SampleList_Deserialize);

static void BM_Fory_MediaContentList_Serialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  MediaContentList obj = create_media_content_list();

  fory::Buffer buffer;
  buffer.reserve(131072);

  for (auto _ : state) {
    buffer.writer_index(0);
    auto result = fory.serialize_to(buffer, obj);
    benchmark::DoNotOptimize(result);
    benchmark::DoNotOptimize(buffer.data());
  }
}
BENCHMARK(BM_Fory_MediaContentList_Serialize);

static void BM_Protobuf_MediaContentList_Serialize(benchmark::State &state) {
  MediaContentList obj = create_media_content_list();
  protobuf::MediaContentList pb = to_pb_media_content_list(obj);
  std::vector<uint8_t> output;
  output.resize(pb.ByteSizeLong());

  for (auto _ : state) {
    pb = to_pb_media_content_list(obj);
    pb.SerializeToArray(output.data(), static_cast<int>(output.size()));
    benchmark::DoNotOptimize(output);
  }
}
BENCHMARK(BM_Protobuf_MediaContentList_Serialize);

static void BM_Fory_MediaContentList_Deserialize(benchmark::State &state) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);
  MediaContentList obj = create_media_content_list();
  auto serialized = fory.serialize(obj);
  if (!serialized.ok()) {
    state.SkipWithError("Serialization failed");
    return;
  }
  auto &bytes = serialized.value();

  auto test_result =
      fory.deserialize<MediaContentList>(bytes.data(), bytes.size());
  if (!test_result.ok()) {
    state.SkipWithError("Deserialization test failed");
    return;
  }

  for (auto _ : state) {
    auto result =
        fory.deserialize<MediaContentList>(bytes.data(), bytes.size());
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Fory_MediaContentList_Deserialize);

static void BM_Protobuf_MediaContentList_Deserialize(benchmark::State &state) {
  protobuf::MediaContentList obj = create_proto_media_content_list();
  std::string serialized;
  obj.SerializeToString(&serialized);

  for (auto _ : state) {
    protobuf::MediaContentList pb_result;
    pb_result.ParseFromString(serialized);
    MediaContentList result = from_pb_media_content_list(pb_result);
    benchmark::DoNotOptimize(result);
  }
}
BENCHMARK(BM_Protobuf_MediaContentList_Deserialize);

// ============================================================================
// Serialized size comparison (printed once at the end)
// ============================================================================

static void BM_PrintSerializedSizes(benchmark::State &state) {
  // Fory
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(true)
                  .track_ref(false)
                  .build();
  register_fory_types(fory);

  NumericStruct fory_struct = create_numeric_struct();
  Sample fory_sample = create_sample();
  MediaContent fory_media = create_media_content();
  StructList fory_struct_list = create_struct_list();
  SampleList fory_sample_list = create_sample_list();
  MediaContentList fory_media_list = create_media_content_list();

  auto fory_struct_bytes = fory.serialize(fory_struct).value();
  auto fory_sample_bytes = fory.serialize(fory_sample).value();
  auto fory_media_bytes = fory.serialize(fory_media).value();
  auto fory_struct_list_bytes = fory.serialize(fory_struct_list).value();
  auto fory_sample_list_bytes = fory.serialize(fory_sample_list).value();
  auto fory_media_list_bytes = fory.serialize(fory_media_list).value();

  // Protobuf
  protobuf::Struct proto_struct = create_proto_struct();
  protobuf::Sample proto_sample = create_proto_sample();
  protobuf::MediaContent proto_media = create_proto_media_content();
  protobuf::StructList proto_struct_list = create_proto_struct_list();
  protobuf::SampleList proto_sample_list = create_proto_sample_list();
  protobuf::MediaContentList proto_media_list =
      create_proto_media_content_list();
  std::string proto_struct_bytes, proto_sample_bytes, proto_media_bytes,
      proto_struct_list_bytes, proto_sample_list_bytes, proto_media_list_bytes;
  proto_struct.SerializeToString(&proto_struct_bytes);
  proto_sample.SerializeToString(&proto_sample_bytes);
  proto_media.SerializeToString(&proto_media_bytes);
  proto_struct_list.SerializeToString(&proto_struct_list_bytes);
  proto_sample_list.SerializeToString(&proto_sample_list_bytes);
  proto_media_list.SerializeToString(&proto_media_list_bytes);

  auto msgpack_size = [](const auto &obj) -> size_t {
    msgpack::sbuffer output;
    msgpack::pack(output, obj);
    return output.size();
  };

  auto msgpack_struct_size = msgpack_size(fory_struct);
  auto msgpack_sample_size = msgpack_size(fory_sample);
  auto msgpack_media_size = msgpack_size(fory_media);
  auto msgpack_struct_list_size = msgpack_size(fory_struct_list);
  auto msgpack_sample_list_size = msgpack_size(fory_sample_list);
  auto msgpack_media_list_size = msgpack_size(fory_media_list);

  for (auto _ : state) {
    // Just run once to print sizes
  }

  state.counters["fory_struct_size"] = fory_struct_bytes.size();
  state.counters["protobuf_struct_size"] = proto_struct_bytes.size();
  state.counters["msgpack_struct_size"] = msgpack_struct_size;

  state.counters["fory_sample_size"] = fory_sample_bytes.size();
  state.counters["protobuf_sample_size"] = proto_sample_bytes.size();
  state.counters["msgpack_sample_size"] = msgpack_sample_size;

  state.counters["fory_media_size"] = fory_media_bytes.size();
  state.counters["protobuf_media_size"] = proto_media_bytes.size();
  state.counters["msgpack_media_size"] = msgpack_media_size;

  state.counters["fory_struct_list_size"] = fory_struct_list_bytes.size();
  state.counters["protobuf_struct_list_size"] = proto_struct_list_bytes.size();
  state.counters["msgpack_struct_list_size"] = msgpack_struct_list_size;

  state.counters["fory_sample_list_size"] = fory_sample_list_bytes.size();
  state.counters["protobuf_sample_list_size"] = proto_sample_list_bytes.size();
  state.counters["msgpack_sample_list_size"] = msgpack_sample_list_size;

  state.counters["fory_media_list_size"] = fory_media_list_bytes.size();
  state.counters["protobuf_media_list_size"] = proto_media_list_bytes.size();
  state.counters["msgpack_media_list_size"] = msgpack_media_list_size;
}
BENCHMARK(BM_PrintSerializedSizes)->Iterations(1);
