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
import org.apache.fory.collection.ObjectIntMap;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Base class for meta-shared layer serializers. This provides the common interface for both
 * interpreter mode ({@link MetaSharedLayerSerializer}) and JIT-generated serializers.
 *
 * @see MetaSharedLayerSerializer
 * @see org.apache.fory.builder.MetaSharedLayerCodecBuilder
 */
public abstract class MetaSharedLayerSerializerBase<T> extends AbstractObjectSerializer<T> {

  public MetaSharedLayerSerializerBase(Fory fory, Class<T> type) {
    super(fory, type);
  }

  /**
   * Read fields and set values on the provided object. Note: When meta share is enabled, the caller
   * (typically ObjectStreamSerializer) is responsible for reading the layer class meta first. This
   * method only reads field data, not the layer class meta.
   *
   * @param buffer the memory buffer to read from
   * @param obj the object to set field values on
   * @return the object with fields set
   */
  public abstract T readAndSetFields(MemoryBuffer buffer, T obj);

  /**
   * Set field values on target object from putFields data. This method maps field names from
   * ObjectStreamClass to actual class fields and sets matching values.
   *
   * @param obj the target object
   * @param fieldIndexMap mapping from field name to index in vals array
   * @param vals the values array from putFields
   */
  @SuppressWarnings("rawtypes")
  public abstract void setFieldValuesFromPutFields(
      Object obj, ObjectIntMap fieldIndexMap, Object[] vals);

  /**
   * Get field values from object for putFields format. This method reads actual class field values
   * and puts them into an array based on ObjectStreamClass field order.
   *
   * @param obj the source object
   * @param fieldIndexMap mapping from field name to index in result array
   * @param arraySize size of the result array
   * @return array of field values in putFields order
   */
  @SuppressWarnings("rawtypes")
  public abstract Object[] getFieldValuesForPutFields(
      Object obj, ObjectIntMap fieldIndexMap, int arraySize);

  /**
   * Write layer class meta to buffer. Called by ObjectStreamSerializer before writing fields. Only
   * writes meta if meta share is enabled.
   *
   * @param buffer the memory buffer to write to
   */
  public abstract void writeLayerClassMeta(MemoryBuffer buffer);

  /**
   * Write fields only, without layer class meta. The layer class meta should be written by the
   * caller (ObjectStreamSerializer) before calling this method.
   *
   * @param buffer the memory buffer to write to
   * @param value the object to write fields from
   */
  public abstract void writeFieldsOnly(MemoryBuffer buffer, T value);

  /**
   * Write field values from array. Used by writeFields() when values come from PutField. The values
   * array should be in the serializer's field order (buildIn, container, other).
   *
   * @param buffer the memory buffer to write to
   * @param vals the values array in field order
   */
  public abstract void writeFieldValues(MemoryBuffer buffer, Object[] vals);

  /**
   * Read field values into array. Used by readFields() to populate GetField. Returns values in the
   * serializer's field order (buildIn, container, other).
   *
   * @param buffer the memory buffer to read from
   * @return array of field values in field order
   */
  public abstract Object[] readFieldValues(MemoryBuffer buffer);

  /**
   * Get the total number of fields in this layer.
   *
   * @return number of fields
   */
  public abstract int getNumFields();

  /**
   * Populate field index map and field types array in the serializer's field order. This is used by
   * ObjectStreamSerializer to build fieldIndexMap and putFieldTypes in the correct order.
   *
   * @param fieldIndexMap map to populate with field name → index
   * @param fieldTypes array to populate with field types (must be pre-allocated with getNumFields()
   *     size)
   */
  @SuppressWarnings("rawtypes")
  public abstract void populateFieldInfo(ObjectIntMap fieldIndexMap, Class<?>[] fieldTypes);
}
