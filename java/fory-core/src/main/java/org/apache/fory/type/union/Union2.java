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

/**
 * A typed union that can hold one of two alternative types. The active alternative is identified by
 * an index (0 or 1).
 *
 * <p>This class extends {@link Union} and provides a type-safe way to represent values that can be
 * one of two types, similar to Rust's Result or Either types.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * // Create a union holding a String (type T1, index 0)
 * Union2<String, Long> union1 = Union2.ofT1("hello");
 *
 * // Create a union holding a Long (type T2, index 1)
 * Union2<String, Long> union2 = Union2.ofT2(100L);
 *
 * // Get the value
 * if (union1.isT1()) {
 *     String value = union1.getT1();
 * }
 * }</pre>
 *
 * <p>For unions with more than 6 types, use the generic {@link Union} class instead.
 *
 * @param <T1> the first alternative type
 * @param <T2> the second alternative type
 * @see Union
 * @see Union3
 * @see Union4
 * @see Union5
 * @see Union6
 */
public final class Union2<T1, T2> extends Union {

  private Union2(int index, Object value) {
    super(index, value);
  }

  /**
   * Creates a Union2 holding a value of the first type T1.
   *
   * @param <T1> the first alternative type
   * @param <T2> the second alternative type
   * @param value the value of type T1
   * @return a new Union2 instance
   */
  public static <T1, T2> Union2<T1, T2> ofT1(T1 value) {
    Union2<T1, T2> union = new Union2<>(0, value);
    assert union.getIndex() == 0 : "ofT1 should create union with index 0";
    return union;
  }

  /**
   * Creates a Union2 holding a value of the second type T2.
   *
   * @param <T1> the first alternative type
   * @param <T2> the second alternative type
   * @param value the value of type T2
   * @return a new Union2 instance
   */
  public static <T1, T2> Union2<T1, T2> ofT2(T2 value) {
    Union2<T1, T2> union = new Union2<>(1, value);
    assert union.getIndex() == 1 : "ofT2 should create union with index 1";
    return union;
  }

  /**
   * Creates a Union2 from an index and value. Used primarily for deserialization.
   *
   * @param <T1> the first alternative type
   * @param <T2> the second alternative type
   * @param index the index (0 or 1)
   * @param value the value
   * @return a new Union2 instance
   */
  public static <T1, T2> Union2<T1, T2> of(int index, Object value) {
    if (index < 0 || index > 1) {
      throw new IllegalArgumentException("Index must be 0 or 1 for Union2, got: " + index);
    }
    return new Union2<>(index, value);
  }

  /**
   * Gets the index of the active alternative.
   *
   * @return 0 if T1 is active, 1 if T2 is active
   */
  @Override
  public int getIndex() {
    return index;
  }

  /**
   * Gets the value as the first type T1.
   *
   * @return the value cast to T1
   * @throws ClassCastException if the active type is not T1
   */
  @SuppressWarnings("unchecked")
  public T1 getT1() {
    return (T1) value;
  }

  /**
   * Gets the value as the second type T2.
   *
   * @return the value cast to T2
   * @throws ClassCastException if the active type is not T2
   */
  @SuppressWarnings("unchecked")
  public T2 getT2() {
    return (T2) value;
  }

  /**
   * Gets the raw value without type casting.
   *
   * @return the value
   */
  @Override
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
   * Checks if this union currently holds a non-null value.
   *
   * @return true if a value is set (not null), false otherwise
   */
  @Override
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
    return index == union.getIndex() && java.util.Objects.equals(value, union.getValue());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(index, value);
  }

  @Override
  public String toString() {
    return "Union2{index=" + index + ", value=" + value + "}";
  }
}
