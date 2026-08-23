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
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.TransparentNullCodec;
import org.apache.fory.json.codec.UnboxedValueCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.TypeRef;

/**
 * JSON type binding resolved and owned by {@link JsonTypeResolver}.
 *
 * <p>The five capability fields are deliberately ordinary fields. A resolver-local JIT lock covers
 * every root graph operation and every generated capability installation, so lock release and the
 * next root acquisition publish slot changes without adding volatile reads to established codec
 * dispatch. These five fields are the sole installed capability state; there are no parallel
 * per-path maps to reconcile on the hot path.
 *
 * <p>{@link JsonTypeResolver} owns canonical exact raw-class {@link ObjectCodec} identity and the
 * corresponding stable metadata owner. This binding stores only installed capabilities; custom
 * codecs, parameterized object bindings, containers, scalars, and dynamic {@code Object} bindings
 * retain their original semantic owner. Each complete capability is installed in its own
 * independently lazy slot.
 */
public final class JsonTypeInfo {
  private final TypeRef<?> typeRef;
  private final Class<?> rawType;
  private final JsonFieldKind kind;
  private final boolean nullable;
  private final boolean rejectsNull;
  private final boolean transparentNull;
  private final UnboxedValueCodec unboxedValueCodec;
  private final String factoryKey;
  private final Class<?> exactCodecClass;
  private StringWriterCodec<Object> stringWriter;
  private Utf8WriterCodec<Object> utf8Writer;
  private Latin1ReaderCodec<Object> latin1Reader;
  private Utf16ReaderCodec<Object> utf16Reader;
  private Utf8ReaderCodec<Object> utf8Reader;
  private final boolean annotationCodec;

  JsonTypeInfo(TypeRef<?> typeRef, JsonFieldKind kind, JsonValueCodec<Object> codec) {
    this(typeRef, kind, codec, false, null, null);
  }

  JsonTypeInfo(
      TypeRef<?> typeRef,
      JsonFieldKind kind,
      JsonValueCodec<Object> codec,
      boolean annotationCodec) {
    this(typeRef, kind, codec, annotationCodec, null, null);
  }

  JsonTypeInfo(
      TypeRef<?> typeRef,
      JsonFieldKind kind,
      JsonValueCodec<Object> codec,
      boolean annotationCodec,
      String factoryKey,
      Class<?> exactCodecClass) {
    this.typeRef = typeRef;
    this.rawType = typeRef.getRawType();
    this.kind = kind;
    TypeExtMeta metadata = typeRef.getTypeExtMeta();
    nullable = metadata != null && metadata.nullable() && !metadata.nullableWrapper();
    transparentNull = codec instanceof TransparentNullCodec;
    rejectsNull =
        metadata != null && !metadata.nullable() && !metadata.nullableWrapper() && !transparentNull;
    unboxedValueCodec = codec instanceof UnboxedValueCodec ? (UnboxedValueCodec) codec : null;
    this.annotationCodec = annotationCodec;
    this.factoryKey = factoryKey;
    this.exactCodecClass = exactCodecClass;
    stringWriter = codec;
    utf8Writer = codec;
    latin1Reader = codec;
    utf16Reader = codec;
    utf8Reader = codec;
  }

  public Type type() {
    return typeRef.getType();
  }

  /** Returns the canonical complete declared type owned by this binding. */
  @Internal
  public TypeRef<?> typeRef() {
    return typeRef;
  }

  public Class<?> rawType() {
    return rawType;
  }

  public JsonFieldKind kind() {
    return kind;
  }

  /** Returns whether this exact declared occurrence rejects a JSON or Java {@code null}. */
  @Internal
  public boolean rejectsNull() {
    return rejectsNull;
  }

  /** Returns whether JSON {@code null} denotes an outer null for this exact occurrence. */
  @Internal
  public boolean nullable() {
    return nullable;
  }

  /** Returns whether the logical non-null value itself materializes JSON {@code null}. */
  @Internal
  public boolean transparentNull() {
    return transparentNull;
  }

  /** Returns the canonical logical codec's unboxed-member operation, or {@code null}. */
  @Internal
  public UnboxedValueCodec unboxedValueCodec() {
    return unboxedValueCodec;
  }

  /** Throws the cold failure for a null supplied to this exact non-null occurrence. */
  @Internal
  public void rejectNullValue() {
    throw new ForyJsonException("JSON null is not allowed for " + typeRef);
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

  @Internal
  public boolean usesAnnotationCodec() {
    return annotationCodec;
  }

  String factoryKey() {
    return factoryKey;
  }

  Class<?> exactCodecClass() {
    return exactCodecClass;
  }
}
