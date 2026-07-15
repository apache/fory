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

package org.apache.fory.json.meta;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;

/** Generated direct invocation for a two-argument {@code JsonAnySetter} method. */
@Internal
public abstract class JsonAnySetterAccessor {
  protected JsonAnySetterAccessor() {}

  /** Returns the exact setter method represented by this operation. */
  public abstract Method setter();

  /** Invokes the setter with one dynamic JSON property. */
  public abstract void put(Object target, String name, Object value);

  /** Preserves ordinary any-setter failure semantics for generated direct calls. */
  protected static ForyJsonException accessException(Method method, Throwable throwable) {
    Throwable cause =
        throwable instanceof InvocationTargetException
            ? ((InvocationTargetException) throwable).getCause()
            : throwable;
    if (cause instanceof Error) {
      throw (Error) cause;
    }
    return new ForyJsonException("Cannot invoke @JsonAnySetter " + method, cause);
  }
}
