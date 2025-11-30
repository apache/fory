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

package org.apache.fory.format.type;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.util.Arrays;
import org.testng.annotations.Test;

public class DataTypesTest {

  @Test
  public void testPrimitiveTypes() {
    assertEquals(DataTypes.bool().typeId(), DataTypes.TYPE_BOOL);
    assertEquals(DataTypes.int8().typeId(), DataTypes.TYPE_INT8);
    assertEquals(DataTypes.int16().typeId(), DataTypes.TYPE_INT16);
    assertEquals(DataTypes.int32().typeId(), DataTypes.TYPE_INT32);
    assertEquals(DataTypes.int64().typeId(), DataTypes.TYPE_INT64);
    assertEquals(DataTypes.float16().typeId(), DataTypes.TYPE_FLOAT16);
    assertEquals(DataTypes.float32().typeId(), DataTypes.TYPE_FLOAT32);
    assertEquals(DataTypes.float64().typeId(), DataTypes.TYPE_FLOAT64);
    assertEquals(DataTypes.utf8().typeId(), DataTypes.TYPE_STRING);
    assertEquals(DataTypes.binary().typeId(), DataTypes.TYPE_BINARY);
  }

  @Test
  public void testBitWidth() {
    assertEquals(DataTypes.bool().bitWidth(), 8);
    assertEquals(DataTypes.int8().bitWidth(), 8);
    assertEquals(DataTypes.int16().bitWidth(), 16);
    assertEquals(DataTypes.int32().bitWidth(), 32);
    assertEquals(DataTypes.int64().bitWidth(), 64);
    assertEquals(DataTypes.float16().bitWidth(), 16);
    assertEquals(DataTypes.float32().bitWidth(), 32);
    assertEquals(DataTypes.float64().bitWidth(), 64);
  }

  @Test
  public void testByteWidth() {
    assertEquals(DataTypes.bool().byteWidth(), 1);
    assertEquals(DataTypes.int8().byteWidth(), 1);
    assertEquals(DataTypes.int16().byteWidth(), 2);
    assertEquals(DataTypes.int32().byteWidth(), 4);
    assertEquals(DataTypes.int64().byteWidth(), 8);
    assertEquals(DataTypes.float16().byteWidth(), 2);
    assertEquals(DataTypes.float32().byteWidth(), 4);
    assertEquals(DataTypes.float64().byteWidth(), 8);
  }

  @Test
  public void testDecimalType() {
    DataTypes.DecimalType decimal = DataTypes.decimal(10, 2);
    assertEquals(decimal.typeId(), DataTypes.TYPE_DECIMAL);
    assertEquals(decimal.precision(), 10);
    assertEquals(decimal.scale(), 2);
  }

  @Test
  public void testListType() {
    DataTypes.ListType listType = DataTypes.list(DataTypes.int32());
    assertEquals(listType.typeId(), DataTypes.TYPE_LIST);
    assertEquals(listType.valueType(), DataTypes.int32());
    assertEquals(listType.numFields(), 1);
    assertNotNull(listType.valueField());
  }

  @Test
  public void testMapType() {
    DataTypes.MapType mapType = DataTypes.map(DataTypes.utf8(), DataTypes.int32());
    assertEquals(mapType.typeId(), DataTypes.TYPE_MAP);
    assertEquals(mapType.keyType(), DataTypes.utf8());
    assertEquals(mapType.itemType(), DataTypes.int32());
    assertEquals(mapType.numFields(), 2);
  }

  @Test
  public void testStructType() {
    DataTypes.StructType structType =
        DataTypes.struct(
            DataTypes.field("a", DataTypes.int32()), DataTypes.field("b", DataTypes.utf8()));
    assertEquals(structType.typeId(), DataTypes.TYPE_STRUCT);
    assertEquals(structType.numFields(), 2);
    assertEquals(structType.field(0).name(), "a");
    assertEquals(structType.field(1).name(), "b");
  }

  @Test
  public void testSchema() {
    Schema schema =
        DataTypes.schema(
            Arrays.asList(
                DataTypes.field("id", DataTypes.int64()),
                DataTypes.field("name", DataTypes.utf8()),
                DataTypes.field("score", DataTypes.float64())));
    assertEquals(schema.numFields(), 3);
    assertEquals(schema.field(0).name(), "id");
    assertEquals(schema.field(1).name(), "name");
    assertEquals(schema.field(2).name(), "score");
    assertEquals(schema.getFieldIndex("name"), 1);
    assertNotNull(schema.getFieldByName("score"));
  }

  @Test
  public void testFieldEquality() {
    Field f1 = DataTypes.field("a", DataTypes.int32(), true);
    Field f2 = DataTypes.field("a", DataTypes.int32(), true);
    Field f3 = DataTypes.field("b", DataTypes.int32(), true);
    assertEquals(f1, f2);
    assertNotNull(f1);
    assertEquals(f1.hashCode(), f2.hashCode());
    // f3 has different name
    assert !f1.equals(f3);
  }

  @Test
  public void testTypeEquality() {
    assertEquals(DataTypes.int32(), DataTypes.int32());
    assertEquals(DataTypes.utf8(), DataTypes.utf8());
    assertEquals(DataTypes.decimal(10, 2), DataTypes.decimal(10, 2));
    assert !DataTypes.decimal(10, 2).equals(DataTypes.decimal(10, 3));
  }
}
