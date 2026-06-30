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

import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.ObjectIntMap;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.RefReader;
import org.apache.fory.context.RefWriter;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;
import org.apache.fory.util.Preconditions;

/**
 * Base class for compatible layer serializers. The default implementation uses reflection-backed
 * field access and generated layer serializers override only the hot field read/write methods.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class CompatibleLayerSerializerBase<T> extends AbstractObjectSerializer<T> {
  protected TypeDef layerTypeDef;
  protected Class<?> layerMarkerClass;
  protected SerializationFieldInfo[] allFields = new SerializationFieldInfo[0];

  public CompatibleLayerSerializerBase(TypeResolver typeResolver, Class<T> type) {
    // Layer serializers are field-only views over an already-created subclass instance. Null keeps
    // object construction with the concrete serializer owner.
    super(typeResolver, type, null);
  }

  public final void setLayerSerializerMeta(TypeDef layerTypeDef, Class<?> layerMarkerClass) {
    Preconditions.checkNotNull(layerTypeDef, "Layer TypeDef must not be null");
    Preconditions.checkNotNull(layerMarkerClass, "Layer marker class must not be null");
    if (this.layerTypeDef != null || this.layerMarkerClass != null) {
      Preconditions.checkState(
          this.layerTypeDef == layerTypeDef && this.layerMarkerClass == layerMarkerClass,
          "Layer serializer metadata already initialized");
      return;
    }
    this.layerTypeDef = layerTypeDef;
    this.layerMarkerClass = layerMarkerClass;
    DescriptorGrouper descriptorGrouper = typeResolver.createDescriptorGrouper(layerTypeDef, type);
    FieldGroups fieldGroups = FieldGroups.buildFieldInfos(typeResolver, descriptorGrouper);
    allFields = fieldGroups.allFields;
  }

  protected final void checkLayerSerializerMeta() {
    Preconditions.checkState(layerTypeDef != null, "Layer serializer metadata isn't initialized");
  }

  @Override
  public void write(WriteContext writeContext, T value) {
    if (config.isMetaShareEnabled()) {
      writeLayerClassMeta(writeContext);
    }
    writeFieldsOnly(writeContext, value);
  }

  public void writeLayerClassMeta(WriteContext writeContext) {
    checkLayerSerializerMeta();
    MemoryBuffer buffer = writeContext.getBuffer();
    MetaWriteContext metaWriteContext = writeContext.getMetaWriteContext();
    if (metaWriteContext == null) {
      return;
    }
    IdentityObjectIntMap<Class<?>> classMap = metaWriteContext.classMap;
    int newId = classMap.size;
    int id = classMap.putOrGet(layerMarkerClass, newId);
    if (id >= 0) {
      buffer.writeVarUInt32((id << 1) | 1);
    } else {
      buffer.writeVarUInt32(newId << 1);
      buffer.writeBytes(layerTypeDef.getEncoded());
    }
  }

  public void writeFieldsOnly(WriteContext writeContext, T value) {
    checkLayerSerializerMeta();
    MemoryBuffer buffer = writeContext.getBuffer();
    RefWriter refWriter = writeContext.getRefWriter();
    Generics generics = writeContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : allFields) {
      writeField(writeContext, value, buffer, refWriter, generics, fieldInfo);
    }
  }

  public void writeFieldValues(WriteContext writeContext, Object[] vals) {
    checkLayerSerializerMeta();
    MemoryBuffer buffer = writeContext.getBuffer();
    RefWriter refWriter = writeContext.getRefWriter();
    int index = 0;
    Generics generics = writeContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : allFields) {
      writeFieldValue(writeContext, buffer, refWriter, generics, fieldInfo, vals[index++]);
    }
  }

  public Object[] readFieldValues(ReadContext readContext) {
    checkLayerSerializerMeta();
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    Object[] vals = new Object[getNumFields()];
    int index = 0;
    Generics generics = readContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : allFields) {
      vals[index++] = readFieldValue(readContext, buffer, refReader, generics, fieldInfo);
    }
    return vals;
  }

  @Override
  public T read(ReadContext readContext) {
    checkLayerSerializerMeta();
    reserveObjectGraphMemory(readContext);
    T obj = newBean();
    readContext.reference(obj);
    return readAndSetFields(readContext, obj);
  }

  public T readAndSetFields(ReadContext readContext, T obj) {
    checkLayerSerializerMeta();
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    Generics generics = readContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : allFields) {
      readAndSetField(readContext, obj, buffer, refReader, generics, fieldInfo);
    }
    return obj;
  }

  public int getNumFields() {
    return allFields.length;
  }

  @SuppressWarnings("rawtypes")
  public void populateFieldInfo(ObjectIntMap fieldIndexMap, Class<?>[] fieldTypes) {
    checkLayerSerializerMeta();
    int index = 0;
    for (SerializationFieldInfo fieldInfo : allFields) {
      populateSingleFieldInfo(fieldInfo, fieldIndexMap, fieldTypes, index++);
    }
  }

  @SuppressWarnings("rawtypes")
  public void setFieldValuesFromPutFields(Object obj, ObjectIntMap fieldIndexMap, Object[] vals) {
    checkLayerSerializerMeta();
    applyPutFieldValues(obj, fieldIndexMap, vals, allFields);
  }

  @SuppressWarnings("rawtypes")
  public Object[] getFieldValuesForPutFields(
      Object obj, ObjectIntMap fieldIndexMap, int arraySize) {
    checkLayerSerializerMeta();
    Object[] vals = new Object[arraySize];
    collectPutFieldValues(obj, fieldIndexMap, vals, allFields);
    return vals;
  }

  @SuppressWarnings("rawtypes")
  public Object[] copyFieldValuesForPutFields(
      CopyContext copyContext, Object obj, ObjectIntMap fieldIndexMap, int arraySize) {
    checkLayerSerializerMeta();
    Object[] vals = new Object[arraySize];
    collectCopiedPutFieldValues(copyContext, obj, fieldIndexMap, vals, allFields);
    return vals;
  }

  public void skipFields(ReadContext readContext) {
    checkLayerSerializerMeta();
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    Generics generics = readContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : allFields) {
      skipField(readContext, buffer, refReader, generics, fieldInfo);
    }
  }

  private void writeField(
      WriteContext writeContext,
      T value,
      MemoryBuffer buffer,
      RefWriter refWriter,
      Generics generics,
      SerializationFieldInfo fieldInfo) {
    if (fieldInfo.codecCategory == FieldGroups.FieldCodecCategory.BUILD_IN) {
      AbstractObjectSerializer.writeBuildInField(
          writeContext, typeResolver, refWriter, fieldInfo, buffer, value);
      return;
    }
    FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
    Object fieldValue = fieldAccessor.getObject(value);
    writeFieldValue(writeContext, buffer, refWriter, generics, fieldInfo, fieldValue);
  }

  private void writeFieldValue(
      WriteContext writeContext,
      MemoryBuffer buffer,
      RefWriter refWriter,
      Generics generics,
      SerializationFieldInfo fieldInfo,
      Object fieldValue) {
    switch (fieldInfo.codecCategory) {
      case BUILD_IN:
        AbstractObjectSerializer.writeBuildInFieldValue(
            writeContext, typeResolver, refWriter, fieldInfo, buffer, fieldValue);
        return;
      case CONTAINER:
        AbstractObjectSerializer.writeContainerFieldValue(
            writeContext, typeResolver, refWriter, generics, fieldInfo, buffer, fieldValue);
        return;
      case OTHER:
        AbstractObjectSerializer.writeField(
            writeContext, typeResolver, refWriter, fieldInfo, buffer, fieldValue);
        return;
      default:
        throw new IllegalStateException("Unknown field codec category " + fieldInfo.codecCategory);
    }
  }

  private Object readFieldValue(
      ReadContext readContext,
      MemoryBuffer buffer,
      RefReader refReader,
      Generics generics,
      SerializationFieldInfo fieldInfo) {
    switch (fieldInfo.codecCategory) {
      case BUILD_IN:
        return AbstractObjectSerializer.readBuildInFieldValue(
            readContext, typeResolver, refReader, fieldInfo, buffer);
      case CONTAINER:
        return AbstractObjectSerializer.readContainerFieldValue(
            readContext, typeResolver, refReader, generics, fieldInfo, buffer);
      case OTHER:
        return AbstractObjectSerializer.readField(
            readContext, typeResolver, refReader, fieldInfo, buffer);
      default:
        throw new IllegalStateException("Unknown field codec category " + fieldInfo.codecCategory);
    }
  }

  private void readAndSetField(
      ReadContext readContext,
      T obj,
      MemoryBuffer buffer,
      RefReader refReader,
      Generics generics,
      SerializationFieldInfo fieldInfo) {
    FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
    if (fieldInfo.codecCategory == FieldGroups.FieldCodecCategory.BUILD_IN) {
      if (fieldAccessor != null) {
        AbstractObjectSerializer.readBuildInFieldValue(
            readContext, typeResolver, refReader, fieldInfo, buffer, obj);
      } else {
        FieldSkipper.skipField(readContext, typeResolver, refReader, fieldInfo, buffer);
      }
      return;
    }
    Object fieldValue = readFieldValue(readContext, buffer, refReader, generics, fieldInfo);
    if (fieldAccessor != null) {
      fieldAccessor.putObject(obj, fieldValue);
    }
  }

  private void skipField(
      ReadContext readContext,
      MemoryBuffer buffer,
      RefReader refReader,
      Generics generics,
      SerializationFieldInfo fieldInfo) {
    switch (fieldInfo.codecCategory) {
      case BUILD_IN:
        FieldSkipper.skipField(readContext, typeResolver, refReader, fieldInfo, buffer);
        return;
      case CONTAINER:
        AbstractObjectSerializer.readContainerFieldValue(
            readContext, typeResolver, refReader, generics, fieldInfo, buffer);
        return;
      case OTHER:
        AbstractObjectSerializer.readField(readContext, typeResolver, refReader, fieldInfo, buffer);
        return;
      default:
        throw new IllegalStateException("Unknown field codec category " + fieldInfo.codecCategory);
    }
  }

  private void populateSingleFieldInfo(
      SerializationFieldInfo fieldInfo,
      ObjectIntMap fieldIndexMap,
      Class<?>[] fieldTypes,
      int index) {
    FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
    if (fieldAccessor != null) {
      fieldIndexMap.put(fieldAccessor.getField().getName(), index);
      fieldTypes[index] = fieldAccessor.getField().getType();
    } else {
      fieldIndexMap.put(fieldInfo.descriptor.getName(), index);
      fieldTypes[index] = fieldInfo.descriptor.getRawType();
    }
  }

  @SuppressWarnings("rawtypes")
  private void applyPutFieldValues(
      Object obj, ObjectIntMap fieldIndexMap, Object[] vals, SerializationFieldInfo[] fieldInfos) {
    for (SerializationFieldInfo fieldInfo : fieldInfos) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        String fieldName = fieldAccessor.getField().getName();
        int index = fieldIndexMap.get(fieldName, -1);
        if (index != -1 && index < vals.length) {
          fieldAccessor.set(obj, vals[index]);
        }
      }
    }
  }

  @SuppressWarnings("rawtypes")
  private void collectPutFieldValues(
      Object obj, ObjectIntMap fieldIndexMap, Object[] vals, SerializationFieldInfo[] fieldInfos) {
    for (SerializationFieldInfo fieldInfo : fieldInfos) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        String fieldName = fieldAccessor.getField().getName();
        int index = fieldIndexMap.get(fieldName, -1);
        if (index != -1 && index < vals.length) {
          vals[index] = fieldAccessor.get(obj);
        }
      }
    }
  }

  @SuppressWarnings("rawtypes")
  private void collectCopiedPutFieldValues(
      CopyContext copyContext,
      Object obj,
      ObjectIntMap fieldIndexMap,
      Object[] vals,
      SerializationFieldInfo[] fieldInfos) {
    for (SerializationFieldInfo fieldInfo : fieldInfos) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        String fieldName = fieldAccessor.getField().getName();
        int index = fieldIndexMap.get(fieldName, -1);
        if (index != -1 && index < vals.length) {
          Object fieldValue = fieldAccessor.get(obj);
          vals[index] =
              fieldInfo.isPrimitiveField || fieldValue == null
                  ? fieldValue
                  : copyContext.copyObject(fieldValue);
        }
      }
    }
  }
}
