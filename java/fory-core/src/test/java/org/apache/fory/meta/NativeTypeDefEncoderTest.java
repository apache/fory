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

import static org.apache.fory.meta.NativeTypeDefEncoder.buildFieldsInfo;
import static org.apache.fory.meta.NativeTypeDefEncoder.getClassFields;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.serializer.ObjectStreamSerializer;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.MapFields;
import org.apache.fory.test.bean.Struct;
import org.apache.fory.type.Types;
import org.apache.fory.util.MurmurHash3;
import org.testng.Assert;
import org.testng.annotations.Test;

public class NativeTypeDefEncoderTest {
  private static final int FIELD_TYPE_MAX_DEPTH = 50;
  private static final int DEEP_TYPE_META_BYTES = 16384;
  private static final int NATIVE_MAP_KIND = 1;
  private static final int NATIVE_OBJECT_HEADER = 0;

  @Test
  public void testBasicTypeDef() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    Class<TypeDefTest.TestFieldsOrderClass1> type = TypeDefTest.TestFieldsOrderClass1.class;
    List<FieldInfo> fieldsInfo = buildFieldsInfo((ClassResolver) fory.getTypeResolver(), type);
    MemoryBuffer buffer =
        NativeTypeDefEncoder.encodeTypeDef(
            (ClassResolver) fory.getTypeResolver(), type, getClassFields(type, fieldsInfo));
    TypeDef typeDef = TypeDef.readTypeDef(fory.getTypeResolver(), buffer);
    Assert.assertEquals(typeDef.getClassName(), type.getName());
    Assert.assertEquals(typeDef.getFieldsInfo().size(), type.getDeclaredFields().length);
    Assert.assertEquals(typeDef.getFieldsInfo(), fieldsInfo);
  }

  @Test
  public void testBigMetaEncoding() {
    for (Class<?> type :
        new Class[] {
          MapFields.class, BeanA.class, Struct.createStructClass("TestBigMetaEncoding", 5)
        }) {
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withMetaShare(true)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), type);
      TypeDef typeDef1 =
          TypeDef.readTypeDef(
              fory.getTypeResolver(), MemoryBuffer.fromByteArray(typeDef.getEncoded()));
      Assert.assertEquals(typeDef1, typeDef);
    }
  }

  @Test
  public void testTypeDefArrayDimensionLimit() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(16);
    buffer.writeByte(3 << 2);
    buffer.writeVarUInt32Small7(256);

    Assert.assertThrows(
        DeserializationException.class,
        () -> FieldTypes.FieldType.read(buffer, fory.getTypeResolver()));
  }

  @Test
  public void testFieldTypeDepth() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withCompatible(false)
            .withMaxDepth(FIELD_TYPE_MAX_DEPTH)
            .withMaxTypeMetaBytes(DEEP_TYPE_META_BYTES)
            .build();
    MemoryBuffer buffer = deepMapFieldType(FIELD_TYPE_MAX_DEPTH, NATIVE_OBJECT_HEADER);
    FieldTypes.FieldType fieldType =
        FieldTypes.FieldType.read(buffer, fory.getTypeResolver(), false, false, NATIVE_MAP_KIND);

    for (int i = 0; i < FIELD_TYPE_MAX_DEPTH; i++) {
      Assert.assertTrue(fieldType instanceof FieldTypes.MapFieldType);
      FieldTypes.MapFieldType mapType = (FieldTypes.MapFieldType) fieldType;
      Assert.assertTrue(mapType.getKeyType() instanceof FieldTypes.ObjectFieldType);
      fieldType = mapType.getValueType();
    }
    Assert.assertTrue(fieldType instanceof FieldTypes.ObjectFieldType);
    Assert.assertEquals(buffer.remaining(), 0);

    FieldTypes.FieldType descriptorFieldType =
        FieldTypes.FieldType.read(
            deepMapFieldType(FIELD_TYPE_MAX_DEPTH, NATIVE_OBJECT_HEADER),
            fory.getTypeResolver(),
            false,
            false,
            NATIVE_MAP_KIND);
    TypeDef typeDef =
        new TypeDef(
            new ClassSpec(ExpectedType.class),
            Collections.singletonList(
                new FieldInfo(ExpectedType.class.getName(), "nested", descriptorFieldType)),
            Long.MIN_VALUE,
            new byte[0]);
    Assert.assertEquals(
        typeDef.getDescriptors(fory.getTypeResolver(), ExpectedType.class).size(), 1);

    Assert.assertThrows(
        DeserializationException.class,
        () ->
            FieldTypes.FieldType.read(
                deepMapFieldType(FIELD_TYPE_MAX_DEPTH + 1, NATIVE_OBJECT_HEADER),
                fory.getTypeResolver(),
                false,
                false,
                NATIVE_MAP_KIND));
  }

  @Test
  public void testMalformedDeepFieldType() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withCompatible(false)
            .withMaxDepth(FIELD_TYPE_MAX_DEPTH)
            .withMaxTypeMetaBytes(DEEP_TYPE_META_BYTES)
            .build();

    MemoryBuffer truncated = deepMapFieldType(FIELD_TYPE_MAX_DEPTH, -1);
    Assert.assertThrows(
        RuntimeException.class,
        () ->
            FieldTypes.FieldType.read(
                truncated, fory.getTypeResolver(), false, false, NATIVE_MAP_KIND));

    MemoryBuffer invalid = deepMapFieldType(FIELD_TYPE_MAX_DEPTH, 6 << 2);
    Assert.assertThrows(
        IllegalStateException.class,
        () ->
            FieldTypes.FieldType.read(
                invalid, fory.getTypeResolver(), false, false, NATIVE_MAP_KIND));
  }

  private static MemoryBuffer deepMapFieldType(int depth, int terminalHeader) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(depth * 2);
    for (int i = 0; i < depth; i++) {
      buffer.writeByte(NATIVE_OBJECT_HEADER);
      if (i + 1 < depth) {
        buffer.writeByte(NATIVE_MAP_KIND << 2);
      }
    }
    if (terminalHeader >= 0) {
      buffer.writeByte(terminalHeader);
    }
    return MemoryBuffer.fromByteArray(buffer.getBytes(0, buffer.writerIndex()));
  }

  @Test
  public void testUnresolvedRootClass() {
    Fory rawWriter =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    byte[] rawTypeDef =
        TypeDef.buildTypeDef(rawWriter.getTypeResolver(), ExpectedType.class).getEncoded();

    Fory namedWriter =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    namedWriter.register(ExpectedType.class, "alias", "ExpectedType");
    byte[] namedTypeDef =
        TypeDef.buildTypeDef(namedWriter.getTypeResolver(), ExpectedType.class).getEncoded();
    Fory namedReader =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    TypeDef namedRoot =
        TypeDef.readTypeDefWithoutRootClass(
            (ClassResolver) namedReader.getTypeResolver(), namedTypeDef);
    Assert.assertEquals(namedRoot.getClassSpec().entireClassName, "alias.ExpectedType");
    Assert.assertNull(namedRoot.getClassSpec().type);

    Fory localReader =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    TypeDef unresolved =
        TypeDef.readTypeDefWithoutRootClass(
            (ClassResolver) localReader.getTypeResolver(), rawTypeDef);
    Assert.assertEquals(unresolved.getClassSpec().entireClassName, ExpectedType.class.getName());
    Assert.assertNull(unresolved.getClassSpec().type);
  }

  public static class ExpectedType implements Serializable {
    private int value;
  }

  @Data
  public static class Foo1 {
    private int f1;
  }

  public static class Foo2 extends Foo1 {}

  @Test
  public void testEmptySubClassSerializer() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), Foo2.class);
    TypeDef typeDef1 =
        TypeDef.readTypeDef(
            fory.getTypeResolver(), MemoryBuffer.fromByteArray(typeDef.getEncoded()));
    Assert.assertEquals(typeDef, typeDef1);
  }

  @Test
  public void testBigClassNameObject() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    TypeDef typeDef =
        TypeDef.buildTypeDef(
            fory.getTypeResolver(),
            TestClassLengthTestClassLengthTestClassLengthTestClassLengthTestClassLengthTestClassLengthTestClassLength
                .InnerClassTestLengthInnerClassTestLengthInnerClassTestLength.class);
    TypeDef typeDef1 =
        TypeDef.readTypeDef(
            fory.getTypeResolver(), MemoryBuffer.fromByteArray(typeDef.getEncoded()));
    Assert.assertEquals(typeDef1, typeDef);
  }

  @Data
  public static class NaturalExtTypeWithFields implements Serializable {
    private static final long serialVersionUID = 1L;
    private int value;
  }

  @Test
  public void testFieldMetadataTypeDefUsesStructKindForNaturalExtSerializer() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();
    fory.registerSerializer(
        NaturalExtTypeWithFields.class,
        new ObjectStreamSerializer(resolver, NaturalExtTypeWithFields.class));

    TypeDef typeDef = TypeDef.buildTypeDef(resolver, NaturalExtTypeWithFields.class);
    Assert.assertTrue(typeDef.isStructSchemaKind());

    TypeDef decoded =
        TypeDef.readTypeDef(resolver, MemoryBuffer.fromByteArray(typeDef.getEncoded()));
    Assert.assertEquals(decoded, typeDef);
  }

  @Test
  public void testEmptyTypeDefKeepsNaturalExtKind() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();
    fory.getTypeResolver().getTypeInfo(java.util.ArrayList.class);

    TypeDef typeDef =
        NativeTypeDefEncoder.buildTypeDefWithFieldInfos(
            resolver, java.util.ArrayList.class, Collections.emptyList());
    Assert.assertEquals(typeDef.getClassSpec().typeId, Types.NAMED_EXT);

    TypeDef decoded =
        TypeDef.readTypeDef(resolver, MemoryBuffer.fromByteArray(typeDef.getEncoded()));
    Assert.assertEquals(decoded, typeDef);
  }

  @Test
  public void testDecodeRejectsKnownClassWithForgedRootKind() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    ClassResolver resolver = (ClassResolver) fory.getTypeResolver();
    fory.getTypeResolver().getTypeInfo(java.util.ArrayList.class);

    TypeDef typeDef =
        NativeTypeDefEncoder.buildTypeDefWithFieldInfos(
            resolver, java.util.ArrayList.class, Collections.emptyList());
    byte[] encoded = typeDef.getEncoded();
    MemoryBuffer encodedBuffer = MemoryBuffer.fromByteArray(encoded);
    long header = encodedBuffer.readInt64();
    Assert.assertEquals(header & TypeDef.COMPRESS_META_FLAG, 0L);
    Assert.assertEquals((int) (header & TypeDef.META_SIZE_MASKS), encoded.length - Long.BYTES);

    byte[] body = Arrays.copyOfRange(encoded, Long.BYTES, encoded.length);
    body[0] =
        (byte) ((NativeTypeDefEncoder.nativeKindCode(Types.NAMED_STRUCT) << 4) | (body[0] & 0x0f));
    MemoryBuffer malformedBody = MemoryBuffer.newHeapBuffer(body.length);
    malformedBody.writeBytes(body);
    MemoryBuffer malformed = NativeTypeDefEncoder.prependHeader(malformedBody, false);
    Assert.assertThrows(
        DeserializationException.class, () -> TypeDef.readTypeDef(resolver, malformed));
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
    MemoryBuffer inputBuffer = MemoryBuffer.newHeapBuffer(TypeDef.META_SIZE_MASKS + 1);
    inputBuffer.writerIndex(TypeDef.META_SIZE_MASKS + 1);
    MemoryBuffer outputBuffer = NativeTypeDefEncoder.prependHeader(inputBuffer, true);

    long header = outputBuffer.readInt64();
    Assert.assertEquals(header & TypeDef.META_SIZE_MASKS, TypeDef.META_SIZE_MASKS);
    Assert.assertEquals(header & TypeDef.COMPRESS_META_FLAG, TypeDef.COMPRESS_META_FLAG);
  }

  @Test
  public void testDecodeRejectsTooManyFields() {
    Fory writer = Fory.builder().withXlang(false).withMetaShare(true).withCompatible(false).build();
    TypeDef typeDef =
        TypeDef.buildTypeDef(writer.getTypeResolver(), TypeDefEncoderTest.ManyFields.class);
    Fory reader =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .withCompatible(false)
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
    Fory writer = Fory.builder().withXlang(false).withMetaShare(true).withCompatible(false).build();
    TypeDef typeDef = TypeDef.buildTypeDef(writer.getTypeResolver(), Foo1.class);
    Fory reader =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .withCompatible(false)
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
  public void testDecodeRejectsReservedGlobalBits() {
    Fory fory = Fory.builder().withXlang(false).withMetaShare(true).withCompatible(false).build();
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), Foo1.class);
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), Foo1.class);
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
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), Foo1.class);
    MemoryBuffer encoded = MemoryBuffer.fromByteArray(typeDef.getEncoded());
    long header = encoded.readInt64();
    Assert.assertEquals(header & TypeDef.COMPRESS_META_FLAG, 0);

    byte[] malformed = corruptEncodedBody(typeDef, "f1");
    Assert.assertThrows(
        DeserializationException.class,
        () -> TypeDef.readTypeDef(fory.getTypeResolver(), MemoryBuffer.fromByteArray(malformed)));
  }

  @Test
  public void testDecodeRejectsBodyOnlyHeaderHash() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), Foo1.class);
    byte[] malformed = rewriteHeaderWithBodyOnlyHash(typeDef);

    Assert.assertThrows(
        DeserializationException.class,
        () -> TypeDef.readTypeDef(fory.getTypeResolver(), MemoryBuffer.fromByteArray(malformed)));
  }

  @Test
  public void testDecodeRejectsHashConsistentMalformedTypeDefBody() {
    Fory fory = Fory.builder().withXlang(false).withMetaShare(true).withCompatible(false).build();
    MemoryBuffer body = MemoryBuffer.newHeapBuffer(1);
    body.writeByte(0);
    MemoryBuffer encoded = NativeTypeDefEncoder.prependHeader(body, false);
    Assert.assertThrows(
        RuntimeException.class, () -> TypeDef.readTypeDef(fory.getTypeResolver(), encoded));
  }

  @Test
  public void testRejectsInheritedDuplicateTag() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .withCompatible(false)
            .requireClassRegistration(false)
            .build();
    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), TaggedChild.class);
    byte[] encoded = typeDef.getEncoded();
    MemoryBuffer encodedBuffer = MemoryBuffer.fromByteArray(encoded);
    long header = encodedBuffer.readInt64();
    byte[] body =
        NativeTypeDefDecoder.decodeTypeDefBuf(
                encodedBuffer, (ClassResolver) fory.getTypeResolver(), header)
            .f0;

    List<FieldInfo> fields =
        buildFieldsInfo((ClassResolver) fory.getTypeResolver(), TaggedChild.class);
    FieldInfo parentField =
        fields.stream()
            .filter(field -> field.getDefinedClass().equals(TaggedParent.class.getName()))
            .findFirst()
            .orElseThrow(AssertionError::new);
    FieldInfo childField =
        fields.stream()
            .filter(field -> field.getDefinedClass().equals(TaggedChild.class.getName()))
            .findFirst()
            .orElseThrow(AssertionError::new);
    byte[] parentFieldBytes = encodedFieldInfo(parentField);
    byte[] childFieldBytes = encodedFieldInfo(childField);
    Assert.assertEquals(childFieldBytes.length, parentFieldBytes.length);

    int childFieldOffset = indexOf(body, childFieldBytes, 0);
    Assert.assertTrue(childFieldOffset >= 0);
    System.arraycopy(parentFieldBytes, 0, body, childFieldOffset, parentFieldBytes.length);
    MemoryBuffer malformedBody = MemoryBuffer.newHeapBuffer(body.length);
    malformedBody.writeBytes(body);
    MemoryBuffer malformed = NativeTypeDefEncoder.prependHeader(malformedBody, false);

    Assert.assertThrows(
        DeserializationException.class,
        () -> TypeDef.readTypeDef(fory.getTypeResolver(), malformed));
  }

  @Test
  public void testRejectsNamespaceEncoding() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    MemoryBuffer body = MemoryBuffer.newHeapBuffer(3);
    body.writeByte(NativeTypeDefEncoder.nativeKindCode(Types.NAMED_STRUCT) << 4);
    body.writeVarUInt32Small7(0);
    body.writeByte(0b11);
    MemoryBuffer encoded = NativeTypeDefEncoder.prependHeader(body, false);

    DeserializationException exception =
        Assert.expectThrows(
            DeserializationException.class,
            () -> TypeDef.readTypeDef(fory.getTypeResolver(), encoded));
    Assert.assertTrue(exception.getMessage().contains("namespace encoding"));
  }

  @Test
  public void testRejectsExtendedNameSize() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    for (int extendedSize : new int[] {-1, Integer.MAX_VALUE}) {
      MemoryBuffer body = MemoryBuffer.newHeapBuffer(8);
      body.writeByte(NativeTypeDefEncoder.nativeKindCode(Types.NAMED_STRUCT) << 4);
      body.writeVarUInt32Small7(0);
      body.writeByte(NativeTypeDefEncoder.BIG_NAME_THRESHOLD << 2);
      body.writeVarUInt32Small7(extendedSize);
      MemoryBuffer encoded = NativeTypeDefEncoder.prependHeader(body, false);

      DeserializationException exception =
          Assert.expectThrows(
              DeserializationException.class,
              () -> TypeDef.readTypeDef(fory.getTypeResolver(), encoded));
      Assert.assertTrue(exception.getMessage().contains("namespace size"));
    }
  }

  @Test
  public void testRejectsExtendedFieldNameSize() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    for (int extendedSize : new int[] {-1, Integer.MAX_VALUE}) {
      MemoryBuffer body = MemoryBuffer.newHeapBuffer(16);
      body.writeByte(NativeTypeDefEncoder.nativeKindCode(Types.STRUCT) << 4);
      body.writeVarUInt32Small7(3);
      body.writeUInt8(Types.STRUCT);
      body.writeVarUInt32(0);
      body.writeByte(7 << 4);
      body.writeVarUInt32Small7(extendedSize);
      MemoryBuffer encoded = NativeTypeDefEncoder.prependHeader(body, false);

      DeserializationException exception =
          Assert.expectThrows(
              DeserializationException.class,
              () -> TypeDef.readTypeDef(fory.getTypeResolver(), encoded));
      Assert.assertTrue(exception.getMessage().contains("field name size"));
    }
  }

  private static byte[] corruptEncodedBody(TypeDef typeDef, String needle) {
    byte[] malformed = typeDef.getEncoded().clone();
    byte[] needleBytes = Encoders.encodeFieldName(needle).getBytes();
    int index = indexOf(malformed, needleBytes, Long.BYTES);
    Assert.assertTrue(index >= Long.BYTES);
    malformed[index + needleBytes.length - 1] ^= 1;
    return malformed;
  }

  private static byte[] encodedFieldInfo(FieldInfo fieldInfo) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(16);
    NativeTypeDefEncoder.writeFieldsInfo(buffer, Collections.singletonList(fieldInfo));
    return buffer.getBytes(0, buffer.writerIndex());
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

  private static int typeDefBodyOffset(byte[] encoded) {
    MemoryBuffer buffer = MemoryBuffer.fromByteArray(encoded);
    long header = buffer.readInt64();
    if ((header & TypeDef.META_SIZE_MASKS) == TypeDef.META_SIZE_MASKS) {
      buffer.readVarUInt32Small14();
    }
    return buffer.readerIndex();
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

  @Test
  public void testAbstractParentClass() {
    Fory fory0 =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .withScopedMetaShare(true)
            .withCompatible(true)
            .requireClassRegistration(false)
            .build();
    Fory fory1 =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .withScopedMetaShare(true)
            .withCompatible(true)
            .build();
    fory1.register(BaseAbstractClass.class);
    fory1.register(ChildClass.class);
    for (Fory fory : new Fory[] {fory0, fory1}) {
      TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), ChildClass.class);
      TypeDef typeDef1 =
          TypeDef.readTypeDef(
              fory.getTypeResolver(), MemoryBuffer.fromByteArray(typeDef.getEncoded()));
      Assert.assertEquals(typeDef, typeDef1);
      ChildClass c = new ChildClass();
      c.setId("123");
      c.setName("test");
      byte[] serialized = fory.serialize(c);
      ChildClass c1 = fory.deserialize(serialized, ChildClass.class);
      Assert.assertEquals(c1.getId(), "123");
      Assert.assertEquals(c1.getName(), "test");
    }
  }

  @Test
  public void testUnregisteredParentLayer() {
    Fory writer = registeredChildFory();
    Fory reader = registeredChildFory();
    writer.register(ChildClass.class, 100);
    reader.register(ChildClass.class, 100);
    ClassResolver readerResolver = (ClassResolver) reader.getTypeResolver();
    Assert.assertFalse(readerResolver.isRegisteredByName(BaseAbstractClass.class.getName()));

    ChildClass child = new ChildClass();
    child.setId("123");
    child.setName("test");
    ChildClass decoded = (ChildClass) reader.deserialize(writer.serialize(child));
    Assert.assertEquals(decoded.getId(), "123");
    Assert.assertEquals(decoded.getName(), "test");

    TypeDef typeDef = TypeDef.buildTypeDef(writer.getTypeResolver(), ChildClass.class);
    TypeDef decodedTypeDef =
        TypeDef.readTypeDef(readerResolver, MemoryBuffer.fromByteArray(typeDef.getEncoded()));
    FieldInfo parentField =
        decodedTypeDef.getFieldsInfo().stream()
            .filter(field -> field.getFieldName().equals("id"))
            .findFirst()
            .orElseThrow(AssertionError::new);
    Assert.assertEquals(parentField.getDefinedClass(), BaseAbstractClass.class.getName());
    Assert.assertEquals(decodedTypeDef.getClassSpec().type, ChildClass.class);
  }

  private static Fory registeredChildFory() {
    return Fory.builder()
        .withXlang(false)
        .withRefTracking(false)
        .withMetaShare(true)
        .withScopedMetaShare(true)
        .withCompatible(true)
        .withDeserializeUnknownClass(false)
        .withAsyncCompilation(false)
        .requireClassRegistration(true)
        .build();
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

  // Test classes for duplicate tag ID validation in TypeDefEncoder
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

  public static class ClassWithLargeTagIds {
    @ForyField(id = 15)
    private String field15;

    @ForyField(id = 32768)
    private String field32768;

    @ForyField(id = 65535)
    private String field65535;

    @ForyField(id = 65551)
    private String field65551;

    @ForyField(id = ForyField.MAX_ID)
    private String fieldMax;
  }

  public static class TaggedParent {
    @ForyField(id = 10)
    private int parentValue;
  }

  public static class TaggedChild extends TaggedParent {
    @ForyField(id = 20)
    private int childValue;
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
    Fory fory = Fory.builder().withXlang(false).withMetaShare(true).withCompatible(false).build();

    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            buildFieldsInfo(
                (ClassResolver) fory.getTypeResolver(), ClassWithDuplicateTagIds.class));
  }

  @Test
  public void testBuildFieldsInfoWithValidTagIds() {
    Fory fory = Fory.builder().withXlang(false).withMetaShare(true).withCompatible(false).build();

    // Should not throw any exception
    List<FieldInfo> fieldsInfo =
        buildFieldsInfo((ClassResolver) fory.getTypeResolver(), ClassWithValidTagIds.class);

    Assert.assertEquals(fieldsInfo.size(), 3);
    // Verify all fields have the correct tag IDs
    for (FieldInfo fieldInfo : fieldsInfo) {
      Assert.assertTrue(fieldInfo.hasFieldId());
    }
  }

  @Test
  public void testLargeTagIdsRoundTrip() {
    Fory fory = Fory.builder().withXlang(false).withMetaShare(true).withCompatible(true).build();

    TypeDef typeDef = TypeDef.buildTypeDef(fory.getTypeResolver(), ClassWithLargeTagIds.class);
    TypeDef decoded =
        TypeDef.readTypeDef(
            fory.getTypeResolver(), MemoryBuffer.fromByteArray(typeDef.getEncoded()));

    Assert.assertEquals(
        decoded.getFieldsInfo().stream().map(FieldInfo::getFieldId).toArray(),
        new Object[] {15, 32768, 65535, 65551, ForyField.MAX_ID});
  }

  @Test
  public void testBuildFieldsInfoWithMixedFields() {
    Fory fory = Fory.builder().withXlang(false).withMetaShare(true).withCompatible(false).build();

    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> buildFieldsInfo((ClassResolver) fory.getTypeResolver(), ClassWithMixedFields.class));
  }
}
