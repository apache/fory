
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

package org.apache.fory.collection.selflist;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.fory.Fory;
import org.apache.fory.ThreadLocalFory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Int64Encoding;
import org.apache.fory.config.Language;
import org.apache.fory.io.ForyInputStream;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Test that demonstrates the CCE bug in self-referential collection serialization. This test is
 * expected to fail without the fix (SelfRefCollectionSerializerFactory).
 */
public class SelfSingletonListTest {
  /** The type parameter bound: a {@code Box} can only contain items of this type. */
  public static class Item {}

  /**
   * Single-element {@link List} with default method implementations. Like a box that can hold
   * exactly one thing.
   */
  public interface SingleItemList<E> extends List<E> {
    E getFirst();

    @Override
    default int size() {
      return 1;
    }

    @Override
    default boolean isEmpty() {
      return false;
    }

    @Override
    default boolean contains(Object o) {
      return Objects.equals(getFirst(), o);
    }

    @Override
    default Iterator<E> iterator() {
      return Collections.singletonList(getFirst()).iterator();
    }

    @Override
    default Object[] toArray() {
      return new Object[] {getFirst()};
    }

    @SuppressWarnings("unchecked")
    @Override
    default <T> T[] toArray(T[] a) {
      return (T[]) Collections.singletonList(getFirst()).toArray();
    }

    @Override
    default E get(int i) {
      if (i != 0) throw new IndexOutOfBoundsException("Index: " + i + ", Size: 1");
      return getFirst();
    }

    @Override
    default int indexOf(Object o) {
      return Objects.equals(getFirst(), o) ? 0 : -1;
    }

    @Override
    default int lastIndexOf(Object o) {
      return indexOf(o);
    }

    @Override
    default ListIterator<E> listIterator() {
      return Collections.singletonList(getFirst()).listIterator();
    }

    @Override
    default ListIterator<E> listIterator(int i) {
      return Collections.singletonList(getFirst()).listIterator(i);
    }

    @Override
    default List<E> subList(int f, int t) {
      return Collections.singletonList(getFirst()).subList(f, t);
    }

    @Override
    default boolean containsAll(Collection<?> c) {
      for (Object o : c) {
        if (!Objects.equals(getFirst(), o)) return false;
      }
      return true;
    }

    @Override
    default boolean add(E e) {
      throw new UnsupportedOperationException();
    }

    @Override
    default boolean remove(Object o) {
      throw new UnsupportedOperationException();
    }

    @Override
    default boolean addAll(Collection<? extends E> c) {
      throw new UnsupportedOperationException();
    }

    @Override
    default boolean addAll(int i, Collection<? extends E> c) {
      throw new UnsupportedOperationException();
    }

    @Override
    default boolean removeAll(Collection<?> c) {
      throw new UnsupportedOperationException();
    }

    @Override
    default boolean retainAll(Collection<?> c) {
      throw new UnsupportedOperationException();
    }

    @Override
    default void clear() {
      throw new UnsupportedOperationException();
    }

    @Override
    default E set(int i, E e) {
      throw new UnsupportedOperationException();
    }

    @Override
    default void add(int i, E e) {
      throw new UnsupportedOperationException();
    }

    @Override
    default E remove(int i) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A box that can contain another box — like Russian nesting dolls. {@code T extends Item} but
   * {@code SingleItemList<Box<?>>} uses an unbounded wildcard, which resolves to {@code Object} —
   * and {@code Object} does not extend {@code Item}. This triggers the {@code ClassCastException}
   * in Fory's JIT codegen.
   */
  public static class Box<T extends Item> implements SingleItemList<Box<? extends Item>> {
    private int id;

    @Override
    public Box<?> getFirst() {
      return this;
    }

    public int getId() {
      return id;
    }

    public void setId(int id) {
      this.id = id;
    }
  }

  /**
   * A shelf holding a list of boxes. When Fory JIT-compiles its serializer, resolving the field's
   * element type triggers the {@code ClassCastException}.
   */
  public static class Shelf {
    private String name;
    private List<Box<? extends Item>> boxes;

    public Shelf() {}

    public Shelf(String name, List<Box<? extends Item>> boxes) {
      this.name = name;
      this.boxes = boxes;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public List<Box<?>> getBoxes() {
      return boxes;
    }

    public void setBoxes(List<Box<?>> boxes) {
      this.boxes = boxes;
    }
  }

  // ==================== tests ====================

  @Test(
      priority = 2,
      expectedExceptions = {
        ClassCastException.class,
        org.apache.fory.exception.DeserializationException.class,
        org.apache.fory.exception.SerializationException.class
      })
  public void testShelfFailsWithoutFix() {
    Shelf shelf = new Shelf("my-shelf", new Box<>());
    byte[] bytes = serialize(createFory(), shelf);
    Shelf cloned = deserialize(createFory(), bytes);
    Assert.assertNotNull(cloned);
  }

  @Test(priority = 1)
  public void testShelfSuccessWithFix() {
    Box<?> box = new Box<>();
    box.setId(42);
    Shelf shelf = new Shelf("my-shelf", box);
    byte[] bytes = serialize(createForyWithFix(), shelf);
    Shelf cloned = deserialize(createForyWithFix(), bytes);
    Assert.assertNotNull(cloned);
    Assert.assertEquals(cloned.getName(), "my-shelf");
    Assert.assertNotNull(cloned.getBoxes());
    Assert.assertEquals(cloned.getBoxes().size(), 1);
  }

  private ThreadLocalFory createFory() {
    return new ThreadLocalFory(
        builder -> {
          ForyBuilder b =
              builder
                  .withLanguage(Language.JAVA)
                  .requireClassRegistration(false)
                  .withRefTracking(true)
                  .withCompatibleMode(CompatibleMode.COMPATIBLE)
                  .withAsyncCompilation(false)
                  .withIntCompressed(true)
                  .withCodegen(true)
                  .withLongCompressed(Int64Encoding.VARINT)
                  .withIntArrayCompressed(true)
                  .withLongArrayCompressed(true);

          Fory fory = b.build();
          return fory;
        });
  }

  private ThreadLocalFory createForyWithFix() {
    return new ThreadLocalFory(
        builder -> {
          ForyBuilder b =
              builder
                  .withLanguage(Language.JAVA)
                  .requireClassRegistration(false)
                  .withRefTracking(true)
                  .withCompatibleMode(CompatibleMode.COMPATIBLE)
                  .withAsyncCompilation(false)
                  .withIntCompressed(true)
                  .withCodegen(true)
                  .withLongCompressed(Int64Encoding.VARINT)
                  .withIntArrayCompressed(true)
                  .withLongArrayCompressed(true)
                  .withSerializerFactory(new SelfRefCollectionSerializerFactory());

          return b.build();
        });
  }

  private <T> byte[] serialize(ThreadLocalFory fory, T object) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    fory.serialize(out, object);
    return out.toByteArray();
  }

  @SuppressWarnings("unchecked")
  private <T> T deserialize(ThreadLocalFory fory, byte[] bytes) {
    return (T) fory.deserialize(new ForyInputStream(new ByteArrayInputStream(bytes)));
  }
}
