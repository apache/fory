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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.CompatibleMode;
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
