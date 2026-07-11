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
  private StringWriterCodec<Object> stringWriter;
  private Utf8WriterCodec<Object> utf8Writer;
  private Latin1ReaderCodec<Object> latin1Reader;
  private Utf16ReaderCodec<Object> utf16Reader;
  private Utf8ReaderCodec<Object> utf8Reader;
  private final boolean defaultObjectCodec;
  private final boolean objectCollectionCodec;
  private final boolean collectionCreatesArrayList;

  JsonTypeInfo(
      Type type,
      TypeRef<?> typeRef,
      Class<?> rawType,
      JsonFieldKind kind,
      JsonCodec<Object> codec,
      boolean objectCollectionCodec,
      boolean collectionCreatesArrayList) {
    this.type = type;
    this.typeRef = typeRef;
    this.rawType = rawType;
    this.kind = kind;
    stringWriter = codec;
    utf8Writer = codec;
    latin1Reader = codec;
    utf16Reader = codec;
    utf8Reader = codec;
    // Only the raw-class ObjectCodec can be replaced by raw-class generated capabilities.
    // ParameterizedObjectCodec owns binding-specific field types and must remain the slot owner.
    defaultObjectCodec = codec.getClass() == ObjectCodec.class;
    this.objectCollectionCodec = objectCollectionCodec;
    this.collectionCreatesArrayList = collectionCreatesArrayList;
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

  public StringWriterCodec<Object> stringWriter() {
    return stringWriter;
  }

  public Utf8WriterCodec<Object> utf8Writer() {
    return utf8Writer;
  }

  public Latin1ReaderCodec<Object> latin1Reader() {
    return latin1Reader;
  }

  public Utf16ReaderCodec<Object> utf16Reader() {
    return utf16Reader;
  }

  public Utf8ReaderCodec<Object> utf8Reader() {
    return utf8Reader;
  }

  void setStringWriter(StringWriterCodec<Object> stringWriter) {
    this.stringWriter = stringWriter;
  }

  void setUtf8Writer(Utf8WriterCodec<Object> utf8Writer) {
    this.utf8Writer = utf8Writer;
  }

  void setLatin1Reader(Latin1ReaderCodec<Object> latin1Reader) {
    this.latin1Reader = latin1Reader;
  }

  void setUtf16Reader(Utf16ReaderCodec<Object> utf16Reader) {
    this.utf16Reader = utf16Reader;
  }

  void setUtf8Reader(Utf8ReaderCodec<Object> utf8Reader) {
    this.utf8Reader = utf8Reader;
  }

  public boolean usesDefaultObjectCodec() {
    return defaultObjectCodec;
  }

  public boolean usesObjectCollectionCodec() {
    return objectCollectionCodec;
  }

  public boolean collectionCreatesArrayList() {
    return collectionCreatesArrayList;
  }

}
