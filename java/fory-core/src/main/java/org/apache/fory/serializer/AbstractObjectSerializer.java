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

import static org.apache.fory.type.TypeUtils.getRawType;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.collection.Tuple3;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.ObjectCreator;
import org.apache.fory.reflect.ObjectCreators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassInfoHolder;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.converter.FieldConverter;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.FinalObjectTypeStub;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.Generics;
import org.apache.fory.util.record.RecordComponent;
import org.apache.fory.util.record.RecordInfo;
import org.apache.fory.util.record.RecordUtils;

public abstract class AbstractObjectSerializer<T> extends Serializer<T> {
  protected final RefResolver refResolver;
  protected final ClassResolver classResolver;
  protected final boolean isRecord;
  protected final ObjectCreator<T> objectCreator;
  private InternalFieldInfo[] fieldInfos;
  private RecordInfo copyRecordInfo;

  public AbstractObjectSerializer(Fory fory, Class<T> type) {
    this(fory, type, ObjectCreators.getObjectCreator(type));
  }

  public AbstractObjectSerializer(Fory fory, Class<T> type, ObjectCreator<T> objectCreator) {
    super(fory, type);
    this.refResolver = fory.getRefResolver();
    this.classResolver = fory.getClassResolver();
    this.isRecord = RecordUtils.isRecord(type);
    this.objectCreator = objectCreator;
  }

