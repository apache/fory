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

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.primitives.ImmutableIntArray;
import java.util.Collection;
import java.util.Map;
import org.apache.fory.json.ForyJsonException;

/** Optional Guava public-API support used by Fory JSON codecs. */
final class GuavaJsonSupport {
  static final String IMMUTABLE_BI_MAP = "com.google.common.collect.ImmutableBiMap";
  static final String IMMUTABLE_INT_ARRAY = "com.google.common.primitives.ImmutableIntArray";
  static final String IMMUTABLE_LIST = "com.google.common.collect.ImmutableList";
  static final String IMMUTABLE_MAP = "com.google.common.collect.ImmutableMap";
  static final String IMMUTABLE_SET = "com.google.common.collect.ImmutableSet";
  static final String IMMUTABLE_SORTED_MAP = "com.google.common.collect.ImmutableSortedMap";
  static final String IMMUTABLE_SORTED_SET = "com.google.common.collect.ImmutableSortedSet";

  private static final String GUAVA_COLLECT = "com.google.common.collect.";
  private static final boolean GUAVA_COLLECTIONS_AVAILABLE =
      isClassAvailable(IMMUTABLE_BI_MAP)
          && isClassAvailable(IMMUTABLE_LIST)
          && isClassAvailable(IMMUTABLE_MAP)
          && isClassAvailable(IMMUTABLE_SET)
          && isClassAvailable(IMMUTABLE_SORTED_MAP)
          && isClassAvailable(IMMUTABLE_SORTED_SET);
  private static final boolean IMMUTABLE_INT_ARRAY_AVAILABLE =
      isClassAvailable(IMMUTABLE_INT_ARRAY);

  private GuavaJsonSupport() {}

  static boolean isImmutableIntArrayAvailable() {
    return IMMUTABLE_INT_ARRAY_AVAILABLE;
  }

  static boolean isImmutableCollection(Class<?> type) {
    if (!GUAVA_COLLECTIONS_AVAILABLE) {
      return false;
    }
    return type == ImmutableList.class
        || type == ImmutableSet.class
        || type == ImmutableSortedSet.class;
  }

  static boolean isImmutableMap(Class<?> type) {
    if (!GUAVA_COLLECTIONS_AVAILABLE) {
      return false;
    }
    return type == ImmutableMap.class
        || type == ImmutableBiMap.class
        || type == ImmutableSortedMap.class;
  }

  static boolean isUnsupportedImmutableImpl(Class<?> type) {
    String name = type.getName();
    return name.startsWith(GUAVA_COLLECT)
        && name.indexOf("Immutable") >= 0
        && !isImmutableCollection(type)
        && !isImmutableMap(type);
  }

  static Class<?> immutableIntArrayType() {
    return ImmutableIntArray.class;
  }

  static Object copyImmutableCollection(Class<?> rawType, Collection<Object> collection) {
    try {
      if (rawType == ImmutableList.class) {
        return ImmutableList.copyOf(collection);
      }
      if (rawType == ImmutableSet.class) {
        return ImmutableSet.copyOf(collection);
      }
      if (rawType == ImmutableSortedSet.class) {
        return ImmutableSortedSet.copyOf(collection);
      }
    } catch (RuntimeException e) {
      throw conversionError(rawType, e);
    }
    throw new ForyJsonException("Unsupported JSON collection type " + rawType);
  }

  static Object copyImmutableMap(Class<?> rawType, Map<Object, Object> map) {
    try {
      if (rawType == ImmutableMap.class) {
        return ImmutableMap.copyOf(map);
      }
      if (rawType == ImmutableBiMap.class) {
        return ImmutableBiMap.copyOf(map);
      }
      if (rawType == ImmutableSortedMap.class) {
        return ImmutableSortedMap.copyOf(map);
      }
    } catch (RuntimeException e) {
      throw conversionError(rawType, e);
    }
    throw new ForyJsonException("Unsupported JSON map type " + rawType);
  }

  static Object copyImmutableIntArray(int[] values) {
    try {
      return ImmutableIntArray.copyOf(values);
    } catch (RuntimeException e) {
      throw conversionError(ImmutableIntArray.class, e);
    }
  }

  static int immutableIntArrayLength(Object value) {
    return ((ImmutableIntArray) value).length();
  }

  static int immutableIntArrayGet(Object value, int index) {
    return ((ImmutableIntArray) value).get(index);
  }

  static ForyJsonException conversionError(Class<?> type, Throwable cause) {
    return new ForyJsonException("Cannot create " + type.getName() + " from JSON", cause);
  }

  private static boolean isClassAvailable(String className) {
    try {
      Class.forName(className, false, GuavaJsonSupport.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      return false;
    }
    return true;
  }
}
