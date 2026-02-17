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

package org.apache.fory.serializer.collection;

import static org.apache.fory.collection.Collections.ofArrayList;
import static org.apache.fory.collection.Collections.ofHashMap;
import static org.apache.fory.collection.Collections.ofHashSet;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.collection.CollectionSerializers.JDKCompatibleCollectionSerializer;
import org.apache.fory.test.bean.Cyclic;
import org.apache.fory.type.GenericType;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.collections.Maps;

@SuppressWarnings("rawtypes")
public class CollectionSerializersTest extends ForyTestBase {

  @Test(dataProvider = "referenceTrackingConfig")
  public void testBasicList(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();
    List<String> data = new ArrayList<>(ImmutableList.of("a", "b", "c"));
    serDeCheckSerializer(fory, data, "ArrayList");
    serDeCheckSerializer(fory, Arrays.asList("a", "b", "c"), "ArraysAsList");
    serDeCheckSerializer(fory, new HashSet<>(data), "HashSet");
    serDeCheckSerializer(fory, new LinkedHashSet<>(data), "LinkedHashSet");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testBasicList(Fory fory) {
    List<Object> data = Arrays.asList(1, true, "test", Cyclic.create(true));
    copyCheck(fory, new ArrayList<>(data));
    copyCheck(fory, data);
    copyCheck(fory, new HashSet<>(data));
    copyCheck(fory, new LinkedHashSet<>(data));
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testBasicListNested(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();
    List<String> data0 = new ArrayList<>(ImmutableList.of("a", "b", "c"));
    List<List<String>> data = new ArrayList<>(ImmutableList.of(data0, data0));
    serDeCheckSerializer(fory, data, "ArrayList");
    serDeCheckSerializer(fory, Arrays.asList("a", "b", "c"), "ArraysAsList");
    serDeCheckSerializer(fory, new HashSet<>(data), "HashSet");
    serDeCheckSerializer(fory, new LinkedHashSet<>(data), "LinkedHashSet");
  }

  @Data
  public static class BasicListNestedJIT {
    public Set<List<List<String>>> data;
    public Set<Set<List<String>>> data1;
    public Collection<ArrayList<Object>> data2;
    public Collection<Set<Collection<String>>> data3;
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testBasicListNestedJIT(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withRefTracking(referenceTracking)
            .withCodegen(true)
            .requireClassRegistration(false)
            .build();
    List<List<List<String>>> list = new ArrayList<>();
    list.add(ofArrayList(ofArrayList("a", "b")));
    list.add(ofArrayList(ofArrayList("a", "b")));
    BasicListNestedJIT o = new BasicListNestedJIT();
    o.data = new HashSet<>(list);
    o.data1 = ofHashSet(ofHashSet(ofArrayList("a", "b")), ofHashSet(ofArrayList("a", "b")));
    o.data2 = ofHashSet(ofArrayList("a", "b"));
    o.data3 = ofHashSet(ofHashSet(ofArrayList("a", "b")), ofHashSet(ofArrayList("a", "b")));
    serDeCheckSerializer(fory, o, "Codec");
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testCollectionGenerics(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();
    List<String> data = new ArrayList<>(ImmutableList.of("a", "b", "c"));
    byte[] bytes1 = fory.serialize(data);
    fory.getGenerics().pushGenericType(GenericType.build(new TypeRef<List<String>>() {}));
    byte[] bytes2 = fory.serialize(data);
    Assert.assertTrue(bytes1.length > bytes2.length);
    assertEquals(fory.deserialize(bytes2), data);
    fory.getGenerics().popGenericType();
    assertThrowsCause(RuntimeException.class, () -> fory.deserialize(bytes2));
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testSortedSet(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();
    // Test serialize Comparator
    TreeSet<String> set =
        new TreeSet<>(
            (Comparator<? super String> & Serializable)
                (s1, s2) -> {
                  int delta = s1.length() - s2.length();
                  if (delta == 0) {
                    return s1.compareTo(s2);
                  } else {
                    return delta;
                  }
                });
    set.add("str11");
    set.add("str2");
    assertEquals(set, serDe(fory, set));
    TreeSet<String> data = new TreeSet<>(ImmutableSet.of("a", "b", "c"));
    serDeCheckSerializer(fory, data, "SortedSet");
    byte[] bytes1 = fory.serialize(data);
    fory.getGenerics().pushGenericType(GenericType.build(new TypeRef<List<String>>() {}));
    byte[] bytes2 = fory.serialize(data);
    Assert.assertTrue(bytes1.length > bytes2.length);
    fory.getGenerics().popGenericType();
    assertThrowsCause(RuntimeException.class, () -> fory.deserialize(bytes2));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testSortedSet(Fory fory) {
    AtomicInteger i = new AtomicInteger(1);
    TreeSet<String> set = new TreeSet<>(new TestComparator(i));

    TreeSet<String> copy = fory.copy(set);

    Comparator<? super String> comparator1 = set.comparator();
    Comparator<? super String> comparator2 = copy.comparator();
    Assert.assertEquals(comparator1, comparator2);
    set.add("str11");
    copy.add("str11");
    Assert.assertEquals(copy, set);
    Assert.assertNotSame(copy, set);
    i.set(-1);
    Assert.assertNotEquals(comparator1, comparator2);
    set.add("str2");
    copy.add("str2");
    Assert.assertNotEquals(Arrays.toString(copy.toArray()), Arrays.toString(set.toArray()));
    copy.add("str");
    Assert.assertEquals(Arrays.toString(copy.toArray()), "[str, str2, str11]");
  }

  private class TestComparator implements Comparator<String> {
    AtomicInteger i;

    public TestComparator(AtomicInteger i) {
      this.i = i;
    }

    @Override
    public int compare(String s1, String s2) {
      int delta = s1.length() - s2.length();
      if (delta == i.get()) {
        return s1.compareTo(s2);
      } else {
        return delta;
      }
    }

    @Override
    public boolean equals(Object obj) {
      return ((TestComparator) obj).i.get() == this.i.get();
    }
  }

  // TreeSet subclass without a Comparator constructor (natural ordering only)
  public static class ChildTreeSet extends TreeSet<String> {
    public ChildTreeSet() {
      super();
    }
  }

  // TreeSet subclass with a Comparator constructor
  public static class ChildTreeSetWithComparator extends TreeSet<String> {
    public ChildTreeSetWithComparator() {
      super();
    }

    public ChildTreeSetWithComparator(Comparator<? super String> comparator) {
      super(comparator);
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testSortedSetSubclassWithoutComparatorCtor(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();
    ChildTreeSet set = new ChildTreeSet();
    set.add("b");
    set.add("a");
    set.add("c");
    ChildTreeSet deserialized = serDe(fory, set);
    assertEquals(deserialized, set);
    assertEquals(deserialized.getClass(), ChildTreeSet.class);
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testSortedSetSubclassWithComparatorCtor(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();
    ChildTreeSetWithComparator set = new ChildTreeSetWithComparator();
    set.add("b");
    set.add("a");
    set.add("c");
    ChildTreeSetWithComparator deserialized = serDe(fory, set);
    assertEquals(deserialized, set);
    assertEquals(deserialized.getClass(), ChildTreeSetWithComparator.class);
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testSortedSetSubclassRegisteredWithSortedSetSerializer(
      boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();
    // Subclass without Comparator constructor should not throw when registered with
    // SortedSetSerializer
    fory.registerSerializer(
        ChildTreeSet.class,
        new CollectionSerializers.SortedSetSerializer<>(fory, ChildTreeSet.class));
    ChildTreeSet set = new ChildTreeSet();
    set.add("b");
    set.add("a");
    set.add("c");
    ChildTreeSet deserialized = serDe(fory, set);
    assertEquals(deserialized, set);
    assertEquals(deserialized.getClass(), ChildTreeSet.class);
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testSortedSetSubclassWithComparatorRegisteredWithSortedSetSerializer(
      boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();
    fory.registerSerializer(
        ChildTreeSetWithComparator.class,
        new CollectionSerializers.SortedSetSerializer<>(fory, ChildTreeSetWithComparator.class));
    ChildTreeSetWithComparator set = new ChildTreeSetWithComparator();
    set.add("b");
    set.add("a");
    set.add("c");
    ChildTreeSetWithComparator deserialized = serDe(fory, set);
    assertEquals(deserialized, set);
    assertEquals(deserialized.getClass(), ChildTreeSetWithComparator.class);
  }

  @Test
  public void testEmptyCollection() {
    serDeCheckSerializer(getJavaFory(), Collections.EMPTY_LIST, "EmptyListSerializer");
    serDeCheckSerializer(getJavaFory(), Collections.emptySortedSet(), "EmptySortedSetSerializer");
    serDeCheckSerializer(getJavaFory(), Collections.EMPTY_SET, "EmptySetSerializer");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testEmptyCollection(Fory fory) {
    copyCheckWithoutSame(fory, Collections.EMPTY_LIST);
    copyCheckWithoutSame(fory, Collections.emptySortedSet());
    copyCheckWithoutSame(fory, Collections.EMPTY_SET);
  }

  @Test
  public void testSingleCollection() {
    serDeCheckSerializer(getJavaFory(), Collections.singletonList(1), "SingletonList");
    serDeCheckSerializer(getJavaFory(), Collections.singleton(1), "SingletonSet");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testSingleCollection(Fory fory) {
    copyCheck(fory, Collections.singletonList(Cyclic.create(true)));
    copyCheck(fory, Collections.singleton(Cyclic.create(true)));
  }

  @Test
  public void tesSkipList() {
    serDeCheckSerializer(
        getJavaFory(),
        new ConcurrentSkipListSet<>(Arrays.asList("a", "b", "c")),
        "ConcurrentSkipListSet");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void tesSkipList(Fory fory) {
    copyCheck(fory, new ConcurrentSkipListSet<>(Arrays.asList("a", "b", "c")));
  }

  @Test
  public void tesVectorSerializer() {
    serDeCheckSerializer(
        getJavaFory(), new Vector<>(Arrays.asList("a", "b", "c")), "VectorSerializer");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void tesVectorSerializer(Fory fory) {
    copyCheck(fory, new Vector<>(Arrays.asList("a", 1, Cyclic.create(true))));
  }

  @Test
  public void tesArrayDequeSerializer() {
    serDeCheckSerializer(
        getJavaFory(), new ArrayDeque<>(Arrays.asList("a", "b", "c")), "ArrayDeque");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void tesArrayDequeSerializer(Fory fory) {
    ImmutableList<Object> list = ImmutableList.of("a", 1, Cyclic.create(true));
    ArrayDeque<Object> deque = new ArrayDeque<>(list);
    ArrayDeque<Object> copy = fory.copy(deque);
    Assert.assertEquals(ImmutableList.copyOf(copy), list);
    Assert.assertNotSame(deque, copy);
  }

  public enum TestEnum {
    A,
    B,
    C,
    D
  }

  @Test
  public void tesEnumSetSerializer() {
    serDe(getJavaFory(), EnumSet.allOf(TestEnum.class));
    Assert.assertEquals(
        getJavaFory()
            .getClassResolver()
            .getSerializerClass(EnumSet.allOf(TestEnum.class).getClass()),
        CollectionSerializers.EnumSetSerializer.class);
    serDe(getJavaFory(), EnumSet.of(TestEnum.A));
    Assert.assertEquals(
        getJavaFory().getClassResolver().getSerializerClass(EnumSet.of(TestEnum.A).getClass()),
        CollectionSerializers.EnumSetSerializer.class);
    serDe(getJavaFory(), EnumSet.of(TestEnum.A, TestEnum.B));
    Assert.assertEquals(
        getJavaFory()
            .getClassResolver()
            .getSerializerClass(EnumSet.of(TestEnum.A, TestEnum.B).getClass()),
        CollectionSerializers.EnumSetSerializer.class);
    // TODO test enum which has enums exceed 128.
  }

  @Test(dataProvider = "foryCopyConfig")
  public void tesEnumSetSerializer(Fory fory) {
    copyCheck(fory, EnumSet.allOf(TestEnum.class));
    copyCheck(fory, EnumSet.of(TestEnum.A));
    copyCheck(fory, EnumSet.of(TestEnum.A, TestEnum.B));
  }

  enum TestEnumWithMethods {
    A {
      @Override
      public void foo() {
        System.out.println("A");
      }
    },
    B,
    C {
      @Override
      public void foo() {}
    },
    D;

    public void foo() {
      System.out.println("default");
    }
  }

  @Test
  public void tesEnumSetSerializerWithMethods() {
    serDe(getJavaFory(), EnumSet.noneOf(TestEnumWithMethods.class));
    serDe(getJavaFory(), EnumSet.of(TestEnumWithMethods.A));
    serDe(getJavaFory(), EnumSet.of(TestEnumWithMethods.B));
    serDe(getJavaFory(), EnumSet.of(TestEnumWithMethods.A, TestEnumWithMethods.B));
    serDe(getJavaFory(), EnumSet.of(TestEnumWithMethods.A, TestEnumWithMethods.C));
    serDe(getJavaFory(), EnumSet.allOf(TestEnumWithMethods.class));
  }

  @Test
  public void tesBitSetSerializer() {
    serDe(getJavaFory(), BitSet.valueOf(LongStream.range(0, 2).toArray()));
    Assert.assertEquals(
        getJavaFory()
            .getClassResolver()
            .getSerializerClass(BitSet.valueOf(LongStream.range(0, 2).toArray()).getClass()),
        CollectionSerializers.BitSetSerializer.class);
    serDe(getJavaFory(), BitSet.valueOf(LongStream.range(0, 128).toArray()));
    Assert.assertEquals(
        getJavaFory()
            .getClassResolver()
            .getSerializerClass(BitSet.valueOf(LongStream.range(0, 128).toArray()).getClass()),
        CollectionSerializers.BitSetSerializer.class);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void tesBitSetSerializer(Fory fory) {
    copyCheck(fory, BitSet.valueOf(LongStream.range(0, 2).toArray()));
    copyCheck(fory, BitSet.valueOf(LongStream.range(0, 128).toArray()));
  }

  @Test
  public void tesPriorityQueueSerializer() {
    serDe(getJavaFory(), new PriorityQueue<>(Arrays.asList("a", "b", "c")));
    Assert.assertEquals(
        getJavaFory().getClassResolver().getSerializerClass(PriorityQueue.class),
        CollectionSerializers.PriorityQueueSerializer.class);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void tesPriorityQueueSerializer(Fory fory) {
    ImmutableList<String> list = ImmutableList.of("a", "b", "c");
    PriorityQueue<String> copy = fory.copy(new PriorityQueue<>(list));
    Assert.assertEquals(ImmutableList.sortedCopyOf(copy), list);
  }

  @Test
  public void testCopyOnWriteArrayList() {
    final CopyOnWriteArrayList<String> list =
        new CopyOnWriteArrayList<>(new String[] {"a", "b", "c"});
    Assert.assertEquals(list, serDe(getJavaFory(), list));
    Assert.assertEquals(
        getJavaFory().getClassResolver().getSerializerClass(CopyOnWriteArrayList.class),
        CollectionSerializers.CopyOnWriteArrayListSerializer.class);
  }

  @EqualsAndHashCode
  public static class NestedCopyOnWriteArrayList {
    private final CopyOnWriteArrayList<String> list;

    public NestedCopyOnWriteArrayList(CopyOnWriteArrayList<String> list) {
      this.list = list;
    }
  }

  @Test
  public void testCopyOnWriteArrayListNested() {
    final CopyOnWriteArrayList<String> list =
        new CopyOnWriteArrayList<>(Arrays.asList("a", "b", "c"));
    NestedCopyOnWriteArrayList nestedObject = new NestedCopyOnWriteArrayList(list);
    Assert.assertEquals(nestedObject, serDe(getJavaFory(), nestedObject));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testCopyOnWriteArrayList(Fory fory) {
    final CopyOnWriteArrayList<Object> list =
        new CopyOnWriteArrayList<>(new Object[] {"a", "b", Cyclic.create(true)});
    copyCheck(fory, list);
  }

  @Data
  @AllArgsConstructor
  public static class CollectionViewTestStruct {
    Collection<String> collection;
    Set<String> set;
  }

  @Test(dataProvider = "javaFory")
  public void testSetFromMap(Fory fory) {
    Set<String> set = Collections.newSetFromMap(Maps.newConcurrentMap());
    set.add("a");
    set.add("b");
    set.add("c");
    serDeCheck(fory, set);
    Assert.assertEquals(
        fory.getClassResolver().getSerializerClass(set.getClass()),
        CollectionSerializers.SetFromMapSerializer.class);
    CollectionViewTestStruct struct1 = serDeCheck(fory, new CollectionViewTestStruct(set, set));
    if (fory.trackingRef()) {
      assertSame(struct1.collection, struct1.set);
    }
    set = Collections.newSetFromMap(new HashMap<String, Boolean>() {});
    set.add("a");
    set.add("b");
    serDeCheck(fory, set);
    CollectionViewTestStruct struct2 = serDeCheck(fory, new CollectionViewTestStruct(set, set));
    if (fory.trackingRef()) {
      assertSame(struct2.collection, struct2.set);
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testSetFromMapCopy(Fory fory) {
    final Set<Object> set = Collections.newSetFromMap(Maps.newConcurrentMap());
    set.add("a");
    set.add("b");
    set.add(Cyclic.create(true));
    copyCheck(fory, set);
  }

  @Test(dataProvider = "javaFory")
  public void testConcurrentMapKeySetViewMap(Fory fory) {
    final ConcurrentHashMap.KeySetView<String, Boolean> set = ConcurrentHashMap.newKeySet();
    set.add("a");
    set.add("b");
    set.add("c");
    Assert.assertEquals(set, serDe(fory, set));
    Assert.assertEquals(
        fory.getClassResolver().getSerializerClass(set.getClass()),
        CollectionSerializers.ConcurrentHashMapKeySetViewSerializer.class);
    CollectionViewTestStruct o = serDeCheck(fory, new CollectionViewTestStruct(set, set));
    if (fory.trackingRef()) {
      assertSame(o.collection, o.set);
    }
    ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
    map.put("k1", "v1");
    if (fory.trackingRef()) {
      ArrayList<Serializable> list = serDeCheck(fory, ofArrayList(map.keySet("v0"), map));
      assertSame(((ConcurrentHashMap.KeySetView) (list.get(0))).getMap(), list.get(1));
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testConcurrentMapKeySetViewMapCopy(Fory fory) {
    final ConcurrentHashMap.KeySetView<Object, Boolean> set = ConcurrentHashMap.newKeySet();
    set.add("a");
    set.add("b");
    set.add(Cyclic.create(true));
    copyCheck(fory, set);
  }

  @Test
  public void testSerializeJavaBlockingQueue() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    {
      ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<>(10);
      queue.add(1);
      queue.add(2);
      queue.add(3);
      ArrayBlockingQueue<Integer> deserialized = serDe(fory, queue);
      assertEquals(new ArrayList<>(deserialized), new ArrayList<>(queue));
      // Verify capacity is preserved
      assertEquals(deserialized.remainingCapacity() + deserialized.size(), 10);
    }
    {
      LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<>(10);
      queue.add(1);
      queue.add(2);
      queue.add(3);
      LinkedBlockingQueue<Integer> deserialized = serDe(fory, queue);
      assertEquals(new ArrayList<>(deserialized), new ArrayList<>(queue));
      // Verify capacity is preserved
      assertEquals(deserialized.remainingCapacity() + deserialized.size(), 10);
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testSerializeJavaBlockingQueue(Fory fory) {
    {
      ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<>(10);
      queue.add(1);
      queue.add(2);
      queue.add(3);
      ArrayBlockingQueue<Integer> copy = fory.copy(queue);
      Assert.assertEquals(Arrays.toString(copy.toArray()), "[1, 2, 3]");
      // Verify capacity is preserved
      Assert.assertEquals(copy.remainingCapacity() + copy.size(), 10);
    }
    {
      LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<>(10);
      queue.add(1);
      queue.add(2);
      queue.add(3);
      LinkedBlockingQueue<Integer> copy = fory.copy(queue);
      Assert.assertEquals(Arrays.toString(copy.toArray()), "[1, 2, 3]");
      // Verify capacity is preserved
      Assert.assertEquals(copy.remainingCapacity() + copy.size(), 10);
    }
  }

  @Test
  public void testCollectionNoJIT() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).withCodegen(false).build();
    serDeCheck(fory, new ArrayList<>(ImmutableList.of("a", "b", "c")));
    serDeCheck(fory, new ArrayList<>(ImmutableList.of(1, 2, 3)));
    serDeCheck(fory, new ArrayList<>(ImmutableList.of("a", 1, "b", 2)));
  }

  @Data
  public static class SimpleBeanCollectionFields {
    public List<String> list;
  }

  @Test(dataProvider = "javaFory")
  public void testSimpleBeanCollectionFields(Fory fory) {
    SimpleBeanCollectionFields obj = new SimpleBeanCollectionFields();
    obj.list = new ArrayList<>();
    obj.list.add("a");
    obj.list.add("b");
    Assert.assertEquals(serDe(fory, obj).toString(), obj.toString());
    if (fory.getConfig().isCodeGenEnabled()) {
      Assert.assertTrue(
          fory.getClassResolver()
              .getSerializerClass(SimpleBeanCollectionFields.class)
              .getName()
              .contains("Codec"));
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testSimpleBeanCollectionFieldsCopy(Fory fory) {
    SimpleBeanCollectionFields obj = new SimpleBeanCollectionFields();
    obj.list = new ArrayList<>();
    obj.list.add("a");
    obj.list.add("b");
    Assert.assertEquals(fory.copy(obj).toString(), obj.toString());
  }

  @Data
  @AllArgsConstructor
  public static class NotFinal {
    int f1;
  }

  @Data
  public static class Container {
    public List<NotFinal> list1;
    public Map<String, NotFinal> map1;
  }

  @Test(dataProvider = "javaFory")
  public void testContainer(Fory fory) {
    Container container = new Container();
    container.list1 = ofArrayList(new NotFinal(1));
    container.map1 = ofHashMap("k", new NotFinal(2));
    serDeCheck(fory, container);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testContainerCopy(Fory fory) {
    Container container = new Container();
    container.list1 = ofArrayList(new NotFinal(1));
    container.map1 = ofHashMap("k", new NotFinal(2));
    copyCheck(fory, container);
  }

  @Data
  public static class CollectionFieldsClass {
    public ArrayList<String> arrayList;
    public List<String> arrayList2;
    public Collection<String> arrayList3;
    public List<String> arrayAsList;
    public Collection<String> arrayAsList2;
    public LinkedList<String> linkedList;
    public List<String> linkedList2;
    public Collection<String> linkedList3;
    public HashSet<String> hashSet;
    public Set<String> hashSet2;
    public LinkedHashSet<String> linkedHashSet;
    public Set<String> linkedHashSet2;
    public TreeSet<String> treeSet;
    public SortedSet<String> treeSet2;
    public ConcurrentSkipListSet<String> skipListSet;
    public Set<String> skipListSet2;
    public Vector<String> vector;
    public List<String> vector2;
    public ArrayDeque<Integer> arrayDeque;
    public Collection<Integer> arrayDeque2;
    public BitSet bitSet1;
    public BitSet bitSet2;
    public PriorityQueue<String> priorityQueue;
    public Collection<String> priorityQueue2;
    public EnumSet<TestEnum> enumSet1;
    public EnumSet<TestEnum> enumSet2;
    public List<String> emptyList1;
    public Set<String> emptySet1;
    // TODO add support for common empty
    public Set<String> emptySortedSet;
    public List<String> singleList1;
    public Set<String> singleSet1;
  }

  public static CollectionFieldsClass createCollectionFieldsObject() {
    CollectionFieldsClass obj = new CollectionFieldsClass();
    ArrayList<String> arrayList = new ArrayList<>(ImmutableList.of("a", "b"));
    obj.arrayList = arrayList;
    obj.arrayList2 = arrayList;
    obj.arrayList3 = arrayList;
    obj.arrayAsList = Arrays.asList("a", "b");
    obj.arrayAsList2 = Arrays.asList("a", "b");
    LinkedList<String> linkedList = new LinkedList<>(Arrays.asList("a", "b"));
    obj.linkedList = linkedList;
    obj.linkedList2 = linkedList;
    obj.linkedList3 = linkedList;
    HashSet<String> hashSet = new HashSet<>(ImmutableSet.of("a", "b"));
    obj.hashSet = hashSet;
    obj.hashSet2 = hashSet;
    obj.linkedHashSet = new LinkedHashSet<>(hashSet);
    obj.linkedHashSet2 = new LinkedHashSet<>(hashSet);
    obj.treeSet = new TreeSet<>(hashSet);
    obj.treeSet2 = new TreeSet<>(hashSet);
    ConcurrentSkipListSet<String> skipListSet =
        new ConcurrentSkipListSet<>(Arrays.asList("a", "b", "c"));
    obj.skipListSet = skipListSet;
    obj.skipListSet2 = skipListSet;
    Vector<String> vector = new Vector<>(Arrays.asList("a", "b", "c"));
    obj.vector = vector;
    obj.vector2 = vector;
    ArrayDeque<Integer> arrayDeque = new ArrayDeque<>(Arrays.asList(1, 2));
    obj.arrayDeque = arrayDeque;
    obj.arrayDeque2 = arrayDeque;
    obj.bitSet1 = BitSet.valueOf(LongStream.range(0, 2).toArray());
    obj.bitSet2 = BitSet.valueOf(LongStream.range(0, 128).toArray());
    PriorityQueue<String> priorityQueue = new PriorityQueue<>(Arrays.asList("a", "b", "c"));
    obj.priorityQueue = priorityQueue;
    obj.priorityQueue2 = priorityQueue;
    obj.enumSet1 = EnumSet.allOf(TestEnum.class);
    obj.enumSet2 = EnumSet.of(TestEnum.A);
    obj.emptyList1 = Collections.emptyList();
    obj.emptySet1 = Collections.emptySet();
    obj.emptySortedSet = Collections.emptySortedSet();
    obj.singleList1 = Collections.singletonList("");
    obj.singleSet1 = Collections.singleton("");
    return obj;
  }

  public static void testCollectionFieldsObjectEqual(
      CollectionFieldsClass got, CollectionFieldsClass obj) throws IllegalAccessException {
    // Fields that can cause flakiness need to be compared separately
    Set<String> special = ImmutableSet.of("hashSet", "hashSet2", "priorityQueue", "priorityQueue2");
    // Compare fields with deterministic ordering
    for (Field f : CollectionSerializersTest.CollectionFieldsClass.class.getDeclaredFields()) {
      if (special.contains(f.getName())) continue;
      f.setAccessible(true);
      Object gv = f.get(got);
      Object ov = f.get(obj);
      Assert.assertEquals(String.valueOf(gv), String.valueOf(ov));
    }
    // Compare everything else
    String hsGot = new TreeSet<>(got.hashSet).toString();
    String hsObj = new TreeSet<>(obj.hashSet).toString();
    Assert.assertEquals(hsGot, hsObj);
    hsGot = new TreeSet<>(got.hashSet2).toString();
    hsObj = new TreeSet<>(obj.hashSet2).toString();
    Assert.assertEquals(hsGot, hsObj);
    List<String> pqGot = new ArrayList<>(got.priorityQueue);
    List<String> pqExp = new ArrayList<>(obj.priorityQueue);
    Collections.sort(pqGot);
    Collections.sort(pqExp);
    Assert.assertEquals(pqGot.toString(), pqExp.toString());
    pqGot = new ArrayList<>(got.priorityQueue2);
    pqExp = new ArrayList<>(obj.priorityQueue2);
    Collections.sort(pqGot);
    Collections.sort(pqExp);
    Assert.assertEquals(pqGot.toString(), pqExp.toString());
  }

  @Test(dataProvider = "javaFory")
  public void testCollectionFieldSerializers(Fory fory) throws Exception {
    CollectionFieldsClass obj = createCollectionFieldsObject();
    testCollectionFieldsObjectEqual(serDe(fory, obj), obj);
    if (fory.getConfig().isCodeGenEnabled()) {
      Assert.assertTrue(
          fory.getClassResolver()
              .getSerializerClass(CollectionFieldsClass.class)
              .getName()
              .contains("Codec"));
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testCollectionFieldSerializersCopy(Fory fory) throws Exception {
    CollectionFieldsClass obj = createCollectionFieldsObject();
    testCollectionFieldsObjectEqual(fory.copy(obj), obj);
  }

  @Data
  @AllArgsConstructor
  public static class NestedCollection1 {
    public List<Collection<Integer>> list1;
  }

  @Test(dataProvider = "javaFory")
  public void testNestedCollection1(Fory fory) {
    ArrayList<Integer> list = ofArrayList(1, 2);
    NestedCollection1 o = new NestedCollection1(ofArrayList(list));
    Assert.assertEquals(serDe(fory, o), o);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testNestedCollection1Copy(Fory fory) {
    ArrayList<Integer> list = ofArrayList(1, 2);
    NestedCollection1 o = new NestedCollection1(ofArrayList(list));
    copyCheck(fory, o);
  }

  @Data
  @AllArgsConstructor
  public static class NestedCollection2 {
    public List<Collection<Collection<Integer>>> list1;
  }

  @Test(dataProvider = "javaFory")
  public void testNestedCollection2(Fory fory) {
    ArrayList<Integer> list = ofArrayList(1, 2);
    NestedCollection2 o = new NestedCollection2(ofArrayList(ofArrayList(list)));
    Assert.assertEquals(serDe(fory, o), o);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testNestedCollection2Copy(Fory fory) {
    ArrayList<Integer> list = ofArrayList(1, 2);
    NestedCollection2 o = new NestedCollection2(ofArrayList(ofArrayList(list)));
    copyCheck(fory, o);
  }

  public static class TestClassForDefaultCollectionSerializer extends AbstractCollection<String> {
    private final List<String> data = new ArrayList<>();

    @Override
    public Iterator<String> iterator() {
      return data.iterator();
    }

    @Override
    public int size() {
      return data.size();
    }

    @Override
    public boolean add(String s) {
      return data.add(s);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      TestClassForDefaultCollectionSerializer strings = (TestClassForDefaultCollectionSerializer) o;
      return Objects.equals(data, strings.data);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(data);
    }
  }

  @Test
  public void testDefaultCollectionSerializer() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    TestClassForDefaultCollectionSerializer collection =
        new TestClassForDefaultCollectionSerializer();
    collection.add("a");
    collection.add("b");
    serDeCheck(fory, collection);
    Assert.assertSame(
        fory.getClassResolver().getSerializerClass(TestClassForDefaultCollectionSerializer.class),
        CollectionSerializers.DefaultJavaCollectionSerializer.class);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testDefaultCollectionSerializer(Fory fory) {
    TestClassForDefaultCollectionSerializer collection =
        new TestClassForDefaultCollectionSerializer();
    collection.add("a");
    collection.add("b");
    copyCheck(fory, collection);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testJavaSerialization() {
    ImmutableSortedSet<Integer> set = ImmutableSortedSet.of(1, 2, 3);
    Class<? extends ImmutableSortedSet> setClass = set.getClass();
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    JDKCompatibleCollectionSerializer javaSerializer =
        new JDKCompatibleCollectionSerializer(fory, setClass);
    javaSerializer.write(buffer, set);
    Object read = javaSerializer.read(buffer);
    assertEquals(set, read);

    assertSame(
        fory.getClassResolver().getSerializer(setClass).getClass(),
        GuavaCollectionSerializers.ImmutableSortedSetSerializer.class);
    buffer.writerIndex(0);
    buffer.readerIndex(0);
    assertEquals(set, fory.deserialize(fory.serialize(buffer, set)));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testJavaSerialization(Fory fory) {
    ImmutableSortedSet<Integer> set = ImmutableSortedSet.of(1, 2, 3);
    Class<? extends ImmutableSortedSet> setClass = set.getClass();
    JDKCompatibleCollectionSerializer javaSerializer =
        new JDKCompatibleCollectionSerializer(fory, setClass);
    Object copy = javaSerializer.copy(set);
    assertEquals(set, copy);
    Assert.assertNotSame(set, copy);
  }

  public static class SubListSerializer extends CollectionSerializer {

    public SubListSerializer(Fory fory, Class cls) {
      super(fory, cls, true);
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32();
      setNumElements(numElements);
      return new ArrayList<>(numElements);
    }
  }

  @Test
  public void testSubList() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withJdkClassSerializableCheck(false)
            .build();
    ArrayList<Integer> list = new ArrayList<>(ImmutableList.of(1, 2, 3, 4));
    fory.registerSerializer(
        list.subList(0, 2).getClass(), new SubListSerializer(fory, list.subList(0, 2).getClass()));
    serDeCheck(fory, list.subList(0, 2));

    //     ArrayList<Integer> list = new ArrayList<>(ImmutableList.of(1, 2, 3, 4));
    //     ByteArrayOutputStream bas = new ByteArrayOutputStream();
    //     Hessian2Output hessian2Output = new Hessian2Output(bas);
    //     serialize(hessian2Output, list.subList(0 ,2));
    //     Object o = deserialize(new Hessian2Input(new ByteArrayInputStream(bas.toByteArray())));
    //     System.out.println(o.getClass());
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testCollectionNullElements(boolean refTracking) {
    // When serialize a collection with all elements null directly, the declare type
    // will be equal to element type: null
    List data = new ArrayList<>();
    data.add(null);
    Fory f = Fory.builder().withLanguage(Language.JAVA).withRefTracking(refTracking).build();
    serDeCheck(f, data);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testCollectionNullElements(Fory fory) {
    List data = new ArrayList<>();
    data.add(null);
    copyCheck(fory, data);
  }

  @Data
  abstract static class Foo {
    private int f1;
  }

  static class Foo1 extends Foo {}

  @Data
  static class CollectionAbstractTest {
    private List<Foo> fooList;
  }

  @Test(dataProvider = "enableCodegen")
  public void testAbstractCollectionElementsSerialization(boolean enableCodegen) {
    Fory fory = Fory.builder().withCodegen(enableCodegen).requireClassRegistration(false).build();
    {
      CollectionAbstractTest test = new CollectionAbstractTest();
      test.fooList = new ArrayList<>(ImmutableList.of(new Foo1(), new Foo1()));
      serDeCheck(fory, test);
    }
    {
      CollectionAbstractTest test = new CollectionAbstractTest();
      test.fooList = new ArrayList<>(ofArrayList(new Foo1(), new Foo1()));
      serDeCheck(fory, test);
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testAbstractCollectionElementsSerialization(Fory fory) {
    {
      CollectionAbstractTest test = new CollectionAbstractTest();
      test.fooList = new ArrayList<>(ImmutableList.of(new Foo1(), new Foo1()));
      copyCheck(fory, test);
    }
    {
      CollectionAbstractTest test = new CollectionAbstractTest();
      test.fooList = new ArrayList<>(ofArrayList(new Foo1(), new Foo1()));
      copyCheck(fory, test);
    }
  }

  @Test(dataProvider = "enableCodegen")
  public void testCollectionAllNullElements(boolean enableCodegen) {
    Fory fory =
        Fory.builder()
            .withCodegen(true)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    List<Foo> fooList = new ArrayList<>();
    fooList.add(null);
    // serDeCheck(fory, fooList);

    CollectionAbstractTest obj = new CollectionAbstractTest();
    // fill elemTypeCache
    obj.fooList = ofArrayList(new Foo1());
    serDeCheck(fory, obj);

    obj.fooList = fooList;
    serDeCheck(fory, obj);

    fory =
        Fory.builder()
            .withCodegen(enableCodegen)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    serDeCheck(fory, obj);
  }

  @Test
  public void testCopyOnWriteArraySet() {
    final CopyOnWriteArraySet<String> set =
        new CopyOnWriteArraySet<>(Arrays.asList("Value1", "Value2"));
    Assert.assertEquals(set, serDe(getJavaFory(), set));
    Assert.assertEquals(
        getJavaFory().getClassResolver().getSerializerClass(CopyOnWriteArraySet.class),
        CollectionSerializers.CopyOnWriteArraySetSerializer.class);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testCopyOnWriteArraySet(Fory fory) {
    final CopyOnWriteArraySet<Object> set =
        new CopyOnWriteArraySet<>(Arrays.asList("a", "b", Cyclic.create(true)));
    copyCheck(fory, set);
  }
}
