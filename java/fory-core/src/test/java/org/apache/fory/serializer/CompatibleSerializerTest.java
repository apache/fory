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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.TestUtils;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.serializer.collection.UnmodifiableSerializersTest;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.test.bean.CollectionFields;
import org.apache.fory.test.bean.Foo;
import org.apache.fory.test.bean.MapFields;
import org.apache.fory.test.bean.Struct;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for compatible mode serialization using meta-shared approach. These tests verify
 * forward/backward compatibility when using {@link CompatibleMode#COMPATIBLE} with scoped meta
 * share.
 */
public class CompatibleSerializerTest extends ForyTestBase {

  @Test
  public void testWrite() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
              .withLanguage(Language.JAVA)
              .withRefTracking(true)
              .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
  public void testWriteNestedCollection() throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .withCodegen(enableCodegen)
            .build();
    BeanA beanA = BeanA.createBeanA(2);
    Class<?> cls = ClassUtils.createCompatibleClass1();
    Object newBeanA = cls.newInstance();
    TestUtils.unsafeCopy(beanA, newBeanA);
    Fory newFory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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

  @Test
  public void testWriteCompatibleMap() throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withClassLoader(structClass.getClassLoader())
            .build();
    serDeCheck(fory, Struct.createPOJO(structClass));
  }

  @Test
  public void testSerializeJavaObject() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .build();
    BeanA beanA = BeanA.createBeanA(2);
    Assert.assertEquals(fory.deserialize(fory.serialize(beanA)), beanA);
    byte[] serialized = fory.serializeJavaObject(beanA);
    Assert.assertEquals(fory.deserializeJavaObject(serialized, BeanA.class), beanA);
  }

  @Test
  public void testSerializeJavaObjectWithTypeInfo() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .build();
    BeanA beanA = BeanA.createBeanA(2);
    byte[] serialized = fory.serializeJavaObjectAndClass(beanA);
    Assert.assertEquals(fory.deserializeJavaObjectAndClass(serialized), beanA);
  }
}
