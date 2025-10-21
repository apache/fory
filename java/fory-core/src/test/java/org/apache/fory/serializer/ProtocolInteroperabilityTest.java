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

import static com.google.common.collect.ImmutableList.of;
import static org.apache.fory.TestUtils.mapOf;
import static org.apache.fory.collection.Collections.ofArrayList;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.serializer.collection.CollectionSerializersTest;
import org.apache.fory.serializer.collection.MapSerializersTest;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.test.bean.MapFields;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Protocol interoperability test between interpreter mode and jit mode. */
public class ProtocolInteroperabilityTest extends ForyTestBase {
  @DataProvider(name = "fory")
  public static Object[][] fory() {
    return Sets.cartesianProduct(
            ImmutableSet.of(true, false), // referenceTracking
            ImmutableSet.of(true, false), // compressNumber
            ImmutableSet.of(true, false), // scopedMetaShare
            ImmutableSet.of(CompatibleMode.SCHEMA_CONSISTENT, CompatibleMode.COMPATIBLE))
        .stream()
        .map(List::toArray)
        .map(
            c ->
                new Object[] {
                  builder()
                      .withLanguage(Language.JAVA)
                      .withRefTracking((Boolean) c[0])
                      .withNumberCompressed((Boolean) c[1])
                      .withCodegen(false)
                      .withScopedMetaShare((Boolean) c[2])
                      .withCompatibleMode((CompatibleMode) c[3])
                      .requireClassRegistration(false)
                      .build(),
                  builder()
                      .withLanguage(Language.JAVA)
                      .withRefTracking((Boolean) c[0])
                      .withNumberCompressed((Boolean) c[1])
                      .withCodegen(true)
                      .withScopedMetaShare((Boolean) c[2])
                      .withCompatibleMode((CompatibleMode) c[3])
                      .requireClassRegistration(false)
                      .build()
                })
        .toArray(Object[][]::new);
  }

  @Data
  @AllArgsConstructor
  public static class GenericBoundTest {
    public List<Collection<Collection<Integer>>> list1;
    public List<? extends Collection> list2;
    public List<? extends Collection<? extends Collection<Integer>>> list3;
  }

  @Test(dataProvider = "fory")
  public void testGenericCollectionBound(Fory fory, Fory foryJIT) {
    ArrayList<Integer> list = ofArrayList(1, 2);
    roundCheck(
        fory,
        foryJIT,
        new GenericBoundTest(
            ofArrayList(ofArrayList(list)),
            of(list),
            new ArrayList<>(of(new ArrayList<>(of(list))))));
  }

  @Data
  @AllArgsConstructor
  public static class GenericMapBoundTest {
    public Map<Map<Integer, Collection<Integer>>, Integer> map1;
    public Map<? extends Map, Integer> map2;
    public Map<? extends Map<Integer, ? extends Collection<Integer>>, Integer> map3;
  }

  @Test(dataProvider = "fory")
  public void testGenericMapBound(Fory fory, Fory foryJIT) {
    ArrayList<Integer> list = new ArrayList<>(of(1, 2));
    roundCheck(
        fory,
        foryJIT,
        new GenericMapBoundTest(
            new HashMap<>(mapOf(new HashMap<>(mapOf(1, list)), 1)),
            new HashMap<>(mapOf(new HashMap<>(mapOf(1, list)), 1)),
            new HashMap<>(mapOf(new HashMap<>(mapOf(1, list)), 1))));
  }

  @Data
  @AllArgsConstructor
  public static class SimpleCollectionTest {
    public List<Integer> integerList;
    public Collection<String> stringList;
  }

