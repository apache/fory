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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.apache.fory.annotation.Internal;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.type.TypeUtils;

/** Portable lower-bound graph-memory estimates for shallow Java owner objects. */
@Internal
public final class GraphMemoryEstimates {
  public static final int REFERENCE_BYTES = 4;
  private static final int OBJECT_BASE_BYTES = 2 * REFERENCE_BYTES;
  private static final int ARRAY_LENGTH_BYTES = TypeUtils.getSizeOfPrimitiveType(int.class);

  private GraphMemoryEstimates() {}

  /**
   * Returns a portable lower-bound shallow owner estimate, not exact JVM heap layout. Primitive
   * fields use their Java storage width and reference fields use Fory's settled 4-byte fallback.
   */
  public static int shallowObjectBytes(Class<?> type) {
    int bytes = OBJECT_BASE_BYTES;
    for (Field field : ReflectionUtils.getFields(type, true)) {
      if (!Modifier.isStatic(field.getModifiers())) {
        bytes = Math.addExact(bytes, fieldBytes(field.getType()));
      }
    }
    return bytes;
  }

  public static int objectArrayBytes() {
    return Math.addExact(OBJECT_BASE_BYTES, ARRAY_LENGTH_BYTES);
  }

  private static int fieldBytes(Class<?> fieldType) {
    return fieldType.isPrimitive() ? TypeUtils.getSizeOfPrimitiveType(fieldType) : REFERENCE_BYTES;
  }
}
