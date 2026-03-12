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

package org.apache.fory.serializer;

import static org.testng.Assert.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.annotation.Int8ArrayType;
import org.apache.fory.annotation.Uint16ArrayType;
import org.apache.fory.annotation.Uint32ArrayType;
import org.apache.fory.annotation.Uint64ArrayType;
import org.apache.fory.annotation.Uint8ArrayType;
import org.apache.fory.collection.Int16List;
import org.apache.fory.collection.Int32List;
import org.apache.fory.collection.Int64List;
import org.apache.fory.collection.Int8List;
import org.apache.fory.collection.Uint16List;
import org.apache.fory.collection.Uint32List;
import org.apache.fory.collection.Uint64List;
import org.apache.fory.collection.Uint8List;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.config.LongEncoding;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class PrimitiveSerializersTest extends ForyTestBase {
  @Test
  public void testUint8Serializer() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).requireClassRegistration(false).build();
    PrimitiveSerializers.Uint8Serializer serializer =
        new PrimitiveSerializers.Uint8Serializer(fory);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(8);
    serializer.write(buffer, 0);
    assertEquals(serializer.read(buffer), Integer.valueOf(0));
    serializer.write(buffer, 255);
    assertEquals(serializer.read(buffer), Integer.valueOf(255));
    assertThrows(IllegalArgumentException.class, () -> serializer.write(buffer, -1));
    assertThrows(IllegalArgumentException.class, () -> serializer.write(buffer, 256));
  }

  @Test
  public void testUint16Serializer() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).requireClassRegistration(false).build();
    PrimitiveSerializers.Uint16Serializer serializer =
        new PrimitiveSerializers.Uint16Serializer(fory);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(16);
    serializer.write(buffer, 0);
    assertEquals(serializer.read(buffer), Integer.valueOf(0));
    serializer.write(buffer, 65535);
    assertEquals(serializer.read(buffer), Integer.valueOf(65535));
    assertThrows(IllegalArgumentException.class, () -> serializer.write(buffer, -1));
    assertThrows(IllegalArgumentException.class, () -> serializer.write(buffer, 65536));
  }

  @Data
  @AllArgsConstructor
  public static class PrimitiveStruct {
    byte byte1;
    byte byte2;
    char char1;
    char char2;
    short short1;
    short short2;
    int int1;
    int int2;
    long long1;
    long long2;
    long long3;
    float float1;
    float float2;
    double double1;
    double double2;
  }

  @Test(dataProvider = "compressNumberAndCodeGen")
  public void testPrimitiveStruct(boolean compressNumber, boolean codegen) {
    PrimitiveStruct struct =
        new PrimitiveStruct(
            Byte.MIN_VALUE,
            Byte.MIN_VALUE,
            Character.MIN_VALUE,
            Character.MIN_VALUE,
            Short.MIN_VALUE,
            Short.MIN_VALUE,
            Integer.MIN_VALUE,
            Integer.MIN_VALUE,
            Long.MIN_VALUE,
            Long.MIN_VALUE,
            -3763915443215605988L, // test Long.reverseBytes in _readVarInt64OnBE
            Float.MIN_VALUE,
            Float.MIN_VALUE,
            Double.MIN_VALUE,
            Double.MIN_VALUE);
    if (compressNumber) {
      ForyBuilder builder =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .withCodegen(codegen)
              .requireClassRegistration(false);
      serDeCheck(
          builder.withNumberCompressed(true).withLongCompressed(LongEncoding.VARINT).build(),
          struct);
      serDeCheck(
          builder.withNumberCompressed(true).withLongCompressed(LongEncoding.TAGGED).build(),
          struct);
    } else {
      Fory fory =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .withCodegen(codegen)
              .requireClassRegistration(false)
              .build();
      serDeCheck(fory, struct);
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testPrimitiveStruct(Fory fory) {
    PrimitiveStruct struct =
        new PrimitiveStruct(
            Byte.MIN_VALUE,
            Byte.MIN_VALUE,
            Character.MIN_VALUE,
            Character.MIN_VALUE,
            Short.MIN_VALUE,
            Short.MIN_VALUE,
            Integer.MIN_VALUE,
            Integer.MIN_VALUE,
            Long.MIN_VALUE,
            Long.MIN_VALUE,
            -3763915443215605988L, // test Long.reverseBytes in _readVarInt64OnBE
            Float.MIN_VALUE,
            Float.MIN_VALUE,
            Double.MIN_VALUE,
            Double.MIN_VALUE);
    copyCheck(fory, struct);
  }

  @DataProvider(name = "compatibleMode")
  public static Object[][] compatibleModeProvider() {
    return new Object[][] {{false}, {true}};
  }

  public static class PrimitiveArrayStruct {
    @Int8ArrayType public byte[] int8Values;
    public short[] int16Values;
    public int[] int32Values;
    public long[] int64Values;
    @Uint8ArrayType public byte[] uint8Values;
    @Uint16ArrayType public short[] uint16Values;
    @Uint32ArrayType public int[] uint32Values;
    @Uint64ArrayType public long[] uint64Values;
  }

  public static class PrimitiveListStruct {
    public Int8List int8Values;
    public Int16List int16Values;
    public Int32List int32Values;
    public Int64List int64Values;
    public Uint8List uint8Values;
    public Uint16List uint16Values;
    public Uint32List uint32Values;
    public Uint64List uint64Values;
  }

  @Test(dataProvider = "compatibleMode")
  public void testPrimitiveArrayListRoundTrip(boolean compatible) {
    CompatibleMode mode = compatible ? CompatibleMode.COMPATIBLE : CompatibleMode.SCHEMA_CONSISTENT;
    Fory arrayFory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(mode)
            .requireClassRegistration(true)
            .build();
    Fory listFory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(mode)
            .requireClassRegistration(true)
            .build();

    arrayFory.register(PrimitiveArrayStruct.class, 1001);
    listFory.register(PrimitiveListStruct.class, 1001);

    PrimitiveArrayStruct arrayStruct = buildPrimitiveArrayStruct();
    PrimitiveListStruct listStruct = buildPrimitiveListStruct(arrayStruct);

    PrimitiveListStruct listRoundTrip =
        listFory.deserialize(arrayFory.serialize(arrayStruct), PrimitiveListStruct.class);
    assertListEqualsArray(listRoundTrip, arrayStruct);

    PrimitiveArrayStruct arrayRoundTrip =
        arrayFory.deserialize(listFory.serialize(listStruct), PrimitiveArrayStruct.class);
    assertArrayEqualsList(arrayRoundTrip, listStruct);
  }

  private PrimitiveArrayStruct buildPrimitiveArrayStruct() {
    PrimitiveArrayStruct struct = new PrimitiveArrayStruct();
    struct.int8Values = new byte[] {1, -2, 3};
    struct.int16Values = new short[] {100, -200, 300};
    struct.int32Values = new int[] {1000, -2000, 3000};
    struct.int64Values = new long[] {10000L, -20000L, 30000L};
    struct.uint8Values = new byte[] {(byte) 200, (byte) 250};
    struct.uint16Values = new short[] {(short) 50000, (short) 60000};
    struct.uint32Values = new int[] {2000000000, 2100000000};
    struct.uint64Values = new long[] {9000000000L, 12000000000L};
    return struct;
  }

  private PrimitiveListStruct buildPrimitiveListStruct(PrimitiveArrayStruct arrays) {
    PrimitiveListStruct struct = new PrimitiveListStruct();
    struct.int8Values = new Int8List(arrays.int8Values);
    struct.int16Values = new Int16List(arrays.int16Values);
    struct.int32Values = new Int32List(arrays.int32Values);
    struct.int64Values = new Int64List(arrays.int64Values);
    struct.uint8Values = new Uint8List(arrays.uint8Values);
    struct.uint16Values = new Uint16List(arrays.uint16Values);
    struct.uint32Values = new Uint32List(arrays.uint32Values);
    struct.uint64Values = new Uint64List(arrays.uint64Values);
    return struct;
  }

  private void assertListEqualsArray(PrimitiveListStruct list, PrimitiveArrayStruct arrays) {
    assertNotNull(list);
    assertTrue(java.util.Arrays.equals(list.int8Values.copyArray(), arrays.int8Values));
    assertTrue(java.util.Arrays.equals(list.int16Values.copyArray(), arrays.int16Values));
    assertTrue(java.util.Arrays.equals(list.int32Values.copyArray(), arrays.int32Values));
    assertTrue(java.util.Arrays.equals(list.int64Values.copyArray(), arrays.int64Values));
    assertTrue(java.util.Arrays.equals(list.uint8Values.copyArray(), arrays.uint8Values));
    assertTrue(java.util.Arrays.equals(list.uint16Values.copyArray(), arrays.uint16Values));
    assertTrue(java.util.Arrays.equals(list.uint32Values.copyArray(), arrays.uint32Values));
    assertTrue(java.util.Arrays.equals(list.uint64Values.copyArray(), arrays.uint64Values));
  }

  private void assertArrayEqualsList(PrimitiveArrayStruct arrays, PrimitiveListStruct list) {
    assertNotNull(arrays);
    assertTrue(java.util.Arrays.equals(arrays.int8Values, list.int8Values.copyArray()));
    assertTrue(java.util.Arrays.equals(arrays.int16Values, list.int16Values.copyArray()));
    assertTrue(java.util.Arrays.equals(arrays.int32Values, list.int32Values.copyArray()));
    assertTrue(java.util.Arrays.equals(arrays.int64Values, list.int64Values.copyArray()));
    assertTrue(java.util.Arrays.equals(arrays.uint8Values, list.uint8Values.copyArray()));
    assertTrue(java.util.Arrays.equals(arrays.uint16Values, list.uint16Values.copyArray()));
    assertTrue(java.util.Arrays.equals(arrays.uint32Values, list.uint32Values.copyArray()));
    assertTrue(java.util.Arrays.equals(arrays.uint64Values, list.uint64Values.copyArray()));
  }
}
