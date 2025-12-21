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

/**
 * A typed union that can hold one of five alternative types. The active alternative is identified
 * by an index (0-4).
 *
 * <p>This class provides a type-safe way to represent values that can be one of five types.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * // Create a union holding a String (type T2, index 1)
 * Union5<Integer, String, Double, Boolean, Long> union = Union5.ofT2("hello");
 *
 * // Get the value
 * if (union.isT2()) {
 *     String value = union.getT2();
 * }
 * }</pre>
 *
 * <p>For unions with more than 6 types, use the generic {@link Union} class instead.
 *
 * @param <T1> the first alternative type
 * @param <T2> the second alternative type
 * @param <T3> the third alternative type
 * @param <T4> the fourth alternative type
 * @param <T5> the fifth alternative type
 * @see Union
 * @see Union2
 * @see Union3
 * @see Union4
 * @see Union6
 */
public class Union5<T1, T2, T3, T4, T5> {
  /** The index indicating which alternative is active. */
  private final int index;

  /** The current value. */
  private final Object value;

  private Union5(int index, Object value) {
    this.index = index;
    this.value = value;
  }

  /**
   * Creates a Union5 holding a value of the first type T1.
   *
   * @param <T1> the first alternative type
   * @param <T2> the second alternative type
   * @param <T3> the third alternative type
   * @param <T4> the fourth alternative type
   * @param <T5> the fifth alternative type
   * @param value the value of type T1
   * @return a new Union5 instance
   */
  public static <T1, T2, T3, T4, T5> Union5<T1, T2, T3, T4, T5> ofT1(T1 value) {
    return new Union5<>(0, value);
  }

  /**
   * Creates a Union5 holding a value of the second type T2.
   *
   * @param <T1> the first alternative type
   * @param <T2> the second alternative type
   * @param <T3> the third alternative type
   * @param <T4> the fourth alternative type
   * @param <T5> the fifth alternative type
   * @param value the value of type T2
   * @return a new Union5 instance
   */
  public static <T1, T2, T3, T4, T5> Union5<T1, T2, T3, T4, T5> ofT2(T2 value) {
    return new Union5<>(1, value);
  }

  /**
   * Creates a Union5 holding a value of the third type T3.
   *
   * @param <T1> the first alternative type
   * @param <T2> the second alternative type
   * @param <T3> the third alternative type
   * @param <T4> the fourth alternative type
   * @param <T5> the fifth alternative type
   * @param value the value of type T3
   * @return a new Union5 instance
   */
  public static <T1, T2, T3, T4, T5> Union5<T1, T2, T3, T4, T5> ofT3(T3 value) {
    return new Union5<>(2, value);
  }

  /**
   * Creates a Union5 holding a value of the fourth type T4.
   *
   * @param <T1> the first alternative type
   * @param <T2> the second alternative type
   * @param <T3> the third alternative type
   * @param <T4> the fourth alternative type
   * @param <T5> the fifth alternative type
   * @param value the value of type T4
   * @return a new Union5 instance
   */
  public static <T1, T2, T3, T4, T5> Union5<T1, T2, T3, T4, T5> ofT4(T4 value) {
    return new Union5<>(3, value);
  }

  /**
   * Creates a Union5 holding a value of the fifth type T5.
   *
   * @param <T1> the first alternative type
   * @param <T2> the second alternative type
   * @param <T3> the third alternative type
   * @param <T4> the fourth alternative type
   * @param <T5> the fifth alternative type
   * @param value the value of type T5
   * @return a new Union5 instance
   */
  public static <T1, T2, T3, T4, T5> Union5<T1, T2, T3, T4, T5> ofT5(T5 value) {
    return new Union5<>(4, value);
  }

  /**
   * Creates a Union5 from an index and value. Used primarily for deserialization.
   *
   * @param <T1> the first alternative type
   * @param <T2> the second alternative type
   * @param <T3> the third alternative type
   * @param <T4> the fourth alternative type
   * @param <T5> the fifth alternative type
   * @param index the index (0-4)
   * @param value the value
   * @return a new Union5 instance
   */
  public static <T1, T2, T3, T4, T5> Union5<T1, T2, T3, T4, T5> of(int index, Object value) {
    if (index < 0 || index > 4) {
      throw new IllegalArgumentException("Index must be 0-4 for Union5, got: " + index);
    }
    return new Union5<>(index, value);
  }

  /**
   * Gets the index of the active alternative.
   *
   * @return 0-4 indicating which type is active
   */
  public int getIndex() {
    return index;
  }

  /**
   * Gets the value as the first type T1.
   *
   * @return the value cast to T1
   */
  @SuppressWarnings("unchecked")
  public T1 getT1() {
    return (T1) value;
  }

  /**
   * Gets the value as the second type T2.
   *
   * @return the value cast to T2
   */
  @SuppressWarnings("unchecked")
  public T2 getT2() {
    return (T2) value;
  }

  /**
   * Gets the value as the third type T3.
   *
   * @return the value cast to T3
   */
  @SuppressWarnings("unchecked")
  public T3 getT3() {
    return (T3) value;
  }

  /**
   * Gets the value as the fourth type T4.
   *
   * @return the value cast to T4
   */
  @SuppressWarnings("unchecked")
  public T4 getT4() {
    return (T4) value;
  }

  /**
   * Gets the value as the fifth type T5.
   *
   * @return the value cast to T5
   */
  @SuppressWarnings("unchecked")
  public T5 getT5() {
    return (T5) value;
  }

  /**
   * Gets the raw value without type casting.
   *
   * @return the value
   */
  public Object getValue() {
    return value;
  }

  /**
   * Checks if the first type T1 is active.
   *
   * @return true if index is 0
   */
  public boolean isT1() {
    return index == 0;
  }

  /**
   * Checks if the second type T2 is active.
   *
   * @return true if index is 1
   */
  public boolean isT2() {
    return index == 1;
  }

  /**
   * Checks if the third type T3 is active.
   *
   * @return true if index is 2
   */
  public boolean isT3() {
    return index == 2;
  }

  /**
   * Checks if the fourth type T4 is active.
   *
   * @return true if index is 3
   */
  public boolean isT4() {
    return index == 3;
  }

  /**
   * Checks if the fifth type T5 is active.
   *
   * @return true if index is 4
   */
  public boolean isT5() {
    return index == 4;
  }

  /**
   * Checks if this union currently holds a non-null value.
   *
   * @return true if a value is set (not null), false otherwise
   */
  public boolean hasValue() {
    return value != null;
  }

  /**
   * Converts this typed union to an untyped Union.
   *
   * @return an untyped Union with the same index and value
   */
  public Union toUnion() {
    return new Union(index, value);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Union5<?, ?, ?, ?, ?> union5 = (Union5<?, ?, ?, ?, ?>) o;
    return index == union5.index && Objects.equals(value, union5.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, value);
  }

  @Override
  public String toString() {
    return "Union5{index=" + index + ", value=" + value + "}";
  }
}
