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

package org.apache.fory.reflect;

import org.apache.fory.annotation.Internal;
import org.apache.fory.exception.ForyException;

/** JDK25 replacement for the JDK8-24 Unsafe-backed instantiator. */
@Internal
final class UnsafeObjectInstantiator<T> extends ObjectInstantiator<T> {
  UnsafeObjectInstantiator(Class<T> type) {
    super(type);
  }

  @Override
  public T newInstance() {
    throw unsupported(type);
  }

  @Override
  public T newInstanceWithArguments(Object... arguments) {
    throw unsupported(type);
  }

  private static ForyException unsupported(Class<?> type) {
    return new ForyException(
        "Unsafe allocation is unsupported for "
            + type
            + " in JDK25+ zero-Unsafe mode. Provide an accessible no-arg constructor, "
            + "use a record canonical constructor, or register a custom serializer.");
  }
}
