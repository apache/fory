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

package org.apache.fory;

import static org.apache.fory.serializer.collection.MapSerializersTest.createMapFieldsObject;
import static org.testng.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.Charset;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.apache.fory.collection.LazyMap;
import org.apache.fory.serializer.EnumSerializerTest;
import org.apache.fory.serializer.EnumSerializerTest.EnumFoo;
import org.apache.fory.serializer.collection.ChildContainerSerializersTest.ChildArrayDeque;
import org.apache.fory.serializer.collection.SynchronizedSerializersTest;
import org.apache.fory.serializer.collection.UnmodifiableSerializersTest;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.test.bean.CollectionFields;
import org.apache.fory.test.bean.Cyclic;
import org.apache.fory.test.bean.MapFields;
import org.apache.fory.util.DateTimeUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ForyCopyTest extends ForyTestBase {

  private final Fory fory = builder().withRefCopy(true).withCodegen(false).build();

  @Test
  public void immutableObjectCopyTest() {
    primitiveCopyTest();
    assertSame("12ca213@!.3");
    assertSame(EnumSerializerTest.EnumFoo.A);
    assertSame(EnumSerializerTest.EnumFoo.B);
    assertSame(BigInteger.valueOf(100));
    assertSame(BigDecimal.valueOf(100, 2));
    timeCopyTest();
    assertSame(Charset.defaultCharset());
    assertSame(Object.class);
    assertSame(Currency.getInstance(Locale.CHINA));
    assertSame(new Object());
    assertSame(Locale.US);
    assertSame(OptionalInt.of(Integer.MAX_VALUE));
    assertSame(OptionalDouble.of(Double.MAX_VALUE));
    assertSame(OptionalLong.of(Long.MAX_VALUE));
    assertSame(Pattern.compile(""));
    assertSame(URI.create("test"));
    assertSame(new UUID(System.currentTimeMillis(), System.currentTimeMillis()));
    // URL is not handle with URLSerializer. use replaceResolveSerializer
    // assertSame(new URL("https://www.baidu.com"));

    assertSame(Collections.EMPTY_LIST);
    assertSame(Collections.EMPTY_MAP);
    assertSame(Collections.EMPTY_SET);
    assertSame(Collections.emptySortedSet());
    assertSame(Collections.emptySortedMap());
  }

  @Test
  public void mutableObjectCopyTest() {
    collectionCopyTest();
    mapCopyTest();
    objectCopyTest();
    dateCopyTest();
  }

  @Test
  public void threadLocalCopyTest() {
    BeanA beanA = BeanA.createBeanA(2);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicReference<Throwable> ex = new AtomicReference<>();
    ThreadLocalFory threadLocalFory =
        builder().withCodegen(false).withRefCopy(true).buildThreadLocalFory();
    threadLocalFory.setTypeChecker((classResolver, className1) -> true);
    threadLocalFory.setSerializerFactory((fory1, cls) -> null);
    threadLocalFory.register(BeanA.class);
    assetEqualsButNotSame(threadLocalFory.copy(beanA));
    executor.execute(
        () -> {
          try {
            assetEqualsButNotSame(threadLocalFory.copy(beanA));
          } catch (Throwable t) {
            ex.set(t);
          }
        });
    Assert.assertNull(ex.get());
  }

  @Test
  public void threadpoolCopyTest() throws InterruptedException {
    BeanA beanA = BeanA.createBeanA(2);
    AtomicBoolean flag = new AtomicBoolean(false);
    ThreadSafeFory threadSafeFory =
        builder()
            .withRefCopy(true)
            .withCodegen(false)
            .withAsyncCompilation(true)
            .buildThreadSafeForyPool(5, 10);
    for (int i = 0; i < 2000; i++) {
      new Thread(
              () -> {
                for (int j = 0; j < 10; j++) {
                  try {
                    threadSafeFory.setClassLoader(beanA.getClass().getClassLoader());
                    Assert.assertEquals(beanA, threadSafeFory.copy(beanA));
                  } catch (Exception e) {
                    e.printStackTrace();
                    flag.set(true);
                  }
                }
              })
          .start();
    }
    TimeUnit.SECONDS.sleep(5);
    Assert.assertFalse(flag.get());
  }

  private void objectCopyTest() {
    for (int i = 1; i <= 10; i++) {
      BeanA beanA = BeanA.createBeanA(i);
      BeanB beanB = BeanB.createBeanB(i);
      assetEqualsButNotSame(beanA);
      assetEqualsButNotSame(beanB);
    }

    Cyclic cyclic = Cyclic.create(true);
    assetEqualsButNotSame(cyclic);
  }

  private void mapCopyTest() {
    Map<String, Integer> map = new HashMap<>();
    map.put("a", 1);
    map.put("b", 2);
    TreeMap<String, Integer> treeMap = new TreeMap<>(Comparator.naturalOrder());
    treeMap.putAll(map);
    EnumMap<EnumFoo, Object> enumMap = new EnumMap<>(EnumFoo.class);
    enumMap.put(EnumFoo.A, 1);
    LazyMap<String, Integer> lazyMap = new LazyMap<>();
    lazyMap.put("a", 1);

    assetEqualsButNotSame(new HashMap<>(map));
    assetEqualsButNotSame(new LinkedHashMap<>(map));
    assetEqualsButNotSame(treeMap);
    assetEqualsButNotSame(Collections.singletonMap("a", 1));
    assetEqualsButNotSame(new ConcurrentHashMap<>(map));
    assetEqualsButNotSame(new ConcurrentSkipListMap<>(map));
    assetEqualsButNotSame(enumMap);
    assetEqualsButNotSame(lazyMap);
  }

  private void collectionCopyTest() {
    List<String> testData = Arrays.asList("1", "2", "3");
    TreeSet<String> treeSet = new TreeSet<>(Comparator.naturalOrder());
    treeSet.addAll(testData);
    assetEqualsButNotSame(new ArrayList<>(testData));
    assetEqualsButNotSame(testData);
    assetEqualsButNotSame(new LinkedList<>(testData));
    assetEqualsButNotSame(new HashSet<>(testData));
    assetEqualsButNotSame(new LinkedHashSet<>(testData));
    assetEqualsButNotSame(treeSet);
    assetEqualsButNotSame(new CopyOnWriteArrayList<>(testData));
    assetEqualsButNotSame(new ConcurrentSkipListSet<>(testData));
    assetEqualsButNotSame(Collections.newSetFromMap(new HashMap<>()));
    assetEqualsButNotSame(ConcurrentHashMap.newKeySet(10));
    assetEqualsButNotSame(new Vector<>(testData));
    assetEqualsButNotSame(EnumSet.of(EnumSerializerTest.EnumFoo.A, EnumSerializerTest.EnumFoo.B));
    assetEqualsButNotSame(BitSet.valueOf(new byte[] {1, 2, 3}));
    assetEqualsButNotSame(Collections.singleton(1));
    assetEqualsButNotSame(Collections.singletonList(1));

    ImmutableList<String> data = ImmutableList.copyOf(testData);
    ChildArrayDeque<String> list = new ChildArrayDeque<>();
    list.addAll(data);
    Assert.assertEquals(data, ImmutableList.copyOf(fory.copy(list)));
    Assert.assertEquals(data, ImmutableList.sortedCopyOf(new PriorityQueue<>(data)));
  }

  private void primitiveCopyTest() {
    assertSame(Boolean.TRUE);
    assertSame(Byte.MAX_VALUE);
    assertSame(Short.MAX_VALUE);
    assertSame(Character.MAX_VALUE);
    assertSame(Integer.MAX_VALUE);
    assertSame(Long.MAX_VALUE);
    assertSame(Float.MAX_VALUE);
    assertSame(Double.MAX_VALUE);
  }

  private void timeCopyTest() {
    assertSame(LocalDate.now());
    assertSame(LocalTime.now());
    assertSame(LocalDateTime.now());
    assertSame(DateTimeUtils.truncateInstantToMicros(Instant.now()));
    assertSame(Duration.ofDays(10));
    assertSame(ZoneOffset.MAX);
    assertSame(ZonedDateTime.now());
    assertSame(Year.now());
    assertSame(YearMonth.now());
    assertSame(MonthDay.now());
    assertSame(Period.ofDays(10));
    assertSame(OffsetTime.now());
    assertSame(OffsetDateTime.now());
  }

  private void dateCopyTest() {
    assetEqualsButNotSame(new java.util.Date());
    assetEqualsButNotSame(new java.sql.Date(System.currentTimeMillis()));
    assetEqualsButNotSame(new Time(System.currentTimeMillis()));
    assetEqualsButNotSame(new Timestamp(System.currentTimeMillis()));
    assetEqualsButNotSame(Calendar.getInstance());
    assetEqualsButNotSame(TimeZone.getDefault());
  }

  private void assertSame(Object obj) {
    Assert.assertSame(obj, fory.copy(obj));
  }

  private void assetEqualsButNotSame(Object obj) {
    Object newObj = fory.copy(obj);
    Assert.assertEquals(obj, newObj);
    Assert.assertNotSame(obj, newObj);
  }

  public static class A {
    private String name;
    private B b;
    private List<Integer> list = new ArrayList<>();

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public B getB() {
      return b;
    }

    public void setB(B b) {
      this.b = b;
    }

    public List<Integer> getList() {
      return list;
    }

    public void setList(List<Integer> list) {
      this.list = list;
    }

    @Override
    public String toString() {
      return "A{" + "name='" + name + '\'' + ", list=" + list + '}';
    }
  }

  public static class B {
    private String name;
    private A a;
    private List<Integer> list = new ArrayList<>();

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public A getA() {
      return a;
    }

    public void setA(A a) {
      this.a = a;
    }

    public List<Integer> getList() {
      return list;
    }

    public void setList(List<Integer> list) {
      this.list = list;
    }

    @Override
    public String toString() {
      return "B{" + "name='" + name + '\'' + ", list=" + list + '}';
    }
  }

  @Test
  public void testCircularRefCopy() {
    Cyclic cyclic = Cyclic.create(true);
    Fory fory = builder().withRefTracking(true).withRefCopy(true).build();
    assertEquals(fory.copy(cyclic), cyclic);
  }

  @Test
  public void testComplexMapCopy() {
    Fory fory = builder().withRefTracking(true).withRefCopy(true).build();
    {
      MapFields mapFields = UnmodifiableSerializersTest.createMapFields();
      assertEquals(fory.copy(mapFields), mapFields);
    }
    {
      MapFields obj = createMapFieldsObject();
      assertEquals(fory.copy(obj), obj);
    }
  }

  @Test
  public void testComplexCollectionCopy() {
    Fory fory = builder().withRefTracking(true).withRefCopy(true).build();
    {
      CollectionFields collectionFields = SynchronizedSerializersTest.createCollectionFields();
      assertEquals(fory.copy(collectionFields).toCanEqual(), collectionFields.toCanEqual());
    }
    {
      CollectionFields collectionFields = UnmodifiableSerializersTest.createCollectionFields();
      assertEquals(fory.copy(collectionFields).toCanEqual(), collectionFields.toCanEqual());
    }
  }
}
