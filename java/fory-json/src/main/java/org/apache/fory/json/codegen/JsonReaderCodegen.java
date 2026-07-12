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

import static org.apache.fory.codegen.ExpressionUtils.add;
import static org.apache.fory.codegen.ExpressionUtils.inline;

import org.apache.fory.codegen.Code;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.meta.JsonAsciiToken;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.reflect.TypeRef;

/**
 * Shared generation mechanics for concrete Latin1, UTF16, and UTF-8 object-reader capabilities.
 *
 * <p>The three nested generators own representation-specific token, field-name, enum, and direct
 * value expressions. This base shares only source-construction algorithms after the concrete reader
 * is selected; it is not a runtime reader mode. Generated readers retain immutable field lookup
 * metadata and concrete child capability fields, avoiding per-field resolver lookup. Wide objects
 * split generated methods to bound compiler size while preserving a single nullable capability
 * entry for each representation.
 */
abstract class JsonReaderCodegen {
  private static final int MIN_SPLIT_READ_FIELDS = 8;
  private static final int READ_FIELD_GROUP_SIZE = 2;
  private static final int READ_FIELD_SWITCH_SIZE = 8;
  private static final boolean LITTLE_ENDIAN = NativeByteOrder.IS_LITTLE_ENDIAN;
  private static final long UTF16_PAIR_MASK = 0x0000FFFF0000FFFFL;
  private static final long UTF16_BYTE_MASK = 0x00FF00FF00FF00FFL;

  final JsonCodegen codegen;

  JsonReaderCodegen(JsonCodegen codegen) {
    this.codegen = codegen;
  }

  abstract Class<?> codecFieldType(JsonFieldInfo property);

  abstract Class<?> readerType();

  abstract Class<?> readerCapabilityType();

  abstract Class<?> readerArrayType();

  abstract String readMethod();

  abstract String readEnumMethod(boolean tokenValueRead, boolean hashFallback);

  final String readEnumMethod(boolean tokenValueRead) {
    return readEnumMethod(tokenValueRead, false);
  }

  abstract String readObjectMethod();

  abstract String readFieldMethod();

  final String readFieldMethod(String readMethod, int start) {
    return readMethod + "Field" + start;
  }

  abstract String readFieldValueMethod();

  abstract boolean isDirectName(String name, boolean tokenValueRead);

  abstract Expression tryReadNextFieldNameColon(JsonFieldInfo property, boolean tokenValueRead);

  abstract Expression readEnumField(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      Expression object,
      boolean tokenValueRead);

  abstract Reference readerRef();

  final Class<?> readNestedType(JsonFieldInfo property) {
    return JsonCodegen.readNestedType(property);
  }

