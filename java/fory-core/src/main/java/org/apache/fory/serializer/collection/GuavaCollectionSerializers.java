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

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Table;
import com.google.common.primitives.ImmutableIntArray;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;

/** Serializers for common guava types. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class GuavaCollectionSerializers {
  private static final String PKG = "com.google.common.collect";
  private static final String IMMUTABLE_BI_MAP_CLASS_NAME = PKG + ".ImmutableBiMap";
  private static final String IMMUTABLE_LIST_CLASS_NAME = PKG + ".ImmutableList";
  private static final String IMMUTABLE_MAP_CLASS_NAME = PKG + ".ImmutableMap";
  private static final String IMMUTABLE_MAP_FORM_CLASS_NAME =
      IMMUTABLE_MAP_CLASS_NAME + "$SerializedForm";
  private static final String IMMUTABLE_BI_MAP_FORM_CLASS_NAME =
      IMMUTABLE_BI_MAP_CLASS_NAME + "$SerializedForm";
  private static final String IMMUTABLE_SET_CLASS_NAME = PKG + ".ImmutableSet";
  private static final String IMMUTABLE_SORTED_MAP_CLASS_NAME = PKG + ".ImmutableSortedMap";
  private static final String IMMUTABLE_SORTED_SET_CLASS_NAME = PKG + ".ImmutableSortedSet";
  private static final String HASH_BASED_TABLE_CLASS_NAME = PKG + ".HashBasedTable";
  private static final String IMMUTABLE_INT_ARRAY_CLASS_NAME =
      "com.google.common.primitives.ImmutableIntArray";
  private static final int NUM_RESERVED_TYPE_IDS = 13;
  private static final boolean GUAVA_AVAILABLE =
      isClassAvailable(IMMUTABLE_BI_MAP_CLASS_NAME)
          && isClassAvailable(IMMUTABLE_LIST_CLASS_NAME)
          && isClassAvailable(IMMUTABLE_MAP_CLASS_NAME)
          && isClassAvailable(IMMUTABLE_SET_CLASS_NAME)
          && isClassAvailable(IMMUTABLE_SORTED_MAP_CLASS_NAME)
          && isClassAvailable(IMMUTABLE_SORTED_SET_CLASS_NAME);

  private interface MapEntryBuilder {
    void put(Object key, Object value);
  }

  abstract static class GuavaCollectionSerializer<T extends Collection>
      extends CollectionSerializer<T> {
    public GuavaCollectionSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, true);
      typeResolver.setSerializer(cls, this);
    }

    protected abstract T xnewInstance(Collection collection);
  }

  public static final class ImmutableListSerializer<T extends ImmutableList>
      extends GuavaCollectionSerializer<T> {
    public ImmutableListSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls);
    }

    @Override
    public Collection newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      return new CollectionContainer<>(numElements);
    }

    @Override
    public T onCollectionRead(Collection collection) {
      Object[] elements = ((CollectionContainer) collection).elements;
      ImmutableList list = ImmutableList.copyOf(elements);
      return (T) list;
    }

    public T xnewInstance(Collection collection) {
      return (T) ImmutableList.copyOf(collection);
    }

    @Override
    public T copy(CopyContext copyContext, T originCollection) {
      Object[] elements = new Object[originCollection.size()];
      copyElements(copyContext, originCollection, elements);
      return (T) ImmutableList.copyOf(elements);
    }
  }

  public static final class RegularImmutableListSerializer<T extends ImmutableList>
      extends GuavaCollectionSerializer<T> {
    public RegularImmutableListSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls);
    }

    @Override
    public Collection newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      return new CollectionContainer(numElements);
    }

    @Override
    public T onCollectionRead(Collection collection) {
      Object[] elements = ((CollectionContainer) collection).elements;
      return (T) ImmutableList.copyOf(elements);
    }

    @Override
    public T copy(CopyContext copyContext, T originCollection) {
      Object[] elements = new Object[originCollection.size()];
      copyElements(copyContext, originCollection, elements);
      return (T) ImmutableList.copyOf(elements);
    }

    @Override
    protected T xnewInstance(Collection collection) {
      return (T) ImmutableList.copyOf(collection);
    }
  }

  public static final class ImmutableSetSerializer<T extends ImmutableSet>
      extends GuavaCollectionSerializer<T> {

    public ImmutableSetSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls);
    }

    @Override
    public Collection newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      return new CollectionContainer<>(numElements);
    }

    @Override
    public T onCollectionRead(Collection collection) {
      Object[] elements = ((CollectionContainer) collection).elements;
      return (T) ImmutableSet.copyOf(elements);
    }

    @Override
    protected T xnewInstance(Collection collection) {
      return (T) ImmutableSet.copyOf(collection);
    }

    @Override
    public T copy(CopyContext copyContext, T originCollection) {
      Object[] elements = new Object[originCollection.size()];
      copyElements(copyContext, originCollection, elements);
      return (T) ImmutableSet.copyOf(elements);
    }
  }

  public static final class ImmutableSortedSetSerializer<T extends ImmutableSortedSet>
      extends CollectionSerializer<T> {
    public ImmutableSortedSetSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, false);
      typeResolver.setSerializer(cls, this);
    }

    @Override
    public Collection onCollectionWrite(WriteContext writeContext, T value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUInt32Small7(value.size());
      writeContext.writeRef(value.comparator());
      return value;
    }

    @Override
    public Collection newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      Comparator comparator = (Comparator) readContext.readRef();
      return new SortedCollectionContainer(comparator, numElements);
    }

    @Override
    public T onCollectionRead(Collection collection) {
      SortedCollectionContainer data = (SortedCollectionContainer) collection;
      Object[] elements = data.elements;
      return (T) new ImmutableSortedSet.Builder<>(data.comparator).add(elements).build();
    }

    @Override
    public T copy(CopyContext copyContext, T originCollection) {
      Comparator comparator = ComparatorCopy.copy(copyContext, originCollection.comparator());
      Object[] elements = new Object[originCollection.size()];
      copyElements(copyContext, originCollection, elements);
      return (T) new ImmutableSortedSet.Builder<>(comparator).add(elements).build();
    }
  }

  abstract static class GuavaMapSerializer<T extends Map> extends MapSerializer<T> {
    public GuavaMapSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, true);
      typeResolver.setSerializer(cls, this);
    }

    protected abstract ImmutableMap.Builder makeBuilder(int size);

    @Override
    public Map newMap(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readMapSize(buffer);
      setNumElements(numElements);
      return new MapContainer(numElements);
    }

    @Override
    public T copy(CopyContext copyContext, T originMap) {
      Builder builder = makeBuilder(originMap.size());
      GuavaCollectionSerializers.copyEntries(
          typeResolver, mapTypeCache(), copyContext, originMap, builder::put);
      return (T) builder.build();
    }

    @Override
    public T onMapRead(Map map) {
      MapContainer container = (MapContainer) map;
      int size = container.size;
      ImmutableMap.Builder builder = makeBuilder(size);
      Object[] keyArray = container.keyArray;
      Object[] valueArray = container.valueArray;
      for (int i = 0; i < size; i++) {
        builder.put(keyArray[i], valueArray[i]);
      }
      return (T) builder.build();
    }

    @Override
    public T read(ReadContext readContext) {
      int size = readMapSize(readContext.getBuffer());
      Map map = new HashMap();
      readElements(readContext, size, map);
      return xnewInstance(map);
    }

    protected abstract T xnewInstance(Map map);
  }

  private static volatile boolean immutableMapBuilderWithExpectedSizeAvailable = true;
  private static volatile boolean immutableBiMapBuilderWithExpectedSizeAvailable = true;

  private static ImmutableMap.Builder newImmutableMapBuilder(int size) {
    if (immutableMapBuilderWithExpectedSizeAvailable) {
      try {
        return ImmutableMap.builderWithExpectedSize(size);
      } catch (NoSuchMethodError e) {
        immutableMapBuilderWithExpectedSizeAvailable = false;
      }
    }
    return ImmutableMap.builder();
  }

  private static ImmutableBiMap.Builder newImmutableBiMapBuilder(int size) {
    if (immutableBiMapBuilderWithExpectedSizeAvailable) {
      try {
        return ImmutableBiMap.builderWithExpectedSize(size);
      } catch (NoSuchMethodError e) {
        immutableBiMapBuilderWithExpectedSizeAvailable = false;
      }
    }
    return ImmutableBiMap.builder();
  }

  public static boolean isGuavaAvailable() {
    return GUAVA_AVAILABLE;
  }

  public static int getNumReservedTypeIds() {
    return NUM_RESERVED_TYPE_IDS;
  }

  private static void copyEntries(
      TypeResolver typeResolver,
      MapLikeSerializer.MapTypeCache state,
      CopyContext copyContext,
      Map<?, ?> originMap,
      MapEntryBuilder builder) {
    for (Map.Entry<?, ?> entry : originMap.entrySet()) {
      Object key = entry.getKey();
      if (key != null) {
        TypeInfo typeInfo = typeResolver.getTypeInfo(key.getClass(), state.keyTypeInfoWriteCache);
        if (!typeInfo.getSerializer().isImmutable()) {
          key = copyContext.copyObject(key, typeInfo.getTypeId());
        }
      }
      Object value = entry.getValue();
      if (value != null) {
        TypeInfo typeInfo =
            typeResolver.getTypeInfo(value.getClass(), state.valueTypeInfoWriteCache);
        if (!typeInfo.getSerializer().isImmutable()) {
          value = copyContext.copyObject(value, typeInfo.getTypeId());
        }
      }
      builder.put(key, value);
    }
  }

  public static final class ImmutableMapSerializer<T extends ImmutableMap>
      extends GuavaMapSerializer<T> {

    public ImmutableMapSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls);
    }

    @Override
    protected ImmutableMap.Builder makeBuilder(int size) {
      return newImmutableMapBuilder(size);
    }

    @Override
    protected T xnewInstance(Map map) {
      return (T) ImmutableMap.copyOf(map);
    }
  }

  public static final class ImmutableBiMapSerializer<T extends ImmutableBiMap>
      extends GuavaMapSerializer<T> {

    public ImmutableBiMapSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls);
    }

    @Override
    protected ImmutableMap.Builder makeBuilder(int size) {
      return newImmutableBiMapBuilder(size);
    }

    @Override
    protected T xnewInstance(Map map) {
      return (T) ImmutableBiMap.copyOf(map);
    }
  }

  public abstract static class GuavaMapFormSerializer extends Serializer {
    private final Constructor<?> constructor;
    private final Method readResolveMethod;
    private final boolean biMap;

    public GuavaMapFormSerializer(TypeResolver typeResolver, Class<?> cls, boolean biMap) {
      super(typeResolver.getConfig(), cls);
      this.biMap = biMap;
      try {
        Class<?> mapClass = biMap ? ImmutableBiMap.class : ImmutableMap.class;
        constructor = cls.getDeclaredConstructor(mapClass);
        constructor.setAccessible(true);
        readResolveMethod = findReadResolve(cls);
        readResolveMethod.setAccessible(true);
      } catch (ReflectiveOperationException e) {
        throw new ForyException(
            "Failed to initialize Guava serialized-form serializer for " + cls, e);
      }
    }

    @Override
    public void write(WriteContext writeContext, Object value) {
      Map<?, ?> map = readFormMap(value);
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUInt32Small7(map.size());
      for (Entry<?, ?> entry : map.entrySet()) {
        writeContext.writeRef(entry.getKey());
        writeContext.writeRef(entry.getValue());
      }
    }

    @Override
    public Object read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int size = buffer.readVarUInt32Small7();
      ImmutableMap.Builder builder =
          biMap ? newImmutableBiMapBuilder(size) : newImmutableMapBuilder(size);
      for (int i = 0; i < size; i++) {
        builder.put(readContext.readRef(), readContext.readRef());
      }
      return builder.build();
    }

    @Override
    public Object copy(CopyContext copyContext, Object value) {
      Map<?, ?> map = readFormMap(value);
      ImmutableMap.Builder builder =
          biMap ? newImmutableBiMapBuilder(map.size()) : newImmutableMapBuilder(map.size());
      for (Entry<?, ?> entry : map.entrySet()) {
        Object key = entry.getKey();
        Object copyKey = key == null ? null : copyContext.copyObject(key);
        Object itemValue = entry.getValue();
        Object copyValue = itemValue == null ? null : copyContext.copyObject(itemValue);
        builder.put(copyKey, copyValue);
      }
      return newForm(builder.build());
    }

    private Map<?, ?> readFormMap(Object value) {
      try {
        return (Map<?, ?>) readResolveMethod.invoke(value);
      } catch (ReflectiveOperationException e) {
        throw new ForyException("Failed to resolve Guava serialized form " + type, e);
      }
    }

    private Object newForm(Map<?, ?> map) {
      try {
        Object guavaMap = biMap ? ImmutableBiMap.copyOf(map) : ImmutableMap.copyOf(map);
        return constructor.newInstance(guavaMap);
      } catch (ReflectiveOperationException e) {
        throw new ForyException("Failed to create Guava serialized form " + type, e);
      }
    }

    private static Method findReadResolve(Class<?> cls) throws NoSuchMethodException {
      Class<?> current = cls;
      while (current != null) {
        try {
          return current.getDeclaredMethod("readResolve");
        } catch (NoSuchMethodException e) {
          current = current.getSuperclass();
        }
      }
      throw new NoSuchMethodException(cls.getName() + ".readResolve()");
    }
  }

  public static final class ImmutableMapFormSerializer extends GuavaMapFormSerializer {
    public ImmutableMapFormSerializer(TypeResolver typeResolver, Class<?> cls) {
      super(typeResolver, cls, false);
    }
  }

  public static final class ImmutableBiMapFormSerializer extends GuavaMapFormSerializer {
    public ImmutableBiMapFormSerializer(TypeResolver typeResolver, Class<?> cls) {
      super(typeResolver, cls, true);
    }
  }

  public static final class ImmutableIntArraySerializer extends Serializer<ImmutableIntArray> {

    public ImmutableIntArraySerializer(TypeResolver typeResolver, Class<ImmutableIntArray> cls) {
      super(typeResolver.getConfig(), cls);
    }

    @Override
    public void write(WriteContext writeContext, ImmutableIntArray value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      int length = value.length();
      buffer.writeVarUInt32Small7(length);
      for (int i = 0; i < length; i++) {
        buffer.writeVarInt32(value.get(i));
      }
    }

    @Override
    public ImmutableIntArray read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int length = buffer.readVarUInt32Small7();
      int[] values = new int[length];
      for (int i = 0; i < length; i++) {
        values[i] = buffer.readVarInt32();
      }
      return ImmutableIntArray.copyOf(values);
    }

    @Override
    public ImmutableIntArray copy(CopyContext copyContext, ImmutableIntArray value) {
      return value;
    }
  }

  public static final class HashBasedTableSerializer extends Serializer<HashBasedTable> {

    public HashBasedTableSerializer(TypeResolver typeResolver, Class<HashBasedTable> cls) {
      super(typeResolver.getConfig(), cls);
      typeResolver.setSerializer(cls, this);
    }

    @Override
    public void write(WriteContext writeContext, HashBasedTable value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUInt32Small7(value.size());
      for (Table.Cell<?, ?, ?> cell : ((HashBasedTable<?, ?, ?>) value).cellSet()) {
        writeContext.writeRef(cell.getRowKey());
        writeContext.writeRef(cell.getColumnKey());
        writeContext.writeRef(cell.getValue());
      }
    }

    @Override
    public HashBasedTable read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int size = buffer.readVarUInt32Small7();
      HashBasedTable table = HashBasedTable.create();
      if (needToWriteRef) {
        readContext.setReadRef(readContext.lastPreservedRefId(), table);
      }
      for (int i = 0; i < size; i++) {
        table.put(readContext.readRef(), readContext.readRef(), readContext.readRef());
      }
      return table;
    }

    @Override
    public HashBasedTable copy(CopyContext copyContext, HashBasedTable value) {
      HashBasedTable table = HashBasedTable.create();
      if (needToCopyRef) {
        copyContext.reference(value, table);
      }
      for (Table.Cell<?, ?, ?> cell : ((HashBasedTable<?, ?, ?>) value).cellSet()) {
        table.put(
            copyContext.copyObject(cell.getRowKey()),
            copyContext.copyObject(cell.getColumnKey()),
            copyContext.copyObject(cell.getValue()));
      }
      return table;
    }
  }

  public static final class ImmutableSortedMapSerializer<T extends ImmutableSortedMap>
      extends MapSerializer<T> {

    public ImmutableSortedMapSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls);
      typeResolver.setSerializer(cls, this);
    }

    @Override
    public Map onMapWrite(WriteContext writeContext, T value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUInt32Small7(value.size());
      writeContext.writeRef(value.comparator());
      return value;
    }

    @Override
    public Map newMap(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readMapSize(buffer);
      setNumElements(numElements);
      Comparator comparator = (Comparator) readContext.readRef();
      return new SortedMapContainer<>(comparator, numElements);
    }

    @Override
    public T copy(CopyContext copyContext, T originMap) {
      Comparator comparator = ComparatorCopy.copy(copyContext, originMap.comparator());
      ImmutableSortedMap.Builder builder = new ImmutableSortedMap.Builder(comparator);
      copyEntries(typeResolver, mapTypeCache(), copyContext, originMap, builder::put);
      return (T) builder.build();
    }

    @Override
    public T onMapRead(Map map) {
      SortedMapContainer mapContainer = (SortedMapContainer) map;
      ImmutableMap.Builder builder = new ImmutableSortedMap.Builder(mapContainer.comparator);
      int size = mapContainer.size;
      Object[] keyArray = mapContainer.keyArray;
      Object[] valueArray = mapContainer.valueArray;
      for (int i = 0; i < size; i++) {
        builder.put(keyArray[i], valueArray[i]);
      }
      return (T) builder.build();
    }
  }

  // TODO guava serializers
  // guava/ArrayListMultimapSerializer - serializer for guava-libraries' ArrayListMultimap
  // guava/ArrayTableSerializer - serializer for guava-libraries' ArrayTable
  // guava/HashMultimapSerializer -- serializer for guava-libraries' HashMultimap
  // guava/ImmutableMultimapSerializer - serializer for guava-libraries' ImmutableMultimap
  // guava/ImmutableTableSerializer - serializer for guava-libraries' ImmutableTable
  // guava/LinkedHashMultimapSerializer - serializer for guava-libraries' LinkedHashMultimap
  // guava/LinkedListMultimapSerializer - serializer for guava-libraries' LinkedListMultimap
  // guava/ReverseListSerializer - serializer for guava-libraries' Lists.ReverseList / Lists.reverse
  // guava/TreeBasedTableSerializer - serializer for guava-libraries' TreeBasedTable
  // guava/TreeMultimapSerializer - serializer for guava-libraries' TreeMultimap
  // guava/UnmodifiableNavigableSetSerializer - serializer for guava-libraries'
  // UnmodifiableNavigableSet

  public static void registerDefaultSerializers(TypeResolver resolver) {
    if (!GUAVA_AVAILABLE) {
      throw new IllegalStateException("Guava classes are not available");
    }
    // Note: Guava common types are not public API, don't register by `ImmutableXXX.of()`,
    // since different guava version may return different type objects, which make class
    // registration
    // inconsistent if peers load different version of guava.
    // For example: guava 20 return ImmutableBiMap for ImmutableMap.of(), but guava 27 return
    // ImmutableMap.
    Class cls =
        loadClass(PKG + ".RegularImmutableBiMap", ImmutableBiMap.of("k1", 1, "k2", 4).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableBiMapSerializer(resolver, cls));
    cls = loadClass(PKG + ".SingletonImmutableBiMap", ImmutableBiMap.of(1, 2).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableBiMapSerializer(resolver, cls));
    cls = loadClass(PKG + ".RegularImmutableMap", ImmutableMap.of("k1", 1, "k2", 2).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableMapSerializer(resolver, cls));
    cls = loadClass(PKG + ".RegularImmutableList", ImmutableList.of().getClass());
    resolver.registerInternalSerializer(cls, new RegularImmutableListSerializer(resolver, cls));
    cls = loadClass(PKG + ".SingletonImmutableList", ImmutableList.of(1).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableListSerializer(resolver, cls));
    cls = loadClass(PKG + ".RegularImmutableSet", ImmutableSet.of(1, 2).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableSetSerializer(resolver, cls));
    cls = loadClass(PKG + ".SingletonImmutableSet", ImmutableSet.of(1).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableSetSerializer(resolver, cls));
    // sorted set/map doesn't support xlang.
    cls = loadClass(PKG + ".RegularImmutableSortedSet", ImmutableSortedSet.of(1, 2).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableSortedSetSerializer<>(resolver, cls));
    cls = loadClass(PKG + ".ImmutableSortedMap", ImmutableSortedMap.of(1, 2).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableSortedMapSerializer<>(resolver, cls));

    // Guava version before 19.0, of() return
    // EmptyImmutableSet/EmptyImmutableBiMap/EmptyImmutableSortedMap/EmptyImmutableSortedSet
    // we register if class exist or register empty to deserialize.
    if (checkClassExist(PKG + ".EmptyImmutableSet")) {
      cls = loadClass(PKG + ".EmptyImmutableSet", ImmutableSet.of().getClass());
      resolver.registerInternalSerializer(cls, new ImmutableSetSerializer(resolver, cls));
    } else {
      class GuavaEmptySet {}

      cls = GuavaEmptySet.class;
      resolver.registerInternalSerializer(cls, new ImmutableSetSerializer(resolver, cls));
    }
    if (checkClassExist(PKG + ".EmptyImmutableBiMap")) {
      cls = loadClass(PKG + ".EmptyImmutableBiMap", ImmutableBiMap.of().getClass());
      resolver.registerInternalSerializer(cls, new ImmutableMapSerializer(resolver, cls));
    } else {
      class GuavaEmptyBiMap {}

      cls = GuavaEmptyBiMap.class;
      resolver.registerInternalSerializer(cls, new ImmutableMapSerializer(resolver, cls));
    }
    if (checkClassExist(PKG + ".EmptyImmutableSortedSet")) {
      cls = loadClass(PKG + ".EmptyImmutableSortedSet", ImmutableSortedSet.of().getClass());
      resolver.registerInternalSerializer(cls, new ImmutableSortedSetSerializer(resolver, cls));
    } else {
      class GuavaEmptySortedSet {}

      cls = GuavaEmptySortedSet.class;
      resolver.registerInternalSerializer(cls, new ImmutableSortedSetSerializer(resolver, cls));
    }
    if (checkClassExist(PKG + ".EmptyImmutableSortedMap")) {
      cls = loadClass(PKG + ".EmptyImmutableSortedMap", ImmutableSortedMap.of().getClass());
      resolver.registerInternalSerializer(cls, new ImmutableSortedMapSerializer(resolver, cls));
    } else {
      class GuavaEmptySortedMap {}

      cls = GuavaEmptySortedMap.class;
      resolver.registerInternalSerializer(cls, new ImmutableSortedMapSerializer(resolver, cls));
    }
  }

  public static Class<? extends Serializer> getSerializerClass(Class<?> cls) {
    switch (cls.getName()) {
      case IMMUTABLE_INT_ARRAY_CLASS_NAME:
        return ImmutableIntArraySerializer.class;
      case IMMUTABLE_MAP_FORM_CLASS_NAME:
        return ImmutableMapFormSerializer.class;
      case IMMUTABLE_BI_MAP_FORM_CLASS_NAME:
        return ImmutableBiMapFormSerializer.class;
      case HASH_BASED_TABLE_CLASS_NAME:
        return HashBasedTableSerializer.class;
      default:
        return null;
    }
  }

  static Class<?> loadClass(String className, Class<?> cache) {
    if (cache.getName().equals(className)) {
      return cache;
    } else {
      try {
        return Class.forName(className, false, GuavaCollectionSerializers.class.getClassLoader());
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
    }
  }

  static boolean checkClassExist(String className) {
    return isClassAvailable(className);
  }

  private static boolean isClassAvailable(String className) {
    try {
      Class.forName(className, false, GuavaCollectionSerializers.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      return false;
    }
    return true;
  }
}
