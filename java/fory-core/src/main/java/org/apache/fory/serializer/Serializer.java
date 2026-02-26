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

import javax.annotation.concurrent.NotThreadSafe;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.type.TypeUtils;

/**
 * Serialize/deserializer objects into binary. Note that this class is designed as an abstract class
 * instead of interface to reduce virtual method call cost of {@link #needToWriteRef}.
 *
 * @param <T> type of objects being serializing/deserializing
 */
@NotThreadSafe
@SuppressWarnings("unchecked")
public abstract class Serializer<T> {
  protected final Fory fory;
  protected final RefResolver refResolver;
  protected final Class<T> type;
  protected final boolean isJava;
  protected final boolean needToWriteRef;

  /**
   * Whether to enable circular reference of copy. Only for mutable objects, immutable objects just
   * return itself.
   */
  protected final boolean needToCopyRef;

  protected final boolean immutable;

  public Serializer(Fory fory, Class<T> type) {
    this(
        fory,
        type,
        fory.trackingRef() && !TypeUtils.isBoxed(TypeUtils.wrap(type)),
        TypeUtils.isPrimitive(type) || TypeUtils.isBoxed(type));
  }

  public Serializer(Fory fory, Class<T> type, boolean immutable) {
    this(fory, type, fory.trackingRef() && !TypeUtils.isBoxed(TypeUtils.wrap(type)), immutable);
  }

  public Serializer(Fory fory, Class<T> type, boolean needToWriteRef, boolean immutable) {
    this.fory = fory;
    this.refResolver = fory.getRefResolver();
    this.type = type;
    this.isJava = !fory.isCrossLanguage();
    this.needToWriteRef = needToWriteRef;
    this.needToCopyRef = fory.copyTrackingRef() && !immutable;
    this.immutable = immutable;
  }

  /**
   * Write value to buffer, this method may write ref/null flags based passed {@code refMode}, and
   * the passed value can be null. Note that this method don't write type info, this method is
   * mostly be used in cases the context has already knows the value type when deserialization.
   */
  public void write(MemoryBuffer buffer, RefMode refMode, T value) {
    // noinspection Duplicates
    if (refMode == RefMode.TRACKING) {
      if (refResolver.writeRefOrNull(buffer, value)) {
        return;
      }
    } else if (refMode == RefMode.NULL_ONLY) {
      if (value == null) {
        buffer.writeByte(Fory.NULL_FLAG);
        return;
      } else {
        buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
      }
    }
    write(buffer, value);
  }

  /**
   * Write value to buffer, this method do not write ref/null flags and the passed value must not be
   * null.
   */
  public abstract void write(MemoryBuffer buffer, T value);

  /**
   * Read value from buffer, this method may read ref/null flags based passed {@code refMode}, and
   * the read value can be null. Note that this method don't read type info, this method is mostly
   * be used in cases the context has already knows the value type for deserialization.
   */
  public T read(MemoryBuffer buffer, RefMode refMode) {
    if (refMode == RefMode.TRACKING) {
      T obj;
      int nextReadRefId = refResolver.tryPreserveRefId(buffer);
      if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
        obj = read(buffer);
        refResolver.setReadObject(nextReadRefId, obj);
        return obj;
      } else {
        return (T) refResolver.getReadObject();
      }
    } else if (refMode != RefMode.NULL_ONLY || buffer.readByte() != Fory.NULL_FLAG) {
      if (needToWriteRef) {
        // in normal case, the read implementation may invoke `refResolver.reference` to
        // support circular reference, so we still need this `-1`
        refResolver.preserveRefId(-1);
      }
      return read(buffer);
    }
    return null;
  }

  /**
   * Read value from buffer, this method wont read ref/null flags and the read value won't be null.
   */
  public abstract T read(MemoryBuffer buffer);

  public T copy(T value) {
    if (isImmutable()) {
      return value;
    }
    throw new UnsupportedOperationException(
        String.format("Copy for %s is not supported", value.getClass()));
  }

  public final boolean needToWriteRef() {
    return needToWriteRef;
  }

  public final boolean needToCopyRef() {
    return needToCopyRef;
  }

  public Class<T> getType() {
    return type;
  }

  public boolean isImmutable() {
    return immutable;
  }
}
