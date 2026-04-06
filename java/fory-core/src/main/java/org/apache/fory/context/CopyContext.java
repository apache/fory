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

package org.apache.fory.context;

import java.util.Arrays;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.type.Types;

/**
 * Per-operation state for object graph copy.
 *
 * <p>{@code CopyContext} keeps the runtime services needed by serializer copy paths and optionally
 * tracks origin-to-copy identity mappings so mutable cyclic graphs can be copied correctly.
 *
 * <p>The context is owned by a single top-level copy operation and must be {@link #reset()} before
 * reuse.
 */
@SuppressWarnings("unchecked")
public final class CopyContext {
  private final TypeResolver typeResolver;
  private final boolean copyRefTracking;
  private final IdentityMap<Object, Object> originToCopyMap;

  /**
   * Creates a copy context for one runtime.
   *
   * @param typeResolver resolver used to discover serializers and type ids during copy
   * @param copyRefTracking whether mutable origin-to-copy references should be tracked
   */
  public CopyContext(TypeResolver typeResolver, boolean copyRefTracking) {
    this.typeResolver = typeResolver;
    this.copyRefTracking = copyRefTracking;
    originToCopyMap = new IdentityMap<>(2);
  }

  /** Clears all per-operation state so this context can be reused for another copy. */
  public void reset() {
    if (copyRefTracking) {
      originToCopyMap.clear();
    }
  }

  /** Returns the resolver used for serializer lookup during copy. */
  public TypeResolver getTypeResolver() {
    return typeResolver;
  }

  /** Returns whether origin-to-copy identity tracking is enabled for this context. */
  public boolean copyTrackingRef() {
    return copyRefTracking;
  }

  /**
   * Registers a newly created copy for an origin object.
   *
   * <p>Call this method immediately after creating a composite object such as an array, collection,
   * map, or bean so cyclic graphs can resolve back-references to the new copy.
   *
   * @param origin original object in the source graph
   * @param copied newly created copy in the destination graph
   */
  public <T> void reference(T origin, T copied) {
    if (copyRefTracking && origin != null) {
      originToCopyMap.put(origin, copied);
    }
  }

  /** Returns the previously registered copy for {@code origin}, or {@code null} if absent. */
  public <T> T getCopyObject(T origin) {
    return (T) originToCopyMap.get(origin);
  }

  /**
   * Copies an object by resolving its runtime type and dispatching to the corresponding serializer.
   *
   * <p>Primitive values, boxed primitives, strings, and common primitive arrays use specialized
   * fast paths before falling back to serializer dispatch.
   */
  public <T> T copyObject(T obj) {
    if (obj == null) {
      return null;
    }
    TypeInfo typeInfo = typeResolver.getTypeInfo(obj.getClass(), true);
    int typeId = typeInfo.getTypeId();
    switch (typeId) {
      case Types.BOOL:
      case Types.INT8:
      case ClassResolver.CHAR_ID:
      case Types.INT16:
      case Types.INT32:
      case Types.FLOAT32:
      case Types.INT64:
      case Types.FLOAT64:
        return obj;
      case Types.STRING:
        if (typeInfo.getType() == String.class) {
          return obj;
        }
        return copyObject(obj, typeInfo.getSerializer());
      case ClassResolver.PRIMITIVE_BOOLEAN_ARRAY_ID:
        return (T) Arrays.copyOf((boolean[]) obj, ((boolean[]) obj).length);
      case ClassResolver.PRIMITIVE_BYTE_ARRAY_ID:
        return (T) Arrays.copyOf((byte[]) obj, ((byte[]) obj).length);
      case ClassResolver.PRIMITIVE_CHAR_ARRAY_ID:
        return (T) Arrays.copyOf((char[]) obj, ((char[]) obj).length);
      case ClassResolver.PRIMITIVE_SHORT_ARRAY_ID:
        return (T) Arrays.copyOf((short[]) obj, ((short[]) obj).length);
      case ClassResolver.PRIMITIVE_INT_ARRAY_ID:
        return (T) Arrays.copyOf((int[]) obj, ((int[]) obj).length);
      case ClassResolver.PRIMITIVE_FLOAT_ARRAY_ID:
        return (T) Arrays.copyOf((float[]) obj, ((float[]) obj).length);
      case ClassResolver.PRIMITIVE_LONG_ARRAY_ID:
        return (T) Arrays.copyOf((long[]) obj, ((long[]) obj).length);
      case ClassResolver.PRIMITIVE_DOUBLE_ARRAY_ID:
        return (T) Arrays.copyOf((double[]) obj, ((double[]) obj).length);
      case ClassResolver.STRING_ARRAY_ID:
        return (T) Arrays.copyOf((String[]) obj, ((String[]) obj).length);
      default:
        return copyObject(obj, typeInfo.getSerializer());
    }
  }

  /**
   * Copies an object when the caller already knows the runtime type id.
   *
   * <p>This avoids a second type-id lookup for callers that already have that information in hand.
   */
  public <T> T copyObject(T obj, int classId) {
    if (obj == null) {
      return null;
    }
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOL_ID:
      case ClassResolver.PRIMITIVE_INT8_ID:
      case ClassResolver.PRIMITIVE_CHAR_ID:
      case ClassResolver.PRIMITIVE_INT16_ID:
      case ClassResolver.PRIMITIVE_INT32_ID:
      case ClassResolver.PRIMITIVE_FLOAT32_ID:
      case ClassResolver.PRIMITIVE_INT64_ID:
      case ClassResolver.PRIMITIVE_FLOAT64_ID:
      case Types.BOOL:
      case Types.INT8:
      case ClassResolver.CHAR_ID:
      case Types.INT16:
      case Types.INT32:
      case Types.FLOAT32:
      case Types.INT64:
      case Types.FLOAT64:
        return obj;
      case Types.STRING:
        if (obj.getClass() == String.class) {
          return obj;
        }
        return copyObject(obj, typeResolver.getTypeInfo(obj.getClass(), true).getSerializer());
      default:
        return copyObject(obj, typeResolver.getTypeInfo(obj.getClass(), true).getSerializer());
    }
  }

  /**
   * Copies an object using a specific serializer.
   *
   * <p>If the serializer participates in copy ref tracking, this method consults the current
   * origin-to-copy map first and records the new copy before returning it.
   */
  public <T> T copyObject(T obj, Serializer<T> serializer) {
    if (serializer.needToCopyRef()) {
      T existing = getCopyObject(obj);
      if (existing != null) {
        return existing;
      }
      T copied = serializer.copy(this, obj);
      originToCopyMap.put(obj, copied);
      return copied;
    }
    return serializer.copy(this, obj);
  }
}
