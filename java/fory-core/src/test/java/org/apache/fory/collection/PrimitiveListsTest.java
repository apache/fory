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

package org.apache.fory.collection;

import static org.testng.Assert.*;

import org.apache.fory.type.Float16;
import org.apache.fory.type.unsigned.Uint16;
import org.apache.fory.type.unsigned.Uint32;
import org.apache.fory.type.unsigned.Uint64;
import org.apache.fory.type.unsigned.Uint8;
import org.testng.annotations.Test;

public class PrimitiveListsTest {

  @Test
  public void boolListSupportsPrimitiveOps() {
    BoolList list = new BoolList();
    assertEquals(list.size(), 0);
    boolean[] initial = list.getArray();

    for (int i = 0; i < 12; i++) {
      list.add(i % 2 == 0);
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(3, Boolean.TRUE);
    assertEquals(list.getBoolean(3), true);
    assertEquals(list.getBoolean(4), false);

    Boolean prev = list.set(0, Boolean.FALSE);
    assertEquals(prev.booleanValue(), true);
    list.set(0, true);
    assertTrue(list.getBoolean(0));

    boolean[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[3], list.getBoolean(3));
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Boolean) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Boolean) null));
  }

  @Test
  public void int8ListSupportsPrimitiveOps() {
    Int8List list = new Int8List();
    assertEquals(list.size(), 0);
    byte[] initial = list.getArray();

    for (int i = 0; i < 12; i++) {
      list.add((byte) i);
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(1, (byte) 99);
    assertEquals(list.size(), 13);
    assertEquals(list.getByte(1), (byte) 99);
    assertEquals(list.getByte(2), (byte) 1);

    Byte prev = list.set(0, Byte.valueOf((byte) 7));
    assertEquals(prev.byteValue(), (byte) 0);
    list.set(0, (int) 8);
    assertEquals(list.getByte(0), (byte) 8);
    assertEquals(list.getInt(1), 99);

    byte[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[0], list.getByte(0));
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Byte) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Byte) null));
  }

  @Test
  public void int16ListSupportsPrimitiveOps() {
    Int16List list = new Int16List();
    assertEquals(list.size(), 0);
    short[] initial = list.getArray();

    for (int i = 0; i < 12; i++) {
      list.add((short) (i * 2));
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(2, (short) 77);
    assertEquals(list.getShort(2), (short) 77);
    assertEquals(list.getShort(3), (short) 4);

    Short prev = list.set(0, Short.valueOf((short) -3));
    assertEquals(prev.shortValue(), (short) 0);
    list.set(0, 1234);
    assertEquals(list.getShort(0), (short) 1234);
    assertEquals(list.getInt(1), 2);

    short[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[2], list.getShort(2));
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Short) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Short) null));
  }

  @Test
  public void int32ListSupportsPrimitiveOps() {
    Int32List list = new Int32List();
    assertEquals(list.size(), 0);
    int[] initial = list.getArray();

    for (int i = 0; i < 12; i++) {
      list.add(i * 3);
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(3, 42);
    assertEquals(list.getInt(3), 42);
    assertEquals(list.getInt(4), 9);

    Integer prev = list.set(0, Integer.valueOf(-5));
    assertEquals(prev.intValue(), 0);
    list.set(0, (long) 11);
    assertEquals(list.getInt(0), 11);

    int[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[3], list.getInt(3));
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Integer) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Integer) null));
  }

  @Test
  public void int64ListSupportsPrimitiveOps() {
    Int64List list = new Int64List();
    assertEquals(list.size(), 0);
    long[] initial = list.getArray();

    for (int i = 0; i < 12; i++) {
      list.add((long) i * 5);
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(4, 123L);
    assertEquals(list.getLong(4), 123L);
    assertEquals(list.getLong(5), 20L);

    Long prev = list.set(0, Long.valueOf(7L));
    assertEquals(prev.longValue(), 0L);
    list.set(0, 9L);
    assertEquals(list.getLong(0), 9L);

    long[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[4], list.getLong(4));
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Long) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Long) null));
  }

  @Test
  public void float32ListSupportsPrimitiveOps() {
    Float32List list = new Float32List();
    assertEquals(list.size(), 0);
    float[] initial = list.getArray();

    for (int i = 0; i < 12; i++) {
      list.add((float) i + 0.5f);
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(3, 42.25f);
    assertEquals(list.getFloat(3), 42.25f, 0.0f);
    assertEquals(list.getFloat(4), 3.5f, 0.0f);

    Float prev = list.set(0, Float.valueOf(-5.25f));
    assertEquals(prev.floatValue(), 0.5f, 0.0f);
    list.set(0, 11.75d);
    assertEquals(list.getFloat(0), 11.75f, 0.0f);

    float[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[3], list.getFloat(3), 0.0f);
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Float) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Float) null));
  }

  @Test
  public void float64ListSupportsPrimitiveOps() {
    Float64List list = new Float64List();
    assertEquals(list.size(), 0);
    double[] initial = list.getArray();

    for (int i = 0; i < 12; i++) {
      list.add((double) i + 0.25d);
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(2, 123.5d);
    assertEquals(list.getDouble(2), 123.5d, 0.0d);
    assertEquals(list.getDouble(3), 2.25d, 0.0d);

    Double prev = list.set(0, Double.valueOf(-7.75d));
    assertEquals(prev.doubleValue(), 0.25d, 0.0d);
    list.set(0, 9.5d);
    assertEquals(list.getDouble(0), 9.5d, 0.0d);

    double[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[2], list.getDouble(2), 0.0d);
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Double) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Double) null));
  }

  @Test
  public void float16ListSupportsPrimitiveOps() {
    Float16List list = new Float16List();
    assertEquals(list.size(), 0);
    short[] initial = list.getArray();

    for (int i = 0; i < 12; i++) {
      list.add((float) i + 0.5f);
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(3, Float16.valueOf(42.25f));
    assertEquals(list.getFloat(3), 42.25f, 0.0f);
    assertEquals(list.getFloat(4), 3.5f, 0.0f);

    Float16 prev = list.set(0, Float16.valueOf(-5.25f));
    assertEquals(prev.toBits(), Float16.toBits(0.5f));
    list.set(0, 11.75f);
    assertEquals(list.getFloat(0), 11.75f, 0.0f);
    assertEquals(list.getShort(0), Float16.toBits(11.75f));

    short[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[3], list.getShort(3));
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Float16) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Float16) null));
  }

  @Test
  public void uint8ListSupportsPrimitiveOps() {
    Uint8List list = new Uint8List();
    assertEquals(list.size(), 0);
    byte[] initial = list.getArray();

    list.add((byte) -1);
    list.add((int) 5);
    for (int i = 0; i < 10; i++) {
      list.add((byte) i);
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(1, new Uint8((byte) 100));
    assertEquals(list.getInt(0), 255);
    assertEquals(list.getInt(1), 100);
    assertEquals(list.getInt(2), 5);

    Uint8 prev = list.set(0, new Uint8((byte) 7));
    assertEquals(prev.intValue(), 255);
    list.set(0, (int) 9);
    assertEquals(list.getInt(0), 9);

    byte[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[1], list.getByte(1));
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Uint8) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Uint8) null));
  }

  @Test
  public void uint16ListSupportsPrimitiveOps() {
    Uint16List list = new Uint16List();
    assertEquals(list.size(), 0);
    short[] initial = list.getArray();

    list.add((short) -1);
    list.add((int) 5);
    for (int i = 0; i < 10; i++) {
      list.add((short) i);
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(2, new Uint16((short) 500));
    assertEquals(list.getInt(0), 65535);
    assertEquals(list.getInt(2), 500);
    assertEquals(list.getInt(3), 0);

    Uint16 prev = list.set(0, new Uint16((short) 7));
    assertEquals(prev.intValue(), 65535);
    list.set(0, 9);
    assertEquals(list.getInt(0), 9);

    short[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[2], list.getShort(2));
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Uint16) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Uint16) null));
  }

  @Test
  public void uint32ListSupportsPrimitiveOps() {
    Uint32List list = new Uint32List();
    assertEquals(list.size(), 0);
    int[] initial = list.getArray();

    list.add(-1L);
    list.add(5);
    for (int i = 0; i < 10; i++) {
      list.add(i);
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(3, new Uint32(123456));
    assertEquals(list.getInt(0), -1);
    assertEquals(list.getInt(3), 123456);
    assertEquals(list.getInt(4), 1);

    Uint32 prev = list.set(0, new Uint32(7));
    assertEquals(prev.intValue(), -1);
    list.set(0, 9L);
    assertEquals(list.getInt(0), 9);

    int[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[3], list.getInt(3));
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Uint32) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Uint32) null));
  }

  @Test
  public void uint64ListSupportsPrimitiveOps() {
    Uint64List list = new Uint64List();
    assertEquals(list.size(), 0);
    long[] initial = list.getArray();

    list.add(-1L);
    list.add(5L);
    for (int i = 0; i < 10; i++) {
      list.add((long) i);
    }
    assertEquals(list.size(), 12);
    assertNotSame(initial, list.getArray());

    list.add(2, new Uint64(123456789L));
    assertEquals(list.getLong(0), -1L);
    assertEquals(list.getLong(2), 123456789L);
    assertEquals(list.getLong(3), 0L);

    Uint64 prev = list.set(0, new Uint64(7L));
    assertEquals(prev.longValue(), -1L);
    list.set(0, 9L);
    assertEquals(list.getLong(0), 9L);

    long[] copy = list.copyArray();
    assertEquals(copy.length, list.size());
    assertEquals(copy[2], list.getLong(2));
    assertNotSame(copy, list.getArray());

    expectThrows(NullPointerException.class, () -> list.add((Uint64) null));
    expectThrows(NullPointerException.class, () -> list.set(0, (Uint64) null));
  }
}
