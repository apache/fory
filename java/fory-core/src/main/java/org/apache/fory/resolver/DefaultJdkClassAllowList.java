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

package org.apache.fory.resolver;

import java.util.HashSet;
import java.util.Set;

/** JDK interface names which can be loaded without explicit class registration. */
final class DefaultJdkClassAllowList {
  private static final Set<String> CLASS_NAMES = new HashSet<>();

  static {
    CLASS_NAMES.add("java.io.Serializable");
    CLASS_NAMES.add("java.lang.Iterable");
    CLASS_NAMES.add("java.util.Iterator");
    CLASS_NAMES.add("java.util.ListIterator");
    CLASS_NAMES.add("java.util.Collection");
    CLASS_NAMES.add("java.util.List");
    CLASS_NAMES.add("java.util.Set");
    CLASS_NAMES.add("java.util.Map");
    CLASS_NAMES.add("java.util.Map$Entry");
    CLASS_NAMES.add("java.util.Queue");
    CLASS_NAMES.add("java.util.Deque");
    CLASS_NAMES.add("java.util.SortedSet");
    CLASS_NAMES.add("java.util.NavigableSet");
    CLASS_NAMES.add("java.util.SortedMap");
    CLASS_NAMES.add("java.util.NavigableMap");
    CLASS_NAMES.add("java.util.Comparator");
    CLASS_NAMES.add("java.util.Enumeration");
    CLASS_NAMES.add("java.util.PrimitiveIterator");
    CLASS_NAMES.add("java.util.PrimitiveIterator$OfDouble");
    CLASS_NAMES.add("java.util.PrimitiveIterator$OfInt");
    CLASS_NAMES.add("java.util.PrimitiveIterator$OfLong");
    CLASS_NAMES.add("java.util.RandomAccess");
    CLASS_NAMES.add("java.util.Spliterator");
    CLASS_NAMES.add("java.util.Spliterator$OfPrimitive");
    CLASS_NAMES.add("java.util.Spliterator$OfDouble");
    CLASS_NAMES.add("java.util.Spliterator$OfInt");
    CLASS_NAMES.add("java.util.Spliterator$OfLong");
    CLASS_NAMES.add("java.util.concurrent.BlockingDeque");
    CLASS_NAMES.add("java.util.concurrent.BlockingQueue");
    CLASS_NAMES.add("java.util.concurrent.ConcurrentMap");
    CLASS_NAMES.add("java.util.concurrent.ConcurrentNavigableMap");
    CLASS_NAMES.add("java.util.concurrent.TransferQueue");
    CLASS_NAMES.add("java.util.stream.BaseStream");
    CLASS_NAMES.add("java.util.stream.Stream");
    CLASS_NAMES.add("java.util.stream.DoubleStream");
    CLASS_NAMES.add("java.util.stream.IntStream");
    CLASS_NAMES.add("java.util.stream.LongStream");
    CLASS_NAMES.add("java.util.stream.Collector");
  }

  private DefaultJdkClassAllowList() {}

  static boolean contains(String className) {
    return CLASS_NAMES.contains(className) || className.startsWith("java.util.function.");
  }
}
