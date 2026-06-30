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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.collection.Int32List;
import org.apache.fory.context.ReadContext;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.annotations.Test;

public class ContainerMemoryBudgetTest extends ForyTestBase {
  private static final long KNOWN_ROOT_MULTIPLIER = 8L;
  private static final long KNOWN_ROOT_SLACK_BYTES = 64L * 1024;
  private static final long STREAM_ROOT_BYTES = 128L * 1024 * 1024;
  private static final int REFERENCE_BYTES = MemoryBuffer.objectArrayIndexScale();

  @Test
  public void testConfigValidation() {
    assertEquals(newFory(-1).getConfig().maxContainerMemoryBytes(), -1);
    assertEquals(newFory(123).getConfig().maxContainerMemoryBytes(), 123);
    assertThrows(IllegalArgumentException.class, () -> builder().withMaxContainerMemoryBytes(0));
    assertThrows(IllegalArgumentException.class, () -> builder().withMaxContainerMemoryBytes(-2));
  }

  @Test
  public void testKnownAutoBudget() {
    Fory fory = newFory(-1);
    ReadContext readContext = prepareContext(fory, 17, false);
    try {
      long budget = knownAutoBytes(17);
      readContext.reserveContainerMemory(budget);
      assertThrows(InsecureException.class, () -> readContext.reserveContainerMemory(1));
    } finally {
      readContext.reset();
    }
  }

  @Test
  public void testStreamAutoBudget() {
    Fory fory = newFory(-1);
    ReadContext readContext = prepareContext(fory, 17, true);
    try {
      readContext.reserveContainerMemory(STREAM_ROOT_BYTES);
      assertThrows(InsecureException.class, () -> readContext.reserveContainerMemory(1));
    } finally {
      readContext.reset();
    }
  }

  @Test
  public void testExplicitBudgetWins() {
    Fory fory = newFory(7);
    ReadContext readContext = prepareContext(fory, 1024 * 1024, false);
    try {
      readContext.reserveContainerMemory(7);
      assertThrows(InsecureException.class, () -> readContext.reserveContainerMemory(1));
    } finally {
      readContext.reset();
    }
  }

  @Test
  public void testNestedEmptyContainersUseParentStorage() {
    List<Object> value = emptyLists(1);
    byte[] bytes = newFory(-1).serialize(value);
    long required = collectionBytes(1);

    assertThrows(InsecureException.class, () -> newFory(required - 1).deserialize(bytes));
    assertEquals(newFory(required).deserialize(bytes), value);
  }

  @Test
  public void testSiblingBudgetIsCumulative() {
    List<Object> value = nullLists(2, 64);
    byte[] bytes = newFory(-1).serialize(value);
    long firstChildOnly = collectionBytes(2) + collectionBytes(64);

    assertThrows(InsecureException.class, () -> newFory(firstChildOnly).deserialize(bytes));
    assertEquals(newFory(firstChildOnly + collectionBytes(64)).deserialize(bytes), value);
  }

  @Test
  public void testMapBudgetAndOverflow() {
    Fory fory = newFory(mapBytes(1) - 1);
    ReadContext readContext = prepareContext(fory, 8, false);
    try {
      assertThrows(InsecureException.class, () -> readContext.reserveContainerMemory(mapBytes(1)));
    } finally {
      readContext.reset();
    }

    Fory exactFory = newFory(mapBytes(1));
    ReadContext exactContext = prepareContext(exactFory, 8, false);
    try {
      exactContext.reserveContainerMemory(mapBytes(1));
      assertThrows(InsecureException.class, () -> exactContext.reserveContainerMemory(1));
    } finally {
      exactContext.reset();
    }

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(8);
    buffer.writeVarUInt32Small7(Integer.MAX_VALUE);
    buffer = trimBuffer(buffer);
    Fory reader = newFory(STREAM_ROOT_BYTES);
    ReadContext mapContext = reader.getReadContext();
    mapContext.prepare(buffer, null, false, buffer.remaining(), false);
    try {
      assertThrows(
          DeserializationException.class,
          () -> reader.getSerializer(HashMap.class).read(mapContext));
    } finally {
      mapContext.reset();
    }
  }

  @Test
  public void testObjectArrayBudget() {
    Fory exactFory = newFory(1);
    ReadContext exactContext = exactFory.getReadContext();
    MemoryBuffer exactBuffer = objectArraySizeBuffer(0);
    exactContext.prepare(exactBuffer, null, false, exactBuffer.remaining(), false);
    try {
      Object[] array = (Object[]) exactFory.getSerializer(Object[].class).read(exactContext);
      assertEquals(array.length, 0);
    } finally {
      exactContext.reset();
    }

    Fory slotFory = newFory(objectArrayBytes(2) - 1);
    ReadContext slotContext = slotFory.getReadContext();
    MemoryBuffer slotBuffer = objectArraySizeBuffer(2);
    slotContext.prepare(slotBuffer, null, false, slotBuffer.remaining(), false);
    try {
      assertThrows(
          InsecureException.class, () -> slotFory.getSerializer(Object[].class).read(slotContext));
    } finally {
      slotContext.reset();
    }
  }

  @Test
  public void testScalarOwnersSkipBudget() {
    Fory fory = newFory(1);
    assertEquals(fory.deserialize(fory.serialize("container budget")), "container budget");

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
    readContext.prepare(buffer, null, false, buffer.remaining(), false);
    try {
      assertThrows(
          IndexOutOfBoundsException.class,
          () -> fory.getSerializer(ArrayList.class).read(readContext));
    } finally {
      readContext.reset();
    }
  }

  private static Fory newFory(long maxContainerMemoryBytes) {
    return builder().withMaxContainerMemoryBytes(maxContainerMemoryBytes).build();
  }

  private static ReadContext prepareContext(
      Fory fory, int rootInputBytes, boolean unknownLengthInput) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(0);
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(buffer, null, false, rootInputBytes, unknownLengthInput);
    return readContext;
  }

  private static long collectionBytes(int numElements) {
    return (long) numElements * REFERENCE_BYTES;
  }

  private static long mapBytes(int numElements) {
    return (long) numElements * 2 * REFERENCE_BYTES;
  }

  private static long objectArrayBytes(int numElements) {
    return (long) numElements * REFERENCE_BYTES;
  }

  private static long knownAutoBytes(int inputBytes) {
    return inputBytes * KNOWN_ROOT_MULTIPLIER + KNOWN_ROOT_SLACK_BYTES;
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

}
