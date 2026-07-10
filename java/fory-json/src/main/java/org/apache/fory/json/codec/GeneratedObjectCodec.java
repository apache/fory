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
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/**
 * Base type for one Java type's generated JSON codec.
 *
 * <p>Each generated subclass directly owns its nullable and non-null writer methods and all reader
 * entry methods. Do not route these methods through shared delegate fields: C2 can inline one
 * type-specific delegate into the shared hot method and produce profile-dependent code size and
 * throughput.
 */
public abstract class GeneratedObjectCodec extends BaseObjectCodec {
  protected GeneratedObjectCodec(ObjectCodec base) {
    super(base.type, base.writeFields, base.readFields, base.instantiator);
  }

  @Override
  public abstract void writeString(
      StringJsonWriter writer, Object value, JsonTypeResolver resolver);

  @Override
  public abstract void writeUtf8(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver);

  @Override
  protected abstract void writeStringNonNull(
      StringJsonWriter writer, Object value, JsonTypeResolver resolver);

  @Override
  protected abstract void writeUtf8NonNull(
      Utf8JsonWriter writer, Object value, JsonTypeResolver resolver);

  @Override
  protected abstract Object readNonNull(
      JsonReader input, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  @Override
  public abstract Object read(JsonReader input, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  @Override
  public abstract Object readLatin1(
      Latin1JsonReader input, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  @Override
  public abstract Object readLatin1NonNull(
      Latin1JsonReader input, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  @Override
  public abstract Object readUtf16(
      Utf16JsonReader input, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  @Override
  public abstract Object readUtf16NonNull(
      Utf16JsonReader input, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  @Override
  public abstract Object readUtf8(
      Utf8JsonReader input, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  @Override
  public abstract Object readUtf8NonNull(
      Utf8JsonReader input, JsonTypeInfo typeInfo, JsonTypeResolver resolver);
}
