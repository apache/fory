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

package org.apache.fory.serializer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import org.apache.fory.Fory;
import org.apache.fory.collection.LongMap;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.collection.CollectionSerializer;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.TypeAnnotationUtils;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.type.union.Union;
import org.apache.fory.type.union.Union2;
import org.apache.fory.type.union.Union3;
import org.apache.fory.type.union.Union4;
import org.apache.fory.type.union.Union5;
import org.apache.fory.type.union.Union6;
import org.apache.fory.type.union.UnknownCase;
import org.apache.fory.util.Preconditions;

/**
 * Serializer for {@link Union} and its subclasses ({@link Union2}, {@link Union3}, {@link Union4},
 * {@link Union5}, {@link Union6}).
 *
 * <p>The serialization format is:
 *
 * <ul>
 *   <li>Union case id (varuint32): identifier for the active alternative
 *   <li>Value data: the serialized value of the active alternative
 * </ul>
 *
 * <p>The Union type (Union, Union2, etc.) is determined by the declared field type during
 * deserialization, not from the serialized data. This allows cross-language interoperability with
 * union types in other languages like C++'s std::variant, Rust's enum, or Python's typing.Union.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class UnionSerializer extends Serializer<Union> {
  private static final Logger LOG = LoggerFactory.getLogger(UnionSerializer.class);

  /** Array of factories for creating Union instances by type tag. */
  private static final BiFunction<Integer, Object, Union>[] FACTORIES =
      new BiFunction[] {
        (BiFunction<Integer, Object, Union>) Union::new,
        (BiFunction<Integer, Object, Union>) Union2::of,
        (BiFunction<Integer, Object, Union>) Union3::of,
        (BiFunction<Integer, Object, Union>) Union4::of,
        (BiFunction<Integer, Object, Union>) Union5::of,
        (BiFunction<Integer, Object, Union>) Union6::of
      };

  private final BiFunction<Integer, Object, Union> factory;
  private final Map<Integer, TypeRef<?>> caseValueTypes;
  private final LongMap<TypeInfo> finalCaseTypeInfo;
  private final LongMap<Serializer> finalCaseSerializers;
  private final LongMap<GenericType> finalCaseGenericTypes;
  private boolean finalCaseSerializersResolved;
  private final TypeResolver resolver;

  public UnionSerializer(TypeResolver typeResolver, Class<? extends Union> cls) {
    super(typeResolver.getConfig(), (Class<Union>) cls);
    int typeIndex = getTypeIndex(cls);
    if (typeIndex >= 0) {
      this.factory = FACTORIES[typeIndex];
    } else {
      this.factory = createFactory(cls);
    }
    finalCaseTypeInfo = new LongMap<>(1);
    finalCaseSerializers = new LongMap<>(1);
    finalCaseGenericTypes = new LongMap<>(1);
    this.caseValueTypes = resolveCaseValueTypes(cls);
    resolver = typeResolver;
  }

  private static int getTypeIndex(Class<? extends Union> cls) {
    if (cls == Union.class) {
      return 0;
    } else if (cls == Union2.class) {
      return 1;
    } else if (cls == Union3.class) {
      return 2;
    } else if (cls == Union4.class) {
      return 3;
    } else if (cls == Union5.class) {
      return 4;
    } else if (cls == Union6.class) {
      return 5;
    } else {
      return -1;
    }
  }

  private static BiFunction<Integer, Object, Union> createFactory(Class<? extends Union> cls) {
    try {
      java.lang.reflect.Constructor<? extends Union> ctor =
          cls.getDeclaredConstructor(int.class, Object.class);
      ctor.setAccessible(true);
      MethodHandle handle = MethodHandles.lookup().unreflectConstructor(ctor);
      return (index, value) -> {
        try {
          return (Union) handle.invoke(index, value);
        } catch (Throwable t) {
          throw new IllegalStateException("Failed to construct union type " + cls.getName(), t);
        }
      };
    } catch (Throwable t) {
      throw new IllegalStateException(
          "Union class "
              + cls.getName()
              + " must declare a constructor (int, Object) for UnionSerializer",
          t);
    }
  }

  @Override
  public void write(WriteContext writeContext, Union union) {
    MemoryBuffer buffer = writeContext.getBuffer();
    int caseId = union.getIndex();
    checkWireCaseId(caseId);
    buffer.writeVarUInt32(caseId);

    Object value = union.getValue();
    int valueTypeId = union.getValueTypeId();
    if (valueTypeId == Types.UNKNOWN) {
      if (value != null) {
        writeContext.writeRef(value);
      } else {
        buffer.writeByte(Fory.NULL_FLAG);
      }
      return;
    }
    writeCaseValue(writeContext, value, valueTypeId, caseId);
  }

  @Override
  public Union read(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    int caseId = buffer.readVarUInt32();
    checkWireCaseId(caseId);
    Object caseValue;
    int nextReadRefId = readContext.tryPreserveRefId();
    if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
      // ref value or not-null value
      TypeInfo declared = getFinalCaseTypeInfo(caseId);
      TypeInfo readTypeInfo = resolver.readTypeInfo(readContext, declared);
      if (declared != null) {
        Serializer serializer = getCaseSerializer(caseId, readTypeInfo.getTypeId(), declared);
        GenericType genericType = getCaseGenericType(caseId, readTypeInfo.getTypeId());
        caseValue = readCaseValue(readContext, serializer, genericType);
      } else {
        caseValue = Serializers.read(readContext, readTypeInfo.getSerializer());
      }
      readContext.setReadRef(nextReadRefId, caseValue);
    } else {
      caseValue = readContext.getReadRef();
    }
    return factory.apply(caseId, caseValue);
  }

  private static void checkWireCaseId(int caseId) {
    if (caseId < 0) {
      throwInvalidWireCaseId(caseId);
    }
  }

  private static void throwInvalidWireCaseId(int caseId) {
    throw new IllegalArgumentException("Union wire case id must be non-negative: " + caseId);
  }

  @Override
  public Union copy(CopyContext copyContext, Union union) {
    if (union == null) {
      return null;
    }
    Object value = union.getValue();
    Object copiedValue = value != null ? copyContext.copyObject(value) : null;
    return factory.apply(union.getIndex(), copiedValue);
  }

  /** Copies a schema-defined union case payload for generated non-Java union carriers. */
  public static Object copyCaseValue(
      CopyContext copyContext, FieldGroups.SerializationFieldInfo fieldInfo, Object value) {
    return StaticGeneratedStructSerializer.copyFieldValue(copyContext, value, fieldInfo);
  }

  /**
   * Copies an unknown union carrier while preserving its original peer case and payload type ids.
   */
  public static UnknownCase copyUnknownValue(CopyContext copyContext, UnknownCase unknownCase) {
    UnknownCase existing = copyContext.getCopyObject(unknownCase);
    if (existing != null) {
      return existing;
    }
    // Generated ADT unknown cases use UnknownCase identity semantics. Reusing the carrier would
    // also reuse a mutable payload, so copy creates a fresh runtime carrier and copies only the
    // polymorphic payload while keeping the wire metadata needed for reserialization.
    Object value = unknownCase.value();
    Object copiedValue = value == null ? null : copyContext.copyObject(value);
    UnknownCase copied =
        UnknownCase.ofRuntime(unknownCase.caseId(), unknownCase.typeId(), copiedValue);
    copyContext.reference(unknownCase, copied);
    return copied;
  }

  /**
   * Writes a schema-defined union case for generated non-Java union carriers.
   *
   * <p>The union envelope is owned here so generated serializers for Scala/Kotlin/etc. do not
   * duplicate protocol details or allocate temporary Java {@link Union} carriers.
   */
  public static void writeCaseValue(
      TypeResolver resolver,
      WriteContext writeContext,
      FieldGroups.SerializationFieldInfo fieldInfo,
      Object value,
      int caseId) {
    writeContext.getBuffer().writeVarUInt32(caseId);
    writeKnownCasePayload(resolver, writeContext, fieldInfo, value);
  }

  private void writeCaseValue(WriteContext writeContext, Object value, int typeId, int caseId) {
    MemoryBuffer buffer = writeContext.getBuffer();
    byte internalTypeId = (byte) typeId;
    boolean primitiveArray = Types.isPrimitiveArray(internalTypeId);
    Serializer serializer;
    TypeInfo typeInfo;
    if (value == null) {
      buffer.writeByte(Fory.NULL_FLAG);
      return;
    }
    typeInfo = getFinalCaseTypeInfo(caseId);
    if (typeInfo == null) {
      Preconditions.checkArgument(!primitiveArray);
      if (!Types.isUserDefinedType(internalTypeId)) {
        typeInfo = resolver.getTypeInfoByTypeId(internalTypeId);
      } else {
        typeInfo = resolver.getTypeInfo(value.getClass());
      }
    }
    Preconditions.checkArgument(typeInfo != null);
    serializer = getCaseSerializer(caseId, typeId, typeInfo);
    if (serializer != null && serializer.needToWriteRef()) {
      if (writeContext.writeRefOrNull(value)) {
        return;
      }
    } else {
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
    }
    if (!Types.isUserDefinedType(internalTypeId)) {
      buffer.writeUInt8(typeId);
    } else {
      resolver.writeTypeInfo(writeContext, typeInfo);
    }
    writeValue(writeContext, value, typeId, serializer, getCaseGenericType(caseId, typeId));
  }

  private static void writeCaseValue(
      WriteContext writeContext, Serializer serializer, GenericType genericType, Object value) {
    if (genericType == null) {
      Serializers.write(writeContext, serializer, value);
      return;
    }
    writeContext.getGenerics().pushGenericType(genericType, writeContext.getDepth());
    writeContext.increaseDepth();
    try {
      Serializers.write(writeContext, serializer, value);
    } finally {
      writeContext.decreaseDepth();
      writeContext.getGenerics().popGenericType(writeContext.getDepth());
    }
  }

  /**
   * Writes an unknown union case using the original peer case id preserved by {@link UnknownCase}.
   */
  public static void writeUnknownValue(WriteContext writeContext, UnknownCase unknownCase) {
    int originalCaseId = unknownCase.caseId();
    checkWireCaseId(originalCaseId);
    writeContext.getBuffer().writeVarUInt32(originalCaseId);
    UnknownCaseSerializer.writePayload(writeContext, unknownCase);
  }

  /**
   * Reads an unknown union payload and captures the runtime metadata needed for reserialization.
   */
  public static UnknownCase readUnknownValue(ReadContext readContext, int caseId) {
    checkWireCaseId(caseId);
    return UnknownCaseSerializer.readPayload(readContext, caseId);
  }

  /** Reads a schema-defined union case payload for generated non-Java union carriers. */
  public static Object readCaseValue(
      TypeResolver resolver,
      ReadContext readContext,
      FieldGroups.SerializationFieldInfo fieldInfo) {
    int typeId = Types.getDescriptorTypeId(resolver, fieldInfo.descriptor);
    if (typeId == Types.UNKNOWN) {
      return readContext.readRef();
    }
    int nextReadRefId = readContext.tryPreserveRefId();
    if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
      TypeInfo declared = getDeclaredCaseTypeInfo(fieldInfo, typeId);
      TypeInfo readTypeInfo = resolver.readTypeInfo(readContext, declared);
      Serializer serializer = getCaseSerializer(fieldInfo, readTypeInfo.getTypeId(), readTypeInfo);
      Object caseValue =
          readCaseValue(
              readContext, serializer, getCaseGenericType(fieldInfo, readTypeInfo.getTypeId()));
      readContext.setReadRef(nextReadRefId, caseValue);
      return caseValue;
    }
    return readContext.getReadRef();
  }

  private static Object readCaseValue(
      ReadContext readContext, Serializer serializer, GenericType genericType) {
    if (genericType == null) {
      return Serializers.read(readContext, serializer);
    }
    readContext.getGenerics().pushGenericType(genericType, readContext.getDepth());
    readContext.increaseDepth();
    try {
      return Serializers.read(readContext, serializer);
    } finally {
      readContext.decreaseDepth();
      readContext.getGenerics().popGenericType(readContext.getDepth());
    }
  }

  private static void writeKnownCasePayload(
      TypeResolver resolver,
      WriteContext writeContext,
      FieldGroups.SerializationFieldInfo fieldInfo,
      Object value) {
    int typeId = Types.getDescriptorTypeId(resolver, fieldInfo.descriptor);
    if (typeId == Types.UNKNOWN) {
      writeContext.writeRef(value);
      return;
    }
    MemoryBuffer buffer = writeContext.getBuffer();
    if (value == null) {
      buffer.writeByte(Fory.NULL_FLAG);
      return;
    }
    TypeInfo typeInfo = getCaseTypeInfo(resolver, fieldInfo, value, typeId);
    Serializer serializer = getCaseSerializer(fieldInfo, typeId, typeInfo);
    if (serializer != null && serializer.needToWriteRef()) {
      if (writeContext.writeRefOrNull(value)) {
        return;
      }
    } else {
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
    }
    if (!Types.isUserDefinedType(typeId)) {
      buffer.writeUInt8(typeId);
    } else {
      resolver.writeTypeInfo(writeContext, typeInfo);
    }
    writeValue(writeContext, value, typeId, serializer, getCaseGenericType(fieldInfo, typeId));
  }

  private static TypeInfo getCaseTypeInfo(
      TypeResolver resolver,
      FieldGroups.SerializationFieldInfo fieldInfo,
      Object value,
      int typeId) {
    if (Types.isPrimitiveType(typeId)) {
      return resolver.getTypeInfoByTypeId(typeId);
    }
    TypeInfo declared = getDeclaredCaseTypeInfo(fieldInfo, typeId);
    if (declared != null) {
      return declared;
    }
    if (!Types.isUserDefinedType(typeId)) {
      return resolver.getTypeInfoByTypeId(typeId);
    }
    return resolver.getTypeInfo(value.getClass());
  }

  private static TypeInfo getDeclaredCaseTypeInfo(
      FieldGroups.SerializationFieldInfo fieldInfo, int typeId) {
    if (Types.isPrimitiveType(typeId)) {
      return null;
    }
    if (fieldInfo.containerTypeInfo != null) {
      return fieldInfo.containerTypeInfo;
    }
    return fieldInfo.typeInfo;
  }

  private static Serializer getCaseSerializer(
      FieldGroups.SerializationFieldInfo fieldInfo, int typeId, TypeInfo fallbackTypeInfo) {
    if (fieldInfo.containerSerializerOverride != null
        && (typeId == Types.LIST
            || typeId == Types.SET
            || typeId == Types.MAP
            || Types.isPrimitiveArray(typeId))) {
      return fieldInfo.containerSerializerOverride;
    }
    return fallbackTypeInfo.getSerializer();
  }

  private Serializer getCaseSerializer(int caseId, int typeId, TypeInfo fallbackTypeInfo) {
    Serializer serializer = finalCaseSerializers.get(caseId);
    if (serializer != null && typeId == Types.LIST) {
      return serializer;
    }
    return fallbackTypeInfo.getSerializer();
  }

  private static GenericType getCaseGenericType(
      FieldGroups.SerializationFieldInfo fieldInfo, int typeId) {
    if (typeId != Types.LIST && typeId != Types.SET && typeId != Types.MAP) {
      return null;
    }
    return fieldInfo.genericType;
  }

  private GenericType getCaseGenericType(int caseId, int typeId) {
    if (typeId != Types.LIST && typeId != Types.MAP) {
      return null;
    }
    return finalCaseGenericTypes.get(caseId);
  }

  private static void writeValue(
      WriteContext writeContext,
      Object value,
      int typeId,
      Serializer serializer,
      GenericType genericType) {
    MemoryBuffer buffer = writeContext.getBuffer();
    int internalTypeId = typeId;
    switch (internalTypeId) {
      case Types.BOOL:
        buffer.writeBoolean((Boolean) value);
        return;
      case Types.INT8:
      case Types.UINT8:
        buffer.writeByte(((Number) value).byteValue());
        return;
      case Types.INT16:
      case Types.UINT16:
        buffer.writeInt16(((Number) value).shortValue());
        return;
      case Types.INT32:
      case Types.UINT32:
        buffer.writeInt32(((Number) value).intValue());
        return;
      case Types.VARINT32:
        buffer.writeVarInt32(((Number) value).intValue());
        return;
      case Types.VAR_UINT32:
        buffer.writeVarUInt32(((Number) value).intValue());
        return;
      case Types.FLOAT32:
        buffer.writeFloat32(((Number) value).floatValue());
        return;
      case Types.INT64:
      case Types.UINT64:
        buffer.writeInt64(((Number) value).longValue());
        return;
      case Types.VARINT64:
        buffer.writeVarInt64(((Number) value).longValue());
        return;
      case Types.TAGGED_INT64:
        buffer.writeTaggedInt64(((Number) value).longValue());
        return;
      case Types.VAR_UINT64:
        buffer.writeVarUInt64(((Number) value).longValue());
        return;
      case Types.TAGGED_UINT64:
        buffer.writeTaggedUInt64(((Number) value).longValue());
        return;
      case Types.FLOAT64:
        buffer.writeFloat64(((Number) value).doubleValue());
        return;
      case Types.STRING:
        writeContext.writeString((String) value);
        return;
      case Types.BINARY:
        buffer.writeBytes((byte[]) value);
        return;
      default:
        break;
    }
    if (serializer != null) {
      writeCaseValue(writeContext, serializer, genericType, value);
      return;
    }
    throw new IllegalStateException("Missing serializer for union type id " + typeId);
  }

  private TypeInfo getFinalCaseTypeInfo(int caseId) {
    if (!finalCaseSerializersResolved) {
      resolveFinalCaseTypeInfo();
      finalCaseSerializersResolved = true;
    }
    return finalCaseTypeInfo.get(caseId);
  }

  private void resolveFinalCaseTypeInfo() {
    for (Map.Entry<Integer, TypeRef<?>> entry : caseValueTypes.entrySet()) {
      TypeRef<?> expectedTypeRef = entry.getValue();
      Class<?> expectedType = expectedTypeRef.getRawType();
      boolean containerCaseType =
          TypeUtils.isCollection(expectedType) || TypeUtils.isMap(expectedType);
      if (!containerCaseType && !isFinalCaseType(expectedType)) {
        continue;
      }
      if (expectedType.isPrimitive()) {
        continue;
      }
      TypeInfo typeInfo = resolver.getTypeInfo(expectedType);
      finalCaseTypeInfo.put(entry.getKey(), typeInfo);
      if (TypeUtils.isPrimitiveListClass(expectedType)) {
        TypeRef<?> elementTypeRef =
            TypeAnnotationUtils.getPrimitiveListElementTypeRef(null, expectedType);
        if (elementTypeRef != null) {
          finalCaseSerializers.put(
              entry.getKey(), new CollectionSerializer(resolver, expectedType));
          finalCaseGenericTypes.put(
              entry.getKey(),
              new GenericType(
                  TypeRef.of(expectedType), true, resolver.buildGenericType(elementTypeRef)));
        }
      } else if (typeInfo.getTypeId() == Types.LIST || typeInfo.getTypeId() == Types.MAP) {
        finalCaseGenericTypes.put(entry.getKey(), resolver.buildGenericType(expectedTypeRef));
      }
    }
  }

  private static boolean isFinalCaseType(Class<?> expectedType) {
    return expectedType.isArray() || Modifier.isFinal(expectedType.getModifiers());
  }

  private Map<Integer, TypeRef<?>> resolveCaseValueTypes(Class<? extends Union> unionClass) {
    Map<Integer, TypeRef<?>> mapping = new HashMap<>();
    Class<? extends Enum<?>> caseEnum = null;
    Field idField = null;
    for (Class<?> nested : unionClass.getDeclaredClasses()) {
      if (nested.isEnum() && nested.getSimpleName().endsWith("Case")) {
        @SuppressWarnings("unchecked")
        Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) nested;
        try {
          Field field = enumClass.getDeclaredField("id");
          if (field.getType() == int.class) {
            caseEnum = enumClass;
            idField = field;
            idField.setAccessible(true);
            break;
          }
        } catch (NoSuchFieldException ignored) {
          // try next enum
        }
      }
    }
    if (caseEnum == null) {
      return mapping;
    }
    for (Enum<?> constant : caseEnum.getEnumConstants()) {
      int caseId;
      try {
        caseId = (int) idField.get(constant);
      } catch (IllegalAccessException e) {
        continue;
      }
      String suffix = toPascalCase(constant.name());
      TypeRef<?> expected = findCaseValueType(unionClass, suffix);
      if (expected != null) {
        mapping.put(caseId, expected);
      }
    }
    return mapping;
  }

  private static TypeRef<?> findCaseValueType(Class<? extends Union> unionClass, String suffix) {
    String setterName = "set" + suffix;
    for (Method method : unionClass.getMethods()) {
      if (!Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
        return TypeUtils.getMethodParameterTypeRef(method, 0);
      }
    }
    String getterName = "get" + suffix;
    for (Method method : unionClass.getMethods()) {
      if (!Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      if (method.getName().equals(getterName) && method.getParameterCount() == 0) {
        Class<?> returnType = method.getReturnType();
        if (returnType != void.class) {
          return TypeUtils.getMethodReturnTypeRef(method);
        }
      }
    }
    return null;
  }

  private static String toPascalCase(String upperSnake) {
    StringBuilder builder = new StringBuilder();
    String[] parts = upperSnake.split("_");
    for (String part : parts) {
      if (part.isEmpty()) {
        continue;
      }
      String lower = part.toLowerCase();
      builder.append(Character.toUpperCase(lower.charAt(0)));
      if (lower.length() > 1) {
        builder.append(lower.substring(1));
      }
    }
    return builder.toString();
  }
}