  @Test(dataProvider = "fory")
  public void testSimpleCollection(Fory fory, Fory foryJIT) {
    roundCheck(
        fory,
        foryJIT,
        new SimpleCollectionTest(new ArrayList<>(of(1, 2)), new LinkedList<>(of("a", "b"))));
    roundCheck(fory, foryJIT, new SimpleCollectionTest(of(1, 2), new HashSet<>(of("a", "b"))));
    roundCheck(fory, foryJIT, new SimpleCollectionTest(Arrays.asList(1, 2), of("a", "b")));
    roundCheck(fory, foryJIT, new SimpleCollectionTest(of(1, 2), new TreeSet<>(of("a", "b"))));
  }

  @Data
  @AllArgsConstructor
  public static class SimpleMapTest {
    public Map<String, Integer> map1;
    public Map<Integer, Integer> map2;
  }

  @Test(dataProvider = "fory")
  public void testSimpleMap(Fory fory, Fory foryJIT) {
    roundCheck(
        fory,
        foryJIT,
        new SimpleMapTest(new HashMap<>(mapOf("k", 2)), new LinkedHashMap<>(mapOf(1, 2))));
    roundCheck(fory, foryJIT, new SimpleMapTest(mapOf("k", 2), new TreeMap<>(mapOf(1, 2))));
  }

  @Data
  @AllArgsConstructor
  public static class NestedCollectionTest {
    public Collection<Integer> list1;
    public List<Collection<Collection<Integer>>> list2;
  }

  @Test(dataProvider = "fory")
  public void testNestedCollection(Fory fory, Fory foryJIT) {
    ArrayList<Integer> list = new ArrayList<>(of(1, 2));
    roundCheck(
        fory,
        foryJIT,
        new NestedCollectionTest(
            new ArrayList<>(list), new ArrayList<>(of(new ArrayList<>(of(list))))));
    roundCheck(
        fory,
        foryJIT,
        new NestedCollectionTest(
            new LinkedList<>(list), new ArrayList<>(of(new ArrayList<>(of(list))))));
  }

  @Data
  @AllArgsConstructor
  public static class NestedMapTest {
    public Map<Map<Integer, Map<Integer, Integer>>, Map<Integer, Integer>> map1;
    public Map<Integer, Integer> map2;
  }

  @Test(dataProvider = "fory")
  public void testNestedMap(Fory fory, Fory foryJIT) {
    roundCheck(
        fory,
        foryJIT,
        new NestedMapTest(
            new HashMap<>(mapOf(mapOf(1, new HashMap<>(mapOf(1, 2))), mapOf(1, 2))),
            new HashMap<>(mapOf(1, 2))));
  }

  @Test(dataProvider = "fory")
  public void testSimplePojo(Fory fory, Fory foryJIT) {
    roundCheck(fory, foryJIT, BeanB.createBeanB(2));
  }

  @Test(dataProvider = "fory")
  public void testNestedPojo(Fory fory, Fory foryJIT) {
    roundCheck(fory, foryJIT, BeanA.createBeanA(2));
  }

