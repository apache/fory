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
import static org.testng.Assert.assertNotSame;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import org.apache.fory.format.encoder.RowEncoderTest.Bar;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.CompactBinaryRow;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.reflect.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CompactCodecTest {

  static {
    Encoders.registerCustomCodec(UUID.class, new CompactUUIDCodec());
    Encoders.registerCustomCodec(NotNullByte.class, new NotNullByteCodec());
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
    final List<Field> fields = encoder.schema().fields();
    assertEquals(fields.get(0).name(), "f2");
    assertEquals(fields.get(1).name(), "f6");
    assertEquals(fields.get(2).name(), "f7");
    assertEquals(fields.get(3).name(), "f1");
    assertEquals(fields.get(4).name(), "f5");
    assertEquals(fields.get(5).name(), "f4");
    assertEquals(fields.get(6).name(), "f3");

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

  @Test
  public void testEncodeResetsNullBits() {
    final CompactType bean1 = new CompactType();
    bean1.f1 = 1;
    bean1.f2 = 2;
    bean1.f3 = 3;
    bean1.f4 = 4;
    bean1.f5 = 5;
    bean1.f6 = 6;
    final RowEncoder<CompactType> encoder =
        Encoders.buildBeanCodec(CompactType.class).compactEncoding().build().get();

    byte[] encoded = encoder.encode(bean1);
    bean1.f7 = "Not null!";
    encoded = encoder.encode(bean1);
    final CompactType deserializedBean = encoder.decode(encoded);
    assertEquals(deserializedBean, bean1);
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
    assertEquals(deserializedBean, list);
    assertEquals(buffer.size(), 37 + 48 + 48);
  }

  @Data
  public static class CompactUuidType {
    public UUID f1;

    public CompactUuidType() {}

    public CompactUuidType(final UUID f1) {
      this.f1 = f1;
    }
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
    // Note: Using binary() type which is variable-width, so size includes offset+size header (8
    // bytes)
    // plus the actual data (16 bytes) plus null bitmap (1 byte) plus alignment = 32 bytes
    assertEquals(buffer.size(), 32);
  }

  static class CompactUUIDCodec implements CustomCodec.MemoryBufferCodec<UUID> {
    @Override
    public Field getForyField(final String fieldName) {
      return DataTypes.field(fieldName, DataTypes.binary());
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
    assertEquals(bean2, deserializedBean2);
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
    assertEquals(row.getSchema().fields().get(0).name(), "f2");
    assertEquals(row.getSchema().fields().get(1).name(), "f1");
    assertEquals(row.getOffset(0), 0);
    assertEquals(row.getOffset(1), 4);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final InlineNestedType deserializedBean = encoder.fromRow(row);
    assertEquals(deserializedBean, bean1);
    assertEquals(buffer.size(), 7);
  }

  @Data
  public static class InlineNestedArrayType {
    public InlineNestedType f1;
    public String f2;
    public UUID[] f3;
  }

  @Test
  public void testInlineNestedArrayType() {
    final InlineNestedArrayType bean1 = new InlineNestedArrayType();
    bean1.f1 = new InlineNestedType();
    bean1.f1.f1 = new Nested1();
    bean1.f1.f1.f1 = 42;
    bean1.f1.f2 = new Nested2();
    bean1.f1.f2.f1 = 75;
    bean1.f2 = "luna";
    bean1.f3 = new UUID[] {new UUID(1, 2), new UUID(3, 4), new UUID(5, 6)};
    final RowEncoder<InlineNestedArrayType> encoder =
        Encoders.buildBeanCodec(InlineNestedArrayType.class).compactEncoding().build().get();
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final InlineNestedArrayType deserializedBean = encoder.fromRow(row);
    assertEquals(deserializedBean, bean1);
    // Size is larger due to variable-width binary encoding for UUIDs
    assertEquals(buffer.size(), 109);
  }

  @Data
  public static class InlinePrimitiveNestedArrayType {
    public InlineNestedType f1;
    public String f2;
    public short[] f3;
  }

  @Test
  public void testInlinePrimitiveNestedArrayType() {
    final InlinePrimitiveNestedArrayType bean1 = new InlinePrimitiveNestedArrayType();
    bean1.f1 = new InlineNestedType();
    bean1.f1.f1 = new Nested1();
    bean1.f1.f1.f1 = 42;
    bean1.f1.f2 = new Nested2();
    bean1.f1.f2.f1 = 75;
    bean1.f2 = "luna";
    bean1.f3 = new short[] {1, 2, 3};
    final RowEncoder<InlinePrimitiveNestedArrayType> encoder =
        Encoders.buildBeanCodec(InlinePrimitiveNestedArrayType.class)
            .compactEncoding()
            .build()
            .get();
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final InlinePrimitiveNestedArrayType deserializedBean = encoder.fromRow(row);
    assertEquals(deserializedBean, bean1);
    assertEquals(buffer.size(), 50);
  }

  @Data
  public static class CompactMapType {
    public Map<UUID, CompactUuidType> map;
  }

  @Test
  public void testCompactMapType() {
    final CompactMapType bean1 = new CompactMapType();
    bean1.map = new HashMap<>();
    final UUID u1 = new UUID(42, 24);
    bean1.map.put(u1, new CompactUuidType(u1));
    final UUID u2 = new UUID(55, 66);
    bean1.map.put(u2, new CompactUuidType(u2));
    final RowEncoder<CompactMapType> encoder =
        Encoders.buildBeanCodec(CompactMapType.class).compactEncoding().build().get();
    final BinaryRow row = encoder.toRow(bean1);
    assertEquals(
        row.getMap(0).keyArray().toString(),
        "[0x2a000000000000001800000000000000,0x37000000000000004200000000000000]");
    assertEquals(
        row.getMap(0).valueArray().toString(),
        "[{f1=0x2a000000000000001800000000000000},{f1=0x37000000000000004200000000000000}]");
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final CompactMapType deserializedBean = encoder.fromRow(row);
    assertEquals(deserializedBean, bean1);
    // Size is larger due to variable-width binary encoding for UUIDs
    assertEquals(buffer.size(), 161);
  }

  @Test
  public void testTwoFieldsAndASet() {
    final TwoFieldsAndASet bean1 = new TwoFieldsAndASet();
    bean1.f1 = new UUID(42, 24);
    bean1.f2 = new UUID(55, 66);
    bean1.f3 = Set.of(LocalDate.of(2112, 1, 1));
    final RowEncoder<TwoFieldsAndASet> encoder =
        Encoders.buildBeanCodec(TwoFieldsAndASet.class).compactEncoding().build().get();
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final TwoFieldsAndASet deserializedBean = encoder.fromRow(row);
    assertEquals(deserializedBean, bean1);
  }

  @Data
  public static class TwoFieldsAndASet {
    public UUID f1;
    public UUID f2;
    public Set<LocalDate> f3;
  }

  @Test
  public void testNestedConfigObject() {
    final InnerConfigObject inner = new InnerConfigObject();
    inner.f1 = new UUID(1, 2);
    inner.f2 = new UUID(3, 4);
    inner.f3 = new UUID(5, 6);
    inner.f4 = true;
    inner.f5 = "Indubitably";
    inner.f6 = EnumSet.of(ConfigEnum.B, ConfigEnum.C);
    final OuterConfigObject bean1 = new OuterConfigObject();
    bean1.f1 = inner;
    bean1.f2 = LocalDate.of(2112, 1, 1);
    final RowEncoder<OuterConfigObject> encoder =
        Encoders.buildBeanCodec(OuterConfigObject.class).compactEncoding().build().get();
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OuterConfigObject deserializedBean = encoder.fromRow(row);
    assertEquals(deserializedBean, bean1);
  }

  @Data
  public static class OuterConfigObject {
    public InnerConfigObject f1;
    public LocalDate f2;
  }

  @Data
  public static class InnerConfigObject {
    public UUID f1;
    public UUID f2;
    public UUID f3;
    public boolean f4;
    public String f5;
    public Set<ConfigEnum> f6;
  }

  public enum ConfigEnum {
    A,
    B,
    C
  }

  @Test
  public void testBiglyBean() {
    final BiglyBean big = new BiglyBean();
    big.f1 = new UUID(1, 2);
    big.f2 = ConfigEnum.B;
    big.f3 = ConfigEnum.C;
    big.f4 = new UUID(3, 4);
    big.f5 = new UUID(5, 6);
    big.f6 = Optional.of("Indubitably");
    big.f7 = LocalDate.of(2112, 2, 2);
    big.f8 = Optional.of(LocalDate.of(1221, 3, 4));
    big.f9 = OptionalLong.of(-42);
    big.f10 = OptionalLong.of(-24);
    big.f11 = 1234;
    big.f12 = 4321;
    big.f13 = Instant.ofEpochMilli(12345678);
    big.f14 = Instant.ofEpochMilli(87654321);
    big.f15 = Optional.of(ConfigEnum.B);
    final RowEncoder<BiglyBean> encoder =
        Encoders.buildBeanCodec(BiglyBean.class).compactEncoding().build().get();
    final BinaryRow row = encoder.toRow(big);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final BiglyBean deserializedBean = encoder.fromRow(row);
    assertEquals(deserializedBean, big);
  }

  @Data
  public static class BiglyBean {
    public UUID f1;
    public ConfigEnum f2;
    public ConfigEnum f3;
    public UUID f4;
    public UUID f5;
    public Optional<String> f6;
    public LocalDate f7;
    public Optional<LocalDate> f8;
    public OptionalLong f9;
    public OptionalLong f10;
    public long f11;
    public long f12;
    public Instant f13;
    public Instant f14;
    public Optional<ConfigEnum> f15;
  }

  @Test
  public void testArrayOfStruct() {
    final ArrayNestedBean bean = new ArrayNestedBean();
    bean.f1 = new UUID(1, 2);
    bean.f2 = new UUID(3, 4);
    bean.f3 = Instant.ofEpochMilli(12345678);
    bean.f4 = true;
    bean.f5 = Instant.ofEpochMilli(87654321);
    bean.f6 = ConfigEnum.B;
    bean.f7 = new UUID(5, 6);
    bean.f8 = false;
    bean.f9 = new ArrayNestedNestedBean();
    bean.f9.f1 = Instant.ofEpochMilli(43218765);
    bean.f9.f2 = "Indubitably";
    bean.f9.f3 = EnumSet.of(ConfigEnum.B, ConfigEnum.C);
    bean.f9.f4 = new UUID(7, 8);
    bean.f9.f5 = "luna";
    bean.f9.f6 = List.of();
    final ArrayEncoder<List<ArrayNestedBean>> encoder =
        Encoders.buildArrayCodec(new TypeRef<List<ArrayNestedBean>>() {})
            .compactEncoding()
            .build()
            .get();
    final List<ArrayNestedBean> expected = List.of(bean, bean);
    final BinaryArray arr = encoder.toArray(expected);
    final MemoryBuffer buffer = MemoryUtils.wrap(arr.toBytes());
    arr.pointTo(buffer, 0, buffer.size());
    final List<ArrayNestedBean> deserializedBean = encoder.fromArray(arr);
    assertEquals(deserializedBean, expected);
  }

  @Data
  public static class ArrayNestedBean {
    public UUID f1;
    public UUID f2;
    public Instant f3;
    public boolean f4;
    public Instant f5;
    public ConfigEnum f6;
    public UUID f7;
    public boolean f8;
    public ArrayNestedNestedBean f9;
  }

  @Data
  public static class ArrayNestedNestedBean {
    public Instant f1;
    public String f2;
    public Set<ConfigEnum> f3;
    public UUID f4;
    public String f5;
    public List<ArrayNestedNestedNestedBean> f6;
  }

  @Data
  public static class ArrayNestedNestedNestedBean {
    public Instant f1;
    public String f2;
    public String f3;
    public String f4;
  }

  @Test
  public void testNotNullByteArray() {
    final List<NotNullByte> expected = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      expected.add(new NotNullByte((byte) i));
    }
    final ArrayEncoder<List<NotNullByte>> encoder =
        Encoders.buildArrayCodec(new TypeRef<List<NotNullByte>>() {})
            .compactEncoding()
            .build()
            .get();
    final BinaryArray arr = encoder.toArray(expected);
    final MemoryBuffer buffer = MemoryUtils.wrap(arr.toBytes());
    arr.pointTo(buffer, 0, buffer.size());
    final List<NotNullByte> deserializedBean = encoder.fromArray(arr);
    assertEquals(deserializedBean, expected);
    assertEquals(arr.getSizeInBytes(), 68);
  }

  @Test
  public void testArrayBufferReuse() {
    final List<NotNullByte> expected = new ArrayList<>();
    final List<NotNullByte> expectedRev = new ArrayList<>();
    for (int i = 0; i < 128; i++) {
      final NotNullByte val = new NotNullByte((byte) i);
      expected.add(val);
      if (i < 16) {
        expectedRev.add(0, val);
      }
    }
    final ArrayEncoder<List<NotNullByte>> encoder =
        Encoders.buildArrayCodec(new TypeRef<List<NotNullByte>>() {})
            .compactEncoding()
            .build()
            .get();
    final BinaryArray arr = encoder.toArray(expected);
    final BinaryArray subRev = encoder.toArray(expectedRev);
    assertNotSame(arr.getBuffer(), subRev.getBuffer());
  }

  @Data
  public static class NotNullByte {
    public byte b;

    public NotNullByte(final byte b) {
      this.b = b;
    }
  }

  public static class NotNullByteCodec implements CustomCodec<NotNullByte, Byte> {
    @Override
    public Field getForyField(final String fieldName) {
      return DataTypes.notNullField(fieldName, DataTypes.int8());
    }

    @Override
    public TypeRef<Byte> encodedType() {
      return new TypeRef<Byte>() {};
    }

    @Override
    public Byte encode(final NotNullByte value) {
      return value.b;
    }

    @Override
    public NotNullByte decode(final Byte value) {
      return new NotNullByte(value);
    }
  }

  @Test
  public void testCompactMapEncoder() {
    final InnerConfigObject inner = new InnerConfigObject();
    inner.f1 = new UUID(1, 2);
    inner.f2 = new UUID(3, 4);
    inner.f3 = new UUID(5, 6);
    inner.f4 = true;
    inner.f5 = "Indubitably";
    inner.f6 = EnumSet.of(ConfigEnum.B, ConfigEnum.C);
    final MapEncoder<Map<UUID, InnerConfigObject>> encoder =
        Encoders.buildMapCodec(new TypeRef<Map<UUID, InnerConfigObject>>() {})
            .compactEncoding()
            .build()
            .get();
    final var expected = Map.of(inner.f1, inner);
    final BinaryMap map = encoder.toMap(expected);
    final MemoryBuffer buffer = MemoryUtils.wrap(map.toBytes());
    map.pointTo(buffer, 0, buffer.size());
    final var deserializedMap = encoder.fromMap(map);
    assertEquals(deserializedMap, expected);
  }

  @Test
  public void testNullClearOffset() {
    final RowEncoder<Bar> encoder =
        Encoders.buildBeanCodec(Bar.class).compactEncoding().build().get();
    final Bar bar = new Bar();
    bar.f1 = 42;
    bar.f2 = null;
    final byte[] nullBefore = encoder.encode(bar);

    bar.f2 = "not null";
    // write offset and size
    encoder.encode(bar);

    bar.f2 = null;
    final byte[] nullAfter = encoder.encode(bar);
    Assert.assertEquals(nullAfter, nullBefore);
  }

  @Data
  public static class MediumBean {
    public UUID f1;
    public LocalDate f2;
    public UUID f3;
  }

  @Test
  public void testOffsetBuffer() {
    final MediumBean bean1 = new MediumBean();
    bean1.f1 = new UUID(1, 2);
    bean1.f2 = LocalDate.ofEpochDay(56);
    bean1.f3 = new UUID(3, 4);

    final MediumBean bean2 = new MediumBean();
    bean2.f1 = new UUID(7, 8);
    bean2.f2 = LocalDate.ofEpochDay(1112);
    bean2.f3 = new UUID(9, 10);

    final MediumBean bean3 = new MediumBean();
    bean3.f1 = new UUID(13, 14);
    bean3.f2 = LocalDate.ofEpochDay(1718);
    bean3.f3 = new UUID(15, 16);

    final ArrayEncoder<List<MediumBean>> encoder =
        Encoders.buildArrayCodec(new TypeRef<List<MediumBean>>() {})
            .compactEncoding()
            .withSizeEmbedded(false)
            .build()
            .get();
    final List<MediumBean> expected = Arrays.asList(bean1, bean2, bean3, null);
    final byte[] bytes = encoder.encode(expected);
    final int offset = 17;
    final var buf = ByteBuffer.allocate(bytes.length + offset);
    buf.put(new byte[offset]);
    buf.put(bytes);
    buf.position(offset);
    buf.limit(offset + bytes.length);
    final List<MediumBean> deserializedBean = encoder.decode(MemoryBuffer.fromByteBuffer(buf));
    assertEquals(deserializedBean, expected);
  }

  @Data
  public static class HeaderClearObject {
    public UUID f1;
    public InnerHeaderClearObject f2;
  }

  @Data
  public static class InnerHeaderClearObject {
    public UUID f1;
    public UUID f2;
    public UUID f3;
  }

  @Test
  public void testHeaderClear() {
    final InnerHeaderClearObject inner = new InnerHeaderClearObject();
    inner.f1 = UUID.randomUUID();

    final HeaderClearObject outer = new HeaderClearObject();
    outer.f2 = inner;

    final RowEncoder<HeaderClearObject> encoder =
        Encoders.buildBeanCodec(HeaderClearObject.class).compactEncoding().build().get();
    final byte[] bytes = encoder.encode(outer);
    final HeaderClearObject deserializedBean = encoder.decode(bytes);
    assertEquals(deserializedBean, outer);
  }
}
