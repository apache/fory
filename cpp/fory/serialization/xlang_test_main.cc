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
#include <iomanip>
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
using ::fory::serialization::Date;
using ::fory::serialization::Fory;
using ::fory::serialization::ForyBuilder;
using ::fory::serialization::Serializer;
using ::fory::serialization::Timestamp;

[[noreturn]] void fail(const std::string &message) {
  throw std::runtime_error(message);
}

std::string get_data_file_path() {
  const char *env = std::getenv("DATA_FILE");
  if (env == nullptr) {
    fail("DATA_FILE environment variable is not set");
  }
  return std::string(env);
}

thread_local std::string g_current_case;

void maybe_dump_input(const std::vector<uint8_t> &data) {
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

std::vector<uint8_t> read_file(const std::string &path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    fail("Failed to open file for reading: " + path);
  }
  std::vector<uint8_t> data((std::istreambuf_iterator<char>(input)),
                            std::istreambuf_iterator<char>());
  maybe_dump_input(data);
  return data;
}

void write_file(const std::string &path, const std::vector<uint8_t> &data) {
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  if (!output) {
    fail("Failed to open file for writing: " + path);
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
  FORY_STRUCT(Item, name);
};

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
  FORY_STRUCT(SimpleStruct, f1, f2, f3, f4, f5, f6, f7, f8, last);
};

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
  FORY_STRUCT(Item1, f1, f2, f3, f4, f5, f6);
};

struct MyStruct {
  int32_t id;
  MyStruct() = default;
  explicit MyStruct(int32_t v) : id(v) {}
  bool operator==(const MyStruct &other) const { return id == other.id; }
  FORY_STRUCT(MyStruct, id);
};

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
  FORY_STRUCT(MyWrapper, color, my_struct, my_ext);
};

struct EmptyWrapper {
  bool operator==(const EmptyWrapper &other) const {
    (void)other;
    return true;
  }
  FORY_STRUCT(EmptyWrapper);
};

struct VersionCheckStruct {
  int32_t f1;
  std::optional<std::string> f2;
  double f3;
  bool operator==(const VersionCheckStruct &other) const {
    return f1 == other.f1 && f2 == other.f2 && f3 == other.f3;
  }
  FORY_STRUCT(VersionCheckStruct, f1, f2, f3);
};

struct StructWithList {
  std::vector<std::string> items;
  bool operator==(const StructWithList &other) const {
    return items == other.items;
  }
  FORY_STRUCT(StructWithList, items);
};

struct StructWithMap {
  std::map<std::string, std::string> data;
  bool operator==(const StructWithMap &other) const {
    return data == other.data;
  }
  FORY_STRUCT(StructWithMap, data);
};

// ============================================================================
// Polymorphic Container Test Types - Using virtual base class
// ============================================================================

struct Animal {
  virtual ~Animal() = default;
  virtual std::string speak() const = 0;
  int32_t age = 0;
  FORY_STRUCT(Animal, age);
};

struct Dog : Animal {
  std::string speak() const override { return "Woof"; }
  std::optional<std::string> name;
  FORY_STRUCT(Dog, FORY_BASE(Animal), name);
};

struct Cat : Animal {
  std::string speak() const override { return "Meow"; }
  int32_t lives = 9;
  FORY_STRUCT(Cat, FORY_BASE(Animal), lives);
};

struct AnimalListHolder {
  std::vector<std::shared_ptr<Animal>> animals;
  FORY_STRUCT(AnimalListHolder, animals);
};

struct AnimalMapHolder {
  std::map<std::string, std::shared_ptr<Animal>> animal_map;
  FORY_STRUCT(AnimalMapHolder, animal_map);
};

// ============================================================================
// Schema Evolution Test Types
// ============================================================================

struct EmptyStructEvolution {
  bool placeholder = false; // C++ templates require at least one field
  bool operator==(const EmptyStructEvolution &other) const {
    return placeholder == other.placeholder;
  }
  FORY_STRUCT(EmptyStructEvolution, placeholder);
};

struct OneStringFieldStruct {
  std::optional<std::string> f1;
  bool operator==(const OneStringFieldStruct &other) const {
    return f1 == other.f1;
  }
  FORY_STRUCT(OneStringFieldStruct, f1);
};

struct TwoStringFieldStruct {
  std::string f1;
  std::string f2;
  bool operator==(const TwoStringFieldStruct &other) const {
    return f1 == other.f1 && f2 == other.f2;
  }
  FORY_STRUCT(TwoStringFieldStruct, f1, f2);
};

enum class TestEnum : int32_t { VALUE_A = 0, VALUE_B = 1, VALUE_C = 2 };
FORY_ENUM(TestEnum, VALUE_A, VALUE_B, VALUE_C);

struct OneEnumFieldStruct {
  TestEnum f1;
  bool operator==(const OneEnumFieldStruct &other) const {
    return f1 == other.f1;
  }
  FORY_STRUCT(OneEnumFieldStruct, f1);
};

struct TwoEnumFieldStruct {
  TestEnum f1;
  TestEnum f2;
  bool operator==(const TwoEnumFieldStruct &other) const {
    return f1 == other.f1 && f2 == other.f2;
  }
  FORY_STRUCT(TwoEnumFieldStruct, f1, f2);
};

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

  // Nullable fields - second half using @fory_field annotation
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

  FORY_STRUCT(NullableComprehensiveSchemaConsistent, byte_field, short_field,
              int_field, long_field, float_field, double_field, bool_field,
              string_field, list_field, set_field, map_field, nullable_int,
              nullable_long, nullable_float, nullable_double, nullable_bool,
              nullable_string, nullable_list, nullable_set, nullable_map);

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

  // Nullable group 1 - boxed types with @fory_field(nullable=true)
  std::optional<int32_t> nullable_int1;
  std::optional<int64_t> nullable_long1;
  std::optional<float> nullable_float1;
  std::optional<double> nullable_double1;
  std::optional<bool> nullable_bool1;

  // Nullable group 2 - reference types with @fory_field(nullable=true)
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

  FORY_STRUCT(NullableComprehensiveCompatible, byte_field, short_field,
              int_field, long_field, float_field, double_field, bool_field,
              boxed_int, boxed_long, boxed_float, boxed_double, boxed_bool,
              string_field, list_field, set_field, map_field, nullable_int1,
              nullable_long1, nullable_float1, nullable_double1, nullable_bool1,
              nullable_string2, nullable_list2, nullable_set2, nullable_map2);

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
  FORY_STRUCT(RefInnerSchemaConsistent, id, name);
};

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
  FORY_STRUCT(RefOuterSchemaConsistent, inner1, inner2);
};

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
  FORY_STRUCT(RefInnerCompatible, id, name);
};

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
  FORY_STRUCT(RefOuterCompatible, inner1, inner2);
};

// Element struct for collection element ref override test
// Matches Java RefOverrideElement with type ID 701
struct RefOverrideElement {
  int32_t id;
  std::string name;
  bool operator==(const RefOverrideElement &other) const {
    return id == other.id && name == other.name;
  }
  FORY_STRUCT(RefOverrideElement, id, name);
};

// Container struct for collection element ref override test
// Matches Java RefOverrideContainer with type ID 702
struct RefOverrideContainer {
  std::vector<std::shared_ptr<RefOverrideElement>> list_field;
  std::map<std::string, std::shared_ptr<RefOverrideElement>> map_field;
  bool operator==(const RefOverrideContainer &other) const {
    return list_field == other.list_field && map_field == other.map_field;
  }
  FORY_STRUCT(RefOverrideContainer, list_field, map_field);
};

// ============================================================================
// Circular Reference Test Types - Self-referencing struct tests
// ============================================================================

// Struct for circular reference tests.
// Contains a self-referencing field and a string field.
// The 'self_ref' field points back to the same object, creating a circular
// reference. Note: Using 'self_ref' instead of 'self' because 'self' is a
// reserved keyword in Rust.
// Matches Java CircularRefStruct with type ID 601 (schema consistent)
// and 602 (compatible)
struct CircularRefStruct {
  std::string name;
  std::shared_ptr<CircularRefStruct> self_ref;

  bool operator==(const CircularRefStruct &other) const {
    if (name != other.name)
      return false;
    // Compare self_ref by checking if both are null or both point to same
    // object (for circular refs, we just check if both have values)
    bool self_eq = (self_ref == nullptr && other.self_ref == nullptr) ||
                   (self_ref != nullptr && other.self_ref != nullptr);
    return self_eq;
  }
  FORY_STRUCT(CircularRefStruct, name, self_ref);
};

// ============================================================================
// Unsigned Number Test Types
// ============================================================================

// UnsignedSchemaConsistentSimple (type id 1)
// A simple test struct for unsigned numbers with tagged encoding.
struct UnsignedSchemaConsistentSimple {
  uint64_t u64_tagged;                         // TAGGED_UINT64
  std::optional<uint64_t> u64_tagged_nullable; // TAGGED_UINT64, nullable

  bool operator==(const UnsignedSchemaConsistentSimple &other) const {
    return u64_tagged == other.u64_tagged &&
           u64_tagged_nullable == other.u64_tagged_nullable;
  }
  FORY_STRUCT(UnsignedSchemaConsistentSimple, u64_tagged, u64_tagged_nullable);
};
FORY_FIELD_CONFIG(UnsignedSchemaConsistentSimple,
                  (u64_tagged, fory::F().tagged()),
                  (u64_tagged_nullable, fory::F().nullable().tagged()));

// UnsignedSchemaConsistent (type id 501)
// Test struct for unsigned numbers in SCHEMA_CONSISTENT mode.
// All fields use the same nullability as Java.
// Note: C++ uses std::optional for nullable fields.
struct UnsignedSchemaConsistent {
  // Primitive unsigned fields (non-nullable)
  uint8_t u8_field;
  uint16_t u16_field;
  uint32_t u32_var_field;    // VAR_UINT32 - variable-length
  uint32_t u32_fixed_field;  // UINT32 - fixed 4-byte
  uint64_t u64_var_field;    // VAR_UINT64 - variable-length
  uint64_t u64_fixed_field;  // UINT64 - fixed 8-byte
  uint64_t u64_tagged_field; // TAGGED_UINT64

