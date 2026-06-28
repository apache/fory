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

import org.apache.fory.codegen.Code;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.json.codec.BaseObjectCodec;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.meta.JsonAsciiToken;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Latin1ObjectReader;
import org.apache.fory.json.reader.ObjectReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf16ObjectReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.reader.Utf8ObjectReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.reflect.TypeRef;

final class JsonReaderCodegen {
  private static final int GENERIC_READER = JsonCodegen.GENERIC_READER;
  private static final int LATIN1_READER = JsonCodegen.LATIN1_READER;
  private static final int UTF16_READER = JsonCodegen.UTF16_READER;
  private static final int UTF8_READER = JsonCodegen.UTF8_READER;

  private final JsonCodegen codegen;

  JsonReaderCodegen(JsonCodegen codegen) {
    this.codegen = codegen;
  }

  private Class<?> codecFieldType(JsonCodec codec) {
    return codegen.codecFieldType(codec);
  }

  private static Class<?> readNestedType(JsonFieldInfo property) {
    return JsonCodegen.readNestedType(property);
  }

  String genReaderCode(
      JsonGeneratedCodecBuilder builder,
      String className,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean record) {
    CodegenContext ctx = builder.context();
    ctx.addImports(
        BaseObjectCodec.class,
        JsonReader.class,
        Latin1JsonReader.class,
        Utf16JsonReader.class,
        Utf8JsonReader.class);
    ctx.implementsInterfaces(
        ctx.type(ObjectReader.class),
        ctx.type(Latin1ObjectReader.class),
        ctx.type(Utf16ObjectReader.class),
        ctx.type(Utf8ObjectReader.class));
    ctx.addField(long[].class, "fieldHashes");
    for (int i = 0; i < properties.length; i++) {
      ctx.addField(JsonFieldInfo.class, "p" + i);
      if (usesReadCodec(properties[i])) {
        ctx.addField(codecFieldType(properties[i].readTypeInfo().codec()), "r" + i);
      }
      if (usesReadTypeField(properties[i])) {
        ctx.addField(JsonTypeInfo.class, "t" + i);
      }
      if (storesReadObjectCodec(type, properties[i])) {
        ctx.addField(BaseObjectCodec.class, "c" + i);
      }
    }
    addGeneratedConstructor(
        ctx,
        readerConstructorExpression(type, properties),
        JsonFieldInfo[].class,
        "properties",
        JsonCodec[].class,
        "codecs",
        BaseObjectCodec[].class,
        "objectCodecs");
    addGeneratedMethod(
        ctx,
        "final",
        "fieldIndex",
        fieldIndexExpression(properties),
        int.class,
        long.class,
        "fieldHash");
    addGeneratedMethod(
        ctx,
        "public",
        "read",
        readExpression(builder, type, properties, GENERIC_READER, record),
        Object.class,
        JsonReader.class,
        "reader",
        BaseObjectCodec.class,
        "owner",
        JsonTypeResolver.class,
        "typeResolver");
    addGeneratedMethod(
        ctx,
        "public",
        "readLatin1",
        fastReadExpression(builder, "readLatin1Slow", type, properties, LATIN1_READER, record),
        Object.class,
        Latin1JsonReader.class,
        "reader",
        BaseObjectCodec.class,
        "owner",
        JsonTypeResolver.class,
        "typeResolver");
    addSlowReadMethods(
        ctx,
        builder,
        "readLatin1Slow",
        Latin1JsonReader.class,
        type,
        properties,
        LATIN1_READER,
        record);
    addGeneratedMethod(
        ctx,
        "public",
        "readUtf16",
        fastReadExpression(builder, "readUtf16Slow", type, properties, UTF16_READER, record),
        Object.class,
        Utf16JsonReader.class,
        "reader",
        BaseObjectCodec.class,
        "owner",
        JsonTypeResolver.class,
        "typeResolver");
    addSlowReadMethods(
        ctx,
        builder,
        "readUtf16Slow",
        Utf16JsonReader.class,
        type,
        properties,
        UTF16_READER,
        record);
    addGeneratedMethod(
        ctx,
        "public",
        "readUtf8",
        fastReadExpression(builder, "readUtf8Slow", type, properties, UTF8_READER, record),
        Object.class,
        Utf8JsonReader.class,
        "reader",
        BaseObjectCodec.class,
        "owner",
        JsonTypeResolver.class,
        "typeResolver");
    addSlowReadMethods(
        ctx, builder, "readUtf8Slow", Utf8JsonReader.class, type, properties, UTF8_READER, record);
    return ctx.genCode();
  }

  private void addSlowReadMethods(
      CodegenContext ctx,
      JsonGeneratedCodecBuilder builder,
      String methodName,
      Class<?> readerType,
      Class<?> type,
      JsonFieldInfo[] properties,
      int readerMode,
      boolean record) {
    Class<?> objectType = record ? Object[].class : type;
    addGeneratedMethod(
        ctx,
        "final",
        methodName,
        slowReadExpression(builder, type, properties, readerMode, record),
        void.class,
        readerType,
        "reader",
        BaseObjectCodec.class,
        "owner",
        JsonTypeResolver.class,
        "typeResolver",
        objectType,
        "object",
        int.class,
        "expectedIndex");
    addGeneratedMethod(
        ctx,
        "final",
        methodName,
        slowReadFromFirstExpression(builder, type, properties, readerMode, record),
        void.class,
        readerType,
        "reader",
        BaseObjectCodec.class,
        "owner",
        JsonTypeResolver.class,
        "typeResolver",
        objectType,
        "object",
        int.class,
        "expectedIndex",
        int.class,
        "firstFieldIndex");
  }