  String genReaderCode(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean record) {
    Class<?> readerType = readerType();
    String readMethod = readMethod();
    String slowMethod = readMethod + "Slow";
    CodegenContext ctx = builder.context();
    ctx.addImports(ObjectCodec.class, readerType, JsonFieldTable.class);
    ctx.implementsInterfaces(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()));
    // Generated readers retain immutable lookup metadata directly. Only record readers keep the
    // ObjectCodec because record allocation and construction remain owned by it.
    ctx.addField(JsonFieldTable.class, "readTable");
    if (record) {
      ctx.addField(ObjectCodec.class, "owner");
    }
    ctx.addField(long[].class, "fieldHashes");
    for (int i = 0; i < properties.length; i++) {
      if (usesReadInfo(properties[i])) {
        ctx.addField(JsonFieldInfo.class, "rp" + i);
      }
      if (JsonCodegen.usesReadCodec(properties[i])) {
        ctx.addField(JsonCodegen.generatedCodecType(ctx, codecFieldType(properties[i])), "r" + i);
      }
      if (storesReadObjectCodec(type, properties[i])) {
        ctx.addField(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()), "o" + i);
      }
    }
    addGeneratedConstructor(
        ctx,
        readerConstructorExpression(type, properties, record),
        ObjectCodec.class,
        "owner",
        JsonFieldInfo[].class,
        "properties",
        JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
        "codecs");
    ctx.clearExprState();
    Code.ExprCode body =
        fastReadExpression(builder, readMethod, slowMethod, type, properties, record).genCode(ctx);
    String bodyCode = body.code();
    bodyCode = bodyCode == null ? "" : ctx.optimizeMethodCode(bodyCode);
    ctx.addMethod(
        "@Override public final",
        readMethod,
        "if (reader.tryReadNullToken()) {\n" + "  return null;\n" + "}\n" + bodyCode,
        Object.class,
        readerType,
        "reader");
    addFastReadGroupMethods(
        ctx, builder, readMethod, slowMethod, readerType, type, properties, record);
    addReadFieldMethods(ctx, builder, readMethod, readerType, type, properties, record);
    addSlowReadMethods(ctx, builder, slowMethod, readerType, type, properties, record);
    return ctx.genCode();
  }

  private void addFastReadGroupMethods(
      CodegenContext ctx,
      JsonGeneratedCodecBuilder builder,
      String readMethod,
      String slowMethod,
      Class<?> readerType,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean record) {
    if (!shouldSplitFastRead(properties)) {
      return;
    }
    Class<?> objectType = record ? Object[].class : type;
    for (int start = 0; start < properties.length; ) {
      int end = readGroupEnd(properties, start);
      addGeneratedMethod(
          ctx,
          "final",
          readGroupMethod(readMethod, start),
          fastReadGroupExpression(builder, slowMethod, type, properties, start, end, record),
          boolean.class,
          readerType,
          "reader",
          objectType,
          "object",
          long[].class,
          "fieldHashes");
      start = end;
    }
  }

  private void addReadFieldMethods(
      CodegenContext ctx,
      JsonGeneratedCodecBuilder builder,
      String readMethod,
      Class<?> readerType,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean record) {
    if (!shouldSplitFieldSwitch(properties)) {
      return;
    }
    Class<?> objectType = record ? Object[].class : type;
    for (int start = 0; start < properties.length; start += READ_FIELD_SWITCH_SIZE) {
      int end = Math.min(start + READ_FIELD_SWITCH_SIZE, properties.length);
      addGeneratedMethod(
          ctx,
          "final",
          readFieldMethod(readMethod, start),
          fieldSwitchRange(
              builder,
              type,
              properties,
              start,
              end,
              objectParam(type, record),
              new Reference("fieldIndex", TypeRef.of(int.class)),
              record),
          void.class,
          readerType,
          "reader",
          objectType,
          "object",
          int.class,
          "fieldIndex");
    }
  }

  private void addSlowReadMethods(
      CodegenContext ctx,
      JsonGeneratedCodecBuilder builder,
      String methodName,
      Class<?> readerType,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean record) {
    Class<?> objectType = record ? Object[].class : type;
    addGeneratedMethod(
        ctx,
        "final",
        methodName,
        slowReadExpression(builder, type, properties, record),
        void.class,
        readerType,
        "reader",
        objectType,
        "object",
        int.class,
        "expectedIndex");
    addGeneratedMethod(
        ctx,
        "final",
        methodName,
        slowReadFromFirstExpression(builder, type, properties, record),
        void.class,
        readerType,
        "reader",
        objectType,
        "object",
        int.class,
        "expectedIndex",
        int.class,
        "firstFieldIndex");
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

  private void addGeneratedConstructor(
      CodegenContext ctx, Expression expression, Object... params) {
    ctx.clearExprState();
    Code.ExprCode body = expression.genCode(ctx);
    String code = body.code();
    code = code == null ? "" : ctx.optimizeMethodCode(code);
    ctx.addConstructor(code, params);
  }

  private Expression readerConstructorExpression(
      Class<?> type, JsonFieldInfo[] properties, boolean record) {
    Expression.ListExpression expressions = new Expression.ListExpression();
    Reference propertiesRef = new Reference("properties", TypeRef.of(JsonFieldInfo[].class));
    Reference codecsRef = new Reference("codecs", TypeRef.of(readerArrayType()));
    Reference owner = new Reference("owner", TypeRef.of(ObjectCodec.class));
    expressions.add(
        new Expression.Assign(
            new Reference("this.readTable", TypeRef.of(JsonFieldTable.class)),
            new Expression.Invoke(owner, "readTable", TypeRef.of(JsonFieldTable.class)).inline()));
    if (record) {
      expressions.add(
          new Expression.Assign(new Reference("this.owner", TypeRef.of(ObjectCodec.class)), owner));
    }
    Reference hashes = new Reference("this.fieldHashes", TypeRef.of(long[].class));
    expressions.add(
        new Expression.Assign(
            hashes,
            new Expression.NewArray(
                    long.class,
                    new Expression.FieldValue(
                        propertiesRef, "length", TypeRef.of(int.class), false, true))
                .inline()));
    for (int i = 0; i < properties.length; i++) {
      Expression id = Expression.Literal.ofInt(i);
      Expression property = new Expression.ArrayValue(propertiesRef, id);
      if (usesReadInfo(properties[i])) {
        expressions.add(
            new Expression.Assign(
                new Reference("this.rp" + i, TypeRef.of(JsonFieldInfo.class)), property));
      }
      expressions.add(
          new Expression.AssignArrayElem(
              hashes,
              new Expression.Invoke(property, "nameHash", TypeRef.of(long.class)).inline(),
              id));
      if (JsonCodegen.usesReadCodec(properties[i])) {
        Class<?> codecType = codecFieldType(properties[i]);
        expressions.add(
            new Expression.Assign(
                new Reference("this.r" + i, TypeRef.of(codecType)),
                new Expression.Cast(
                    new Expression.ArrayValue(codecsRef, id), TypeRef.of(codecType))));
      } else if (storesReadObjectCodec(type, properties[i])) {
        expressions.add(
            new Expression.Assign(
                new Reference("this.o" + i, TypeRef.of(readerCapabilityType())),
                new Expression.ArrayValue(codecsRef, id)));
      }
    }
    return expressions;
  }

  private Expression fastReadExpression(
      JsonGeneratedCodecBuilder builder,
      String readMethod,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean record) {
    if (shouldSplitFastRead(properties)) {
      return splitFastReadExpression(builder, readMethod, slowMethod, type, properties, record);
    }
    Expression object = objectExpression(builder, record);
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(new Expression.Invoke(readerRef(), "enterDepth"));
    expressions.add(object);
    expressions.add(expectExpr('{'));
    expressions.add(new Expression.If(consumeExpr('}'), returnObject(object, record)));
    if (properties.length == 0) {
      expressions.add(slowCall(slowMethod, object, Expression.Literal.ofInt(0)));
      expressions.add(returnObject(object, record));
      return expressions;
    }
    Expression hashes = fieldRef("fieldHashes", long[].class);
    if (properties.length > 1) {
      hashes = new Expression.Variable("localFieldHashes", hashes);
      expressions.add(hashes);
    }
    Expression[] skips = new Expression[properties.length];
    for (int i = 1; i < properties.length; i++) {
      skips[i] = new Expression.Variable("skip" + i, Expression.Literal.False);
      expressions.add(skips[i]);
    }
    for (int i = 0; i < properties.length; i++) {
      Expression read =
          fastReadField(builder, slowMethod, type, properties, i, object, hashes, skips, record);
      expressions.add(i == 0 ? read : new Expression.If(not(skips[i]), read));
    }
    expressions.add(returnObject(object, record));
    return expressions;
  }

  private Expression splitFastReadExpression(
      JsonGeneratedCodecBuilder builder,
      String readMethod,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean record) {
    Expression object = objectExpression(builder, record);
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(new Expression.Invoke(readerRef(), "enterDepth"));
    expressions.add(object);
    expressions.add(expectExpr('{'));
    expressions.add(new Expression.If(consumeExpr('}'), returnObject(object, record)));
    Expression hashes =
        new Expression.Variable("localFieldHashes", fieldRef("fieldHashes", long[].class));
    expressions.add(hashes);
    for (int start = 0; start < properties.length; ) {
      int end = readGroupEnd(properties, start);
      Expression groupCall =
          inline(
              new Expression.Invoke(
                  new Reference("this", TypeRef.of(Object.class)),
                  readGroupMethod(readMethod, start),
                  "",
                  TypeRef.of(boolean.class),
                  false,
                  false,
                  readerRef(),
                  object,
                  hashes));
      expressions.add(new Expression.If(not(groupCall), returnObject(object, record)));
      start = end;
    }
    expressions.add(returnObject(object, record));
    return expressions;
  }

  private Expression fastReadGroupExpression(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int start,
      int end,
      boolean record) {
    Expression object = objectParam(type, record);
    Expression hashes = new Reference("fieldHashes", TypeRef.of(long[].class));
    Expression[] skips = new Expression[properties.length];
    Expression.ListExpression expressions = new Expression.ListExpression();
    for (int i = start + 1; i < end; i++) {
      skips[i] = new Expression.Variable("skip" + i, Expression.Literal.False);
      expressions.add(skips[i]);
    }
    for (int i = start; i < end; i++) {
      Expression read =
          fastReadField(
              builder, slowMethod, type, properties, i, end, true, object, hashes, skips, record);
      expressions.add(i == start ? read : new Expression.If(not(skips[i]), read));
    }
    if (end < properties.length) {
      expressions.add(returnTrue());
    } else {
      expressions.add(returnFalse());
    }
    return expressions;
  }

  private Expression fastReadField(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int index,
      Expression object,
      Expression hashes,
      Expression[] skips,
      boolean record) {
    return fastReadField(
        builder,
        slowMethod,
        type,
        properties,
        index,
        properties.length,
        false,
        object,
        hashes,
        skips,
        record);
  }

  private Expression fastReadField(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int index,
      int groupEnd,
      boolean groupHelper,
      Expression object,
      Expression hashes,
      Expression[] skips,
      boolean record) {
    if (isDirectName(properties[index].name(), true)) {
      return statementIf(
          tryReadNextFieldNameColon(properties[index], true),
          new Expression.ListExpression(
              readField(builder, type, properties[index], index, object, record, true),
              fieldEnd(
                  slowMethod, properties.length, groupEnd, groupHelper, index, object, record)),
          nextDirectFallback(
              builder,
              slowMethod,
              type,
              properties,
              index,
              groupEnd,
              groupHelper,
              object,
              hashes,
              skips,
              record),
          groupHelper);
    }
    return nextDirectFallback(
        builder,
        slowMethod,
        type,
        properties,
        index,
        groupEnd,
        groupHelper,
        object,
        hashes,
        skips,
        record);
  }

  private Expression nextDirectFallback(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int index,
      int groupEnd,
      boolean groupHelper,
      Expression object,
      Expression hashes,
      Expression[] skips,
      boolean record) {
    int nextIndex = index + 1;
    if (nextIndex < groupEnd && isDirectName(properties[nextIndex].name(), true)) {
      return statementIf(
          tryReadNextFieldNameColon(properties[nextIndex], true),
          new Expression.ListExpression(
              readField(builder, type, properties[nextIndex], nextIndex, object, record, true),
              new Expression.Assign(skips[nextIndex], Expression.Literal.True),
              fieldEnd(
                  slowMethod, properties.length, groupEnd, groupHelper, nextIndex, object, record)),
          hashFallback(
              builder,
              slowMethod,
              type,
              properties,
              index,
              groupEnd,
              groupHelper,
              object,
              hashes,
              skips,
              record),
          groupHelper);
    }
    return hashFallback(
        builder,
        slowMethod,
        type,
        properties,
        index,
        groupEnd,
        groupHelper,
        object,
        hashes,
        skips,
        record);
  }

  private Expression hashFallback(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int index,
      int groupEnd,
      boolean groupHelper,
      Expression object,
      Expression hashes,
      Expression[] skips,
      boolean record) {
    Expression fieldHash = readFieldNameHash("fieldHash" + index);
    return new Expression.ListExpression(
        fieldHash,
        fastReadFieldFromHash(
            builder,
            slowMethod,
            type,
            properties,
            index,
            groupEnd,
            groupHelper,
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
      int groupEnd,
      boolean groupHelper,
      Expression object,
      Expression hashes,
      Expression[] skips,
      Expression fieldHash,
      boolean record) {
    Expression fallback;
    if (index + 1 < groupEnd) {
      fallback =
          statementIf(
              eq(fieldHash, arrayValue(hashes, index + 1)),
              new Expression.ListExpression(
                  expectExpr(':'),
                  readField(builder, type, properties[index + 1], index + 1, object, record, false),
                  new Expression.Assign(skips[index + 1], Expression.Literal.True),
                  fieldEnd(
                      slowMethod,
                      properties.length,
                      groupEnd,
                      groupHelper,
                      index + 1,
                      object,
                      record)),
              slowConsumedReturn(
                  slowMethod, index, fieldIndexInvoke(fieldHash), object, record, groupHelper),
              groupHelper);
    } else {
      fallback =
          slowConsumedReturn(
              slowMethod, index, fieldIndexInvoke(fieldHash), object, record, groupHelper);
    }
    return statementIf(
        ne(fieldHash, arrayValue(hashes, index)),
        fallback,
        new Expression.ListExpression(
            expectExpr(':'),
            readField(builder, type, properties[index], index, object, record, false),
            fieldEnd(slowMethod, properties.length, groupEnd, groupHelper, index, object, record)),
        groupHelper);
  }

  final boolean isPackedName(String name) {
    int length = name.length();
    if (length == 0 || length > Long.BYTES) {
      return false;
    }
    return isAsciiName(name);
  }

  final boolean isUtf16FieldNameToken(String name) {
    int length = name.length();
    if (length == 0 || length + 3 > 12) {
      return false;
    }
    return isAsciiName(name);
  }

  final boolean isAsciiName(String name) {
    int length = name.length();
    for (int i = 0; i < length; i++) {
      char ch = name.charAt(i);
      if (ch == '"' || ch == '\\' || ch < 0x20 || ch > 0xFF) {
        return false;
      }
    }
    return true;
  }

  final long packedNameMask(int length) {
    return length == Long.BYTES ? -1L : (1L << (length << 3)) - 1L;
  }

  final boolean shouldSplitFastRead(JsonFieldInfo[] properties) {
    return properties.length >= MIN_SPLIT_READ_FIELDS;
  }

  final String readGroupMethod(String readMethod, int start) {
    return readMethod + "Group" + start;
  }

  final int readGroupEnd(JsonFieldInfo[] properties, int start) {
    int end = start + 1;
    while (end < properties.length
        && end - start < READ_FIELD_GROUP_SIZE
        && canPairReadFields(properties[end - 1], properties[end])) {
      end++;
    }
    return end;
  }

  final boolean canPairReadFields(JsonFieldInfo left, JsonFieldInfo right) {
    JsonFieldKind leftKind = left.readKind();
    JsonFieldKind rightKind = right.readKind();
    if (leftKind == null || rightKind == null) {
      return false;
    }
    // Fast-read fallback branches duplicate some field reads in each group. Keep method-size
    // estimation conservative so generated helpers stay close to the C2-friendly target.
    if (leftKind == JsonFieldKind.ENUM
        || rightKind == JsonFieldKind.ENUM
        || leftKind == JsonFieldKind.COLLECTION
        || rightKind == JsonFieldKind.COLLECTION
        || leftKind == JsonFieldKind.MAP
        || rightKind == JsonFieldKind.MAP) {
      return false;
    }
    if (leftKind == JsonFieldKind.ARRAY || rightKind == JsonFieldKind.ARRAY) {
      return false;
    }
    if (leftKind == JsonFieldKind.OBJECT || rightKind == JsonFieldKind.OBJECT) {
      return leftKind == JsonFieldKind.BOOLEAN || rightKind == JsonFieldKind.BOOLEAN;
    }
    return true;
  }

  final boolean shouldSplitFieldSwitch(JsonFieldInfo[] properties) {
    return properties.length > READ_FIELD_SWITCH_SIZE;
  }

  private Expression objectExpression(JsonGeneratedCodecBuilder builder, boolean record) {
    if (record) {
      return new Expression.Variable(
          "object",
          inline(
              new Expression.Invoke(
                  ownerRef(), "newRecordFieldValues", TypeRef.of(Object[].class), false)));
    }
    return builder.newObject();
  }

  private Expression returnObject(Expression object, boolean record) {
    Expression exitDepth = new Expression.Invoke(readerRef(), "exitDepth");
    if (record) {
      return new Expression.ListExpression(
          exitDepth,
          new Expression.Return(
              inline(
                  new Expression.Invoke(
                      ownerRef(), "newRecord", TypeRef.of(Object.class), object))));
    }
    return new Expression.ListExpression(exitDepth, new Expression.Return(object));
  }

  final Expression returnTrue() {
    return new Expression.Return(Expression.Literal.True);
  }

  final Expression returnFalse() {
    return new Expression.Return(Expression.Literal.False);
  }

  final Expression statementIf(
      Expression predicate, Expression trueExpr, Expression falseExpr, boolean statementOnly) {
    if (statementOnly) {
      return new Expression.If(predicate, trueExpr, falseExpr, false, TypeRef.of(void.class));
    }
    return new Expression.If(predicate, trueExpr, falseExpr);
  }

  private Expression slowConsumedReturn(
      String slowMethod, int index, Expression firstFieldIndex, Expression object, boolean record) {
    return new Expression.ListExpression(
        slowCall(slowMethod, object, Expression.Literal.ofInt(index), firstFieldIndex),
        returnObject(object, record));
  }

  private Expression slowConsumedReturn(
      String slowMethod,
      int index,
      Expression firstFieldIndex,
      Expression object,
      boolean record,
      boolean groupHelper) {
    if (!groupHelper) {
      return slowConsumedReturn(slowMethod, index, firstFieldIndex, object, record);
    }
    return new Expression.ListExpression(
        slowCall(slowMethod, object, Expression.Literal.ofInt(index), firstFieldIndex),
        returnFalse());
  }

  private Expression fieldEnd(
      String slowMethod,
      int propertyCount,
      int groupEnd,
      boolean groupHelper,
      int index,
      Expression object,
      boolean record) {
    if (!groupHelper) {
      return fieldEnd(slowMethod, propertyCount, index, object, record);
    }
    return fastReadGroupEnd(slowMethod, propertyCount, index, object);
  }

  private Expression fieldEnd(
      String slowMethod, int propertyCount, int index, Expression object, boolean record) {
    if (index + 1 < propertyCount) {
      return new Expression.If(not(consumeCommaOrEndObjectExpr()), returnObject(object, record));
    }
    return new Expression.If(
        consumeCommaOrEndObjectExpr(),
        slowCall(slowMethod, object, Expression.Literal.ofInt(propertyCount)));
  }

  private Expression fastReadGroupEnd(
      String slowMethod, int propertyCount, int index, Expression object) {
    if (index + 1 < propertyCount) {
      return new Expression.If(not(consumeCommaOrEndObjectExpr()), returnFalse());
    }
    return new Expression.ListExpression(
        new Expression.If(
            consumeCommaOrEndObjectExpr(),
            slowCall(slowMethod, object, Expression.Literal.ofInt(propertyCount))));
  }

  private Expression slowReadExpression(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean record) {
    Expression object = objectParam(type, record);
    Expression hashes =
        new Expression.Variable("localFieldHashes", fieldRef("fieldHashes", long[].class));
    Reference expectedIndex = new Reference("expectedIndex", TypeRef.of(int.class));
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(hashes);
    Expression.ListExpression loop = new Expression.ListExpression();
    loop.add(readNextHashedField(builder, type, properties, object, hashes, expectedIndex, record));
    loop.add(new Expression.If(not(consumeCommaOrEndObjectExpr()), new Expression.Break()));
    expressions.add(new Expression.While(Expression.Literal.True, loop));
    return expressions;
  }

  private Expression slowReadFromFirstExpression(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
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
    loop.add(expectExpr(':'));
    loop.add(fieldSwitch(builder, type, properties, object, fieldIndex, record));
    loop.add(updateExpectedIndex(expectedIndex, fieldIndex));
    loop.add(new Expression.If(not(consumeCommaOrEndObjectExpr()), new Expression.Return()));
    Expression fieldHash = readFieldNameHash("fieldHash");
    loop.add(fieldHash);
    loop.add(new Expression.Assign(fieldIndex, fieldIndexValue(expectedIndex, hashes, fieldHash)));
    expressions.add(new Expression.While(Expression.Literal.True, loop));
    return expressions;
  }

  private Expression readNextHashedField(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      Expression object,
      Expression hashes,
      Expression expectedIndex,
      boolean record) {
    Expression fieldHash = readFieldNameHash("fieldHash");
    Expression fieldIndex =
        new Expression.Variable("fieldIndex", fieldIndexValue(expectedIndex, hashes, fieldHash));
    return new Expression.ListExpression(
        fieldHash,
        fieldIndex,
        expectExpr(':'),
        fieldSwitch(builder, type, properties, object, fieldIndex, record),
        updateExpectedIndex(expectedIndex, fieldIndex));
  }

  private Expression fieldSwitch(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      Expression object,
      Expression fieldIndex,
      boolean record) {
    if (shouldSplitFieldSwitch(properties)) {
      int chunks = (properties.length + READ_FIELD_SWITCH_SIZE - 1) / READ_FIELD_SWITCH_SIZE;
      Expression.Switch.Case[] cases = new Expression.Switch.Case[chunks];
      for (int i = 0, start = 0; i < chunks; i++, start += READ_FIELD_SWITCH_SIZE) {
        cases[i] =
            new Expression.Switch.Case(
                i,
                new Expression.ListExpression(
                    new Expression.Invoke(
                        new Reference("this", TypeRef.of(Object.class)),
                        readFieldMethod(readMethod(), start),
                        "",
                        TypeRef.of(void.class),
                        false,
                        false,
                        readerRef(),
                        object,
                        fieldIndex),
                    new Expression.Break()));
      }
      Expression chunkIndex =
          new Expression.Arithmetic(
              true, "/", fieldIndex, Expression.Literal.ofInt(READ_FIELD_SWITCH_SIZE));
      return new Expression.Switch(
          chunkIndex, cases, new Expression.Invoke(readerRef(), "skipValue"));
    }
    return fieldSwitchRange(
        builder, type, properties, 0, properties.length, object, fieldIndex, record);
  }

  private Expression fieldSwitchRange(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      int start,
      int end,
      Expression object,
      Expression fieldIndex,
      boolean record) {
    Expression.Switch.Case[] cases = new Expression.Switch.Case[end - start];
    for (int i = start; i < end; i++) {
      cases[i - start] =
          new Expression.Switch.Case(
              i,
              new Expression.ListExpression(
                  readField(builder, type, properties[i], i, object, record, false),
                  new Expression.Break()));
    }
    return new Expression.Switch(
        fieldIndex, cases, new Expression.Invoke(readerRef(), "skipValue"));
  }

  private Expression updateExpectedIndex(Expression expectedIndex, Expression fieldIndex) {
    return new Expression.If(
        ge(fieldIndex, Expression.Literal.ofInt(0)),
        new Expression.Assign(expectedIndex, add(fieldIndex, Expression.Literal.ofInt(1))));
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

  final Expression objectParam(Class<?> type, boolean record) {
    return record
        ? new Reference("object", TypeRef.of(Object[].class))
        : new Reference("object", TypeRef.of(type));
  }

  final Reference ownerRef() {
    return fieldRef("owner", ObjectCodec.class);
  }

  final Reference readTableRef() {
    return fieldRef("readTable", JsonFieldTable.class);
  }

  final Reference selfRef() {
    return new Reference("this", TypeRef.of(readerCapabilityType()));
  }

  final Reference fieldRef(String name, Class<?> type) {
    return Reference.fieldRef(name, TypeRef.of(type));
  }

  final Expression expectExpr(char token) {
    return new Expression.Invoke(readerRef(), "expectNextToken", Expression.Literal.ofChar(token));
  }

  final Expression consumeExpr(char token) {
    return new Expression.Invoke(
            readerRef(),
            "consumeNextToken",
            TypeRef.of(boolean.class),
            Expression.Literal.ofChar(token))
        .inline();
  }

  final Expression consumeCommaOrEndObjectExpr() {
    return new Expression.Invoke(
            readerRef(), "consumeNextCommaOrEndObject", TypeRef.of(boolean.class))
        .inline();
  }

  final Expression tryReadNullExpr() {
    return new Expression.Invoke(readerRef(), "tryReadNextNullToken", TypeRef.of(boolean.class))
        .inline();
  }

  final Expression readBooleanExpr() {
    return readBooleanExpr(false);
  }

  final Expression readBooleanExpr(boolean tokenValueRead) {
    return new Expression.Invoke(
            readerRef(), readBooleanMethod(tokenValueRead), TypeRef.of(boolean.class))
        .inline();
  }

  final Expression readIntExpr() {
    return readIntExpr(false);
  }

  final Expression readIntExpr(boolean tokenValueRead) {
    return new Expression.Invoke(readerRef(), readIntMethod(tokenValueRead), TypeRef.of(int.class))
        .inline();
  }

  final Expression readLongExpr() {
    return readLongExpr(false);
  }

  final Expression readLongExpr(boolean tokenValueRead) {
    return new Expression.Invoke(
            readerRef(), readLongMethod(tokenValueRead), TypeRef.of(long.class))
        .inline();
  }

  final Expression readFloatExpr() {
    return new Expression.Invoke(readerRef(), readFloatMethod(), TypeRef.of(float.class)).inline();
  }

  final Expression readDoubleExpr() {
    return new Expression.Invoke(readerRef(), readDoubleMethod(), TypeRef.of(double.class))
        .inline();
  }

  final Expression readStringExpr() {
    return readStringExpr(false);
  }

  final Expression readStringExpr(boolean tokenValueRead) {
    return new Expression.Invoke(
            readerRef(), readStringMethod(tokenValueRead), TypeRef.of(String.class), true)
        .inline();
  }

  final String readBooleanMethod(boolean tokenValueRead) {
    return tokenValueRead ? "readBooleanTokenValue" : "readNextBooleanValue";
  }

  final String readIntMethod(boolean tokenValueRead) {
    return tokenValueRead ? "readIntTokenValue" : "readNextIntValue";
  }

  final String readLongMethod(boolean tokenValueRead) {
    return tokenValueRead ? "readLongTokenValue" : "readNextLongValue";
  }

  // Exact generated field-name tokens stop at ':'. The next-value methods preserve the direct
  // token parser for compact JSON while accepting legal whitespace before the value.
  final String readFloatMethod() {
    return "readNextFloatValue";
  }

  final String readDoubleMethod() {
    return "readNextDoubleValue";
  }

  final String readStringMethod(boolean tokenValueRead) {
    return tokenValueRead ? "readNullableStringToken" : "readNextNullableString";
  }

  final Expression readFieldNameHash(String namePrefix) {
    return new Expression.Invoke(
        readerRef(), "readFieldNameHash", namePrefix, TypeRef.of(long.class), false);
  }

  final Expression fieldIndexInvoke(Expression fieldHash) {
    return new Expression.Invoke(readTableRef(), "index", TypeRef.of(int.class), true, fieldHash)
        .inline();
  }

  final Expression tryReadAsciiFieldNameColon(JsonFieldInfo property) {
    String token = fieldNameToken(property.name());
    int tokenLength = token.length();
    int suffixLength = JsonAsciiToken.suffixLength(tokenLength);
    // Whitespace, escapes, and UTF8 spellings that do not match the raw token fall through without
    // consuming input.
    if (suffixLength == 0) {
      return new Expression.Invoke(
              readerRef(),
              "tryReadNextFieldNameToken0",
              TypeRef.of(boolean.class),
              Expression.Literal.ofLong(JsonAsciiToken.prefix(token)),
              Expression.Literal.ofLong(JsonAsciiToken.prefixMask(tokenLength)),
              Expression.Literal.ofInt(tokenLength))
          .inline();
    }
    if (suffixLength > 3) {
      return new Expression.Invoke(
              readerRef(),
              "tryReadNextFieldNameToken8",
              TypeRef.of(boolean.class),
              Expression.Literal.ofLong(JsonAsciiToken.prefix(token)),
              Expression.Literal.ofLong(JsonAsciiToken.suffixLong(token)),
              Expression.Literal.ofLong(JsonAsciiToken.suffixMask(tokenLength)),
              Expression.Literal.ofInt(tokenLength))
          .inline();
    }
    return new Expression.Invoke(
            readerRef(),
            "tryReadNextFieldNameToken" + suffixLength,
            TypeRef.of(boolean.class),
            Expression.Literal.ofLong(JsonAsciiToken.prefix(token)),
            Expression.Literal.ofLong(JsonAsciiToken.prefixMask(tokenLength)),
            Expression.Literal.ofInt(JsonAsciiToken.suffix(token)),
            Expression.Literal.ofInt(tokenLength))
        .inline();
  }

  final Expression tryReadUtf16FieldNameColon(JsonFieldInfo property, boolean tokenValueRead) {
    String name = property.name();
    int length = name.length();
    if (tokenValueRead) {
      String token = fieldNameToken(name);
      int tokenLength = token.length();
      int tailLength = Math.max(0, tokenLength - 4);
      if (tokenLength <= 8) {
        return new Expression.Invoke(
                readerRef(),
                "tryReadNextFieldNameUtf16Token2",
                TypeRef.of(boolean.class),
                Expression.Literal.ofLong(utf16TokenWord(token, 0, Math.min(tokenLength, 4))),
                Expression.Literal.ofLong(utf16WordMask(Math.min(tokenLength, 4))),
                Expression.Literal.ofLong(
                    tailLength == 0 ? 0 : utf16TokenWord(token, 4, tailLength)),
                Expression.Literal.ofLong(tailLength == 0 ? 0 : utf16WordMask(tailLength)),
                Expression.Literal.ofInt(tokenLength))
            .inline();
      }
      return new Expression.Invoke(
              readerRef(),
              "tryReadNextFieldNameUtf16Token3",
              TypeRef.of(boolean.class),
              Expression.Literal.ofLong(utf16TokenWord(token, 0, 4)),
              Expression.Literal.ofLong(utf16TokenWord(token, 4, 4)),
              Expression.Literal.ofLong(utf16TokenWord(token, 8, tokenLength - 8)),
              Expression.Literal.ofInt(tokenLength))
          .inline();
    }
    int tailLength = Math.max(0, length - 4);
    return new Expression.Invoke(
            readerRef(),
            "tryReadNextFieldNameUtf16",
            TypeRef.of(boolean.class),
            Expression.Literal.ofLong(property.nameHash()),
            Expression.Literal.ofLong(packedNameMask(length)),
            Expression.Literal.ofLong(utf16NameWord(name, 0, Math.min(length, 4))),
            Expression.Literal.ofLong(utf16WordMask(Math.min(length, 4))),
            Expression.Literal.ofLong(tailLength == 0 ? 0 : utf16NameWord(name, 4, tailLength)),
            Expression.Literal.ofLong(tailLength == 0 ? 0 : utf16WordMask(tailLength)),
            Expression.Literal.ofInt(length))
        .inline();
  }

  final Expression tryReadPackedFieldNameColon(JsonFieldInfo property) {
    return new Expression.Invoke(
            readerRef(),
            "tryReadNextFieldNameColon",
            TypeRef.of(boolean.class),
            Expression.Literal.ofLong(property.nameHash()),
            Expression.Literal.ofLong(packedNameMask(property.name().length())),
            Expression.Literal.ofInt(property.name().length()))
        .inline();
  }

  final long utf16NameWord(String name, int start, int length) {
    return utf16TokenWord(name, start, length);
  }

  final long utf16TokenWord(String token, int start, int length) {
    long value = 0;
    for (int i = 0; i < length; i++) {
      value |= (long) (token.charAt(start + i) & 0xFF) << (i << 3);
    }
    long word = spreadLatin1ToUtf16(value);
    return LITTLE_ENDIAN ? word : word << 8;
  }

  final long utf16WordMask(int length) {
    return length == 4 ? -1L : (1L << (length << 4)) - 1;
  }

  final long spreadLatin1ToUtf16(long value) {
    value = (value | (value << 16)) & UTF16_PAIR_MASK;
    return (value | (value << 8)) & UTF16_BYTE_MASK;
  }

  final Expression tryReadNextStringToken(String token) {
    int tokenLength = token.length();
    int suffixLength = JsonAsciiToken.suffixLength(tokenLength);
    if (suffixLength == 0) {
      return new Expression.Invoke(
              readerRef(),
              "tryReadNextStringToken0",
              TypeRef.of(boolean.class),
              Expression.Literal.ofLong(JsonAsciiToken.prefix(token)),
              Expression.Literal.ofLong(JsonAsciiToken.prefixMask(tokenLength)),
              Expression.Literal.ofInt(tokenLength))
          .inline();
    }
    return new Expression.Invoke(
            readerRef(),
            "tryReadNextStringToken" + suffixLength,
            TypeRef.of(boolean.class),
            Expression.Literal.ofLong(JsonAsciiToken.prefix(token)),
            Expression.Literal.ofLong(JsonAsciiToken.prefixMask(tokenLength)),
            Expression.Literal.ofInt(JsonAsciiToken.suffix(token)),
            Expression.Literal.ofInt(tokenLength))
        .inline();
  }

  final String fieldNameToken(String name) {
    return "\"" + name + "\":";
  }

  final Expression slowCall(String slowMethod, Expression object, Expression expectedIndex) {
    return slowCall(slowMethod, object, expectedIndex, null);
  }

  final Expression slowCall(
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
        object,
        expectedIndex,
        firstFieldIndex);
  }

  final Reference readerRefForCall() {
    return new Reference("reader");
  }

  final Expression arrayValue(Expression array, int index) {
    return new Expression.ArrayValue(array, Expression.Literal.ofInt(index));
  }

  final Expression eq(Expression left, Expression right) {
    return new Expression.Comparator("==", left, right, true);
  }

  final Expression ne(Expression left, Expression right) {
    return new Expression.Comparator("!=", left, right, true);
  }

  final Expression lt(Expression left, Expression right) {
    return new Expression.Comparator("<", left, right, true);
  }

  final Expression ge(Expression left, Expression right) {
    return new Expression.Comparator(">=", left, right, true);
  }

  final Expression and(Expression left, Expression right) {
    return new Expression.LogicalAnd(left, right);
  }

  final Expression not(Expression expression) {
    return new Expression.Not(expression);
  }

  final boolean usesReadCodec(JsonFieldInfo property) {
    switch (property.readKind()) {
      case ENUM:
      case ARRAY:
      case COLLECTION:
      case MAP:
        return true;
      case OBJECT:
        return !usesReadObjectCodec(property);
      default:
        return false;
    }
  }

  final boolean usesReadInfo(JsonFieldInfo property) {
    switch (property.readKind()) {
      case BOOLEAN:
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
      case STRING:
      case ENUM:
      case COLLECTION:
      case ARRAY:
      case MAP:
        return false;
      case OBJECT:
        return property.readRawType().isPrimitive();
      case BYTE:
      case SHORT:
      case CHAR:
      default:
        return true;
    }
  }

  final boolean usesReadObjectCodec(JsonFieldInfo property) {
    return property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && property.readTypeInfo().usesDefaultObjectCodec();
  }

  final boolean storesReadObjectCodec(Class<?> type, JsonFieldInfo property) {
    Class<?> nestedType = readNestedType(property);
    return nestedType != null && nestedType != type;
  }

  private Expression readField(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo property,
      int id,
      Expression object,
      boolean record,
      boolean tokenValueRead) {
    if (record) {
      return readRecordField(type, property, id, object, tokenValueRead);
    }
    Class<?> rawType = property.readRawType();
    switch (property.readKind()) {
      case BOOLEAN:
        return readBoolean(builder, property, rawType, object, tokenValueRead);
      case INT:
        return readInt(builder, property, rawType, object, tokenValueRead);
      case LONG:
        return readLong(builder, property, rawType, object, tokenValueRead);
      case FLOAT:
        return readFloat(builder, property, rawType, object);
      case DOUBLE:
        return readDouble(builder, property, rawType, object);
      case STRING:
        return builder.setField(property, object, readStringExpr(tokenValueRead));
      case ENUM:
        return readEnum(builder, property, id, object, tokenValueRead);
      case COLLECTION:
        return readCollection(builder, property, id, object);
      case ARRAY:
      case MAP:
        return readResolvedField(builder, property, id, object);
      case OBJECT:
        return readObject(builder, type, property, id, object);
      default:
        return new Expression.Invoke(
            fieldRef("rp" + id, JsonFieldInfo.class), readFieldMethod(), readerRef(), object);
    }
  }

  private Expression readRecordField(
      Class<?> type, JsonFieldInfo property, int id, Expression object, boolean tokenValueRead) {
    Class<?> rawType = property.readRawType();
    switch (property.readKind()) {
      case BOOLEAN:
        return readRecordBoolean(rawType, id, object, tokenValueRead);
      case INT:
        return readRecordInt(rawType, id, object, tokenValueRead);
      case LONG:
        return readRecordLong(rawType, id, object, tokenValueRead);
      case FLOAT:
        return readRecordFloat(rawType, id, object);
      case DOUBLE:
        return readRecordDouble(rawType, id, object);
      case STRING:
        return assignRecord(object, id, readStringExpr(tokenValueRead));
      case ENUM:
        return readRecordEnum(id, object, tokenValueRead);
      case COLLECTION:
        return readRecordCollection(property, id, object);
      case ARRAY:
      case MAP:
        return assignRecord(object, id, readResolvedValue(property, id));
      case OBJECT:
        return readRecordObject(type, property, id, object);
      default:
        return assignRecord(
            object,
            id,
            new Expression.Invoke(
                fieldRef("rp" + id, JsonFieldInfo.class),
                readFieldValueMethod(),
                TypeRef.of(Object.class),
                true,
                readerRef()));
    }
  }

  final Expression readRecordBoolean(
      Class<?> rawType, int id, Expression object, boolean tokenValueRead) {
    Expression value = box(Boolean.class, readBooleanExpr(tokenValueRead));
    if (rawType.isPrimitive()) {
      return assignRecord(object, id, value);
    }
    return new Expression.If(
        tryReadNullExpr(),
        assignRecord(object, id, new Expression.Null(TypeRef.of(Boolean.class), false)),
        assignRecord(object, id, value));
  }

  final Expression readRecordInt(
      Class<?> rawType, int id, Expression object, boolean tokenValueRead) {
    Expression value = box(Integer.class, readIntExpr(tokenValueRead));
    if (rawType.isPrimitive()) {
      return assignRecord(object, id, value);
    }
    return new Expression.If(
        tryReadNullExpr(),
        assignRecord(object, id, new Expression.Null(TypeRef.of(Integer.class), false)),
        assignRecord(object, id, value));
  }

  final Expression readRecordLong(
      Class<?> rawType, int id, Expression object, boolean tokenValueRead) {
    Expression value = box(Long.class, readLongExpr(tokenValueRead));
    if (rawType.isPrimitive()) {
      return assignRecord(object, id, value);
    }
    return new Expression.If(
        tryReadNullExpr(),
        assignRecord(object, id, new Expression.Null(TypeRef.of(Long.class), false)),
        assignRecord(object, id, value));
  }

  final Expression readRecordFloat(Class<?> rawType, int id, Expression object) {
    Expression value = box(Float.class, readFloatExpr());
    if (rawType.isPrimitive()) {
      return assignRecord(object, id, value);
    }
    return new Expression.If(
        tryReadNullExpr(),
        assignRecord(object, id, new Expression.Null(TypeRef.of(Float.class), false)),
        assignRecord(object, id, value));
  }

  final Expression readRecordDouble(Class<?> rawType, int id, Expression object) {
    Expression value = box(Double.class, readDoubleExpr());
    if (rawType.isPrimitive()) {
      return assignRecord(object, id, value);
    }
    return new Expression.If(
        tryReadNullExpr(),
        assignRecord(object, id, new Expression.Null(TypeRef.of(Double.class), false)),
        assignRecord(object, id, value));
  }

  final Expression readRecordEnum(int id, Expression object, boolean tokenValueRead) {
    return new Expression.If(
        tryReadNullExpr(),
        assignRecord(object, id, new Expression.Null(TypeRef.of(Object.class), false)),
        assignRecord(object, id, readEnumValue(Object.class, id, tokenValueRead)));
  }

  final Expression readRecordObject(
      Class<?> type, JsonFieldInfo property, int id, Expression object) {
    if (property.readRawType() == Object.class
        || !property.readTypeInfo().usesDefaultObjectCodec()) {
      return assignRecord(object, id, readResolvedValue(property, id));
    }
    return assignRecord(object, id, readObjectValue(type, property, id));
  }

  final Expression readRecordCollection(JsonFieldInfo property, int id, Expression object) {
    return assignRecord(object, id, readCollectionValue(property, id));
  }

  final Expression readBoolean(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      Class<?> rawType,
      Expression object,
      boolean tokenValueRead) {
    if (rawType.isPrimitive()) {
      return builder.setField(property, object, readBooleanExpr(tokenValueRead));
    }
    return new Expression.If(
        tryReadNullExpr(),
        builder.setNull(property, object),
        builder.setField(property, object, box(Boolean.class, readBooleanExpr(tokenValueRead))));
  }

  final Expression readInt(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      Class<?> rawType,
      Expression object,
      boolean tokenValueRead) {
    if (rawType.isPrimitive()) {
      return builder.setField(property, object, readIntExpr(tokenValueRead));
    }
    return new Expression.If(
        tryReadNullExpr(),
        builder.setNull(property, object),
        builder.setField(property, object, box(Integer.class, readIntExpr(tokenValueRead))));
  }

  final Expression readLong(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      Class<?> rawType,
      Expression object,
      boolean tokenValueRead) {
    if (rawType.isPrimitive()) {
      return builder.setField(property, object, readLongExpr(tokenValueRead));
    }
    return new Expression.If(
        tryReadNullExpr(),
        builder.setNull(property, object),
        builder.setField(property, object, box(Long.class, readLongExpr(tokenValueRead))));
  }

  final Expression readFloat(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      Class<?> rawType,
      Expression object) {
    if (rawType.isPrimitive()) {
      return builder.setField(property, object, readFloatExpr());
    }
    return new Expression.If(
        tryReadNullExpr(),
        builder.setNull(property, object),
        builder.setField(property, object, box(Float.class, readFloatExpr())));
  }

  final Expression readDouble(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      Class<?> rawType,
      Expression object) {
    if (rawType.isPrimitive()) {
      return builder.setField(property, object, readDoubleExpr());
    }
    return new Expression.If(
        tryReadNullExpr(),
        builder.setNull(property, object),
        builder.setField(property, object, box(Double.class, readDoubleExpr())));
  }

  final Expression readEnum(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      Expression object,
      boolean tokenValueRead) {
    return new Expression.If(
        tryReadNullExpr(),
        builder.setNull(property, object),
        readEnumField(builder, property, id, object, tokenValueRead));
  }

  final Expression readEnumFallback(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      Expression object,
      boolean tokenValueRead) {
    return builder.setField(
        property, object, readEnumValue(property.readRawType(), id, tokenValueRead, true));
  }

  final Expression readAsciiEnumField(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      Expression object,
      boolean tokenValueRead) {
    Expression fallback = readEnumFallback(builder, property, id, object, tokenValueRead);
    if (!tokenValueRead) {
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
              tryReadNextStringToken(token),
              builder.setField(property, object, new Expression.EnumExpression(constant)),
              fallback);
    }
    return fallback;
  }

  final Expression readResolvedField(
      JsonGeneratedCodecBuilder builder, JsonFieldInfo property, int id, Expression object) {
    return builder.setField(property, object, readResolvedValue(property, id));
  }

  final Expression readCollection(
      JsonGeneratedCodecBuilder builder, JsonFieldInfo property, int id, Expression object) {
    return builder.setField(property, object, readCollectionValue(property, id));
  }

  final Expression readObject(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo property,
      int id,
      Expression object) {
    if (property.readRawType() == Object.class
        || !property.readTypeInfo().usesDefaultObjectCodec()) {
      return readResolvedField(builder, property, id, object);
    }
    return builder.setField(property, object, readObjectValue(type, property, id));
  }

  final Expression assignRecord(Expression object, int id, Expression value) {
    return new Expression.AssignArrayElem(object, inline(value), Expression.Literal.ofInt(id));
  }

  final Expression box(Class<?> boxedType, Expression value) {
    return new Expression.StaticInvoke(
        boxedType, "valueOf", "", TypeRef.of(boxedType), false, true, false, value);
  }

  final Expression readEnumValue(Class<?> enumType, int id, boolean tokenValueRead) {
    return readEnumValue(enumType, id, tokenValueRead, false);
  }

  final Expression readEnumValue(
      Class<?> enumType, int id, boolean tokenValueRead, boolean hashFallback) {
    return new Expression.Cast(
        inline(
            new Expression.Invoke(
                fieldRef("r" + id, ScalarCodecs.EnumCodec.class),
                readEnumMethod(tokenValueRead, hashFallback),
                "",
                TypeRef.of(Object.class),
                false,
                false,
                readerRef())),
        TypeRef.of(enumType));
  }

  final Expression readResolvedValue(JsonFieldInfo property, int id) {
    // The selected capability consumes the complete nullable value. Requesting expression
    // null-state here only emits dead boolean locals around codec calls and bloats hot generated
    // reader methods.
    Expression value =
        inline(
            new Expression.Invoke(
                fieldRef("r" + id, readerCapabilityType()),
                readObjectMethod(),
                TypeRef.of(Object.class),
                false,
                readerRef()));
    if (property.readRawType().isPrimitive()) {
      value =
          new Expression.Invoke(
                  fieldRef("rp" + id, JsonFieldInfo.class),
                  "requirePrimitive",
                  TypeRef.of(Object.class),
                  false,
                  value)
              .inline();
    }
    return new Expression.Cast(value, TypeRef.of(property.readRawType()));
  }

  final Expression readCollectionValue(JsonFieldInfo property, int id) {
    return new Expression.Cast(
        inline(
            new Expression.Invoke(
                fieldRef("r" + id, CollectionCodec.class),
                readMethod(),
                TypeRef.of(Object.class),
                false,
                readerRef())),
        TypeRef.of(property.readRawType()));
  }

  final Expression readObjectValue(Class<?> type, JsonFieldInfo property, int id) {
    Expression codec =
        property.readRawType() == type ? selfRef() : fieldRef("o" + id, readerCapabilityType());
    return new Expression.Cast(
        inline(
            new Expression.Invoke(
                codec, readMethod(), TypeRef.of(Object.class), false, readerRef())),
        TypeRef.of(property.readRawType()));
  }

  static final class Latin1Generator extends JsonReaderCodegen {
    Latin1Generator(JsonCodegen codegen) {
      super(codegen);
    }

    @Override
    Class<?> codecFieldType(JsonFieldInfo property) {
      return codegen.latin1ReaderFieldType(property.readTypeInfo());
    }

    @Override
    Class<?> readerType() {
      return Latin1JsonReader.class;
    }

    @Override
    Class<?> readerCapabilityType() {
      return Latin1ReaderCodec.class;
    }

    @Override
    Class<?> readerArrayType() {
      return Latin1ReaderCodec[].class;
    }

    @Override
    String readMethod() {
      return "readLatin1";
    }

    @Override
    String readEnumMethod(boolean tokenValueRead, boolean hashFallback) {
      return tokenValueRead
          ? (hashFallback ? "readLatin1EnumHashToken" : "readLatin1EnumToken")
          : "readNextLatin1Enum";
    }

    @Override
    String readObjectMethod() {
      return "readLatin1";
    }

    @Override
    String readFieldMethod() {
      return "readLatin1";
    }

    @Override
    String readFieldValueMethod() {
      return "readLatin1Value";
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
      return new Reference("reader", TypeRef.of(Latin1JsonReader.class));
    }
  }

  static final class Utf16Generator extends JsonReaderCodegen {
    Utf16Generator(JsonCodegen codegen) {
      super(codegen);
    }

    @Override
    Class<?> codecFieldType(JsonFieldInfo property) {
      return codegen.utf16ReaderFieldType(property.readTypeInfo());
    }

    @Override
    Class<?> readerType() {
      return Utf16JsonReader.class;
    }

    @Override
    Class<?> readerCapabilityType() {
      return Utf16ReaderCodec.class;
    }

    @Override
    Class<?> readerArrayType() {
      return Utf16ReaderCodec[].class;
    }

    @Override
    String readMethod() {
      return "readUtf16";
    }

    @Override
    String readEnumMethod(boolean tokenValueRead, boolean hashFallback) {
      return "readNextUtf16Enum";
    }

    @Override
    String readObjectMethod() {
      return "readUtf16";
    }

    @Override
    String readFieldMethod() {
      return "readUtf16";
    }

    @Override
    String readFieldValueMethod() {
      return "readUtf16Value";
    }

    @Override
    boolean isDirectName(String name, boolean tokenValueRead) {
      return tokenValueRead ? isUtf16FieldNameToken(name) : isPackedName(name);
    }

    @Override
    Expression tryReadNextFieldNameColon(JsonFieldInfo property, boolean tokenValueRead) {
      return tryReadUtf16FieldNameColon(property, tokenValueRead);
    }

    @Override
    Expression readEnumField(
        JsonGeneratedCodecBuilder builder,
        JsonFieldInfo property,
        int id,
        Expression object,
        boolean tokenValueRead) {
      return readEnumFallback(builder, property, id, object, tokenValueRead);
    }

    @Override
    Reference readerRef() {
      return new Reference("reader", TypeRef.of(Utf16JsonReader.class));
    }
  }

  static final class Utf8Generator extends JsonReaderCodegen {
    Utf8Generator(JsonCodegen codegen) {
      super(codegen);
    }

    @Override
    Class<?> codecFieldType(JsonFieldInfo property) {
      return codegen.utf8ReaderFieldType(property.readTypeInfo());
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

    @Override
    String readFieldValueMethod() {
      return "readUtf8Value";
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
}