  // Nullable unsigned fields (using std::optional)
  std::optional<uint8_t> u8_nullable_field;
  std::optional<uint16_t> u16_nullable_field;
  std::optional<uint32_t> u32_var_nullable_field;
  std::optional<uint32_t> u32_fixed_nullable_field;
  std::optional<uint64_t> u64_var_nullable_field;
  std::optional<uint64_t> u64_fixed_nullable_field;
  std::optional<uint64_t> u64_tagged_nullable_field;

  bool operator==(const UnsignedSchemaConsistent &other) const {
    return u8_field == other.u8_field && u16_field == other.u16_field &&
           u32_var_field == other.u32_var_field &&
           u32_fixed_field == other.u32_fixed_field &&
           u64_var_field == other.u64_var_field &&
           u64_fixed_field == other.u64_fixed_field &&
           u64_tagged_field == other.u64_tagged_field &&
           u8_nullable_field == other.u8_nullable_field &&
           u16_nullable_field == other.u16_nullable_field &&
           u32_var_nullable_field == other.u32_var_nullable_field &&
           u32_fixed_nullable_field == other.u32_fixed_nullable_field &&
           u64_var_nullable_field == other.u64_var_nullable_field &&
           u64_fixed_nullable_field == other.u64_fixed_nullable_field &&
           u64_tagged_nullable_field == other.u64_tagged_nullable_field;
  }
  FORY_STRUCT(UnsignedSchemaConsistent, u8_field, u16_field, u32_var_field,
              u32_fixed_field, u64_var_field, u64_fixed_field, u64_tagged_field,
              u8_nullable_field, u16_nullable_field, u32_var_nullable_field,
              u32_fixed_nullable_field, u64_var_nullable_field,
              u64_fixed_nullable_field, u64_tagged_nullable_field);
};
// Use new FORY_FIELD_CONFIG with builder pattern for encoding specification
FORY_FIELD_CONFIG(UnsignedSchemaConsistent, (u8_field, fory::F()),
                  (u16_field, fory::F()), (u32_var_field, fory::F().varint()),
                  (u32_fixed_field, fory::F().fixed()),
                  (u64_var_field, fory::F().varint()),
                  (u64_fixed_field, fory::F().fixed()),
                  (u64_tagged_field, fory::F().tagged()),
                  (u8_nullable_field, fory::F().nullable()),
                  (u16_nullable_field, fory::F().nullable()),
                  (u32_var_nullable_field, fory::F().nullable().varint()),
                  (u32_fixed_nullable_field, fory::F().nullable().fixed()),
                  (u64_var_nullable_field, fory::F().nullable().varint()),
                  (u64_fixed_nullable_field, fory::F().nullable().fixed()),
                  (u64_tagged_nullable_field, fory::F().nullable().tagged()));

// UnsignedSchemaCompatible (type id 502)
// Test struct for unsigned numbers in COMPATIBLE mode.
// Group 1: std::optional types (nullable in C++, non-nullable in Java)
// Group 2: Non-optional types with Field2 suffix (non-nullable in C++, nullable
// in Java)
struct UnsignedSchemaCompatible {
  // Group 1: Nullable in C++ (std::optional), non-nullable in Java
  std::optional<uint8_t> u8_field1;
  std::optional<uint16_t> u16_field1;
  std::optional<uint32_t> u32_var_field1;
  std::optional<uint32_t> u32_fixed_field1;
  std::optional<uint64_t> u64_var_field1;
  std::optional<uint64_t> u64_fixed_field1;
  std::optional<uint64_t> u64_tagged_field1;

  // Group 2: Non-nullable in C++, nullable in Java
  uint8_t u8_field2;
  uint16_t u16_field2;
  uint32_t u32_var_field2;
  uint32_t u32_fixed_field2;
  uint64_t u64_var_field2;
  uint64_t u64_fixed_field2;
  uint64_t u64_tagged_field2;

