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

package org.apache.fory.type.union;

import java.util.Objects;
import org.apache.fory.type.Types;

/**
 * A general union that holds a value and an index. This is the base class for all typed unions
 * (Union2, Union3, etc.).
 *
 * <p>This class can be used directly when deserializing union data without knowing the specific
 * union type, or when you need more than 6 alternative types.
 *
 * <p>For type-safe unions with up to 6 types, use {@link Union2}, {@link Union3}, {@link Union4},
 * {@link Union5}, or {@link Union6} instead.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * // When deserializing union data without type information
 * Union union = new Union(0, "hello");
 * Object value = union.getValue();
 * int index = union.getIndex();
 *
 * // For unions with more than 6 types
 * Union union = new Union(7, someValue);
 * }</pre>
 *
 * @see Union2
 * @see Union3
 * @see Union4
 * @see Union5
 * @see Union6
 */
public class Union {
  /** The index indicating which alternative is active. */
  protected int index;

  /** The current value. */
  protected Object value;

  /** The type id of the current value (xlang type id). */
  protected int typeId;

  /**
   * Creates a new Union with the specified index and value.
   *
   * @param index the index of the active alternative
   * @param value the value
   */
  public Union(int index, Object value) {
    this(index, value, Types.UNKNOWN);
  }

  /**
   * Creates a new Union with the specified index, value and value type id.
   *
   * @param index the index of the active alternative
   * @param value the value
   * @param typeId the xlang type id of the value
   */
  public Union(int index, Object value, int typeId) {
    this.index = index;
    this.value = value;
    this.typeId = typeId;
  }

  /**
   * Gets the index of the active alternative.
   *
   * @return the index
   */
  public int getIndex() {
    return index;
  }

  /**
   * Gets the current value.
   *
   * @return the value
   */
  public Object getValue() {
    return value;
  }

  /**
   * Gets the current value cast to the specified type.
   *
   * @param <T> the expected type
   * @param type the class of the expected type
   * @return the current value cast to the specified type
   * @throws ClassCastException if the value cannot be cast to the specified type
   */
  @SuppressWarnings("unchecked")
  public <T> T getValue(Class<T> type) {
    return (T) value;
  }

  /**
   * Gets the xlang type id of the current value.
   *
   * @return the value type id
   */
  public int getValueTypeId() {
    return typeId;
  }

  /**
   * Checks if this union currently holds a value.
   *
   * @return true if a value is set (not null), false otherwise
   */
  public boolean hasValue() {
    return value != null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Union)) {
      return false;
    }
    Union union = (Union) o;
    return index == union.getIndex() && Objects.equals(value, union.getValue());
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, value);
  }

  @Override
  public String toString() {
    return "Union{index=" + index + ", value=" + value + "}";
  }
}
