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

package org.apache.fory.type;

import java.util.Arrays;
import java.util.Objects;

/**
 * A tagged union type that can hold one of several alternative types. The active alternative is
 * identified by an index.
 *
 * <p>This class provides a type-safe way to represent values that can be one of several types,
 * similar to Rust's enum, C++'s std::variant, or TypeScript's union types.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * // Create a union that can hold Integer, String, or Double
 * Union union = Union.of(Integer.class, String.class, Double.class);
 *
 * // Set an integer value (index 0)
 * union.setValue(42);
 *
 * // Get the value
 * Integer value = union.getValue(Integer.class);
 *
 * // Check the active type
 * int index = union.getActiveIndex(); // returns 0
 * }</pre>
 */
public class Union {
  /** The alternative types that this union can hold. */
  private final Class<?>[] alternativeTypes;

  /** The index of the currently active alternative (-1 if not set). */
  private int activeIndex;

  /** The current value. */
  private Object value;

  /**
   * Creates a new Union with the specified alternative types.
   *
   * @param alternativeTypes the types that this union can hold
   */
  public Union(Class<?>... alternativeTypes) {
    if (alternativeTypes == null || alternativeTypes.length == 0) {
      throw new IllegalArgumentException("Union must have at least one alternative type");
    }
    this.alternativeTypes = alternativeTypes.clone();
    this.activeIndex = -1;
    this.value = null;
  }

  /**
   * Creates a new Union with the specified alternative types.
   *
   * @param alternativeTypes the types that this union can hold
   * @return a new Union instance
   */
  public static Union of(Class<?>... alternativeTypes) {
    return new Union(alternativeTypes);
  }

  /**
   * Creates a new Union with the specified alternative types and initial value.
   *
   * @param value the initial value
   * @param alternativeTypes the types that this union can hold
   * @return a new Union instance with the value set
   */
  public static Union ofValue(Object value, Class<?>... alternativeTypes) {
    Union union = new Union(alternativeTypes);
    union.setValue(value);
    return union;
  }

  /**
   * Gets the alternative types that this union can hold.
   *
   * @return an array of alternative types
   */
  public Class<?>[] getAlternativeTypes() {
    return alternativeTypes.clone();
  }

  /**
   * Gets the number of alternative types.
   *
   * @return the number of alternative types
   */
  public int getAlternativeCount() {
    return alternativeTypes.length;
  }

  /**
   * Gets the index of the currently active alternative.
   *
   * @return the active index, or -1 if no value is set
   */
  public int getActiveIndex() {
    return activeIndex;
  }

  /**
   * Sets the active index directly. This is primarily used during deserialization.
   *
   * @param index the index to set
   */
  public void setActiveIndex(int index) {
    if (index < -1 || index >= alternativeTypes.length) {
      throw new IndexOutOfBoundsException(
          "Index "
              + index
              + " out of bounds for union with "
              + alternativeTypes.length
              + " alternatives");
    }
    this.activeIndex = index;
  }

  /**
   * Gets the type of the currently active alternative.
   *
   * @return the active type, or null if no value is set
   */
  public Class<?> getActiveType() {
    if (activeIndex < 0) {
      return null;
    }
    return alternativeTypes[activeIndex];
  }

  /**
   * Gets the current value.
   *
   * @return the current value, or null if not set
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
   * Sets the value of this union. The value must be an instance of one of the alternative types.
   *
   * @param value the value to set
   * @throws IllegalArgumentException if the value is not an instance of any alternative type
   */
  public void setValue(Object value) {
    if (value == null) {
      this.activeIndex = -1;
      this.value = null;
      return;
    }

    Class<?> valueClass = value.getClass();
    for (int i = 0; i < alternativeTypes.length; i++) {
      if (alternativeTypes[i].isAssignableFrom(valueClass)) {
        this.activeIndex = i;
        this.value = value;
        return;
      }
    }

    throw new IllegalArgumentException(
        "Value of type "
            + valueClass.getName()
            + " is not compatible with any alternative type: "
            + Arrays.toString(alternativeTypes));
  }

  /**
   * Sets the value at a specific index. This is primarily used during deserialization.
   *
   * @param index the index of the alternative type
   * @param value the value to set
   */
  public void setValueAt(int index, Object value) {
    if (index < 0 || index >= alternativeTypes.length) {
      throw new IndexOutOfBoundsException(
          "Index "
              + index
              + " out of bounds for union with "
              + alternativeTypes.length
              + " alternatives");
    }
    this.activeIndex = index;
    this.value = value;
  }

  /**
   * Checks if this union currently holds a value.
   *
   * @return true if a value is set, false otherwise
   */
  public boolean hasValue() {
    return activeIndex >= 0;
  }

  /**
   * Checks if the current value is of the specified type.
   *
   * @param type the type to check
   * @return true if the current value is of the specified type
   */
  public boolean isType(Class<?> type) {
    if (activeIndex < 0) {
      return false;
    }
    return alternativeTypes[activeIndex].equals(type);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Union union = (Union) o;
    return activeIndex == union.activeIndex
        && Arrays.equals(alternativeTypes, union.alternativeTypes)
        && Objects.equals(value, union.value);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(activeIndex, value);
    result = 31 * result + Arrays.hashCode(alternativeTypes);
    return result;
  }

  @Override
  public String toString() {
    if (activeIndex < 0) {
      return "Union{empty, types=" + Arrays.toString(alternativeTypes) + "}";
    }
    return "Union{index="
        + activeIndex
        + ", type="
        + alternativeTypes[activeIndex].getSimpleName()
        + ", value="
        + value
        + "}";
  }
}
