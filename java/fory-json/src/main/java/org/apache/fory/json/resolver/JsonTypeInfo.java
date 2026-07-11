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
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.reflect.TypeRef;

/** JSON type binding resolved and owned by {@link JsonTypeResolver}. */
public final class JsonTypeInfo {
  private final Type type;
  private final TypeRef<?> typeRef;
  private final Class<?> rawType;
  private final JsonFieldKind kind;
  // JsonJITContext orders every installation and dependent-field update. These stay plain fields
  // so established codec calls do not pay volatile or atomic access on the hot path.
  private StringWriterCodec stringWriter;
  private Utf8WriterCodec utf8Writer;
  private Latin1ReaderCodec latin1Reader;
  private Utf16ReaderCodec utf16Reader;
  private Utf8ReaderCodec utf8Reader;
  private final boolean defaultObjectCodec;
  private final boolean primitive;

  JsonTypeInfo(
      Type type, TypeRef<?> typeRef, Class<?> rawType, JsonFieldKind kind, JsonCodec codec) {
    this.type = type;
    this.typeRef = typeRef;
    this.rawType = rawType;
    this.kind = kind;
    stringWriter = codec;
    utf8Writer = codec;
    latin1Reader = codec;
    utf16Reader = codec;
    utf8Reader = codec;
    defaultObjectCodec = codec instanceof ObjectCodec;
    primitive = rawType.isPrimitive();
  }

  public Type type() {
    return type;
  }

  public TypeRef<?> typeRef() {
    return typeRef;
  }

  public Class<?> rawType() {
    return rawType;
  }

  public JsonFieldKind kind() {
    return kind;
  }

  public StringWriterCodec stringWriter() {
    return stringWriter;
  }

  public Utf8WriterCodec utf8Writer() {
    return utf8Writer;
  }

  public Latin1ReaderCodec latin1Reader() {
    return latin1Reader;
  }

  public Utf16ReaderCodec utf16Reader() {
    return utf16Reader;
  }

  public Utf8ReaderCodec utf8Reader() {
    return utf8Reader;
  }

  void setStringWriter(StringWriterCodec stringWriter) {
    this.stringWriter = stringWriter;
  }

  void setUtf8Writer(Utf8WriterCodec utf8Writer) {
    this.utf8Writer = utf8Writer;
  }

  void setLatin1Reader(Latin1ReaderCodec latin1Reader) {
    this.latin1Reader = latin1Reader;
  }

  void setUtf16Reader(Utf16ReaderCodec utf16Reader) {
    this.utf16Reader = utf16Reader;
  }

  void setUtf8Reader(Utf8ReaderCodec utf8Reader) {
    this.utf8Reader = utf8Reader;
  }

  public boolean usesDefaultObjectCodec() {
    return defaultObjectCodec;
  }

  public boolean primitive() {
    return primitive;
  }
}
