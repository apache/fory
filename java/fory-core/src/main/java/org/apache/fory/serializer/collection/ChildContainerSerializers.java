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

import static org.apache.fory.collection.Collections.ofHashSet;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import org.apache.fory.builder.LayerMarkerClassGenerator;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.AbstractObjectSerializer;
import org.apache.fory.serializer.FieldGroups;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.serializer.JavaSerializer;
import org.apache.fory.serializer.MetaSharedLayerSerializer;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.util.Preconditions;

/**
 * Serializers for subclasses of common JDK container types. Subclasses of {@link ArrayList}/{@link
 * HashMap}/{@link LinkedHashMap}/{@link java.util.TreeMap}/etc have `writeObject`/`readObject`
 * defined, which will use JDK compatible serializers, thus inefficient. Serializers will optimize
 * the serialization for those cases by serializing super classes part separately using existing
 * JIT/interpreter-mode serializers.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class ChildContainerSerializers {

  public static Class<? extends Serializer> getCollectionSerializerClass(Class<?> cls) {
    if (ChildCollectionSerializer.superClasses.contains(cls)
        || ChildSortedSetSerializer.superClasses.contains(cls)
        || ChildPriorityQueueSerializer.superClasses.contains(cls)) {
      return null;
    }
    if (ClassResolver.useReplaceResolveSerializer(cls)) {
      return null;
    }
    Class<?> childClass = cls;
    while (cls != Object.class) {
      if (ChildCollectionSerializer.superClasses.contains(cls)) {
        if (!ReflectionUtils.hasNoArgConstructor(childClass)) {
          return null;
        }
        if (cls == ArrayList.class) {
          return ChildArrayListSerializer.class;
        } else {
          return ChildCollectionSerializer.class;
        }
      } else if (ChildSortedSetSerializer.superClasses.contains(cls)) {
        if (ChildSortedSetSerializer.supports(childClass)) {
          return ChildSortedSetSerializer.class;
        }
        return null;
      } else if (ChildPriorityQueueSerializer.superClasses.contains(cls)) {
        if (ChildPriorityQueueSerializer.supports(childClass)) {
          return ChildPriorityQueueSerializer.class;
        }
        return null;
      } else {
        if (JavaSerializer.getReadRefMethod(cls, false) != null
            || JavaSerializer.getWriteObjectMethod(cls, false) != null) {
          return null;
        }
      }
      cls = cls.getSuperclass();
    }
    return null;
  }

  public static Class<? extends Serializer> getMapSerializerClass(Class<?> cls) {
    if (ChildMapSerializer.superClasses.contains(cls)
        || ChildSortedMapSerializer.superClasses.contains(cls)) {
      return null;
    }
    if (ClassResolver.useReplaceResolveSerializer(cls)) {
      return null;
    }
    Class<?> childClass = cls;
    while (cls != Object.class) {
      if (ChildMapSerializer.superClasses.contains(cls)) {
        if (!ReflectionUtils.hasNoArgConstructor(childClass)) {
          return null;
        }
        return ChildMapSerializer.class;
      } else if (ChildSortedMapSerializer.superClasses.contains(cls)) {
        if (ChildSortedMapSerializer.supports(childClass)) {
          return ChildSortedMapSerializer.class;
        }
        return null;
      } else {
        if (JavaSerializer.getReadRefMethod(cls, false) != null
            || JavaSerializer.getWriteObjectMethod(cls, false) != null) {
          return null;
        }
      }
      cls = cls.getSuperclass();
    }
    return null;
  }

  /**
   * Serializer for subclasses of {@link ChildCollectionSerializer#superClasses} if no jdk custom
   * serialization in those classes.
   */
  public static class ChildCollectionSerializer<T extends Collection>
      extends CollectionSerializer<T> {
    public static Set<Class<?>> superClasses =
        ofHashSet(
            ArrayList.class, LinkedList.class, ArrayDeque.class, Vector.class, HashSet.class
            // PriorityQueue/TreeSet/ConcurrentSkipListSet need comparator as constructor argument
            );
    protected SerializationFieldInfo[] fieldInfos;
    protected final Serializer[] slotsSerializers;
    protected final Set<Class<?>> slotSuperClasses;

    public ChildCollectionSerializer(TypeResolver typeResolver, Class<T> cls) {
      this(typeResolver, cls, superClasses);
    }

    protected ChildCollectionSerializer(
        TypeResolver typeResolver, Class<T> cls, Set<Class<?>> superClasses) {
      super(typeResolver, cls);
      this.slotSuperClasses = superClasses;
      slotsSerializers = buildSlotsSerializers(typeResolver, superClasses, cls);
    }

    @Override
    public Collection onCollectionWrite(WriteContext writeContext, T value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUint32Small7(value.size());
      for (Serializer slotsSerializer : slotsSerializers) {
        slotsSerializer.write(writeContext, value);
      }
      return value;
    }

    public Collection newCollection(ReadContext readContext) {
      Collection collection = super.newCollection(readContext);
      readAndSetFields(readContext, typeResolver, collection, slotsSerializers);
      return collection;
    }

    @Override
    public Collection newCollection(CopyContext copyContext, Collection originCollection) {
      Collection newCollection = super.newCollection(copyContext, originCollection);
      copyChildFields(copyContext, originCollection, newCollection);
      return newCollection;
    }

    protected final void readChildFields(ReadContext readContext, Object collection) {
      readAndSetFields(readContext, typeResolver, collection, slotsSerializers);
    }

    protected final void copyChildFields(
        CopyContext copyContext, Object originCollection, Object newCollection) {
      if (fieldInfos == null) {
        List<Field> fields = ReflectionUtils.getFieldsWithoutSuperClasses(type, slotSuperClasses);
        fieldInfos = FieldGroups.buildFieldsInfo(typeResolver, fields).allFields;
      }
      AbstractObjectSerializer.copyFields(copyContext, fieldInfos, originCollection, newCollection);
    }
  }

  public static final class ChildArrayListSerializer<T extends ArrayList>
      extends ChildCollectionSerializer<T> {
    public ChildArrayListSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls);
    }

    @Override
    public T newCollection(ReadContext readContext) {
      T collection = (T) super.newCollection(readContext);
      int numElements = getAndClearNumElements();
      setNumElements(numElements);
      collection.ensureCapacity(numElements);
      return collection;
    }
  }

  public static final class ChildSortedSetSerializer<T extends SortedSet>
      extends ChildCollectionSerializer<T> {
    public static final Set<Class<?>> superClasses =
        ofHashSet(TreeSet.class, ConcurrentSkipListSet.class);
    private final SortedSetSubclassFactory<T> subclassFactory;

    public ChildSortedSetSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, superClasses);
      subclassFactory = new SortedSetSubclassFactory<>(cls);
    }

    static boolean supports(Class<?> cls) {
      return SortedSetSubclassFactory.supports(cls);
    }

    @Override
    public Collection onCollectionWrite(WriteContext writeContext, T value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUint32Small7(value.size());
      writeContext.writeRef(value.comparator());
      for (Serializer slotsSerializer : slotsSerializers) {
        slotsSerializer.write(writeContext, value);
      }
      return value;
    }

    @Override
    public T newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      int refId = readContext.lastPreservedRefId();
      Comparator comparator = (Comparator) readContext.readRef();
      T collection = subclassFactory.newCollection(comparator);
      readContext.setReadRef(refId, collection);
      readChildFields(readContext, collection);
      return collection;
    }

    @Override
    public Collection newCollection(CopyContext copyContext, Collection originCollection) {
      T newCollection =
          subclassFactory.newCollection(
              copyContext.copyObject(((SortedSet<?>) originCollection).comparator()));
      copyChildFields(copyContext, originCollection, newCollection);
      return newCollection;
    }
  }

  public static final class ChildPriorityQueueSerializer<T extends PriorityQueue>
      extends ChildCollectionSerializer<T> {
    public static final Set<Class<?>> superClasses = ofHashSet(PriorityQueue.class);
    private final PriorityQueueSubclassFactory<T> subclassFactory;

    public ChildPriorityQueueSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, superClasses);
      subclassFactory = new PriorityQueueSubclassFactory<>(cls);
    }

    static boolean supports(Class<?> cls) {
      return PriorityQueueSubclassFactory.supports(cls);
    }

    @Override
    public Collection onCollectionWrite(WriteContext writeContext, T value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUint32Small7(value.size());
      writeContext.writeRef(value.comparator());
      for (Serializer slotsSerializer : slotsSerializers) {
        slotsSerializer.write(writeContext, value);
      }
      return value;
    }

    @Override
    public T newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      int refId = readContext.lastPreservedRefId();
      Comparator comparator = (Comparator) readContext.readRef();
      T collection = subclassFactory.newCollection(comparator, numElements);
      readContext.setReadRef(refId, collection);
      readChildFields(readContext, collection);
      return collection;
    }

    @Override
    public Collection newCollection(CopyContext copyContext, Collection originCollection) {
      T newCollection =
          subclassFactory.newCollection(
              copyContext.copyObject(((PriorityQueue<?>) originCollection).comparator()),
              originCollection.size());
      copyChildFields(copyContext, originCollection, newCollection);
      return newCollection;
    }
  }

  /**
   * Serializer for subclasses of {@link ChildMapSerializer#superClasses} if no jdk custom
   * serialization in those classes.
   */
  public static class ChildMapSerializer<T extends Map> extends MapSerializer<T> {
    public static Set<Class<?>> superClasses =
        ofHashSet(
            HashMap.class, LinkedHashMap.class, ConcurrentHashMap.class
            // TreeMap/ConcurrentSkipListMap need comparator as constructor argument
            );
    protected final Serializer[] slotsSerializers;
    private SerializationFieldInfo[] fieldInfos;
    protected final Set<Class<?>> slotSuperClasses;

    public ChildMapSerializer(TypeResolver typeResolver, Class<T> cls) {
      this(typeResolver, cls, superClasses);
    }

    protected ChildMapSerializer(
        TypeResolver typeResolver, Class<T> cls, Set<Class<?>> superClasses) {
      super(typeResolver, cls);
      this.slotSuperClasses = superClasses;
      slotsSerializers = buildSlotsSerializers(typeResolver, superClasses, cls);
    }

    @Override
    public Map onMapWrite(WriteContext writeContext, T value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUint32Small7(value.size());
      for (Serializer slotsSerializer : slotsSerializers) {
        slotsSerializer.write(writeContext, value);
      }
      return value;
    }

    @Override
    public Map newMap(ReadContext readContext) {
      Map map = super.newMap(readContext);
      readAndSetFields(readContext, typeResolver, map, slotsSerializers);
      return map;
    }

    @Override
    public Map newMap(CopyContext copyContext, Map originMap) {
      Map newMap = super.newMap(copyContext, originMap);
      copyChildFields(copyContext, originMap, newMap);
      return newMap;
    }

    protected final void readChildFields(ReadContext readContext, Object map) {
      readAndSetFields(readContext, typeResolver, map, slotsSerializers);
    }

    protected final void copyChildFields(CopyContext copyContext, Object originMap, Object newMap) {
      if (fieldInfos == null || fieldInfos.length == 0) {
        List<Field> fields = ReflectionUtils.getFieldsWithoutSuperClasses(type, slotSuperClasses);
        fieldInfos = FieldGroups.buildFieldsInfo(typeResolver, fields).allFields;
      }
      AbstractObjectSerializer.copyFields(copyContext, fieldInfos, originMap, newMap);
    }
  }

  public static final class ChildSortedMapSerializer<T extends SortedMap>
      extends ChildMapSerializer<T> {
    public static final Set<Class<?>> superClasses =
        ofHashSet(TreeMap.class, ConcurrentSkipListMap.class);
    private final SortedMapSubclassFactory<T> subclassFactory;

    public ChildSortedMapSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls, superClasses);
      subclassFactory = new SortedMapSubclassFactory<>(cls);
    }

    static boolean supports(Class<?> cls) {
      return SortedMapSubclassFactory.supports(cls);
    }

    @Override
    public Map onMapWrite(WriteContext writeContext, T value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeVarUint32Small7(value.size());
      writeContext.writeRef(value.comparator());
      for (Serializer slotsSerializer : slotsSerializers) {
        slotsSerializer.write(writeContext, value);
      }
      return value;
    }

    @Override
    public Map newMap(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      int refId = readContext.lastPreservedRefId();
      Comparator comparator = (Comparator) readContext.readRef();
      T map = subclassFactory.newMap(comparator);
      readContext.setReadRef(refId, map);
      readChildFields(readContext, map);
      return map;
    }

    @Override
    public Map newMap(CopyContext copyContext, Map originMap) {
      T newMap =
          subclassFactory.newMap(
              copyContext.copyObject(((SortedMap<?, ?>) originMap).comparator()));
      copyChildFields(copyContext, originMap, newMap);
      return newMap;
    }
  }

  private static final class SortedSetSubclassFactory<T extends SortedSet> {
    private final Class<T> type;
    private final MethodHandle noArgConstructor;
    private final MethodHandle comparatorConstructor;
    private final MethodHandle sortedSetConstructor;
    private final MethodHandle collectionConstructor;

    private SortedSetSubclassFactory(Class<T> type) {
      this.type = type;
      noArgConstructor = ReflectionUtils.getCtrHandle(type, false);
      comparatorConstructor = findConstructorHandle(type, Comparator.class);
      sortedSetConstructor = findConstructorHandle(type, SortedSet.class);
      collectionConstructor = findConstructorHandle(type, Collection.class);
    }

    private static boolean supports(Class<?> cls) {
      return ReflectionUtils.getCtrHandle(cls, false) != null
          || findConstructorHandle(cls, Comparator.class) != null
          || findConstructorHandle(cls, SortedSet.class) != null
          || findConstructorHandle(cls, Collection.class) != null;
    }

    private T newCollection(Comparator comparator) {
      if (type == TreeSet.class) {
        return (T) new TreeSet(comparator);
      }
      if (type == ConcurrentSkipListSet.class) {
        return (T) new ConcurrentSkipListSet(comparator);
      }
      if (comparatorConstructor != null) {
        return invokeConstructor(comparatorConstructor, comparator);
      }
      if (comparator != null && sortedSetConstructor != null) {
        return invokeConstructor(sortedSetConstructor, new TreeSet(comparator));
      }
      if (noArgConstructor != null) {
        return invokeConstructor(noArgConstructor);
      }
      if (comparator == null && collectionConstructor != null) {
        return invokeConstructor(collectionConstructor, Collections.emptyList());
      }
      throw unsupportedConstructor(type);
    }
  }

  private static final class SortedMapSubclassFactory<T extends SortedMap> {
    private final Class<T> type;
    private final MethodHandle noArgConstructor;
    private final MethodHandle comparatorConstructor;
    private final MethodHandle sortedMapConstructor;
    private final MethodHandle mapConstructor;

    private SortedMapSubclassFactory(Class<T> type) {
      this.type = type;
      noArgConstructor = ReflectionUtils.getCtrHandle(type, false);
      comparatorConstructor = findConstructorHandle(type, Comparator.class);
      sortedMapConstructor = findConstructorHandle(type, SortedMap.class);
      mapConstructor = findConstructorHandle(type, Map.class);
    }

    private static boolean supports(Class<?> cls) {
      return ReflectionUtils.getCtrHandle(cls, false) != null
          || findConstructorHandle(cls, Comparator.class) != null
          || findConstructorHandle(cls, SortedMap.class) != null
          || findConstructorHandle(cls, Map.class) != null;
    }

    private T newMap(Comparator comparator) {
      if (type == TreeMap.class) {
        return (T) new TreeMap(comparator);
      }
      if (type == ConcurrentSkipListMap.class) {
        return (T) new ConcurrentSkipListMap(comparator);
      }
      if (comparatorConstructor != null) {
        return invokeConstructor(comparatorConstructor, comparator);
      }
      if (comparator != null && sortedMapConstructor != null) {
        return invokeConstructor(sortedMapConstructor, new TreeMap(comparator));
      }
      if (noArgConstructor != null) {
        return invokeConstructor(noArgConstructor);
      }
      if (comparator == null && mapConstructor != null) {
        return invokeConstructor(mapConstructor, Collections.emptyMap());
      }
      throw unsupportedConstructor(type);
    }
  }

  private static final class PriorityQueueSubclassFactory<T extends PriorityQueue> {
    private final Class<T> type;
    private final MethodHandle noArgConstructor;
    private final MethodHandle capacityConstructor;
    private final MethodHandle comparatorConstructor;
    private final MethodHandle capacityComparatorConstructor;
    private final MethodHandle priorityQueueConstructor;
    private final MethodHandle sortedSetConstructor;
    private final MethodHandle collectionConstructor;

    private PriorityQueueSubclassFactory(Class<T> type) {
      this.type = type;
      noArgConstructor = ReflectionUtils.getCtrHandle(type, false);
      capacityConstructor = findConstructorHandle(type, int.class);
      comparatorConstructor = findConstructorHandle(type, Comparator.class);
      capacityComparatorConstructor = findConstructorHandle(type, int.class, Comparator.class);
      priorityQueueConstructor = findConstructorHandle(type, PriorityQueue.class);
      sortedSetConstructor = findConstructorHandle(type, SortedSet.class);
      collectionConstructor = findConstructorHandle(type, Collection.class);
    }

    private static boolean supports(Class<?> cls) {
      return ReflectionUtils.getCtrHandle(cls, false) != null
          || findConstructorHandle(cls, int.class) != null
          || findConstructorHandle(cls, Comparator.class) != null
          || findConstructorHandle(cls, int.class, Comparator.class) != null
          || findConstructorHandle(cls, PriorityQueue.class) != null
          || findConstructorHandle(cls, SortedSet.class) != null
          || findConstructorHandle(cls, Collection.class) != null;
    }

    private T newCollection(Comparator comparator, int numElements) {
      int capacity = Math.max(numElements, 1);
      if (type == PriorityQueue.class) {
        return (T) new PriorityQueue(capacity, comparator);
      }
      if (capacityComparatorConstructor != null) {
        return invokeConstructor(capacityComparatorConstructor, capacity, comparator);
      }
      if (comparator != null) {
        if (comparatorConstructor != null) {
          return invokeConstructor(comparatorConstructor, comparator);
        }
        if (priorityQueueConstructor != null) {
          return invokeConstructor(
              priorityQueueConstructor, new PriorityQueue(capacity, comparator));
        }
        if (sortedSetConstructor != null) {
          return invokeConstructor(sortedSetConstructor, new TreeSet(comparator));
        }
        if (collectionConstructor != null) {
          return invokeConstructor(collectionConstructor, new TreeSet(comparator));
        }
      }
      if (noArgConstructor != null) {
        return invokeConstructor(noArgConstructor);
      }
      if (capacityConstructor != null) {
        return invokeConstructor(capacityConstructor, capacity);
      }
      if (priorityQueueConstructor != null) {
        return invokeConstructor(priorityQueueConstructor, new PriorityQueue(capacity, comparator));
      }
      if (sortedSetConstructor != null) {
        return invokeConstructor(sortedSetConstructor, new TreeSet(comparator));
      }
      if (collectionConstructor != null) {
        return invokeConstructor(collectionConstructor, Collections.emptyList());
      }
      throw unsupportedConstructor(type);
    }
  }

  private static MethodHandle findConstructorHandle(Class<?> cls, Class<?>... parameterTypes) {
    try {
      return ReflectionUtils.getCtrHandle(cls, parameterTypes);
    } catch (Throwable t) {
      return null;
    }
  }

  private static <T> T invokeConstructor(MethodHandle constructor, Object... args) {
    try {
      return (T) constructor.invokeWithArguments(args);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private static UnsupportedOperationException unsupportedConstructor(Class<?> cls) {
    return new UnsupportedOperationException(
        "Class "
            + cls.getName()
            + " requires a supported child-container constructor for auto-selected optimized"
            + " serialization");
  }

  private static <T> Serializer[] buildSlotsSerializers(
      TypeResolver typeResolver, Set<Class<?>> superClasses, Class<T> cls) {
    Preconditions.checkArgument(!superClasses.contains(cls));
    List<Serializer> serializers = new ArrayList<>();
    int layerIndex = 0;
    while (!superClasses.contains(cls)) {
      Serializer slotsSerializer;
      if (typeResolver.getConfig().isCompatible()) {
        TypeDef layerTypeDef = typeResolver.getTypeDef(cls, false);
        // Use layer index within class hierarchy (not global counter)
        // This ensures unique marker classes for each layer
        Class<?> layerMarkerClass = LayerMarkerClassGenerator.getOrCreate(cls, layerIndex);
        slotsSerializer =
            new MetaSharedLayerSerializer(typeResolver, cls, layerTypeDef, layerMarkerClass);
      } else {
        slotsSerializer = new ObjectSerializer<>(typeResolver, cls, false);
      }
      serializers.add(slotsSerializer);
      cls = (Class<T>) cls.getSuperclass();
      layerIndex++;
    }
    Collections.reverse(serializers);
    return serializers.toArray(new Serializer[0]);
  }

  private static void readAndSetFields(
      ReadContext readContext,
      TypeResolver typeResolver,
      Object collection,
      Serializer[] slotsSerializers) {
    for (Serializer slotsSerializer : slotsSerializers) {
      if (slotsSerializer instanceof MetaSharedLayerSerializer) {
        MetaSharedLayerSerializer metaSerializer = (MetaSharedLayerSerializer) slotsSerializer;
        // Read layer class meta first if meta share is enabled
        // This corresponds to writeLayerClassMeta() in MetaSharedLayerSerializer.write()
        if (typeResolver.getConfig().isMetaShareEnabled()) {
          readAndSkipLayerClassMeta(readContext);
        }
        metaSerializer.readAndSetFields(readContext, collection);
      } else {
        ((ObjectSerializer) slotsSerializer).readAndSetFields(readContext, collection);
      }
    }
  }

  /**
   * Read and skip the layer class meta from buffer. This is used to skip over the class definition
   * that was written by MetaSharedLayerSerializer.writeLayerClassMeta(). For
   * ChildContainerSerializers, we use the same serializer on both write and read sides, so we just
   * need to skip the meta without actually parsing it.
   */
  private static void readAndSkipLayerClassMeta(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    MetaReadContext metaReadContext = readContext.getMetaReadContext();
    if (metaReadContext == null) {
      return;
    }
    int indexMarker = buffer.readVarUint32Small14();
    boolean isRef = (indexMarker & 1) == 1;
    int index = indexMarker >>> 1;
    if (isRef) {
      // Reference to previously read type - nothing more to read
      return;
    }
    // New type - need to read and skip the TypeDef bytes
    long id = buffer.readInt64();
    TypeDef.skipTypeDef(buffer, id);
    // Add a placeholder to keep readTypeInfos indices in sync with the write side's classMap.
    // The write side (writeLayerClassMeta) adds layer marker classes to classMap which shares
    // the same index space as writeSharedClassMeta. Without this placeholder, subsequent
    // readSharedClassMeta reference lookups would use wrong indices.
    metaReadContext.readTypeInfos.add(null);
  }
}
