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
import org.apache.fory.collection.ObjectArray;
import org.apache.fory.collection.ObjectIntMap;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.ClassDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassInfoHolder;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.resolver.RefResolver;
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
@SuppressWarnings({"unchecked"})
public class MetaSharedLayerSerializer<T> extends MetaSharedLayerSerializerBase<T> {
  private final ClassDef layerClassDef;
  private final Class<?> layerMarkerClass;
  private final SerializationFieldInfo[] buildInFields;
  private final SerializationFieldInfo[] otherFields;
  private final SerializationFieldInfo[] containerFields;
  private final ClassInfoHolder classInfoHolder;
  private final SerializationBinding binding;
  private final TypeResolver typeResolver;

  /**
   * Creates a new MetaSharedLayerSerializer.
   *
   * @param fory the Fory instance
   * @param type the target class for this layer
   * @param layerClassDef the ClassDef for this layer only (resolveParent=false)
   * @param layerMarkerClass the generated marker class used as key in metaContext.classMap
   */
  public MetaSharedLayerSerializer(
      Fory fory, Class<T> type, ClassDef layerClassDef, Class<?> layerMarkerClass) {
    super(fory, type);
    this.layerClassDef = layerClassDef;
    this.layerMarkerClass = layerMarkerClass;
    this.typeResolver = fory._getTypeResolver();
    this.binding = SerializationBinding.createBinding(fory);
    this.classInfoHolder = classResolver.nilClassInfoHolder();

    // Build field infos from layerClassDef
    Collection<Descriptor> descriptors = layerClassDef.getDescriptors(typeResolver, type);
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
    writeFinalFields(buffer, value);
    writeContainerFields(buffer, value);
    writeOtherFields(buffer, value);
  }

  private void writeLayerClassMeta(MemoryBuffer buffer) {
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    if (metaContext == null) {
      return;
    }
    IdentityObjectIntMap<Class<?>> classMap = metaContext.classMap;
    int newId = classMap.size;
    int id = classMap.putOrGet(layerMarkerClass, newId);
    if (id >= 0) {
      // Already sent this layer definition
      buffer.writeVarUint32(id << 1 | 0b1);
    } else {
      // First time, queue the layer ClassDef to be written at stream end
      buffer.writeVarUint32(newId << 1 | 0b1);
      metaContext.writingClassDefs.add(layerClassDef);
    }
  }

