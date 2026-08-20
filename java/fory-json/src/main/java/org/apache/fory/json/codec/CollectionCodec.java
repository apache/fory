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

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.AbstractQueue;
import java.util.AbstractSequentialList;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.NavigableSet;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonArray;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.GraphMemoryEstimates;

/**
 * Codec family for declared Java collection types.
 *
 * <p>{@link #create(Class, TypeRef, JsonTypeResolver)} consumes the declared {@link TypeRef} during
 * cold construction, resolves the element binding once, and selects a direct scalar specialization
 * when the exact built-in element codec permits it. Runtime codecs retain only the collection
 * factory and resolved element state required by their loops; they do not retain the construction
 * {@code TypeRef}. Generic and object-element codecs load one concrete child capability for the
 * active representation before iterating.
 *
 * <p>The collection factory owns the requested concrete collection shape and any immutable finish
 * conversion. Readers start with un-sized storage because a JSON array provides no trusted element
 * count. Dynamic {@code Object} arrays are materialized as {@link JsonArray}, while typed targets
 * use their selected factory.
 */
public abstract class CollectionCodec<T extends Collection<?>> implements JsonValueCodec<T> {
  private static final Class<?> UNTYPED_COLLECTION = ArrayList.class;
  private static final int REFERENCE_BYTES = GraphMemoryEstimates.REFERENCE_BYTES;
  // Reserve before each batch's final child read. This leaves at most 1023 reference slots
  // pending while avoiding a graph-budget call for every element.
  private static final int REFERENCE_BATCH_SIZE = 1024;
  private static final int REFERENCE_BATCH_MASK = REFERENCE_BATCH_SIZE - 1;
  private static final int REFERENCE_BATCH_BYTES = REFERENCE_BATCH_SIZE * REFERENCE_BYTES;
  // Fixed-size ArrayList reads reserve inline at allocation branches so this Java 8 target selects
  // the int budget path without a forwarding accessor.
  static final int ARRAY_LIST_OWNER_BYTES =
      GraphMemoryEstimates.shallowObjectBytes(ArrayList.class);
  private static final int JSON_ARRAY_OWNER_BYTES =
      GraphMemoryEstimates.shallowObjectBytes(JsonArray.class);

  private final CollectionFactory factory;
  private final boolean createsArrayList;

  CollectionCodec(CollectionFactory factory) {
    this.factory = factory;
    this.createsArrayList = factory.createsArrayList();
  }

  private static Object requireElement(JsonTypeInfo type, Object element) {
    if (element == null && type.rejectsNull()) {
      type.rejectNullValue();
    }
    return element;
  }

  private static void requireWriteElement(JsonTypeInfo type, Object element) {
    if (element == null && type.rejectsNull()) {
      type.rejectNullValue();
    }
  }

  public static CollectionCodec<?> create(
      Class<?> rawType, TypeRef<?> typeRef, JsonTypeResolver resolver) {
    TypeRef<?> elementTypeRef = CodecUtils.elementTypeRef(typeRef);
    Type elementType = elementTypeRef.getType();
    Class<?> elementRawType = CodecUtils.rawType(elementType, Object.class);
    CollectionFactory factory = collectionFactory(rawType, elementRawType);
    JsonTypeInfo elementTypeInfo = resolver.getTypeInfo(elementTypeRef);
    return create(factory, elementTypeInfo, resolver.canonicalObjectCodec(elementTypeInfo) != null);
  }

  @Internal
  public static CollectionCodec<?> create(
      Class<?> rawType,
      Class<?> elementRawType,
      JsonTypeInfo elementTypeInfo,
      JsonTypeResolver resolver) {
    return create(
        collectionFactory(rawType, elementRawType),
        elementTypeInfo,
        resolver.canonicalObjectCodec(elementTypeInfo) != null);
  }

  private static CollectionCodec<?> create(
      CollectionFactory factory, JsonTypeInfo elementTypeInfo, boolean objectElement) {
    Object elementCodec = elementTypeInfo.stringWriter();
    if (elementCodec == ScalarCodecs.StringCodec.INSTANCE) {
      return new StringCollectionCodec(factory, elementTypeInfo);
    }
    if (elementCodec == ScalarCodecs.BooleanCodec.BOXED) {
      return new BooleanCollectionCodec(factory, elementTypeInfo);
    }
    if (elementCodec == ScalarCodecs.IntCodec.BOXED) {
      return new IntCollectionCodec(factory, elementTypeInfo);
    }
    if (elementCodec == ScalarCodecs.LongCodec.BOXED) {
      return new LongCollectionCodec(factory, elementTypeInfo);
    }
    if (elementCodec == ScalarCodecs.ShortCodec.BOXED) {
      return new ShortCollectionCodec(factory, elementTypeInfo);
    }
    if (elementCodec == ScalarCodecs.ByteCodec.BOXED) {
      return new ByteCollectionCodec(factory, elementTypeInfo);
    }
    if (elementCodec == ScalarCodecs.FloatCodec.BOXED) {
      return new FloatCollectionCodec(factory, elementTypeInfo);
    }
    if (elementCodec == ScalarCodecs.DoubleCodec.BOXED) {
      return new DoubleCollectionCodec(factory, elementTypeInfo);
    }
    if (elementCodec == ScalarCodecs.BigIntegerCodec.INSTANCE) {
      return new BigIntegerCollectionCodec(factory, elementTypeInfo);
    }
    if (elementCodec == ScalarCodecs.BigDecimalCodec.INSTANCE) {
      return new BigDecimalCollectionCodec(factory, elementTypeInfo);
    }
    if (objectElement) {
      return new ObjectCollectionCodec(factory, elementTypeInfo);
    }
    return new GenericCollectionCodec(factory, elementTypeInfo);
  }

