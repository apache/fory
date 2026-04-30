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
import org.apache.fory.annotation.Int32Type;
import org.apache.fory.annotation.Int64Type;
import org.apache.fory.annotation.UInt32Elements;
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
    @UInt8Type public byte value;
  }

  public static class NestedUInt32OnInteger {
    public List<@UInt32Type Integer> values;
  }

  public static class UInt32ArrayStruct {
    @UInt32Elements public int[] ids;
  }

  @Data
  public static class PrimitiveListOverrides {
    public UInt32List packed;

    @UInt32Type(encoding = Int32Encoding.FIXED)
    public UInt32List fixedPacked;

    @UInt32Type public UInt32List varCollection;

    @UInt64Type(encoding = Int64Encoding.TAGGED)
    public UInt64List taggedCollection;
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

    assertRegistered(fieldType(typeDef, "packed"), Types.UINT32_ARRAY);
    assertRegistered(fieldType(typeDef, "fixedPacked"), Types.UINT32_ARRAY);
    assertRegistered(
        assertCollection(fieldType(typeDef, "varCollection"), Types.LIST).getElementType(),
        Types.VAR_UINT32);
    assertRegistered(
        assertCollection(fieldType(typeDef, "taggedCollection"), Types.LIST).getElementType(),
        Types.TAGGED_UINT64);

    PrimitiveListOverrides value = new PrimitiveListOverrides();
    value.packed = uint32List(1L, 4_000_000_000L);
    value.fixedPacked = uint32List(2L, 4_294_967_295L);
    value.varCollection = uint32List(3L, 4_294_967_295L);
    value.taggedCollection = uint64List(4L, Long.MAX_VALUE, -1L);
    serDeCheck(fory, value);
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
