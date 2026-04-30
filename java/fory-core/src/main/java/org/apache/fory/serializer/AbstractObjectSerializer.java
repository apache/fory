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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.config.Config;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.RefReader;
import org.apache.fory.context.RefWriter;
import org.apache.fory.context.WriteContext;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.ObjectCreator;
import org.apache.fory.reflect.ObjectCreators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.DispatchId;
import org.apache.fory.type.Float16;
import org.apache.fory.type.Generics;
import org.apache.fory.type.unsigned.UInt16;
import org.apache.fory.type.unsigned.UInt32;
import org.apache.fory.type.unsigned.UInt64;
import org.apache.fory.type.unsigned.UInt8;
import org.apache.fory.util.record.RecordComponent;
import org.apache.fory.util.record.RecordInfo;
import org.apache.fory.util.record.RecordUtils;

public abstract class AbstractObjectSerializer<T> extends Serializer<T> {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractObjectSerializer.class);
  protected final Config config;
  protected final TypeResolver typeResolver;
  protected final boolean isRecord;
  protected final ObjectCreator<T> objectCreator;
  private SerializationFieldInfo[] fieldInfos;
  private RecordInfo copyRecordInfo;

  public AbstractObjectSerializer(TypeResolver typeResolver, Class<T> type) {
    this(typeResolver, type, ObjectCreators.getObjectCreator(type));
  }

  public AbstractObjectSerializer(
      TypeResolver typeResolver, Class<T> type, ObjectCreator<T> objectCreator) {
    super(typeResolver.getConfig(), type);
    this.config = typeResolver.getConfig();
    this.typeResolver = typeResolver;
    this.isRecord = RecordUtils.isRecord(type);
    this.objectCreator = objectCreator;
  }

  static void writeField(
      WriteContext writeContext,
      TypeResolver typeResolver,
      RefWriter refWriter,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer,
      Object fieldValue) {
    writeField(
        writeContext, typeResolver, refWriter, fieldInfo, fieldInfo.refMode, buffer, fieldValue);
  }

  static void writeField(
      WriteContext writeContext,
      TypeResolver typeResolver,
      RefWriter refWriter,
      SerializationFieldInfo fieldInfo,
      RefMode refMode,
      MemoryBuffer buffer,
      Object fieldValue) {
    if (fieldInfo.useDeclaredTypeInfo) {
      Serializer<Object> serializer = fieldInfo.typeInfo.getSerializer();
      if (refMode == RefMode.TRACKING) {
        if (!refWriter.writeRefOrNull(buffer, fieldValue)) {
          serializer.write(writeContext, fieldValue);
        }
      } else if (refMode == RefMode.NULL_ONLY) {
        if (fieldValue == null) {
          buffer.writeByte(Fory.NULL_FLAG);
          return;
        }
        buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
        serializer.write(writeContext, fieldValue);
      } else {
        serializer.write(writeContext, fieldValue);
      }
      return;
    }
    if (refMode == RefMode.TRACKING) {
      writeContext.writeRef(fieldValue, fieldInfo.classInfoHolder);
    } else if (refMode == RefMode.NULL_ONLY) {
      if (fieldValue == null) {
        buffer.writeByte(Fory.NULL_FLAG);
        return;
      }
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
      writeContext.writeNonRef(fieldValue, fieldInfo.classInfoHolder);
    } else {
      writeContext.writeNonRef(fieldValue, fieldInfo.classInfoHolder);
    }
  }

  static Object readField(
      ReadContext readContext,
      TypeResolver typeResolver,
      RefReader refReader,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer) {
    return readField(readContext, typeResolver, refReader, fieldInfo, fieldInfo.refMode, buffer);
  }

  static Object readField(
      ReadContext readContext,
      TypeResolver typeResolver,
      RefReader refReader,
      SerializationFieldInfo fieldInfo,
      RefMode refMode,
      MemoryBuffer buffer) {
    if (fieldInfo.useDeclaredTypeInfo) {
      if (refMode == RefMode.TRACKING) {
        return readContext.readRef(fieldInfo.typeInfo);
      }
      if (refMode != RefMode.NULL_ONLY || buffer.readByte() != Fory.NULL_FLAG) {
        refReader.preserveRefId(-1);
        return readContext.readNonRef(fieldInfo.typeInfo);
      }
      return null;
    }
    if (refMode == RefMode.TRACKING) {
      int nextReadRefId = refReader.tryPreserveRefId(buffer);
      if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
        Object value =
            typeResolver
                .readTypeInfo(readContext, fieldInfo.type)
                .getSerializer()
                .read(readContext);
        refReader.setReadRef(nextReadRefId, value);
        return value;
      }
      return refReader.getReadRef();
    }
    if (refMode != RefMode.NULL_ONLY || buffer.readByte() != Fory.NULL_FLAG) {
      TypeInfo typeInfo = typeResolver.readTypeInfo(readContext, fieldInfo.type);
      return typeInfo.getSerializer().read(readContext, RefMode.NONE);
    }
    return null;
  }

  /**
   * Write field value to buffer by reading from the object via fieldAccessor. Handles primitive
   * types, unsigned/compressed numbers, and common types like String with optimized fast paths.
   */
  static void writeBuildInField(
      WriteContext writeContext,
      TypeResolver typeResolver,
      RefWriter refWriter,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer,
      Object obj) {
    // Handle primitive fields with direct memory access
    if (fieldInfo.isPrimitiveField) {
      writePrimitiveFieldValue(buffer, obj, fieldInfo.fieldAccessor, fieldInfo.dispatchId);
      return;
    }
    // Handle non-primitive fields based on refMode
    Object fieldValue = fieldInfo.fieldAccessor.getObject(obj);
    writeBuildInFieldValue(writeContext, typeResolver, refWriter, fieldInfo, buffer, fieldValue);
  }

  /**
   * Write field value to buffer. Handles primitive types, unsigned/compressed numbers, and common
   * types like String with optimized fast paths.
   *
   * <p>This method is the write counterpart of {@link #readBuildInFieldValue(Fory, TypeResolver,
   * RefReader, SerializationFieldInfo, MemoryBuffer)}.
   */
  static void writeBuildInFieldValue(
      WriteContext writeContext,
      TypeResolver typeResolver,
      RefWriter refWriter,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer,
      Object fieldValue) {
    RefMode refMode = fieldInfo.refMode;
    // Handle non-primitive fields based on refMode
    if (refMode == RefMode.NONE) {
      // No null flag - write value directly
      writeNotPrimitiveFieldValue(
          writeContext, typeResolver, refWriter, buffer, fieldValue, fieldInfo);
    } else if (refMode == RefMode.NULL_ONLY) {
      // Write null flag, then value if not null
      if (fieldValue == null) {
        buffer.writeByte(Fory.NULL_FLAG);
        return;
      }
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
      writeNotPrimitiveFieldValue(
          writeContext, typeResolver, refWriter, buffer, fieldValue, fieldInfo);
    } else {
      writeField(writeContext, typeResolver, refWriter, fieldInfo, buffer, fieldValue);
    }
  }

  /**
   * Write a primitive field value to buffer using direct memory offset access.
   *
   * @param buffer the buffer to write to
   * @param targetObject the object containing the field
   * @param fieldOffset the memory offset of the field
   * @param dispatchId the class ID of the primitive type
   * @return true if dispatchId is not a primitive type and needs further write handling
   */
  private static boolean writePrimitiveFieldValue(
      MemoryBuffer buffer, Object targetObject, long fieldOffset, int dispatchId) {
    switch (dispatchId) {
      case DispatchId.BOOL:
        buffer.writeBoolean(Platform.getBoolean(targetObject, fieldOffset));
        return false;
      case DispatchId.INT8:
        buffer.writeByte(Platform.getByte(targetObject, fieldOffset));
        return false;
      case DispatchId.UINT8:
        buffer.writeByte(Platform.getInt(targetObject, fieldOffset));
        return false;
      case DispatchId.CHAR:
        buffer.writeChar(Platform.getChar(targetObject, fieldOffset));
        return false;
      case DispatchId.INT16:
        buffer.writeInt16(Platform.getShort(targetObject, fieldOffset));
        return false;
      case DispatchId.UINT16:
        buffer.writeInt16((short) Platform.getInt(targetObject, fieldOffset));
        return false;
      case DispatchId.INT32:
        buffer.writeInt32(Platform.getInt(targetObject, fieldOffset));
        return false;
      case DispatchId.UINT32:
        buffer.writeInt32((int) Platform.getLong(targetObject, fieldOffset));
        return false;
      case DispatchId.VARINT32:
        buffer.writeVarInt32(Platform.getInt(targetObject, fieldOffset));
        return false;
      case DispatchId.VAR_UINT32:
        buffer.writeVarUInt32((int) Platform.getLong(targetObject, fieldOffset));
        return false;
      case DispatchId.FLOAT32:
        buffer.writeFloat32(Platform.getFloat(targetObject, fieldOffset));
        return false;
      case DispatchId.INT64:
      case DispatchId.UINT64:
        buffer.writeInt64(Platform.getLong(targetObject, fieldOffset));
        return false;
      case DispatchId.VARINT64:
        buffer.writeVarInt64(Platform.getLong(targetObject, fieldOffset));
        return false;
      case DispatchId.TAGGED_INT64:
        buffer.writeTaggedInt64(Platform.getLong(targetObject, fieldOffset));
        return false;
      case DispatchId.VAR_UINT64:
        buffer.writeVarUInt64(Platform.getLong(targetObject, fieldOffset));
        return false;
      case DispatchId.TAGGED_UINT64:
        buffer.writeTaggedUInt64(Platform.getLong(targetObject, fieldOffset));
        return false;
      case DispatchId.FLOAT64:
        buffer.writeFloat64(Platform.getDouble(targetObject, fieldOffset));
        return false;
      default:
        return true;
    }
  }

  /**
   * Write a primitive field value to buffer using the field accessor.
   *
   * @param buffer the buffer to write to
   * @param targetObject the object containing the field
   * @param fieldAccessor the accessor to get the field value
   * @param dispatchId the class ID of the primitive type
   * @return true if dispatchId is not a primitive type and needs further write handling
   */
  static boolean writePrimitiveFieldValue(
      MemoryBuffer buffer, Object targetObject, FieldAccessor fieldAccessor, int dispatchId) {
    long fieldOffset = fieldAccessor.getFieldOffset();
    if (fieldOffset != -1) {
      return writePrimitiveFieldValue(buffer, targetObject, fieldOffset, dispatchId);
    }
    // graalvm use GeneratedAccessor, which will be this code path.
    switch (dispatchId) {
      case DispatchId.BOOL:
        buffer.writeBoolean((Boolean) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.INT8:
        buffer.writeByte((Byte) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.UINT8:
        buffer.writeByte((Integer) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.CHAR:
        buffer.writeChar((Character) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.INT16:
        buffer.writeInt16((Short) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.UINT16:
        buffer.writeInt16(((Integer) fieldAccessor.get(targetObject)).shortValue());
        return false;
      case DispatchId.INT32:
        buffer.writeInt32((Integer) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.UINT32:
        buffer.writeInt32(((Long) fieldAccessor.get(targetObject)).intValue());
        return false;
      case DispatchId.VARINT32:
        buffer.writeVarInt32((Integer) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.VAR_UINT32:
        buffer.writeVarUInt32(((Long) fieldAccessor.get(targetObject)).intValue());
        return false;
      case DispatchId.FLOAT32:
        buffer.writeFloat32((Float) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.INT64:
        buffer.writeInt64((Long) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.UINT64:
        buffer.writeInt64((Long) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.VARINT64:
        buffer.writeVarInt64((Long) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.TAGGED_INT64:
        buffer.writeTaggedInt64((Long) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.VAR_UINT64:
        buffer.writeVarUInt64((Long) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.TAGGED_UINT64:
        buffer.writeTaggedUInt64((Long) fieldAccessor.get(targetObject));
        return false;
      case DispatchId.FLOAT64:
        buffer.writeFloat64((Double) fieldAccessor.get(targetObject));
        return false;
      default:
        return true;
    }
  }

  /**
   * Write field value to buffer. This method handle the situation which all fields are not null.
   */
  static void writeNotPrimitiveFieldValue(
      WriteContext writeContext,
      TypeResolver typeResolver,
      RefWriter refWriter,
      MemoryBuffer buffer,
      Object fieldValue,
      SerializationFieldInfo fieldInfo) {
    if (fieldValue == null) {
      throw new IllegalArgumentException(
          "Non-nullable field has null value. In xlang mode, fields are non-nullable by default. "
              + "Use @ForyField(nullable=true) to allow null values.");
    }
    // add time types serialization here.
    switch (fieldInfo.dispatchId) {
      case DispatchId.STRING: // fastpath for string.
        writeContext.writeString((String) fieldValue);
        return;
      case DispatchId.BOOL:
        buffer.writeBoolean((Boolean) fieldValue);
        return;
      case DispatchId.INT8:
        buffer.writeByte((Byte) fieldValue);
        return;
      case DispatchId.UINT8:
        buffer.writeByte((Integer) fieldValue);
        return;
      case DispatchId.EXT_UINT8:
        buffer.writeByte(((UInt8) fieldValue).byteValue());
        return;
      case DispatchId.CHAR:
        buffer.writeChar((Character) fieldValue);
        return;
      case DispatchId.INT16:
        buffer.writeInt16((Short) fieldValue);
        return;
      case DispatchId.UINT16:
        buffer.writeInt16(((Integer) fieldValue).shortValue());
        return;
      case DispatchId.EXT_UINT16:
        buffer.writeInt16(((UInt16) fieldValue).shortValue());
        return;
      case DispatchId.INT32:
        buffer.writeInt32((Integer) fieldValue);
        return;
      case DispatchId.UINT32:
        buffer.writeInt32(((Long) fieldValue).intValue());
        return;
      case DispatchId.EXT_UINT32:
        buffer.writeInt32(((UInt32) fieldValue).intValue());
        return;
      case DispatchId.VARINT32:
        buffer.writeVarInt32((Integer) fieldValue);
        return;
      case DispatchId.VAR_UINT32:
        buffer.writeVarUInt32(((Long) fieldValue).intValue());
        return;
      case DispatchId.EXT_VAR_UINT32:
        buffer.writeVarUInt32(((UInt32) fieldValue).intValue());
        return;
      case DispatchId.INT64:
        buffer.writeInt64((Long) fieldValue);
        return;
      case DispatchId.UINT64:
        buffer.writeInt64((Long) fieldValue);
        return;
      case DispatchId.EXT_UINT64:
        buffer.writeInt64(((UInt64) fieldValue).longValue());
        return;
      case DispatchId.VARINT64:
        buffer.writeVarInt64((Long) fieldValue);
        return;
      case DispatchId.TAGGED_INT64:
        buffer.writeTaggedInt64((Long) fieldValue);
        return;
      case DispatchId.VAR_UINT64:
        buffer.writeVarUInt64((Long) fieldValue);
        return;
      case DispatchId.EXT_VAR_UINT64:
        buffer.writeVarUInt64(((UInt64) fieldValue).longValue());
        return;
      case DispatchId.TAGGED_UINT64:
        buffer.writeTaggedUInt64((Long) fieldValue);
        return;
      case DispatchId.FLOAT32:
        buffer.writeFloat32((Float) fieldValue);
        return;
      case DispatchId.FLOAT64:
        buffer.writeFloat64((Double) fieldValue);
        return;
      case DispatchId.FLOAT16:
        buffer.writeInt16(((Float16) fieldValue).toBits());
        return;
      case DispatchId.BFLOAT16:
        buffer.writeInt16(((BFloat16) fieldValue).toBits());
        return;
      default:
        writeField(
            writeContext, typeResolver, refWriter, fieldInfo, RefMode.NONE, buffer, fieldValue);
    }
  }

  static void writeContainerFieldValue(
      WriteContext writeContext,
      TypeResolver typeResolver,
      RefWriter refWriter,
      Generics generics,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer,
      Object fieldValue) {
    if (fieldInfo.refMode == RefMode.TRACKING) {
      if (refWriter.writeRefOrNull(buffer, fieldValue)) {
        return;
      }
    } else if (fieldInfo.refMode == RefMode.NULL_ONLY) {
      if (fieldValue == null) {
        buffer.writeByte(Fory.NULL_FLAG);
        return;
      }
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
    }
    generics.pushGenericType(fieldInfo.genericType, writeContext.getDepth());
    if (fieldInfo.containerSerializerOverride != null) {
      writeContext.increaseDepth();
      ((Serializer) fieldInfo.containerSerializerOverride).write(writeContext, fieldValue);
      writeContext.decreaseDepth();
    } else if (writeContext.isCrossLanguage() && fieldInfo.useDeclaredTypeInfo) {
      TypeInfo typeInfo =
          typeResolver.getTypeInfo(fieldValue.getClass(), fieldInfo.classInfoHolder);
      writeContext.writeData(typeInfo, fieldValue);
    } else if (fieldInfo.useDeclaredTypeInfo) {
      TypeInfo typeInfo =
          typeResolver.getTypeInfo(fieldValue.getClass(), fieldInfo.classInfoHolder);
      writeContext.writeNonRef(fieldValue, typeInfo);
    } else {
      writeContext.writeNonRef(fieldValue, fieldInfo.classInfoHolder);
    }
    generics.popGenericType(writeContext.getDepth());
  }

  /**
   * Read a container field value (Collection or Map). Handles reference tracking, nullable fields,
   * and pushes/pops generic type information for proper deserialization of parameterized types.
   *
   * @param typeResolver resolver used for type metadata read
   * @param refReader resolver used for reference tracking
   * @param generics the generics context for tracking parameterized types
   * @param fieldInfo the field metadata including generic type info and nullability
   * @param buffer the buffer to read from
   * @return the deserialized container field value, or null if the field is nullable and was null
   */
  static Object readContainerFieldValue(
      ReadContext readContext,
      TypeResolver typeResolver,
      RefReader refReader,
      Generics generics,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer) {
    Object fieldValue;
    switch (fieldInfo.refMode) {
      case NONE:
        refReader.preserveRefId(-1);
        generics.pushGenericType(fieldInfo.genericType, readContext.getDepth());
        fieldValue = readContainerFieldValueNoRef(readContext, fieldInfo);
        generics.popGenericType(readContext.getDepth());
        break;
      case NULL_ONLY:
        {
          refReader.preserveRefId(-1);
          byte headFlag = buffer.readByte();
          if (headFlag == Fory.NULL_FLAG) {
            return null;
          }
          generics.pushGenericType(fieldInfo.genericType, readContext.getDepth());
          fieldValue = readContainerFieldValueNoRef(readContext, fieldInfo);
          generics.popGenericType(readContext.getDepth());
        }
        break;
      case TRACKING:
        generics.pushGenericType(fieldInfo.genericType, readContext.getDepth());
        fieldValue =
            readContainerFieldValueRef(readContext, typeResolver, refReader, fieldInfo, buffer);
        generics.popGenericType(readContext.getDepth());
        break;
      default:
        throw new IllegalStateException("Unknown refMode: " + fieldInfo.refMode);
    }
    return fieldValue;
  }

  private static Object readContainerFieldValueNoRef(
      ReadContext readContext, SerializationFieldInfo fieldInfo) {
    if (fieldInfo.containerSerializerOverride != null) {
      return readContext.readNonRef(fieldInfo.containerSerializerOverride);
    }
    if (readContext.getConfig().isXlang()) {
      return readContext.readNonRef(fieldInfo.containerTypeInfo);
    }
    return readContext.readNonRef(fieldInfo.classInfoHolder);
  }

  private static Object readContainerFieldValueRef(
      ReadContext readContext,
      TypeResolver typeResolver,
      RefReader refReader,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer) {
    int nextReadRefId = refReader.tryPreserveRefId(buffer);
    if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
      Object value;
      if (fieldInfo.containerSerializerOverride != null) {
        value = readContext.readNonRef(fieldInfo.containerSerializerOverride);
      } else if (readContext.getConfig().isXlang()) {
        value = readContext.readNonRef(fieldInfo.containerTypeInfo);
      } else {
        value = readContext.readData(typeResolver.readTypeInfo(readContext));
      }
      refReader.setReadRef(nextReadRefId, value);
      return value;
    }
    return refReader.getReadRef();
  }

  /**
   * Read field value from buffer and return it. Handles primitive types, unsigned/compressed
   * numbers, and common types like String with optimized fast paths.
   *
   * <p>This method is similar to {@link #readBuildInFieldValue(ReadContext, TypeResolver,
   * RefReader, SerializationFieldInfo, MemoryBuffer)}, but returns the field value instead of
   * setting it into the target object.
   *
   * <p>The refMode determines how to read the value from buffer: - RefMode.NONE: read directly
   * without null flag - RefMode.NULL_ONLY: read null flag first, then value if not null -
   * RefMode.TRACKING: use reference tracking
   *
   * @param typeResolver resolver used for type metadata read
   * @param refReader resolver used for reference tracking
   * @param fieldInfo the field metadata including type and nullability info
   * @param buffer the buffer to read from
   * @return the deserialized field value, or null if the field is nullable and was null
   * @see #readBuildInFieldValue(ReadContext, TypeResolver, RefReader, SerializationFieldInfo,
   *     MemoryBuffer)
   */
  static Object readBuildInFieldValue(
      ReadContext readContext,
      TypeResolver typeResolver,
      RefReader refReader,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer) {
    int dispatchId = fieldInfo.dispatchId;
    RefMode refMode = fieldInfo.refMode;
    // Use refMode to determine if there's a null flag prefix in the stream
    if (refMode == RefMode.NONE) {
      // No null flag in stream - read directly
      return readNotNullBuildInFieldValue(
          readContext, typeResolver, refReader, buffer, fieldInfo, dispatchId);
    } else if (refMode == RefMode.NULL_ONLY) {
      // Read null flag from buffer
      if (buffer.readByte() == Fory.NULL_FLAG) {
        return null;
      }
      return readNotNullBuildInFieldValue(
          readContext, typeResolver, refReader, buffer, fieldInfo, dispatchId);
    }
    return readField(readContext, typeResolver, refReader, fieldInfo, buffer);
  }

  /**
   * Handle all numeric fields read include unsigned and compressed numbers. It also include
   * fastpath for common type such as String.
   */
  static void readBuildInFieldValue(
      ReadContext readContext,
      TypeResolver typeResolver,
      RefReader refReader,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer,
      Object targetObject) {
    FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
    int dispatchId = fieldInfo.dispatchId;
    if (fieldInfo.refMode == RefMode.NONE) {
      if (fieldInfo.isPrimitiveField) {
        readPrimitiveFieldValue(buffer, targetObject, fieldAccessor, dispatchId);
      } else {
        readNotPrimitiveFieldValue(
            readContext, typeResolver, refReader, buffer, targetObject, fieldInfo, dispatchId);
      }
    } else if (fieldInfo.refMode == RefMode.NULL_ONLY) {
      if (buffer.readByte() == Fory.NULL_FLAG) {
        return;
      }
      if (fieldInfo.isPrimitiveField) {
        readPrimitiveFieldValue(buffer, targetObject, fieldAccessor, dispatchId);
      } else {
        readNotPrimitiveFieldValue(
            readContext, typeResolver, refReader, buffer, targetObject, fieldInfo, dispatchId);
      }
    } else {
      Object fieldValue = readField(readContext, typeResolver, refReader, fieldInfo, buffer);
      fieldAccessor.putObject(targetObject, fieldValue);
    }
  }

  /**
   * Read a non-nullable basic object value from buffer and return it. Handles PRIMITIVE_*, and
   * STRING dispatch IDs with optimized fast paths.
   */
  private static Object readNotNullBuildInFieldValue(
      ReadContext readContext,
      TypeResolver typeResolver,
      RefReader refReader,
      MemoryBuffer buffer,
      SerializationFieldInfo fieldInfo,
      int dispatchId) {
    switch (dispatchId) {
      case DispatchId.BOOL:
        return buffer.readBoolean();
      case DispatchId.INT8:
        return buffer.readByte();
      case DispatchId.UINT8:
        return buffer.readByte() & 0xFF;
      case DispatchId.EXT_UINT8:
        return UInt8.valueOf(buffer.readByte());
      case DispatchId.CHAR:
        return buffer.readChar();
      case DispatchId.INT16:
        return buffer.readInt16();
      case DispatchId.UINT16:
        return buffer.readInt16() & 0xFFFF;
      case DispatchId.EXT_UINT16:
        return UInt16.valueOf(buffer.readInt16());
      case DispatchId.INT32:
        return buffer.readInt32();
      case DispatchId.UINT32:
        return Integer.toUnsignedLong(buffer.readInt32());
      case DispatchId.EXT_UINT32:
        return UInt32.valueOf(buffer.readInt32());
      case DispatchId.VARINT32:
        return buffer.readVarInt32();
      case DispatchId.VAR_UINT32:
        return Integer.toUnsignedLong(buffer.readVarUInt32());
      case DispatchId.EXT_VAR_UINT32:
        return UInt32.valueOf(buffer.readVarUInt32());
      case DispatchId.INT64:
        return buffer.readInt64();
      case DispatchId.UINT64:
        return buffer.readInt64();
      case DispatchId.EXT_UINT64:
        return UInt64.valueOf(buffer.readInt64());
      case DispatchId.VARINT64:
        return buffer.readVarInt64();
      case DispatchId.TAGGED_INT64:
        return buffer.readTaggedInt64();
      case DispatchId.VAR_UINT64:
        return buffer.readVarUInt64();
      case DispatchId.EXT_VAR_UINT64:
        return UInt64.valueOf(buffer.readVarUInt64());
      case DispatchId.TAGGED_UINT64:
        return buffer.readTaggedUInt64();
      case DispatchId.FLOAT32:
        return buffer.readFloat32();
      case DispatchId.FLOAT64:
        return buffer.readFloat64();
      case DispatchId.FLOAT16:
        return Float16.fromBits(buffer.readInt16());
      case DispatchId.BFLOAT16:
        return BFloat16.fromBits(buffer.readInt16());
      case DispatchId.STRING:
        return readContext.readString();
      default:
        return readField(readContext, typeResolver, refReader, fieldInfo, RefMode.NONE, buffer);
    }
  }

  /**
   * Read a primitive value from buffer and set it to field referenced by <code>fieldAccessor</code>
   * of <code>targetObject</code>.
   */
  private static void readPrimitiveFieldValue(
      MemoryBuffer buffer, Object targetObject, FieldAccessor fieldAccessor, int dispatchId) {
    long fieldOffset = fieldAccessor.getFieldOffset();
    if (fieldOffset != -1) {
      readPrimitiveFieldValue(buffer, targetObject, fieldOffset, dispatchId);
      return;
    }
    // graalvm use GeneratedAccessor, which will be this code path.
    // we still need `PRIMITIVE` cases since peer may send
    switch (dispatchId) {
      case DispatchId.BOOL:
        fieldAccessor.set(targetObject, buffer.readBoolean());
        return;
      case DispatchId.INT8:
        fieldAccessor.set(targetObject, buffer.readByte());
        return;
      case DispatchId.UINT8:
        fieldAccessor.set(targetObject, buffer.readByte() & 0xFF);
        return;
      case DispatchId.CHAR:
        fieldAccessor.set(targetObject, buffer.readChar());
        return;
      case DispatchId.INT16:
        fieldAccessor.set(targetObject, buffer.readInt16());
        return;
      case DispatchId.UINT16:
        fieldAccessor.set(targetObject, buffer.readInt16() & 0xFFFF);
        return;
      case DispatchId.INT32:
        fieldAccessor.set(targetObject, buffer.readInt32());
        return;
      case DispatchId.UINT32:
        fieldAccessor.set(targetObject, Integer.toUnsignedLong(buffer.readInt32()));
        return;
      case DispatchId.VARINT32:
        fieldAccessor.set(targetObject, buffer.readVarInt32());
        return;
      case DispatchId.VAR_UINT32:
        fieldAccessor.set(targetObject, Integer.toUnsignedLong(buffer.readVarUInt32()));
        return;
      case DispatchId.FLOAT32:
        fieldAccessor.set(targetObject, buffer.readFloat32());
        return;
      case DispatchId.INT64:
      case DispatchId.UINT64:
        fieldAccessor.set(targetObject, buffer.readInt64());
        return;
      case DispatchId.VARINT64:
        fieldAccessor.set(targetObject, buffer.readVarInt64());
        return;
      case DispatchId.TAGGED_INT64:
        fieldAccessor.set(targetObject, buffer.readTaggedInt64());
        return;
      case DispatchId.VAR_UINT64:
        fieldAccessor.set(targetObject, buffer.readVarUInt64());
        return;
      case DispatchId.TAGGED_UINT64:
        fieldAccessor.set(targetObject, buffer.readTaggedUInt64());
        return;
      case DispatchId.FLOAT64:
        fieldAccessor.set(targetObject, buffer.readFloat64());
        return;
      default:
        throw new IllegalArgumentException("Unsupported dispatch id " + dispatchId);
    }
  }

  /**
   * Read a primitive field value from buffer and set it using direct memory offset access.
   *
   * @param buffer the buffer to read from
   * @param targetObject the object to set the field value on
   * @param fieldOffset the memory offset of the field
   * @param dispatchId the dispatch ID of the primitive type
   */
  private static void readPrimitiveFieldValue(
      MemoryBuffer buffer, Object targetObject, long fieldOffset, int dispatchId) {
    switch (dispatchId) {
      case DispatchId.BOOL:
        Platform.putBoolean(targetObject, fieldOffset, buffer.readBoolean());
        return;
      case DispatchId.INT8:
        Platform.putByte(targetObject, fieldOffset, buffer.readByte());
        return;
      case DispatchId.UINT8:
        Platform.putInt(targetObject, fieldOffset, buffer.readByte() & 0xFF);
        return;
      case DispatchId.CHAR:
        Platform.putChar(targetObject, fieldOffset, buffer.readChar());
        return;
      case DispatchId.INT16:
        Platform.putShort(targetObject, fieldOffset, buffer.readInt16());
        return;
      case DispatchId.UINT16:
        Platform.putInt(targetObject, fieldOffset, buffer.readInt16() & 0xFFFF);
        return;
      case DispatchId.INT32:
        Platform.putInt(targetObject, fieldOffset, buffer.readInt32());
        return;
      case DispatchId.UINT32:
        Platform.putLong(targetObject, fieldOffset, Integer.toUnsignedLong(buffer.readInt32()));
        return;
      case DispatchId.VARINT32:
        Platform.putInt(targetObject, fieldOffset, buffer.readVarInt32());
        return;
      case DispatchId.VAR_UINT32:
        Platform.putLong(targetObject, fieldOffset, Integer.toUnsignedLong(buffer.readVarUInt32()));
        return;
      case DispatchId.FLOAT32:
        Platform.putFloat(targetObject, fieldOffset, buffer.readFloat32());
        return;
      case DispatchId.INT64:
      case DispatchId.UINT64:
        Platform.putLong(targetObject, fieldOffset, buffer.readInt64());
        return;
      case DispatchId.VARINT64:
        Platform.putLong(targetObject, fieldOffset, buffer.readVarInt64());
        return;
      case DispatchId.TAGGED_INT64:
        Platform.putLong(targetObject, fieldOffset, buffer.readTaggedInt64());
        return;
      case DispatchId.VAR_UINT64:
        Platform.putLong(targetObject, fieldOffset, buffer.readVarUInt64());
        return;
      case DispatchId.TAGGED_UINT64:
        Platform.putLong(targetObject, fieldOffset, buffer.readTaggedUInt64());
        return;
      case DispatchId.FLOAT64:
        Platform.putDouble(targetObject, fieldOffset, buffer.readFloat64());
        return;
      default:
        throw new IllegalArgumentException("Unsupported dispatch id " + dispatchId);
    }
  }

  /**
   * Read field value from buffer and set it on the target object. This method handles PRIMITIVE_*
   * and NOTNULL_BOXED_* dispatch IDs where null values are not allowed.
   */
  private static void readNotPrimitiveFieldValue(
      ReadContext readContext,
      TypeResolver typeResolver,
      RefReader refReader,
      MemoryBuffer buffer,
      Object targetObject,
      SerializationFieldInfo fieldInfo,
      int dispatchId) {
    FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
    switch (dispatchId) {
      case DispatchId.BOOL:
        fieldAccessor.putObject(targetObject, buffer.readBoolean());
        return;
      case DispatchId.INT8:
        fieldAccessor.putObject(targetObject, buffer.readByte());
        return;
      case DispatchId.UINT8:
        fieldAccessor.putObject(targetObject, buffer.readByte() & 0xFF);
        return;
      case DispatchId.EXT_UINT8:
        fieldAccessor.putObject(targetObject, UInt8.valueOf(buffer.readByte()));
        return;
      case DispatchId.CHAR:
        fieldAccessor.putObject(targetObject, buffer.readChar());
        return;
      case DispatchId.INT16:
        fieldAccessor.putObject(targetObject, buffer.readInt16());
        return;
      case DispatchId.UINT16:
        fieldAccessor.putObject(targetObject, buffer.readInt16() & 0xFFFF);
        return;
      case DispatchId.EXT_UINT16:
        fieldAccessor.putObject(targetObject, UInt16.valueOf(buffer.readInt16()));
        return;
      case DispatchId.INT32:
        fieldAccessor.putObject(targetObject, buffer.readInt32());
        return;
      case DispatchId.UINT32:
        fieldAccessor.putObject(targetObject, Integer.toUnsignedLong(buffer.readInt32()));
        return;
      case DispatchId.EXT_UINT32:
        fieldAccessor.putObject(targetObject, UInt32.valueOf(buffer.readInt32()));
        return;
      case DispatchId.VARINT32:
        fieldAccessor.putObject(targetObject, buffer.readVarInt32());
        return;
      case DispatchId.VAR_UINT32:
        fieldAccessor.putObject(targetObject, Integer.toUnsignedLong(buffer.readVarUInt32()));
        return;
      case DispatchId.EXT_VAR_UINT32:
        fieldAccessor.putObject(targetObject, UInt32.valueOf(buffer.readVarUInt32()));
        return;
      case DispatchId.INT64:
        fieldAccessor.putObject(targetObject, buffer.readInt64());
        return;
      case DispatchId.UINT64:
        fieldAccessor.putObject(targetObject, buffer.readInt64());
        return;
      case DispatchId.EXT_UINT64:
        fieldAccessor.putObject(targetObject, UInt64.valueOf(buffer.readInt64()));
        return;
      case DispatchId.VARINT64:
        fieldAccessor.putObject(targetObject, buffer.readVarInt64());
        return;
      case DispatchId.TAGGED_INT64:
        fieldAccessor.putObject(targetObject, buffer.readTaggedInt64());
        return;
      case DispatchId.VAR_UINT64:
        fieldAccessor.putObject(targetObject, buffer.readVarUInt64());
        return;
      case DispatchId.EXT_VAR_UINT64:
        fieldAccessor.putObject(targetObject, UInt64.valueOf(buffer.readVarUInt64()));
        return;
      case DispatchId.TAGGED_UINT64:
        fieldAccessor.putObject(targetObject, buffer.readTaggedUInt64());
        return;
      case DispatchId.FLOAT32:
        fieldAccessor.putObject(targetObject, buffer.readFloat32());
        return;
      case DispatchId.FLOAT64:
        fieldAccessor.putObject(targetObject, buffer.readFloat64());
        return;
      case DispatchId.FLOAT16:
        fieldAccessor.putObject(targetObject, Float16.fromBits(buffer.readInt16()));
        return;
      case DispatchId.BFLOAT16:
        fieldAccessor.putObject(targetObject, BFloat16.fromBits(buffer.readInt16()));
        return;
      case DispatchId.STRING:
        fieldAccessor.putObject(targetObject, readContext.readString());
        return;
      default:
        // Use RefMode.NONE because null flag was already handled by caller
        Object fieldValue =
            readField(readContext, typeResolver, refReader, fieldInfo, RefMode.NONE, buffer);
        fieldAccessor.putObject(targetObject, fieldValue);
    }
  }

  @Override
  public T copy(CopyContext copyContext, T originObj) {
    if (immutable) {
      return originObj;
    }
    if (isRecord) {
      return copyRecord(copyContext, originObj);
    }
    T newObj = newBean();
    copyContext.reference(originObj, newObj);
    copyFields(copyContext, originObj, newObj);
    return newObj;
  }

  private T copyRecord(CopyContext copyContext, T originObj) {
    Object[] fieldValues = copyFields(copyContext, originObj);
    try {
      T t = objectCreator.newInstanceWithArguments(fieldValues);
      Arrays.fill(copyRecordInfo.getRecordComponents(), null);
      copyContext.reference(originObj, t);
      return t;
    } catch (Throwable e) {
      Platform.throwException(e);
    }
    return originObj;
  }

  private Object[] copyFields(CopyContext copyContext, T originObj) {
    SerializationFieldInfo[] fieldInfos = this.fieldInfos;
    if (fieldInfos == null) {
      fieldInfos = buildFieldsInfo();
    }
    Object[] fieldValues = new Object[fieldInfos.length];
    for (int i = 0; i < fieldInfos.length; i++) {
      SerializationFieldInfo fieldInfo = fieldInfos[i];
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      long fieldOffset = fieldAccessor.getFieldOffset();
      if (fieldOffset != -1) {
        if (fieldInfo.isPrimitiveField) {
          fieldValues[i] = copyPrimitiveField(originObj, fieldOffset, fieldInfo.dispatchId);
        } else {
          fieldValues[i] =
              copyNotPrimitiveField(copyContext, originObj, fieldOffset, fieldInfo.dispatchId);
        }
      } else {
        // field in record class has offset -1
        Object fieldValue = fieldAccessor.get(originObj);
        fieldValues[i] = copyContext.copyObject(fieldValue, fieldInfo.dispatchId);
      }
    }
    return RecordUtils.remapping(copyRecordInfo, fieldValues);
  }

  private void copyFields(CopyContext copyContext, T originObj, T newObj) {
    SerializationFieldInfo[] fieldInfos = this.fieldInfos;
    if (fieldInfos == null) {
      fieldInfos = buildFieldsInfo();
    }
    copyFields(copyContext, fieldInfos, originObj, newObj);
  }

  public static void copyFields(
      CopyContext copyContext,
      SerializationFieldInfo[] fieldInfos,
      Object originObj,
      Object newObj) {
    for (SerializationFieldInfo fieldInfo : fieldInfos) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      long fieldOffset = fieldAccessor.getFieldOffset();
      // record class won't go to this path;
      assert fieldOffset != -1;
      if (fieldInfo.isPrimitiveField) {
        copySetPrimitiveField(originObj, newObj, fieldOffset, fieldInfo.dispatchId);
      } else {
        copySetNotPrimitiveField(copyContext, originObj, newObj, fieldOffset, fieldInfo.dispatchId);
      }
    }
  }

  private static void copySetPrimitiveField(
      Object originObj, Object newObj, long fieldOffset, int typeId) {
    switch (typeId) {
      case DispatchId.BOOL:
        Platform.putBoolean(newObj, fieldOffset, Platform.getBoolean(originObj, fieldOffset));
        break;
      case DispatchId.INT8:
        Platform.putByte(newObj, fieldOffset, Platform.getByte(originObj, fieldOffset));
        break;
      case DispatchId.UINT8:
        Platform.putInt(newObj, fieldOffset, Platform.getInt(originObj, fieldOffset));
        break;
      case DispatchId.CHAR:
        Platform.putChar(newObj, fieldOffset, Platform.getChar(originObj, fieldOffset));
        break;
      case DispatchId.INT16:
        Platform.putShort(newObj, fieldOffset, Platform.getShort(originObj, fieldOffset));
        break;
      case DispatchId.UINT16:
        Platform.putInt(newObj, fieldOffset, Platform.getInt(originObj, fieldOffset));
        break;
      case DispatchId.INT32:
      case DispatchId.VARINT32:
        Platform.putInt(newObj, fieldOffset, Platform.getInt(originObj, fieldOffset));
        break;
      case DispatchId.UINT32:
      case DispatchId.VAR_UINT32:
        Platform.putLong(newObj, fieldOffset, Platform.getLong(originObj, fieldOffset));
        break;
      case DispatchId.INT64:
      case DispatchId.VARINT64:
      case DispatchId.TAGGED_INT64:
      case DispatchId.UINT64:
      case DispatchId.VAR_UINT64:
      case DispatchId.TAGGED_UINT64:
        Platform.putLong(newObj, fieldOffset, Platform.getLong(originObj, fieldOffset));
        break;
      case DispatchId.FLOAT32:
        Platform.putFloat(newObj, fieldOffset, Platform.getFloat(originObj, fieldOffset));
        break;
      case DispatchId.FLOAT64:
        Platform.putDouble(newObj, fieldOffset, Platform.getDouble(originObj, fieldOffset));
        break;
      default:
        throw new RuntimeException("Unknown primitive type: " + typeId);
    }
  }

  private static void copySetNotPrimitiveField(
      CopyContext copyContext, Object originObj, Object newObj, long fieldOffset, int typeId) {
    switch (typeId) {
      case DispatchId.BOOL:
      case DispatchId.INT8:
      case DispatchId.UINT8:
      case DispatchId.EXT_UINT8:
      case DispatchId.CHAR:
      case DispatchId.INT16:
      case DispatchId.UINT16:
      case DispatchId.EXT_UINT16:
      case DispatchId.INT32:
      case DispatchId.VARINT32:
      case DispatchId.UINT32:
      case DispatchId.EXT_UINT32:
      case DispatchId.VAR_UINT32:
      case DispatchId.EXT_VAR_UINT32:
      case DispatchId.INT64:
      case DispatchId.VARINT64:
      case DispatchId.TAGGED_INT64:
      case DispatchId.UINT64:
      case DispatchId.EXT_UINT64:
      case DispatchId.VAR_UINT64:
      case DispatchId.EXT_VAR_UINT64:
      case DispatchId.TAGGED_UINT64:
      case DispatchId.FLOAT32:
      case DispatchId.FLOAT64:
      case DispatchId.FLOAT16:
      case DispatchId.BFLOAT16:
      case DispatchId.STRING:
        Platform.putObject(newObj, fieldOffset, Platform.getObject(originObj, fieldOffset));
        break;
      default:
        Platform.putObject(
            newObj,
            fieldOffset,
            copyContext.copyObject(Platform.getObject(originObj, fieldOffset)));
    }
  }

  private Object copyPrimitiveField(Object targetObject, long fieldOffset, int typeId) {
    switch (typeId) {
      case DispatchId.BOOL:
        return Platform.getBoolean(targetObject, fieldOffset);
      case DispatchId.INT8:
        return Platform.getByte(targetObject, fieldOffset);
      case DispatchId.UINT8:
        return Platform.getInt(targetObject, fieldOffset);
      case DispatchId.CHAR:
        return Platform.getChar(targetObject, fieldOffset);
      case DispatchId.INT16:
        return Platform.getShort(targetObject, fieldOffset);
      case DispatchId.UINT16:
        return Platform.getInt(targetObject, fieldOffset);
      case DispatchId.INT32:
      case DispatchId.VARINT32:
        return Platform.getInt(targetObject, fieldOffset);
      case DispatchId.UINT32:
      case DispatchId.VAR_UINT32:
        return Platform.getLong(targetObject, fieldOffset);
      case DispatchId.FLOAT32:
        return Platform.getFloat(targetObject, fieldOffset);
      case DispatchId.INT64:
      case DispatchId.VARINT64:
      case DispatchId.TAGGED_INT64:
      case DispatchId.UINT64:
      case DispatchId.VAR_UINT64:
      case DispatchId.TAGGED_UINT64:
        return Platform.getLong(targetObject, fieldOffset);
      case DispatchId.FLOAT64:
        return Platform.getDouble(targetObject, fieldOffset);
      default:
        throw new RuntimeException("Unknown primitive type: " + typeId);
    }
  }

  private Object copyNotPrimitiveField(
      CopyContext copyContext, Object targetObject, long fieldOffset, int typeId) {
    switch (typeId) {
      case DispatchId.BOOL:
      case DispatchId.INT8:
      case DispatchId.UINT8:
      case DispatchId.CHAR:
      case DispatchId.INT16:
      case DispatchId.UINT16:
      case DispatchId.INT32:
      case DispatchId.VARINT32:
      case DispatchId.UINT32:
      case DispatchId.VAR_UINT32:
      case DispatchId.FLOAT32:
      case DispatchId.INT64:
      case DispatchId.VARINT64:
      case DispatchId.TAGGED_INT64:
      case DispatchId.UINT64:
      case DispatchId.VAR_UINT64:
      case DispatchId.TAGGED_UINT64:
      case DispatchId.FLOAT64:
      case DispatchId.FLOAT16:
      case DispatchId.BFLOAT16:
      case DispatchId.STRING:
        return Platform.getObject(targetObject, fieldOffset);
      default:
        return copyContext.copyObject(Platform.getObject(targetObject, fieldOffset));
    }
  }

  private SerializationFieldInfo[] buildFieldsInfo() {
    List<Descriptor> descriptors = new ArrayList<>();
    if (RecordUtils.isRecord(type)) {
      RecordComponent[] components = RecordUtils.getRecordComponents(type);
      assert components != null;
      try {
        for (RecordComponent component : components) {
          Field field = type.getDeclaredField(component.getName());
          descriptors.add(
              new Descriptor(
                  field, TypeRef.of(field.getAnnotatedType()), component.getAccessor(), null));
        }
      } catch (NoSuchFieldException e) {
        // impossible
        Platform.throwException(e);
      }
    } else {
      for (Field field : ReflectionUtils.getFields(type, true)) {
        if (!Modifier.isStatic(field.getModifiers())) {
          descriptors.add(new Descriptor(field, TypeRef.of(field.getAnnotatedType()), null, null));
        }
      }
    }
    DescriptorGrouper descriptorGrouper =
        FieldGroups.buildDescriptorGrouper(typeResolver, descriptors, false, null);
    FieldGroups fieldGroups = FieldGroups.buildFieldInfos(typeResolver, descriptorGrouper);
    fieldInfos = fieldGroups.allFields;
    if (isRecord) {
      List<String> fieldNames =
          Arrays.stream(fieldInfos)
              .map(f -> f.fieldAccessor.getField().getName())
              .collect(Collectors.toList());
      copyRecordInfo = new RecordInfo(type, fieldNames);
    }
    return fieldInfos;
  }

  protected T newBean() {
    return objectCreator.newInstance();
  }
}