  @Test(dataProvider = "fory")
  public void testComplexCollection(Fory fory, Fory foryJIT) {
    CollectionSerializersTest.CollectionFieldsClass o =
        CollectionSerializersTest.createCollectionFieldsObject();
    roundCheck(
        fory,
        foryJIT,
        o,
        obj -> {
          try {
            CollectionSerializersTest.testCollectionFieldsObjectEqual(
                (CollectionSerializersTest.CollectionFieldsClass) obj, o);
            return "OK";
          } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test(dataProvider = "fory")
  public void testComplexMap(Fory fory, Fory foryJIT) {
    MapFields o = MapSerializersTest.createMapFieldsObject();
    roundCheck(fory, foryJIT, o);
  }

  @DataProvider(name = "metaShareFory")
  public static Object[][] metaShareFory() {
    return Sets.cartesianProduct(
            ImmutableSet.of(CompatibleMode.COMPATIBLE) // structFieldsRepeat
            )
        .stream()
        .map(List::toArray)
        .map(
            c ->
                new Object[] {
                  builder()
                      .withLanguage(Language.JAVA)
                      .withMetaShare(true)
                      .withCompatibleMode((CompatibleMode) c[0])
                      .withScopedMetaShare(false)
                      .withCodegen(false)
                      .requireClassRegistration(false)
                      .build(),
                  builder()
                      .withLanguage(Language.JAVA)
                      .withMetaShare(true)
                      .withCompatibleMode((CompatibleMode) c[0])
                      .withScopedMetaShare(false)
                      .withCodegen(true)
                      .requireClassRegistration(false)
                      .build()
                })
        .toArray(Object[][]::new);
  }

  @Test(dataProvider = "metaShareFory")
  public void testSimpleCollectionTestMetaShare(Fory fory, Fory foryJIT) {
    metaShareRoundCheck(
        fory, foryJIT, new SimpleCollectionTest(of(1, 2), new HashSet<>(of("a", "b"))));
  }

  @Test(dataProvider = "metaShareFory")
  public void testSimplePojoMetaShare(Fory fory, Fory foryJIT) {
    metaShareRoundCheck(fory, foryJIT, BeanB.createBeanB(2));
  }

  @Data
  @AllArgsConstructor
  public static class InnerPojo {
    public int anInt;
  }

  @Data
  @AllArgsConstructor
  public static class OuterPojo1 {
    public List<Integer> integers;
    public List<InnerPojo> list;
  }

  @Data
  @AllArgsConstructor
  public static class OuterPojo2 {
    public List<Integer> integers;
    public List<BeanB> list;
  }

  @Test(dataProvider = "metaShareFory")
  public void testSimpleNestedPojoMetaShare(Fory fory, Fory foryJIT) {
    metaShareRoundCheck(
        fory,
        foryJIT,
        new OuterPojo1(ofArrayList(1, 2), ofArrayList(new InnerPojo(10), new InnerPojo(10))));
    metaShareRoundCheck(
        fory,
        foryJIT,
        new OuterPojo2(new ArrayList<>(of(1, 2)), new ArrayList<>(of(BeanB.createBeanB(2)))));
  }

  @Test(dataProvider = "metaShareFory")
  public void testNestedPojoMetaShare(Fory fory, Fory foryJIT) {
    metaShareRoundCheck(fory, foryJIT, BeanA.createBeanA(2));
  }

  @Data
  @AllArgsConstructor
  public static class InnerTypeSerializerNotCreated {
    public int[][] intArray;
    public AtomicBoolean[] booleans;
  }

  @Test(dataProvider = "metaShareFory")
  public void testInnerTypeSerializerNotCreated(Fory fory, Fory foryJIT) {
    metaShareRoundCheck(
        fory,
        foryJIT,
        new InnerTypeSerializerNotCreated(
            new int[][] {{1, 2}, {3, 4}}, new AtomicBoolean[] {new AtomicBoolean(true)}),
        Object::toString);
  }

  private static void metaShareRoundCheck(Fory fory1, Fory fory2, Object o) {
    metaShareRoundCheck(fory1, fory2, o, Function.identity());
  }

  private static void metaShareRoundCheck(
      Fory fory1, Fory fory2, Object o, Function<Object, Object> compareHook) {
    MetaContext context1 = new MetaContext();
    MetaContext context2 = new MetaContext();
    for (int i = 0; i < 2; i++) {
      fory1.getSerializationContext().setMetaContext(context1);
      byte[] bytes1 = fory1.serialize(o);
      fory2.getSerializationContext().setMetaContext(context2);
      Object o1 = fory2.deserialize(bytes1);
      Assert.assertEquals(compareHook.apply(o1), compareHook.apply(o));
      fory2.getSerializationContext().setMetaContext(context2);
      byte[] bytes2 = fory2.serialize(o1);
      fory1.getSerializationContext().setMetaContext(context1);
      Object o2 = fory1.deserialize(bytes2);
      Assert.assertEquals(compareHook.apply(o2), compareHook.apply(o));
    }
  }
}
