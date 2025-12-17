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

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.config.Language;
import org.apache.fory.resolver.TypeResolver;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TypeDefEncoderTest {

  // Test data: Class with duplicate tag IDs (both set to 100)
  @Data
  public static class ClassWithDuplicateTagIds {
    @ForyField(id = 100)
    private String field1;

    @ForyField(id = 100)
    private String field2;

    @ForyField(id = 200)
    private int field3;
  }

  // Test data: Class with duplicate tag IDs set to 0
  @Data
  public static class ClassWithDuplicateTagIdsZero {
    @ForyField(id = 0)
    private String field1;

    @ForyField(id = 0)
    private int field2;
  }

  // Test data: Class with valid unique tag IDs
  @Data
  public static class ClassWithValidTagIds {
    @ForyField(id = 100)
    private String field1;

    @ForyField(id = 200)
    private String field2;

    @ForyField(id = 300)
    private int field3;
  }

  // Test data: Class with mixed annotations (with and without tags)
  @Data
  public static class ClassWithMixedAnnotations {
    @ForyField(id = 50)
    private String annotatedField1;

    private String noAnnotation;

    @ForyField(id = -1) // -1 means use field name
    private String optOutField;

    @ForyField(id = 60)
    private int annotatedField2;
  }

  // Test data: Class with duplicate tag IDs in mixed scenario
  @Data
  public static class ClassWithMixedDuplicateTagIds {
    @ForyField(id = 50)
    private String annotatedField1;

    private String noAnnotation;

    @ForyField(id = -1) // -1 means use field name
    private String optOutField;

    @ForyField(id = 50) // Duplicate with annotatedField1
    private int annotatedField2;
  }

  // Test data: Class with single field
  @Data
  public static class ClassWithSingleField {
    @ForyField(id = 42)
    private String field;
  }

  // Test data: Class with no annotations
  @Data
  public static class ClassWithNoAnnotations {
    private String field1;
    private int field2;
    private double field3;
  }

  // Test data: Class with all fields using field names (tagId = -1)
  @Data
  public static class ClassWithAllFieldNames {
    @ForyField(id = -1)
    private String field1;

    @ForyField(id = -1)
    private int field2;

    @ForyField(id = -1)
    private double field3;
  }

  // Test data: Class with large tag IDs
  @Data
  public static class ClassWithLargeTagIds {
    @ForyField(id = 32000)
    private String field1;

    @ForyField(id = 32767) // Max short value
    private int field2;
  }

  @Test
  public void testBuildFieldsInfoWithDuplicateTagIds() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withMetaShare(true).build();
    fory.register(ClassWithDuplicateTagIds.class);
    TypeResolver resolver = fory.getXtypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithDuplicateTagIds.class, "field1"),
            getField(ClassWithDuplicateTagIds.class, "field2"),
            getField(ClassWithDuplicateTagIds.class, "field3"));

    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> TypeDefEncoder.buildFieldsInfo(resolver, ClassWithDuplicateTagIds.class, fields));
  }

  @Test
  public void testBuildFieldsInfoWithDuplicateTagIdsZero() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withMetaShare(true).build();
    fory.register(ClassWithDuplicateTagIdsZero.class);
    TypeResolver resolver = fory.getXtypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithDuplicateTagIdsZero.class, "field1"),
            getField(ClassWithDuplicateTagIdsZero.class, "field2"));

    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> TypeDefEncoder.buildFieldsInfo(resolver, ClassWithDuplicateTagIdsZero.class, fields));
  }

  @Test
  public void testBuildFieldsInfoWithValidTagIds() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withMetaShare(true).build();
    fory.register(ClassWithValidTagIds.class);
    TypeResolver resolver = fory.getXtypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithValidTagIds.class, "field1"),
            getField(ClassWithValidTagIds.class, "field2"),
            getField(ClassWithValidTagIds.class, "field3"));

    List<ClassDef.FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithValidTagIds.class, fields);

    Assert.assertEquals(fieldInfos.size(), 3);

    // Verify all fields have the correct tag IDs
    Assert.assertTrue(fieldInfos.get(0).hasFieldId());
    Assert.assertEquals(fieldInfos.get(0).getFieldId(), (short) 100);
    Assert.assertEquals(fieldInfos.get(0).getFieldName(), "field1");

    Assert.assertTrue(fieldInfos.get(1).hasFieldId());
    Assert.assertEquals(fieldInfos.get(1).getFieldId(), (short) 200);
    Assert.assertEquals(fieldInfos.get(1).getFieldName(), "field2");

    Assert.assertTrue(fieldInfos.get(2).hasFieldId());
    Assert.assertEquals(fieldInfos.get(2).getFieldId(), (short) 300);
    Assert.assertEquals(fieldInfos.get(2).getFieldName(), "field3");
  }

  @Test
  public void testBuildFieldsInfoWithMixedAnnotations() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withMetaShare(true).build();
    fory.register(ClassWithMixedAnnotations.class);
    TypeResolver resolver = fory.getXtypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithMixedAnnotations.class, "annotatedField1"),
            getField(ClassWithMixedAnnotations.class, "noAnnotation"),
            getField(ClassWithMixedAnnotations.class, "optOutField"),
            getField(ClassWithMixedAnnotations.class, "annotatedField2"));

    List<ClassDef.FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithMixedAnnotations.class, fields);

    Assert.assertEquals(fieldInfos.size(), 4);

    // annotatedField1 should have tag 50
    Assert.assertTrue(fieldInfos.get(0).hasFieldId());
    Assert.assertEquals(fieldInfos.get(0).getFieldId(), (short) 50);
    Assert.assertEquals(fieldInfos.get(0).getFieldName(), "annotatedField1");

    // noAnnotation should not have a tag (uses field name)
    Assert.assertFalse(fieldInfos.get(1).hasFieldId());
    Assert.assertEquals(fieldInfos.get(1).getFieldName(), "noAnnotation");

    // optOutField with id=-1 should not have a tag (uses field name)
    Assert.assertFalse(fieldInfos.get(2).hasFieldId());
    Assert.assertEquals(fieldInfos.get(2).getFieldName(), "optOutField");

    // annotatedField2 should have tag 60
    Assert.assertTrue(fieldInfos.get(3).hasFieldId());
    Assert.assertEquals(fieldInfos.get(3).getFieldId(), (short) 60);
    Assert.assertEquals(fieldInfos.get(3).getFieldName(), "annotatedField2");
  }

  @Test
  public void testBuildFieldsInfoWithMixedDuplicateTagIds() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withMetaShare(true).build();
    fory.register(ClassWithMixedDuplicateTagIds.class);
    TypeResolver resolver = fory.getXtypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithMixedDuplicateTagIds.class, "annotatedField1"),
            getField(ClassWithMixedDuplicateTagIds.class, "noAnnotation"),
            getField(ClassWithMixedDuplicateTagIds.class, "optOutField"),
            getField(ClassWithMixedDuplicateTagIds.class, "annotatedField2"));

    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            TypeDefEncoder.buildFieldsInfo(resolver, ClassWithMixedDuplicateTagIds.class, fields));
  }

  @Test
  public void testBuildFieldsInfoWithSingleField() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withMetaShare(true).build();
    fory.register(ClassWithSingleField.class);
    TypeResolver resolver = fory.getXtypeResolver();

    List<Field> fields = Collections.singletonList(getField(ClassWithSingleField.class, "field"));

    List<ClassDef.FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithSingleField.class, fields);

    Assert.assertEquals(fieldInfos.size(), 1);
    Assert.assertTrue(fieldInfos.get(0).hasFieldId());
    Assert.assertEquals(fieldInfos.get(0).getFieldId(), (short) 42);
    Assert.assertEquals(fieldInfos.get(0).getFieldName(), "field");
  }

  @Test
  public void testBuildFieldsInfoWithNoAnnotations() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withMetaShare(true).build();
    fory.register(ClassWithNoAnnotations.class);
    TypeResolver resolver = fory.getXtypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithNoAnnotations.class, "field1"),
            getField(ClassWithNoAnnotations.class, "field2"),
            getField(ClassWithNoAnnotations.class, "field3"));

    List<ClassDef.FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithNoAnnotations.class, fields);

    Assert.assertEquals(fieldInfos.size(), 3);

    // All fields should not have tags (use field names)
    for (ClassDef.FieldInfo fieldInfo : fieldInfos) {
      Assert.assertFalse(fieldInfo.hasFieldId());
    }
  }

  @Test
  public void testBuildFieldsInfoWithAllFieldNames() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withMetaShare(true).build();
    fory.register(ClassWithAllFieldNames.class);
    TypeResolver resolver = fory.getXtypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithAllFieldNames.class, "field1"),
            getField(ClassWithAllFieldNames.class, "field2"),
            getField(ClassWithAllFieldNames.class, "field3"));

    List<ClassDef.FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithAllFieldNames.class, fields);

    Assert.assertEquals(fieldInfos.size(), 3);

    // All fields with id=-1 should not have tags (use field names)
    for (ClassDef.FieldInfo fieldInfo : fieldInfos) {
      Assert.assertFalse(fieldInfo.hasFieldId());
    }
  }

  @Test
  public void testBuildFieldsInfoWithLargeTagIds() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withMetaShare(true).build();
    fory.register(ClassWithLargeTagIds.class);
    TypeResolver resolver = fory.getXtypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithLargeTagIds.class, "field1"),
            getField(ClassWithLargeTagIds.class, "field2"));

    List<ClassDef.FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithLargeTagIds.class, fields);

    Assert.assertEquals(fieldInfos.size(), 2);

    Assert.assertTrue(fieldInfos.get(0).hasFieldId());
    Assert.assertEquals(fieldInfos.get(0).getFieldId(), (short) 32000);

    Assert.assertTrue(fieldInfos.get(1).hasFieldId());
    Assert.assertEquals(fieldInfos.get(1).getFieldId(), (short) 32767);
  }

  @Test
  public void testBuildFieldsInfoWithEmptyFieldList() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withMetaShare(true).build();
    fory.register(ClassWithValidTagIds.class);
    TypeResolver resolver = fory.getXtypeResolver();

    List<Field> fields = Collections.emptyList();

    List<ClassDef.FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithValidTagIds.class, fields);

    Assert.assertEquals(fieldInfos.size(), 0);
  }

  /** Helper method to get a field from a class by name. */
  private Field getField(Class<?> clazz, String fieldName) {
    try {
      return clazz.getDeclaredField(fieldName);
    } catch (NoSuchFieldException e) {
      throw new RuntimeException(
          "Field not found: " + fieldName + " in class " + clazz.getName(), e);
    }
  }
}
