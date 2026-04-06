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

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
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
    if (ChildCollectionSerializer.superClasses.contains(cls)) {
      return null;
    }
    if (ClassResolver.useReplaceResolveSerializer(cls)) {
      return null;
    }
    // Collection/Map must have default constructor to be invoked by fory, otherwise created object
    // can't be used to adding elements.
    // For example: `new ArrayList<Integer> { add(1);}`, without default constructor, created
    // list will have elementData as null, adding elements will raise NPE.
    if (!ReflectionUtils.hasNoArgConstructor(cls)) {
      return null;
    }
    while (cls != Object.class) {
      if (ChildCollectionSerializer.superClasses.contains(cls)) {
        if (cls == ArrayList.class) {
          return ChildArrayListSerializer.class;
        } else {
          return ChildCollectionSerializer.class;
        }
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
    if (ChildMapSerializer.superClasses.contains(cls)) {
      return null;
    }
    if (ClassResolver.useReplaceResolveSerializer(cls)) {
      return null;
    }
    // Collection/Map must have default constructor to be invoked by fory, otherwise created object
    // can't be used to adding elements.
    // For example: `new ArrayList<Integer> { add(1);}`, without default constructor, created
    // list will have elementData as null, adding elements will raise NPE.
    if (!ReflectionUtils.hasNoArgConstructor(cls)) {
      return null;
    }
    while (cls != Object.class) {
      if (ChildMapSerializer.superClasses.contains(cls)) {
        return ChildMapSerializer.class;
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

    public ChildCollectionSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls);
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
      if (fieldInfos == null) {
        List<Field> fields = ReflectionUtils.getFieldsWithoutSuperClasses(type, superClasses);
        fieldInfos = FieldGroups.buildFieldsInfo(typeResolver, fields).allFields;
      }
      AbstractObjectSerializer.copyFields(copyContext, fieldInfos, originCollection, newCollection);
      return newCollection;
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
    private final Serializer[] slotsSerializers;
    private SerializationFieldInfo[] fieldInfos;

    public ChildMapSerializer(TypeResolver typeResolver, Class<T> cls) {
      super(typeResolver, cls);
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
      if (fieldInfos == null || fieldInfos.length == 0) {
        List<Field> fields = ReflectionUtils.getFieldsWithoutSuperClasses(type, superClasses);
        fieldInfos = FieldGroups.buildFieldsInfo(typeResolver, fields).allFields;
      }
      AbstractObjectSerializer.copyFields(copyContext, fieldInfos, originMap, newMap);
      return newMap;
    }
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
