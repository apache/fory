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
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;

import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Primitives;
import java.lang.reflect.Method;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.builder.Generated;
import org.apache.fory.config.Config;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.longlongpkg.C1;
import org.apache.fory.resolver.longlongpkg.C2;
import org.apache.fory.resolver.longlongpkg.C3;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.serializer.StringSerializer;
import org.apache.fory.serializer.collection.CollectionSerializer;
import org.apache.fory.serializer.collection.CollectionSerializers;
import org.apache.fory.serializer.collection.MapSerializers;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ClassResolverTest extends ForyTestBase {
  private static final Logger LOG = LoggerFactory.getLogger(ClassResolverTest.class);

  @Test
  public void testPrimitivesClassId() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
    // Test that primitive types have consecutive IDs
    List<Class<?>> primitiveClasses = TypeUtils.getSortedPrimitiveClasses();
    for (int i = 0; i < primitiveClasses.size() - 1; i++) {
      assertEquals(
          classResolver.getRegisteredClassId(primitiveClasses.get(i)) + 1,
          classResolver.getRegisteredClassId(primitiveClasses.get(i + 1)).shortValue());
      assertTrue(classResolver.getRegisteredClassId(primitiveClasses.get(i)) > 0);
    }
    assertTrue(
        classResolver.getRegisteredClassId(primitiveClasses.get(primitiveClasses.size() - 1)) > 0);
    // Test that boxed types all have valid positive IDs
    // Note: boxed types are no longer consecutive due to unsigned type IDs being added
    List<Class<?>> boxedClasses = TypeUtils.getSortedBoxedClasses();
    for (Class<?> boxedClass : boxedClasses) {
      assertTrue(classResolver.getRegisteredClassId(boxedClass) > 0);
    }
  }

  @Test
  public void testRegisterClassByName() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(true).build();
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
    classResolver.register(C1.class, "ns", "C1");
    Assert.assertThrows(
        IllegalArgumentException.class, () -> classResolver.register(C1.class, "ns", "C1"));
    Assert.assertThrows(
        IllegalArgumentException.class, () -> classResolver.registerInternal(C1.class, 200));
    classResolver.register(C2.class, "", "C2");
    classResolver.register(Foo.class, "ns", "Foo");

    byte[] serialized = fory.serialize(C1.class);
    Assert.assertTrue(serialized.length < 13, "Serialize length " + serialized.length);
    serDeCheck(fory, C1.class);

    serialized = fory.serialize(C2.class);
    Assert.assertTrue(serialized.length < 13, "Serialize length " + serialized.length);
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
  public void testRegisterClassWithUserIds() {
    // Test that user IDs 0 and 1 work correctly with separated user type IDs.
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(true).build();
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();

    // Register with user ID 0
    classResolver.register(Foo.class, 0);
    // Register with user ID 1
    classResolver.register(Bar.class, 1);

    // Verify registered IDs are user IDs and type IDs are internal types only.
    assertEquals(classResolver.getRegisteredClassId(Foo.class).shortValue(), (short) 0);
    assertEquals(classResolver.getRegisteredClassId(Bar.class).shortValue(), (short) 1);
    TypeInfo fooTypeInfo = classResolver.getTypeInfo(Foo.class);
    TypeInfo barTypeInfo = classResolver.getTypeInfo(Bar.class);
    assertEquals(fooTypeInfo.getUserTypeId(), 0);
    assertEquals(barTypeInfo.getUserTypeId(), 1);
    int fooTypeId = fooTypeInfo.getTypeId();
    int barTypeId = barTypeInfo.getTypeId();
    assertTrue(fooTypeId == Types.STRUCT || fooTypeId == Types.COMPATIBLE_STRUCT);
    assertTrue(barTypeId == Types.STRUCT || barTypeId == Types.COMPATIBLE_STRUCT);

    // Verify serialization/deserialization works
    Foo foo = new Foo();
    foo.f1 = 42;
    serDeCheck(fory, foo);

    Bar bar = new Bar();
    bar.f1 = 10;
    bar.f2 = 100L;
    serDeCheck(fory, bar);
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
              fory.getTypeResolver().getSerializerClass(BeanB.class)));
      // ensure serialize class first won't make object fail to serialize.
      serDeCheck(fory, BeanB.createBeanB(2));
    }
    {
      Fory fory =
          Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
      serDeCheck(fory, new Object[] {BeanB.class, BeanB.createBeanB(2)});
    }
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
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

  @Test
  public void testSharedRegistrySharesTypeDefCachesAcrossForyInstances() {
    ForyBuilder builder =
        Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory1 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    Fory fory2 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);

    ClassResolver resolver1 = (ClassResolver) fory1.getTypeResolver();
    ClassResolver resolver2 = (ClassResolver) fory2.getTypeResolver();

    assertSame(resolver1.typeDefMap, resolver2.typeDefMap);
    assertSame(
        resolver1.extRegistry.currentLayerTypeDef, resolver2.extRegistry.currentLayerTypeDef);
    assertSame(resolver1.extRegistry.descriptorsCache, resolver2.extRegistry.descriptorsCache);
    assertSame(resolver1.extRegistry.codeGeneratorMap, resolver2.extRegistry.codeGeneratorMap);

    TypeDef first = resolver1.getTypeDef(BeanB.class, true);
    TypeDef second = resolver2.getTypeDef(BeanB.class, true);
    assertSame(first, second);
    assertNotSame(resolver1, resolver2);
  }

  @Test
  public void testSharedRegistryCachesTypeDefByIdButKeepsTypeInfoLocal() {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withMetaShare(true);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory1 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    Fory fory2 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);

    ClassResolver resolver1 = (ClassResolver) fory1.getTypeResolver();
    ClassResolver resolver2 = (ClassResolver) fory2.getTypeResolver();
    TypeDef typeDef = resolver1.getTypeDef(BeanB.class, true);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    typeDef.writeTypeDef(buffer);

    buffer.readerIndex(0);
    TypeDef canonicalTypeDef1 = resolver1.readTypeDef(buffer, buffer.readInt64());
    buffer.readerIndex(0);
    TypeDef canonicalTypeDef2 = resolver2.readTypeDef(buffer, buffer.readInt64());

    assertSame(sharedRegistry.typeDefById.get(typeDef.getId()), canonicalTypeDef1);
    assertSame(canonicalTypeDef1, canonicalTypeDef2);
    assertNull(resolver1.extRegistry.typeInfoByTypeDefId.get(typeDef.getId()));
    assertNull(resolver2.extRegistry.typeInfoByTypeDefId.get(typeDef.getId()));

    TypeInfo typeInfo1 = resolver1.buildMetaSharedTypeInfo(canonicalTypeDef1);
    TypeInfo typeInfo2 = resolver2.buildMetaSharedTypeInfo(canonicalTypeDef2);

    assertSame(resolver1.extRegistry.typeInfoByTypeDefId.get(typeDef.getId()), typeInfo1);
    assertSame(resolver2.extRegistry.typeInfoByTypeDefId.get(typeDef.getId()), typeInfo2);
    assertNotSame(typeInfo1, typeInfo2);
  }

  @Test
  public void testSharedRegistrySharesThreadSafeSerializersOnly() {
    ForyBuilder builder =
        Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory1 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    Fory fory2 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);

    ClassResolver resolver1 = (ClassResolver) fory1.getTypeResolver();
    ClassResolver resolver2 = (ClassResolver) fory2.getTypeResolver();

    TypeInfo classInfo1 = resolver1.getTypeInfo(Class.class);
    TypeInfo classInfo2 = resolver2.getTypeInfo(Class.class);
    assertNotSame(classInfo1, classInfo2);
    assertSame(classInfo1.getSerializer(), classInfo2.getSerializer());
    assertSame(
        sharedRegistry.getSerializer(Class.class, Serializers.ClassSerializer.class),
        classInfo1.getSerializer());

    TypeInfo stringInfo1 = resolver1.getTypeInfo(String.class);
    TypeInfo stringInfo2 = resolver2.getTypeInfo(String.class);
    assertNotSame(stringInfo1, stringInfo2);
    assertNotSame(stringInfo1.getSerializer(), stringInfo2.getSerializer());
    assertNull(sharedRegistry.getSerializer(String.class, StringSerializer.class));

    resolver1.register(ThreadSafeRegisteredType.class, 2048L);
    resolver1.registerSerializer(
        ThreadSafeRegisteredType.class, ThreadSafeRegisteredTypeSerializer.class);
    resolver2.register(ThreadSafeRegisteredType.class, 2048L);
    resolver2.registerSerializer(
        ThreadSafeRegisteredType.class, ThreadSafeRegisteredTypeSerializer.class);

    TypeInfo registeredInfo1 = resolver1.getTypeInfo(ThreadSafeRegisteredType.class);
    TypeInfo registeredInfo2 = resolver2.getTypeInfo(ThreadSafeRegisteredType.class);
    assertNotSame(registeredInfo1, registeredInfo2);
    assertSame(registeredInfo1.getSerializer(), registeredInfo2.getSerializer());
    assertSame(
        sharedRegistry.getSerializer(
            ThreadSafeRegisteredType.class, ThreadSafeRegisteredTypeSerializer.class),
        registeredInfo1.getSerializer());
  }

  @Test
  public void testSharedRegistryReusesThreadSafeSerializerWithoutReconstruction() {
    CountingThreadSafeRegisteredTypeSerializer.constructorCalls.set(0);
    ForyBuilder builder =
        Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory1 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    Fory fory2 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);

    ClassResolver resolver1 = (ClassResolver) fory1.getTypeResolver();
    ClassResolver resolver2 = (ClassResolver) fory2.getTypeResolver();

    resolver1.register(CountingThreadSafeRegisteredType.class, 2049L);
    resolver1.registerSerializer(
        CountingThreadSafeRegisteredType.class, CountingThreadSafeRegisteredTypeSerializer.class);
    assertEquals(CountingThreadSafeRegisteredTypeSerializer.constructorCalls.get(), 1);

    resolver2.register(CountingThreadSafeRegisteredType.class, 2049L);
    resolver2.registerSerializer(
        CountingThreadSafeRegisteredType.class, CountingThreadSafeRegisteredTypeSerializer.class);
    assertEquals(CountingThreadSafeRegisteredTypeSerializer.constructorCalls.get(), 1);

    Serializer<?> sharedSerializer =
        sharedRegistry.getSerializer(
            CountingThreadSafeRegisteredType.class, CountingThreadSafeRegisteredTypeSerializer.class);
    assertSame(sharedSerializer, resolver1.getTypeInfo(CountingThreadSafeRegisteredType.class).getSerializer());
    assertSame(sharedSerializer, resolver2.getTypeInfo(CountingThreadSafeRegisteredType.class).getSerializer());
    assertNotSame(
        resolver1.getTypeInfo(CountingThreadSafeRegisteredType.class),
        resolver2.getTypeInfo(CountingThreadSafeRegisteredType.class));
  }

  @Test
  public void testSharedRegistryCachesFieldDescriptorsAndDescriptorGrouper() {
    ForyBuilder builder =
        Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory1 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    Fory fory2 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);

    ClassResolver resolver1 = (ClassResolver) fory1.getTypeResolver();
    ClassResolver resolver2 = (ClassResolver) fory2.getTypeResolver();
    resolver1.finishRegistration();
    resolver2.finishRegistration();

    List<Descriptor> descriptors1 = resolver1.getFieldDescriptors(BeanB.class, true);
    List<Descriptor> descriptors2 = resolver2.getFieldDescriptors(BeanB.class, true);
    assertSame(descriptors1, descriptors2);

    DescriptorGrouper grouper1 = resolver1.getFieldDescriptorGrouper(BeanB.class, true, false);
    DescriptorGrouper grouper2 = resolver2.getFieldDescriptorGrouper(BeanB.class, true, false);
    assertSame(grouper1, grouper2);
    assertEquals(grouper1.getSortedDescriptors(), grouper2.getSortedDescriptors());

    Function<Descriptor, Descriptor> descriptorUpdator = Function.identity();
    DescriptorGrouper updatedGrouper1 =
        resolver1.getFieldDescriptorGrouper(BeanB.class, true, false, descriptorUpdator);
    DescriptorGrouper updatedGrouper2 =
        resolver2.getFieldDescriptorGrouper(BeanB.class, true, false, descriptorUpdator);
    assertSame(updatedGrouper1, updatedGrouper2);
    assertEquals(updatedGrouper1.getSortedDescriptors(), updatedGrouper2.getSortedDescriptors());
  }

  @Test
  public void testSharedRegistryCachesTypeDefDescriptorsAndDescriptorGrouperBySemanticKey() {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withMetaShare(true)
            .requireClassRegistration(false);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory1 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    Fory fory2 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);

    ClassResolver resolver1 = (ClassResolver) fory1.getTypeResolver();
    ClassResolver resolver2 = (ClassResolver) fory2.getTypeResolver();
    resolver1.finishRegistration();
    resolver2.finishRegistration();

    TypeDef canonicalTypeDef = resolver1.getTypeDef(BeanB.class, true);
    MemoryBuffer buffer1 = MemoryBuffer.newHeapBuffer(256);
    canonicalTypeDef.writeTypeDef(buffer1);
    TypeDef typeDef1 = TypeDef.readTypeDef(fory1.getTypeResolver(), buffer1);
    MemoryBuffer buffer2 = MemoryBuffer.newHeapBuffer(256);
    canonicalTypeDef.writeTypeDef(buffer2);
    TypeDef typeDef2 = TypeDef.readTypeDef(fory2.getTypeResolver(), buffer2);

    assertNotSame(typeDef1, typeDef2);
    assertEquals(typeDef1.getId(), typeDef2.getId());

    List<Descriptor> descriptors1 = typeDef1.getDescriptors(resolver1, BeanB.class);
    List<Descriptor> descriptors2 = typeDef2.getDescriptors(resolver2, BeanB.class);
    assertSame(descriptors1, descriptors2);

    DescriptorGrouper grouper1 = resolver1.createDescriptorGrouper(typeDef1, BeanB.class);
    DescriptorGrouper grouper2 = resolver2.createDescriptorGrouper(typeDef2, BeanB.class);
    assertSame(grouper1, grouper2);
    assertEquals(grouper1.getSortedDescriptors(), grouper2.getSortedDescriptors());
  }

  @Test
  public void testRegisterNamedClassCachesOnlyNamespaceAndTypeName() {
    ForyBuilder builder = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(true);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();

    int before = sharedRegistry.metaStringMap.size();
    classResolver.register(C1.class, "ns", "C1");

    assertEquals(sharedRegistry.metaStringMap.size() - before, 2);
  }

  @Test
  public void testFinishRegisterPublishesAndAdoptsSharedRegistration() {
    ForyBuilder builder = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(true);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory1 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    Fory fory2 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);

    ClassResolver resolver1 = (ClassResolver) fory1.getTypeResolver();
    ClassResolver resolver2 = (ClassResolver) fory2.getTypeResolver();

    resolver1.register(BeanB.class, 1);
    resolver1.register(C1.class, "ns", "C1");
    assertNull(resolver2.getRegisteredClassId(BeanB.class));
    assertNull(resolver2.getRegisteredClass("ns.C1"));

    resolver1.finishRegistration();

    assertEquals(sharedRegistry.registeredClassIdMap.get(BeanB.class), Integer.valueOf(1));
    assertEquals(sharedRegistry.registeredClasses.get("ns.C1"), C1.class);
    resolver2.finishRegistration();
    assertEquals(resolver2.getRegisteredClassId(BeanB.class), Integer.valueOf(1));
    assertEquals(resolver2.getRegisteredClass("ns.C1"), C1.class);
  }

  private static void finishBuilder(ForyBuilder builder) {
    try {
      Method finish = ForyBuilder.class.getDeclaredMethod("finish");
      finish.setAccessible(true);
      finish.invoke(builder);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
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
      ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      fory.getWriteContext().prepare(buffer, null);
      try {
        classResolver.writeClassInternal(fory.getWriteContext(), getClass());
        int writerIndex = buffer.writerIndex();
        classResolver.writeClassInternal(fory.getWriteContext(), getClass());
        Assert.assertEquals(buffer.writerIndex(), writerIndex + 2);
      } finally {
        fory.getWriteContext().reset();
      }
      buffer.writerIndex(0);
    }
    {
      Fory fory =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .withRefTracking(true)
              .requireClassRegistration(false)
              .build();
      ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      fory.getWriteContext().prepare(buffer, null);
      try {
        classResolver.writeClassAndUpdateCache(fory.getWriteContext(), buffer, getClass());
        classResolver.writeClassAndUpdateCache(fory.getWriteContext(), buffer, getClass());
      } finally {
        fory.getWriteContext().reset();
      }
      fory.getReadContext().prepare(buffer, null, false);
      try {
        Assert.assertSame(classResolver.readTypeInfo(fory.getReadContext()).getCls(), getClass());
        Assert.assertSame(classResolver.readTypeInfo(fory.getReadContext()).getCls(), getClass());
      } finally {
        fory.getReadContext().reset();
      }
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
    withWriteContext(
        fory,
        buffer,
        context -> {
          context.writeRef(C1.class);
          context.writeRef(C2.class);
          context.writeRef(C3.class);
        });
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
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
    Assert.assertFalse(
        classResolver.needToWriteRef(TypeRef.of(TestNeedToWriteReferenceClass.class)));
    assertNull(classResolver.getTypeInfo(TestNeedToWriteReferenceClass.class, false));
  }

  @Test
  public void testSetSerializer() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
    {
      classResolver.setSerializer(Foo.class, new ObjectSerializer<>(fory.getTypeResolver(), Foo.class));
      TypeInfo typeInfo = classResolver.getTypeInfo(Foo.class);
      assertSame(typeInfo.getSerializer().getClass(), ObjectSerializer.class);
      // Create another ObjectSerializer to test setSerializer updates the existing classInfo
      classResolver.setSerializer(
          Foo.class, new ObjectSerializer<>(fory.getTypeResolver(), Foo.class, true));
      Assert.assertSame(classResolver.getTypeInfo(Foo.class), typeInfo);
      assertSame(typeInfo.getSerializer().getClass(), ObjectSerializer.class);
    }
    {
      classResolver.registerInternal(Bar.class);
      TypeInfo typeInfo = classResolver.getTypeInfo(Bar.class);
      classResolver.setSerializer(Bar.class, new ObjectSerializer<>(fory.getTypeResolver(), Bar.class));
      Assert.assertSame(classResolver.getTypeInfo(Bar.class), typeInfo);
      assertSame(typeInfo.getSerializer().getClass(), ObjectSerializer.class);
      // Create another ObjectSerializer to test setSerializer updates the existing classInfo
      classResolver.setSerializer(
          Bar.class, new ObjectSerializer<>(fory.getTypeResolver(), Bar.class, true));
      Assert.assertSame(classResolver.getTypeInfo(Bar.class), typeInfo);
      assertSame(typeInfo.getSerializer().getClass(), ObjectSerializer.class);
    }
  }

  private static class ErrorSerializer extends Serializer<Foo> {
    public ErrorSerializer(TypeResolver typeResolver) {
      super(typeResolver.getConfig(), Foo.class);
      typeResolver.setSerializer(Foo.class, this);
      throw new RuntimeException();
    }

    @Override
    public void write(WriteContext writeContext, Foo value) {}

    @Override
    public Foo read(ReadContext readContext) {
      return null;
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
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
    Assert.assertThrows(() -> Serializers.newSerializer(fory, Foo.class, ErrorSerializer.class));
    Assert.assertNull(classResolver.getSerializer(Foo.class, false));
    Assert.assertThrows(
        () ->
            classResolver.createSerializerSafe(
                Foo.class, () -> new ErrorSerializer(fory.getTypeResolver())));
    Assert.assertNull(classResolver.getSerializer(Foo.class, false));
  }

  @Test
  public void testPrimitive() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).build();
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
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

    public FooCustomSerializer(TypeResolver typeResolver, Class<Foo> type) {
      super(typeResolver.getConfig(), type);
    }

    @Override
    public void write(WriteContext writeContext, Foo value) {
      writeContext.getBuffer().writeInt32(value.f1);
    }

    @Override
    public Foo read(ReadContext readContext) {
      final Foo foo = new Foo();
      foo.f1 = readContext.getBuffer().readInt32();
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
        fory.getTypeResolver().getSerializer(foo.getClass()).getClass(), FooCustomSerializer.class);
  }

  @Test
  public void testRejectIncompatibleCollectionAndMapSerializerRegistration() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    IllegalArgumentException collectionException =
        Assert.expectThrows(
            IllegalArgumentException.class,
            () -> fory.registerSerializer(ArrayList.class, FooCustomSerializer.class));
    Assert.assertTrue(collectionException.getMessage().contains("collection type"));
    IllegalArgumentException mapException =
        Assert.expectThrows(
            IllegalArgumentException.class,
            () ->
                fory.registerSerializer(
                    HashMap.class, new FooCustomSerializer(fory.getTypeResolver(), Foo.class)));
    Assert.assertTrue(mapException.getMessage().contains("map type"));
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

    public InterfaceCustomSerializer(TypeResolver typeResolver, Class<ITest> type) {
      super(typeResolver.getConfig(), type);
    }

    @Override
    public void write(WriteContext writeContext, ITest value) {
      writeContext.getBuffer().writeInt32(value.getF1());
    }

    @Override
    public ITest read(ReadContext readContext) {
      final ITest iTest = new ImplTest();
      iTest.setF1(readContext.getBuffer().readInt32());
      return iTest;
    }
  }

  @Test
  public void testInterfaceCustomSerializer() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.registerSerializer(
        ITest.class, new InterfaceCustomSerializer(fory.getTypeResolver(), ITest.class));
    final ITest iTest = new ImplTest();
    iTest.setF1(100);

    Assert.assertEquals(iTest, serDe(fory, iTest));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializer(iTest.getClass()).getClass(),
        InterfaceCustomSerializer.class);

    fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.register(ITest.class);
    Assert.assertNotEquals(
        fory.getTypeResolver().getSerializer(ImplTest.class).getClass(),
        InterfaceCustomSerializer.class);

    fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.registerSerializer(
        ITest.class, new InterfaceCustomSerializer(fory.getTypeResolver(), ITest.class));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializer(ImplTest.class).getClass(),
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

    public AbstractCustomSerializer(TypeResolver typeResolver, Class<AbsTest> type) {
      super(typeResolver.getConfig(), type);
    }

    @Override
    public void write(WriteContext writeContext, AbsTest value) {
      writeContext.getBuffer().writeInt32(value.getF1());
    }

    @Override
    public AbsTest read(ReadContext readContext) {
      // TODO maybe new SubAbsTest or Sub2AbsTest
      final AbsTest absTest = new SubAbsTest();
      absTest.setF1(readContext.getBuffer().readInt32());
      return absTest;
    }
  }

  @Test
  public void testAbstractCustomSerializer() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.registerSerializer(
        AbsTest.class, new AbstractCustomSerializer(fory.getTypeResolver(), AbsTest.class));
    final AbsTest absTest = new SubAbsTest();
    absTest.setF1(100);

    Assert.assertEquals(absTest, serDe(fory, absTest));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializer(absTest.getClass()).getClass(),
        AbstractCustomSerializer.class);

    final AbsTest abs2Test = new Sub2AbsTest();
    abs2Test.setF1(100);

    Assert.assertEquals(abs2Test.getF1(), serDe(fory, abs2Test).getF1());
    Assert.assertEquals(
        fory.getTypeResolver().getSerializer(abs2Test.getClass()).getClass(),
        AbstractCustomSerializer.class);
  }

  // Test enum with abstract methods (which makes the enum class abstract)
  enum AbstractEnum {
    VALUE1 {
      @Override
      public int getValue() {
        return 1;
      }
    },
    VALUE2 {
      @Override
      public int getValue() {
        return 2;
      }
    };

    public abstract int getValue();
  }

  @Test
  public void testAbstractEnumIsSerializable() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
    // Abstract enums should be serializable
    Assert.assertTrue(classResolver.isSerializable(AbstractEnum.class));
    // The concrete enum value classes should also be serializable
    Assert.assertTrue(classResolver.isSerializable(AbstractEnum.VALUE1.getClass()));
    Assert.assertTrue(classResolver.isSerializable(AbstractEnum.VALUE2.getClass()));
  }

  @Test
  public void testAbstractEnumSerialization() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    // Serialize and deserialize abstract enum values
    Assert.assertEquals(AbstractEnum.VALUE1, serDe(fory, AbstractEnum.VALUE1));
    Assert.assertEquals(AbstractEnum.VALUE2, serDe(fory, AbstractEnum.VALUE2));
    Assert.assertEquals(1, ((AbstractEnum) serDe(fory, AbstractEnum.VALUE1)).getValue());
    Assert.assertEquals(2, ((AbstractEnum) serDe(fory, AbstractEnum.VALUE2)).getValue());
  }

  @Test
  public void testAbstractObjectArraySerialization() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    // Create an array of abstract type with concrete instances
    AbsTest[] array = new AbsTest[2];
    SubAbsTest item1 = new SubAbsTest();
    item1.setF1(10);
    item1.f2 = 100L;
    Sub2AbsTest item2 = new Sub2AbsTest();
    item2.setF1(20);
    item2.f2 = 200L;
    item2.f3 = "test";
    array[0] = item1;
    array[1] = item2;

    AbsTest[] result = serDe(fory, array);
    Assert.assertEquals(result.length, 2);
    Assert.assertEquals(result[0].getF1(), 10);
    Assert.assertEquals(((SubAbsTest) result[0]).f2, 100L);
    Assert.assertEquals(result[1].getF1(), 20);
    Assert.assertEquals(((Sub2AbsTest) result[1]).f2, 200L);
    Assert.assertEquals(((Sub2AbsTest) result[1]).f3, "test");
  }

  @Test
  public void testAbstractObjectArrayWithRegistration() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(true).build();
    // Register the concrete types but not the abstract type
    fory.register(SubAbsTest.class);
    fory.register(Sub2AbsTest.class);
    fory.register(AbsTest[].class);

    AbsTest[] array = new AbsTest[2];
    SubAbsTest item1 = new SubAbsTest();
    item1.setF1(10);
    item1.f2 = 100L;
    Sub2AbsTest item2 = new Sub2AbsTest();
    item2.setF1(20);
    item2.f2 = 200L;
    item2.f3 = "test";
    array[0] = item1;
    array[1] = item2;

    AbsTest[] result = serDe(fory, array);
    Assert.assertEquals(result.length, 2);
    Assert.assertEquals(result[0].getF1(), 10);
    Assert.assertEquals(result[1].getF1(), 20);
  }

  @Test
  public void testAbstractEnumArraySerialization() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    // Create an array of abstract enum type
    AbstractEnum[] array = new AbstractEnum[] {AbstractEnum.VALUE1, AbstractEnum.VALUE2};

    AbstractEnum[] result = serDe(fory, array);
    Assert.assertEquals(result.length, 2);
    Assert.assertEquals(result[0], AbstractEnum.VALUE1);
    Assert.assertEquals(result[1], AbstractEnum.VALUE2);
    Assert.assertEquals(result[0].getValue(), 1);
    Assert.assertEquals(result[1].getValue(), 2);
  }

  @Data
  private static class ThreadSafeRegisteredType {
    private final int value;
  }

  @Data
  private static class CountingThreadSafeRegisteredType {
    private final int value;
  }

  private static final class ThreadSafeRegisteredTypeSerializer
      extends Serializer<ThreadSafeRegisteredType> {
    private ThreadSafeRegisteredTypeSerializer(Config config, Class<ThreadSafeRegisteredType> cls) {
      super(config, cls);
    }

    @Override
    public void write(WriteContext writeContext, ThreadSafeRegisteredType value) {
      writeContext.getBuffer().writeVarInt32(value.getValue());
    }

    @Override
    public ThreadSafeRegisteredType read(ReadContext readContext) {
      return new ThreadSafeRegisteredType(readContext.getBuffer().readVarInt32());
    }

    @Override
    public boolean threadSafe() {
      return true;
    }
  }

  private static final class CountingThreadSafeRegisteredTypeSerializer
      extends Serializer<CountingThreadSafeRegisteredType> {
    private static final AtomicInteger constructorCalls = new AtomicInteger();

    private CountingThreadSafeRegisteredTypeSerializer(
        Config config, Class<CountingThreadSafeRegisteredType> cls) {
      super(config, cls);
      constructorCalls.incrementAndGet();
    }

    @Override
    public void write(WriteContext writeContext, CountingThreadSafeRegisteredType value) {
      writeContext.getBuffer().writeVarInt32(value.getValue());
    }

    @Override
    public CountingThreadSafeRegisteredType read(ReadContext readContext) {
      return new CountingThreadSafeRegisteredType(readContext.getBuffer().readVarInt32());
    }

    @Override
    public boolean threadSafe() {
      return true;
    }
  }
}
