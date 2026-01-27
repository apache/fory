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

#include "gtest/gtest.h"

#include "fory/meta/field.h"
#include "fory/meta/field_info.h"
#include <memory>
#include <optional>
#include <string>

namespace fory {

namespace test {

// ============================================================================
// Type Traits Tests
// ============================================================================

TEST(FieldTraits, IsSharedPtr) {
  static_assert(!detail::is_shared_ptr_v<int>);
  static_assert(!detail::is_shared_ptr_v<std::string>);
  static_assert(!detail::is_shared_ptr_v<std::unique_ptr<int>>);
  static_assert(detail::is_shared_ptr_v<std::shared_ptr<int>>);
  static_assert(detail::is_shared_ptr_v<std::shared_ptr<std::string>>);
}

TEST(FieldTraits, IsUniquePtr) {
  static_assert(!detail::is_unique_ptr_v<int>);
  static_assert(!detail::is_unique_ptr_v<std::string>);
  static_assert(!detail::is_unique_ptr_v<std::shared_ptr<int>>);
  static_assert(detail::is_unique_ptr_v<std::unique_ptr<int>>);
  static_assert(detail::is_unique_ptr_v<std::unique_ptr<std::string>>);
}

TEST(FieldTraits, IsOptional) {
  static_assert(!detail::is_optional_v<int>);
  static_assert(!detail::is_optional_v<std::string>);
  static_assert(!detail::is_optional_v<std::shared_ptr<int>>);
  static_assert(detail::is_optional_v<std::optional<int>>);
  static_assert(detail::is_optional_v<std::optional<std::string>>);
}

TEST(FieldTraits, IsSmartPtr) {
  static_assert(!detail::is_smart_ptr_v<int>);
  static_assert(!detail::is_smart_ptr_v<std::string>);
  static_assert(!detail::is_smart_ptr_v<std::optional<int>>);
  static_assert(detail::is_smart_ptr_v<std::shared_ptr<int>>);
  static_assert(detail::is_smart_ptr_v<std::unique_ptr<int>>);
}

// ============================================================================
// fory::field<> Basic Tests
// ============================================================================

TEST(Field, BasicPrimitive) {
  using FieldType = field<int32_t, 0>;
  static_assert(FieldType::tag_id == 0);
  static_assert(FieldType::is_nullable == false);
  static_assert(FieldType::track_ref == false);

  FieldType f;
  f = 42;
  EXPECT_EQ(f.value, 42);

  // Implicit conversion
  int32_t val = f;
  EXPECT_EQ(val, 42);
}

TEST(Field, BasicString) {
  using FieldType = field<std::string, 1>;
  static_assert(FieldType::tag_id == 1);
  static_assert(FieldType::is_nullable == false);
  static_assert(FieldType::track_ref == false);

  FieldType f;
  f = "hello";
  EXPECT_EQ(f.value, "hello");
}

TEST(Field, OptionalField) {
  // std::optional is inherently nullable
  using FieldType = field<std::optional<int32_t>, 2>;
  static_assert(FieldType::tag_id == 2);
  static_assert(FieldType::is_nullable == true);
  static_assert(FieldType::track_ref == false);

  FieldType f;
  f = std::optional<int32_t>(123);
  EXPECT_TRUE(f.value.has_value());
  EXPECT_EQ(*f.value, 123);
}

TEST(Field, SharedPtrNonNullable) {
  // shared_ptr is non-nullable by default
  using FieldType = field<std::shared_ptr<int32_t>, 3>;
  static_assert(FieldType::tag_id == 3);
  static_assert(FieldType::is_nullable == false);
  static_assert(FieldType::track_ref == true);

  FieldType f;
  f = std::make_shared<int32_t>(99);
  EXPECT_NE(f.value, nullptr);
  EXPECT_EQ(*f.value, 99);
}

TEST(Field, SharedPtrNullable) {
  // shared_ptr with nullable option
  using FieldType = field<std::shared_ptr<int32_t>, 4, nullable>;
  static_assert(FieldType::tag_id == 4);
  static_assert(FieldType::is_nullable == true);
  static_assert(FieldType::track_ref == true);

  FieldType f;
  EXPECT_EQ(f.value, nullptr); // Default is null
}

TEST(Field, SharedPtrWithRef) {
  // shared_ptr with ref tracking
  using FieldType = field<std::shared_ptr<int32_t>, 5, ref>;
  static_assert(FieldType::tag_id == 5);
  static_assert(FieldType::is_nullable == false);
  static_assert(FieldType::track_ref == true);
}

TEST(Field, SharedPtrNullableWithRef) {
  // shared_ptr with both nullable and ref
  using FieldType = field<std::shared_ptr<int32_t>, 6, nullable, ref>;
  static_assert(FieldType::tag_id == 6);
  static_assert(FieldType::is_nullable == true);
  static_assert(FieldType::track_ref == true);
}

TEST(Field, UniquePtrNonNullable) {
  // unique_ptr is non-nullable by default
  using FieldType = field<std::unique_ptr<int32_t>, 7>;
  static_assert(FieldType::tag_id == 7);
  static_assert(FieldType::is_nullable == false);
  static_assert(FieldType::track_ref == false); // ref not valid for unique_ptr
}

TEST(Field, UniquePtrNullable) {
  // unique_ptr with nullable option
  using FieldType = field<std::unique_ptr<int32_t>, 8, nullable>;
  static_assert(FieldType::tag_id == 8);
  static_assert(FieldType::is_nullable == true);
  static_assert(FieldType::track_ref == false);
}

TEST(Field, SharedPtrNotNull) {
  // shared_ptr with not_null option (explicit non-nullable)
  using FieldType = field<std::shared_ptr<int32_t>, 9, not_null>;
  static_assert(FieldType::tag_id == 9);
  static_assert(FieldType::is_nullable == false);
  static_assert(FieldType::track_ref == true);
}

TEST(Field, SharedPtrNotNullWithRef) {
  // shared_ptr with not_null and ref options
  using FieldType = field<std::shared_ptr<int32_t>, 10, not_null, ref>;
  static_assert(FieldType::tag_id == 10);
  static_assert(FieldType::is_nullable == false);
  static_assert(FieldType::track_ref == true);
}

// ============================================================================
// fory::field<> Type Traits Tests
// ============================================================================

TEST(FieldTraits, IsForyField) {
  static_assert(!is_fory_field_v<int>);
  static_assert(!is_fory_field_v<std::string>);
  static_assert(!is_fory_field_v<std::shared_ptr<int>>);
  static_assert(is_fory_field_v<field<int, 0>>);
  static_assert(is_fory_field_v<field<std::string, 1>>);
  static_assert(is_fory_field_v<field<std::shared_ptr<int>, 2, nullable>>);
}

TEST(FieldTraits, UnwrapField) {
  static_assert(std::is_same_v<unwrap_field_t<int>, int>);
  static_assert(std::is_same_v<unwrap_field_t<std::string>, std::string>);
  static_assert(std::is_same_v<unwrap_field_t<field<int, 0>>, int>);
  static_assert(
      std::is_same_v<unwrap_field_t<field<std::string, 1>>, std::string>);
  static_assert(
      std::is_same_v<unwrap_field_t<field<std::shared_ptr<int>, 2, nullable>>,
                     std::shared_ptr<int>>);
}

TEST(FieldTraits, FieldTagId) {
  static_assert(field_tag_id_v<int> == -1);
  static_assert(field_tag_id_v<field<int, 0>> == 0);
  static_assert(field_tag_id_v<field<std::string, 42>> == 42);
}

TEST(FieldTraits, FieldIsNullable) {
  static_assert(field_is_nullable_v<int> == false);
  static_assert(field_is_nullable_v<std::optional<int>> == true);
  static_assert(field_is_nullable_v<field<int, 0>> == false);
  static_assert(field_is_nullable_v<field<std::optional<int>, 1>> == true);
  static_assert(field_is_nullable_v<field<std::shared_ptr<int>, 2>> == false);
  static_assert(field_is_nullable_v<field<std::shared_ptr<int>, 3, nullable>> ==
                true);
}

TEST(FieldTraits, FieldTrackRef) {
  static_assert(field_track_ref_v<int> == false);
  static_assert(field_track_ref_v<std::shared_ptr<int>> == true);
  static_assert(field_track_ref_v<field<int, 0>> == false);
  static_assert(field_track_ref_v<field<std::shared_ptr<int>, 1>> == true);
  static_assert(field_track_ref_v<field<std::shared_ptr<int>, 2, ref>> == true);
  static_assert(
      field_track_ref_v<field<std::shared_ptr<int>, 3, nullable, ref>> == true);
}

// ============================================================================
// Struct with fory::field<> members
// ============================================================================

struct Person {
  field<std::string, 0> name;
  field<int32_t, 1> age;
  field<std::optional<std::string>, 2> nickname;
  field<std::shared_ptr<Person>, 3, ref> parent;
  field<std::shared_ptr<Person>, 4, nullable> guardian;
  FORY_STRUCT(Person, name, age, nickname, parent, guardian);
};

TEST(FieldStruct, BasicUsage) {
  Person p;
  p.name = "Alice";
  p.age = 30;
  p.nickname = std::optional<std::string>("Ali");
  p.parent = nullptr;
  p.guardian = nullptr;

  EXPECT_EQ(p.name.value, "Alice");
  EXPECT_EQ(p.age.value, 30);
  EXPECT_TRUE(p.nickname.value.has_value());
  EXPECT_EQ(*p.nickname.value, "Ali");
  EXPECT_EQ(p.parent.value, nullptr);
  EXPECT_EQ(p.guardian.value, nullptr);
}

TEST(FieldStruct, FieldInfo) {
  Person p;
  constexpr auto info = meta::ForyFieldInfo(p);

  static_assert(info.Size == 5);
  static_assert(info.Name == "Person");
  static_assert(info.Names[0] == "name");
  static_assert(info.Names[1] == "age");
  static_assert(info.Names[2] == "nickname");
  static_assert(info.Names[3] == "parent");
  static_assert(info.Names[4] == "guardian");
}

} // namespace test

} // namespace fory

