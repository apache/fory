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
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/** Null handling shared by codecs whose five concrete paths own their non-null implementation. */
public abstract class AbstractJsonCodec implements JsonCodec {
  @Override
  public void writeString(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
    if (value == null) {
      writer.writeNull();
    } else {
      writeStringNonNull(writer, value, resolver);
    }
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
    if (value == null) {
      writer.writeNull();
    } else {
      writeUtf8NonNull(writer, value, resolver);
    }
  }

  @Override
  public Object readLatin1(
      Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (reader.tryReadNullToken()) {
      return nullValue(typeInfo);
    }
    return readLatin1NonNull(reader, typeInfo, resolver);
  }

  @Override
  public Object readUtf16(
      Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (reader.tryReadNullToken()) {
      return nullValue(typeInfo);
    }
    return readUtf16NonNull(reader, typeInfo, resolver);
  }

  @Override
  public Object readUtf8(Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (reader.tryReadNullToken()) {
      return nullValue(typeInfo);
    }
    return readUtf8NonNull(reader, typeInfo, resolver);
  }

  abstract void writeStringNonNull(
      StringJsonWriter writer, Object value, JsonTypeResolver resolver);

  abstract void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver);

  abstract Object readLatin1NonNull(
      Latin1JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  abstract Object readUtf16NonNull(
      Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  abstract Object readUtf8NonNull(
      Utf8JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  private static Object nullValue(JsonTypeInfo typeInfo) {
    if (typeInfo.primitive()) {
      throw new ForyJsonException("Cannot read null into primitive " + typeInfo.rawType());
    }
    return null;
  }
}
