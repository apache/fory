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

import static org.apache.fory.io.ForyStreamReader.of;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.LongFunction;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.collection.BoolList;
import org.apache.fory.collection.Int32List;
import org.apache.fory.collection.Int64List;
import org.apache.fory.collection.UInt32List;
import org.apache.fory.collection.UInt64List;
import org.apache.fory.config.Int64Encoding;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.serializer.collection.PrimitiveListSerializers;
import org.apache.fory.type.BFloat16Array;
import org.apache.fory.type.Float16Array;
import org.apache.fory.type.Types;
import org.apache.fory.util.PrimitiveArrayCompressionType;
import org.testng.annotations.Test;

public class GraphMemoryBudgetTest extends ForyTestBase {
  private static final long DEFAULT_GRAPH_MEMORY_BYTES = 128L * 1024 * 1024;
  private static final int REFERENCE_BYTES = GraphMemoryEstimates.REFERENCE_BYTES;

  @Test
  public void testConfigDefaultsAndValidation() {
    assertEquals(builder().build().getConfig().maxGraphMemoryBytes(), DEFAULT_GRAPH_MEMORY_BYTES);
    assertEquals(newFory(123).getConfig().maxGraphMemoryBytes(), 123);
    assertThrows(IllegalArgumentException.class, () -> newFory(0));
    assertThrows(IllegalArgumentException.class, () -> newFory(-2));
  }

  @Test
  public void testDefaultFixedBudget() {
    ReadContext readContext = prepareContext(builder().build());
    try {
      readContext.reserveGraphMemory(DEFAULT_GRAPH_MEMORY_BYTES);
      assertThrows(InsecureException.class, () -> readContext.reserveGraphMemory(1));
    } finally {
      readContext.reset();
    }
  }

  @Test
  public void testExplicitBudgetWins() {
    Fory fory = newFory(7);
    ReadContext readContext = prepareContext(fory);
    try {
      readContext.reserveGraphMemory(7);
      assertThrows(InsecureException.class, () -> readContext.reserveGraphMemory(1));
    } finally {
      readContext.reset();
    }
  }

  @Test
  public void testNestedEmptyContainers() {
    List<Object> value = emptyLists(1);
    byte[] bytes = builder().build().serialize(value);
    long required = collectionBytes(1) + collectionBytes(0);

    assertThrows(InsecureException.class, () -> newFory(required - 1).deserialize(bytes));
    assertThrows(
        InsecureException.class,
        () -> newFory(required - 1).deserialize(of(new ByteArrayInputStream(bytes))));
    assertEquals(newFory(required).deserialize(bytes), value);
    assertEquals(newFory(required).deserialize(of(new ByteArrayInputStream(bytes))), value);
  }

  @Test
  public void testSiblingBudgetIsCumulative() {
    List<Object> value = nullLists(2, 64);
    byte[] bytes = builder().build().serialize(value);
    long firstChildOnly = collectionBytes(2) + collectionBytes(64);

    assertThrows(InsecureException.class, () -> newFory(firstChildOnly).deserialize(bytes));
    assertEquals(newFory(collectionBytes(2) + 2L * collectionBytes(64)).deserialize(bytes), value);
  }

  @Test
  public void testMapBudgetAndOverflow() {
    Fory fory = newFory(mapBytes(1) - 1);
    ReadContext readContext = prepareContext(fory);
    try {
      assertThrows(InsecureException.class, () -> readContext.reserveGraphMemory(mapBytes(1)));
    } finally {
      readContext.reset();
    }

    Fory exactFory = newFory(mapBytes(1));
    ReadContext exactContext = prepareContext(exactFory);
    try {
      exactContext.reserveGraphMemory(mapBytes(1));
      assertThrows(InsecureException.class, () -> exactContext.reserveGraphMemory(1));
    } finally {
      exactContext.reset();
    }

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(8);
    buffer.writeVarUInt32Small7(Integer.MAX_VALUE);
    buffer = trimBuffer(buffer);
    Fory reader = newFory(DEFAULT_GRAPH_MEMORY_BYTES);
    ReadContext mapContext = reader.getReadContext();
    mapContext.prepare(buffer, null, false);
    try {
      assertThrows(
          RuntimeException.class, () -> reader.getSerializer(HashMap.class).read(mapContext));
    } finally {
      mapContext.reset();
    }
  }

