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

import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Dense Java carrier for xlang {@code array<float16>} values. */
public final class Float16Array implements Iterable<Float16>, Serializable {
  private static final long serialVersionUID = 1L;

  private final short[] bits;

  public Float16Array(int size) {
    this.bits = new short[size];
  }

  public Float16Array(Float16[] values) {
    this.bits = new short[values.length];
    for (int i = 0; i < values.length; i++) {
      Float16 value = values[i];
      if (value == null) {
        throw new IllegalArgumentException("Float16Array doesn't support null at index " + i);
      }
      bits[i] = value.toBits();
    }
  }

  private Float16Array(short[] bits, boolean copy) {
    this.bits = copy ? Arrays.copyOf(bits, bits.length) : bits;
  }

  public static Float16Array fromBits(short[] bits) {
    return new Float16Array(bits, true);
  }

  public static Float16Array fromFloats(float[] values) {
    short[] bits = new short[values.length];
    for (int i = 0; i < values.length; i++) {
      bits[i] = Float16.toBits(values[i]);
    }
    return new Float16Array(bits, false);
  }

  public static Float16Array of(float... values) {
    return fromFloats(values);
  }

  public static Float16Array wrapBits(short[] bits) {
    return new Float16Array(bits, false);
  }

  public int length() {
    return bits.length;
  }

  public int size() {
    return bits.length;
  }

  public Float16 get(int index) {
    return Float16.fromBits(bits[index]);
  }

  public float getFloat(int index) {
    return Float16.toFloat(bits[index]);
  }

  public short getBits(int index) {
    return bits[index];
  }

  public void set(int index, Float16 value) {
    if (value == null) {
      throw new NullPointerException("value");
    }
    bits[index] = value.toBits();
  }

  public void setFloat(int index, float value) {
    bits[index] = Float16.toBits(value);
  }

  public void setBits(int index, short bits) {
    this.bits[index] = bits;
  }

  public short[] copyBits() {
    return Arrays.copyOf(bits, bits.length);
  }

  public short[] getBitsArray() {
    return bits;
  }

  @Override
  public Iterator<Float16> iterator() {
    return new Iterator<Float16>() {
      private int index;

      @Override
      public boolean hasNext() {
        return index < bits.length;
      }

      @Override
      public Float16 next() {
        if (index >= bits.length) {
          throw new NoSuchElementException();
        }
        return Float16.fromBits(bits[index++]);
      }
    };
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Float16Array)) {
      return false;
    }
    Float16Array other = (Float16Array) obj;
    return Arrays.equals(bits, other.bits);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bits);
  }

  @Override
  public String toString() {
    float[] values = new float[bits.length];
    for (int i = 0; i < bits.length; i++) {
      values[i] = Float16.toFloat(bits[i]);
    }
    return Arrays.toString(values);
  }
}
