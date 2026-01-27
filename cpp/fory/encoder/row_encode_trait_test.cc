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
#include <memory>
#include <optional>
#include <type_traits>

#include "fory/encoder/row_encode_trait.h"
#include "fory/row/writer.h"

namespace fory {
namespace row {

namespace test {

struct A {
  int x;
  float y;
  bool z;
  FORY_STRUCT(A, x, y, z);
};

TEST(RowEncodeTrait, Basic) {
  auto field_vector = encoder::RowEncodeTrait<A>::FieldVector();

  static_assert(std::is_same_v<decltype(field_vector), encoder::FieldVector>);

  ASSERT_EQ(field_vector.size(), 3);
  ASSERT_EQ(field_vector[0]->name(), "x");
  ASSERT_EQ(field_vector[1]->name(), "y");
  ASSERT_EQ(field_vector[2]->name(), "z");

  ASSERT_EQ(field_vector[0]->type()->name(), "int32");
  ASSERT_EQ(field_vector[1]->type()->name(), "float");
  ASSERT_EQ(field_vector[2]->type()->name(), "bool");

  RowWriter writer(schema(field_vector));
  writer.Reset();

  A a{233, 3.14, true};
  encoder::RowEncodeTrait<A>::Write(encoder::EmptyWriteVisitor{}, a, writer);

  auto row = writer.ToRow();
  ASSERT_EQ(row->GetInt32(0), 233);
  ASSERT_FLOAT_EQ(row->GetFloat(1), 3.14);
  ASSERT_EQ(row->GetBoolean(2), true);
}

struct B {
  int num;
  std::string str;
  FORY_STRUCT(B, num, str);
};

TEST(RowEncodeTrait, String) {
  RowWriter writer(encoder::RowEncodeTrait<B>::Schema());
  writer.Reset();

  B b{233, "hello"};
  encoder::RowEncodeTrait<B>::Write(encoder::EmptyWriteVisitor{}, b, writer);

  auto row = writer.ToRow();
  ASSERT_EQ(row->GetString(1), "hello");
  ASSERT_EQ(row->GetInt32(0), 233);

  ASSERT_EQ(writer.schema()->field(1)->type()->name(), "utf8");
}

struct C {
  const int a;
  volatile float b;
  bool c;
  FORY_STRUCT(C, a, b, c);
};

TEST(RowEncodeTrait, Const) {
  RowWriter writer(encoder::RowEncodeTrait<C>::Schema());
  writer.Reset();

  C c{233, 1.1, true};
  encoder::RowEncodeTrait<C>::Write(encoder::EmptyWriteVisitor{}, c, writer);

  auto row = writer.ToRow();
  ASSERT_EQ(row->GetInt32(0), 233);
  ASSERT_FLOAT_EQ(row->GetFloat(1), 1.1);
  ASSERT_EQ(row->GetBoolean(2), true);
}

struct D {
  int x;
  A y;
  B z;
  FORY_STRUCT(D, x, y, z);
};

TEST(RowEncodeTrait, NestedStruct) {
  RowWriter writer(encoder::RowEncodeTrait<D>::Schema());
  std::vector<std::unique_ptr<RowWriter>> children;
  writer.Reset();

  D d{233, {234, 3.14, true}, {235, "hi"}};
  encoder::RowEncodeTrait<D>::Write(
      encoder::DefaultWriteVisitor<decltype(children)>{children}, d, writer);

  auto row = writer.ToRow();
  ASSERT_EQ(row->GetInt32(0), 233);

  auto y_row = row->GetStruct(1);
  ASSERT_EQ(y_row->GetInt32(0), 234);
  ASSERT_FLOAT_EQ(y_row->GetFloat(1), 3.14);
  ASSERT_EQ(y_row->GetBoolean(2), true);

  auto z_row = row->GetStruct(2);
  ASSERT_EQ(z_row->GetString(1), "hi");
  ASSERT_EQ(z_row->GetInt32(0), 235);

  ASSERT_EQ(writer.schema()->field(0)->type()->name(), "int32");
  ASSERT_EQ(writer.schema()->field(1)->type()->name(), "struct");
  ASSERT_EQ(writer.schema()->field(2)->type()->name(), "struct");

  auto y_writer = dynamic_cast<RowWriter *>(writer.children()[0]);
  ASSERT_TRUE(y_writer);

  auto y_schema = y_writer->schema();
  ASSERT_EQ(y_schema->field(0)->name(), "x");
  ASSERT_EQ(y_schema->field(1)->name(), "y");
  ASSERT_EQ(y_schema->field(2)->name(), "z");

  ASSERT_EQ(y_schema->field(0)->type()->name(), "int32");
  ASSERT_EQ(y_schema->field(1)->type()->name(), "float");
  ASSERT_EQ(y_schema->field(2)->type()->name(), "bool");
}

TEST(RowEncodeTrait, SimpleArray) {
  std::vector<int> a{10, 20, 30};

  auto type = encoder::RowEncodeTrait<decltype(a)>::Type();

  ASSERT_EQ(type->name(), "list");
  ASSERT_EQ(type->field(0)->type()->name(), "int32");

  ArrayWriter writer(std::dynamic_pointer_cast<ListType>(type));
  writer.Reset(a.size());

  encoder::RowEncodeTrait<decltype(a)>::Write(encoder::EmptyWriteVisitor{}, a,
                                              writer);

  auto array = writer.CopyToArrayData();
  ASSERT_EQ(array->GetInt32(0), 10);
  ASSERT_EQ(array->GetInt32(1), 20);
  ASSERT_EQ(array->GetInt32(2), 30);
}

TEST(RowEncodeTrait, StructInArray) {
  std::vector<A> a{{233, 1.1, false}, {234, 3.14, true}};

  auto type = encoder::RowEncodeTrait<decltype(a)>::Type();

  ASSERT_EQ(type->name(), "list");
  ASSERT_EQ(type->field(0)->type()->name(), "struct");

  ArrayWriter writer(std::dynamic_pointer_cast<ListType>(type));
  writer.Reset(a.size());

  encoder::RowEncodeTrait<decltype(a)>::Write(encoder::EmptyWriteVisitor{}, a,
                                              writer);

  auto array = writer.CopyToArrayData();

  auto row1 = array->GetStruct(0);
  ASSERT_EQ(row1->GetInt32(0), 233);
  ASSERT_FLOAT_EQ(row1->GetFloat(1), 1.1);
  ASSERT_EQ(row1->GetBoolean(2), false);

  auto row2 = array->GetStruct(1);
  ASSERT_EQ(row2->GetInt32(0), 234);
  ASSERT_FLOAT_EQ(row2->GetFloat(1), 3.14);
  ASSERT_EQ(row2->GetBoolean(2), true);
}

struct E {
  int a;
  std::vector<int> b;
  FORY_STRUCT(E, a, b);
};

TEST(RowEncodeTrait, ArrayInStruct) {
  E e{233, {10, 20, 30}};

  auto type = encoder::RowEncodeTrait<decltype(e)>::Type();

  ASSERT_EQ(type->name(), "struct");
  ASSERT_EQ(type->field(0)->type()->name(), "int32");
  ASSERT_EQ(type->field(1)->type()->name(), "list");

  RowWriter writer(encoder::RowEncodeTrait<decltype(e)>::Schema());
  writer.Reset();

  encoder::RowEncodeTrait<decltype(e)>::Write(encoder::EmptyWriteVisitor{}, e,
                                              writer);

  auto row = writer.ToRow();
  ASSERT_EQ(row->GetInt32(0), 233);

  ASSERT_EQ(row->GetArray(1)->GetInt32(0), 10);
  ASSERT_EQ(row->GetArray(1)->GetInt32(1), 20);
  ASSERT_EQ(row->GetArray(1)->GetInt32(2), 30);
}

TEST(RowEncodeTrait, ArrayInArray) {
  std::vector<std::vector<int>> a{{10}, {20, 30}, {40, 50, 60}};

  auto type = encoder::RowEncodeTrait<decltype(a)>::Type();

  ASSERT_EQ(type->name(), "list");
  ASSERT_EQ(type->field(0)->type()->name(), "list");

  ArrayWriter writer(std::dynamic_pointer_cast<ListType>(type));
  writer.Reset(a.size());

  encoder::RowEncodeTrait<decltype(a)>::Write(encoder::EmptyWriteVisitor{}, a,
                                              writer);

  auto array = writer.CopyToArrayData();
  ASSERT_EQ(array->GetArray(0)->GetInt32(0), 10);
  ASSERT_EQ(array->GetArray(1)->GetInt32(0), 20);
  ASSERT_EQ(array->GetArray(1)->GetInt32(1), 30);
  ASSERT_EQ(array->GetArray(2)->GetInt32(0), 40);
  ASSERT_EQ(array->GetArray(2)->GetInt32(1), 50);
  ASSERT_EQ(array->GetArray(2)->GetInt32(2), 60);
}

struct F {
  bool a;
  std::optional<int> b;
  int c;
  FORY_STRUCT(F, a, b, c);
};

TEST(RowEncodeTrait, Optional) {
  F x{false, 233, 111}, y{true, std::nullopt, 222};

  auto schema = encoder::RowEncodeTrait<F>::Type();
  ASSERT_EQ(schema->field(0)->type()->name(), "bool");
  ASSERT_EQ(schema->field(1)->type()->name(), "int32");
  ASSERT_EQ(schema->field(2)->type()->name(), "int32");

  {
    RowWriter writer(encoder::RowEncodeTrait<F>::Schema());
    writer.Reset();

    encoder::RowEncodeTrait<F>::Write(encoder::EmptyWriteVisitor{}, x, writer);

    auto row = writer.ToRow();
    ASSERT_EQ(row->IsNullAt(0), false);
    ASSERT_EQ(row->IsNullAt(1), false);
    ASSERT_EQ(row->IsNullAt(2), false);

    ASSERT_EQ(row->GetInt32(1), 233);
    ASSERT_EQ(row->GetInt32(2), 111);
  }

  {
    RowWriter writer(encoder::RowEncodeTrait<F>::Schema());
    writer.Reset();

    encoder::RowEncodeTrait<F>::Write(encoder::EmptyWriteVisitor{}, y, writer);

    auto row = writer.ToRow();
    ASSERT_EQ(row->IsNullAt(0), false);
    ASSERT_EQ(row->IsNullAt(1), true);
    ASSERT_EQ(row->IsNullAt(2), false);

    ASSERT_EQ(row->GetInt32(2), 222);
  }
}

struct G {
  std::map<int, std::map<int, int>> a;
  std::map<std::string, A> b;
  FORY_STRUCT(G, a, b);
};

TEST(RowEncodeTrait, Map) {
  G v{{{1, {{3, 4}, {5, 6}}}, {2, {{7, 8}, {9, 10}, {11, 12}}}},
      {{"a", A{1, 1.1, true}}, {"b", A{2, 3.3, false}}}};

  auto schema = encoder::RowEncodeTrait<G>::Type();

  auto a_map = std::dynamic_pointer_cast<MapType>(schema->field(0)->type());
  ASSERT_EQ(a_map->key_type()->name(), "int32");
  ASSERT_EQ(a_map->item_type()->name(), "map");
  ASSERT_EQ(std::dynamic_pointer_cast<MapType>(a_map->item_type())
                ->key_type()
                ->name(),
            "int32");
  ASSERT_EQ(std::dynamic_pointer_cast<MapType>(a_map->item_type())
                ->item_type()
                ->name(),
            "int32");

  auto b_map = std::dynamic_pointer_cast<MapType>(schema->field(1)->type());
  ASSERT_EQ(b_map->key_type()->name(), "utf8");
  ASSERT_EQ(b_map->item_type()->name(), "struct");
  ASSERT_EQ(b_map->item_type()->field(0)->type()->name(), "int32");
  ASSERT_EQ(b_map->item_type()->field(1)->type()->name(), "float");
  ASSERT_EQ(b_map->item_type()->field(2)->type()->name(), "bool");

  RowWriter writer(encoder::RowEncodeTrait<G>::Schema());
  writer.Reset();

  encoder::RowEncodeTrait<G>::Write(encoder::EmptyWriteVisitor{}, v, writer);

  auto map_a = writer.ToRow()->GetMap(0);
  ASSERT_EQ(map_a->keys_array()->GetInt32(0), 1);
  ASSERT_EQ(map_a->keys_array()->GetInt32(1), 2);
  ASSERT_EQ(map_a->values_array()->GetMap(0)->keys_array()->GetInt32(0), 3);
  ASSERT_EQ(map_a->values_array()->GetMap(0)->keys_array()->GetInt32(1), 5);
  ASSERT_EQ(map_a->values_array()->GetMap(0)->values_array()->GetInt32(0), 4);
  ASSERT_EQ(map_a->values_array()->GetMap(0)->values_array()->GetInt32(1), 6);
  ASSERT_EQ(map_a->values_array()->GetMap(1)->keys_array()->GetInt32(0), 7);
  ASSERT_EQ(map_a->values_array()->GetMap(1)->keys_array()->GetInt32(1), 9);
  ASSERT_EQ(map_a->values_array()->GetMap(1)->keys_array()->GetInt32(2), 11);
  ASSERT_EQ(map_a->values_array()->GetMap(1)->values_array()->GetInt32(0), 8);
  ASSERT_EQ(map_a->values_array()->GetMap(1)->values_array()->GetInt32(1), 10);
  ASSERT_EQ(map_a->values_array()->GetMap(1)->values_array()->GetInt32(2), 12);

  auto map_b = writer.ToRow()->GetMap(1);
  ASSERT_EQ(map_b->keys_array()->GetString(0), "a");
  ASSERT_EQ(map_b->keys_array()->GetString(1), "b");
  ASSERT_EQ(map_b->values_array()->GetStruct(0)->GetInt32(0), 1);
  ASSERT_EQ(map_b->values_array()->GetStruct(1)->GetInt32(0), 2);
  ASSERT_FLOAT_EQ(map_b->values_array()->GetStruct(0)->GetFloat(1), 1.1);
  ASSERT_FLOAT_EQ(map_b->values_array()->GetStruct(1)->GetFloat(1), 3.3);
  ASSERT_EQ(map_b->values_array()->GetStruct(0)->GetBoolean(2), true);
  ASSERT_EQ(map_b->values_array()->GetStruct(1)->GetBoolean(2), false);
}

} // namespace test

} // namespace row
} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
