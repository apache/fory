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

#include <iostream>

#include "fory/row/row.h"
#include "fory/row/writer.h"
#include "gtest/gtest.h"
#include <memory>
#include <string>
#include <vector>

namespace fory {
namespace row {

TEST(RowTest, write) {
  auto f1 = field("f1", utf8());
  auto f2 = field("f2", int32());
  auto arr_type = list(int32());
  auto f3 = field("f3", arr_type);
  auto map_type = map(utf8(), float32());
  auto f4 = field("f4", map_type);
  auto struct_type = std::dynamic_pointer_cast<StructType>(
      struct_({field("n1", utf8()), field("n2", int32())}));
  auto f5 = field("f5", struct_type);
  std::vector<FieldPtr> fields = {f1, f2, f3, f4, f5};
  auto s = schema(fields);

  RowWriter row_writer(s);
  row_writer.reset();
  row_writer.write_string(0, std::string("str"));
  row_writer.write(1, static_cast<int32_t>(1));

  // array
  row_writer.set_not_null_at(2);
  int start = row_writer.cursor();
  ArrayWriter array_writer(arr_type, &row_writer);
  array_writer.reset(2);
  array_writer.write(0, static_cast<int32_t>(2));
  array_writer.write(1, static_cast<int32_t>(2));
  EXPECT_EQ(array_writer.copy_to_array_data()->to_string(),
            std::string("[2, 2]"));
  row_writer.set_offset_and_size(2, start, row_writer.cursor() - start);

  // map
  row_writer.set_not_null_at(3);
  int offset = row_writer.cursor();
  row_writer.write_directly(-1);
  ArrayWriter key_array_writer(list(utf8()), &row_writer);
  key_array_writer.reset(2);
  key_array_writer.write_string(0, "key1");
  key_array_writer.write_string(1, "key2");
  EXPECT_EQ(key_array_writer.copy_to_array_data()->to_string(),
            std::string("[key1, key2]"));
  row_writer.write_directly(offset, key_array_writer.size());
  ArrayWriter value_array_writer(list(float32()), &row_writer);
  value_array_writer.reset(2);
  value_array_writer.write(0, 1.0f);
  value_array_writer.write(1, 1.0f);
  EXPECT_EQ(value_array_writer.copy_to_array_data()->to_string(),
            std::string("[1, 1]"));
  int size = row_writer.cursor() - offset;
  row_writer.set_offset_and_size(3, offset, size);

  // struct
  RowWriter struct_writer(schema(struct_type->fields()), &row_writer);
  row_writer.set_not_null_at(4);
  offset = row_writer.cursor();
  struct_writer.reset();
  struct_writer.write_string(0, "str");
  struct_writer.write(1, 1);
  size = row_writer.cursor() - offset;
  row_writer.set_offset_and_size(4, offset, size);

  auto row = row_writer.to_row();
  EXPECT_EQ(row->get_string(0), std::string("str"));
  EXPECT_EQ(row->get_int32(1), 1);
  EXPECT_EQ(row->get_array(2)->get_int32(0), 2);
  EXPECT_EQ(row->get_array(2)->get_int32(1), 2);
  EXPECT_EQ(row->to_string(),
            "{f1=str, f2=1, f3=[2, 2], "
            "f4=Map([key1, key2], [1, 1]), f5={n1=str, n2=1}}");
}

TEST(RowTest, WriteNestedRepeately) {
  auto f0 = field("f0", int32());
  auto f1 = field("f1", list(int32()));
  auto s = schema({f0, f1});
  int row_nums = 100;
  RowWriter row_writer(s);
  auto list_type = std::dynamic_pointer_cast<ListType>(s->field(1)->type());
  ArrayWriter array_writer(list_type, &row_writer);
  for (int i = 0; i < row_nums; ++i) {
    std::shared_ptr<Buffer> buffer;
    allocate_buffer(16, &buffer);
    row_writer.set_buffer(buffer);
    row_writer.reset();
    row_writer.write(0, std::numeric_limits<int32_t>::max());

    int start = row_writer.cursor();
    int array_elements = 50;
    array_writer.reset(array_elements);
    for (int j = 0; j < array_elements; ++j) {
      array_writer.write(j, std::numeric_limits<int32_t>::min());
    }
    row_writer.set_offset_and_size(1, start, row_writer.cursor() - start);
    auto row = row_writer.to_row();
    EXPECT_EQ(row->get_int32(0), 2147483647);
    EXPECT_EQ(row->get_array(1)->num_elements(), array_elements);
    EXPECT_EQ(row->get_array(1)->get_int32(0), -2147483648);
  }
}

TEST(ArrayTest, from) {
  std::vector<int32_t> vec = {1, 2, 3, 4};
  auto array = ArrayData::from(vec);
  // std::cout << array->to_string() << std::endl;
  EXPECT_EQ(array->num_elements(), vec.size());
}

} // namespace row
} // namespace fory

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}
