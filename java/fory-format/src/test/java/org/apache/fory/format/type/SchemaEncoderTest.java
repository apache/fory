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

import java.util.Arrays;
import org.testng.annotations.Test;

public class SchemaEncoderTest {

  @Test
  public void testSerializeSimpleSchema() {
    Schema schema =
        new Schema(
            Arrays.asList(
                new Field("id", DataTypes.int32(), true),
                new Field("name", DataTypes.utf8(), true),
                new Field("score", DataTypes.float64(), true),
                new Field("active", DataTypes.bool(), true)));

    byte[] bytes = SchemaEncoder.toBytes(schema);
    Schema deserialized = SchemaEncoder.fromBytes(bytes);

    assertEquals(deserialized, schema);
    assertEquals(deserialized.numFields(), 4);
    assertEquals(deserialized.field(0).name(), "id");
    assertEquals(deserialized.field(1).name(), "name");
    assertEquals(deserialized.field(2).name(), "score");
    assertEquals(deserialized.field(3).name(), "active");
  }

  @Test
  public void testSerializeNestedSchema() {
    DataType addressType =
        DataTypes.struct(
            new Field("street", DataTypes.utf8(), true),
            new Field("city", DataTypes.utf8(), true),
            new Field("zip", DataTypes.int32(), true));

    Schema schema =
        new Schema(
            Arrays.asList(
                new Field("id", DataTypes.int32(), true),
                new Field("name", DataTypes.utf8(), true),
                new Field("address", addressType, true)));

    byte[] bytes = SchemaEncoder.toBytes(schema);
    Schema deserialized = SchemaEncoder.fromBytes(bytes);

    assertEquals(deserialized, schema);
    assertEquals(deserialized.numFields(), 3);
    assertEquals(deserialized.field(2).name(), "address");
    assertEquals(deserialized.field(2).type().typeId(), DataTypes.TYPE_STRUCT);
  }

  @Test
  public void testSerializeListType() {
    Schema schema =
        new Schema(
            Arrays.asList(
                new Field("id", DataTypes.int32(), true),
                new Field("tags", DataTypes.list(DataTypes.utf8()), true)));

    byte[] bytes = SchemaEncoder.toBytes(schema);
    Schema deserialized = SchemaEncoder.fromBytes(bytes);

    assertEquals(deserialized, schema);
  }

  @Test
  public void testSerializeMapType() {
    Schema schema =
        new Schema(
            Arrays.asList(
                new Field("id", DataTypes.int32(), true),
                new Field("metadata", DataTypes.map(DataTypes.utf8(), DataTypes.int32()), true)));

    byte[] bytes = SchemaEncoder.toBytes(schema);
    Schema deserialized = SchemaEncoder.fromBytes(bytes);

    assertEquals(deserialized, schema);
  }

  @Test
  public void testSerializeDecimalType() {
    Schema schema =
        new Schema(
            Arrays.asList(
                new Field("id", DataTypes.int32(), true),
                new Field("amount", DataTypes.decimal(18, 2), true)));

    byte[] bytes = SchemaEncoder.toBytes(schema);
    Schema deserialized = SchemaEncoder.fromBytes(bytes);

    assertEquals(deserialized, schema);
    DataTypes.DecimalType decimalType = (DataTypes.DecimalType) deserialized.field(1).type();
    assertEquals(decimalType.precision(), 18);
    assertEquals(decimalType.scale(), 2);
  }

  @Test
  public void testSerializeWithNullability() {
    Schema schema =
        new Schema(
            Arrays.asList(
                new Field("id", DataTypes.int32(), true),
                new Field("name", DataTypes.utf8(), true),
                new Field("score", DataTypes.float64(), false)));

    byte[] bytes = SchemaEncoder.toBytes(schema);
    Schema deserialized = SchemaEncoder.fromBytes(bytes);

    assertEquals(deserialized, schema);
    assertEquals(deserialized.field(0).nullable(), true);
    assertEquals(deserialized.field(1).nullable(), true);
    assertEquals(deserialized.field(2).nullable(), false);
  }

  @Test
  public void testRoundTripWithDataTypes() {
    Schema schema =
        new Schema(
            Arrays.asList(
                new Field("f1", DataTypes.bool(), true),
                new Field("f2", DataTypes.int8(), true),
                new Field("f3", DataTypes.int16(), true),
                new Field("f4", DataTypes.int32(), true),
                new Field("f5", DataTypes.int64(), true),
                new Field("f6", DataTypes.float32(), true),
                new Field("f7", DataTypes.float64(), true),
                new Field("f8", DataTypes.utf8(), true),
                new Field("f9", DataTypes.binary(), true),
                new Field("f10", DataTypes.date32(), true),
                new Field("f11", DataTypes.timestamp(), true),
                new Field("f12", DataTypes.duration(), true)));

    byte[] bytes = DataTypes.serializeSchema(schema);
    Schema deserialized = DataTypes.deserializeSchema(bytes);

    assertEquals(deserialized, schema);
  }
}
