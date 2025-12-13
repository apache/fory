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

package org.apache.fory.meta;

import static org.apache.fory.meta.ClassDefEncoder.buildFieldsInfo;
import static org.apache.fory.meta.ClassDefEncoder.getClassFields;

import java.io.Serializable;
import java.util.List;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.MapFields;
import org.apache.fory.test.bean.Struct;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ClassDefEncoderTest {

  @Test
  public void testBasicClassDef() {
    Fory fory = Fory.builder().withMetaShare(true).build();
    Class<ClassDefTest.TestFieldsOrderClass1> type = ClassDefTest.TestFieldsOrderClass1.class;
    List<ClassDef.FieldInfo> fieldsInfo = buildFieldsInfo(fory.getClassResolver(), type);
    MemoryBuffer buffer =
        ClassDefEncoder.encodeClassDef(
            fory.getClassResolver(), type, getClassFields(type, fieldsInfo), true);
    ClassDef classDef = ClassDef.readClassDef(fory, buffer);
    Assert.assertEquals(classDef.getClassName(), type.getName());
    Assert.assertEquals(classDef.getFieldsInfo().size(), type.getDeclaredFields().length);
    Assert.assertEquals(classDef.getFieldsInfo(), fieldsInfo);
  }

  @Test
  public void testBigMetaEncoding() {
    for (Class<?> type :
        new Class[] {
          MapFields.class, BeanA.class, Struct.createStructClass("TestBigMetaEncoding", 5)
        }) {
      Fory fory = Fory.builder().withMetaShare(true).build();
      ClassDef classDef = ClassDef.buildClassDef(fory, type);
      ClassDef classDef1 =
          ClassDef.readClassDef(fory, MemoryBuffer.fromByteArray(classDef.getEncoded()));
      Assert.assertEquals(classDef1, classDef);
    }
  }

  @Data
  public static class Foo1 {
    private int f1;
  }

  public static class Foo2 extends Foo1 {}

  @Test
  public void testEmptySubClassSerializer() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(true).build();
    ClassDef classDef = ClassDef.buildClassDef(fory, Foo2.class);
    ClassDef classDef1 =
        ClassDef.readClassDef(fory, MemoryBuffer.fromByteArray(classDef.getEncoded()));
    Assert.assertEquals(classDef, classDef1);
  }

  @Test
  public void testBigClassNameObject() {
    Fory fory = Fory.builder().withMetaShare(true).build();
    ClassDef classDef =
        ClassDef.buildClassDef(
            fory,
            TestClassLengthTestClassLengthTestClassLengthTestClassLengthTestClassLengthTestClassLengthTestClassLength
                .InnerClassTestLengthInnerClassTestLengthInnerClassTestLength.class);
    ClassDef classDef1 =
        ClassDef.readClassDef(fory, MemoryBuffer.fromByteArray(classDef.getEncoded()));
    Assert.assertEquals(classDef1, classDef);
  }

  @Data
  public static
  class TestClassLengthTestClassLengthTestClassLengthTestClassLengthTestClassLengthTestClassLengthTestClassLength
      implements Serializable {
    private String name;
    private InnerClassTestLengthInnerClassTestLengthInnerClassTestLength innerClassTestLength;

    @Data
    public static class InnerClassTestLengthInnerClassTestLengthInnerClassTestLength
        implements Serializable {
      private static final long serialVersionUID = -867612757789099089L;
      private Long itemId;
    }
  }

  @Test
  public void testPrependHeader() {
    MemoryBuffer inputBuffer = MemoryBuffer.newHeapBuffer(ClassDef.META_SIZE_MASKS + 1);
    inputBuffer.writerIndex(ClassDef.META_SIZE_MASKS + 1);
    MemoryBuffer outputBuffer = ClassDefEncoder.prependHeader(inputBuffer, true, false);

    long header = outputBuffer.readInt64();
    Assert.assertEquals(header & ClassDef.META_SIZE_MASKS, ClassDef.META_SIZE_MASKS);
    Assert.assertEquals(header & ClassDef.COMPRESS_META_FLAG, ClassDef.COMPRESS_META_FLAG);
    Assert.assertEquals(header & ClassDef.HAS_FIELDS_META_FLAG, 0);
  }

  @Test
  public void testAbstractParentClass() {
    Fory fory0 =
        Fory.builder()
            .withMetaShare(true)
            .withScopedMetaShare(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .build();
    Fory fory1 =
        Fory.builder()
            .withMetaShare(true)
            .withScopedMetaShare(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory1.register(BaseAbstractClass.class);
    fory1.register(ChildClass.class);
    for (Fory fory : new Fory[] {fory0, fory1}) {
      ClassDef classDef = ClassDef.buildClassDef(fory, ChildClass.class);
      ClassDef classDef1 =
          ClassDef.readClassDef(fory, MemoryBuffer.fromByteArray(classDef.getEncoded()));
      Assert.assertEquals(classDef, classDef1);
      ChildClass c = new ChildClass();
      c.setId("123");
      c.setName("test");
      byte[] serialized = fory.serialize(c);
      ChildClass c1 = fory.deserialize(serialized, ChildClass.class);
      Assert.assertEquals(c1.getId(), "123");
      Assert.assertEquals(c1.getName(), "test");
    }
  }

  @Data
  public abstract static class BaseAbstractClass {
    private String id;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  @Data
  public static class ChildClass extends BaseAbstractClass {
    private String name;
  }

  // Test classes for duplicate tag ID validation in ClassDefEncoder
  @Data
  public static class ClassWithDuplicateTagIds {
    @ForyField(id = 10)
    private String fieldA;

    @ForyField(id = 10)
    private String fieldB;

    @ForyField(id = 20)
    private int fieldC;
  }

  @Data
  public static class ClassWithValidTagIds {
    @ForyField(id = 10)
    private String fieldA;

    @ForyField(id = 20)
    private String fieldB;

    @ForyField(id = 30)
    private int fieldC;
  }

  @Data
  public static class ClassWithMixedFields {
    @ForyField(id = 15)
    private String annotatedField1;

    private String noAnnotation;

    @ForyField(id = 15) // Duplicate with annotatedField1
    private int annotatedField2;
  }

  @Test
  public void testBuildFieldsInfoWithDuplicateTagIds() {
    Fory fory = Fory.builder().withMetaShare(true).build();

    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> buildFieldsInfo(fory.getClassResolver(), ClassWithDuplicateTagIds.class));
  }

  @Test
  public void testBuildFieldsInfoWithValidTagIds() {
    Fory fory = Fory.builder().withMetaShare(true).build();

    // Should not throw any exception
    List<ClassDef.FieldInfo> fieldsInfo =
        buildFieldsInfo(fory.getClassResolver(), ClassWithValidTagIds.class);

    Assert.assertEquals(fieldsInfo.size(), 3);
    // Verify all fields have the correct tag IDs
    for (ClassDef.FieldInfo fieldInfo : fieldsInfo) {
      Assert.assertTrue(fieldInfo.hasTag());
    }
  }

  @Test
  public void testBuildFieldsInfoWithMixedFields() {
    Fory fory = Fory.builder().withMetaShare(true).build();

    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> buildFieldsInfo(fory.getClassResolver(), ClassWithMixedFields.class));
  }
}
