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

import org.apache.fory.Fory;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.ObjectIntMap;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;
import org.apache.fory.util.Preconditions;

/**
 * Base class for meta-shared layer serializers. The default implementation uses the reflection
 * field accessors built from the layer {@link TypeDef}. Generated layer serializers override only
 * the hot field read/write methods and reuse the remaining reflection-backed helpers here.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class MetaSharedLayerSerializerBase<T> extends AbstractObjectSerializer<T> {
  protected TypeDef layerTypeDef;
  protected Class<?> layerMarkerClass;
  protected SerializationFieldInfo[] buildInFields = new SerializationFieldInfo[0];
  protected SerializationFieldInfo[] otherFields = new SerializationFieldInfo[0];
  protected SerializationFieldInfo[] containerFields = new SerializationFieldInfo[0];

  public MetaSharedLayerSerializerBase(Fory fory, Class<T> type) {
    super(fory, type);
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
    TypeResolver typeResolver = fory.getTypeResolver();
    DescriptorGrouper descriptorGrouper = typeResolver.createDescriptorGrouper(layerTypeDef, type);
    FieldGroups fieldGroups = FieldGroups.buildFieldInfos(fory, descriptorGrouper);
    this.buildInFields = fieldGroups.buildInFields;
    this.otherFields = fieldGroups.userTypeFields;
    this.containerFields = fieldGroups.containerFields;
  }

  protected final void checkLayerSerializerMeta() {
    Preconditions.checkState(layerTypeDef != null, "Layer serializer metadata isn't initialized");
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    if (fory.getConfig().isMetaShareEnabled()) {
      writeLayerClassMeta(buffer);
    }
    writeFieldsOnly(buffer, value);
  }

  public void writeLayerClassMeta(MemoryBuffer buffer) {
    checkLayerSerializerMeta();
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    if (metaContext == null) {
      return;
    }
    IdentityObjectIntMap<Class<?>> classMap = metaContext.classMap;
    int newId = classMap.size;
    int id = classMap.putOrGet(layerMarkerClass, newId);
    if (id >= 0) {
      buffer.writeVarUint32((id << 1) | 1);
    } else {
      buffer.writeVarUint32(newId << 1);
      buffer.writeBytes(layerTypeDef.getEncoded());
    }
  }

  public void writeFieldsOnly(MemoryBuffer buffer, T value) {
    checkLayerSerializerMeta();
    writeBuildInFields(buffer, value);
    writeContainerFields(buffer, value);
    writeOtherFields(buffer, value);
  }

  public void writeFieldValues(MemoryBuffer buffer, Object[] vals) {
    checkLayerSerializerMeta();
    int index = 0;
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      AbstractObjectSerializer.writeBuildInFieldValue(
          fory, typeResolver, refResolver, fieldInfo, buffer, vals[index++]);
    }
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      AbstractObjectSerializer.writeContainerFieldValue(
          fory, typeResolver, refResolver, generics, fieldInfo, buffer, vals[index++]);
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      AbstractObjectSerializer.writeField(
          fory, typeResolver, refResolver, fieldInfo, buffer, vals[index++]);
    }
  }

  public Object[] readFieldValues(MemoryBuffer buffer) {
    checkLayerSerializerMeta();
    Object[] vals = new Object[getNumFields()];
    int index = 0;
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      vals[index++] =
          AbstractObjectSerializer.readBuildInFieldValue(
              fory, typeResolver, refResolver, fieldInfo, buffer);
    }
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      vals[index++] =
          AbstractObjectSerializer.readContainerFieldValue(
              fory, typeResolver, refResolver, generics, fieldInfo, buffer);
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      vals[index++] =
          AbstractObjectSerializer.readField(fory, typeResolver, refResolver, fieldInfo, buffer);
    }
    return vals;
  }

  @Override
  public T read(MemoryBuffer buffer) {
    checkLayerSerializerMeta();
    T obj = newBean();
    refResolver.reference(obj);
    return readAndSetFields(buffer, obj);
  }

  public T readAndSetFields(MemoryBuffer buffer, T obj) {
    checkLayerSerializerMeta();
    readBuildInFields(buffer, obj);
    readContainerFields(buffer, obj);
    readOtherFields(buffer, obj);
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

  public void skipFields(MemoryBuffer buffer) {
    checkLayerSerializerMeta();
    skipBuildInFields(buffer);
    skipContainerFields(buffer);
    skipOtherFields(buffer);
  }

  private void writeBuildInFields(MemoryBuffer buffer, T value) {
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      AbstractObjectSerializer.writeBuildInField(
          fory, typeResolver, refResolver, fieldInfo, buffer, value);
    }
  }

  private void writeContainerFields(MemoryBuffer buffer, T value) {
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      AbstractObjectSerializer.writeContainerFieldValue(
          fory, typeResolver, refResolver, generics, fieldInfo, buffer, fieldValue);
    }
  }

  private void writeOtherFields(MemoryBuffer buffer, T value) {
    for (SerializationFieldInfo fieldInfo : otherFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      AbstractObjectSerializer.writeField(
          fory, typeResolver, refResolver, fieldInfo, buffer, fieldValue);
    }
  }

  private void readBuildInFields(MemoryBuffer buffer, T targetObject) {
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        AbstractObjectSerializer.readBuildInFieldValue(
            fory, typeResolver, refResolver, fieldInfo, buffer, targetObject);
      } else {
        FieldSkipper.skipField(fory, typeResolver, refResolver, fieldInfo, buffer);
      }
    }
  }

  private void readContainerFields(MemoryBuffer buffer, T obj) {
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      Object fieldValue =
          AbstractObjectSerializer.readContainerFieldValue(
              fory, typeResolver, refResolver, generics, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
  }

  private void readOtherFields(MemoryBuffer buffer, T obj) {
    for (SerializationFieldInfo fieldInfo : otherFields) {
      Object fieldValue =
          AbstractObjectSerializer.readField(fory, typeResolver, refResolver, fieldInfo, buffer);
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

  private void skipBuildInFields(MemoryBuffer buffer) {
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      FieldSkipper.skipField(fory, typeResolver, refResolver, fieldInfo, buffer);
    }
  }

  private void skipContainerFields(MemoryBuffer buffer) {
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      AbstractObjectSerializer.readContainerFieldValue(
          fory, typeResolver, refResolver, generics, fieldInfo, buffer);
    }
  }

  private void skipOtherFields(MemoryBuffer buffer) {
    for (SerializationFieldInfo fieldInfo : otherFields) {
      AbstractObjectSerializer.readField(fory, typeResolver, refResolver, fieldInfo, buffer);
    }
  }
}
