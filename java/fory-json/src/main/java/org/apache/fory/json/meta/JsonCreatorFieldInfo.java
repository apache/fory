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

package org.apache.fory.json.meta;

import java.lang.reflect.Type;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonFormat;
import org.apache.fory.json.codec.DirectUnboxedValueCodec;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.TransparentUnboxedValueCodec;
import org.apache.fory.json.codec.UnboxedValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.reflect.TypeRef;

/** Immutable input metadata for one {@code JsonCreator} argument. */
@Internal
public final class JsonCreatorFieldInfo {
  private final String name;
  private final long nameHash;
  private final int argumentIndex;
  private final TypeRef<?> typeRef;
  private final Class<?> rawType;
  private final JsonCodec codecAnnotation;
  private final Class<? extends JsonValueCodec<?>> valueCodecClass;
  private final JsonFormat formatAnnotation;
  private final boolean unboxedRequired;
  private final boolean selectedCodec;
  private JsonTypeInfo typeInfo;
  private JsonTypeInfo occurrenceTypeInfo;
  private UnboxedValueCodec unboxedValueCodec;

  public JsonCreatorFieldInfo(
      String name,
      int argumentIndex,
      TypeRef<?> typeRef,
      Class<?> rawType,
      JsonCodec codecAnnotation,
      Class<? extends JsonValueCodec<?>> valueCodecClass,
      JsonFormat formatAnnotation,
      boolean unboxedRequired) {
    this.name = name;
    nameHash = JsonFieldNameHash.hash(name);
    this.argumentIndex = argumentIndex;
    this.typeRef = typeRef;
    this.rawType = rawType;
    this.codecAnnotation = codecAnnotation;
    this.valueCodecClass = valueCodecClass;
    this.formatAnnotation = formatAnnotation;
    this.unboxedRequired = unboxedRequired;
    selectedCodec = codecAnnotation != null || valueCodecClass != null || formatAnnotation != null;
  }

  public String name() {
    return name;
  }

  /** Returns parent-local metadata with a transformed JSON name and the same creator argument. */
  public JsonCreatorFieldInfo withName(String transformedName) {
    return new JsonCreatorFieldInfo(
        transformedName,
        argumentIndex,
        typeRef,
        rawType,
        codecAnnotation,
        valueCodecClass,
        formatAnnotation,
        unboxedRequired);
  }

  public long nameHash() {
    return nameHash;
  }

  public int argumentIndex() {
    return argumentIndex;
  }

  public Type type() {
    return typeRef.getType();
  }

  public TypeRef<?> typeRef() {
    return typeRef;
  }

  public Class<?> rawType() {
    return rawType;
  }

  public JsonTypeInfo typeInfo() {
    return typeInfo;
  }

  public void resolveType(JsonTypeResolver resolver) {
    if (!unboxedRequired) {
      typeInfo = selectedCodec ? selectedTypeInfo(resolver) : resolver.getTypeInfo(typeRef);
      occurrenceTypeInfo = typeInfo;
      return;
    }
    if (selectedCodec) {
      throw new ForyJsonException(
          "JSON creator property "
              + name
              + " cannot select a codec or format for the unboxed logical type "
              + typeRef);
    }
    JsonTypeInfo canonical = resolver.getTypeInfo(typeRef);
    UnboxedValueCodec operation = canonical.unboxedValueCodec();
    if (operation == null || operation.carrierType() != rawType) {
      throw new ForyJsonException(
          "JSON creator property "
              + name
              + " has no exact unboxed carrier operation for "
              + rawType.getName());
    }
    unboxedValueCodec = operation;
    occurrenceTypeInfo = canonical;
    if (operation instanceof DirectUnboxedValueCodec) {
      typeInfo = canonical;
    } else if (operation instanceof TransparentUnboxedValueCodec) {
      typeInfo = ((TransparentUnboxedValueCodec) operation).valueTypeInfo();
    } else {
      throw new ForyJsonException(
          "JSON creator property "
              + name
              + " has an unsupported unboxed carrier capability for "
              + rawType.getName());
    }
  }