  private void writeFinalFields(MemoryBuffer buffer, T value) {
    Fory fory = this.fory;
    RefResolver refResolver = this.refResolver;
    boolean metaShareEnabled = fory.getConfig().isMetaShareEnabled();
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      boolean nullable = fieldInfo.nullable;
      short classId = fieldInfo.classId;
      if (AbstractObjectSerializer.writePrimitiveFieldValue(
          fory, buffer, value, fieldAccessor, classId)) {
        Object fieldValue = fieldAccessor.getObject(value);
        boolean writeBasicObjectResult =
            nullable
                ? AbstractObjectSerializer.writeBasicNullableObjectFieldValue(
                    fory, buffer, fieldValue, classId)
                : AbstractObjectSerializer.writeBasicObjectFieldValue(
                    fory, buffer, fieldValue, classId);
        if (writeBasicObjectResult) {
          Serializer<Object> serializer = fieldInfo.classInfo.getSerializer();
          if (!metaShareEnabled || fieldInfo.useDeclaredTypeInfo) {
            if (!fieldInfo.trackingRef) {
              binding.writeNullable(buffer, fieldValue, serializer, nullable);
            } else {
              binding.writeRef(buffer, fieldValue, serializer);
            }
          } else {
            if (fieldInfo.trackingRef && serializer.needToWriteRef()) {
              if (!refResolver.writeRefOrNull(buffer, fieldValue)) {
                typeResolver.writeClassInfo(buffer, fieldInfo.classInfo);
                binding.write(buffer, serializer, fieldValue);
              }
            } else {
              binding.writeNullable(buffer, fieldValue, serializer, nullable);
            }
          }
        }
      }
    }
  }

  private void writeContainerFields(MemoryBuffer buffer, T value) {
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      AbstractObjectSerializer.writeContainerFieldValue(
          binding, refResolver, typeResolver, generics, fieldInfo, buffer, fieldValue);
    }
  }

  private void writeOtherFields(MemoryBuffer buffer, T value) {
    for (SerializationFieldInfo fieldInfo : otherFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      AbstractObjectSerializer.writeOtherFieldValue(binding, buffer, fieldInfo, fieldValue);
    }
  }

  @Override
  public T read(MemoryBuffer buffer) {
    // This method creates a new object - for layer serialization, use readAndSetFields instead
    T obj = newBean();
    refResolver.reference(obj);
    return readAndSetFields(buffer, obj);
  }

  /**
   * Read layer class meta and fields, setting values on the provided object.
   *
   * @param buffer the memory buffer to read from
   * @param obj the object to set field values on
   * @return the object with fields set
   */
  @Override
  public T readAndSetFields(MemoryBuffer buffer, T obj) {
    // Read and verify layer class meta (only if meta share is enabled)
    if (fory.getConfig().isMetaShareEnabled()) {
      readLayerClassMeta(buffer);
    }
    // Read fields in order: final, container, other
    readFinalFields(buffer, obj);
    readContainerFields(buffer, obj);
    readUserTypeFields(buffer, obj);
    return obj;
  }

  private void readLayerClassMeta(MemoryBuffer buffer) {
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    if (metaContext == null) {
      return;
    }
    int header = buffer.readVarUint32Small14();
    int id = header >>> 1;
    // The class def will be read at stream end via readClassDefs()
    // Here we just verify the ID is valid
    if ((header & 0b1) != 0) {
      // Meta share ID - either already known or will be resolved from stream end
      ObjectArray<ClassInfo> readClassInfos = metaContext.readClassInfos;
      if (id >= readClassInfos.size) {
        // ClassDef not yet read - it will be available after readClassDefs() is called
        // For now, we proceed with field reading using our local field info
      }
    }
  }

  private void readFinalFields(MemoryBuffer buffer, T obj) {
    Fory fory = this.fory;
    RefResolver refResolver = this.refResolver;
    ClassResolver classResolver = this.classResolver;

    for (SerializationFieldInfo fieldInfo : buildInFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        boolean nullable = fieldInfo.nullable;
        short classId = fieldInfo.classId;
        if (AbstractObjectSerializer.readPrimitiveFieldValue(
                fory, buffer, obj, fieldAccessor, classId)
            && (nullable
                ? AbstractObjectSerializer.readBasicNullableObjectFieldValue(
                    fory, buffer, obj, fieldAccessor, classId)
                : AbstractObjectSerializer.readBasicObjectFieldValue(
                    fory, buffer, obj, fieldAccessor, classId))) {
          Object fieldValue =
              AbstractObjectSerializer.readFinalObjectFieldValue(
                  binding, refResolver, classResolver, fieldInfo, buffer);
          fieldAccessor.putObject(obj, fieldValue);
        }
      } else {
        // Field doesn't exist in current class - skip the value
        if (MetaSharedSerializer.skipPrimitiveFieldValueFailed(fory, fieldInfo.classId, buffer)) {
          if (fieldInfo.classInfo == null) {
            fory.readRef(buffer, classInfoHolder);
          } else {
            AbstractObjectSerializer.readFinalObjectFieldValue(
                binding, refResolver, classResolver, fieldInfo, buffer);
          }
        }
      }
    }
  }

  private void readContainerFields(MemoryBuffer buffer, T obj) {
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      Object fieldValue =
          AbstractObjectSerializer.readContainerFieldValue(binding, generics, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
  }

  private void readUserTypeFields(MemoryBuffer buffer, T obj) {
    for (SerializationFieldInfo fieldInfo : otherFields) {
      Object fieldValue = AbstractObjectSerializer.readOtherFieldValue(binding, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
  }

  @Override
  public void xwrite(MemoryBuffer buffer, T value) {
    write(buffer, value);
  }

  @Override
  public T xread(MemoryBuffer buffer) {
    return read(buffer);
  }

  /** Returns the ClassDef for this layer. */
  public ClassDef getLayerClassDef() {
    return layerClassDef;
  }

  /** Returns the marker class used as key in metaContext.classMap. */
  public Class<?> getLayerMarkerClass() {
    return layerMarkerClass;
  }

  /** Returns the number of fields in this layer. */
  public int getNumFields() {
    return buildInFields.length + containerFields.length + otherFields.length;
  }

  /**
   * Write field values from an array. Used by putFields/writeFields in ObjectStreamSerializer.
   *
   * @param buffer the memory buffer to write to
   * @param vals array of field values in the same order as fields are declared
   */
  public void writeFieldsValues(MemoryBuffer buffer, Object[] vals) {
    // Write layer class meta using marker class as key (only if meta share is enabled)
    if (fory.getConfig().isMetaShareEnabled()) {
      writeLayerClassMeta(buffer);
    }
    // Write field values from array
    int index = 0;
    // Write final fields
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      Object fieldValue = vals[index++];
      writeFieldValueFromArray(buffer, fieldInfo, fieldValue);
    }
    // Write container fields
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      Object fieldValue = vals[index++];
      AbstractObjectSerializer.writeContainerFieldValue(
          binding, refResolver, typeResolver, generics, fieldInfo, buffer, fieldValue);
    }
    // Write other fields
    for (SerializationFieldInfo fieldInfo : otherFields) {
      Object fieldValue = vals[index++];
      AbstractObjectSerializer.writeOtherFieldValue(binding, buffer, fieldInfo, fieldValue);
    }
  }

  private void writeFieldValueFromArray(
      MemoryBuffer buffer, SerializationFieldInfo fieldInfo, Object fieldValue) {
    short classId = fieldInfo.classId;
    boolean nullable = fieldInfo.nullable;

    // Handle primitives first
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        buffer.writeBoolean((Boolean) fieldValue);
        return;
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        buffer.writeByte((Byte) fieldValue);
        return;
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        buffer.writeChar((Character) fieldValue);
        return;
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        buffer.writeInt16((Short) fieldValue);
        return;
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        if (fory.compressInt()) {
          buffer.writeVarInt32((Integer) fieldValue);
        } else {
          buffer.writeInt32((Integer) fieldValue);
        }
        return;
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        buffer.writeFloat32((Float) fieldValue);
        return;
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        fory.writeInt64(buffer, (Long) fieldValue);
        return;
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        buffer.writeFloat64((Double) fieldValue);
        return;
      default:
        break;
    }

    // Handle objects
    boolean metaShareEnabled = fory.getConfig().isMetaShareEnabled();
    Serializer<Object> serializer = fieldInfo.classInfo.getSerializer();
    if (!metaShareEnabled || fieldInfo.useDeclaredTypeInfo) {
      if (!fieldInfo.trackingRef) {
        binding.writeNullable(buffer, fieldValue, serializer, nullable);
      } else {
        binding.writeRef(buffer, fieldValue, serializer);
      }
    } else {
      if (fieldInfo.trackingRef && serializer.needToWriteRef()) {
        if (!refResolver.writeRefOrNull(buffer, fieldValue)) {
          typeResolver.writeClassInfo(buffer, fieldInfo.classInfo);
          binding.write(buffer, serializer, fieldValue);
        }
      } else {
        binding.writeNullable(buffer, fieldValue, serializer, nullable);
      }
    }
  }

  /**
   * Read field values into an array. Used by readFields in ObjectStreamSerializer.
   *
   * @param buffer the memory buffer to read from
   * @param vals array to store field values
   */
  public void readFields(MemoryBuffer buffer, Object[] vals) {
    // Read and verify layer class meta (only if meta share is enabled)
    if (fory.getConfig().isMetaShareEnabled()) {
      readLayerClassMeta(buffer);
    }
    // Read field values into array
    int index = 0;
    // Read final fields
    for (SerializationFieldInfo fieldInfo : buildInFields) {
      vals[index++] = readFieldValueToArray(buffer, fieldInfo);
    }
    // Read container fields
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      vals[index++] =
          AbstractObjectSerializer.readContainerFieldValue(binding, generics, fieldInfo, buffer);
    }
    // Read other fields
    for (SerializationFieldInfo fieldInfo : otherFields) {
      vals[index++] = AbstractObjectSerializer.readOtherFieldValue(binding, fieldInfo, buffer);
    }
  }

  private Object readFieldValueToArray(MemoryBuffer buffer, SerializationFieldInfo fieldInfo) {
    short classId = fieldInfo.classId;

    // Handle primitives
    if (classId >= ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID
        && classId <= ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID) {
      return Serializers.readPrimitiveValue(fory, buffer, classId);
    }

    // Handle objects
    return AbstractObjectSerializer.readFinalObjectFieldValue(
        binding, refResolver, classResolver, fieldInfo, buffer);
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
  @SuppressWarnings("rawtypes")
  public Object[] getFieldValuesForPutFields(
      Object obj, org.apache.fory.collection.ObjectIntMap fieldIndexMap, int arraySize) {
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
}
