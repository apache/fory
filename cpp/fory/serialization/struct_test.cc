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

/**
 * Comprehensive struct serialization test suite.
 *
 * Tests struct serialization including:
 * - Edge cases (single field, many fields)
 * - All primitive types
 * - Nested structs
 * - Structs with containers
 * - Structs with optional fields
 * - Complex real-world scenarios
 */

#include "fory/serialization/fory.h"
#include "fory/type/type.h"
#include "gtest/gtest.h"
#include <cfloat>
#include <climits>
#include <map>
#include <optional>
#include <string>
#include <vector>

// ============================================================================
// FORY_STRUCT can be declared inside the struct/class definition or at
// namespace scope.
// ============================================================================

// Edge cases
struct SingleFieldStruct {
  int32_t value;
  bool operator==(const SingleFieldStruct &other) const {
    return value == other.value;
  }
  FORY_STRUCT(SingleFieldStruct, value);
};

struct TwoFieldStruct {
  int32_t x;
  int32_t y;
  bool operator==(const TwoFieldStruct &other) const {
    return x == other.x && y == other.y;
  }
  FORY_STRUCT(TwoFieldStruct, x, y);
};

struct ManyFieldsStruct {
  bool b1;
  int8_t i8;
  int16_t i16;
  int32_t i32;
  int64_t i64;
  float f32;
  double f64;
  std::string str;

  bool operator==(const ManyFieldsStruct &other) const {
    return b1 == other.b1 && i8 == other.i8 && i16 == other.i16 &&
           i32 == other.i32 && i64 == other.i64 && f32 == other.f32 &&
           f64 == other.f64 && str == other.str;
  }
  FORY_STRUCT(ManyFieldsStruct, b1, i8, i16, i32, i64, f32, f64, str);
};

struct FieldConfigTaggedStruct {
  int32_t a;
  int64_t b;
  std::string c;

  bool operator==(const FieldConfigTaggedStruct &other) const {
    return a == other.a && b == other.b && c == other.c;
  }
  FORY_STRUCT(FieldConfigTaggedStruct, a, b, c);
};

struct FieldTagsTaggedStruct {
  int32_t a;
  int64_t b;
  std::string c;

  bool operator==(const FieldTagsTaggedStruct &other) const {
    return a == other.a && b == other.b && c == other.c;
  }
  FORY_STRUCT(FieldTagsTaggedStruct, a, b, c);
};

FORY_FIELD_CONFIG(FieldConfigTaggedStruct, (a, fory::F().id(1)),
                  (b, fory::F().id(2)), (c, fory::F().id(3)));
FORY_FIELD_TAGS(FieldTagsTaggedStruct, (a, 1), (b, 2), (c, 3));

class PrivateFieldsStruct {
public:
  PrivateFieldsStruct() = default;
  PrivateFieldsStruct(int32_t id, std::string name, std::vector<int32_t> scores)
      : id_(id), name_(std::move(name)), scores_(std::move(scores)) {}

  bool operator==(const PrivateFieldsStruct &other) const {
    return id_ == other.id_ && name_ == other.name_ && scores_ == other.scores_;
  }

private:
  int32_t id_ = 0;
  std::string name_;
  std::vector<int32_t> scores_;

public:
  FORY_STRUCT(PrivateFieldsStruct, id_, name_, scores_);
};

// All primitives
struct AllPrimitivesStruct {
  bool bool_val;
  int8_t int8_val;
  int16_t int16_val;
  int32_t int32_val;
  int64_t int64_val;
  uint8_t uint8_val;
  uint16_t uint16_val;
  uint32_t uint32_val;
  uint64_t uint64_val;
  float float_val;
  double double_val;

  bool operator==(const AllPrimitivesStruct &other) const {
    return bool_val == other.bool_val && int8_val == other.int8_val &&
           int16_val == other.int16_val && int32_val == other.int32_val &&
           int64_val == other.int64_val && uint8_val == other.uint8_val &&
           uint16_val == other.uint16_val && uint32_val == other.uint32_val &&
           uint64_val == other.uint64_val && float_val == other.float_val &&
           double_val == other.double_val;
  }
  FORY_STRUCT(AllPrimitivesStruct, bool_val, int8_val, int16_val, int32_val,
              int64_val, uint8_val, uint16_val, uint32_val, uint64_val,
              float_val, double_val);
};

