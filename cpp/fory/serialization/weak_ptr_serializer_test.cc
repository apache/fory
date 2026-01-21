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
#include "gtest/gtest.h"
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace fory {
namespace serialization {
namespace {

// ============================================================================
// Test Helpers
// ============================================================================

Fory create_serializer(bool track_ref = true) {
  return Fory::builder().track_ref(track_ref).build();
}

// ============================================================================
// Basic SharedWeak Tests
// ============================================================================

TEST(SharedWeakTest, DefaultConstructor) {
  SharedWeak<int32_t> weak;
  EXPECT_TRUE(weak.expired());
  EXPECT_EQ(weak.upgrade(), nullptr);
  EXPECT_EQ(weak.use_count(), 0);
}

TEST(SharedWeakTest, FromSharedPtr) {
  auto strong = std::make_shared<int32_t>(42);
  SharedWeak<int32_t> weak = SharedWeak<int32_t>::from(strong);

  EXPECT_FALSE(weak.expired());
  EXPECT_EQ(weak.use_count(), 1);

  auto upgraded = weak.upgrade();
  ASSERT_NE(upgraded, nullptr);
  EXPECT_EQ(*upgraded, 42);
  EXPECT_EQ(upgraded, strong);
}

TEST(SharedWeakTest, FromWeakPtr) {
  auto strong = std::make_shared<int32_t>(123);
  std::weak_ptr<int32_t> std_weak = strong;
  SharedWeak<int32_t> weak = SharedWeak<int32_t>::from_weak(std_weak);

  EXPECT_FALSE(weak.expired());
  auto upgraded = weak.upgrade();
  ASSERT_NE(upgraded, nullptr);
  EXPECT_EQ(*upgraded, 123);
}

TEST(SharedWeakTest, Update) {
  SharedWeak<int32_t> weak;
  EXPECT_TRUE(weak.expired());

  auto strong = std::make_shared<int32_t>(999);
  weak.update(std::weak_ptr<int32_t>(strong));

  EXPECT_FALSE(weak.expired());
  auto upgraded = weak.upgrade();
  ASSERT_NE(upgraded, nullptr);
  EXPECT_EQ(*upgraded, 999);
}

TEST(SharedWeakTest, ClonesShareInternalStorage) {
  auto strong = std::make_shared<int32_t>(100);
  SharedWeak<int32_t> weak1 = SharedWeak<int32_t>::from(strong);
  SharedWeak<int32_t> weak2 = weak1; // Copy

  // Both should point to the same object
  EXPECT_EQ(weak1.upgrade(), weak2.upgrade());

  // Now update via weak2 with a new object
  auto new_strong = std::make_shared<int32_t>(200);
  weak2.update(std::weak_ptr<int32_t>(new_strong));

  // weak1 should also see the update (they share internal storage)
  auto upgraded1 = weak1.upgrade();
  auto upgraded2 = weak2.upgrade();
  ASSERT_NE(upgraded1, nullptr);
  ASSERT_NE(upgraded2, nullptr);
  EXPECT_EQ(*upgraded1, 200);
  EXPECT_EQ(*upgraded2, 200);
  EXPECT_EQ(upgraded1, upgraded2);
}

TEST(SharedWeakTest, ExpiresWhenStrongDestroyed) {
  SharedWeak<int32_t> weak;
  {
    auto strong = std::make_shared<int32_t>(42);
    weak = SharedWeak<int32_t>::from(strong);
    EXPECT_FALSE(weak.expired());
  }
  // strong is now out of scope
  EXPECT_TRUE(weak.expired());
  EXPECT_EQ(weak.upgrade(), nullptr);
}

TEST(SharedWeakTest, OwnerEquals) {
  auto strong1 = std::make_shared<int32_t>(1);
  auto strong2 = std::make_shared<int32_t>(1); // Same value but different ptr

  SharedWeak<int32_t> weak1a = SharedWeak<int32_t>::from(strong1);
  SharedWeak<int32_t> weak1b = SharedWeak<int32_t>::from(strong1);
  SharedWeak<int32_t> weak2 = SharedWeak<int32_t>::from(strong2);

  EXPECT_TRUE(weak1a.owner_equals(weak1b));
  EXPECT_FALSE(weak1a.owner_equals(weak2));
}

// ============================================================================
// Serialization Test Structs
// ============================================================================

struct SimpleStruct {
  int32_t value;
};
FORY_STRUCT(SimpleStruct, value);

struct StructWithWeak {
  int32_t id;
  SharedWeak<SimpleStruct> weak_ref;
};
FORY_STRUCT(StructWithWeak, id, weak_ref);

struct StructWithBothRefs {
  int32_t id;
  std::shared_ptr<SimpleStruct> strong_ref;
  SharedWeak<SimpleStruct> weak_ref;
};
FORY_STRUCT(StructWithBothRefs, id, strong_ref, weak_ref);

struct MultipleWeakRefsWithOwner {
  std::shared_ptr<SimpleStruct> owner;
  SharedWeak<SimpleStruct> weak1;
  SharedWeak<SimpleStruct> weak2;
  SharedWeak<SimpleStruct> weak3;
};
FORY_STRUCT(MultipleWeakRefsWithOwner, owner, weak1, weak2, weak3);

struct NodeWithParent {
  int32_t value;
  SharedWeak<NodeWithParent> parent;
  std::vector<std::shared_ptr<NodeWithParent>> children;
};
FORY_STRUCT(NodeWithParent, value, parent, children);

// ============================================================================
// Serialization Tests
// ============================================================================

TEST(WeakPtrSerializerTest, NullWeakRoundTrip) {
  StructWithWeak original;
  original.id = 42;
  original.weak_ref = SharedWeak<SimpleStruct>(); // Empty weak

  auto fory = create_serializer(true);
  fory.register_struct<SimpleStruct>(100);
  fory.register_struct<StructWithWeak>(101);

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<StructWithWeak>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  const auto &deserialized = deserialize_result.value();
  EXPECT_EQ(deserialized.id, 42);
  EXPECT_TRUE(deserialized.weak_ref.expired());
  EXPECT_EQ(deserialized.weak_ref.upgrade(), nullptr);
}

TEST(WeakPtrSerializerTest, ValidWeakRoundTrip) {
  // Use a structure that has both strong and weak refs to the same object.
  // The strong ref keeps the object alive after deserialization.
  auto target = std::make_shared<SimpleStruct>();
  target->value = 123;

  StructWithBothRefs original;
  original.id = 1;
  original.strong_ref = target;
  original.weak_ref = SharedWeak<SimpleStruct>::from(target);

  auto fory = create_serializer(true);
  fory.register_struct<SimpleStruct>(100);
  fory.register_struct<StructWithBothRefs>(101);

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<StructWithBothRefs>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  const auto &deserialized = deserialize_result.value();
  EXPECT_EQ(deserialized.id, 1);

  // The strong_ref keeps the object alive
  ASSERT_NE(deserialized.strong_ref, nullptr);
  EXPECT_EQ(deserialized.strong_ref->value, 123);

  // The weak should upgrade to the same object as strong_ref
  auto upgraded = deserialized.weak_ref.upgrade();
  ASSERT_NE(upgraded, nullptr);
  EXPECT_EQ(upgraded->value, 123);
  EXPECT_EQ(upgraded, deserialized.strong_ref);
}

TEST(WeakPtrSerializerTest, MultipleWeakToSameTarget) {
  // Use a structure with an owner (strong ref) plus multiple weak refs.
  // The owner keeps the target alive after deserialization.
  auto target = std::make_shared<SimpleStruct>();
  target->value = 999;

  MultipleWeakRefsWithOwner original;
  original.owner = target;
  original.weak1 = SharedWeak<SimpleStruct>::from(target);
  original.weak2 = SharedWeak<SimpleStruct>::from(target);
  original.weak3 = SharedWeak<SimpleStruct>::from(target);

  auto fory = create_serializer(true);
  fory.register_struct<SimpleStruct>(100);
  fory.register_struct<MultipleWeakRefsWithOwner>(102);

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<MultipleWeakRefsWithOwner>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  const auto &deserialized = deserialize_result.value();

  // The owner keeps the object alive
  ASSERT_NE(deserialized.owner, nullptr);
  EXPECT_EQ(deserialized.owner->value, 999);

  // All three weak pointers should upgrade to the same object as owner
  auto upgraded1 = deserialized.weak1.upgrade();
  auto upgraded2 = deserialized.weak2.upgrade();
  auto upgraded3 = deserialized.weak3.upgrade();

  ASSERT_NE(upgraded1, nullptr);
  ASSERT_NE(upgraded2, nullptr);
  ASSERT_NE(upgraded3, nullptr);

  EXPECT_EQ(upgraded1->value, 999);
  EXPECT_EQ(upgraded1, deserialized.owner);
  EXPECT_EQ(upgraded1, upgraded2);
  EXPECT_EQ(upgraded2, upgraded3);
}

TEST(WeakPtrSerializerTest, ParentChildGraph) {
  // Create parent
  auto parent = std::make_shared<NodeWithParent>();
  parent->value = 1;
  parent->parent = SharedWeak<NodeWithParent>(); // No parent

  // Create children that point back to parent
  auto child1 = std::make_shared<NodeWithParent>();
  child1->value = 2;
  child1->parent = SharedWeak<NodeWithParent>::from(parent);

  auto child2 = std::make_shared<NodeWithParent>();
  child2->value = 3;
  child2->parent = SharedWeak<NodeWithParent>::from(parent);

  parent->children.push_back(child1);
  parent->children.push_back(child2);

  auto fory = create_serializer(true);
  fory.register_struct<NodeWithParent>(103);

  auto bytes_result = fory.serialize(parent);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<std::shared_ptr<NodeWithParent>>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_NE(deserialized, nullptr);
  EXPECT_EQ(deserialized->value, 1);
  EXPECT_TRUE(deserialized->parent.expired()); // Root has no parent

  // Check children
  ASSERT_EQ(deserialized->children.size(), 2u);

  auto des_child1 = deserialized->children[0];
  ASSERT_NE(des_child1, nullptr);
  EXPECT_EQ(des_child1->value, 2);

  auto des_child2 = deserialized->children[1];
  ASSERT_NE(des_child2, nullptr);
  EXPECT_EQ(des_child2->value, 3);

  // Verify parent references point back to the deserialized parent
  auto parent_from_child1 = des_child1->parent.upgrade();
  auto parent_from_child2 = des_child2->parent.upgrade();

  ASSERT_NE(parent_from_child1, nullptr);
  ASSERT_NE(parent_from_child2, nullptr);
  EXPECT_EQ(parent_from_child1, deserialized);
  EXPECT_EQ(parent_from_child2, deserialized);
}

TEST(WeakPtrSerializerTest, ForwardReferenceResolution) {
  // Create a structure where the weak reference appears before the strong ref
  // This tests forward reference resolution

  // In this setup, child1's parent weak appears before the actual parent object
  // The serialization order depends on struct field order

  auto parent = std::make_shared<NodeWithParent>();
  parent->value = 100;

  auto child = std::make_shared<NodeWithParent>();
  child->value = 200;
  child->parent = SharedWeak<NodeWithParent>::from(parent);

  // Parent's children list includes the child
  parent->children.push_back(child);

  auto fory = create_serializer(true);
  fory.register_struct<NodeWithParent>(103);

  auto bytes_result = fory.serialize(parent);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<std::shared_ptr<NodeWithParent>>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();

  // Verify the circular reference is preserved
  ASSERT_EQ(deserialized->children.size(), 1u);
  auto des_child = deserialized->children[0];
  auto parent_from_child = des_child->parent.upgrade();
  ASSERT_NE(parent_from_child, nullptr);
  EXPECT_EQ(parent_from_child, deserialized);
}

TEST(WeakPtrSerializerTest, DeepNestedGraph) {
  // Create a deep chain: A -> B -> C with each child having weak to parent
  auto nodeA = std::make_shared<NodeWithParent>();
  nodeA->value = 1;

  auto nodeB = std::make_shared<NodeWithParent>();
  nodeB->value = 2;
  nodeB->parent = SharedWeak<NodeWithParent>::from(nodeA);

  auto nodeC = std::make_shared<NodeWithParent>();
  nodeC->value = 3;
  nodeC->parent = SharedWeak<NodeWithParent>::from(nodeB);

  nodeA->children.push_back(nodeB);
  nodeB->children.push_back(nodeC);

  auto fory = create_serializer(true);
  fory.register_struct<NodeWithParent>(103);

  auto bytes_result = fory.serialize(nodeA);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<std::shared_ptr<NodeWithParent>>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto desA = std::move(deserialize_result).value();
  ASSERT_NE(desA, nullptr);
  EXPECT_EQ(desA->value, 1);
  EXPECT_TRUE(desA->parent.expired());

  ASSERT_EQ(desA->children.size(), 1u);
  auto desB = desA->children[0];
  EXPECT_EQ(desB->value, 2);
  EXPECT_EQ(desB->parent.upgrade(), desA);

  ASSERT_EQ(desB->children.size(), 1u);
  auto desC = desB->children[0];
  EXPECT_EQ(desC->value, 3);
  EXPECT_EQ(desC->parent.upgrade(), desB);
}

// ============================================================================
// Error Cases
// ============================================================================

TEST(WeakPtrSerializerTest, RequiresTrackRef) {
  auto target = std::make_shared<SimpleStruct>();
  target->value = 42;

  StructWithWeak original;
  original.id = 1;
  original.weak_ref = SharedWeak<SimpleStruct>::from(target);

  // Create serializer WITHOUT track_ref
  auto fory = Fory::builder().track_ref(false).build();
  fory.register_struct<SimpleStruct>(100);
  fory.register_struct<StructWithWeak>(101);

  auto bytes_result = fory.serialize(original);
  EXPECT_FALSE(bytes_result.ok()) << "Should fail when track_ref is disabled";
  EXPECT_TRUE(bytes_result.error().to_string().find("track_ref") !=
              std::string::npos);
}

// ============================================================================
// Type Traits Tests
// ============================================================================

TEST(WeakPtrSerializerTest, TypeTraits) {
  // Test is_nullable
  EXPECT_TRUE(is_nullable_v<SharedWeak<int32_t>>);
  EXPECT_TRUE(is_nullable_v<SharedWeak<SimpleStruct>>);

  // Test is_nullable
  EXPECT_TRUE(is_nullable_v<SharedWeak<int32_t>>);
  EXPECT_TRUE(is_nullable_v<SharedWeak<SimpleStruct>>);

  // Test is_shared_weak
  EXPECT_TRUE(is_shared_weak_v<SharedWeak<int32_t>>);
  EXPECT_TRUE(is_shared_weak_v<SharedWeak<SimpleStruct>>);
  EXPECT_FALSE(is_shared_weak_v<std::shared_ptr<int32_t>>);
  EXPECT_FALSE(is_shared_weak_v<std::weak_ptr<int32_t>>);
  EXPECT_FALSE(is_shared_weak_v<int32_t>);
}

} // namespace
} // namespace serialization
} // namespace fory
