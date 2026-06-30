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

package org.apache.fory.collection.selflist;

import java.util.Collection;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.collection.CollectionLikeSerializer;
import org.apache.fory.type.TypeUtils;

/**
 * A {@link CollectionLikeSerializer} adapter for types that implement {@link Collection} but whose
 * wire format is a plain object, not a collection.
 *
 * <p>This serializer satisfies the {@code instanceof CollectionLikeSerializer} check in JIT codec
 * generation while delegating actual read/write to a lazily-created {@link ObjectSerializer} that
 * handles the object wire format.
 *
 * <p>Typical use case: a POJO that implements {@code List} (e.g. a self-referential collection like
 * {@code Box<T> implements List<Box<?>>}). The POJO should be serialized as an object (with its
 * fields), not as a collection (with size + elements).
 *
 * <p><b>Usage via {@link org.apache.fory.serializer.SerializerFactory}:</b>
 *
 * <pre>{@code
 * foryBuilder.withSerializerFactory(new SelfRefCollectionSerializerFactory());
 * }</pre>
 *
 * <p><b>Important:</b> This adapter only works when the JIT codec is <em>not</em> generating
 * codegen-hook code for the collection. When {@link #supportCodegenHook()} returns {@code false},
 * the JIT codec calls {@link #write(WriteContext, Object)} and {@link #read(ReadContext)} directly,
 * bypassing the collection read/write code generation path.
 *
 * @param <T> the type to serialize (must implement {@link Collection})
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class SingleItemListSerializer<T extends Collection<?>> extends CollectionLikeSerializer<T> {

  private final TypeResolver typeResolver;
  private volatile Serializer<T> delegateSerializer;

  /**
   * Creates a new SingleItemListSerializer with a lazily-created delegate.
   *
   * @param typeResolver the type resolver
   * @param cls the class to serialize
   */
  public SingleItemListSerializer(TypeResolver typeResolver, Class<T> cls) {
    super(typeResolver, cls, false); // supportCodegenHook = false
    this.typeResolver = typeResolver;
  }

  // ==================== delegate ====================

  private Serializer<T> getDelegate() {
    if (delegateSerializer == null) {
      synchronized (this) {
        if (delegateSerializer == null) {
          delegateSerializer = new ObjectSerializer<>(typeResolver, (Class<T>) type);
        }
      }
    }
    return delegateSerializer;
  }

  // ==================== write ====================

  /**
   * Delegates to the real serializer.
   *
   * <p>Called by JIT codec when {@code supportCodegenHook} is false:
   *
   * <pre>
   *   new Invoke(serializer, "write", writeContext, collection)
   * </pre>
   */
  @Override
  public void write(WriteContext writeContext, T value) {
    if (!writeContext.writeRefOrNull(value)) {
      getDelegate().write(writeContext, value);
    }
  }

  // ==================== read ====================

  /**
   * Delegates to the real serializer.
   *
   * <p>Called by JIT codec when {@code supportCodegenHook} is false:
   *
   * <pre>
   *   read(serializer, buffer, OBJECT_TYPE) → serializer.read(readContext)
   * </pre>
   */
  @Override
  public T read(ReadContext readContext) {
    return getDelegate().read(readContext);
  }

  // ==================== codegen hook methods (not used when supportCodegenHook=false)
  // ====================

  /** Not invoked when {@code supportCodegenHook} is false. */
  @Override
  public Collection onCollectionWrite(WriteContext writeContext, T value) {
    return (Collection) value;
  }

  /** Not invoked when {@code supportCodegenHook} is false. */
  @Override
  public T onCollectionRead(Collection collection) {
    return (T) collection;
  }

  // ==================== copy ====================

  @Override
  public T copy(CopyContext copyContext, T original) {
    return getDelegate().copy(copyContext, original);
  }

  // ==================== static helpers ====================

  /**
   * Detects whether a class is a self-referential collection: a POJO that implements {@code
   * Collection<X>} where {@code X} is the class itself.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code Box<T> implements List<Box<?>>} → true
   *   <li>{@code ArrayList<String>} → false
   *   <li>{@code ArrayList<ArrayList<String>>} → false (nested, not self-ref)
   * </ul>
   */
  public static boolean isSelfRefCollection(Class<?> cls) {
    if (!Collection.class.isAssignableFrom(cls)) {
      return false;
    }
    if (TypeUtils.isPrimitiveListClass(cls)) {
      return false;
    }
    try {
      TypeRef<?> typeRef = TypeRef.of(cls);
      TypeRef<?> elementType = TypeUtils.getElementType(typeRef);
      return elementType.getRawType() == cls;
    } catch (Exception e) {
      return false;
    }
  }
}
