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

package org.apache.fory.format.row.binary;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.testng.annotations.Test;

public class BinaryRowTest {
  private static final Logger LOG = LoggerFactory.getLogger(BinaryRowTest.class);

  // test align to see whether align can bring us performance gains.
  @Test(enabled = false)
  public void testAlign() {
    MemoryBuffer buf = MemoryBuffer.buffer(64);
    buf.putInt64(6, 100L);
    buf.putInt64(14, 100L);
    long nums = 1000_000_000;
    // warm
    for (int i = 0; i < nums; i++) {
      buf.getInt64(6);
      buf.getInt64(14);
    }
    long t = System.nanoTime();
    for (int i = 0; i < nums; i++) {
      buf.getInt64(6);
      buf.getInt64(14);
    }
    long duration = System.nanoTime() - t;
    System.out.format("non-aligned cost:\ttotal %sns %sms\n", duration, duration / 1000_000);

    MemoryBuffer buf2 = MemoryBuffer.buffer(64);
    buf2.putInt64(8, 100L);
    buf2.putInt64(16, 100L);
    // warm
    for (int i = 0; i < nums; i++) {
      buf2.getInt64(8);
      buf2.getInt64(16);
    }
    t = System.nanoTime();
    for (int i = 0; i < nums; i++) {
      buf2.getInt64(8);
      buf2.getInt64(16);
    }
    duration = System.nanoTime() - t;
    System.out.format("aligned cost:\ttotal %sns %sms\n", duration, duration / 1000_000);
  }

  // Test performance difference between access field offset by an offset array and calculate
  // offset.
  // The result shows that calculating offset have a much better performance than read from offset
  // array.
  @Test(enabled = false)
  public void testOffsetAccessPerf() {
    int numFields = 100;
    int[] offsetsArray = new int[numFields];
    for (int i = 0; i < numFields; i++) {
      offsetsArray[i] = i;
    }

    byte[] bytes = new byte[numFields];
    int iterNums = 1000_000_000;
    // warm
    for (int i = 0; i < iterNums; i++) {
      for (int j = 0; j < numFields; j++) {
        int tmp = offsetsArray[j];
        Platform.getByte(bytes, tmp);
      }
    }
    // test access offset array
    long startTime = System.nanoTime();
    for (int i = 0; i < iterNums; i++) {
      for (int j = 0; j < numFields; j++) {
        int tmp = offsetsArray[j];
        Platform.getByte(bytes, tmp);
      }
    }
    long duration = System.nanoTime() - startTime;
    LOG.info("Array access offset take " + duration + "ns, " + duration / 1000_000 + " ms\n");

    int headerInBytes = 64;
    // warm
    for (int i = 0; i < iterNums; i++) {
      for (int j = 0; j < numFields; j++) {
        int tmp = headerInBytes + 8 * j;
        Platform.getByte(bytes, tmp);
      }
    }
    // test calc offset
    startTime = System.nanoTime();
    for (int i = 0; i < iterNums; i++) {
      for (int j = 0; j < numFields; j++) {
        int tmp = headerInBytes + 8 * j;
        Platform.getByte(bytes, tmp);
      }
    }
    duration = System.nanoTime() - startTime;
    LOG.info("Compute access offset take " + duration + "ns, " + duration / 1000_000 + " ms\n");
  }