// String handling
struct StringTestStruct {
  std::string empty;
  std::string ascii;
  std::string utf8;
  std::string long_text;

  bool operator==(const StringTestStruct &other) const {
    return empty == other.empty && ascii == other.ascii && utf8 == other.utf8 &&
           long_text == other.long_text;
  }
  FORY_STRUCT(StringTestStruct, empty, ascii, utf8, long_text);
};

// Nested structs
struct Point2D {
  int32_t x;
  int32_t y;
  bool operator==(const Point2D &other) const {
    return x == other.x && y == other.y;
  }
  FORY_STRUCT(Point2D, x, y);
};

struct Point3D {
  int32_t x;
  int32_t y;
  int32_t z;
  bool operator==(const Point3D &other) const {
    return x == other.x && y == other.y && z == other.z;
  }
  FORY_STRUCT(Point3D, x, y, z);
};

struct Rectangle {
  Point2D top_left;
  Point2D bottom_right;
  bool operator==(const Rectangle &other) const {
    return top_left == other.top_left && bottom_right == other.bottom_right;
  }
  FORY_STRUCT(Rectangle, top_left, bottom_right);
};

struct BoundingBox {
  Rectangle bounds;
  std::string label;
  bool operator==(const BoundingBox &other) const {
    return bounds == other.bounds && label == other.label;
  }
  FORY_STRUCT(BoundingBox, bounds, label);
};

struct Scene {
  Point3D camera;
  Point3D light;
  Rectangle viewport;
  bool operator==(const Scene &other) const {
    return camera == other.camera && light == other.light &&
           viewport == other.viewport;
  }
  FORY_STRUCT(Scene, camera, light, viewport);
};

struct EvolvingStruct {
  int32_t id;

  FORY_STRUCT(EvolvingStruct, id);
};

struct FixedStruct {
  int32_t id;

  FORY_STRUCT(FixedStruct, id);
};

FORY_STRUCT_EVOLVING(FixedStruct, false);

// Containers
struct VectorStruct {
  std::vector<int32_t> numbers;
  std::vector<std::string> strings;
  std::vector<Point2D> points;

  bool operator==(const VectorStruct &other) const {
    return numbers == other.numbers && strings == other.strings &&
           points == other.points;
  }
  FORY_STRUCT(VectorStruct, numbers, strings, points);
};

struct NamedItem {
  int32_t id;
  std::string name;

  bool operator==(const NamedItem &other) const {
    return id == other.id && name == other.name;
  }
  FORY_STRUCT(NamedItem, id, name);
};

struct MapStruct {
  std::map<std::string, int32_t> str_to_int;
  std::map<int32_t, std::string> int_to_str;
  std::map<std::string, Point2D> named_points;

  bool operator==(const MapStruct &other) const {
    return str_to_int == other.str_to_int && int_to_str == other.int_to_str &&
           named_points == other.named_points;
  }
  FORY_STRUCT(MapStruct, str_to_int, int_to_str, named_points);
};

struct NestedContainerStruct {
  std::vector<std::vector<int32_t>> matrix;
  std::map<std::string, std::vector<int32_t>> grouped_numbers;

  bool operator==(const NestedContainerStruct &other) const {
    return matrix == other.matrix && grouped_numbers == other.grouped_numbers;
  }
  FORY_STRUCT(NestedContainerStruct, matrix, grouped_numbers);
};

// Optional fields
struct OptionalFieldsStruct {
  std::string name;
  std::optional<int32_t> age;
  std::optional<std::string> email;
  std::optional<Point2D> location;

  bool operator==(const OptionalFieldsStruct &other) const {
    return name == other.name && age == other.age && email == other.email &&
           location == other.location;
  }
  FORY_STRUCT(OptionalFieldsStruct, name, age, email, location);
};

// Enums
enum class Color { RED = 0, GREEN = 1, BLUE = 2 };
enum class Status : int32_t { PENDING = 0, ACTIVE = 1, COMPLETED = 2 };

