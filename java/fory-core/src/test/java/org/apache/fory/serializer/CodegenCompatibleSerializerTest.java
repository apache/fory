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

import static org.apache.fory.serializer.ClassUtils.loadClass;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.TestUtils;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.serializer.collection.UnmodifiableSerializersTest;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.test.bean.CollectionFields;
import org.apache.fory.test.bean.Foo;
import org.apache.fory.test.bean.MapFields;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests for compatible mode serialization using meta-shared approach with codegen. These tests
 * verify forward/backward compatibility when using compatible mode with scoped meta share and
 * codegen enabled.
 */
public class CodegenCompatibleSerializerTest extends ForyTestBase {

  @DataProvider(name = "config")
  public static Object[][] config() {
    return Sets.cartesianProduct(
            ImmutableSet.of(true), // referenceTracking
            ImmutableSet.of(true)) // enable codegen
        .stream()
        .map(List::toArray)
        .toArray(Object[][]::new);
  }

  private ForyBuilder foryBuilder() {
    return Fory.builder()
        .withXlang(false)
        .requireClassRegistration(false)
        .suppressClassRegistrationWarnings(true);
  }

  @Test(dataProvider = "config")
  public void testWrite(boolean referenceTracking, boolean enableCodegen) {
    Fory fory =
        foryBuilder()
            .withRefTracking(referenceTracking)
            .withCodegen(enableCodegen)
            .withCompatible(true)
            .build();
    serDeCheck(fory, Foo.create());
    serDeCheck(fory, BeanB.createBeanB(2));
    serDeCheck(fory, BeanA.createBeanA(2));
  }

  @Test(dataProvider = "config")
  public void testCopy(boolean referenceTracking, boolean enableCodegen) {
    Fory fory =
        foryBuilder()
            .withRefCopy(referenceTracking)
            .withCodegen(enableCodegen)
            .withCompatible(true)
            .build();
    copyCheck(fory, Foo.create());
    copyCheck(fory, BeanB.createBeanB(2));
    copyCheck(fory, BeanA.createBeanA(2));
  }