  private void addGeneratedConstructor(
      CodegenContext ctx, Expression expression, Object... params) {
    ctx.clearExprState();
    Code.ExprCode body = expression.genCode(ctx);
    String code = body.code();
    code = code == null ? "" : ctx.optimizeMethodCode(code);
    ctx.addConstructor(code, params);
  }

  private void addGeneratedMethod(
      CodegenContext ctx,
      String modifier,
      String name,
      Expression expression,
      Class<?> returnType,
      Object... params) {
    ctx.clearExprState();
    Code.ExprCode body = expression.genCode(ctx);
    String code = body.code();
    code = code == null ? "" : ctx.optimizeMethodCode(code);
    ctx.addMethod(modifier, name, code, returnType, params);
  }

  private Expression readerConstructorExpression(Class<?> type, JsonFieldInfo[] properties) {
    Expression.ListExpression expressions = new Expression.ListExpression();
    Reference propertiesRef = new Reference("properties", TypeRef.of(JsonFieldInfo[].class));
    Reference codecsRef = new Reference("codecs", TypeRef.of(JsonCodec[].class));
    Reference objectCodecsRef = new Reference("objectCodecs", TypeRef.of(BaseObjectCodec[].class));
    Reference hashes = new Reference("this.fieldHashes", TypeRef.of(long[].class));
    expressions.add(
        new Expression.Assign(
            hashes,
            new Expression.NewArray(
                long.class,
                new Expression.FieldValue(
                    propertiesRef, "length", TypeRef.of(int.class), false, true))));
    for (int i = 0; i < properties.length; i++) {
      Expression id = Expression.Literal.ofInt(i);
      Expression property = new Expression.ArrayValue(propertiesRef, id);
      expressions.add(
          new Expression.Assign(
              new Reference("this.p" + i, TypeRef.of(JsonFieldInfo.class)), property));
      expressions.add(
          new Expression.AssignArrayElem(
              hashes, new Expression.Invoke(property, "nameHash", TypeRef.of(long.class)), id));
      if (usesReadCodec(properties[i])) {
        Class<?> codecType = codecFieldType(properties[i].readTypeInfo().codec());
        expressions.add(
            new Expression.Assign(
                new Reference("this.r" + i, TypeRef.of(codecType)),
                new Expression.Cast(
                    new Expression.ArrayValue(codecsRef, id), TypeRef.of(codecType))));
      }
      if (usesReadTypeField(properties[i])) {
        expressions.add(
            new Expression.Assign(
                new Reference("this.t" + i, TypeRef.of(JsonTypeInfo.class)),
                new Expression.Invoke(property, "readTypeInfo", TypeRef.of(JsonTypeInfo.class))));
      }
      if (storesReadObjectCodec(type, properties[i])) {
        expressions.add(
            new Expression.Assign(
                new Reference("this.c" + i, TypeRef.of(BaseObjectCodec.class)),
                new Expression.ArrayValue(objectCodecsRef, id)));
      }
    }
    return expressions;
  }

  private Expression fieldIndexExpression(JsonFieldInfo[] properties) {
    Expression.ListExpression expressions = new Expression.ListExpression();
    Reference fieldHash = new Reference("fieldHash", TypeRef.of(long.class));
    for (int i = 0; i < properties.length; i++) {
      expressions.add(
          new Expression.If(
              eq(fieldHash, Expression.Literal.ofLong(properties[i].nameHash())),
              new Expression.Return(Expression.Literal.ofInt(i))));
    }
    expressions.add(new Expression.Return(Expression.Literal.ofInt(-1)));
    return expressions;
  }

  private Expression readExpression(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      int readerMode,
      boolean record) {
    Expression object = objectExpression(builder, record);
    Expression hashes =
        new Expression.Variable("localFieldHashes", fieldRef("fieldHashes", long[].class));
    Expression expectedIndex =
        new Expression.Variable("expectedIndex", Expression.Literal.ofInt(0));
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(object);
    expressions.add(expectExpr(readerMode, '{'));
    expressions.add(new Expression.If(consumeExpr(readerMode, '}'), returnObject(object, record)));
    expressions.add(hashes);
    expressions.add(expectedIndex);
    Expression.ListExpression loop = new Expression.ListExpression();
    loop.add(
        readNextHashedField(
            builder, type, properties, readerMode, object, hashes, expectedIndex, record));
    loop.add(
        new Expression.If(not(consumeCommaOrEndObjectExpr(readerMode)), new Expression.Break()));
    expressions.add(new Expression.While(Expression.Literal.True, loop));
    expressions.add(returnObject(object, record));
    return expressions;
  }

  private Expression fastReadExpression(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int readerMode,
      boolean record) {
    Expression object = objectExpression(builder, record);
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(object);
    expressions.add(expectExpr(readerMode, '{'));
    expressions.add(new Expression.If(consumeExpr(readerMode, '}'), returnObject(object, record)));
    if (properties.length == 0) {
      expressions.add(slowCall(slowMethod, object, Expression.Literal.ofInt(0)));
      expressions.add(returnObject(object, record));
      return expressions;
    }
    Expression hashes =
        new Expression.Variable("localFieldHashes", fieldRef("fieldHashes", long[].class));
    expressions.add(hashes);
    Expression[] skips = new Expression[properties.length];
    for (int i = 1; i < properties.length; i++) {
      skips[i] = new Expression.Variable("skip" + i, Expression.Literal.False);
      expressions.add(skips[i]);
    }
    for (int i = 0; i < properties.length; i++) {
      Expression read =
          fastReadField(
              builder, slowMethod, type, properties, i, readerMode, object, hashes, skips, record);
      expressions.add(i == 0 ? read : new Expression.If(not(skips[i]), read));
    }
    expressions.add(returnObject(object, record));
    return expressions;
  }