  @Test
  public void testEmptyContainerOwnerEstimates() {
    assertEmptyOwnerCharged(ArrayList.class, collectionBytes(0));
    assertEmptyOwnerCharged(HashSet.class, hashSetBytes(0));
    assertEmptyOwnerCharged(HashMap.class, mapBytes(0));
  }

  @Test
  public void testObjectArrayBudget() {
    Fory exactFory = newFory(objectArrayBytes(0));
    ReadContext exactContext = exactFory.getReadContext();
    MemoryBuffer exactBuffer = objectArraySizeBuffer(0);
    exactContext.prepare(exactBuffer, null, false);
    try {
      Object[] array = (Object[]) exactFory.getSerializer(Object[].class).read(exactContext);
      assertEquals(array.length, 0);
    } finally {
      exactContext.reset();
    }

    Fory slotFory = newFory(objectArrayBytes(2) - 1);
    ReadContext slotContext = slotFory.getReadContext();
    MemoryBuffer slotBuffer = objectArraySizeBuffer(2);
    slotContext.prepare(slotBuffer, null, false);
    try {
      assertThrows(
          InsecureException.class, () -> slotFory.getSerializer(Object[].class).read(slotContext));
    } finally {
      slotContext.reset();
    }
  }

  @Test
  public void testPojoGraphBudget() {
    Pojo value = new Pojo(7, 9L, "child string is skipped as a leaf");
    byte[] bytes = builder().build().serialize(value);
    long required = pojoBytes();

    assertThrows(InsecureException.class, () -> newFory(required - 1, false).deserialize(bytes));
    assertEquals(newFory(required, false).deserialize(bytes), value);

    assertThrows(InsecureException.class, () -> newFory(required - 1, true).deserialize(bytes));
    assertEquals(newFory(required, true).deserialize(bytes), value);
  }

  @Test
  public void testNestedEmptyPojoGraphBudget() {
    ArrayList<Object> value = new ArrayList<>();
    value.add(new EmptyPojo());
    value.add(new EmptyPojo());
    byte[] bytes = builder().build().serialize(value);
    long required = collectionBytes(2) + 2L * emptyPojoBytes();

    assertThrows(InsecureException.class, () -> newFory(required - 1).deserialize(bytes));
    List<?> decoded = (List<?>) newFory(required).deserialize(bytes);
    assertEquals(decoded.size(), 2);
    assertTrue(decoded.get(0) instanceof EmptyPojo);
    assertTrue(decoded.get(1) instanceof EmptyPojo);
  }

  @Test
  public void testGenericSelfRefBudget() {
    GenericNode<String> value = new GenericNode<>("root");
    value.next = value;
    value.children.add(value);
    long required = genericNodeBytes() + collectionBytes(1);

    Fory writer = genericNodeFory(DEFAULT_GRAPH_MEMORY_BYTES, true);
    byte[] bytes = writer.serialize(value);

    assertThrows(
        InsecureException.class, () -> genericNodeFory(required - 1, false).deserialize(bytes));
    assertGenericNode(genericNodeFory(required, false).deserialize(bytes));

    assertThrows(
        InsecureException.class, () -> genericNodeFory(required - 1, true).deserialize(bytes));
    assertGenericNode(genericNodeFory(required, true).deserialize(bytes));
  }

  @Test
  public void testSubListViewBudget() {
    ArrayList<Integer> source = new ArrayList<>();
    Collections.addAll(source, 1, 2, 3, 4);
    List<Integer> value = source.subList(1, 3);
    byte[] bytes = builder().withRefTracking(true).build().serialize(value);
    long required =
        collectionBytes(source.size()) + GraphMemoryEstimates.shallowObjectBytes(value.getClass());

    assertThrows(InsecureException.class, () -> newFory(required - 1).deserialize(bytes));
    assertEquals(newFory(required).deserialize(bytes), value);
  }