struct EnumStruct {
  Color color;
  Status status;
  bool operator==(const EnumStruct &other) const {
    return color == other.color && status == other.status;
  }
  FORY_STRUCT(EnumStruct, color, status);
};

// Real-world scenarios
struct UserProfile {
  int64_t user_id;
  std::string username;
  std::string email;
  std::optional<std::string> bio;
  std::vector<std::string> interests;
  std::map<std::string, std::string> metadata;
  int32_t follower_count;
  bool is_verified;

  bool operator==(const UserProfile &other) const {
    return user_id == other.user_id && username == other.username &&
           email == other.email && bio == other.bio &&
           interests == other.interests && metadata == other.metadata &&
           follower_count == other.follower_count &&
           is_verified == other.is_verified;
  }
  FORY_STRUCT(UserProfile, user_id, username, email, bio, interests, metadata,
              follower_count, is_verified);
};

struct Product {
  int64_t product_id;
  std::string name;
  std::string description;
  double price;
  int32_t stock;
  std::vector<std::string> tags;
  std::map<std::string, std::string> attributes;

  bool operator==(const Product &other) const {
    return product_id == other.product_id && name == other.name &&
           description == other.description && price == other.price &&
           stock == other.stock && tags == other.tags &&
           attributes == other.attributes;
  }
  FORY_STRUCT(Product, product_id, name, description, price, stock, tags,
              attributes);
};

struct OrderItem {
  int64_t product_id;
  int32_t quantity;
  double unit_price;

  bool operator==(const OrderItem &other) const {
    return product_id == other.product_id && quantity == other.quantity &&
           unit_price == other.unit_price;
  }
  FORY_STRUCT(OrderItem, product_id, quantity, unit_price);
};

struct Order {
  int64_t order_id;
  int64_t customer_id;
  std::vector<OrderItem> items;
  double total_amount;
  Status order_status;

  bool operator==(const Order &other) const {
    return order_id == other.order_id && customer_id == other.customer_id &&
           items == other.items && total_amount == other.total_amount &&
           order_status == other.order_status;
  }
  FORY_STRUCT(Order, order_id, customer_id, items, total_amount, order_status);
};

namespace nested_test {
namespace inner {

struct InClassStruct {
  int32_t id;
  std::string name;
  bool operator==(const InClassStruct &other) const {
    return id == other.id && name == other.name;
  }
  FORY_STRUCT(InClassStruct, id, name);
};

struct OutClassStruct {
  int32_t id;
  std::string name;
  bool operator==(const OutClassStruct &other) const {
    return id == other.id && name == other.name;
  }
};

FORY_STRUCT(OutClassStruct, id, name);

} // namespace inner
} // namespace nested_test

namespace external_test {

struct ExternalStruct {
  int32_t id;
  std::string name;
  bool operator==(const ExternalStruct &other) const {
    return id == other.id && name == other.name;
  }
};

FORY_STRUCT(ExternalStruct, id, name);

struct ExternalEmpty {
  bool operator==(const ExternalEmpty & /*other*/) const { return true; }
};

FORY_STRUCT(ExternalEmpty);

} // namespace external_test

// ============================================================================
// TEST IMPLEMENTATION (Inside namespace)
// ============================================================================

