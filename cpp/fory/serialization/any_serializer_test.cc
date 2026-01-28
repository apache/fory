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

#include "fory/serialization/any_serializer.h"
#include "fory/serialization/fory.h"
#include "gtest/gtest.h"

#include <any>
#include <string>

namespace fory {
namespace serialization {
namespace test {

struct AnyInnerStruct {
  int32_t id;
  std::string name;

  bool operator==(const AnyInnerStruct &other) const {
    return id == other.id && name == other.name;
  }

  FORY_STRUCT(AnyInnerStruct, id, name);
};

inline bool any_equals(const std::any &left, const std::any &right) {
  if (left.type() != right.type()) {
    return false;
  }
  if (left.type() == typeid(std::string)) {
    return std::any_cast<const std::string &>(left) ==
           std::any_cast<const std::string &>(right);
  }
  if (left.type() == typeid(AnyInnerStruct)) {
    return std::any_cast<const AnyInnerStruct &>(left) ==
           std::any_cast<const AnyInnerStruct &>(right);
  }
  return false;
}

struct AnyHolderStruct {
  std::any first;
  std::any second;

  bool operator==(const AnyHolderStruct &other) const {
    return any_equals(first, other.first) && any_equals(second, other.second);
  }

  FORY_STRUCT(AnyHolderStruct, first, second);
};

TEST(AnySerializerTest, RoundTripStructFields) {
  auto fory = Fory::builder().xlang(true).track_ref(false).build();

  ASSERT_TRUE(fory.register_struct<AnyInnerStruct>(1).ok());
  ASSERT_TRUE(fory.register_struct<AnyHolderStruct>(2).ok());

  ASSERT_TRUE(register_any_type<std::string>(fory.type_resolver()).ok());
  ASSERT_TRUE(register_any_type<AnyInnerStruct>(fory.type_resolver()).ok());

  AnyHolderStruct original;
  original.first = std::string("hello any");
  original.second = AnyInnerStruct{42, "nested"};

  auto serialize_result = fory.serialize(original);
  ASSERT_TRUE(serialize_result.ok())
      << "Serialization failed: " << serialize_result.error().to_string();

  std::vector<uint8_t> bytes = std::move(serialize_result).value();
  auto deserialize_result =
      fory.deserialize<AnyHolderStruct>(bytes.data(), bytes.size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().to_string();

  AnyHolderStruct deserialized = std::move(deserialize_result).value();
  EXPECT_EQ(original, deserialized);
}

} // namespace test
} // namespace serialization
} // namespace fory
