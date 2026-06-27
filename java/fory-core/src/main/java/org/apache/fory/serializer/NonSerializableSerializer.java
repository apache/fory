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

import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.resolver.TypeResolver;

/**
 * Serializer for non-Serializable JDK classes (e.g. {@code java.lang.Package}).
 *
 * <p>Binary serialization remains unsupported — {@link #write} and {@link #read} throw
 * {@link UnsupportedOperationException}, preserving the prior behavior of {@code ClassResolver}'s
 * JDK-serializable guard. However {@code copy} is supported via the field copy via
 * {@link AbstractObjectSerializer}, so {@code Fory.copy()} can handle object graphs
 * that transitively contain such classes (issue #2941).
 */
public final class NonSerializableSerializer<T> extends AbstractObjectSerializer<T> {
  public NonSerializableSerializer(TypeResolver typeResolver, Class<T> type) {
      super(typeResolver, type);
  }

  @Override
  public void write(WriteContext writeContext, T value) {
      throw new UnsupportedOperationException(
              String.format("Class %s doesn't support serialization.", type.getName()));
  }

  @Override
  public T read(ReadContext readContext) {
      throw new UnsupportedOperationException(
              String.format("Class %s doesn't support serialization.", type.getName()));
  }
}
