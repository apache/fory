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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
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
      {
        builder()
            .withRefTracking(false)
            .withScopedMetaShare(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build()
      },
      {
        builder()
            .withRefTracking(false)
            .withScopedMetaShare(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build()
      },
      {
        builder()
            .withRefTracking(false)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .build()
      },
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
          fory.getClassResolver().getSerializer(newList.getClass()).getClass(),
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
          fory.getClassResolver().getSerializer(innerList.getClass()).getClass(),
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
          fory.getClassResolver().getSerializer(newList.getClass()).getClass(),
          ChildContainerSerializers.ChildArrayListSerializer.class);
      ArrayList<Object> innerList =
          new ArrayList<Object>() {
            {
              add(Cyclic.create(true));
            }
          };
      copyCheck(fory, innerList);
      Assert.assertEquals(
          fory.getClassResolver().getSerializer(innerList.getClass()).getClass(),
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
          fory.getClassResolver().getSerializer(newMap.getClass()).getClass(),
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
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withDeserializeUnknownClass(true)
            .withMetaShare(true)
            .withScopedMetaShare(false)
            .withCodegen(enableCodegen)
            .build();
    serDeMetaShared(fory, outerDO);
  }

  /**
   * Tests that meta context indices stay synchronized when layer class meta entries from
   * readAndSkipLayerClassMeta are interleaved with regular type info entries. Multiple instances of
   * the same nested HashMap subclass type force meta context reference lookups, which would fail if
   * readAndSkipLayerClassMeta did not add placeholders to readTypeInfos.
   */
  @Test(dataProvider = "enableCodegen")
  public void testMetaContextIndexSyncWithNestedChildMaps(boolean enableCodegen) {
    Fory fory =
        builder()
            .withCodegen(enableCodegen)
            .withAsyncCompilation(false)
            .withRefTracking(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
            .withLanguage(Language.JAVA)
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
  public void testNestedHashMapSubclassWithCompatibleMode() {
    Fory fory =
        Fory.builder()
            .withCodegen(false)
            .withAsyncCompilation(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withLanguage(Language.JAVA)
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
  public void testMixedCollectionSubclassesWithCompatibleMode() {
    Fory fory =
        Fory.builder()
            .withCodegen(false)
            .withAsyncCompilation(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withLanguage(Language.JAVA)
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
