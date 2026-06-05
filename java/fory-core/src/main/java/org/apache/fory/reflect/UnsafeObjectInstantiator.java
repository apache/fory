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
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.platform.internal._UnsafeUtils;
import sun.misc.Unsafe;

/** JDK8-24 Unsafe-backed instantiator for classes without an invocable constructor. */
@SuppressWarnings("unchecked")
@Internal
final class UnsafeObjectInstantiator<T> extends ObjectInstantiator<T> {
  private static final Unsafe UNSAFE = AndroidSupport.IS_ANDROID ? null : _UnsafeUtils.UNSAFE;
  private static final boolean UNSAFE_ALLOCATION_AVAILABLE =
      UNSAFE != null && JdkVersion.MAJOR_VERSION < 25;

  UnsafeObjectInstantiator(Class<T> type) {
    super(type);
  }

  @Override
  public T newInstance() {
    if (!UNSAFE_ALLOCATION_AVAILABLE) {
      throw unsupported(type);
    }
    try {
      return (T) UNSAFE.allocateInstance(type);
    } catch (InstantiationException e) {
      throw allocationFailed(type, e);
    }
  }

  @Override
  public T newInstanceWithArguments(Object... arguments) {
    throw new UnsupportedOperationException();
  }

  private static ForyException unsupported(Class<?> type) {
    return new ForyException(
        "Constructor-bypassing Unsafe allocation is unsupported in this runtime for " + type);
  }

  private static ForyException allocationFailed(Class<?> type, InstantiationException cause) {
    return new ForyException("Failed to allocate instance for " + type, cause);
  }
}
