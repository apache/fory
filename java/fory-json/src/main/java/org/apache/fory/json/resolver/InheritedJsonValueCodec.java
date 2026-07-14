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

package org.apache.fory.json.resolver;

import java.lang.reflect.Type;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/**
 * Applies the runtime result contract when a broader declaration codec is inherited by a target.
 */
final class InheritedJsonValueCodec implements JsonValueCodec<Object> {
  private final Type targetType;
  private final Class<?> targetRawType;
  private final Class<? extends JsonValueCodec<?>> codecClass;
  private final String origins;
  private final JsonValueCodec<Object> delegate;

  InheritedJsonValueCodec(
      Type targetType,
      Class<?> targetRawType,
      Class<? extends JsonValueCodec<?>> codecClass,
      Class<?>[] origins,
      JsonValueCodec<?> delegate) {
    this.targetType = targetType;
    this.targetRawType = targetRawType;
    this.codecClass = codecClass;
    this.origins = originNames(origins);
    this.delegate = erase(delegate);
  }

  @Override
  public void writeString(StringJsonWriter writer, Object value) {
    delegate.writeString(writer, value);
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, Object value) {
    delegate.writeUtf8(writer, value);
  }

  @Override
  public Object readLatin1(Latin1JsonReader reader) {
    return validate(delegate.readLatin1(reader));
  }

  @Override
  public Object readUtf16(Utf16JsonReader reader) {
    return validate(delegate.readUtf16(reader));
  }

  @Override
  public Object readUtf8(Utf8JsonReader reader) {
    return validate(delegate.readUtf8(reader));
  }

  private Object validate(Object value) {
    if (value != null && !targetRawType.isInstance(value)) {
      throw incompatibleResult(value.getClass());
    }
    return value;
  }

  private ForyJsonException incompatibleResult(Class<?> actualType) {
    return new ForyJsonException(
        "Json codec "
            + codecClass.getName()
            + " declared on "
            + origins
            + " returned "
            + actualType.getName()
            + " while decoding "
            + targetType.getTypeName()
            + " (raw type "
            + targetRawType.getName()
            + "); expected null or a value assignable to "
            + targetRawType.getName()
            + ".");
  }

  private static String originNames(Class<?>[] origins) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < origins.length; i++) {
      if (i != 0) {
        builder.append(", ");
      }
      builder.append(origins[i].getName());
    }
    return builder.toString();
  }

  @SuppressWarnings("unchecked")
  private static JsonValueCodec<Object> erase(JsonValueCodec<?> codec) {
    return (JsonValueCodec<Object>) codec;
  }
}