namespace fory {
namespace serialization {
namespace test {

// Helper to register all test struct types on a Fory instance
inline void register_all_test_types(Fory &fory) {
  uint32_t type_id = 1;

  // Register all struct types used in tests
  fory.register_struct<SingleFieldStruct>(type_id++);
  fory.register_struct<TwoFieldStruct>(type_id++);
  fory.register_struct<ManyFieldsStruct>(type_id++);
  fory.register_struct<PrivateFieldsStruct>(type_id++);
  fory.register_struct<AllPrimitivesStruct>(type_id++);
  fory.register_struct<StringTestStruct>(type_id++);
  fory.register_struct<Point2D>(type_id++);
  fory.register_struct<Point3D>(type_id++);
  fory.register_struct<Rectangle>(type_id++);
  fory.register_struct<BoundingBox>(type_id++);
  fory.register_struct<Scene>(type_id++);
  fory.register_struct<VectorStruct>(type_id++);
  fory.register_struct<MapStruct>(type_id++);
  fory.register_struct<NestedContainerStruct>(type_id++);
  fory.register_struct<OptionalFieldsStruct>(type_id++);
  fory.register_struct<EnumStruct>(type_id++);
  fory.register_struct<UserProfile>(type_id++);
  fory.register_struct<Product>(type_id++);
  fory.register_struct<OrderItem>(type_id++);
  fory.register_struct<Order>(type_id++);
  fory.register_struct<nested_test::inner::InClassStruct>(type_id++);
  fory.register_struct<nested_test::inner::OutClassStruct>(type_id++);
  fory.register_struct<external_test::ExternalStruct>(type_id++);
  fory.register_struct<external_test::ExternalEmpty>(type_id++);
  fory.register_enum<Color>(type_id++);
  fory.register_enum<Status>(type_id++);
}

template <typename T> void test_roundtrip(const T &original) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  register_all_test_types(fory);

  auto serialize_result = fory.serialize(original);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().to_string();

  std::vector<uint8_t> bytes = std::move(serialize_result).value();
  ASSERT_GT(bytes.size(), 0);

