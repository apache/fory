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

#include "fory/serialization/fory.h"
#include "fory/serialization/temporal_serializers.h"
#include "fory/thirdparty/MurmurHash3.h"

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <stdexcept>
#include <string>
#include <tuple>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

using ::fory::Buffer;
using ::fory::Error;
using ::fory::Result;
using ::fory::serialization::Fory;
using ::fory::serialization::ForyBuilder;
using ::fory::serialization::LocalDate;
using ::fory::serialization::Serializer;
using ::fory::serialization::Timestamp;

[[noreturn]] void Fail(const std::string &message) {
  throw std::runtime_error(message);
}

std::string GetDataFilePath() {
  const char *env = std::getenv("DATA_FILE");
  if (env == nullptr) {
    Fail("DATA_FILE environment variable is not set");
  }
  return std::string(env);
}

thread_local std::string g_current_case;

void MaybeDumpInput(const std::vector<uint8_t> &data) {
  const char *dump_env = std::getenv("FORY_CPP_DUMP_CASE");
  if (dump_env == nullptr || g_current_case != dump_env) {
    return;
  }
  const char *dir = std::getenv("FORY_CPP_DUMP_DIR");
  std::string dump_dir = dir ? std::string(dir) : std::string("/tmp");
  std::string out_path = dump_dir + "/cpp_case_" + g_current_case + ".bin";
  std::ofstream output(out_path, std::ios::binary | std::ios::trunc);
  if (!output) {
    std::cerr << "Failed to dump input to " << out_path << std::endl;
    return;
  }
  output.write(reinterpret_cast<const char *>(data.data()),
               static_cast<std::streamsize>(data.size()));
  std::cerr << "Dumped input for case " << g_current_case << " to " << out_path
            << std::endl;
}

std::vector<uint8_t> ReadFile(const std::string &path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    Fail("Failed to open file for reading: " + path);
  }
  std::vector<uint8_t> data((std::istreambuf_iterator<char>(input)),
                            std::istreambuf_iterator<char>());
  MaybeDumpInput(data);
  return data;
}

void WriteFile(const std::string &path, const std::vector<uint8_t> &data) {
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  if (!output) {
    Fail("Failed to open file for writing: " + path);
  }
  output.write(reinterpret_cast<const char *>(data.data()),
               static_cast<std::streamsize>(data.size()));
  output.flush();
  output.close();
}

// ---------------------------------------------------------------------------
// xlang model definitions shared with Java/Rust tests
// ---------------------------------------------------------------------------

enum class Color : int32_t { Green = 0, Red = 1, Blue = 2, White = 3 };
FORY_ENUM(Color, Green, Red, Blue, White);

struct Item {
  std::string name;
  bool operator==(const Item &other) const { return name == other.name; }
};
FORY_STRUCT(Item, name);

struct SimpleStruct {
  std::map<int32_t, double> f1;
  int32_t f2;
  Item f3;
  std::string f4;
  Color f5;
  std::vector<std::string> f6;
  int32_t f7;
  int32_t f8;
  int32_t last;
  bool operator==(const SimpleStruct &other) const {
    return f1 == other.f1 && f2 == other.f2 && f3 == other.f3 &&
           f4 == other.f4 && f5 == other.f5 && f6 == other.f6 &&
           f7 == other.f7 && f8 == other.f8 && last == other.last;
  }
};
FORY_STRUCT(SimpleStruct, f1, f2, f3, f4, f5, f6, f7, f8, last);

// Integer struct used for cross-language boxed integer tests.
// Java xlang mode: all fields are non-nullable by default.
struct Item1 {
  int32_t f1;
  int32_t f2;
  int32_t f3;
  int32_t f4;
  int32_t f5;
  int32_t f6;
  bool operator==(const Item1 &other) const {
    return f1 == other.f1 && f2 == other.f2 && f3 == other.f3 &&
           f4 == other.f4 && f5 == other.f5 && f6 == other.f6;
  }
};
FORY_STRUCT(Item1, f1, f2, f3, f4, f5, f6);

struct MyStruct {
  int32_t id;
  MyStruct() = default;
  explicit MyStruct(int32_t v) : id(v) {}
  bool operator==(const MyStruct &other) const { return id == other.id; }
};
FORY_STRUCT(MyStruct, id);

struct MyExt {
  int32_t id;
  bool operator==(const MyExt &other) const { return id == other.id; }
};

// MyExt is modeled as an ext type (custom serializer) rather than a
// struct to mirror Rust's MyExt and Java's MyExtSerializer in
// XlangTestBase. Do not register it with FORY_STRUCT; instead we
// provide a Serializer<MyExt> specialization and register it via
// register_extension_type.

struct MyWrapper {
  Color color;
  MyStruct my_struct;
  MyExt my_ext;
  bool operator==(const MyWrapper &other) const {
    return color == other.color && my_struct == other.my_struct &&
           my_ext == other.my_ext;
  }
};
FORY_STRUCT(MyWrapper, color, my_struct, my_ext);

struct EmptyWrapper {
  bool placeholder = false;
  bool operator==(const EmptyWrapper &other) const {
    return placeholder == other.placeholder;
  }
};
FORY_STRUCT(EmptyWrapper, placeholder);

struct VersionCheckStruct {
  int32_t f1;
  std::optional<std::string> f2;
  double f3;
  bool operator==(const VersionCheckStruct &other) const {
    return f1 == other.f1 && f2 == other.f2 && f3 == other.f3;
  }
};
FORY_STRUCT(VersionCheckStruct, f1, f2, f3);

struct StructWithList {
  std::vector<std::string> items;
  bool operator==(const StructWithList &other) const {
    return items == other.items;
  }
};
FORY_STRUCT(StructWithList, items);

struct StructWithMap {
  std::map<std::string, std::string> data;
  bool operator==(const StructWithMap &other) const {
    return data == other.data;
  }
};
FORY_STRUCT(StructWithMap, data);

// ============================================================================
// Polymorphic Container Test Types - Using virtual base class
// ============================================================================

struct Animal {
  virtual ~Animal() = default;
  virtual std::string speak() const = 0;
  int32_t age = 0;
};
FORY_STRUCT(Animal, age);

struct Dog : Animal {
  std::string speak() const override { return "Woof"; }
  std::optional<std::string> name;
};
FORY_STRUCT(Dog, age, name);

struct Cat : Animal {
  std::string speak() const override { return "Meow"; }
  int32_t lives = 9;
};
FORY_STRUCT(Cat, age, lives);

struct AnimalListHolder {
  std::vector<std::shared_ptr<Animal>> animals;
};
FORY_STRUCT(AnimalListHolder, animals);

struct AnimalMapHolder {
  std::map<std::string, std::shared_ptr<Animal>> animal_map;
};
FORY_STRUCT(AnimalMapHolder, animal_map);

// ============================================================================
// Schema Evolution Test Types
// ============================================================================

struct EmptyStructEvolution {
  bool placeholder = false; // C++ templates require at least one field
  bool operator==(const EmptyStructEvolution &other) const {
    return placeholder == other.placeholder;
  }
};
FORY_STRUCT(EmptyStructEvolution, placeholder);

struct OneStringFieldStruct {
  std::optional<std::string> f1;
  bool operator==(const OneStringFieldStruct &other) const {
    return f1 == other.f1;
  }
};
FORY_STRUCT(OneStringFieldStruct, f1);

struct TwoStringFieldStruct {
  std::string f1;
  std::string f2;
  bool operator==(const TwoStringFieldStruct &other) const {
    return f1 == other.f1 && f2 == other.f2;
  }
};
FORY_STRUCT(TwoStringFieldStruct, f1, f2);

enum class TestEnum : int32_t { VALUE_A = 0, VALUE_B = 1, VALUE_C = 2 };
FORY_ENUM(TestEnum, VALUE_A, VALUE_B, VALUE_C);

struct OneEnumFieldStruct {
  TestEnum f1;
  bool operator==(const OneEnumFieldStruct &other) const {
    return f1 == other.f1;
  }
};
FORY_STRUCT(OneEnumFieldStruct, f1);

struct TwoEnumFieldStruct {
  TestEnum f1;
  TestEnum f2;
  bool operator==(const TwoEnumFieldStruct &other) const {
    return f1 == other.f1 && f2 == other.f2;
  }
};
FORY_STRUCT(TwoEnumFieldStruct, f1, f2);

// ============================================================================
// Nullable Field Test Types - Comprehensive versions matching Java structs
// ============================================================================

// NullableComprehensiveSchemaConsistent (type id 401)
// Matches Java's NullableComprehensiveSchemaConsistent for SCHEMA_CONSISTENT
// mode
struct NullableComprehensiveSchemaConsistent {
  // Base non-nullable primitive fields
  int8_t byte_field;
  int16_t short_field;
  int32_t int_field;
  int64_t long_field;
  float float_field;
  double double_field;
  bool bool_field;

  // Base non-nullable reference fields
  std::string string_field;
  std::vector<std::string> list_field;
  std::set<std::string> set_field;
  std::map<std::string, std::string> map_field;

  // Nullable fields - first half using boxed types (std::optional)
  std::optional<int32_t> nullable_int;
  std::optional<int64_t> nullable_long;
  std::optional<float> nullable_float;

  // Nullable fields - second half using @ForyField annotation
  std::optional<double> nullable_double;
  std::optional<bool> nullable_bool;
  std::optional<std::string> nullable_string;
  std::optional<std::vector<std::string>> nullable_list;
  std::optional<std::set<std::string>> nullable_set;
  std::optional<std::map<std::string, std::string>> nullable_map;

  bool operator==(const NullableComprehensiveSchemaConsistent &other) const {
    return byte_field == other.byte_field && short_field == other.short_field &&
           int_field == other.int_field && long_field == other.long_field &&
           std::abs(float_field - other.float_field) < 1e-6 &&
           std::abs(double_field - other.double_field) < 1e-9 &&
           bool_field == other.bool_field &&
           string_field == other.string_field &&
           list_field == other.list_field && set_field == other.set_field &&
           map_field == other.map_field && nullable_int == other.nullable_int &&
           nullable_long == other.nullable_long &&
           compare_optional_float(nullable_float, other.nullable_float) &&
           compare_optional_double(nullable_double, other.nullable_double) &&
           nullable_bool == other.nullable_bool &&
           nullable_string == other.nullable_string &&
           nullable_list == other.nullable_list &&
           nullable_set == other.nullable_set &&
           nullable_map == other.nullable_map;
  }

private:
  static bool compare_optional_float(const std::optional<float> &a,
                                     const std::optional<float> &b) {
    if (a.has_value() != b.has_value())
      return false;
    if (!a.has_value())
      return true;
    return std::abs(*a - *b) < 1e-6;
  }