  @Test
  public void testCreateRow() {
    int i = 0;
    List<Field> fields =
        Arrays.asList(
            DataTypes.primitiveArrayField("f_int_array_" + i++, DataTypes.int32()),
            DataTypes.field("f_byte_" + i++, false, DataTypes.int8()),
            DataTypes.field("f_short_" + i++, false, DataTypes.int16()),
            DataTypes.field("f_int_" + i++, false, DataTypes.int32()),
            DataTypes.field("f_long_" + i++, false, DataTypes.int64()),
            DataTypes.field("f_float_" + i++, false, DataTypes.float32()),
            DataTypes.field("f_double_" + i++, false, DataTypes.float64()),
            DataTypes.field("f_boolean_" + i++, false, DataTypes.bool()),
            DataTypes.field("f_date_" + i++, true, DataTypes.date32()),
            DataTypes.field("f_timestamp_" + i++, DataTypes.timestamp()),
            DataTypes.field("f_binary", DataTypes.binary()),
            DataTypes.field("f_string_" + i++, DataTypes.utf8()),
            DataTypes.field("f_decimal_", DataTypes.bigintDecimal()),
            DataTypes.arrayField("f_array_" + i++, DataTypes.utf8()),
            DataTypes.mapField("f_map_" + i++, DataTypes.utf8(), DataTypes.utf8()));
    Schema schema = new Schema(fields);

    BinaryRowWriter writer = new BinaryRowWriter(schema);
    writer.reset();
    i = 0;
    int start = writer.writerIndex();
    // System.out.println("before fromPrimitiveArray " + writer.writerIndex());
    BinaryArrayWriter intArrayWriter =
        new BinaryArrayWriter(DataTypes.PRIMITIVE_INT_ARRAY_FIELD, writer);
    int[] arr = new int[] {1, 2};
    intArrayWriter.reset(2);
    intArrayWriter.fromPrimitiveArray(arr);
    writer.setNotNullAt(i);
    // reset BinaryArrayWriter(ArrayType type, BinaryWriter writer) increase writerIndex, which
    // increase
    // writer's writerIndex,
    // so we need to record writerIndex before call BinaryArrayWriter(ArrayType type, BinaryWriter
    // writer) to setOffsetAndSize.
    writer.setOffsetAndSize(i, start, intArrayWriter.size());
    // System.out.println("after fromPrimitiveArray " + writer.writerIndex());
    // System.out.println(intArrayWriter.toArray());
    BinaryArray a = new BinaryArray(DataTypes.PRIMITIVE_INT_ARRAY_FIELD);
    a.pointTo(writer.getBuffer(), start, intArrayWriter.size());

    i++;
    writer.write(i++, (byte) 1);
    writer.write(i++, (short) 1);
    writer.write(i++, Integer.MAX_VALUE);
    writer.write(i++, Long.MAX_VALUE);
    writer.write(i++, Float.MAX_VALUE);
    writer.write(i++, Double.MAX_VALUE);
    writer.write(i++, true);
    writer.write(i++, 10);
    writer.write(i++, System.currentTimeMillis());
    writer.write(i++, new byte[] {1, 2, 3, 4, 5});
    writer.write(i++, "str");
    writer.write(i++, BigDecimal.valueOf(100, 0));
    BinaryArrayWriter arrayWriter = new BinaryArrayWriter(DataTypes.arrayField(DataTypes.utf8()));
    arrayWriter.reset(2);
    arrayWriter.write(0, "str0");
    arrayWriter.write(1, "str1");
    // System.out.println("array: " + arrayWriter.toArray());
    writer.write(i++, arrayWriter.toArray());

    // write map. see BinaryMapTest
    int offset = writer.writerIndex();
    // preserve 8 bytes to write the key array numBytes later
    writer.writeDirectly(-1);
    BinaryArrayWriter keyArrayWriter =
        new BinaryArrayWriter(DataTypes.arrayField(DataTypes.utf8()), writer);
    keyArrayWriter.reset(2);
    keyArrayWriter.write(0, "k0");
    keyArrayWriter.write(1, "k1");
    // System.out.println("key array: " + keyArrayWriter.toArray());
    // write key array numBytes
    writer.writeDirectly(offset, keyArrayWriter.size());
    BinaryArrayWriter valueArrayWriter =
        new BinaryArrayWriter(DataTypes.arrayField(DataTypes.utf8()), writer);
    valueArrayWriter.reset(2);
    valueArrayWriter.write(0, "v0");
    valueArrayWriter.write(1, "v1");
    // System.out.println("value array: " + valueArrayWriter.toArray());

    int size = writer.writerIndex() - offset;
    writer.setNotNullAt(i);
    writer.setOffsetAndSize(i++, offset, size);
    BinaryRow row = writer.getRow();
    row.toMap();
    // System.out.println(row);
  }

  @Test
  public void testCreateBasicRow() {
    int i = 0;
    List<Field> fields =
        Arrays.asList(
            DataTypes.field("f_byte_" + i++, false, DataTypes.int8()),
            DataTypes.field("f_short_" + i++, false, DataTypes.int16()),
            DataTypes.field("f_int_" + i++, false, DataTypes.int32()),
            DataTypes.field("f_long_" + i++, false, DataTypes.int64()),
            DataTypes.field("f_float_" + i++, false, DataTypes.float32()),
            DataTypes.field("f_double_" + i++, false, DataTypes.float64()),
            DataTypes.field("f_boolean_" + i++, false, DataTypes.bool()),
            DataTypes.field("f_string_" + i++, DataTypes.utf8()));
    Schema schema = new Schema(fields);

    BinaryRowWriter writer = new BinaryRowWriter(schema);
    writer.reset();
    int index = 0;
    writer.write(index++, (byte) 1);
    writer.write(index++, Short.MAX_VALUE);
    writer.write(index++, Integer.MIN_VALUE);
    writer.write(index++, Long.MIN_VALUE);
    writer.write(index++, 0.1f);
    writer.write(index++, 0.1d);
    writer.write(index++, true);
    writer.write(index++, "abc");
    BinaryRow row = writer.getRow();
    System.out.println("row " + row);
  }
}
