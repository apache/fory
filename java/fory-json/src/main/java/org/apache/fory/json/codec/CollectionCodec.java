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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.NavigableSet;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JSONArray;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;

public abstract class CollectionCodec extends AbstractJsonCodec {
  private static final Class<?> UNTYPED_COLLECTION = ArrayList.class;

  private final TypeRef<?> typeRef;
  private final CollectionFactory factory;

  CollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
    this.typeRef = typeRef;
    this.factory = factory;
  }

  public static CollectionCodec create(
      Class<?> rawType, TypeRef<?> typeRef, JsonTypeResolver resolver) {
    TypeRef<?> elementTypeRef = CodecUtils.elementTypeRef(typeRef);
    Type elementType = elementTypeRef.getType();
    Class<?> elementRawType = CodecUtils.rawType(elementType, Object.class);
    CollectionFactory factory = collectionFactory(rawType, elementRawType);
    if (elementRawType == String.class) {
      return new StringCollectionCodec(typeRef, factory);
    }
    if (elementRawType == Boolean.class || elementRawType == boolean.class) {
      return new BooleanCollectionCodec(typeRef, factory);
    }
    if (elementRawType == Integer.class || elementRawType == int.class) {
      return new IntCollectionCodec(typeRef, factory);
    }
    if (elementRawType == Long.class || elementRawType == long.class) {
      return new LongCollectionCodec(typeRef, factory);
    }
    if (elementRawType == Short.class || elementRawType == short.class) {
      return new ShortCollectionCodec(typeRef, factory);
    }
    if (elementRawType == Byte.class || elementRawType == byte.class) {
      return new ByteCollectionCodec(typeRef, factory);
    }
    if (elementRawType == Float.class || elementRawType == float.class) {
      return new FloatCollectionCodec(typeRef, factory);
    }
    if (elementRawType == Double.class || elementRawType == double.class) {
      return new DoubleCollectionCodec(typeRef, factory);
    }
    if (elementRawType == BigInteger.class) {
      return new BigIntegerCollectionCodec(typeRef, factory);
    }
    if (elementRawType == BigDecimal.class) {
      return new BigDecimalCollectionCodec(typeRef, factory);
    }
    JsonTypeInfo elementTypeInfo = resolver.getTypeInfo(elementType, elementRawType);
    JsonCodec elementCodec = elementTypeInfo.codec();
    if (elementCodec instanceof BaseObjectCodec) {
      return new ObjectCollectionCodec(
          typeRef, factory, elementTypeInfo, (BaseObjectCodec) elementCodec);
    }
    return new GenericCollectionCodec(typeRef, factory, elementTypeInfo, elementCodec);
  }

  final TypeRef<?> typeRef() {
    return typeRef;
  }

  static Collection<Object> readUntyped(JsonReader reader, JsonTypeResolver resolver) {
    JsonTypeInfo elementInfo = resolver.getTypeInfo(Object.class, Object.class);
    Collection<Object> collection = new JSONArray();
    readGeneric(reader, collection, elementInfo, elementInfo.codec(), resolver);
    return collection;
  }

  final Collection<Object> newCollection() {
    // JSON arrays do not carry a trusted size. Avoid speculative backing-array preallocation in
    // parser hot paths; it can waste memory for small arrays and amplify untrusted input.
    return factory.newCollection();
  }

  public abstract Object readLatin1NonNull(
      Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  public abstract Object readUtf16NonNull(
      Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  public abstract Object readUtf8NonNull(
      Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  private static void readGeneric(
      JsonReader reader,
      Collection<Object> collection,
      JsonTypeInfo elementInfo,
      JsonCodec elementCodec,
      JsonTypeResolver resolver) {
    reader.enterDepth();
    reader.expect('[');
    if (!reader.consume(']')) {
      do {
        collection.add(elementCodec.read(reader, elementInfo, resolver));
      } while (reader.consumeCommaOrEndArray());
    }
    reader.exitDepth();
  }

  @SuppressWarnings("unchecked")
  private static CollectionFactory collectionFactory(Class<?> rawType, Class<?> elementRawType) {
    if (rawType == JSONArray.class) {
      return JSONArray::new;
    }
    if (rawType == EnumSet.class) {
      if (!elementRawType.isEnum()) {
        throw new ForyJsonException("EnumSet requires an enum element type");
      }
      Class<? extends Enum> enumType = (Class<? extends Enum>) elementRawType;
      return () -> (Collection<Object>) EnumSet.noneOf(enumType);
    }
    if (rawType == UNTYPED_COLLECTION || rawType.isInterface()) {
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
      return () -> new ArrayList<>(0);
    }
    return () -> {
      try {
        return (Collection<Object>) rawType.newInstance();
      } catch (ReflectiveOperationException e) {
        throw new ForyJsonException("Cannot create collection " + rawType, e);
      }
    };
  }

  private interface CollectionFactory {
    Collection<Object> newCollection();
  }

  public abstract static class DirectCollectionCodec extends CollectionCodec {
    DirectCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
    }

    @Override
    final Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expect('[');
      if (!reader.consume(']')) {
        do {
          collection.add(readNullableElement(reader));
        } while (reader.consumeCommaOrEndArray());
      }
      reader.exitDepth();
      return collection;
    }

    @Override
    public final Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readLatin1NonNull(reader, typeInfo, resolver);
    }

    @Override
    public final Object readLatin1NonNull(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(readNullableLatin1Element(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return collection;
    }

    @Override
    public final Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readUtf16NonNull(reader, typeInfo, resolver);
    }

    @Override
    public final Object readUtf16NonNull(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(readNullableUtf16Element(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return collection;
    }

    @Override
    public final Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readUtf8NonNull(reader, typeInfo, resolver);
    }

    @Override
    public final Object readUtf8NonNull(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(readNullableUtf8Element(reader));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return collection;
    }

    abstract Object readElement(JsonReader reader);

    Object readNullableElement(JsonReader reader) {
      return reader.tryReadNull() ? null : readElement(reader);
    }

    Object readLatin1Element(Latin1JsonReader reader) {
      return readElement(reader);
    }

    Object readNullableLatin1Element(Latin1JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : readLatin1Element(reader);
    }

    Object readUtf16Element(Utf16JsonReader reader) {
      return readElement(reader);
    }

    Object readNullableUtf16Element(Utf16JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : readUtf16Element(reader);
    }

    Object readUtf8Element(Utf8JsonReader reader) {
      return readElement(reader);
    }

    Object readNullableUtf8Element(Utf8JsonReader reader) {
      return reader.tryReadNextNullToken() ? null : readUtf8Element(reader);
    }
  }

  public static final class GenericCollectionCodec extends CollectionCodec {
    private final JsonTypeInfo elementTypeInfo;
    private final JsonCodec elementCodec;

    private GenericCollectionCodec(
        TypeRef<?> typeRef,
        CollectionFactory factory,
        JsonTypeInfo elementTypeInfo,
        JsonCodec elementCodec) {
      super(typeRef, factory);
      this.elementTypeInfo = elementTypeInfo;
      this.elementCodec = elementCodec;
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
        writer.writeComma(index++);
        elementCodec.write(writer, element, resolver);
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
        writer.writeComma(index++);
        elementCodec.writeString(writer, element, resolver);
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
        writer.writeComma(index++);
        elementCodec.writeUtf8(writer, element, resolver);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      Collection<Object> collection = newCollection();
      readGeneric(reader, collection, elementTypeInfo, elementCodec, resolver);
      return collection;
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readLatin1NonNull(reader, typeInfo, resolver);
    }

    @Override
    public Object readLatin1NonNull(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(elementCodec.readLatin1(reader, elementTypeInfo, resolver));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return collection;
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readUtf16NonNull(reader, typeInfo, resolver);
    }

    @Override
    public Object readUtf16NonNull(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(elementCodec.readUtf16(reader, elementTypeInfo, resolver));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return collection;
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readUtf8NonNull(reader, typeInfo, resolver);
    }

    @Override
    public Object readUtf8NonNull(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(elementCodec.readUtf8(reader, elementTypeInfo, resolver));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return collection;
    }
  }

  public static final class ObjectCollectionCodec extends CollectionCodec {
    private final JsonTypeInfo elementTypeInfo;
    private final BaseObjectCodec elementCodec;

    private ObjectCollectionCodec(
        TypeRef<?> typeRef,
        CollectionFactory factory,
        JsonTypeInfo elementTypeInfo,
        BaseObjectCodec elementCodec) {
      super(typeRef, factory);
      this.elementTypeInfo = elementTypeInfo;
      this.elementCodec = elementCodec;
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
        writer.writeComma(index++);
        if (element == null) {
          writer.writeNull();
        } else {
          elementCodec.writeNonNull(writer, element, resolver);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
        writer.writeComma(index++);
        if (element == null) {
          writer.writeNull();
        } else {
          elementCodec.writeStringNonNull(writer, element, resolver);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
        writer.writeComma(index++);
        if (element == null) {
          writer.writeNull();
        } else {
          elementCodec.writeUtf8NonNull(writer, element, resolver);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expect('[');
      if (!reader.consume(']')) {
        do {
          collection.add(
              reader.tryReadNull()
                  ? null
                  : elementCodec.readNonNull(reader, elementTypeInfo, resolver));
        } while (reader.consumeCommaOrEndArray());
      }
      reader.exitDepth();
      return collection;
    }

    @Override
    public Object readLatin1(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readLatin1NonNull(reader, typeInfo, resolver);
    }

    @Override
    public Object readLatin1NonNull(
        Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(
              reader.tryReadNextNullToken()
                  ? null
                  : elementCodec.readLatin1NonNull(reader, elementTypeInfo, resolver));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return collection;
    }

    @Override
    public Object readUtf16(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readUtf16NonNull(reader, typeInfo, resolver);
    }

    @Override
    public Object readUtf16NonNull(
        Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(
              reader.tryReadNextNullToken()
                  ? null
                  : elementCodec.readUtf16NonNull(reader, elementTypeInfo, resolver));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return collection;
    }

    @Override
    public Object readUtf8(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      return readUtf8NonNull(reader, typeInfo, resolver);
    }

    @Override
    public Object readUtf8NonNull(
        Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      reader.enterDepth();
      Collection<Object> collection = newCollection();
      reader.expectNextToken('[');
      if (!reader.consumeNextToken(']')) {
        do {
          collection.add(
              reader.tryReadNextNullToken()
                  ? null
                  : elementCodec.readUtf8NonNull(reader, elementTypeInfo, resolver));
        } while (reader.consumeNextCommaOrEndArray());
      }
      reader.exitDepth();
      return collection;
    }
  }

  public static final class StringCollectionCodec extends DirectCollectionCodec {
    private StringCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
        writer.writeComma(index++);
        if (element == null) {
          writer.writeNull();
        } else {
          writer.writeString((String) element);
        }
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
        writer.writeStringElement(index++, (String) element);
      }
      writer.writeArrayEnd();
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
        writer.writeStringElement(index++, (String) element);
      }
      writer.writeArrayEnd();
    }

    @Override
    Object readElement(JsonReader reader) {
      return reader.readString();
    }

    @Override
    Object readNullableElement(JsonReader reader) {
      return reader.readNullableString();
    }

    @Override
    Object readNullableLatin1Element(Latin1JsonReader reader) {
      return reader.readNextNullableString();
    }

    @Override
    Object readNullableUtf16Element(Utf16JsonReader reader) {
      return reader.readNextNullableString();
    }

    @Override
    Object readNullableUtf8Element(Utf8JsonReader reader) {
      return reader.readNextNullableString();
    }
  }

  public static final class BooleanCollectionCodec extends DirectCollectionCodec {
    private BooleanCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
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
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
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
    Object readElement(JsonReader reader) {
      return reader.readBoolean();
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.readNextBooleanValue();
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.readNextBooleanValue();
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.readNextBooleanValue();
    }
  }

  public abstract static class NumberCollectionCodec extends DirectCollectionCodec {
    NumberCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
    }

    @Override
    final void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
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
    final void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeArrayStart();
      int index = 0;
      for (Object element : (Collection<?>) value) {
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
    Object readElement(JsonReader reader) {
      return reader.readInt();
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.readNextIntValue();
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.readNextIntValue();
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.readNextIntValue();
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
    Object readElement(JsonReader reader) {
      return reader.readLong();
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return reader.readNextLongValue();
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return reader.readNextLongValue();
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return reader.readNextLongValue();
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
    Object readElement(JsonReader reader) {
      return readShort(reader.readInt());
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return readShort(reader.readNextIntValue());
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return readShort(reader.readNextIntValue());
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return readShort(reader.readNextIntValue());
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
    Object readElement(JsonReader reader) {
      return readByte(reader.readInt());
    }

    @Override
    Object readLatin1Element(Latin1JsonReader reader) {
      return readByte(reader.readNextIntValue());
    }

    @Override
    Object readUtf16Element(Utf16JsonReader reader) {
      return readByte(reader.readNextIntValue());
    }

    @Override
    Object readUtf8Element(Utf8JsonReader reader) {
      return readByte(reader.readNextIntValue());
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
    Object readElement(JsonReader reader) {
      return Float.parseFloat(reader.readNumber());
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
    Object readElement(JsonReader reader) {
      return Double.parseDouble(reader.readNumber());
    }
  }

  public static final class BigIntegerCollectionCodec extends NumberCollectionCodec {
    private BigIntegerCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeNumber(value.toString());
    }

    @Override
    Object readElement(JsonReader reader) {
      return new BigInteger(reader.readNumber());
    }
  }

  public static final class BigDecimalCollectionCodec extends NumberCollectionCodec {
    private BigDecimalCollectionCodec(TypeRef<?> typeRef, CollectionFactory factory) {
      super(typeRef, factory);
    }

    @Override
    void writeNumber(JsonWriter writer, Object value) {
      writer.writeNumber(value.toString());
    }

    @Override
    Object readElement(JsonReader reader) {
      return new BigDecimal(reader.readNumber());
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
