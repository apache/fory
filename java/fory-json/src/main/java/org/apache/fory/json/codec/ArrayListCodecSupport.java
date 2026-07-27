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

import java.util.ArrayList;
import org.apache.fory.annotation.Internal;

/**
 * Backing-array access for exact generated {@link ArrayList} codecs.
 *
 * <p>The Java 8 root implementation reports this optimization as unavailable. The JDK 9
 * multi-release implementation reads the backing array with a {@code VarHandle}. Availability is
 * queried only while generating source, so Java 8 and Android generated loops retain ordinary
 * {@link ArrayList#get(int)} calls without a hot-path runtime branch.
 */
@Internal
public final class ArrayListCodecSupport {
  private ArrayListCodecSupport() {}

  /** Returns whether the current runtime exposes the standard {@code ArrayList} backing array. */
  public static boolean isAvailable() {
    return false;
  }

  /** Returns the backing array of an exact {@link ArrayList}. */
  public static Object[] elements(ArrayList<?> list) {
    throw new UnsupportedOperationException("Direct ArrayList access is unavailable");
  }
}