  static Collection<Object> readUntyped(Latin1JsonReader reader) {
    JsonTypeInfo elementInfo = reader.typeResolver().getTypeInfo(Object.class, Object.class);
    reader.reserveGraphMemory(JSON_ARRAY_OWNER_BYTES);
    Collection<Object> collection = new JsonArray();
    Latin1ReaderCodec<Object> codec = elementInfo.latin1Reader();
    reader.enterDepth();
    reader.expectNextToken('[');
    int size = 0;
    if (!reader.consumeNextToken(']')) {
      do {
        if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
          reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
        }
        collection.add(codec.readLatin1(reader));
        size++;
      } while (reader.consumeNextCommaOrEndArray());
    }
    int tailSize = size & REFERENCE_BATCH_MASK;
    if (tailSize != 0) {
      reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
    }
    reader.exitDepth();
    return collection;
  }

  static Collection<Object> readUntyped(Utf16JsonReader reader) {
    JsonTypeInfo elementInfo = reader.typeResolver().getTypeInfo(Object.class, Object.class);
    reader.reserveGraphMemory(JSON_ARRAY_OWNER_BYTES);
    Collection<Object> collection = new JsonArray();
    Utf16ReaderCodec<Object> codec = elementInfo.utf16Reader();
    reader.enterDepth();
    reader.expectNextToken('[');
    int size = 0;
    if (!reader.consumeNextToken(']')) {
      do {
        if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
          reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
        }
        collection.add(codec.readUtf16(reader));
        size++;
      } while (reader.consumeNextCommaOrEndArray());
    }
    int tailSize = size & REFERENCE_BATCH_MASK;
    if (tailSize != 0) {
      reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
    }
    reader.exitDepth();
    return collection;
  }

  static Collection<Object> readUntyped(Utf8JsonReader reader) {
    JsonTypeInfo elementInfo = reader.typeResolver().getTypeInfo(Object.class, Object.class);
    reader.reserveGraphMemory(JSON_ARRAY_OWNER_BYTES);
    Collection<Object> collection = new JsonArray();
    Utf8ReaderCodec<Object> codec = elementInfo.utf8Reader();
    reader.enterDepth();
    reader.expectNextToken('[');
    int size = 0;
    if (!reader.consumeNextToken(']')) {
      do {
        if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
          reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
        }
        collection.add(codec.readUtf8(reader));
        size++;
      } while (reader.consumeNextCommaOrEndArray());
    }
    int tailSize = size & REFERENCE_BATCH_MASK;
    if (tailSize != 0) {
      reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
    }
    reader.exitDepth();
    return collection;
  }

  @Internal
  final Collection<Object> newCollection(JsonReader reader) {
    // JSON arrays do not carry a trusted size. Avoid speculative backing-array preallocation in
    // parser hot paths; it can waste memory for small arrays and amplify untrusted input.
    return factory.newCollection(reader);
  }

  @Internal
  final Collection<?> finishCollection(JsonReader reader, Collection<Object> collection) {
    return factory.finish(reader, collection);
  }

  @Internal
  public final boolean createsArrayList() {
    return createsArrayList;
  }

  public abstract T readLatin1(Latin1JsonReader reader);

  public abstract T readUtf16(Utf16JsonReader reader);

  public abstract T readUtf8(Utf8JsonReader reader);

  @SuppressWarnings("unchecked")
  private static CollectionFactory collectionFactory(Class<?> rawType, Class<?> elementRawType) {
    if (unsupportedCollectionType(rawType) || GuavaCodecs.isUnsupportedImmutableImpl(rawType)) {
      return unsupportedCollectionFactory(rawType);
    }
    CollectionFactory guavaFactory = GuavaCodecs.collectionFactory(rawType);
    if (guavaFactory != null) {
      return guavaFactory;
    }
    if (rawType == JsonArray.class) {
      return new CollectionFactory(JsonArray.class) {
        @Override
        public Collection<Object> newCollection(JsonReader reader) {
          reader.reserveGraphMemory(ownerBytes());
          return new JsonArray();
        }
      };
    }
    if (rawType == EnumSet.class) {
      if (!elementRawType.isEnum()) {
        throw new ForyJsonException("EnumSet requires an enum element type");
      }
      Class<? extends Enum> enumType = (Class<? extends Enum>) elementRawType;
      Collection<Object> empty = (Collection<Object>) EnumSet.noneOf(enumType);
      int ownerBytes = enumSetOwnerBytes(empty.getClass(), enumType.getEnumConstants().length);
      return new CollectionFactory(ownerBytes) {
        @Override
        public Collection<Object> newCollection(JsonReader reader) {
          reader.reserveGraphMemory(ownerBytes());
          return (Collection<Object>) EnumSet.noneOf(enumType);
        }
      };
    }
    if (rawType == AbstractSequentialList.class) {
      return new CollectionFactory(LinkedList.class) {
        @Override
        public Collection<Object> newCollection(JsonReader reader) {
          reader.reserveGraphMemory(ownerBytes());
          return new LinkedList<>();
        }
      };
    }
    if (rawType == AbstractList.class || rawType == AbstractCollection.class) {
      return CollectionFactory.ARRAY_LIST;
    }
    if (rawType == AbstractSet.class) {
      return new CollectionFactory(LinkedHashSet.class) {
        @Override
        public Collection<Object> newCollection(JsonReader reader) {
          reader.reserveGraphMemory(ownerBytes());
          return new LinkedHashSet<>();
        }
      };
    }
    if (rawType == AbstractQueue.class) {
      return new CollectionFactory(LinkedBlockingQueue.class) {
        @Override
        public Collection<Object> newCollection(JsonReader reader) {
          reader.reserveGraphMemory(ownerBytes());
          return new LinkedBlockingQueue<>();
        }
      };
    }
    if (rawType == UNTYPED_COLLECTION || rawType.isInterface()) {
      if (BlockingDeque.class.isAssignableFrom(rawType)) {
        return new CollectionFactory(LinkedBlockingDeque.class) {
          @Override
          public Collection<Object> newCollection(JsonReader reader) {
            reader.reserveGraphMemory(ownerBytes());
            return new LinkedBlockingDeque<>();
          }
        };
      }
      if (BlockingQueue.class.isAssignableFrom(rawType)) {
        return new CollectionFactory(LinkedBlockingQueue.class) {
          @Override
          public Collection<Object> newCollection(JsonReader reader) {
            reader.reserveGraphMemory(ownerBytes());
            return new LinkedBlockingQueue<>();
          }
        };
      }
      if (NavigableSet.class.isAssignableFrom(rawType)
          || SortedSet.class.isAssignableFrom(rawType)) {
        return new CollectionFactory(TreeSet.class) {
          @Override
          public Collection<Object> newCollection(JsonReader reader) {
            reader.reserveGraphMemory(ownerBytes());
            return new TreeSet<>();
          }
        };
      }
      if (Set.class.isAssignableFrom(rawType)) {
        return new CollectionFactory(LinkedHashSet.class) {
          @Override
          public Collection<Object> newCollection(JsonReader reader) {
            reader.reserveGraphMemory(ownerBytes());
            return new LinkedHashSet<>();
          }
        };
      }
      if (Queue.class.isAssignableFrom(rawType)) {
        return new CollectionFactory(ArrayDeque.class) {
          @Override
          public Collection<Object> newCollection(JsonReader reader) {
            reader.reserveGraphMemory(ownerBytes());
            return new ArrayDeque<>();
          }
        };
      }
      return CollectionFactory.ARRAY_LIST;
    }
    if (GraalvmSupport.isGraalRuntime()) {
      MethodHandle constructor = ReflectionUtils.getCtrHandle(rawType, new Class<?>[0]);
      return new CollectionFactory(rawType) {
        @Override
        public Collection<Object> newCollection(JsonReader reader) {
          reader.reserveGraphMemory(ownerBytes());
          try {
            return (Collection<Object>) constructor.invoke();
          } catch (Throwable e) {
            throw new ForyJsonException("Cannot create collection " + rawType, e);
          }
        }
      };
    }
    return new CollectionFactory(rawType) {
      @Override
      public Collection<Object> newCollection(JsonReader reader) {
        reader.reserveGraphMemory(ownerBytes());
        try {
          return (Collection<Object>) rawType.newInstance();
        } catch (ReflectiveOperationException e) {
          throw new ForyJsonException("Cannot create collection " + rawType, e);
        }
      }
    };
  }

  private static CollectionFactory unsupportedCollectionFactory(Class<?> rawType) {
    return new CollectionFactory(0) {
      @Override
      public Collection<Object> newCollection(JsonReader reader) {
        throw new ForyJsonException("Unsupported JSON collection type " + rawType);
      }
    };
  }

  private static boolean unsupportedCollectionType(Class<?> rawType) {
    if (ArrayBlockingQueue.class.isAssignableFrom(rawType)) {
      return true;
    }
    String name = rawType.getName();
    return name.startsWith("java.util.ImmutableCollections$")
        || name.equals("java.util.Arrays$ArrayList")
        || name.startsWith("java.util.Collections$Empty")
        || name.startsWith("java.util.Collections$Singleton")
        || name.startsWith("java.util.Collections$Unmodifiable");
  }

  private static int collectionOwnerBytes(Class<?> type) {
    if (LinkedHashSet.class.isAssignableFrom(type)) {
      return Math.addExact(
          GraphMemoryEstimates.shallowObjectBytes(type),
          GraphMemoryEstimates.shallowObjectBytes(LinkedHashMap.class));
    }
    if (HashSet.class.isAssignableFrom(type)) {
      return Math.addExact(
          GraphMemoryEstimates.shallowObjectBytes(type),
          GraphMemoryEstimates.shallowObjectBytes(HashMap.class));
    }
    if (TreeSet.class.isAssignableFrom(type)) {
      return Math.addExact(
          GraphMemoryEstimates.shallowObjectBytes(type),
          GraphMemoryEstimates.shallowObjectBytes(TreeMap.class));
    }
    return GraphMemoryEstimates.shallowObjectBytes(type);
  }

  private static int enumSetOwnerBytes(Class<?> type, int enumCount) {
    int ownerBytes = GraphMemoryEstimates.shallowObjectBytes(type);
    if (enumCount <= Long.SIZE) {
      return ownerBytes;
    }
    int wordBytes = Math.multiplyExact((enumCount + Long.SIZE - 1) / Long.SIZE, Long.BYTES);
    // Primitive and reference arrays share the same object-and-length header estimate.
    return Math.addExact(
        ownerBytes, Math.addExact(GraphMemoryEstimates.objectArrayBytes(), wordBytes));
  }

  abstract static class CollectionFactory {
    static final CollectionFactory ARRAY_LIST =
        new CollectionFactory(ARRAY_LIST_OWNER_BYTES) {
          @Override
          public Collection<Object> newCollection(JsonReader reader) {
            reader.reserveGraphMemory(ownerBytes());
            return new ArrayList<>(0);
          }

          @Override
          public boolean createsArrayList() {
            return true;
          }
        };

    private final int ownerBytes;

    CollectionFactory(Class<?> ownerType) {
      this(collectionOwnerBytes(ownerType));
    }

    CollectionFactory(int ownerBytes) {
      this.ownerBytes = ownerBytes;
    }

    abstract Collection<Object> newCollection(JsonReader reader);

    final int ownerBytes() {
      return ownerBytes;
    }

    Collection<?> finish(JsonReader reader, Collection<Object> collection) {
      return collection;
    }

    boolean createsArrayList() {
      return false;
    }
  }

  public abstract static class DirectCollectionCodec extends CollectionCodec<Collection<?>> {
    private final JsonTypeInfo elementTypeInfo;

    DirectCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory);
      this.elementTypeInfo = elementTypeInfo;
    }

    final Object requireElement(Object element) {
      return CollectionCodec.requireElement(elementTypeInfo, element);
    }

    final void requireWriteElement(Object element) {
      CollectionCodec.requireWriteElement(elementTypeInfo, element);
    }

    @Override
    public final Collection<?> readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      if (createsArrayList()) {
        return finishCollection(reader, readLatin1ArrayList(reader));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection(reader);
      reader.expectNextToken('[');
      int size = 0;
      if (!reader.consumeNextToken(']')) {
        do {
          if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
            reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
          }
          collection.add(requireElement(readLatin1Element(reader)));
          size++;
        } while (reader.consumeNextCommaOrEndArray());
      }
      int tailSize = size & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return finishCollection(reader, collection);
    }

    @Override
    public final Collection<?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      if (createsArrayList()) {
        return finishCollection(reader, readUtf16ArrayList(reader));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection(reader);
      reader.expectNextToken('[');
      int size = 0;
      if (!reader.consumeNextToken(']')) {
        do {
          if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
            reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
          }
          collection.add(requireElement(readUtf16Element(reader)));
          size++;
        } while (reader.consumeNextCommaOrEndArray());
      }
      int tailSize = size & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return finishCollection(reader, collection);
    }

    @Override
    public final Collection<?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      if (createsArrayList()) {
        return finishCollection(reader, readUtf8ArrayList(reader));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection(reader);
      reader.expectNextToken('[');
      int size = 0;
      if (!reader.consumeNextToken(']')) {
        do {
          if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
            reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
          }
          collection.add(requireElement(readUtf8Element(reader)));
          size++;
        } while (reader.consumeNextCommaOrEndArray());
      }
      int tailSize = size & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return finishCollection(reader, collection);
    }

    private ArrayList<Object> readLatin1ArrayList(Latin1JsonReader reader) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES);
        return new ArrayList<>(0);
      }
      Object e0 = requireElement(readLatin1Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = requireElement(readLatin1Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 2 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = requireElement(readLatin1Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 3 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = requireElement(readLatin1Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 4 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(4);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        return list;
      }
      // Keep this real exact-allocation prefix in the collection owner. Splitting here makes each
      // method smaller than C2's hot-inline limit, so a generated caller can absorb the collection
      // and element closure solely according to compilation order. The uncommon longer tail stays
      // separate below.
      Object e4 = requireElement(readLatin1Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 5 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      Object e5 = requireElement(readLatin1Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 6 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(6);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        return list;
      }
      return readLatin1ArrayListLongTail(reader, e0, e1, e2, e3, e4, e5);
    }

    private ArrayList<Object> readLatin1ArrayListLongTail(
        Latin1JsonReader reader, Object e0, Object e1, Object e2, Object e3, Object e4, Object e5) {
      Object e6 = requireElement(readLatin1Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 7 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(7);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        return list;
      }
      Object e7 = requireElement(readLatin1Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 8 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(8);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        list.add(e7);
        return list;
      }
      reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 9 * REFERENCE_BYTES);
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      list.add(requireElement(readLatin1Element(reader)));
      int pendingSize = 0;
      while (reader.consumeNextCommaOrEndArray()) {
        if ((pendingSize & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
          reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
        }
        list.add(requireElement(readLatin1Element(reader)));
        pendingSize++;
      }
      int tailSize = pendingSize & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return list;
    }

    private ArrayList<Object> readUtf16ArrayList(Utf16JsonReader reader) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES);
        return new ArrayList<>(0);
      }
      Object e0 = requireElement(readUtf16Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = requireElement(readUtf16Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 2 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = requireElement(readUtf16Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 3 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = requireElement(readUtf16Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 4 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(4);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        return list;
      }
      return readUtf16ArrayListTail(reader, e0, e1, e2, e3);
    }

    private ArrayList<Object> readUtf16ArrayListTail(
        Utf16JsonReader reader, Object e0, Object e1, Object e2, Object e3) {
      Object e4 = requireElement(readUtf16Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 5 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      Object e5 = requireElement(readUtf16Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 6 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(6);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        return list;
      }
      return readUtf16ArrayListLongTail(reader, e0, e1, e2, e3, e4, e5);
    }

    private ArrayList<Object> readUtf16ArrayListLongTail(
        Utf16JsonReader reader, Object e0, Object e1, Object e2, Object e3, Object e4, Object e5) {
      Object e6 = requireElement(readUtf16Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 7 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(7);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        return list;
      }
      Object e7 = requireElement(readUtf16Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 8 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(8);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        list.add(e7);
        return list;
      }
      reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 9 * REFERENCE_BYTES);
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      list.add(requireElement(readUtf16Element(reader)));
      int pendingSize = 0;
      while (reader.consumeNextCommaOrEndArray()) {
        if ((pendingSize & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
          reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
        }
        list.add(requireElement(readUtf16Element(reader)));
        pendingSize++;
      }
      int tailSize = pendingSize & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return list;
    }

    private ArrayList<Object> readUtf8ArrayList(Utf8JsonReader reader) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES);
        return new ArrayList<>(0);
      }
      Object e0 = requireElement(readUtf8Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = requireElement(readUtf8Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 2 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = requireElement(readUtf8Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 3 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = requireElement(readUtf8Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 4 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(4);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        return list;
      }
      return readUtf8ArrayListTail(reader, e0, e1, e2, e3);
    }

    private ArrayList<Object> readUtf8ArrayListTail(
        Utf8JsonReader reader, Object e0, Object e1, Object e2, Object e3) {
      Object e4 = requireElement(readUtf8Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 5 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      Object e5 = requireElement(readUtf8Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 6 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(6);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        return list;
      }
      return readUtf8ArrayListLongTail(reader, e0, e1, e2, e3, e4, e5);
    }

    private ArrayList<Object> readUtf8ArrayListLongTail(
        Utf8JsonReader reader, Object e0, Object e1, Object e2, Object e3, Object e4, Object e5) {
      Object e6 = requireElement(readUtf8Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 7 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(7);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        return list;
      }
      Object e7 = requireElement(readUtf8Element(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 8 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(8);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        list.add(e7);
        return list;
      }
      reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 9 * REFERENCE_BYTES);
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      list.add(requireElement(readUtf8Element(reader)));
      int pendingSize = 0;
      while (reader.consumeNextCommaOrEndArray()) {
        if ((pendingSize & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
          reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
        }
        list.add(requireElement(readUtf8Element(reader)));
        pendingSize++;
      }
      int tailSize = pendingSize & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return list;
    }

    abstract Object readLatin1Element(Latin1JsonReader reader);

    abstract Object readUtf16Element(Utf16JsonReader reader);

    abstract Object readUtf8Element(Utf8JsonReader reader);
  }

  public static final class GenericCollectionCodec extends CollectionCodec<Collection<?>> {
    private final JsonTypeInfo elementTypeInfo;

    private GenericCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory);
      this.elementTypeInfo = elementTypeInfo;
    }

    @Override
    public void writeString(StringJsonWriter writer, Collection<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      StringWriterCodec<Object> codec = elementTypeInfo.stringWriter();
      writer.writeArrayStart();
      int index = 0;
      for (Object element : value) {
        writer.writeComma(index++);
        requireWriteElement(elementTypeInfo, element);
        codec.writeString(writer, element);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Collection<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Utf8WriterCodec<Object> codec = elementTypeInfo.utf8Writer();
      writer.writeArrayStart();
      int index = 0;
      for (Object element : value) {
        writer.writeComma(index++);
        requireWriteElement(elementTypeInfo, element);
        codec.writeUtf8(writer, element);
      }
      writer.writeArrayEnd();
    }

    @Override
    public Collection<?> readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection(reader);
      Latin1ReaderCodec<Object> codec = elementTypeInfo.latin1Reader();
      reader.expectNextToken('[');
      int size = 0;
      if (!reader.consumeNextToken(']')) {
        do {
          if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
            reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
          }
          collection.add(requireElement(elementTypeInfo, codec.readLatin1(reader)));
          size++;
        } while (reader.consumeNextCommaOrEndArray());
      }
      int tailSize = size & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return finishCollection(reader, collection);
    }

    @Override
    public Collection<?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection(reader);
      Utf16ReaderCodec<Object> codec = elementTypeInfo.utf16Reader();
      reader.expectNextToken('[');
      int size = 0;
      if (!reader.consumeNextToken(']')) {
        do {
          if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
            reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
          }
          collection.add(requireElement(elementTypeInfo, codec.readUtf16(reader)));
          size++;
        } while (reader.consumeNextCommaOrEndArray());
      }
      int tailSize = size & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return finishCollection(reader, collection);
    }

    @Override
    public Collection<?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection(reader);
      Utf8ReaderCodec<Object> codec = elementTypeInfo.utf8Reader();
      reader.expectNextToken('[');
      int size = 0;
      if (!reader.consumeNextToken(']')) {
        do {
          if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
            reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
          }
          collection.add(requireElement(elementTypeInfo, codec.readUtf8(reader)));
          size++;
        } while (reader.consumeNextCommaOrEndArray());
      }
      int tailSize = size & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return finishCollection(reader, collection);
    }
  }

  public static final class ObjectCollectionCodec extends CollectionCodec<Collection<?>> {
    private final JsonTypeInfo elementTypeInfo;

    private ObjectCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory);
      this.elementTypeInfo = elementTypeInfo;
    }

    @Override
    public void writeString(StringJsonWriter writer, Collection<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      StringWriterCodec<Object> codec = elementTypeInfo.stringWriter();
      writer.writeArrayStart();
      if (value.getClass() == ArrayList.class) {
        ArrayList<?> list = (ArrayList<?>) value;
        for (int index = 0, size = list.size(); index < size; index++) {
          Object element = list.get(index);
          writer.writeComma(index);
          requireWriteElement(elementTypeInfo, element);
          codec.writeString(writer, element);
        }
      } else {
        int index = 0;
        for (Object element : value) {
          writer.writeComma(index++);
          requireWriteElement(elementTypeInfo, element);
          codec.writeString(writer, element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Collection<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      Utf8WriterCodec<Object> codec = elementTypeInfo.utf8Writer();
      writer.writeArrayStart();
      if (value.getClass() == ArrayList.class) {
        ArrayList<?> list = (ArrayList<?>) value;
        for (int index = 0, size = list.size(); index < size; index++) {
          Object element = list.get(index);
          writer.writeComma(index);
          requireWriteElement(elementTypeInfo, element);
          codec.writeUtf8(writer, element);
        }
      } else {
        int index = 0;
        for (Object element : value) {
          writer.writeComma(index++);
          requireWriteElement(elementTypeInfo, element);
          codec.writeUtf8(writer, element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public Collection<?> readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      Latin1ReaderCodec<Object> codec = elementTypeInfo.latin1Reader();
      if (createsArrayList()) {
        return finishCollection(reader, readLatin1ArrayList(reader, codec));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection(reader);
      reader.expectNextToken('[');
      int size = 0;
      if (!reader.consumeNextToken(']')) {
        do {
          if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
            reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
          }
          collection.add(requireElement(elementTypeInfo, codec.readLatin1(reader)));
          size++;
        } while (reader.consumeNextCommaOrEndArray());
      }
      int tailSize = size & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return finishCollection(reader, collection);
    }

    @Override
    public Collection<?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      Utf16ReaderCodec<Object> codec = elementTypeInfo.utf16Reader();
      if (createsArrayList()) {
        return finishCollection(reader, readUtf16ArrayList(reader, codec));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection(reader);
      reader.expectNextToken('[');
      int size = 0;
      if (!reader.consumeNextToken(']')) {
        do {
          if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
            reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
          }
          collection.add(requireElement(elementTypeInfo, codec.readUtf16(reader)));
          size++;
        } while (reader.consumeNextCommaOrEndArray());
      }
      int tailSize = size & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return finishCollection(reader, collection);
    }

    @Override
    public Collection<?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      Utf8ReaderCodec<Object> codec = elementTypeInfo.utf8Reader();
      if (createsArrayList()) {
        return finishCollection(reader, readUtf8ArrayList(reader, codec));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection(reader);
      reader.expectNextToken('[');
      int size = 0;
      if (!reader.consumeNextToken(']')) {
        do {
          if ((size & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
            reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
          }
          collection.add(requireElement(elementTypeInfo, codec.readUtf8(reader)));
          size++;
        } while (reader.consumeNextCommaOrEndArray());
      }
      int tailSize = size & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return finishCollection(reader, collection);
    }

    private ArrayList<Object> readLatin1ArrayList(
        Latin1JsonReader reader, Latin1ReaderCodec<Object> codec) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES);
        return new ArrayList<>(0);
      }
      Object e0 = requireElement(elementTypeInfo, codec.readLatin1(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = requireElement(elementTypeInfo, codec.readLatin1(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 2 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = requireElement(elementTypeInfo, codec.readLatin1(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 3 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = requireElement(elementTypeInfo, codec.readLatin1(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 4 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(4);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        return list;
      }
      // Keep this real exact-allocation prefix in the collection owner. Splitting here makes each
      // method smaller than C2's hot-inline limit, so a generated caller can absorb the collection
      // and element closure solely according to compilation order. The uncommon longer tail stays
      // separate below.
      Object e4 = requireElement(elementTypeInfo, codec.readLatin1(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 5 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      Object e5 = requireElement(elementTypeInfo, codec.readLatin1(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 6 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(6);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        return list;
      }
      return readLatin1ArrayListLongTail(reader, codec, e0, e1, e2, e3, e4, e5);
    }

    private ArrayList<Object> readLatin1ArrayListLongTail(
        Latin1JsonReader reader,
        Latin1ReaderCodec<Object> codec,
        Object e0,
        Object e1,
        Object e2,
        Object e3,
        Object e4,
        Object e5) {
      Object e6 = requireElement(elementTypeInfo, codec.readLatin1(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 7 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(7);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        return list;
      }
      Object e7 = requireElement(elementTypeInfo, codec.readLatin1(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 8 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(8);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        list.add(e7);
        return list;
      }
      // Capacity nine is materialized before the ninth child is read, so charge every backing
      // slot before allocating the list.
      reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 9 * REFERENCE_BYTES);
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      list.add(requireElement(elementTypeInfo, codec.readLatin1(reader)));
      int pendingSize = 0;
      while (reader.consumeNextCommaOrEndArray()) {
        if ((pendingSize & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
          reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
        }
        list.add(requireElement(elementTypeInfo, codec.readLatin1(reader)));
        pendingSize++;
      }
      int tailSize = pendingSize & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return list;
    }

    private ArrayList<Object> readUtf16ArrayList(
        Utf16JsonReader reader, Utf16ReaderCodec<Object> codec) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES);
        return new ArrayList<>(0);
      }
      Object e0 = requireElement(elementTypeInfo, codec.readUtf16(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = requireElement(elementTypeInfo, codec.readUtf16(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 2 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = requireElement(elementTypeInfo, codec.readUtf16(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 3 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = requireElement(elementTypeInfo, codec.readUtf16(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 4 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(4);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        return list;
      }
      return readUtf16ArrayListTail(reader, codec, e0, e1, e2, e3);
    }

    private ArrayList<Object> readUtf16ArrayListTail(
        Utf16JsonReader reader,
        Utf16ReaderCodec<Object> codec,
        Object e0,
        Object e1,
        Object e2,
        Object e3) {
      Object e4 = requireElement(elementTypeInfo, codec.readUtf16(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 5 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      Object e5 = requireElement(elementTypeInfo, codec.readUtf16(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 6 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(6);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        return list;
      }
      return readUtf16ArrayListLongTail(reader, codec, e0, e1, e2, e3, e4, e5);
    }

    private ArrayList<Object> readUtf16ArrayListLongTail(
        Utf16JsonReader reader,
        Utf16ReaderCodec<Object> codec,
        Object e0,
        Object e1,
        Object e2,
        Object e3,
        Object e4,
        Object e5) {
      Object e6 = requireElement(elementTypeInfo, codec.readUtf16(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 7 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(7);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        return list;
      }
      Object e7 = requireElement(elementTypeInfo, codec.readUtf16(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 8 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(8);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        list.add(e7);
        return list;
      }
      // Capacity nine is materialized before the ninth child is read, so charge every backing
      // slot before allocating the list.
      reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 9 * REFERENCE_BYTES);
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      list.add(requireElement(elementTypeInfo, codec.readUtf16(reader)));
      int pendingSize = 0;
      while (reader.consumeNextCommaOrEndArray()) {
        if ((pendingSize & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
          reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
        }
        list.add(requireElement(elementTypeInfo, codec.readUtf16(reader)));
        pendingSize++;
      }
      int tailSize = pendingSize & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return list;
    }

    private ArrayList<Object> readUtf8ArrayList(
        Utf8JsonReader reader, Utf8ReaderCodec<Object> codec) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES);
        return new ArrayList<>(0);
      }
      Object e0 = requireElement(elementTypeInfo, codec.readUtf8(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = requireElement(elementTypeInfo, codec.readUtf8(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 2 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = requireElement(elementTypeInfo, codec.readUtf8(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 3 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = requireElement(elementTypeInfo, codec.readUtf8(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 4 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(4);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        return list;
      }
      // Keep the fifth exact-allocation lane in the collection owner. If this lane is split after
      // four elements, both resulting methods fall below C2's hot-inline limit and let an outer
      // fallback caller absorb the object-element closure according to compilation order.
      Object e4 = requireElement(elementTypeInfo, codec.readUtf8(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 5 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      return readUtf8ArrayListTail(reader, codec, e0, e1, e2, e3, e4);
    }

    private ArrayList<Object> readUtf8ArrayListTail(
        Utf8JsonReader reader,
        Utf8ReaderCodec<Object> codec,
        Object e0,
        Object e1,
        Object e2,
        Object e3,
        Object e4) {
      Object e5 = requireElement(elementTypeInfo, codec.readUtf8(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 6 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(6);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        return list;
      }
      return readUtf8ArrayListLongTail(reader, codec, e0, e1, e2, e3, e4, e5);
    }

    private ArrayList<Object> readUtf8ArrayListLongTail(
        Utf8JsonReader reader,
        Utf8ReaderCodec<Object> codec,
        Object e0,
        Object e1,
        Object e2,
        Object e3,
        Object e4,
        Object e5) {
      Object e6 = requireElement(elementTypeInfo, codec.readUtf8(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 7 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(7);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        return list;
      }
      Object e7 = requireElement(elementTypeInfo, codec.readUtf8(reader));
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 8 * REFERENCE_BYTES);
        ArrayList<Object> list = new ArrayList<>(8);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        list.add(e5);
        list.add(e6);
        list.add(e7);
        return list;
      }
      // Capacity nine is materialized before the ninth child is read, so charge every backing
      // slot before allocating the list.
      reader.reserveGraphMemory(ARRAY_LIST_OWNER_BYTES + 9 * REFERENCE_BYTES);
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      list.add(requireElement(elementTypeInfo, codec.readUtf8(reader)));
      int pendingSize = 0;
      while (reader.consumeNextCommaOrEndArray()) {
        if ((pendingSize & REFERENCE_BATCH_MASK) == REFERENCE_BATCH_MASK) {
          reader.reserveGraphMemory(REFERENCE_BATCH_BYTES);
        }
        list.add(requireElement(elementTypeInfo, codec.readUtf8(reader)));
        pendingSize++;
      }
      int tailSize = pendingSize & REFERENCE_BATCH_MASK;
      if (tailSize != 0) {
        reader.reserveGraphMemory(tailSize * REFERENCE_BYTES);
      }
      reader.exitDepth();
      return list;
    }
  }

  public static final class StringCollectionCodec extends DirectCollectionCodec {
    private StringCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory, elementTypeInfo);
    }

    @Override
    public void writeString(StringJsonWriter writer, Collection<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeArrayStart();
      int index = 0;
      for (Object element : value) {
        requireWriteElement(element);
        writer.writeStringElement(index++, (String) element);
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Collection<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeArrayStart();
      int index = 0;
      for (Object element : value) {
        requireWriteElement(element);
        writer.writeStringElement(index++, (String) element);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.readNextNullableString();
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.readNextNullableString();
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.readNextNullableString();
    }
  }

  public static final class BooleanCollectionCodec extends DirectCollectionCodec {
    private BooleanCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory, elementTypeInfo);
    }

    @Override
    public void writeString(StringJsonWriter writer, Collection<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeArrayStart();
      int index = 0;
      for (Object element : value) {
        writer.writeComma(index++);
        if (element == null) {
          requireWriteElement(null);
          writer.writeNull();
        } else {
          writer.writeBoolean((boolean) element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Collection<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeArrayStart();
      int index = 0;
      for (Object element : value) {
        writer.writeComma(index++);
        if (element == null) {
          requireWriteElement(null);
          writer.writeNull();
        } else {
          writer.writeBoolean((boolean) element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextBooleanValue();
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextBooleanValue();
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextBooleanValue();
    }
  }

  public abstract static class NumberCollectionCodec extends DirectCollectionCodec {
    NumberCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory, elementTypeInfo);
    }

    @Override
    public final void writeString(StringJsonWriter writer, Collection<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeArrayStart();
      int index = 0;
      for (Object element : value) {
        writer.writeComma(index++);
        if (element == null) {
          requireWriteElement(null);
          writer.writeNull();
        } else {
          writeNumber(writer, element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    public final void writeUtf8(Utf8JsonWriter writer, Collection<?> value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeArrayStart();
      int index = 0;
      for (Object element : value) {
        writer.writeComma(index++);
        if (element == null) {
          requireWriteElement(null);
          writer.writeNull();
        } else {
          writeNumber(writer, element);
        }
      }
      writer.writeArrayEnd();
    }

    abstract void writeNumber(JsonWriter writer, Object value);
  }

  public static final class IntCollectionCodec extends NumberCollectionCodec {
    private IntCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory, elementTypeInfo);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeInt((int) value);
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextIntValue();
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextIntValue();
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextIntValue();
    }
  }

  public static final class LongCollectionCodec extends NumberCollectionCodec {
    private LongCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory, elementTypeInfo);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeLong((long) value);
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextLongValue();
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextLongValue();
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextLongValue();
    }
  }

  public static final class ShortCollectionCodec extends NumberCollectionCodec {
    private ShortCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory, elementTypeInfo);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeInt((short) value);
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : readShort(reader.readNextIntValue());
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : readShort(reader.readNextIntValue());
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : readShort(reader.readNextIntValue());
    }
  }

  public static final class ByteCollectionCodec extends NumberCollectionCodec {
    private ByteCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory, elementTypeInfo);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeInt((byte) value);
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : readByte(reader.readNextIntValue());
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : readByte(reader.readNextIntValue());
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : readByte(reader.readNextIntValue());
    }
  }

  public static final class FloatCollectionCodec extends NumberCollectionCodec {
    private FloatCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory, elementTypeInfo);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeFloat((float) value);
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextFloatValue();
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextFloatValue();
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextFloatValue();
    }
  }

  public static final class DoubleCollectionCodec extends NumberCollectionCodec {
    private DoubleCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory, elementTypeInfo);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeDouble((double) value);
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextDoubleValue();
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextDoubleValue();
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readNextDoubleValue();
    }
  }

  public static final class BigIntegerCollectionCodec extends NumberCollectionCodec {
    private BigIntegerCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory, elementTypeInfo);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeBigInteger((BigInteger) value);
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readBigInteger();
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readBigInteger();
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readBigInteger();
    }
  }

  public static final class BigDecimalCollectionCodec extends NumberCollectionCodec {
    private BigDecimalCollectionCodec(CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(factory, elementTypeInfo);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeBigDecimal((BigDecimal) value);
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readBigDecimal();
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readBigDecimal();
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : reader.readBigDecimal();
    }
  }

  private static short readShort(int value) {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw new ForyJsonException("Short overflow");
    }
    return (short) value;
  }

  private static byte readByte(int value) {
    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
      throw new ForyJsonException("Byte overflow");
    }
    return (byte) value;
  }
}