  @Test
  public void testBoxedArrayAsListBudget() {
    List<Boolean> booleans = Arrays.asList(true, false, true);
    byte[] booleanBytes = boxedArrayAsListBytes(booleans, Types.BOOL_ARRAY);
    long booleanRequired = collectionBytes(booleans.size());

    assertThrows(
        InsecureException.class,
        () -> readBoxedArrayAsList(booleanRequired - 1, booleanBytes, Types.BOOL_ARRAY));
    assertEquals(readBoxedArrayAsList(booleanRequired, booleanBytes, Types.BOOL_ARRAY), booleans);

    List<Double> doubles = Arrays.asList(1.0, 2.0, 3.0);
    byte[] doubleBytes = boxedArrayAsListBytes(doubles, Types.FLOAT64_ARRAY);
    long doubleRequired = primitiveArrayBytes(doubles.size(), 8);

    assertThrows(
        InsecureException.class,
        () -> readBoxedArrayAsList(doubleRequired - 1, doubleBytes, Types.FLOAT64_ARRAY));
    assertEquals(readBoxedArrayAsList(doubleRequired, doubleBytes, Types.FLOAT64_ARRAY), doubles);
  }

  @Test
  public void testArraysAsListBudget() {
    List<Object> value = Arrays.asList(null, null, null);
    byte[] bytes = builder().build().serialize(value);
    long required =
        objectArrayBytes(value.size()) + GraphMemoryEstimates.shallowObjectBytes(value.getClass());

    assertThrows(InsecureException.class, () -> newFory(required - 1).deserialize(bytes));
    assertEquals(newFory(required).deserialize(bytes), value);
  }

  @Test
  public void testLeafScalarSkipsBudget() {
    Fory fory = newFory(1);
    assertEquals(fory.deserialize(fory.serialize("graph budget")), "graph budget");
  }

  @Test
  public void testPrimitiveArrayBudget() {
    Object[][] cases = {
      {new boolean[] {true, false, true}, 3, 1, 0},
      {new byte[] {1, 2, 3}, 3, 1, 0},
      {new char[] {'a', 'b', 'c'}, 3, 2, 0},
      {new short[] {1, 2, 3}, 3, 2, 0},
      {new int[] {1, 2, 3}, 3, 4, 0},
      {new long[] {1, 2, 3}, 3, 8, 0},
      {new float[] {1, 2, 3}, 3, 4, 0},
      {new double[] {1, 2, 3}, 3, 8, 0},
      {Float16Array.of(1, 2, 3), 3, 2, GraphMemoryEstimates.shallowObjectBytes(Float16Array.class)},
      {
        BFloat16Array.of(1, 2, 3),
        3,
        2,
        GraphMemoryEstimates.shallowObjectBytes(BFloat16Array.class)
      }
    };
    for (Object[] testCase : cases) {
      Object value = testCase[0];
      int length = (int) testCase[1];
      int elemSize = (int) testCase[2];
      int wrapperBytes = (int) testCase[3];
      assertGraphBudget(
          value,
          primitiveArrayBytes(length, elemSize) + wrapperBytes,
          GraphMemoryBudgetTest::newFory);
    }
  }

  @Test
  public void testCompressedPrimitiveArrayBudget() {
    assertGraphBudget(
        new int[] {1, 2, 3},
        primitiveArrayBytes(3, 4),
        GraphMemoryBudgetTest::newCompressedPrimitiveFory);
    assertGraphBudget(
        new long[] {1, 2, 3},
        primitiveArrayBytes(3, 8),
        GraphMemoryBudgetTest::newCompressedPrimitiveFory);
  }

