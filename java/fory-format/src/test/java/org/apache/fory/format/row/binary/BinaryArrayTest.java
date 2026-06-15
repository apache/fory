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

import java.util.Arrays;
import java.util.Random;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BinaryArrayTest {
  private static final Logger LOG = LoggerFactory.getLogger(BinaryArrayTest.class);

  @Test
  public void fromPrimitiveArray() {
    int[] arr = new int[] {1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2};
    BinaryArray.fromPrimitiveArray(arr);
    BinaryArrayWriter writer = new BinaryArrayWriter(DataTypes.PRIMITIVE_INT_ARRAY_FIELD);
    writer.reset(arr.length);
    writer.fromPrimitiveArray(arr);
    writer.toArray();
  }

  @Test
  public void primitiveArrayRoundTrip() {
    assertRoundTrip(new byte[] {1, -2, 3});
    assertRoundTrip(new boolean[] {true, false, true});
    assertRoundTrip(new short[] {1, -2, Short.MAX_VALUE});
    assertRoundTrip(new int[] {1, -2, Integer.MAX_VALUE});
    assertRoundTrip(new long[] {1L, -2L, Long.MAX_VALUE});
    assertRoundTrip(new float[] {1.25f, -2.5f, Float.MAX_VALUE});
    assertRoundTrip(new double[] {1.25d, -2.5d, Double.MAX_VALUE});
  }

  @Test
  public void primitiveWireEndian() {
    assertValueBytes(
        BinaryArray.fromPrimitiveArray(new short[] {(short) 0x1234}), bytes(0x34, 0x12));
    assertValueBytes(
        BinaryArray.fromPrimitiveArray(new int[] {0x12345678}), bytes(0x78, 0x56, 0x34, 0x12));
    assertValueBytes(
        BinaryArray.fromPrimitiveArray(new long[] {0x0102030405060708L}),
        bytes(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01));
    assertValueBytes(
        BinaryArray.fromPrimitiveArray(new float[] {Float.intBitsToFloat(0x12345678)}),
        bytes(0x78, 0x56, 0x34, 0x12));
    assertValueBytes(
        BinaryArray.fromPrimitiveArray(new double[] {Double.longBitsToDouble(0x0102030405060708L)}),
        bytes(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01));

    BinaryArrayWriter writer = new BinaryArrayWriter(DataTypes.PRIMITIVE_INT_ARRAY_FIELD);
    writer.reset(1);
    writer.fromPrimitiveArray(new int[] {0x12345678});
    assertValueBytes(writer.toArray(), bytes(0x78, 0x56, 0x34, 0x12));
  }

  private static void assertRoundTrip(byte[] arr) {
    Assert.assertEquals(BinaryArray.fromPrimitiveArray(arr).toByteArray(), arr);
    BinaryArrayWriter writer = new BinaryArrayWriter(DataTypes.PRIMITIVE_BYTE_ARRAY_FIELD);
    writer.reset(arr.length);
    writer.fromPrimitiveArray(arr);
    Assert.assertEquals(writer.toArray().toByteArray(), arr);
  }

  private static void assertRoundTrip(boolean[] arr) {
    Assert.assertEquals(BinaryArray.fromPrimitiveArray(arr).toBooleanArray(), arr);
    BinaryArrayWriter writer = new BinaryArrayWriter(DataTypes.PRIMITIVE_BOOLEAN_ARRAY_FIELD);
    writer.reset(arr.length);
    writer.fromPrimitiveArray(arr);
    Assert.assertEquals(writer.toArray().toBooleanArray(), arr);
  }

  private static void assertRoundTrip(short[] arr) {
    Assert.assertEquals(BinaryArray.fromPrimitiveArray(arr).toShortArray(), arr);
    BinaryArrayWriter writer = new BinaryArrayWriter(DataTypes.PRIMITIVE_SHORT_ARRAY_FIELD);
    writer.reset(arr.length);
    writer.fromPrimitiveArray(arr);
    Assert.assertEquals(writer.toArray().toShortArray(), arr);
  }

  private static void assertRoundTrip(int[] arr) {
    Assert.assertEquals(BinaryArray.fromPrimitiveArray(arr).toIntArray(), arr);
    BinaryArrayWriter writer = new BinaryArrayWriter(DataTypes.PRIMITIVE_INT_ARRAY_FIELD);
    writer.reset(arr.length);
    writer.fromPrimitiveArray(arr);
    Assert.assertEquals(writer.toArray().toIntArray(), arr);
  }

  private static void assertRoundTrip(long[] arr) {
    Assert.assertEquals(BinaryArray.fromPrimitiveArray(arr).toLongArray(), arr);
    BinaryArrayWriter writer = new BinaryArrayWriter(DataTypes.PRIMITIVE_LONG_ARRAY_FIELD);
    writer.reset(arr.length);
    writer.fromPrimitiveArray(arr);
    Assert.assertEquals(writer.toArray().toLongArray(), arr);
  }

  private static void assertRoundTrip(float[] arr) {
    Assert.assertEquals(BinaryArray.fromPrimitiveArray(arr).toFloatArray(), arr);
    BinaryArrayWriter writer = new BinaryArrayWriter(DataTypes.PRIMITIVE_FLOAT_ARRAY_FIELD);
    writer.reset(arr.length);
    writer.fromPrimitiveArray(arr);
    Assert.assertEquals(writer.toArray().toFloatArray(), arr);
  }

  private static void assertRoundTrip(double[] arr) {
    Assert.assertEquals(BinaryArray.fromPrimitiveArray(arr).toDoubleArray(), arr);
    BinaryArrayWriter writer = new BinaryArrayWriter(DataTypes.PRIMITIVE_DOUBLE_ARRAY_FIELD);
    writer.reset(arr.length);
    writer.fromPrimitiveArray(arr);
    Assert.assertEquals(writer.toArray().toDoubleArray(), arr);
  }

  private static void assertValueBytes(BinaryArray array, byte[] expected) {
    byte[] bytes = array.toBytes();
    int offset = BinaryArray.calculateHeaderInBytes(1);
    Assert.assertEquals(Arrays.copyOfRange(bytes, offset, offset + expected.length), expected);
  }

  private static byte[] bytes(int... values) {
    byte[] bytes = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      bytes[i] = (byte) values[i];
    }
    return bytes;
  }

  private int elem;

  @Test(enabled = false)
  public void testAccessPerf() {
    int length = 10000;
    int[] arr = new int[length];
    Random random = new Random();
    for (int i = 0; i < length; i++) {
      arr[i] = random.nextInt();
    }
    BinaryArray binaryArray = BinaryArray.fromPrimitiveArray(arr);
    int iterNums = 100_000;

    // warm
    for (int i = 0; i < iterNums; i++) {
      for (int j = 0; j < length; j++) {
        elem = arr[j];
      }
    }
    // test array
    long startTime = System.nanoTime();
    for (int i = 0; i < iterNums; i++) {
      for (int j = 0; j < length; j++) {
        elem = arr[j];
      }
    }
    long duration = System.nanoTime() - startTime;
    LOG.info("access array take " + duration + "ns, " + duration / 1000_000 + " ms\n");

    for (int i = 0; i < iterNums; i++) {
      for (int j = 0; j < length; j++) {
        elem = binaryArray.getInt32(j);
      }
    }
    // test binary array
    startTime = System.nanoTime();
    for (int i = 0; i < iterNums; i++) {
      for (int j = 0; j < length; j++) {
        elem = binaryArray.getInt32(j);
      }
    }
    duration = System.nanoTime() - startTime;
    LOG.info("access BinaryArray take " + duration + "ns, " + duration / 1000_000 + " ms\n");
  }

  @Test
  public void getDimensionsTest() {
    {
      int[] arr = new int[] {1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2};
      int[] dimensions = BinaryArray.getDimensions(BinaryArray.fromPrimitiveArray(arr), 1);
      Assert.assertEquals(dimensions, new int[] {arr.length});
    }

    {
      BinaryArrayWriter writer =
          new BinaryArrayWriter(DataTypes.arrayField(DataTypes.PRIMITIVE_INT_ARRAY_FIELD));
      writer.reset(4);
      int[] a = new int[] {1, 2, 1};
      writer.setNullAt(0);
      writer.setNullAt(1);
      writer.write(2, BinaryArray.fromPrimitiveArray(a));
      writer.write(3, BinaryArray.fromPrimitiveArray(a));
      BinaryArray array = writer.toArray();

      int[] dimensions = BinaryArray.getDimensions(array, 2);
      Assert.assertEquals(dimensions, new int[] {4, 3});
    }
  }
}