// ============================================================================
// FORY_FIELD_TAGS Macro Tests
// ============================================================================

namespace field_tags_test {

// Test struct with pure C++ types (no fory::field wrappers)
struct Document {
  std::string title;
  int32_t version;
  std::optional<std::string> description;
  std::shared_ptr<Document> author;
  std::shared_ptr<Document> reviewer;
  std::shared_ptr<Document> parent;
  std::unique_ptr<std::string> metadata;
  FORY_STRUCT(Document, title, version, description, author, reviewer, parent,
              metadata);
};

// Test struct with nullable + ref combined
struct Node {
  std::string name;
  std::shared_ptr<Node> left;
  std::shared_ptr<Node> right;
  FORY_STRUCT(Node, name, left, right);
};

// Test with single field
struct SingleField {
  int32_t value;
  FORY_STRUCT(SingleField, value);
};

// Define field tags in the same namespace as the types.
FORY_FIELD_TAGS(Document, (title, 0),     // string: non-nullable
                (version, 1),             // int: non-nullable
                (description, 2),         // optional: inherently nullable
                (author, 3),              // shared_ptr: non-nullable (default)
                (reviewer, 4, nullable),  // shared_ptr: nullable
                (parent, 5, ref),         // shared_ptr: non-nullable, ref
                (metadata, 6, nullable)); // unique_ptr: nullable

FORY_FIELD_TAGS(Node, (name, 0), (left, 1, nullable, ref),
                (right, 2, nullable, ref));

FORY_FIELD_TAGS(SingleField, (value, 0));

} // namespace field_tags_test

