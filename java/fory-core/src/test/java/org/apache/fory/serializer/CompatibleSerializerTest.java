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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.TestUtils;
import org.apache.fory.config.Language;
import org.apache.fory.context.ReadContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.serializer.collection.UnmodifiableSerializersTest;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.test.bean.CollectionFields;
import org.apache.fory.test.bean.Foo;
import org.apache.fory.test.bean.MapFields;
import org.apache.fory.test.bean.Struct;
import org.apache.fory.type.Types;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for compatible mode serialization using shared class metadata. These tests verify
 * forward/backward compatibility when using compatible mode with scoped meta share.
 */
public class CompatibleSerializerTest extends ForyTestBase {
  private static final int CATALOG_SNAPSHOT_CLASS_ID = 4100;
  private static final int MOVIE_DOCUMENT_CLASS_ID = 4101;
  private static final int LEGACY_LABELS_CLASS_ID = 4102;
  private static final int NULLABLE_KEY_LABELS_CLASS_ID = 4103;

  @Test
  public void testWrite() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    serDeCheck(fory, Foo.create());
    serDeCheck(fory, BeanB.createBeanB(2));
    serDeCheck(fory, BeanA.createBeanA(2));
  }

  @Test
  public void testCopy() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    copyCheck(fory, Foo.create());
    copyCheck(fory, BeanB.createBeanB(2));
    copyCheck(fory, BeanA.createBeanA(2));
  }

  @Test
  public void testWriteCompatibleBasic() throws Exception {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    Object foo = Foo.create();
    for (Class<?> fooClass :
        new Class<?>[] {
          Foo.createCompatibleClass1(), Foo.createCompatibleClass2(), Foo.createCompatibleClass3(),
        }) {
      Object newFoo = fooClass.newInstance();
      TestUtils.unsafeCopy(foo, newFoo);
      Fory newFory =
          Fory.builder()
              .withXlang(false)
              .withRefTracking(true)
              .withCompatible(true)
              .requireClassRegistration(false)
              .withClassLoader(fooClass.getClassLoader())
              .build();
      {
        byte[] foo1Bytes = newFory.serialize(newFoo);
        Object deserialized = fory.deserialize(foo1Bytes);
        Assert.assertEquals(deserialized.getClass(), Foo.class);
        Assert.assertTrue(TestUtils.objectCommonFieldsEquals(deserialized, newFoo));
        byte[] fooBytes = fory.serialize(deserialized);
        TestUtils.objectFieldsEquals(newFory.deserialize(fooBytes), newFoo, true);
      }
      {
        byte[] bytes1 = fory.serialize(foo);
        Object o1 = newFory.deserialize(bytes1);
        Assert.assertTrue(TestUtils.objectCommonFieldsEquals(o1, foo));
        Object o2 = fory.deserialize(newFory.serialize(o1));
        List<String> fields =
            Arrays.stream(fooClass.getDeclaredFields())
                .map(f -> f.getDeclaringClass().getSimpleName() + f.getName())
                .collect(Collectors.toList());
        TestUtils.objectFieldsEquals(new HashSet<>(fields), o2, foo, true);
      }
      {
        Object o3 = fory.deserialize(newFory.serialize(foo));
        TestUtils.objectFieldsEquals(o3, foo, true);
      }
    }
  }

  @Test
  public void testNullableListBodyBounds() throws Exception {
    Method method =
        CompatibleCollectionArrayReader.class.getDeclaredMethod(
            "readNullableListBoxedElements", ReadContext.class, int.class, int.class, int.class);
    method.setAccessible(true);
    MemoryBuffer buffer = MemoryUtils.buffer(0);
    Fory fory = builder().build();
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(buffer, null, false, buffer.remaining(), false);
    try {
      InvocationTargetException exception =
          Assert.expectThrows(
              InvocationTargetException.class,
              () -> method.invoke(null, readContext, 1024, Types.INT32_ARRAY, Types.INT32));
      Assert.assertTrue(exception.getCause() instanceof IndexOutOfBoundsException);
    } finally {
      readContext.reset();
    }
  }

  @Test
  public void testWriteNestedCollection() throws Exception {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    CollectionFields collectionFields = UnmodifiableSerializersTest.createCollectionFields();
    byte[] bytes = fory.serialize(collectionFields);
    Object o = fory.deserialize(bytes);
    Object o1 = CollectionFields.copyToCanEqual(o, o.getClass().newInstance());
    Object o2 =
        CollectionFields.copyToCanEqual(
            collectionFields, collectionFields.getClass().newInstance());
    Assert.assertEquals(o1, o2);
  }

  @Test
  public void testWriteNestedMap() throws Exception {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    MapFields mapFields = UnmodifiableSerializersTest.createMapFields();
    byte[] bytes = fory.serialize(mapFields);
    Object o = fory.deserialize(bytes);
    Object o1 = MapFields.copyToCanEqual(o, o.getClass().newInstance());
    Object o2 = MapFields.copyToCanEqual(mapFields, mapFields.getClass().newInstance());
    Assert.assertEquals(o1, o2);
  }

  @Test(dataProvider = "enableCodegen")
  public void testWriteCompatibleContainer(boolean enableCodegen) throws Exception {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .withCodegen(enableCodegen)
            .build();
    BeanA beanA = BeanA.createBeanA(2);
    Class<?> cls = ClassUtils.createCompatibleClass1();
    Object newBeanA = cls.newInstance();
    TestUtils.unsafeCopy(beanA, newBeanA);
    Fory newFory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .withClassLoader(cls.getClassLoader())
            .build();
    byte[] newBeanABytes = newFory.serialize(newBeanA);
    Object deserialized = fory.deserialize(newBeanABytes);
    Assert.assertTrue(TestUtils.objectCommonFieldsEquals(deserialized, newBeanA));
    Assert.assertEquals(deserialized.getClass(), BeanA.class);
    byte[] beanABytes = fory.serialize(deserialized);
    TestUtils.objectFieldsEquals(newFory.deserialize(beanABytes), newBeanA, true);

    byte[] objBytes = fory.serialize(beanA);
    Object obj2 = newFory.deserialize(objBytes);
    Assert.assertTrue(TestUtils.objectCommonFieldsEquals(obj2, newBeanA));
  }

  @Test
  public void testWriteCompatibleCollection() throws Exception {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    CollectionFields collectionFields = UnmodifiableSerializersTest.createCollectionFields();
    {
      //      Object o = serDe(fory, collectionFields);
      //      Object o1 = CollectionFields.copyToCanEqual(o, o.getClass().newInstance());
      //      Object o2 =
      //          CollectionFields.copyToCanEqual(
      //              collectionFields, collectionFields.getClass().newInstance());
      //      Assert.assertEquals(o1, o2);
    }
    Class<?> cls = ClassUtils.createCompatibleClass2();
    Object newObj = cls.newInstance();
    TestUtils.unsafeCopy(collectionFields, newObj);
    Fory newFory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .withClassLoader(cls.getClassLoader())
            .build();
    byte[] bytes1 = newFory.serialize(newObj);
    Object deserialized = fory.deserialize(bytes1);
    Assert.assertTrue(
        TestUtils.objectCommonFieldsEquals(
            CollectionFields.copyToCanEqual(deserialized, deserialized.getClass().newInstance()),
            CollectionFields.copyToCanEqual(newObj, newObj.getClass().newInstance())));
    Assert.assertEquals(deserialized.getClass(), CollectionFields.class);
    byte[] bytes2 = fory.serialize(deserialized);
    Object obj2 = newFory.deserialize(bytes2);
    TestUtils.objectFieldsEquals(
        CollectionFields.copyToCanEqual(obj2, obj2.getClass().newInstance()),
        CollectionFields.copyToCanEqual(newObj, newObj.getClass().newInstance()),
        true);

    byte[] objBytes = fory.serialize(collectionFields);
    Object obj3 = newFory.deserialize(objBytes);
    Assert.assertTrue(
        TestUtils.objectCommonFieldsEquals(
            CollectionFields.copyToCanEqual(obj3, obj3.getClass().newInstance()),
            CollectionFields.copyToCanEqual(newObj, newObj.getClass().newInstance())));
  }

  @Test(dataProvider = "twoBoolOptions")
  public void testCompatibleModeSkipsRemovedTreeMapSubclassField(
      boolean asyncCompilation, boolean codegen) {
    byte[] bytes = writeCatalogSnapshotV1(asyncCompilation, codegen);

    Fory reader = catalogFory(asyncCompilation, codegen);
    reader.register(CatalogSnapshotV2.class, CATALOG_SNAPSHOT_CLASS_ID);
    reader.register(MovieDocumentV2.class, MOVIE_DOCUMENT_CLASS_ID);
    reader.register(LegacyLabelsV2.class, LEGACY_LABELS_CLASS_ID);
    reader.register(NullableKeyLabelsV2.class, NULLABLE_KEY_LABELS_CLASS_ID);

    CatalogSnapshotV2 decoded = reader.deserialize(bytes, CatalogSnapshotV2.class);
    Assert.assertEquals(decoded.batchId, "batch-1");
    Assert.assertEquals(decoded.movies.size(), 1);
    Assert.assertEquals(decoded.movies.get(0).id, "m-1");
  }

  @Test
  public void testWriteCompatibleMap() throws Exception {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    MapFields mapFields = UnmodifiableSerializersTest.createMapFields();
    {
      Object o = serDe(fory, mapFields);
      Object o1 = MapFields.copyToCanEqual(o, o.getClass().newInstance());
      Object o2 = MapFields.copyToCanEqual(mapFields, mapFields.getClass().newInstance());
      Assert.assertEquals(o1, o2);
    }
    Class<?> cls = ClassUtils.createCompatibleClass3();
    Object newObj = cls.newInstance();
    TestUtils.unsafeCopy(mapFields, newObj);
    Fory newFory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .withClassLoader(cls.getClassLoader())
            .build();
    byte[] bytes1 = newFory.serialize(newObj);
    Object deserialized = fory.deserialize(bytes1);
    Assert.assertTrue(
        TestUtils.objectCommonFieldsEquals(
            MapFields.copyToCanEqual(deserialized, deserialized.getClass().newInstance()),
            MapFields.copyToCanEqual(newObj, newObj.getClass().newInstance())));
    Assert.assertEquals(deserialized.getClass(), MapFields.class);
    byte[] bytes2 = fory.serialize(deserialized);
    Object obj2 = newFory.deserialize(bytes2);
    TestUtils.objectFieldsEquals(
        MapFields.copyToCanEqual(obj2, obj2.getClass().newInstance()),
        MapFields.copyToCanEqual(newObj, newObj.getClass().newInstance()),
        true);

    byte[] objBytes = fory.serialize(mapFields);
    Object obj3 = newFory.deserialize(objBytes);
    Assert.assertTrue(
        TestUtils.objectCommonFieldsEquals(
            MapFields.copyToCanEqual(obj3, obj3.getClass().newInstance()),
            MapFields.copyToCanEqual(newObj, newObj.getClass().newInstance())));
  }

  @Data
  public static class CompressTestClass {
    public int f1;
    public int f2;
    public int f3;
  }

  @Test(dataProvider = "compressNumber")
  public void testCompressInt(boolean compressNumber) throws Exception {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withCompatible(true)
            .withNumberCompressed(compressNumber)
            .requireClassRegistration(false)
            .build();
    CompressTestClass o = new CompressTestClass();
    o.f1 = 100;
    o.f2 = Integer.MAX_VALUE;
    o.f3 = Integer.MIN_VALUE;
    serDeCheck(fory, o);
  }

  @Test(dataProvider = "compressNumber")
  public void testCompressNumberStruct(boolean compressNumber) throws Exception {
    Class<?> structClass = Struct.createNumberStructClass("CompatibleCompressIntStruct", 2);
    Fory fory =
        builder()
            .withNumberCompressed(compressNumber)
            .withCompatible(true)
            .withClassLoader(structClass.getClassLoader())
            .build();
    serDeCheck(fory, Struct.createPOJO(structClass));
  }

  @Test
  public void testSerializeDeserializeApis() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    BeanA beanA = BeanA.createBeanA(2);
    Assert.assertEquals(fory.deserialize(fory.serialize(beanA)), beanA);
    byte[] serialized = fory.serialize(beanA);
    Assert.assertEquals(fory.deserialize(serialized, BeanA.class), beanA);
  }

  private static byte[] writeCatalogSnapshotV1(boolean asyncCompilation, boolean codegen) {
    Fory writer = catalogFory(asyncCompilation, codegen);
    writer.register(CatalogSnapshotV1.class, CATALOG_SNAPSHOT_CLASS_ID);
    writer.register(MovieDocumentV1.class, MOVIE_DOCUMENT_CLASS_ID);
    writer.register(LegacyLabelsV1.class, LEGACY_LABELS_CLASS_ID);
    writer.register(NullableKeyLabelsV1.class, NULLABLE_KEY_LABELS_CLASS_ID);
    return writer.serialize(new CatalogSnapshotV1("batch-1"));
  }

  private static Fory catalogFory(boolean asyncCompilation, boolean codegen) {
    return Fory.builder()
        .withLanguage(Language.JAVA)
        .withCompatible(true)
        .requireClassRegistration(false)
        .withDeserializeUnknownClass(true)
        .withAsyncCompilation(asyncCompilation)
        .withCodegen(codegen)
        .build();
  }

  public static class LegacyLabelsV1 extends TreeMap<String, String> {
    public LegacyLabelsV1() {}
  }

  public static class LegacyLabelsV2 extends TreeMap<String, String> {
    public LegacyLabelsV2() {}
  }

  public static class NullableKeyLabelsV1 extends HashMap<String, String> {
    public NullableKeyLabelsV1() {}
  }

  public static class NullableKeyLabelsV2 extends HashMap<String, String> {
    public NullableKeyLabelsV2() {}
  }

  public static class MovieDocumentV1 {
    public String id;
    public LegacyLabelsV1 labels;
    public NullableKeyLabelsV1 nullableKeyLabels;

    public MovieDocumentV1() {}

    MovieDocumentV1(String id) {
      this.id = id;
      labels = new LegacyLabelsV1();
      labels.put("region", "se");
      labels.put("unknown", null);
      nullableKeyLabels = new NullableKeyLabelsV1();
      nullableKeyLabels.put(null, "fallback");
    }
  }

  public static class MovieDocumentV2 {
    public String id;

    public MovieDocumentV2() {}
  }

  public static class CatalogSnapshotV1 {
    public String batchId;
    public ArrayList<MovieDocumentV1> movies;

    public CatalogSnapshotV1() {}

    CatalogSnapshotV1(String batchId) {
      this.batchId = batchId;
      movies = new ArrayList<>();
      movies.add(new MovieDocumentV1("m-1"));
    }
  }

  public static class CatalogSnapshotV2 {
    public String batchId;
    public ArrayList<MovieDocumentV2> movies;

    public CatalogSnapshotV2() {}
  }
}
