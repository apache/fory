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
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

public abstract class AbstractJsonCodec implements JsonCodec {
  @Override
  public final void write(JsonWriter writer, Object value, JsonTypeResolver resolver) {
    if (value == null) {
      writer.writeNull();
    } else {
      writeNonNull(writer, value, resolver);
    }
  }

  @Override
  public final void writeString(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
    if (value == null) {
      writer.writeNull();
    } else {
      writeStringNonNull(writer, value, resolver);
    }
  }

  @Override
  public final void writeUtf8(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
    if (value == null) {
      writer.writeNull();
    } else {
      writeUtf8NonNull(writer, value, resolver);
    }
  }

  @Override
  public Object read(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (reader.peekNull()) {
      reader.readNull();
      if (typeInfo.primitive()) {
        throw new ForyJsonException("Cannot read null into primitive " + typeInfo.rawType());
      }
      return null;
    }
    return readNonNull(reader, typeInfo, resolver);
  }

  abstract void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver);

  void writeStringNonNull(StringJsonWriter writer, Object value, JsonTypeResolver resolver) {
    writeNonNull(writer, value, resolver);
  }

  abstract void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver);

  abstract Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  final void readFieldDefault(
      JsonReader reader,
      Object object,
      JsonFieldAccessor accessor,
      JsonTypeInfo typeInfo,
      JsonTypeResolver resolver) {
    JsonCodec.super.readField(reader, object, accessor, typeInfo, resolver);
  }
}