namespace fory {
namespace test {

using field_tags_test::Document;
using field_tags_test::Node;
using field_tags_test::SingleField;

TEST(FieldTags, HasTags) {
  static_assert(detail::has_field_tags_v<Document> == true);
  static_assert(detail::has_field_tags_v<Person> == false); // Uses fory::field
  static_assert(detail::has_field_tags_v<int> == false);
}

TEST(FieldTags, FieldCount) {
  static_assert(detail::FieldTagsInfo<Document>::field_count == 7);
}

TEST(FieldTags, TagIds) {
  // Check tag IDs
  static_assert(detail::GetFieldTagEntry<Document, 0>::id == 0);
  static_assert(detail::GetFieldTagEntry<Document, 1>::id == 1);
  static_assert(detail::GetFieldTagEntry<Document, 2>::id == 2);
  static_assert(detail::GetFieldTagEntry<Document, 3>::id == 3);
  static_assert(detail::GetFieldTagEntry<Document, 4>::id == 4);
  static_assert(detail::GetFieldTagEntry<Document, 5>::id == 5);
  static_assert(detail::GetFieldTagEntry<Document, 6>::id == 6);
}

TEST(FieldTags, Nullability) {
  // title (string): non-nullable
  static_assert(detail::GetFieldTagEntry<Document, 0>::is_nullable == false);
  // version (int): non-nullable
  static_assert(detail::GetFieldTagEntry<Document, 1>::is_nullable == false);
  // description (optional): inherently nullable
  static_assert(detail::GetFieldTagEntry<Document, 2>::is_nullable == true);
  // author (shared_ptr): non-nullable (default)
  static_assert(detail::GetFieldTagEntry<Document, 3>::is_nullable == false);
  // reviewer (shared_ptr, nullable): nullable
  static_assert(detail::GetFieldTagEntry<Document, 4>::is_nullable == true);
  // parent (shared_ptr, ref): non-nullable
  static_assert(detail::GetFieldTagEntry<Document, 5>::is_nullable == false);
  // metadata (unique_ptr, nullable): nullable
  static_assert(detail::GetFieldTagEntry<Document, 6>::is_nullable == true);
}

TEST(FieldTags, RefTracking) {
  // shared_ptr fields track refs by default
  static_assert(detail::GetFieldTagEntry<Document, 0>::track_ref == false);
  static_assert(detail::GetFieldTagEntry<Document, 1>::track_ref == false);
  static_assert(detail::GetFieldTagEntry<Document, 2>::track_ref == false);
  static_assert(detail::GetFieldTagEntry<Document, 3>::track_ref == true);
  static_assert(detail::GetFieldTagEntry<Document, 4>::track_ref == true);
  static_assert(detail::GetFieldTagEntry<Document, 5>::track_ref == true);
  static_assert(detail::GetFieldTagEntry<Document, 6>::track_ref == false);
}

TEST(FieldTags, NullableWithRef) {
  // name: non-nullable, no ref
  static_assert(detail::GetFieldTagEntry<Node, 0>::id == 0);
  static_assert(detail::GetFieldTagEntry<Node, 0>::is_nullable == false);
  static_assert(detail::GetFieldTagEntry<Node, 0>::track_ref == false);

  // left: nullable + ref
  static_assert(detail::GetFieldTagEntry<Node, 1>::id == 1);
  static_assert(detail::GetFieldTagEntry<Node, 1>::is_nullable == true);
  static_assert(detail::GetFieldTagEntry<Node, 1>::track_ref == true);

  // right: nullable + ref
  static_assert(detail::GetFieldTagEntry<Node, 2>::id == 2);
  static_assert(detail::GetFieldTagEntry<Node, 2>::is_nullable == true);
  static_assert(detail::GetFieldTagEntry<Node, 2>::track_ref == true);
}

TEST(FieldTags, SingleField) {
  static_assert(detail::has_field_tags_v<SingleField> == true);
  static_assert(detail::FieldTagsInfo<SingleField>::field_count == 1);
  static_assert(detail::GetFieldTagEntry<SingleField, 0>::id == 0);
  static_assert(detail::GetFieldTagEntry<SingleField, 0>::is_nullable == false);
  static_assert(detail::GetFieldTagEntry<SingleField, 0>::track_ref == false);
}

} // namespace test

} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
