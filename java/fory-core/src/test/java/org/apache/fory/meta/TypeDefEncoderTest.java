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
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.Types;
import org.apache.fory.type.union.Union;
import org.apache.fory.util.MurmurHash3;
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

    private String optOutField;

    @ForyField(id = 50) // Duplicate with annotatedField1
    private int annotatedField2;
  }

  // Test data: Class with invalid negative tag ID
  @Data
  public static class ClassWithNegativeTagId {
    @ForyField(id = -2)
    private String field;
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

  public static class EmptyStruct {}

  public static class ParentNameOrderedNonPrimitives {
    private String zString;
  }

  public static class ChildNameOrderedNonPrimitives extends ParentNameOrderedNonPrimitives {
    private Map<String, Integer> aMap;
    private int count;
  }

  public static class ParentShadowedField {
    private String duplicateName;
  }

  public static class ChildShadowedField extends ParentShadowedField {
    private String duplicateName;
    private int count;
  }

  public static class ClassWithSnakeCaseCollision {
    private String fooBar;
    private String foo_bar;
  }

  public static class ClassWithTaggedSnakeCaseCollision {
    @ForyField(id = 7)
    private String fooBar;

    private String foo_bar;
  }

  @Data
  public static class ClassWithNameOrderedNonPrimitives {
    private String zString;
    private Map<String, Integer> aMap;
    private List<String> mList;
    private int count;
  }

  @Data
  public static class ClassWithTaggedNonPrimitives {
    @ForyField(id = 3)
    private String stringField;

    @ForyField(id = 1)
    private Map<String, Integer> mapField;

    @ForyField(id = 2)
    private List<String> listField;

    private int count;
  }

  public static class ManyFields {
    int f00;
    int f01;
    int f02;
    int f03;
    int f04;
    int f05;
    int f06;
    int f07;
    int f08;
    int f09;
    int f10;
    int f11;
    int f12;
    int f13;
    int f14;
    int f15;
    int f16;
    int f17;
    int f18;
    int f19;
    int f20;
    int f21;
    int f22;
    int f23;
    int f24;
    int f25;
    int f26;
    int f27;
    int f28;
    int f29;
    int f30;
    int f31;
  }

  // Test data: Class with all fields using field names
  @Data
  public static class ClassWithAllFieldNames {
    private String field1;

    private int field2;

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

  public static class NestedUnionMapField {
    private Map<String, Union> values;
  }

  @Test
  public void testNestedUnionSchemaCompare() {
    Fory fory = Fory.builder().withXlang(true).build();
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(16);
    buffer.writeVarUInt32Small7(Types.MAP << 2);
    buffer.writeVarUInt32Small7(Types.STRING << 2);
    buffer.writeVarUInt32Small7(Types.TYPED_UNION << 2);
    buffer.readerIndex(0);

    FieldTypes.MapFieldType fieldType =
        (FieldTypes.MapFieldType)
            FieldTypes.FieldType.readCrossLanguage(buffer, (XtypeResolver) fory.getTypeResolver());

    Assert.assertTrue(fieldType.getValueType() instanceof FieldTypes.ObjectFieldType);
    Assert.assertEquals(fieldType.getValueType().getTypeId(), Types.TYPED_UNION);

    Descriptor localDescriptor =
        Descriptor.getDescriptorsMap(NestedUnionMapField.class).get("values");
    new FieldInfo(NestedUnionMapField.class.getName(), "values", fieldType)
        .toDescriptor(fory.getTypeResolver(), localDescriptor);
  }

  @Test
  public void testBuildFieldsInfoWithDuplicateTagIds() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithDuplicateTagIds.class);
    TypeResolver resolver = fory.getTypeResolver();

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
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithDuplicateTagIdsZero.class);
    TypeResolver resolver = fory.getTypeResolver();

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
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithValidTagIds.class);
    TypeResolver resolver = fory.getTypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithValidTagIds.class, "field1"),
            getField(ClassWithValidTagIds.class, "field2"),
            getField(ClassWithValidTagIds.class, "field3"));

    List<FieldInfo> fieldInfos =
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
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithMixedAnnotations.class);
    TypeResolver resolver = fory.getTypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithMixedAnnotations.class, "annotatedField1"),
            getField(ClassWithMixedAnnotations.class, "noAnnotation"),
            getField(ClassWithMixedAnnotations.class, "optOutField"),
            getField(ClassWithMixedAnnotations.class, "annotatedField2"));

    List<FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithMixedAnnotations.class, fields);

    Assert.assertEquals(fieldInfos.size(), 4);

    // annotatedField1 should have tag 50
    Assert.assertTrue(fieldInfos.get(0).hasFieldId());
    Assert.assertEquals(fieldInfos.get(0).getFieldId(), (short) 50);
    Assert.assertEquals(fieldInfos.get(0).getFieldName(), "annotatedField1");

    // noAnnotation should not have a tag (uses field name)
    Assert.assertFalse(fieldInfos.get(1).hasFieldId());
    Assert.assertEquals(fieldInfos.get(1).getFieldName(), "noAnnotation");

    // optOutField should not have a tag (uses field name)
    Assert.assertFalse(fieldInfos.get(2).hasFieldId());
    Assert.assertEquals(fieldInfos.get(2).getFieldName(), "optOutField");

    // annotatedField2 should have tag 60
    Assert.assertTrue(fieldInfos.get(3).hasFieldId());
    Assert.assertEquals(fieldInfos.get(3).getFieldId(), (short) 60);
    Assert.assertEquals(fieldInfos.get(3).getFieldName(), "annotatedField2");
  }

  @Test
  public void testBuildFieldsInfoWithMixedDuplicateTagIds() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithMixedDuplicateTagIds.class);
    TypeResolver resolver = fory.getTypeResolver();

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
  public void testBuildFieldsInfoRejectsNegativeTagId() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    TypeResolver resolver = fory.getTypeResolver();
    List<Field> fields = Collections.singletonList(getField(ClassWithNegativeTagId.class, "field"));
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> TypeDefEncoder.buildFieldsInfo(resolver, ClassWithNegativeTagId.class, fields));
  }

  @Test
  public void testBuildFieldsInfoWithSingleField() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithSingleField.class);
    TypeResolver resolver = fory.getTypeResolver();

    List<Field> fields = Collections.singletonList(getField(ClassWithSingleField.class, "field"));

    List<FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithSingleField.class, fields);

    Assert.assertEquals(fieldInfos.size(), 1);
    Assert.assertTrue(fieldInfos.get(0).hasFieldId());
    Assert.assertEquals(fieldInfos.get(0).getFieldId(), (short) 42);
    Assert.assertEquals(fieldInfos.get(0).getFieldName(), "field");
  }

  @Test
  public void testBuildFieldsInfoWithNoAnnotations() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithNoAnnotations.class);
    TypeResolver resolver = fory.getTypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithNoAnnotations.class, "field1"),
            getField(ClassWithNoAnnotations.class, "field2"),
            getField(ClassWithNoAnnotations.class, "field3"));

    List<FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithNoAnnotations.class, fields);

    Assert.assertEquals(fieldInfos.size(), 3);

    // All fields should not have tags (use field names)
    for (FieldInfo fieldInfo : fieldInfos) {
      Assert.assertFalse(fieldInfo.hasFieldId());
    }
  }

  @Test
  public void testBuildFieldsInfoWithAllFieldNames() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithAllFieldNames.class);
    TypeResolver resolver = fory.getTypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithAllFieldNames.class, "field1"),
            getField(ClassWithAllFieldNames.class, "field2"),
            getField(ClassWithAllFieldNames.class, "field3"));

    List<FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithAllFieldNames.class, fields);

    Assert.assertEquals(fieldInfos.size(), 3);

    // All fields without configured IDs should not have tags (use field names)
    for (FieldInfo fieldInfo : fieldInfos) {
      Assert.assertFalse(fieldInfo.hasFieldId());
    }
  }

  @Test
  public void testBuildFieldsInfoWithLargeTagIds() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithLargeTagIds.class);
    TypeResolver resolver = fory.getTypeResolver();

    List<Field> fields =
        Arrays.asList(
            getField(ClassWithLargeTagIds.class, "field1"),
            getField(ClassWithLargeTagIds.class, "field2"));

    List<FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithLargeTagIds.class, fields);

    Assert.assertEquals(fieldInfos.size(), 2);

    Assert.assertTrue(fieldInfos.get(0).hasFieldId());
    Assert.assertEquals(fieldInfos.get(0).getFieldId(), (short) 32000);

    Assert.assertTrue(fieldInfos.get(1).hasFieldId());
    Assert.assertEquals(fieldInfos.get(1).getFieldId(), (short) 32767);
  }

  @Test
  public void testBuildFieldsInfoWithEmptyFieldList() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithValidTagIds.class);
    TypeResolver resolver = fory.getTypeResolver();

    List<Field> fields = Collections.emptyList();

    List<FieldInfo> fieldInfos =
        TypeDefEncoder.buildFieldsInfo(resolver, ClassWithValidTagIds.class, fields);

    Assert.assertEquals(fieldInfos.size(), 0);
  }

  @Test
  public void testXlangTypeDefIsNotCompressed() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithNoAnnotations.class);

    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), ClassWithNoAnnotations.class);
    Assert.assertEquals(typeDef.getId() & TypeDef.COMPRESS_META_FLAG, 0);
  }

  @Test
  public void testTypeDefOrdersNonPrimitivesByNameIdentifier() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithNameOrderedNonPrimitives.class);

    TypeDef typeDef =
        TypeDef.buildTypeDef(fory.getTypeResolver(), ClassWithNameOrderedNonPrimitives.class);

    Assert.assertEquals(fieldNames(typeDef), Arrays.asList("count", "aMap", "mList", "zString"));
  }

  @Test
  public void testTypeDefOrdersInheritedNonPrimitivesByProtocolOrder() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ChildNameOrderedNonPrimitives.class);

    TypeDef typeDef =
        TypeDef.buildTypeDef(fory.getTypeResolver(), ChildNameOrderedNonPrimitives.class);

    Assert.assertEquals(fieldNames(typeDef), Arrays.asList("count", "aMap", "zString"));
  }

  @Test
  public void testTypeDefDropsOnlyInheritedShadowedXlangName() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ChildShadowedField.class);

    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), ChildShadowedField.class);

    Assert.assertEquals(fieldNames(typeDef), Arrays.asList("count", "duplicateName"));
  }

  @Test
  public void testTypeDefRejectsUntaggedSnakeCaseCollision() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithSnakeCaseCollision.class);

    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> TypeDef.buildTypeDef(fory.getTypeResolver(), ClassWithSnakeCaseCollision.class));
  }

  @Test
  public void testTypeDefAllowsTaggedSnakeCaseCollision() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithTaggedSnakeCaseCollision.class);

    TypeDef typeDef =
        TypeDef.buildTypeDef(fory.getTypeResolver(), ClassWithTaggedSnakeCaseCollision.class);

    Assert.assertEquals(fieldNames(typeDef), Arrays.asList("fooBar", "foo_bar"));
  }

  @Test
  public void testTypeDefOrdersNonPrimitivesByNumericTagIdentifier() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithTaggedNonPrimitives.class);

    TypeDef typeDef =
        TypeDef.buildTypeDef(fory.getTypeResolver(), ClassWithTaggedNonPrimitives.class);

    Assert.assertEquals(
        fieldNames(typeDef), Arrays.asList("count", "mapField", "listField", "stringField"));
  }

  @Test
  public void testExtendedFieldCountHeaderDoesNotSetRegisterByName() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ManyFields.class, 6002);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), ManyFields.class);
    byte bodyHeader = typeDef.getEncoded()[typeDefBodyOffset(typeDef.getEncoded())];

    Assert.assertEquals(bodyHeader & TypeDefEncoder.SMALL_NUM_FIELDS_THRESHOLD, 31);
    Assert.assertEquals(bodyHeader & TypeDefEncoder.REGISTER_BY_NAME_FLAG, 0);
    TypeDef decoded =
        TypeDef.readTypeDef(
            fory.getTypeResolver(), MemoryBuffer.fromByteArray(typeDef.getEncoded()));
    Assert.assertEquals(decoded.getFieldsInfo().size(), 32);
  }

  @Test
  public void testDecodeRejectsTooManyFields() {
    Fory writer = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    writer.register(ManyFields.class, 6002);
    TypeDef typeDef = TypeDef.buildTypeDef(writer.getTypeResolver(), ManyFields.class);
    Fory reader =
        Fory.builder()
            .withXlang(true)
            .withCompatible(false)
            .withMetaShare(true)
            .withMaxTypeFields(31)
            .build();

    DeserializationException exception =
        Assert.expectThrows(
            DeserializationException.class,
            () ->
                TypeDef.readTypeDef(
                    reader.getTypeResolver(), MemoryBuffer.fromByteArray(typeDef.getEncoded())));
    Assert.assertTrue(exception.getMessage().contains("maxTypeFields"));
  }

  @Test
  public void testDecodeRejectsTypeMetaBodySize() {
    Fory writer = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    writer.register(ClassWithNoAnnotations.class, 6003);
    TypeDef typeDef = TypeDef.buildTypeDef(writer.getTypeResolver(), ClassWithNoAnnotations.class);
    Fory reader =
        Fory.builder()
            .withXlang(true)
            .withCompatible(false)
            .withMetaShare(true)
            .withMaxTypeMetaBytes(1)
            .build();

    DeserializationException exception =
        Assert.expectThrows(
            DeserializationException.class,
            () ->
                TypeDef.readTypeDef(
                    reader.getTypeResolver(), MemoryBuffer.fromByteArray(typeDef.getEncoded())));
    Assert.assertTrue(exception.getMessage().contains("maxTypeMetaBytes"));
  }

  @Test
  public void testDecodeRejectsCompressedXlangTypeDef() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithNoAnnotations.class);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), ClassWithNoAnnotations.class);
    byte[] body = Arrays.copyOfRange(typeDef.getEncoded(), Long.BYTES, typeDef.getEncoded().length);
    byte[] compressed = fory.getConfig().getMetaCompressor().compress(body, 0, body.length);
    MemoryBuffer compressedBody = MemoryBuffer.fromByteArray(compressed);
    MemoryBuffer encoded = NativeTypeDefEncoder.prependHeader(compressedBody, true);

    Assert.assertThrows(
        DeserializationException.class, () -> TypeDef.readTypeDef(fory.getTypeResolver(), encoded));
  }

  @Test
  public void testDecodeRejectsReservedGlobalBits() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithNoAnnotations.class);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), ClassWithNoAnnotations.class);
    MemoryBuffer encoded = MemoryBuffer.fromByteArray(typeDef.getEncoded());
    long header = encoded.readInt64();

    MemoryBuffer malformed = MemoryBuffer.newHeapBuffer(typeDef.getEncoded().length);
    malformed.writeInt64(header | TypeDef.RESERVED_META_FLAGS);
    malformed.writeBytes(
        typeDef.getEncoded(), Long.BYTES, typeDef.getEncoded().length - Long.BYTES);
    Assert.assertThrows(
        DeserializationException.class,
        () -> TypeDef.readTypeDef(fory.getTypeResolver(), malformed));
  }

  @Test
  public void testDecodeRejectsTrailingTypeDefBodyBytes() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithNoAnnotations.class);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), ClassWithNoAnnotations.class);
    MemoryBuffer encoded = MemoryBuffer.fromByteArray(typeDef.getEncoded());
    long header = encoded.readInt64();
    long size = header & TypeDef.META_SIZE_MASKS;
    Assert.assertTrue(size < TypeDef.META_SIZE_MASKS);

    MemoryBuffer malformed = MemoryBuffer.newHeapBuffer(typeDef.getEncoded().length + 1);
    malformed.writeInt64((header & ~TypeDef.META_SIZE_MASKS) | (size + 1));
    malformed.writeBytes(
        typeDef.getEncoded(), Long.BYTES, typeDef.getEncoded().length - Long.BYTES);
    malformed.writeByte(0);
    Assert.assertThrows(
        DeserializationException.class,
        () -> TypeDef.readTypeDef(fory.getTypeResolver(), malformed));
  }

  @Test
  public void testDecodeRejectsParsedTypeDefWithMismatchedHash() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithNoAnnotations.class);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), ClassWithNoAnnotations.class);
    byte[] malformed = corruptEncodedBody(typeDef, "field1");

    Assert.assertThrows(
        DeserializationException.class,
        () -> TypeDef.readTypeDef(fory.getTypeResolver(), MemoryBuffer.fromByteArray(malformed)));
  }

  @Test
  public void testDecodeRejectsBodyOnlyHeaderHash() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(ClassWithNoAnnotations.class);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), ClassWithNoAnnotations.class);
    byte[] malformed = rewriteHeaderWithBodyOnlyHash(typeDef);

    Assert.assertThrows(
        DeserializationException.class,
        () -> TypeDef.readTypeDef(fory.getTypeResolver(), MemoryBuffer.fromByteArray(malformed)));
  }

  @Test
  public void testDecodeRejectsHashConsistentMalformedTypeDefBody() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    MemoryBuffer body = MemoryBuffer.newHeapBuffer(1);
    body.writeByte(0);
    MemoryBuffer encoded = NativeTypeDefEncoder.prependHeader(body, false);
    Assert.assertThrows(
        RuntimeException.class, () -> TypeDef.readTypeDef(fory.getTypeResolver(), encoded));
  }

  @Test
  public void testSkipTypeDefRejectsExtendedSizeOverflow() {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(8);
    buffer.writeVarUInt32(-1);

    Assert.assertThrows(
        DeserializationException.class, () -> TypeDef.skipTypeDef(buffer, TypeDef.META_SIZE_MASKS));
  }

  @Test
  public void testDecodeRejectsRegisteredTypeDefKindMismatch() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(EmptyStruct.class, 6001);
    MemoryBuffer body = MemoryBuffer.newHeapBuffer(8);
    body.writeByte(0);
    body.writeVarUInt32(6001);
    MemoryBuffer encoded = NativeTypeDefEncoder.prependHeader(body, false);

    Assert.assertThrows(
        DeserializationException.class, () -> TypeDef.readTypeDef(fory.getTypeResolver(), encoded));
  }

  @Test
  public void testDecodePreservesCompatibleStructKindForRegisteredStruct() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(false).withMetaShare(true).build();
    fory.register(EmptyStruct.class, 6001);
    MemoryBuffer body = MemoryBuffer.newHeapBuffer(8);
    body.writeByte(TypeDefEncoder.STRUCT_FLAG | TypeDefEncoder.COMPATIBLE_FLAG);
    body.writeVarUInt32(6001);
    MemoryBuffer encoded = NativeTypeDefEncoder.prependHeader(body, false);

    TypeDef typeDef = TypeDef.readTypeDef(fory.getTypeResolver(), encoded);
    Assert.assertEquals(typeDef.getClassSpec().typeId, Types.COMPATIBLE_STRUCT);
  }

  private static byte[] corruptEncodedBody(TypeDef typeDef, String needle) {
    byte[] malformed = typeDef.getEncoded().clone();
    byte[] needleBytes = Encoders.encodeFieldName(needle).getBytes();
    int index = indexOf(malformed, needleBytes, Long.BYTES);
    Assert.assertTrue(index >= Long.BYTES);
    malformed[index + needleBytes.length - 1] ^= 1;
    return malformed;
  }

  private static List<String> fieldNames(TypeDef typeDef) {
    return typeDef.getFieldsInfo().stream()
        .map(FieldInfo::getFieldName)
        .collect(Collectors.toList());
  }

  private static byte[] rewriteHeaderWithBodyOnlyHash(TypeDef typeDef) {
    byte[] malformed = typeDef.getEncoded().clone();
    MemoryBuffer buffer = MemoryBuffer.fromByteArray(malformed);
    long header = buffer.readInt64();
    int bodyOffset = typeDefBodyOffset(malformed);
    int size = malformed.length - bodyOffset;
    long hashMask = -1L << (Long.SIZE - TypeDef.NUM_HASH_BITS);
    long bodyOnlyHash = bodyOnlyTypeDefHashBits(malformed, bodyOffset, size);
    Assert.assertNotEquals(header & hashMask, bodyOnlyHash);
    MemoryBuffer.fromByteArray(malformed).putInt64(0, bodyOnlyHash | (header & ~hashMask));
    return malformed;
  }

  private static long bodyOnlyTypeDefHashBits(byte[] bytes, int offset, int size) {
    long hash = MurmurHash3.murmurhash3_x64_128(bytes, offset, size, 47)[0];
    hash <<= (Long.SIZE - TypeDef.NUM_HASH_BITS);
    long hashMask = -1L << (Long.SIZE - TypeDef.NUM_HASH_BITS);
    return Math.abs(hash) & hashMask;
  }

  private static int indexOf(byte[] bytes, byte[] needle, int fromIndex) {
    for (int i = fromIndex; i <= bytes.length - needle.length; i++) {
      boolean match = true;
      for (int j = 0; j < needle.length; j++) {
        if (bytes[i + j] != needle[j]) {
          match = false;
          break;
        }
      }
      if (match) {
        return i;
      }
    }
    return -1;
  }

  private static int typeDefBodyOffset(byte[] encoded) {
    MemoryBuffer buffer = MemoryBuffer.fromByteArray(encoded);
    long header = buffer.readInt64();
    if ((header & TypeDef.META_SIZE_MASKS) == TypeDef.META_SIZE_MASKS) {
      buffer.readVarUInt32();
    }
    return buffer.readerIndex();
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
