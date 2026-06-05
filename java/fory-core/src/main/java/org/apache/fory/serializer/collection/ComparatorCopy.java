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

package org.apache.fory.serializer.collection;

import java.util.Comparator;
import org.apache.fory.context.CopyContext;

final class ComparatorCopy {
  private static final Class<?> NATURAL_ORDER_CLASS = Comparator.naturalOrder().getClass();
  private static final Class<?> REVERSE_ORDER_CLASS = Comparator.reverseOrder().getClass();

  private ComparatorCopy() {}

  static Comparator copy(CopyContext copyContext, Comparator comparator) {
    if (comparator == null || isJdkSingleton(comparator)) {
      // Xlang copy is JVM-local; immutable JDK comparator singletons do not need a wire type.
      return comparator;
    }
    return copyContext.copyObject(comparator);
  }

  private static boolean isJdkSingleton(Comparator comparator) {
    Class<?> type = comparator.getClass();
    return type == NATURAL_ORDER_CLASS || type == REVERSE_ORDER_CLASS;
  }
}
