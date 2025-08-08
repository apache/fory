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

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import lombok.Data;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CustomCodecTest {

  static {
    Encoders.registerCustomCodec(CustomType.class, ZoneId.class, new ZoneIdEncoder());
    Encoders.registerCustomCodec(CustomByteBuf.class, new CustomByteBufEncoder());
    Encoders.registerCustomCodec(CustomByteBuf2.class, new CustomByteBuf2Encoder());
    Encoders.registerCustomCodec(CustomByteBuf3.class, new CustomByteBuf3Encoder());
    Encoders.registerCustomCodec(UUID.class, new UuidEncoder());
    Encoders.registerCustomCodec(InterceptedType.class, new InterceptedTypeEncoder());
    Encoders.registerCustomCollectionFactory(
        SortedSet.class, UUID.class, new SortedSetOfUuidDecoder());
  }

  @Data
  public static class CustomType {
    public ZoneId f1;
    public CustomByteBuf f2;
    public CustomByteBuf2 f3;
    public CustomByteBuf3 f4;

    public CustomType() {}
  }

  @Data
  public static class CustomByteBuf {
    final byte[] buf;

    CustomByteBuf(final byte[] buf) {
      this.buf = buf;
    }
  }

  @Data
  public static class CustomByteBuf2 {
    final byte[] buf;

    CustomByteBuf2(final byte[] buf) {
      this.buf = buf;
    }
  }

  @Data
  public static class CustomByteBuf3 {
    final byte[] buf;

    CustomByteBuf3(final byte[] buf) {
      this.buf = buf;
    }
  }

  @Data
  public static class UuidType {
    public UUID f1;
    public UUID[] f2;
    public SortedSet<UUID> f3;

    public UuidType() {}
  }

  @Test
  public void testCustomTypes() {
    final CustomType bean = new CustomType();
    bean.f1 = ZoneId.of("America/Los_Angeles");
    bean.f2 = new CustomByteBuf("f2 value".getBytes(StandardCharsets.UTF_8));
    bean.f3 = new CustomByteBuf2("f3 value".getBytes(StandardCharsets.UTF_8));
    bean.f4 = new CustomByteBuf3("f4 value".getBytes(StandardCharsets.UTF_8));
    final RowEncoder<CustomType> encoder = Encoders.bean(CustomType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final CustomType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Test
  public void testNullFields() {
    final CustomType bean = new CustomType();
    final RowEncoder<CustomType> encoder = Encoders.bean(CustomType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final CustomType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Test
  public void testUuidFields() {
    final UuidType bean = new UuidType();
    bean.f1 = new UUID(1, 2);
    bean.f2 = new UUID[] {new UUID(2, 3), new UUID(3, 4), new UUID(5, 6)};
    bean.f3 = new TreeSet<>(Arrays.asList(new UUID(7, 8), new UUID(9, 10)));
    final RowEncoder<UuidType> encoder = Encoders.bean(UuidType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final UuidType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
    Assert.assertEquals(deserializedBean.f3.comparator(), UnsignedUuidComparator.INSTANCE);
  }

  static class ZoneIdEncoder implements CustomCodec<ZoneId, String> {
    @Override
    public Field getField(final String fieldName) {
      return Field.nullable(fieldName, ArrowType.Utf8.INSTANCE);
    }

    @Override
    public String encode(final ZoneId value) {
      return Objects.toString(value, null);
    }

    @Override
    public ZoneId decode(final String value) {
      return ZoneId.of(value);
    }

    @Override
    public TypeRef<String> encodedType() {
      return TypeRef.of(String.class);
    }
  }

  static class CustomByteBufEncoder implements CustomCodec.MemoryBufferCodec<CustomByteBuf> {
    @Override
    public MemoryBuffer encode(final CustomByteBuf value) {
      return MemoryBuffer.wrap(value.buf);
    }

    @Override
    public CustomByteBuf decode(final MemoryBuffer value) {
      return new CustomByteBuf(value.getRemainingBytes());
    }
  }

  static class CustomByteBuf2Encoder implements CustomCodec.ByteArrayCodec<CustomByteBuf2> {
    @Override
    public byte[] encode(final CustomByteBuf2 value) {
      return value.buf;
    }

    @Override
    public CustomByteBuf2 decode(final byte[] value) {
      return new CustomByteBuf2(value);
    }
  }

  static class CustomByteBuf3Encoder implements CustomCodec.BinaryArrayCodec<CustomByteBuf3> {
    @Override
    public BinaryArray encode(final CustomByteBuf3 value) {
      return BinaryArray.fromPrimitiveArray(value.buf);
    }

    @Override
    public CustomByteBuf3 decode(final BinaryArray value) {
      return new CustomByteBuf3(value.toByteArray());
    }
  }

  static class UuidEncoder implements CustomCodec.MemoryBufferCodec<UUID> {
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

  static class SortedSetOfUuidDecoder implements CustomCollectionFactory<UUID, SortedSet<UUID>> {
    @Override
    public SortedSet<UUID> newCollection(final int size) {
      return new TreeSet<>(UnsignedUuidComparator.INSTANCE);
    }
  }

  private enum UnsignedUuidComparator implements Comparator<UUID> {
    INSTANCE;

    @Override
    public int compare(final UUID o1, final UUID o2) {
      final int cmpMsb =
          Long.compareUnsigned(o1.getMostSignificantBits(), o2.getMostSignificantBits());
      if (cmpMsb != 0) {
        return cmpMsb;
      }
      return Long.compareUnsigned(o1.getLeastSignificantBits(), o2.getLeastSignificantBits());
    }
  }

  public interface InterceptedType {
    int f1();
  }

  public static class InterceptedTypeImpl implements InterceptedType {
    private final int f1;

    public InterceptedTypeImpl(final int f1) {
      this.f1 = f1;
    }

    @Override
    public int f1() {
      return f1;
    }
  }

  static class InterceptedTypeEncoder implements CustomCodec.InterceptingCodec<InterceptedType> {
    @Override
    public TypeRef<InterceptedType> encodedType() {
      return TypeRef.of(InterceptedType.class);
    }

    @Override
    public InterceptedType encode(final InterceptedType value) {
      return new InterceptedTypeImpl(value.f1() + 2);
    }

    @Override
    public InterceptedType decode(final InterceptedType value) {
      return new InterceptedTypeImpl(value.f1() + 3);
    }
  }

  @Test
  public void testCodecTypeInterception() {
    final InterceptedType bean = new InterceptedTypeImpl(42);
    final RowEncoder<InterceptedType> encoder = Encoders.bean(InterceptedType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final InterceptedType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f1(), bean.f1() + 5);
    Assert.assertEquals(deserializedBean.getClass(), InterceptedTypeImpl.class);
  }

  public interface WrapInterceptedType {
    InterceptedType f1();
  }

  public static class WrapInterceptedTypeImpl implements WrapInterceptedType {
    private final InterceptedType f1;

    public WrapInterceptedTypeImpl(final InterceptedType f1) {
      this.f1 = f1;
    }

    @Override
    public InterceptedType f1() {
      return f1;
    }
  }

  @Test
  public void testNestedCodecTypeInterception() {
    final WrapInterceptedType bean = new WrapInterceptedTypeImpl(new InterceptedTypeImpl(42));
    final RowEncoder<WrapInterceptedType> encoder = Encoders.bean(WrapInterceptedType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final WrapInterceptedType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f1().f1(), bean.f1().f1() + 5);
    Assert.assertEquals(deserializedBean.f1().getClass(), InterceptedTypeImpl.class);
  }
}
