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

import static org.apache.fory.serializer.collection.UnmodifiableSerializers.createSerializer;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.test.bean.CollectionFields;
import org.apache.fory.test.bean.MapFields;
import org.testng.Assert;
import org.testng.annotations.Test;

public class UnmodifiableSerializersTest extends ForyTestBase {
  static long SOURCE_COLLECTION_FIELD_OFFSET =
      ReflectionUtils.getFieldOffset(
          Collections.synchronizedCollection(Collections.emptyList()).getClass(), "c");
  static long SOURCE_MAP_FIELD_OFFSET =
      ReflectionUtils.getFieldOffset(
          Collections.synchronizedMap(Collections.emptyMap()).getClass(), "m");

  @SuppressWarnings("unchecked")
  @Test
  public void testWrite() throws Exception {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    MemoryBuffer buffer = MemoryBuffer.buffer(32);
    Object[] values =
        new Object[] {
          Collections.unmodifiableCollection(Collections.singletonList("abc")),
          Collections.unmodifiableCollection(Arrays.asList("abc", "def")),
          Collections.unmodifiableList(Arrays.asList("abc", "def")),
          Collections.unmodifiableList(new LinkedList<>(Arrays.asList("abc", "def"))),
          Collections.unmodifiableSet(new HashSet<>(Arrays.asList("abc", "def"))),
          Collections.unmodifiableSortedSet(new TreeSet<>(Arrays.asList("abc", "def"))),
          Collections.unmodifiableMap(ImmutableMap.of("k1", "v1")),
          Collections.unmodifiableSortedMap(new TreeMap<>(ImmutableMap.of("k1", "v1")))
        };
    for (Object value : values) {
      buffer.writerIndex(0);
      buffer.readerIndex(0);
      Serializer serializer = createSerializer(fory, value.getClass());
      serializer.write(buffer, value);
      Object newObj = serializer.read(buffer);
      assertEquals(newObj.getClass(), value.getClass());
      long sourceCollectionFieldOffset =
          Collection.class.isAssignableFrom(value.getClass())
              ? SOURCE_COLLECTION_FIELD_OFFSET
              : SOURCE_MAP_FIELD_OFFSET;
      Object innerValue = Platform.getObject(value, sourceCollectionFieldOffset);
      Object newValue = Platform.getObject(newObj, sourceCollectionFieldOffset);
      assertEquals(innerValue, newValue);

      newObj = serDe(fory, value);
      innerValue = Platform.getObject(value, sourceCollectionFieldOffset);
      newValue = Platform.getObject(newObj, sourceCollectionFieldOffset);
      assertEquals(innerValue, newValue);
      assertTrue(
          fory.getClassResolver()
              .getSerializerClass(value.getClass())
              .getName()
              .contains("Unmodifiable"));
    }
  }

  public static CollectionFields createCollectionFields() {
    CollectionFields obj = new CollectionFields();
    obj.collection = Collections.unmodifiableCollection(Arrays.asList(1, 2));
    obj.collection2 = Arrays.asList(1, 2);
    List<String> randomAccessList = Collections.unmodifiableList(Arrays.asList("abc", "def"));
    obj.randomAccessList = randomAccessList;
    obj.randomAccessList2 = randomAccessList;
    obj.randomAccessList3 = randomAccessList;
    List<String> list = Collections.unmodifiableList(new LinkedList<>(Arrays.asList("abc", "def")));
    obj.list = list;
    obj.list2 = list;
    obj.list3 = list;
    Set<String> set = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("abc", "def")));
    obj.set = set;
    obj.set2 = set;
    obj.set3 = set;
    SortedSet<String> treeSet =
        Collections.unmodifiableSortedSet(new TreeSet<>(Arrays.asList("abc", "def")));
    obj.sortedSet = treeSet;
    obj.sortedSet2 = treeSet;
    obj.sortedSet3 = treeSet;
    Map<String, String> map = Collections.unmodifiableMap(ImmutableMap.of("k1", "v1"));
    obj.map = map;
    obj.map2 = map;
    SortedMap<Integer, Integer> sortedMap =
        Collections.unmodifiableSortedMap(new TreeMap<>(ImmutableMap.of(1, 2)));
    obj.sortedMap = sortedMap;
    obj.sortedMap2 = sortedMap;
    return obj;
  }

  @Test(dataProvider = "javaFory")
  public void testCollectionFieldSerializers(Fory fory) {
    CollectionFields obj = createCollectionFields();
    Object newObj = serDe(fory, obj);
    assertEquals(((CollectionFields) (newObj)).toCanEqual(), obj.toCanEqual());
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testCollectionFieldSerializersCopy(Fory fory) {
    CollectionFields obj = createCollectionFields();
    Object newObj = fory.copy(obj);
    assertEquals(((CollectionFields) (newObj)).toCanEqual(), obj.toCanEqual());
  }

  public static MapFields createMapFields() {
    MapFields obj = new MapFields();
    Map<String, Integer> map = ImmutableMap.of("k1", 1, "k2", 2);
    obj.map = Collections.unmodifiableMap(map);
    obj.map2 = Collections.unmodifiableMap(map);
    obj.map3 = new HashMap<>(map);
    obj.mapKeyFinal =
        Collections.unmodifiableMap(
            new HashMap<>(ImmutableMap.of("k1", map, "k2", new HashMap<>(map))));
    obj.mapValueFinal = Collections.unmodifiableMap(new HashMap<>(map));
    obj.linkedHashMap = Collections.unmodifiableMap(new LinkedHashMap<>(map));
    obj.linkedHashMap2 = Collections.unmodifiableMap(new LinkedHashMap<>(map));
    obj.linkedHashMap3 = new LinkedHashMap<>(map);
    obj.sortedMap = new TreeMap<>(map);
    obj.sortedMap2 = new TreeMap<>(map);
    obj.sortedMap3 = new TreeMap<>(map);
    obj.concurrentHashMap = Collections.unmodifiableMap(new ConcurrentHashMap<>(map));
    obj.concurrentHashMap2 = new ConcurrentHashMap<>(map);
    obj.concurrentHashMap3 = new ConcurrentHashMap<>(map);
    obj.skipListMap = Collections.unmodifiableMap(new ConcurrentSkipListMap<>(map));
    obj.skipListMap2 = new ConcurrentSkipListMap<>(map);
    obj.skipListMap3 = new ConcurrentSkipListMap<>(map);
    EnumMap<CollectionSerializersTest.TestEnum, Object> enumMap =
        new EnumMap<>(CollectionSerializersTest.TestEnum.class);
    enumMap.put(CollectionSerializersTest.TestEnum.A, 1);
    enumMap.put(CollectionSerializersTest.TestEnum.B, "str");
    obj.enumMap = Collections.unmodifiableMap(enumMap);
    obj.enumMap2 = enumMap;
    return obj;
  }

  @Test(dataProvider = "javaFory")
  public void testMapFieldSerializers(Fory fory) {
    MapFields obj = createMapFields();
    Assert.assertEquals(serDe(fory, obj), obj);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testMapFieldSerializersCopy(Fory fory) {
    MapFields obj = createMapFields();
    copyCheck(fory, obj);
  }
}