  bool operator==(const UnsignedSchemaCompatible &other) const {
    return u8_field1 == other.u8_field1 && u16_field1 == other.u16_field1 &&
           u32_var_field1 == other.u32_var_field1 &&
           u32_fixed_field1 == other.u32_fixed_field1 &&
           u64_var_field1 == other.u64_var_field1 &&
           u64_fixed_field1 == other.u64_fixed_field1 &&
           u64_tagged_field1 == other.u64_tagged_field1 &&
           u8_field2 == other.u8_field2 && u16_field2 == other.u16_field2 &&
           u32_var_field2 == other.u32_var_field2 &&
           u32_fixed_field2 == other.u32_fixed_field2 &&
           u64_var_field2 == other.u64_var_field2 &&
           u64_fixed_field2 == other.u64_fixed_field2 &&
           u64_tagged_field2 == other.u64_tagged_field2;
  }
  FORY_STRUCT(UnsignedSchemaCompatible, u8_field1, u16_field1, u32_var_field1,
              u32_fixed_field1, u64_var_field1, u64_fixed_field1,
              u64_tagged_field1, u8_field2, u16_field2, u32_var_field2,
              u32_fixed_field2, u64_var_field2, u64_fixed_field2,
              u64_tagged_field2);
};
// Use new FORY_FIELD_CONFIG with builder pattern for encoding specification
// Group 1: nullable in C++ (std::optional), non-nullable in Java
// Group 2: non-nullable in C++, nullable in Java
FORY_FIELD_CONFIG(UnsignedSchemaCompatible, (u8_field1, fory::F().nullable()),
                  (u16_field1, fory::F().nullable()),
                  (u32_var_field1, fory::F().nullable().varint()),
                  (u32_fixed_field1, fory::F().nullable().fixed()),
                  (u64_var_field1, fory::F().nullable().varint()),
                  (u64_fixed_field1, fory::F().nullable().fixed()),
                  (u64_tagged_field1, fory::F().nullable().tagged()),
                  (u8_field2, fory::F()), (u16_field2, fory::F()),
                  (u32_var_field2, fory::F().varint()),
                  (u32_fixed_field2, fory::F().fixed()),
                  (u64_var_field2, fory::F().varint()),
                  (u64_fixed_field2, fory::F().fixed()),
                  (u64_tagged_field2, fory::F().tagged()));

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
      auto type_info_res =
          ctx.type_resolver().get_type_info(std::type_index(typeid(MyExt)));
      if (!type_info_res.ok()) {
        ctx.set_error(std::move(type_info_res).error());
        return;
      }
      auto result = ctx.write_any_type_info(type_info_res.value()->type_id,
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
      const TypeInfo *type_info = ctx.read_any_type_info(ctx.error());
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
T unwrap(Result<T, Error> result, const std::string &context) {
  if (!result.ok()) {
    fail(context + ": " + result.error().message());
  }
  return std::move(result).value();
}

void ensure_ok(Result<void, Error> result, const std::string &context) {
  if (!result.ok()) {
    fail(context + ": " + result.error().message());
  }
}

Buffer make_buffer(std::vector<uint8_t> &bytes) {
  Buffer buffer(bytes.data(), static_cast<uint32_t>(bytes.size()), false);
  buffer.writer_index(static_cast<uint32_t>(bytes.size()));
  buffer.reader_index(0);
  return buffer;
}

template <typename T> T read_next(Fory &fory, Buffer &buffer) {
  // Use Buffer-based deserialize API which updates buffer's reader_index
  auto result = fory.deserialize<T>(buffer);
  if (!result.ok()) {
    fail("Failed to deserialize value: " + result.error().message());
  }
  return std::move(result).value();
}

template <typename T>
void append_serialized(Fory &fory, const T &value, std::vector<uint8_t> &out) {
  auto result = fory.serialize(value);
  if (!result.ok()) {
    fail("Failed to serialize value: " + result.error().message());
  }
  const auto &bytes = result.value();
  out.insert(out.end(), bytes.begin(), bytes.end());
}

Fory build_fory(bool compatible = true, bool xlang = true,
                bool check_struct_version = false, bool track_ref = false) {
  // In Java xlang mode, check_class_version is automatically set to true for
  // SCHEMA_CONSISTENT mode (compatible=false). Match this behavior in C++.
  bool actual_check_version = check_struct_version;
  if (xlang && !compatible) {
    actual_check_version = true;
  }
  return Fory::builder()
      .compatible(compatible)
      .xlang(xlang)
      .check_struct_version(actual_check_version)
      .track_ref(track_ref)
      .build();
}

void register_basic_structs(Fory &fory) {
  ensure_ok(fory.register_enum<Color>(101), "register Color");
  ensure_ok(fory.register_struct<Item>(102), "register Item");
  ensure_ok(fory.register_struct<SimpleStruct>(103), "register SimpleStruct");
}

// Forward declarations for test handlers
namespace {
void run_test_buffer(const std::string &data_file);
void run_test_buffer_var(const std::string &data_file);
void run_test_murmur_hash3(const std::string &data_file);
void run_test_string_serializer(const std::string &data_file);
void run_test_cross_language_serializer(const std::string &data_file);
void run_test_simple_struct(const std::string &data_file);
void run_test_simple_named_struct(const std::string &data_file);
void run_test_list(const std::string &data_file);
void run_test_map(const std::string &data_file);
void run_test_integer(const std::string &data_file);
void run_test_item(const std::string &data_file);
void run_test_color(const std::string &data_file);
void run_test_struct_with_list(const std::string &data_file);
void run_test_struct_with_map(const std::string &data_file);
void run_test_skip_id_custom(const std::string &data_file);
void run_test_skip_name_custom(const std::string &data_file);
void run_test_consistent_named(const std::string &data_file);
void run_test_struct_version_check(const std::string &data_file);
void run_test_polymorphic_list(const std::string &data_file);
void run_test_polymorphic_map(const std::string &data_file);
void run_test_one_string_field_schema(const std::string &data_file);
void run_test_one_string_field_compatible(const std::string &data_file);
void run_test_two_string_field_compatible(const std::string &data_file);
void run_test_schema_evolution_compatible(const std::string &data_file);
void run_test_schema_evolution_compatible_reverse(const std::string &data_file);
void run_test_one_enum_field_schema(const std::string &data_file);
void run_test_one_enum_field_compatible(const std::string &data_file);
void run_test_two_enum_field_compatible(const std::string &data_file);
void run_test_enum_schema_evolution_compatible(const std::string &data_file);
void run_test_enum_schema_evolution_compatible_reverse(
    const std::string &data_file);
void run_test_nullable_field_schema_consistent_not_null(
    const std::string &data_file);
void run_test_nullable_field_schema_consistent_null(
    const std::string &data_file);
void run_test_nullable_field_compatible_not_null(const std::string &data_file);
void run_test_nullable_field_compatible_null(const std::string &data_file);
void run_test_ref_schema_consistent(const std::string &data_file);
void run_test_ref_compatible(const std::string &data_file);
void run_test_collection_element_ref_override(const std::string &data_file);
void run_test_circular_ref_schema_consistent(const std::string &data_file);
void run_test_circular_ref_compatible(const std::string &data_file);
void run_test_unsigned_schema_consistent_simple(const std::string &data_file);
void run_test_unsigned_schema_consistent(const std::string &data_file);
void run_test_unsigned_schema_compatible(const std::string &data_file);
} // namespace

int main(int argc, char **argv) {
  if (argc < 2) {
    fail("Usage: xlang_test_main --case <test_name>");
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
    fail("Missing --case argument");
  }

  g_current_case = case_name;

  const std::string data_file = get_data_file_path();
  try {
    if (case_name == "test_buffer") {
      run_test_buffer(data_file);
    } else if (case_name == "test_buffer_var") {
      run_test_buffer_var(data_file);
    } else if (case_name == "test_murmurhash3") {
      run_test_murmur_hash3(data_file);
    } else if (case_name == "test_string_serializer") {
      run_test_string_serializer(data_file);
    } else if (case_name == "test_cross_language_serializer") {
      run_test_cross_language_serializer(data_file);
    } else if (case_name == "test_simple_struct") {
      run_test_simple_struct(data_file);
    } else if (case_name == "test_named_simple_struct") {
      run_test_simple_named_struct(data_file);
    } else if (case_name == "test_list") {
      run_test_list(data_file);
    } else if (case_name == "test_map") {
      run_test_map(data_file);
    } else if (case_name == "test_integer") {
      run_test_integer(data_file);
    } else if (case_name == "test_item") {
      run_test_item(data_file);
    } else if (case_name == "test_color") {
      run_test_color(data_file);
    } else if (case_name == "test_struct_with_list") {
      run_test_struct_with_list(data_file);
    } else if (case_name == "test_struct_with_map") {
      run_test_struct_with_map(data_file);
    } else if (case_name == "test_skip_id_custom") {
      run_test_skip_id_custom(data_file);
    } else if (case_name == "test_skip_name_custom") {
      run_test_skip_name_custom(data_file);
    } else if (case_name == "test_consistent_named") {
      run_test_consistent_named(data_file);
    } else if (case_name == "test_struct_version_check") {
      run_test_struct_version_check(data_file);
    } else if (case_name == "test_polymorphic_list") {
      run_test_polymorphic_list(data_file);
    } else if (case_name == "test_polymorphic_map") {
      run_test_polymorphic_map(data_file);
    } else if (case_name == "test_one_string_field_schema") {
      run_test_one_string_field_schema(data_file);
    } else if (case_name == "test_one_string_field_compatible") {
      run_test_one_string_field_compatible(data_file);
    } else if (case_name == "test_two_string_field_compatible") {
      run_test_two_string_field_compatible(data_file);
    } else if (case_name == "test_schema_evolution_compatible") {
      run_test_schema_evolution_compatible(data_file);
    } else if (case_name == "test_schema_evolution_compatible_reverse") {
      run_test_schema_evolution_compatible_reverse(data_file);
    } else if (case_name == "test_one_enum_field_schema") {
      run_test_one_enum_field_schema(data_file);
    } else if (case_name == "test_one_enum_field_compatible") {
      run_test_one_enum_field_compatible(data_file);
    } else if (case_name == "test_two_enum_field_compatible") {
      run_test_two_enum_field_compatible(data_file);
    } else if (case_name == "test_enum_schema_evolution_compatible") {
      run_test_enum_schema_evolution_compatible(data_file);
    } else if (case_name == "test_enum_schema_evolution_compatible_reverse") {
      run_test_enum_schema_evolution_compatible_reverse(data_file);
    } else if (case_name == "test_nullable_field_schema_consistent_not_null") {
      run_test_nullable_field_schema_consistent_not_null(data_file);
    } else if (case_name == "test_nullable_field_schema_consistent_null") {
      run_test_nullable_field_schema_consistent_null(data_file);
    } else if (case_name == "test_nullable_field_compatible_not_null") {
      run_test_nullable_field_compatible_not_null(data_file);
    } else if (case_name == "test_nullable_field_compatible_null") {
      run_test_nullable_field_compatible_null(data_file);
    } else if (case_name == "test_ref_schema_consistent") {
      run_test_ref_schema_consistent(data_file);
    } else if (case_name == "test_ref_compatible") {
      run_test_ref_compatible(data_file);
    } else if (case_name == "test_collection_element_ref_override") {
      run_test_collection_element_ref_override(data_file);
    } else if (case_name == "test_circular_ref_schema_consistent") {
      run_test_circular_ref_schema_consistent(data_file);
    } else if (case_name == "test_circular_ref_compatible") {
      run_test_circular_ref_compatible(data_file);
    } else if (case_name == "test_unsigned_schema_consistent_simple") {
      run_test_unsigned_schema_consistent_simple(data_file);
    } else if (case_name == "test_unsigned_schema_consistent") {
      run_test_unsigned_schema_consistent(data_file);
    } else if (case_name == "test_unsigned_schema_compatible") {
      run_test_unsigned_schema_compatible(data_file);
    } else {
      fail("Unknown test case: " + case_name);
    }
  } catch (const std::exception &ex) {
    std::cerr << "xlang_test_main failed: " << ex.what() << std::endl;
    return 1;
  }
  return 0;
}

namespace {

void run_test_buffer(const std::string &data_file) {
  auto bytes = read_file(data_file);
  Buffer buffer(bytes.data(), static_cast<uint32_t>(bytes.size()), false);

  Error error;
  uint8_t bool_val_raw = buffer.read_uint8(error);
  if (!error.ok())
    fail("Failed to read bool: " + error.message());
  bool bool_val = bool_val_raw != 0;

  int8_t int8_val = buffer.read_int8(error);
  if (!error.ok())
    fail("Failed to read int8: " + error.message());

  int16_t int16_val = buffer.read_int16(error);
  if (!error.ok())
    fail("Failed to read int16: " + error.message());

  int32_t int32_val = buffer.read_int32(error);
  if (!error.ok())
    fail("Failed to read int32: " + error.message());

  int64_t int64_val = buffer.read_int64(error);
  if (!error.ok())
    fail("Failed to read int64: " + error.message());

  float float_val = buffer.read_float(error);
  if (!error.ok())
    fail("Failed to read float: " + error.message());

  double double_val = buffer.read_double(error);
  if (!error.ok())
    fail("Failed to read double: " + error.message());

  uint32_t varint = buffer.read_var_uint32(error);
  if (!error.ok())
    fail("Failed to read varint: " + error.message());

  int32_t payload_len = buffer.read_int32(error);
  if (!error.ok())
    fail("Failed to read payload len: " + error.message());

  if (payload_len < 0 || buffer.reader_index() + payload_len > buffer.size()) {
    fail("Invalid payload length in buffer test");
  }
  std::vector<uint8_t> payload(bytes.begin() + buffer.reader_index(),
                               bytes.begin() + buffer.reader_index() +
                                   payload_len);
  buffer.skip(payload_len, error);
  if (!error.ok())
    fail("Failed to skip payload: " + error.message());

  if (!bool_val || int8_val != std::numeric_limits<int8_t>::max() ||
      int16_val != std::numeric_limits<int16_t>::max() ||
      int32_val != std::numeric_limits<int32_t>::max() ||
      int64_val != std::numeric_limits<int64_t>::max() ||
      std::abs(float_val + 1.1f) > 1e-6 || std::abs(double_val + 1.1) > 1e-9 ||
      varint != 100 || payload != std::vector<uint8_t>({'a', 'b'})) {
    fail("Buffer test validation failed");
  }

  Buffer out_buffer;
  out_buffer.write_uint8(1);
  out_buffer.write_int8(std::numeric_limits<int8_t>::max());
  out_buffer.write_int16(std::numeric_limits<int16_t>::max());
  out_buffer.write_int32(std::numeric_limits<int32_t>::max());
  out_buffer.write_int64(std::numeric_limits<int64_t>::max());
  out_buffer.write_float(-1.1f);
  out_buffer.write_double(-1.1);
  out_buffer.write_var_uint32(100);
  out_buffer.write_int32(static_cast<int32_t>(payload.size()));
  out_buffer.write_bytes(payload.data(), static_cast<uint32_t>(payload.size()));

  std::vector<uint8_t> out(out_buffer.data(),
                           out_buffer.data() + out_buffer.writer_index());
  write_file(data_file, out);
}

void run_test_buffer_var(const std::string &data_file) {
  auto bytes = read_file(data_file);
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
    int32_t result = buffer.read_var_int32(error);
    if (!error.ok() || result != value) {
      fail("VarInt32 mismatch");
    }
  }

  const std::vector<uint32_t> expected_varuint32 = {
      0u,       1u,       127u,       128u,       16383u,      16384u,
      2097151u, 2097152u, 268435455u, 268435456u, 2147483646u, 2147483647u};
  for (uint32_t value : expected_varuint32) {
    uint32_t result = buffer.read_var_uint32(error);
    if (!error.ok() || result != value) {
      fail("VarUint32 mismatch");
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
    uint64_t result = buffer.read_var_uint64(error);
    if (!error.ok() || result != value) {
      fail("VarUint64 mismatch");
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
    int64_t result = buffer.read_var_int64(error);
    if (!error.ok() || result != value) {
      fail("VarInt64 mismatch");
    }
  }

  Buffer out_buffer;
  for (int32_t value : expected_varint32) {
    out_buffer.write_var_int32(value);
  }
  for (uint32_t value : expected_varuint32) {
    out_buffer.write_var_uint32(value);
  }
  for (uint64_t value : expected_varuint64) {
    out_buffer.write_var_uint64(value);
  }
  for (int64_t value : expected_varint64) {
    out_buffer.write_var_int64(value);
  }

  std::vector<uint8_t> out(out_buffer.data(),
                           out_buffer.data() + out_buffer.writer_index());
  write_file(data_file, out);
}

void run_test_murmur_hash3(const std::string &data_file) {
  auto bytes = read_file(data_file);
  if (bytes.size() < 16) {
    fail("Not enough bytes for murmurhash test");
  }
  Buffer buffer(bytes.data(), static_cast<uint32_t>(bytes.size()), false);

  Error error;
  int64_t first = buffer.read_int64(error);
  if (!error.ok())
    fail("Failed to read first int64: " + error.message());

  int64_t second = buffer.read_int64(error);
  if (!error.ok())
    fail("Failed to read second int64: " + error.message());

  int64_t hash_out[2] = {0, 0};
  MurmurHash3_x64_128("\x01\x02\x08", 3, 47, hash_out);
  if (first != hash_out[0] || second != hash_out[1]) {
    fail("MurmurHash3 mismatch");
  }
}

void run_test_string_serializer(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  std::vector<std::string> test_strings = {
      "ab",     "Rust123", "Çüéâäàåçêëèïî", "こんにちは",
      "Привет", "𝄞🎵🎶",   "Hello, 世界",
  };

  {
    std::vector<uint8_t> copy = bytes;
    Buffer buffer = make_buffer(copy);
    for (const auto &expected : test_strings) {
      auto actual = read_next<std::string>(fory, buffer);
      if (actual != expected) {
        fail("String serializer mismatch");
      }
    }
  }

  std::vector<uint8_t> out;
  out.reserve(bytes.size());
  for (const auto &s : test_strings) {
    append_serialized(fory, s, out);
  }
  write_file(data_file, out);
}

void run_test_cross_language_serializer(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  register_basic_structs(fory);

  std::vector<std::string> str_list = {"hello", "world"};
  std::set<std::string> str_set = {"hello", "world"};
  std::map<std::string, std::string> str_map = {{"hello", "world"},
                                                {"foo", "bar"}};
  Date day(18954); // 2021-11-23
  Timestamp instant(std::chrono::seconds(100));

  std::vector<uint8_t> copy = bytes;
  Buffer buffer = make_buffer(copy);

  auto expect_bool = [&](bool expected) {
    if (read_next<bool>(fory, buffer) != expected) {
      fail("Boolean mismatch in cross-language serializer test");
    }
  };

  expect_bool(true);
  expect_bool(false);

  int32_t v1 = read_next<int32_t>(fory, buffer);
  if (v1 != -1)
    fail("int32 -1 mismatch, got: " + std::to_string(v1));

  int8_t v2 = read_next<int8_t>(fory, buffer);
  if (v2 != std::numeric_limits<int8_t>::max())
    fail("int8 max mismatch, got: " + std::to_string(v2));

  int8_t v3 = read_next<int8_t>(fory, buffer);
  if (v3 != std::numeric_limits<int8_t>::min())
    fail("int8 min mismatch, got: " + std::to_string(v3));

  int16_t v4 = read_next<int16_t>(fory, buffer);
  if (v4 != std::numeric_limits<int16_t>::max())
    fail("int16 max mismatch, got: " + std::to_string(v4));

  int16_t v5 = read_next<int16_t>(fory, buffer);
  if (v5 != std::numeric_limits<int16_t>::min())
    fail("int16 min mismatch, got: " + std::to_string(v5));

  int32_t v6 = read_next<int32_t>(fory, buffer);
  if (v6 != std::numeric_limits<int32_t>::max())
    fail("int32 max mismatch, got: " + std::to_string(v6));

  int32_t v7 = read_next<int32_t>(fory, buffer);
  if (v7 != std::numeric_limits<int32_t>::min())
    fail("int32 min mismatch, got: " + std::to_string(v7));

  int64_t v8 = read_next<int64_t>(fory, buffer);
  if (v8 != std::numeric_limits<int64_t>::max())
    fail("int64 max mismatch, got: " + std::to_string(v8));

  int64_t v9 = read_next<int64_t>(fory, buffer);
  if (v9 != std::numeric_limits<int64_t>::min())
    fail("int64 min mismatch, got: " + std::to_string(v9));
  if (std::abs(read_next<float>(fory, buffer) + 1.0f) > 1e-6 ||
      std::abs(read_next<double>(fory, buffer) + 1.0) > 1e-9) {
    fail("Float mismatch");
  }
  if (read_next<std::string>(fory, buffer) != "str") {
    fail("String mismatch");
  }
  if (read_next<Date>(fory, buffer) != day) {
    fail("Date mismatch");
  }
  if (read_next<Timestamp>(fory, buffer) != instant) {
    fail("Timestamp mismatch");
  }
  if (read_next<std::vector<bool>>(fory, buffer) !=
      std::vector<bool>({true, false})) {
    fail("Boolean array mismatch");
  }
  if (read_next<std::vector<uint8_t>>(fory, buffer) !=
      std::vector<uint8_t>({1, static_cast<uint8_t>(127)})) {
    fail("Byte array mismatch");
  }
  if (read_next<std::vector<int16_t>>(fory, buffer) !=
      std::vector<int16_t>({1, std::numeric_limits<int16_t>::max()})) {
    fail("Short array mismatch");
  }
  if (read_next<std::vector<int32_t>>(fory, buffer) !=
      std::vector<int32_t>({1, std::numeric_limits<int32_t>::max()})) {
    fail("Int array mismatch");
  }
  if (read_next<std::vector<int64_t>>(fory, buffer) !=
      std::vector<int64_t>({1, std::numeric_limits<int64_t>::max()})) {
    fail("Long array mismatch");
  }
  if (read_next<std::vector<float>>(fory, buffer) !=
      std::vector<float>({1.0f, 2.0f})) {
    fail("Float array mismatch");
  }
  if (read_next<std::vector<double>>(fory, buffer) !=
      std::vector<double>({1.0, 2.0})) {
    fail("Double array mismatch");
  }
  if (read_next<std::vector<std::string>>(fory, buffer) != str_list) {
    fail("List mismatch");
  }
  if (read_next<std::set<std::string>>(fory, buffer) != str_set) {
    fail("Set mismatch");
  }
  if (read_next<std::map<std::string, std::string>>(fory, buffer) != str_map) {
    fail("Map mismatch");
  }
  if (read_next<Color>(fory, buffer) != Color::White) {
    fail("Color mismatch");
  }

  std::vector<uint8_t> out;
  out.reserve(bytes.size());
  append_serialized(fory, true, out);
  append_serialized(fory, false, out);
  append_serialized(fory, -1, out);
  append_serialized(fory, std::numeric_limits<int8_t>::max(), out);
  append_serialized(fory, std::numeric_limits<int8_t>::min(), out);
  append_serialized(fory, std::numeric_limits<int16_t>::max(), out);
  append_serialized(fory, std::numeric_limits<int16_t>::min(), out);
  append_serialized(fory, std::numeric_limits<int32_t>::max(), out);
  append_serialized(fory, std::numeric_limits<int32_t>::min(), out);
  append_serialized(fory, std::numeric_limits<int64_t>::max(), out);
  append_serialized(fory, std::numeric_limits<int64_t>::min(), out);
  append_serialized(fory, -1.0f, out);
  append_serialized(fory, -1.0, out);
  append_serialized(fory, std::string("str"), out);
  append_serialized(fory, day, out);
  append_serialized(fory, instant, out);
  append_serialized(fory, std::vector<bool>({true, false}), out);
  append_serialized(fory, std::vector<uint8_t>({1, 127}), out);
  append_serialized(
      fory, std::vector<int16_t>({1, std::numeric_limits<int16_t>::max()}),
      out);
  append_serialized(
      fory, std::vector<int32_t>({1, std::numeric_limits<int32_t>::max()}),
      out);
  append_serialized(
      fory, std::vector<int64_t>({1, std::numeric_limits<int64_t>::max()}),
      out);
  append_serialized(fory, std::vector<float>({1.0f, 2.0f}), out);
  append_serialized(fory, std::vector<double>({1.0, 2.0}), out);
  append_serialized(fory, str_list, out);
  append_serialized(fory, str_set, out);
  append_serialized(fory, str_map, out);
  append_serialized(fory, Color::White, out);

  write_file(data_file, out);
}

SimpleStruct build_simple_struct() {
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

void run_test_simple_struct(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  register_basic_structs(fory);
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
  auto expected = build_simple_struct();
  Buffer buffer = make_buffer(bytes);
  auto value = read_next<SimpleStruct>(fory, buffer);
  if (!(value == expected)) {
    fail("SimpleStruct mismatch");
  }
  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_simple_named_struct(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  ensure_ok(fory.register_enum<Color>("demo", "color"), "register color");
  ensure_ok(fory.register_struct<Item>("demo", "item"), "register item");
  ensure_ok(fory.register_struct<SimpleStruct>("demo", "simple_struct"),
            "register simple_struct");
  auto expected = build_simple_struct();
  Buffer buffer = make_buffer(bytes);
  auto value = read_next<SimpleStruct>(fory, buffer);
  if (!(value == expected)) {
    fail("Named SimpleStruct mismatch");
  }
  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_list(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  ensure_ok(fory.register_struct<Item>(102), "register Item");
  Buffer buffer = make_buffer(bytes);

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

  if (read_next<std::vector<std::optional<std::string>>>(fory, buffer) !=
      expected1) {
    fail("List string mismatch");
  }
  if (read_next<std::vector<std::optional<std::string>>>(fory, buffer) !=
      expected2) {
    fail("List string with null mismatch");
  }
  if (read_next<std::vector<std::optional<Item>>>(fory, buffer) !=
      expected_items1) {
    fail("List item mismatch");
  }
  if (read_next<std::vector<std::optional<Item>>>(fory, buffer) !=
      expected_items2) {
    fail("List item with null mismatch");
  }

  std::vector<uint8_t> out;
  append_serialized(fory, expected1, out);
  append_serialized(fory, expected2, out);
  append_serialized(fory, expected_items1, out);
  append_serialized(fory, expected_items2, out);
  write_file(data_file, out);
}

void run_test_map(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  ensure_ok(fory.register_struct<Item>(102), "register Item");
  Buffer buffer = make_buffer(bytes);

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

  if (read_next<std::map<OptStr, OptStr>>(fory, buffer) != str_map) {
    fail("Map<string,string> mismatch");
  }
  if (read_next<std::map<OptStr, OptItem>>(fory, buffer) != item_map) {
    fail("Map<string,item> mismatch");
  }

  std::vector<uint8_t> out;
  append_serialized(fory, str_map, out);
  append_serialized(fory, item_map, out);
  write_file(data_file, out);
}

void run_test_integer(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  ensure_ok(fory.register_struct<Item1>(101), "register Item1");
  Buffer buffer = make_buffer(bytes);

  Item1 expected;
  expected.f1 = 1;
  expected.f2 = 2;
  expected.f3 = 3;
  expected.f4 = 4;
  expected.f5 = 0;
  expected.f6 = 0;

  auto item_value = read_next<Item1>(fory, buffer);
  if (!(item_value == expected)) {
    fail("Item1 mismatch");
  }
  // Note: we do not consume the trailing primitive integers from the
  // Java-produced payload here. They are validated on the Java and Rust
  // sides and re-written by the C++ side below for the C++ -> Java
  // round-trip.

  std::vector<uint8_t> out;
  append_serialized(fory, item_value, out);
  append_serialized(fory, 1, out);
  append_serialized(fory, 2, out);
  append_serialized(fory, std::optional<int32_t>(3), out);
  append_serialized(fory, std::optional<int32_t>(4), out);
  // xlang mode uses nullable=false by default, so write 0 not null
  append_serialized(fory, 0, out);
  append_serialized(fory, 0, out);
  write_file(data_file, out);
}

void run_test_item(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  ensure_ok(fory.register_struct<Item>(102), "register Item");

  Buffer buffer = make_buffer(bytes);

  Item expected1;
  expected1.name = std::string("test_item_1");
  Item expected2;
  expected2.name = std::string("test_item_2");
  Item expected3;
  expected3.name = std::string(""); // Empty string for non-nullable field

  Item item1 = read_next<Item>(fory, buffer);
  if (!(item1 == expected1)) {
    fail("Item 1 mismatch");
  }
  Item item2 = read_next<Item>(fory, buffer);
  if (!(item2 == expected2)) {
    fail("Item 2 mismatch");
  }
  Item item3 = read_next<Item>(fory, buffer);
  if (!(item3 == expected3)) {
    fail("Item 3 mismatch");
  }

  std::vector<uint8_t> out;
  append_serialized(fory, expected1, out);
  append_serialized(fory, expected2, out);
  append_serialized(fory, expected3, out);
  write_file(data_file, out);
}

void run_test_color(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  ensure_ok(fory.register_enum<Color>(101), "register Color");

  Buffer buffer = make_buffer(bytes);

  if (read_next<Color>(fory, buffer) != Color::Green) {
    fail("Color Green mismatch");
  }
  if (read_next<Color>(fory, buffer) != Color::Red) {
    fail("Color Red mismatch");
  }
  if (read_next<Color>(fory, buffer) != Color::Blue) {
    fail("Color Blue mismatch");
  }
  if (read_next<Color>(fory, buffer) != Color::White) {
    fail("Color White mismatch");
  }

  std::vector<uint8_t> out;
  append_serialized(fory, Color::Green, out);
  append_serialized(fory, Color::Red, out);
  append_serialized(fory, Color::Blue, out);
  append_serialized(fory, Color::White, out);
  write_file(data_file, out);
}

void run_test_struct_with_list(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  ensure_ok(fory.register_struct<StructWithList>(201),
            "register StructWithList");

  Buffer buffer = make_buffer(bytes);

  StructWithList expected1;
  expected1.items = {std::string("a"), std::string("b"), std::string("c")};

  StructWithList expected2;
  expected2.items = {std::string("x"), std::string(""),
                     std::string("z")}; // Empty string instead of null

  StructWithList struct1 = read_next<StructWithList>(fory, buffer);
  if (!(struct1 == expected1)) {
    fail("StructWithList 1 mismatch");
  }

  StructWithList struct2 = read_next<StructWithList>(fory, buffer);
  if (!(struct2 == expected2)) {
    fail("StructWithList 2 mismatch");
  }

  std::vector<uint8_t> out;
  append_serialized(fory, expected1, out);
  append_serialized(fory, expected2, out);
  write_file(data_file, out);
}

void run_test_struct_with_map(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  ensure_ok(fory.register_struct<StructWithMap>(202), "register StructWithMap");

  Buffer buffer = make_buffer(bytes);

  StructWithMap expected1;
  expected1.data = {{std::string("key1"), std::string("value1")},
                    {std::string("key2"), std::string("value2")}};

  StructWithMap expected2;
  // Java test uses null values - but with xlang non-nullable default,
  // these should be empty strings or the test may need adjustment
  expected2.data = {{std::string("k1"), std::string("")},
                    {std::string(""), std::string("v2")}};

  StructWithMap struct1 = read_next<StructWithMap>(fory, buffer);
  if (!(struct1 == expected1)) {
    fail("StructWithMap 1 mismatch");
  }

  StructWithMap struct2 = read_next<StructWithMap>(fory, buffer);
  if (!(struct2 == expected2)) {
    fail("StructWithMap 2 mismatch");
  }

  std::vector<uint8_t> out;
  append_serialized(fory, expected1, out);
  append_serialized(fory, expected2, out);
  write_file(data_file, out);
}

void run_test_skip_id_custom(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto limited = build_fory(true, true);
  ensure_ok(limited.register_extension_type<MyExt>(103),
            "register MyExt limited");
  ensure_ok(limited.register_struct<EmptyWrapper>(104),
            "register EmptyWrapper limited");
  {
    std::vector<uint8_t> copy = bytes;
    Buffer buffer = make_buffer(copy);
    (void)read_next<EmptyWrapper>(limited, buffer);
  }

  auto full = build_fory(true, true);
  ensure_ok(full.register_enum<Color>(101), "register Color full");
  ensure_ok(full.register_struct<MyStruct>(102), "register MyStruct full");
  ensure_ok(full.register_extension_type<MyExt>(103), "register MyExt full");
  ensure_ok(full.register_struct<MyWrapper>(104), "register MyWrapper full");

  MyWrapper wrapper;
  wrapper.color = Color::White;
  wrapper.my_struct = MyStruct(42);
  wrapper.my_ext = MyExt{43};

  std::vector<uint8_t> out;
  append_serialized(full, wrapper, out);
  write_file(data_file, out);
}

void run_test_skip_name_custom(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto limited = build_fory(true, true);
  ensure_ok(limited.register_extension_type<MyExt>("my_ext"),
            "register named MyExt");
  ensure_ok(limited.register_struct<EmptyWrapper>("my_wrapper"),
            "register named EmptyWrapper");
  {
    std::vector<uint8_t> copy = bytes;
    Buffer buffer = make_buffer(copy);
    auto result = limited.deserialize<EmptyWrapper>(buffer);
    if (!result.ok()) {
      fail("Failed to deserialize EmptyWrapper: " + result.error().message());
    }
  }

  auto full = build_fory(true, true);
  ensure_ok(full.register_enum<Color>("color"), "register named Color");
  ensure_ok(full.register_struct<MyStruct>("my_struct"),
            "register named MyStruct");
  ensure_ok(full.register_extension_type<MyExt>("my_ext"),
            "register named MyExt");
  ensure_ok(full.register_struct<MyWrapper>("my_wrapper"),
            "register named MyWrapper");

  MyWrapper wrapper;
  wrapper.color = Color::White;
  wrapper.my_struct = MyStruct(42);
  wrapper.my_ext = MyExt{43};

  std::vector<uint8_t> out;
  append_serialized(full, wrapper, out);
  write_file(data_file, out);
}

void run_test_consistent_named(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // Java uses SCHEMA_CONSISTENT mode which does NOT enable meta sharing
  auto fory = build_fory(false, true, true);
  ensure_ok(fory.register_enum<Color>("color"), "register named color");
  ensure_ok(fory.register_struct<MyStruct>("my_struct"),
            "register named MyStruct");
  ensure_ok(fory.register_extension_type<MyExt>("my_ext"),
            "register named MyExt");

  MyStruct my_struct(42);
  MyExt my_ext{43};

  Buffer buffer = make_buffer(bytes);
  for (int i = 0; i < 3; ++i) {
    if (read_next<Color>(fory, buffer) != Color::White) {
      fail("Consistent named color mismatch");
    }
  }
  for (int i = 0; i < 3; ++i) {
    if (!(read_next<MyStruct>(fory, buffer) == my_struct)) {
      fail("Consistent named struct mismatch");
    }
  }
  for (int i = 0; i < 3; ++i) {
    if (!(read_next<MyExt>(fory, buffer) == my_ext)) {
      fail("Consistent named ext mismatch");
    }
  }

  std::vector<uint8_t> out;
  for (int i = 0; i < 3; ++i) {
    append_serialized(fory, Color::White, out);
  }
  for (int i = 0; i < 3; ++i) {
    append_serialized(fory, my_struct, out);
  }
  for (int i = 0; i < 3; ++i) {
    append_serialized(fory, my_ext, out);
  }
  write_file(data_file, out);
}

void run_test_struct_version_check(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // Java uses SCHEMA_CONSISTENT mode which does NOT enable meta sharing
  auto fory = build_fory(false, true, true);
  ensure_ok(fory.register_struct<VersionCheckStruct>(201),
            "register VersionCheckStruct");

  VersionCheckStruct expected;
  expected.f1 = 10;
  expected.f2 = std::string("test");
  expected.f3 = 3.2;

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<VersionCheckStruct>(fory, buffer);
  if (!(value == expected)) {
    fail("VersionCheckStruct mismatch");
  }

  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_polymorphic_list(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  ensure_ok(fory.register_struct<Dog>(302), "register Dog");
  ensure_ok(fory.register_struct<Cat>(303), "register Cat");
  ensure_ok(fory.register_struct<AnimalListHolder>(304),
            "register AnimalListHolder");

  Buffer buffer = make_buffer(bytes);

  // Part 1: Read List<Animal> with polymorphic elements (Dog, Cat)
  auto animals = read_next<std::vector<std::shared_ptr<Animal>>>(fory, buffer);
  if (animals.size() != 2) {
    fail("Animal list size mismatch, got: " + std::to_string(animals.size()));
  }

  // First element should be Dog
  auto *dog = dynamic_cast<Dog *>(animals[0].get());
  if (dog == nullptr) {
    fail("First element is not a Dog");
  }
  if (dog->age != 3 || dog->name != std::string("Buddy")) {
    fail("First Dog mismatch: age=" + std::to_string(dog->age) +
         ", name=" + dog->name.value_or("null"));
  }

  // Second element should be Cat
  auto *cat = dynamic_cast<Cat *>(animals[1].get());
  if (cat == nullptr) {
    fail("Second element is not a Cat");
  }
  if (cat->age != 5 || cat->lives != 9) {
    fail("Cat mismatch: age=" + std::to_string(cat->age) +
         ", lives=" + std::to_string(cat->lives));
  }

  // Part 2: Read AnimalListHolder (List<Animal> as struct field)
  auto holder = read_next<AnimalListHolder>(fory, buffer);
  if (holder.animals.size() != 2) {
    fail("AnimalListHolder size mismatch");
  }

  auto *dog2 = dynamic_cast<Dog *>(holder.animals[0].get());
  if (dog2 == nullptr || dog2->name != std::string("Rex")) {
    fail("AnimalListHolder first animal (Dog) mismatch");
  }

  auto *cat2 = dynamic_cast<Cat *>(holder.animals[1].get());
  if (cat2 == nullptr || cat2->lives != 7) {
    fail("AnimalListHolder second animal (Cat) mismatch");
  }

  // write back
  std::vector<uint8_t> out;
  append_serialized(fory, animals, out);
  append_serialized(fory, holder, out);
  write_file(data_file, out);
}

void run_test_polymorphic_map(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true);
  ensure_ok(fory.register_struct<Dog>(302), "register Dog");
  ensure_ok(fory.register_struct<Cat>(303), "register Cat");
  ensure_ok(fory.register_struct<AnimalMapHolder>(305),
            "register AnimalMapHolder");

  Buffer buffer = make_buffer(bytes);

  // Part 1: Read Map<String, Animal> with polymorphic values
  using OptStr = std::optional<std::string>;
  auto animal_map =
      read_next<std::map<OptStr, std::shared_ptr<Animal>>>(fory, buffer);
  if (animal_map.size() != 2) {
    fail("Animal map size mismatch, got: " + std::to_string(animal_map.size()));
  }

  auto dog1_it = animal_map.find(std::string("dog1"));
  if (dog1_it == animal_map.end()) {
    fail("Dog1 not found in map");
  }
  auto *dog = dynamic_cast<Dog *>(dog1_it->second.get());
  if (dog == nullptr || dog->age != 2 || dog->name != std::string("Rex")) {
    fail("Animal map dog1 mismatch");
  }

  auto cat1_it = animal_map.find(std::string("cat1"));
  if (cat1_it == animal_map.end()) {
    fail("Cat1 not found in map");
  }
  auto *cat = dynamic_cast<Cat *>(cat1_it->second.get());
  if (cat == nullptr || cat->age != 4 || cat->lives != 9) {
    fail("Animal map cat1 mismatch");
  }

  // Part 2: Read AnimalMapHolder (Map<String, Animal> as struct field)
  auto holder = read_next<AnimalMapHolder>(fory, buffer);
  if (holder.animal_map.size() != 2) {
    fail("AnimalMapHolder size mismatch");
  }

  auto my_dog_it = holder.animal_map.find(std::string("myDog"));
  if (my_dog_it == holder.animal_map.end()) {
    fail("myDog not found in holder map");
  }
  auto *my_dog = dynamic_cast<Dog *>(my_dog_it->second.get());
  if (my_dog == nullptr || my_dog->name != std::string("Fido")) {
    fail("AnimalMapHolder myDog mismatch");
  }

  auto my_cat_it = holder.animal_map.find(std::string("myCat"));
  if (my_cat_it == holder.animal_map.end()) {
    fail("myCat not found in holder map");
  }
  auto *my_cat = dynamic_cast<Cat *>(my_cat_it->second.get());
  if (my_cat == nullptr || my_cat->lives != 8) {
    fail("AnimalMapHolder myCat mismatch");
  }

  // write back
  std::vector<uint8_t> out;
  append_serialized(fory, animal_map, out);
  append_serialized(fory, holder, out);
  write_file(data_file, out);
}

// ============================================================================
// Schema Evolution Tests - String Fields
// ============================================================================

void run_test_one_string_field_schema(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true,
  // check_struct_version=true
  auto fory = build_fory(false, true, true);
  ensure_ok(fory.register_struct<OneStringFieldStruct>(200),
            "register OneStringFieldStruct");

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<OneStringFieldStruct>(fory, buffer);

  OneStringFieldStruct expected;
  expected.f1 = std::string("hello");
  if (!(value == expected)) {
    fail("OneStringFieldStruct schema mismatch: got f1=" +
         value.f1.value_or("null"));
  }

  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_one_string_field_compatible(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true); // COMPATIBLE mode
  ensure_ok(fory.register_struct<OneStringFieldStruct>(200),
            "register OneStringFieldStruct");

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<OneStringFieldStruct>(fory, buffer);

  OneStringFieldStruct expected;
  expected.f1 = std::string("hello");
  if (!(value == expected)) {
    fail("OneStringFieldStruct compatible mismatch: got f1=" +
         value.f1.value_or("null"));
  }

  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_two_string_field_compatible(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true); // COMPATIBLE mode
  ensure_ok(fory.register_struct<TwoStringFieldStruct>(201),
            "register TwoStringFieldStruct");

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<TwoStringFieldStruct>(fory, buffer);

  TwoStringFieldStruct expected;
  expected.f1 = std::string("first");
  expected.f2 = std::string("second");
  if (!(value == expected)) {
    fail("TwoStringFieldStruct compatible mismatch: got f1=" + value.f1 +
         ", f2=" + value.f2);
  }

  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_schema_evolution_compatible(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // Read TwoStringFieldStruct data as EmptyStructEvolution
  auto fory = build_fory(true, true); // COMPATIBLE mode
  ensure_ok(fory.register_struct<EmptyStructEvolution>(200),
            "register EmptyStructEvolution");

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<EmptyStructEvolution>(fory, buffer);

  // Serialize back as EmptyStructEvolution
  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_schema_evolution_compatible_reverse(
    const std::string &data_file) {
  auto bytes = read_file(data_file);
  // Read OneStringFieldStruct data as TwoStringFieldStruct (missing f2)
  auto fory = build_fory(true, true); // COMPATIBLE mode
  ensure_ok(fory.register_struct<TwoStringFieldStruct>(200),
            "register TwoStringFieldStruct");

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<TwoStringFieldStruct>(fory, buffer);

  // f1 should be "only_one", f2 should be empty (default value)
  if (value.f1 != "only_one") {
    fail("Schema evolution reverse mismatch: expected f1='only_one', got f1=" +
         value.f1);
  }
  // f2 should be empty (not present in source data, default initialized)
  if (!value.f2.empty()) {
    fail("Schema evolution reverse mismatch: expected f2='', got f2=" +
         value.f2);
  }

  // Serialize back
  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

// ============================================================================
// Schema Evolution Tests - Enum Fields
// ============================================================================

void run_test_one_enum_field_schema(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true,
  // check_struct_version=true
  auto fory = build_fory(false, true, true);
  ensure_ok(fory.register_enum<TestEnum>(210), "register TestEnum");
  ensure_ok(fory.register_struct<OneEnumFieldStruct>(211),
            "register OneEnumFieldStruct");

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<OneEnumFieldStruct>(fory, buffer);

  if (value.f1 != TestEnum::VALUE_B) {
    fail("OneEnumFieldStruct schema mismatch: expected VALUE_B, got " +
         std::to_string(static_cast<int32_t>(value.f1)));
  }

  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_one_enum_field_compatible(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true); // COMPATIBLE mode
  ensure_ok(fory.register_enum<TestEnum>(210), "register TestEnum");
  ensure_ok(fory.register_struct<OneEnumFieldStruct>(211),
            "register OneEnumFieldStruct");

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<OneEnumFieldStruct>(fory, buffer);

  if (value.f1 != TestEnum::VALUE_A) {
    fail("OneEnumFieldStruct compatible mismatch: expected VALUE_A, got " +
         std::to_string(static_cast<int32_t>(value.f1)));
  }

  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_two_enum_field_compatible(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true); // COMPATIBLE mode
  ensure_ok(fory.register_enum<TestEnum>(210), "register TestEnum");
  ensure_ok(fory.register_struct<TwoEnumFieldStruct>(212),
            "register TwoEnumFieldStruct");

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<TwoEnumFieldStruct>(fory, buffer);

  if (value.f1 != TestEnum::VALUE_A) {
    fail("TwoEnumFieldStruct compatible mismatch: expected f1=VALUE_A, got " +
         std::to_string(static_cast<int32_t>(value.f1)));
  }
  if (value.f2 != TestEnum::VALUE_C) {
    fail("TwoEnumFieldStruct compatible mismatch: expected f2=VALUE_C, got " +
         std::to_string(static_cast<int32_t>(value.f2)));
  }

  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_enum_schema_evolution_compatible(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // Read TwoEnumFieldStruct data as EmptyStructEvolution
  auto fory = build_fory(true, true); // COMPATIBLE mode
  ensure_ok(fory.register_enum<TestEnum>(210), "register TestEnum");
  ensure_ok(fory.register_struct<EmptyStructEvolution>(211),
            "register EmptyStructEvolution");

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<EmptyStructEvolution>(fory, buffer);

  // Serialize back as EmptyStructEvolution
  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_enum_schema_evolution_compatible_reverse(
    const std::string &data_file) {
  auto bytes = read_file(data_file);
  // Read OneEnumFieldStruct data as TwoEnumFieldStruct (missing f2)
  auto fory = build_fory(true, true); // COMPATIBLE mode
  ensure_ok(fory.register_enum<TestEnum>(210), "register TestEnum");
  ensure_ok(fory.register_struct<TwoEnumFieldStruct>(211),
            "register TwoEnumFieldStruct");

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<TwoEnumFieldStruct>(fory, buffer);

  // f1 should be VALUE_C
  if (value.f1 != TestEnum::VALUE_C) {
    fail("Enum schema evolution reverse mismatch: expected f1=VALUE_C, got " +
         std::to_string(static_cast<int32_t>(value.f1)));
  }
  // f2 should be default (VALUE_A = 0, not present in source data)
  if (value.f2 != TestEnum::VALUE_A) {
    fail("Enum schema evolution reverse mismatch: expected f2=VALUE_A "
         "(default), got " +
         std::to_string(static_cast<int32_t>(value.f2)));
  }

  // Serialize back
  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

// ============================================================================
// Nullable Field Tests - Comprehensive versions
// ============================================================================

void run_test_nullable_field_schema_consistent_not_null(
    const std::string &data_file) {
  auto bytes = read_file(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true,
  // check_struct_version=true
  auto fory = build_fory(false, true, true);
  ensure_ok(fory.register_struct<NullableComprehensiveSchemaConsistent>(401),
            "register NullableComprehensiveSchemaConsistent");

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

  // Nullable fields - all have values (second half - @fory_field)
  expected.nullable_double = 2.5;
  expected.nullable_bool = false;
  expected.nullable_string = std::string("nullable_value");
  expected.nullable_list =
      std::vector<std::string>{std::string("p"), std::string("q")};
  expected.nullable_set =
      std::set<std::string>{std::string("m"), std::string("n")};
  expected.nullable_map = std::map<std::string, std::string>{
      {std::string("nk1"), std::string("nv1")}};

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<NullableComprehensiveSchemaConsistent>(fory, buffer);
  if (!(value == expected)) {
    fail("NullableComprehensiveSchemaConsistent not null mismatch");
  }

  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_nullable_field_schema_consistent_null(
    const std::string &data_file) {
  auto bytes = read_file(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true,
  // check_struct_version=true
  auto fory = build_fory(false, true, true);
  ensure_ok(fory.register_struct<NullableComprehensiveSchemaConsistent>(401),
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

  // Nullable fields - all null (second half - @fory_field)
  expected.nullable_double = std::nullopt;
  expected.nullable_bool = std::nullopt;
  expected.nullable_string = std::nullopt;
  expected.nullable_list = std::nullopt;
  expected.nullable_set = std::nullopt;
  expected.nullable_map = std::nullopt;

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<NullableComprehensiveSchemaConsistent>(fory, buffer);
  if (!(value == expected)) {
    fail("NullableComprehensiveSchemaConsistent null mismatch");
  }

  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_nullable_field_compatible_not_null(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true); // COMPATIBLE mode
  ensure_ok(fory.register_struct<NullableComprehensiveCompatible>(402),
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

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<NullableComprehensiveCompatible>(fory, buffer);
  if (!(value == expected)) {
    fail("NullableComprehensiveCompatible not null mismatch");
  }

  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

void run_test_nullable_field_compatible_null(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(true, true); // COMPATIBLE mode
  ensure_ok(fory.register_struct<NullableComprehensiveCompatible>(402),
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

  Buffer buffer = make_buffer(bytes);
  auto value = read_next<NullableComprehensiveCompatible>(fory, buffer);
  if (!(value == expected)) {
    fail("NullableComprehensiveCompatible null mismatch");
  }

  std::vector<uint8_t> out;
  append_serialized(fory, value, out);
  write_file(data_file, out);
}

// ============================================================================
// Reference Tracking Tests - Cross-language shared reference tests
// ============================================================================

void run_test_ref_schema_consistent(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true,
  // check_struct_version=true, track_ref=true
  auto fory = build_fory(false, true, true, true);
  ensure_ok(fory.register_struct<RefInnerSchemaConsistent>(501),
            "register RefInnerSchemaConsistent");
  ensure_ok(fory.register_struct<RefOuterSchemaConsistent>(502),
            "register RefOuterSchemaConsistent");

  Buffer buffer = make_buffer(bytes);
  auto outer = read_next<RefOuterSchemaConsistent>(fory, buffer);

  // Both inner1 and inner2 should have values
  if (outer.inner1 == nullptr) {
    fail("RefOuterSchemaConsistent: inner1 should not be null");
  }
  if (outer.inner2 == nullptr) {
    fail("RefOuterSchemaConsistent: inner2 should not be null");
  }

  // Both should have the same values (they reference the same object in Java)
  if (outer.inner1->id != 42) {
    fail("RefOuterSchemaConsistent: inner1.id should be 42, got " +
         std::to_string(outer.inner1->id));
  }
  if (outer.inner1->name != "shared_inner") {
    fail(
        "RefOuterSchemaConsistent: inner1.name should be 'shared_inner', got " +
        outer.inner1->name);
  }
  if (*outer.inner1 != *outer.inner2) {
    fail("RefOuterSchemaConsistent: inner1 and inner2 should be equal (same "
         "reference)");
  }

  // In C++, shared_ptr may or may not point to the same object after
  // deserialization, depending on reference tracking implementation.
  // The key test is that both have equal values.

  // Re-serialize and write back
  std::vector<uint8_t> out;
  append_serialized(fory, outer, out);
  write_file(data_file, out);
}

void run_test_ref_compatible(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // COMPATIBLE mode: compatible=true, xlang=true, check_struct_version=false,
  // track_ref=true
  auto fory = build_fory(true, true, false, true);
  ensure_ok(fory.register_struct<RefInnerCompatible>(503),
            "register RefInnerCompatible");
  ensure_ok(fory.register_struct<RefOuterCompatible>(504),
            "register RefOuterCompatible");

  Buffer buffer = make_buffer(bytes);
  auto outer = read_next<RefOuterCompatible>(fory, buffer);

  // Both inner1 and inner2 should have values
  if (outer.inner1 == nullptr) {
    fail("RefOuterCompatible: inner1 should not be null");
  }
  if (outer.inner2 == nullptr) {
    fail("RefOuterCompatible: inner2 should not be null");
  }

  // Both should have the same values (they reference the same object in Java)
  if (outer.inner1->id != 99) {
    fail("RefOuterCompatible: inner1.id should be 99, got " +
         std::to_string(outer.inner1->id));
  }
  if (outer.inner1->name != "compatible_shared") {
    fail("RefOuterCompatible: inner1.name should be 'compatible_shared', got " +
         outer.inner1->name);
  }
  if (*outer.inner1 != *outer.inner2) {
    fail("RefOuterCompatible: inner1 and inner2 should be equal (same "
         "reference)");
  }

  // Re-serialize and write back
  std::vector<uint8_t> out;
  append_serialized(fory, outer, out);
  write_file(data_file, out);
}

void run_test_collection_element_ref_override(const std::string &data_file) {
  auto bytes = read_file(data_file);
  auto fory = build_fory(false, true, true, true);
  ensure_ok(fory.register_struct<RefOverrideElement>(701),
            "register RefOverrideElement");
  ensure_ok(fory.register_struct<RefOverrideContainer>(702),
            "register RefOverrideContainer");

  Buffer buffer = make_buffer(bytes);
  auto outer = read_next<RefOverrideContainer>(fory, buffer);
  if (outer.list_field.empty()) {
    fail("RefOverrideContainer: list_field should not be empty");
  }

  auto shared = outer.list_field.front();
  RefOverrideContainer out_container;
  out_container.list_field = {shared, shared};
  out_container.map_field = {{"k1", shared}, {"k2", shared}};

  std::vector<uint8_t> out;
  append_serialized(fory, out_container, out);
  write_file(data_file, out);
}

// ============================================================================
// Circular Reference Tests - Self-referencing struct tests
// ============================================================================

void run_test_circular_ref_schema_consistent(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true,
  // check_struct_version=true, track_ref=true
  auto fory = build_fory(false, true, true, true);
  ensure_ok(fory.register_struct<CircularRefStruct>(601),
            "register CircularRefStruct");

  Buffer buffer = make_buffer(bytes);
  auto obj = read_next<std::shared_ptr<CircularRefStruct>>(fory, buffer);

  // The object should not be null
  if (obj == nullptr) {
    fail("CircularRefStruct: obj should not be null");
  }

  // Verify the name field
  if (obj->name != "circular_test") {
    fail("CircularRefStruct: name should be 'circular_test', got " + obj->name);
  }

  // self_ref should point to the same object (circular reference)
  if (obj->self_ref == nullptr) {
    fail("CircularRefStruct: self_ref should not be null");
  }

  // The key test: self_ref should point back to the same object
  if (obj->self_ref.get() != obj.get()) {
    fail("CircularRefStruct: self_ref should point to same object (circular "
         "reference)");
  }

  // Re-serialize and write back
  std::vector<uint8_t> out;
  append_serialized(fory, obj, out);
  write_file(data_file, out);
}

void run_test_circular_ref_compatible(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // COMPATIBLE mode: compatible=true, xlang=true, check_struct_version=false,
  // track_ref=true
  auto fory = build_fory(true, true, false, true);
  ensure_ok(fory.register_struct<CircularRefStruct>(602),
            "register CircularRefStruct");

  Buffer buffer = make_buffer(bytes);
  auto obj = read_next<std::shared_ptr<CircularRefStruct>>(fory, buffer);

  // The object should not be null
  if (obj == nullptr) {
    fail("CircularRefStruct: obj should not be null");
  }

  // Verify the name field
  if (obj->name != "compatible_circular") {
    fail("CircularRefStruct: name should be 'compatible_circular', got " +
         obj->name);
  }

  // self_ref should point to the same object (circular reference)
  if (obj->self_ref == nullptr) {
    fail("CircularRefStruct: self_ref should not be null");
  }

  // The key test: self_ref should point back to the same object
  if (obj->self_ref.get() != obj.get()) {
    fail("CircularRefStruct: self_ref should point to same object (circular "
         "reference)");
  }

  // Re-serialize and write back
  std::vector<uint8_t> out;
  append_serialized(fory, obj, out);
  write_file(data_file, out);
}

// ============================================================================
// Unsigned Number Tests
// ============================================================================

void run_test_unsigned_schema_consistent_simple(const std::string &data_file) {
  auto bytes = read_file(data_file);
  std::cerr << "[DEBUG] test_unsigned_schema_consistent_simple: read "
            << bytes.size() << " bytes from " << data_file << std::endl;
  // Print first 32 bytes as hex
  std::cerr << "[DEBUG] First bytes: ";
  for (size_t i = 0; i < std::min(bytes.size(), size_t(32)); ++i) {
    std::cerr << std::hex << std::setw(2) << std::setfill('0')
              << static_cast<int>(bytes[i]) << " ";
  }
  std::cerr << std::dec << std::endl;

  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true
  auto fory = build_fory(false, true, false, false);
  ensure_ok(fory.register_struct<UnsignedSchemaConsistentSimple>(1),
            "register UnsignedSchemaConsistentSimple");

  Buffer buffer = make_buffer(bytes);
  auto obj = read_next<UnsignedSchemaConsistentSimple>(fory, buffer);
  std::cerr << "[DEBUG] Deserialized: u64_tagged=" << obj.u64_tagged
            << ", u64_tagged_nullable="
            << (obj.u64_tagged_nullable.has_value()
                    ? std::to_string(obj.u64_tagged_nullable.value())
                    : "null")
            << std::endl;

  // Verify fields
  if (obj.u64_tagged != 1000000000) {
    fail("UnsignedSchemaConsistentSimple: u64_tagged should be 1000000000, "
         "got " +
         std::to_string(obj.u64_tagged));
  }
  if (!obj.u64_tagged_nullable.has_value() ||
      obj.u64_tagged_nullable.value() != 500000000) {
    fail("UnsignedSchemaConsistentSimple: u64_tagged_nullable should be "
         "500000000");
  }

  // Re-serialize and write back
  std::vector<uint8_t> out;
  append_serialized(fory, obj, out);
  write_file(data_file, out);
}

void run_test_unsigned_schema_consistent(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // SCHEMA_CONSISTENT mode: compatible=false, xlang=true
  auto fory = build_fory(false, true, false, false);
  ensure_ok(fory.register_struct<UnsignedSchemaConsistent>(501),
            "register UnsignedSchemaConsistent");

  Buffer buffer = make_buffer(bytes);
  auto obj = read_next<UnsignedSchemaConsistent>(fory, buffer);

  // Verify primitive unsigned fields
  if (obj.u8_field != 200) {
    fail("UnsignedSchemaConsistent: u8_field should be 200, got " +
         std::to_string(obj.u8_field));
  }
  if (obj.u16_field != 60000) {
    fail("UnsignedSchemaConsistent: u16_field should be 60000, got " +
         std::to_string(obj.u16_field));
  }
  if (obj.u32_var_field != 3000000000) {
    fail("UnsignedSchemaConsistent: u32_var_field should be 3000000000, got " +
         std::to_string(obj.u32_var_field));
  }
  if (obj.u32_fixed_field != 4000000000) {
    fail(
        "UnsignedSchemaConsistent: u32_fixed_field should be 4000000000, got " +
        std::to_string(obj.u32_fixed_field));
  }
  if (obj.u64_var_field != 10000000000) {
    fail("UnsignedSchemaConsistent: u64_var_field should be 10000000000, got " +
         std::to_string(obj.u64_var_field));
  }
  if (obj.u64_fixed_field != 15000000000) {
    fail("UnsignedSchemaConsistent: u64_fixed_field should be 15000000000, "
         "got " +
         std::to_string(obj.u64_fixed_field));
  }
  if (obj.u64_tagged_field != 1000000000) {
    fail("UnsignedSchemaConsistent: u64_tagged_field should be 1000000000, "
         "got " +
         std::to_string(obj.u64_tagged_field));
  }

  // Verify nullable unsigned fields
  if (!obj.u8_nullable_field.has_value() ||
      obj.u8_nullable_field.value() != 128) {
    fail("UnsignedSchemaConsistent: u8_nullable_field should be 128");
  }
  if (!obj.u16_nullable_field.has_value() ||
      obj.u16_nullable_field.value() != 40000) {
    fail("UnsignedSchemaConsistent: u16_nullable_field should be 40000");
  }
  if (!obj.u32_var_nullable_field.has_value() ||
      obj.u32_var_nullable_field.value() != 2500000000) {
    fail("UnsignedSchemaConsistent: u32_var_nullable_field should be "
         "2500000000");
  }
  if (!obj.u32_fixed_nullable_field.has_value() ||
      obj.u32_fixed_nullable_field.value() != 3500000000) {
    fail("UnsignedSchemaConsistent: u32_fixed_nullable_field should be "
         "3500000000");
  }
  if (!obj.u64_var_nullable_field.has_value() ||
      obj.u64_var_nullable_field.value() != 8000000000) {
    fail("UnsignedSchemaConsistent: u64_var_nullable_field should be "
         "8000000000");
  }
  if (!obj.u64_fixed_nullable_field.has_value() ||
      obj.u64_fixed_nullable_field.value() != 12000000000) {
    fail("UnsignedSchemaConsistent: u64_fixed_nullable_field should be "
         "12000000000");
  }
  if (!obj.u64_tagged_nullable_field.has_value() ||
      obj.u64_tagged_nullable_field.value() != 500000000) {
    fail("UnsignedSchemaConsistent: u64_tagged_nullable_field should be "
         "500000000");
  }

  // Debug: print field values before re-serialization
  std::cerr << "[DEBUG] Before re-serialization:\n";
  std::cerr << "  u8_field=" << static_cast<int>(obj.u8_field)
            << " u16_field=" << obj.u16_field
            << " u32_var_field=" << obj.u32_var_field
            << " u32_fixed_field=" << obj.u32_fixed_field << "\n";
  std::cerr << "  u64_var_field=" << obj.u64_var_field
            << " u64_fixed_field=" << obj.u64_fixed_field
            << " u64_tagged_field=" << obj.u64_tagged_field << "\n";

  // Re-serialize and write back
  std::vector<uint8_t> out;
  append_serialized(fory, obj, out);

  // Debug: print output bytes for inspection
  std::cerr << "[DEBUG] Serialized " << out.size() << " bytes:\n";
  std::cerr << "[DEBUG] hex: ";
  for (size_t i = 0; i < std::min(out.size(), size_t(80)); ++i) {
    std::cerr << std::hex << std::setw(2) << std::setfill('0')
              << static_cast<int>(out[i]);
  }
  std::cerr << std::dec << "\n";

  write_file(data_file, out);
}

void run_test_unsigned_schema_compatible(const std::string &data_file) {
  auto bytes = read_file(data_file);
  // COMPATIBLE mode: compatible=true, xlang=true
  auto fory = build_fory(true, true, false, false);
  ensure_ok(fory.register_struct<UnsignedSchemaCompatible>(502),
            "register UnsignedSchemaCompatible");

  Buffer buffer = make_buffer(bytes);
  auto obj = read_next<UnsignedSchemaCompatible>(fory, buffer);

  // Verify Group 1: Nullable fields (values from Java's non-nullable fields)
  if (!obj.u8_field1.has_value() || obj.u8_field1.value() != 200) {
    fail("UnsignedSchemaCompatible: u8_field1 should be 200");
  }
  if (!obj.u16_field1.has_value() || obj.u16_field1.value() != 60000) {
    fail("UnsignedSchemaCompatible: u16_field1 should be 60000");
  }
  if (!obj.u32_var_field1.has_value() ||
      obj.u32_var_field1.value() != 3000000000) {
    fail("UnsignedSchemaCompatible: u32_var_field1 should be 3000000000");
  }
  if (!obj.u32_fixed_field1.has_value() ||
      obj.u32_fixed_field1.value() != 4000000000) {
    fail("UnsignedSchemaCompatible: u32_fixed_field1 should be 4000000000");
  }
  if (!obj.u64_var_field1.has_value() ||
      obj.u64_var_field1.value() != 10000000000) {
    fail("UnsignedSchemaCompatible: u64_var_field1 should be 10000000000");
  }
  if (!obj.u64_fixed_field1.has_value() ||
      obj.u64_fixed_field1.value() != 15000000000) {
    fail("UnsignedSchemaCompatible: u64_fixed_field1 should be 15000000000");
  }
  if (!obj.u64_tagged_field1.has_value() ||
      obj.u64_tagged_field1.value() != 1000000000) {
    fail("UnsignedSchemaCompatible: u64_tagged_field1 should be 1000000000");
  }

  // Verify Group 2: Non-nullable fields (values from Java's nullable fields)
  if (obj.u8_field2 != 128) {
    fail("UnsignedSchemaCompatible: u8_field2 should be 128, got " +
         std::to_string(obj.u8_field2));
  }
  if (obj.u16_field2 != 40000) {
    fail("UnsignedSchemaCompatible: u16_field2 should be 40000, got " +
         std::to_string(obj.u16_field2));
  }
  if (obj.u32_var_field2 != 2500000000) {
    fail("UnsignedSchemaCompatible: u32_var_field2 should be 2500000000, got " +
         std::to_string(obj.u32_var_field2));
  }
  if (obj.u32_fixed_field2 != 3500000000) {
    fail("UnsignedSchemaCompatible: u32_fixed_field2 should be 3500000000, "
         "got " +
         std::to_string(obj.u32_fixed_field2));
  }
  if (obj.u64_var_field2 != 8000000000) {
    fail("UnsignedSchemaCompatible: u64_var_field2 should be 8000000000, got " +
         std::to_string(obj.u64_var_field2));
  }
  if (obj.u64_fixed_field2 != 12000000000) {
    fail("UnsignedSchemaCompatible: u64_fixed_field2 should be 12000000000, "
         "got " +
         std::to_string(obj.u64_fixed_field2));
  }
  if (obj.u64_tagged_field2 != 500000000) {
    fail("UnsignedSchemaCompatible: u64_tagged_field2 should be 500000000, "
         "got " +
         std::to_string(obj.u64_tagged_field2));
  }

  // Re-serialize and write back
  std::vector<uint8_t> out;
  append_serialized(fory, obj, out);
  write_file(data_file, out);
}

} // namespace