  /**
   * Read final object field value. Note that primitive field value can't be read by this method,
   * because primitive field doesn't write null flag.
   */
  static Object readFinalObjectFieldValue(
      SerializationBinding binding,
      RefResolver refResolver,
      TypeResolver typeResolver,
      FinalTypeField fieldInfo,
      boolean isFinal,
      MemoryBuffer buffer) {
    Serializer<Object> serializer = fieldInfo.classInfo.getSerializer();
    binding.incReadDepth();
    Object fieldValue;
    boolean nullable = fieldInfo.nullable;
    if (isFinal) {
      if (!fieldInfo.trackingRef) {
        fieldValue = binding.readNullable(buffer, serializer, nullable);
      } else {
        // whether tracking ref is recorded in `fieldInfo.serializer`, so it's still
        // consistent with jit serializer.
        fieldValue = binding.readRef(buffer, serializer);
      }
    } else {
      if (serializer.needToWriteRef()) {
        int nextReadRefId = refResolver.tryPreserveRefId(buffer);
        if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
          typeResolver.readClassInfo(buffer, fieldInfo.classInfo);
          fieldValue = serializer.read(buffer);
          refResolver.setReadObject(nextReadRefId, fieldValue);
        } else {
          fieldValue = refResolver.getReadObject();
        }
      } else {
        if (nullable) {
          byte headFlag = buffer.readByte();
          if (headFlag == Fory.NULL_FLAG) {
            binding.decDepth();
            return null;
          }
        }
        typeResolver.readClassInfo(buffer, fieldInfo.classInfo);
        fieldValue = serializer.read(buffer);
      }
    }
    binding.decDepth();
    return fieldValue;
  }

  static Object readOtherFieldValue(
      SerializationBinding binding, GenericTypeField fieldInfo, MemoryBuffer buffer) {
    Object fieldValue;
    boolean nullable = fieldInfo.nullable;
    if (fieldInfo.genericType.getCls().isEnum()) {
      if (buffer.readByte() == Fory.NULL_FLAG) {
        return null;
      } else {
        return fieldInfo.genericType.getSerializer(binding.typeResolver).read(buffer);
      }
    } else if (fieldInfo.trackingRef) {
      fieldValue = binding.readRef(buffer, fieldInfo);
    } else {
      binding.preserveRefId(-1);
      if (nullable) {
        byte headFlag = buffer.readByte();
        if (headFlag == Fory.NULL_FLAG) {
          return null;
        }
      }
      fieldValue = binding.readNonRef(buffer, fieldInfo);
    }
    return fieldValue;
  }

  static Object readContainerFieldValue(
      SerializationBinding binding,
      Generics generics,
      GenericTypeField fieldInfo,
      MemoryBuffer buffer) {
    Object fieldValue;
    if (fieldInfo.trackingRef) {
      generics.pushGenericType(fieldInfo.genericType);
      fieldValue = binding.readContainerFieldValueRef(buffer, fieldInfo);
      generics.popGenericType();
    } else {
      binding.preserveRefId(-1);
      boolean nullable = fieldInfo.nullable;
      if (nullable) {
        byte headFlag = buffer.readByte();
        if (headFlag == Fory.NULL_FLAG) {
          return null;
        }
      }
      generics.pushGenericType(fieldInfo.genericType);
      fieldValue = binding.readContainerFieldValue(buffer, fieldInfo);
      generics.popGenericType();
    }
    return fieldValue;
  }

  static boolean writePrimitiveFieldValueFailed(
      Fory fory,
      MemoryBuffer buffer,
      Object targetObject,
      FieldAccessor fieldAccessor,
      short classId) {
    long fieldOffset = fieldAccessor.getFieldOffset();
    if (fieldOffset != -1) {
      return writePrimitiveFieldValueFailed(fory, buffer, targetObject, fieldOffset, classId);
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

  static boolean writePrimitiveFieldValueFailed(
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
   * Write field value to buffer. This method handle the situation which all fields are not null.
   *
   * @return true if field value isn't written by this function.
   */
  static boolean writeBasicObjectFieldValueFailed(
      Fory fory, MemoryBuffer buffer, Object fieldValue, short classId) {
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

  static boolean writeBasicNullableObjectFieldValueFailed(
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
   * Read a primitive value from buffer and set it to field referenced by <code>fieldAccessor</code>
   * of <code>targetObject</code>.
   *
   * @return true if <code>classId</code> is not a primitive type id.
   */
  static boolean readPrimitiveFieldValueFailed(
      Fory fory,
      MemoryBuffer buffer,
      Object targetObject,
      FieldAccessor fieldAccessor,
      short classId) {
    long fieldOffset = fieldAccessor.getFieldOffset();
    if (fieldOffset != -1) {
      return readPrimitiveFieldValueFailed(fory, buffer, targetObject, fieldOffset, classId);
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

  private static boolean readPrimitiveFieldValueFailed(
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
   * read field value from buffer. This method handle the situation which all fields are not null.
   *
   * @return true if field value isn't read by this function.
   */
  static boolean readBasicObjectFieldValueFailed(
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

  static boolean readBasicNullableObjectFieldValueFailed(
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
    InternalFieldInfo[] fieldInfos = this.fieldInfos;
    if (fieldInfos == null) {
      fieldInfos = buildFieldsInfo();
    }
    Object[] fieldValues = new Object[fieldInfos.length];
    for (int i = 0; i < fieldInfos.length; i++) {
      InternalFieldInfo fieldInfo = fieldInfos[i];
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
    InternalFieldInfo[] fieldInfos = this.fieldInfos;
    if (fieldInfos == null) {
      fieldInfos = buildFieldsInfo();
    }
    for (InternalFieldInfo fieldInfo : fieldInfos) {
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
      Fory fory, InternalFieldInfo[] fieldInfos, Object originObj, Object newObj) {
    for (InternalFieldInfo fieldInfo : fieldInfos) {
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

  private InternalFieldInfo[] buildFieldsInfo() {
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
    Tuple3<Tuple2<FinalTypeField[], boolean[]>, GenericTypeField[], GenericTypeField[]> infos =
        buildFieldInfos(fory, descriptorGrouper);
    fieldInfos = new InternalFieldInfo[descriptors.size()];
    System.arraycopy(infos.f0.f0, 0, fieldInfos, 0, infos.f0.f0.length);
    System.arraycopy(infos.f1, 0, fieldInfos, infos.f0.f0.length, infos.f1.length);
    System.arraycopy(infos.f2, 0, fieldInfos, fieldInfos.length - infos.f2.length, infos.f2.length);
    if (isRecord) {
      List<String> fieldNames =
          Arrays.stream(fieldInfos)
              .map(f -> f.fieldAccessor.getField().getName())
              .collect(Collectors.toList());
      copyRecordInfo = new RecordInfo(type, fieldNames);
    }
    return fieldInfos;
  }

  public static InternalFieldInfo[] buildFieldsInfo(Fory fory, List<Field> fields) {
    List<Descriptor> descriptors = new ArrayList<>();
    for (Field field : fields) {
      if (!Modifier.isTransient(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
        descriptors.add(new Descriptor(field, TypeRef.of(field.getGenericType()), null, null));
      }
    }
    DescriptorGrouper descriptorGrouper =
        fory.getClassResolver().createDescriptorGrouper(descriptors, false);
    Tuple3<Tuple2<FinalTypeField[], boolean[]>, GenericTypeField[], GenericTypeField[]> infos =
        buildFieldInfos(fory, descriptorGrouper);
    InternalFieldInfo[] fieldInfos = new InternalFieldInfo[descriptors.size()];
    System.arraycopy(infos.f0.f0, 0, fieldInfos, 0, infos.f0.f0.length);
    System.arraycopy(infos.f1, 0, fieldInfos, infos.f0.f0.length, infos.f1.length);
    System.arraycopy(infos.f2, 0, fieldInfos, fieldInfos.length - infos.f2.length, infos.f2.length);
    return fieldInfos;
  }

  protected T newBean() {
    return objectCreator.newInstance();
  }

  static Tuple3<Tuple2<FinalTypeField[], boolean[]>, GenericTypeField[], GenericTypeField[]>
      buildFieldInfos(Fory fory, DescriptorGrouper grouper) {
    // When a type is both Collection/Map and final, add it to collection/map fields to keep
    // consistent with jit.
    Collection<Descriptor> primitives = grouper.getPrimitiveDescriptors();
    Collection<Descriptor> boxed = grouper.getBoxedDescriptors();
    Collection<Descriptor> finals = grouper.getFinalDescriptors();
    FinalTypeField[] finalFields =
        new FinalTypeField[primitives.size() + boxed.size() + finals.size()];
    int cnt = 0;
    for (Descriptor d : primitives) {
      finalFields[cnt++] = new FinalTypeField(fory, d);
    }
    for (Descriptor d : boxed) {
      finalFields[cnt++] = new FinalTypeField(fory, d);
    }
    // TODO(chaokunyang) Support Pojo<T> generics besides Map/Collection subclass
    //  when it's supported in BaseObjectCodecBuilder.
    for (Descriptor d : finals) {
      finalFields[cnt++] = new FinalTypeField(fory, d);
    }
    boolean[] isFinal = new boolean[finalFields.length];
    for (int i = 0; i < isFinal.length; i++) {
      ClassInfo classInfo = finalFields[i].classInfo;
      isFinal[i] = classInfo != null && fory.getClassResolver().isMonomorphic(classInfo.getCls());
    }
    cnt = 0;
    GenericTypeField[] otherFields = new GenericTypeField[grouper.getOtherDescriptors().size()];
    for (Descriptor descriptor : grouper.getOtherDescriptors()) {
      GenericTypeField genericTypeField = new GenericTypeField(fory, descriptor);
      otherFields[cnt++] = genericTypeField;
    }
    cnt = 0;
    Collection<Descriptor> collections = grouper.getCollectionDescriptors();
    Collection<Descriptor> maps = grouper.getMapDescriptors();
    GenericTypeField[] containerFields = new GenericTypeField[collections.size() + maps.size()];
    for (Descriptor d : collections) {
      containerFields[cnt++] = new GenericTypeField(fory, d);
    }
    for (Descriptor d : maps) {
      containerFields[cnt++] = new GenericTypeField(fory, d);
    }
    return Tuple3.of(Tuple2.of(finalFields, isFinal), otherFields, containerFields);
  }

  public static class InternalFieldInfo {
    protected final TypeRef<?> typeRef;
    protected final short classId;
    protected final String qualifiedFieldName;
    protected final FieldAccessor fieldAccessor;
    protected final FieldConverter<?> fieldConverter;
    protected boolean nullable;
    protected boolean trackingRef;

    private InternalFieldInfo(Fory fory, Descriptor d, short classId) {
      this.typeRef = d.getTypeRef();
      this.classId = classId;
      this.qualifiedFieldName = d.getDeclaringClass() + "." + d.getName();
      this.fieldAccessor = d.getField() != null ? FieldAccessor.createAccessor(d.getField()) : null;
      fieldConverter = d.getFieldConverter();
      nullable = d.isNullable();
      if (fory.trackingRef()) {
        trackingRef = d.isTrackingRef();
      }
    }

    @Override
    public String toString() {
      return "InternalFieldInfo{"
          + "typeRef="
          + typeRef
          + ", classId="
          + classId
          + ", qualifiedFieldName='"
          + qualifiedFieldName
          + ", fieldAccessor="
          + fieldAccessor
          + ", nullable="
          + nullable
          + '}';
    }
  }

  static final class FinalTypeField extends InternalFieldInfo {
    final ClassInfo classInfo;

    private FinalTypeField(Fory fory, Descriptor d) {
      super(fory, d, getRegisteredClassId(fory, d.getTypeRef().getRawType()));
      // invoke `copy` to avoid ObjectSerializer construct clear serializer by `clearSerializer`.
      if (typeRef.getRawType() == FinalObjectTypeStub.class) {
        // `FinalObjectTypeStub` has no fields, using its `classInfo`
        // will make deserialization failed.
        classInfo = null;
      } else {
        classInfo = SerializationUtils.getClassInfo(fory, typeRef.getRawType());
        if (!fory.isShareMeta()
            && !fory.isCompatible()
            && classInfo.getSerializer() instanceof ReplaceResolveSerializer) {
          // overwrite replace resolve serializer for final field
          classInfo.setSerializer(new FinalFieldReplaceResolveSerializer(fory, classInfo.getCls()));
        }
      }
    }
  }

  static final class GenericTypeField extends InternalFieldInfo {
    final GenericType genericType;
    final ClassInfoHolder classInfoHolder;
    final boolean isArray;
    final ClassInfo containerClassInfo;

    private GenericTypeField(Fory fory, Descriptor d) {
      super(fory, d, getRegisteredClassId(fory, getRawType(d.getTypeRef())));
      // TODO support generics <T> in Pojo<T>, see ComplexObjectSerializer.getGenericTypes
      ClassResolver classResolver = fory.getClassResolver();
      GenericType t = classResolver.buildGenericType(typeRef);
      Class<?> cls = t.getCls();
      if (t.getTypeParametersCount() > 0) {
        boolean skip =
            Arrays.stream(t.getTypeParameters()).allMatch(p -> p.getCls() == Object.class);
        if (skip) {
          t = new GenericType(t.getTypeRef(), t.isMonomorphic());
        }
      }
      genericType = t;
      classInfoHolder = classResolver.nilClassInfoHolder();
      isArray = cls.isArray();
      if (!fory.isCrossLanguage()) {
        containerClassInfo = null;
      } else {
        if (classResolver.isMap(cls)
            || classResolver.isCollection(cls)
            || classResolver.isSet(cls)) {
          containerClassInfo = fory.getXtypeResolver().getClassInfo(cls);
        } else {
          containerClassInfo = null;
        }
      }
    }

    @Override
    public String toString() {
      return "GenericTypeField{"
          + "genericType="
          + genericType
          + ", classInfoHolder="
          + classInfoHolder
          + ", trackingRef="
          + trackingRef
          + ", typeRef="
          + typeRef
          + ", classId="
          + classId
          + ", qualifiedFieldName='"
          + qualifiedFieldName
          + ", fieldAccessor="
          + fieldAccessor
          + ", nullable="
          + nullable
          + '}';
    }
  }

  private static short getRegisteredClassId(Fory fory, Class<?> cls) {
    Short classId = fory.getClassResolver().getRegisteredClassId(cls);
    return classId == null ? ClassResolver.NO_CLASS_ID : classId;
  }
}
