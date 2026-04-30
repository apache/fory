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

import java.io.Externalizable;
import java.lang.invoke.MethodHandle;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.apache.fory.collection.LazyMap;
import org.apache.fory.collection.MapSnapshot;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.ExternalizableSerializer;
import org.apache.fory.serializer.ReplaceResolveSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.util.Preconditions;

/**
 * Serializers for classes implements {@link Collection}. All map serializers must extends {@link
 * MapSerializer}.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class MapSerializers {

  public static final class HashMapSerializer extends MapSerializer<HashMap> {
    public HashMapSerializer(TypeResolver typeResolver) {
      super(typeResolver, HashMap.class, true);
    }

    @Override
    public HashMap newMap(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUInt32Small7();
      setNumElements(numElements);
      HashMap hashMap = new HashMap(numElements);
      readContext.reference(hashMap);
      return hashMap;
    }

    @Override
    public Map newMap(Map map) {
      return new HashMap(map.size());
    }
  }

  public static final class LinkedHashMapSerializer extends MapSerializer<LinkedHashMap> {
    public LinkedHashMapSerializer(TypeResolver typeResolver) {
      super(typeResolver, LinkedHashMap.class, true);
    }

    @Override
    public LinkedHashMap newMap(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUInt32Small7();
      setNumElements(numElements);
      LinkedHashMap hashMap = new LinkedHashMap(numElements);
      readContext.reference(hashMap);
      return hashMap;
    }

    @Override
    public Map newMap(Map map) {
      return new LinkedHashMap(map.size());
    }
  }

  public static final class LazyMapSerializer extends MapSerializer<LazyMap> {
    public LazyMapSerializer(TypeResolver typeResolver) {
      super(typeResolver, LazyMap.class, true);
    }

    @Override
    public LazyMap newMap(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUInt32Small7();
      setNumElements(numElements);
      LazyMap map = new LazyMap(numElements);
      readContext.reference(map);
      return map;
    }

    @Override
    public Map newMap(Map map) {
      return new LazyMap(map.size());
    }
  }

  public static class SortedMapSerializer<T extends SortedMap> extends MapSerializer<T> {
    private MethodHandle comparatorConstructor;
    private MethodHandle noArgConstructor;

    public SortedMapSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, true);
      if (cls != TreeMap.class) {
        try {
          comparatorConstructor = ReflectionUtils.getCtrHandle(cls, Comparator.class);
        } catch (Exception e) {
          // Subclass doesn't have a (Comparator) constructor, fall back to no-arg constructor.
          try {
            noArgConstructor = ReflectionUtils.getCtrHandle(cls);
          } catch (Exception e2) {
            throw new UnsupportedOperationException(
                "Class " + cls.getName() + " requires either a (Comparator) or no-arg constructor",
                e2);
          }
        }
      }
    }

    @Override
    public Map onMapWrite(WriteContext writeContext, T value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUInt32Small7(value.size());
      if (config.isXlang()) {
        return value;
      } else {
        writeContext.writeRef(value.comparator());
      }
      return value;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map newMap(ReadContext readContext) {
      assert !config.isXlang();
      MemoryBuffer buffer = readContext.getBuffer();
      setNumElements(buffer.readVarUInt32Small7());
      T map;
      Comparator comparator = (Comparator) readContext.readRef();
      if (type == TreeMap.class) {
        map = (T) new TreeMap(comparator);
      } else {
        try {
          if (comparatorConstructor != null) {
            map = (T) comparatorConstructor.invoke(comparator);
          } else {
            map = (T) noArgConstructor.invoke();
          }
        } catch (Throwable e) {
          throw new RuntimeException(e);
        }
      }
      readContext.reference(map);
      return map;
    }

    @Override
    public Map newMap(CopyContext copyContext, Map originMap) {
      Comparator comparator = copyContext.copyObject(((SortedMap) originMap).comparator());
      Map map;
      if (type == TreeMap.class) {
        map = new TreeMap(comparator);
      } else {
        try {
          if (comparatorConstructor != null) {
            map = (Map) comparatorConstructor.invoke(comparator);
          } else {
            map = (Map) noArgConstructor.invoke();
          }
        } catch (Throwable e) {
          throw new RuntimeException(e);
        }
      }
      return map;
    }
  }

  public static final class EmptyMapSerializer extends MapSerializer<Map<?, ?>> {

    public EmptyMapSerializer(TypeResolver typeResolver, Class<Map<?, ?>> cls) {
      super(typeResolver, cls, typeResolver.getConfig().isXlang(), true);
    }

    @Override
    public void write(WriteContext writeContext, Map<?, ?> value) {
      if (config.isXlang()) {
        super.write(writeContext, value);
      }
    }

    @Override
    public Map<?, ?> read(ReadContext readContext) {
      if (config.isXlang()) {
        throw new IllegalStateException();
      }
      return Collections.EMPTY_MAP;
    }
  }

  public static final class EmptySortedMapSerializer extends MapSerializer<SortedMap<?, ?>> {
    public EmptySortedMapSerializer(TypeResolver typeResolver, Class<SortedMap<?, ?>> cls) {
      super(typeResolver, cls, typeResolver.getConfig().isXlang(), true);
    }

    @Override
    public void write(WriteContext writeContext, SortedMap<?, ?> value) {}

    @Override
    public SortedMap<?, ?> read(ReadContext readContext) {
      return Collections.emptySortedMap();
    }
  }

  public static final class SingletonMapSerializer extends MapSerializer<Map<?, ?>> {

    public SingletonMapSerializer(TypeResolver typeResolver, Class<Map<?, ?>> cls) {
      super(typeResolver, cls, typeResolver.getConfig().isXlang());
    }

    @Override
    public Map<?, ?> copy(CopyContext copyContext, Map<?, ?> originMap) {
      Entry<?, ?> entry = originMap.entrySet().iterator().next();
      return Collections.singletonMap(
          copyContext.copyObject(entry.getKey()), copyContext.copyObject(entry.getValue()));
    }

    @Override
    public void write(WriteContext writeContext, Map<?, ?> value) {
      if (config.isXlang()) {
        super.write(writeContext, value);
      } else {
        Map.Entry entry = value.entrySet().iterator().next();
        writeContext.writeRef(entry.getKey());
        writeContext.writeRef(entry.getValue());
      }
    }

    @Override
    public Map<?, ?> read(ReadContext readContext) {
      if (config.isXlang()) {
        throw new UnsupportedOperationException();
      }
      Object key = readContext.readRef();
      Object value = readContext.readRef();
      return Collections.singletonMap(key, value);
    }
  }

  public static final class ConcurrentHashMapSerializer
      extends ConcurrentMapSerializer<ConcurrentHashMap> {
    public ConcurrentHashMapSerializer(TypeResolver typeResolver, Class<ConcurrentHashMap> type) {
      super(typeResolver, type, true);
    }

    @Override
    public ConcurrentHashMap newMap(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUInt32Small7();
      setNumElements(numElements);
      ConcurrentHashMap map = new ConcurrentHashMap(numElements);
      readContext.reference(map);
      return map;
    }

    @Override
    public Map newMap(Map map) {
      return new ConcurrentHashMap(map.size());
    }
  }

  public static final class ConcurrentSkipListMapSerializer
      extends ConcurrentMapSerializer<ConcurrentSkipListMap> {

    public ConcurrentSkipListMapSerializer(
        TypeResolver typeResolver, Class<ConcurrentSkipListMap> cls) {
      super(typeResolver, cls, true);
    }

    @Override
    public MapSnapshot onMapWrite(WriteContext writeContext, ConcurrentSkipListMap value) {
      MapSnapshot snapshot = super.onMapWrite(writeContext, value);
      if (config.isXlang()) {
        return snapshot;
      }
      writeContext.writeRef(value.comparator());
      return snapshot;
    }

    @Override
    public ConcurrentSkipListMap newMap(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUInt32Small7();
      setNumElements(numElements);
      Comparator comparator = (Comparator) readContext.readRef();
      ConcurrentSkipListMap map = new ConcurrentSkipListMap(comparator);
      readContext.reference(map);
      return map;
    }

    @Override
    public Map newMap(CopyContext copyContext, Map originMap) {
      Comparator comparator =
          copyContext.copyObject(((ConcurrentSkipListMap) originMap).comparator());
      return new ConcurrentSkipListMap(comparator);
    }
  }

  public static class EnumMapSerializer extends MapSerializer<EnumMap> {
    // Make offset compatible with graalvm native image.
    private static final long keyTypeFieldOffset;

    static {
      try {
        keyTypeFieldOffset = Platform.objectFieldOffset(EnumMap.class.getDeclaredField("keyType"));
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    }

    public EnumMapSerializer(TypeResolver typeResolver) {
      // getMapKeyValueType(EnumMap.class) will be `K, V` without Enum as key bound.
      // so no need to infer key generics in init.
      super(typeResolver, EnumMap.class, true);
    }

    @Override
    public Map onMapWrite(WriteContext writeContext, EnumMap value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUInt32Small7(value.size());
      Class keyType = (Class) Platform.getObject(value, keyTypeFieldOffset);
      ((ClassResolver) typeResolver).writeClassAndUpdateCache(writeContext, keyType);
      return value;
    }

    @Override
    public EnumMap newMap(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      setNumElements(buffer.readVarUInt32Small7());
      Class<?> keyType = typeResolver.readTypeInfo(readContext).getType();
      return new EnumMap(keyType);
    }

    @Override
    public EnumMap copy(CopyContext copyContext, EnumMap originMap) {
      return new EnumMap(originMap);
    }
  }

  public static class StringKeyMapSerializer<T> extends MapSerializer<Map<String, T>> {

    public StringKeyMapSerializer(TypeResolver typeResolver, Class<Map<String, T>> cls) {
      super(typeResolver, cls, true);
    }

    @Override
    protected <K, V> void copyEntry(
        CopyContext copyContext, Map<K, V> originMap, Map<K, V> newMap) {
      ClassResolver classResolver = (ClassResolver) typeResolver;
      MapTypeCache state = mapTypeCache();
      for (Entry<K, V> entry : originMap.entrySet()) {
        V value = entry.getValue();
        if (value != null) {
          TypeInfo typeInfo =
              classResolver.getTypeInfo(value.getClass(), state.valueTypeInfoWriteCache);
          if (!typeInfo.getSerializer().isImmutable()) {
            value = copyContext.copyObject(value, typeInfo.getTypeId());
          }
        }
        newMap.put(entry.getKey(), value);
      }
    }
  }

  /**
   * Java serializer to serialize all fields of a map implementation. Note that this serializer
   * won't use element generics and doesn't support JIT, performance won't be the best, but the
   * correctness can be ensured.
   */
  public static final class DefaultJavaMapSerializer<T> extends MapLikeSerializer<T> {
    private Serializer<T> dataSerializer;

    public DefaultJavaMapSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, false);
      Preconditions.checkArgument(
          !config.isXlang(),
          "Fory cross-language default map serializer should use " + MapSerializer.class);
      typeResolver.setSerializer(cls, this);
      Class<? extends Serializer> serializerClass =
          ((ClassResolver) typeResolver)
              .getObjectSerializerClass(
                  cls, sc -> dataSerializer = Serializers.newSerializer(typeResolver, cls, sc));
      dataSerializer = Serializers.newSerializer(typeResolver, cls, serializerClass);
      // No need to set object serializer to this, it will be set in class resolver later.
      // typeResolver.setSerializer(cls, this);
    }

    @Override
    public Map onMapWrite(WriteContext writeContext, T value) {
      throw new IllegalStateException();
    }

    @Override
    public T onMapCopy(Map map) {
      throw new IllegalStateException();
    }

    @Override
    public T onMapRead(Map map) {
      throw new IllegalStateException();
    }

    @Override
    public void write(WriteContext writeContext, T value) {
      dataSerializer.write(writeContext, value);
    }

    @Override
    public T copy(CopyContext copyContext, T value) {
      return copyContext.copyObject(value, dataSerializer);
    }

    @Override
    public T read(ReadContext readContext) {
      return dataSerializer.read(readContext);
    }
  }

  /** Map serializer for class with JDK custom serialization methods defined. */
  public static class JDKCompatibleMapSerializer<T> extends MapLikeSerializer<T> {
    private final Serializer serializer;

    public JDKCompatibleMapSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, false);
      // Map which defined `writeReplace` may use this serializer, so check replace/resolve
      // is necessary.
      Class<? extends Serializer> serializerType =
          ClassResolver.useReplaceResolveSerializer(cls)
              ? ReplaceResolveSerializer.class
              : Externalizable.class.isAssignableFrom(cls)
                  ? ExternalizableSerializer.class
                  : config.getDefaultJDKStreamSerializerType();
      serializer = Serializers.newSerializer(typeResolver, cls, serializerType);
    }

    @Override
    public Map onMapWrite(WriteContext writeContext, T value) {
      throw new IllegalStateException();
    }

    @Override
    public T onMapCopy(Map map) {
      throw new IllegalStateException();
    }

    @Override
    public T onMapRead(Map map) {
      throw new IllegalStateException();
    }

    @SuppressWarnings("unchecked")
    @Override
    public T read(ReadContext readContext) {
      return (T) serializer.read(readContext);
    }

    @Override
    public void write(WriteContext writeContext, T value) {
      serializer.write(writeContext, value);
    }

    @Override
    public T copy(CopyContext copyContext, T value) {
      return copyContext.copyObject(value, (Serializer<T>) serializer);
    }
  }

  public static class XlangMapSerializer extends MapLikeSerializer {

    public XlangMapSerializer(TypeResolver typeResolver, Class cls) {
      super(typeResolver, cls, true);
    }

    @Override
    public Map onMapWrite(WriteContext writeContext, Object value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      Map v = (Map) value;
      buffer.writeVarUInt32Small7(v.size());
      return v;
    }

    @Override
    public Object onMapCopy(Map map) {
      throw new IllegalStateException("should not be called");
    }

    public Map newMap(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUInt32Small7();
      setNumElements(numElements);
      HashMap<Object, Object> map = new HashMap<>(numElements);
      readContext.reference(map);
      return map;
    }

    @Override
    public Object onMapRead(Map map) {
      return map;
    }
  }

  // TODO(chaokunyang) support ConcurrentSkipListMap.SubMap mo efficiently.
  public static void registerDefaultSerializers(TypeResolver resolver) {
    resolver.registerInternalSerializer(HashMap.class, new HashMapSerializer(resolver));
    resolver.registerInternalSerializer(LinkedHashMap.class, new LinkedHashMapSerializer(resolver));
    resolver.registerInternalSerializer(
        TreeMap.class, new SortedMapSerializer<>(resolver, TreeMap.class));
    resolver.registerInternalSerializer(
        Collections.EMPTY_MAP.getClass(),
        new EmptyMapSerializer(resolver, (Class<Map<?, ?>>) Collections.EMPTY_MAP.getClass()));
    resolver.registerInternalSerializer(
        Collections.emptySortedMap().getClass(),
        new EmptySortedMapSerializer(
            resolver, (Class<SortedMap<?, ?>>) Collections.emptySortedMap().getClass()));
    resolver.registerInternalSerializer(
        Collections.singletonMap(null, null).getClass(),
        new SingletonMapSerializer(
            resolver, (Class<Map<?, ?>>) Collections.singletonMap(null, null).getClass()));
    resolver.registerInternalSerializer(
        ConcurrentHashMap.class,
        new ConcurrentHashMapSerializer(resolver, ConcurrentHashMap.class));
    resolver.registerInternalSerializer(
        ConcurrentSkipListMap.class,
        new ConcurrentSkipListMapSerializer(resolver, ConcurrentSkipListMap.class));
    resolver.registerInternalSerializer(EnumMap.class, new EnumMapSerializer(resolver));
    resolver.registerInternalSerializer(LazyMap.class, new LazyMapSerializer(resolver));
  }
}
