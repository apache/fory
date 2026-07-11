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

import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;

/** Shared representation-independent implementation used by scalar and direct codecs. */
abstract class SharedJsonCodec extends AbstractJsonCodec {
  @Override
  void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
    writeStringValue(writer, value, resolver);
  }

  @Override
  public Object readLatin1(
      Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    return readValueOrNull(reader, typeInfo, resolver);
  }

  @Override
  public Object readUtf16(
      Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    return readValueOrNull(reader, typeInfo, resolver);
  }

  @Override
  public Object readUtf8(Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    return readValueOrNull(reader, typeInfo, resolver);
  }

  @Override
  Object readLatin1NonNull(
      Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    return readValue(reader, typeInfo, resolver);
  }

  @Override
  Object readUtf16NonNull(
      Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    return readValue(reader, typeInfo, resolver);
  }

  @Override
  Object readUtf8NonNull(Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    return readValue(reader, typeInfo, resolver);
  }

  @Override
  public void readLatin1Field(
      Latin1JsonReader reader,
      Object object,
      JsonFieldAccessor accessor,
      JsonTypeInfo typeInfo,
      JsonTypeResolver resolver) {
    readFieldValue(reader, object, accessor, typeInfo, resolver);
  }

  @Override
  public void readUtf16Field(
      Utf16JsonReader reader,
      Object object,
      JsonFieldAccessor accessor,
      JsonTypeInfo typeInfo,
      JsonTypeResolver resolver) {
    readFieldValue(reader, object, accessor, typeInfo, resolver);
  }

  @Override
  public void readUtf8Field(
      Utf8JsonReader reader,
      Object object,
      JsonFieldAccessor accessor,
      JsonTypeInfo typeInfo,
      JsonTypeResolver resolver) {
    readFieldValue(reader, object, accessor, typeInfo, resolver);
  }

  Object readValueOrNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (reader.peekNull()) {
      reader.readNull();
      if (typeInfo.primitive()) {
        throw new ForyJsonException("Cannot read null into primitive " + typeInfo.rawType());
      }
      return null;
    }
    return readValue(reader, typeInfo, resolver);
  }

  void readFieldValue(
      JsonReader reader,
      Object object,
      JsonFieldAccessor accessor,
      JsonTypeInfo typeInfo,
      JsonTypeResolver resolver) {
    accessor.putObject(object, readValueOrNull(reader, typeInfo, resolver));
  }

  final void readFieldValueDefault(
      JsonReader reader,
      Object object,
      JsonFieldAccessor accessor,
      JsonTypeInfo typeInfo,
      JsonTypeResolver resolver) {
    accessor.putObject(object, readValueOrNull(reader, typeInfo, resolver));
  }

  abstract void writeStringValue(StringJsonWriter writer, Object value, JsonTypeResolver resolver);

  abstract Object readValue(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver);
}
