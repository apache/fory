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
import org.apache.fory.platform.JdkVersion;
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
            .withXlang(false)
            .withRefTracking(false)
            .withNumberCompressed(compressNumber)
            .requireClassRegistration(false)
            .withCompatible(false)
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
            .withXlang(false)
            .withRefTracking(true)
            .withClassLoader(clz.getClassLoader())
            .requireClassRegistration(false)
            .withCompatible(false)
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
            .withCompatible(xlang)
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
        .filter(e -> !e.getKey().equals("<init>") && !e.getKey().equals("write"))
        .forEach(
            e ->
                Assert.assertTrue(
                    e.getValue() < 325,
                    String.format(
                        "Method %s for class %s has size %d > 325",
                        e.getKey(), compileUnit.getQualifiedClassName(), e.getValue())));
  }

  @Data
  public static class SmallInlineFields {
    public int f1;
    public long f2;
    public String f3;
    public List<String> f4;
    public Object f5;
  }

  @Test(dataProvider = "compressNumber")
  public void testSmallStructInlinesFieldGroups(boolean compressNumber) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withNumberCompressed(compressNumber)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    ObjectCodecBuilder codecBuilder = new ObjectCodecBuilder(SmallInlineFields.class, fory);
    CompileUnit compileUnit =
        new CompileUnit(
            CodeGenerator.getPackage(SmallInlineFields.class),
            codecBuilder.codecClassName(SmallInlineFields.class),
            codecBuilder::genCode);
    byte[] bytecode =
        JaninoUtils.toBytecode(SmallInlineFields.class.getClassLoader(), compileUnit)
            .values()
            .iterator()
            .next();
    JaninoUtils.CodeStats classStats = JaninoUtils.getClassStats(bytecode);
    Assert.assertFalse(
        classStats.methodsSize.keySet().stream()
            .anyMatch(name -> name.startsWith("writeFields") || name.startsWith("readField")));
  }

  @Test
  public void testContainer() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
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

  public static final class VarHandleFinalFields {
    public final int publicFinal;
    protected final long protectedFinal;
    final String packageFinal;
    private final String privateFinal;
    private int privateValue;

    public VarHandleFinalFields() {
      this(0, 0, null, null, 0);
    }

    VarHandleFinalFields(
        int publicFinal,
        long protectedFinal,
        String packageFinal,
        String privateFinal,
        int privateValue) {
      this.publicFinal = publicFinal;
      this.protectedFinal = protectedFinal;
      this.packageFinal = packageFinal;
      this.privateFinal = privateFinal;
      this.privateValue = privateValue;
    }
  }

  @Test
  public void testJdk25VarHandleFieldAccess() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    String code = new ObjectCodecBuilder(VarHandleFinalFields.class, fory).genCode();
    if (JdkVersion.MAJOR_VERSION >= 25) {
      Assert.assertTrue(code.contains("java.lang.invoke.VarHandle"));
      Assert.assertTrue(code.contains("publicFinal_varHandle_"));
      Assert.assertTrue(code.contains("protectedFinal_varHandle_"));
      Assert.assertTrue(code.contains("packageFinal_varHandle_"));
      Assert.assertTrue(code.contains("privateFinal_varHandle_"));
      Assert.assertTrue(code.contains("privateValue_varHandle_"));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.getVarHandle"));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.getObject"));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.setInt"));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.setLong"));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.setObject"));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.setInt(publicFinal_varHandle_"));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.setLong(protectedFinal_varHandle_"));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.setObject(packageFinal_varHandle_"));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.setObject(privateFinal_varHandle_"));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.setInt(privateValue_varHandle_"));
      Assert.assertFalse(code.contains("FieldAccessor.createAccessor"));
      Assert.assertFalse(code.contains("_varHandle_.get("));
      Assert.assertFalse(code.contains("_varHandle_.set("));
    }
    VarHandleFinalFields bean = new VarHandleFinalFields(1, 2L, "package", "private", 3);
    VarHandleFinalFields copy = (VarHandleFinalFields) fory.deserialize(fory.serialize(bean));
    Assert.assertEquals(copy.publicFinal, 1);
    Assert.assertEquals(copy.protectedFinal, 2L);
    Assert.assertEquals(copy.packageFinal, "package");
    Assert.assertEquals(copy.privateFinal, "private");
    Assert.assertEquals(copy.privateValue, 3);
  }

  public static class VarHandleDuplicateParent {
    private final int value;

    public VarHandleDuplicateParent() {
      this(0);
    }

    VarHandleDuplicateParent(int value) {
      this.value = value;
    }

    public int parentValue() {
      return value;
    }
  }

  public static final class VarHandleDuplicateChild extends VarHandleDuplicateParent {
    private final int value;

    public VarHandleDuplicateChild() {
      this(0, 0);
    }

    VarHandleDuplicateChild(int parentValue, int childValue) {
      super(parentValue);
      this.value = childValue;
    }

    public int childValue() {
      return value;
    }
  }

  @Test
  public void testJdk25DuplicateVarHandles() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    String code = new ObjectCodecBuilder(VarHandleDuplicateChild.class, fory).genCode();
    if (JdkVersion.MAJOR_VERSION >= 25) {
      String parentHandle = duplicateValueVarHandleName(VarHandleDuplicateParent.class);
      String childHandle = duplicateValueVarHandleName(VarHandleDuplicateChild.class);
      Assert.assertTrue(code.contains(parentHandle));
      Assert.assertTrue(code.contains(childHandle));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.getInt(" + parentHandle));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.getInt(" + childHandle));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.setInt(" + parentHandle));
      Assert.assertTrue(code.contains("VarHandleCodegenSupport.setInt(" + childHandle));
    }
    VarHandleDuplicateChild bean = new VarHandleDuplicateChild(1, 2);
    VarHandleDuplicateChild copy = (VarHandleDuplicateChild) fory.deserialize(fory.serialize(bean));
    Assert.assertEquals(copy.parentValue(), 1);
    Assert.assertEquals(copy.childValue(), 2);
  }

  private static String duplicateValueVarHandleName(Class<?> declaringClass) {
    return declaringClass.getName().replaceAll("\\.|\\$", "_") + "_value_varHandle_";
  }

  @Test
  public void testAccessLevel() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    AccessBeans.PublicClass object = AccessBeans.createPublicClassObject();
    serDeCheckSerializer(fory, object, "Codec");
  }

  @Test
  public void testTypeConflictWhenMethodSplits() {
    // For issue #1370
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    fory.serialize(new MethodSpiltObject());
    Assert.assertTrue(
        fory.getTypeResolver().getSerializer(MethodSpiltObject.class)
            instanceof Generated.GeneratedSerializer);
  }
}
