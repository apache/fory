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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/** Declared public Guava JSON type codecs and factories. */
public final class GuavaCodecs {
  private static final String IMMUTABLE_BI_MAP = "com.google.common.collect.ImmutableBiMap";
  private static final String IMMUTABLE_INT_ARRAY =
      "com.google.common.primitives.ImmutableIntArray";
  private static final String IMMUTABLE_LIST = "com.google.common.collect.ImmutableList";
  private static final String IMMUTABLE_MAP = "com.google.common.collect.ImmutableMap";
  private static final String IMMUTABLE_SET = "com.google.common.collect.ImmutableSet";
  private static final String IMMUTABLE_SORTED_MAP = "com.google.common.collect.ImmutableSortedMap";
  private static final String IMMUTABLE_SORTED_SET = "com.google.common.collect.ImmutableSortedSet";

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

  private GuavaCodecs() {}

  public static void registerExactCodecs(Map<Class<?>, JsonCodec<?>> exactCodecs) {
    if (IMMUTABLE_INT_ARRAY_AVAILABLE) {
      Direct.registerExactCodecs(exactCodecs);
    }
  }

  static boolean isUnsupportedImmutableImpl(Class<?> type) {
    String name = type.getName();
    return name.startsWith(GUAVA_COLLECT)
        && name.indexOf("Immutable") >= 0
        && !isSupportedImmutableCollection(type)
        && !isSupportedImmutableMap(type);
  }

  static CollectionCodec.CollectionFactory collectionFactory(Class<?> rawType) {
    if (!isSupportedImmutableCollection(rawType)) {
      return null;
    }
    return Direct.collectionFactory(rawType);
  }

  static MapCodec.MapFactory mapFactory(Class<?> rawType) {
    if (!isSupportedImmutableMap(rawType)) {
      return null;
    }
    return Direct.mapFactory(rawType);
  }

  static ForyJsonException conversionError(Class<?> type, Throwable cause) {
    return new ForyJsonException("Cannot create " + type.getName() + " from JSON", cause);
  }

  private static boolean isSupportedImmutableCollectionName(String name) {
    return name.equals(IMMUTABLE_LIST)
        || name.equals(IMMUTABLE_SET)
        || name.equals(IMMUTABLE_SORTED_SET);
  }

  private static boolean isSupportedImmutableMapName(String name) {
    return name.equals(IMMUTABLE_MAP)
        || name.equals(IMMUTABLE_BI_MAP)
        || name.equals(IMMUTABLE_SORTED_MAP);
  }

  private static boolean isSupportedImmutableCollection(Class<?> rawType) {
    return GUAVA_COLLECTIONS_AVAILABLE
        && isSupportedImmutableCollectionName(rawType.getName())
        && Direct.isSupportedImmutableCollection(rawType);
  }

  private static boolean isSupportedImmutableMap(Class<?> rawType) {
    return GUAVA_COLLECTIONS_AVAILABLE
        && isSupportedImmutableMapName(rawType.getName())
        && Direct.isSupportedImmutableMap(rawType);
  }

  private static boolean isClassAvailable(String className) {
    try {
      Class.forName(className, false, GuavaCodecs.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      return false;
    }
    return true;
  }

  // Keep direct Guava class descriptors in nested classes so ordinary no-Guava startup can
  // initialize the outer owner for class-name availability checks without loading Guava APIs.
  private static final class Direct {
    private Direct() {}

    private static void registerExactCodecs(Map<Class<?>, JsonCodec<?>> exactCodecs) {
      exactCodecs.put(ImmutableIntArray.class, ImmutableIntArrayCodec.INSTANCE);
    }

    private static CollectionCodec.CollectionFactory collectionFactory(Class<?> rawType) {
      if (!isSupportedImmutableCollection(rawType)) {
        return null;
      }
      return new CollectionCodec.CollectionFactory() {
        @Override
        public Collection<Object> newCollection() {
          return new ArrayList<>(0);
        }

        @Override
        public Collection<?> finish(Collection<Object> collection) {
          return copyImmutableCollection(rawType, collection);
        }
      };
    }

    private static MapCodec.MapFactory mapFactory(Class<?> rawType) {
      if (!isSupportedImmutableMap(rawType)) {
        return null;
      }
      return new MapCodec.MapFactory() {
        @Override
        public Map<Object, Object> newMap() {
          return new LinkedHashMap<>(0);
        }

        @Override
        public Map<?, ?> finish(Map<Object, Object> map) {
          return copyImmutableMap(rawType, map);
        }
      };
    }

    private static boolean isSupportedImmutableCollection(Class<?> rawType) {
      return rawType == ImmutableList.class
          || rawType == ImmutableSet.class
          || rawType == ImmutableSortedSet.class;
    }

    private static boolean isSupportedImmutableMap(Class<?> rawType) {
      return rawType == ImmutableMap.class
          || rawType == ImmutableBiMap.class
          || rawType == ImmutableSortedMap.class;
    }

    private static Collection<?> copyImmutableCollection(
        Class<?> rawType, Collection<Object> collection) {
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

    private static Map<?, ?> copyImmutableMap(Class<?> rawType, Map<Object, Object> map) {
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
  }

  private static final class ImmutableIntArrayCodec implements JsonCodec<ImmutableIntArray> {
    private static final ImmutableIntArrayCodec INSTANCE = new ImmutableIntArrayCodec();

    private ImmutableIntArrayCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, ImmutableIntArray value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      ImmutableIntArray values = value;
      int length = values.length();
      writer.writeArrayStart();
      for (int i = 0; i < length; i++) {
        writer.writeComma(i);
        writer.writeInt(values.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, ImmutableIntArray value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      ImmutableIntArray values = value;
      int length = values.length();
      writer.writeArrayStart();
      for (int i = 0; i < length; i++) {
        writer.writeComma(i);
        writer.writeInt(values.get(i));
      }
      writer.writeArrayEnd();
    }

    @Override
    public ImmutableIntArray readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : copyOf(readInts(reader));
    }

    @Override
    public ImmutableIntArray readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : copyOf(readInts(reader));
    }

    @Override
    public ImmutableIntArray readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : copyOf(readInts(reader));
    }

    private ImmutableIntArray copyOf(int[] values) {
      try {
        return ImmutableIntArray.copyOf(values);
      } catch (RuntimeException e) {
        throw conversionError(ImmutableIntArray.class, e);
      }
    }

    private static int[] readInts(Latin1JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new int[0];
      }
      int[] values = new int[8];
      int size = 0;
      do {
        if (reader.tryReadNull()) {
          throw new ForyJsonException("Cannot read null into ImmutableIntArray element");
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readInt();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    private static int[] readInts(Utf16JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new int[0];
      }
      int[] values = new int[8];
      int size = 0;
      do {
        if (reader.tryReadNull()) {
          throw new ForyJsonException("Cannot read null into ImmutableIntArray element");
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readInt();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }

    private static int[] readInts(Utf8JsonReader reader) {
      reader.enterDepth();
      reader.expect('[');
      if (reader.consume(']')) {
        reader.exitDepth();
        return new int[0];
      }
      int[] values = new int[8];
      int size = 0;
      do {
        if (reader.tryReadNull()) {
          throw new ForyJsonException("Cannot read null into ImmutableIntArray element");
        }
        if (size == values.length) {
          values = Arrays.copyOf(values, values.length << 1);
        }
        values[size++] = reader.readInt();
      } while (reader.consume(','));
      reader.expect(']');
      reader.exitDepth();
      return Arrays.copyOf(values, size);
    }
  }
}
