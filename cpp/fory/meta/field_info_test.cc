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
#include <cstdint>
#include <type_traits>

namespace fory {

namespace test {

struct A {
  int x;
  float y;
  bool z;
  FORY_STRUCT(A, x, y, z);
};

TEST(FieldInfo, Simple) {
  A a;
  constexpr auto info = meta::fory_field_info(a);

  static_assert(info.Size == 3);

  static_assert(info.Name == "A");

  static_assert(info.Names[0] == "x");
  static_assert(info.Names[1] == "y");
  static_assert(info.Names[2] == "z");

  static_assert(std::get<0>(decltype(info)::ptrs()) == &A::x);
  static_assert(std::get<1>(decltype(info)::ptrs()) == &A::y);
  static_assert(std::get<2>(decltype(info)::ptrs()) == &A::z);
}

struct B {
  A a;
  int hidden;
  FORY_STRUCT(B, a);
};

TEST(FieldInfo, Hidden) {
  B b;
  constexpr auto info = meta::fory_field_info(b);

  static_assert(info.Size == 1);

  static_assert(info.Name == "B");

  static_assert(info.Names[0] == "a");

  static_assert(std::get<0>(decltype(info)::ptrs()) == &B::a);
}

struct C {
  const int32_t &value() const { return value_; }
  int32_t &value() { return value_; }
  C &value(int32_t v) {
    value_ = v;
    return *this;
  }

  const int32_t &get_count() const { return count_; }
  C &set_count(int32_t v) {
    count_ = v;
    return *this;
  }

  int32_t value_ = 0;
  int32_t count_ = 0;

  FORY_STRUCT(C, FORY_PROPERTY(value),
              FORY_PROPERTY(count, get_count, set_count, fory::F(7).varint()));
};

TEST(FieldInfo, Property) {
  C c;
  constexpr auto info = meta::fory_field_info(c);

  static_assert(info.Size == 2);
  static_assert(info.Names[0] == "value");
  static_assert(info.Names[1] == "count");

  using ValueEntry = decltype(std::get<0>(decltype(info)::ptrs()));
  using CountEntry = decltype(std::get<1>(decltype(info)::ptrs()));
  static_assert(meta::IsPropertyDescriptorV<ValueEntry>);
  static_assert(meta::IsPropertyDescriptorV<CountEntry>);
  static_assert(std::is_same_v<meta::FieldRawTypeT<C, ValueEntry>, int32_t>);
  static_assert(std::is_same_v<meta::FieldRawTypeT<C, CountEntry>, int32_t>);
  static_assert(std::get<1>(decltype(info)::entries).meta.id_ == 7);
  static_assert(std::get<1>(decltype(info)::entries).meta.encoding_ ==
                Encoding::Varint);

  auto entries = decltype(info)::ptrs();
  field_value_set(c, std::get<0>(entries), 11);
  field_value_set(c, std::get<1>(entries), 22);
  EXPECT_EQ(field_value_get(c, std::get<0>(entries)), 11);
  EXPECT_EQ(field_value_get(c, std::get<1>(entries)), 22);
}

} // namespace test

} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
