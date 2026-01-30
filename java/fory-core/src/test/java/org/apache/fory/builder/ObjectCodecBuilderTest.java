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

package org.apache.fory.builder;

import static org.apache.fory.collection.Collections.ofArrayList;
import static org.apache.fory.collection.Collections.ofHashMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.codegen.javalangnameconflict.MethodSpiltObject;
import org.apache.fory.config.Language;
import org.apache.fory.serializer.collection.CollectionSerializersTest;
import org.apache.fory.test.bean.AccessBeans;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.test.bean.Foo;
import org.apache.fory.test.bean.Struct;
import org.codehaus.commons.compiler.util.reflect.ByteArrayClassLoader;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ObjectCodecBuilderTest extends ForyTestBase {

  @Test(dataProvider = "compressNumber")
  public void genCode(boolean compressNumber) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .withNumberCompressed(compressNumber)
            .requireClassRegistration(false)
            .build();
    new ObjectCodecBuilder(Foo.class, fory).genCode();
    // System.out.println(code);
    new ObjectCodecBuilder(BeanA.class, fory).genCode();
    new ObjectCodecBuilder(BeanB.class, fory).genCode();
    new ObjectCodecBuilder(Struct.createStructClass("ObjectCodecBuilderTestStruct", 1), fory)
        .genCode();
  }

  @Test
  public void testDefaultPackage() throws Exception {
    CompileUnit unit =
        new CompileUnit(
            "",
            "A",
            ("" + "public class A {\n" + "  public int f1;\n" + "  public Object f2;\n" + "}"));
    ByteArrayClassLoader classLoader =
        JaninoUtils.compile(Thread.currentThread().getContextClassLoader(), unit);
    Class<?> clz = classLoader.loadClass("A");
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withClassLoader(clz.getClassLoader())
            .requireClassRegistration(false)
            .build();
    Object obj = clz.newInstance();
    Field f1 = clz.getDeclaredField("f1");
    f1.setAccessible(true);
    f1.set(obj, 2);
    Object newObj = fory.deserialize(fory.serialize(obj));
    Assert.assertEquals(f1.get(newObj), 2);
  }

  @DataProvider(name = "codecConfig")
  public static Object[][] codecConfig() {
    return Sets.cartesianProduct(
            ImmutableSet.of(true, false), // xlang
            ImmutableSet.of(true, false), // referenceTracking
            ImmutableSet.of(true, false), // compressNumber
            ImmutableSet.of(1, 4, 7) // structFieldsRepeat
            )
        .stream()
        .map(List::toArray)
        .toArray(Object[][]::new);
  }

  @Test(dataProvider = "codecConfig")
  public void testSeqCodec(
      boolean xlang, boolean referenceTracking, boolean compressNumber, int fieldsRepeat) {
    Class<?> structClass = Struct.createStructClass("Struct" + fieldsRepeat, fieldsRepeat);
    Fory fory =
        Fory.builder()
            .withXlang(xlang)
            .withRefTracking(referenceTracking)
            .withClassLoader(structClass.getClassLoader())
            .withNumberCompressed(compressNumber)
            .requireClassRegistration(false)
            .build();
    Object struct = Struct.createPOJO(structClass);
    fory.register(structClass);
    if (!xlang) {
      // all fields must not be null for xlang mode, but struct has null fields.
      Assert.assertEquals(fory.deserialize(fory.serialize(struct)), struct);
    }
    checkMethodSize(structClass, fory);
  }

  private void checkMethodSize(Class<?> clz, Fory fory) {
    ObjectCodecBuilder codecBuilder = new ObjectCodecBuilder(clz, fory);
    CompileUnit compileUnit =
        new CompileUnit(
            CodeGenerator.getPackage(clz), codecBuilder.codecClassName(clz), codecBuilder::genCode);
    byte[] bytecode =
        JaninoUtils.toBytecode(clz.getClassLoader(), compileUnit).values().iterator().next();
    JaninoUtils.CodeStats classStats = JaninoUtils.getClassStats(bytecode);
    // System.out.println(classStats);
    classStats.methodsSize.entrySet().stream()
        .filter(e -> !e.getKey().equals("<init>"))
        .forEach(
            e ->
                Assert.assertTrue(
                    e.getValue() < 325,
                    String.format(
                        "Method %s for class %s has size %d > 325",
                        e.getKey(), compileUnit.getQualifiedClassName(), e.getValue())));
  }

  @Test
  public void testContainer() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    CollectionSerializersTest.Container container = new CollectionSerializersTest.Container();
    container.list1 = ofArrayList(new CollectionSerializersTest.NotFinal(1));
    container.map1 = ofHashMap("k", new CollectionSerializersTest.NotFinal(2));
    serDeCheck(fory, container);
    checkMethodSize(CollectionSerializersTest.Container.class, fory);
  }

  @Data
  public static class NestedContainer {
    private List<List<String>> list1;
    public Map<String, Set<List<String>>> map1;
  }

  @Test
  public void testNestedContainer() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    NestedContainer nestedContainer = new NestedContainer();
    List<List<String>> list1 = new ArrayList<>();
    list1.add(new ArrayList<>(ImmutableList.of("a", "b")));
    list1.add(new ArrayList<>(ImmutableList.of("a", "b")));
    nestedContainer.list1 = list1;
    Map<String, Set<List<String>>> map1 = new HashMap<>();
    map1.put("k1", new HashSet<>(list1));
    map1.put("k2", new HashSet<>(list1));
    nestedContainer.map1 = map1;
    serDeCheck(fory, nestedContainer);
    checkMethodSize(NestedContainer.class, fory);
  }

  @Test
  public void testAccessLevel() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    AccessBeans.PublicClass object = AccessBeans.createPublicClassObject();
    serDeCheckSerializer(fory, object, "Codec");
  }

  @Test
  public void testTypeConflictWhenMethodSplits() {
    // For issue #1370
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    fory.serialize(new MethodSpiltObject());
    Assert.assertTrue(
        fory.getClassResolver().getSerializer(MethodSpiltObject.class)
            instanceof Generated.GeneratedSerializer);
  }
}
