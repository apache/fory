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

package org.apache.fory.resolver;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Primitives;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.builder.Generated;
import org.apache.fory.config.Language;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.longlongpkg.C1;
import org.apache.fory.resolver.longlongpkg.C2;
import org.apache.fory.resolver.longlongpkg.C3;
import org.apache.fory.serializer.CompatibleSerializer;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.serializer.collection.CollectionSerializer;
import org.apache.fory.serializer.collection.CollectionSerializers;
import org.apache.fory.serializer.collection.MapSerializers;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.type.TypeUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ClassResolverTest extends ForyTestBase {
  private static final Logger LOG = LoggerFactory.getLogger(ClassResolverTest.class);

  @Test
  public void testPrimitivesClassId() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    ClassResolver classResolver = fory.getClassResolver();
    for (List<Class<?>> classes :
        ImmutableList.of(
            TypeUtils.getSortedPrimitiveClasses(), TypeUtils.getSortedBoxedClasses())) {
      for (int i = 0; i < classes.size() - 1; i++) {
        assertEquals(
            classResolver.getRegisteredClassId(classes.get(i)) + 1,
            classResolver.getRegisteredClassId(classes.get(i + 1)).shortValue());
        assertTrue(classResolver.getRegisteredClassId(classes.get(i)) > 0);
      }
      assertEquals(
          classResolver.getRegisteredClassId(classes.get(classes.size() - 2)) + 1,
          classResolver.getRegisteredClassId(classes.get(classes.size() - 1)).shortValue());
      assertTrue(classResolver.getRegisteredClassId(classes.get(classes.size() - 1)) > 0);
    }
  }

  @Test
  public void testRegisterClassByName() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(true).build();
    ClassResolver classResolver = fory.getClassResolver();
    classResolver.register(C1.class, "ns", "C1");
    Assert.assertThrows(
        IllegalArgumentException.class, () -> classResolver.register(C1.class, "ns", "C1"));
    Assert.assertThrows(
        IllegalArgumentException.class, () -> classResolver.register(C1.class, 200));
    classResolver.register(C2.class, "", "C2");
    classResolver.register(Foo.class, "ns", "Foo");

    Assert.assertTrue(fory.serialize(C1.class).length < 12);
    serDeCheck(fory, C1.class);

    Assert.assertTrue(fory.serialize(C2.class).length < 12);
    serDeCheck(fory, C2.class);

    Foo foo = new Foo();
    foo.f1 = 10;
    serDeCheck(fory, foo);
  }

  @Test
  public void testRegisterClass() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
  }

  @Test
  public void testGetSerializerClass() throws ClassNotFoundException {
    {
      Fory fory =
          Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
      // serialize class first will create a class info with serializer null.
      serDeCheck(fory, BeanB.class);
      Assert.assertTrue(
          Generated.GeneratedSerializer.class.isAssignableFrom(
              fory.getClassResolver().getSerializerClass(BeanB.class)));
      // ensure serialize class first won't make object fail to serialize.
      serDeCheck(fory, BeanB.createBeanB(2));
    }
    {
      Fory fory =
          Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
      serDeCheck(fory, new Object[] {BeanB.class, BeanB.createBeanB(2)});
    }
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    ClassResolver classResolver = fory.getClassResolver();
    assertEquals(
        classResolver.getSerializerClass(ArrayList.class),
        CollectionSerializers.ArrayListSerializer.class);
    assertEquals(
        classResolver.getSerializerClass(Arrays.asList(1, 2).getClass()),
        CollectionSerializers.ArraysAsListSerializer.class);
    assertEquals(classResolver.getSerializerClass(LinkedList.class), CollectionSerializer.class);

    assertEquals(
        classResolver.getSerializerClass(HashSet.class),
        CollectionSerializers.HashSetSerializer.class);
    assertEquals(
        classResolver.getSerializerClass(LinkedHashSet.class),
        CollectionSerializers.LinkedHashSetSerializer.class);
    assertEquals(
        classResolver.getSerializerClass(TreeSet.class),
        CollectionSerializers.SortedSetSerializer.class);

    assertEquals(
        classResolver.getSerializerClass(HashMap.class), MapSerializers.HashMapSerializer.class);
    assertEquals(
        classResolver.getSerializerClass(LinkedHashMap.class),
        MapSerializers.LinkedHashMapSerializer.class);
    assertEquals(
        classResolver.getSerializerClass(TreeMap.class), MapSerializers.SortedMapSerializer.class);

    assertEquals(
        classResolver.getSerializerClass(ArrayBlockingQueue.class),
        CollectionSerializers.ArrayBlockingQueueSerializer.class);
    assertEquals(
        classResolver.getSerializerClass(ConcurrentHashMap.class),
        MapSerializers.ConcurrentHashMapSerializer.class);
    assertEquals(
        classResolver.getSerializerClass(
            Class.forName("org.apache.fory.serializer.collection.CollectionContainer")),
        CollectionSerializers.DefaultJavaCollectionSerializer.class);
    assertEquals(
        classResolver.getSerializerClass(
            Class.forName("org.apache.fory.serializer.collection.MapContainer")),
        MapSerializers.DefaultJavaMapSerializer.class);
  }

  interface Interface1 {}

  interface Interface2 {}

  @Test
  public void testSerializeClassesShared() {
    Fory fory = builder().build();
    serDeCheck(fory, Foo.class);
    serDeCheck(fory, Arrays.asList(Foo.class, Foo.class));
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testSerializeClasses(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .build();
    Primitives.allPrimitiveTypes()
        .forEach(cls -> assertSame(cls, fory.deserialize(fory.serialize(cls))));
    Primitives.allWrapperTypes()
        .forEach(cls -> assertSame(cls, fory.deserialize(fory.serialize(cls))));
    assertSame(Class.class, fory.deserialize(fory.serialize(Class.class)));
    assertSame(Fory.class, fory.deserialize(fory.serialize(Fory.class)));
    List<Class<?>> classes =
        Arrays.asList(getClass(), getClass(), Foo.class, Foo.class, Bar.class, Bar.class);
    serDeCheck(fory, classes);
    serDeCheck(
        fory,
        Arrays.asList(Interface1.class, Interface1.class, Interface2.class, Interface2.class));
  }

  @Test
  public void testWriteClassName() {
    {
      Fory fory =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .withRefTracking(true)
              .requireClassRegistration(false)
              .build();
      ClassResolver classResolver = fory.getClassResolver();
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      classResolver.writeClassInternal(buffer, getClass());
      int writerIndex = buffer.writerIndex();
      classResolver.writeClassInternal(buffer, getClass());
      Assert.assertEquals(buffer.writerIndex(), writerIndex + 2);
      buffer.writerIndex(0);
    }
    {
      Fory fory =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .withRefTracking(true)
              .requireClassRegistration(false)
              .build();
      ClassResolver classResolver = fory.getClassResolver();
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      classResolver.writeClassAndUpdateCache(buffer, getClass());
      classResolver.writeClassAndUpdateCache(buffer, getClass());
      Assert.assertSame(classResolver.readClassInfo(buffer).getCls(), getClass());
      Assert.assertSame(classResolver.readClassInfo(buffer).getCls(), getClass());
      classResolver.reset();
      buffer.writerIndex(0);
      buffer.readerIndex(0);
      List<org.apache.fory.test.bean.Foo> fooList =
          Arrays.asList(
              org.apache.fory.test.bean.Foo.create(), org.apache.fory.test.bean.Foo.create());
      Assert.assertEquals(fory.deserialize(fory.serialize(fooList)), fooList);
      Assert.assertEquals(fory.deserialize(fory.serialize(fooList)), fooList);
    }
  }

  @Test
  public void testWriteClassNamesInSamePackage() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    fory.writeRef(buffer, C1.class);
    fory.writeRef(buffer, C2.class);
    fory.writeRef(buffer, C3.class);
    int len1 = C1.class.getName().getBytes(StandardCharsets.UTF_8).length;
    LOG.info("SomeClass1 {}", len1);
    LOG.info("buffer.writerIndex {}", buffer.writerIndex());
    Assert.assertTrue(buffer.writerIndex() < (3 + 8 + 3 + len1) * 3);
  }

  @Data
  static class Foo {
    int f1;
  }

  @EqualsAndHashCode(callSuper = true)
  @ToString
  static class Bar extends Foo {
    long f2;
  }

  @Test
  public void testClassRegistrationInit() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).withCodegen(false).build();
    serDeCheck(fory, new HashMap<>(ImmutableMap.of("a", 1, "b", 2)));
  }

  private enum TestNeedToWriteReferenceClass {
    A,
    B
  }

  @Test
  public void testNeedToWriteReference() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    ClassResolver classResolver = fory.getClassResolver();
    Assert.assertFalse(
        classResolver.needToWriteRef(TypeRef.of(TestNeedToWriteReferenceClass.class)));
    assertNull(classResolver.getClassInfo(TestNeedToWriteReferenceClass.class, false));
  }

  @Test
  public void testSetSerializer() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    ClassResolver classResolver = fory.getClassResolver();
    {
      classResolver.setSerializer(Foo.class, new ObjectSerializer<>(fory, Foo.class));
      ClassInfo classInfo = classResolver.getClassInfo(Foo.class);
      assertSame(classInfo.getSerializer().getClass(), ObjectSerializer.class);
      classResolver.setSerializer(Foo.class, new CompatibleSerializer<>(fory, Foo.class));
      Assert.assertSame(classResolver.getClassInfo(Foo.class), classInfo);
      assertSame(classInfo.getSerializer().getClass(), CompatibleSerializer.class);
    }
    {
      classResolver.register(Bar.class);
      ClassInfo classInfo = classResolver.getClassInfo(Bar.class);
      classResolver.setSerializer(Bar.class, new ObjectSerializer<>(fory, Bar.class));
      Assert.assertSame(classResolver.getClassInfo(Bar.class), classInfo);
      assertSame(classInfo.getSerializer().getClass(), ObjectSerializer.class);
      classResolver.setSerializer(Bar.class, new CompatibleSerializer<>(fory, Bar.class));
      Assert.assertSame(classResolver.getClassInfo(Bar.class), classInfo);
      assertSame(classInfo.getSerializer().getClass(), CompatibleSerializer.class);
    }
  }

  private static class ErrorSerializer extends Serializer<Foo> {
    public ErrorSerializer(Fory fory) {
      super(fory, Foo.class);
      fory.getClassResolver().setSerializer(Foo.class, this);
      throw new RuntimeException();
    }
  }

  @Test
  public void testResetSerializer() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    ClassResolver classResolver = fory.getClassResolver();
    Assert.assertThrows(() -> Serializers.newSerializer(fory, Foo.class, ErrorSerializer.class));
    Assert.assertNull(classResolver.getSerializer(Foo.class, false));
    Assert.assertThrows(
        () -> classResolver.createSerializerSafe(Foo.class, () -> new ErrorSerializer(fory)));
    Assert.assertNull(classResolver.getSerializer(Foo.class, false));
  }

  @Test
  public void testPrimitive() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).build();
    ClassResolver classResolver = fory.getClassResolver();
    Assert.assertTrue(classResolver.isPrimitive(classResolver.getRegisteredClassId(void.class)));
    Assert.assertTrue(classResolver.isPrimitive(classResolver.getRegisteredClassId(boolean.class)));
    Assert.assertTrue(classResolver.isPrimitive(classResolver.getRegisteredClassId(byte.class)));
    Assert.assertTrue(classResolver.isPrimitive(classResolver.getRegisteredClassId(short.class)));
    Assert.assertTrue(classResolver.isPrimitive(classResolver.getRegisteredClassId(char.class)));
    Assert.assertTrue(classResolver.isPrimitive(classResolver.getRegisteredClassId(int.class)));
    Assert.assertTrue(classResolver.isPrimitive(classResolver.getRegisteredClassId(long.class)));
    Assert.assertTrue(classResolver.isPrimitive(classResolver.getRegisteredClassId(float.class)));
    Assert.assertTrue(classResolver.isPrimitive(classResolver.getRegisteredClassId(double.class)));
    Assert.assertFalse(classResolver.isPrimitive(classResolver.getRegisteredClassId(String.class)));
    Assert.assertFalse(classResolver.isPrimitive(classResolver.getRegisteredClassId(Date.class)));
  }

  // without static for test
  class FooCustomSerializer extends Serializer<Foo> {

    public FooCustomSerializer(Fory fory, Class<Foo> type) {
      super(fory, type);
    }

    @Override
    public void write(MemoryBuffer buffer, Foo value) {
      buffer.writeInt32(value.f1);
    }

    @Override
    public Foo read(MemoryBuffer buffer) {
      final Foo foo = new Foo();
      foo.f1 = buffer.readInt32();
      return foo;
    }
  }

  @Test
  public void testFooCustomSerializer() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).build();
    Assert.assertThrows(() -> fory.registerSerializer(Foo.class, FooCustomSerializer.class));
    fory.registerSerializer(Foo.class, f -> new FooCustomSerializer(f, Foo.class));
    final Foo foo = new Foo();
    foo.setF1(100);

    Assert.assertEquals(foo, serDe(fory, foo));
    Assert.assertEquals(
        fory.getClassResolver().getSerializer(foo.getClass()).getClass(),
        FooCustomSerializer.class);
  }

  interface ITest {
    int getF1();

    void setF1(int f1);
  }

  @ToString
  @EqualsAndHashCode
  static class ImplTest implements ITest {
    int f1;

    @Override
    public int getF1() {
      return f1;
    }

    @Override
    public void setF1(int f1) {
      this.f1 = f1;
    }
  }

  static class InterfaceCustomSerializer extends Serializer<ITest> {

    public InterfaceCustomSerializer(Fory fory, Class<ITest> type) {
      super(fory, type);
    }

    @Override
    public void write(MemoryBuffer buffer, ITest value) {
      buffer.writeInt32(value.getF1());
    }

    @Override
    public ITest read(MemoryBuffer buffer) {
      final ITest iTest = new ImplTest();
      iTest.setF1(buffer.readInt32());
      return iTest;
    }
  }

  @Test
  public void testInterfaceCustomSerializer() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.registerSerializer(ITest.class, new InterfaceCustomSerializer(fory, ITest.class));
    final ITest iTest = new ImplTest();
    iTest.setF1(100);

    Assert.assertEquals(iTest, serDe(fory, iTest));
    Assert.assertEquals(
        fory.getClassResolver().getSerializer(iTest.getClass()).getClass(),
        InterfaceCustomSerializer.class);

    fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.register(ITest.class);
    Assert.assertNotEquals(
        fory.getClassResolver().getSerializer(ImplTest.class).getClass(),
        InterfaceCustomSerializer.class);

    fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.registerSerializer(ITest.class, new InterfaceCustomSerializer(fory, ITest.class));
    Assert.assertEquals(
        fory.getClassResolver().getSerializer(ImplTest.class).getClass(),
        InterfaceCustomSerializer.class);
  }

  @Test
  public void testRegisterAbstractClass() {}

  @Data
  abstract static class AbsTest {
    int f1;
  }

  @EqualsAndHashCode(callSuper = true)
  @ToString
  static class SubAbsTest extends AbsTest {
    long f2;
  }

  @EqualsAndHashCode(callSuper = true)
  @ToString
  static class Sub2AbsTest extends SubAbsTest {
    Object f3;
  }

  static class AbstractCustomSerializer extends Serializer<AbsTest> {

    public AbstractCustomSerializer(Fory fory, Class<AbsTest> type) {
      super(fory, type);
    }

    @Override
    public void write(MemoryBuffer buffer, AbsTest value) {
      buffer.writeInt32(value.getF1());
    }

    @Override
    public AbsTest read(MemoryBuffer buffer) {
      // TODO maybe new SubAbsTest or Sub2AbsTest
      final AbsTest absTest = new SubAbsTest();
      absTest.setF1(buffer.readInt32());
      return absTest;
    }
  }

  @Test
  public void testAbstractCustomSerializer() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.registerSerializer(AbsTest.class, new AbstractCustomSerializer(fory, AbsTest.class));
    final AbsTest absTest = new SubAbsTest();
    absTest.setF1(100);

    Assert.assertEquals(absTest, serDe(fory, absTest));
    Assert.assertEquals(
        fory.getClassResolver().getSerializer(absTest.getClass()).getClass(),
        AbstractCustomSerializer.class);

    final AbsTest abs2Test = new Sub2AbsTest();
    abs2Test.setF1(100);

    Assert.assertEquals(abs2Test.getF1(), serDe(fory, abs2Test).getF1());
    Assert.assertEquals(
        fory.getClassResolver().getSerializer(abs2Test.getClass()).getClass(),
        AbstractCustomSerializer.class);
  }
}