  static bool compare_optional_double(const std::optional<double> &a,
                                      const std::optional<double> &b) {
    if (a.has_value() != b.has_value())
      return false;
    if (!a.has_value())
      return true;
    return std::abs(*a - *b) < 1e-9;
  }
};
FORY_STRUCT(NullableComprehensiveSchemaConsistent, byte_field, short_field,
            int_field, long_field, float_field, double_field, bool_field,
            string_field, list_field, set_field, map_field, nullable_int,
            nullable_long, nullable_float, nullable_double, nullable_bool,
            nullable_string, nullable_list, nullable_set, nullable_map);

// NullableComprehensiveCompatible (type id 402)
// Matches Java's NullableComprehensiveCompatible for COMPATIBLE mode
struct NullableComprehensiveCompatible {
  // Base non-nullable primitive fields
  int8_t byte_field;
  int16_t short_field;
  int32_t int_field;
  int64_t long_field;
  float float_field;
  double double_field;
  bool bool_field;

  // Base non-nullable boxed fields (not nullable by default in xlang)
  int32_t boxed_int;
  int64_t boxed_long;
  float boxed_float;
  double boxed_double;
  bool boxed_bool;

  // Base non-nullable reference fields
  std::string string_field;
  std::vector<std::string> list_field;
  std::set<std::string> set_field;
  std::map<std::string, std::string> map_field;

  // Nullable group 1 - boxed types with @ForyField(nullable=true)
  std::optional<int32_t> nullable_int1;
  std::optional<int64_t> nullable_long1;
  std::optional<float> nullable_float1;
  std::optional<double> nullable_double1;
  std::optional<bool> nullable_bool1;

  // Nullable group 2 - reference types with @ForyField(nullable=true)
  std::optional<std::string> nullable_string2;
  std::optional<std::vector<std::string>> nullable_list2;
  std::optional<std::set<std::string>> nullable_set2;
  std::optional<std::map<std::string, std::string>> nullable_map2;

  bool operator==(const NullableComprehensiveCompatible &other) const {
    return byte_field == other.byte_field && short_field == other.short_field &&
           int_field == other.int_field && long_field == other.long_field &&
           std::abs(float_field - other.float_field) < 1e-6 &&
           std::abs(double_field - other.double_field) < 1e-9 &&
           bool_field == other.bool_field && boxed_int == other.boxed_int &&
           boxed_long == other.boxed_long &&
           std::abs(boxed_float - other.boxed_float) < 1e-6 &&
           std::abs(boxed_double - other.boxed_double) < 1e-9 &&
           boxed_bool == other.boxed_bool &&
           string_field == other.string_field &&
           list_field == other.list_field && set_field == other.set_field &&
           map_field == other.map_field &&
           nullable_int1 == other.nullable_int1 &&
           nullable_long1 == other.nullable_long1 &&
           compare_optional_float(nullable_float1, other.nullable_float1) &&
           compare_optional_double(nullable_double1, other.nullable_double1) &&
           nullable_bool1 == other.nullable_bool1 &&
           nullable_string2 == other.nullable_string2 &&
           nullable_list2 == other.nullable_list2 &&
           nullable_set2 == other.nullable_set2 &&
           nullable_map2 == other.nullable_map2;
  }

private:
  static bool compare_optional_float(const std::optional<float> &a,
                                     const std::optional<float> &b) {
    if (a.has_value() != b.has_value())
      return false;
    if (!a.has_value())
      return true;
    return std::abs(*a - *b) < 1e-6;
  }

  static bool compare_optional_double(const std::optional<double> &a,
                                      const std::optional<double> &b) {
    if (a.has_value() != b.has_value())
      return false;
    if (!a.has_value())
      return true;
    return std::abs(*a - *b) < 1e-9;
  }
};
FORY_STRUCT(NullableComprehensiveCompatible, byte_field, short_field, int_field,
            long_field, float_field, double_field, bool_field, boxed_int,
            boxed_long, boxed_float, boxed_double, boxed_bool, string_field,
            list_field, set_field, map_field, nullable_int1, nullable_long1,
            nullable_float1, nullable_double1, nullable_bool1, nullable_string2,
            nullable_list2, nullable_set2, nullable_map2);

// ============================================================================
// Reference Tracking Test Types - Cross-language shared reference tests
// ============================================================================

// Inner struct for reference tracking test (SCHEMA_CONSISTENT mode)
// Matches Java RefInnerSchemaConsistent with type ID 501
struct RefInnerSchemaConsistent {
  int32_t id;
  std::string name;
  bool operator==(const RefInnerSchemaConsistent &other) const {
    return id == other.id && name == other.name;
  }
  bool operator!=(const RefInnerSchemaConsistent &other) const {
    return !(*this == other);
  }
};
FORY_STRUCT(RefInnerSchemaConsistent, id, name);

// Outer struct for reference tracking test (SCHEMA_CONSISTENT mode)
// Contains two fields that both point to the same inner object.
// Matches Java RefOuterSchemaConsistent with type ID 502
// Uses std::shared_ptr for reference tracking to share the same object
struct RefOuterSchemaConsistent {
  std::shared_ptr<RefInnerSchemaConsistent> inner1;
  std::shared_ptr<RefInnerSchemaConsistent> inner2;
  bool operator==(const RefOuterSchemaConsistent &other) const {
    bool inner1_eq = (inner1 == nullptr && other.inner1 == nullptr) ||
                     (inner1 != nullptr && other.inner1 != nullptr &&
                      *inner1 == *other.inner1);
    bool inner2_eq = (inner2 == nullptr && other.inner2 == nullptr) ||
                     (inner2 != nullptr && other.inner2 != nullptr &&
                      *inner2 == *other.inner2);
    return inner1_eq && inner2_eq;
  }
};
FORY_STRUCT(RefOuterSchemaConsistent, inner1, inner2);
FORY_FIELD_TAGS(RefOuterSchemaConsistent, (inner1, 0, nullable, ref),
                (inner2, 1, nullable, ref));
// Verify field tags are correctly parsed
static_assert(fory::detail::has_field_tags_v<RefOuterSchemaConsistent>,
              "RefOuterSchemaConsistent should have field tags");
static_assert(fory::detail::GetFieldTagEntry<RefOuterSchemaConsistent, 0>::id ==
                  0,
              "inner1 should have id=0");
static_assert(
    fory::detail::GetFieldTagEntry<RefOuterSchemaConsistent, 0>::is_nullable ==
        true,
    "inner1 should be nullable");
static_assert(
    fory::detail::GetFieldTagEntry<RefOuterSchemaConsistent, 0>::track_ref ==
        true,
    "inner1 should have track_ref=true");

// Inner struct for reference tracking test (COMPATIBLE mode)
// Matches Java RefInnerCompatible with type ID 503
struct RefInnerCompatible {
  int32_t id;
  std::string name;
  bool operator==(const RefInnerCompatible &other) const {
    return id == other.id && name == other.name;
  }
  bool operator!=(const RefInnerCompatible &other) const {
    return !(*this == other);
  }
};
FORY_STRUCT(RefInnerCompatible, id, name);

// Outer struct for reference tracking test (COMPATIBLE mode)
// Contains two fields that both point to the same inner object.
// Matches Java RefOuterCompatible with type ID 504
// Uses std::shared_ptr for reference tracking to share the same object
struct RefOuterCompatible {
  std::shared_ptr<RefInnerCompatible> inner1;
  std::shared_ptr<RefInnerCompatible> inner2;
  bool operator==(const RefOuterCompatible &other) const {
    bool inner1_eq = (inner1 == nullptr && other.inner1 == nullptr) ||
                     (inner1 != nullptr && other.inner1 != nullptr &&
                      *inner1 == *other.inner1);
    bool inner2_eq = (inner2 == nullptr && other.inner2 == nullptr) ||
                     (inner2 != nullptr && other.inner2 != nullptr &&
                      *inner2 == *other.inner2);
    return inner1_eq && inner2_eq;
  }
};
FORY_STRUCT(RefOuterCompatible, inner1, inner2);
FORY_FIELD_TAGS(RefOuterCompatible, (inner1, 0, nullable, ref),
                (inner2, 1, nullable, ref));

namespace fory {
namespace serialization {

// Custom serializer for MyExt that matches Rust's MyExt serializer
// and Java's MyExtSerializer: it simply serializes the `id` field as
// a varint32 in xlang mode.
template <> struct Serializer<MyExt> {
  static constexpr TypeId type_id = TypeId::EXT;

  static void write(const MyExt &value, WriteContext &ctx, RefMode ref_mode,
                    bool write_type, bool has_generics = false) {
    (void)has_generics;
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      // Delegate dynamic typeinfo to WriteContext so that user type
      // ids and named registrations are encoded consistently with
      // other ext types.
      auto result =
          ctx.write_any_typeinfo(static_cast<uint32_t>(TypeId::UNKNOWN),
                                 std::type_index(typeid(MyExt)));
      if (!result.ok()) {
        ctx.set_error(std::move(result).error());
        return;
      }
    }
    write_data(value, ctx);
  }

  static void write_data(const MyExt &value, WriteContext &ctx) {
    Serializer<int32_t>::write_data(value.id, ctx);
  }

  static void write_data_generic(const MyExt &value, WriteContext &ctx,
                                 bool has_generics) {
    (void)has_generics;
    write_data(value, ctx);
  }

