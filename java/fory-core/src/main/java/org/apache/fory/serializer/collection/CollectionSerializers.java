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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.fory.Fory;
import org.apache.fory.collection.CollectionSnapshot;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.ReplaceResolveSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.unsafe._JDKAccess;

/**
 * Serializers for classes implements {@link Collection}. All collection serializers should extend
 * {@link CollectionSerializer}.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class CollectionSerializers {

  public static final class ArrayListSerializer extends CollectionSerializer<ArrayList> {
    public ArrayListSerializer(Fory fory) {
      super(fory, ArrayList.class, true);
    }

    @Override
    public ArrayList newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      ArrayList arrayList = new ArrayList(numElements);
      fory.getRefResolver().reference(arrayList);
      return arrayList;
    }
  }

  public static final class ArraysAsListSerializer extends CollectionSerializer<List<?>> {
    // Make offset compatible with graalvm native image.
    private static final long arrayFieldOffset;

    static {
      try {
        Field arrayField = Class.forName("java.util.Arrays$ArrayList").getDeclaredField("a");
        arrayFieldOffset = Platform.objectFieldOffset(arrayField);
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    }

    public ArraysAsListSerializer(Fory fory, Class<List<?>> cls) {
      super(fory, cls, fory.isCrossLanguage());
    }

    @Override
    public List<?> copy(List<?> originCollection) {
      Object[] elements = new Object[originCollection.size()];
      List<?> newCollection = Arrays.asList(elements);
      if (needToCopyRef) {
        fory.reference(originCollection, newCollection);
      }
      copyElements(originCollection, elements);
      return newCollection;
    }

    @Override
    public void write(MemoryBuffer buffer, List<?> value) {
      try {
        final Object[] array = (Object[]) Platform.getObject(value, arrayFieldOffset);
        fory.writeRef(buffer, array);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public List<?> read(MemoryBuffer buffer) {
      final Object[] array = (Object[]) fory.readRef(buffer);
      Preconditions.checkNotNull(array);
      return Arrays.asList(array);
    }

    @Override
    public void xwrite(MemoryBuffer buffer, List<?> value) {
      super.write(buffer, value);
    }

    @Override
    public List<?> xread(MemoryBuffer buffer) {
      return super.read(buffer);
    }

    @Override
    public ArrayList newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      ArrayList arrayList = new ArrayList(numElements);
      fory.getRefResolver().reference(arrayList);
      return arrayList;
    }
  }

  public static final class HashSetSerializer extends CollectionSerializer<HashSet> {
    public HashSetSerializer(Fory fory) {
      super(fory, HashSet.class, true);
    }

    @Override
    public HashSet newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      HashSet hashSet = new HashSet(numElements);
      fory.getRefResolver().reference(hashSet);
      return hashSet;
    }
  }

  public static final class LinkedHashSetSerializer extends CollectionSerializer<LinkedHashSet> {
    public LinkedHashSetSerializer(Fory fory) {
      super(fory, LinkedHashSet.class, true);
    }

    @Override
    public LinkedHashSet newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      LinkedHashSet hashSet = new LinkedHashSet(numElements);
      fory.getRefResolver().reference(hashSet);
      return hashSet;
    }
  }

  public static class SortedSetSerializer<T extends SortedSet> extends CollectionSerializer<T> {
    private MethodHandle comparatorConstructor;
    private MethodHandle noArgConstructor;

    public SortedSetSerializer(Fory fory, Class<T> cls) {
      super(fory, cls, true);
      if (cls != TreeSet.class) {
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
    public Collection onCollectionWrite(MemoryBuffer buffer, T value) {
      buffer.writeVarUint32Small7(value.size());
      if (!fory.isCrossLanguage()) {
        fory.writeRef(buffer, value.comparator());
      }
      return value;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T newCollection(MemoryBuffer buffer) {
      assert !fory.isCrossLanguage();
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      T collection;
      Comparator comparator = (Comparator) fory.readRef(buffer);
      if (type == TreeSet.class) {
        collection = (T) new TreeSet(comparator);
      } else {
        try {
          if (comparatorConstructor != null) {
            collection = (T) comparatorConstructor.invoke(comparator);
          } else {
            collection = (T) noArgConstructor.invoke();
          }
        } catch (Throwable e) {
          throw new RuntimeException(e);
        }
      }
      fory.getRefResolver().reference(collection);
      return collection;
    }

    @Override
    public Collection newCollection(Collection originCollection) {
      Collection collection;
      Comparator comparator = fory.copyObject(((SortedSet) originCollection).comparator());
      if (Objects.equals(type, TreeSet.class)) {
        collection = new TreeSet(comparator);
      } else {
        try {
          if (comparatorConstructor != null) {
            collection = (T) comparatorConstructor.invoke(comparator);
          } else {
            collection = (T) noArgConstructor.invoke();
          }
        } catch (Throwable e) {
          throw new RuntimeException(e);
        }
      }
      return collection;
    }
  }

  // ------------------------------ collections serializers ------------------------------ //
  // For cross-language serialization, if the data is passed from python, the data will be
  // deserialized by `MapSerializers` and `CollectionSerializers`.
  // But if the data is serialized by following collections serializers, we need to ensure the real
  // type of `xread` is the same as the type when serializing.
  public static final class EmptyListSerializer extends CollectionSerializer<List<?>> {

    public EmptyListSerializer(Fory fory, Class<List<?>> cls) {
      super(fory, cls, false, true);
    }

    @Override
    public void write(MemoryBuffer buffer, List<?> value) {}

    @Override
    public void xwrite(MemoryBuffer buffer, List<?> value) {
      // write length
      buffer.writeVarUint32Small7(0);
    }

    @Override
    public List<?> read(MemoryBuffer buffer) {
      return Collections.EMPTY_LIST;
    }

    @Override
    public List<?> xread(MemoryBuffer buffer) {
      buffer.readVarUint32Small7();
      return Collections.EMPTY_LIST;
    }
  }

  public static class CopyOnWriteArrayListSerializer
      extends ConcurrentCollectionSerializer<CopyOnWriteArrayList> {

    public CopyOnWriteArrayListSerializer(Fory fory, Class<CopyOnWriteArrayList> type) {
      super(fory, type, true);
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      return new CollectionContainer<>(numElements);
    }

    @Override
    public CopyOnWriteArrayList onCollectionRead(Collection collection) {
      Object[] elements = ((CollectionContainer) collection).elements;
      return new CopyOnWriteArrayList(elements);
    }

    @Override
    public CopyOnWriteArrayList copy(CopyOnWriteArrayList originCollection) {
      CopyOnWriteArrayList newCollection = new CopyOnWriteArrayList();
      if (needToCopyRef) {
        fory.reference(originCollection, newCollection);
      }
      List copyList = new ArrayList(originCollection.size());
      copyElements(originCollection, copyList);
      newCollection.addAll(copyList);
      return newCollection;
    }
  }

  public static class CopyOnWriteArraySetSerializer
      extends ConcurrentCollectionSerializer<CopyOnWriteArraySet> {

    public CopyOnWriteArraySetSerializer(Fory fory, Class<CopyOnWriteArraySet> type) {
      super(fory, type, true);
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      return new CollectionContainer<>(numElements);
    }

    @Override
    public CopyOnWriteArraySet onCollectionRead(Collection collection) {
      Object[] elements = ((CollectionContainer) collection).elements;
      return new CopyOnWriteArraySet(Arrays.asList(elements));
    }

    @Override
    public CopyOnWriteArraySet copy(CopyOnWriteArraySet originCollection) {
      CopyOnWriteArraySet newCollection = new CopyOnWriteArraySet();
      if (needToCopyRef) {
        fory.reference(originCollection, newCollection);
      }
      List copyList = new ArrayList(originCollection.size());
      copyElements(originCollection, copyList);
      newCollection.addAll(copyList);
      return newCollection;
    }
  }

  public static final class EmptySetSerializer extends CollectionSerializer<Set<?>> {

    public EmptySetSerializer(Fory fory, Class<Set<?>> cls) {
      super(fory, cls, fory.isCrossLanguage(), true);
    }

    @Override
    public void write(MemoryBuffer buffer, Set<?> value) {}

    @Override
    public void xwrite(MemoryBuffer buffer, Set<?> value) {
      super.write(buffer, value);
    }

    @Override
    public Set<?> read(MemoryBuffer buffer) {
      return Collections.EMPTY_SET;
    }

    @Override
    public Set<?> xread(MemoryBuffer buffer) {
      throw new UnsupportedOperationException();
    }
  }

  public static final class EmptySortedSetSerializer extends CollectionSerializer<SortedSet<?>> {

    public EmptySortedSetSerializer(Fory fory, Class<SortedSet<?>> cls) {
      super(fory, cls, fory.isCrossLanguage(), true);
    }

    @Override
    public void write(MemoryBuffer buffer, SortedSet<?> value) {}

    @Override
    public SortedSet<?> read(MemoryBuffer buffer) {
      return Collections.emptySortedSet();
    }

    @Override
    public void xwrite(MemoryBuffer buffer, SortedSet<?> value) {
      super.write(buffer, value);
    }

    @Override
    public SortedSet<?> xread(MemoryBuffer buffer) {
      throw new UnsupportedOperationException();
    }
  }

  public static final class CollectionsSingletonListSerializer
      extends CollectionSerializer<List<?>> {

    public CollectionsSingletonListSerializer(Fory fory, Class<List<?>> cls) {
      super(fory, cls, fory.isCrossLanguage());
    }

    @Override
    public List<?> copy(List<?> originCollection) {
      return Collections.singletonList(fory.copyObject(originCollection.get(0)));
    }

    @Override
    public void write(MemoryBuffer buffer, List<?> value) {
      fory.writeRef(buffer, value.get(0));
    }

    @Override
    public void xwrite(MemoryBuffer buffer, List<?> value) {
      super.write(buffer, value);
    }

    @Override
    public List<?> read(MemoryBuffer buffer) {
      return Collections.singletonList(fory.readRef(buffer));
    }

    @Override
    public List<?> xread(MemoryBuffer buffer) {
      throw new UnsupportedOperationException();
    }
  }

  public static final class CollectionsSingletonSetSerializer extends CollectionSerializer<Set<?>> {

    public CollectionsSingletonSetSerializer(Fory fory, Class<Set<?>> cls) {
      super(fory, cls, fory.isCrossLanguage());
    }

    @Override
    public Set<?> copy(Set<?> originCollection) {
      return Collections.singleton(fory.copyObject(originCollection.iterator().next()));
    }

    @Override
    public void write(MemoryBuffer buffer, Set<?> value) {
      fory.writeRef(buffer, value.iterator().next());
    }

    @Override
    public void xwrite(MemoryBuffer buffer, Set<?> value) {
      super.write(buffer, value);
    }

    @Override
    public Set<?> read(MemoryBuffer buffer) {
      return Collections.singleton(fory.readRef(buffer));
    }

    @Override
    public Set<?> xread(MemoryBuffer buffer) {
      throw new UnsupportedOperationException();
    }
  }

  public static final class ConcurrentSkipListSetSerializer
      extends ConcurrentCollectionSerializer<ConcurrentSkipListSet> {

    public ConcurrentSkipListSetSerializer(Fory fory, Class<ConcurrentSkipListSet> cls) {
      super(fory, cls, true);
    }

    @Override
    public CollectionSnapshot onCollectionWrite(MemoryBuffer buffer, ConcurrentSkipListSet value) {
      CollectionSnapshot snapshot = super.onCollectionWrite(buffer, value);
      if (!fory.isCrossLanguage()) {
        fory.writeRef(buffer, value.comparator());
      }
      return snapshot;
    }

    @Override
    public ConcurrentSkipListSet newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      assert !fory.isCrossLanguage();
      RefResolver refResolver = fory.getRefResolver();
      int refId = refResolver.lastPreservedRefId();
      // It's possible that comparator/elements has circular ref to set.
      Comparator comparator = (Comparator) fory.readRef(buffer);
      ConcurrentSkipListSet skipListSet = new ConcurrentSkipListSet(comparator);
      refResolver.setReadObject(refId, skipListSet);
      return skipListSet;
    }
  }

  public static final class SetFromMapSerializer extends CollectionSerializer<Set<?>> {

    private static final long MAP_FIELD_OFFSET;
    private static final List EMPTY_COLLECTION_STUB = new ArrayList<>();

    private static final MethodHandle m;

    private static final MethodHandle s;

    static {
      try {
        Class<?> type = Class.forName("java.util.Collections$SetFromMap");
        Field mapField = type.getDeclaredField("m");
        MAP_FIELD_OFFSET = Platform.objectFieldOffset(mapField);
        MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(type);
        m = lookup.findSetter(type, "m", Map.class);
        s = lookup.findSetter(type, "s", Set.class);
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    }

    public SetFromMapSerializer(Fory fory, Class<Set<?>> type) {
      super(fory, type, true);
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      final TypeInfo mapTypeInfo = fory.getClassResolver().readTypeInfo(buffer);
      final MapLikeSerializer mapSerializer = (MapLikeSerializer) mapTypeInfo.getSerializer();
      RefResolver refResolver = fory.getRefResolver();
      // It's possible that elements or nested fields has circular ref to set.
      int refId = refResolver.lastPreservedRefId();
      Set set;
      if (buffer.readBoolean()) {
        refResolver.preserveRefId();
        set = Collections.newSetFromMap(mapSerializer.newMap(buffer));
        setNumElements(mapSerializer.getAndClearNumElements());
      } else {
        Map map = (Map) mapSerializer.read(buffer);
        try {
          set = Platform.newInstance(type);
          m.invoke(set, map);
          s.invoke(set, map.keySet());
        } catch (Throwable e) {
          throw new RuntimeException(e);
        }
        setNumElements(0);
      }
      refResolver.setReadObject(refId, set);
      return set;
    }

    @Override
    public Collection newCollection(Collection originCollection) {
      assert !fory.isCrossLanguage();
      Map<?, Boolean> map =
          (Map<?, Boolean>) Platform.getObject(originCollection, MAP_FIELD_OFFSET);
      MapLikeSerializer mapSerializer =
          (MapLikeSerializer) fory.getClassResolver().getSerializer(map.getClass());
      Map newMap = mapSerializer.newMap(map);
      return Collections.newSetFromMap(newMap);
    }

    @Override
    public Collection onCollectionWrite(MemoryBuffer buffer, Set<?> value) {
      final Map<?, Boolean> map = (Map<?, Boolean>) Platform.getObject(value, MAP_FIELD_OFFSET);
      final TypeInfo typeInfo = fory.getClassResolver().getTypeInfo(map.getClass());
      MapLikeSerializer mapSerializer = (MapLikeSerializer) typeInfo.getSerializer();
      fory.getClassResolver().writeTypeInfo(buffer, typeInfo);
      if (mapSerializer.supportCodegenHook) {
        buffer.writeBoolean(true);
        mapSerializer.onMapWrite(buffer, map);
        return value;
      } else {
        buffer.writeBoolean(false);
        mapSerializer.write(buffer, map);
        return EMPTY_COLLECTION_STUB;
      }
    }
  }

  public static final class ConcurrentHashMapKeySetViewSerializer
      extends CollectionSerializer<ConcurrentHashMap.KeySetView> {
    private final TypeInfoHolder mapTypeInfoHolder;
    private final TypeInfoHolder valueTypeInfoHolder;

    public ConcurrentHashMapKeySetViewSerializer(
        Fory fory, Class<ConcurrentHashMap.KeySetView> type) {
      super(fory, type, false);
      mapTypeInfoHolder = fory.getClassResolver().nilTypeInfoHolder();
      valueTypeInfoHolder = fory.getClassResolver().nilTypeInfoHolder();
    }

    @Override
    public void write(MemoryBuffer buffer, ConcurrentHashMap.KeySetView value) {
      fory.writeRef(buffer, value.getMap(), mapTypeInfoHolder);
      fory.writeRef(buffer, value.getMappedValue(), valueTypeInfoHolder);
    }

    @Override
    public ConcurrentHashMap.KeySetView read(MemoryBuffer buffer) {
      ConcurrentHashMap map = (ConcurrentHashMap) fory.readRef(buffer, mapTypeInfoHolder);
      Object value = fory.readRef(buffer, valueTypeInfoHolder);
      return map.keySet(value);
    }

    @Override
    public ConcurrentHashMap.KeySetView copy(ConcurrentHashMap.KeySetView value) {
      ConcurrentHashMap newMap = fory.copyObject(value.getMap());
      return newMap.keySet(fory.copyObject(value.getMappedValue()));
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      throw new IllegalStateException(
          "Should not be invoked since we set supportCodegenHook to false");
    }
  }

  public static final class VectorSerializer extends CollectionSerializer<Vector> {

    public VectorSerializer(Fory fory, Class<Vector> cls) {
      super(fory, cls, true);
    }

    @Override
    public Vector newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      Vector<Object> vector = new Vector<>(numElements);
      fory.getRefResolver().reference(vector);
      return vector;
    }
  }

  public static final class ArrayDequeSerializer extends CollectionSerializer<ArrayDeque> {

    public ArrayDequeSerializer(Fory fory, Class<ArrayDeque> cls) {
      super(fory, cls, true);
    }

    @Override
    public ArrayDeque newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      ArrayDeque deque = new ArrayDeque(numElements);
      fory.getRefResolver().reference(deque);
      return deque;
    }
  }

  public static class EnumSetSerializer extends CollectionSerializer<EnumSet> {
    public EnumSetSerializer(Fory fory, Class<EnumSet> type) {
      // getElementType(EnumSet.class) will be `E` without Enum as bound.
      // so no need to infer generics in init.
      super(fory, type, false);
    }

    @Override
    public void write(MemoryBuffer buffer, EnumSet object) {
      Class<?> elemClass;
      if (object.isEmpty()) {
        EnumSet tmp = EnumSet.complementOf(object);
        if (tmp.isEmpty()) {
          throw new ForyException("An EnumSet must have a defined Enum to be serialized.");
        }
        elemClass = tmp.iterator().next().getClass();
      } else {
        elemClass = object.iterator().next().getClass();
      }
      if (!elemClass.isEnum()) {
        elemClass = elemClass.getEnclosingClass();
      }
      fory.getClassResolver().writeClassAndUpdateCache(buffer, elemClass);
      Serializer serializer = fory.getClassResolver().getSerializer(elemClass);
      buffer.writeVarUint32Small7(object.size());
      for (Object element : object) {
        serializer.write(buffer, element);
      }
    }

    @Override
    public EnumSet read(MemoryBuffer buffer) {
      Class elemClass = fory.getClassResolver().readTypeInfo(buffer).getCls();
      EnumSet object = EnumSet.noneOf(elemClass);
      Serializer elemSerializer = fory.getClassResolver().getSerializer(elemClass);
      int length = buffer.readVarUint32Small7();
      for (int i = 0; i < length; i++) {
        object.add(elemSerializer.read(buffer));
      }
      return object;
    }

    @Override
    public EnumSet copy(EnumSet originCollection) {
      return EnumSet.copyOf(originCollection);
    }
  }

  public static class BitSetSerializer extends Serializer<BitSet> {
    public BitSetSerializer(Fory fory, Class<BitSet> type) {
      super(fory, type);
    }

    @Override
    public void write(MemoryBuffer buffer, BitSet set) {
      long[] values = set.toLongArray();
      buffer.writePrimitiveArrayWithSize(
          values, Platform.LONG_ARRAY_OFFSET, Math.multiplyExact(values.length, 8));
    }

    @Override
    public BitSet copy(BitSet originCollection) {
      return BitSet.valueOf(originCollection.toLongArray());
    }

    @Override
    public BitSet read(MemoryBuffer buffer) {
      long[] values = buffer.readLongs(buffer.readVarUint32Small7());
      return BitSet.valueOf(values);
    }
  }

  public static class PriorityQueueSerializer extends CollectionSerializer<PriorityQueue> {
    public PriorityQueueSerializer(Fory fory, Class<PriorityQueue> cls) {
      super(fory, cls, true);
    }

    public Collection onCollectionWrite(MemoryBuffer buffer, PriorityQueue value) {
      buffer.writeVarUint32Small7(value.size());
      if (!fory.isCrossLanguage()) {
        fory.writeRef(buffer, value.comparator());
      }
      return value;
    }

    @Override
    public Collection newCollection(Collection collection) {
      return new PriorityQueue(
          collection.size(), fory.copyObject(((PriorityQueue) collection).comparator()));
    }

    @Override
    public PriorityQueue newCollection(MemoryBuffer buffer) {
      assert !fory.isCrossLanguage();
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      Comparator comparator = (Comparator) fory.readRef(buffer);
      PriorityQueue queue = new PriorityQueue(comparator);
      fory.getRefResolver().reference(queue);
      return queue;
    }
  }

  /**
   * Serializer for {@link ArrayBlockingQueue}.
   *
   * <p>This serializer handles the capacity field which is essential for ArrayBlockingQueue since
   * it's a bounded queue. The capacity is stored in the items array length and needs to be
   * preserved during serialization.
   */
  public static class ArrayBlockingQueueSerializer
      extends ConcurrentCollectionSerializer<ArrayBlockingQueue> {

    // Use reflection to get the items array length which represents the capacity.
    // This avoids race conditions when reading remainingCapacity() and size() separately.
    private static final long ITEMS_OFFSET;

    static {
      try {
        Field itemsField = ArrayBlockingQueue.class.getDeclaredField("items");
        ITEMS_OFFSET = Platform.objectFieldOffset(itemsField);
      } catch (NoSuchFieldException e) {
        throw new RuntimeException(e);
      }
    }

    public ArrayBlockingQueueSerializer(Fory fory, Class<ArrayBlockingQueue> cls) {
      super(fory, cls, true);
    }

    private static int getCapacity(ArrayBlockingQueue queue) {
      Object[] items = (Object[]) Platform.getObject(queue, ITEMS_OFFSET);
      return items.length;
    }

    @Override
    public CollectionSnapshot onCollectionWrite(MemoryBuffer buffer, ArrayBlockingQueue value) {
      // Read capacity before creating snapshot to ensure consistency
      int capacity = getCapacity(value);
      CollectionSnapshot snapshot = super.onCollectionWrite(buffer, value);
      buffer.writeVarUint32Small7(capacity);
      return snapshot;
    }

    @Override
    public ArrayBlockingQueue newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      int capacity = buffer.readVarUint32Small7();
      ArrayBlockingQueue queue = new ArrayBlockingQueue<>(capacity);
      fory.getRefResolver().reference(queue);
      return queue;
    }

    @Override
    public Collection newCollection(Collection collection) {
      ArrayBlockingQueue abq = (ArrayBlockingQueue) collection;
      int capacity = getCapacity(abq);
      return new ArrayBlockingQueue<>(capacity);
    }
  }

  /**
   * Serializer for {@link LinkedBlockingQueue}.
   *
   * <p>This serializer handles the capacity field which is essential for LinkedBlockingQueue since
   * it can be a bounded queue. The capacity needs to be preserved during serialization.
   */
  public static class LinkedBlockingQueueSerializer
      extends ConcurrentCollectionSerializer<LinkedBlockingQueue> {

    // Use reflection to get the capacity field directly.
    // This avoids race conditions when reading remainingCapacity() and size() separately.
    private static final long CAPACITY_OFFSET;

    static {
      try {
        Field capacityField = LinkedBlockingQueue.class.getDeclaredField("capacity");
        CAPACITY_OFFSET = Platform.objectFieldOffset(capacityField);
      } catch (NoSuchFieldException e) {
        throw new RuntimeException(e);
      }
    }

    public LinkedBlockingQueueSerializer(Fory fory, Class<LinkedBlockingQueue> cls) {
      super(fory, cls, true);
    }

    private static int getCapacity(LinkedBlockingQueue queue) {
      return Platform.getInt(queue, CAPACITY_OFFSET);
    }

    @Override
    public CollectionSnapshot onCollectionWrite(MemoryBuffer buffer, LinkedBlockingQueue value) {
      // Read capacity before creating snapshot to ensure consistency
      int capacity = getCapacity(value);
      CollectionSnapshot snapshot = super.onCollectionWrite(buffer, value);
      buffer.writeVarUint32Small7(capacity);
      return snapshot;
    }

    @Override
    public LinkedBlockingQueue newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      int capacity = buffer.readVarUint32Small7();
      LinkedBlockingQueue queue = new LinkedBlockingQueue<>(capacity);
      fory.getRefResolver().reference(queue);
      return queue;
    }

    @Override
    public Collection newCollection(Collection collection) {
      LinkedBlockingQueue lbq = (LinkedBlockingQueue) collection;
      int capacity = getCapacity(lbq);
      return new LinkedBlockingQueue<>(capacity);
    }
  }

  /**
   * Java serializer to serialize all fields of a collection implementation. Note that this
   * serializer won't use element generics and doesn't support JIT, performance won't be the best,
   * but the correctness can be ensured.
   */
  public static final class DefaultJavaCollectionSerializer<T> extends CollectionLikeSerializer<T> {
    private Serializer<T> dataSerializer;

    public DefaultJavaCollectionSerializer(Fory fory, Class<T> cls) {
      super(fory, cls, false);
      Preconditions.checkArgument(
          !fory.isCrossLanguage(),
          "Fory cross-language default collection serializer should use "
              + CollectionSerializer.class);
      fory.getClassResolver().setSerializer(cls, this);
      Class<? extends Serializer> serializerClass =
          fory.getClassResolver()
              .getObjectSerializerClass(
                  cls, sc -> dataSerializer = Serializers.newSerializer(fory, cls, sc));
      dataSerializer = Serializers.newSerializer(fory, cls, serializerClass);
      // No need to set object serializer to this, it will be set in class resolver later.
      // fory.getClassResolver().setSerializer(cls, this);
    }

    @Override
    public Collection onCollectionWrite(MemoryBuffer buffer, T value) {
      throw new IllegalStateException();
    }

    @Override
    public T onCollectionRead(Collection collection) {
      throw new IllegalStateException();
    }

    @Override
    public void write(MemoryBuffer buffer, T value) {
      dataSerializer.write(buffer, value);
    }

    @Override
    public T copy(T originCollection) {
      return fory.copyObject(originCollection, dataSerializer);
    }

    @Override
    public T read(MemoryBuffer buffer) {
      return dataSerializer.read(buffer);
    }
  }

  /** Collection serializer for class with JDK custom serialization methods defined. */
  public static final class JDKCompatibleCollectionSerializer<T>
      extends CollectionLikeSerializer<T> {
    private final Serializer serializer;

    public JDKCompatibleCollectionSerializer(Fory fory, Class<T> cls) {
      super(fory, cls, false);
      // Collection which defined `writeReplace` may use this serializer, so check replace/resolve
      // is necessary.
      Class<? extends Serializer> serializerType =
          ClassResolver.useReplaceResolveSerializer(cls)
              ? ReplaceResolveSerializer.class
              : fory.getDefaultJDKStreamSerializerType();
      serializer = Serializers.newSerializer(fory, cls, serializerType);
    }

    @Override
    public Collection onCollectionWrite(MemoryBuffer buffer, T value) {
      throw new IllegalStateException();
    }

    @SuppressWarnings("unchecked")
    @Override
    public T read(MemoryBuffer buffer) {
      return (T) serializer.read(buffer);
    }

    @Override
    public T onCollectionRead(Collection collection) {
      throw new IllegalStateException();
    }

    @Override
    public void write(MemoryBuffer buffer, T value) {
      serializer.write(buffer, value);
    }

    @Override
    public T copy(T value) {
      return fory.copyObject(value, (Serializer<T>) serializer);
    }
  }

  public abstract static class XlangCollectionDefaultSerializer extends CollectionLikeSerializer {

    public XlangCollectionDefaultSerializer(Fory fory, Class cls) {
      super(fory, cls);
    }

    @Override
    public Collection onCollectionWrite(MemoryBuffer buffer, Object value) {
      Collection v = (Collection) value;
      buffer.writeVarUint32Small7(v.size());
      return v;
    }

    @Override
    public Object onCollectionRead(Collection collection) {
      return collection;
    }
  }

  public static class XlangListDefaultSerializer extends XlangCollectionDefaultSerializer {
    public XlangListDefaultSerializer(Fory fory, Class cls) {
      super(fory, cls);
    }

    @Override
    public List newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      ArrayList list = new ArrayList(numElements);
      fory.getRefResolver().reference(list);
      return list;
    }
  }

  public static class XlangSetDefaultSerializer extends XlangCollectionDefaultSerializer {
    public XlangSetDefaultSerializer(Fory fory, Class cls) {
      super(fory, cls);
    }

    @Override
    public Set newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      HashSet set = new HashSet(numElements);
      fory.getRefResolver().reference(set);
      return set;
    }
  }

  // TODO add JDK11:JdkImmutableListSerializer,JdkImmutableMapSerializer,JdkImmutableSetSerializer
  //  by jit codegen those constructor for compiling in jdk8.
  // TODO Support ArraySubListSerializer, SubListSerializer

  public static void registerDefaultSerializers(Fory fory) {
    TypeResolver resolver = fory.getTypeResolver();
    resolver.registerInternalSerializer(ArrayList.class, new ArrayListSerializer(fory));
    Class arrayAsListClass = Arrays.asList(1, 2).getClass();
    resolver.registerInternalSerializer(
        arrayAsListClass, new ArraysAsListSerializer(fory, arrayAsListClass));
    resolver.registerInternalSerializer(
        LinkedList.class, new CollectionSerializer(fory, LinkedList.class, true));
    resolver.registerInternalSerializer(HashSet.class, new HashSetSerializer(fory));
    resolver.registerInternalSerializer(LinkedHashSet.class, new LinkedHashSetSerializer(fory));
    resolver.registerInternalSerializer(
        TreeSet.class, new SortedSetSerializer<>(fory, TreeSet.class));
    resolver.registerInternalSerializer(
        Collections.EMPTY_LIST.getClass(),
        new EmptyListSerializer(fory, (Class<List<?>>) Collections.EMPTY_LIST.getClass()));
    resolver.registerInternalSerializer(
        Collections.emptySortedSet().getClass(),
        new EmptySortedSetSerializer(
            fory, (Class<SortedSet<?>>) Collections.emptySortedSet().getClass()));
    resolver.registerInternalSerializer(
        Collections.EMPTY_SET.getClass(),
        new EmptySetSerializer(fory, (Class<Set<?>>) Collections.EMPTY_SET.getClass()));
    resolver.registerInternalSerializer(
        Collections.singletonList(null).getClass(),
        new CollectionsSingletonListSerializer(
            fory, (Class<List<?>>) Collections.singletonList(null).getClass()));
    resolver.registerInternalSerializer(
        Collections.singleton(null).getClass(),
        new CollectionsSingletonSetSerializer(
            fory, (Class<Set<?>>) Collections.singleton(null).getClass()));
    resolver.registerInternalSerializer(
        ConcurrentSkipListSet.class,
        new ConcurrentSkipListSetSerializer(fory, ConcurrentSkipListSet.class));
    resolver.registerInternalSerializer(Vector.class, new VectorSerializer(fory, Vector.class));
    resolver.registerInternalSerializer(
        ArrayDeque.class, new ArrayDequeSerializer(fory, ArrayDeque.class));
    resolver.registerInternalSerializer(BitSet.class, new BitSetSerializer(fory, BitSet.class));
    resolver.registerInternalSerializer(
        PriorityQueue.class, new PriorityQueueSerializer(fory, PriorityQueue.class));
    resolver.registerInternalSerializer(
        ArrayBlockingQueue.class, new ArrayBlockingQueueSerializer(fory, ArrayBlockingQueue.class));
    resolver.registerInternalSerializer(
        LinkedBlockingQueue.class,
        new LinkedBlockingQueueSerializer(fory, LinkedBlockingQueue.class));
    resolver.registerInternalSerializer(
        CopyOnWriteArrayList.class,
        new CopyOnWriteArrayListSerializer(fory, CopyOnWriteArrayList.class));
    final Class setFromMapClass = Collections.newSetFromMap(new HashMap<>()).getClass();
    resolver.registerInternalSerializer(
        setFromMapClass, new SetFromMapSerializer(fory, setFromMapClass));
    resolver.registerInternalSerializer(
        ConcurrentHashMap.KeySetView.class,
        new ConcurrentHashMapKeySetViewSerializer(fory, ConcurrentHashMap.KeySetView.class));
    resolver.registerInternalSerializer(
        CopyOnWriteArraySet.class,
        new CopyOnWriteArraySetSerializer(fory, CopyOnWriteArraySet.class));
  }
}
