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
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.MapFields;
import org.apache.fory.test.bean.Struct;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ClassDefEncoderTest {

  @Test
  public void testBasicClassDef() throws Exception {
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
}