  @Test
  public void testNarrowPrimitiveArrayBudget() {
    int[] ints = {1, 2, 3};
    byte[] intBytes = uncompressedIntArrayBytes(ints);
    long intRequired = primitiveArrayBytes(ints.length, 4);
    assertThrows(
        InsecureException.class, () -> readNarrowIntArray(newFory(intRequired - 1), intBytes));
    assertTrue(Arrays.equals(readNarrowIntArray(newFory(intRequired), intBytes), ints));

    long[] longs = {1, 2, 3};
    byte[] longBytes = uncompressedLongArrayBytes(longs);
    long longRequired = primitiveArrayBytes(longs.length, 8);
    assertThrows(
        InsecureException.class, () -> readNarrowLongArray(newFory(longRequired - 1), longBytes));
    assertTrue(Arrays.equals(readNarrowLongArray(newFory(longRequired), longBytes), longs));
  }

  @Test
  public void testPrimitiveListBudget() {
    assertGraphBudget(
        new BoolList(new boolean[] {true, false, true}),
        primitiveListBytes(BoolList.class, 3, 1),
        GraphMemoryBudgetTest::newFory);
    assertGraphBudget(
        new Int32List(new int[] {1, 2, 3}),
        primitiveListBytes(Int32List.class, 3, 4),
        GraphMemoryBudgetTest::newCompressedPrimitiveFory);
    assertGraphBudget(
        new Int64List(new long[] {1, 2, 3}),
        primitiveListBytes(Int64List.class, 3, 8),
        GraphMemoryBudgetTest::newCompressedPrimitiveFory);
    assertGraphBudget(
        new UInt32List(new int[] {1, 2, 3}),
        primitiveListBytes(UInt32List.class, 3, 4),
        GraphMemoryBudgetTest::newCompressedPrimitiveFory);
    assertGraphBudget(
        new UInt64List(new long[] {1, 2, 3}),
        primitiveListBytes(UInt64List.class, 3, 8),
        GraphMemoryBudgetTest::newCompressedPrimitiveFory);
  }

  @Test
  public void testXlangPrimitiveListBudget() {
    Int32List value = new Int32List(new int[] {1, 2, 3});
    long required = primitiveListBytes(Int32List.class, 3, 4);
    Fory writer = newXlangFory(DEFAULT_GRAPH_MEMORY_BYTES);
    Serializer<Int32List> writerSerializer =
        new PrimitiveListSerializers.Int32ListSerializer(writer.getTypeResolver());
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    WriteContext writeContext = writer.getWriteContext();
    writeContext.prepare(buffer, null);
    try {
      writerSerializer.write(writeContext, value);
    } finally {
      writeContext.reset();
    }
    byte[] bytes = buffer.getBytes(0, buffer.writerIndex());

    assertThrows(InsecureException.class, () -> readInt32List(newXlangFory(required - 1), bytes));
    assertEquals(readInt32List(newXlangFory(required), bytes), value);
  }

  @Test
  public void testTruncatedCollectionStillFails() {
    Fory fory = newFory(collectionBytes(3));
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(8);
    buffer.writeVarUInt32Small7(3);
    buffer.writeByte(0);
    buffer.writeByte(0);
    buffer = trimBuffer(buffer);
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(buffer, null, false);
    try {
      assertThrows(
          RuntimeException.class, () -> fory.getSerializer(ArrayList.class).read(readContext));
    } finally {
      readContext.reset();
    }
  }

  private static Fory newFory(long maxGraphMemoryBytes) {
    return newFory(maxGraphMemoryBytes, true);
  }

  private static Fory newFory(long maxGraphMemoryBytes, boolean codegen) {
    return builder().withMaxGraphMemoryBytes(maxGraphMemoryBytes).withCodegen(codegen).build();
  }

  private static Fory genericNodeFory(long maxGraphMemoryBytes, boolean codegen) {
    return builder()
        .withMaxGraphMemoryBytes(maxGraphMemoryBytes)
        .withCodegen(codegen)
        .withRefTracking(true)
        .build();
  }

