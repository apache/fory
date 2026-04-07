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

package org.apache.fory;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.fory.annotation.Internal;
import org.apache.fory.resolver.TypeChecker;
import org.apache.fory.serializer.SerializerFactory;

/**
 * Thread-safe serializer interface. {@link Fory} is not thread-safe; implementations of this
 * interface are.
 *
 * <p>The runtime class loader is fixed when the thread-safe serializer is built. If you need a
 * different class loader, build a different {@link ThreadSafeFory} instance.
 */
public interface ThreadSafeFory extends BaseFory {

  /**
   * Provide a context to execution operations on {@link Fory} directly and return the executed
   * result.
   */
  <R> R execute(Function<Fory, R> action);

  /** Deserialize <code>obj</code> from a {@link ByteBuffer}. */
  Object deserialize(ByteBuffer byteBuffer);

  /**
   * Set TypeChecker of serializer for current thread only.
   *
   * @param typeChecker {@link TypeChecker} for type checking
   */
  void setTypeChecker(TypeChecker typeChecker);

  /**
   * Set tSerializerFactory of serializer for current thread only.
   *
   * @param serializerFactory {@link SerializerFactory} for serializerFactory
   */
  void setSerializerFactory(SerializerFactory serializerFactory);

  @Internal
  void registerCallback(Consumer<Fory> callback);
}
