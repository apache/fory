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

package org.apache.fory.json.resolver;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.apache.fory.json.ForyJsonException;

/** Java 17 sealed-hierarchy discovery backed only by class-file schema metadata. */
final class SealedClassSupport {
  private SealedClassSupport() {}

  static Class<?>[] subtypes(Class<?> baseType) {
    if (!baseType.isSealed()) {
      throw new ForyJsonException(
          "Empty @JsonSubTypes requires a sealed Java type " + baseType.getName());
    }
    // PermittedSubclasses is trusted static schema metadata. JSON input never influences this
    // traversal and later selects only a logical name from the validated finite result.
    ArrayList<Class<?>> result = new ArrayList<>();
    Set<Class<?>> visited = Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
    collect(baseType, result, visited);
    return result.toArray(new Class<?>[0]);
  }

  private static void collect(
      Class<?> sealedType, ArrayList<Class<?>> result, Set<Class<?>> visited) {
    for (Class<?> subtype : sealedType.getPermittedSubclasses()) {
      if (!visited.add(subtype)) {
        continue;
      }
      int modifiers = subtype.getModifiers();
      boolean concrete = !subtype.isInterface() && !Modifier.isAbstract(modifiers);
      if (concrete) {
        result.add(subtype);
      }
      if (subtype.isSealed()) {
        collect(subtype, result, visited);
      } else if (!concrete) {
        throw new ForyJsonException(
            "Sealed JSON hierarchy has an open abstract branch " + subtype.getName());
      }
    }
  }
}
