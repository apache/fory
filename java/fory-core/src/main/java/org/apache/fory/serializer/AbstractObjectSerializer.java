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
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.ObjectCreator;
import org.apache.fory.reflect.ObjectCreators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;
import org.apache.fory.util.record.RecordComponent;
import org.apache.fory.util.record.RecordInfo;
import org.apache.fory.util.record.RecordUtils;

public abstract class AbstractObjectSerializer<T> extends Serializer<T> {
  protected final RefResolver refResolver;
  protected final ClassResolver classResolver;
  protected final TypeResolver typeResolver;
  protected final boolean isRecord;
  protected final ObjectCreator<T> objectCreator;
  private FieldGroups.SerializationFieldInfo[] fieldInfos;
  private RecordInfo copyRecordInfo;

  public AbstractObjectSerializer(Fory fory, Class<T> type) {
    this(fory, type, ObjectCreators.getObjectCreator(type));
  }

  public AbstractObjectSerializer(Fory fory, Class<T> type, ObjectCreator<T> objectCreator) {
    super(fory, type);
    this.refResolver = fory.getRefResolver();
    this.classResolver = fory.getClassResolver();
    this.typeResolver = fory._getTypeResolver();
    this.isRecord = RecordUtils.isRecord(type);
    this.objectCreator = objectCreator;
  }

  static void writeOtherFieldValue(
      SerializationBinding binding,
      MemoryBuffer buffer,
      FieldGroups.SerializationFieldInfo fieldInfo,
      Object fieldValue) {
    if (fieldInfo.useDeclaredTypeInfo) {
      switch (fieldInfo.refMode) {
        case NONE:
          binding.writeNonRef(buffer, fieldValue, fieldInfo.serializer);
          break;
        case NULL_ONLY:
          if (fieldValue == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            binding.writeNonRef(buffer, fieldValue, fieldInfo.serializer);
          }
          break;
        case TRACKING:
          binding.writeRef(buffer, fieldValue, fieldInfo.serializer);
          break;
        default:
          throw new IllegalStateException("Unexpected refMode: " + fieldInfo.refMode);
      }
    } else {
      switch (fieldInfo.refMode) {
        case NONE:
          binding.writeNonRef(buffer, fieldValue, fieldInfo.classInfoHolder);
          break;
        case NULL_ONLY:
          if (fieldValue == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            binding.writeNonRef(buffer, fieldValue, fieldInfo.classInfoHolder);
          }
          break;
        case TRACKING:
          binding.writeRef(buffer, fieldValue, fieldInfo.classInfoHolder);
          break;
        default:
          throw new IllegalStateException("Unexpected refMode: " + fieldInfo.refMode);
      }
    }
  }

  static void writeContainerFieldValue(
      SerializationBinding binding,
      RefResolver refResolver,
      TypeResolver typeResolver,
      Generics generics,
      FieldGroups.SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer,
      Object fieldValue) {
    switch (fieldInfo.refMode) {
      case NONE:
        generics.pushGenericType(fieldInfo.genericType);
        binding.writeContainerFieldValue(
            buffer,
            fieldValue,
            typeResolver.getClassInfo(fieldValue.getClass(), fieldInfo.classInfoHolder));
        generics.popGenericType();
        break;
      case NULL_ONLY:
        if (fieldValue == null) {
          buffer.writeByte(Fory.NULL_FLAG);
        } else {
          buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
          generics.pushGenericType(fieldInfo.genericType);
          binding.writeContainerFieldValue(
              buffer,
              fieldValue,
              typeResolver.getClassInfo(fieldValue.getClass(), fieldInfo.classInfoHolder));
          generics.popGenericType();
        }
        break;
      case TRACKING:
        if (!refResolver.writeRefOrNull(buffer, fieldValue)) {
          ClassInfo classInfo =
              typeResolver.getClassInfo(fieldValue.getClass(), fieldInfo.classInfoHolder);
          generics.pushGenericType(fieldInfo.genericType);
          binding.writeContainerFieldValue(buffer, fieldValue, classInfo);
          generics.popGenericType();
        }
        break;
      default:
        throw new IllegalStateException("Unexpected refMode: " + fieldInfo.refMode);
    }
  }

