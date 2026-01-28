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
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.util.unsafe._JDKAccess;

/** Serializers for jdk9+ java.util.ImmutableCollections. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class ImmutableCollectionSerializers {
  private static Class<?> List12;
  private static Class<?> ListN;
  private static Class<?> SubList;
  private static Class<?> Set12;
  private static Class<?> SetN;
  private static Class<?> Map1;
  private static Class<?> MapN;
  private static MethodHandle listFactory;
  private static MethodHandle setFactory;
  private static MethodHandle map1Factory;
  private static MethodHandle mapNFactory;

  static {
    if (Platform.JAVA_VERSION > 8) {
      try {
        List12 = Class.forName("java.util.ImmutableCollections$List12");
        ListN = Class.forName("java.util.ImmutableCollections$ListN");
        SubList = Class.forName("java.util.ImmutableCollections$SubList");
        Set12 = Class.forName("java.util.ImmutableCollections$Set12");
        SetN = Class.forName("java.util.ImmutableCollections$SetN");
        Map1 = Class.forName("java.util.ImmutableCollections$Map1");
        MapN = Class.forName("java.util.ImmutableCollections$MapN");
        listFactory =
            _JDKAccess._trustedLookup(List.class)
                .findStatic(List.class, "of", MethodType.methodType(List.class, Object[].class));
        setFactory =
            _JDKAccess._trustedLookup(Set.class)
                .findStatic(Set.class, "of", MethodType.methodType(Set.class, Object[].class));
        map1Factory =
            _JDKAccess._trustedLookup(Map1)
                .findConstructor(
                    Map1, MethodType.methodType(void.class, Object.class, Object.class));
        mapNFactory =
            _JDKAccess._trustedLookup(MapN)
                .findConstructor(MapN, MethodType.methodType(void.class, Object[].class));
      } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
        e.printStackTrace();
        Platform.throwException(e);
      }
    } else {
      // Use stub class as placeholder to ensure jdk8 registered id consistent with JDK9+.
      class List12Stub {}

      class ListNStub {}

      class SubListStub {}

      class Set12Stub {}

      class SetNStub {}

      class Map1Stub {}

      class MapNStub {}

      List12 = List12Stub.class;
      ListN = ListNStub.class;
      SubList = SubListStub.class;
      Set12 = Set12Stub.class;
      SetN = SetNStub.class;
      Map1 = Map1Stub.class;
      MapN = MapNStub.class;
    }
  }

  public static class ImmutableListSerializer extends CollectionSerializer {
    public ImmutableListSerializer(Fory fory, Class cls) {
      super(fory, cls, true);
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      if (Platform.JAVA_VERSION > 8) {
        return new CollectionContainer<>(numElements);
      } else {
        return new ArrayList(numElements);
      }
    }

    @Override
    public Collection copy(Collection originCollection) {
      if (Platform.JAVA_VERSION <= 8) {
        throw new UnsupportedOperationException(
            String.format(
                "Only support jdk9+ java.util.ImmutableCollections deep copy. %s",
                originCollection.getClass()));
      }
      Object[] elements = new Object[originCollection.size()];
      copyElements(originCollection, elements);
      try {
        return (List) listFactory.invoke(elements);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Collection onCollectionRead(Collection collection) {
      if (Platform.JAVA_VERSION > 8) {
        CollectionContainer container = (CollectionContainer) collection;
        try {
          collection = (List) listFactory.invoke(container.elements);
        } catch (Throwable e) {
          throw new RuntimeException(e);
        }
      } else {
        collection = Collections.unmodifiableList((List) collection);
      }
      return collection;
    }
  }

  public static class ImmutableSetSerializer extends CollectionSerializer {
    public ImmutableSetSerializer(Fory fory, Class cls) {
      super(fory, cls, true);
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      if (Platform.JAVA_VERSION > 8) {
        return new CollectionContainer<>(numElements);
      } else {
        return new HashSet(numElements);
      }
    }

    @Override
    public Collection copy(Collection originCollection) {
      if (Platform.JAVA_VERSION <= 8) {
        throw new UnsupportedOperationException(
            String.format(
                "Only support jdk9+ java.util.ImmutableCollections deep copy. %s",
                originCollection.getClass()));
      }
      Object[] elements = new Object[originCollection.size()];
      copyElements(originCollection, elements);
      try {
        return (Set) setFactory.invoke(elements);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Collection onCollectionRead(Collection collection) {
      if (Platform.JAVA_VERSION > 8) {
        CollectionContainer container = (CollectionContainer) collection;
        try {
          collection = (Set) setFactory.invoke(container.elements);
        } catch (Throwable e) {
          throw new RuntimeException(e);
        }
      } else {
        collection = Collections.unmodifiableSet((HashSet) collection);
      }
      return collection;
    }
  }

  public static class ImmutableMapSerializer extends MapSerializer {
    public ImmutableMapSerializer(Fory fory, Class cls) {
      super(fory, cls, true);
    }

    @Override
    public Map newMap(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      if (Platform.JAVA_VERSION > 8) {
        return new JDKImmutableMapContainer(numElements);
      } else {
        return new HashMap(numElements);
      }
    }

    @Override
    public Map copy(Map originMap) {
      if (Platform.JAVA_VERSION <= 8) {
        throw new UnsupportedOperationException(
            String.format(
                "Only support jdk9+ java.util.ImmutableCollections deep copy. %s",
                originMap.getClass()));
      }
      int size = originMap.size();
      Object[] elements = new Object[size * 2];
      copyEntry(originMap, elements);
      try {
        if (size == 1) {
          return (Map) map1Factory.invoke(elements[0], elements[1]);
        } else {
          return (Map) mapNFactory.invoke(elements);
        }
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Map onMapRead(Map map) {
      if (Platform.JAVA_VERSION > 8) {
        JDKImmutableMapContainer container = (JDKImmutableMapContainer) map;
        try {
          if (container.size() == 1) {
            map = (Map) map1Factory.invoke(container.array[0], container.array[1]);
          } else {
            map = (Map) mapNFactory.invoke(container.array);
          }
        } catch (Throwable e) {
          throw new RuntimeException(e);
        }
      } else {
        map = Collections.unmodifiableMap(map);
      }
      return map;
    }
  }

  public static void registerSerializers(Fory fory) {
    TypeResolver resolver = fory.getTypeResolver();
    resolver.registerInternalSerializer(List12, new ImmutableListSerializer(fory, List12));
    resolver.registerInternalSerializer(ListN, new ImmutableListSerializer(fory, ListN));
    resolver.registerInternalSerializer(SubList, new ImmutableListSerializer(fory, SubList));
    resolver.registerInternalSerializer(Set12, new ImmutableSetSerializer(fory, Set12));
    resolver.registerInternalSerializer(SetN, new ImmutableSetSerializer(fory, SetN));
    resolver.registerInternalSerializer(Map1, new ImmutableMapSerializer(fory, Map1));
    resolver.registerInternalSerializer(MapN, new ImmutableMapSerializer(fory, MapN));
  }
}
