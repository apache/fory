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
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;

/** Immutable input metadata for one {@code JsonCreator} argument. */
@Internal
public final class JsonCreatorFieldInfo {
  private final String name;
  private final long nameHash;
  private final int argumentIndex;
  private final Type type;
  private final Class<?> rawType;
  private final JsonCodec codecAnnotation;
  private final Class<? extends JsonValueCodec<?>> valueCodecClass;
  private JsonTypeInfo typeInfo;

  public JsonCreatorFieldInfo(
      String name,
      int argumentIndex,
      Type type,
      Class<?> rawType,
      JsonCodec codecAnnotation,
      Class<? extends JsonValueCodec<?>> valueCodecClass) {
    this.name = name;
    nameHash = JsonFieldNameHash.hash(name);
    this.argumentIndex = argumentIndex;
    this.type = type;
    this.rawType = rawType;
    this.codecAnnotation = codecAnnotation;
    this.valueCodecClass = valueCodecClass;
  }

  public String name() {
    return name;
  }

  /** Returns parent-local metadata with a transformed JSON name and the same creator argument. */
  public JsonCreatorFieldInfo withName(String transformedName) {
    return new JsonCreatorFieldInfo(
        transformedName, argumentIndex, type, rawType, codecAnnotation, valueCodecClass);
  }

  public long nameHash() {
    return nameHash;
  }

  public int argumentIndex() {
    return argumentIndex;
  }

  public Type type() {
    return type;
  }

  public Class<?> rawType() {
    return rawType;
  }

  public JsonTypeInfo typeInfo() {
    return typeInfo;
  }

  public void resolveType(JsonTypeResolver resolver) {
    typeInfo =
        codecAnnotation != null
            ? resolver.getTypeInfo(type, rawType, codecAnnotation)
            : valueCodecClass != null
                ? resolver.getTypeInfo(type, rawType, valueCodecClass)
                : resolver.getTypeInfo(type, rawType);
  }

  public Object readLatin1(Latin1JsonReader reader) {
    return requirePrimitive(typeInfo.latin1Reader().readLatin1(reader), rawType);
  }

  public Object readUtf16(Utf16JsonReader reader) {
    return requirePrimitive(typeInfo.utf16Reader().readUtf16(reader), rawType);
  }

  public Object readUtf8(Utf8JsonReader reader) {
    return requirePrimitive(typeInfo.utf8Reader().readUtf8(reader), rawType);
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
