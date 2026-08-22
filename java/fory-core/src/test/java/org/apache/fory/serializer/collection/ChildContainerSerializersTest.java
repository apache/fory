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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.builder.LayerMarkerClassGenerator;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.resolver.SharedRegistry;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.CompatibleLayerSerializer;
import org.apache.fory.serializer.CompatibleLayerSerializerBase;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.test.bean.Cyclic;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@SuppressWarnings({"unchecked", "rawtypes"})
public class ChildContainerSerializersTest extends ForyTestBase {
  public static class ChildArrayList<E> extends ArrayList<E> {
    private int state;

    @Override
    public String toString() {
      return "ChildArrayList{" + "state=" + state + ",data=" + super.toString() + '}';
    }
  }

  public static class ChildLinkedList<E> extends LinkedList<E> {}

  public static class ChildArrayDeque<E> extends ArrayDeque<E> {}

  public static class ChildVector<E> extends Vector<E> {}

  public static class ChildHashSet<E> extends HashSet<E> {}

  @DataProvider(name = "foryConfig")
  public static Object[][] foryConfig() {
    return new Object[][] {
      {builder().withRefTracking(false).withScopedMetaShare(false).withCompatible(true).build()},
      {builder().withRefTracking(false).withScopedMetaShare(true).withCompatible(true).build()},
      {builder().withRefTracking(false).withCompatible(false).build()},
    };
  }

