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
import org.apache.fory.collection.CollectionSnapshot;
import org.apache.fory.config.Config;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.ExternalizableSerializer;
import org.apache.fory.serializer.ReplaceResolveSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.util.Preconditions;

/**
 * Serializers for classes implements {@link Collection}. All collection serializers should extend
 * {@link CollectionSerializer}.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class CollectionSerializers {
  private static final Comparator NATURAL_ORDER_COMPARATOR = Comparator.naturalOrder();

  private static void requireXlangNaturalOrdering(Class<?> type, Comparator<?> comparator) {
    if (comparator != null && comparator != NATURAL_ORDER_COMPARATOR) {
      throw new UnsupportedOperationException(
          "Xlang serialization of "
              + type.getName()
              + " with a custom comparator is unsupported because the xlang set wire format "
              + "does not encode comparators");
    }
  }

  private static void throwBinarySizeLimitExceeded(long size, int maxBinarySize) {
    throw new DeserializationException(
        "Binary payload size " + size + " exceeds max binary size " + maxBinarySize);
  }

  private static void throwNegativeBinarySize(int size) {
    throw new DeserializationException("Binary payload size must be non-negative: " + size);
  }

  private static void throwUnalignedBinarySize(int size, int elemSize) {
    throw new DeserializationException(
        "Binary payload size " + size + " is not aligned to element size " + elemSize);
  }

  private static void checkBoundedQueueCapacity(Config config, int numElements, int capacity) {
    // Keep these as direct primitive branches. This collection read path is JIT-sensitive; using
    // Preconditions.checkArgument here adds helper/varargs overhead and hurts inlining.
    if (numElements < 0) {
      throw new DeserializationException("Queue size must be non-negative: " + numElements);
    }
    if (capacity <= 0) {
      throw new DeserializationException("Queue capacity must be positive: " + capacity);
    }
    if (capacity < numElements) {
      throw new DeserializationException(
          "Queue capacity " + capacity + " is smaller than serialized size " + numElements);
    }
    int maxCollectionSize = config.maxCollectionSize();
    if (capacity > maxCollectionSize) {
      throw new DeserializationException(
          "Queue capacity " + capacity + " exceeds max collection size " + maxCollectionSize);
    }
  }

  private static UnsupportedOperationException unsupportedBoundedQueueWrite(Class<?> type) {
    return new UnsupportedOperationException(
        "Serializing or copying "
            + type.getName()
            + " requires access to its exact capacity field. This runtime can deserialize existing "
            + "payloads for this type, but cannot serialize or copy it without JDK concurrent "
            + "field access.");
  }

  public static final class ArrayListSerializer extends CollectionSerializer<ArrayList> {
    public ArrayListSerializer(TypeResolver typeResolver) {
      super(typeResolver, ArrayList.class, true);
    }

    @Override
    public ArrayList newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      ArrayList arrayList = new ArrayList(numElements);
      readContext.reference(arrayList);
      return arrayList;
    }
  }

  public static final class ArraysAsListSerializer extends CollectionSerializer<List<?>> {
    private static final class ArrayAccess {
      private static final FieldAccessor ACCESSOR;

      static {
        try {
          Field arrayField = Class.forName("java.util.Arrays$ArrayList").getDeclaredField("a");
          ACCESSOR = FieldAccessor.createAccessor(arrayField);
        } catch (final Exception e) {
          throw new RuntimeException(e);
        }
      }
    }

    public ArraysAsListSerializer(TypeResolver typeResolver, Class<List<?>> cls) {
      super(typeResolver, cls, typeResolver.getConfig().isXlang());
    }

    @Override
    public List<?> copy(CopyContext copyContext, List<?> originCollection) {
      Object[] elements = new Object[originCollection.size()];
      List<?> newCollection = Arrays.asList(elements);
      copyContext.reference(originCollection, newCollection);
      copyElements(copyContext, originCollection, elements);
      return newCollection;
    }

    @Override
    public void write(WriteContext writeContext, List<?> value) {
      if (config.isXlang()) {
        super.write(writeContext, value);
      } else {
        Object[] array =
            !MemoryUtils.JDK_COLLECTION_FIELD_ACCESS
                ? value.toArray()
                : (Object[]) ArrayAccess.ACCESSOR.getObject(value);
        writeContext.writeRef(array);
      }
    }

    @Override
    public List<?> read(ReadContext readContext) {
      if (config.isXlang()) {
        return super.read(readContext);
      } else {
        Object[] array = (Object[]) readContext.readRef();
        Preconditions.checkNotNull(array);
        return Arrays.asList(array);
      }
    }

    @Override
    public ArrayList newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      ArrayList arrayList = new ArrayList(numElements);
      readContext.reference(arrayList);
      return arrayList;
    }
  }

  public static final class HashSetSerializer extends CollectionSerializer<HashSet> {
    public HashSetSerializer(TypeResolver typeResolver) {
      super(typeResolver, HashSet.class, true);
    }

    @Override
    public HashSet newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      HashSet hashSet = new HashSet(numElements);
      readContext.reference(hashSet);
      return hashSet;
    }
  }

  public static final class LinkedHashSetSerializer extends CollectionSerializer<LinkedHashSet> {
    public LinkedHashSetSerializer(TypeResolver typeResolver) {
      super(typeResolver, LinkedHashSet.class, true);
    }

    @Override
    public LinkedHashSet newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      LinkedHashSet hashSet = new LinkedHashSet(numElements);
      readContext.reference(hashSet);
      return hashSet;
    }
  }

  public static class SortedSetSerializer<T extends SortedSet> extends CollectionSerializer<T> {
    private MethodHandle comparatorConstructor;
    private MethodHandle noArgConstructor;

    public SortedSetSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, true);
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
    public Collection onCollectionWrite(WriteContext writeContext, T value) {
      if (config.isXlang()) {
        requireXlangNaturalOrdering(type, value.comparator());
      }
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
    public T newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      T collection;
      Comparator comparator = config.isXlang() ? null : (Comparator) readContext.readRef();
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
      readContext.reference(collection);
      return collection;
    }

    @Override
    public Collection newCollection(CopyContext copyContext, Collection originCollection) {
      Collection collection;
      Comparator comparator =
          ComparatorCopy.copy(copyContext, ((SortedSet) originCollection).comparator());
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
  // type of read result is the same as the type when serializing.
  public static final class EmptyListSerializer extends CollectionSerializer<List<?>> {

    public EmptyListSerializer(TypeResolver typeResolver, Class<List<?>> cls) {
      super(typeResolver, cls, false, true);
    }

    @Override
    public void write(WriteContext writeContext, List<?> value) {
      if (config.isXlang()) {
        // write length
        writeContext.getBuffer().writeVarUInt32Small7(0);
      }
    }

    @Override
    public List<?> read(ReadContext readContext) {
      if (config.isXlang()) {
        int numElements = readCollectionSize(readContext.getBuffer());
        if (numElements != 0) {
          throw new DeserializationException(
              "Empty list payload must have zero elements but got " + numElements);
        }
      }
      return Collections.EMPTY_LIST;
    }
  }

  public static class CopyOnWriteArrayListSerializer
      extends ConcurrentCollectionSerializer<CopyOnWriteArrayList> {

    public CopyOnWriteArrayListSerializer(
        TypeResolver typeResolver, Class<CopyOnWriteArrayList> type) {
      super(typeResolver, type, true);
    }

    @Override
    public Collection newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      return new CollectionContainer<>(numElements);
    }

    @Override
    public CopyOnWriteArrayList onCollectionRead(Collection collection) {
      Object[] elements = ((CollectionContainer) collection).elements;
      return new CopyOnWriteArrayList(elements);
    }

    @Override
    public CopyOnWriteArrayList copy(
        CopyContext copyContext, CopyOnWriteArrayList originCollection) {
      CopyOnWriteArrayList newCollection = new CopyOnWriteArrayList();
      copyContext.reference(originCollection, newCollection);
      List copyList = new ArrayList(originCollection.size());
      copyElements(copyContext, originCollection, copyList);
      newCollection.addAll(copyList);
      return newCollection;
    }
  }

  public static class CopyOnWriteArraySetSerializer
      extends ConcurrentCollectionSerializer<CopyOnWriteArraySet> {

    public CopyOnWriteArraySetSerializer(
        TypeResolver typeResolver, Class<CopyOnWriteArraySet> type) {
      super(typeResolver, type, true);
    }

    @Override
    public Collection newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      return new CollectionContainer<>(numElements);
    }

    @Override
    public CopyOnWriteArraySet onCollectionRead(Collection collection) {
      Object[] elements = ((CollectionContainer) collection).elements;
      return new CopyOnWriteArraySet(Arrays.asList(elements));
    }

    @Override
    public CopyOnWriteArraySet copy(CopyContext copyContext, CopyOnWriteArraySet originCollection) {
      CopyOnWriteArraySet newCollection = new CopyOnWriteArraySet();
      copyContext.reference(originCollection, newCollection);
      List copyList = new ArrayList(originCollection.size());
      copyElements(copyContext, originCollection, copyList);
      newCollection.addAll(copyList);
      return newCollection;
    }
  }

  public static final class EmptySetSerializer extends CollectionSerializer<Set<?>> {

    public EmptySetSerializer(TypeResolver typeResolver, Class<Set<?>> cls) {
      super(typeResolver, cls, typeResolver.getConfig().isXlang(), true);
    }

    @Override
    public void write(WriteContext writeContext, Set<?> value) {
      if (config.isXlang()) {
        super.write(writeContext, value);
      }
    }

    @Override
    public Set<?> read(ReadContext readContext) {
      if (config.isXlang()) {
        throw new UnsupportedOperationException();
      }
      return Collections.EMPTY_SET;
    }
  }

  public static final class EmptySortedSetSerializer extends CollectionSerializer<SortedSet<?>> {

    public EmptySortedSetSerializer(TypeResolver typeResolver, Class<SortedSet<?>> cls) {
      super(typeResolver, cls, typeResolver.getConfig().isXlang(), true);
    }

    @Override
    public void write(WriteContext writeContext, SortedSet<?> value) {
      if (config.isXlang()) {
        super.write(writeContext, value);
      }
    }

    @Override
    public SortedSet<?> read(ReadContext readContext) {
      if (config.isXlang()) {
        throw new UnsupportedOperationException();
      }
      return Collections.emptySortedSet();
    }
  }

  public static final class CollectionsSingletonListSerializer
      extends CollectionSerializer<List<?>> {

    public CollectionsSingletonListSerializer(TypeResolver typeResolver, Class<List<?>> cls) {
      super(typeResolver, cls, typeResolver.getConfig().isXlang());
    }

    @Override
    public List<?> copy(CopyContext copyContext, List<?> originCollection) {
      return Collections.singletonList(copyContext.copyObject(originCollection.get(0)));
    }

    @Override
    public void write(WriteContext writeContext, List<?> value) {
      if (config.isXlang()) {
        super.write(writeContext, value);
      } else {
        writeContext.writeRef(value.get(0));
      }
    }

    @Override
    public List<?> read(ReadContext readContext) {
      if (config.isXlang()) {
        throw new UnsupportedOperationException();
      } else {
        return Collections.singletonList(readContext.readRef());
      }
    }
  }

  public static final class CollectionsSingletonSetSerializer extends CollectionSerializer<Set<?>> {

    public CollectionsSingletonSetSerializer(TypeResolver typeResolver, Class<Set<?>> cls) {
      super(typeResolver, cls, typeResolver.getConfig().isXlang());
    }

    @Override
    public Set<?> copy(CopyContext copyContext, Set<?> originCollection) {
      return Collections.singleton(copyContext.copyObject(originCollection.iterator().next()));
    }

    @Override
    public void write(WriteContext writeContext, Set<?> value) {
      if (config.isXlang()) {
        super.write(writeContext, value);
      } else {
        writeContext.writeRef(value.iterator().next());
      }
    }

    @Override
    public Set<?> read(ReadContext readContext) {
      if (config.isXlang()) {
        throw new UnsupportedOperationException();
      } else {
        return Collections.singleton(readContext.readRef());
      }
    }
  }

  public static final class ConcurrentSkipListSetSerializer
      extends ConcurrentCollectionSerializer<ConcurrentSkipListSet> {

    public ConcurrentSkipListSetSerializer(
        TypeResolver typeResolver, Class<ConcurrentSkipListSet> cls) {
      super(typeResolver, cls, true);
    }

    @Override
    public CollectionSnapshot onCollectionWrite(
        WriteContext writeContext, ConcurrentSkipListSet value) {
      if (config.isXlang()) {
        requireXlangNaturalOrdering(type, value.comparator());
      }
      CollectionSnapshot snapshot = super.onCollectionWrite(writeContext, value);
      if (config.isXlang()) {
        return snapshot;
      }
      writeContext.writeRef(value.comparator());
      return snapshot;
    }

    @Override
    public ConcurrentSkipListSet newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      if (config.isXlang()) {
        ConcurrentSkipListSet skipListSet = new ConcurrentSkipListSet();
        readContext.reference(skipListSet);
        return skipListSet;
      }
      int refId = readContext.lastPreservedRefId();
      // It's possible that comparator/elements has circular ref to set.
      Comparator comparator = (Comparator) readContext.readRef();
      ConcurrentSkipListSet skipListSet = new ConcurrentSkipListSet(comparator);
      readContext.setReadRef(refId, skipListSet);
      return skipListSet;
    }

    @Override
    public Collection newCollection(CopyContext copyContext, Collection originCollection) {
      Comparator comparator =
          ComparatorCopy.copy(copyContext, ((ConcurrentSkipListSet) originCollection).comparator());
      return new ConcurrentSkipListSet(comparator);
    }
  }

  public static final class SetFromMapSerializer extends CollectionSerializer<Set<?>> {
    private static final List EMPTY_COLLECTION_STUB = new ArrayList<>();

    private static final class SetFromMapAccess {
      private static final FieldAccessor MAP_ACCESSOR;
      private static final FieldAccessor KEY_SET_ACCESSOR;

      static {
        try {
          Class<?> type = Class.forName("java.util.Collections$SetFromMap");
          MAP_ACCESSOR = FieldAccessor.createAccessor(type.getDeclaredField("m"));
          KEY_SET_ACCESSOR = FieldAccessor.createAccessor(type.getDeclaredField("s"));
        } catch (final Exception e) {
          throw new RuntimeException(e);
        }
      }

      static Map<?, Boolean> map(Set<?> set) {
        return (Map<?, Boolean>) MAP_ACCESSOR.getObject(set);
      }

      static void restore(Set<?> set, Map<?, Boolean> map) {
        MAP_ACCESSOR.putObject(set, map);
        KEY_SET_ACCESSOR.putObject(set, map.keySet());
      }
    }

    public SetFromMapSerializer(TypeResolver typeResolver, Class<Set<?>> type) {
      super(typeResolver, type, true);
    }

    @Override
    public Collection newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      final TypeInfo mapTypeInfo = typeResolver.readTypeInfo(readContext);
      final MapLikeSerializer mapSerializer = (MapLikeSerializer) mapTypeInfo.getSerializer();
      // It's possible that elements or nested fields has circular ref to set.
      int refId = readContext.lastPreservedRefId();
      Set set;
      if (buffer.readBoolean()) {
        readContext.preserveRefId(refId);
        set = Collections.newSetFromMap(mapSerializer.newMap(readContext));
        setNumElements(mapSerializer.getAndClearNumElements());
      } else {
        if (!MemoryUtils.JDK_COLLECTION_FIELD_ACCESS) {
          throw new UnsupportedOperationException(
              "This runtime cannot read SetFromMap backing-map payloads that require hidden JDK field "
                  + "restoration");
        }
        Map<?, Boolean> map = (Map<?, Boolean>) mapSerializer.read(readContext);
        try {
          set = Collections.newSetFromMap(new HashMap<>());
          SetFromMapAccess.restore(set, map);
        } catch (Throwable e) {
          throw new UnsupportedOperationException(
              "This runtime cannot restore SetFromMap backing-map payloads through final JDK fields",
              e);
        }
        setNumElements(0);
      }
      readContext.setReadRef(refId, set);
      return set;
    }

    @Override
    public Collection newCollection(CopyContext copyContext, Collection originCollection) {
      assert !config.isXlang();
      return Collections.newSetFromMap(new HashMap(originCollection.size()));
    }

    @Override
    public Set<?> copy(CopyContext copyContext, Set<?> originCollection) {
      if (!MemoryUtils.JDK_COLLECTION_FIELD_ACCESS) {
        return (Set<?>) super.copy(copyContext, originCollection);
      }
      Map<?, Boolean> map = SetFromMapAccess.map(originCollection);
      Set<?> result = Collections.newSetFromMap(new HashMap<>());
      copyContext.reference(originCollection, result);
      Map<?, Boolean> newMap = copyContext.copyObject(map);
      SetFromMapAccess.restore(result, newMap);
      return result;
    }

    @Override
    public Collection onCollectionWrite(WriteContext writeContext, Set<?> value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      Map<?, Boolean> map;
      TypeInfo typeInfo;
      if (!MemoryUtils.JDK_COLLECTION_FIELD_ACCESS) {
        HashMap source = new HashMap<>(value.size());
        for (Object element : value) {
          source.put(element, Boolean.TRUE);
        }
        map = source;
        typeInfo = typeResolver.getTypeInfo(HashMap.class);
      } else {
        map = SetFromMapAccess.map(value);
        typeInfo = typeResolver.getTypeInfo(map.getClass());
      }
      MapLikeSerializer mapSerializer = (MapLikeSerializer) typeInfo.getSerializer();
      typeResolver.writeTypeInfo(writeContext, typeInfo);
      if (mapSerializer.supportCodegenHook) {
        buffer.writeBoolean(true);
        mapSerializer.onMapWrite(writeContext, map);
        return value;
      } else {
        buffer.writeBoolean(false);
        mapSerializer.write(writeContext, map);
        return EMPTY_COLLECTION_STUB;
      }
    }
  }

  public static final class ConcurrentHashMapKeySetViewSerializer
      extends CollectionSerializer<ConcurrentHashMap.KeySetView> {
    private final TypeInfoHolder mapTypeInfoHolder;
    private final TypeInfoHolder valueTypeInfoHolder;

    public ConcurrentHashMapKeySetViewSerializer(
        TypeResolver typeResolver, Class<ConcurrentHashMap.KeySetView> type) {
      super(typeResolver, type, false);
      mapTypeInfoHolder = typeResolver.nilTypeInfoHolder();
      valueTypeInfoHolder = typeResolver.nilTypeInfoHolder();
    }

    @Override
    public void write(WriteContext writeContext, ConcurrentHashMap.KeySetView value) {
      writeContext.writeRef(value.getMap(), mapTypeInfoHolder);
      writeContext.writeRef(value.getMappedValue(), valueTypeInfoHolder);
    }

    @Override
    public ConcurrentHashMap.KeySetView read(ReadContext readContext) {
      ConcurrentHashMap map = (ConcurrentHashMap) readContext.readRef(mapTypeInfoHolder);
      Object value = readContext.readRef(valueTypeInfoHolder);
      return map.keySet(value);
    }

    @Override
    public ConcurrentHashMap.KeySetView copy(
        CopyContext copyContext, ConcurrentHashMap.KeySetView value) {
      ConcurrentHashMap newMap = copyContext.copyObject(value.getMap());
      return newMap.keySet(copyContext.copyObject(value.getMappedValue()));
    }

    @Override
    public Collection newCollection(ReadContext readContext) {
      throw new IllegalStateException(
          "Should not be invoked since we set supportCodegenHook to false");
    }
  }

  public static final class VectorSerializer extends CollectionSerializer<Vector> {

    public VectorSerializer(TypeResolver typeResolver, Class<Vector> cls) {
      super(typeResolver, cls, true);
    }

    @Override
    public Vector newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      Vector<Object> vector = new Vector<>(numElements);
      readContext.reference(vector);
      return vector;
    }
  }

  public static final class ArrayDequeSerializer extends CollectionSerializer<ArrayDeque> {

    public ArrayDequeSerializer(TypeResolver typeResolver, Class<ArrayDeque> cls) {
      super(typeResolver, cls, true);
    }

    @Override
    public ArrayDeque newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      ArrayDeque deque = new ArrayDeque(numElements);
      readContext.reference(deque);
      return deque;
    }
  }

  public static class EnumSetSerializer extends CollectionSerializer<EnumSet> {
    public EnumSetSerializer(TypeResolver typeResolver, Class<EnumSet> type) {
      // getElementType(EnumSet.class) will be `E` without Enum as bound.
      // so no need to infer generics in init.
      super(typeResolver, type, false);
    }

    @Override
    public void write(WriteContext writeContext, EnumSet object) {
      MemoryBuffer buffer = writeContext.getBuffer();
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
      ((ClassResolver) typeResolver).writeClassAndUpdateCache(writeContext, elemClass);
      Serializer serializer = typeResolver.getSerializer(elemClass);
      buffer.writeVarUInt32Small7(object.size());
      for (Object element : object) {
        serializer.write(writeContext, element);
      }
    }

    @Override
    public EnumSet read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      Class elemClass = typeResolver.readTypeInfo(readContext).getType();
      EnumSet object = EnumSet.noneOf(elemClass);
      Serializer elemSerializer = typeResolver.getSerializer(elemClass);
      int length = readCollectionSize(buffer);
      for (int i = 0; i < length; i++) {
        object.add(elemSerializer.read(readContext));
      }
      return object;
    }

    @Override
    public EnumSet copy(CopyContext copyContext, EnumSet originCollection) {
      return EnumSet.copyOf(originCollection);
    }
  }

  public static class BitSetSerializer extends Serializer<BitSet> {
    private final int maxBinarySize;

    public BitSetSerializer(TypeResolver typeResolver, Class<BitSet> type) {
      super(typeResolver.getConfig(), type);
      maxBinarySize = typeResolver.getConfig().maxBinarySize();
    }

    @Override
    public void write(WriteContext writeContext, BitSet set) {
      MemoryBuffer buffer = writeContext.getBuffer();
      long[] values = set.toLongArray();
      buffer.writeLongsWithSize(values);
    }

    @Override
    public BitSet copy(CopyContext copyContext, BitSet originCollection) {
      return BitSet.valueOf(originCollection.toLongArray());
    }

    @Override
    public BitSet read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int size = buffer.readVarUInt32Small7();
      if (size < 0) {
        throwNegativeBinarySize(size);
      }
      if ((size & 7) != 0) {
        throwUnalignedBinarySize(size, 8);
      }
      if (size > maxBinarySize) {
        throwBinarySizeLimitExceeded(size, maxBinarySize);
      }
      long[] values = new long[size >>> 3];
      buffer.readInt64ArrayPayload(values, size);
      return BitSet.valueOf(values);
    }
  }

  public static class PriorityQueueSerializer extends CollectionSerializer<PriorityQueue> {
    public PriorityQueueSerializer(TypeResolver typeResolver, Class<PriorityQueue> cls) {
      super(typeResolver, cls, true);
    }

    public Collection onCollectionWrite(WriteContext writeContext, PriorityQueue value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUInt32Small7(value.size());
      if (config.isXlang()) {
        return value;
      } else {
        writeContext.writeRef(value.comparator());
      }
      return value;
    }

    @Override
    public Collection newCollection(CopyContext copyContext, Collection collection) {
      return new PriorityQueue(
          collection.size(),
          ComparatorCopy.copy(copyContext, ((PriorityQueue) collection).comparator()));
    }

    @Override
    public PriorityQueue newCollection(ReadContext readContext) {
      assert !config.isXlang();
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      Comparator comparator = (Comparator) readContext.readRef();
      PriorityQueue queue = new PriorityQueue(comparator);
      readContext.reference(queue);
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
    private static final class ItemsAccess {
      private static final FieldAccessor ACCESSOR;

      static {
        try {
          Field itemsField = ArrayBlockingQueue.class.getDeclaredField("items");
          ACCESSOR = FieldAccessor.createAccessor(itemsField);
        } catch (NoSuchFieldException e) {
          throw new RuntimeException(e);
        }
      }
    }

    public ArrayBlockingQueueSerializer(TypeResolver typeResolver, Class<ArrayBlockingQueue> cls) {
      super(typeResolver, cls, true);
    }

    private static int getCapacity(ArrayBlockingQueue queue) {
      if (!MemoryUtils.JDK_CONCURRENT_FIELD_ACCESS) {
        throw unsupportedBoundedQueueWrite(ArrayBlockingQueue.class);
      }
      Object[] items = (Object[]) ItemsAccess.ACCESSOR.getObject(queue);
      return items.length;
    }

    @Override
    public CollectionSnapshot onCollectionWrite(
        WriteContext writeContext, ArrayBlockingQueue value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      // Read capacity before creating snapshot to ensure consistency
      int capacity = getCapacity(value);
      CollectionSnapshot snapshot = super.onCollectionWrite(writeContext, value);
      buffer.writeVarUInt32Small7(capacity);
      return snapshot;
    }

    @Override
    public ArrayBlockingQueue newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      int capacity = buffer.readVarUInt32Small7();
      checkBoundedQueueCapacity(config, numElements, capacity);
      ArrayBlockingQueue queue = new ArrayBlockingQueue<>(capacity);
      readContext.reference(queue);
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
    private static final class CapacityAccess {
      private static final FieldAccessor ACCESSOR;

      static {
        try {
          Field capacityField = LinkedBlockingQueue.class.getDeclaredField("capacity");
          ACCESSOR = FieldAccessor.createAccessor(capacityField);
        } catch (NoSuchFieldException e) {
          throw new RuntimeException(e);
        }
      }
    }

    public LinkedBlockingQueueSerializer(
        TypeResolver typeResolver, Class<LinkedBlockingQueue> cls) {
      super(typeResolver, cls, true);
    }

    private static int getCapacity(LinkedBlockingQueue queue) {
      if (!MemoryUtils.JDK_CONCURRENT_FIELD_ACCESS) {
        throw unsupportedBoundedQueueWrite(LinkedBlockingQueue.class);
      }
      return CapacityAccess.ACCESSOR.getInt(queue);
    }

    @Override
    public CollectionSnapshot onCollectionWrite(
        WriteContext writeContext, LinkedBlockingQueue value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      // Read capacity before creating snapshot to ensure consistency
      int capacity = getCapacity(value);
      CollectionSnapshot snapshot = super.onCollectionWrite(writeContext, value);
      buffer.writeVarUInt32Small7(capacity);
      return snapshot;
    }

    @Override
    public LinkedBlockingQueue newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      int capacity = buffer.readVarUInt32Small7();
      checkBoundedQueueCapacity(config, numElements, capacity);
      LinkedBlockingQueue queue = new LinkedBlockingQueue<>(capacity);
      readContext.reference(queue);
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

    public DefaultJavaCollectionSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, false);
      Preconditions.checkArgument(
          !config.isXlang(),
          "Fory cross-language default collection serializer should use "
              + CollectionSerializer.class);
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
    public Collection onCollectionWrite(WriteContext writeContext, T value) {
      throw new IllegalStateException();
    }

    @Override
    public T onCollectionRead(Collection collection) {
      throw new IllegalStateException();
    }

    @Override
    public void write(WriteContext writeContext, T value) {
      dataSerializer.write(writeContext, value);
    }

    @Override
    public T copy(CopyContext copyContext, T originCollection) {
      return copyContext.copyObject(originCollection, dataSerializer);
    }

    @Override
    public T read(ReadContext readContext) {
      return dataSerializer.read(readContext);
    }
  }

  /** Collection serializer for class with JDK custom serialization methods defined. */
  public static final class JDKCompatibleCollectionSerializer<T>
      extends CollectionLikeSerializer<T> {
    private final Serializer serializer;

    public JDKCompatibleCollectionSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, false);
      // Collection which defined `writeReplace` may use this serializer, so check replace/resolve
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
    public Collection onCollectionWrite(WriteContext writeContext, T value) {
      throw new IllegalStateException();
    }

    @SuppressWarnings("unchecked")
    @Override
    public T read(ReadContext readContext) {
      return (T) serializer.read(readContext);
    }

    @Override
    public T onCollectionRead(Collection collection) {
      throw new IllegalStateException();
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

  public abstract static class XlangCollectionDefaultSerializer extends CollectionLikeSerializer {

    public XlangCollectionDefaultSerializer(TypeResolver typeResolver, Class cls) {
      super(typeResolver, cls);
    }

    @Override
    public Collection onCollectionWrite(WriteContext writeContext, Object value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      Collection v = (Collection) value;
      buffer.writeVarUInt32Small7(v.size());
      return v;
    }

    @Override
    public Object onCollectionRead(Collection collection) {
      return collection;
    }
  }

  public static class XlangListDefaultSerializer extends XlangCollectionDefaultSerializer {
    public XlangListDefaultSerializer(TypeResolver typeResolver, Class cls) {
      super(typeResolver, cls);
    }

    @Override
    public List newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      ArrayList list = new ArrayList(numElements);
      readContext.reference(list);
      return list;
    }
  }

  public static class XlangSetDefaultSerializer extends XlangCollectionDefaultSerializer {
    public XlangSetDefaultSerializer(TypeResolver typeResolver, Class cls) {
      super(typeResolver, cls);
    }

    @Override
    public Set newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      HashSet set = new HashSet(numElements);
      readContext.reference(set);
      return set;
    }
  }

  // TODO add JDK11:JdkImmutableListSerializer,JdkImmutableMapSerializer,JdkImmutableSetSerializer
  //  by jit codegen those constructor for compiling in jdk8.
  // TODO Support ArraySubListSerializer, SubListSerializer

  public static void registerDefaultSerializers(TypeResolver resolver) {
    resolver.registerInternalSerializer(ArrayList.class, new ArrayListSerializer(resolver));
    Class arrayAsListClass = Arrays.asList(1, 2).getClass();
    resolver.registerInternalSerializer(
        arrayAsListClass, new ArraysAsListSerializer(resolver, arrayAsListClass));
    resolver.registerInternalSerializer(
        LinkedList.class, new CollectionSerializer(resolver, LinkedList.class, true));
    resolver.registerInternalSerializer(HashSet.class, new HashSetSerializer(resolver));
    resolver.registerInternalSerializer(LinkedHashSet.class, new LinkedHashSetSerializer(resolver));
    resolver.registerInternalSerializer(
        TreeSet.class, new SortedSetSerializer<>(resolver, TreeSet.class));
    resolver.registerInternalSerializer(
        Collections.EMPTY_LIST.getClass(),
        new EmptyListSerializer(resolver, (Class<List<?>>) Collections.EMPTY_LIST.getClass()));
    resolver.registerInternalSerializer(
        Collections.emptySortedSet().getClass(),
        new EmptySortedSetSerializer(
            resolver, (Class<SortedSet<?>>) Collections.emptySortedSet().getClass()));
    resolver.registerInternalSerializer(
        Collections.EMPTY_SET.getClass(),
        new EmptySetSerializer(resolver, (Class<Set<?>>) Collections.EMPTY_SET.getClass()));
    resolver.registerInternalSerializer(
        Collections.singletonList(null).getClass(),
        new CollectionsSingletonListSerializer(
            resolver, (Class<List<?>>) Collections.singletonList(null).getClass()));
    resolver.registerInternalSerializer(
        Collections.singleton(null).getClass(),
        new CollectionsSingletonSetSerializer(
            resolver, (Class<Set<?>>) Collections.singleton(null).getClass()));
    resolver.registerInternalSerializer(
        ConcurrentSkipListSet.class,
        new ConcurrentSkipListSetSerializer(resolver, ConcurrentSkipListSet.class));
    resolver.registerInternalSerializer(Vector.class, new VectorSerializer(resolver, Vector.class));
    resolver.registerInternalSerializer(
        ArrayDeque.class, new ArrayDequeSerializer(resolver, ArrayDeque.class));
    resolver.registerInternalSerializer(BitSet.class, new BitSetSerializer(resolver, BitSet.class));
    resolver.registerInternalSerializer(
        PriorityQueue.class, new PriorityQueueSerializer(resolver, PriorityQueue.class));
    resolver.registerInternalSerializer(
        ArrayBlockingQueue.class,
        new ArrayBlockingQueueSerializer(resolver, ArrayBlockingQueue.class));
    resolver.registerInternalSerializer(
        LinkedBlockingQueue.class,
        new LinkedBlockingQueueSerializer(resolver, LinkedBlockingQueue.class));
    resolver.registerInternalSerializer(
        CopyOnWriteArrayList.class,
        new CopyOnWriteArrayListSerializer(resolver, CopyOnWriteArrayList.class));
    final Class setFromMapClass = Collections.newSetFromMap(new HashMap<>()).getClass();
    resolver.registerInternalSerializer(
        setFromMapClass, new SetFromMapSerializer(resolver, setFromMapClass));
    resolver.registerInternalSerializer(
        ConcurrentHashMap.KeySetView.class,
        new ConcurrentHashMapKeySetViewSerializer(resolver, ConcurrentHashMap.KeySetView.class));
    resolver.registerInternalSerializer(
        CopyOnWriteArraySet.class,
        new CopyOnWriteArraySetSerializer(resolver, CopyOnWriteArraySet.class));
  }
}
