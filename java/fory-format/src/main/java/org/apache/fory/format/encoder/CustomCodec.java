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

package org.apache.fory.format.encoder;

import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.TypeRef;

/**
 * Extension point to customize Fory row codec behavior. Supports intercepting types to be written
 * ({@code encode}) and read ({@code decode}).
 *
 * @param <T> the type the codec decodes to (used in Java)
 * @param <E> the type the codec encodes to (byte representation)
 */
public interface CustomCodec<T, E> {

  /**
   * Returns the Fory Field for the given field name, or null to infer it from {@link
   * #encodedType()}.
   *
   * <p>The returned field overrides only the column's logical type and nullability; its physical
   * row representation must match what the codec reads and writes through {@link #encodedType()}.
   * In particular, a fixed-width column (for example {@code int64} or {@code fixedWidthBinary(16)})
   * must equal the exact byte width {@link #encode} produces, because the compact layout sizes the
   * column's slot from this declaration. Declaring a width the codec does not honor corrupts the
   * compact row (the eager row format word-pads fixed slots and can mask it). For example {@link
   * MemoryBufferCodec} declares a variable {@code binary} column, while a UUID codec writing a
   * 16-byte buffer may declare {@code fixedWidthBinary(16)}.
   *
   * @param fieldName the name of the field
   * @return the Fory field definition, or null to use default inference
   */
  Field getForyField(String fieldName);

  TypeRef<E> encodedType();

  E encode(T value);

  /**
   * Reconstructs the value from its encoded representation. A codec keyed on {@link
   * java.util.Optional} owns the field's absence and must return a non-null {@code Optional} (use
   * {@link java.util.Optional#empty()} for absence); the decoded value is assigned straight to the
   * Optional field, so returning {@code null} would surface later as a {@code
   * NullPointerException}.
   */
  T decode(E value);

  /** Specialized codec base for encoding and decoding to/from {@link MemoryBuffer}. */
  interface MemoryBufferCodec<T> extends CustomCodec<T, MemoryBuffer> {
    @Override
    default TypeRef<MemoryBuffer> encodedType() {
      return TypeRef.of(MemoryBuffer.class);
    }

    @Override
    default Field getForyField(final String fieldName) {
      return DataTypes.field(fieldName, DataTypes.binary());
    }
  }

  /** Specialized codec base for encoding and decoding to/from {@code byte[]}. */
  interface ByteArrayCodec<T> extends CustomCodec<T, byte[]> {
    @Override
    default TypeRef<byte[]> encodedType() {
      return TypeRef.of(byte[].class);
    }

    @Override
    default Field getForyField(final String fieldName) {
      return DataTypes.primitiveArrayField(fieldName, DataTypes.int8());
    }
  }

  /** Specialized codec base for encoding and decoding to/from {@link BinaryArray}. */
  interface BinaryArrayCodec<T> extends CustomCodec<T, BinaryArray> {
    @Override
    default TypeRef<BinaryArray> encodedType() {
      return TypeRef.of(BinaryArray.class);
    }

    @Override
    default Field getForyField(final String fieldName) {
      return DataTypes.primitiveArrayField(fieldName, DataTypes.int8());
    }
  }

  /**
   * Specialized codec base for read and write replace of a value, without changing its type.
   * Example use: converting Fory generated implementation into a standard user-provided
   * implementation.
   */
  interface InterceptingCodec<T> extends CustomCodec<T, T> {
    @Override
    default Field getForyField(final String fieldName) {
      return null;
    }

    @Override
    default T decode(final T value) {
      return value;
    }

    @Override
    default T encode(final T value) {
      return value;
    }
  }
}