  private Expression fastReadField(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int index,
      int readerMode,
      Expression object,
      Expression hashes,
      Expression[] skips,
      boolean record) {
    if (isPackedName(properties[index].name())) {
      return new Expression.If(
          tryReadNextFieldNameColon(readerMode, properties[index]),
          new Expression.ListExpression(
              readField(
                  builder,
                  type,
                  properties[index],
                  index,
                  readerMode,
                  object,
                  record,
                  usesTokenValueRead(readerMode)),
              fieldEnd(slowMethod, properties.length, index, readerMode, object, record)),
          nextDirectFallback(
              builder,
              slowMethod,
              type,
              properties,
              index,
              readerMode,
              object,
              hashes,
              skips,
              record));
    }
    return nextDirectFallback(
        builder, slowMethod, type, properties, index, readerMode, object, hashes, skips, record);
  }

  private Expression nextDirectFallback(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int index,
      int readerMode,
      Expression object,
      Expression hashes,
      Expression[] skips,
      boolean record) {
    int nextIndex = index + 1;
    if (nextIndex < properties.length && isPackedName(properties[nextIndex].name())) {
      return new Expression.If(
          tryReadNextFieldNameColon(readerMode, properties[nextIndex]),
          new Expression.ListExpression(
              readField(
                  builder,
                  type,
                  properties[nextIndex],
                  nextIndex,
                  readerMode,
                  object,
                  record,
                  usesTokenValueRead(readerMode)),
              fieldEnd(slowMethod, properties.length, nextIndex, readerMode, object, record),
              new Expression.Assign(skips[nextIndex], Expression.Literal.True)),
          hashFallback(
              builder,
              slowMethod,
              type,
              properties,
              index,
              readerMode,
              object,
              hashes,
              skips,
              record));
    }
    return hashFallback(
        builder, slowMethod, type, properties, index, readerMode, object, hashes, skips, record);
  }

  private Expression hashFallback(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int index,
      int readerMode,
      Expression object,
      Expression hashes,
      Expression[] skips,
      boolean record) {
    Expression fieldHash = readFieldNameHash(readerMode, "fieldHash" + index);
    return new Expression.ListExpression(
        fieldHash,
        fastReadFieldFromHash(
            builder,
            slowMethod,
            type,
            properties,
            index,
            readerMode,
            object,
            hashes,
            skips,
            fieldHash,
            record));
  }

  private Expression fastReadFieldFromHash(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int index,
      int readerMode,
      Expression object,
      Expression hashes,
      Expression[] skips,
      Expression fieldHash,
      boolean record) {
    Expression fallback;
    if (index + 1 < properties.length) {
      fallback =
          new Expression.If(
              eq(fieldHash, arrayValue(hashes, index + 1)),
              new Expression.ListExpression(
                  expectExpr(readerMode, ':'),
                  readField(
                      builder,
                      type,
                      properties[index + 1],
                      index + 1,
                      readerMode,
                      object,
                      record,
                      false),
                  fieldEnd(slowMethod, properties.length, index + 1, readerMode, object, record),
                  new Expression.Assign(skips[index + 1], Expression.Literal.True)),
              slowConsumedReturn(slowMethod, index, fieldIndexInvoke(fieldHash), object, record));
    } else {
      fallback = slowConsumedReturn(slowMethod, index, fieldIndexInvoke(fieldHash), object, record);
    }
    return new Expression.If(
        ne(fieldHash, arrayValue(hashes, index)),
        fallback,
        new Expression.ListExpression(
            expectExpr(readerMode, ':'),
            readField(builder, type, properties[index], index, readerMode, object, record, false),
            fieldEnd(slowMethod, properties.length, index, readerMode, object, record)));
  }