  /**
   * Write a primitive field value to buffer using direct memory offset access.
   *
   * @param fory the fory instance for compression settings
   * @param buffer the buffer to write to
   * @param targetObject the object containing the field
   * @param fieldOffset the memory offset of the field
   * @param classId the class ID of the primitive type
   * @return true if classId is not a primitive type and needs further write handling
   */
  static boolean writePrimitiveFieldValue(
      Fory fory, MemoryBuffer buffer, Object targetObject, long fieldOffset, short classId) {
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        buffer.writeBoolean(Platform.getBoolean(targetObject, fieldOffset));
        return false;
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        buffer.writeByte(Platform.getByte(targetObject, fieldOffset));
        return false;
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        buffer.writeChar(Platform.getChar(targetObject, fieldOffset));
        return false;
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        buffer.writeInt16(Platform.getShort(targetObject, fieldOffset));
        return false;
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        {
          int fieldValue = Platform.getInt(targetObject, fieldOffset);
          if (fory.compressInt()) {
            buffer.writeVarInt32(fieldValue);
          } else {
            buffer.writeInt32(fieldValue);
          }
          return false;
        }
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        buffer.writeFloat32(Platform.getFloat(targetObject, fieldOffset));
        return false;
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        {
          long fieldValue = Platform.getLong(targetObject, fieldOffset);
          fory.writeInt64(buffer, fieldValue);
          return false;
        }
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        buffer.writeFloat64(Platform.getDouble(targetObject, fieldOffset));
        return false;
      default:
        return true;
    }
  }

  /**
   * Write a primitive field value to buffer using the field accessor.
   *
   * @param fory the fory instance for compression settings
   * @param buffer the buffer to write to
   * @param targetObject the object containing the field
   * @param fieldAccessor the accessor to get the field value
   * @param classId the class ID of the primitive type
   * @return true if classId is not a primitive type and needs further write handling
   */
  static boolean writePrimitiveFieldValue(
      Fory fory,
      MemoryBuffer buffer,
      Object targetObject,
      FieldAccessor fieldAccessor,
      short classId) {
    long fieldOffset = fieldAccessor.getFieldOffset();
    if (fieldOffset != -1) {
      return writePrimitiveFieldValue(fory, buffer, targetObject, fieldOffset, classId);
    }
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        buffer.writeBoolean((Boolean) fieldAccessor.get(targetObject));
        return false;
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        buffer.writeByte((Byte) fieldAccessor.get(targetObject));
        return false;
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        buffer.writeChar((Character) fieldAccessor.get(targetObject));
        return false;
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        buffer.writeInt16((Short) fieldAccessor.get(targetObject));
        return false;
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        {
          int fieldValue = (Integer) fieldAccessor.get(targetObject);
          if (fory.compressInt()) {
            buffer.writeVarInt32(fieldValue);
          } else {
            buffer.writeInt32(fieldValue);
          }
          return false;
        }
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        buffer.writeFloat32((Float) fieldAccessor.get(targetObject));
        return false;
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        {
          long fieldValue = (long) fieldAccessor.get(targetObject);
          fory.writeInt64(buffer, fieldValue);
          return false;
        }
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        buffer.writeFloat64((Double) fieldAccessor.get(targetObject));
        return false;
      default:
        return true;
    }
  }

  /**
   * Write field value to buffer. This method handle the situation which all fields are not null.
   *
   * @return true if field value isn't written by this function.
   */
  static boolean writeBasicObjectFieldValue(
      Fory fory, MemoryBuffer buffer, Object fieldValue, short classId) {
    if (fieldValue == null) {
      throw new IllegalArgumentException(
          "Non-nullable field has null value. In xlang mode, fields are non-nullable by default. "
              + "Use @ForyField(nullable=true) to allow null values.");
    }
    if (!fory.isBasicTypesRefIgnored()) {
      return true; // let common path handle this.
    }
    // add time types serialization here.
    switch (classId) {
      case ClassResolver.STRING_CLASS_ID: // fastpath for string.
        String stringValue = (String) (fieldValue);
        if (fory.getStringSerializer().needToWriteRef()) {
          fory.writeJavaStringRef(buffer, stringValue);
        } else {
          fory.writeString(buffer, stringValue);
        }
        return false;
      case ClassResolver.BOOLEAN_CLASS_ID:
        {
          buffer.writeBoolean((Boolean) fieldValue);
          return false;
        }
      case ClassResolver.BYTE_CLASS_ID:
        {
          buffer.writeByte((Byte) fieldValue);
          return false;
        }
      case ClassResolver.CHAR_CLASS_ID:
        {
          buffer.writeChar((Character) fieldValue);
          return false;
        }
      case ClassResolver.SHORT_CLASS_ID:
        {
          buffer.writeInt16((Short) fieldValue);
          return false;
        }
      case ClassResolver.INTEGER_CLASS_ID:
        {
          if (fory.compressInt()) {
            buffer.writeVarInt32((Integer) fieldValue);
          } else {
            buffer.writeInt32((Integer) fieldValue);
          }
          return false;
        }
      case ClassResolver.FLOAT_CLASS_ID:
        {
          buffer.writeFloat32((Float) fieldValue);
          return false;
        }
      case ClassResolver.LONG_CLASS_ID:
        {
          fory.writeInt64(buffer, (Long) fieldValue);
          return false;
        }
      case ClassResolver.DOUBLE_CLASS_ID:
        {
          buffer.writeFloat64((Double) fieldValue);
          return false;
        }
      default:
        return true;
    }
  }

  /**
   * Write a nullable boxed primitive or String field value to buffer. Writes null flag before value
   * if the field is null.
   *
   * @param fory the fory instance for compression and ref tracking settings
   * @param buffer the buffer to write to
   * @param fieldValue the field value to write (may be null)
   * @param classId the class ID of the boxed type
   * @return true if classId is not a basic type or ref tracking is enabled, needing further write
   *     handling
   */
  static boolean writeBasicNullableObjectFieldValue(
      Fory fory, MemoryBuffer buffer, Object fieldValue, short classId) {
    if (!fory.isBasicTypesRefIgnored()) {
      return true; // let common path handle this.
    }
    // add time types serialization here.
    switch (classId) {
      case ClassResolver.STRING_CLASS_ID: // fastpath for string.
        fory.writeJavaStringRef(buffer, (String) (fieldValue));
        return false;
      case ClassResolver.BOOLEAN_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            buffer.writeBoolean((Boolean) (fieldValue));
          }
          return false;
        }
      case ClassResolver.BYTE_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            buffer.writeByte((Byte) (fieldValue));
          }
          return false;
        }
      case ClassResolver.CHAR_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            buffer.writeChar((Character) (fieldValue));
          }
          return false;
        }
      case ClassResolver.SHORT_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            buffer.writeInt16((Short) (fieldValue));
          }
          return false;
        }
      case ClassResolver.INTEGER_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            if (fory.compressInt()) {
              buffer.writeVarInt32((Integer) (fieldValue));
            } else {
              buffer.writeInt32((Integer) (fieldValue));
            }
          }
          return false;
        }
      case ClassResolver.FLOAT_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            buffer.writeFloat32((Float) (fieldValue));
          }
          return false;
        }
      case ClassResolver.LONG_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            fory.writeInt64(buffer, (Long) fieldValue);
          }
          return false;
        }
      case ClassResolver.DOUBLE_CLASS_ID:
        {
          if (fieldValue == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            buffer.writeFloat64((Double) (fieldValue));
          }
          return false;
        }
      default:
        return true;
    }
  }

  /**
   * Read final object field value. Note that primitive field value can't be read by this method,
   * because primitive field doesn't write null flag.
   */
  static Object readFinalObjectFieldValue(
      SerializationBinding binding,
      RefResolver refResolver,
      TypeResolver typeResolver,
      FieldGroups.SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer) {
    Serializer<Object> serializer = fieldInfo.classInfo.getSerializer();
    binding.incReadDepth();
    Object fieldValue;
    if (fieldInfo.useDeclaredTypeInfo) {
      switch (fieldInfo.refMode) {
        case NONE:
          fieldValue = binding.read(buffer, serializer);
          break;
        case NULL_ONLY:
          fieldValue = binding.readNullable(buffer, serializer);
          break;
        case TRACKING:
          // whether tracking ref is recorded in `fieldInfo.serializer`, so it's still
          // consistent with jit serializer.
          fieldValue = binding.readRef(buffer, serializer);
          break;
        default:
          throw new IllegalStateException("Unknown refMode: " + fieldInfo.refMode);
      }
    } else {
      switch (fieldInfo.refMode) {
        case NONE:
          typeResolver.readClassInfo(buffer, fieldInfo.classInfo);
          fieldValue = serializer.read(buffer);
          break;
        case NULL_ONLY:
          {
            byte headFlag = buffer.readByte();
            if (headFlag == Fory.NULL_FLAG) {
              binding.decDepth();
              return null;
            }
            typeResolver.readClassInfo(buffer, fieldInfo.classInfo);
            fieldValue = serializer.read(buffer);
          }
          break;
        case TRACKING:
          {
            int nextReadRefId = refResolver.tryPreserveRefId(buffer);
            if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
              typeResolver.readClassInfo(buffer, fieldInfo.classInfo);
              fieldValue = serializer.read(buffer);
              refResolver.setReadObject(nextReadRefId, fieldValue);
            } else {
              fieldValue = refResolver.getReadObject();
            }
          }
          break;
        default:
          throw new IllegalStateException("Unknown refMode: " + fieldInfo.refMode);
      }
    }
    binding.decDepth();
    return fieldValue;
  }

  /**
   * Read a non-container field value that is not a final type. Handles enum types, reference
   * tracking, and nullable fields according to xlang serialization protocol.
   *
   * @param binding the serialization binding for read operations
   * @param fieldInfo the field metadata including type info and nullability
   * @param buffer the buffer to read from
   * @return the deserialized field value, or null if the field is nullable and was null
   */
  static Object readOtherFieldValue(
      SerializationBinding binding,
      FieldGroups.SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer) {
    // Note: Enum has special handling for xlang compatibility - no type info for enum fields
    if (fieldInfo.genericType.getCls().isEnum()) {
      // Only read null flag when the field is nullable (for xlang compatibility)
      if (fieldInfo.nullable && buffer.readByte() == Fory.NULL_FLAG) {
        return null;
      }
      return fieldInfo.genericType.getSerializer(binding.typeResolver).read(buffer);
    }
    Object fieldValue;
    switch (fieldInfo.refMode) {
      case NONE:
        binding.preserveRefId(-1);
        fieldValue = binding.readNonRef(buffer, fieldInfo);
        break;
      case NULL_ONLY:
        {
          binding.preserveRefId(-1);
          byte headFlag = buffer.readByte();
          if (headFlag == Fory.NULL_FLAG) {
            return null;
          }
          fieldValue = binding.readNonRef(buffer, fieldInfo);
        }
        break;
      case TRACKING:
        fieldValue = binding.readRef(buffer, fieldInfo);
        break;
      default:
        throw new IllegalStateException("Unknown refMode: " + fieldInfo.refMode);
    }
    return fieldValue;
  }

  /**
   * Read a container field value (Collection or Map). Handles reference tracking, nullable fields,
   * and pushes/pops generic type information for proper deserialization of parameterized types.
   *
   * @param binding the serialization binding for read operations
   * @param generics the generics context for tracking parameterized types
   * @param fieldInfo the field metadata including generic type info and nullability
   * @param buffer the buffer to read from
   * @return the deserialized container field value, or null if the field is nullable and was null
   */
  static Object readContainerFieldValue(
      SerializationBinding binding,
      Generics generics,
      FieldGroups.SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer) {
    Object fieldValue;
    switch (fieldInfo.refMode) {
      case NONE:
        binding.preserveRefId(-1);
        generics.pushGenericType(fieldInfo.genericType);
        fieldValue = binding.readContainerFieldValue(buffer, fieldInfo);
        generics.popGenericType();
        break;
      case NULL_ONLY:
        {
          binding.preserveRefId(-1);
          byte headFlag = buffer.readByte();
          if (headFlag == Fory.NULL_FLAG) {
            return null;
          }
          generics.pushGenericType(fieldInfo.genericType);
          fieldValue = binding.readContainerFieldValue(buffer, fieldInfo);
          generics.popGenericType();
        }
        break;
      case TRACKING:
        generics.pushGenericType(fieldInfo.genericType);
        fieldValue = binding.readContainerFieldValueRef(buffer, fieldInfo);
        generics.popGenericType();
        break;
      default:
        throw new IllegalStateException("Unknown refMode: " + fieldInfo.refMode);
    }
    return fieldValue;
  }

  /**
   * Read a primitive value from buffer and set it to field referenced by <code>fieldAccessor</code>
   * of <code>targetObject</code>.
   *
   * @return true if <code>classId</code> is not a primitive type id.
   */
  static boolean readPrimitiveFieldValue(
      Fory fory,
      MemoryBuffer buffer,
      Object targetObject,
      FieldAccessor fieldAccessor,
      short classId) {
    long fieldOffset = fieldAccessor.getFieldOffset();
    if (fieldOffset != -1) {
      return readPrimitiveFieldValue(fory, buffer, targetObject, fieldOffset, classId);
    }
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        fieldAccessor.set(targetObject, buffer.readBoolean());
        return false;
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        fieldAccessor.set(targetObject, buffer.readByte());
        return false;
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        fieldAccessor.set(targetObject, buffer.readChar());
        return false;
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        fieldAccessor.set(targetObject, buffer.readInt16());
        return false;
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        if (fory.compressInt()) {
          fieldAccessor.set(targetObject, buffer.readVarInt32());
        } else {
          fieldAccessor.set(targetObject, buffer.readInt32());
        }
        return false;
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        fieldAccessor.set(targetObject, buffer.readFloat32());
        return false;
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        fieldAccessor.set(targetObject, fory.readInt64(buffer));
        return false;
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        fieldAccessor.set(targetObject, buffer.readFloat64());
        return false;
      default:
        return true;
    }
  }

  /**
   * Read a primitive field value from buffer and set it using direct memory offset access.
   *
   * @param fory the fory instance for compression settings
   * @param buffer the buffer to read from
   * @param targetObject the object to set the field value on
   * @param fieldOffset the memory offset of the field
   * @param classId the class ID of the primitive type
   * @return true if classId is not a primitive type and needs further read handling
   */
  private static boolean readPrimitiveFieldValue(
      Fory fory, MemoryBuffer buffer, Object targetObject, long fieldOffset, short classId) {
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        Platform.putBoolean(targetObject, fieldOffset, buffer.readBoolean());
        return false;
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        Platform.putByte(targetObject, fieldOffset, buffer.readByte());
        return false;
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        Platform.putChar(targetObject, fieldOffset, buffer.readChar());
        return false;
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        Platform.putShort(targetObject, fieldOffset, buffer.readInt16());
        return false;
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        if (fory.compressInt()) {
          Platform.putInt(targetObject, fieldOffset, buffer.readVarInt32());
        } else {
          Platform.putInt(targetObject, fieldOffset, buffer.readInt32());
        }
        return false;
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        Platform.putFloat(targetObject, fieldOffset, buffer.readFloat32());
        return false;
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        Platform.putLong(targetObject, fieldOffset, fory.readInt64(buffer));
        return false;
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        Platform.putDouble(targetObject, fieldOffset, buffer.readFloat64());
        return false;
      default:
        return true;
    }
  }

  /**
   * Read a nullable primitive field value from buffer. Reads the null flag first and returns early
   * if null.
   *
   * @param fory the fory instance for compression settings
   * @param buffer the buffer to read from
   * @param targetObject the object to set the field value on
   * @param fieldAccessor the accessor to set the field value
   * @param classId the class ID of the primitive type
   * @return true if classId is not a primitive type and needs further read handling; false if value
   *     was null or successfully read
   */
  static boolean readPrimitiveNullableFieldValue(
      Fory fory,
      MemoryBuffer buffer,
      Object targetObject,
      FieldAccessor fieldAccessor,
      short classId) {
    if (buffer.readByte() == Fory.NULL_FLAG) {
      return false;
    }
    return readPrimitiveFieldValue(fory, buffer, targetObject, fieldAccessor, classId);
  }

  /**
   * read field value from buffer. This method handle the situation which all fields are not null.
   *
   * @return true if field value isn't read by this function.
   */
  static boolean readBasicObjectFieldValue(
      Fory fory,
      MemoryBuffer buffer,
      Object targetObject,
      FieldAccessor fieldAccessor,
      short classId) {
    if (!fory.isBasicTypesRefIgnored()) {
      return true; // let common path handle this.
    }
    // add time types serialization here.
    switch (classId) {
      case ClassResolver.STRING_CLASS_ID: // fastpath for string.
        if (fory.getStringSerializer().needToWriteRef()) {
          fieldAccessor.putObject(targetObject, fory.readJavaStringRef(buffer));
        } else {
          fieldAccessor.putObject(targetObject, fory.readString(buffer));
        }
        return false;
      case ClassResolver.BOOLEAN_CLASS_ID:
        {
          fieldAccessor.putObject(targetObject, buffer.readBoolean());
          return false;
        }
      case ClassResolver.BYTE_CLASS_ID:
        {
          fieldAccessor.putObject(targetObject, buffer.readByte());
          return false;
        }
      case ClassResolver.CHAR_CLASS_ID:
        {
          fieldAccessor.putObject(targetObject, buffer.readChar());
          return false;
        }
      case ClassResolver.SHORT_CLASS_ID:
        {
          fieldAccessor.putObject(targetObject, buffer.readInt16());
          return false;
        }
      case ClassResolver.INTEGER_CLASS_ID:
        {
          if (fory.compressInt()) {
            fieldAccessor.putObject(targetObject, buffer.readVarInt32());
          } else {
            fieldAccessor.putObject(targetObject, buffer.readInt32());
          }
          return false;
        }
      case ClassResolver.FLOAT_CLASS_ID:
        {
          fieldAccessor.putObject(targetObject, buffer.readFloat32());
          return false;
        }
      case ClassResolver.LONG_CLASS_ID:
        {
          fieldAccessor.putObject(targetObject, fory.readInt64(buffer));
          return false;
        }
      case ClassResolver.DOUBLE_CLASS_ID:
        {
          fieldAccessor.putObject(targetObject, buffer.readFloat64());
          return false;
        }
      default:
        return true;
    }
  }

  /**
   * Read a nullable boxed primitive or String field value from buffer and set it on the target
   * object. Reads the null flag before value for nullable types.
   *
   * @param fory the fory instance for compression and ref tracking settings
   * @param buffer the buffer to read from
   * @param targetObject the object to set the field value on
   * @param fieldAccessor the accessor to set the field value
   * @param classId the class ID of the boxed type
   * @return true if classId is not a basic type or ref tracking is enabled, needing further read
   *     handling
   */
  static boolean readBasicNullableObjectFieldValue(
      Fory fory,
      MemoryBuffer buffer,
      Object targetObject,
      FieldAccessor fieldAccessor,
      short classId) {
    if (!fory.isBasicTypesRefIgnored()) {
      return true; // let common path handle this.
    }
    // add time types serialization here.
    switch (classId) {
      case ClassResolver.STRING_CLASS_ID: // fastpath for string.
        fieldAccessor.putObject(targetObject, fory.readJavaStringRef(buffer));
        return false;
      case ClassResolver.BOOLEAN_CLASS_ID:
        {
          if (buffer.readByte() == Fory.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readBoolean());
          }
          return false;
        }
      case ClassResolver.BYTE_CLASS_ID:
        {
          if (buffer.readByte() == Fory.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readByte());
          }
          return false;
        }
      case ClassResolver.CHAR_CLASS_ID:
        {
          if (buffer.readByte() == Fory.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readChar());
          }
          return false;
        }
      case ClassResolver.SHORT_CLASS_ID:
        {
          if (buffer.readByte() == Fory.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readInt16());
          }
          return false;
        }
      case ClassResolver.INTEGER_CLASS_ID:
        {
          if (buffer.readByte() == Fory.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            if (fory.compressInt()) {
              fieldAccessor.putObject(targetObject, buffer.readVarInt32());
            } else {
              fieldAccessor.putObject(targetObject, buffer.readInt32());
            }
          }
          return false;
        }
      case ClassResolver.FLOAT_CLASS_ID:
        {
          if (buffer.readByte() == Fory.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readFloat32());
          }
          return false;
        }
      case ClassResolver.LONG_CLASS_ID:
        {
          if (buffer.readByte() == Fory.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, fory.readInt64(buffer));
          }
          return false;
        }
      case ClassResolver.DOUBLE_CLASS_ID:
        {
          if (buffer.readByte() == Fory.NULL_FLAG) {
            fieldAccessor.putObject(targetObject, null);
          } else {
            fieldAccessor.putObject(targetObject, buffer.readFloat64());
          }
          return false;
        }
      default:
        return true;
    }
  }

  @Override
  public T copy(T originObj) {
    if (immutable) {
      return originObj;
    }
    if (isRecord) {
      return copyRecord(originObj);
    }
    T newObj = newBean();
    if (needToCopyRef) {
      fory.reference(originObj, newObj);
    }
    copyFields(originObj, newObj);
    return newObj;
  }

  private T copyRecord(T originObj) {
    Object[] fieldValues = copyFields(originObj);
    try {
      T t = (T) objectCreator.newInstanceWithArguments(fieldValues);
      Arrays.fill(copyRecordInfo.getRecordComponents(), null);
      fory.reference(originObj, t);
      return t;
    } catch (Throwable e) {
      Platform.throwException(e);
    }
    return originObj;
  }

  private Object[] copyFields(T originObj) {
    FieldGroups.SerializationFieldInfo[] fieldInfos = this.fieldInfos;
    if (fieldInfos == null) {
      fieldInfos = buildFieldsInfo();
    }
    Object[] fieldValues = new Object[fieldInfos.length];
    for (int i = 0; i < fieldInfos.length; i++) {
      FieldGroups.SerializationFieldInfo fieldInfo = fieldInfos[i];
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      long fieldOffset = fieldAccessor.getFieldOffset();
      if (fieldOffset != -1) {
        fieldValues[i] = copyField(originObj, fieldOffset, fieldInfo.classId);
      } else {
        // field in record class has offset -1
        Object fieldValue = fieldAccessor.get(originObj);
        fieldValues[i] = fory.copyObject(fieldValue, fieldInfo.classId);
      }
    }
    return RecordUtils.remapping(copyRecordInfo, fieldValues);
  }

  private void copyFields(T originObj, T newObj) {
    FieldGroups.SerializationFieldInfo[] fieldInfos = this.fieldInfos;
    if (fieldInfos == null) {
      fieldInfos = buildFieldsInfo();
    }
    for (FieldGroups.SerializationFieldInfo fieldInfo : fieldInfos) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      long fieldOffset = fieldAccessor.getFieldOffset();
      // record class won't go to this path;
      assert fieldOffset != -1;
      switch (fieldInfo.classId) {
        case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
          Platform.putByte(newObj, fieldOffset, Platform.getByte(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
          Platform.putChar(newObj, fieldOffset, Platform.getChar(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
          Platform.putShort(newObj, fieldOffset, Platform.getShort(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_INT_CLASS_ID:
          Platform.putInt(newObj, fieldOffset, Platform.getInt(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
          Platform.putLong(newObj, fieldOffset, Platform.getLong(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
          Platform.putFloat(newObj, fieldOffset, Platform.getFloat(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
          Platform.putDouble(newObj, fieldOffset, Platform.getDouble(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
          Platform.putBoolean(newObj, fieldOffset, Platform.getBoolean(originObj, fieldOffset));
          break;
        case ClassResolver.BOOLEAN_CLASS_ID:
        case ClassResolver.BYTE_CLASS_ID:
        case ClassResolver.CHAR_CLASS_ID:
        case ClassResolver.SHORT_CLASS_ID:
        case ClassResolver.INTEGER_CLASS_ID:
        case ClassResolver.FLOAT_CLASS_ID:
        case ClassResolver.LONG_CLASS_ID:
        case ClassResolver.DOUBLE_CLASS_ID:
        case ClassResolver.STRING_CLASS_ID:
          Platform.putObject(newObj, fieldOffset, Platform.getObject(originObj, fieldOffset));
          break;
        default:
          Platform.putObject(
              newObj, fieldOffset, fory.copyObject(Platform.getObject(originObj, fieldOffset)));
      }
    }
  }

  public static void copyFields(
      Fory fory, FieldGroups.SerializationFieldInfo[] fieldInfos, Object originObj, Object newObj) {
    for (FieldGroups.SerializationFieldInfo fieldInfo : fieldInfos) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      long fieldOffset = fieldAccessor.getFieldOffset();
      // record class won't go to this path;
      assert fieldOffset != -1;
      switch (fieldInfo.classId) {
        case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
          Platform.putByte(newObj, fieldOffset, Platform.getByte(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
          Platform.putChar(newObj, fieldOffset, Platform.getChar(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
          Platform.putShort(newObj, fieldOffset, Platform.getShort(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_INT_CLASS_ID:
          Platform.putInt(newObj, fieldOffset, Platform.getInt(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
          Platform.putLong(newObj, fieldOffset, Platform.getLong(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
          Platform.putFloat(newObj, fieldOffset, Platform.getFloat(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
          Platform.putDouble(newObj, fieldOffset, Platform.getDouble(originObj, fieldOffset));
          break;
        case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
          Platform.putBoolean(newObj, fieldOffset, Platform.getBoolean(originObj, fieldOffset));
          break;
        case ClassResolver.BOOLEAN_CLASS_ID:
        case ClassResolver.BYTE_CLASS_ID:
        case ClassResolver.CHAR_CLASS_ID:
        case ClassResolver.SHORT_CLASS_ID:
        case ClassResolver.INTEGER_CLASS_ID:
        case ClassResolver.FLOAT_CLASS_ID:
        case ClassResolver.LONG_CLASS_ID:
        case ClassResolver.DOUBLE_CLASS_ID:
        case ClassResolver.STRING_CLASS_ID:
          Platform.putObject(newObj, fieldOffset, Platform.getObject(originObj, fieldOffset));
          break;
        default:
          Platform.putObject(
              newObj, fieldOffset, fory.copyObject(Platform.getObject(originObj, fieldOffset)));
      }
    }
  }

  private Object copyField(Object targetObject, long fieldOffset, short classId) {
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        return Platform.getBoolean(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        return Platform.getByte(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        return Platform.getChar(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        return Platform.getShort(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        return Platform.getInt(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        return Platform.getFloat(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        return Platform.getLong(targetObject, fieldOffset);
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        return Platform.getDouble(targetObject, fieldOffset);
      case ClassResolver.BOOLEAN_CLASS_ID:
      case ClassResolver.BYTE_CLASS_ID:
      case ClassResolver.CHAR_CLASS_ID:
      case ClassResolver.SHORT_CLASS_ID:
      case ClassResolver.INTEGER_CLASS_ID:
      case ClassResolver.FLOAT_CLASS_ID:
      case ClassResolver.LONG_CLASS_ID:
      case ClassResolver.DOUBLE_CLASS_ID:
      case ClassResolver.STRING_CLASS_ID:
        return Platform.getObject(targetObject, fieldOffset);
      default:
        return fory.copyObject(Platform.getObject(targetObject, fieldOffset));
    }
  }

  private FieldGroups.SerializationFieldInfo[] buildFieldsInfo() {
    List<Descriptor> descriptors = new ArrayList<>();
    if (RecordUtils.isRecord(type)) {
      RecordComponent[] components = RecordUtils.getRecordComponents(type);
      assert components != null;
      try {
        for (RecordComponent component : components) {
          Field field = type.getDeclaredField(component.getName());
          descriptors.add(
              new Descriptor(
                  field, TypeRef.of(field.getGenericType()), component.getAccessor(), null));
        }
      } catch (NoSuchFieldException e) {
        // impossible
        Platform.throwException(e);
      }
    } else {
      for (Field field : ReflectionUtils.getFields(type, true)) {
        if (!Modifier.isStatic(field.getModifiers())) {
          descriptors.add(new Descriptor(field, TypeRef.of(field.getGenericType()), null, null));
        }
      }
    }
    DescriptorGrouper descriptorGrouper =
        fory.getClassResolver().createDescriptorGrouper(descriptors, false);
    FieldGroups fieldGroups = FieldGroups.buildFieldInfos(fory, descriptorGrouper);
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
