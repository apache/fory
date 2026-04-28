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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.format.row.ArrayData;
import org.apache.fory.format.row.MapData;
import org.apache.fory.format.row.Row;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SchemaRowFieldTest {

  public static class Issue3616RowData {
    public boolean fBool;
    public byte fTiny;
    public short fSmall;
    public int fInt1;
    public long fBigint;
    public float fFloat;
    public double fDouble;
    public String fString;
    public byte[] fBinary;
    public int fDate;
    public Long fTimestamp;
    public long fDecimalRaw;
    public int fDecimalScale;
    public int fInt2;
  }

  public static class NestedBean {
    public int nestedInt;
    public String nestedString;
  }

  public static class CompositeBean {
    public List<String> arrayValue;
    public Map<String, Integer> mapValue;
    public NestedBean nestedValue;
  }

  @Test
  public void testNamedFieldHandlesUseSchemaOrder() {
    RowEncoder<Issue3616RowData> encoder = Encoders.bean(Issue3616RowData.class);
    Issue3616RowData data = new Issue3616RowData();
    data.fBool = false;
    data.fTiny = 1;
    data.fSmall = 2;
    data.fInt1 = 3;
    data.fBigint = 4L;
    data.fFloat = 5.0f;
    data.fDouble = 6.0;
    data.fString = "asdasd";
    data.fBinary = new byte[] {1, 2, 3};
    data.fDate = 7;
    data.fTimestamp = 8L;
    data.fDecimalRaw = 9L;
    data.fDecimalScale = 10;
    data.fInt2 = 15;

    BinaryRow row = encoder.toRow(data);
    Schema schema = encoder.schema();
    Schema.Int32Field fInt2 = schema.int32Field("fInt2");

    Assert.assertEquals(fInt2.name(), "f_int2");
    Assert.assertEquals(fInt2.ordinal(), schema.getFieldIndex(fInt2.name()));
    Assert.assertNotEquals(fInt2.ordinal(), 13);
    Assert.assertEquals(fInt2.get(row), 15);
    Assert.assertEquals(schema.stringField("fString").get(row), "asdasd");
    Assert.assertFalse(schema.boolField("fBool").get(row));
  }

  @Test
  public void testTypedScalarFieldReads() {
    Schema schema =
        DataTypes.schema(
            DataTypes.field("boolValue", DataTypes.bool(), false),
            DataTypes.field("byteValue", DataTypes.int8(), false),
            DataTypes.field("shortValue", DataTypes.int16(), false),
            DataTypes.field("intValue", DataTypes.int32(), false),
            DataTypes.field("longValue", DataTypes.int64(), false),
            DataTypes.field("floatValue", DataTypes.float32(), false),
            DataTypes.field("doubleValue", DataTypes.float64(), false),
            DataTypes.field("dateValue", DataTypes.date32(), false),
            DataTypes.field("timestampValue", DataTypes.timestamp(), false),
            DataTypes.field("decimalValue", DataTypes.decimal(10, 2)),
            DataTypes.field("stringValue", DataTypes.utf8()),
            DataTypes.field("binaryValue", DataTypes.binary()));

    BinaryRowWriter writer = new BinaryRowWriter(schema);
    writer.reset();
    writer.write(0, true);
    writer.write(1, (byte) 2);
    writer.write(2, (short) 3);
    writer.write(3, 4);
    writer.write(4, 5L);
    writer.write(5, 6.0f);
    writer.write(6, 7.0d);
    writer.write(7, 8);
    writer.write(8, 9L);
    writer.write(9, BigDecimal.valueOf(12345, 2));
    writer.write(10, "fory");
    writer.write(11, new byte[] {10, 11, 12});
    BinaryRow row = writer.getRow();

    Assert.assertTrue(schema.boolField("boolValue").get(row));
    Assert.assertEquals(schema.int8Field("byteValue").get(row), (byte) 2);
    Assert.assertEquals(schema.int16Field("shortValue").get(row), (short) 3);
    Assert.assertEquals(schema.int32Field("intValue").get(row), 4);
    Assert.assertEquals(schema.int64Field("longValue").get(row), 5L);
    Assert.assertEquals(schema.float32Field("floatValue").get(row), 6.0f);
    Assert.assertEquals(schema.float64Field("doubleValue").get(row), 7.0d);
    Assert.assertEquals(schema.dateField("dateValue").get(row), 8);
    Assert.assertEquals(schema.timestampField("timestampValue").get(row), 9L);
    Assert.assertEquals(schema.decimalField("decimalValue").get(row), BigDecimal.valueOf(12345, 2));
    Assert.assertEquals(schema.stringField("stringValue").get(row), "fory");
    Assert.assertEquals(schema.binaryField("binaryValue").get(row), new byte[] {10, 11, 12});
  }

  @Test
  public void testTypedFixedWidthFieldSetters() {
    Schema schema =
        DataTypes.schema(
            DataTypes.field("boolValue", DataTypes.bool(), false),
            DataTypes.field("byteValue", DataTypes.int8(), false),
            DataTypes.field("shortValue", DataTypes.int16(), false),
            DataTypes.field("intValue", DataTypes.int32(), false),
            DataTypes.field("longValue", DataTypes.int64(), false),
            DataTypes.field("floatValue", DataTypes.float32(), false),
            DataTypes.field("doubleValue", DataTypes.float64(), false),
            DataTypes.field("dateValue", DataTypes.date32(), false),
            DataTypes.field("timestampValue", DataTypes.timestamp(), false));

    BinaryRowWriter writer = new BinaryRowWriter(schema);
    writer.reset();
    for (int i = 0; i < schema.numFields(); i++) {
      writer.write(i, 0L);
    }
    BinaryRow row = writer.getRow();

    schema.boolField("boolValue").set(row, true);
    schema.int8Field("byteValue").set(row, (byte) 1);
    schema.int16Field("shortValue").set(row, (short) 2);
    schema.int32Field("intValue").set(row, 3);
    schema.int64Field("longValue").set(row, 4L);
    schema.float32Field("floatValue").set(row, 5.0f);
    schema.float64Field("doubleValue").set(row, 6.0d);
    schema.dateField("dateValue").set(row, 7);
    schema.timestampField("timestampValue").set(row, 8L);

    Assert.assertTrue(schema.boolField("boolValue").get(row));
    Assert.assertEquals(schema.int8Field("byteValue").get(row), (byte) 1);
    Assert.assertEquals(schema.int16Field("shortValue").get(row), (short) 2);
    Assert.assertEquals(schema.int32Field("intValue").get(row), 3);
    Assert.assertEquals(schema.int64Field("longValue").get(row), 4L);
    Assert.assertEquals(schema.float32Field("floatValue").get(row), 5.0f);
    Assert.assertEquals(schema.float64Field("doubleValue").get(row), 6.0d);
    Assert.assertEquals(schema.dateField("dateValue").get(row), 7);
    Assert.assertEquals(schema.timestampField("timestampValue").get(row), 8L);
  }

  @Test
  public void testTypedCompositeFieldReads() {
    CompositeBean bean = new CompositeBean();
    bean.arrayValue = Arrays.asList("a", "b");
    bean.mapValue = new HashMap<>();
    bean.mapValue.put("k", 10);
    bean.nestedValue = new NestedBean();
    bean.nestedValue.nestedInt = 7;
    bean.nestedValue.nestedString = "nested";

    RowEncoder<CompositeBean> encoder = Encoders.bean(CompositeBean.class);
    BinaryRow row = encoder.toRow(bean);
    Schema schema = encoder.schema();

    ArrayData array = schema.arrayField("arrayValue").get(row);
    Assert.assertEquals(array.numElements(), 2);
    Assert.assertEquals(array.getString(0), "a");
    Assert.assertEquals(array.getString(1), "b");

    MapData map = schema.mapField("mapValue").get(row);
    Assert.assertEquals(map.numElements(), 1);
    Assert.assertEquals(map.keyArray().getString(0), "k");
    Assert.assertEquals(map.valueArray().getInt32(0), 10);

    Schema.StructField nestedField = schema.structField("nestedValue");
    Row nestedRow = nestedField.get(row);
    Schema nestedSchema = nestedField.schema();
    Assert.assertEquals(nestedSchema.int32Field("nestedInt").get(nestedRow), 7);
    Assert.assertEquals(nestedSchema.stringField("nestedString").get(nestedRow), "nested");
  }

  @Test
  public void testTypedFieldValidation() {
    Schema schema =
        DataTypes.schema(
            DataTypes.field("name", DataTypes.utf8()), DataTypes.field("score", DataTypes.int32()));

    IllegalArgumentException missing =
        Assert.expectThrows(IllegalArgumentException.class, () -> schema.int32Field("missing"));
    Assert.assertEquals(missing.getMessage(), "Field missing doesn't exist in schema");

    IllegalArgumentException wrongType =
        Assert.expectThrows(IllegalArgumentException.class, () -> schema.int32Field("name"));
    Assert.assertEquals(wrongType.getMessage(), "Field name is utf8, expected int32");
  }
}
