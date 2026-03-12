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

import java.util.Collection;
import org.apache.fory.Fory;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.ObjectIntMap;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;

/**
 * A meta-shared serializer for a single layer in a class hierarchy. This serializer is used by
 * {@link ObjectStreamSerializer} to serialize fields of a specific class layer without including
 * parent class fields.
 *
 * <p>Unlike {@link MetaSharedSerializer} which handles the full class hierarchy, this serializer
 * only handles fields declared in a specific class layer. It uses a generated marker class as the
 * key in {@code metaContext.classMap} to ensure unique identification of each layer.
 *
 * @see MetaSharedSerializer
 * @see ObjectStreamSerializer
 * @see org.apache.fory.builder.LayerMarkerClassGenerator
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class MetaSharedLayerSerializer<T> extends MetaSharedLayerSerializerBase<T> {
  private final TypeDef layerTypeDef;
  private final Class<?> layerMarkerClass;
  private final SerializationFieldInfo[] buildInFields;
  private final SerializationFieldInfo[] otherFields;
  private final SerializationFieldInfo[] containerFields;

  /**
   * Creates a new MetaSharedLayerSerializer.
   *
   * @param fory the Fory instance
   * @param type the target class for this layer
   * @param layerTypeDef the TypeDef for this layer only (resolveParent=false)
   * @param layerMarkerClass the generated marker class used as key in metaContext.classMap
   */
  public MetaSharedLayerSerializer(
      Fory fory, Class<T> type, TypeDef layerTypeDef, Class<?> layerMarkerClass) {
    super(fory, type);
    this.layerTypeDef = layerTypeDef;
    this.layerMarkerClass = layerMarkerClass;
    TypeResolver typeResolver = fory.getTypeResolver();

    // Build field infos from layerTypeDef
    Collection<Descriptor> descriptors = layerTypeDef.getDescriptors(typeResolver, type);
    DescriptorGrouper descriptorGrouper = typeResolver.createDescriptorGrouper(descriptors, false);
    FieldGroups fieldGroups = FieldGroups.buildFieldInfos(fory, descriptorGrouper);
    this.buildInFields = fieldGroups.buildInFields;
    this.otherFields = fieldGroups.userTypeFields;
    this.containerFields = fieldGroups.containerFields;
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    // Write layer class meta using marker class as key (only if meta share is enabled)
    if (fory.getConfig().isMetaShareEnabled()) {
      writeLayerClassMeta(buffer);
    }
    // Write fields in order: final, container, other
    writeFieldsOnly(buffer, value);
  }

  @Override
  public void writeLayerClassMeta(MemoryBuffer buffer) {
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    if (metaContext == null) {
      return;
    }
    IdentityObjectIntMap<Class<?>> classMap = metaContext.classMap;
    int newId = classMap.size;
    int id = classMap.putOrGet(layerMarkerClass, newId);
    if (id >= 0) {
      // Reference to previously written type: (index << 1) | 1, LSB=1
      buffer.writeVarUint32((id << 1) | 1);
    } else {
      // New type: index << 1, LSB=0, followed by TypeDef bytes inline
      buffer.writeVarUint32(newId << 1);
      buffer.writeBytes(layerTypeDef.getEncoded());
    }
  }

  @Override
  public void writeFieldsOnly(MemoryBuffer buffer, T value) {
    // Write fields in order: buildIn, container, other
    writeBuildInFields(buffer, value);
    writeContainerFields(buffer, value);
    writeOtherFields(buffer, value);
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

  @Override
  public void writeFieldValues(MemoryBuffer buffer, Object[] vals) {
    // Write fields from array in order: buildIn, container, other
    int index = 0;
    // Write buildIn fields
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      AbstractObjectSerializer.writeBuildInFieldValue(
          fory, typeResolver, refResolver, fieldInfo, buffer, vals[index++]);
    }
    // Write container fields
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      AbstractObjectSerializer.writeContainerFieldValue(
          fory, typeResolver, refResolver, generics, fieldInfo, buffer, vals[index++]);
    }
    // Write other fields
    for (SerializationFieldInfo fieldInfo : otherFields) {
      AbstractObjectSerializer.writeField(
          fory, typeResolver, refResolver, fieldInfo, buffer, vals[index++]);
    }
  }

  @Override
  public Object[] readFieldValues(MemoryBuffer buffer) {
    Object[] vals = new Object[getNumFields()];
    int index = 0;
    // Read buildIn fields
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      vals[index++] =
          AbstractObjectSerializer.readBuildInFieldValue(
              fory, typeResolver, refResolver, fieldInfo, buffer);
    }
    // Read container fields
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      vals[index++] =
          AbstractObjectSerializer.readContainerFieldValue(
              fory, typeResolver, refResolver, generics, fieldInfo, buffer);
    }
    // Read other fields
    for (SerializationFieldInfo fieldInfo : otherFields) {
      vals[index++] =
          AbstractObjectSerializer.readField(fory, typeResolver, refResolver, fieldInfo, buffer);
    }
    return vals;
  }

  @Override
  public void populateFieldInfo(ObjectIntMap fieldIndexMap, Class<?>[] fieldTypes) {
    int index = 0;
    // BuildIn fields first
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      populateSingleFieldInfo(fieldInfo, fieldIndexMap, fieldTypes, index++);
    }
    // Container fields next
    for (SerializationFieldInfo fieldInfo : containerFields) {
      populateSingleFieldInfo(fieldInfo, fieldIndexMap, fieldTypes, index++);
    }
    // Other fields last
    for (SerializationFieldInfo fieldInfo : otherFields) {
      populateSingleFieldInfo(fieldInfo, fieldIndexMap, fieldTypes, index++);
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
      // Field doesn't exist in actual class (e.g., from serialPersistentFields).
      // Use descriptor info instead.
      fieldIndexMap.put(fieldInfo.descriptor.getName(), index);
      fieldTypes[index] = fieldInfo.descriptor.getRawType();
    }
  }

  @Override
  public T read(MemoryBuffer buffer) {
    // Note: Layer class meta is read by ObjectStreamSerializer before calling this method.
    // This serializer is designed for use with ObjectStreamSerializer only.
    T obj = newBean();
    refResolver.reference(obj);
    return readFieldsOnly(buffer, obj);
  }

  /**
   * Read fields and set values on the provided object. Note: When meta share is enabled, the caller
   * (typically ObjectStreamSerializer) is responsible for reading the layer class meta first. This
   * method only reads field data.
   *
   * @param buffer the memory buffer to read from
   * @param obj the object to set field values on
   * @return the object with fields set
   */
  @Override
  public T readAndSetFields(MemoryBuffer buffer, T obj) {
    // Note: Layer class meta is read by ObjectStreamSerializer before calling this method
    // (when meta share is enabled). This method only reads field values.
    return readFieldsOnly(buffer, obj);
  }

  /**
   * Read fields only, without reading layer class meta. This is used when the caller has already
   * read and processed the layer class meta (e.g., for schema evolution where different senders may
   * have different TypeDefs for the same layer).
   *
   * @param buffer the memory buffer to read from
   * @param obj the object to set field values on
   * @return the object with fields set
   */
  @SuppressWarnings("unchecked")
  public T readFieldsOnly(MemoryBuffer buffer, Object obj) {
    // Read fields in order: final, container, other
    readFinalFields(buffer, (T) obj);
    readContainerFields(buffer, (T) obj);
    readUserTypeFields(buffer, (T) obj);
    return (T) obj;
  }

  private void readFinalFields(MemoryBuffer buffer, T targetObject) {
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        AbstractObjectSerializer.readBuildInFieldValue(
            fory, typeResolver, refResolver, fieldInfo, buffer, targetObject);
      } else {
        // Field doesn't exist in current class - skip the value
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

  private void readUserTypeFields(MemoryBuffer buffer, T obj) {
    for (SerializationFieldInfo fieldInfo : otherFields) {
      Object fieldValue =
          AbstractObjectSerializer.readField(fory, typeResolver, refResolver, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
  }

  /** Returns the number of fields in this layer. */
  public int getNumFields() {
    return buildInFields.length + containerFields.length + otherFields.length;
  }

  /**
   * Set field values on target object from putFields data. This method maps field names from
   * ObjectStreamClass to actual class fields and sets matching values.
   *
   * @param obj the target object
   * @param fieldIndexMap mapping from field name to index in vals array
   * @param vals the values array from putFields
   */
  @Override
  @SuppressWarnings("rawtypes")
  public void setFieldValuesFromPutFields(Object obj, ObjectIntMap fieldIndexMap, Object[] vals) {
    // Set final fields
    setFieldValuesFromPutFields(obj, fieldIndexMap, vals, buildInFields);
    setFieldValuesFromPutFields(obj, fieldIndexMap, vals, containerFields);
    setFieldValuesFromPutFields(obj, fieldIndexMap, vals, otherFields);
  }

  private void setFieldValuesFromPutFields(
      Object obj, ObjectIntMap fieldIndexMap, Object[] vals, SerializationFieldInfo[] fieldInfos) {
    // Set other fields
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

  /**
   * Get field values from object for putFields format. This method reads actual class field values
   * and puts them into an array based on ObjectStreamClass field order.
   *
   * @param obj the source object
   * @param fieldIndexMap mapping from field name to index in result array
   * @param arraySize size of the result array
   * @return array of field values in putFields order
   */
  @Override
  public Object[] getFieldValuesForPutFields(
      Object obj, ObjectIntMap fieldIndexMap, int arraySize) {
    Object[] vals = new Object[arraySize];
    // Get final fields
    getFieldValuesForPutFields(obj, fieldIndexMap, vals, buildInFields);
    // Get container fields
    getFieldValuesForPutFields(obj, fieldIndexMap, vals, containerFields);
    // Get other fields
    getFieldValuesForPutFields(obj, fieldIndexMap, vals, otherFields);
    return vals;
  }

  private void getFieldValuesForPutFields(
      Object obj,
      ObjectIntMap fieldIndexMap,
      Object[] vals,
      SerializationFieldInfo[] buildInFields) {
    for (SerializationFieldInfo fieldInfo : buildInFields) {
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

  /**
   * Skip field data in the buffer without setting it to any object. This is used for schema
   * evolution when a class layer exists in the sender but not in the receiver. The layer meta
   * should already be consumed before calling this method.
   *
   * @param buffer the memory buffer to read from
   */
  public void skipFields(MemoryBuffer buffer) {
    // Skip all fields in order: buildIn, container, other
    // We read the values but don't set them anywhere (they're discarded)
    skipBuildInFields(buffer);
    skipContainerFields(buffer);
    skipOtherFields(buffer);
  }

  private void skipBuildInFields(MemoryBuffer buffer) {
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      // Read the field value (discarding the result) to advance buffer position
      FieldSkipper.skipField(fory, typeResolver, refResolver, fieldInfo, buffer);
    }
  }

  private void skipContainerFields(MemoryBuffer buffer) {
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      // Read container field value to advance buffer position
      AbstractObjectSerializer.readContainerFieldValue(
          fory, typeResolver, refResolver, generics, fieldInfo, buffer);
    }
  }

  private void skipOtherFields(MemoryBuffer buffer) {
    for (SerializationFieldInfo fieldInfo : otherFields) {
      // Read field value to advance buffer position
      AbstractObjectSerializer.readField(fory, typeResolver, refResolver, fieldInfo, buffer);
    }
  }
}
