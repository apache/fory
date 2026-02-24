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
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
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
  private final Map<Integer, Class<?>> caseValueTypes;
  private final LongMap<TypeInfo> finalCaseTypeInfo;
  private boolean finalCaseSerializersResolved;
  private final TypeResolver resolver;

  public UnionSerializer(Fory fory, Class<? extends Union> cls) {
    super(fory, (Class<Union>) cls);
    int typeIndex = getTypeIndex(cls);
    if (typeIndex >= 0) {
      this.factory = FACTORIES[typeIndex];
    } else {
      this.factory = createFactory(cls);
    }
    finalCaseTypeInfo = new LongMap<>();
    this.caseValueTypes = resolveCaseValueTypes(cls);
    resolver = fory.getTypeResolver();
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
  public void write(MemoryBuffer buffer, Union union) {
    int index = union.getIndex();
    buffer.writeVarUint32(index);

    Object value = union.getValue();
    int valueTypeId = union.getValueTypeId();
    if (valueTypeId == Types.UNKNOWN) {
      if (value != null) {
        fory.writeRef(buffer, value);
      } else {
        buffer.writeByte(Fory.NULL_FLAG);
      }
      return;
    }
    writeCaseValue(buffer, value, valueTypeId, index);
  }

  @Override
  public Union read(MemoryBuffer buffer) {
    int index = buffer.readVarUint32();
    Object caseValue;
    int nextReadRefId = refResolver.tryPreserveRefId(buffer);
    if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
      // ref value or not-null value
      TypeInfo declared = getFinalCaseTypeInfo(index);
      TypeInfo readTypeInfo = resolver.readTypeInfo(buffer, declared);
      if (declared != null) {
        caseValue = Serializers.read(buffer, declared.getSerializer());
      } else {
        caseValue = Serializers.read(buffer, readTypeInfo.getSerializer());
      }
      refResolver.setReadObject(nextReadRefId, caseValue);
    } else {
      caseValue = refResolver.getReadObject();
    }
    return factory.apply(index, caseValue);
  }

  @Override
  public Union copy(Union union) {
    if (union == null) {
      return null;
    }
    Object value = union.getValue();
    Object copiedValue = value != null ? fory.copyObject(value) : null;
    return factory.apply(union.getIndex(), copiedValue);
  }

  private void writeCaseValue(MemoryBuffer buffer, Object value, int typeId, int caseId) {
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
    serializer = typeInfo.getSerializer();
    RefResolver refResolver = fory.getRefResolver();
    if (serializer != null && serializer.needToWriteRef()) {
      if (refResolver.writeRefOrNull(buffer, value)) {
        return;
      }
    } else {
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
    }
    if (!Types.isUserDefinedType(internalTypeId)) {
      buffer.writeUint8(typeId);
    } else {
      resolver.writeTypeInfo(buffer, typeInfo);
    }
    writeValue(buffer, value, typeId, serializer);
  }

  private void writeValue(MemoryBuffer buffer, Object value, int typeId, Serializer serializer) {
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
        buffer.writeVarUint32(((Number) value).intValue());
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
        buffer.writeVarUint64(((Number) value).longValue());
        return;
      case Types.TAGGED_UINT64:
        buffer.writeTaggedUint64(((Number) value).longValue());
        return;
      case Types.FLOAT64:
        buffer.writeFloat64(((Number) value).doubleValue());
        return;
      case Types.STRING:
        fory.writeString(buffer, (String) value);
        return;
      case Types.BINARY:
        buffer.writeBytes((byte[]) value);
        return;
      default:
        break;
    }
    if (serializer != null) {
      Serializers.write(buffer, serializer, value);
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
    for (Map.Entry<Integer, Class<?>> entry : caseValueTypes.entrySet()) {
      Class<?> expectedType = entry.getValue();
      if (!isFinalCaseType(expectedType)) {
        continue;
      }
      if (expectedType.isPrimitive()) {
        continue;
      }
      TypeInfo typeInfo = fory.getTypeResolver().getTypeInfo(expectedType);
      finalCaseTypeInfo.put(entry.getKey(), typeInfo);
    }
  }

  private static boolean isFinalCaseType(Class<?> expectedType) {
    return expectedType.isArray() || Modifier.isFinal(expectedType.getModifiers());
  }

  private Map<Integer, Class<?>> resolveCaseValueTypes(Class<? extends Union> unionClass) {
    Map<Integer, Class<?>> mapping = new HashMap<>();
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
      Class<?> expected = findCaseValueType(unionClass, suffix);
      if (expected != null) {
        mapping.put(caseId, expected);
      }
    }
    return mapping;
  }

  private static Class<?> findCaseValueType(Class<? extends Union> unionClass, String suffix) {
    String setterName = "set" + suffix;
    for (Method method : unionClass.getMethods()) {
      if (!Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
        return method.getParameterTypes()[0];
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
          return returnType;
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
