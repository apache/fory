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

package org.apache.fory.json.codegen;

import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.ExpressionUtils;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.meta.JsonAsciiToken;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.reflect.TypeRef;

final class Utf8ReaderCodegen extends JsonReaderCodegen {
  // Generated String collections own three bounded word probes. Longer inputs continue in the
  // reader-owned scanner, keeping escape, Unicode, malformed-input, and arbitrary-length work out
  // of each generated prefix method.
  private static final int DIRECT_STRING_WORDS = 3;

  Utf8ReaderCodegen(JsonCodegen codegen, JsonTypeResolver resolver, boolean finalDependencies) {
    super(codegen, resolver, finalDependencies);
  }

  Utf8ReaderCodegen(
      JsonCodegen codegen,
      JsonTypeResolver resolver,
      boolean finalDependencies,
      int[] fastReadGroupEnds) {
    super(codegen, resolver, finalDependencies, fastReadGroupEnds);
  }

  @Override
  Class<?> codecFieldType(JsonFieldInfo property) {
    return codegen.utf8ReaderFieldType(property.readTypeInfo(), resolver, finalDependencies());
  }

  @Override
  Class<?> readerType() {
    return Utf8JsonReader.class;
  }

  @Override
  Class<?> readerCapabilityType() {
    return Utf8ReaderCodec.class;
  }

  @Override
  Class<?> readerArrayType() {
    return Utf8ReaderCodec[].class;
  }

  @Override
  String readMethod() {
    return "readUtf8";
  }

  @Override
  String readerSlotMethod() {
    return "utf8Reader";
  }

  @Override
  String readEnumMethod(boolean tokenValueRead, boolean hashFallback) {
    return tokenValueRead
        ? (hashFallback ? "readUtf8EnumHashToken" : "readUtf8EnumToken")
        : "readNextUtf8Enum";
  }

  @Override
  String readObjectMethod() {
    return "readUtf8";
  }

  @Override
  String readFieldMethod() {
    return "readUtf8";
  }

  static Expression readStringElement(String id) {
    Reference reader = new Reference("reader", TypeRef.of(Utf8JsonReader.class));
    Expression.Variable value =
        new Expression.Variable("stringElement" + id, TypeRef.of(String.class));
    Expression fallback =
        new Expression.If(
            new Expression.Invoke(reader, "tryReadNextNullToken", TypeRef.of(boolean.class))
                .inline(),
            new Expression.Assign(value, ExpressionUtils.nullValue(String.class)),
            new Expression.Assign(
                value,
                new Expression.Invoke(
                        reader, "readNullableStringToken", TypeRef.of(String.class), true)
                    .inline()));
    return new Expression.ListExpression(
        value,
        new Expression.If(
            tryConsumeStringQuote(reader),
            new Expression.Assign(value, readStringToken(id, reader)),
            fallback),
        value);
  }

  static Expression readQuotedStringElement(String id) {
    Reference reader = new Reference("reader", TypeRef.of(Utf8JsonReader.class));
    return readStringToken(id, reader);
  }

  private static Expression readStringToken(String id, Expression reader) {
    Expression start =
        new Expression.Variable(
            "stringStart" + id,
            new Expression.Invoke(reader, "position", TypeRef.of(int.class)).inline());
    Expression offset = new Expression.Variable("stringOffset" + id, start);
    Expression inputLength =
        new Expression.Variable(
            "stringInputLength" + id,
            new Expression.Invoke(reader, "inputLength", TypeRef.of(int.class)).inline());
    Expression directWordEnd =
        new Expression.Variable(
            "stringDirectWordEnd" + id,
            new Expression.Subtract(
                true, inputLength, Expression.Literal.ofInt(Long.BYTES * DIRECT_STRING_WORDS)));
    Expression state = new Expression.Variable("stringState" + id, Expression.Literal.ofLong(0L));
    Expression value = new Expression.Variable("stringValue" + id, TypeRef.of(String.class));
    Expression zero = Expression.Literal.ofLong(0L);
    Expression directScans = new Expression.Empty();
    for (int i = 0; i < DIRECT_STRING_WORDS; i++) {
      directScans =
          new Expression.ListExpression(
              new Expression.Assign(state, scanStringWord(reader, offset)),
              new Expression.If(
                  equal(state, zero),
                  new Expression.ListExpression(advanceStringOffset(offset), directScans)));
    }
    directScans =
        new Expression.If(
            new Expression.Comparator("<=", offset, directWordEnd, true), directScans);
    Expression finish =
        new Expression.If(
            notEqual(state, zero),
            new Expression.Assign(value, finishStringWord(reader, start, offset, state)),
            new Expression.Assign(value, readStringLongTail(reader, start, offset)));
    return new Expression.ListExpression(
        start, offset, inputLength, directWordEnd, state, value, directScans, finish, value);
  }

  private static Expression tryConsumeStringQuote(Expression reader) {
    return new Expression.Invoke(reader, "tryConsumeStringQuote", TypeRef.of(boolean.class))
        .inline();
  }

  private static Expression readStringLongTail(
      Expression reader, Expression start, Expression offset) {
    return new Expression.Invoke(
            reader, "readStringTokenLongTail", TypeRef.of(String.class), start, offset)
        .inline();
  }

  private static Expression finishStringWord(
      Expression reader, Expression start, Expression offset, Expression state) {
    return new Expression.Invoke(
            reader, "finishStringWord", TypeRef.of(String.class), start, offset, state)
        .inline();
  }

  private static Expression advanceStringOffset(Expression offset) {
    return new Expression.Assign(
        offset, new Expression.Add(true, offset, Expression.Literal.ofInt(Long.BYTES)));
  }

  private static Expression equal(Expression left, Expression right) {
    return new Expression.Comparator("==", left, right, true);
  }

  private static Expression notEqual(Expression left, Expression right) {
    return new Expression.Comparator("!=", left, right, true);
  }

  private static Expression scanStringWord(Expression reader, Expression offset) {
    return new Expression.Invoke(reader, "scanStringWord", TypeRef.of(long.class), offset).inline();
  }

  @Override
  boolean isDirectName(String name, boolean tokenValueRead) {
    return JsonAsciiToken.isLongPackable(fieldNameToken(name));
  }

  @Override
  Expression tryReadNextFieldNameColon(JsonFieldInfo property, boolean tokenValueRead) {
    return tryReadAsciiFieldNameColon(property);
  }

  @Override
  Expression readEnumField(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      Expression object,
      boolean tokenValueRead) {
    return readAsciiEnumField(builder, property, id, object, tokenValueRead);
  }

  @Override
  Reference readerRef() {
    return new Reference("reader", TypeRef.of(Utf8JsonReader.class));
  }
}
