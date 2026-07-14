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

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.apache.fory.codegen.Code;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.meta.JsonAsciiToken;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;

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
  private AnyInfo any;
  private Class<?> ownerType;

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
      boolean record,
      JsonCreatorInfo creatorInfo) {
    ownerType = type;
    if (creatorInfo != null) {
      return genCreatorReaderCode(builder, type, creatorInfo);
    }
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

  String genAnyReaderCode(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean record,
      JsonCreatorInfo creatorInfo,
      AnyInfo any) {
    this.any = any;
    ownerType = type;
    if (creatorInfo != null) {
      return genAnyCreatorReaderCode(builder, type, creatorInfo);
    }
    Class<?> concreteReaderType = readerType();
    String readMethod = readMethod();
    String anyReadMethod = readMethod + "Any";
    String slowMethod = readMethod + "Slow";
    CodegenContext ctx = builder.context();
    ctx.addImports(ObjectCodec.class, concreteReaderType, JsonFieldTable.class, Map.class);
    ctx.implementsInterfaces(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()));
    ctx.addField(JsonFieldTable.class, "readTable");
    ctx.addField(ObjectCodec.class, "owner");
    ctx.addField(long[].class, "fieldHashes");
    boolean storesAnyReader = storesAnyReader(type);
    if (storesAnyReader) {
      ctx.addField(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()), "anyReader");
    }
    if (any.readField() != null && Modifier.isFinal(any.readField().getModifiers())) {
      addFinalAnyMapMethod(ctx);
    }
    if (any.readSetter() != null) {
      addAnySetterMethod(ctx, type, any);
    }
    addReaderFields(ctx, type, properties);
    if (storesAnyReader) {
      addGeneratedConstructor(
          ctx,
          readerConstructorExpression(type, properties, record),
          ObjectCodec.class,
          "owner",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
          "codecs",
          JsonCodegen.generatedCodecType(ctx, readerCapabilityType()),
          "anyReader");
    } else {
      addGeneratedConstructor(
          ctx,
          readerConstructorExpression(type, properties, record),
          ObjectCodec.class,
          "owner",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
          "codecs");
    }
    addGeneratedMethod(
        ctx,
        "private final",
        anyReadMethod,
        fastReadExpression(builder, readMethod, slowMethod, type, properties, record),
        Object.class,
        concreteReaderType,
        "reader",
        boolean.class,
        "hasDiscriminator",
        long.class,
        "discriminatorHash");
    ctx.addMethod(
        "@Override public final",
        readMethod,
        "if (reader.tryReadNullToken()) {\n  return null;\n}\n"
            + "return this."
            + anyReadMethod
            + "(reader, false, 0L);",
        Object.class,
        concreteReaderType,
        "reader");
    ctx.addMethod(
        "@Override public final",
        readMethod,
        "if (reader.tryReadNullToken()) {\n  return null;\n}\n"
            + "return this."
            + anyReadMethod
            + "(reader, true, discriminatorHash);",
        Object.class,
        concreteReaderType,
        "reader",
        long.class,
        "discriminatorHash");
    addFastReadGroupMethods(
        ctx, builder, readMethod, slowMethod, concreteReaderType, type, properties, record);
    addReadFieldMethods(ctx, builder, readMethod, concreteReaderType, type, properties, record);
    addSlowReadMethods(ctx, builder, slowMethod, concreteReaderType, type, properties, record);
    return ctx.genCode();
  }

  private void addReaderFields(CodegenContext ctx, Class<?> type, JsonFieldInfo[] properties) {
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
  }

  private void addFinalAnyMapMethod(CodegenContext ctx) {
    ctx.addMethod(
        "private final",
        "requireAnyMap",
        "if (map == null) {\n" + "  throw owner.nullFinalAnyMap();\n" + "}\n" + "return map;",
        Map.class,
        Map.class,
        "map");
  }

  private void addAnySetterMethod(CodegenContext ctx, Class<?> type, AnyInfo any) {
    Method setter = any.readSetter();
    Class<?> valueType = setter.getParameterTypes()[1];
    String methodName = setter.getName();
    Class<?> castType = valueType.isPrimitive() ? TypeUtils.boxedType(valueType) : valueType;
    String value = valueType == Object.class ? "value" : "(" + ctx.type(castType) + ") value";
    if (valueType.isPrimitive()) {
      // Explicit unboxing prevents a boxed overload from winning over the annotated primitive
      // setter during generated Java overload resolution.
      value = "(" + value + ")." + valueType.getName() + "Value()";
    }
    String nullCheck =
        valueType.isPrimitive()
            ? "if (value == null) {\n  throw owner.nullPrimitiveAnyValue();\n}\n"
            : "";
    ctx.addMethod(
        "private final",
        "callAnySetter",
        nullCheck
            + "try {\n"
            + "  object."
            + methodName
            + "(name, "
            + value
            + ");\n"
            + "} catch (Throwable e) {\n"
            + "  if (e instanceof Error) {\n"
            + "    throw (Error) e;\n"
            + "  }\n"
            + "  throw owner.anyAccessorFailure(\""
            + methodName
            + "\", e);\n"
            + "}",
        void.class,
        type,
        "object",
        String.class,
        "name",
        Object.class,
        "value");
  }

  private String genCreatorReaderCode(
      JsonGeneratedCodecBuilder builder, Class<?> type, JsonCreatorInfo creatorInfo) {
    Class<?> concreteReaderType = readerType();
    CodegenContext ctx = builder.context();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    ctx.addImports(ObjectCodec.class, concreteReaderType, JsonCreatorInfo.class);
    ctx.implementsInterfaces(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()));
    ctx.addField(ObjectCodec.class, "owner");
    ctx.addField(JsonCreatorInfo.class, "creator");
    for (int i = 0; i < fields.length; i++) {
      if (!isDirectCreatorPrimitive(fields[i])) {
        ctx.addField(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()), "r" + i);
      }
    }
    addGeneratedConstructor(
        ctx,
        creatorConstructorExpression(fields),
        ObjectCodec.class,
        "owner",
        JsonFieldInfo[].class,
        "properties",
        JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
        "codecs");
    addCreatorMethod(ctx, type, creatorInfo.executable(), fields);
    ctx.clearExprState();
    Code.ExprCode body = creatorReadExpression(type, creatorInfo).genCode(ctx);
    String bodyCode = body.code();
    bodyCode = bodyCode == null ? "" : ctx.optimizeMethodCode(bodyCode);
    ctx.addMethod(
        "@Override public final",
        readMethod(),
        "if (reader.tryReadNullToken()) {\n  return null;\n}\n" + bodyCode,
        Object.class,
        concreteReaderType,
        "reader");
    return ctx.genCode();
  }

  private String genAnyCreatorReaderCode(
      JsonGeneratedCodecBuilder builder, Class<?> type, JsonCreatorInfo creatorInfo) {
    Class<?> concreteReaderType = readerType();
    String readMethod = readMethod();
    String anyReadMethod = readMethod + "Any";
    CodegenContext ctx = builder.context();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    ctx.addImports(
        ObjectCodec.class,
        concreteReaderType,
        JsonCreatorInfo.class,
        JsonFieldTable.class,
        Map.class);
    ctx.implementsInterfaces(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()));
    ctx.addField(ObjectCodec.class, "owner");
    ctx.addField(JsonCreatorInfo.class, "creator");
    ctx.addField(JsonFieldTable.class, "readTable");
    boolean storesAnyReader = storesAnyReader(type);
    if (storesAnyReader) {
      ctx.addField(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()), "anyReader");
    }
    for (int i = 0; i < fields.length; i++) {
      if (!isDirectCreatorPrimitive(fields[i])) {
        ctx.addField(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()), "r" + i);
      }
    }
    if (storesAnyReader) {
      addGeneratedConstructor(
          ctx,
          creatorConstructorExpression(fields),
          ObjectCodec.class,
          "owner",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
          "codecs",
          JsonCodegen.generatedCodecType(ctx, readerCapabilityType()),
          "anyReader");
    } else {
      addGeneratedConstructor(
          ctx,
          creatorConstructorExpression(fields),
          ObjectCodec.class,
          "owner",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
          "codecs");
    }
    addAnyCreatorMethod(ctx, type, creatorInfo.executable());
    addGeneratedMethod(
        ctx,
        "private final",
        anyReadMethod,
        anyCreatorReadExpression(type, creatorInfo),
        Object.class,
        concreteReaderType,
        "reader",
        boolean.class,
        "hasDiscriminator",
        long.class,
        "discriminatorHash");
    ctx.addMethod(
        "@Override public final",
        readMethod,
        "if (reader.tryReadNullToken()) {\n  return null;\n}\n"
            + "return this."
            + anyReadMethod
            + "(reader, false, 0L);",
        Object.class,
        concreteReaderType,
        "reader");
    ctx.addMethod(
        "@Override public final",
        readMethod,
        "if (reader.tryReadNullToken()) {\n  return null;\n}\n"
            + "return this."
            + anyReadMethod
            + "(reader, true, discriminatorHash);",
        Object.class,
        concreteReaderType,
        "reader",
        long.class,
        "discriminatorHash");
    return ctx.genCode();
  }

  private Expression anyCreatorReadExpression(Class<?> type, JsonCreatorInfo creatorInfo) {
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    Class<?>[] parameterTypes = creatorInfo.executable().getParameterTypes();
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(new Expression.Invoke(readerRef(), "enterDepth"));
    Expression[] arguments = new Expression[parameterTypes.length];
    for (int i = 0; i < parameterTypes.length; i++) {
      arguments[i] = new Expression.Variable("a" + i, creatorDefault(parameterTypes[i]));
      expressions.add(arguments[i]);
    }
    Expression anyMap =
        new Expression.Variable("anyMap", new Expression.Null(TypeRef.of(Map.class), false));
    expressions.add(anyMap);
    expressions.add(expectExpr('{'));
    expressions.add(
        new Expression.If(
            consumeExpr('}'),
            new Expression.ListExpression(
                new Expression.Invoke(readerRef(), "exitDepth"),
                new Expression.Return(createValue(type, arguments)))));

    Expression.ListExpression loop = new Expression.ListExpression();
    Expression fieldStart =
        new Expression.Variable(
            "fieldStart",
            new Expression.Invoke(readerRef(), "position", TypeRef.of(int.class)).inline());
    Expression hash = readFieldNameHash("creatorFieldHash");
    Expression creatorIndex =
        new Expression.Variable(
            "creatorFieldIndex",
            new Expression.Invoke(
                    fieldRef("creator", JsonCreatorInfo.class),
                    "index",
                    TypeRef.of(int.class),
                    true,
                    hash)
                .inline());
    loop.add(fieldStart);
    loop.add(hash);
    loop.add(creatorIndex);
    loop.add(expectExpr(':'));
    Expression.Switch.Case[] cases = new Expression.Switch.Case[fields.length];
    for (int i = 0; i < fields.length; i++) {
      cases[i] =
          new Expression.Switch.Case(
              i,
              new Expression.ListExpression(
                  new Expression.Assign(
                      arguments[fields[i].argumentIndex()], readCreatorValue(fields[i], i)),
                  new Expression.Break()));
    }
    loop.add(
        new Expression.If(
            ge(creatorIndex, Expression.Literal.ofInt(0)),
            new Expression.Switch(
                creatorIndex, cases, new Expression.Invoke(readerRef(), "skipValue")),
            readUnknownCreator(fieldStart, hash, anyMap)));
    loop.add(
        new Expression.If(
            not(consumeExpr(',')),
            new Expression.ListExpression(expectExpr('}'), new Expression.Break())));
    expressions.add(new Expression.While(Expression.Literal.True, loop));
    Expression finished =
        new Expression.Variable(
            "finishedAnyMap",
            new Expression.Cast(
                new Expression.Invoke(ownerRef(), "finishAnyMap", TypeRef.of(Map.class), anyMap),
                TypeRef.of(parameterTypes[any.constructionIndex()])));
    expressions.add(
        new Expression.If(
            ne(anyMap, new Expression.Null(TypeRef.of(Map.class), false)),
            new Expression.ListExpression(
                finished, new Expression.Assign(arguments[any.constructionIndex()], finished))));
    expressions.add(new Expression.Invoke(readerRef(), "exitDepth"));
    expressions.add(new Expression.Return(createValue(type, arguments)));
    return expressions;
  }

  private Expression readUnknownCreator(Expression fieldStart, Expression hash, Expression anyMap) {
    Expression match =
        new Expression.Variable(
            "fieldMatch",
            new Expression.Invoke(readTableRef(), "match", TypeRef.of(int.class), true, hash)
                .inline());
    Expression skip = new Expression.Invoke(readerRef(), "skipValue");
    Expression reserved =
        new Expression.LogicalOr(
            eq(match, Expression.Literal.ofInt(JsonFieldTable.SKIP)),
            new Expression.LogicalAnd(hasDiscriminatorRef(), eq(hash, discriminatorHashRef())));
    Expression name =
        new Expression.Variable(
            "anyName",
            new Expression.Invoke(
                    readerRef(), "materializeFieldName", TypeRef.of(String.class), fieldStart)
                .inline());
    Expression value =
        new Expression.Variable(
            "anyValue",
            new Expression.Invoke(
                anyReaderRef(), readMethod(), TypeRef.of(Object.class), readerRef()));
    Expression create =
        new Expression.Assign(
            anyMap,
            new Expression.Invoke(ownerRef(), "newAnyMap", TypeRef.of(Map.class), false).inline());
    Expression read =
        new Expression.ListExpression(
            name,
            value,
            new Expression.If(
                eq(anyMap, new Expression.Null(TypeRef.of(Map.class), false)), create),
            new Expression.Invoke(ownerRef(), "putAnyMap", anyMap, name, value));
    return new Expression.ListExpression(
        match,
        new Expression.If(
            reserved,
            skip,
            new Expression.If(
                eq(match, Expression.Literal.ofInt(JsonFieldTable.UNKNOWN)), read, skip)));
  }

  private void addAnyCreatorMethod(CodegenContext ctx, Class<?> type, Executable executable) {
    Class<?>[] parameterTypes = executable.getParameterTypes();
    Object[] parameters = new Object[parameterTypes.length << 1];
    StringBuilder invocation = new StringBuilder();
    for (int i = 0; i < parameterTypes.length; i++) {
      parameters[i << 1] = parameterTypes[i];
      parameters[(i << 1) + 1] = "a" + i;
      if (i != 0) {
        invocation.append(", ");
      }
      invocation.append('a').append(i);
    }
    String typeName = ctx.type(type);
    String expression =
        executable instanceof Constructor
            ? "new " + typeName + "(" + invocation + ")"
            : typeName + "." + ((Method) executable).getName() + "(" + invocation + ")";
    StringBuilder body = new StringBuilder();
    body.append(typeName)
        .append(" value;\ntry {\n  value = ")
        .append(expression)
        .append(";\n")
        .append("} catch (Throwable e) {\n  throw owner.creatorFailure(e);\n}\n");
    if (executable instanceof Method) {
      body.append("return (").append(typeName).append(") owner.requireCreatorResult(value);");
    } else {
      body.append("return value;");
    }
    ctx.addMethod("private final", "createValue", body.toString(), type, parameters);
  }

  private Expression creatorConstructorExpression(JsonCreatorFieldInfo[] fields) {
    Expression.ListExpression expressions = new Expression.ListExpression();
    Reference owner = new Reference("owner", TypeRef.of(ObjectCodec.class));
    expressions.add(
        new Expression.Assign(new Reference("this.owner", TypeRef.of(ObjectCodec.class)), owner));
    expressions.add(
        new Expression.Assign(
            new Reference("this.creator", TypeRef.of(JsonCreatorInfo.class)),
            new Expression.Invoke(owner, "creatorInfo", TypeRef.of(JsonCreatorInfo.class))
                .inline()));
    if (any != null) {
      expressions.add(
          new Expression.Assign(
              new Reference("this.readTable", TypeRef.of(JsonFieldTable.class)),
              new Expression.Invoke(owner, "readTable", TypeRef.of(JsonFieldTable.class))
                  .inline()));
      if (storesAnyReader(ownerType)) {
        expressions.add(
            new Expression.Assign(
                new Reference("this.anyReader", TypeRef.of(readerCapabilityType())),
                new Reference("anyReader", TypeRef.of(readerCapabilityType()))));
      }
    }
    Reference codecs = new Reference("codecs", TypeRef.of(readerArrayType()));
    for (int i = 0; i < fields.length; i++) {
      if (!isDirectCreatorPrimitive(fields[i])) {
        expressions.add(
            new Expression.Assign(
                new Reference("this.r" + i, TypeRef.of(readerCapabilityType())),
                new Expression.ArrayValue(codecs, Expression.Literal.ofInt(i))));
      }
    }
    return expressions;
  }

  private Expression creatorReadExpression(Class<?> type, JsonCreatorInfo creatorInfo) {
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(new Expression.Invoke(readerRef(), "enterDepth"));
    Expression[] arguments = new Expression[fields.length];
    for (int i = 0; i < fields.length; i++) {
      arguments[i] = new Expression.Variable("a" + i, creatorDefault(fields[i].rawType()));
      expressions.add(arguments[i]);
    }
    expressions.add(expectExpr('{'));
    expressions.add(
        new Expression.If(
            consumeExpr('}'),
            new Expression.ListExpression(
                new Expression.Invoke(readerRef(), "exitDepth"),
                new Expression.Return(createValue(type, arguments)))));

    Expression.ListExpression loop = new Expression.ListExpression();
    Expression hash = readFieldNameHash("creatorFieldHash");
    Expression fieldIndex =
        new Expression.Variable(
            "creatorFieldIndex",
            new Expression.Invoke(
                    fieldRef("creator", JsonCreatorInfo.class),
                    "index",
                    TypeRef.of(int.class),
                    true,
                    hash)
                .inline());
    loop.add(hash);
    loop.add(fieldIndex);
    loop.add(expectExpr(':'));
    Expression.Switch.Case[] cases = new Expression.Switch.Case[fields.length];
    for (int i = 0; i < fields.length; i++) {
      cases[i] =
          new Expression.Switch.Case(
              i,
              new Expression.ListExpression(
                  new Expression.Assign(arguments[i], readCreatorValue(fields[i], i)),
                  new Expression.Break()));
    }
    loop.add(
        new Expression.Switch(fieldIndex, cases, new Expression.Invoke(readerRef(), "skipValue")));
    loop.add(
        new Expression.If(
            not(consumeExpr(',')),
            new Expression.ListExpression(expectExpr('}'), new Expression.Break())));
    expressions.add(new Expression.While(Expression.Literal.True, loop));
    expressions.add(new Expression.Invoke(readerRef(), "exitDepth"));
    expressions.add(new Expression.Return(createValue(type, arguments)));
    return expressions;
  }

  private void addCreatorMethod(
      CodegenContext ctx, Class<?> type, Executable executable, JsonCreatorFieldInfo[] fields) {
    Object[] parameters = new Object[fields.length << 1];
    StringBuilder invocation = new StringBuilder();
    for (int i = 0; i < fields.length; i++) {
      parameters[i << 1] = fields[i].rawType();
      parameters[(i << 1) + 1] = "a" + i;
      if (i != 0) {
        invocation.append(", ");
      }
      invocation.append('a').append(i);
    }
    String typeName = ctx.type(type);
    String expression =
        executable instanceof Constructor
            ? "new " + typeName + "(" + invocation + ")"
            : typeName + "." + ((Method) executable).getName() + "(" + invocation + ")";
    StringBuilder body = new StringBuilder();
    body.append(typeName)
        .append(" value;\ntry {\n  value = ")
        .append(expression)
        .append(";\n")
        .append("} catch (Throwable e) {\n  throw owner.creatorFailure(e);\n}\n");
    if (executable instanceof Method) {
      body.append("return (").append(typeName).append(") owner.requireCreatorResult(value);");
    } else {
      body.append("return value;");
    }
    ctx.addMethod("private final", "createValue", body.toString(), type, parameters);
  }

  private Expression createValue(Class<?> type, Expression[] arguments) {
    return new Expression.Invoke(
        new Reference("this", TypeRef.of(Object.class)),
        "createValue",
        "",
        TypeRef.of(type),
        false,
        false,
        arguments);
  }

  private Expression creatorDefault(Class<?> type) {
    if (!type.isPrimitive()) {
      return new Expression.Null(TypeRef.of(type), false);
    }
    if (type == boolean.class) {
      return Expression.Literal.False;
    }
    if (type == byte.class) {
      return Expression.Literal.ofByte((short) 0);
    }
    if (type == short.class) {
      return Expression.Literal.ofShort((short) 0);
    }
    if (type == int.class) {
      return Expression.Literal.ofInt(0);
    }
    if (type == long.class) {
      return Expression.Literal.ofLong(0L);
    }
    if (type == char.class) {
      return Expression.Literal.ofChar((char) 0);
    }
    return new Expression.Literal(type == float.class ? 0F : 0D, TypeRef.of(type));
  }

  private Expression readCreatorValue(JsonCreatorFieldInfo field, int id) {
    Class<?> type = field.rawType();
    if (!isDirectCreatorPrimitive(field)) {
      Expression value =
          new Expression.Invoke(
              fieldRef("r" + id, readerCapabilityType()),
              readMethod(),
              TypeRef.of(Object.class),
              readerRef());
      if (type.isPrimitive()) {
        value =
            new Expression.StaticInvoke(
                JsonCreatorFieldInfo.class,
                "requirePrimitive",
                TypeRef.of(Object.class),
                value,
                Expression.Literal.ofClass(type));
      }
      return new Expression.Cast(value, TypeRef.of(type));
    }
    if (type == boolean.class) {
      return readBooleanExpr();
    }
    if (type == byte.class) {
      return new Expression.StaticInvoke(
          JsonCreatorFieldInfo.class, "checkedByte", TypeRef.of(byte.class), readIntExpr());
    }
    if (type == short.class) {
      return new Expression.StaticInvoke(
          JsonCreatorFieldInfo.class, "checkedShort", TypeRef.of(short.class), readIntExpr());
    }
    if (type == int.class) {
      return readIntExpr();
    }
    if (type == long.class) {
      return readLongExpr();
    }
    if (type == float.class) {
      return readFloatExpr();
    }
    if (type == double.class) {
      return readDoubleExpr();
    }
    if (type == char.class) {
      return new Expression.Invoke(readerRef(), "readChar", TypeRef.of(char.class)).inline();
    }
    throw new IllegalStateException("Unsupported primitive creator type " + type);
  }

  private static boolean isDirectCreatorPrimitive(JsonCreatorFieldInfo field) {
    Class<?> type = field.rawType();
    if (!type.isPrimitive()) {
      return false;
    }
    JsonFieldKind kind = field.typeInfo().kind();
    return type == boolean.class && kind == JsonFieldKind.BOOLEAN
        || (type == byte.class && kind == JsonFieldKind.BYTE)
        || (type == short.class && kind == JsonFieldKind.SHORT)
        || (type == int.class && kind == JsonFieldKind.INT)
        || (type == long.class && kind == JsonFieldKind.LONG)
        || (type == float.class && kind == JsonFieldKind.FLOAT)
        || (type == double.class && kind == JsonFieldKind.DOUBLE)
        || (type == char.class && kind == JsonFieldKind.CHAR);
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
      if (any == null) {
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
      } else {
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
            "fieldHashes",
            Map.class,
            "anyMap",
            boolean.class,
            "hasDiscriminator",
            long.class,
            "discriminatorHash");
      }
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
    // An Any slow invocation consumes the entire remaining object and finishes its map before
    // returning. This makes the Map parameter its local accumulator without a holder or return
    // carrier; callers must not resume member consumption after the slow call.
    if (any == null) {
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
      return;
    }
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
        "expectedIndex",
        Map.class,
        "anyMap",
        boolean.class,
        "hasDiscriminator",
        long.class,
        "discriminatorHash");
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
        "firstFieldIndex",
        long.class,
        "firstFieldHash",
        int.class,
        "firstFieldStart",
        Map.class,
        "anyMap",
        boolean.class,
        "hasDiscriminator",
        long.class,
        "discriminatorHash");
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
    if (record || any != null) {
      expressions.add(
          new Expression.Assign(new Reference("this.owner", TypeRef.of(ObjectCodec.class)), owner));
    }
    if (any != null) {
      if (storesAnyReader(ownerType)) {
        expressions.add(
            new Expression.Assign(
                new Reference("this.anyReader", TypeRef.of(readerCapabilityType())),
                new Reference("anyReader", TypeRef.of(readerCapabilityType()))));
      }
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
    Expression anyMap = anyMap(builder, object, record);
    if (anyMap != null) {
      expressions.add(anyMap);
    }
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
    Expression anyMap = anyMap(builder, object, record);
    if (anyMap != null) {
      expressions.add(anyMap);
    }
    expressions.add(expectExpr('{'));
    expressions.add(new Expression.If(consumeExpr('}'), returnObject(object, record)));
    Expression hashes =
        new Expression.Variable("localFieldHashes", fieldRef("fieldHashes", long[].class));
    expressions.add(hashes);
    for (int start = 0; start < properties.length; ) {
      int end = readGroupEnd(properties, start);
      Expression groupCall = inline(readGroupCall(readMethod, start, object, hashes));
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

  private Expression readGroupCall(
      String readMethod, int start, Expression object, Expression hashes) {
    if (any == null) {
      return new Expression.Invoke(
          new Reference("this", TypeRef.of(Object.class)),
          readGroupMethod(readMethod, start),
          "",
          TypeRef.of(boolean.class),
          false,
          false,
          readerRef(),
          object,
          hashes);
    }
    return new Expression.Invoke(
        new Reference("this", TypeRef.of(Object.class)),
        readGroupMethod(readMethod, start),
        "",
        TypeRef.of(boolean.class),
        false,
        false,
        readerRef(),
        object,
        hashes,
        anyMapRef(),
        hasDiscriminatorRef(),
        discriminatorHashRef());
  }

  private Expression anyMap(JsonGeneratedCodecBuilder builder, Expression object, boolean record) {
    if (any == null || any.readField() == null) {
      return null;
    }
    Expression initial =
        record
            ? new Expression.Null(TypeRef.of(Map.class), false)
            : new Expression.Cast(
                inline(builder.anyValue(any.readField(), object)), TypeRef.of(Map.class));
    return new Expression.Variable("anyMap", initial);
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
    Expression fieldStart =
        any == null
            ? null
            : new Expression.Variable(
                "fieldStart" + index,
                new Expression.Invoke(readerRef(), "position", TypeRef.of(int.class)).inline());
    Expression fieldHash = readFieldNameHash("fieldHash" + index);
    Expression.ListExpression expressions = new Expression.ListExpression();
    if (fieldStart != null) {
      expressions.add(fieldStart);
    }
    expressions.add(fieldHash);
    expressions.add(
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
            fieldStart,
            record));
    return expressions;
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
      Expression fieldStart,
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
                  slowMethod,
                  index,
                  fieldIndexInvoke(fieldHash),
                  fieldHash,
                  fieldStart,
                  object,
                  record,
                  groupHelper),
              groupHelper);
    } else {
      fallback =
          slowConsumedReturn(
              slowMethod,
              index,
              fieldIndexInvoke(fieldHash),
              fieldHash,
              fieldStart,
              object,
              record,
              groupHelper);
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

  private Expression slowConsumedReturn(
      String slowMethod,
      int index,
      Expression firstFieldIndex,
      Expression firstFieldHash,
      Expression firstFieldStart,
      Expression object,
      boolean record,
      boolean groupHelper) {
    if (any == null) {
      return slowConsumedReturn(slowMethod, index, firstFieldIndex, object, record, groupHelper);
    }
    if (!groupHelper) {
      return new Expression.ListExpression(
          slowCall(
              slowMethod,
              object,
              Expression.Literal.ofInt(index),
              firstFieldIndex,
              firstFieldHash,
              firstFieldStart),
          returnObject(object, record));
    }
    return new Expression.ListExpression(
        slowCall(
            slowMethod,
            object,
            Expression.Literal.ofInt(index),
            firstFieldIndex,
            firstFieldHash,
            firstFieldStart),
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
    Expression anyMapCreated = anyMapCreatedFlag(expressions);
    Expression.ListExpression loop = new Expression.ListExpression();
    loop.add(
        readNextHashedField(
            builder, type, properties, object, hashes, expectedIndex, anyMapCreated, record));
    loop.add(new Expression.If(not(consumeCommaOrEndObjectExpr()), new Expression.Break()));
    expressions.add(new Expression.While(Expression.Literal.True, loop));
    finishAnyRead(builder, expressions, object, anyMapCreated, record);
    return expressions;
  }

  private Expression anyMapCreatedFlag(Expression.ListExpression expressions) {
    if (any == null || any.readField() == null) {
      return null;
    }
    Expression flag = new Expression.Variable("anyMapCreated", Expression.Literal.False);
    expressions.add(flag);
    return flag;
  }

  private void finishAnyRead(
      JsonGeneratedCodecBuilder builder,
      Expression.ListExpression expressions,
      Expression object,
      Expression anyMapCreated,
      boolean record) {
    Expression finish = finishAnyReadExpression(builder, object, anyMapCreated, record);
    if (finish != null) {
      expressions.add(finish);
    }
  }

  private Expression finishAnyReadExpression(
      JsonGeneratedCodecBuilder builder,
      Expression object,
      Expression anyMapCreated,
      boolean record) {
    if (any == null || any.readField() == null) {
      return null;
    }
    Reference map = new Reference("anyMap", TypeRef.of(Map.class));
    Expression finished =
        new Expression.Variable(
            "finishedAnyMap",
            new Expression.Cast(
                new Expression.Invoke(ownerRef(), "finishAnyMap", TypeRef.of(Map.class), map),
                TypeRef.of(Map.class)));
    Expression store;
    if (record) {
      store =
          new Expression.AssignArrayElem(
              object, finished, Expression.Literal.ofInt(any.constructionIndex()));
    } else if (Modifier.isFinal(any.readField().getModifiers())) {
      store = new Expression.Empty();
    } else {
      store = builder.setAnyField(any.readField(), object, finished);
    }
    return new Expression.If(anyMapCreated, new Expression.ListExpression(finished, store));
  }

  private Expression finishAnyReadAndReturn(
      JsonGeneratedCodecBuilder builder,
      Expression object,
      Expression anyMapCreated,
      boolean record) {
    Expression finish = finishAnyReadExpression(builder, object, anyMapCreated, record);
    return finish == null
        ? new Expression.Return()
        : new Expression.ListExpression(finish, new Expression.Return());
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
    Expression anyMapCreated = anyMapCreatedFlag(expressions);
    Expression.ListExpression loop = new Expression.ListExpression();
    loop.add(expectExpr(':'));
    loop.add(
        fieldSwitch(
            builder,
            type,
            properties,
            object,
            fieldIndex,
            new Reference("firstFieldHash", TypeRef.of(long.class)),
            any == null ? null : new Reference("firstFieldStart", TypeRef.of(int.class)),
            anyMapCreated,
            record));
    loop.add(updateExpectedIndex(expectedIndex, fieldIndex));
    loop.add(
        new Expression.If(
            not(consumeCommaOrEndObjectExpr()),
            any == null
                ? new Expression.Return()
                : finishAnyReadAndReturn(builder, object, anyMapCreated, record)));
    Expression fieldStart =
        any == null
            ? null
            : new Expression.Variable(
                "fieldStart",
                new Expression.Invoke(readerRef(), "position", TypeRef.of(int.class)).inline());
    if (fieldStart != null) {
      loop.add(fieldStart);
    }
    Expression fieldHash = readFieldNameHash("fieldHash");
    loop.add(fieldHash);
    loop.add(new Expression.Assign(fieldIndex, fieldIndexValue(expectedIndex, hashes, fieldHash)));
    if (any != null) {
      loop.add(
          new Expression.Assign(
              new Reference("firstFieldHash", TypeRef.of(long.class)), fieldHash));
      loop.add(
          new Expression.Assign(
              new Reference("firstFieldStart", TypeRef.of(int.class)), fieldStart));
    }
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
      Expression anyMapCreated,
      boolean record) {
    Expression fieldStart =
        any == null
            ? null
            : new Expression.Variable(
                "fieldStart",
                new Expression.Invoke(readerRef(), "position", TypeRef.of(int.class)).inline());
    Expression fieldHash = readFieldNameHash("fieldHash");
    Expression fieldIndex =
        new Expression.Variable("fieldIndex", fieldIndexValue(expectedIndex, hashes, fieldHash));
    Expression.ListExpression expressions = new Expression.ListExpression();
    if (fieldStart != null) {
      expressions.add(fieldStart);
    }
    expressions.add(fieldHash);
    expressions.add(
        fieldIndex,
        expectExpr(':'),
        fieldSwitch(
            builder,
            type,
            properties,
            object,
            fieldIndex,
            fieldHash,
            fieldStart,
            anyMapCreated,
            record),
        updateExpectedIndex(expectedIndex, fieldIndex));
    return expressions;
  }

  private Expression fieldSwitch(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      Expression object,
      Expression fieldIndex,
      boolean record) {
    return fieldSwitch(builder, type, properties, object, fieldIndex, null, null, null, record);
  }

  private Expression fieldSwitch(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      Expression object,
      Expression fieldIndex,
      Expression fieldHash,
      Expression fieldStart,
      Expression anyMapCreated,
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
      Expression known =
          new Expression.Switch(chunkIndex, cases, new Expression.Invoke(readerRef(), "skipValue"));
      return any == null
          ? known
          : new Expression.If(
              ge(fieldIndex, Expression.Literal.ofInt(0)),
              known,
              readUnknown(object, fieldIndex, fieldHash, fieldStart, anyMapCreated, record));
    }
    return fieldSwitchRange(
        builder,
        type,
        properties,
        0,
        properties.length,
        object,
        fieldIndex,
        fieldHash,
        fieldStart,
        anyMapCreated,
        record);
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
    return fieldSwitchRange(
        builder, type, properties, start, end, object, fieldIndex, null, null, null, record);
  }

  private Expression fieldSwitchRange(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      int start,
      int end,
      Expression object,
      Expression fieldIndex,
      Expression fieldHash,
      Expression fieldStart,
      Expression anyMapCreated,
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
        fieldIndex,
        cases,
        any == null
            ? new Expression.Invoke(readerRef(), "skipValue")
            : readUnknown(object, fieldIndex, fieldHash, fieldStart, anyMapCreated, record));
  }

  private Expression readUnknown(
      Expression object,
      Expression fieldIndex,
      Expression fieldHash,
      Expression fieldStart,
      Expression anyMapCreated,
      boolean record) {
    Expression skip = new Expression.Invoke(readerRef(), "skipValue");
    Expression reserved =
        new Expression.LogicalOr(
            eq(fieldIndex, Expression.Literal.ofInt(JsonFieldTable.SKIP)),
            new Expression.LogicalAnd(
                hasDiscriminatorRef(), eq(fieldHash, discriminatorHashRef())));
    Expression name =
        new Expression.Variable(
            "anyName",
            new Expression.Invoke(
                    readerRef(), "materializeFieldName", TypeRef.of(String.class), fieldStart)
                .inline());
    Expression value =
        new Expression.Variable(
            "anyValue",
            new Expression.Invoke(
                anyReaderRef(), readMethod(), TypeRef.of(Object.class), readerRef()));
    Expression write;
    if (any.readSetter() != null) {
      write =
          new Expression.Invoke(
              new Reference("this", TypeRef.of(Object.class)),
              "callAnySetter",
              "",
              TypeRef.of(void.class),
              false,
              false,
              object,
              name,
              value);
    } else {
      Reference map = new Reference("anyMap", TypeRef.of(Map.class));
      Expression create =
          record
              ? new Expression.ListExpression(
                  new Expression.Assign(
                      map,
                      new Expression.Invoke(ownerRef(), "newAnyMap", TypeRef.of(Map.class), false)
                          .inline()),
                  new Expression.Assign(anyMapCreated, Expression.Literal.True))
              : Modifier.isFinal(any.readField().getModifiers())
                  ? new Expression.Invoke(
                      new Reference("this", TypeRef.of(Object.class)),
                      "requireAnyMap",
                      "",
                      TypeRef.of(Map.class),
                      false,
                      false,
                      map)
                  : new Expression.ListExpression(
                      new Expression.Assign(
                          map,
                          new Expression.Invoke(
                                  ownerRef(), "newAnyMap", TypeRef.of(Map.class), false)
                              .inline()),
                      new Expression.Assign(anyMapCreated, Expression.Literal.True));
      Expression put = new Expression.Invoke(ownerRef(), "putAnyMap", map, name, value);
      write =
          new Expression.ListExpression(
              new Expression.If(eq(map, new Expression.Null(TypeRef.of(Map.class), false)), create),
              put);
    }
    Expression read = new Expression.ListExpression(name, value, write);
    return new Expression.If(
        reserved,
        skip,
        new Expression.If(
            eq(fieldIndex, Expression.Literal.ofInt(JsonFieldTable.UNKNOWN)), read, skip));
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

  private boolean storesAnyReader(Class<?> type) {
    return !any.valueTypeInfo().usesDefaultObjectCodec() || any.valueTypeInfo().rawType() != type;
  }

  private Expression anyReaderRef() {
    return storesAnyReader(ownerType) ? fieldRef("anyReader", readerCapabilityType()) : selfRef();
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
    return new Expression.Invoke(
            readTableRef(), any == null ? "index" : "match", TypeRef.of(int.class), true, fieldHash)
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
    return slowCall(slowMethod, object, expectedIndex, firstFieldIndex, null, null);
  }

  final Expression slowCall(
      String slowMethod,
      Expression object,
      Expression expectedIndex,
      Expression firstFieldIndex,
      Expression firstFieldHash,
      Expression firstFieldStart) {
    if (any != null) {
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
            expectedIndex,
            anyMapRef(),
            hasDiscriminatorRef(),
            discriminatorHashRef());
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
          firstFieldIndex,
          firstFieldHash,
          firstFieldStart,
          anyMapRef(),
          hasDiscriminatorRef(),
          discriminatorHashRef());
    }
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

  private Expression anyMapRef() {
    return any.readField() == null
        ? new Expression.Null(TypeRef.of(Map.class), false)
        : new Reference("anyMap", TypeRef.of(Map.class));
  }

  private Reference hasDiscriminatorRef() {
    return new Reference("hasDiscriminator", TypeRef.of(boolean.class));
  }

  private Reference discriminatorHashRef() {
    return new Reference("discriminatorHash", TypeRef.of(long.class));
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
