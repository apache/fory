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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.WeakHashMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.fory.annotation.Expose;
import org.apache.fory.annotation.Ignore;
import org.apache.fory.builder.Generated;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.exception.ForyException;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.exception.SerializationException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.memory.Platform;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.serializer.ArraySerializersTest;
import org.apache.fory.serializer.EnumSerializerTest;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.Struct;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.DateTimeUtils;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ForyTest extends ForyTestBase {
  @DataProvider(name = "languageConfig")
  public static Object[][] languageConfig() {
    return new Object[][] {{Language.JAVA}, {Language.XLANG}};
  }

  @Test(dataProvider = "crossLanguageReferenceTrackingConfig")
  public void primitivesTest(boolean referenceTracking, Language language) {
    Fory fory1 =
        Fory.builder()
            .withLanguage(language)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    Fory fory2 =
        Fory.builder()
            .withLanguage(language)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    assertEquals(true, serDe(fory1, fory2, true));
    assertEquals(Byte.MAX_VALUE, serDe(fory1, fory2, Byte.MAX_VALUE));
    assertEquals(Short.MAX_VALUE, serDe(fory1, fory2, Short.MAX_VALUE));
    assertEquals(Integer.MAX_VALUE, serDe(fory1, fory2, Integer.MAX_VALUE));
    assertEquals(Long.MAX_VALUE, serDe(fory1, fory2, Long.MAX_VALUE));
    assertEquals(Float.MAX_VALUE, serDe(fory1, fory2, Float.MAX_VALUE));
    assertEquals(Double.MAX_VALUE, serDe(fory1, fory2, Double.MAX_VALUE));
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void basicTest(boolean referenceTracking) {
    ForyBuilder builder =
        Fory.builder().withRefTracking(referenceTracking).requireClassRegistration(false);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    assertEquals("str", serDe(fory1, fory2, "str"));
    assertEquals("str", serDe(fory1, fory2, new StringBuilder("str")).toString());
    assertEquals("str", serDe(fory1, fory2, new StringBuffer("str")).toString());
    assertEquals(EnumSerializerTest.EnumFoo.A, serDe(fory1, fory2, EnumSerializerTest.EnumFoo.A));
    assertEquals(EnumSerializerTest.EnumFoo.B, serDe(fory1, fory2, EnumSerializerTest.EnumFoo.B));
    assertEquals(
        EnumSerializerTest.EnumSubClass.A, serDe(fory1, fory2, EnumSerializerTest.EnumSubClass.A));
    assertEquals(
        EnumSerializerTest.EnumSubClass.B, serDe(fory1, fory2, EnumSerializerTest.EnumSubClass.B));
    assertEquals(BigInteger.valueOf(100), serDe(fory1, fory2, BigInteger.valueOf(100)));
    assertEquals(BigDecimal.valueOf(100, 2), serDe(fory1, fory2, BigDecimal.valueOf(100, 2)));
    java.sql.Date sqlDate = new java.sql.Date(System.currentTimeMillis());
    assertEquals(sqlDate, serDe(fory1, fory2, sqlDate));
    LocalDate localDate = LocalDate.now();
    assertEquals(localDate, serDe(fory1, fory2, localDate));
    Date utilDate = new Date();
    assertEquals(utilDate, serDe(fory1, fory2, utilDate));
    Timestamp timestamp = new Timestamp(System.currentTimeMillis());
    assertEquals(timestamp, serDe(fory1, fory2, timestamp));
    Instant instant = DateTimeUtils.truncateInstantToMicros(Instant.now());
    assertEquals(instant, serDe(fory1, fory2, instant));

    ArraySerializersTest.testPrimitiveArray(fory1, fory2);

    assertEquals(Arrays.asList(1, 2), serDe(fory1, fory2, Arrays.asList(1, 2)));
    List<String> arrayList = Arrays.asList("str", "str");
    assertEquals(arrayList, serDe(fory1, fory2, arrayList));
    assertEquals(new LinkedList<>(arrayList), serDe(fory1, fory2, new LinkedList<>(arrayList)));
    assertEquals(new HashSet<>(arrayList), serDe(fory1, fory2, new HashSet<>(arrayList)));
    TreeSet<String> treeSet = new TreeSet<>(Comparator.naturalOrder());
    treeSet.add("str1");
    treeSet.add("str2");
    assertEquals(treeSet, serDe(fory1, fory2, treeSet));

    HashMap<String, Integer> hashMap = new HashMap<>();
    hashMap.put("k1", 1);
    hashMap.put("k2", 2);
    assertEquals(hashMap, serDe(fory1, fory2, hashMap));
    assertEquals(new LinkedHashMap<>(hashMap), serDe(fory1, fory2, new LinkedHashMap<>(hashMap)));
    TreeMap<String, Integer> treeMap = new TreeMap<>(Comparator.naturalOrder());
    treeMap.putAll(hashMap);
    assertEquals(treeMap, serDe(fory1, fory2, treeMap));
    assertEquals(Collections.EMPTY_LIST, serDe(fory1, fory2, Collections.EMPTY_LIST));
    assertEquals(Collections.EMPTY_SET, serDe(fory1, fory2, Collections.EMPTY_SET));
    assertEquals(Collections.EMPTY_MAP, serDe(fory1, fory2, Collections.EMPTY_MAP));
    assertEquals(
        Collections.singletonList("str"), serDe(fory1, fory2, Collections.singletonList("str")));
    assertEquals(Collections.singleton("str"), serDe(fory1, fory2, Collections.singleton("str")));
    assertEquals(
        Collections.singletonMap("k", 1), serDe(fory1, fory2, Collections.singletonMap("k", 1)));
  }

  @Test(dataProvider = "languageConfig")
  public void testSerializationToBuffer(Language language) {
    Fory fory1 = Fory.builder().withLanguage(language).requireClassRegistration(false).build();
    Fory fory2 = Fory.builder().withLanguage(language).requireClassRegistration(false).build();
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    assertSerializationToBuffer(fory1, fory2, buffer);
  }

  @Test(dataProvider = "languageConfig")
  public void testSerializationSlicedBuffer(Language language) {
    Fory fory1 = Fory.builder().withLanguage(language).requireClassRegistration(false).build();
    Fory fory2 = Fory.builder().withLanguage(language).requireClassRegistration(false).build();
    MemoryBuffer buffer0 = MemoryUtils.buffer(64);
    buffer0.writeInt64(-1);
    buffer0.writeInt64(-1);
    buffer0.readInt64();
    buffer0.readInt64();
    MemoryBuffer buffer = buffer0.slice(8);
    assertSerializationToBuffer(fory1, fory2, buffer);
  }

  public void assertSerializationToBuffer(Fory fory1, Fory fory2, MemoryBuffer buffer) {
    if (fory1.isCrossLanguage()) {
      fory1.register(EnumSerializerTest.EnumFoo.class);
      fory2.register(EnumSerializerTest.EnumFoo.class);
      fory1.register(EnumSerializerTest.EnumSubClass.class);
      fory2.register(EnumSerializerTest.EnumSubClass.class);
    }

    assertEquals(true, serDeCheckIndex(fory1, fory2, buffer, true));
    assertEquals(Byte.MAX_VALUE, serDeCheckIndex(fory1, fory2, buffer, Byte.MAX_VALUE));
    assertEquals(Short.MAX_VALUE, serDeCheckIndex(fory1, fory2, buffer, Short.MAX_VALUE));
    assertEquals("str", serDeCheckIndex(fory1, fory2, buffer, "str"));
    assertEquals("str", serDeCheckIndex(fory1, fory2, buffer, new StringBuilder("str")).toString());
    assertEquals(
        EnumSerializerTest.EnumFoo.A,
        serDeCheckIndex(fory1, fory2, buffer, EnumSerializerTest.EnumFoo.A));
    assertEquals(
        EnumSerializerTest.EnumSubClass.A,
        serDeCheckIndex(fory1, fory2, buffer, EnumSerializerTest.EnumSubClass.A));
    assertTrue(
        Arrays.equals(
            new boolean[] {false, true},
            (boolean[]) serDeCheckIndex(fory1, fory2, buffer, new boolean[] {false, true})));
    assertEquals(
        new byte[] {1, 1}, (byte[]) serDeCheckIndex(fory1, fory2, buffer, new byte[] {1, 1}));
    assertEquals(Arrays.asList(1, 2), serDe(fory1, fory2, buffer, Arrays.asList(1, 2)));
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void serializeBeanTest(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    BeanA beanA = BeanA.createBeanA(2);
    byte[] bytes = fory.serialize(beanA);
    Object o = fory.deserialize(bytes);
    assertEquals(beanA, o);
  }

  @Test
  public void testSerializeException() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).withRefTracking(true).build();
    fory.serialize(new Exception());
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void registerTest(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    fory.register(BeanA.class);
    BeanA beanA = BeanA.createBeanA(2);
    assertEquals(beanA, serDe(fory, beanA));
  }

  @EqualsAndHashCode
  static class A implements Serializable {
    public Object f1 = 1;
    public Object f2 = 1;
    private Object f3 = "str";

    Object getF3() {
      return f3;
    }

    void setF3(Object f3) {
      this.f3 = f3;
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testOffHeap(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    long ptr = 0;
    try {
      int size = 1024;
      ptr = Platform.allocateMemory(size);
      MemoryBuffer buffer = fory.serialize(new A(), ptr, size);
      assertNull(buffer.getHeapMemory());

      Object obj = fory.deserialize(ptr, size);
      assertEquals(new A(), obj);
    } finally {
      Platform.freeMemory(ptr);
    }
  }

  public static class Outer {
    private long x;
    private Inner inner;

    private static class Inner {
      int y;
    }
  }

  @Test
  public void testSerializePrivateBean() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCodegen(false)
            .requireClassRegistration(false)
            .build();
    Outer outer = new Outer();
    outer.inner = new Outer.Inner();
    fory.deserialize(fory.serialize(outer));
    assertTrue(fory.getClassResolver().getSerializer(Outer.class) instanceof ObjectSerializer);
    assertTrue(
        fory.getClassResolver().getSerializer(Outer.Inner.class) instanceof ObjectSerializer);
  }

  @Test
  public void testSerializePrivateBeanJIT() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCodegen(true)
            .requireClassRegistration(false)
            .build();
    Outer outer = new Outer();
    outer.inner = new Outer.Inner();
    fory.deserialize(fory.serialize(outer));
    assertTrue(fory.getClassResolver().getSerializer(Outer.class) instanceof Generated);
    assertTrue(fory.getClassResolver().getSerializer(Outer.Inner.class) instanceof Generated);
  }

  @Data
  public static class PackageLevelBean {
    public long f1;
    private long f2;
  }

  @Test
  public void testSerializePackageLevelBean() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCodegen(false)
            .requireClassRegistration(false)
            .build();
    PackageLevelBean o = new PackageLevelBean();
    o.f1 = 10;
    o.f2 = 1;
    serDeCheckSerializer(fory, o, "Object");
  }

  @Test
  public void testSerializePackageLevelBeanJIT() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCodegen(true)
            .requireClassRegistration(false)
            .build();
    PackageLevelBean o = new PackageLevelBean();
    o.f1 = 10;
    o.f2 = 1;
    serDeCheckSerializer(fory, o, "PackageLevelBean");
  }

  static class B {
    int f1;
  }

  static class C extends B {
    int f1;
  }

  @Test(dataProvider = "javaFory")
  public void testDuplicateFields(Fory fory) {
    C c = new C();
    ((B) c).f1 = 100;
    c.f1 = -100;
    assertEquals(((B) c).f1, 100);
    assertEquals(c.f1, -100);
    C newC = (C) serDe(fory, c);
    assertEquals(newC.f1, c.f1);
    assertEquals(((B) newC).f1, ((B) c).f1);
  }

  @Test(dataProvider = "crossLanguageReferenceTrackingConfig")
  public void testGuava(boolean referenceTracking, Language language) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    Assert.assertEquals(serDe(fory, ImmutableList.of(1)), ImmutableList.of(1));
    Assert.assertEquals(serDe(fory, ImmutableList.of(1, 2)), ImmutableList.of(1, 2));
    Assert.assertEquals(serDe(fory, ImmutableList.of(1, 2, "str")), ImmutableList.of(1, 2, "str"));
    Assert.assertEquals(
        serDe(fory, ImmutableMap.of(1, 2, "k", "v")), ImmutableMap.of(1, 2, "k", "v"));
  }

  @Test(dataProvider = "enableCodegen")
  public void testSerializeJDKObject(boolean enableCodegen) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withJdkClassSerializableCheck(false)
            .requireClassRegistration(false)
            .withCodegen(enableCodegen)
            .build();
    StringTokenizer tokenizer = new StringTokenizer("abc,1,23", ",");
    assertEquals(serDe(fory, tokenizer).countTokens(), tokenizer.countTokens());
  }

  @Test
  public void testJDKSerializableCheck() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    serDe(fory, ByteBuffer.allocate(32));
    serDe(fory, ByteBuffer.allocateDirect(32));
    assertThrows(InsecureException.class, () -> fory.serialize(new Thread()));
    assertThrowsCause(
        UnsupportedOperationException.class, () -> fory.serialize(MethodHandles.lookup()));
  }

  @Test
  public void testClassRegistration() {
    Fory fory = Fory.builder().requireClassRegistration(true).build();
    class A {}
    assertThrows(InsecureException.class, () -> fory.serialize(new A()));
    Fory fory1 = Fory.builder().requireClassRegistration(false).build();
    serDe(fory1, new A());
  }

  @Data
  @AllArgsConstructor
  private static class IgnoreFields {
    @Ignore int f1;
    @Ignore long f2;
    long f3;
  }

  @Test
  public void testIgnoreFields() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    IgnoreFields o = serDe(fory, new IgnoreFields(1, 2, 3));
    assertEquals(0, o.f1);
    assertEquals(0, o.f2);
    assertEquals(3, o.f3);
  }

  @Data
  @AllArgsConstructor
  private static class ExposeFields {
    @Expose int f1;
    @Expose long f2;
    long f3;
    @Expose ImmutableMap<String, Integer> map1;
    ImmutableMap<String, Integer> map2;
  }

  @Test
  public void testExposeFields() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    ImmutableMap<String, Integer> map1 = ImmutableMap.of("1", 1);
    ImmutableMap<String, Integer> map2 = ImmutableMap.of("2", 2);
    ExposeFields o = serDe(fory, new ExposeFields(1, 2, 3, map1, map2));
    assertEquals(1, o.f1);
    assertEquals(2, o.f2);
    assertEquals(0, o.f3);
    assertEquals(o.map1, map1);
    assertNull(o.map2);
  }

  @Data
  @AllArgsConstructor
  private static class ExposeFields2 {
    @Expose int f1;
    @Ignore long f2;
    long f3;
  }

  @Test
  public void testExposeFields2() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    assertThrowsCause(RuntimeException.class, () -> serDe(fory, new ExposeFields2(1, 2, 3)));
  }

  @Test(timeOut = 60_000)
  public void testClassGC() {
    WeakHashMap<Object, Boolean> map = new WeakHashMap<>();
    foryGC(map);
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    Descriptor.clearDescriptorCache();
    TestUtils.triggerOOMForSoftGC(
        () -> {
          System.out.printf("Wait map keys %s gc.\n", map.keySet());
          return !map.isEmpty();
        });
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
  }

  private void foryGC(WeakHashMap<Object, Boolean> map) {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    Class<?> structClass1 = Struct.createStructClass("TestClassGC", 1, false);
    System.out.println(structClass1.hashCode());
    Object struct1 = Struct.createPOJO(structClass1);
    serDe(fory, struct1);
    Class<? extends Serializer> serializerClass =
        fory.getClassResolver().getSerializerClass(structClass1);
    assertTrue(serializerClass.getName().contains("Codec"));
    map.put(fory, true);
    System.out.println(fory.hashCode());
    map.put(struct1, true);
    map.put(structClass1, true);
    System.out.println(structClass1.hashCode());
  }

  @Test
  public void testSerializeJavaObject() {
    Fory fory = Fory.builder().requireClassRegistration(false).withLanguage(Language.JAVA).build();
    BeanA beanA = BeanA.createBeanA(2);
    assertEquals(fory.deserializeJavaObject(fory.serializeJavaObject(beanA), BeanA.class), beanA);
    assertEquals(
        fory.deserializeJavaObjectAndClass(fory.serializeJavaObjectAndClass(beanA)), beanA);
    assertEquals(
        fory.deserializeJavaObjectAndClass(
            MemoryBuffer.fromByteArray(fory.serializeJavaObjectAndClass(beanA))),
        beanA);
  }

  @Data
  static class DomainObject {
    UUID id;
  }

  static class UUIDSerializer extends Serializer<UUID> {
    public UUIDSerializer(Fory fory) {
      super(fory, UUID.class);
    }

    @Override
    public UUID read(MemoryBuffer buffer) {
      return new UUID(buffer.readInt64(), buffer.readInt64());
    }

    @Override
    public void write(MemoryBuffer buffer, UUID value) {
      buffer.writeInt64(value.getMostSignificantBits());
      buffer.writeInt64(value.getLeastSignificantBits());
    }
  }

  @Test
  public void testRegisterPrivateSerializer() {
    Fory fory = Fory.builder().withRefTracking(true).requireClassRegistration(false).build();
    fory.registerSerializer(UUID.class, new UUIDSerializer(fory));
    DomainObject obj = new DomainObject();
    obj.id = UUID.randomUUID();
    serDeCheckSerializer(fory, obj, "Codec");
  }

  @Test
  public void testCircularReferenceStackOverflowMessage() {
    class A {
      A f;
    }
    A a = new A();
    a.f = a;
    Fory fory = Fory.builder().withRefTracking(false).requireClassRegistration(false).build();
    try {
      fory.serialize(a);
      throw new IllegalStateException("SerializationException not raised.");
    } catch (SerializationException e) {
      Throwable ex = e.getCause();
      Assert.assertTrue(ex.getMessage().contains("reference"));
    }
  }

  @Test
  public void testPkgAccessLevelParentClass() {
    Fory fory = Fory.builder().withRefTracking(true).requireClassRegistration(false).build();
    HashBasedTable<Object, Object, Object> table = HashBasedTable.create(2, 4);
    table.put("r", "c", 100);
    serDeCheckSerializer(fory, table, "Codec");
  }

  @Data
  static class PrintReadObject {
    public PrintReadObject() {
      throw new RuntimeException();
    }

    public PrintReadObject(boolean b) {}
  }

  @Test
  public void testPrintReadObjectsWhenFailed() {
    Fory fory =
        Fory.builder()
            .withRefTracking(true)
            .withCodegen(false)
            .requireClassRegistration(false)
            .build();
    PrintReadObject o = new PrintReadObject(true);
    try {
      serDe(fory, ImmutableList.of(ImmutableList.of("a", "b"), o));
      Assert.fail();
    } catch (ForyException e) {
      Assert.assertTrue(e.getMessage().contains("[a, b]"));
    }
  }

  @Test
  public void testNullObjSerAndDe() {
    Fory fory =
        Fory.builder()
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withMetaShare(true)
            .build();
    MetaContext metaContext = new MetaContext();
    fory.getSerializationContext().setMetaContext(metaContext);
    byte[] bytes = fory.serializeJavaObjectAndClass(null);
    fory.getSerializationContext().setMetaContext(metaContext);
    Object obj = fory.deserializeJavaObjectAndClass(bytes);
    assertNull(obj);
  }

  @Test
  public void testResetBufferToSizeLimit() {
    final int minBufferBytes = 64;
    final int limitInBytes = 1024;
    Fory fory = Fory.builder().withBufferSizeLimitBytes(limitInBytes).build();

    final byte[] smallPayload = new byte[0];
    final byte[] serializedSmall = fory.serialize(smallPayload);
    assertEquals(fory.getBuffer().size(), minBufferBytes);

    fory.deserialize(serializedSmall);
    assertEquals(fory.getBuffer().size(), minBufferBytes);

    final byte[] largePayload = new byte[limitInBytes * 2];
    final byte[] serializedLarge = fory.serialize(largePayload);
    assertEquals(fory.getBuffer().size(), limitInBytes);

    fory.deserialize(serializedLarge);
    assertEquals(fory.getBuffer().size(), limitInBytes);
  }

  @EqualsAndHashCode
  static class Struct1 {
    int f1;
    String f2;

    public Struct1(int f1, String f2) {
      this.f1 = f1;
      this.f2 = f2;
    }
  }

  static class Struct2 {
    int f1;
    String f2;
    double f3;
  }

  @Test
  public void testStructMapping() {
    ThreadSafeFory fory1 =
        Fory.builder().withCompatibleMode(CompatibleMode.COMPATIBLE).buildThreadSafeFory();
    ThreadSafeFory fory2 =
        Fory.builder().withCompatibleMode(CompatibleMode.COMPATIBLE).buildThreadSafeFory();
    fory1.register(Struct1.class);
    fory2.register(Struct2.class);
    Struct1 struct1 = new Struct1(10, "abc");
    Struct2 struct2 = (Struct2) fory2.deserialize(fory1.serialize(struct1));
    Assert.assertEquals(struct2.f1, struct1.f1);
    Assert.assertEquals(struct2.f2, struct1.f2);
    struct1 = (Struct1) fory1.deserialize(fory2.serialize(struct2));
    Assert.assertEquals(struct1.f1, struct2.f1);
    Assert.assertEquals(struct1.f2, struct2.f2);
  }

  @Test
  public void testDeserializeJavaObjectWrongType() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    Struct1 struct1 = new Struct1(10, "abc");
    byte[] bytes = fory.serializeJavaObject(struct1);
    // first deserialize as Struct1 (correct type)
    Assert.assertEquals(fory.deserializeJavaObject(bytes, Struct1.class), struct1);
    // then deserialize as Struct2 (wrong type)
    Assert.assertThrows(
        DeserializationException.class, () -> fory.deserializeJavaObject(bytes, Struct2.class));
  }

  private Object maxDepthData() {
    List<Object> list = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      List<Object> list1 = new ArrayList<>();
      list1.add("abc");
      list1.add(list);
      list = list1;
    }
    return list;
  }

  @Test
  public void testMaxDepth() {
    byte[] bytes = Fory.builder().requireClassRegistration(false).build().serialize(maxDepthData());
    Fory fory =
        Fory.builder().requireClassRegistration(false).withName("fory1").withMaxDepth(3).build();
    assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
  }

  @AllArgsConstructor
  static class MaxDepth {
    int f1;
    Object f2;
  }

  @Test
  public void testMaxDepthCodegen() {
    assertTrue(TypeUtils.hasExpandableLeafs(MaxDepth.class));
    MaxDepth maxDepth =
        new MaxDepth(
            1, new MaxDepth(2, new MaxDepth(3, new MaxDepth(4, new MaxDepth(5, maxDepthData())))));
    byte[] bytes = Fory.builder().requireClassRegistration(false).build().serialize(maxDepth);
    Fory fory =
        Fory.builder().requireClassRegistration(false).withName("fory2").withMaxDepth(3).build();
    assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
  }
}