  private static ReadContext prepareContext(Fory fory) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(0);
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(buffer, null, false);
    return readContext;
  }

  private static byte[] boxedArrayAsListBytes(List<?> value, int typeId) {
    Fory fory = newXlangFory(DEFAULT_GRAPH_MEMORY_BYTES);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(8);
    WriteContext writeContext = fory.getWriteContext();
    writeContext.prepare(buffer, null);
    try {
      boxedArrayAsListSerializer(fory, typeId).write(writeContext, value);
      return buffer.getBytes(0, buffer.writerIndex());
    } finally {
      writeContext.reset();
    }
  }

  private static List<?> readBoxedArrayAsList(long maxGraphMemoryBytes, byte[] bytes, int typeId) {
    Fory fory = newXlangFory(maxGraphMemoryBytes);
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(MemoryBuffer.fromByteArray(bytes), null, false);
    try {
      return boxedArrayAsListSerializer(fory, typeId).read(readContext);
    } finally {
      readContext.reset();
    }
  }

  private static PrimitiveListSerializers.BoxedArrayAsListSerializer boxedArrayAsListSerializer(
      Fory fory, int typeId) {
    return new PrimitiveListSerializers.BoxedArrayAsListSerializer(
        fory.getTypeResolver(), typeId, "values");
  }

  private static Fory newXlangFory(long maxGraphMemoryBytes) {
    return builder()
        .withXlang(true)
        .withCodegen(false)
        .withMaxGraphMemoryBytes(maxGraphMemoryBytes)
        .build();
  }

  private static Fory newCompressedPrimitiveFory(long maxGraphMemoryBytes) {
    return builder()
        .withMaxGraphMemoryBytes(maxGraphMemoryBytes)
        .withIntArrayCompressed(true)
        .withLongArrayCompressed(true)
        .withLongCompressed(Int64Encoding.VARINT)
        .build();
  }

  private static void assertGraphBudget(
      Object value, long required, LongFunction<Fory> foryFactory) {
    byte[] bytes = foryFactory.apply(DEFAULT_GRAPH_MEMORY_BYTES).serialize(value);
    assertThrows(InsecureException.class, () -> foryFactory.apply(required - 1).deserialize(bytes));
    Object decoded = foryFactory.apply(required).deserialize(bytes);
    assertEquals(decoded.getClass(), value.getClass());
  }

  private static Int32List readInt32List(Fory fory, byte[] bytes) {
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(MemoryBuffer.fromByteArray(bytes), null, false);
    try {
      return new PrimitiveListSerializers.Int32ListSerializer(fory.getTypeResolver())
          .read(readContext);
    } finally {
      readContext.reset();
    }
  }

  private static byte[] uncompressedIntArrayBytes(int[] values) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    buffer.writeByte((byte) PrimitiveArrayCompressionType.NONE.getValue());
    buffer.writeIntsWithSize(values);
    return buffer.getBytes(0, buffer.writerIndex());
  }

  private static byte[] uncompressedLongArrayBytes(long[] values) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    buffer.writeByte((byte) PrimitiveArrayCompressionType.NONE.getValue());
    buffer.writeLongsWithSize(values);
    return buffer.getBytes(0, buffer.writerIndex());
  }

  private static int[] readNarrowIntArray(Fory fory, byte[] bytes) {
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(MemoryBuffer.fromByteArray(bytes), null, false);
    try {
      return new CompressedArraySerializers.CompressedIntArraySerializer(fory.getTypeResolver())
          .read(readContext);
    } finally {
      readContext.reset();
    }
  }

  private static long[] readNarrowLongArray(Fory fory, byte[] bytes) {
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(MemoryBuffer.fromByteArray(bytes), null, false);
    try {
      return new CompressedArraySerializers.CompressedLongArraySerializer(fory.getTypeResolver())
          .read(readContext);
    } finally {
      readContext.reset();
    }
  }

  private static long collectionBytes(int numElements) {
    return GraphMemoryEstimates.shallowObjectBytes(ArrayList.class)
        + (long) numElements * REFERENCE_BYTES;
  }

  private static long hashSetBytes(int numElements) {
    return GraphMemoryEstimates.shallowObjectBytes(HashSet.class)
        + GraphMemoryEstimates.shallowObjectBytes(HashMap.class)
        + (long) numElements * REFERENCE_BYTES;
  }

  private static long mapBytes(int numElements) {
    return GraphMemoryEstimates.shallowObjectBytes(HashMap.class)
        + (long) numElements * 2 * REFERENCE_BYTES;
  }

  private static long objectArrayBytes(int numElements) {
    return GraphMemoryEstimates.objectArrayBytes() + (long) numElements * REFERENCE_BYTES;
  }

  private static long primitiveArrayBytes(int numElements, int elemSize) {
    return GraphMemoryEstimates.objectArrayBytes() + (long) numElements * elemSize;
  }

  private static long primitiveListBytes(Class<?> type, int numElements, int elemSize) {
    return GraphMemoryEstimates.shallowObjectBytes(type)
        + primitiveArrayBytes(numElements, elemSize);
  }

  private static long emptyPojoBytes() {
    return GraphMemoryEstimates.shallowObjectBytes(EmptyPojo.class);
  }

  private static long pojoBytes() {
    return GraphMemoryEstimates.shallowObjectBytes(Pojo.class);
  }

  private static long genericNodeBytes() {
    return GraphMemoryEstimates.shallowObjectBytes(GenericNode.class);
  }

  private static void assertEmptyOwnerCharged(Class<?> type, long ownerBytes) {
    MemoryBuffer buffer = objectArraySizeBuffer(0);
    Fory rejected = newFory(ownerBytes - 1);
    ReadContext rejectedContext = rejected.getReadContext();
    rejectedContext.prepare(buffer, null, false);
    try {
      assertThrows(
          InsecureException.class, () -> rejected.getSerializer(type).read(rejectedContext));
    } finally {
      rejectedContext.reset();
    }

    Fory accepted = newFory(ownerBytes);
    ReadContext acceptedContext = accepted.getReadContext();
    acceptedContext.prepare(objectArraySizeBuffer(0), null, false);
    try {
      Object value = accepted.getSerializer(type).read(acceptedContext);
      assertTrue(type.isInstance(value));
    } finally {
      acceptedContext.reset();
    }
  }

  @SuppressWarnings("unchecked")
  private static void assertGenericNode(Object decodedObject) {
    GenericNode<String> decoded = (GenericNode<String>) decodedObject;
    assertEquals(decoded.value, "root");
    assertSame(decoded.next, decoded);
    assertEquals(decoded.children.size(), 1);
    assertSame(decoded.children.get(0), decoded);
  }

  private static List<Object> emptyLists(int numElements) {
    List<Object> root = new ArrayList<>(numElements);
    for (int i = 0; i < numElements; i++) {
      root.add(new ArrayList<>());
    }
    return root;
  }

  private static List<Object> nullLists(int siblings, int childElements) {
    List<Object> root = new ArrayList<>(siblings);
    for (int i = 0; i < siblings; i++) {
      List<Object> child = new ArrayList<>(childElements);
      for (int j = 0; j < childElements; j++) {
        child.add(null);
      }
      root.add(child);
    }
    return root;
  }

  private static MemoryBuffer objectArraySizeBuffer(int numElements) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(8);
    buffer.writeVarUInt32Small7(numElements);
    return trimBuffer(buffer);
  }

  private static MemoryBuffer trimBuffer(MemoryBuffer buffer) {
    return MemoryBuffer.fromByteArray(buffer.getBytes(0, buffer.writerIndex()));
  }

  public static final class EmptyPojo {}

  public static final class GenericNode<T> {
    public T value;
    public GenericNode<T> next;
    public List<GenericNode<T>> children = new ArrayList<>();

    public GenericNode() {}

    GenericNode(T value) {
      this.value = value;
    }
  }

  public static final class Pojo {
    public int intValue;
    public long longValue;
    public String name;

    public Pojo() {}

    Pojo(int intValue, long longValue, String name) {
      this.intValue = intValue;
      this.longValue = longValue;
      this.name = name;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Pojo)) {
        return false;
      }
      Pojo other = (Pojo) obj;
      return intValue == other.intValue
          && longValue == other.longValue
          && java.util.Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(intValue, longValue, name);
    }
  }
}