  auto deserialize_result = fory.deserialize<T>(bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().to_string();

  T deserialized = std::move(deserialize_result).value();
  EXPECT_EQ(original, deserialized);
}

// ============================================================================
// TESTS
// ============================================================================

TEST(StructComprehensiveTest, SingleFieldStruct) {
  test_roundtrip(SingleFieldStruct{0});
  test_roundtrip(SingleFieldStruct{42});
  test_roundtrip(SingleFieldStruct{-100});
  test_roundtrip(SingleFieldStruct{INT32_MAX});
  test_roundtrip(SingleFieldStruct{INT32_MIN});
}

TEST(StructComprehensiveTest, TwoFieldStruct) {
  test_roundtrip(TwoFieldStruct{0, 0});
  test_roundtrip(TwoFieldStruct{10, 20});
  test_roundtrip(TwoFieldStruct{-5, 15});
}

TEST(StructComprehensiveTest, ManyFieldsStruct) {
  test_roundtrip(ManyFieldsStruct{true, 127, 32767, 2147483647,
                                  9223372036854775807LL, 3.14f, 2.718,
                                  "Hello, World!"});
  test_roundtrip(ManyFieldsStruct{false, -128, -32768, INT32_MIN,
                                  -9223372036854775807LL - 1, -1.0f, -1.0, ""});
}

TEST(StructComprehensiveTest, FieldTagsMatchFieldConfigSize) {
  FieldConfigTaggedStruct config_obj{1, 2, "config"};
  FieldTagsTaggedStruct tags_obj{1, 2, "config"};

  auto fory_config =
      Fory::builder().xlang(true).compatible(true).track_ref(false).build();
  auto fory_tags =
      Fory::builder().xlang(true).compatible(true).track_ref(false).build();

  ASSERT_TRUE(fory_config.register_struct<FieldConfigTaggedStruct>(101).ok());
  ASSERT_TRUE(fory_tags.register_struct<FieldTagsTaggedStruct>(101).ok());

  auto config_bytes = fory_config.serialize(config_obj);
  ASSERT_TRUE(config_bytes.ok())
      << "Serialization failed: " << config_bytes.error().to_string();
  auto tags_bytes = fory_tags.serialize(tags_obj);
  ASSERT_TRUE(tags_bytes.ok())
      << "Serialization failed: " << tags_bytes.error().to_string();

  EXPECT_EQ(config_bytes->size(), tags_bytes->size());
}

TEST(StructComprehensiveTest, PrivateFieldsStruct) {
  test_roundtrip(PrivateFieldsStruct{42, "secret", {1, 2, 3}});
}

TEST(StructComprehensiveTest, AllPrimitivesZero) {
  test_roundtrip(AllPrimitivesStruct{false, 0, 0, 0, 0, 0, 0, 0, 0, 0.0f, 0.0});
}

TEST(StructComprehensiveTest, AllPrimitivesMax) {
  test_roundtrip(AllPrimitivesStruct{true, INT8_MAX, INT16_MAX, INT32_MAX,
                                     INT64_MAX, UINT8_MAX, UINT16_MAX,
                                     UINT32_MAX, UINT64_MAX, FLT_MAX, DBL_MAX});
}

TEST(StructComprehensiveTest, AllPrimitivesMin) {
  test_roundtrip(AllPrimitivesStruct{false, INT8_MIN, INT16_MIN, INT32_MIN,
                                     INT64_MIN, 0, 0, 0, 0, -FLT_MAX,
                                     -DBL_MAX});
}

TEST(StructComprehensiveTest, StringVariations) {
  test_roundtrip(StringTestStruct{"", "Hello", "UTF-8", "Short"});

  std::string long_str(1000, 'x');
  test_roundtrip(StringTestStruct{"", "ASCII Text", "Emoji", long_str});
}

TEST(StructComprehensiveTest, Point2D) {
  test_roundtrip(Point2D{0, 0});
  test_roundtrip(Point2D{10, 20});
  test_roundtrip(Point2D{-5, 15});
}

TEST(StructComprehensiveTest, Point3D) {
  test_roundtrip(Point3D{0, 0, 0});
  test_roundtrip(Point3D{10, 20, 30});
}

TEST(StructComprehensiveTest, RectangleNested) {
  test_roundtrip(Rectangle{{0, 0}, {10, 10}});
  test_roundtrip(Rectangle{{-5, -5}, {5, 5}});
}

TEST(StructComprehensiveTest, BoundingBoxDoubleNested) {
  test_roundtrip(BoundingBox{{{0, 0}, {100, 100}}, "Main View"});
  test_roundtrip(BoundingBox{{{-50, -50}, {50, 50}}, "Centered"});
}

TEST(StructComprehensiveTest, SceneMultipleNested) {
  test_roundtrip(Scene{{0, 0, 10}, {100, 100, 200}, {{0, 0}, {800, 600}}});
}

TEST(StructComprehensiveTest, VectorStructEmpty) {
  test_roundtrip(VectorStruct{{}, {}, {}});
}

TEST(StructComprehensiveTest, VectorStructMultiple) {
  test_roundtrip(VectorStruct{{1, 2, 3}, {"foo", "bar"}, {{0, 0}, {10, 10}}});
}

TEST(StructComprehensiveTest, NamedStructElementTypeInfo) {
  std::vector<NamedItem> items{{1, "alpha"}, {2, "beta"}};

  auto fory = Fory::builder().xlang(true).track_ref(false).build();
  ASSERT_TRUE(fory.register_struct<NamedItem>("test", "NamedItem").ok());

  auto ser_result = fory.serialize(items);
  ASSERT_TRUE(ser_result.ok())
      << "Serialization failed: " << ser_result.error().to_string();

  std::vector<uint8_t> bytes = std::move(ser_result).value();
  auto deser_result =
      fory.deserialize<std::vector<NamedItem>>(bytes.data(), bytes.size());
  ASSERT_TRUE(deser_result.ok())
      << "Deserialization failed: " << deser_result.error().to_string();

  EXPECT_EQ(items, deser_result.value());
}

TEST(StructComprehensiveTest, MapStructEmpty) {
  test_roundtrip(MapStruct{{}, {}, {}});
}

TEST(StructComprehensiveTest, MapStructMultiple) {
  test_roundtrip(MapStruct{{{"one", 1}, {"two", 2}},
                           {{1, "one"}, {2, "two"}},
                           {{"A", {0, 0}}, {"B", {10, 10}}}});
}

TEST(StructComprehensiveTest, NestedContainers) {
  test_roundtrip(NestedContainerStruct{{{1, 2}, {3, 4}}, {{"a", {10, 20}}}});
}

TEST(StructComprehensiveTest, OptionalFieldsAllEmpty) {
  test_roundtrip(
      OptionalFieldsStruct{"John", std::nullopt, std::nullopt, std::nullopt});
}

TEST(StructComprehensiveTest, OptionalFieldsSome) {
  test_roundtrip(OptionalFieldsStruct{"Jane", 25, std::nullopt, std::nullopt});
}

TEST(StructComprehensiveTest, OptionalFieldsAll) {
  test_roundtrip(
      OptionalFieldsStruct{"Alice", 30, "alice@example.com", Point2D{10, 20}});
}

TEST(StructComprehensiveTest, EnumFields) {
  test_roundtrip(EnumStruct{Color::RED, Status::PENDING});
  test_roundtrip(EnumStruct{Color::GREEN, Status::ACTIVE});
  test_roundtrip(EnumStruct{Color::BLUE, Status::COMPLETED});
}

TEST(StructComprehensiveTest, UserProfileBasic) {
  test_roundtrip(UserProfile{
      12345, "johndoe", "john@example.com", std::nullopt, {}, {}, 0, false});
}

TEST(StructComprehensiveTest, UserProfileComplete) {
  test_roundtrip(UserProfile{67890,
                             "janedoe",
                             "jane@example.com",
                             "Engineer",
                             {"coding", "reading"},
                             {{"location", "SF"}},
                             5000,
                             true});
}

TEST(StructComprehensiveTest, ProductSimple) {
  test_roundtrip(Product{1001,
                         "Widget",
                         "A useful widget",
                         19.99,
                         100,
                         {"tools"},
                         {{"color", "blue"}}});
}

TEST(StructComprehensiveTest, OrderEmpty) {
  test_roundtrip(Order{1, 12345, {}, 0.0, Status::PENDING});
}

TEST(StructComprehensiveTest, OrderMultiple) {
  test_roundtrip(Order{2,
                       67890,
                       {{1001, 2, 19.99}, {2002, 1, 299.99}},
                       389.98,
                       Status::COMPLETED});
}

TEST(StructComprehensiveTest, LargeVectorOfStructs) {
  std::vector<Point2D> points;
  for (int i = 0; i < 1000; ++i) {
    points.push_back({i, i * 2});
  }

  auto fory = Fory::builder().xlang(true).build();
  // Register Point2D for the vector elements
  fory.register_struct<Point2D>(1);

  auto ser_result = fory.serialize(points);
  ASSERT_TRUE(ser_result.ok());

  std::vector<uint8_t> bytes = std::move(ser_result).value();
  auto deser_result =
      fory.deserialize<std::vector<Point2D>>(bytes.data(), bytes.size());
  ASSERT_TRUE(deser_result.ok());
  EXPECT_EQ(points, deser_result.value());
}

TEST(StructComprehensiveTest, NestedNamespaceInClassStruct) {
  test_roundtrip(nested_test::inner::InClassStruct{7, "in"});
}

TEST(StructComprehensiveTest, NestedNamespaceOutClassStruct) {
  test_roundtrip(nested_test::inner::OutClassStruct{8, "out"});
}

TEST(StructComprehensiveTest, ExternalStruct) {
  test_roundtrip(external_test::ExternalStruct{1, "external"});
  test_roundtrip(external_test::ExternalStruct{42, ""});
}

TEST(StructComprehensiveTest, ExternalEmptyStruct) {
  test_roundtrip(external_test::ExternalEmpty{});
}

TEST(StructComprehensiveTest, StructEvolvingOverride) {
  auto fory =
      Fory::builder().xlang(true).compatible(true).track_ref(false).build();
  ASSERT_TRUE(fory.register_struct<EvolvingStruct>(1).ok());
  ASSERT_TRUE(fory.register_struct<FixedStruct>(2).ok());

  auto evolving_info = fory.type_resolver().get_type_info<EvolvingStruct>();
  ASSERT_TRUE(evolving_info.ok());
  EXPECT_EQ(evolving_info.value()->type_id,
            static_cast<uint32_t>(TypeId::COMPATIBLE_STRUCT));

  auto fixed_info = fory.type_resolver().get_type_info<FixedStruct>();
  ASSERT_TRUE(fixed_info.ok());
  EXPECT_EQ(fixed_info.value()->type_id, static_cast<uint32_t>(TypeId::STRUCT));
}

} // namespace test
} // namespace serialization
} // namespace fory
