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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.annotation.ArrayType;
import org.apache.fory.annotation.BFloat16Type;
import org.apache.fory.annotation.Float16Type;
import org.apache.fory.annotation.Int32Type;
import org.apache.fory.annotation.Int64Type;
import org.apache.fory.annotation.Int8Type;
import org.apache.fory.annotation.UInt32Type;
import org.apache.fory.annotation.UInt64Type;
import org.apache.fory.annotation.UInt8Type;
import org.apache.fory.collection.UInt32List;
import org.apache.fory.collection.UInt64List;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.config.Int64Encoding;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.FieldTypes.CollectionFieldType;
import org.apache.fory.meta.FieldTypes.FieldType;
import org.apache.fory.meta.FieldTypes.MapFieldType;
import org.apache.fory.meta.FieldTypes.RegisteredFieldType;
import org.apache.fory.type.Float16;
import org.apache.fory.type.Types;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class NestedTypeAnnotationTest extends ForyTestBase {
  @DataProvider
  public static Object[][] enableCodegen() {
    return new Object[][] {{false}, {true}};
  }

  @Data
  public static class NestedAnnotatedStruct {
    public Map<
            @UInt32Type(encoding = Int32Encoding.FIXED) Long,
            List<@Int64Type(encoding = Int64Encoding.TAGGED) Long>>
        map;

    public Set<@Int32Type(encoding = Int32Encoding.FIXED) Integer> fixedSet;

    public List<@Int32Type Integer> varList;
  }

  @Data
  public static class SameRawGenericAnnotations {
    public List<@Int32Type(encoding = Int32Encoding.FIXED) Integer> fixed;

    public List<@Int32Type Integer> var;
  }

  public static class UInt8OnByte {
    public @UInt8Type byte value;
  }

  public static class NestedUInt32OnInteger {
    public List<@UInt32Type Integer> values;
  }

  public static class UInt32ArrayStruct {
    public @UInt32Type int[] ids;
  }

  @Data
  public static class PrimitiveListOverrides {
    public UInt32List values;

    @ArrayType public UInt32List denseValues;

    public @UInt32Type(encoding = Int32Encoding.FIXED) UInt32List fixedValues;

    public @UInt64Type(encoding = Int64Encoding.TAGGED) UInt64List taggedValues;
  }

  @Data
  public static class ByteArraySchemaKinds {
    public byte[] payload;

    public @Int8Type byte[] signedValues;

    public @UInt8Type byte[] unsignedValues;
  }

  @Data
  public static class NestedPrimitiveArrayAnnotations {
    public List<@Int8Type byte[]> int8ArrayList;

    public List<@UInt8Type byte[]> uint8ArrayList;

    public Map<String, @UInt8Type byte[]> uint8ArrayValuesByName;

    public Map<String, @UInt32Type int[]> uint32ArrayValuesByName;

    public List<@Float16Type short[]> float16ArrayBitsList;

    public List<@BFloat16Type short[]> bfloat16ArrayBitsList;
  }

  @Data
  public static class ReducedPrecisionShortArrays {
    public @Float16Type short[] float16Values;

    public @BFloat16Type short[] bfloat16Values;
  }

  @Data
  public static class BoxedListArrayAdapters {
    @ArrayType public List<Boolean> denseFlags;

    @ArrayType public List<Integer> denseIds;

    @ArrayType public List<@UInt32Type Long> denseUnsignedIds;

    @ArrayType public List<Float> denseFloats;

    @ArrayType public List<Float16> denseFloat16Values;
  }

  public static class BoxedUInt8ListArray {
    @ArrayType public List<@UInt8Type Integer> values;
  }

  public static class InvalidArrayCollection {
    @ArrayType public Set<Integer> values;
  }

  public static class InvalidArrayElement {
    @ArrayType public List<String> values;
  }

  public static class InvalidArrayFixedEncoding {
    @ArrayType public List<@Int32Type(encoding = Int32Encoding.FIXED) Integer> values;
  }

  public static class InvalidArrayTaggedEncoding {
    @ArrayType public List<@UInt64Type(encoding = Int64Encoding.TAGGED) Long> values;
  }

  public static class InvalidUInt32ArrayEncoding {
    public @UInt32Type(encoding = Int32Encoding.FIXED) int[] values;
  }

  public static class InvalidUInt64ArrayEncoding {
    public @UInt64Type(encoding = Int64Encoding.TAGGED) long[] values;
  }

  @Data
  public static class RemoteNestedField {
    public int id;

    public Map<
            @UInt32Type(encoding = Int32Encoding.FIXED) Long,
            List<@Int64Type(encoding = Int64Encoding.TAGGED) Long>>
        dropped;

    public String tail;
  }

  @Data
  public static class LocalMissingNestedField {
    public int id;

    public String tail;
  }

  @Data
  public static class LocalDifferentNestedField {
    public int id;

    public Map<@UInt32Type Long, List<@Int64Type Long>> dropped;

    public String tail;
  }

  @Test
  public void nestedAnnotationsSurviveTypeDefEncoding() {
    Fory fory = xlangFory(false, false);
    fory.register(NestedAnnotatedStruct.class, 710);

    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), NestedAnnotatedStruct.class);
    assertNestedAnnotatedStructMeta(typeDef);

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(128);
    typeDef.writeTypeDef(buffer);
    TypeDef decoded = TypeDef.readTypeDef(fory.getTypeResolver(), buffer);
    Assert.assertEquals(decoded, typeDef);
    assertNestedAnnotatedStructMeta(decoded);
  }

  @Test
  public void sameRawGenericTypeWithDifferentAnnotationsDoesNotCollide() {
    Fory fory = xlangFory(false, false);
    fory.register(SameRawGenericAnnotations.class, 711);

    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), SameRawGenericAnnotations.class);
    assertRegistered(
        assertCollection(fieldType(typeDef, "fixed"), Types.LIST).getElementType(), Types.INT32);
    assertRegistered(
        assertCollection(fieldType(typeDef, "var"), Types.LIST).getElementType(), Types.VARINT32);
  }

  @Test(dataProvider = "enableCodegen")
  public void nestedAnnotatedContainersRoundTrip(boolean enableCodegen) {
    Fory fory = xlangFory(false, enableCodegen);
    fory.register(NestedAnnotatedStruct.class, 712);

    NestedAnnotatedStruct value = new NestedAnnotatedStruct();
    value.map = new LinkedHashMap<>();
    value.map.put(4_000_000_000L, Arrays.asList(7L, -12L, 1_073_741_824L));
    value.fixedSet = new LinkedHashSet<>(Arrays.asList(1, 2, 3));
    value.varList = Arrays.asList(1, 128, 16_384);

    serDeCheck(fory, value);
  }

  @Test
  public void invalidUnsignedCarriersAreRejected() {
    Fory fory = xlangFory(false, false);
    Assert.expectThrows(
        IllegalArgumentException.class,
        () -> TypeDef.buildTypeDef(fory.getTypeResolver(), UInt8OnByte.class));
    Assert.expectThrows(
        IllegalArgumentException.class,
        () -> TypeDef.buildTypeDef(fory.getTypeResolver(), NestedUInt32OnInteger.class));
  }

  @Test(dataProvider = "enableCodegen")
  public void unsignedPrimitiveArrayUsesUnsignedArrayMetadata(boolean enableCodegen) {
    Fory fory = xlangFory(false, enableCodegen);
    fory.register(UInt32ArrayStruct.class, 713);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), UInt32ArrayStruct.class);
    assertRegistered(fieldType(typeDef, "ids"), Types.UINT32_ARRAY);

    UInt32ArrayStruct value = new UInt32ArrayStruct();
    value.ids = new int[] {-1, 0, 7, Integer.MIN_VALUE};
    UInt32ArrayStruct copy = serDe(fory, value);
    Assert.assertEquals(copy.ids, value.ids);
  }

  @Test(dataProvider = "enableCodegen")
  public void primitiveListAnnotationsSelectPackedOrCollectionProtocol(boolean enableCodegen) {
    Fory fory = xlangFory(false, enableCodegen);
    fory.register(PrimitiveListOverrides.class, 714);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), PrimitiveListOverrides.class);

    assertRegistered(
        assertCollection(fieldType(typeDef, "values"), Types.LIST).getElementType(),
        Types.VAR_UINT32);
    assertRegistered(fieldType(typeDef, "denseValues"), Types.UINT32_ARRAY);
    assertRegistered(
        assertCollection(fieldType(typeDef, "fixedValues"), Types.LIST).getElementType(),
        Types.UINT32);
    assertRegistered(
        assertCollection(fieldType(typeDef, "taggedValues"), Types.LIST).getElementType(),
        Types.TAGGED_UINT64);

    PrimitiveListOverrides value = new PrimitiveListOverrides();
    value.values = uint32List(1L, 4_000_000_000L);
    value.denseValues = uint32List(2L, 4_294_967_295L);
    value.fixedValues = uint32List(3L, 4_294_967_295L);
    value.taggedValues = uint64List(4L, Long.MAX_VALUE, -1L);
    serDeCheck(fory, value);
  }

  @Test(dataProvider = "enableCodegen")
  public void byteArraysDistinguishBinaryAndNumericArrays(boolean enableCodegen) {
    Fory fory = xlangFory(false, enableCodegen);
    fory.register(ByteArraySchemaKinds.class, 717);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), ByteArraySchemaKinds.class);

    assertRegistered(fieldType(typeDef, "payload"), Types.BINARY);
    assertRegistered(fieldType(typeDef, "signedValues"), Types.INT8_ARRAY);
    assertRegistered(fieldType(typeDef, "unsignedValues"), Types.UINT8_ARRAY);

    ByteArraySchemaKinds value = new ByteArraySchemaKinds();
    value.payload = new byte[] {1, 2, 3};
    value.signedValues = new byte[] {-1, 0, 1};
    value.unsignedValues = new byte[] {(byte) 0xff, 0, 1};
    serDeCheck(fory, value);
  }

  @Test
  public void nestedPrimitiveArrayAnnotationsUseArrayMetadata() {
    Fory fory = xlangFory(false, false);
    fory.register(NestedPrimitiveArrayAnnotations.class, 721);
    TypeDef typeDef =
        TypeDef.buildTypeDef(fory.getTypeResolver(), NestedPrimitiveArrayAnnotations.class);

    assertRegistered(
        assertCollection(fieldType(typeDef, "int8ArrayList"), Types.LIST).getElementType(),
        Types.INT8_ARRAY);
    assertRegistered(
        assertCollection(fieldType(typeDef, "uint8ArrayList"), Types.LIST).getElementType(),
        Types.UINT8_ARRAY);
    assertRegistered(
        assertMap(fieldType(typeDef, "uint8ArrayValuesByName"), Types.MAP).getValueType(),
        Types.UINT8_ARRAY);
    assertRegistered(
        assertMap(fieldType(typeDef, "uint32ArrayValuesByName"), Types.MAP).getValueType(),
        Types.UINT32_ARRAY);
    assertRegistered(
        assertCollection(fieldType(typeDef, "float16ArrayBitsList"), Types.LIST).getElementType(),
        Types.FLOAT16_ARRAY);
    assertRegistered(
        assertCollection(fieldType(typeDef, "bfloat16ArrayBitsList"), Types.LIST).getElementType(),
        Types.BFLOAT16_ARRAY);
  }

  @Test(dataProvider = "enableCodegen")
  public void reducedPrecisionShortArraysUseArrayMetadata(boolean enableCodegen) {
    Fory fory = xlangFory(false, enableCodegen);
    fory.register(ReducedPrecisionShortArrays.class, 718);
    TypeDef typeDef =
        TypeDef.buildTypeDef(fory.getTypeResolver(), ReducedPrecisionShortArrays.class);

    assertRegistered(fieldType(typeDef, "float16Values"), Types.FLOAT16_ARRAY);
    assertRegistered(fieldType(typeDef, "bfloat16Values"), Types.BFLOAT16_ARRAY);

    ReducedPrecisionShortArrays value = new ReducedPrecisionShortArrays();
    value.float16Values = new short[] {(short) 0x0000, (short) 0x3C00, (short) 0xBC00};
    value.bfloat16Values = new short[] {(short) 0x0000, (short) 0x3F80, (short) 0xBF80};
    serDeCheck(fory, value);
  }

  @Test(dataProvider = "enableCodegen")
  public void boxedListArrayAdaptersUseArrayMetadata(boolean enableCodegen) {
    Fory fory = xlangFory(false, enableCodegen);
    fory.register(BoxedListArrayAdapters.class, 719);
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), BoxedListArrayAdapters.class);

    assertRegistered(fieldType(typeDef, "denseFlags"), Types.BOOL_ARRAY);
    assertRegistered(fieldType(typeDef, "denseIds"), Types.INT32_ARRAY);
    assertRegistered(fieldType(typeDef, "denseUnsignedIds"), Types.UINT32_ARRAY);
    assertRegistered(fieldType(typeDef, "denseFloats"), Types.FLOAT32_ARRAY);
    assertRegistered(fieldType(typeDef, "denseFloat16Values"), Types.FLOAT16_ARRAY);

    BoxedListArrayAdapters value = new BoxedListArrayAdapters();
    value.denseFlags = Arrays.asList(true, false, true);
    value.denseIds = Arrays.asList(1, -2, Integer.MAX_VALUE);
    value.denseUnsignedIds = Arrays.asList(0L, 4_000_000_000L);
    value.denseFloats = Arrays.asList(1.5f, -2.25f);
    value.denseFloat16Values = Arrays.asList(Float16.fromBits((short) 0x3C00));
    BoxedListArrayAdapters copy = serDe(fory, value);
    Assert.assertEquals(copy.denseFlags, value.denseFlags);
    assertIntegralListEquals(copy.denseIds, value.denseIds);
    assertIntegralListEquals(copy.denseUnsignedIds, value.denseUnsignedIds);
    Assert.assertEquals(copy.denseFloats, value.denseFloats);
    Assert.assertEquals(copy.denseFloat16Values.size(), value.denseFloat16Values.size());
    Assert.assertEquals(
        copy.denseFloat16Values.get(0).toBits(), value.denseFloat16Values.get(0).toBits());
  }

  @Test
  public void boxedListArrayAdaptersRejectInvalidForms() {
    Fory fory = xlangFory(false, false);
    Assert.expectThrows(
        IllegalArgumentException.class,
        () -> TypeDef.buildTypeDef(fory.getTypeResolver(), InvalidArrayCollection.class));
    Assert.expectThrows(
        IllegalArgumentException.class,
        () -> TypeDef.buildTypeDef(fory.getTypeResolver(), InvalidArrayElement.class));
    Assert.expectThrows(
        IllegalArgumentException.class,
        () -> TypeDef.buildTypeDef(fory.getTypeResolver(), InvalidArrayFixedEncoding.class));
    Assert.expectThrows(
        IllegalArgumentException.class,
        () -> TypeDef.buildTypeDef(fory.getTypeResolver(), InvalidArrayTaggedEncoding.class));
    Assert.expectThrows(
        IllegalArgumentException.class,
        () -> TypeDef.buildTypeDef(fory.getTypeResolver(), InvalidUInt32ArrayEncoding.class));
    Assert.expectThrows(
        IllegalArgumentException.class,
        () -> TypeDef.buildTypeDef(fory.getTypeResolver(), InvalidUInt64ArrayEncoding.class));

    fory.register(BoxedUInt8ListArray.class, 720);
    BoxedUInt8ListArray nullElement = new BoxedUInt8ListArray();
    nullElement.values = Arrays.asList(1, null);
    Assert.expectThrows(
        org.apache.fory.exception.SerializationException.class, () -> fory.serialize(nullElement));

    BoxedUInt8ListArray outOfRange = new BoxedUInt8ListArray();
    outOfRange.values = Arrays.asList(256);
    Assert.expectThrows(
        org.apache.fory.exception.SerializationException.class, () -> fory.serialize(outOfRange));
  }

  @Test(dataProvider = "enableCodegen")
  public void compatibleMissingFieldSkipUsesRemoteNestedMetadata(boolean enableCodegen) {
    Fory writer = xlangFory(true, enableCodegen);
    writer.register(RemoteNestedField.class, 715);
    Fory reader = xlangFory(true, enableCodegen);
    reader.register(LocalMissingNestedField.class, 715);

    RemoteNestedField value = remoteNestedField();
    LocalMissingNestedField copy = (LocalMissingNestedField) serDeObject(writer, reader, value);
    Assert.assertEquals(copy.id, value.id);
    Assert.assertEquals(copy.tail, value.tail);
  }

  @Test(dataProvider = "enableCodegen")
  public void compatibleDifferentFieldMetadataUsesRemoteNestedMetadata(boolean enableCodegen) {
    Fory writer = xlangFory(true, enableCodegen);
    writer.register(RemoteNestedField.class, 716);
    Fory reader = xlangFory(true, enableCodegen);
    reader.register(LocalDifferentNestedField.class, 716);

    RemoteNestedField value = remoteNestedField();
    LocalDifferentNestedField copy = (LocalDifferentNestedField) serDeObject(writer, reader, value);
    Assert.assertEquals(copy.id, value.id);
    Assert.assertEquals(copy.dropped, value.dropped);
    Assert.assertEquals(copy.tail, value.tail);
  }

  private static Fory xlangFory(boolean compatible, boolean enableCodegen) {
    return Fory.builder()
        .withXlang(true)
        .withCompatible(compatible)
        .withCodegen(enableCodegen)
        .build();
  }

  private static RemoteNestedField remoteNestedField() {
    RemoteNestedField value = new RemoteNestedField();
    value.id = 7;
    value.dropped = new LinkedHashMap<>();
    value.dropped.put(4_000_000_000L, Arrays.asList(-1L, 1_073_741_824L, 42L));
    value.tail = "after skipped annotated field";
    return value;
  }

  private static void assertIntegralListEquals(
      List<? extends Number> actual, List<? extends Number> expected) {
    Assert.assertEquals(actual.size(), expected.size());
    for (int i = 0; i < actual.size(); i++) {
      Assert.assertEquals(actual.get(i).longValue(), expected.get(i).longValue());
    }
  }

  private static UInt32List uint32List(long... values) {
    UInt32List list = new UInt32List();
    for (long value : values) {
      list.add(value);
    }
    return list;
  }

  private static UInt64List uint64List(long... values) {
    UInt64List list = new UInt64List();
    for (long value : values) {
      list.add(value);
    }
    return list;
  }

  private static void assertNestedAnnotatedStructMeta(TypeDef typeDef) {
    MapFieldType map = assertMap(fieldType(typeDef, "map"), Types.MAP);
    assertRegistered(map.getKeyType(), Types.UINT32);
    CollectionFieldType valueList = assertCollection(map.getValueType(), Types.LIST);
    assertRegistered(valueList.getElementType(), Types.TAGGED_INT64);

    CollectionFieldType fixedSet = assertCollection(fieldType(typeDef, "fixedSet"), Types.SET);
    assertRegistered(fixedSet.getElementType(), Types.INT32);

    CollectionFieldType varList = assertCollection(fieldType(typeDef, "varList"), Types.LIST);
    assertRegistered(varList.getElementType(), Types.VARINT32);
  }

  private static FieldType fieldType(TypeDef typeDef, String fieldName) {
    for (FieldInfo fieldInfo : typeDef.getFieldsInfo()) {
      if (fieldInfo.getFieldName().equals(fieldName)) {
        return fieldInfo.getFieldType();
      }
    }
    throw new AssertionError("No field named " + fieldName + " in " + typeDef);
  }

  private static CollectionFieldType assertCollection(FieldType fieldType, int typeId) {
    Assert.assertTrue(fieldType instanceof CollectionFieldType, fieldType.toString());
    Assert.assertEquals(fieldType.typeId, typeId);
    return (CollectionFieldType) fieldType;
  }

  private static MapFieldType assertMap(FieldType fieldType, int typeId) {
    Assert.assertTrue(fieldType instanceof MapFieldType, fieldType.toString());
    Assert.assertEquals(fieldType.typeId, typeId);
    return (MapFieldType) fieldType;
  }

  private static void assertRegistered(FieldType fieldType, int typeId) {
    Assert.assertTrue(fieldType instanceof RegisteredFieldType, fieldType.toString());
    Assert.assertEquals(((RegisteredFieldType) fieldType).getTypeId(), typeId);
  }
}
