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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Primitives;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.builder.Generated;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.ForyException;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.logging.LogLevel;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.FieldTypes;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.longlongpkg.C1;
import org.apache.fory.resolver.longlongpkg.C2;
import org.apache.fory.resolver.longlongpkg.C3;
import org.apache.fory.serializer.ArraySerializers;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.serializer.Shareable;
import org.apache.fory.serializer.UnknownClass;
import org.apache.fory.serializer.collection.CollectionSerializer;
import org.apache.fory.serializer.collection.CollectionSerializers;
import org.apache.fory.serializer.collection.MapSerializers;
import org.apache.fory.test.bean.BeanA;
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
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
  public void testRegisterDottedName() {
    for (boolean xlang : new boolean[] {false, true}) {
      Fory fory =
          Fory.builder()
              .withXlang(xlang)
              .requireClassRegistration(true)
              .withCompatible(xlang)
              .build();
      TypeResolver resolver = fory.getTypeResolver();

      fory.register(C1.class, "ns.C1");
      TypeInfo typeInfo = resolver.getTypeInfo(C1.class);
      assertEquals(typeInfo.decodeNamespace(), "ns");
      assertEquals(typeInfo.decodeTypeName(), "C1");

      Assert.assertThrows(
          IllegalArgumentException.class, () -> fory.register(C2.class, "ns", "C2.Bad"));
      Assert.assertThrows(IllegalArgumentException.class, () -> fory.register(C2.class, "ns."));
    }
  }

  @Test
  public void testRegisterClass() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
  }

  @Test
  public void testRegisterClassWithUserIds() {
    // Test that user IDs 0 and 1 work correctly with separated user type IDs.
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
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
          Fory.builder()
              .withXlang(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
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
          Fory.builder()
              .withXlang(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      serDeCheck(fory, new Object[] {BeanB.class, BeanB.createBeanB(2)});
    }
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
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
  public void testSuppressXtypeWarnings() throws Exception {
    String suppressed =
        captureOutput(
            () ->
                resolveMissingXtype(
                    Fory.builder()
                        .withXlang(true)
                        .withMetaShare(true)
                        .withDeserializeUnknownClass(true)
                        .suppressClassRegistrationWarnings(true)
                        .build()));
    assertEquals(count(suppressed, "Class missing.pkg.MissingType not registered"), 0);

    String unsuppressed =
        captureOutput(
            () ->
                resolveMissingXtype(
                    Fory.builder()
                        .withXlang(true)
                        .withMetaShare(true)
                        .withDeserializeUnknownClass(true)
                        .suppressClassRegistrationWarnings(false)
                        .build()));
    assertEquals(count(unsuppressed, "Class missing.pkg.MissingType not registered"), 1);
  }

  @Test
  public void testSharedRegistrySharesTypeDefCachesAcrossForyInstances() {
    ForyBuilder builder =
        Fory.builder().withXlang(false).requireClassRegistration(false).withCompatible(false);
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
  public void testReadTypeDefPublishesValidatedTypeDefById() {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
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
    TypeDef readTypeDef1 = resolver1.readTypeDef(buffer, buffer.readInt64());
    buffer.readerIndex(0);
    TypeDef readTypeDef2 = resolver2.readTypeDef(buffer, buffer.readInt64());

    assertSame(readTypeDef1, readTypeDef2);

    TypeInfo typeInfo1 = resolver1.buildMetaSharedTypeInfo(readTypeDef1);
    TypeInfo typeInfo2 = resolver2.buildMetaSharedTypeInfo(readTypeDef2);

    assertNotSame(typeInfo1, typeInfo2);
  }

  @Test
  public void testRemoteTypeDefChecksTypeChecker() {
    Fory reader =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .withMetaShare(true)
            .build();
    reader
        .getTypeResolver()
        .setTypeChecker((resolver, className) -> !className.equals(BeanB.class.getName()));
    ClassResolver resolver = (ClassResolver) reader.getTypeResolver();
    TypeDef typeDef = resolver.getTypeDef(BeanB.class, true);
    ReadContext readContext = reader.getReadContext();
    readContext.setMetaReadContext(new MetaReadContext());
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    readContext.prepare(buffer, null, false);
    buffer.writeVarUInt32(0);
    typeDef.writeTypeDef(buffer);
    buffer.readerIndex(0);

    Assert.assertThrows(
        InsecureException.class, () -> resolver.readSharedClassMeta(readContext, BeanB.class));
  }

  @Test
  public void testMetaLoadClassChecksDisallowedList() {
    String className = ProcessBuilder.class.getName();
    CountingClassLoader classLoader =
        new CountingClassLoader(ClassResolverTest.class.getClassLoader(), className);
    Fory reader =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .withMetaShare(true)
            .withClassLoader(classLoader)
            .build();
    ClassResolver resolver = (ClassResolver) reader.getTypeResolver();

    Assert.assertThrows(
        InsecureException.class, () -> resolver.loadClassForMeta(className, false, -1));
    assertEquals(classLoader.loadCount, 0);
  }

  @Test
  public void testNativeArrayClassLoading() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();
    resolver.register(BeanB.class);

    Assert.assertThrows(InsecureException.class, () -> resolver.loadClass("["));

    assertSame(
        resolver.loadClass("[[[[[[L" + BeanB.class.getName() + ";"), BeanB[][][][][][].class);
    resolver.register(Interface1.class);
    assertSame(
        resolver.loadClass("[[[[[[L" + Interface1.class.getName() + ";"),
        Interface1[][][][][][].class);
    Assert.assertThrows(
        InsecureException.class,
        () -> resolver.loadClass("[[[[[[[L" + BeanB.class.getName() + ";"));

    Fory xlangFory =
        Fory.builder().withXlang(true).requireClassRegistration(true).withCompatible(true).build();
    xlangFory.register(BeanB.class);
    Assert.assertThrows(
        InsecureException.class,
        () -> xlangFory.getTypeResolver().loadClass("[L" + BeanB.class.getName() + ";"));

    FieldTypes.ArrayFieldType nestedArray =
        new FieldTypes.ArrayFieldType(
            Types.ARRAY,
            true,
            true,
            new FieldTypes.ArrayFieldType(
                Types.ARRAY,
                true,
                true,
                new FieldTypes.ObjectFieldType(Types.STRUCT, true, true),
                3),
            4);
    Assert.assertThrows(
        InsecureException.class,
        () -> nestedArray.toTypeToken(resolver, TypeRef.of(BeanB[][][][][][][].class)));

    resolver.register(BeanB[][][][][][][].class);
    assertSame(
        resolver.loadClass("[[[[[[[L" + BeanB.class.getName() + ";"), BeanB[][][][][][][].class);
    assertSame(
        nestedArray.toTypeToken(resolver, TypeRef.of(BeanB[][][][][][][].class)).getRawType(),
        BeanB[][][][][][][].class);

    Fory arraySerializerFory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    Class<?> sevenDimensionalArray = Foo[][][][][][][].class;
    arraySerializerFory.registerSerializer(
        sevenDimensionalArray,
        ArraySerializers.newObjectArraySerializer(
            arraySerializerFory.getTypeResolver(), sevenDimensionalArray));
    assertSame(
        arraySerializerFory.getTypeResolver().loadClass(sevenDimensionalArray.getName()),
        sevenDimensionalArray);
    Class<?> sevenDimensionalEnumArray = TestNeedToWriteReferenceClass[][][][][][][].class;
    arraySerializerFory.registerSerializer(
        sevenDimensionalEnumArray,
        ArraySerializers.newObjectArraySerializer(
            arraySerializerFory.getTypeResolver(), sevenDimensionalEnumArray));
    assertSame(
        arraySerializerFory.getTypeResolver().loadClass(sevenDimensionalEnumArray.getName()),
        sevenDimensionalEnumArray);

    Fory serializerFory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    serializerFory.registerSerializer(
        Foo.class, typeResolver -> new FooCustomSerializer(typeResolver, Foo.class));
    ClassResolver serializerResolver = (ClassResolver) serializerFory.getTypeResolver();
    assertNull(serializerResolver.getRegisteredClassId(Foo.class));
    assertSame(
        serializerResolver.loadClass("[[[[[[L" + Foo.class.getName() + ";"), Foo[][][][][][].class);

    Fory unknownFory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .withMetaShare(true)
            .withDeserializeUnknownClass(true)
            .build();
    ClassResolver unknownResolver = (ClassResolver) unknownFory.getTypeResolver();
    String unknownDescriptor = "[[[[[[Lmissing.Type;";
    Class<?> unknownArray = unknownResolver.loadClassForMeta(unknownDescriptor, false, 6);
    assertEquals(TypeUtils.getArrayDimensions(unknownArray), 6);
    Assert.assertThrows(
        InsecureException.class, () -> unknownResolver.loadClass(unknownDescriptor));

    FieldTypes.ArrayFieldType nestedUnknownEnum =
        new FieldTypes.ArrayFieldType(
            Types.ARRAY,
            true,
            true,
            new FieldTypes.ArrayFieldType(
                Types.ARRAY, true, true, new FieldTypes.EnumFieldType(true, Types.ENUM, -1), 2),
            3);
    Class<?> unknownEnumArray = nestedUnknownEnum.toTypeToken(unknownResolver, null).getRawType();
    assertEquals(TypeUtils.getArrayDimensions(unknownEnumArray), 5);
    assertSame(TypeUtils.getArrayComponent(unknownEnumArray), UnknownClass.UnknownEnum.class);
  }

  @Test
  public void testArrayCheckerUsesDescriptor() {
    String descriptor = "[[[[[[[L" + BeanB.class.getName() + ";";
    CountingClassLoader classLoader =
        new CountingClassLoader(ClassResolverTest.class.getClassLoader(), BeanB.class.getName());
    AtomicReference<String> checkedName = new AtomicReference<>();
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .withClassLoader(classLoader)
            .withTypeChecker(
                (resolver, className) -> {
                  checkedName.set(className);
                  return false;
                })
            .build();

    Assert.assertThrows(
        InsecureException.class, () -> fory.getTypeResolver().loadClass(descriptor));
    assertEquals(checkedName.get(), descriptor);
    assertEquals(classLoader.loadCount, 0);

    FieldTypes.ArrayFieldType rejectedFieldArray =
        new FieldTypes.ArrayFieldType(
            Types.ARRAY, true, true, new FieldTypes.ObjectFieldType(Types.STRUCT, true, true), 7);
    checkedName.set(null);
    Assert.assertThrows(
        InsecureException.class,
        () ->
            rejectedFieldArray.toTypeToken(
                fory.getTypeResolver(), TypeRef.of(BeanB[][][][][][][].class)));
    assertEquals(checkedName.get(), descriptor);
    assertEquals(classLoader.loadCount, 0);

    String acceptedDescriptor = "[[[[[[L" + BeanB.class.getName() + ";";
    AtomicReference<String> acceptedName = new AtomicReference<>();
    Fory acceptedFory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .withTypeChecker(
                (resolver, className) -> {
                  acceptedName.set(className);
                  return className.equals(acceptedDescriptor);
                })
            .build();
    FieldTypes.ArrayFieldType acceptedFieldArray =
        new FieldTypes.ArrayFieldType(
            Types.ARRAY, true, true, new FieldTypes.ObjectFieldType(Types.STRUCT, true, true), 6);
    Class<?> acceptedArray =
        acceptedFieldArray
            .toTypeToken(acceptedFory.getTypeResolver(), TypeRef.of(BeanB[][][][][][].class))
            .getRawType();
    assertSame(acceptedArray, BeanB[][][][][][].class);
    acceptedFory.getTypeResolver().checkClassForDeserialization(acceptedArray);
    assertEquals(acceptedName.get(), acceptedDescriptor);
  }

  @Test
  public void testRemoteSchemaVersionLimitByType() {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .withMetaShare(true)
            .withMaxSchemaVersionsPerType(1);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();
    TypeDef first = TypeDef.buildTypeDef(resolver, BeanB.class);
    TypeDef second = TypeDef.buildTypeDef(resolver, BeanA.class);

    assertSame(first, sharedRegistry.getOrCreateRemoteTypeDef(first, "remote.Type"));
    assertSame(first, sharedRegistry.getOrCreateRemoteTypeDef(first, "remote.Type"));
    Assert.assertThrows(
        ForyException.class, () -> sharedRegistry.getOrCreateRemoteTypeDef(second, "remote.Type"));
  }

  @Test
  public void testSharedRegistryRemoteSchemaLimitInit() {
    SharedRegistry sharedRegistry = new SharedRegistry();

    Assert.assertThrows(
        IllegalArgumentException.class, () -> sharedRegistry.setRemoteSchemaLimits(0, 3));
    Assert.assertThrows(
        IllegalArgumentException.class, () -> sharedRegistry.setRemoteSchemaLimits(10, 0));

    sharedRegistry.setRemoteSchemaLimits(10, 3);
    sharedRegistry.setRemoteSchemaLimits(10, 3);
    Assert.assertThrows(
        IllegalArgumentException.class, () -> sharedRegistry.setRemoteSchemaLimits(11, 3));
    Assert.assertThrows(
        IllegalArgumentException.class, () -> sharedRegistry.setRemoteSchemaLimits(10, 4));
  }

  @Test
  public void testRemoteEnumTypeDefUsesLimit() {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(true)
            .requireClassRegistration(true)
            .withCompatible(false)
            .withMetaShare(true)
            .withMaxSchemaVersionsPerType(1);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    fory.register(TestNeedToWriteReferenceClass.class, "test.Enum");
    fory.register(OtherTestNeedToWriteReferenceClass.class, "test.OtherEnum");
    TypeResolver resolver = fory.getTypeResolver();
    TypeDef first = resolver.getTypeDef(TestNeedToWriteReferenceClass.class, true);
    TypeDef second = TypeDef.buildTypeDef(resolver, OtherTestNeedToWriteReferenceClass.class);

    Assert.assertFalse(first.isStructSchemaKind());
    assertSame(first, sharedRegistry.getOrCreateRemoteTypeDef(first, "remote.Enum"));
    Assert.assertThrows(
        ForyException.class, () -> sharedRegistry.getOrCreateRemoteTypeDef(second, "remote.Enum"));
  }

  @Test
  public void testExactLocalEnumTypeDefBypassesLimit() {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(true)
            .requireClassRegistration(true)
            .withCompatible(false)
            .withMetaShare(true)
            .withMaxSchemaVersionsPerType(1);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    fory.register(TestNeedToWriteReferenceClass.class, "test.Enum");
    fory.register(OtherTestNeedToWriteReferenceClass.class, "test.OtherEnum");
    TypeResolver resolver = fory.getTypeResolver();
    TypeDef exact = resolver.getTypeDef(TestNeedToWriteReferenceClass.class, true);
    TypeDef other = TypeDef.buildTypeDef(resolver, OtherTestNeedToWriteReferenceClass.class);

    ReadContext readContext = fory.getReadContext();
    readContext.setMetaReadContext(new MetaReadContext());
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    readContext.prepare(buffer, null, false);
    buffer.writeVarUInt32(0);
    exact.writeTypeDef(buffer);
    buffer.readerIndex(0);

    assertSame(
        resolver.readSharedClassMeta(readContext, TestNeedToWriteReferenceClass.class).getType(),
        TestNeedToWriteReferenceClass.class);
    assertSame(other, sharedRegistry.getOrCreateRemoteTypeDef(other, "test.Enum"));
  }

  @Test
  public void testIdEnumDoesNotUseTypeDefMetaLimits() {
    Fory fory =
        Fory.builder()
            .withXlang(true)
            .requireClassRegistration(true)
            .withCompatible(true)
            .withMaxTypeMetaBytes(1)
            .withMaxSchemaVersionsPerType(1)
            .build();
    fory.register(TestNeedToWriteReferenceClass.class, 101);

    serDeCheck(fory, TestNeedToWriteReferenceClass.A);
  }

  @Test
  public void testIdExtDoesNotUseTypeDefMetaLimits() {
    Fory fory =
        Fory.builder()
            .withXlang(true)
            .requireClassRegistration(true)
            .withCompatible(true)
            .withMaxTypeMetaBytes(1)
            .withMaxSchemaVersionsPerType(1)
            .build();
    fory.register(IdLimitExt.class, 102);
    fory.registerSerializer(IdLimitExt.class, IdLimitExtSerializer.class);

    IdLimitExt value = new IdLimitExt();
    value.value = 42;
    serDeCheck(fory, value);
    assertEquals(fory.getTypeResolver().getTypeInfo(IdLimitExt.class).getTypeId(), Types.EXT);
  }

  @Test
  public void testRemoteSchemaVersionsUseRemoteTypeKey() {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .withMetaShare(true)
            .withMaxSchemaVersionsPerType(1);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();

    TypeDef first = TypeDef.buildTypeDef(resolver, BeanB.class);
    TypeDef second = TypeDef.buildTypeDef(resolver, BeanA.class);

    assertSame(first, sharedRegistry.getOrCreateRemoteTypeDef(first, "remote.UnknownA"));
    assertSame(second, sharedRegistry.getOrCreateRemoteTypeDef(second, "remote.UnknownB"));
  }

  @Test
  public void testRemoteTypeDefCheckOnly() {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .withMetaShare(true)
            .withMaxSchemaVersionsPerType(1);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();

    TypeDef checked = TypeDef.buildTypeDef(resolver, BeanB.class);
    TypeDef accepted = TypeDef.buildTypeDef(resolver, BeanA.class);

    sharedRegistry.checkRemoteTypeDefLimit(checked, "remote.Type");
    assertSame(accepted, sharedRegistry.getOrCreateRemoteTypeDef(accepted, "remote.Type"));
  }

  @Test
  public void testSharedRegistryCachesFieldDescriptorsAndDescriptorGrouper() {
    ForyBuilder builder =
        Fory.builder().withXlang(false).requireClassRegistration(false).withCompatible(false);
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
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .withCompatible(false);
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
    ForyBuilder builder =
        Fory.builder().withXlang(false).requireClassRegistration(true).withCompatible(false);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();

    int before = sharedRegistry.metaStringMap.size();
    classResolver.register(C1.class, "ns", "C1");

    assertEquals(sharedRegistry.metaStringMap.size() - before, 2);
  }

  @Test
  public void testCustomNameDoesNotAddJavaName() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();
    resolver.register(Foo.class, "alias", "Foo");

    assertSame(resolver.loadClass("alias.Foo"), Foo.class);
    assertSame(resolver.loadClassForMeta("alias.Foo", false, -1), Foo.class);
    Assert.assertThrows(InsecureException.class, () -> resolver.loadClass(Foo.class.getName()));
    Assert.assertThrows(
        InsecureException.class, () -> resolver.loadClassForMeta(Foo.class.getName(), false, -1));
    Assert.assertThrows(
        InsecureException.class, () -> resolver.loadClass("[L" + Foo.class.getName() + ";"));
  }

  @Test
  public void testIdRegistrationAcceptsJavaName() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();
    resolver.register(Foo.class, 101);

    assertSame(resolver.loadClass(Foo.class.getName()), Foo.class);
    assertSame(resolver.loadClassForMeta(Foo.class.getName(), false, -1), Foo.class);
  }

  @Test
  public void testFinishRegisterPublishesAndAdoptsSharedRegistration() {
    ForyBuilder builder =
        Fory.builder().withXlang(false).requireClassRegistration(true).withCompatible(false);
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

  private static final class CountingClassLoader extends ClassLoader {
    private final String countedClassName;
    private int loadCount;

    private CountingClassLoader(ClassLoader parent, String countedClassName) {
      super(parent);
      this.countedClassName = countedClassName;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (countedClassName.equals(name)) {
        loadCount++;
      }
      return super.loadClass(name, resolve);
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
            .withXlang(false)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .withCompatible(false)
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
  public void testClassLiteralRegistration() {
    Fory writer =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    byte[] serialized = writer.serialize(Foo.class);

    Fory reader = Fory.builder().withXlang(false).withCompatible(false).build();
    Assert.assertThrows(InsecureException.class, () -> reader.deserialize(serialized));

    Fory registeredReader = Fory.builder().withXlang(false).withCompatible(false).build();
    registeredReader.register(Foo.class);
    Assert.assertSame(registeredReader.deserialize(serialized), Foo.class);
  }

  @Test
  public void testWriteClassName() {
    {
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withRefTracking(true)
              .requireClassRegistration(false)
              .withCompatible(false)
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
              .withXlang(false)
              .withRefTracking(true)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      fory.getWriteContext().prepare(buffer, null);
      try {
        classResolver.writeClassAndUpdateCache(fory.getWriteContext(), getClass());
        classResolver.writeClassAndUpdateCache(fory.getWriteContext(), getClass());
      } finally {
        fory.getWriteContext().reset();
      }
      fory.getReadContext().prepare(buffer, null, false);
      try {
        Assert.assertSame(classResolver.readTypeInfo(fory.getReadContext()).getType(), getClass());
        Assert.assertSame(classResolver.readTypeInfo(fory.getReadContext()).getType(), getClass());
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
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

  @Data
  static class IdLimitExt {
    int value;
  }

  @EqualsAndHashCode(callSuper = true)
  @ToString
  static class Bar extends Foo {
    long f2;
  }

  @Test
  public void testClassRegistrationInit() {
    Fory fory = Fory.builder().withXlang(false).withCodegen(false).withCompatible(false).build();
    serDeCheck(fory, new HashMap<>(ImmutableMap.of("a", 1, "b", 2)));
  }

  private enum TestNeedToWriteReferenceClass {
    A,
    B
  }

  private enum OtherTestNeedToWriteReferenceClass {
    C,
    D
  }

  public static class IdLimitExtSerializer extends Serializer<IdLimitExt> {
    public IdLimitExtSerializer(TypeResolver typeResolver, Class<IdLimitExt> type) {
      super(typeResolver.getConfig(), type);
    }

    @Override
    public void write(WriteContext writeContext, IdLimitExt value) {
      writeContext.getBuffer().writeInt32(value.value);
    }

    @Override
    public IdLimitExt read(ReadContext readContext) {
      IdLimitExt value = new IdLimitExt();
      value.value = readContext.getBuffer().readInt32();
      return value;
    }
  }

  @Test
  public void testNeedToWriteReference() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withCompatible(false)
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
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
    {
      classResolver.setSerializer(
          Foo.class, new ObjectSerializer<>(fory.getTypeResolver(), Foo.class));
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
      classResolver.setSerializer(
          Bar.class, new ObjectSerializer<>(fory.getTypeResolver(), Bar.class));
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
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withCompatible(false)
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
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
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

  static class ShareableFooSerializer extends Serializer<Foo> implements Shareable {

    public ShareableFooSerializer(TypeResolver typeResolver, Class<Foo> type) {
      super(typeResolver.getConfig(), type);
    }

    @Override
    public void write(WriteContext writeContext, Foo value) {
      writeContext.getBuffer().writeInt32(value.f1);
    }

    @Override
    public Foo read(ReadContext readContext) {
      Foo foo = new Foo();
      foo.f1 = readContext.getBuffer().readInt32();
      return foo;
    }
  }

  @Test
  public void testFooCustomSerializer() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    Assert.assertThrows(() -> fory.registerSerializer(Foo.class, FooCustomSerializer.class));
    fory.registerSerializer(Foo.class, f -> new FooCustomSerializer(f, Foo.class));
    final Foo foo = new Foo();
    foo.setF1(100);

    Assert.assertEquals(foo, serDe(fory, foo));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializer(foo.getClass()).getClass(), FooCustomSerializer.class);
  }

  @Test
  public void testSerializerRegistrationTrustsClass() {
    Fory writer =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    writer.registerSerializer(Foo.class, f -> new FooCustomSerializer(f, Foo.class));
    Foo foo = new Foo();
    foo.f1 = 100;
    byte[] bytes = writer.serialize(foo);

    Fory unregisteredReader =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    Assert.assertThrows(InsecureException.class, () -> unregisteredReader.deserialize(bytes));

    Fory registeredReader =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    registeredReader.registerSerializer(Foo.class, f -> new FooCustomSerializer(f, Foo.class));
    ClassResolver resolver = (ClassResolver) registeredReader.getTypeResolver();
    assertNull(resolver.getRegisteredClassId(Foo.class));
    assertEquals(registeredReader.deserialize(bytes), foo);

    Fory checkedReader =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withTypeChecker((typeResolver, className) -> !className.equals(Foo.class.getName()))
            .withCompatible(false)
            .build();
    checkedReader.registerSerializer(Foo.class, f -> new FooCustomSerializer(f, Foo.class));
    assertSame(checkedReader.getTypeResolver().loadClass(Foo.class.getName()), Foo.class);
    assertEquals(checkedReader.deserialize(bytes), foo);
  }

  @Test
  public void testAutomaticSerializerDoesNotTrustClass() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withTypeChecker((resolver, className) -> !className.equals(Foo.class.getName()))
            .withCompatible(false)
            .build();

    fory.getTypeResolver().getSerializer(Foo.class);
    Assert.assertThrows(
        InsecureException.class, () -> fory.getTypeResolver().loadClass(Foo.class.getName()));
  }

  @Test
  public void testSerializerFirstRegistration() {
    Fory idFory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    idFory.registerSerializer(Foo.class, f -> new FooCustomSerializer(f, Foo.class));
    idFory.register(Foo.class, 101);
    assertSame(
        idFory.getTypeResolver().getSerializer(Foo.class).getClass(), FooCustomSerializer.class);
    assertEquals(idFory.getTypeResolver().getTypeInfo(Foo.class).getTypeId(), Types.EXT);
    Foo idValue = new Foo();
    idValue.f1 = 11;
    assertEquals(serDe(idFory, idValue), idValue);

    Fory namedFory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    namedFory.registerSerializer(Foo.class, f -> new FooCustomSerializer(f, Foo.class));
    namedFory.register(Foo.class, "test", "Foo");
    assertSame(
        namedFory.getTypeResolver().getSerializer(Foo.class).getClass(), FooCustomSerializer.class);
    assertEquals(namedFory.getTypeResolver().getTypeInfo(Foo.class).getTypeId(), Types.NAMED_EXT);
    Foo namedValue = new Foo();
    namedValue.f1 = 12;
    assertEquals(serDe(namedFory, namedValue), namedValue);

    Fory nameFirstFory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    nameFirstFory.register(Foo.class, "test", "Foo");
    nameFirstFory.registerSerializer(Foo.class, f -> new FooCustomSerializer(f, Foo.class));
    assertEquals(
        ((ClassResolver) nameFirstFory.getTypeResolver()).getRegisteredName(Foo.class), "test.Foo");
    Foo nameFirstValue = new Foo();
    nameFirstValue.f1 = 13;
    assertEquals(serDe(nameFirstFory, nameFirstValue), nameFirstValue);
  }

  @Test
  public void testRuntimeAliasRegistrationConflict() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    TypeResolver resolver = fory.getTypeResolver();
    fory.registerSerializer(Foo.class, f -> new FooCustomSerializer(f, Foo.class));
    resolver.registerRuntimeTypeAlias(Bar.class, Foo.class);

    Assert.assertThrows(IllegalArgumentException.class, () -> fory.register(Bar.class, 101));
  }

  @Test
  public void testShareableSerializerSharedAcrossRuntimes() {
    ForyBuilder builder =
        Fory.builder().withXlang(false).requireClassRegistration(true).withCompatible(false);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory1 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);
    Fory fory2 = new Fory(builder, ClassResolverTest.class.getClassLoader(), sharedRegistry);

    ClassResolver resolver1 = (ClassResolver) fory1.getTypeResolver();
    ClassResolver resolver2 = (ClassResolver) fory2.getTypeResolver();

    resolver1.register(Foo.class, 101);
    resolver2.register(Foo.class, 101);
    resolver1.registerSerializer(Foo.class, ShareableFooSerializer.class);
    resolver2.registerSerializer(Foo.class, ShareableFooSerializer.class);
    resolver1.finishRegistration();
    resolver2.finishRegistration();

    Serializer<?> serializer1 = resolver1.getSerializer(Foo.class);
    TypeInfo sharedTypeInfo = sharedRegistry.registeredTypeInfoCache.get(Foo.class);
    assertNotNull(sharedTypeInfo);
    assertSame(sharedRegistry.getRegisteredSerializer(Foo.class), serializer1);
    assertSame(sharedTypeInfo, resolver1.getTypeInfo(Foo.class, false));

    TypeInfo sharedTypeInfo2 = resolver2.getTypeInfo(Foo.class, false);
    assertNotNull(sharedTypeInfo2);
    assertSame(sharedTypeInfo2, sharedTypeInfo);
    assertSame(sharedTypeInfo2.getSerializer(), serializer1);

    Foo foo = new Foo();
    foo.f1 = 123;
    assertEquals(fory2.deserialize(fory1.serialize(foo)), foo);
  }

  @Test
  public void testRejectIncompatibleCollectionAndMapSerializerRegistration() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    fory.registerSerializer(
        ITest.class, new InterfaceCustomSerializer(fory.getTypeResolver(), ITest.class));
    final ITest iTest = new ImplTest();
    iTest.setF1(100);

    Assert.assertEquals(iTest, serDe(fory, iTest));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializer(iTest.getClass()).getClass(),
        InterfaceCustomSerializer.class);

    fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    fory.register(ITest.class);
    Assert.assertNotEquals(
        fory.getTypeResolver().getSerializer(ImplTest.class).getClass(),
        InterfaceCustomSerializer.class);

    fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    ClassResolver classResolver = (ClassResolver) fory.getTypeResolver();
    // Abstract enums should be serializable
    Assert.assertTrue(classResolver.isSerializable(AbstractEnum.class));
    // The concrete enum value classes should also be serializable
    Assert.assertTrue(classResolver.isSerializable(AbstractEnum.VALUE1.getClass()));
    Assert.assertTrue(classResolver.isSerializable(AbstractEnum.VALUE2.getClass()));
  }

  @Test
  public void testAbstractEnumSerialization() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    // Serialize and deserialize abstract enum values
    Assert.assertEquals(AbstractEnum.VALUE1, serDe(fory, AbstractEnum.VALUE1));
    Assert.assertEquals(AbstractEnum.VALUE2, serDe(fory, AbstractEnum.VALUE2));
    Assert.assertEquals(1, ((AbstractEnum) serDe(fory, AbstractEnum.VALUE1)).getValue());
    Assert.assertEquals(2, ((AbstractEnum) serDe(fory, AbstractEnum.VALUE2)).getValue());
  }

  @Test
  public void testAbstractObjectArraySerialization() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    // Create an array of abstract enum type
    AbstractEnum[] array = new AbstractEnum[] {AbstractEnum.VALUE1, AbstractEnum.VALUE2};

    AbstractEnum[] result = serDe(fory, array);
    Assert.assertEquals(result.length, 2);
    Assert.assertEquals(result[0], AbstractEnum.VALUE1);
    Assert.assertEquals(result[1], AbstractEnum.VALUE2);
    Assert.assertEquals(result[0].getValue(), 1);
    Assert.assertEquals(result[1].getValue(), 2);
  }

  @Test
  public void testReadResolveTypeDefState() {
    byte[] groupBytes =
        newReadResolveFory()
            .serialize(
                new ReadResolveGroup(7, new HashSet<>(Arrays.asList(ReadResolveCode.of("FR")))));
    byte[] geoBytes =
        newReadResolveFory()
            .serialize(
                new ReadResolveGeo(new ArrayList<>(Arrays.asList(ReadResolveCode.of("GB")))));

    Fory reader = newReadResolveFory();
    ClassResolver resolver = (ClassResolver) reader.getTypeResolver();
    assertEquals(resolver.getTypeIdForTypeDef(ReadResolveCode.class), Types.EXT);
    assertEquals(
        reader.deserialize(groupBytes),
        new ReadResolveGroup(7, new HashSet<>(Arrays.asList(ReadResolveCode.of("FR")))));
    assertEquals(resolver.getTypeIdForTypeDef(ReadResolveCode.class), Types.EXT);
    assertEquals(
        reader.deserialize(geoBytes),
        new ReadResolveGeo(new ArrayList<>(Arrays.asList(ReadResolveCode.of("GB")))));
    assertEquals(resolver.getTypeIdForTypeDef(ReadResolveCode.class), Types.EXT);
  }

  private static Fory newReadResolveFory() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(true)
            .withCompatible(true)
            .build();
    fory.register(ReadResolveCode.class, 100);
    fory.register(ReadResolveGroup.class, 101);
    fory.register(ReadResolveGeo.class, 102);
    return fory;
  }

  public static final class ReadResolveCode implements Serializable {
    public String value;

    public ReadResolveCode() {}

    private ReadResolveCode(String value) {
      this.value = value;
    }

    static ReadResolveCode of(String value) {
      return new ReadResolveCode(value);
    }

    private Object readResolve() {
      return this;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ReadResolveCode)) {
        return false;
      }
      ReadResolveCode that = (ReadResolveCode) o;
      return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value);
    }
  }

  public static final class ReadResolveGroup implements Serializable {
    public int id;
    public Set<ReadResolveCode> codes;

    public ReadResolveGroup() {}

    ReadResolveGroup(int id, Set<ReadResolveCode> codes) {
      this.id = id;
      this.codes = codes;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ReadResolveGroup)) {
        return false;
      }
      ReadResolveGroup that = (ReadResolveGroup) o;
      return id == that.id && Objects.equals(codes, that.codes);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, codes);
    }
  }

  public static final class ReadResolveGeo implements Serializable {
    public List<ReadResolveCode> codes;

    public ReadResolveGeo() {}

    ReadResolveGeo(List<ReadResolveCode> codes) {
      this.codes = codes;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ReadResolveGeo)) {
        return false;
      }
      ReadResolveGeo that = (ReadResolveGeo) o;
      return Objects.equals(codes, that.codes);
    }

    @Override
    public int hashCode() {
      return Objects.hash(codes);
    }
  }

  private static String captureOutput(Runnable action) throws Exception {
    int previousLogLevel = LoggerFactory.getLogLevel();
    PrintStream previousOut = System.out;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      LoggerFactory.setLogLevel(LogLevel.WARN_LEVEL);
      System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8.name()));
      action.run();
    } finally {
      System.setOut(previousOut);
      LoggerFactory.setLogLevel(previousLogLevel);
    }
    return out.toString(StandardCharsets.UTF_8.name());
  }

  private static void resolveMissingXtype(Fory fory) {
    try {
      Method method =
          XtypeResolver.class.getDeclaredMethod(
              "loadBytesToTypeInfoWithTypeId",
              int.class,
              EncodedMetaString.class,
              EncodedMetaString.class);
      method.setAccessible(true);
      method.invoke(
          fory.getTypeResolver(),
          Types.NAMED_STRUCT,
          Encoders.PACKAGE_ENCODER.encodeBinary("missing.pkg"),
          Encoders.TYPE_NAME_ENCODER.encodeBinary("MissingType"));
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (!(cause instanceof IllegalStateException)
          || !cause.getMessage().contains("missing.pkg.MissingType")) {
        throw new AssertionError(e);
      }
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static int count(String text, String pattern) {
    int count = 0;
    int from = 0;
    while (true) {
      int index = text.indexOf(pattern, from);
      if (index < 0) {
        return count;
      }
      count++;
      from = index + pattern.length();
    }
  }
}