  @Test(dataProvider = "foryConfig")
  public void testChildCollection(Fory fory) {
    List<Integer> data = ImmutableList.of(1, 2);
    {
      ChildArrayList<Integer> list = new ChildArrayList<>();
      list.addAll(data);
      list.state = 3;
      ChildArrayList<Integer> newList = serDe(fory, list);
      Assert.assertEquals(newList, list);
      Assert.assertEquals(newList.state, 3);
      Assert.assertEquals(
          fory.getTypeResolver().getSerializer(newList.getClass()).getClass(),
          ChildContainerSerializers.ChildArrayListSerializer.class);
      ArrayList<Integer> innerList =
          new ArrayList<Integer>() {
            {
              add(1);
            }
          };
      // innerList captures outer this.
      serDeCheck(fory, innerList);
      Assert.assertEquals(
          fory.getTypeResolver().getSerializer(innerList.getClass()).getClass(),
          CollectionSerializers.JDKCompatibleCollectionSerializer.class);
    }
    {
      ChildLinkedList<Integer> list = new ChildLinkedList<>();
      list.addAll(data);
      serDeCheck(fory, list);
    }
    {
      ChildArrayDeque<Integer> list = new ChildArrayDeque<>();
      list.addAll(data);
      Assert.assertEquals(ImmutableList.copyOf((ArrayDeque) (serDe(fory, list))), data);
    }
    {
      ChildVector<Integer> list = new ChildVector<>();
      list.addAll(data);
      serDeCheck(fory, list);
    }
    {
      ChildHashSet<Integer> list = new ChildHashSet<>();
      list.addAll(data);
      serDeCheck(fory, list);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testChildCollectionRejectsMismatchedClassLayerCount() {
    Fory fory = builder().withRefTracking(false).withCodegen(false).withCompatible(true).build();
    MemoryBuffer payload = MemoryUtils.buffer(32);
    payload.writeVarUInt32Small7(0);
    payload.writeVarUInt32Small7(0);

    payload.readerIndex(0);
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(payload, null, false);
    Serializer<ChildArrayList> serializer =
        (Serializer<ChildArrayList>) fory.getTypeResolver().getSerializer(ChildArrayList.class);

    Assert.assertThrows(ForyException.class, () -> serializer.read(readContext));
  }

  @Test
  public void testLayerMetadataIdentity() throws Exception {
    Fory fory =
        builder()
            .withRefTracking(false)
            .withCodegen(false)
            .withCompatible(true)
            .withMetaShare(true)
            .build();
    TypeResolver resolver = fory.getTypeResolver();
    TypeDef localTypeDef = resolver.getTypeDef(ChildHashMap1.class, false);
    TypeDef wrongTypeDef = resolver.getTypeDef(ChildHashMap2.class, false);
    CompatibleLayerSerializerBase localSerializer =
        new CompatibleLayerSerializer<>(
            resolver,
            ChildHashMap1.class,
            localTypeDef,
            LayerMarkerClassGenerator.getOrCreate(ChildHashMap1.class, 0));
    Method readLayerSerializer =
        ChildContainerSerializers.class.getDeclaredMethod(
            "readLayerSerializer",
            ReadContext.class,
            TypeResolver.class,
            CompatibleLayerSerializerBase.class);
    readLayerSerializer.setAccessible(true);

    MetaReadContext refMetaContext = new MetaReadContext();
    refMetaContext.readTypeInfos.add(new TypeInfo(ChildHashMap2.class, wrongTypeDef));
    MemoryBuffer refBuffer = MemoryUtils.buffer(16);
    refBuffer.writeVarUInt32(1);
    ReadContext refReadContext = fory.getReadContext();
    refReadContext.setMetaReadContext(refMetaContext);
    refReadContext.prepare(refBuffer, null, false);
    InvocationTargetException refError =
        Assert.expectThrows(
            InvocationTargetException.class,
            () -> readLayerSerializer.invoke(null, refReadContext, resolver, localSerializer));
    Assert.assertTrue(refError.getCause() instanceof ForyException);

    Field remoteTypeDefByHeaderHash =
        SharedRegistry.class.getDeclaredField("remoteTypeDefByHeaderHash");
    remoteTypeDefByHeaderHash.setAccessible(true);
    Map<Long, TypeDef> remoteTypeDefs =
        (Map<Long, TypeDef>) remoteTypeDefByHeaderHash.get(resolver.getSharedRegistry());
    Assert.assertFalse(remoteTypeDefs.containsKey(TypeDef.headerHash(wrongTypeDef.getId())));

    MetaReadContext bodyMetaContext = new MetaReadContext();
    MemoryBuffer bodyBuffer = MemoryUtils.buffer(256);
    bodyBuffer.writeVarUInt32(0);
    wrongTypeDef.writeTypeDef(bodyBuffer);
    ReadContext bodyReadContext = fory.getReadContext();
    bodyReadContext.setMetaReadContext(bodyMetaContext);
    bodyReadContext.prepare(bodyBuffer, null, false);
    InvocationTargetException bodyError =
        Assert.expectThrows(
            InvocationTargetException.class,
            () -> readLayerSerializer.invoke(null, bodyReadContext, resolver, localSerializer));
    Assert.assertTrue(bodyError.getCause() instanceof ForyException);
    Assert.assertFalse(remoteTypeDefs.containsKey(TypeDef.headerHash(wrongTypeDef.getId())));
    Assert.assertEquals(bodyMetaContext.readTypeInfos.size, 0);
  }

  @Test
  public void testLayerHeaderHashHit() throws Exception {
    Fory fory =
        builder()
            .withRefTracking(false)
            .withCodegen(false)
            .withCompatible(true)
            .withMetaShare(true)
            .build();
    TypeResolver resolver = fory.getTypeResolver();
    TypeDef localTypeDef = resolver.getTypeDef(ChildHashMap1.class, false);
    TypeDef remoteTypeDef = resolver.getTypeDef(ChildHashMap2.class, false);
    CompatibleLayerSerializerBase localSerializer =
        new CompatibleLayerSerializer<>(
            resolver,
            ChildHashMap1.class,
            localTypeDef,
            LayerMarkerClassGenerator.getOrCreate(ChildHashMap1.class, 0));
    Method readLayerSerializer =
        ChildContainerSerializers.class.getDeclaredMethod(
            "readLayerSerializer",
            ReadContext.class,
            TypeResolver.class,
            CompatibleLayerSerializerBase.class);
    readLayerSerializer.setAccessible(true);
    Field remoteTypeDefByHeaderHash =
        SharedRegistry.class.getDeclaredField("remoteTypeDefByHeaderHash");
    remoteTypeDefByHeaderHash.setAccessible(true);
    Map<Long, TypeDef> remoteTypeDefs =
        (Map<Long, TypeDef>) remoteTypeDefByHeaderHash.get(resolver.getSharedRegistry());
    long headerHash = TypeDef.headerHash(localTypeDef.getId());
    remoteTypeDefs.put(headerHash, remoteTypeDef);

    MemoryBuffer body = layerFrame(localTypeDef, 7, 7);
    ReadContext readContext = fory.getReadContext();
    readContext.setMetaReadContext(new MetaReadContext());
    readContext.prepare(body, null, false);
    CompatibleLayerSerializerBase result =
        (CompatibleLayerSerializerBase)
            readLayerSerializer.invoke(null, readContext, resolver, localSerializer);

    Assert.assertSame(result.getLayerTypeDef(), localTypeDef);
    Assert.assertEquals(body.readerIndex(), body.size());

    MemoryBuffer truncated = layerFrame(localTypeDef, 9, 8);
    ReadContext truncatedContext = fory.getReadContext();
    truncatedContext.setMetaReadContext(new MetaReadContext());
    truncatedContext.prepare(truncated, null, false);
    InvocationTargetException error =
        Assert.expectThrows(
            InvocationTargetException.class,
            () -> readLayerSerializer.invoke(null, truncatedContext, resolver, localSerializer));
    Assert.assertTrue(error.getCause() instanceof IndexOutOfBoundsException);
  }

  @Test
  public void testCheckedLayerOwnerMismatch() throws Exception {
    Fory fory =
        builder()
            .withRefTracking(false)
            .withCodegen(false)
            .withCompatible(true)
            .withMetaShare(true)
            .build();
    TypeResolver resolver = fory.getTypeResolver();
    TypeDef localTypeDef = resolver.getTypeDef(ChildHashMap1.class, false);
    TypeDef remoteTypeDef = resolver.getTypeDef(ChildHashMap2.class, false);
    CompatibleLayerSerializerBase localSerializer =
        new CompatibleLayerSerializer<>(
            resolver,
            ChildHashMap1.class,
            localTypeDef,
            LayerMarkerClassGenerator.getOrCreate(ChildHashMap1.class, 0));
    Method readLayerSerializer =
        ChildContainerSerializers.class.getDeclaredMethod(
            "readLayerSerializer",
            ReadContext.class,
            TypeResolver.class,
            CompatibleLayerSerializerBase.class);
    readLayerSerializer.setAccessible(true);
    Field remoteTypeDefByHeaderHash =
        SharedRegistry.class.getDeclaredField("remoteTypeDefByHeaderHash");
    remoteTypeDefByHeaderHash.setAccessible(true);
    Map<Long, TypeDef> remoteTypeDefs =
        (Map<Long, TypeDef>) remoteTypeDefByHeaderHash.get(resolver.getSharedRegistry());
    remoteTypeDefs.put(TypeDef.headerHash(remoteTypeDef.getId()), remoteTypeDef);

    MemoryBuffer body = layerFrame(remoteTypeDef, 4, 4);
    ReadContext readContext = fory.getReadContext();
    readContext.setMetaReadContext(new MetaReadContext());
    readContext.prepare(body, null, false);
    InvocationTargetException error =
        Assert.expectThrows(
            InvocationTargetException.class,
            () -> readLayerSerializer.invoke(null, readContext, resolver, localSerializer));

    Assert.assertTrue(error.getCause() instanceof ForyException);
  }

  private static MemoryBuffer layerFrame(
      TypeDef typeDef, int declaredBodySize, int writtenBodySize) {
    MemoryBuffer buffer = MemoryUtils.buffer(writtenBodySize + 16);
    buffer.writeVarUInt32(0);
    buffer.writeInt64((typeDef.getId() & ~0xfffL) | declaredBodySize);
    for (int i = 0; i < writtenBodySize; i++) {
      buffer.writeByte(0);
    }
    return MemoryBuffer.fromByteArray(buffer.getBytes(0, buffer.writerIndex()));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testChildCollectionCopy(Fory fory) {
    List<Object> data = ImmutableList.of(1, true, "test", Cyclic.create(true));
    {
      ChildArrayList<Object> list = new ChildArrayList<>();
      list.addAll(data);
      list.state = 3;
      ChildArrayList<Object> newList = fory.copy(list);
      Assert.assertEquals(newList, list);
      Assert.assertEquals(newList.state, 3);
      Assert.assertEquals(
          fory.getTypeResolver().getSerializer(newList.getClass()).getClass(),
          ChildContainerSerializers.ChildArrayListSerializer.class);
      ArrayList<Object> innerList =
          new ArrayList<Object>() {
            {
              add(Cyclic.create(true));
            }
          };
      copyCheck(fory, innerList);
      Assert.assertEquals(
          fory.getTypeResolver().getSerializer(innerList.getClass()).getClass(),
          CollectionSerializers.JDKCompatibleCollectionSerializer.class);
    }
    {
      ChildLinkedList<Object> list = new ChildLinkedList<>();
      list.addAll(data);
      copyCheck(fory, list);
    }
    {
      ChildArrayDeque<Object> list = new ChildArrayDeque<>();
      list.addAll(data);
      Assert.assertEquals(ImmutableList.copyOf(fory.copy(list)), data);
    }
    {
      ChildVector<Object> list = new ChildVector<>();
      list.addAll(data);
      copyCheck(fory, list);
    }
    {
      ChildHashSet<Object> list = new ChildHashSet<>();
      list.addAll(data);
      copyCheck(fory, list);
    }
  }

  public static class ChildHashMap<K, V> extends HashMap<K, V> {
    private int state;
  }

  public static class ChildLinkedHashMap<K, V> extends LinkedHashMap<K, V> {}

  public static class ChildConcurrentHashMap<K, V> extends ConcurrentHashMap<K, V> {}

  @Test(dataProvider = "foryConfig")
  public void testChildMap(Fory fory) {
    Map<String, Integer> data = ImmutableMap.of("k1", 1, "k2", 2);
    {
      ChildHashMap<String, Integer> map = new ChildHashMap<>();
      map.putAll(data);
      map.state = 3;
      ChildHashMap<String, Integer> newMap = (ChildHashMap<String, Integer>) serDe(fory, map);
      Assert.assertEquals(newMap, map);
      Assert.assertEquals(newMap.state, 3);
      Assert.assertEquals(
          fory.getTypeResolver().getSerializer(newMap.getClass()).getClass(),
          ChildContainerSerializers.ChildMapSerializer.class);
    }
    {
      ChildLinkedHashMap<String, Integer> map = new ChildLinkedHashMap<>();
      map.putAll(data);
      serDeCheck(fory, map);
    }
    {
      ChildConcurrentHashMap<String, Integer> map = new ChildConcurrentHashMap<>();
      map.putAll(data);
      serDeCheck(fory, map);
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testChildMapCopy(Fory fory) {
    Map<String, Object> data = ImmutableMap.of("k1", 1, "k2", 2, "k3", Cyclic.create(true));
    {
      ChildHashMap<String, Object> map = new ChildHashMap<>();
      map.putAll(data);
      map.state = 3;
      ChildHashMap<String, Object> copy = fory.copy(map);
      Assert.assertEquals(map, copy);
      Assert.assertEquals(map.state, copy.state);
      Assert.assertNotSame(map, copy);
    }
    {
      ChildLinkedHashMap<String, Object> map = new ChildLinkedHashMap<>();
      map.putAll(data);
      copyCheck(fory, map);
    }
    {
      ChildConcurrentHashMap<String, Object> map = new ChildConcurrentHashMap<>();
      map.putAll(data);
      copyCheck(fory, map);
    }
  }

  private static class CustomMap extends HashMap<String, String> {}

  @Data
  private static class UserDO {
    private CustomMap features;
  }

  @Test(dataProvider = "enableCodegen")
  public void testSerializeCustomPrivateMap(boolean enableCodegen) {
    CustomMap features = new CustomMap();
    features.put("a", "A");
    UserDO outerDO = new UserDO();
    outerDO.setFeatures(features);
    Fory fory =
        builder()
            .withCompatible(true)
            .withDeserializeUnknownClass(true)
            .withMetaShare(true)
            .withScopedMetaShare(false)
            .withCodegen(enableCodegen)
            .build();
    serDeMetaShare(fory, outerDO);
  }

  /**
   * Tests that meta context indices stay synchronized when layer class meta entries from
   * readAndSkipLayerClassMeta are interleaved with regular type info entries. Multiple instances of
   * the same nested HashMap subclass type force meta context reference lookups, which would fail if
   * readAndSkipLayerClassMeta did not add placeholders to readTypeInfos.
   */
  @Test(dataProvider = "enableCodegen")
  public void testMetaReadContextIndexSyncWithNestedChildMaps(boolean enableCodegen) {
    Fory fory =
        builder()
            .withCodegen(enableCodegen)
            .withAsyncCompilation(false)
            .withRefTracking(false)
            .withCompatible(true)
            .build();

    ChildHashMap1 map1a = new ChildHashMap1();
    map1a.put("k1", "v1");

    ChildHashMap1 map1b = new ChildHashMap1();
    map1b.put("k2", "v2");

    ChildHashMap2 map2a = new ChildHashMap2();
    map2a.put("a", map1a);

    ChildHashMap2 map2b = new ChildHashMap2();
    map2b.put("b", map1b);

    ChildHashMap3 map3a = new ChildHashMap3();
    map3a.put("x", map2a);

    ChildHashMap3 map3b = new ChildHashMap3();
    map3b.put("y", map2b);

    ChildHashMap4 map4 = new ChildHashMap4();
    map4.put("group1", map3a);
    map4.put("group2", map3b);

    ChildMapHolder holder = new ChildMapHolder("meta-sync-test", map4);
    ChildMapHolder deserialized = serDe(fory, holder);
    Assert.assertEquals(deserialized, holder);
  }

  /* Deeply nested HashMap subclass hierarchy for testing generic propagation */

  public static class ChildHashMap1 extends HashMap<String, String> {}

  public static class ChildHashMap2 extends HashMap<String, ChildHashMap1> {}

  public static class ChildHashMap3 extends HashMap<String, ChildHashMap2> {}

  public static class ChildHashMap4 extends HashMap<String, ChildHashMap3> {}

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ChildMapHolder {
    private String id;
    private ChildHashMap4 nestedMaps;
  }

  @Test(dataProvider = "enableCodegen")
  public void testNestedHashMapSubclassSerialization(boolean enableCodegen) {
    Fory fory =
        Fory.builder()
            .withCodegen(enableCodegen)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withXlang(false)
            .withCompatible(false)
            .build();

    ChildHashMap1 map1a = new ChildHashMap1();
    map1a.put("k1", "v1");
    map1a.put("k2", "v2");

    ChildHashMap1 map1b = new ChildHashMap1();
    map1b.put("k3", "v3");
    map1b.put("k4", "v4");

    ChildHashMap2 map2a = new ChildHashMap2();
    map2a.put("a", map1a);
    map2a.put("b", map1b);

    ChildHashMap2 map2b = new ChildHashMap2();
    map2b.put("c", map1b);

    ChildHashMap3 map3a = new ChildHashMap3();
    map3a.put("x", map2a);
    map3a.put("y", map2b);

    ChildHashMap3 map3b = new ChildHashMap3();
    map3b.put("z", map2a);

    ChildHashMap4 map4 = new ChildHashMap4();
    map4.put("group1", map3a);
    map4.put("group2", map3b);

    ChildMapHolder holder = new ChildMapHolder("doc-123", map4);
    ChildMapHolder deserialized = serDe(fory, holder);
    Assert.assertEquals(deserialized, holder);
  }

  @Test
  public void testNestedHashMapSubclassWithCompatible() {
    Fory fory =
        Fory.builder()
            .withCodegen(false)
            .withAsyncCompilation(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withCompatible(true)
            .withXlang(false)
            .build();

    ChildHashMap1 map1a = new ChildHashMap1();
    map1a.put("k1", "v1");
    map1a.put("k2", "v2");

    ChildHashMap1 map1b = new ChildHashMap1();
    map1b.put("k3", "v3");
    map1b.put("k4", "v4");

    ChildHashMap2 map2a = new ChildHashMap2();
    map2a.put("a", map1a);
    map2a.put("b", map1b);

    ChildHashMap2 map2b = new ChildHashMap2();
    map2b.put("c", map1a);

    ChildHashMap3 map3a = new ChildHashMap3();
    map3a.put("x", map2a);
    map3a.put("y", map2b);

    ChildHashMap3 map3b = new ChildHashMap3();
    map3b.put("z", map2a);

    ChildHashMap4 map4 = new ChildHashMap4();
    map4.put("group1", map3a);
    map4.put("group2", map3b);

    ChildMapHolder holder = new ChildMapHolder("config-456", map4);
    ChildMapHolder deserialized = serDe(fory, holder);
    Assert.assertEquals(deserialized, holder);
  }

  private static final class LengthThenNaturalComparator implements Comparator<String> {
    @Override
    public int compare(String left, String right) {
      int delta = Integer.compare(left.length(), right.length());
      if (delta != 0) {
        return delta;
      }
      return left.compareTo(right);
    }
  }

  public static class AutoChildTreeSet extends TreeSet<String> {
    private String state;

    public AutoChildTreeSet() {}
  }

  public static class AutoChildConcurrentSkipListSet extends ConcurrentSkipListSet<String> {
    private String state;

    public AutoChildConcurrentSkipListSet(SortedSet<String> values) {
      super(values);
    }
  }

  public static class AutoChildPriorityQueue extends PriorityQueue<String> {
    private String state;

    public AutoChildPriorityQueue(SortedSet<String> values) {
      super(values);
    }
  }

  public static class AutoChildTreeMap extends TreeMap<String, String> {
    private String state;

    public AutoChildTreeMap() {}
  }

  public static class AutoChildConcurrentSkipListMap extends ConcurrentSkipListMap<String, String> {
    private String state;

    public AutoChildConcurrentSkipListMap(SortedMap<String, String> values) {
      super(values);
    }
  }

  private static SortedSet<String> newComparatorSortedSetSource() {
    TreeSet<String> set = new TreeSet<>(new LengthThenNaturalComparator());
    set.addAll(ImmutableList.of("bbb", "a", "cc"));
    return set;
  }

  private static SortedMap<String, String> newComparatorSortedMapSource() {
    TreeMap<String, String> map = new TreeMap<>(new LengthThenNaturalComparator());
    map.put("bbb", "B");
    map.put("a", "A");
    map.put("cc", "C");
    return map;
  }

  private static List<String> drainPriorityQueue(PriorityQueue<String> queue) {
    PriorityQueue<String> copy = new PriorityQueue<>(queue);
    List<String> values = new ArrayList<>();
    while (!copy.isEmpty()) {
      values.add(copy.poll());
    }
    return values;
  }

  @Test(dataProvider = "foryConfig")
  public void testAutoSelectsOptimizedSortedCollectionChildSerializers(Fory fory) {
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(AutoChildTreeSet.class),
        ChildContainerSerializers.ChildSortedSetSerializer.class);
    AutoChildTreeSet treeSet = new AutoChildTreeSet();
    treeSet.state = "tree-state";
    treeSet.addAll(ImmutableList.of("b", "a", "c"));
    AutoChildTreeSet treeSetCopy = serDe(fory, treeSet);
    Assert.assertEquals(treeSetCopy, treeSet);
    Assert.assertEquals(treeSetCopy.state, treeSet.state);
    Assert.assertNull(treeSetCopy.comparator());

    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(AutoChildConcurrentSkipListSet.class),
        ChildContainerSerializers.ChildSortedSetSerializer.class);
    AutoChildConcurrentSkipListSet skipListSet =
        new AutoChildConcurrentSkipListSet(newComparatorSortedSetSource());
    skipListSet.state = "skip-state";
    AutoChildConcurrentSkipListSet skipListSetCopy = serDe(fory, skipListSet);
    Assert.assertEquals(skipListSetCopy, skipListSet);
    Assert.assertEquals(skipListSetCopy.state, skipListSet.state);
    Assert.assertEquals(new ArrayList<>(skipListSetCopy), new ArrayList<>(skipListSet));
    Assert.assertNotNull(skipListSetCopy.comparator());
    Assert.assertEquals(skipListSetCopy.comparator().getClass(), LengthThenNaturalComparator.class);

    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(AutoChildPriorityQueue.class),
        ChildContainerSerializers.ChildPriorityQueueSerializer.class);
    AutoChildPriorityQueue priorityQueue =
        new AutoChildPriorityQueue(newComparatorSortedSetSource());
    priorityQueue.state = "queue-state";
    AutoChildPriorityQueue priorityQueueCopy = serDe(fory, priorityQueue);
    Assert.assertEquals(priorityQueueCopy.state, priorityQueue.state);
    Assert.assertEquals(drainPriorityQueue(priorityQueueCopy), drainPriorityQueue(priorityQueue));
    Assert.assertNotNull(priorityQueueCopy.comparator());
    Assert.assertEquals(
        priorityQueueCopy.comparator().getClass(), LengthThenNaturalComparator.class);
  }

  @Test(dataProvider = "foryConfig")
  public void testAutoSelectsOptimizedSortedMapChildSerializers(Fory fory) {
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(AutoChildTreeMap.class),
        ChildContainerSerializers.ChildSortedMapSerializer.class);
    AutoChildTreeMap treeMap = new AutoChildTreeMap();
    treeMap.state = "tree-map-state";
    treeMap.put("b", "B");
    treeMap.put("a", "A");
    treeMap.put("c", "C");
    AutoChildTreeMap treeMapCopy = serDe(fory, treeMap);
    Assert.assertEquals(treeMapCopy, treeMap);
    Assert.assertEquals(treeMapCopy.state, treeMap.state);
    Assert.assertNull(treeMapCopy.comparator());

    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(AutoChildConcurrentSkipListMap.class),
        ChildContainerSerializers.ChildSortedMapSerializer.class);
    AutoChildConcurrentSkipListMap skipListMap =
        new AutoChildConcurrentSkipListMap(newComparatorSortedMapSource());
    skipListMap.state = "skip-map-state";
    AutoChildConcurrentSkipListMap skipListMapCopy = serDe(fory, skipListMap);
    Assert.assertEquals(skipListMapCopy, skipListMap);
    Assert.assertEquals(skipListMapCopy.state, skipListMap.state);
    Assert.assertEquals(
        new ArrayList<>(skipListMapCopy.keySet()), new ArrayList<>(skipListMap.keySet()));
    Assert.assertNotNull(skipListMapCopy.comparator());
    Assert.assertEquals(skipListMapCopy.comparator().getClass(), LengthThenNaturalComparator.class);
  }

  /* Mixed collection subclass test (TreeSet + HashMap subclasses) */

  public static class ChildTreeSet extends TreeSet<ChildTreeSetEntry> {
    public ChildTreeSet() {
      super();
    }

    public static ChildTreeSet empty() {
      return new ChildTreeSet();
    }

    public static Collector<ChildTreeSetEntry, ?, ChildTreeSet> collector() {
      return Collectors.collectingAndThen(
          Collectors.toCollection(TreeSet::new),
          set -> {
            ChildTreeSet docs = new ChildTreeSet();
            docs.addAll(set);
            return docs;
          });
    }

    public static ChildTreeSet of(Collection<ChildTreeSetEntry> multiple) {
      return multiple.stream().collect(collector());
    }
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @EqualsAndHashCode
  public static class ChildTreeSetEntry implements Comparable<ChildTreeSetEntry> {
    private String id;
    private String name;

    @Override
    public int compareTo(ChildTreeSetEntry o) {
      return this.id.compareTo(o.id);
    }
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ChildMixedContainer {
    private String id;
    private ChildHashMap4 nestedMaps;
    private ChildTreeSet entries;
    private Map<String, ChildTreeSet> entriesByCategory;
  }

  @Test
  public void testMixedCollectionSubclassesWithCompatible() {
    Fory fory =
        Fory.builder()
            .withCodegen(false)
            .withAsyncCompilation(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withCompatible(true)
            .withXlang(false)
            .build();

    ChildHashMap1 map1 = new ChildHashMap1();
    map1.put("k1", "v1");
    map1.put("k2", "v2");

    ChildHashMap2 map2 = new ChildHashMap2();
    map2.put("a", map1);

    ChildHashMap3 map3 = new ChildHashMap3();
    map3.put("x", map2);

    ChildHashMap4 map4 = new ChildHashMap4();
    map4.put("group1", map3);

    ChildTreeSet set1 = ChildTreeSet.empty();
    set1.add(new ChildTreeSetEntry("1", "entry1"));
    set1.add(new ChildTreeSetEntry("2", "entry2"));

    ChildTreeSet set2 = ChildTreeSet.empty();
    set2.add(new ChildTreeSetEntry("3", "entry3"));

    Map<String, ChildTreeSet> setsByKey = new HashMap<>();
    setsByKey.put("category1", set1);
    setsByKey.put("category2", set2);

    ChildMixedContainer container = new ChildMixedContainer("mixed-789", map4, set1, setsByKey);
    ChildMixedContainer deserialized = serDe(fory, container);
    Assert.assertEquals(deserialized, container);
  }

  public static class ChildLinkedListElemList extends LinkedList<ChildLinkedListElemList> {}

  public static class ChildLinkedListElemListStruct {
    public ChildLinkedListElemList list;
  }

  @Test
  public void testElemTypeSameWithCollection() {
    Fory fory = builder().withRefTracking(true).build();
    ChildLinkedListElemList list = new ChildLinkedListElemList();
    list.add(list);
    ChildLinkedListElemList list1 = serDe(fory, list);
    Assert.assertSame(list1.get(0), list1);

    ChildLinkedListElemListStruct struct = new ChildLinkedListElemListStruct();
    struct.list = list;
    ChildLinkedListElemListStruct struct1 = serDe(fory, struct);
    Assert.assertSame(struct1.list.get(0), struct1.list);
  }
}
