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
#include <type_traits>

#include "fory/encoder/row_encode_trait.h"
#include "fory/encoder/row_encoder.h"
#include "fory/row/writer.h"

namespace fory {
namespace row {

namespace test2 {

struct A {
  float a;
  std::string b;
};

FORY_FIELD_INFO(A, a, b);

struct B {
  int x;
  A y;
};

FORY_FIELD_INFO(B, x, y);

TEST(RowEncoder, Simple) {
  B v{233, {1.23, "hello"}};

  encoder::RowEncoder<B> enc;

  auto &schema = enc.GetSchema();
  ASSERT_EQ(schema.field_names(), (std::vector<std::string>{"x", "y"}));
  ASSERT_EQ(schema.field(0)->type()->name(), "int32");
  ASSERT_EQ(schema.field(1)->type()->name(), "struct");
  ASSERT_EQ(schema.field(1)->type()->field(0)->name(), "a");
  ASSERT_EQ(schema.field(1)->type()->field(1)->name(), "b");
  ASSERT_EQ(schema.field(1)->type()->field(0)->type()->name(), "float");
  ASSERT_EQ(schema.field(1)->type()->field(1)->type()->name(), "utf8");

  enc.Encode(v);

  auto row = enc.GetWriter().ToRow();
  ASSERT_EQ(row->GetInt32(0), 233);
  auto y_row = row->GetStruct(1);
  ASSERT_EQ(y_row->GetString(1), "hello");
  ASSERT_FLOAT_EQ(y_row->GetFloat(0), 1.23);
}

struct C {
  std::vector<A> x;
  bool y;
};

FORY_FIELD_INFO(C, x, y);

TEST(RowEncoder, SimpleArray) {
  std::vector<C> v{C{{{1, "a"}, {2, "b"}}, false},
                   C{{{1.1, "x"}, {2.2, "y"}, {3.3, "z"}}, true}};

  encoder::RowEncoder<decltype(v)> enc;

  auto &type = enc.GetType();
  ASSERT_EQ(type.name(), "list");
  ASSERT_EQ(type.field(0)->type()->name(), "struct");
  ASSERT_EQ(type.field(0)->type()->field(0)->name(), "x");
  ASSERT_EQ(type.field(0)->type()->field(1)->name(), "y");
  ASSERT_EQ(type.field(0)->type()->field(0)->type()->name(), "list");
  ASSERT_EQ(type.field(0)->type()->field(0)->type()->field(0)->type()->name(),
            "struct");
  ASSERT_EQ(type.field(0)
                ->type()
                ->field(0)
                ->type()
                ->field(0)
                ->type()
                ->field(0)
                ->type()
                ->name(),
            "float");
  ASSERT_EQ(type.field(0)
                ->type()
                ->field(0)
                ->type()
                ->field(0)
                ->type()
                ->field(1)
                ->type()
                ->name(),
            "utf8");
  ASSERT_EQ(type.field(0)->type()->field(1)->type()->name(), "bool");

  enc.Encode(v);

  auto data = enc.GetWriter().CopyToArrayData();
  ASSERT_EQ(data->GetStruct(0)->GetArray(0)->GetStruct(0)->GetFloat(0), 1);
  ASSERT_EQ(data->GetStruct(0)->GetArray(0)->GetStruct(1)->GetFloat(0), 2);
  ASSERT_FLOAT_EQ(data->GetStruct(1)->GetArray(0)->GetStruct(0)->GetFloat(0),
                  1.1);
  ASSERT_FLOAT_EQ(data->GetStruct(1)->GetArray(0)->GetStruct(1)->GetFloat(0),
                  2.2);
  ASSERT_FLOAT_EQ(data->GetStruct(1)->GetArray(0)->GetStruct(2)->GetFloat(0),
                  3.3);
  ASSERT_EQ(data->GetStruct(0)->GetArray(0)->GetStruct(0)->GetString(1), "a");
  ASSERT_EQ(data->GetStruct(0)->GetArray(0)->GetStruct(1)->GetString(1), "b");
  ASSERT_EQ(data->GetStruct(1)->GetArray(0)->GetStruct(0)->GetString(1), "x");
  ASSERT_EQ(data->GetStruct(1)->GetArray(0)->GetStruct(1)->GetString(1), "y");
  ASSERT_EQ(data->GetStruct(1)->GetArray(0)->GetStruct(2)->GetString(1), "z");
  ASSERT_EQ(data->GetStruct(0)->GetBoolean(1), false);
  ASSERT_EQ(data->GetStruct(1)->GetBoolean(1), true);
}

} // namespace test2
} // namespace row
} // namespace fory
