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

import org.apache.fory.Fory;
import org.apache.fory.util.ExceptionUtils;

/**
 * The projection codec for one historical layout of a schema-evolution codec. The generated classes
 * {@code C} are shared by every encoder of the codec and are generated once, on the first decode of
 * the layout by any encoder. Each encoder then binds its own codec {@code P} with {@link #compile}.
 */
abstract class ProjectionSource<C, P> {
  private volatile C classes;

  /** The failure of {@link #generate}, rethrown by every later compile. Guarded by {@code this}. */
  private Throwable failure;

  /**
   * Generates the projection classes for this layout. Runs at most once, unless it fails with a
   * {@link VirtualMachineError}.
   */
  abstract C generate();

  /** Creates one encoder's projection codec from the generated classes. */
  abstract P bind(C classes, Fory fory);

  final P compile(final Fory fory) {
    C c = classes;
    if (c == null) {
      synchronized (this) {
        c = classes;
        if (c == null) {
          if (failure != null) {
            throw ExceptionUtils.throwException(failure);
          }
          try {
            c = generate();
          } catch (Throwable t) {
            // Generation is deterministic, so a retry would rerun the code generator only to fail
            // again, on every decode of this layout. A VirtualMachineError such as an
            // OutOfMemoryError can be transient, so a later decode retries it.
            if (!(t instanceof VirtualMachineError)) {
              failure = t;
            }
            throw ExceptionUtils.throwException(t);
          }
          classes = c;
        }
      }
    }
    return bind(c, fory);
  }
}
