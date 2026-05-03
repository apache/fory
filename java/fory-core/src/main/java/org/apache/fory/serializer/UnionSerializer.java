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
import java.lang.reflect.AnnotatedType;
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
import org.apache.fory.util.Preconditions;

/**
 * Serializer for {@link Union} and its subclasses ({@link Union2}, {@link Union3}, {@link Union4},
 * {@link Union5}, {@link Union6}).
 *
 * <p>The serialization format is:
 *
 * <ul>
 *   <li>Variant index (varuint32): identifies which alternative type is active
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
    int index = union.getIndex();
    buffer.writeVarUInt32(index);

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
    writeCaseValue(writeContext, value, valueTypeId, index);
  }

  @Override
  public Union read(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    int index = buffer.readVarUInt32();
    Object caseValue;
    int nextReadRefId = readContext.tryPreserveRefId();
    if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
      // ref value or not-null value
      TypeInfo declared = getFinalCaseTypeInfo(index);
      TypeInfo readTypeInfo = resolver.readTypeInfo(readContext, declared);
      if (declared != null) {
        Serializer serializer = getCaseSerializer(index, readTypeInfo.getTypeId(), declared);
        GenericType genericType = getCaseGenericType(index, readTypeInfo.getTypeId());
        caseValue = readCaseValue(readContext, serializer, genericType);
      } else {
        caseValue = Serializers.read(readContext, readTypeInfo.getSerializer());
      }
      readContext.setReadRef(nextReadRefId, caseValue);
    } else {
      caseValue = readContext.getReadRef();
    }
    return factory.apply(index, caseValue);
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

  private void writeCaseValue(
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

  private void writeValue(
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

  private Object readCaseValue(
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

  private Serializer getCaseSerializer(int caseId, int typeId, TypeInfo fallbackTypeInfo) {
    Serializer serializer = finalCaseSerializers.get(caseId);
    if (serializer != null && typeId == Types.LIST) {
      return serializer;
    }
    return fallbackTypeInfo.getSerializer();
  }

  private GenericType getCaseGenericType(int caseId, int typeId) {
    if (typeId != Types.LIST && typeId != Types.MAP) {
      return null;
    }
    return finalCaseGenericTypes.get(caseId);
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
        AnnotatedType[] parameterTypes = method.getAnnotatedParameterTypes();
        return TypeRef.of(parameterTypes[0]);
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
          return TypeRef.of(method.getAnnotatedReturnType());
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
