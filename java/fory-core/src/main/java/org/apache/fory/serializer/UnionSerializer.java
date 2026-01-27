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
import java.util.function.BiFunction;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.type.Types;
import org.apache.fory.type.union.Union;
import org.apache.fory.type.union.Union2;
import org.apache.fory.type.union.Union3;
import org.apache.fory.type.union.Union4;
import org.apache.fory.type.union.Union5;
import org.apache.fory.type.union.Union6;

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
public class UnionSerializer extends Serializer<Union> {
  /** Array of factories for creating Union instances by type tag. */
  @SuppressWarnings("unchecked")
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

  @SuppressWarnings("unchecked")
  public UnionSerializer(Fory fory, Class<? extends Union> cls) {
    super(fory, (Class<Union>) cls);
    int typeIndex = getTypeIndex(cls);
    if (typeIndex >= 0) {
      this.factory = FACTORIES[typeIndex];
    } else {
      this.factory = createFactory(cls);
    }
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
          return (Union) handle.invoke(index.intValue(), value);
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
    xwrite(buffer, union);
  }

  @Override
  public void xwrite(MemoryBuffer buffer, Union union) {
    int index = union.getIndex();
    buffer.writeVarUint32(index);

    Object value = union.getValue();
    int valueTypeId = union.getValueTypeId();
    if (valueTypeId == Types.UNKNOWN) {
      if (value != null) {
        fory.xwriteRef(buffer, value);
      } else {
        buffer.writeByte(Fory.NULL_FLAG);
      }
      return;
    }
    writeCaseValue(buffer, value, valueTypeId);
  }

  @Override
  public Union read(MemoryBuffer buffer) {
    return xread(buffer);
  }

  @Override
  public Union xread(MemoryBuffer buffer) {
    int index = buffer.readVarUint32();
    Object value = fory.xreadRef(buffer);
    return factory.apply(index, value);
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

  private void writeCaseValue(MemoryBuffer buffer, Object value, int typeId) {
    Serializer serializer = null;
    if (value != null) {
      serializer = getSerializer(typeId, value);
    }
    RefResolver refResolver = fory.getRefResolver();
    if (serializer != null && serializer.needToWriteRef()) {
      if (refResolver.writeRefOrNull(buffer, value)) {
        return;
      }
    } else {
      if (value == null) {
        buffer.writeByte(Fory.NULL_FLAG);
        return;
      }
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
    }
    writeTypeInfo(buffer, typeId, value);
    writeValue(buffer, value, typeId, serializer);
  }

  private Serializer getSerializer(int typeId, Object value) {
    int internalTypeId = typeId & 0xff;
    if (isPrimitiveType(internalTypeId)) {
      return null;
    }
    ClassInfo classInfo = getClassInfo(typeId, value);
    return classInfo == null ? null : classInfo.getSerializer();
  }

  private void writeTypeInfo(MemoryBuffer buffer, int typeId, Object value) {
    ClassInfo classInfo = getClassInfo(typeId, value);
    if (classInfo == null) {
      buffer.writeVarUint32Small7(typeId);
      return;
    }
    fory._getTypeResolver().writeClassInfo(buffer, classInfo);
  }

  private ClassInfo getClassInfo(int typeId, Object value) {
    TypeResolver resolver = fory._getTypeResolver();
    int internalTypeId = typeId & 0xff;
    if (typeId >= 256 && resolver instanceof XtypeResolver) {
      ClassInfo classInfo = ((XtypeResolver) resolver).getUserTypeInfo(typeId >>> 8);
      if (classInfo != null) {
        if ((classInfo.getTypeId() & 0xff) == internalTypeId) {
          return classInfo;
        }
      }
    }
    if (isNamedType(internalTypeId)) {
      return resolver.getClassInfo(value.getClass());
    }
    if (resolver instanceof XtypeResolver) {
      ClassInfo classInfo = ((XtypeResolver) resolver).getXtypeInfo(typeId);
      if (classInfo != null) {
        return classInfo;
      }
    }
    return resolver.getClassInfo(value.getClass());
  }

  private void writeValue(MemoryBuffer buffer, Object value, int typeId, Serializer serializer) {
    int internalTypeId = typeId & 0xff;
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
      serializer.xwrite(buffer, value);
      return;
    }
    throw new IllegalStateException("Missing serializer for union type id " + typeId);
  }

  private static boolean isNamedType(int internalTypeId) {
    return internalTypeId == Types.NAMED_ENUM
        || internalTypeId == Types.NAMED_STRUCT
        || internalTypeId == Types.NAMED_EXT
        || internalTypeId == Types.NAMED_UNION
        || internalTypeId == Types.NAMED_COMPATIBLE_STRUCT;
  }

  private static boolean isPrimitiveType(int internalTypeId) {
    switch (internalTypeId) {
      case Types.BOOL:
      case Types.INT8:
      case Types.INT16:
      case Types.INT32:
      case Types.VARINT32:
      case Types.INT64:
      case Types.VARINT64:
      case Types.TAGGED_INT64:
      case Types.UINT8:
      case Types.UINT16:
      case Types.UINT32:
      case Types.VAR_UINT32:
      case Types.UINT64:
      case Types.VAR_UINT64:
      case Types.TAGGED_UINT64:
      case Types.FLOAT16:
      case Types.FLOAT32:
      case Types.FLOAT64:
      case Types.STRING:
      case Types.BINARY:
        return true;
      default:
        return false;
    }
  }
}
