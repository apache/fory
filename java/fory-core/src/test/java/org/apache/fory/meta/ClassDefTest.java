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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.test.bean.Foo;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.Types;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ClassDefTest extends ForyTestBase {
  static class TestFieldsOrderClass1 {
    private int intField2;
    private boolean booleanField;
    private Object objField;
    private long longField;
  }

  static class TestFieldsOrderClass2 extends TestFieldsOrderClass1 {
    private int intField1;
    private boolean booleanField;
    private int childIntField2;
    private boolean childBoolField1;
    private byte childByteField;
    private short childShortField;
    private long childLongField;
  }

  static class DuplicateFieldClass extends TestFieldsOrderClass1 {
    private int intField1;
    private boolean booleanField;
    private Object objField;
    private long longField;
  }

  static class ContainerClass extends TestFieldsOrderClass1 {
    private int intField1;
    private long longField;
    private Collection<String> collection;
    private List<Integer> list1;
    private List<Object> list2;
    private List list3;
    private Map<String, Object> map1;
    private Map<String, Integer> map2;
    private Map map3;
  }

  @Test
  public void testFieldsOrder() {
    List<Field> fieldList = new ArrayList<>();
    Collections.addAll(fieldList, TestFieldsOrderClass1.class.getDeclaredFields());
    Collections.addAll(fieldList, TestFieldsOrderClass2.class.getDeclaredFields());
    TreeSet<Field> sorted = new TreeSet<>(ClassDef.FIELD_COMPARATOR);
    sorted.addAll(fieldList);
    assertEquals(fieldList.size(), sorted.size());
    fieldList.sort(ClassDef.FIELD_COMPARATOR);
  }

  @Test
  public void testClassDefSerialization() throws NoSuchFieldException {
    Fory fory = Fory.builder().withMetaShare(true).build();
    {
      ClassDef classDef =
          ClassDef.buildClassDef(
              fory.getClassResolver(),
              TestFieldsOrderClass1.class,
              ImmutableList.of(TestFieldsOrderClass1.class.getDeclaredField("longField")));
      MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
      classDef.writeClassDef(buffer);
      ClassDef classDef1 = ClassDef.readClassDef(fory, buffer);
      assertEquals(classDef1.getClassName(), classDef.getClassName());
      assertEquals(classDef1, classDef);
    }
    {
      ClassDef classDef =
          ClassDef.buildClassDef(
              fory.getClassResolver(),
              TestFieldsOrderClass1.class,
              ReflectionUtils.getFields(TestFieldsOrderClass1.class, true));
      assertEquals(classDef.getClassName(), TestFieldsOrderClass1.class.getName());
      assertEquals(
          classDef.getFieldsInfo().size(),
          ReflectionUtils.getFields(TestFieldsOrderClass1.class, true).size());
      MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
      classDef.writeClassDef(buffer);
      ClassDef classDef1 = ClassDef.readClassDef(fory, buffer);
      assertEquals(classDef1.getClassName(), classDef.getClassName());
      assertEquals(classDef1, classDef);
    }
    {
      ClassDef classDef =
          ClassDef.buildClassDef(
              fory.getClassResolver(),
              TestFieldsOrderClass2.class,
              ReflectionUtils.getFields(TestFieldsOrderClass2.class, true));
      assertEquals(classDef.getClassName(), TestFieldsOrderClass2.class.getName());
      assertEquals(
          classDef.getFieldsInfo().size(),
          ReflectionUtils.getFields(TestFieldsOrderClass2.class, true).size());
      MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
      classDef.writeClassDef(buffer);
      ClassDef classDef1 = ClassDef.readClassDef(fory, buffer);
      assertEquals(classDef1.getClassName(), classDef.getClassName());
      assertEquals(classDef1, classDef);
    }
  }

  @Test
  public void testDuplicateFieldsClass() {
    Fory fory = Fory.builder().withMetaShare(true).build();
    {
      ClassDef classDef =
          ClassDef.buildClassDef(
              fory.getClassResolver(),
              DuplicateFieldClass.class,
              ReflectionUtils.getFields(DuplicateFieldClass.class, true));
      assertEquals(classDef.getClassName(), DuplicateFieldClass.class.getName());
      assertEquals(
          classDef.getFieldsInfo().size(),
          ReflectionUtils.getFields(DuplicateFieldClass.class, true).size());
      MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
      classDef.writeClassDef(buffer);
      ClassDef classDef1 = ClassDef.readClassDef(fory, buffer);
      assertEquals(classDef1.getClassName(), classDef.getClassName());
      assertEquals(classDef1, classDef);
    }
  }

  @Test
  public void testContainerClass() {
    Fory fory = Fory.builder().withMetaShare(true).build();
    List<Field> fields = ReflectionUtils.getFields(ContainerClass.class, true);
    ClassDef classDef =
        ClassDef.buildClassDef(fory.getClassResolver(), ContainerClass.class, fields);
    assertEquals(classDef.getClassName(), ContainerClass.class.getName());
    assertEquals(classDef.getFieldsInfo().size(), fields.size());
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    classDef.writeClassDef(buffer);
    ClassDef classDef1 = ClassDef.readClassDef(fory, buffer);
    assertEquals(classDef1.getClassName(), classDef.getClassName());
    assertEquals(classDef1, classDef);
  }

  @Test
  public void testInterface() {
    Fory fory = Fory.builder().withMetaShare(true).build();
    ClassDef classDef = ClassDef.buildClassDef(fory, Map.class);
    assertTrue(classDef.getFieldsInfo().isEmpty());
    assertTrue(classDef.hasFieldsMeta());
  }

  @Test
  public void testTypeExtInfo() {
    Fory fory = Fory.builder().withRefTracking(true).withMetaShare(true).build();
    ClassResolver classResolver = fory.getClassResolver();
    assertTrue(
        classResolver.needToWriteRef(
            TypeRef.of(Foo.class, new TypeExtMeta(Types.STRUCT, true, true))));
    assertFalse(
        classResolver.needToWriteRef(
            TypeRef.of(Foo.class, new TypeExtMeta(Types.STRUCT, true, false))));
  }

  // Test classes for duplicate tag ID validation
  static class ClassWithDuplicateTagIds {
    @ForyField(id = 1)
    private String field1;

    @ForyField(id = 1)
    private String field2;

    @ForyField(id = 2)
    private int field3;
  }

  static class ClassWithDuplicateTagIdsMultiple {
    @ForyField(id = 5)
    private String field1;

    @ForyField(id = 5)
    private String field2;

    @ForyField(id = 5)
    private int field3;
  }

  static class ClassWithValidTagIds {
    @ForyField(id = 1)
    private String field1;

    @ForyField(id = 2)
    private String field2;

    @ForyField(id = 3)
    private int field3;
  }

  @Test
  public void testDuplicateTagIdsThrowsException() {
    Fory fory = Fory.builder().withMetaShare(true).build();
    List<Field> fields = ReflectionUtils.getFields(ClassWithDuplicateTagIds.class, true);

    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            ClassDef.buildClassDef(
                fory.getClassResolver(), ClassWithDuplicateTagIds.class, fields));
  }

  @Test
  public void testDuplicateTagIdsMultipleThrowsException() {
    Fory fory = Fory.builder().withMetaShare(true).build();
    List<Field> fields = ReflectionUtils.getFields(ClassWithDuplicateTagIdsMultiple.class, true);

    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            ClassDef.buildClassDef(
                fory.getClassResolver(), ClassWithDuplicateTagIdsMultiple.class, fields));
  }

  @Test
  public void testValidTagIdsSucceeds() {
    Fory fory = Fory.builder().withMetaShare(true).build();
    List<Field> fields = ReflectionUtils.getFields(ClassWithValidTagIds.class, true);

    // Should not throw any exception
    ClassDef classDef =
        ClassDef.buildClassDef(fory.getClassResolver(), ClassWithValidTagIds.class, fields);
    assertEquals(classDef.getClassName(), ClassWithValidTagIds.class.getName());
    assertEquals(classDef.getFieldsInfo().size(), fields.size());
  }

  // Test classes for getDescriptors method
  static class TargetClassWithDuplicateTagIds {
    @ForyField(id = 100)
    private String field1;

    @ForyField(id = 100) // Duplicate tag ID
    private String field2;

    @ForyField(id = 200)
    private int field3;
  }

  static class TargetClassWithValidTags {
    @ForyField(id = 10)
    private String taggedField1;

    @ForyField(id = 20)
    private int taggedField2;

    private String normalField;
  }

  static class TargetClassWithMixedTags {
    @ForyField(id = 50)
    private String field1;

    private String field2;

    @ForyField(id = 60)
    private int field3;
  }

  @Test
  public void testGetDescriptorsWithDuplicateTagIds() {
    Fory fory = Fory.builder().withMetaShare(true).build();

    // Build a ClassDef with valid fields (no duplicates in ClassDef itself)
    List<Field> sourceFields = ReflectionUtils.getFields(ClassWithValidTagIds.class, true);
    ClassDef classDef =
        ClassDef.buildClassDef(fory.getClassResolver(), ClassWithValidTagIds.class, sourceFields);

    // Try to get descriptors for a class that has duplicate tag IDs
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            classDef.getDescriptors(fory.getClassResolver(), TargetClassWithDuplicateTagIds.class));
  }

  @Test
  public void testGetDescriptorsWithValidTags() {
    Fory fory = Fory.builder().withMetaShare(true).build();

    // Build a ClassDef with tagged fields
    List<Field> sourceFields = ReflectionUtils.getFields(TargetClassWithValidTags.class, true);
    ClassDef classDef =
        ClassDef.buildClassDef(
            fory.getClassResolver(), TargetClassWithValidTags.class, sourceFields);

    // Get descriptors should succeed
    List<Descriptor> descriptors =
        classDef.getDescriptors(fory.getClassResolver(), TargetClassWithValidTags.class);

    assertEquals(descriptors.size(), 3);
  }

  @Test
  public void testGetDescriptorsWithMixedTags() {
    Fory fory = Fory.builder().withMetaShare(true).build();

    // Build a ClassDef with mixed tagged and non-tagged fields
    List<Field> sourceFields = ReflectionUtils.getFields(TargetClassWithMixedTags.class, true);
    ClassDef classDef =
        ClassDef.buildClassDef(
            fory.getClassResolver(), TargetClassWithMixedTags.class, sourceFields);

    // Get descriptors should succeed
    List<Descriptor> descriptors =
        classDef.getDescriptors(fory.getClassResolver(), TargetClassWithMixedTags.class);

    assertEquals(descriptors.size(), 3);

    // Verify that tagged fields are matched by tag, not by name
    boolean foundField1 = false;
    boolean foundField2 = false;
    boolean foundField3 = false;

    for (Descriptor desc : descriptors) {
      if (desc.getName().equals("field1")) {
        foundField1 = true;
      } else if (desc.getName().equals("field2")) {
        foundField2 = true;
      } else if (desc.getName().equals("field3")) {
        foundField3 = true;
      }
    }

    assertTrue(foundField1);
    assertTrue(foundField2);
    assertTrue(foundField3);
  }

  static class SourceClassWithTags {
    @ForyField(id = 100)
    private String renamedField; // This will be matched by tag ID

    @ForyField(id = 200)
    private int anotherField;
  }

  static class TargetClassWithDifferentNames {
    @ForyField(id = 100)
    private String differentName; // Same tag ID as renamedField

    @ForyField(id = 200)
    private int alsoRenamed; // Same tag ID as anotherField
  }

  @Test
  public void testGetDescriptorsMatchesByTagNotName() {
    Fory fory = Fory.builder().withMetaShare(true).build();

    // Build a ClassDef from source class with specific tag IDs
    List<Field> sourceFields = ReflectionUtils.getFields(SourceClassWithTags.class, true);
    ClassDef classDef =
        ClassDef.buildClassDef(fory.getClassResolver(), SourceClassWithTags.class, sourceFields);

    // Get descriptors for target class with different field names but same tag IDs
    List<Descriptor> descriptors =
        classDef.getDescriptors(fory.getClassResolver(), TargetClassWithDifferentNames.class);

    // Should match fields by tag ID, not by name
    assertEquals(descriptors.size(), 2);

    // Verify the descriptors were matched correctly (by tag, not name)
    // When matched by tag, descriptors will have the target class field information
    for (Descriptor desc : descriptors) {
      // The descriptor should have the field from the target class since it was matched by tag
      assertTrue(
          desc.getName().equals("differentName") || desc.getName().equals("alsoRenamed"),
          "Descriptor name should match target class field names when matched by tag ID");
    }
  }

  static class TargetClassWithZeroTagId {
    @ForyField(id = 0)
    private String field1;

    @ForyField(id = 0) // Duplicate tag ID 0
    private String field2;
  }

  @Test
  public void testGetDescriptorsWithDuplicateZeroTagIds() {
    Fory fory = Fory.builder().withMetaShare(true).build();

    // Build a ClassDef with some fields
    List<Field> sourceFields = ReflectionUtils.getFields(ClassWithValidTagIds.class, true);
    ClassDef classDef =
        ClassDef.buildClassDef(fory.getClassResolver(), ClassWithValidTagIds.class, sourceFields);

    // Try to get descriptors for a class that has duplicate tag ID 0
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> classDef.getDescriptors(fory.getClassResolver(), TargetClassWithZeroTagId.class));
  }

  static class EmptyClass {
    // No fields
  }

  @Test
  public void testGetDescriptorsWithEmptyClass() {
    Fory fory = Fory.builder().withMetaShare(true).build();

    // Build a ClassDef with no fields
    List<Field> sourceFields = ReflectionUtils.getFields(EmptyClass.class, true);
    ClassDef classDef =
        ClassDef.buildClassDef(fory.getClassResolver(), EmptyClass.class, sourceFields);

    // Get descriptors should succeed and return empty list
    List<Descriptor> descriptors =
        classDef.getDescriptors(fory.getClassResolver(), EmptyClass.class);

    assertEquals(descriptors.size(), 0);
  }

  static class InheritedBaseClass {
    @ForyField(id = 10)
    private String baseField;
  }

  static class InheritedChildClass extends InheritedBaseClass {
    @ForyField(id = 20)
    private String childField;
  }

  static class InheritedChildWithDuplicateTag extends InheritedBaseClass {
    @ForyField(id = 10) // Duplicate with baseField
    private String childField;
  }

  @Test
  public void testGetDescriptorsWithInheritance() {
    Fory fory = Fory.builder().withMetaShare(true).build();

    // Build a ClassDef with inherited fields
    List<Field> sourceFields = ReflectionUtils.getFields(InheritedChildClass.class, true);
    ClassDef classDef =
        ClassDef.buildClassDef(fory.getClassResolver(), InheritedChildClass.class, sourceFields);

    // Get descriptors should succeed
    List<Descriptor> descriptors =
        classDef.getDescriptors(fory.getClassResolver(), InheritedChildClass.class);

    // Should have both base and child fields
    assertEquals(descriptors.size(), 2);
  }

  @Test
  public void testGetDescriptorsWithInheritedDuplicateTag() {
    Fory fory = Fory.builder().withMetaShare(true).build();

    // Build a ClassDef with some fields
    List<Field> sourceFields = ReflectionUtils.getFields(InheritedBaseClass.class, true);
    ClassDef classDef =
        ClassDef.buildClassDef(fory.getClassResolver(), InheritedBaseClass.class, sourceFields);

    // Try to get descriptors for a class that has duplicate tag ID across inheritance
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            classDef.getDescriptors(fory.getClassResolver(), InheritedChildWithDuplicateTag.class));
  }
}
