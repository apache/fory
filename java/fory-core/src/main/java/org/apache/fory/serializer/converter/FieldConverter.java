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

package org.apache.fory.serializer.converter;

import java.lang.reflect.Field;
import org.apache.fory.reflect.FieldAccessor;

/**
 * Abstract base class for field converters that handle type conversions during
 * serialization/deserialization.
 *
 * <p>Field converters are responsible for converting values from one type to another when setting
 * field values. This is particularly useful when dealing with cross-language serialization where
 * type mappings may differ between languages, or when handling legacy data with different type
 * representations.
 *
 * <p>Each converter is associated with a specific field through a {@link FieldAccessor}, which
 * provides the mechanism to actually set the converted value on the target object.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Create a converter for an int field
 * FieldConverter<Integer> converter = new IntConverter(fieldAccessor);
 *
 * // Convert a string "123" to integer and set it on the target object
 * converter.set(targetObject, "123");
 * }</pre>
 *
 * @param <T> the target type that this converter produces
 * @see FieldConverters for factory methods to create specific converter instances
 * @see FieldAccessor for the field access mechanism
 */
public abstract class FieldConverter<T> {
  private final FieldAccessor fieldAccessor;

  /**
   * Constructs a new FieldConverter with the specified field accessor.
   *
   * @param fieldAccessor the field accessor that will be used to set converted values on target
   *     objects
   * @throws IllegalArgumentException if fieldAccessor is null
   */
  protected FieldConverter(FieldAccessor fieldAccessor) {
    this.fieldAccessor = fieldAccessor;
  }

  /**
   * Converts the given object to the target type.
   *
   * <p>This method performs the actual type conversion logic. The implementation should handle null
   * values appropriately and throw {@link UnsupportedOperationException} for incompatible types.
   *
   * @param from the object to convert
   * @return the converted object of type T
   * @throws UnsupportedOperationException if the source type is not compatible with this converter
   * @throws NumberFormatException if converting from String to a numeric type and the string is not
   *     a valid number
   * @throws ArithmeticException if the numeric conversion would result in overflow or underflow
   */
  public abstract T convert(Object from);

  /**
   * Converts the given object and sets it on the target object's field.
   *
   * <p>This is a convenience method that combines conversion and field setting in one operation. It
   * first converts the input object using {@link #convert(Object)}, then uses the field accessor to
   * set the converted value on the target object.
   *
   * @param target the target object whose field will be set
   * @param from the object to convert and set
   * @throws UnsupportedOperationException if the source type is not compatible with this converter
   * @throws NumberFormatException if converting from String to a numeric type and the string is not
   *     a valid number
   * @throws ArithmeticException if the numeric conversion would result in overflow or underflow
   * @throws IllegalArgumentException if target is null or the field accessor fails
   */
  public void set(Object target, Object from) {
    T converted = convert(from);
    fieldAccessor.set(target, converted);
  }

  public Field getField() {
    return fieldAccessor.getField();
  }
}