  @Test(dataProvider = "config")
  public void testWriteCompatibleBasic(boolean referenceTracking, boolean enableCodegen)
      throws Exception {
    Supplier<ForyBuilder> builder =
        () ->
            Fory.builder()
                .withXlang(false)
                .withRefTracking(referenceTracking)
                .withCodegen(enableCodegen)
                .withCompatible(true)
                .requireClassRegistration(false);
    Fory fory = builder.get().build();
    Object foo = Foo.create();
    for (Class<?> fooClass :
        new Class<?>[] {
          Foo.createCompatibleClass1(), Foo.createCompatibleClass2(), Foo.createCompatibleClass3(),
        }) {
      Object newFoo = fooClass.newInstance();
      TestUtils.unsafeCopy(foo, newFoo);
      Fory newFory = builder.get().withClassLoader(fooClass.getClassLoader()).build();

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

  @Test(dataProvider = "config")
  public void testWriteCompatibleBasicCopy(boolean referenceTracking, boolean enableCodegen)
      throws Exception {
    Supplier<ForyBuilder> builder =
        () ->
            Fory.builder()
                .withXlang(false)
                .withRefCopy(referenceTracking)
                .withCodegen(enableCodegen)
                .withCompatible(true)
                .requireClassRegistration(false);
    Fory fory = builder.get().build();
    Object foo = Foo.create();
    for (Class<?> fooClass :
        new Class<?>[] {
          Foo.createCompatibleClass1(), Foo.createCompatibleClass2(), Foo.createCompatibleClass3(),
        }) {
      Object newFoo = fooClass.newInstance();
      TestUtils.unsafeCopy(foo, newFoo);
      Fory newFory = builder.get().withClassLoader(fooClass.getClassLoader()).build();
      {
        Object copy = fory.copy(newFoo);
        Assert.assertEquals(copy.getClass().getName(), Foo.class.getName());
        Assert.assertTrue(TestUtils.objectCommonFieldsEquals(copy, newFoo));
      }
      {
        Object o3 = newFory.copy(foo);
        TestUtils.objectFieldsEquals(o3, foo, true);
      }
    }
  }

  @Test(dataProvider = "config")
  public void testWriteCompatibleCollectionBasic(boolean referenceTracking, boolean enableCodegen)
      throws Exception {
    BeanA beanA = BeanA.createBeanA(2);
    Supplier<ForyBuilder> builder =
        () ->
            Fory.builder()
                .withXlang(false)
                .withRefTracking(referenceTracking)
                .withCodegen(enableCodegen)
                .withCompatible(true)
                .requireClassRegistration(false);
    Fory fory = builder.get().build();
    String pkg = BeanA.class.getPackage().getName();
    String code =
        ""
            + "package "
            + pkg
            + ";\n"
            + "import java.util.*;\n"
            + "import java.math.*;\n"
            + "public class BeanA {\n"
            + "  private List<Double> doubleList;\n"
            + "  private Iterable<BeanB> beanBIterable;\n"
            + "  private List<BeanB> beanBList;\n"
            + "}";
    Class<?> cls1 =
        loadClass(
            BeanA.class,
            code,
            CodegenCompatibleSerializerTest.class + "testWriteCompatibleCollectionBasic_1");
    Fory fory1 = builder.get().withClassLoader(cls1.getClassLoader()).build();
    code =
        ""
            + "package "
            + pkg
            + ";\n"
            + "import java.util.*;\n"
            + "import java.math.*;\n"
            + "public class BeanA {\n"
            + "  private List<Double> doubleList;\n"
            + "  private Iterable<BeanB> beanBIterable;\n"
            + "}";
    Class<?> cls2 =
        loadClass(
            BeanA.class,
            code,
            CodegenCompatibleSerializerTest.class + "testWriteCompatibleCollectionBasic_2");
    Object newBeanA = cls2.newInstance();
    TestUtils.unsafeCopy(beanA, newBeanA);
    Fory fory2 = builder.get().withClassLoader(cls2.getClassLoader()).build();
    byte[] newBeanABytes = fory2.serialize(newBeanA);
    Object deserialized = fory1.deserialize(newBeanABytes);
    Assert.assertTrue(TestUtils.objectCommonFieldsEquals(deserialized, newBeanA));
    Assert.assertEquals(deserialized.getClass(), cls1);
    byte[] beanABytes = fory1.serialize(deserialized);
    TestUtils.objectFieldsEquals(fory2.deserialize(beanABytes), newBeanA, true);

    byte[] objBytes = fory1.serialize(beanA);
    Object obj2 = fory2.deserialize(objBytes);
    Assert.assertTrue(TestUtils.objectCommonFieldsEquals(obj2, newBeanA));

    Assert.assertEquals(fory.deserialize(fory2.serialize(beanA)), beanA);
  }

  @Test(dataProvider = "config")
  public void testWriteCompatibleContainer(boolean referenceTracking, boolean enableCodegen)
      throws Exception {
    Supplier<ForyBuilder> builder =
        () ->
            Fory.builder()
                .withXlang(false)
                .withRefTracking(referenceTracking)
                .withCodegen(enableCodegen)
                .withCompatible(true)
                .requireClassRegistration(false);
    Fory fory = builder.get().build();
    BeanA beanA = BeanA.createBeanA(2);
    serDe(fory, beanA);
    Class<?> cls = ClassUtils.createCompatibleClass1();
    Object newBeanA = cls.newInstance();
    TestUtils.unsafeCopy(beanA, newBeanA);
    Fory newFory = builder.get().withClassLoader(cls.getClassLoader()).build();
    byte[] newBeanABytes = newFory.serialize(newBeanA);
    BeanA deserialized = (BeanA) fory.deserialize(newBeanABytes);
    Assert.assertTrue(TestUtils.objectCommonFieldsEquals(deserialized, newBeanA));
    Assert.assertEquals(deserialized.getClass(), BeanA.class);
    byte[] beanABytes = fory.serialize(deserialized);
    TestUtils.objectFieldsEquals(newFory.deserialize(beanABytes), newBeanA, true);

    byte[] objBytes = fory.serialize(beanA);
    Object obj2 = newFory.deserialize(objBytes);
    Assert.assertTrue(TestUtils.objectCommonFieldsEquals(obj2, newBeanA));
    Assert.assertEquals(fory.deserialize(newFory.serialize(beanA)), beanA);
  }

  @Test(dataProvider = "config")
  public void testWriteCompatibleCollection(boolean referenceTracking, boolean enableCodegen)
      throws Exception {
    Supplier<ForyBuilder> builder =
        () ->
            Fory.builder()
                .withXlang(false)
                .withRefTracking(referenceTracking)
                .withCodegen(enableCodegen)
                .withCompatible(true)
                .requireClassRegistration(false);
    Fory fory = builder.get().build();
    CollectionFields collectionFields = UnmodifiableSerializersTest.createCollectionFields();
    {
      Object o = serDe(fory, collectionFields);
      Object o1 = CollectionFields.copyToCanEqual(o, o.getClass().newInstance());
      Object o2 =
          CollectionFields.copyToCanEqual(
              collectionFields, collectionFields.getClass().newInstance());
      Assert.assertEquals(o1, o2);
    }
    Class<?> cls2 = ClassUtils.createCompatibleClass2();
    Object newObj = cls2.newInstance();
    TestUtils.unsafeCopy(collectionFields, newObj);
    Fory fory2 = builder.get().withClassLoader(cls2.getClassLoader()).build();
    byte[] bytes1 = fory2.serialize(newObj);
    Object deserialized = fory.deserialize(bytes1);
    Assert.assertTrue(
        TestUtils.objectCommonFieldsEquals(
            CollectionFields.copyToCanEqual(deserialized, deserialized.getClass().newInstance()),
            CollectionFields.copyToCanEqual(newObj, newObj.getClass().newInstance())));
    Assert.assertEquals(deserialized.getClass(), CollectionFields.class);

    byte[] bytes2 = fory.serialize(deserialized);
    Object obj2 = fory2.deserialize(bytes2);
    TestUtils.objectFieldsEquals(
        CollectionFields.copyToCanEqual(obj2, obj2.getClass().newInstance()),
        CollectionFields.copyToCanEqual(newObj, newObj.getClass().newInstance()),
        true);

    byte[] objBytes = fory.serialize(collectionFields);
    Object obj3 = fory2.deserialize(objBytes);
    Assert.assertTrue(
        TestUtils.objectCommonFieldsEquals(
            CollectionFields.copyToCanEqual(obj3, obj3.getClass().newInstance()),
            CollectionFields.copyToCanEqual(newObj, newObj.getClass().newInstance())));

    Assert.assertEquals(
        ((CollectionFields) (fory.deserialize(fory2.serialize(collectionFields)))).toCanEqual(),
        collectionFields.toCanEqual());
  }

  @Test(dataProvider = "config")
  public void testWriteCompatibleMap(boolean referenceTracking, boolean enableCodegen)
      throws Exception {
    Supplier<ForyBuilder> builder =
        () ->
            Fory.builder()
                .withXlang(false)
                .withRefTracking(referenceTracking)
                .withCodegen(enableCodegen)
                .withCompatible(true)
                .requireClassRegistration(false);
    Fory fory = builder.get().build();
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
    Fory fory2 = builder.get().withClassLoader(cls.getClassLoader()).build();
    byte[] bytes1 = fory2.serialize(newObj);
    Object deserialized = fory.deserialize(bytes1);
    Assert.assertTrue(
        TestUtils.objectCommonFieldsEquals(
            MapFields.copyToCanEqual(deserialized, deserialized.getClass().newInstance()),
            MapFields.copyToCanEqual(newObj, newObj.getClass().newInstance())));
    Assert.assertEquals(deserialized.getClass(), MapFields.class);

    byte[] bytes2 = fory.serialize(deserialized);
    Object obj2 = fory2.deserialize(bytes2);
    Assert.assertTrue(
        TestUtils.objectCommonFieldsEquals(
            MapFields.copyToCanEqual(obj2, obj2.getClass().newInstance()),
            MapFields.copyToCanEqual(newObj, newObj.getClass().newInstance())));

    byte[] objBytes = fory.serialize(mapFields);
    Object obj3 = fory2.deserialize(objBytes);
    Assert.assertTrue(
        TestUtils.objectCommonFieldsEquals(
            MapFields.copyToCanEqual(obj3, obj3.getClass().newInstance()),
            MapFields.copyToCanEqual(newObj, newObj.getClass().newInstance())));

    Assert.assertEquals(
        ((MapFields) (fory.deserialize(fory2.serialize(mapFields)))).toCanEqual(),
        mapFields.toCanEqual());
  }
}