  static MyExt read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return MyExt{};
    }
    if (read_type) {
      // Validate dynamic type info and consume any named metadata.
      const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
      if (ctx.has_error()) {
        return MyExt{};
      }
      if (!type_info) {
        ctx.set_error(Error::type_error("TypeInfo for MyExt not found"));
        return MyExt{};
      }
    }
    MyExt value;
    value.id = Serializer<int32_t>::read_data(ctx);
    return value;
  }

  static MyExt read_data(ReadContext &ctx) {
    MyExt value;
    value.id = Serializer<int32_t>::read_data(ctx);
    return value;
  }

  static MyExt read_data_generic(ReadContext &ctx, bool has_generics) {
    (void)has_generics;
    return read_data(ctx);
  }

  static MyExt read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                   const TypeInfo &type_info) {
    (void)type_info;
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory

// ---------------------------------------------------------------------------
// Helpers for interacting with Fory Result/Buffer
// ---------------------------------------------------------------------------

template <typename T>
T Unwrap(Result<T, Error> result, const std::string &context) {
  if (!result.ok()) {
    Fail(context + ": " + result.error().message());
  }
  return std::move(result).value();
}

void EnsureOk(Result<void, Error> result, const std::string &context) {
  if (!result.ok()) {
    Fail(context + ": " + result.error().message());
  }
}

Buffer MakeBuffer(std::vector<uint8_t> &bytes) {
  Buffer buffer(bytes.data(), static_cast<uint32_t>(bytes.size()), false);
  buffer.WriterIndex(static_cast<uint32_t>(bytes.size()));
  buffer.ReaderIndex(0);
  return buffer;
}

template <typename T> T ReadNext(Fory &fory, Buffer &buffer) {
  // Use Buffer-based deserialize API which updates buffer's reader_index
  auto result = fory.deserialize<T>(buffer);
  if (!result.ok()) {
    Fail("Failed to deserialize value: " + result.error().message());
  }
  return std::move(result).value();
}

template <typename T>
void AppendSerialized(Fory &fory, const T &value, std::vector<uint8_t> &out) {
  auto result = fory.serialize(value);
  if (!result.ok()) {
    Fail("Failed to serialize value: " + result.error().message());
  }
  const auto &bytes = result.value();
  out.insert(out.end(), bytes.begin(), bytes.end());
}

Fory BuildFory(bool compatible = true, bool xlang = true,
               bool check_struct_version = false, bool track_ref = false) {
  return Fory::builder()
      .compatible(compatible)
      .xlang(xlang)
      .check_struct_version(check_struct_version)
      .track_ref(track_ref)
      .build();
}

void RegisterBasicStructs(Fory &fory) {
  EnsureOk(fory.register_enum<Color>(101), "register Color");
  EnsureOk(fory.register_struct<Item>(102), "register Item");
  EnsureOk(fory.register_struct<SimpleStruct>(103), "register SimpleStruct");
}

// Forward declarations for test handlers
namespace {
void RunTestBuffer(const std::string &data_file);
void RunTestBufferVar(const std::string &data_file);
void RunTestMurmurHash3(const std::string &data_file);
void RunTestStringSerializer(const std::string &data_file);
void RunTestCrossLanguageSerializer(const std::string &data_file);
void RunTestSimpleStruct(const std::string &data_file);
void RunTestSimpleNamedStruct(const std::string &data_file);
void RunTestList(const std::string &data_file);
void RunTestMap(const std::string &data_file);
void RunTestInteger(const std::string &data_file);
void RunTestItem(const std::string &data_file);
void RunTestColor(const std::string &data_file);
void RunTestStructWithList(const std::string &data_file);
void RunTestStructWithMap(const std::string &data_file);
void RunTestSkipIdCustom(const std::string &data_file);
void RunTestSkipNameCustom(const std::string &data_file);
void RunTestConsistentNamed(const std::string &data_file);
void RunTestStructVersionCheck(const std::string &data_file);
void RunTestPolymorphicList(const std::string &data_file);
void RunTestPolymorphicMap(const std::string &data_file);
void RunTestOneStringFieldSchema(const std::string &data_file);
void RunTestOneStringFieldCompatible(const std::string &data_file);
void RunTestTwoStringFieldCompatible(const std::string &data_file);
void RunTestSchemaEvolutionCompatible(const std::string &data_file);
void RunTestSchemaEvolutionCompatibleReverse(const std::string &data_file);
void RunTestOneEnumFieldSchema(const std::string &data_file);
void RunTestOneEnumFieldCompatible(const std::string &data_file);
void RunTestTwoEnumFieldCompatible(const std::string &data_file);
void RunTestEnumSchemaEvolutionCompatible(const std::string &data_file);
void RunTestEnumSchemaEvolutionCompatibleReverse(const std::string &data_file);
void RunTestNullableFieldSchemaConsistentNotNull(const std::string &data_file);
void RunTestNullableFieldSchemaConsistentNull(const std::string &data_file);
void RunTestNullableFieldCompatibleNotNull(const std::string &data_file);
void RunTestNullableFieldCompatibleNull(const std::string &data_file);
void RunTestRefSchemaConsistent(const std::string &data_file);
void RunTestRefCompatible(const std::string &data_file);
} // namespace

int main(int argc, char **argv) {
  if (argc < 2) {
    Fail("Usage: xlang_test_main --case <test_name>");
  }
  std::string case_name;
  for (int i = 1; i < argc; ++i) {
    std::string arg(argv[i]);
    if ((arg == "--case" || arg == "-c") && i + 1 < argc) {
      case_name = argv[++i];
    } else if (case_name.empty()) {
      case_name = arg;
    }
  }
  if (case_name.empty()) {
    Fail("Missing --case argument");
  }

  g_current_case = case_name;

  const std::string data_file = GetDataFilePath();
  try {
    if (case_name == "test_buffer") {
      RunTestBuffer(data_file);
    } else if (case_name == "test_buffer_var") {
      RunTestBufferVar(data_file);
    } else if (case_name == "test_murmurhash3") {
      RunTestMurmurHash3(data_file);
    } else if (case_name == "test_string_serializer") {
      RunTestStringSerializer(data_file);
    } else if (case_name == "test_cross_language_serializer") {
      RunTestCrossLanguageSerializer(data_file);
    } else if (case_name == "test_simple_struct") {
      RunTestSimpleStruct(data_file);
    } else if (case_name == "test_named_simple_struct") {
      RunTestSimpleNamedStruct(data_file);
    } else if (case_name == "test_list") {
      RunTestList(data_file);
    } else if (case_name == "test_map") {
      RunTestMap(data_file);
    } else if (case_name == "test_integer") {
      RunTestInteger(data_file);
    } else if (case_name == "test_item") {
      RunTestItem(data_file);
    } else if (case_name == "test_color") {
      RunTestColor(data_file);
    } else if (case_name == "test_struct_with_list") {
      RunTestStructWithList(data_file);
    } else if (case_name == "test_struct_with_map") {
      RunTestStructWithMap(data_file);
    } else if (case_name == "test_skip_id_custom") {
      RunTestSkipIdCustom(data_file);
    } else if (case_name == "test_skip_name_custom") {
      RunTestSkipNameCustom(data_file);
    } else if (case_name == "test_consistent_named") {
      RunTestConsistentNamed(data_file);
    } else if (case_name == "test_struct_version_check") {
      RunTestStructVersionCheck(data_file);
    } else if (case_name == "test_polymorphic_list") {
      RunTestPolymorphicList(data_file);
    } else if (case_name == "test_polymorphic_map") {
      RunTestPolymorphicMap(data_file);
    } else if (case_name == "test_one_string_field_schema") {
      RunTestOneStringFieldSchema(data_file);
    } else if (case_name == "test_one_string_field_compatible") {
      RunTestOneStringFieldCompatible(data_file);
    } else if (case_name == "test_two_string_field_compatible") {
      RunTestTwoStringFieldCompatible(data_file);
    } else if (case_name == "test_schema_evolution_compatible") {
      RunTestSchemaEvolutionCompatible(data_file);
    } else if (case_name == "test_schema_evolution_compatible_reverse") {
      RunTestSchemaEvolutionCompatibleReverse(data_file);
    } else if (case_name == "test_one_enum_field_schema") {
      RunTestOneEnumFieldSchema(data_file);
    } else if (case_name == "test_one_enum_field_compatible") {
      RunTestOneEnumFieldCompatible(data_file);
    } else if (case_name == "test_two_enum_field_compatible") {
      RunTestTwoEnumFieldCompatible(data_file);
    } else if (case_name == "test_enum_schema_evolution_compatible") {
      RunTestEnumSchemaEvolutionCompatible(data_file);
    } else if (case_name == "test_enum_schema_evolution_compatible_reverse") {
      RunTestEnumSchemaEvolutionCompatibleReverse(data_file);
    } else if (case_name == "test_nullable_field_schema_consistent_not_null") {
      RunTestNullableFieldSchemaConsistentNotNull(data_file);
    } else if (case_name == "test_nullable_field_schema_consistent_null") {
      RunTestNullableFieldSchemaConsistentNull(data_file);
    } else if (case_name == "test_nullable_field_compatible_not_null") {
      RunTestNullableFieldCompatibleNotNull(data_file);
    } else if (case_name == "test_nullable_field_compatible_null") {
      RunTestNullableFieldCompatibleNull(data_file);
    } else if (case_name == "test_ref_schema_consistent") {
      RunTestRefSchemaConsistent(data_file);
    } else if (case_name == "test_ref_compatible") {
      RunTestRefCompatible(data_file);
    } else {
      Fail("Unknown test case: " + case_name);
    }
  } catch (const std::exception &ex) {
    std::cerr << "xlang_test_main failed: " << ex.what() << std::endl;
    return 1;
  }
  return 0;
}

namespace {

void RunTestBuffer(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  Buffer buffer(bytes.data(), static_cast<uint32_t>(bytes.size()), false);

  Error error;
  uint8_t bool_val_raw = buffer.ReadUint8(error);
  if (!error.ok())
    Fail("Failed to read bool: " + error.message());
  bool bool_val = bool_val_raw != 0;

  int8_t int8_val = buffer.ReadInt8(error);
  if (!error.ok())
    Fail("Failed to read int8: " + error.message());

  int16_t int16_val = buffer.ReadInt16(error);
  if (!error.ok())
    Fail("Failed to read int16: " + error.message());

  int32_t int32_val = buffer.ReadInt32(error);
  if (!error.ok())
    Fail("Failed to read int32: " + error.message());

  int64_t int64_val = buffer.ReadInt64(error);
  if (!error.ok())
    Fail("Failed to read int64: " + error.message());

  float float_val = buffer.ReadFloat(error);
  if (!error.ok())
    Fail("Failed to read float: " + error.message());

  double double_val = buffer.ReadDouble(error);
  if (!error.ok())
    Fail("Failed to read double: " + error.message());

  uint32_t varint = buffer.ReadVarUint32(error);
  if (!error.ok())
    Fail("Failed to read varint: " + error.message());

  int32_t payload_len = buffer.ReadInt32(error);
  if (!error.ok())
    Fail("Failed to read payload len: " + error.message());

  if (payload_len < 0 || buffer.reader_index() + payload_len > buffer.size()) {
    Fail("Invalid payload length in buffer test");
  }
  std::vector<uint8_t> payload(bytes.begin() + buffer.reader_index(),
                               bytes.begin() + buffer.reader_index() +
                                   payload_len);
  buffer.Skip(payload_len, error);
  if (!error.ok())
    Fail("Failed to skip payload: " + error.message());

  if (!bool_val || int8_val != std::numeric_limits<int8_t>::max() ||
      int16_val != std::numeric_limits<int16_t>::max() ||
      int32_val != std::numeric_limits<int32_t>::max() ||
      int64_val != std::numeric_limits<int64_t>::max() ||
      std::abs(float_val + 1.1f) > 1e-6 || std::abs(double_val + 1.1) > 1e-9 ||
      varint != 100 || payload != std::vector<uint8_t>({'a', 'b'})) {
    Fail("Buffer test validation failed");
  }

  Buffer out_buffer;
  out_buffer.WriteUint8(1);
  out_buffer.WriteInt8(std::numeric_limits<int8_t>::max());
  out_buffer.WriteInt16(std::numeric_limits<int16_t>::max());
  out_buffer.WriteInt32(std::numeric_limits<int32_t>::max());
  out_buffer.WriteInt64(std::numeric_limits<int64_t>::max());
  out_buffer.WriteFloat(-1.1f);
  out_buffer.WriteDouble(-1.1);
  out_buffer.WriteVarUint32(100);
  out_buffer.WriteInt32(static_cast<int32_t>(payload.size()));
  out_buffer.WriteBytes(payload.data(), static_cast<uint32_t>(payload.size()));

  std::vector<uint8_t> out(out_buffer.data(),
                           out_buffer.data() + out_buffer.writer_index());
  WriteFile(data_file, out);
}

void RunTestBufferVar(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  Buffer buffer(bytes.data(), static_cast<uint32_t>(bytes.size()), false);

  Error error;
  const std::vector<int32_t> expected_varint32 = {
      std::numeric_limits<int32_t>::min(),
      std::numeric_limits<int32_t>::min() + 1,
      -1000000,
      -1000,
      -128,
      -1,
      0,
      1,
      127,
      128,
      16383,
      16384,
      2097151,
      2097152,
      268435455,
      268435456,
      std::numeric_limits<int32_t>::max() - 1,
      std::numeric_limits<int32_t>::max()};
  for (int32_t value : expected_varint32) {
    int32_t result = buffer.ReadVarInt32(error);
    if (!error.ok() || result != value) {
      Fail("VarInt32 mismatch");
    }
  }

  const std::vector<uint32_t> expected_varuint32 = {
      0u,       1u,       127u,       128u,       16383u,      16384u,
      2097151u, 2097152u, 268435455u, 268435456u, 2147483646u, 2147483647u};
  for (uint32_t value : expected_varuint32) {
    uint32_t result = buffer.ReadVarUint32(error);
    if (!error.ok() || result != value) {
      Fail("VarUint32 mismatch");
    }
  }

  const std::vector<uint64_t> expected_varuint64 = {
      0ull,
      1ull,
      127ull,
      128ull,
      16383ull,
      16384ull,
      2097151ull,
      2097152ull,
      268435455ull,
      268435456ull,
      34359738367ull,
      34359738368ull,
      4398046511103ull,
      4398046511104ull,
      562949953421311ull,
      562949953421312ull,
      72057594037927935ull,
      72057594037927936ull,
      static_cast<uint64_t>(std::numeric_limits<int64_t>::max())};
  for (uint64_t value : expected_varuint64) {
    uint64_t result = buffer.ReadVarUint64(error);
    if (!error.ok() || result != value) {
      Fail("VarUint64 mismatch");
    }
  }

  const std::vector<int64_t> expected_varint64 = {
      std::numeric_limits<int64_t>::min(),
      std::numeric_limits<int64_t>::min() + 1,
      -1000000000000LL,
      -1000000LL,
      -1000LL,
      -128LL,
      -1LL,
      0LL,
      1LL,
      127LL,
      1000LL,
      1000000LL,
      1000000000000LL,
      std::numeric_limits<int64_t>::max() - 1,
      std::numeric_limits<int64_t>::max()};
  for (int64_t value : expected_varint64) {
    int64_t result = buffer.ReadVarInt64(error);
    if (!error.ok() || result != value) {
      Fail("VarInt64 mismatch");
    }
  }

  Buffer out_buffer;
  for (int32_t value : expected_varint32) {
    out_buffer.WriteVarInt32(value);
  }
  for (uint32_t value : expected_varuint32) {
    out_buffer.WriteVarUint32(value);
  }
  for (uint64_t value : expected_varuint64) {
    out_buffer.WriteVarUint64(value);
  }
  for (int64_t value : expected_varint64) {
    out_buffer.WriteVarInt64(value);
  }

  std::vector<uint8_t> out(out_buffer.data(),
                           out_buffer.data() + out_buffer.writer_index());
  WriteFile(data_file, out);
}

void RunTestMurmurHash3(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  if (bytes.size() < 16) {
    Fail("Not enough bytes for murmurhash test");
  }
  Buffer buffer(bytes.data(), static_cast<uint32_t>(bytes.size()), false);

  Error error;
  int64_t first = buffer.ReadInt64(error);
  if (!error.ok())
    Fail("Failed to read first int64: " + error.message());

  int64_t second = buffer.ReadInt64(error);
  if (!error.ok())
    Fail("Failed to read second int64: " + error.message());

  int64_t hash_out[2] = {0, 0};
  MurmurHash3_x64_128("\x01\x02\x08", 3, 47, hash_out);
  if (first != hash_out[0] || second != hash_out[1]) {
    Fail("MurmurHash3 mismatch");
  }
}

void RunTestStringSerializer(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  std::vector<std::string> test_strings = {
      "ab",     "Rust123", "Çüéâäàåçêëèïî", "こんにちは",
      "Привет", "𝄞🎵🎶",   "Hello, 世界",
  };

  {
    std::vector<uint8_t> copy = bytes;
    Buffer buffer = MakeBuffer(copy);
    for (const auto &expected : test_strings) {
      auto actual = ReadNext<std::string>(fory, buffer);
      if (actual != expected) {
        Fail("String serializer mismatch");
      }
    }
  }

  std::vector<uint8_t> out;
  out.reserve(bytes.size());
  for (const auto &s : test_strings) {
    AppendSerialized(fory, s, out);
  }
  WriteFile(data_file, out);
}

void RunTestCrossLanguageSerializer(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  RegisterBasicStructs(fory);

  std::vector<std::string> str_list = {"hello", "world"};
  std::set<std::string> str_set = {"hello", "world"};
  std::map<std::string, std::string> str_map = {{"hello", "world"},
                                                {"foo", "bar"}};
  LocalDate day(18954); // 2021-11-23
  Timestamp instant(std::chrono::nanoseconds(100000000));

  std::vector<uint8_t> copy = bytes;
  Buffer buffer = MakeBuffer(copy);

  auto expect_bool = [&](bool expected) {
    if (ReadNext<bool>(fory, buffer) != expected) {
      Fail("Boolean mismatch in cross-language serializer test");
    }
  };

  expect_bool(true);
  expect_bool(false);

  int32_t v1 = ReadNext<int32_t>(fory, buffer);
  if (v1 != -1)
    Fail("int32 -1 mismatch, got: " + std::to_string(v1));

  int8_t v2 = ReadNext<int8_t>(fory, buffer);
  if (v2 != std::numeric_limits<int8_t>::max())
    Fail("int8 max mismatch, got: " + std::to_string(v2));

  int8_t v3 = ReadNext<int8_t>(fory, buffer);
  if (v3 != std::numeric_limits<int8_t>::min())
    Fail("int8 min mismatch, got: " + std::to_string(v3));

  int16_t v4 = ReadNext<int16_t>(fory, buffer);
  if (v4 != std::numeric_limits<int16_t>::max())
    Fail("int16 max mismatch, got: " + std::to_string(v4));

  int16_t v5 = ReadNext<int16_t>(fory, buffer);
  if (v5 != std::numeric_limits<int16_t>::min())
    Fail("int16 min mismatch, got: " + std::to_string(v5));

  int32_t v6 = ReadNext<int32_t>(fory, buffer);
  if (v6 != std::numeric_limits<int32_t>::max())
    Fail("int32 max mismatch, got: " + std::to_string(v6));

  int32_t v7 = ReadNext<int32_t>(fory, buffer);
  if (v7 != std::numeric_limits<int32_t>::min())
    Fail("int32 min mismatch, got: " + std::to_string(v7));

  int64_t v8 = ReadNext<int64_t>(fory, buffer);
  if (v8 != std::numeric_limits<int64_t>::max())
    Fail("int64 max mismatch, got: " + std::to_string(v8));

  int64_t v9 = ReadNext<int64_t>(fory, buffer);
  if (v9 != std::numeric_limits<int64_t>::min())
    Fail("int64 min mismatch, got: " + std::to_string(v9));
  if (std::abs(ReadNext<float>(fory, buffer) + 1.0f) > 1e-6 ||
      std::abs(ReadNext<double>(fory, buffer) + 1.0) > 1e-9) {
    Fail("Float mismatch");
  }
  if (ReadNext<std::string>(fory, buffer) != "str") {
    Fail("String mismatch");
  }
  if (ReadNext<LocalDate>(fory, buffer) != day) {
    Fail("LocalDate mismatch");
  }
  if (ReadNext<Timestamp>(fory, buffer) != instant) {
    Fail("Timestamp mismatch");
  }
  if (ReadNext<std::vector<bool>>(fory, buffer) !=
      std::vector<bool>({true, false})) {
    Fail("Boolean array mismatch");
  }
  if (ReadNext<std::vector<uint8_t>>(fory, buffer) !=
      std::vector<uint8_t>({1, static_cast<uint8_t>(127)})) {
    Fail("Byte array mismatch");
  }
  if (ReadNext<std::vector<int16_t>>(fory, buffer) !=
      std::vector<int16_t>({1, std::numeric_limits<int16_t>::max()})) {
    Fail("Short array mismatch");
  }
  if (ReadNext<std::vector<int32_t>>(fory, buffer) !=
      std::vector<int32_t>({1, std::numeric_limits<int32_t>::max()})) {
    Fail("Int array mismatch");
  }
  if (ReadNext<std::vector<int64_t>>(fory, buffer) !=
      std::vector<int64_t>({1, std::numeric_limits<int64_t>::max()})) {
    Fail("Long array mismatch");
  }
  if (ReadNext<std::vector<float>>(fory, buffer) !=
      std::vector<float>({1.0f, 2.0f})) {
    Fail("Float array mismatch");
  }
  if (ReadNext<std::vector<double>>(fory, buffer) !=
      std::vector<double>({1.0, 2.0})) {
    Fail("Double array mismatch");
  }
  if (ReadNext<std::vector<std::string>>(fory, buffer) != str_list) {
    Fail("List mismatch");
  }
  if (ReadNext<std::set<std::string>>(fory, buffer) != str_set) {
    Fail("Set mismatch");
  }
  if (ReadNext<std::map<std::string, std::string>>(fory, buffer) != str_map) {
    Fail("Map mismatch");
  }
  if (ReadNext<Color>(fory, buffer) != Color::White) {
    Fail("Color mismatch");
  }

  std::vector<uint8_t> out;
  out.reserve(bytes.size());
  AppendSerialized(fory, true, out);
  AppendSerialized(fory, false, out);
  AppendSerialized(fory, -1, out);
  AppendSerialized(fory, std::numeric_limits<int8_t>::max(), out);
  AppendSerialized(fory, std::numeric_limits<int8_t>::min(), out);
  AppendSerialized(fory, std::numeric_limits<int16_t>::max(), out);
  AppendSerialized(fory, std::numeric_limits<int16_t>::min(), out);
  AppendSerialized(fory, std::numeric_limits<int32_t>::max(), out);
  AppendSerialized(fory, std::numeric_limits<int32_t>::min(), out);
  AppendSerialized(fory, std::numeric_limits<int64_t>::max(), out);
  AppendSerialized(fory, std::numeric_limits<int64_t>::min(), out);
  AppendSerialized(fory, -1.0f, out);
  AppendSerialized(fory, -1.0, out);
  AppendSerialized(fory, std::string("str"), out);
  AppendSerialized(fory, day, out);
  AppendSerialized(fory, instant, out);
  AppendSerialized(fory, std::vector<bool>({true, false}), out);
  AppendSerialized(fory, std::vector<uint8_t>({1, 127}), out);
  AppendSerialized(
      fory, std::vector<int16_t>({1, std::numeric_limits<int16_t>::max()}),
      out);
  AppendSerialized(
      fory, std::vector<int32_t>({1, std::numeric_limits<int32_t>::max()}),
      out);
  AppendSerialized(
      fory, std::vector<int64_t>({1, std::numeric_limits<int64_t>::max()}),
      out);
  AppendSerialized(fory, std::vector<float>({1.0f, 2.0f}), out);
  AppendSerialized(fory, std::vector<double>({1.0, 2.0}), out);
  AppendSerialized(fory, str_list, out);
  AppendSerialized(fory, str_set, out);
  AppendSerialized(fory, str_map, out);
  AppendSerialized(fory, Color::White, out);

  WriteFile(data_file, out);
}

SimpleStruct BuildSimpleStruct() {
  SimpleStruct obj;
  obj.f1 = {{1, 1.0}, {2, 2.0}};
  obj.f2 = 39;
  obj.f3 = Item{std::string("item")};
  obj.f4 = std::string("f4");
  obj.f5 = Color::White;
  obj.f6 = {std::string("f6")};
  obj.f7 = 40;
  obj.f8 = 41;
  obj.last = 42;
  return obj;
}

void RunTestSimpleStruct(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  RegisterBasicStructs(fory);
#ifdef FORY_DEBUG
  {
    const auto &local_meta = fory.type_resolver().struct_meta<SimpleStruct>();
    const auto &fields = local_meta.get_field_infos();
    std::cerr << "[xlang][local_meta] SimpleStruct fields=" << fields.size()
              << std::endl;
    for (const auto &f : fields) {
      std::cerr << "  local field_id=" << f.field_id
                << ", name=" << f.field_name
                << ", type_id=" << f.field_type.type_id
                << ", nullable=" << f.field_type.nullable << std::endl;
    }
  }
#endif
  auto expected = BuildSimpleStruct();
  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<SimpleStruct>(fory, buffer);
  if (!(value == expected)) {
    Fail("SimpleStruct mismatch");
  }
  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestSimpleNamedStruct(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  EnsureOk(fory.register_enum<Color>("demo", "color"), "register color");
  EnsureOk(fory.register_struct<Item>("demo", "item"), "register item");
  EnsureOk(fory.register_struct<SimpleStruct>("demo", "simple_struct"),
           "register simple_struct");
  auto expected = BuildSimpleStruct();
  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<SimpleStruct>(fory, buffer);
  if (!(value == expected)) {
    Fail("Named SimpleStruct mismatch");
  }
  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestList(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  EnsureOk(fory.register_struct<Item>(102), "register Item");
  Buffer buffer = MakeBuffer(bytes);

  std::vector<std::optional<std::string>> expected1 = {std::string("a"),
                                                       std::string("b")};
  std::vector<std::optional<std::string>> expected2 = {std::nullopt,
                                                       std::string("b")};
  Item item_a;
  item_a.name = std::string("a");
  Item item_b;
  item_b.name = std::string("b");
  Item item_c;
  item_c.name = std::string("c");
  std::vector<std::optional<Item>> expected_items1 = {item_a, item_b};
  std::vector<std::optional<Item>> expected_items2 = {std::nullopt, item_c};

  if (ReadNext<std::vector<std::optional<std::string>>>(fory, buffer) !=
      expected1) {
    Fail("List string mismatch");
  }
  if (ReadNext<std::vector<std::optional<std::string>>>(fory, buffer) !=
      expected2) {
    Fail("List string with null mismatch");
  }
  if (ReadNext<std::vector<std::optional<Item>>>(fory, buffer) !=
      expected_items1) {
    Fail("List item mismatch");
  }
  if (ReadNext<std::vector<std::optional<Item>>>(fory, buffer) !=
      expected_items2) {
    Fail("List item with null mismatch");
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, expected1, out);
  AppendSerialized(fory, expected2, out);
  AppendSerialized(fory, expected_items1, out);
  AppendSerialized(fory, expected_items2, out);
  WriteFile(data_file, out);
}

void RunTestMap(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  EnsureOk(fory.register_struct<Item>(102), "register Item");
  Buffer buffer = MakeBuffer(bytes);

  using OptStr = std::optional<std::string>;
  using OptItem = std::optional<Item>;
  std::map<OptStr, OptStr> str_map = {{std::string("k1"), std::string("v1")},
                                      {std::nullopt, std::string("v2")},
                                      {std::string("k3"), std::nullopt},
                                      {std::string("k4"), std::string("v4")}};
  Item item1{std::string("item1")};
  Item item2{std::string("item2")};
  Item item3{std::string("item3")};
  std::map<OptStr, OptItem> item_map = {{std::string("k1"), item1},
                                        {std::nullopt, item2},
                                        {std::string("k3"), std::nullopt},
                                        {std::string("k4"), item3}};

  if (ReadNext<std::map<OptStr, OptStr>>(fory, buffer) != str_map) {
    Fail("Map<string,string> mismatch");
  }
  if (ReadNext<std::map<OptStr, OptItem>>(fory, buffer) != item_map) {
    Fail("Map<string,item> mismatch");
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, str_map, out);
  AppendSerialized(fory, item_map, out);
  WriteFile(data_file, out);
}

void RunTestInteger(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  EnsureOk(fory.register_struct<Item1>(101), "register Item1");
  Buffer buffer = MakeBuffer(bytes);

  Item1 expected;
  expected.f1 = 1;
  expected.f2 = 2;
  expected.f3 = 3;
  expected.f4 = 4;
  expected.f5 = 0;
  expected.f6 = 0;

  auto item_value = ReadNext<Item1>(fory, buffer);
  if (!(item_value == expected)) {
    Fail("Item1 mismatch");
  }
  // Note: we do not consume the trailing primitive integers from the
  // Java-produced payload here. They are validated on the Java and Rust
  // sides and re-written by the C++ side below for the C++ -> Java
  // round-trip.

  std::vector<uint8_t> out;
  AppendSerialized(fory, item_value, out);
  AppendSerialized(fory, 1, out);
  AppendSerialized(fory, 2, out);
  AppendSerialized(fory, std::optional<int32_t>(3), out);
  AppendSerialized(fory, std::optional<int32_t>(4), out);
  // xlang mode uses nullable=false by default, so write 0 not null
  AppendSerialized(fory, 0, out);
  AppendSerialized(fory, 0, out);
  WriteFile(data_file, out);
}

void RunTestItem(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  EnsureOk(fory.register_struct<Item>(102), "register Item");

  Buffer buffer = MakeBuffer(bytes);

  Item expected1;
  expected1.name = std::string("test_item_1");
  Item expected2;
  expected2.name = std::string("test_item_2");
  Item expected3;
  expected3.name = std::string(""); // Empty string for non-nullable field

  Item item1 = ReadNext<Item>(fory, buffer);
  if (!(item1 == expected1)) {
    Fail("Item 1 mismatch");
  }
  Item item2 = ReadNext<Item>(fory, buffer);
  if (!(item2 == expected2)) {
    Fail("Item 2 mismatch");
  }
  Item item3 = ReadNext<Item>(fory, buffer);
  if (!(item3 == expected3)) {
    Fail("Item 3 mismatch");
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, expected1, out);
  AppendSerialized(fory, expected2, out);
  AppendSerialized(fory, expected3, out);
  WriteFile(data_file, out);
}

void RunTestColor(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  EnsureOk(fory.register_enum<Color>(101), "register Color");

  Buffer buffer = MakeBuffer(bytes);

  if (ReadNext<Color>(fory, buffer) != Color::Green) {
    Fail("Color Green mismatch");
  }
  if (ReadNext<Color>(fory, buffer) != Color::Red) {
    Fail("Color Red mismatch");
  }
  if (ReadNext<Color>(fory, buffer) != Color::Blue) {
    Fail("Color Blue mismatch");
  }
  if (ReadNext<Color>(fory, buffer) != Color::White) {
    Fail("Color White mismatch");
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, Color::Green, out);
  AppendSerialized(fory, Color::Red, out);
  AppendSerialized(fory, Color::Blue, out);
  AppendSerialized(fory, Color::White, out);
  WriteFile(data_file, out);
}

void RunTestStructWithList(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  EnsureOk(fory.register_struct<StructWithList>(201),
           "register StructWithList");

  Buffer buffer = MakeBuffer(bytes);

  StructWithList expected1;
  expected1.items = {std::string("a"), std::string("b"), std::string("c")};

  StructWithList expected2;
  expected2.items = {std::string("x"), std::string(""),
                     std::string("z")}; // Empty string instead of null

  StructWithList struct1 = ReadNext<StructWithList>(fory, buffer);
  if (!(struct1 == expected1)) {
    Fail("StructWithList 1 mismatch");
  }

  StructWithList struct2 = ReadNext<StructWithList>(fory, buffer);
  if (!(struct2 == expected2)) {
    Fail("StructWithList 2 mismatch");
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, expected1, out);
  AppendSerialized(fory, expected2, out);
  WriteFile(data_file, out);
}

void RunTestStructWithMap(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  EnsureOk(fory.register_struct<StructWithMap>(202), "register StructWithMap");

  Buffer buffer = MakeBuffer(bytes);

  StructWithMap expected1;
  expected1.data = {{std::string("key1"), std::string("value1")},
                    {std::string("key2"), std::string("value2")}};

  StructWithMap expected2;
  // Java test uses null values - but with xlang non-nullable default,
  // these should be empty strings or the test may need adjustment
  expected2.data = {{std::string("k1"), std::string("")},
                    {std::string(""), std::string("v2")}};

  StructWithMap struct1 = ReadNext<StructWithMap>(fory, buffer);
  if (!(struct1 == expected1)) {
    Fail("StructWithMap 1 mismatch");
  }

  StructWithMap struct2 = ReadNext<StructWithMap>(fory, buffer);
  if (!(struct2 == expected2)) {
    Fail("StructWithMap 2 mismatch");
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, expected1, out);
  AppendSerialized(fory, expected2, out);
  WriteFile(data_file, out);
}

void RunTestSkipIdCustom(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto limited = BuildFory(true, true);
  EnsureOk(limited.register_extension_type<MyExt>(103),
           "register MyExt limited");
  EnsureOk(limited.register_struct<EmptyWrapper>(104),
           "register EmptyWrapper limited");
  {
    std::vector<uint8_t> copy = bytes;
    Buffer buffer = MakeBuffer(copy);
    (void)ReadNext<EmptyWrapper>(limited, buffer);
  }

  auto full = BuildFory(true, true);
  EnsureOk(full.register_enum<Color>(101), "register Color full");
  EnsureOk(full.register_struct<MyStruct>(102), "register MyStruct full");
  EnsureOk(full.register_extension_type<MyExt>(103), "register MyExt full");
  EnsureOk(full.register_struct<MyWrapper>(104), "register MyWrapper full");

  MyWrapper wrapper;
  wrapper.color = Color::White;
  wrapper.my_struct = MyStruct(42);
  wrapper.my_ext = MyExt{43};

  std::vector<uint8_t> out;
  AppendSerialized(full, wrapper, out);
  WriteFile(data_file, out);
}

void RunTestSkipNameCustom(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto limited = BuildFory(true, true);
  EnsureOk(limited.register_extension_type<MyExt>("my_ext"),
           "register named MyExt");
  EnsureOk(limited.register_struct<EmptyWrapper>("my_wrapper"),
           "register named EmptyWrapper");
  {
    std::vector<uint8_t> copy = bytes;
    Buffer buffer = MakeBuffer(copy);
    auto result = limited.deserialize<EmptyWrapper>(buffer);
    if (!result.ok()) {
      Fail("Failed to deserialize EmptyWrapper: " + result.error().message());
    }
  }

  auto full = BuildFory(true, true);
  EnsureOk(full.register_enum<Color>("color"), "register named Color");
  EnsureOk(full.register_struct<MyStruct>("my_struct"),
           "register named MyStruct");
  EnsureOk(full.register_extension_type<MyExt>("my_ext"),
           "register named MyExt");
  EnsureOk(full.register_struct<MyWrapper>("my_wrapper"),
           "register named MyWrapper");

  MyWrapper wrapper;
  wrapper.color = Color::White;
  wrapper.my_struct = MyStruct(42);
  wrapper.my_ext = MyExt{43};

  std::vector<uint8_t> out;
  AppendSerialized(full, wrapper, out);
  WriteFile(data_file, out);
}

void RunTestConsistentNamed(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // Java uses SCHEMA_CONSISTENT mode which does NOT enable meta sharing
  auto fory = BuildFory(false, true, true);
  EnsureOk(fory.register_enum<Color>("color"), "register named color");
  EnsureOk(fory.register_struct<MyStruct>("my_struct"),
           "register named MyStruct");
  EnsureOk(fory.register_extension_type<MyExt>("my_ext"),
           "register named MyExt");

  MyStruct my_struct(42);
  MyExt my_ext{43};

  Buffer buffer = MakeBuffer(bytes);
  for (int i = 0; i < 3; ++i) {
    if (ReadNext<Color>(fory, buffer) != Color::White) {
      Fail("Consistent named color mismatch");
    }
  }
  for (int i = 0; i < 3; ++i) {
    if (!(ReadNext<MyStruct>(fory, buffer) == my_struct)) {
      Fail("Consistent named struct mismatch");
    }
  }
  for (int i = 0; i < 3; ++i) {
    if (!(ReadNext<MyExt>(fory, buffer) == my_ext)) {
      Fail("Consistent named ext mismatch");
    }
  }

  std::vector<uint8_t> out;
  for (int i = 0; i < 3; ++i) {
    AppendSerialized(fory, Color::White, out);
  }
  for (int i = 0; i < 3; ++i) {
    AppendSerialized(fory, my_struct, out);
  }
  for (int i = 0; i < 3; ++i) {
    AppendSerialized(fory, my_ext, out);
  }
  WriteFile(data_file, out);
}

void RunTestStructVersionCheck(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // Java uses SCHEMA_CONSISTENT mode which does NOT enable meta sharing
  auto fory = BuildFory(false, true, true);
  EnsureOk(fory.register_struct<VersionCheckStruct>(201),
           "register VersionCheckStruct");

  VersionCheckStruct expected;
  expected.f1 = 10;
  expected.f2 = std::string("test");
  expected.f3 = 3.2;

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<VersionCheckStruct>(fory, buffer);
  if (!(value == expected)) {
    Fail("VersionCheckStruct mismatch");
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestPolymorphicList(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  EnsureOk(fory.register_struct<Dog>(302), "register Dog");
  EnsureOk(fory.register_struct<Cat>(303), "register Cat");
  EnsureOk(fory.register_struct<AnimalListHolder>(304),
           "register AnimalListHolder");

  Buffer buffer = MakeBuffer(bytes);

  // Part 1: Read List<Animal> with polymorphic elements (Dog, Cat)
  auto animals = ReadNext<std::vector<std::shared_ptr<Animal>>>(fory, buffer);
  if (animals.size() != 2) {
    Fail("Animal list size mismatch, got: " + std::to_string(animals.size()));
  }

  // First element should be Dog
  auto *dog = dynamic_cast<Dog *>(animals[0].get());
  if (dog == nullptr) {
    Fail("First element is not a Dog");
  }
  if (dog->age != 3 || dog->name != std::string("Buddy")) {
    Fail("First Dog mismatch: age=" + std::to_string(dog->age) +
         ", name=" + dog->name.value_or("null"));
  }

  // Second element should be Cat
  auto *cat = dynamic_cast<Cat *>(animals[1].get());
  if (cat == nullptr) {
    Fail("Second element is not a Cat");
  }
  if (cat->age != 5 || cat->lives != 9) {
    Fail("Cat mismatch: age=" + std::to_string(cat->age) +
         ", lives=" + std::to_string(cat->lives));
  }

  // Part 2: Read AnimalListHolder (List<Animal> as struct field)
  auto holder = ReadNext<AnimalListHolder>(fory, buffer);
  if (holder.animals.size() != 2) {
    Fail("AnimalListHolder size mismatch");
  }

  auto *dog2 = dynamic_cast<Dog *>(holder.animals[0].get());
  if (dog2 == nullptr || dog2->name != std::string("Rex")) {
    Fail("AnimalListHolder first animal (Dog) mismatch");
  }

  auto *cat2 = dynamic_cast<Cat *>(holder.animals[1].get());
  if (cat2 == nullptr || cat2->lives != 7) {
    Fail("AnimalListHolder second animal (Cat) mismatch");
  }

  // Write back
  std::vector<uint8_t> out;
  AppendSerialized(fory, animals, out);
  AppendSerialized(fory, holder, out);
  WriteFile(data_file, out);
}

void RunTestPolymorphicMap(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true);
  EnsureOk(fory.register_struct<Dog>(302), "register Dog");
  EnsureOk(fory.register_struct<Cat>(303), "register Cat");
  EnsureOk(fory.register_struct<AnimalMapHolder>(305),
           "register AnimalMapHolder");

  Buffer buffer = MakeBuffer(bytes);

  // Part 1: Read Map<String, Animal> with polymorphic values
  using OptStr = std::optional<std::string>;
  auto animalMap =
      ReadNext<std::map<OptStr, std::shared_ptr<Animal>>>(fory, buffer);
  if (animalMap.size() != 2) {
    Fail("Animal map size mismatch, got: " + std::to_string(animalMap.size()));
  }

  auto dog1It = animalMap.find(std::string("dog1"));
  if (dog1It == animalMap.end()) {
    Fail("Dog1 not found in map");
  }
  auto *dog = dynamic_cast<Dog *>(dog1It->second.get());
  if (dog == nullptr || dog->age != 2 || dog->name != std::string("Rex")) {
    Fail("Animal map dog1 mismatch");
  }

  auto cat1It = animalMap.find(std::string("cat1"));
  if (cat1It == animalMap.end()) {
    Fail("Cat1 not found in map");
  }
  auto *cat = dynamic_cast<Cat *>(cat1It->second.get());
  if (cat == nullptr || cat->age != 4 || cat->lives != 9) {
    Fail("Animal map cat1 mismatch");
  }

  // Part 2: Read AnimalMapHolder (Map<String, Animal> as struct field)
  auto holder = ReadNext<AnimalMapHolder>(fory, buffer);
  if (holder.animal_map.size() != 2) {
    Fail("AnimalMapHolder size mismatch");
  }

  auto myDogIt = holder.animal_map.find(std::string("myDog"));
  if (myDogIt == holder.animal_map.end()) {
    Fail("myDog not found in holder map");
  }
  auto *myDog = dynamic_cast<Dog *>(myDogIt->second.get());
  if (myDog == nullptr || myDog->name != std::string("Fido")) {
    Fail("AnimalMapHolder myDog mismatch");
  }

  auto myCatIt = holder.animal_map.find(std::string("myCat"));
  if (myCatIt == holder.animal_map.end()) {
    Fail("myCat not found in holder map");
  }
  auto *myCat = dynamic_cast<Cat *>(myCatIt->second.get());
  if (myCat == nullptr || myCat->lives != 8) {
    Fail("AnimalMapHolder myCat mismatch");
  }

  // Write back
  std::vector<uint8_t> out;
  AppendSerialized(fory, animalMap, out);
  AppendSerialized(fory, holder, out);
  WriteFile(data_file, out);
}

// ============================================================================
// Schema Evolution Tests - String Fields
// ============================================================================

void RunTestOneStringFieldSchema(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true,
  // check_struct_version=true
  auto fory = BuildFory(false, true, true);
  EnsureOk(fory.register_struct<OneStringFieldStruct>(200),
           "register OneStringFieldStruct");

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<OneStringFieldStruct>(fory, buffer);

  OneStringFieldStruct expected;
  expected.f1 = std::string("hello");
  if (!(value == expected)) {
    Fail("OneStringFieldStruct schema mismatch: got f1=" +
         value.f1.value_or("null"));
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestOneStringFieldCompatible(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true); // COMPATIBLE mode
  EnsureOk(fory.register_struct<OneStringFieldStruct>(200),
           "register OneStringFieldStruct");

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<OneStringFieldStruct>(fory, buffer);

  OneStringFieldStruct expected;
  expected.f1 = std::string("hello");
  if (!(value == expected)) {
    Fail("OneStringFieldStruct compatible mismatch: got f1=" +
         value.f1.value_or("null"));
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestTwoStringFieldCompatible(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true); // COMPATIBLE mode
  EnsureOk(fory.register_struct<TwoStringFieldStruct>(201),
           "register TwoStringFieldStruct");

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<TwoStringFieldStruct>(fory, buffer);

  TwoStringFieldStruct expected;
  expected.f1 = std::string("first");
  expected.f2 = std::string("second");
  if (!(value == expected)) {
    Fail("TwoStringFieldStruct compatible mismatch: got f1=" + value.f1 +
         ", f2=" + value.f2);
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestSchemaEvolutionCompatible(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // Read TwoStringFieldStruct data as EmptyStructEvolution
  auto fory = BuildFory(true, true); // COMPATIBLE mode
  EnsureOk(fory.register_struct<EmptyStructEvolution>(200),
           "register EmptyStructEvolution");

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<EmptyStructEvolution>(fory, buffer);

  // Serialize back as EmptyStructEvolution
  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestSchemaEvolutionCompatibleReverse(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // Read OneStringFieldStruct data as TwoStringFieldStruct (missing f2)
  auto fory = BuildFory(true, true); // COMPATIBLE mode
  EnsureOk(fory.register_struct<TwoStringFieldStruct>(200),
           "register TwoStringFieldStruct");

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<TwoStringFieldStruct>(fory, buffer);

  // f1 should be "only_one", f2 should be empty (default value)
  if (value.f1 != "only_one") {
    Fail("Schema evolution reverse mismatch: expected f1='only_one', got f1=" +
         value.f1);
  }
  // f2 should be empty (not present in source data, default initialized)
  if (!value.f2.empty()) {
    Fail("Schema evolution reverse mismatch: expected f2='', got f2=" +
         value.f2);
  }

  // Serialize back
  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

// ============================================================================
// Schema Evolution Tests - Enum Fields
// ============================================================================

void RunTestOneEnumFieldSchema(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true,
  // check_struct_version=true
  auto fory = BuildFory(false, true, true);
  EnsureOk(fory.register_enum<TestEnum>(210), "register TestEnum");
  EnsureOk(fory.register_struct<OneEnumFieldStruct>(211),
           "register OneEnumFieldStruct");

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<OneEnumFieldStruct>(fory, buffer);

  if (value.f1 != TestEnum::VALUE_B) {
    Fail("OneEnumFieldStruct schema mismatch: expected VALUE_B, got " +
         std::to_string(static_cast<int32_t>(value.f1)));
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestOneEnumFieldCompatible(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true); // COMPATIBLE mode
  EnsureOk(fory.register_enum<TestEnum>(210), "register TestEnum");
  EnsureOk(fory.register_struct<OneEnumFieldStruct>(211),
           "register OneEnumFieldStruct");

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<OneEnumFieldStruct>(fory, buffer);

  if (value.f1 != TestEnum::VALUE_A) {
    Fail("OneEnumFieldStruct compatible mismatch: expected VALUE_A, got " +
         std::to_string(static_cast<int32_t>(value.f1)));
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestTwoEnumFieldCompatible(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true); // COMPATIBLE mode
  EnsureOk(fory.register_enum<TestEnum>(210), "register TestEnum");
  EnsureOk(fory.register_struct<TwoEnumFieldStruct>(212),
           "register TwoEnumFieldStruct");

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<TwoEnumFieldStruct>(fory, buffer);

  if (value.f1 != TestEnum::VALUE_A) {
    Fail("TwoEnumFieldStruct compatible mismatch: expected f1=VALUE_A, got " +
         std::to_string(static_cast<int32_t>(value.f1)));
  }
  if (value.f2 != TestEnum::VALUE_C) {
    Fail("TwoEnumFieldStruct compatible mismatch: expected f2=VALUE_C, got " +
         std::to_string(static_cast<int32_t>(value.f2)));
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestEnumSchemaEvolutionCompatible(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // Read TwoEnumFieldStruct data as EmptyStructEvolution
  auto fory = BuildFory(true, true); // COMPATIBLE mode
  EnsureOk(fory.register_enum<TestEnum>(210), "register TestEnum");
  EnsureOk(fory.register_struct<EmptyStructEvolution>(211),
           "register EmptyStructEvolution");

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<EmptyStructEvolution>(fory, buffer);

  // Serialize back as EmptyStructEvolution
  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestEnumSchemaEvolutionCompatibleReverse(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // Read OneEnumFieldStruct data as TwoEnumFieldStruct (missing f2)
  auto fory = BuildFory(true, true); // COMPATIBLE mode
  EnsureOk(fory.register_enum<TestEnum>(210), "register TestEnum");
  EnsureOk(fory.register_struct<TwoEnumFieldStruct>(211),
           "register TwoEnumFieldStruct");

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<TwoEnumFieldStruct>(fory, buffer);

  // f1 should be VALUE_C
  if (value.f1 != TestEnum::VALUE_C) {
    Fail("Enum schema evolution reverse mismatch: expected f1=VALUE_C, got " +
         std::to_string(static_cast<int32_t>(value.f1)));
  }
  // f2 should be default (VALUE_A = 0, not present in source data)
  if (value.f2 != TestEnum::VALUE_A) {
    Fail("Enum schema evolution reverse mismatch: expected f2=VALUE_A "
         "(default), got " +
         std::to_string(static_cast<int32_t>(value.f2)));
  }

  // Serialize back
  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

// ============================================================================
// Nullable Field Tests - Comprehensive versions
// ============================================================================

void RunTestNullableFieldSchemaConsistentNotNull(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true,
  // check_struct_version=true
  auto fory = BuildFory(false, true, true);
  EnsureOk(fory.register_struct<NullableComprehensiveSchemaConsistent>(401),
           "register NullableComprehensiveSchemaConsistent");

  // Debug: Print sorted field order
  {
    const char *debug_env = std::getenv("ENABLE_FORY_DEBUG_OUTPUT");
    if (debug_env && std::string(debug_env) == "1") {
      using Helpers = fory::serialization::detail::CompileTimeFieldHelpers<
          NullableComprehensiveSchemaConsistent>;
      std::cerr << "[C++][fory-debug] NullableComprehensiveSchemaConsistent "
                   "sorted field order:\n";
      for (size_t i = 0; i < Helpers::FieldCount; ++i) {
        size_t orig_idx = Helpers::sorted_indices[i];
        std::cerr << "  [" << i << "] orig_idx=" << orig_idx
                  << " name=" << Helpers::sorted_field_names[i]
                  << " type_id=" << Helpers::type_ids[orig_idx]
                  << " nullable=" << Helpers::nullable_flags[orig_idx]
                  << " group=" << Helpers::group_rank(orig_idx) << "\n";
      }
      std::cerr << std::endl;
    }
  }

  NullableComprehensiveSchemaConsistent expected;
  // Base non-nullable primitive fields
  expected.byte_field = 1;
  expected.short_field = 2;
  expected.int_field = 42;
  expected.long_field = 123456789;
  expected.float_field = 1.5f;
  expected.double_field = 2.5;
  expected.bool_field = true;

  // Base non-nullable reference fields
  expected.string_field = std::string("hello");
  expected.list_field = {std::string("a"), std::string("b"), std::string("c")};
  expected.set_field = {std::string("x"), std::string("y")};
  expected.map_field = {{std::string("key1"), std::string("value1")},
                        {std::string("key2"), std::string("value2")}};

  // Nullable fields - all have values (first half - boxed)
  expected.nullable_int = 100;
  expected.nullable_long = 200;
  expected.nullable_float = 1.5f;

  // Nullable fields - all have values (second half - @ForyField)
  expected.nullable_double = 2.5;
  expected.nullable_bool = false;
  expected.nullable_string = std::string("nullable_value");
  expected.nullable_list =
      std::vector<std::string>{std::string("p"), std::string("q")};
  expected.nullable_set =
      std::set<std::string>{std::string("m"), std::string("n")};
  expected.nullable_map = std::map<std::string, std::string>{
      {std::string("nk1"), std::string("nv1")}};

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<NullableComprehensiveSchemaConsistent>(fory, buffer);
  if (!(value == expected)) {
    Fail("NullableComprehensiveSchemaConsistent not null mismatch");
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestNullableFieldSchemaConsistentNull(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true,
  // check_struct_version=true
  auto fory = BuildFory(false, true, true);
  EnsureOk(fory.register_struct<NullableComprehensiveSchemaConsistent>(401),
           "register NullableComprehensiveSchemaConsistent");

  NullableComprehensiveSchemaConsistent expected;
  // Base non-nullable primitive fields - must have values
  expected.byte_field = 1;
  expected.short_field = 2;
  expected.int_field = 42;
  expected.long_field = 123456789;
  expected.float_field = 1.5f;
  expected.double_field = 2.5;
  expected.bool_field = true;

  // Base non-nullable reference fields - must have values
  expected.string_field = std::string("hello");
  expected.list_field = {std::string("a"), std::string("b"), std::string("c")};
  expected.set_field = {std::string("x"), std::string("y")};
  expected.map_field = {{std::string("key1"), std::string("value1")},
                        {std::string("key2"), std::string("value2")}};

  // Nullable fields - all null (first half - boxed)
  expected.nullable_int = std::nullopt;
  expected.nullable_long = std::nullopt;
  expected.nullable_float = std::nullopt;

  // Nullable fields - all null (second half - @ForyField)
  expected.nullable_double = std::nullopt;
  expected.nullable_bool = std::nullopt;
  expected.nullable_string = std::nullopt;
  expected.nullable_list = std::nullopt;
  expected.nullable_set = std::nullopt;
  expected.nullable_map = std::nullopt;

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<NullableComprehensiveSchemaConsistent>(fory, buffer);
  if (!(value == expected)) {
    Fail("NullableComprehensiveSchemaConsistent null mismatch");
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestNullableFieldCompatibleNotNull(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true); // COMPATIBLE mode
  EnsureOk(fory.register_struct<NullableComprehensiveCompatible>(402),
           "register NullableComprehensiveCompatible");

  NullableComprehensiveCompatible expected;
  // Base non-nullable primitive fields
  expected.byte_field = 1;
  expected.short_field = 2;
  expected.int_field = 42;
  expected.long_field = 123456789;
  expected.float_field = 1.5f;
  expected.double_field = 2.5;
  expected.bool_field = true;

  // Base non-nullable boxed fields
  expected.boxed_int = 10;
  expected.boxed_long = 20;
  expected.boxed_float = 1.1f;
  expected.boxed_double = 2.2;
  expected.boxed_bool = true;

  // Base non-nullable reference fields
  expected.string_field = std::string("hello");
  expected.list_field = {std::string("a"), std::string("b"), std::string("c")};
  expected.set_field = {std::string("x"), std::string("y")};
  expected.map_field = {{std::string("key1"), std::string("value1")},
                        {std::string("key2"), std::string("value2")}};

  // Nullable group 1 - all have values
  expected.nullable_int1 = 100;
  expected.nullable_long1 = 200;
  expected.nullable_float1 = 1.5f;
  expected.nullable_double1 = 2.5;
  expected.nullable_bool1 = false;

  // Nullable group 2 - all have values
  expected.nullable_string2 = std::string("nullable_value");
  expected.nullable_list2 =
      std::vector<std::string>{std::string("p"), std::string("q")};
  expected.nullable_set2 =
      std::set<std::string>{std::string("m"), std::string("n")};
  expected.nullable_map2 = std::map<std::string, std::string>{
      {std::string("nk1"), std::string("nv1")}};

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<NullableComprehensiveCompatible>(fory, buffer);
  if (!(value == expected)) {
    Fail("NullableComprehensiveCompatible not null mismatch");
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

void RunTestNullableFieldCompatibleNull(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  auto fory = BuildFory(true, true); // COMPATIBLE mode
  EnsureOk(fory.register_struct<NullableComprehensiveCompatible>(402),
           "register NullableComprehensiveCompatible");

  NullableComprehensiveCompatible expected;
  // Base non-nullable primitive fields - must have values
  expected.byte_field = 1;
  expected.short_field = 2;
  expected.int_field = 42;
  expected.long_field = 123456789;
  expected.float_field = 1.5f;
  expected.double_field = 2.5;
  expected.bool_field = true;

  // Base non-nullable boxed fields - must have values
  expected.boxed_int = 10;
  expected.boxed_long = 20;
  expected.boxed_float = 1.1f;
  expected.boxed_double = 2.2;
  expected.boxed_bool = true;

  // Base non-nullable reference fields - must have values
  expected.string_field = std::string("hello");
  expected.list_field = {std::string("a"), std::string("b"), std::string("c")};
  expected.set_field = {std::string("x"), std::string("y")};
  expected.map_field = {{std::string("key1"), std::string("value1")},
                        {std::string("key2"), std::string("value2")}};

  // Nullable group 1 - all null
  expected.nullable_int1 = std::nullopt;
  expected.nullable_long1 = std::nullopt;
  expected.nullable_float1 = std::nullopt;
  expected.nullable_double1 = std::nullopt;
  expected.nullable_bool1 = std::nullopt;

  // Nullable group 2 - all null
  expected.nullable_string2 = std::nullopt;
  expected.nullable_list2 = std::nullopt;
  expected.nullable_set2 = std::nullopt;
  expected.nullable_map2 = std::nullopt;

  Buffer buffer = MakeBuffer(bytes);
  auto value = ReadNext<NullableComprehensiveCompatible>(fory, buffer);
  if (!(value == expected)) {
    Fail("NullableComprehensiveCompatible null mismatch");
  }

  std::vector<uint8_t> out;
  AppendSerialized(fory, value, out);
  WriteFile(data_file, out);
}

// ============================================================================
// Reference Tracking Tests - Cross-language shared reference tests
// ============================================================================

void RunTestRefSchemaConsistent(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true,
  // check_struct_version=true, track_ref=true
  auto fory = BuildFory(false, true, true, true);
  EnsureOk(fory.register_struct<RefInnerSchemaConsistent>(501),
           "register RefInnerSchemaConsistent");
  EnsureOk(fory.register_struct<RefOuterSchemaConsistent>(502),
           "register RefOuterSchemaConsistent");

  Buffer buffer = MakeBuffer(bytes);
  auto outer = ReadNext<RefOuterSchemaConsistent>(fory, buffer);

  // Both inner1 and inner2 should have values
  if (outer.inner1 == nullptr) {
    Fail("RefOuterSchemaConsistent: inner1 should not be null");
  }
  if (outer.inner2 == nullptr) {
    Fail("RefOuterSchemaConsistent: inner2 should not be null");
  }

  // Both should have the same values (they reference the same object in Java)
  if (outer.inner1->id != 42) {
    Fail("RefOuterSchemaConsistent: inner1.id should be 42, got " +
         std::to_string(outer.inner1->id));
  }
  if (outer.inner1->name != "shared_inner") {
    Fail(
        "RefOuterSchemaConsistent: inner1.name should be 'shared_inner', got " +
        outer.inner1->name);
  }
  if (*outer.inner1 != *outer.inner2) {
    Fail("RefOuterSchemaConsistent: inner1 and inner2 should be equal (same "
         "reference)");
  }

  // In C++, shared_ptr may or may not point to the same object after
  // deserialization, depending on reference tracking implementation.
  // The key test is that both have equal values.

  // Re-serialize and write back
  std::vector<uint8_t> out;
  AppendSerialized(fory, outer, out);
  WriteFile(data_file, out);
}

void RunTestRefCompatible(const std::string &data_file) {
  auto bytes = ReadFile(data_file);
  // COMPATIBLE mode: compatible=true, xlang=true, check_struct_version=false,
  // track_ref=true
  auto fory = BuildFory(true, true, false, true);
  EnsureOk(fory.register_struct<RefInnerCompatible>(503),
           "register RefInnerCompatible");
  EnsureOk(fory.register_struct<RefOuterCompatible>(504),
           "register RefOuterCompatible");

  Buffer buffer = MakeBuffer(bytes);
  auto outer = ReadNext<RefOuterCompatible>(fory, buffer);

  // Both inner1 and inner2 should have values
  if (outer.inner1 == nullptr) {
    Fail("RefOuterCompatible: inner1 should not be null");
  }
  if (outer.inner2 == nullptr) {
    Fail("RefOuterCompatible: inner2 should not be null");
  }

  // Both should have the same values (they reference the same object in Java)
  if (outer.inner1->id != 99) {
    Fail("RefOuterCompatible: inner1.id should be 99, got " +
         std::to_string(outer.inner1->id));
  }
  if (outer.inner1->name != "compatible_shared") {
    Fail("RefOuterCompatible: inner1.name should be 'compatible_shared', got " +
         outer.inner1->name);
  }
  if (*outer.inner1 != *outer.inner2) {
    Fail("RefOuterCompatible: inner1 and inner2 should be equal (same "
         "reference)");
  }

  // Re-serialize and write back
  std::vector<uint8_t> out;
  AppendSerialized(fory, outer, out);
  WriteFile(data_file, out);
}

} // namespace
