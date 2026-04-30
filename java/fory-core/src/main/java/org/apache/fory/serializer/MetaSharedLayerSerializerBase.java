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
 * Base class for meta-shared layer serializers. The default implementation uses reflection-backed
 * field access and generated layer serializers override only the hot field read/write methods.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class MetaSharedLayerSerializerBase<T> extends AbstractObjectSerializer<T> {
  protected TypeDef layerTypeDef;
  protected Class<?> layerMarkerClass;
  protected SerializationFieldInfo[] buildInFields = new SerializationFieldInfo[0];
  protected SerializationFieldInfo[] otherFields = new SerializationFieldInfo[0];
  protected SerializationFieldInfo[] containerFields = new SerializationFieldInfo[0];

  public MetaSharedLayerSerializerBase(TypeResolver typeResolver, Class<T> type) {
    super(typeResolver, type);
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
    buildInFields = fieldGroups.buildInFields;
    otherFields = fieldGroups.userTypeFields;
    containerFields = fieldGroups.containerFields;
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
    writeBuildInFields(writeContext, value);
    writeContainerFields(writeContext, value);
    writeOtherFields(writeContext, value);
  }

  public void writeFieldValues(WriteContext writeContext, Object[] vals) {
    checkLayerSerializerMeta();
    MemoryBuffer buffer = writeContext.getBuffer();
    RefWriter refWriter = writeContext.getRefWriter();
    int index = 0;
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      AbstractObjectSerializer.writeBuildInFieldValue(
          writeContext, typeResolver, refWriter, fieldInfo, buffer, vals[index++]);
    }
    Generics generics = writeContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      AbstractObjectSerializer.writeContainerFieldValue(
          writeContext, typeResolver, refWriter, generics, fieldInfo, buffer, vals[index++]);
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      AbstractObjectSerializer.writeField(
          writeContext, typeResolver, refWriter, fieldInfo, buffer, vals[index++]);
    }
  }

  public Object[] readFieldValues(ReadContext readContext) {
    checkLayerSerializerMeta();
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    Object[] vals = new Object[getNumFields()];
    int index = 0;
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      vals[index++] =
          AbstractObjectSerializer.readBuildInFieldValue(
              readContext, typeResolver, refReader, fieldInfo, buffer);
    }
    Generics generics = readContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      vals[index++] =
          AbstractObjectSerializer.readContainerFieldValue(
              readContext, typeResolver, refReader, generics, fieldInfo, buffer);
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      vals[index++] =
          AbstractObjectSerializer.readField(
              readContext, typeResolver, refReader, fieldInfo, buffer);
    }
    return vals;
  }

  @Override
  public T read(ReadContext readContext) {
    checkLayerSerializerMeta();
    T obj = newBean();
    readContext.reference(obj);
    return readAndSetFields(readContext, obj);
  }

  public T readAndSetFields(ReadContext readContext, T obj) {
    checkLayerSerializerMeta();
    readBuildInFields(readContext, obj);
    readContainerFields(readContext, obj);
    readOtherFields(readContext, obj);
    return obj;
  }

  public int getNumFields() {
    return buildInFields.length + containerFields.length + otherFields.length;
  }

  @SuppressWarnings("rawtypes")
  public void populateFieldInfo(ObjectIntMap fieldIndexMap, Class<?>[] fieldTypes) {
    checkLayerSerializerMeta();
    int index = 0;
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      populateSingleFieldInfo(fieldInfo, fieldIndexMap, fieldTypes, index++);
    }
    for (SerializationFieldInfo fieldInfo : containerFields) {
      populateSingleFieldInfo(fieldInfo, fieldIndexMap, fieldTypes, index++);
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      populateSingleFieldInfo(fieldInfo, fieldIndexMap, fieldTypes, index++);
    }
  }

  @SuppressWarnings("rawtypes")
  public void setFieldValuesFromPutFields(Object obj, ObjectIntMap fieldIndexMap, Object[] vals) {
    checkLayerSerializerMeta();
    applyPutFieldValues(obj, fieldIndexMap, vals, buildInFields);
    applyPutFieldValues(obj, fieldIndexMap, vals, containerFields);
    applyPutFieldValues(obj, fieldIndexMap, vals, otherFields);
  }

  @SuppressWarnings("rawtypes")
  public Object[] getFieldValuesForPutFields(
      Object obj, ObjectIntMap fieldIndexMap, int arraySize) {
    checkLayerSerializerMeta();
    Object[] vals = new Object[arraySize];
    collectPutFieldValues(obj, fieldIndexMap, vals, buildInFields);
    collectPutFieldValues(obj, fieldIndexMap, vals, containerFields);
    collectPutFieldValues(obj, fieldIndexMap, vals, otherFields);
    return vals;
  }

  public void skipFields(ReadContext readContext) {
    checkLayerSerializerMeta();
    skipBuildInFields(readContext);
    skipContainerFields(readContext);
    skipOtherFields(readContext);
  }

  private void writeBuildInFields(WriteContext writeContext, T value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    RefWriter refWriter = writeContext.getRefWriter();
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      AbstractObjectSerializer.writeBuildInField(
          writeContext, typeResolver, refWriter, fieldInfo, buffer, value);
    }
  }

  private void writeContainerFields(WriteContext writeContext, T value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    RefWriter refWriter = writeContext.getRefWriter();
    Generics generics = writeContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      AbstractObjectSerializer.writeContainerFieldValue(
          writeContext, typeResolver, refWriter, generics, fieldInfo, buffer, fieldValue);
    }
  }

  private void writeOtherFields(WriteContext writeContext, T value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    RefWriter refWriter = writeContext.getRefWriter();
    for (SerializationFieldInfo fieldInfo : otherFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      AbstractObjectSerializer.writeField(
          writeContext, typeResolver, refWriter, fieldInfo, buffer, fieldValue);
    }
  }

  private void readBuildInFields(ReadContext readContext, T targetObject) {
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        AbstractObjectSerializer.readBuildInFieldValue(
            readContext, typeResolver, refReader, fieldInfo, buffer, targetObject);
      } else {
        FieldSkipper.skipField(readContext, typeResolver, refReader, fieldInfo, buffer);
      }
    }
  }

  private void readContainerFields(ReadContext readContext, T obj) {
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    Generics generics = readContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      Object fieldValue =
          AbstractObjectSerializer.readContainerFieldValue(
              readContext, typeResolver, refReader, generics, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
  }

  private void readOtherFields(ReadContext readContext, T obj) {
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    for (SerializationFieldInfo fieldInfo : otherFields) {
      Object fieldValue =
          AbstractObjectSerializer.readField(
              readContext, typeResolver, refReader, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
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

  private void skipBuildInFields(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      FieldSkipper.skipField(readContext, typeResolver, refReader, fieldInfo, buffer);
    }
  }

  private void skipContainerFields(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    Generics generics = readContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      AbstractObjectSerializer.readContainerFieldValue(
          readContext, typeResolver, refReader, generics, fieldInfo, buffer);
    }
  }

  private void skipOtherFields(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    for (SerializationFieldInfo fieldInfo : otherFields) {
      AbstractObjectSerializer.readField(readContext, typeResolver, refReader, fieldInfo, buffer);
    }
  }
}
