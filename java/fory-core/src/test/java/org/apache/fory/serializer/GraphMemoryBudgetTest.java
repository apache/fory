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
import static org.testng.Assert.assertNotNull;
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
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.collection.Int32List;
import org.apache.fory.context.ReadContext;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.memory.MemoryBuffer;
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
          DeserializationException.class,
          () -> reader.getSerializer(HashMap.class).read(mapContext));
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
  public void testScalarOwnersSkipBudget() {
    Fory fory = newFory(1);
    assertEquals(fory.deserialize(fory.serialize("graph budget")), "graph budget");

    byte[] bytes = new byte[] {1, 2, 3};
    assertTrue(Arrays.equals((byte[]) fory.deserialize(fory.serialize(bytes)), bytes));

    int[] ints = new int[] {4, 5, 6};
    assertTrue(Arrays.equals((int[]) fory.deserialize(fory.serialize(ints)), ints));

    Int32List denseList = new Int32List(new int[] {7, 8, 9});
    assertEquals(fory.deserialize(fory.serialize(denseList)), denseList);
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
          IndexOutOfBoundsException.class,
          () -> fory.getSerializer(ArrayList.class).read(readContext));
    } finally {
      readContext.reset();
    }
  }

  private static Fory compatibleFory(long maxGraphMemoryBytes, boolean codegen) {
    return Fory.builder()
        .withXlang(false)
        .withCompatible(true)
        .requireClassRegistration(false)
        .suppressClassRegistrationWarnings(true)
        .withMaxGraphMemoryBytes(maxGraphMemoryBytes)
        .withCodegen(codegen)
        .build();
  }

  public static class BudgetUpstream {
    public int kept;
    public int e0;
    public int e1;
    public int e2;

    public BudgetUpstream() {}

    public BudgetUpstream(int base) {
      kept = base;
      e0 = base + 1;
      e1 = base + 2;
      e2 = base + 3;
    }
  }

  public static class BudgetSinkDownstream {
    public int kept;
    public ForyExtraFields extraFields;

    public BudgetSinkDownstream() {}
  }

  /**
   * The compatible-mode extra-fields sink retains new owners (a {@link ForyExtraFields}, its
   * backing HashMap, and one map entry per captured field). {@code ForyExtraFieldsSupport.capture}
   * must charge those owners to the graph-memory budget before allocating, on both the interpreter
   * and generated read paths, so untrusted input cannot spawn many partial objects and grow
   * retained heap past maxGraphMemoryBytes. The exact required budget is (object owner) + (sink+map
   * owners on first capture) + 3 * (per-entry). The formula is recomputed independently of
   * ForyExtraFieldsSupport's private constants so this test also guards the accounting itself.
   */
  @Test
  public void testExtraFieldsSinkChargedToGraphBudget() {
    long sinkOwnerBytes =
        (long) GraphMemoryEstimates.shallowObjectBytes(ForyExtraFields.class)
            + GraphMemoryEstimates.shallowObjectBytes(HashMap.class);
    long perEntryBytes = 2L * REFERENCE_BYTES;
    long required =
        GraphMemoryEstimates.shallowObjectBytes(BudgetSinkDownstream.class)
            + sinkOwnerBytes
            + 3 * perEntryBytes;

    for (boolean codegen : new boolean[] {false, true}) {
      byte[] bytes =
          compatibleFory(DEFAULT_GRAPH_MEMORY_BYTES, codegen).serialize(new BudgetUpstream(100));

      // One byte short of the full budget: first field capture must reject when it tries to reserve
      // sink cost.
      assertThrows(
          InsecureException.class,
          () ->
              compatibleFory(required - 1, codegen).deserialize(bytes, BudgetSinkDownstream.class));

      // Exactly the sink cost: capture succeeds and the unmatched fields land in the sink.
      BudgetSinkDownstream ok =
          compatibleFory(required, codegen).deserialize(bytes, BudgetSinkDownstream.class);
      assertEquals(ok.kept, 100, "codegen=" + codegen);
      assertNotNull(ok.extraFields, "codegen=" + codegen);
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
