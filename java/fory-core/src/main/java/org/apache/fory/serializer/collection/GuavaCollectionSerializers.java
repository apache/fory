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

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.util.unsafe._JDKAccess;

/** Serializers for common guava types. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class GuavaCollectionSerializers {
  abstract static class GuavaCollectionSerializer<T extends Collection>
      extends CollectionSerializer<T> {
    public GuavaCollectionSerializer(Fory fory, Class<T> cls) {
      super(fory, cls, true);
      fory.getClassResolver().setSerializer(cls, this);
    }

    protected abstract T xnewInstance(Collection collection);
  }

  public static final class ImmutableListSerializer<T extends ImmutableList>
      extends GuavaCollectionSerializer<T> {
    public ImmutableListSerializer(Fory fory, Class<T> cls) {
      super(fory, cls);
      fory.getClassResolver().setSerializer(cls, this);
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
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
    public T copy(T originCollection) {
      Object[] elements = new Object[originCollection.size()];
      copyElements(originCollection, elements);
      return (T) ImmutableList.copyOf(elements);
    }
  }

  private static final String pkg = "com.google.common.collect";
  private static Function regularImmutableListInvokeCache;

  private static synchronized Function regularImmutableListInvoke() {
    if (regularImmutableListInvokeCache == null) {
      Class<?> cls = loadClass(pkg + ".RegularImmutableList", ImmutableList.of(1, 2).getClass());
      MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(cls);
      MethodHandle ctr = null;
      try {
        ctr = lookup.findConstructor(cls, MethodType.methodType(void.class, Object[].class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        Platform.throwException(e);
      }
      regularImmutableListInvokeCache = _JDKAccess.makeJDKFunction(lookup, ctr);
    }
    return regularImmutableListInvokeCache;
  }

  public static final class RegularImmutableListSerializer<T extends ImmutableList>
      extends GuavaCollectionSerializer<T> {
    private final Function<Object[], ImmutableList> function;

    public RegularImmutableListSerializer(Fory fory, Class<T> cls) {
      super(fory, cls);
      function = (Function<Object[], ImmutableList>) regularImmutableListInvoke();
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      return new CollectionContainer(numElements);
    }

    @Override
    public T onCollectionRead(Collection collection) {
      Object[] elements = ((CollectionContainer) collection).elements;
      return (T) function.apply(elements);
    }

    @Override
    public T copy(T originCollection) {
      Object[] elements = new Object[originCollection.size()];
      copyElements(originCollection, elements);
      return (T) function.apply(elements);
    }

    @Override
    protected T xnewInstance(Collection collection) {
      return (T) ImmutableList.copyOf(collection);
    }
  }

  public static final class ImmutableSetSerializer<T extends ImmutableSet>
      extends GuavaCollectionSerializer<T> {

    public ImmutableSetSerializer(Fory fory, Class<T> cls) {
      super(fory, cls);
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
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
    public T copy(T originCollection) {
      Object[] elements = new Object[originCollection.size()];
      copyElements(originCollection, elements);
      return (T) ImmutableSet.copyOf(elements);
    }
  }

  public static final class ImmutableSortedSetSerializer<T extends ImmutableSortedSet>
      extends CollectionSerializer<T> {
    public ImmutableSortedSetSerializer(Fory fory, Class<T> cls) {
      super(fory, cls, false);
      fory.getClassResolver().setSerializer(cls, this);
    }

    @Override
    public Collection onCollectionWrite(MemoryBuffer buffer, T value) {
      buffer.writeVarUint32Small7(value.size());
      fory.writeRef(buffer, value.comparator());
      return value;
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      Comparator comparator = (Comparator) fory.readRef(buffer);
      return new SortedCollectionContainer(comparator, numElements);
    }

    @Override
    public T onCollectionRead(Collection collection) {
      SortedCollectionContainer data = (SortedCollectionContainer) collection;
      Object[] elements = data.elements;
      return (T) new ImmutableSortedSet.Builder<>(data.comparator).add(elements).build();
    }

    @Override
    public T copy(T originCollection) {
      Comparator comparator = fory.copyObject(originCollection.comparator());
      Object[] elements = new Object[originCollection.size()];
      copyElements(originCollection, elements);
      return (T) new ImmutableSortedSet.Builder<>(comparator).add(elements).build();
    }
  }

  abstract static class GuavaMapSerializer<T extends Map> extends MapSerializer<T> {

    public GuavaMapSerializer(Fory fory, Class<T> cls) {
      super(fory, cls, true);
      fory.getClassResolver().setSerializer(cls, this);
    }

    protected abstract ImmutableMap.Builder makeBuilder(int size);

    @Override
    public Map newMap(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      return new MapContainer(numElements);
    }

    @Override
    public T copy(T originMap) {
      Builder builder = makeBuilder(originMap.size());
      copyEntry(originMap, builder);
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
    public T read(MemoryBuffer buffer) {
      int size = buffer.readVarUint32Small7();
      Map map = new HashMap();
      readElements(buffer, size, map);
      return xnewInstance(map);
    }

    protected abstract T xnewInstance(Map map);
  }

  private static final ClassValue<Function> builderCtrCache =
      new ClassValue<Function>() {
        @Override
        protected Function computeValue(Class<?> builderClass) {
          MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(builderClass);
          MethodHandle ctr = null;
          boolean lowVersionGuava = false;
          try {
            ctr =
                lookup.findConstructor(builderClass, MethodType.methodType(void.class, int.class));
          } catch (NoSuchMethodException | IllegalAccessException e) {
            try {
              ctr = lookup.findConstructor(builderClass, MethodType.methodType(void.class));
              lowVersionGuava = true;
            } catch (NoSuchMethodException | IllegalAccessException e2) {
              Platform.throwException(e);
            }
          }
          if (lowVersionGuava) {
            return _JDKAccess.makeJDKFunction(lookup, ctr, MethodType.methodType(Object.class));
          }
          return _JDKAccess.makeJDKFunction(lookup, ctr);
        }
      };

  public static final class ImmutableMapSerializer<T extends ImmutableMap>
      extends GuavaMapSerializer<T> {

    private final Function<Integer, ImmutableMap.Builder> builderCtr;

    public ImmutableMapSerializer(Fory fory, Class<T> cls) {
      super(fory, cls);
      builderCtr = builderCtrCache.get(ImmutableMap.Builder.class);
      fory.getClassResolver().setSerializer(cls, this);
    }

    @Override
    protected ImmutableMap.Builder makeBuilder(int size) {
      return builderCtr.apply(size);
    }

    @Override
    protected T xnewInstance(Map map) {
      return (T) ImmutableMap.copyOf(map);
    }
  }

  public static final class ImmutableBiMapSerializer<T extends ImmutableBiMap>
      extends GuavaMapSerializer<T> {
    private final Function<Integer, ImmutableBiMap.Builder> builderCtr;

    public ImmutableBiMapSerializer(Fory fory, Class<T> cls) {
      super(fory, cls);
      builderCtr = builderCtrCache.get(ImmutableBiMap.Builder.class);
      fory.getClassResolver().setSerializer(cls, this);
    }

    @Override
    protected ImmutableMap.Builder makeBuilder(int size) {
      return builderCtr.apply(size);
    }

    @Override
    protected T xnewInstance(Map map) {
      return (T) ImmutableBiMap.copyOf(map);
    }
  }

  public static final class ImmutableSortedMapSerializer<T extends ImmutableSortedMap>
      extends MapSerializer<T> {

    public ImmutableSortedMapSerializer(Fory fory, Class<T> cls) {
      super(fory, cls);
      fory.getClassResolver().setSerializer(cls, this);
    }

    @Override
    public Map onMapWrite(MemoryBuffer buffer, T value) {
      buffer.writeVarUint32Small7(value.size());
      fory.writeRef(buffer, value.comparator());
      return value;
    }

    @Override
    public Map newMap(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      Comparator comparator = (Comparator) fory.readRef(buffer);
      return new SortedMapContainer<>(comparator, numElements);
    }

    @Override
    public T copy(T originMap) {
      Comparator comparator = fory.copyObject(originMap.comparator());
      ImmutableSortedMap.Builder builder = new ImmutableSortedMap.Builder(comparator);
      copyEntry(originMap, builder);
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
  // guava/HashBasedTableSerializer - serializer for guava-libraries' HashBasedTable
  // guava/HashMultimapSerializer -- serializer for guava-libraries' HashMultimap
  // guava/ImmutableListSerializer - serializer for guava-libraries' ImmutableList
  // guava/ImmutableSetSerializer - serializer for guava-libraries' ImmutableSet
  // guava/ImmutableMapSerializer - serializer for guava-libraries' ImmutableMap
  // guava/ImmutableMultimapSerializer - serializer for guava-libraries' ImmutableMultimap
  // guava/ImmutableSortedSetSerializer - serializer for guava-libraries' ImmutableSortedSet
  // guava/ImmutableTableSerializer - serializer for guava-libraries' ImmutableTable
  // guava/LinkedHashMultimapSerializer - serializer for guava-libraries' LinkedHashMultimap
  // guava/LinkedListMultimapSerializer - serializer for guava-libraries' LinkedListMultimap
  // guava/ReverseListSerializer - serializer for guava-libraries' Lists.ReverseList / Lists.reverse
  // guava/TreeBasedTableSerializer - serializer for guava-libraries' TreeBasedTable
  // guava/TreeMultimapSerializer - serializer for guava-libraries' TreeMultimap
  // guava/UnmodifiableNavigableSetSerializer - serializer for guava-libraries'
  // UnmodifiableNavigableSet

  public static void registerDefaultSerializers(Fory fory) {
    // Note: Guava common types are not public API, don't register by `ImmutableXXX.of()`,
    // since different guava version may return different type objects, which make class
    // registration
    // inconsistent if peers load different version of guava.
    // For example: guava 20 return ImmutableBiMap for ImmutableMap.of(), but guava 27 return
    // ImmutableMap.
    TypeResolver resolver = fory.getTypeResolver();
    Class cls =
        loadClass(pkg + ".RegularImmutableBiMap", ImmutableBiMap.of("k1", 1, "k2", 4).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableBiMapSerializer(fory, cls));
    cls = loadClass(pkg + ".SingletonImmutableBiMap", ImmutableBiMap.of(1, 2).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableBiMapSerializer(fory, cls));
    cls = loadClass(pkg + ".RegularImmutableMap", ImmutableMap.of("k1", 1, "k2", 2).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableMapSerializer(fory, cls));
    cls = loadClass(pkg + ".RegularImmutableList", ImmutableList.of().getClass());
    resolver.registerInternalSerializer(cls, new RegularImmutableListSerializer(fory, cls));
    cls = loadClass(pkg + ".SingletonImmutableList", ImmutableList.of(1).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableListSerializer(fory, cls));
    cls = loadClass(pkg + ".RegularImmutableSet", ImmutableSet.of(1, 2).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableSetSerializer(fory, cls));
    cls = loadClass(pkg + ".SingletonImmutableSet", ImmutableSet.of(1).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableSetSerializer(fory, cls));
    // sorted set/map doesn't support xlang.
    cls = loadClass(pkg + ".RegularImmutableSortedSet", ImmutableSortedSet.of(1, 2).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableSortedSetSerializer<>(fory, cls));
    cls = loadClass(pkg + ".ImmutableSortedMap", ImmutableSortedMap.of(1, 2).getClass());
    resolver.registerInternalSerializer(cls, new ImmutableSortedMapSerializer<>(fory, cls));

    // Guava version before 19.0, of() return
    // EmptyImmutableSet/EmptyImmutableBiMap/EmptyImmutableSortedMap/EmptyImmutableSortedSet
    // we register if class exist or register empty to deserialize.
    if (checkClassExist(pkg + ".EmptyImmutableSet")) {
      cls = loadClass(pkg + ".EmptyImmutableSet", ImmutableSet.of().getClass());
      resolver.registerInternalSerializer(cls, new ImmutableSetSerializer(fory, cls));
    } else {
      class GuavaEmptySet {}

      cls = GuavaEmptySet.class;
      resolver.registerInternalSerializer(cls, new ImmutableSetSerializer(fory, cls));
    }
    if (checkClassExist(pkg + ".EmptyImmutableBiMap")) {
      cls = loadClass(pkg + ".EmptyImmutableBiMap", ImmutableBiMap.of().getClass());
      resolver.registerInternalSerializer(cls, new ImmutableMapSerializer(fory, cls));
    } else {
      class GuavaEmptyBiMap {}

      cls = GuavaEmptyBiMap.class;
      resolver.registerInternalSerializer(cls, new ImmutableMapSerializer(fory, cls));
    }
    if (checkClassExist(pkg + ".EmptyImmutableSortedSet")) {
      cls = loadClass(pkg + ".EmptyImmutableSortedSet", ImmutableSortedSet.of().getClass());
      resolver.registerInternalSerializer(cls, new ImmutableSortedSetSerializer(fory, cls));
    } else {
      class GuavaEmptySortedSet {}

      cls = GuavaEmptySortedSet.class;
      resolver.registerInternalSerializer(cls, new ImmutableSortedSetSerializer(fory, cls));
    }
    if (checkClassExist(pkg + ".EmptyImmutableSortedMap")) {
      cls = loadClass(pkg + ".EmptyImmutableSortedMap", ImmutableSortedMap.of().getClass());
      resolver.registerInternalSerializer(cls, new ImmutableSortedMapSerializer(fory, cls));
    } else {
      class GuavaEmptySortedMap {}

      cls = GuavaEmptySortedMap.class;
      resolver.registerInternalSerializer(cls, new ImmutableSortedMapSerializer(fory, cls));
    }
  }

  static Class<?> loadClass(String className, Class<?> cache) {
    if (cache.getName().equals(className)) {
      return cache;
    } else {
      try {
        return Class.forName(className);
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
    }
  }

  static boolean checkClassExist(String className) {
    try {
      Class.forName(className);
    } catch (ClassNotFoundException e) {
      return false;
    }
    return true;
  }
}
