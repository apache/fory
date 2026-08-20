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

import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/**
 * Convenience base class for application-defined, representation-neutral JSON value codecs.
 *
 * <p>Subclasses implement one {@link #write(JsonWriter, Object)} method and one {@link
 * #read(JsonReader)} method instead of repeating the same logic for every concrete reader and
 * writer. Each operation owns one complete JSON value, including {@code null}, just like {@link
 * JsonValueCodec}.
 *
 * <p>This class is intended only for user codecs. Its representation-neutral bridge adds a virtual
 * method call to every read and write. Fory's built-in codecs must implement {@link JsonValueCodec}
 * directly, and performance-sensitive user codecs should do the same. Direct implementation also
 * remains necessary when a codec needs representation-specific reader or writer behavior.
 *
 * @param <T> Java value type encoded by this codec
 */
public abstract class AbstractJsonValueCodec<T> implements JsonValueCodec<T> {
  /** Writes one complete JSON value, including {@code null}. */
  public abstract void write(JsonWriter writer, T value);

  /** Reads one complete JSON value, including JSON {@code null}. */
  public abstract T read(JsonReader reader);

  @Override
  public final void writeString(StringJsonWriter writer, T value) {
    write(writer, value);
  }

  @Override
  public final void writeUtf8(Utf8JsonWriter writer, T value) {
    write(writer, value);
  }

  @Override
  public final T readLatin1(Latin1JsonReader reader) {
    return read(reader);
  }

  @Override
  public final T readUtf16(Utf16JsonReader reader) {
    return read(reader);
  }

  @Override
  public final T readUtf8(Utf8JsonReader reader) {
    return read(reader);
  }
}