  private JsonTypeInfo selectedTypeInfo(JsonTypeResolver resolver) {
    return codecAnnotation != null
        ? resolver.getTypeInfo(typeRef, codecAnnotation)
        : valueCodecClass != null
            ? resolver.getTypeInfo(typeRef, valueCodecClass)
            : resolver.getTypeInfo(typeRef, formatAnnotation);
  }

  public Object readLatin1(Latin1JsonReader reader) {
    if (readOccurrenceNull(reader)) {
      return null;
    }
    Object value =
        unboxedValueCodec == null
            ? typeInfo.latin1Reader().readLatin1(reader)
            : unboxedValueCodec.readLatin1Carrier(reader);
    return requirePrimitive(value, rawType);
  }

  public Object readUtf16(Utf16JsonReader reader) {
    if (readOccurrenceNull(reader)) {
      return null;
    }
    Object value =
        unboxedValueCodec == null
            ? typeInfo.utf16Reader().readUtf16(reader)
            : unboxedValueCodec.readUtf16Carrier(reader);
    return requirePrimitive(value, rawType);
  }

  public Object readUtf8(Utf8JsonReader reader) {
    if (readOccurrenceNull(reader)) {
      return null;
    }
    Object value =
        unboxedValueCodec == null
            ? typeInfo.utf8Reader().readUtf8(reader)
            : unboxedValueCodec.readUtf8Carrier(reader);
    return requirePrimitive(value, rawType);
  }

  private boolean readOccurrenceNull(org.apache.fory.json.reader.JsonReader reader) {
    if (!occurrenceTypeInfo.nullable() && !occurrenceTypeInfo.rejectsNull()) {
      return false;
    }
    if (!reader.tryReadNull()) {
      return false;
    }
    if (occurrenceTypeInfo.rejectsNull()) {
      rejectNullRead();
    }
    return true;
  }

  /** Returns whether a present JSON null materializes a non-null logical value in this carrier. */
  public boolean materializesNullCarrier() {
    return unboxedValueCodec != null && occurrenceTypeInfo.transparentNull();
  }

  /** Returns the cold-bound unboxed carrier operation. */
  public UnboxedValueCodec unboxedValueCodec() {
    return unboxedValueCodec;
  }

  /** Returns the exact transparent carrier operation, or {@code null}. */
  public TransparentUnboxedValueCodec transparentUnboxedValueCodec() {
    return unboxedValueCodec instanceof TransparentUnboxedValueCodec
        ? (TransparentUnboxedValueCodec) unboxedValueCodec
        : null;
  }

  /** Returns the exact semantic leaf carrier operation, or {@code null}. */
  public DirectUnboxedValueCodec directUnboxedValueCodec() {
    return unboxedValueCodec instanceof DirectUnboxedValueCodec
        ? (DirectUnboxedValueCodec) unboxedValueCodec
        : null;
  }

  /** Throws the cold failure used by interpreted and generated readers. */
  public Object rejectNullRead() {
    throw new ForyJsonException("JSON creator property " + name + " is not nullable");
  }

  /** Enforces the shared interpreted/generated null contract for a primitive creator argument. */
  public static Object requirePrimitive(Object value, Class<?> rawType) {
    if (value == null && rawType.isPrimitive()) {
      throw new ForyJsonException("Cannot read null into primitive creator parameter " + rawType);
    }
    return value;
  }

  /** Narrows a generated creator integer after enforcing JSON byte range. */
  public static byte checkedByte(int value) {
    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
      throw new ForyJsonException("Byte overflow");
    }
    return (byte) value;
  }

  /** Narrows a generated creator integer after enforcing JSON short range. */
  public static short checkedShort(int value) {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw new ForyJsonException("Short overflow");
    }
    return (short) value;
  }
}
