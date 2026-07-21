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
import org.apache.fory.json.codec.JsonUnwrappedInfo;
import org.apache.fory.json.codec.JsonUnwrappedInfo.Group;
import org.apache.fory.json.codec.JsonUnwrappedInfo.ReadRoute;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.meta.JsonAsciiToken;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;

/**
 * Shared generation mechanics for concrete Latin1, UTF16, and UTF-8 object-reader capabilities.
 *
 * <p>The three concrete generators own representation-specific token, field-name, enum, and direct
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
  private boolean storesSelfReader;

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
    // Generated mutable readers retain immutable lookup metadata directly. Creator-backed readers
    // are selected above and consume the exact executable owned by JsonCreatorInfo.
    ctx.addField(JsonFieldTable.class, "readTable");
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
        readerConstructorExpression(type, properties),
        ObjectCodec.class,
        "owner",
        JsonFieldInfo[].class,
        "properties",
        JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
        "codecs");
    ctx.clearExprState();
    Code.ExprCode body =
        fastReadExpression(builder, readMethod, slowMethod, type, properties).genCode(ctx);
    String bodyCode = body.code();
    bodyCode = bodyCode == null ? "" : ctx.optimizeMethodCode(bodyCode);
    ctx.addMethod(
        "@Override public final",
        readMethod,
        "if (reader.tryReadNullToken()) {\n" + "  return null;\n" + "}\n" + bodyCode,
        Object.class,
        readerType,
        "reader");
    addFastReadGroupMethods(ctx, builder, readMethod, slowMethod, readerType, type, properties);
    addReadFieldMethods(ctx, builder, readMethod, readerType, type, properties);
    addSlowReadMethods(ctx, builder, slowMethod, readerType, type, properties);
    return ctx.genCode();
  }

  String genAnyReaderCode(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      JsonCreatorInfo creatorInfo,
      AnyInfo any) {
    this.any = any;
    ownerType = type;
    storesSelfReader = JsonCodegen.storesSelfReader(type, properties, creatorInfo != null, any);
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
    addSelfReaderField(ctx);
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
          readerConstructorExpression(type, properties),
          ObjectCodec.class,
          "owner",
          JsonFieldTable.class,
          "readTable",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
          "codecs",
          JsonCodegen.generatedCodecType(ctx, readerCapabilityType()),
          "anyReader");
    } else {
      addGeneratedConstructor(
          ctx,
          readerConstructorExpression(type, properties),
          ObjectCodec.class,
          "owner",
          JsonFieldTable.class,
          "readTable",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
          "codecs");
    }
    addGeneratedMethod(
        ctx,
        "private final",
        anyReadMethod,
        fastReadExpression(builder, readMethod, slowMethod, type, properties),
        Object.class,
        concreteReaderType,
        "reader");
    ctx.addMethod(
        "@Override public final",
        readMethod,
        "if (reader.tryReadNullToken()) {\n  return null;\n}\n"
            + "return this."
            + anyReadMethod
            + "(reader);",
        Object.class,
        concreteReaderType,
        "reader");
    addFastReadGroupMethods(
        ctx, builder, readMethod, slowMethod, concreteReaderType, type, properties);
    addReadFieldMethods(ctx, builder, readMethod, concreteReaderType, type, properties);
    addSlowReadMethods(ctx, builder, slowMethod, concreteReaderType, type, properties);
    return ctx.genCode();
  }

  String genUnwrappedReaderCode(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      ObjectCodec<?> owner,
      JsonUnwrappedInfo unwrapped) {
    AnyInfo ownerAny = owner.anyInfo();
    this.any =
        ownerAny != null && (ownerAny.readField() != null || ownerAny.readSetter() != null)
            ? ownerAny
            : null;
    ownerType = type;
    JsonCreatorInfo rootCreator = owner.creatorInfo();
    boolean rootArray = rootCreator != null;
    JsonFieldInfo[] directFields = owner.readFields();
    JsonCreatorFieldInfo[] directCreatorFields = rootCreator == null ? null : rootCreator.fields();
    int directCount = rootCreator == null ? directFields.length : directCreatorFields.length;
    ReadRoute[] routes = unwrapped.readRoutes();
    Group[] groups = unwrapped.groups();
    CodegenContext ctx = builder.context();
    ctx.addImports(
        ObjectCodec.class,
        readerType(),
        JsonFieldTable.class,
        JsonCreatorInfo.class,
        JsonUnwrappedInfo.class,
        Map.class);
    ctx.implementsInterfaces(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()));
    ctx.addField(ObjectCodec.class, "owner");
    ctx.addField(JsonFieldTable.class, "readTable");
    ctx.addField(JsonUnwrappedInfo.class, "unwrapped");
    if (rootCreator != null) {
      ctx.addField(JsonCreatorInfo.class, "creator");
    }
    storesSelfReader = JsonCodegen.storesSelfReader(owner);
    addSelfReaderField(ctx);
    boolean storesAnyReader = any != null && storesAnyReader(type);
    if (storesAnyReader) {
      ctx.addField(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()), "anyReader");
    }
    if (any != null
        && any.readField() != null
        && Modifier.isFinal(any.readField().getModifiers())) {
      addFinalAnyMapMethod(ctx);
    }
    if (any != null && any.readSetter() != null) {
      addAnySetterMethod(ctx, type, any);
    }
    if (rootCreator == null) {
      addReaderFields(ctx, type, directFields);
    } else {
      for (int i = 0; i < directCreatorFields.length; i++) {
        if (!isDirectCreatorPrimitive(directCreatorFields[i])) {
          ctx.addField(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()), "r" + i);
        }
      }
    }
    for (int i = 0; i < routes.length; i++) {
      int id = directCount + i;
      JsonFieldInfo field = routes[i].field();
      if (field != null) {
        if (usesReadInfo(field)) {
          ctx.addField(JsonFieldInfo.class, "rp" + id);
        }
        if (JsonCodegen.usesReadCodec(field)) {
          ctx.addField(JsonCodegen.generatedCodecType(ctx, codecFieldType(field)), "r" + id);
        }
        if (storesReadObjectCodec(type, field)) {
          ctx.addField(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()), "o" + id);
        }
      } else if (!isDirectCreatorPrimitive(routes[i].creatorField())) {
        ctx.addField(JsonCodegen.generatedCodecType(ctx, readerCapabilityType()), "r" + id);
      }
    }
    if (groups.length != 0) {
      ctx.addField(ObjectCodec[].class, "groupCodecs");
      ctx.addField(int[].class, "groupParents");
      ctx.addField(int[].class, "groupEnds");
    }
    Expression constructor =
        unwrappedReaderConstructor(
            type,
            directFields,
            directCreatorFields,
            routes,
            groups,
            directCount,
            storesAnyReader,
            any != null);
    if (storesAnyReader) {
      addGeneratedConstructor(
          ctx,
          constructor,
          ObjectCodec.class,
          "owner",
          JsonFieldTable.class,
          "readTable",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
          "codecs",
          JsonCodegen.generatedCodecType(ctx, readerCapabilityType()),
          "anyReader");
    } else if (any != null) {
      addGeneratedConstructor(
          ctx,
          constructor,
          ObjectCodec.class,
          "owner",
          JsonFieldTable.class,
          "readTable",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
          "codecs");
    } else {
      addGeneratedConstructor(
          ctx,
          constructor,
          ObjectCodec.class,
          "owner",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
          "codecs");
    }
    addUnwrappedReadMethods(
        ctx,
        builder,
        type,
        directFields,
        directCreatorFields,
        routes,
        groups,
        directCount,
        rootArray);
    addUnwrappedCreatorMethods(ctx, type, rootCreator, groups);
    addUnwrappedGroupMethods(ctx, builder, type, groups, rootArray);
    ctx.clearExprState();
    Expression root = unwrappedRootWorkspace(builder, rootCreator != null);
    Expression workspaces =
        new Expression.Variable(
            "groupWorkspaces",
            new Expression.NewArray(Object.class, Expression.Literal.ofInt(groups.length)));
    Expression present =
        new Expression.Variable(
            "groupPresent",
            new Expression.NewArray(boolean.class, Expression.Literal.ofInt(groups.length)));
    Expression.ListExpression body = new Expression.ListExpression();
    body.add(new Expression.Invoke(readerRef(), "enterDepth"), root, workspaces, present);
    Expression anyMap = anyMap(builder, root, rootArray);
    if (anyMap != null) {
      body.add(anyMap);
    }
    body.add(expectExpr('{'));
    Expression.ListExpression members =
        unwrappedMembers(
            builder,
            type,
            directFields,
            directCreatorFields,
            routes,
            groups,
            directCount,
            root,
            workspaces,
            present,
            rootArray);
    body.add(new Expression.If(not(consumeExpr('}')), members));
    Expression anyCreated =
        any == null || any.readField() == null
            ? null
            : new Expression.Variable("anyMapCreated", Expression.Literal.False);
    // The loop owns Any-map creation. Its flag is declared before the loop when needed.
    if (anyCreated != null) {
      // Rebuild the member expression with the shared flag after declaring it.
      body = new Expression.ListExpression();
      body.add(
          new Expression.Invoke(readerRef(), "enterDepth"),
          root,
          workspaces,
          present,
          anyMap,
          anyCreated,
          expectExpr('{'));
      members =
          unwrappedMembers(
              builder,
              type,
              directFields,
              directCreatorFields,
              routes,
              groups,
              directCount,
              root,
              workspaces,
              present,
              rootArray,
              anyCreated);
      body.add(new Expression.If(not(consumeExpr('}')), members));
    }
    finishAnyRead(builder, body, root, anyCreated, rootArray);
    if (groups.length != 0) {
      body.add(finishUnwrappedGroups(type, root, workspaces, present, rootArray));
    }
    Expression finishedRoot = finishUnwrappedRoot(root, type, rootCreator);
    body.add(new Expression.Invoke(readerRef(), "exitDepth"), new Expression.Return(finishedRoot));
    Code.ExprCode generated = body.genCode(ctx);
    String code = generated.code();
    code = code == null ? "" : ctx.optimizeMethodCode(code);
    ctx.addMethod(
        "@Override public final",
        readMethod(),
        "if (reader.tryReadNullToken()) {\n  return null;\n}\n" + code,
        Object.class,
        readerType(),
        "reader");
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
    addCreatorMethod(ctx, type, creatorInfo.executable());
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
    addSelfReaderField(ctx);
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
          JsonFieldTable.class,
          "readTable",
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
          JsonFieldTable.class,
          "readTable",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, readerArrayType()),
          "codecs");
    }
    addCreatorMethod(ctx, type, creatorInfo.executable());
    addGeneratedMethod(
        ctx,
        "private final",
        anyReadMethod,
        anyCreatorReadExpression(type, creatorInfo),
        Object.class,
        concreteReaderType,
        "reader");
    ctx.addMethod(
        "@Override public final",
        readMethod,
        "if (reader.tryReadNullToken()) {\n  return null;\n}\n"
            + "return this."
            + anyReadMethod
            + "(reader);",
        Object.class,
        concreteReaderType,
        "reader");
    return ctx.genCode();
  }

  private Expression anyCreatorReadExpression(Class<?> type, JsonCreatorInfo creatorInfo) {
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    Class<?>[] parameterTypes = creatorInfo.executable().getParameterTypes();
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(new Expression.Invoke(readerRef(), "enterDepth"));
    Expression[] arguments = creatorArguments(creatorInfo.executable(), expressions);
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
    Expression reserved = eq(match, Expression.Literal.ofInt(JsonFieldTable.SKIP));
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

  private void addCreatorMethod(CodegenContext ctx, Class<?> type, Executable executable) {
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
              new Reference("readTable", TypeRef.of(JsonFieldTable.class))));
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
    Expression[] arguments = creatorArguments(creatorInfo.executable(), expressions);
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
                  new Expression.Assign(
                      arguments[fields[i].argumentIndex()], readCreatorValue(fields[i], i)),
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

  private Expression[] creatorArguments(
      Executable executable, Expression.ListExpression expressions) {
    // Creator fields cover only read-enabled JSON members. The executable owns the full argument
    // shape, including ignored or getter-only parameters which must still receive typed defaults.
    Class<?>[] parameterTypes = executable.getParameterTypes();
    Expression[] arguments = new Expression[parameterTypes.length];
    for (int i = 0; i < parameterTypes.length; i++) {
      arguments[i] = new Expression.Variable("a" + i, creatorDefault(parameterTypes[i]));
      expressions.add(arguments[i]);
    }
    return arguments;
  }

  private void addUnwrappedCreatorMethods(
      CodegenContext ctx, Class<?> rootType, JsonCreatorInfo rootCreator, Group[] groups) {
    if (rootCreator != null) {
      addWorkspaceCreatorMethod(
          ctx, rootType, rootCreator.executable(), unwrappedCreatorMethod(-1), "owner");
    }
    for (int i = 0; i < groups.length; i++) {
      ObjectCodec<?> child = groups[i].childCodec();
      JsonCreatorInfo creator = child.creatorInfo();
      if (creator != null) {
        addWorkspaceCreatorMethod(
            ctx,
            child.type(),
            creator.executable(),
            unwrappedCreatorMethod(i),
            "this.groupCodecs[" + i + "]");
      }
    }
  }

  private void addWorkspaceCreatorMethod(
      CodegenContext ctx,
      Class<?> type,
      Executable executable,
      String methodName,
      String codecExpression) {
    Class<?>[] parameterTypes = executable.getParameterTypes();
    StringBuilder invocation = new StringBuilder();
    for (int i = 0; i < parameterTypes.length; i++) {
      if (i != 0) {
        invocation.append(", ");
      }
      Class<?> parameterType = parameterTypes[i];
      Class<?> castType =
          parameterType.isPrimitive() ? TypeUtils.boxedType(parameterType) : parameterType;
      invocation
          .append("((")
          .append(ctx.type(castType))
          .append(") arguments[")
          .append(i)
          .append("])");
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
        .append("} catch (Throwable e) {\n  throw ")
        .append(codecExpression)
        .append(".creatorFailure(e);\n}\n");
    if (executable instanceof Method) {
      body.append("return (")
          .append(typeName)
          .append(") ")
          .append(codecExpression)
          .append(".requireCreatorResult(value);");
    } else {
      body.append("return value;");
    }
    ctx.addMethod("private final", methodName, body.toString(), type, Object[].class, "arguments");
  }

  private String unwrappedCreatorMethod(int groupIndex) {
    return groupIndex < 0 ? "createUnwrappedRoot" : "createUnwrappedGroup" + groupIndex;
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
      JsonFieldInfo[] properties) {
    if (!shouldSplitFastRead(properties)) {
      return;
    }
    for (int start = 0; start < properties.length; ) {
      int end = readGroupEnd(properties, start);
      if (any == null) {
        addGeneratedMethod(
            ctx,
            "final",
            readGroupMethod(readMethod, start),
            fastReadGroupExpression(builder, slowMethod, type, properties, start, end),
            boolean.class,
            readerType,
            "reader",
            type,
            "object",
            long[].class,
            "fieldHashes");
      } else {
        addGeneratedMethod(
            ctx,
            "final",
            readGroupMethod(readMethod, start),
            fastReadGroupExpression(builder, slowMethod, type, properties, start, end),
            boolean.class,
            readerType,
            "reader",
            type,
            "object",
            long[].class,
            "fieldHashes",
            Map.class,
            "anyMap");
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
      JsonFieldInfo[] properties) {
    if (!shouldSplitFieldSwitch(properties)) {
      return;
    }
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
              objectParam(type),
              new Reference("fieldIndex", TypeRef.of(int.class))),
          void.class,
          readerType,
          "reader",
          type,
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
      JsonFieldInfo[] properties) {
    // An Any slow invocation consumes the entire remaining object and finishes its map before
    // returning. This makes the Map parameter its local accumulator without a holder or return
    // carrier; callers must not resume member consumption after the slow call.
    if (any == null) {
      addGeneratedMethod(
          ctx,
          "final",
          methodName,
          slowReadExpression(builder, type, properties),
          void.class,
          readerType,
          "reader",
          type,
          "object",
          int.class,
          "expectedIndex");
      addGeneratedMethod(
          ctx,
          "final",
          methodName,
          slowReadFromFirstExpression(builder, type, properties),
          void.class,
          readerType,
          "reader",
          type,
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
        slowReadExpression(builder, type, properties),
        void.class,
        readerType,
        "reader",
        type,
        "object",
        int.class,
        "expectedIndex",
        Map.class,
        "anyMap");
    addGeneratedMethod(
        ctx,
        "final",
        methodName,
        slowReadFromFirstExpression(builder, type, properties),
        void.class,
        readerType,
        "reader",
        type,
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
        "anyMap");
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

  private Expression readerConstructorExpression(Class<?> type, JsonFieldInfo[] properties) {
    Expression.ListExpression expressions = new Expression.ListExpression();
    Reference propertiesRef = new Reference("properties", TypeRef.of(JsonFieldInfo[].class));
    Reference codecsRef = new Reference("codecs", TypeRef.of(readerArrayType()));
    Reference owner = new Reference("owner", TypeRef.of(ObjectCodec.class));
    Expression table =
        any == null
            ? new Expression.Invoke(owner, "readTable", TypeRef.of(JsonFieldTable.class)).inline()
            : new Reference("readTable", TypeRef.of(JsonFieldTable.class));
    expressions.add(
        new Expression.Assign(
            new Reference("this.readTable", TypeRef.of(JsonFieldTable.class)), table));
    if (any != null) {
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

  private Expression unwrappedReaderConstructor(
      Class<?> type,
      JsonFieldInfo[] directFields,
      JsonCreatorFieldInfo[] creatorFields,
      ReadRoute[] routes,
      Group[] groups,
      int directCount,
      boolean storesAnyReader,
      boolean hasAny) {
    Expression.ListExpression expressions = new Expression.ListExpression();
    Reference owner = new Reference("owner", TypeRef.of(ObjectCodec.class));
    Reference properties = new Reference("properties", TypeRef.of(JsonFieldInfo[].class));
    Reference codecs = new Reference("codecs", TypeRef.of(readerArrayType()));
    Reference unwrapped = new Reference("this.unwrapped", TypeRef.of(JsonUnwrappedInfo.class));
    expressions.add(
        new Expression.Assign(new Reference("this.owner", TypeRef.of(ObjectCodec.class)), owner),
        new Expression.Assign(
            new Reference("this.readTable", TypeRef.of(JsonFieldTable.class)),
            hasAny
                ? new Reference("readTable", TypeRef.of(JsonFieldTable.class))
                : new Expression.Invoke(owner, "readTable", TypeRef.of(JsonFieldTable.class))
                    .inline()),
        new Expression.Assign(
            unwrapped,
            new Expression.Invoke(owner, "unwrappedInfo", TypeRef.of(JsonUnwrappedInfo.class))
                .inline()));
    if (creatorFields != null) {
      expressions.add(
          new Expression.Assign(
              new Reference("this.creator", TypeRef.of(JsonCreatorInfo.class)),
              new Expression.Invoke(owner, "creatorInfo", TypeRef.of(JsonCreatorInfo.class))
                  .inline()));
      for (int i = 0; i < creatorFields.length; i++) {
        if (!isDirectCreatorPrimitive(creatorFields[i])) {
          expressions.add(
              new Expression.Assign(
                  new Reference("this.r" + i, TypeRef.of(readerCapabilityType())),
                  new Expression.ArrayValue(codecs, Expression.Literal.ofInt(i))));
        }
      }
    } else {
      addUnwrappedReaderAssignments(expressions, type, directFields, 0, properties, codecs);
    }
    for (int i = 0; i < routes.length; i++) {
      int id = directCount + i;
      JsonFieldInfo field = routes[i].field();
      if (field != null) {
        addUnwrappedReaderAssignment(expressions, type, field, id, properties, codecs);
      } else if (!isDirectCreatorPrimitive(routes[i].creatorField())) {
        expressions.add(
            new Expression.Assign(
                new Reference("this.r" + id, TypeRef.of(readerCapabilityType())),
                new Expression.ArrayValue(codecs, Expression.Literal.ofInt(id))));
      }
    }
    if (groups.length != 0) {
      expressions.add(
          new Expression.Assign(
              new Reference("this.groupCodecs", TypeRef.of(ObjectCodec[].class)),
              new Expression.Invoke(unwrapped, "groupCodecs", TypeRef.of(ObjectCodec[].class))
                  .inline()),
          new Expression.Assign(
              new Reference("this.groupParents", TypeRef.of(int[].class)),
              new Expression.Invoke(unwrapped, "groupParents", TypeRef.of(int[].class)).inline()),
          new Expression.Assign(
              new Reference("this.groupEnds", TypeRef.of(int[].class)),
              new Expression.Invoke(unwrapped, "groupEnds", TypeRef.of(int[].class)).inline()));
    }
    if (storesAnyReader) {
      expressions.add(
          new Expression.Assign(
              new Reference("this.anyReader", TypeRef.of(readerCapabilityType())),
              new Reference("anyReader", TypeRef.of(readerCapabilityType()))));
    }
    return expressions;
  }

  private void addUnwrappedReaderAssignments(
      Expression.ListExpression expressions,
      Class<?> type,
      JsonFieldInfo[] fields,
      int offset,
      Reference properties,
      Reference codecs) {
    for (int i = 0; i < fields.length; i++) {
      addUnwrappedReaderAssignment(expressions, type, fields[i], offset + i, properties, codecs);
    }
  }

  private void addUnwrappedReaderAssignment(
      Expression.ListExpression expressions,
      Class<?> type,
      JsonFieldInfo field,
      int id,
      Reference properties,
      Reference codecs) {
    Expression property = new Expression.ArrayValue(properties, Expression.Literal.ofInt(id));
    if (usesReadInfo(field)) {
      expressions.add(
          new Expression.Assign(
              new Reference("this.rp" + id, TypeRef.of(JsonFieldInfo.class)), property));
    }
    if (JsonCodegen.usesReadCodec(field)) {
      Class<?> codecType = codecFieldType(field);
      expressions.add(
          new Expression.Assign(
              new Reference("this.r" + id, TypeRef.of(codecType)),
              new Expression.Cast(
                  new Expression.ArrayValue(codecs, Expression.Literal.ofInt(id)),
                  TypeRef.of(codecType))));
    } else if (storesReadObjectCodec(type, field)) {
      expressions.add(
          new Expression.Assign(
              new Reference("this.o" + id, TypeRef.of(readerCapabilityType())),
              new Expression.ArrayValue(codecs, Expression.Literal.ofInt(id))));
    }
  }

  private Expression unwrappedRootWorkspace(JsonGeneratedCodecBuilder builder, boolean creator) {
    if (!creator) {
      return builder.newObject();
    }
    Expression value =
        new Expression.Invoke(
            fieldRef("creator", JsonCreatorInfo.class), "newArguments", TypeRef.of(Object[].class));
    return new Expression.Variable("object", inline(value));
  }

  private Expression finishUnwrappedRoot(Expression root, Class<?> type, JsonCreatorInfo creator) {
    if (creator == null) {
      return root;
    }
    return new Expression.Invoke(
        new Reference("this", TypeRef.of(Object.class)),
        unwrappedCreatorMethod(-1),
        "",
        TypeRef.of(type),
        false,
        false,
        new Expression.Cast(root, TypeRef.of(Object[].class)));
  }

  private Expression.ListExpression unwrappedMembers(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] directFields,
      JsonCreatorFieldInfo[] creatorFields,
      ReadRoute[] routes,
      Group[] groups,
      int directCount,
      Expression root,
      Expression workspaces,
      Expression present,
      boolean rootArray) {
    return unwrappedMembers(
        builder,
        type,
        directFields,
        creatorFields,
        routes,
        groups,
        directCount,
        root,
        workspaces,
        present,
        rootArray,
        null);
  }

  private Expression.ListExpression unwrappedMembers(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] directFields,
      JsonCreatorFieldInfo[] creatorFields,
      ReadRoute[] routes,
      Group[] groups,
      int directCount,
      Expression root,
      Expression workspaces,
      Expression present,
      boolean rootArray,
      Expression anyCreated) {
    Expression.ListExpression expressions = new Expression.ListExpression();
    Expression.ListExpression loop = new Expression.ListExpression();
    Expression fieldStart =
        any == null
            ? null
            : new Expression.Variable(
                "fieldStart",
                new Expression.Invoke(readerRef(), "position", TypeRef.of(int.class)).inline());
    if (fieldStart != null) {
      loop.add(fieldStart);
    }
    Expression hash = readFieldNameHash("fieldHash");
    loop.add(hash);
    Expression direct =
        new Expression.Variable(
            "directIndex",
            new Expression.Invoke(
                    creatorFields == null
                        ? readTableRef()
                        : fieldRef("creator", JsonCreatorInfo.class),
                    creatorFields == null ? "match" : "index",
                    TypeRef.of(int.class),
                    true,
                    hash)
                .inline());
    loop.add(direct, expectExpr(':'));
    Expression directRead =
        creatorFields == null
            ? unwrappedDirectFields(builder, type, directFields, root, direct)
            : unwrappedDirectCreator(creatorFields, root, direct);
    Expression directMiss = direct;
    if (creatorFields != null) {
      directMiss =
          new Expression.Variable(
              "directMiss",
              new Expression.Invoke(readTableRef(), "match", TypeRef.of(int.class), true, hash)
                  .inline());
    }
    Expression routeIndex =
        new Expression.Variable(
            "routeIndex",
            new Expression.Invoke(
                    fieldRef("unwrapped", JsonUnwrappedInfo.class),
                    "match",
                    TypeRef.of(int.class),
                    true,
                    hash)
                .inline());
    Expression routeRead =
        unwrappedRouteSwitch(builder, routes, groups, directCount, routeIndex, workspaces, present);
    Expression unknown =
        any == null
            ? new Expression.Invoke(readerRef(), "skipValue")
            : readUnknown(root, routeIndex, hash, fieldStart, anyCreated, rootArray);
    Expression afterDirect =
        new Expression.If(
            eq(directMiss, Expression.Literal.ofInt(JsonFieldTable.SKIP)),
            new Expression.Invoke(readerRef(), "skipValue"),
            new Expression.ListExpression(
                routeIndex,
                new Expression.If(
                    ge(routeIndex, Expression.Literal.ofInt(0)), routeRead, unknown)));
    if (creatorFields != null) {
      afterDirect = new Expression.ListExpression(directMiss, afterDirect);
    }
    loop.add(new Expression.If(ge(direct, Expression.Literal.ofInt(0)), directRead, afterDirect));
    loop.add(
        new Expression.If(
            not(consumeExpr(',')),
            new Expression.ListExpression(expectExpr('}'), new Expression.Break())));
    expressions.add(new Expression.While(Expression.Literal.True, loop));
    return expressions;
  }

  private Expression unwrappedDirectFields(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] fields,
      Expression root,
      Expression index) {
    if (fields.length > READ_FIELD_SWITCH_SIZE) {
      int chunks = (fields.length + READ_FIELD_SWITCH_SIZE - 1) / READ_FIELD_SWITCH_SIZE;
      Expression.Switch.Case[] cases = new Expression.Switch.Case[chunks];
      for (int i = 0, start = 0; i < chunks; i++, start += READ_FIELD_SWITCH_SIZE) {
        cases[i] =
            new Expression.Switch.Case(
                i,
                new Expression.ListExpression(
                    new Expression.Invoke(
                        new Reference("this", TypeRef.of(Object.class)),
                        unwrappedDirectMethod(start),
                        "",
                        TypeRef.of(void.class),
                        false,
                        false,
                        readerRef(),
                        root,
                        index),
                    new Expression.Break()));
      }
      Expression chunk =
          new Expression.Arithmetic(
              true, "/", index, Expression.Literal.ofInt(READ_FIELD_SWITCH_SIZE));
      return new Expression.Switch(chunk, cases, new Expression.Invoke(readerRef(), "skipValue"));
    }
    return unwrappedDirectFields(builder, type, fields, 0, fields.length, root, index);
  }

  private Expression unwrappedDirectFields(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] fields,
      int start,
      int end,
      Expression root,
      Expression index) {
    Expression.Switch.Case[] cases = new Expression.Switch.Case[end - start];
    for (int i = start; i < end; i++) {
      Expression read = readField(builder, type, fields[i], i, root, false);
      cases[i - start] =
          new Expression.Switch.Case(
              i, new Expression.ListExpression(read, new Expression.Break()));
    }
    return new Expression.Switch(index, cases, new Expression.Invoke(readerRef(), "skipValue"));
  }

  private Expression unwrappedDirectCreator(
      JsonCreatorFieldInfo[] fields, Expression root, Expression index) {
    if (fields.length > READ_FIELD_SWITCH_SIZE) {
      int chunks = (fields.length + READ_FIELD_SWITCH_SIZE - 1) / READ_FIELD_SWITCH_SIZE;
      Expression.Switch.Case[] cases = new Expression.Switch.Case[chunks];
      for (int i = 0, start = 0; i < chunks; i++, start += READ_FIELD_SWITCH_SIZE) {
        cases[i] =
            new Expression.Switch.Case(
                i,
                new Expression.ListExpression(
                    new Expression.Invoke(
                        new Reference("this", TypeRef.of(Object.class)),
                        unwrappedDirectMethod(start),
                        "",
                        TypeRef.of(void.class),
                        false,
                        false,
                        readerRef(),
                        root,
                        index),
                    new Expression.Break()));
      }
      Expression chunk =
          new Expression.Arithmetic(
              true, "/", index, Expression.Literal.ofInt(READ_FIELD_SWITCH_SIZE));
      return new Expression.Switch(chunk, cases, new Expression.Invoke(readerRef(), "skipValue"));
    }
    return unwrappedDirectCreator(fields, 0, fields.length, root, index);
  }

  private Expression unwrappedDirectCreator(
      JsonCreatorFieldInfo[] fields, int start, int end, Expression root, Expression index) {
    Expression.Switch.Case[] cases = new Expression.Switch.Case[end - start];
    for (int i = start; i < end; i++) {
      JsonCreatorFieldInfo field = fields[i];
      cases[i - start] =
          new Expression.Switch.Case(
              i,
              new Expression.ListExpression(
                  new Expression.AssignArrayElem(
                      root,
                      readCreatorValue(field, i),
                      Expression.Literal.ofInt(field.argumentIndex())),
                  new Expression.Break()));
    }
    return new Expression.Switch(index, cases, new Expression.Invoke(readerRef(), "skipValue"));
  }

  private Expression unwrappedRouteSwitch(
      JsonGeneratedCodecBuilder builder,
      ReadRoute[] routes,
      Group[] groups,
      int directCount,
      Expression routeIndex,
      Expression workspaces,
      Expression present) {
    if (routes.length > READ_FIELD_SWITCH_SIZE) {
      int chunks = (routes.length + READ_FIELD_SWITCH_SIZE - 1) / READ_FIELD_SWITCH_SIZE;
      Expression.Switch.Case[] cases = new Expression.Switch.Case[chunks];
      for (int i = 0, start = 0; i < chunks; i++, start += READ_FIELD_SWITCH_SIZE) {
        cases[i] =
            new Expression.Switch.Case(
                i,
                new Expression.ListExpression(
                    new Expression.Invoke(
                        new Reference("this", TypeRef.of(Object.class)),
                        unwrappedRouteMethod(start),
                        "",
                        TypeRef.of(void.class),
                        false,
                        false,
                        readerRef(),
                        workspaces,
                        present,
                        routeIndex),
                    new Expression.Break()));
      }
      Expression chunk =
          new Expression.Arithmetic(
              true, "/", routeIndex, Expression.Literal.ofInt(READ_FIELD_SWITCH_SIZE));
      return new Expression.Switch(chunk, cases, new Expression.Invoke(readerRef(), "skipValue"));
    }
    return unwrappedRouteSwitch(
        builder, routes, groups, directCount, 0, routes.length, routeIndex, workspaces, present);
  }

  private Expression unwrappedRouteSwitch(
      JsonGeneratedCodecBuilder builder,
      ReadRoute[] routes,
      Group[] groups,
      int directCount,
      int start,
      int end,
      Expression routeIndex,
      Expression workspaces,
      Expression present) {
    Expression.Switch.Case[] cases = new Expression.Switch.Case[end - start];
    for (int i = start; i < end; i++) {
      ReadRoute route = routes[i];
      int id = directCount + i;
      Expression.ListExpression read = new Expression.ListExpression();
      addEnsureUnwrappedGroup(read, route.group(), workspaces, present);
      int groupIndex = route.group().readIndex();
      ObjectCodec<?> child = route.group().childCodec();
      Expression workspace =
          new Expression.ArrayValue(workspaces, Expression.Literal.ofInt(groupIndex));
      if (route.field() != null) {
        JsonFieldInfo field = route.field();
        if (child.creatorInfo() != null) {
          throw new IllegalStateException("Creator group route must use creator metadata");
        }
        Expression object = new Expression.Cast(workspace, TypeRef.of(child.type()));
        read.add(readField(builder, ownerType, field, id, object, false));
      } else {
        JsonCreatorFieldInfo field = route.creatorField();
        read.add(
            new Expression.AssignArrayElem(
                new Expression.Cast(workspace, TypeRef.of(Object[].class)),
                readCreatorValue(field, id),
                Expression.Literal.ofInt(field.argumentIndex())));
      }
      read.add(new Expression.Break());
      cases[i - start] = new Expression.Switch.Case(i, read);
    }
    return new Expression.Switch(
        routeIndex, cases, new Expression.Invoke(readerRef(), "skipValue"));
  }

  private void addUnwrappedReadMethods(
      CodegenContext ctx,
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] fields,
      JsonCreatorFieldInfo[] creatorFields,
      ReadRoute[] routes,
      Group[] groups,
      int directCount,
      boolean rootArray) {
    if (directCount > READ_FIELD_SWITCH_SIZE) {
      Class<?> rootType = rootArray ? Object[].class : type;
      for (int start = 0; start < directCount; start += READ_FIELD_SWITCH_SIZE) {
        int end = Math.min(start + READ_FIELD_SWITCH_SIZE, directCount);
        Expression root = new Reference("object", TypeRef.of(rootType));
        Expression index = new Reference("fieldIndex", TypeRef.of(int.class));
        Expression read =
            creatorFields == null
                ? unwrappedDirectFields(builder, type, fields, start, end, root, index)
                : unwrappedDirectCreator(creatorFields, start, end, root, index);
        addGeneratedMethod(
            ctx,
            "private final",
            unwrappedDirectMethod(start),
            read,
            void.class,
            readerType(),
            "reader",
            rootType,
            "object",
            int.class,
            "fieldIndex");
      }
    }
    if (routes.length > READ_FIELD_SWITCH_SIZE) {
      for (int start = 0; start < routes.length; start += READ_FIELD_SWITCH_SIZE) {
        int end = Math.min(start + READ_FIELD_SWITCH_SIZE, routes.length);
        addGeneratedMethod(
            ctx,
            "private final",
            unwrappedRouteMethod(start),
            unwrappedRouteSwitch(
                builder,
                routes,
                groups,
                directCount,
                start,
                end,
                new Reference("routeIndex", TypeRef.of(int.class)),
                new Reference("groupWorkspaces", TypeRef.of(Object[].class)),
                new Reference("groupPresent", TypeRef.of(boolean[].class))),
            void.class,
            readerType(),
            "reader",
            Object[].class,
            "groupWorkspaces",
            boolean[].class,
            "groupPresent",
            int.class,
            "routeIndex");
      }
    }
  }

  private String unwrappedDirectMethod(int start) {
    return readMethod() + "UnwrappedDirect" + start;
  }

  private String unwrappedRouteMethod(int start) {
    return readMethod() + "UnwrappedRoute" + start;
  }

  private void addEnsureUnwrappedGroup(
      Expression.ListExpression expressions,
      Group group,
      Expression workspaces,
      Expression present) {
    expressions.add(
        new Expression.Invoke(
            new Reference("this", TypeRef.of(Object.class)),
            "ensureUnwrappedGroup",
            "",
            TypeRef.of(void.class),
            false,
            false,
            Expression.Literal.ofInt(group.readIndex()),
            workspaces,
            present));
  }

  private void addUnwrappedGroupMethods(
      CodegenContext ctx,
      JsonGeneratedCodecBuilder builder,
      Class<?> rootType,
      Group[] groups,
      boolean rootArray) {
    if (groups.length == 0) {
      return;
    }
    // Groups are preorder-indexed and groupEnds skips sibling subtrees, so a target path is
    // initialized in one pass without repeatedly walking its ancestors.
    String ensureBody =
        "int current = target;\n"
            + "while (this.groupParents[current] >= 0 "
            + "&& !present[this.groupParents[current]]) {\n"
            + "  current = this.groupParents[current];\n"
            + "}\n"
            + "while (true) {\n"
            + "  if (!present[current]) {\n"
            + "    initUnwrappedGroup(current, workspaces, present);\n"
            + "  }\n"
            + "  if (current == target) {\n"
            + "    return;\n"
            + "  }\n"
            + "  current++;\n"
            + "  while (this.groupEnds[current] < target) {\n"
            + "    current = this.groupEnds[current] + 1;\n"
            + "  }\n"
            + "}";
    ctx.addMethod(
        "private final",
        "ensureUnwrappedGroup",
        ensureBody,
        void.class,
        int.class,
        "target",
        Object[].class,
        "workspaces",
        boolean[].class,
        "present");
    addUnwrappedGroupInitMethods(ctx, groups);
    addUnwrappedGroupFinishMethods(ctx, builder, rootType, groups, rootArray);
  }

  private void addUnwrappedGroupInitMethods(CodegenContext ctx, Group[] groups) {
    int chunks = (groups.length + READ_FIELD_SWITCH_SIZE - 1) / READ_FIELD_SWITCH_SIZE;
    StringBuilder dispatch = new StringBuilder("switch (groupIndex / ");
    dispatch.append(READ_FIELD_SWITCH_SIZE).append(") {\n");
    for (int chunk = 0; chunk < chunks; chunk++) {
      int start = chunk * READ_FIELD_SWITCH_SIZE;
      int end = Math.min(start + READ_FIELD_SWITCH_SIZE, groups.length);
      StringBuilder body = new StringBuilder("switch (groupIndex) {\n");
      for (int i = start; i < end; i++) {
        ObjectCodec<?> child = groups[i].childCodec();
        body.append("case ").append(i).append(":\n  workspaces[").append(i).append("] = ");
        if (child.creatorInfo() != null) {
          body.append("this.groupCodecs[").append(i).append("].creatorInfo().newArguments();\n");
        } else {
          body.append("this.groupCodecs[").append(i).append("].newInstance();\n");
        }
        body.append("  present[").append(i).append("] = true;\n  return;\n");
      }
      body.append("default:\n  throw new IllegalArgumentException();\n}");
      ctx.addMethod(
          "private final",
          unwrappedGroupInitMethod(start),
          body.toString(),
          void.class,
          int.class,
          "groupIndex",
          Object[].class,
          "workspaces",
          boolean[].class,
          "present");
      dispatch
          .append("case ")
          .append(chunk)
          .append(":\n  ")
          .append(unwrappedGroupInitMethod(start))
          .append("(groupIndex, workspaces, present);\n  return;\n");
    }
    dispatch.append("default:\n  throw new IllegalArgumentException();\n}");
    ctx.addMethod(
        "private final",
        "initUnwrappedGroup",
        dispatch.toString(),
        void.class,
        int.class,
        "groupIndex",
        Object[].class,
        "workspaces",
        boolean[].class,
        "present");
  }

  private void addUnwrappedGroupFinishMethods(
      CodegenContext ctx,
      JsonGeneratedCodecBuilder builder,
      Class<?> rootType,
      Group[] groups,
      boolean rootArray) {
    Class<?> rootWorkspaceType = rootArray ? Object[].class : rootType;
    int chunks = (groups.length + READ_FIELD_SWITCH_SIZE - 1) / READ_FIELD_SWITCH_SIZE;
    Expression.Switch.Case[] dispatchCases = new Expression.Switch.Case[chunks];
    for (int chunk = 0; chunk < chunks; chunk++) {
      int start = chunk * READ_FIELD_SWITCH_SIZE;
      int end = Math.min(start + READ_FIELD_SWITCH_SIZE, groups.length);
      Expression.Switch.Case[] cases = new Expression.Switch.Case[end - start];
      for (int i = start; i < end; i++) {
        cases[i - start] =
            new Expression.Switch.Case(
                i,
                new Expression.ListExpression(
                    finishUnwrappedGroup(
                        builder,
                        groups[i],
                        i,
                        new Reference("root", TypeRef.of(rootWorkspaceType)),
                        new Reference("workspaces", TypeRef.of(Object[].class))),
                    new Expression.Break()));
      }
      addGeneratedMethod(
          ctx,
          "private final",
          unwrappedGroupFinishMethod(start),
          new Expression.Switch(
              new Reference("groupIndex", TypeRef.of(int.class)), cases, new Expression.Empty()),
          void.class,
          rootWorkspaceType,
          "root",
          Object[].class,
          "workspaces",
          int.class,
          "groupIndex");
      dispatchCases[chunk] =
          new Expression.Switch.Case(
              chunk,
              new Expression.ListExpression(
                  new Expression.Invoke(
                      new Reference("this", TypeRef.of(Object.class)),
                      unwrappedGroupFinishMethod(start),
                      "",
                      TypeRef.of(void.class),
                      false,
                      false,
                      new Reference("root", TypeRef.of(rootWorkspaceType)),
                      new Reference("workspaces", TypeRef.of(Object[].class)),
                      new Reference("groupIndex", TypeRef.of(int.class))),
                  new Expression.Break()));
    }
    Expression chunk =
        new Expression.Arithmetic(
            true,
            "/",
            new Reference("groupIndex", TypeRef.of(int.class)),
            Expression.Literal.ofInt(READ_FIELD_SWITCH_SIZE));
    addGeneratedMethod(
        ctx,
        "private final",
        "finishUnwrappedGroup",
        new Expression.Switch(chunk, dispatchCases, new Expression.Empty()),
        void.class,
        rootWorkspaceType,
        "root",
        Object[].class,
        "workspaces",
        int.class,
        "groupIndex");
    String body =
        "for (int i = this.groupCodecs.length - 1; i >= 0; i--) {\n"
            + "  if (present[i]) {\n"
            + "    finishUnwrappedGroup(root, workspaces, i);\n"
            + "  }\n"
            + "}";
    ctx.addMethod(
        "private final",
        "finishUnwrappedGroups",
        body,
        void.class,
        rootWorkspaceType,
        "root",
        Object[].class,
        "workspaces",
        boolean[].class,
        "present");
  }

  private Expression finishUnwrappedGroup(
      JsonGeneratedCodecBuilder builder,
      Group group,
      int index,
      Expression root,
      Expression workspaces) {
    ObjectCodec<?> childCodec = group.childCodec();
    Expression workspace = new Expression.ArrayValue(workspaces, Expression.Literal.ofInt(index));
    Expression child;
    if (childCodec.creatorInfo() != null) {
      child =
          new Expression.Invoke(
              new Reference("this", TypeRef.of(Object.class)),
              unwrappedCreatorMethod(index),
              "",
              TypeRef.of(childCodec.type()),
              false,
              false,
              new Expression.Cast(workspace, TypeRef.of(Object[].class)));
    } else {
      child = workspace;
    }
    Group parent = group.parent();
    ObjectCodec<?> parentCodec = group.parentCodec();
    Expression parentWorkspace =
        parent == null
            ? root
            : new Expression.ArrayValue(workspaces, Expression.Literal.ofInt(parent.readIndex()));
    if (parentCodec.creatorInfo() != null) {
      return new Expression.AssignArrayElem(
          new Expression.Cast(parentWorkspace, TypeRef.of(Object[].class)),
          child,
          Expression.Literal.ofInt(group.declaration().constructionIndex()));
    }
    return builder.setUnwrapped(
        group.declaration(),
        new Expression.Cast(parentWorkspace, TypeRef.of(parentCodec.type())),
        new Expression.Cast(child, TypeRef.of(childCodec.type())));
  }

  private Expression finishUnwrappedGroups(
      Class<?> rootType,
      Expression root,
      Expression workspaces,
      Expression present,
      boolean rootArray) {
    Class<?> rootWorkspaceType = rootArray ? Object[].class : rootType;
    return new Expression.Invoke(
        new Reference("this", TypeRef.of(Object.class)),
        "finishUnwrappedGroups",
        "",
        TypeRef.of(void.class),
        false,
        false,
        new Expression.Cast(root, TypeRef.of(rootWorkspaceType)),
        workspaces,
        present);
  }

  private String unwrappedGroupInitMethod(int start) {
    return "initUnwrappedGroup" + start;
  }

  private String unwrappedGroupFinishMethod(int start) {
    return "finishUnwrappedGroup" + start;
  }

  private Expression fastReadExpression(
      JsonGeneratedCodecBuilder builder,
      String readMethod,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties) {
    if (shouldSplitFastRead(properties)) {
      return splitFastReadExpression(builder, readMethod, slowMethod, type, properties);
    }
    Expression object = objectExpression(builder);
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(new Expression.Invoke(readerRef(), "enterDepth"));
    expressions.add(object);
    Expression anyMap = anyMap(builder, object);
    if (anyMap != null) {
      expressions.add(anyMap);
    }
    expressions.add(expectExpr('{'));
    expressions.add(new Expression.If(consumeExpr('}'), returnObject(object)));
    if (properties.length == 0) {
      expressions.add(slowCall(slowMethod, object, Expression.Literal.ofInt(0)));
      expressions.add(returnObject(object));
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
          fastReadField(builder, slowMethod, type, properties, i, object, hashes, skips);
      expressions.add(i == 0 ? read : new Expression.If(not(skips[i]), read));
    }
    expressions.add(returnObject(object));
    return expressions;
  }

  private Expression splitFastReadExpression(
      JsonGeneratedCodecBuilder builder,
      String readMethod,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties) {
    Expression object = objectExpression(builder);
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(new Expression.Invoke(readerRef(), "enterDepth"));
    expressions.add(object);
    Expression anyMap = anyMap(builder, object);
    if (anyMap != null) {
      expressions.add(anyMap);
    }
    expressions.add(expectExpr('{'));
    expressions.add(new Expression.If(consumeExpr('}'), returnObject(object)));
    Expression hashes =
        new Expression.Variable("localFieldHashes", fieldRef("fieldHashes", long[].class));
    expressions.add(hashes);
    for (int start = 0; start < properties.length; ) {
      int end = readGroupEnd(properties, start);
      Expression groupCall = inline(readGroupCall(readMethod, start, object, hashes));
      expressions.add(new Expression.If(not(groupCall), returnObject(object)));
      start = end;
    }
    expressions.add(returnObject(object));
    return expressions;
  }

  private Expression fastReadGroupExpression(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int start,
      int end) {
    Expression object = objectParam(type);
    Expression hashes = new Reference("fieldHashes", TypeRef.of(long[].class));
    Expression[] skips = new Expression[properties.length];
    Expression.ListExpression expressions = new Expression.ListExpression();
    for (int i = start + 1; i < end; i++) {
      skips[i] = new Expression.Variable("skip" + i, Expression.Literal.False);
      expressions.add(skips[i]);
    }
    for (int i = start; i < end; i++) {
      Expression read =
          fastReadField(builder, slowMethod, type, properties, i, end, true, object, hashes, skips);
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
        anyMapRef());
  }

  private Expression anyMap(JsonGeneratedCodecBuilder builder, Expression object) {
    if (any == null || any.readField() == null) {
      return null;
    }
    Expression initial =
        new Expression.Cast(
            inline(builder.anyValue(any.readField(), object)), TypeRef.of(Map.class));
    return new Expression.Variable("anyMap", initial);
  }

  private Expression anyMap(
      JsonGeneratedCodecBuilder builder, Expression object, boolean creatorWorkspace) {
    if (!creatorWorkspace) {
      return anyMap(builder, object);
    }
    if (any == null || any.readField() == null) {
      return null;
    }
    return new Expression.Variable("anyMap", new Expression.Null(TypeRef.of(Map.class), false));
  }

  private Expression fastReadField(
      JsonGeneratedCodecBuilder builder,
      String slowMethod,
      Class<?> type,
      JsonFieldInfo[] properties,
      int index,
      Expression object,
      Expression hashes,
      Expression[] skips) {
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
        skips);
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
      Expression[] skips) {
    if (isDirectName(properties[index].name(), true)) {
      return statementIf(
          tryReadNextFieldNameColon(properties[index], true),
          new Expression.ListExpression(
              readField(builder, type, properties[index], index, object, true),
              fieldEnd(slowMethod, properties.length, groupEnd, groupHelper, index, object)),
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
              skips),
          groupHelper);
    }
    return nextDirectFallback(
        builder, slowMethod, type, properties, index, groupEnd, groupHelper, object, hashes, skips);
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
      Expression[] skips) {
    int nextIndex = index + 1;
    if (nextIndex < groupEnd && isDirectName(properties[nextIndex].name(), true)) {
      return statementIf(
          tryReadNextFieldNameColon(properties[nextIndex], true),
          new Expression.ListExpression(
              readField(builder, type, properties[nextIndex], nextIndex, object, true),
              new Expression.Assign(skips[nextIndex], Expression.Literal.True),
              fieldEnd(slowMethod, properties.length, groupEnd, groupHelper, nextIndex, object)),
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
              skips),
          groupHelper);
    }
    return hashFallback(
        builder, slowMethod, type, properties, index, groupEnd, groupHelper, object, hashes, skips);
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
      Expression[] skips) {
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
            fieldStart));
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
      Expression fieldStart) {
    Expression fallback;
    if (index + 1 < groupEnd) {
      fallback =
          statementIf(
              eq(fieldHash, arrayValue(hashes, index + 1)),
              new Expression.ListExpression(
                  expectExpr(':'),
                  readField(builder, type, properties[index + 1], index + 1, object, false),
                  new Expression.Assign(skips[index + 1], Expression.Literal.True),
                  fieldEnd(
                      slowMethod, properties.length, groupEnd, groupHelper, index + 1, object)),
              slowConsumedReturn(
                  slowMethod,
                  index,
                  fieldIndexInvoke(fieldHash),
                  fieldHash,
                  fieldStart,
                  object,
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
              groupHelper);
    }
    return statementIf(
        ne(fieldHash, arrayValue(hashes, index)),
        fallback,
        new Expression.ListExpression(
            expectExpr(':'),
            readField(builder, type, properties[index], index, object, false),
            fieldEnd(slowMethod, properties.length, groupEnd, groupHelper, index, object)),
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

  private Expression objectExpression(JsonGeneratedCodecBuilder builder) {
    return builder.newObject();
  }

  private Expression returnObject(Expression object) {
    Expression exitDepth = new Expression.Invoke(readerRef(), "exitDepth");
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
      String slowMethod, int index, Expression firstFieldIndex, Expression object) {
    return new Expression.ListExpression(
        slowCall(slowMethod, object, Expression.Literal.ofInt(index), firstFieldIndex),
        returnObject(object));
  }

  private Expression slowConsumedReturn(
      String slowMethod,
      int index,
      Expression firstFieldIndex,
      Expression object,
      boolean groupHelper) {
    if (!groupHelper) {
      return slowConsumedReturn(slowMethod, index, firstFieldIndex, object);
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
      boolean groupHelper) {
    if (any == null) {
      return slowConsumedReturn(slowMethod, index, firstFieldIndex, object, groupHelper);
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
          returnObject(object));
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
      Expression object) {
    if (!groupHelper) {
      return fieldEnd(slowMethod, propertyCount, index, object);
    }
    return fastReadGroupEnd(slowMethod, propertyCount, index, object);
  }

  private Expression fieldEnd(String slowMethod, int propertyCount, int index, Expression object) {
    if (index + 1 < propertyCount) {
      return new Expression.If(not(consumeCommaOrEndObjectExpr()), returnObject(object));
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
      JsonGeneratedCodecBuilder builder, Class<?> type, JsonFieldInfo[] properties) {
    Expression object = objectParam(type);
    Expression hashes =
        new Expression.Variable("localFieldHashes", fieldRef("fieldHashes", long[].class));
    Reference expectedIndex = new Reference("expectedIndex", TypeRef.of(int.class));
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(hashes);
    Expression anyMapCreated = anyMapCreatedFlag(expressions);
    Expression.ListExpression loop = new Expression.ListExpression();
    loop.add(
        readNextHashedField(
            builder, type, properties, object, hashes, expectedIndex, anyMapCreated));
    loop.add(new Expression.If(not(consumeCommaOrEndObjectExpr()), new Expression.Break()));
    expressions.add(new Expression.While(Expression.Literal.True, loop));
    finishAnyRead(builder, expressions, object, anyMapCreated);
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
      Expression anyMapCreated) {
    finishAnyRead(builder, expressions, object, anyMapCreated, false);
  }

  private void finishAnyRead(
      JsonGeneratedCodecBuilder builder,
      Expression.ListExpression expressions,
      Expression object,
      Expression anyMapCreated,
      boolean creatorWorkspace) {
    Expression finish = finishAnyReadExpression(builder, object, anyMapCreated, creatorWorkspace);
    if (finish != null) {
      expressions.add(finish);
    }
  }

  private Expression finishAnyReadExpression(
      JsonGeneratedCodecBuilder builder, Expression object, Expression anyMapCreated) {
    return finishAnyReadExpression(builder, object, anyMapCreated, false);
  }

  private Expression finishAnyReadExpression(
      JsonGeneratedCodecBuilder builder,
      Expression object,
      Expression anyMapCreated,
      boolean creatorWorkspace) {
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
    if (creatorWorkspace) {
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
      JsonGeneratedCodecBuilder builder, Expression object, Expression anyMapCreated) {
    Expression finish = finishAnyReadExpression(builder, object, anyMapCreated);
    return finish == null
        ? new Expression.Return()
        : new Expression.ListExpression(finish, new Expression.Return());
  }

  private Expression slowReadFromFirstExpression(
      JsonGeneratedCodecBuilder builder, Class<?> type, JsonFieldInfo[] properties) {
    Expression object = objectParam(type);
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
            anyMapCreated));
    loop.add(updateExpectedIndex(expectedIndex, fieldIndex));
    loop.add(
        new Expression.If(
            not(consumeCommaOrEndObjectExpr()),
            any == null
                ? new Expression.Return()
                : finishAnyReadAndReturn(builder, object, anyMapCreated)));
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
      Expression anyMapCreated) {
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
            builder, type, properties, object, fieldIndex, fieldHash, fieldStart, anyMapCreated),
        updateExpectedIndex(expectedIndex, fieldIndex));
    return expressions;
  }

  private Expression fieldSwitch(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      Expression object,
      Expression fieldIndex) {
    return fieldSwitch(builder, type, properties, object, fieldIndex, null, null, null);
  }

  private Expression fieldSwitch(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      Expression object,
      Expression fieldIndex,
      Expression fieldHash,
      Expression fieldStart,
      Expression anyMapCreated) {
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
              readUnknown(object, fieldIndex, fieldHash, fieldStart, anyMapCreated));
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
        anyMapCreated);
  }

  private Expression fieldSwitchRange(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      int start,
      int end,
      Expression object,
      Expression fieldIndex) {
    return fieldSwitchRange(
        builder,
        type,
        properties,
        start,
        end,
        object,
        fieldIndex,
        new Expression.Invoke(readerRef(), "skipValue"));
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
      Expression anyMapCreated) {
    Expression unknown =
        any == null
            ? new Expression.Invoke(readerRef(), "skipValue")
            : readUnknown(object, fieldIndex, fieldHash, fieldStart, anyMapCreated);
    return fieldSwitchRange(builder, type, properties, start, end, object, fieldIndex, unknown);
  }

  private Expression fieldSwitchRange(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      int start,
      int end,
      Expression object,
      Expression fieldIndex,
      Expression unknown) {
    Expression.Switch.Case[] cases = new Expression.Switch.Case[end - start];
    for (int i = start; i < end; i++) {
      cases[i - start] =
          new Expression.Switch.Case(
              i,
              new Expression.ListExpression(
                  readField(builder, type, properties[i], i, object, false),
                  new Expression.Break()));
    }
    return new Expression.Switch(fieldIndex, cases, unknown);
  }

  private Expression readUnknown(
      Expression object,
      Expression fieldIndex,
      Expression fieldHash,
      Expression fieldStart,
      Expression anyMapCreated) {
    return readUnknown(object, fieldIndex, fieldHash, fieldStart, anyMapCreated, false);
  }

  private Expression readUnknown(
      Expression object,
      Expression fieldIndex,
      Expression fieldHash,
      Expression fieldStart,
      Expression anyMapCreated,
      boolean creatorWorkspace) {
    Expression skip = new Expression.Invoke(readerRef(), "skipValue");
    Expression reserved = eq(fieldIndex, Expression.Literal.ofInt(JsonFieldTable.SKIP));
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
          creatorWorkspace
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

  final Expression objectParam(Class<?> type) {
    return new Reference("object", TypeRef.of(type));
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

  private void addSelfReaderField(CodegenContext ctx) {
    if (storesSelfReader) {
      // Canonical readers keep this self-reference. A parent-local inline instance is rewired to
      // the canonical reader before publication so its derived skip table cannot reach children.
      ctx.addField(
          false,
          JsonCodegen.generatedCodecType(ctx, readerCapabilityType()),
          "selfReader",
          selfRef());
    }
  }

  private Expression nestedSelfReaderRef() {
    return storesSelfReader ? fieldRef("selfReader", readerCapabilityType()) : selfRef();
  }

  private boolean storesAnyReader(Class<?> type) {
    return !any.valueTypeInfo().usesDefaultObjectCodec() || any.valueTypeInfo().rawType() != type;
  }

  private Expression anyReaderRef() {
    return storesAnyReader(ownerType)
        ? fieldRef("anyReader", readerCapabilityType())
        : nestedSelfReaderRef();
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
            anyMapRef());
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
          anyMapRef());
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
      boolean tokenValueRead) {
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
        property.readRawType() == type
            ? nestedSelfReaderRef()
            : fieldRef("o" + id, readerCapabilityType());
    return new Expression.Cast(
        inline(
            new Expression.Invoke(
                codec, readMethod(), TypeRef.of(Object.class), false, readerRef())),
        TypeRef.of(property.readRawType()));
  }
}
