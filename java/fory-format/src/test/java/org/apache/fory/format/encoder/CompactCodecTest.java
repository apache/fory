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

package org.apache.fory.format.encoder;

import static org.testng.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.CompactBinaryRow;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.testng.annotations.Test;

public class CompactCodecTest {

  static {
    Encoders.registerCustomCodec(CompactUuidType.class, UUID.class, new CompactUUIDCodec());
  }

  @Data
  public static class CompactType {
    public float f1;
    public double f2;
    public byte f3;
    public short f4;
    public int f5;
    public long f6;
    public String f7;
  }

  @Test
  public void testCompactType() {
    final CompactType bean1 = new CompactType();
    bean1.f1 = 1;
    bean1.f2 = 2;
    bean1.f3 = 3;
    bean1.f4 = 4;
    bean1.f5 = 5;
    bean1.f6 = 6;
    bean1.f7 = "7";
    final RowEncoder<CompactType> encoder =
        Encoders.buildBeanCodec(CompactType.class).compactEncoding().build().get();
    final List<Field> fields = encoder.schema().getFields();
    assertEquals(fields.get(0).getName(), "f2");
    assertEquals(fields.get(1).getName(), "f6");
    assertEquals(fields.get(2).getName(), "f7");
    assertEquals(fields.get(3).getName(), "f1");
    assertEquals(fields.get(4).getName(), "f5");
    assertEquals(fields.get(5).getName(), "f4");
    assertEquals(fields.get(6).getName(), "f3");

    final BinaryRow row = encoder.toRow(bean1);
    assertEquals(row.getClass(), CompactBinaryRow.class);
    assertEquals(row.getOffset(0), 0);
    assertEquals(row.getFloat64(0), 2);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final CompactType deserializedBean = encoder.fromRow(row);
    assertEquals(bean1, deserializedBean);
    assertEquals(buffer.size(), 4 + 7 + 1 + 4 + 8 + 1 + 2 + 4 + 8 + 8 + 1);
  }

  @Data
  public static class CompactListType {
    public List<CompactType> f1;
  }

  @Test
  public void testCompactListType() {
    final CompactType bean1 = new CompactType();
    bean1.f1 = 1;
    bean1.f2 = 2;
    bean1.f3 = 3;
    bean1.f4 = 4;
    bean1.f5 = 5;
    bean1.f6 = 6;
    bean1.f7 = "7";

    final CompactType bean2 = new CompactType();
    bean2.f1 = 7;
    bean2.f2 = 6;
    bean2.f3 = 5;
    bean2.f4 = 4;
    bean2.f5 = 3;
    bean2.f6 = 2;
    bean2.f7 = "1";

    final CompactListType list = new CompactListType();
    list.f1 = Arrays.asList(bean1, bean2);

    final RowEncoder<CompactListType> encoder =
        Encoders.buildBeanCodec(CompactListType.class).compactEncoding().build().get();

    final BinaryRow row = encoder.toRow(list);
    assertEquals(row.getClass(), CompactBinaryRow.class);
    assertEquals(row.getArray(0).getStruct(0).getClass(), CompactBinaryRow.class);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final CompactListType deserializedBean = encoder.fromRow(row);
    assertEquals(list, deserializedBean);
    assertEquals(buffer.size(), 48 + 48 + 48);
  }

  @Data
  public static class CompactUuidType {
    public UUID f1;
  }

  @Test
  public void testCompactUuidType() {
    final CompactUuidType bean1 = new CompactUuidType();
    bean1.f1 = new UUID(42, 24);
    final RowEncoder<CompactUuidType> encoder =
        Encoders.buildBeanCodec(CompactUuidType.class).compactEncoding().build().get();
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final CompactUuidType deserializedBean = encoder.fromRow(row);
    assertEquals(bean1, deserializedBean);
    assertEquals(buffer.size(), 16 + 8);
  }

  static class CompactUUIDCodec implements CustomCodec.MemoryBufferCodec<UUID> {
    @Override
    public Field getField(final String fieldName) {
      return Field.nullable(fieldName, new ArrowType.FixedSizeBinary(16));
    }

    @Override
    public MemoryBuffer encode(final UUID value) {
      final MemoryBuffer result = MemoryBuffer.newHeapBuffer(16);
      result.putInt64(0, value.getMostSignificantBits());
      result.putInt64(8, value.getLeastSignificantBits());
      return result;
    }

    @Override
    public UUID decode(final MemoryBuffer value) {
      return new UUID(value.readInt64(), value.readInt64());
    }
  }

  @Data
  public static class Nested1 {
    public short f1;
  }

  @Data
  public static class Nested2 {
    public int f1;
  }

  @Test
  public void testAllNonnullElideBitmap() {
    final Nested1 bean1 = new Nested1();
    bean1.f1 = 42;
    final Nested2 bean2 = new Nested2();
    bean2.f1 = 75;
    final RowEncoder<Nested1> encoder =
        Encoders.buildBeanCodec(Nested1.class).compactEncoding().build().get();
    BinaryRow row = encoder.toRow(bean1);
    MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final Nested1 deserializedBean = encoder.fromRow(row);
    assertEquals(bean1, deserializedBean);
    assertEquals(buffer.size(), 2);

    final RowEncoder<Nested2> encoder2 =
        Encoders.buildBeanCodec(Nested2.class).compactEncoding().build().get();
    row = encoder2.toRow(bean2);
    buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final Nested2 deserializedBean2 = encoder2.fromRow(row);
    assertEquals(bean1, deserializedBean2);
    assertEquals(buffer.size(), 4);
  }

  @Data
  public static class InlineNestedType {
    public Nested1 f1;
    public Nested2 f2;
  }

  @Test
  public void testInlineNestedType() {
    final InlineNestedType bean1 = new InlineNestedType();
    bean1.f1 = new Nested1();
    bean1.f1.f1 = 42;
    bean1.f2 = new Nested2();
    bean1.f2.f1 = 75;
    final RowEncoder<InlineNestedType> encoder =
        Encoders.buildBeanCodec(InlineNestedType.class).compactEncoding().build().get();
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final InlineNestedType deserializedBean = encoder.fromRow(row);
    assertEquals(bean1, deserializedBean);
    assertEquals(buffer.size(), 16 + 8);
  }
}