  private static boolean isPackedName(String name) {
    int length = name.length();
    if (length == 0 || length > Long.BYTES) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      char ch = name.charAt(i);
      if (ch == 0 || ch > 0xFF) {
        return false;
      }
    }
    return true;
  }

  private static long packedNameMask(int length) {
    return length == Long.BYTES ? -1L : (1L << (length << 3)) - 1L;
  }

  private Expression objectExpression(JsonGeneratedCodecBuilder builder, boolean record) {
    if (record) {
      return new Expression.Variable(
          "object",
          new Expression.Invoke(
              ownerRef(), "newRecordFieldValues", TypeRef.of(Object[].class), false));
    }
    return new Expression.Variable("object", builder.newObject());
  }

  private Expression returnObject(Expression object, boolean record) {
    if (record) {
      return new Expression.Return(
          new Expression.Invoke(ownerRef(), "newRecord", TypeRef.of(Object.class), object));
    }
    return new Expression.Return(object);
  }

  private Expression slowConsumedReturn(
      String slowMethod, int index, Expression firstFieldIndex, Expression object, boolean record) {
    return new Expression.ListExpression(
        slowCall(slowMethod, object, Expression.Literal.ofInt(index), firstFieldIndex),
        returnObject(object, record));
  }

  private Expression fieldEnd(
      String slowMethod,
      int propertyCount,
      int index,
      int readerMode,
      Expression object,
      boolean record) {
    if (index + 1 < propertyCount) {
      return new Expression.If(
          not(consumeCommaOrEndObjectExpr(readerMode)), returnObject(object, record));
    }
    return new Expression.If(
        consumeCommaOrEndObjectExpr(readerMode),
        slowCall(slowMethod, object, Expression.Literal.ofInt(propertyCount)));
  }

  private Expression slowReadExpression(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      int readerMode,
      boolean record) {
    Expression object = objectParam(type, record);
    Expression hashes =
        new Expression.Variable("localFieldHashes", fieldRef("fieldHashes", long[].class));
    Reference expectedIndex = new Reference("expectedIndex", TypeRef.of(int.class));
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(hashes);
    Expression.ListExpression loop = new Expression.ListExpression();
    loop.add(
        readNextHashedField(
            builder, type, properties, readerMode, object, hashes, expectedIndex, record));
    loop.add(
        new Expression.If(not(consumeCommaOrEndObjectExpr(readerMode)), new Expression.Break()));
    expressions.add(new Expression.While(Expression.Literal.True, loop));
    return expressions;
  }

  private Expression slowReadFromFirstExpression(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      int readerMode,
      boolean record) {
    Expression object = objectParam(type, record);
    Expression hashes =
        new Expression.Variable("localFieldHashes", fieldRef("fieldHashes", long[].class));
    Reference expectedIndex = new Reference("expectedIndex", TypeRef.of(int.class));
    Expression fieldIndex =
        new Expression.Variable(
            "fieldIndex", new Reference("firstFieldIndex", TypeRef.of(int.class)));
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(hashes);
    expressions.add(fieldIndex);
    Expression.ListExpression loop = new Expression.ListExpression();
    loop.add(expectExpr(readerMode, ':'));
    loop.add(fieldSwitch(builder, type, properties, readerMode, object, fieldIndex, record));
    loop.add(updateExpectedIndex(expectedIndex, fieldIndex));
    loop.add(
        new Expression.If(not(consumeCommaOrEndObjectExpr(readerMode)), new Expression.Return()));
    Expression fieldHash = readFieldNameHash(readerMode, "fieldHash");
    loop.add(fieldHash);
    loop.add(new Expression.Assign(fieldIndex, fieldIndexValue(expectedIndex, hashes, fieldHash)));
    expressions.add(new Expression.While(Expression.Literal.True, loop));
    return expressions;
  }

  private Expression readNextHashedField(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      int readerMode,
      Expression object,
      Expression hashes,
      Expression expectedIndex,
      boolean record) {
    Expression fieldHash = readFieldNameHash(readerMode, "fieldHash");
    Expression fieldIndex =
        new Expression.Variable("fieldIndex", fieldIndexValue(expectedIndex, hashes, fieldHash));
    return new Expression.ListExpression(
        fieldHash,
        fieldIndex,
        expectExpr(readerMode, ':'),
        fieldSwitch(builder, type, properties, readerMode, object, fieldIndex, record),
        updateExpectedIndex(expectedIndex, fieldIndex));
  }

  private Expression fieldSwitch(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      int readerMode,
      Expression object,
      Expression fieldIndex,
      boolean record) {
    Expression.Switch.Case[] cases = new Expression.Switch.Case[properties.length];
    for (int i = 0; i < properties.length; i++) {
      cases[i] =
          new Expression.Switch.Case(
              i,
              new Expression.ListExpression(
                  readField(builder, type, properties[i], i, readerMode, object, record, false),
                  new Expression.Break()));
    }
    return new Expression.Switch(
        fieldIndex, cases, new Expression.Invoke(readerRef(readerMode), "skipValue"));
  }

  private Expression updateExpectedIndex(Expression expectedIndex, Expression fieldIndex) {
    return new Expression.If(
        ge(fieldIndex, Expression.Literal.ofInt(0)),
        new Expression.Assign(
            expectedIndex, new Expression.Add(fieldIndex, Expression.Literal.ofInt(1))));
  }

  private Expression fieldIndexValue(
      Expression expectedIndex, Expression hashes, Expression fieldHash) {
    return new Expression.Ternary(
        and(
            lt(
                expectedIndex,
                new Expression.FieldValue(hashes, "length", TypeRef.of(int.class), false, true)),
            eq(fieldHash, new Expression.ArrayValue(hashes, expectedIndex))),
        expectedIndex,
        fieldIndexInvoke(fieldHash),
        true,
        TypeRef.of(int.class));
  }

  private static Expression objectParam(Class<?> type, boolean record) {
    return record
        ? new Reference("object", TypeRef.of(Object[].class))
        : new Reference("object", TypeRef.of(type));
  }

  private static Reference ownerRef() {
    return new Reference("owner", TypeRef.of(BaseObjectCodec.class));
  }

  private static Reference typeResolverRef() {
    return new Reference("typeResolver", TypeRef.of(JsonTypeResolver.class));
  }

  private static Reference fieldRef(String name, Class<?> type) {
    return Reference.fieldRef(name, TypeRef.of(type));
  }

  private static Reference readerRef(int readerMode) {
    return new Reference("reader", TypeRef.of(readerClass(readerMode)));
  }

  private static Class<?> readerClass(int readerMode) {
    switch (readerMode) {
      case LATIN1_READER:
        return Latin1JsonReader.class;
      case UTF16_READER:
        return Utf16JsonReader.class;
      case UTF8_READER:
        return Utf8JsonReader.class;
      default:
        return JsonReader.class;
    }
  }

  private static Expression expectExpr(int readerMode, char token) {
    return new Expression.Invoke(
        readerRef(readerMode),
        readerMode == GENERIC_READER ? "expect" : "expectNextToken",
        Expression.Literal.ofChar(token));
  }

  private static Expression consumeExpr(int readerMode, char token) {
    return new Expression.Invoke(
            readerRef(readerMode),
            readerMode == GENERIC_READER ? "consume" : "consumeNextToken",
            TypeRef.of(boolean.class),
            Expression.Literal.ofChar(token))
        .inline();
  }

  private static Expression consumeCommaOrEndObjectExpr(int readerMode) {
    return new Expression.Invoke(
            readerRef(readerMode),
            readerMode == GENERIC_READER
                ? "consumeCommaOrEndObject"
                : "consumeNextCommaOrEndObject",
            TypeRef.of(boolean.class))
        .inline();
  }

  private static Expression tryReadNullExpr(int readerMode) {
    return new Expression.Invoke(
            readerRef(readerMode),
            readerMode == GENERIC_READER ? "tryReadNull" : "tryReadNextNullToken",
            TypeRef.of(boolean.class))
        .inline();
  }

  private static Expression readBooleanExpr(int readerMode) {
    return readBooleanExpr(readerMode, false);
  }

  private static Expression readBooleanExpr(int readerMode, boolean tokenValueRead) {
    return new Expression.Invoke(
            readerRef(readerMode),
            readBooleanMethod(readerMode, tokenValueRead),
            TypeRef.of(boolean.class))
        .inline();
  }

  private static Expression readIntExpr(int readerMode) {
    return readIntExpr(readerMode, false);
  }

  private static Expression readIntExpr(int readerMode, boolean tokenValueRead) {
    return new Expression.Invoke(
            readerRef(readerMode), readIntMethod(readerMode, tokenValueRead), TypeRef.of(int.class))
        .inline();
  }

  private static Expression readLongExpr(int readerMode) {
    return readLongExpr(readerMode, false);
  }

  private static Expression readLongExpr(int readerMode, boolean tokenValueRead) {
    return new Expression.Invoke(
            readerRef(readerMode),
            readLongMethod(readerMode, tokenValueRead),
            TypeRef.of(long.class))
        .inline();
  }

  private static Expression readStringExpr(int readerMode) {
    return readStringExpr(readerMode, false);
  }

  private static Expression readStringExpr(int readerMode, boolean tokenValueRead) {
    return new Expression.Invoke(
            readerRef(readerMode),
            readStringMethod(readerMode, tokenValueRead),
            TypeRef.of(String.class),
            true)
        .inline();
  }

  private static String readBooleanMethod(int readerMode, boolean tokenValueRead) {
    if (readerMode == GENERIC_READER) {
      return "readBoolean";
    }
    return tokenValueRead ? "readBooleanTokenValue" : "readNextBooleanValue";
  }

  private static String readIntMethod(int readerMode, boolean tokenValueRead) {
    if (readerMode == GENERIC_READER) {
      return "readInt";
    }
    return tokenValueRead ? "readIntTokenValue" : "readNextIntValue";
  }

  private static String readLongMethod(int readerMode, boolean tokenValueRead) {
    if (readerMode == GENERIC_READER) {
      return "readLong";
    }
    return tokenValueRead ? "readLongTokenValue" : "readNextLongValue";
  }

  private static String readStringMethod(int readerMode, boolean tokenValueRead) {
    if (readerMode == GENERIC_READER) {
      return "readNullableString";
    }
    return tokenValueRead ? "readNullableStringToken" : "readNextNullableString";
  }

  private static boolean usesTokenValueRead(int readerMode) {
    return readerMode == LATIN1_READER || readerMode == UTF8_READER;
  }

  private static Expression readFieldNameHash(int readerMode, String namePrefix) {
    return new Expression.Invoke(
        readerRef(readerMode), "readFieldNameHash", namePrefix, TypeRef.of(long.class), false);
  }

  private static Expression fieldIndexInvoke(Expression fieldHash) {
    return new Expression.Invoke(
            new Reference("this", TypeRef.of(Object.class)),
            "fieldIndex",
            "",
            TypeRef.of(int.class),
            false,
            false,
            fieldHash)
        .inline();
  }

  private static Expression tryReadNextFieldNameColon(int readerMode, JsonFieldInfo property) {
    if (readerMode == LATIN1_READER || readerMode == UTF8_READER) {
      String name = property.name();
      String token = fieldNameToken(name);
      int tokenLength = token.length();
      int suffixLength = JsonAsciiToken.suffixLength(tokenLength);
      // This is a compact-JSON fast path. Whitespace, escapes, and UTF8 spellings that do not
      // match the raw token fall through to the generated field-hash reader without consuming.
      if (suffixLength == 0) {
        return new Expression.Invoke(
                readerRef(readerMode),
                "tryReadNextFieldNameToken0",
                TypeRef.of(boolean.class),
                Expression.Literal.ofLong(JsonAsciiToken.prefix(token)),
                Expression.Literal.ofLong(JsonAsciiToken.prefixMask(tokenLength)),
                Expression.Literal.ofInt(tokenLength))
            .inline();
      }
      return new Expression.Invoke(
              readerRef(readerMode),
              "tryReadNextFieldNameToken" + suffixLength,
              TypeRef.of(boolean.class),
              Expression.Literal.ofLong(JsonAsciiToken.prefix(token)),
              Expression.Literal.ofLong(JsonAsciiToken.prefixMask(tokenLength)),
              Expression.Literal.ofInt(JsonAsciiToken.suffix(token)),
              Expression.Literal.ofInt(tokenLength))
          .inline();
    }
    return new Expression.Invoke(
            readerRef(readerMode),
            "tryReadNextFieldNameColon",
            TypeRef.of(boolean.class),
            Expression.Literal.ofLong(property.nameHash()),
            Expression.Literal.ofLong(packedNameMask(property.name().length())),
            Expression.Literal.ofInt(property.name().length()))
        .inline();
  }

  private static Expression tryReadNextStringToken(int readerMode, String token) {
    int tokenLength = token.length();
    int suffixLength = JsonAsciiToken.suffixLength(tokenLength);
    if (suffixLength == 0) {
      return new Expression.Invoke(
              readerRef(readerMode),
              "tryReadNextStringToken0",
              TypeRef.of(boolean.class),
              Expression.Literal.ofLong(JsonAsciiToken.prefix(token)),
              Expression.Literal.ofLong(JsonAsciiToken.prefixMask(tokenLength)),
              Expression.Literal.ofInt(tokenLength))
          .inline();
    }
    return new Expression.Invoke(
            readerRef(readerMode),
            "tryReadNextStringToken" + suffixLength,
            TypeRef.of(boolean.class),
            Expression.Literal.ofLong(JsonAsciiToken.prefix(token)),
            Expression.Literal.ofLong(JsonAsciiToken.prefixMask(tokenLength)),
            Expression.Literal.ofInt(JsonAsciiToken.suffix(token)),
            Expression.Literal.ofInt(tokenLength))
        .inline();
  }

  private static String fieldNameToken(String name) {
    return "\"" + name + "\":";
  }

  private static Expression slowCall(
      String slowMethod, Expression object, Expression expectedIndex) {
    return slowCall(slowMethod, object, expectedIndex, null);
  }

  private static Expression slowCall(
      String slowMethod, Expression object, Expression expectedIndex, Expression firstFieldIndex) {
    if (firstFieldIndex == null) {
      return new Expression.Invoke(
          new Reference("this", TypeRef.of(Object.class)),
          slowMethod,
          "",
          TypeRef.of(void.class),
          false,
          false,
          readerRefForCall(),
          ownerRef(),
          typeResolverRef(),
          object,
          expectedIndex);
    }
    return new Expression.Invoke(
        new Reference("this", TypeRef.of(Object.class)),
        slowMethod,
        "",
        TypeRef.of(void.class),
        false,
        false,
        readerRefForCall(),
        ownerRef(),
        typeResolverRef(),
        object,
        expectedIndex,
        firstFieldIndex);
  }

  private static Reference readerRefForCall() {
    return new Reference("reader");
  }

  private static Expression arrayValue(Expression array, int index) {
    return new Expression.ArrayValue(array, Expression.Literal.ofInt(index));
  }

  private static Expression eq(Expression left, Expression right) {
    return new Expression.Comparator("==", left, right, true);
  }

  private static Expression ne(Expression left, Expression right) {
    return new Expression.Comparator("!=", left, right, true);
  }

  private static Expression lt(Expression left, Expression right) {
    return new Expression.Comparator("<", left, right, true);
  }

  private static Expression ge(Expression left, Expression right) {
    return new Expression.Comparator(">=", left, right, true);
  }

  private static Expression and(Expression left, Expression right) {
    return new Expression.LogicalAnd(left, right);
  }

  private static Expression not(Expression expression) {
    return new Expression.Not(expression);
  }

  private static boolean usesWriteCodec(JsonFieldInfo property) {
    switch (property.writeKind()) {
      case ARRAY:
      case MAP:
      case OBJECT:
        return true;
      case COLLECTION:
        return property.writeElementRawType() != String.class;
      default:
        return false;
    }
  }

  private static boolean usesReadCodec(JsonFieldInfo property) {
    switch (property.readKind()) {
      case ENUM:
      case ARRAY:
      case COLLECTION:
      case MAP:
        return true;
      default:
        return false;
    }
  }

  private static boolean usesReadTypeField(JsonFieldInfo property) {
    switch (property.readKind()) {
      case ARRAY:
      case COLLECTION:
      case MAP:
        return true;
      case OBJECT:
        return usesReadObjectCodec(property);
      default:
        return false;
    }
  }

  private static boolean usesReadObjectCodec(JsonFieldInfo property) {
    return property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && property.readTypeInfo().codec() instanceof BaseObjectCodec;
  }

  private static boolean storesReadObjectCodec(Class<?> type, JsonFieldInfo property) {
    Class<?> nestedType = readNestedType(property);
    return nestedType != null && nestedType != type;
  }

  private Expression readField(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo property,
      int id,
      int readerMode,
      Expression object,
      boolean record,
      boolean tokenValueRead) {
    if (record) {
      return readRecordField(type, property, id, readerMode, object, tokenValueRead);
    }
    Class<?> rawType = property.readRawType();
    switch (property.readKind()) {
      case BOOLEAN:
        return readBoolean(builder, property, rawType, readerMode, object, tokenValueRead);
      case INT:
        return readInt(builder, property, rawType, readerMode, object, tokenValueRead);
      case LONG:
        return readLong(builder, property, rawType, readerMode, object, tokenValueRead);
      case STRING:
        return builder.setField(property, object, readStringExpr(readerMode, tokenValueRead));
      case ENUM:
        return readEnum(builder, property, id, readerMode, object, tokenValueRead);
      case COLLECTION:
        return readCollection(builder, property, id, readerMode, object);
      case ARRAY:
      case MAP:
        return readResolvedField(builder, property, id, readerMode, object);
      case OBJECT:
        return readObject(builder, type, property, id, readerMode, object);
      default:
        return new Expression.Invoke(
            fieldRef("p" + id, JsonFieldInfo.class),
            "read",
            readerRef(readerMode),
            object,
            typeResolverRef());
    }
  }

  private Expression readRecordField(
      Class<?> type,
      JsonFieldInfo property,
      int id,
      int readerMode,
      Expression object,
      boolean tokenValueRead) {
    Class<?> rawType = property.readRawType();
    switch (property.readKind()) {
      case BOOLEAN:
        return readRecordBoolean(rawType, id, readerMode, object, tokenValueRead);
      case INT:
        return readRecordInt(rawType, id, readerMode, object, tokenValueRead);
      case LONG:
        return readRecordLong(rawType, id, readerMode, object, tokenValueRead);
      case STRING:
        return assignRecord(object, id, readStringExpr(readerMode, tokenValueRead));
      case ENUM:
        return readRecordEnum(id, readerMode, object, tokenValueRead);
      case COLLECTION:
        return readRecordCollection(property, id, readerMode, object);
      case ARRAY:
      case MAP:
        return assignRecord(object, id, readResolvedValue(property, id, readerMode));
      case OBJECT:
        return readRecordObject(type, property, id, readerMode, object);
      default:
        return assignRecord(
            object,
            id,
            new Expression.Invoke(
                fieldRef("p" + id, JsonFieldInfo.class),
                "readValue",
                TypeRef.of(Object.class),
                true,
                readerRef(readerMode),
                typeResolverRef()));
    }
  }

  private static Expression readRecordBoolean(
      Class<?> rawType, int id, int readerMode, Expression object, boolean tokenValueRead) {
    Expression value = box(Boolean.class, readBooleanExpr(readerMode, tokenValueRead));
    if (rawType.isPrimitive()) {
      return assignRecord(object, id, value);
    }
    return new Expression.If(
        tryReadNullExpr(readerMode),
        assignRecord(object, id, new Expression.Null(TypeRef.of(Boolean.class), false)),
        assignRecord(object, id, value));
  }

  private static Expression readRecordInt(
      Class<?> rawType, int id, int readerMode, Expression object, boolean tokenValueRead) {
    Expression value = box(Integer.class, readIntExpr(readerMode, tokenValueRead));
    if (rawType.isPrimitive()) {
      return assignRecord(object, id, value);
    }
    return new Expression.If(
        tryReadNullExpr(readerMode),
        assignRecord(object, id, new Expression.Null(TypeRef.of(Integer.class), false)),
        assignRecord(object, id, value));
  }

  private static Expression readRecordLong(
      Class<?> rawType, int id, int readerMode, Expression object, boolean tokenValueRead) {
    Expression value = box(Long.class, readLongExpr(readerMode, tokenValueRead));
    if (rawType.isPrimitive()) {
      return assignRecord(object, id, value);
    }
    return new Expression.If(
        tryReadNullExpr(readerMode),
        assignRecord(object, id, new Expression.Null(TypeRef.of(Long.class), false)),
        assignRecord(object, id, value));
  }

  private static Expression readRecordEnum(
      int id, int readerMode, Expression object, boolean tokenValueRead) {
    return new Expression.If(
        tryReadNullExpr(readerMode),
        assignRecord(object, id, new Expression.Null(TypeRef.of(Object.class), false)),
        assignRecord(object, id, readEnumValue(Object.class, id, readerMode, tokenValueRead)));
  }

  private static Expression readRecordObject(
      Class<?> type, JsonFieldInfo property, int id, int readerMode, Expression object) {
    if (property.readRawType() == Object.class
        || !(property.readTypeInfo().codec() instanceof BaseObjectCodec)) {
      return assignRecord(
          object,
          id,
          new Expression.Invoke(
              fieldRef("p" + id, JsonFieldInfo.class),
              "readValue",
              TypeRef.of(Object.class),
              true,
              readerRef(readerMode),
              typeResolverRef()));
    }
    return new Expression.If(
        tryReadNullExpr(readerMode),
        assignRecord(object, id, new Expression.Null(TypeRef.of(property.readRawType()), false)),
        assignRecord(object, id, readObjectValue(type, property, id, readerMode)));
  }

  private static Expression readRecordCollection(
      JsonFieldInfo property, int id, int readerMode, Expression object) {
    if (readerMode == GENERIC_READER) {
      return assignRecord(object, id, readResolvedValue(property, id, readerMode));
    }
    return new Expression.If(
        tryReadNullExpr(readerMode),
        assignRecord(object, id, new Expression.Null(TypeRef.of(property.readRawType()), false)),
        assignRecord(object, id, readCollectionValue(property, id, readerMode)));
  }

  private static Expression readBoolean(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      Class<?> rawType,
      int readerMode,
      Expression object,
      boolean tokenValueRead) {
    if (rawType.isPrimitive()) {
      return builder.setField(property, object, readBooleanExpr(readerMode, tokenValueRead));
    }
    return new Expression.If(
        tryReadNullExpr(readerMode),
        builder.setNull(property, object),
        builder.setField(
            property, object, box(Boolean.class, readBooleanExpr(readerMode, tokenValueRead))));
  }

  private static Expression readInt(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      Class<?> rawType,
      int readerMode,
      Expression object,
      boolean tokenValueRead) {
    if (rawType.isPrimitive()) {
      return builder.setField(property, object, readIntExpr(readerMode, tokenValueRead));
    }
    return new Expression.If(
        tryReadNullExpr(readerMode),
        builder.setNull(property, object),
        builder.setField(
            property, object, box(Integer.class, readIntExpr(readerMode, tokenValueRead))));
  }

  private static Expression readLong(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      Class<?> rawType,
      int readerMode,
      Expression object,
      boolean tokenValueRead) {
    if (rawType.isPrimitive()) {
      return builder.setField(property, object, readLongExpr(readerMode, tokenValueRead));
    }
    return new Expression.If(
        tryReadNullExpr(readerMode),
        builder.setNull(property, object),
        builder.setField(
            property, object, box(Long.class, readLongExpr(readerMode, tokenValueRead))));
  }

  private static Expression readEnum(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      int readerMode,
      Expression object,
      boolean tokenValueRead) {
    return new Expression.If(
        tryReadNullExpr(readerMode),
        builder.setNull(property, object),
        readEnumField(builder, property, id, readerMode, object, tokenValueRead));
  }

  private static Expression readEnumField(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      int readerMode,
      Expression object,
      boolean tokenValueRead) {
    Expression fallback =
        builder.setField(
            property,
            object,
            readEnumValue(property.readRawType(), id, readerMode, tokenValueRead, true));
    if (!tokenValueRead || (readerMode != LATIN1_READER && readerMode != UTF8_READER)) {
      return fallback;
    }
    Enum<?>[] constants = (Enum<?>[]) property.readRawType().getEnumConstants();
    for (int i = constants.length - 1; i >= 0; i--) {
      Enum<?> constant = constants[i];
      String token = "\"" + constant.name() + "\"";
      if (!JsonAsciiToken.isPackable(token)) {
        continue;
      }
      fallback =
          new Expression.If(
              tryReadNextStringToken(readerMode, token),
              builder.setField(property, object, new Expression.EnumExpression(constant)),
              fallback);
    }
    return fallback;
  }

  private static Expression readResolvedField(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      int readerMode,
      Expression object) {
    return builder.setField(property, object, readResolvedValue(property, id, readerMode));
  }

  private static Expression readCollection(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      int readerMode,
      Expression object) {
    if (readerMode == GENERIC_READER) {
      return readResolvedField(builder, property, id, readerMode, object);
    }
    return new Expression.If(
        tryReadNullExpr(readerMode),
        builder.setNull(property, object),
        builder.setField(property, object, readCollectionValue(property, id, readerMode)));
  }

  private static Expression readObject(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo property,
      int id,
      int readerMode,
      Expression object) {
    if (property.readRawType() == Object.class
        || !(property.readTypeInfo().codec() instanceof BaseObjectCodec)) {
      return new Expression.Invoke(
          fieldRef("p" + id, JsonFieldInfo.class),
          "read",
          readerRef(readerMode),
          object,
          typeResolverRef());
    }
    return new Expression.If(
        tryReadNullExpr(readerMode),
        builder.setNull(property, object),
        builder.setField(property, object, readObjectValue(type, property, id, readerMode)));
  }

  private static Expression assignRecord(Expression object, int id, Expression value) {
    return new Expression.AssignArrayElem(object, value, Expression.Literal.ofInt(id));
  }

  private static Expression box(Class<?> boxedType, Expression value) {
    return new Expression.StaticInvoke(
        boxedType, "valueOf", "", TypeRef.of(boxedType), false, true, false, value);
  }

  private static Expression readEnumValue(
      Class<?> enumType, int id, int readerMode, boolean tokenValueRead) {
    return readEnumValue(enumType, id, readerMode, tokenValueRead, false);
  }

  private static Expression readEnumValue(
      Class<?> enumType, int id, int readerMode, boolean tokenValueRead, boolean hashFallback) {
    return new Expression.Cast(
        new Expression.Invoke(
            fieldRef("r" + id, JsonCodec.class),
            readEnumMethod(readerMode, tokenValueRead, hashFallback),
            "",
            TypeRef.of(Object.class),
            true,
            false,
            readerRef(readerMode)),
        TypeRef.of(enumType));
  }

  private static Expression readResolvedValue(JsonFieldInfo property, int id, int readerMode) {
    return new Expression.Cast(
        new Expression.Invoke(
            fieldRef("r" + id, JsonCodec.class),
            readObjectMethod(readerMode),
            TypeRef.of(Object.class),
            true,
            readerRef(readerMode),
            fieldRef("t" + id, JsonTypeInfo.class),
            typeResolverRef()),
        TypeRef.of(property.readRawType()));
  }

  private static Expression readCollectionValue(JsonFieldInfo property, int id, int readerMode) {
    return new Expression.Cast(
        new Expression.Invoke(
            fieldRef("r" + id, CollectionCodec.class),
            readObjectNonNullMethod(readerMode),
            TypeRef.of(Object.class),
            true,
            readerRef(readerMode),
            fieldRef("t" + id, JsonTypeInfo.class),
            typeResolverRef()),
        TypeRef.of(property.readRawType()));
  }

  private static Expression readObjectValue(
      Class<?> type, JsonFieldInfo property, int id, int readerMode) {
    Expression codec =
        property.readRawType() == type ? ownerRef() : fieldRef("c" + id, BaseObjectCodec.class);
    return new Expression.Cast(
        new Expression.Invoke(
            codec,
            readObjectNonNullMethod(readerMode),
            TypeRef.of(Object.class),
            true,
            readerRef(readerMode),
            fieldRef("t" + id, JsonTypeInfo.class),
            typeResolverRef()),
        TypeRef.of(property.readRawType()));
  }

  private static String readEnumMethod(int readerMode, boolean tokenValueRead) {
    return readEnumMethod(readerMode, tokenValueRead, false);
  }

  private static String readEnumMethod(
      int readerMode, boolean tokenValueRead, boolean hashFallback) {
    switch (readerMode) {
      case LATIN1_READER:
        return tokenValueRead
            ? (hashFallback ? "readLatin1EnumHashToken" : "readLatin1EnumToken")
            : "readNextLatin1Enum";
      case UTF16_READER:
        return "readNextUtf16Enum";
      case UTF8_READER:
        return tokenValueRead
            ? (hashFallback ? "readUtf8EnumHashToken" : "readUtf8EnumToken")
            : "readNextUtf8Enum";
      default:
        return "readEnum";
    }
  }

  private static String readObjectMethod(int readerMode) {
    switch (readerMode) {
      case LATIN1_READER:
        return "readLatin1";
      case UTF16_READER:
        return "readUtf16";
      case UTF8_READER:
        return "readUtf8";
      default:
        return "read";
    }
  }

  private static String readObjectNonNullMethod(int readerMode) {
    switch (readerMode) {
      case LATIN1_READER:
        return "readLatin1NonNull";
      case UTF16_READER:
        return "readUtf16NonNull";
      case UTF8_READER:
        return "readUtf8NonNull";
      default:
        return "read";
    }
  }
}
