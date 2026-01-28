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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.ObjectSerializer;

@SuppressWarnings({"rawtypes", "unchecked"})
public class SubListSerializers {
  private static final Logger LOG = LoggerFactory.getLogger(SubListSerializers.class);

  private static final Class<?> SubListClass;
  private static final Class<?> RandomAccessSubListClass;
  private static final Class<?> ArrayListSubListClass;

  private interface Stub {}

  static {
    Class<?> sublistClass;
    try {
      sublistClass = Class.forName("java.util.SubList");
    } catch (ClassNotFoundException e) {
      sublistClass = ReflectionUtils.loadClass("java.util.AbstractList$SubList");
    }
    SubListClass = sublistClass;
    Class<?> randomAccessSubListClass;
    try {
      randomAccessSubListClass = Class.forName("java.util.RandomAccessSubList");
    } catch (ClassNotFoundException e) {
      randomAccessSubListClass =
          ReflectionUtils.loadClass("java.util.AbstractList$RandomAccessSubList");
    }
    RandomAccessSubListClass = randomAccessSubListClass;
    try {
      ArrayListSubListClass = Class.forName("java.util.ArrayList$SubList");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public static void registerSerializers(Fory fory, boolean preserveView) {
    TypeResolver classResolver = fory.getTypeResolver();
    // java.util.ImmutableCollections$SubList is already registered in
    // ImmutableCollectionSerializers
    for (Class<?> cls :
        new Class[] {SubListClass, RandomAccessSubListClass, ArrayListSubListClass}) {
      if (fory.trackingRef() && preserveView && !fory.isCrossLanguage()) {
        classResolver.registerInternalSerializer(cls, new SubListViewSerializer(fory, cls));
      } else {
        classResolver.registerInternalSerializer(
            cls, new SubListSerializer(fory, (Class<List>) cls));
      }
    }
  }

  public static final class SubListViewSerializer extends CollectionSerializer<List> {
    private ObjectSerializer dataSerializer;
    private boolean serializedBefore;

    public SubListViewSerializer(Fory fory, Class cls) {
      super(fory, Stub.class.isAssignableFrom(cls) ? (Class<List>) ArrayListSubListClass : cls);
      assert !fory.isCrossLanguage();
    }

    @Override
    public Collection onCollectionWrite(MemoryBuffer buffer, List value) {
      throw new IllegalStateException();
    }

    @Override
    public List onCollectionRead(Collection collection) {
      throw new IllegalStateException();
    }

    @Override
    public void write(MemoryBuffer buffer, List value) {
      checkSerialization(value);
      (getObjectSerializer()).write(buffer, value);
    }

    @Override
    public List read(MemoryBuffer buffer) {
      List value = (List) (getObjectSerializer()).read(buffer);
      checkSerialization(value);
      return value;
    }

    private ObjectSerializer getObjectSerializer() {
      ObjectSerializer dataSerializer = this.dataSerializer;
      if (dataSerializer == null) {
        dataSerializer = this.dataSerializer = new ObjectSerializer(fory, type);
      }
      return dataSerializer;
    }

    @Override
    public List copy(List value) {
      throw new UnsupportedOperationException(
          "parent list didn't copy modCount, but sublist does copy it");
    }

    private void checkSerialization(Object value) {
      if (!serializedBefore) {
        serializedBefore = true;
        LOG.warn(
            "List view of type {} is being serialized/deserialized, this is not recommended, please don't "
                + "serialize such types, it's not allowed for serialization by JDK too. "
                + "To ensure consistency between view and the raw List, we must serialize raw List together even if "
                + "the raw List is not referenced other places. This may cause extra data be serialized if the original List "
                + "is not referenced in the object graph being serialized, but this is necessary. "
                + "Otherwise, serializing multiple view of same original list will bring data duplication, "
                + "and if you update the view, the original list won't be updated too. "
                + "If you want to serialize SubList view as a standard List, you can register a serializer by "
                + "`fory.registerSerializer(cls, new SubListSerializer(fory, (Class<List>) cls))`, object type of deserialized "
                + "value will be {}",
            value.getClass(),
            ArrayList.class);
      }
    }
  }

  public static final class SubListSerializer extends CollectionSerializer {

    public SubListSerializer(Fory fory, Class<List> type) {
      super(fory, type, true);
      fory.getClassResolver().setSerializer(type, this);
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
      int numElements = buffer.readVarUint32Small7();
      setNumElements(numElements);
      return new ArrayList(numElements);
    }

    @Override
    public Collection newCollection(Collection collection) {
      return new ArrayList(collection.size());
    }
  }
}
