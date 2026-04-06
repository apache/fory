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
import org.apache.fory.config.Config;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.type.TypeUtils;

/**
 * Base contract for reading, writing, and copying a specific Java type.
 *
 * <p>A serializer is responsible only for the payload of {@code T}. Type metadata, ref/null
 * headers, and operation state are owned by {@link WriteContext}, {@link ReadContext}, and {@link
 * CopyContext}.
 *
 * <p>This type stays an abstract class instead of an interface so hot-path flag checks such as
 * {@link #needToWriteRef()} avoid an extra virtual dispatch.
 *
 * <p>Unless a subclass overrides {@link #shareable()}, instances are runtime-local and must not be
 * shared across equivalent runtimes or concurrent operations.
 *
 * @param <T> value type handled by this serializer
 */
@SuppressWarnings("unchecked")
public abstract class Serializer<T> {
  protected final Class<T> type;
  protected final boolean needToWriteRef;

  /**
   * Whether to enable circular reference of copy. Only for mutable objects, immutable objects just
   * return itself.
   */
  protected final boolean needToCopyRef;

  protected final boolean immutable;

  /**
   * Creates a serializer using ref/copy defaults derived from {@link Config} and {@code type}.
   *
   * <p>Primitive and boxed primitive types are treated as immutable. Boxed primitives also skip ref
   * tracking even when the runtime tracks references for general objects.
   */
  public Serializer(Config config, Class<T> type) {
    this(
        config,
        type,
        config.trackingRef() && !TypeUtils.isBoxed(TypeUtils.wrap(type)),
        TypeUtils.isPrimitive(type) || TypeUtils.isBoxed(type));
  }

  /**
   * Creates a serializer with an explicit immutability contract while keeping default ref-tracking
   * behavior derived from {@link Config}.
   */
  public Serializer(Config config, Class<T> type, boolean immutable) {
    this(config, type, config.trackingRef() && !TypeUtils.isBoxed(TypeUtils.wrap(type)), immutable);
  }

  /**
   * Creates a serializer with explicit ref-writing and immutability behavior.
   *
   * @param config runtime configuration used to derive copy-ref behavior
   * @param type value type handled by this serializer
   * @param needToWriteRef whether payload reads and writes participate in runtime ref tracking
   * @param immutable whether values can be returned directly during copy
   */
  public Serializer(Config config, Class<T> type, boolean needToWriteRef, boolean immutable) {
    this.type = type;
    this.needToWriteRef = needToWriteRef;
    this.needToCopyRef = config.copyRef() && !immutable;
    this.immutable = immutable;
  }

  /**
   * Writes a nullable value using the supplied ref/null policy.
   *
   * <p>This helper writes ref or null flags according to {@code refMode}, but it never writes type
   * metadata. Callers are expected to already know which serializer should handle the payload.
   */
  public void write(WriteContext writeContext, RefMode refMode, T value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    // noinspection Duplicates
    if (refMode == RefMode.TRACKING) {
      if (writeContext.writeRefOrNull(value)) {
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
    write(writeContext, value);
  }

  /**
   * Writes a non-null payload without ref/null flags or type metadata.
   *
   * <p>This is the core write hook implemented by subclasses.
   */
  public abstract void write(WriteContext writeContext, T value);

  /**
   * Reads a nullable value using the supplied ref/null policy.
   *
   * <p>This helper consumes ref or null flags according to {@code refMode}, but it never reads type
   * metadata. Callers are expected to already know which serializer should handle the payload.
   *
   * <p>When ref tracking is enabled, the returned instance is also registered with the active
   * {@link ReadContext}.
   */
  public T read(ReadContext readContext, RefMode refMode) {
    MemoryBuffer buffer = readContext.getBuffer();
    if (refMode == RefMode.TRACKING) {
      T obj;
      int nextReadRefId = readContext.tryPreserveRefId();
      if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
        obj = read(readContext);
        readContext.setReadRef(nextReadRefId, obj);
        return obj;
      } else {
        return (T) readContext.getReadRef();
      }
    } else if (refMode != RefMode.NULL_ONLY || buffer.readByte() != Fory.NULL_FLAG) {
      if (needToWriteRef) {
        // in normal case, the read implementation may invoke `readContext.reference` to
        // support circular reference, so we still need this `-1`
        readContext.preserveRefId(-1);
      }
      return read(readContext);
    }
    return null;
  }

  /**
   * Reads a non-null payload without consuming ref/null flags or type metadata.
   *
   * <p>This is the core read hook implemented by subclasses.
   */
  public abstract T read(ReadContext readContext);

  /**
   * Copies a value into the active {@link CopyContext}.
   *
   * <p>Immutable serializers return the original value by default. Mutable serializers that support
   * copy must override this method.
   *
   * @throws UnsupportedOperationException if copy is requested for a mutable serializer that does
   *     not implement it
   */
  public T copy(CopyContext copyContext, T value) {
    if (isImmutable()) {
      return value;
    }
    throw new UnsupportedOperationException(
        String.format("Copy for %s is not supported", value.getClass()));
  }

  /** Returns whether payload reads and writes participate in runtime ref tracking. */
  public final boolean needToWriteRef() {
    return needToWriteRef;
  }

  /** Returns whether {@link CopyContext} tracks origin-to-copy references for this serializer. */
  public final boolean needToCopyRef() {
    return needToCopyRef;
  }

  /** Returns the Java type handled by this serializer. */
  public Class<T> getType() {
    return type;
  }

  /** Returns whether values can be reused directly during copy operations. */
  public boolean isImmutable() {
    return immutable;
  }

  /**
   * Returns whether this serializer instance may be shared across equivalent runtimes.
   *
   * <p>The default is {@code false}, which means the serializer instance is runtime-local and must
   * only be used by the runtime that created it.
   *
   * <p>Returning {@code true} is a stronger contract than ordinary thread safety. A shareable
   * serializer must satisfy all the following:
   *
   * <ul>
   *   <li>It is safe for concurrent use by multiple threads.
   *   <li>It does not retain operation-local mutable state such as active {@link WriteContext},
   *       {@link ReadContext}, {@link CopyContext}, or temporary {@link MemoryBuffer} references.
   *   <li>It does not retain runtime-local mutable state whose behavior depends on a specific
   *       runtime instance, such as mutable resolver state tied to a particular class-loading or
   *       registration session.
   *   <li>Any retained fields are immutable, effectively immutable, or otherwise concurrency-safe
   *       and independent of the calling runtime.
   * </ul>
   *
   * <p>Override this only in concrete leaf serializers. Intermediate base classes should keep the
   * default to avoid accidentally widening sharing for subclasses that carry mutable state.
   *
   * <p>If in doubt, keep the default {@code false}.
   */
  public boolean shareable() {
    return false;
  }
}
