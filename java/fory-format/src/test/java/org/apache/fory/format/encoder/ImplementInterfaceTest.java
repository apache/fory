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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeSet;
import lombok.Data;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.reflect.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ImplementInterfaceTest {

  public interface InterfaceType {
    int getF1();

    String getF2();

    NestedType getNested();

    PoisonPill getPoison();

    static PoisonPill builder() {
      return new PoisonPill();
    }
  }

  public interface NestedType {
    @ForyField(nullable = false)
    String getF3();
  }

  public static class PoisonPill {}

  @Data
  static class ImplementInterface implements InterfaceType {
    public int f1;
    public String f2;
    public NestedType nested;
    public PoisonPill poison = new PoisonPill();

    public ImplementInterface(final int f1, final String f2) {
      this.f1 = f1;
      this.f2 = f2;
    }
  }

  @Data
  static class ImplementNestedType implements NestedType {
    public String f3;

    public ImplementNestedType(final String f3) {
      this.f3 = f3;
    }
  }

  static {
    Encoders.registerCustomCodec(PoisonPill.class, new PoisonPillCodec());
    Encoders.registerCustomCodec(Id.class, new IdCodec());
    Encoders.registerCustomCodec(ListLazyElemInner.class, new ListLazyElemInnerCodec());
  }

  @Test
  public void testInterfaceTypes() {
    final InterfaceType bean1 = new ImplementInterface(42, "42");
    final RowEncoder<InterfaceType> encoder = Encoders.bean(InterfaceType.class);
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final InterfaceType deserializedBean = encoder.fromRow(row);
    assertEquals(bean1, deserializedBean);
  }

  @Test
  public void testNullValue() {
    final InterfaceType bean1 = new ImplementInterface(42, null);
    final RowEncoder<InterfaceType> encoder = Encoders.bean(InterfaceType.class);
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final InterfaceType deserializedBean = encoder.fromRow(row);
    assertEquals(deserializedBean, bean1);
  }

  @Test
  public void testNestedValue() {
    final ImplementInterface bean1 = new ImplementInterface(42, "42");
    bean1.nested = new ImplementNestedType("f3");
    final RowEncoder<InterfaceType> encoder = Encoders.bean(InterfaceType.class);
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final InterfaceType deserializedBean = encoder.fromRow(row);
    assertEquals(bean1, deserializedBean);
    Assert.assertEquals(bean1.nested.getF3(), deserializedBean.getNested().getF3());
  }

  private void assertEquals(final InterfaceType bean1, final InterfaceType deserializedBean) {
    Assert.assertNotSame(deserializedBean.getClass(), bean1.getClass());
    Assert.assertEquals(deserializedBean.getF1(), bean1.getF1());
    Assert.assertEquals(deserializedBean.getF2(), bean1.getF2());
  }

  static class PoisonPillCodec implements CustomCodec.ByteArrayCodec<PoisonPill> {
    @Override
    public byte[] encode(final PoisonPill value) {
      return new byte[0];
    }

    @Override
    public PoisonPill decode(final byte[] value) {
      throw new AssertionError();
    }
  }

  public interface OptionalType {
    Optional<String> f1();

    OptionalInt f2();

    OptionalLong f3();

    OptionalDouble f4();
  }

  static class OptionalTypeImpl implements OptionalType {
    Optional<String> f1;
    OptionalInt f2;
    OptionalLong f3;
    OptionalDouble f4;

    @Override
    public Optional<String> f1() {
      return f1;
    }

    @Override
    public OptionalInt f2() {
      return f2;
    }

    @Override
    public OptionalLong f3() {
      return f3;
    }

    @Override
    public OptionalDouble f4() {
      return f4;
    }
  }

  @Test
  public void testNullOptional() {
    final OptionalType bean1 = new OptionalTypeImpl();
    final RowEncoder<OptionalType> encoder = Encoders.bean(OptionalType.class);
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f1(), Optional.empty());
    Assert.assertEquals(deserializedBean.f2(), OptionalInt.empty());
    Assert.assertEquals(deserializedBean.f3(), OptionalLong.empty());
    Assert.assertEquals(deserializedBean.f4(), OptionalDouble.empty());
  }

  @Test
  public void testPresentOptional() {
    final OptionalTypeImpl bean1 = new OptionalTypeImpl();
    bean1.f1 = Optional.of("42");
    final RowEncoder<OptionalType> encoder = Encoders.bean(OptionalType.class);
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f1(), Optional.of("42"));
  }

  @Test
  public void testPresentOptionalInteger() {
    final OptionalTypeImpl bean1 = new OptionalTypeImpl();
    bean1.f2 = OptionalInt.of(42);
    final RowEncoder<OptionalType> encoder = Encoders.bean(OptionalType.class);
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f2(), OptionalInt.of(42));
  }

  @Test
  public void testPresentOptionalLong() {
    final OptionalTypeImpl bean1 = new OptionalTypeImpl();
    bean1.f3 = OptionalLong.of(42);
    final RowEncoder<OptionalType> encoder = Encoders.bean(OptionalType.class);
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f3(), OptionalLong.of(42));
  }

  @Test
  public void testPresentOptionalDouble() {
    final OptionalTypeImpl bean1 = new OptionalTypeImpl();
    bean1.f4 = OptionalDouble.of(42.42);
    final RowEncoder<OptionalType> encoder = Encoders.bean(OptionalType.class);
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f4(), OptionalDouble.of(42.42));
  }

  public static class Id<T> {
    byte id;

    Id(final byte id) {
      this.id = id;
    }
  }

  public interface OptionalCustomType {
    Optional<Id<OptionalCustomType>> f1();
  }

  static class OptionalCustomTypeImpl implements OptionalCustomType {
    @Override
    public Optional<Id<OptionalCustomType>> f1() {
      return Optional.of(new Id<>((byte) 42));
    }
  }

  static class IdCodec<T> implements CustomCodec.MemoryBufferCodec<Id<T>> {
    @Override
    public MemoryBuffer encode(final Id<T> value) {
      return MemoryBuffer.fromByteArray(new byte[] {value.id});
    }

    @Override
    public Id<T> decode(final MemoryBuffer value) {
      return new Id<>(value.readByte());
    }
  }

  @Test
  public void testOptionalCustomType() {
    final OptionalCustomType bean1 = new OptionalCustomTypeImpl();
    final RowEncoder<OptionalCustomType> encoder = Encoders.bean(OptionalCustomType.class);
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalCustomType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f1().get().id, bean1.f1().get().id);
  }

  public interface ListInner {
    int f1();
  }

  static class ListInnerImpl implements ListInner {
    private final int f1;

    ListInnerImpl(final int f1) {
      this.f1 = f1;
    }

    @Override
    public int f1() {
      return f1;
    }
  }

  public interface ListOuter {
    List<ListInner> f1();
  }

  static class ListOuterImpl implements ListOuter {
    private final List<ListInner> f1;

    ListOuterImpl(final List<ListInner> f1) {
      this.f1 = f1;
    }

    @Override
    public List<ListInner> f1() {
      return f1;
    }
  }

  @Test
  public void testListTooLazy() {
    final ListOuter bean1 = new ListOuterImpl(Arrays.asList(new ListInnerImpl(42)));
    final RowEncoder<ListOuter> encoder = Encoders.bean(ListOuter.class);
    final BinaryRow row = encoder.toRow(bean1);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final ListOuter deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f1().get(0).f1(), 42);
  }

  public interface Value extends Comparable<Value> {
    int v();

    @Override
    default int compareTo(final Value o) {
      return Integer.compare(v(), o.v());
    }
  }

  public static class ValueImpl implements Value {
    int v;

    public ValueImpl(final int v) {
      this.v = v;
    }

    @Override
    public int v() {
      return v;
    }
  }

  @Test
  public void testTreeSetOfInterface() {
    final ArrayEncoder<TreeSet<Value>> encoder =
        Encoders.arrayEncoder(new TypeRef<TreeSet<Value>>() {});
    final TreeSet<Value> expected = new TreeSet<Value>();
    expected.add(new ValueImpl(1));
    expected.add(new ValueImpl(3));
    expected.add(new ValueImpl(5));
    final BinaryArray array = encoder.toArray(expected);
    final MemoryBuffer buffer = array.getBuffer();
    array.pointTo(buffer, 0, buffer.size());
    final TreeSet<Value> deserializedBean = encoder.fromArray(array);
    Assert.assertEquals(deserializedBean, expected);
  }

  public static class ListLazyElemInner {
    private final int f1;
    static boolean check;

    ListLazyElemInner(final int f1) {
      if (check) {
        Assert.assertEquals(f1, 42);
      }
      this.f1 = f1;
    }

    public int f1() {
      return f1;
    }
  }

  static class ListLazyElemInnerCodec implements CustomCodec<ListLazyElemInner, Integer> {
    @Override
    public Field getField(final String fieldName) {
      return DataTypes.field(fieldName, DataTypes.int32());
    }

    @Override
    public TypeRef<Integer> encodedType() {
      return TypeRef.of(Integer.class);
    }

    @Override
    public Integer encode(final ListLazyElemInner value) {
      return value.f1();
    }

    @Override
    public ListLazyElemInner decode(final Integer value) {
      return new ListLazyElemInner(value);
    }
  }

  public interface ListLazyElemOuter {
    List<ListLazyElemInner> f1();
  }

  static class ListLazyElemOuterImpl implements ListLazyElemOuter {
    private final List<ListLazyElemInner> f1;

    ListLazyElemOuterImpl(final List<ListLazyElemInner> f1) {
      this.f1 = f1;
    }

    @Override
    public List<ListLazyElemInner> f1() {
      return f1;
    }
  }

  @Test
  public void testListElementsLazy() {
    final ListLazyElemOuter bean1 =
        new ListLazyElemOuterImpl(
            Arrays.asList(
                new ListLazyElemInner(0),
                new ListLazyElemInner(1),
                new ListLazyElemInner(42),
                null,
                new ListLazyElemInner(4)));
    final RowEncoder<ListLazyElemOuter> encoder = Encoders.bean(ListLazyElemOuter.class);
    final BinaryRow row = encoder.toRow(bean1);
    ListLazyElemInner.check = true;
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final ListLazyElemOuter deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f1().get(2).f1(), 42);
    Assert.assertEquals(deserializedBean.f1().get(3), null);
  }
}
