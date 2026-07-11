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
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.NavigableSet;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonArray;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;

public abstract class CollectionCodec<T extends Collection<?>> implements JsonCodec<T> {
  private static final Class<?> UNTYPED_COLLECTION = ArrayList.class;

  private final TypeRef<?> typeRef;
  private final CollectionFactory factory;
  private final boolean createsArrayList;

  CollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
    this.typeRef = typeRef;
    this.factory = factory;
    this.createsArrayList = factory.createsArrayList();
  }

  public static CollectionCodec<?> create(
      Class<?> rawType, TypeRef<?> typeRef, JsonTypeResolver resolver) {
    TypeRef<?> elementTypeRef = CodecUtils.elementTypeRef(typeRef);
    Type elementType = elementTypeRef.getType();
    Class<?> elementRawType = CodecUtils.rawType(elementType, Object.class);
    CollectionFactory factory = collectionFactory(rawType, elementRawType);
    JsonTypeInfo elementTypeInfo = resolver.getTypeInfo(elementType, elementRawType);
    Object elementCodec = elementTypeInfo.stringWriter();
    if (elementCodec == ScalarCodecs.StringCodec.INSTANCE) {
      return new StringCollectionCodec(typeRef, factory);
    }
    if (elementCodec == ScalarCodecs.BooleanCodec.BOXED) {
      return new BooleanCollectionCodec(typeRef, factory);
    }
    if (elementCodec == ScalarCodecs.IntCodec.BOXED) {
      return new IntCollectionCodec(typeRef, factory);
    }
    if (elementCodec == ScalarCodecs.LongCodec.BOXED) {
      return new LongCollectionCodec(typeRef, factory);
    }
    if (elementCodec == ScalarCodecs.ShortCodec.BOXED) {
      return new ShortCollectionCodec(typeRef, factory);
    }
    if (elementCodec == ScalarCodecs.ByteCodec.BOXED) {
      return new ByteCollectionCodec(typeRef, factory);
    }
    if (elementCodec == ScalarCodecs.FloatCodec.BOXED) {
      return new FloatCollectionCodec(typeRef, factory);
    }
    if (elementCodec == ScalarCodecs.DoubleCodec.BOXED) {
      return new DoubleCollectionCodec(typeRef, factory);
    }
    if (elementCodec == ScalarCodecs.BigIntegerCodec.INSTANCE) {
      return new BigIntegerCollectionCodec(typeRef, factory);
    }
    if (elementCodec == ScalarCodecs.BigDecimalCodec.INSTANCE) {
      return new BigDecimalCollectionCodec(typeRef, factory);
    }
    if (elementTypeInfo.usesDefaultObjectCodec()) {
      return new ObjectCollectionCodec(typeRef, factory, elementTypeInfo);
    }
    return new GenericCollectionCodec(typeRef, factory, elementTypeInfo);
  }

  final TypeRef<?> typeRef() {
    return typeRef;
  }

  static Collection<Object> readUntyped(Latin1JsonReader reader) {
    JsonTypeInfo elementInfo = reader.typeResolver().getTypeInfo(Object.class, Object.class);
    Collection<Object> collection = new JsonArray();
    Latin1ReaderCodec<Object> codec = elementInfo.latin1Reader();
    reader.enterDepth();
    reader.expectNextToken('[');
    if (!reader.consumeNextToken(']')) {
      do {
        collection.add(codec.readLatin1(reader));
      } while (reader.consumeNextCommaOrEndArray());
    }
    reader.exitDepth();
    return collection;
  }

  static Collection<Object> readUntyped(Utf16JsonReader reader) {
    JsonTypeInfo elementInfo = reader.typeResolver().getTypeInfo(Object.class, Object.class);
    Collection<Object> collection = new JsonArray();
    Utf16ReaderCodec<Object> codec = elementInfo.utf16Reader();
    reader.enterDepth();
    reader.expectNextToken('[');
    if (!reader.consumeNextToken(']')) {
      do {
        collection.add(codec.readUtf16(reader));
      } while (reader.consumeNextCommaOrEndArray());
    }
    reader.exitDepth();
    return collection;
  }

  static Collection<Object> readUntyped(Utf8JsonReader reader) {
    JsonTypeInfo elementInfo = reader.typeResolver().getTypeInfo(Object.class, Object.class);
    Collection<Object> collection = new JsonArray();
    Utf8ReaderCodec<Object> codec = elementInfo.utf8Reader();
    reader.enterDepth();
    reader.expectNextToken('[');
    if (!reader.consumeNextToken(']')) {
      do {
        collection.add(codec.readUtf8(reader));
      } while (reader.consumeNextCommaOrEndArray());
    }
    reader.exitDepth();
    return collection;
  }

  @Internal
  public final Collection<Object> newCollection() {
    // JSON arrays do not carry a trusted size. Avoid speculative backing-array preallocation in
    // parser hot paths; it can waste memory for small arrays and amplify untrusted input.
    return factory.newCollection();
  }

  @Internal
  public final Collection<?> finishCollection(Collection<Object> collection) {
    return factory.finish(collection);
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
      return JsonArray::new;
    }
    if (rawType == EnumSet.class) {
      if (!elementRawType.isEnum()) {
        throw new ForyJsonException("EnumSet requires an enum element type");
      }
      Class<? extends Enum> enumType = (Class<? extends Enum>) elementRawType;
      return () -> (Collection<Object>) EnumSet.noneOf(enumType);
    }
    if (rawType == AbstractSequentialList.class) {
      return LinkedList::new;
    }
    if (rawType == AbstractList.class || rawType == AbstractCollection.class) {
      return CollectionFactory.ARRAY_LIST;
    }
    if (rawType == AbstractSet.class) {
      return LinkedHashSet::new;
    }
    if (rawType == AbstractQueue.class) {
      return LinkedBlockingQueue::new;
    }
    if (rawType == UNTYPED_COLLECTION || rawType.isInterface()) {
      if (BlockingDeque.class.isAssignableFrom(rawType)) {
        return LinkedBlockingDeque::new;
      }
      if (BlockingQueue.class.isAssignableFrom(rawType)) {
        return LinkedBlockingQueue::new;
      }
      if (NavigableSet.class.isAssignableFrom(rawType)
          || SortedSet.class.isAssignableFrom(rawType)) {
        return TreeSet::new;
      }
      if (Set.class.isAssignableFrom(rawType)) {
        return LinkedHashSet::new;
      }
      if (Queue.class.isAssignableFrom(rawType)) {
        return ArrayDeque::new;
      }
      return CollectionFactory.ARRAY_LIST;
    }
    return () -> {
      try {
        return (Collection<Object>) rawType.newInstance();
      } catch (ReflectiveOperationException e) {
        throw new ForyJsonException("Cannot create collection " + rawType, e);
      }
    };
  }

  private static CollectionFactory unsupportedCollectionFactory(Class<?> rawType) {
    return () -> {
      throw new ForyJsonException("Unsupported JSON collection type " + rawType);
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

  interface CollectionFactory {
    CollectionFactory ARRAY_LIST =
        new CollectionFactory() {
          @Override
          public Collection<Object> newCollection() {
            return new ArrayList<>(0);
          }

          @Override
          public boolean createsArrayList() {
            return true;
          }
        };

    Collection<Object> newCollection();

    default Collection<?> finish(Collection<Object> collection) {
      return collection;
    }

    default boolean createsArrayList() {
      return false;
    }
  }

  public abstract static class DirectCollectionCodec extends CollectionCodec<Collection<?>> {
    DirectCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
    }

    @Override
    public final Collection<?> readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      if (createsArrayList()) {
        return finishCollection(readLatin1ArrayList(reader));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(readLatin1Element(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return finishCollection(collection);
    }

    @Override
    public final Collection<?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      if (createsArrayList()) {
        return finishCollection(readUtf16ArrayList(reader));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(readUtf16Element(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return finishCollection(collection);
    }

    @Override
    public final Collection<?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      if (createsArrayList()) {
        return finishCollection(readUtf8ArrayList(reader));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(readUtf8Element(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return finishCollection(collection);
    }

    private ArrayList<Object> readLatin1ArrayList(Latin1JsonReader reader) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new ArrayList<>(0);
      }
      Object e0 = readLatin1Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = readLatin1Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = readLatin1Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = readLatin1Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(4);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        return list;
      }
      return readLatin1ArrayListTail(reader, e0, e1, e2, e3);
    }

    private ArrayList<Object> readLatin1ArrayListTail(
        Latin1JsonReader reader, Object e0, Object e1, Object e2, Object e3) {
      Object e4 = readLatin1Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      Object e5 = readLatin1Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e6 = readLatin1Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e7 = readLatin1Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      do {
        list.add(readLatin1Element(reader));
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return list;
    }

    private ArrayList<Object> readUtf16ArrayList(Utf16JsonReader reader) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new ArrayList<>(0);
      }
      Object e0 = readUtf16Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = readUtf16Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = readUtf16Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = readUtf16Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e4 = readUtf16Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      Object e5 = readUtf16Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e6 = readUtf16Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e7 = readUtf16Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      do {
        list.add(readUtf16Element(reader));
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return list;
    }

    private ArrayList<Object> readUtf8ArrayList(Utf8JsonReader reader) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new ArrayList<>(0);
      }
      Object e0 = readUtf8Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = readUtf8Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = readUtf8Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = readUtf8Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e4 = readUtf8Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      Object e5 = readUtf8Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e6 = readUtf8Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e7 = readUtf8Element(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      do {
        list.add(readUtf8Element(reader));
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return list;
    }

    abstract Object readLatin1Element(Latin1JsonReader reader);

    abstract Object readUtf16Element(Utf16JsonReader reader);

    abstract Object readUtf8Element(Utf8JsonReader reader);
  }

  public static final class GenericCollectionCodec extends CollectionCodec<Collection<?>> {
    private final JsonTypeInfo elementTypeInfo;

    private GenericCollectionCodec(
        TypeRef<?> typeRef, CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(typeRef, factory);
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
      Collection<Object> collection = newCollection();
      Latin1ReaderCodec<Object> codec = elementTypeInfo.latin1Reader();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(codec.readLatin1(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return finishCollection(collection);
    }

    @Override
    public Collection<?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      Utf16ReaderCodec<Object> codec = elementTypeInfo.utf16Reader();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(codec.readUtf16(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return finishCollection(collection);
    }

    @Override
    public Collection<?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      Utf8ReaderCodec<Object> codec = elementTypeInfo.utf8Reader();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(codec.readUtf8(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return finishCollection(collection);
    }
  }

  public static final class ObjectCollectionCodec extends CollectionCodec<Collection<?>> {
    private final JsonTypeInfo elementTypeInfo;

    private ObjectCollectionCodec(
        TypeRef<?> typeRef, CollectionFactory factory, JsonTypeInfo elementTypeInfo) {
      super(typeRef, factory);
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
          codec.writeString(writer, element);
        }
      } else {
        int index = 0;
        for (Object element : value) {
          writer.writeComma(index++);
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
          codec.writeUtf8(writer, element);
        }
      } else {
        int index = 0;
        for (Object element : value) {
          writer.writeComma(index++);
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
        return finishCollection(readLatin1ArrayList(reader, codec));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(codec.readLatin1(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return finishCollection(collection);
    }

    @Override
    public Collection<?> readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      Utf16ReaderCodec<Object> codec = elementTypeInfo.utf16Reader();
      if (createsArrayList()) {
        return finishCollection(readUtf16ArrayList(reader, codec));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(codec.readUtf16(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return finishCollection(collection);
    }

    @Override
    public Collection<?> readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      Utf8ReaderCodec<Object> codec = elementTypeInfo.utf8Reader();
      if (createsArrayList()) {
        return finishCollection(readUtf8ArrayList(reader, codec));
      }
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(codec.readUtf8(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return finishCollection(collection);
    }

    private ArrayList<Object> readLatin1ArrayList(
        Latin1JsonReader reader, Latin1ReaderCodec<Object> codec) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new ArrayList<>(0);
      }
      Object e0 = codec.readLatin1(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = codec.readLatin1(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = codec.readLatin1(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = codec.readLatin1(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(4);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        return list;
      }
      return readLatin1ArrayListTail(reader, codec, e0, e1, e2, e3);
    }

    private ArrayList<Object> readLatin1ArrayListTail(
        Latin1JsonReader reader,
        Latin1ReaderCodec<Object> codec,
        Object e0,
        Object e1,
        Object e2,
        Object e3) {
      Object e4 = codec.readLatin1(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      Object e5 = codec.readLatin1(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e6 = codec.readLatin1(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e7 = codec.readLatin1(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      do {
        list.add(codec.readLatin1(reader));
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return list;
    }

    private ArrayList<Object> readUtf16ArrayList(
        Utf16JsonReader reader, Utf16ReaderCodec<Object> codec) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new ArrayList<>(0);
      }
      Object e0 = codec.readUtf16(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = codec.readUtf16(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = codec.readUtf16(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = codec.readUtf16(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e4 = codec.readUtf16(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      Object e5 = codec.readUtf16(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e6 = codec.readUtf16(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e7 = codec.readUtf16(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      do {
        list.add(codec.readUtf16(reader));
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return list;
    }

    private ArrayList<Object> readUtf8ArrayList(
        Utf8JsonReader reader, Utf8ReaderCodec<Object> codec) {
      reader.enterDepth();
      reader.expectNextToken('[');
      if (reader.consumeNextToken(']')) {
        reader.exitDepth();
        return new ArrayList<>(0);
      }
      Object e0 = codec.readUtf8(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(1);
        list.add(e0);
        return list;
      }
      Object e1 = codec.readUtf8(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(2);
        list.add(e0);
        list.add(e1);
        return list;
      }
      Object e2 = codec.readUtf8(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(3);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        return list;
      }
      Object e3 = codec.readUtf8(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(4);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        return list;
      }
      return readUtf8ArrayListTail(reader, codec, e0, e1, e2, e3);
    }

    private ArrayList<Object> readUtf8ArrayListTail(
        Utf8JsonReader reader,
        Utf8ReaderCodec<Object> codec,
        Object e0,
        Object e1,
        Object e2,
        Object e3) {
      Object e4 = codec.readUtf8(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
        ArrayList<Object> list = new ArrayList<>(5);
        list.add(e0);
        list.add(e1);
        list.add(e2);
        list.add(e3);
        list.add(e4);
        return list;
      }
      Object e5 = codec.readUtf8(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e6 = codec.readUtf8(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      Object e7 = codec.readUtf8(reader);
      if (!reader.consumeNextCommaOrEndArray()) {
        reader.exitDepth();
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
      ArrayList<Object> list = new ArrayList<>(9);
      list.add(e0);
      list.add(e1);
      list.add(e2);
      list.add(e3);
      list.add(e4);
      list.add(e5);
      list.add(e6);
      list.add(e7);
      do {
        list.add(codec.readUtf8(reader));
      } while (reader.consumeNextCommaOrEndArray());
      reader.exitDepth();
      return list;
    }
  }

  public static final class StringCollectionCodec extends DirectCollectionCodec {
    private StringCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
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
    private BooleanCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
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
    NumberCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
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
    private IntCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
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
    private LongCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
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
    private ShortCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
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
    private ByteCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
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
    private FloatCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
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
    private DoubleCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
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
    private BigIntegerCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
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
    private BigDecimalCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
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
