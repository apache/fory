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

package org.apache.fory.json.codec;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import org.apache.fory.annotation.Internal;
import org.apache.fory.platform.internal._JDKAccess;

/**
 * JDK 9 backing-array access for exact generated {@link ArrayList} codecs.
 *
 * <p>A generated loop first proves {@code value.getClass() == ArrayList.class} and snapshots the
 * list size. Reading the backing array once then removes the mutable {@code size} and {@code
 * elementData} reloads that {@link ArrayList#get(int)} must retain across an arbitrary element
 * writer call. As with every serializer traversal, the input graph must not be mutated while it is
 * being written.
 *
 * <p>Availability is queried only while generating source. Unsupported runtimes keep the ordinary
 * {@code ArrayList.get} source shape, so generated hot loops contain neither an availability branch
 * nor reflective dispatch. The element access itself is a plain {@link VarHandle} read; do not
 * replace it with {@code Unsafe}.
 */
@Internal
public final class ArrayListCodecSupport {
  private static final VarHandle ELEMENTS = loadElements();

  private ArrayListCodecSupport() {}

  /** Returns whether the current runtime exposes the standard {@code ArrayList} backing array. */
  public static boolean isAvailable() {
    return ELEMENTS != null;
  }

  /** Returns the backing array of an exact {@link ArrayList}. */
  public static Object[] elements(ArrayList<?> list) {
    return (Object[]) ELEMENTS.get(list);
  }

  private static VarHandle loadElements() {
    try {
      return _JDKAccess._trustedLookup(ArrayList.class)
          .findVarHandle(ArrayList.class, "elementData", Object[].class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      return null;
    }
  }
}
