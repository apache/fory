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
import static org.apache.fory.codegen.ExpressionUtils.cast;
import static org.apache.fory.codegen.ExpressionUtils.eq;
import static org.apache.fory.codegen.ExpressionUtils.inline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.fory.codegen.Code;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.ExpressionOptimizer;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.JsonUnwrappedInfo;
import org.apache.fory.json.codec.JsonUnwrappedInfo.Group;
import org.apache.fory.json.codec.JsonUnwrappedInfo.WriteEntry;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.reflect.TypeRef;

/**
 * Shared generation mechanics for concrete String and UTF-8 object-writer capabilities.
 *
 * <p>The concrete generators own representation-specific field prefixes, scalar stores, and child
 * capability types. This base shares source-construction algorithms only after the concrete writer
 * is selected; it is not a runtime output mode. Generated writers retain precomputed field tokens
 * and concrete child capabilities, fuse safe object prefixes, and split wide objects into bounded
 * methods to protect compiler and inlining budgets without adding per-field dispatch.
 */
abstract class JsonWriterCodegen {
  // Bound field logic in independently compiled generated methods. The entry method keeps a small
  // tail inline below; wide objects use fewer bounded calls without adding runtime dispatch.
  private static final int MAX_MEMBERS_PER_METHOD = 16;
  private static final int INLINE_TAIL_MEMBERS = 4;

  final JsonCodegen codegen;
  private Class<?> ownerType;

  JsonWriterCodegen(JsonCodegen codegen) {
    this.codegen = codegen;
  }

  abstract Class<?> codecFieldType(JsonFieldInfo property);

  abstract Class<?> writerType();

  abstract Class<?> codecArrayType();

  abstract Class<?> completeWriterType();

  abstract String writeMethod();

  // This names private split methods in ordinary complete writers. It is not a partial-object
  // capability; keep the generated literal stable so unaffected writer source remains identical.
  abstract String memberGroupMethod();

  abstract String writeAnyMethod();

  abstract int splitMemberThreshold();

  abstract PrefixFields prefixFields(JsonFieldInfo[] properties, boolean objectStartFused);

  abstract void addPrefixFields(
      CodegenContext ctx, JsonFieldInfo property, int id, PrefixFields fields);

  abstract void addPrefixAssignments(
      Expression.ListExpression expressions,
      Expression property,
      JsonFieldInfo field,
      int id,
      PrefixFields fields);

  abstract Reference writerRef();

  abstract Expression writeObjectStartPrimitive(
      JsonFieldInfo property, Expression value, Expression writer);

  abstract Expression tryWriteObjectStartString(
      JsonFieldInfo property, Expression value, Expression writer);

  abstract Expression writeNumberField(
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean longValue,
      boolean commaKnown,
      Expression index,
      Expression writer);

  abstract Expression writeStringField(
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean commaKnown,
      Expression index,
      Expression writer);

  abstract Expression writeFieldName(
      JsonFieldInfo property, int id, boolean commaKnown, Expression index, Expression writer);

  abstract Expression booleanFieldValue(
      int id, Expression value, boolean commaKnown, Expression index);

  abstract Expression enumFieldValue(
      int id, Expression value, boolean commaKnown, Expression index);

  abstract Expression utf16EnumFieldValue(
      int id, Expression value, boolean commaKnown, Expression index);

  abstract Expression writeExactScalar(JsonFieldInfo property, Expression value, Expression writer);

  abstract Expression writeExactArray(JsonFieldInfo property, Expression value, Expression writer);

  private static boolean usesWriteCodec(JsonFieldInfo property) {
    return JsonCodegen.usesWriteCodec(property);
  }

  static Reference fieldRef(String name, Class<?> type) {
    return Reference.fieldRef(name, TypeRef.of(type));
  }

  private static Expression eq(Expression left, Expression right) {
    return new Expression.Comparator("==", left, right, true);
  }

  private static Expression ne(Expression left, Expression right) {
    return new Expression.Comparator("!=", left, right, true);
  }

  private static void addGeneratedMethod(
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

  private static void addGeneratedConstructor(
      CodegenContext ctx, Expression expression, Object... params) {
    ctx.clearExprState();
    Code.ExprCode body = expression.genCode(ctx);
    String code = body.code();
    code = code == null ? "" : ctx.optimizeMethodCode(code);
    ctx.addConstructor(code, params);
  }

  String genWriterCode(
      JsonGeneratedCodecBuilder builder, Class<?> type, JsonFieldInfo[] properties) {
    ownerType = type;
    CodegenContext ctx = builder.context();
    ctx.addImports(writerType());
    ctx.implementsInterfaces(JsonCodegen.generatedCodecType(ctx, completeWriterType()));
    boolean objectStartFused = canFuseObjectStart(properties);
    PrefixFields prefixFields = prefixFields(properties, objectStartFused);
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (usesWriteInfo(property)) {
        ctx.addField(JsonFieldInfo.class, "wp" + i);
      }
      if (storesWriteCodec(property)) {
        ctx.addField(JsonCodegen.generatedCodecType(ctx, codecFieldType(property)), "w" + i);
      }
      if (usesPrefix(property)) {
        addPrefixFields(ctx, property, i, prefixFields);
      }
    }
    addGeneratedConstructor(
        ctx,
        writerConstructorExpression(properties, prefixFields),
        JsonFieldInfo[].class,
        "properties",
        JsonCodegen.generatedCodecArrayType(ctx, codecArrayType()),
        "codecs");
    String bodyCode;
    // Keep the sole nullable capability entry small for wide POJOs. Callers can inline its null
    // ownership without absorbing the independently compiled field graph into a container loop.
    if (properties.length >= splitMemberThreshold()) {
      String objectMethod = writeMethod() + "Object";
      addGeneratedMethod(
          ctx,
          "private final",
          objectMethod,
          writeExpression(
              builder, properties, objectStartFused, new Reference("object", TypeRef.of(type))),
          void.class,
          writerType(),
          "writer",
          type,
          "object");
      bodyCode = "this." + objectMethod + "(writer, (" + ctx.type(type) + ") value);\n";
    } else {
      ctx.clearExprState();
      Expression castObject =
          inline(
              new Expression.Cast(
                  new Reference("value", TypeRef.of(Object.class)), TypeRef.of(type)));
      Expression object =
          properties.length <= 1 ? castObject : new Expression.Variable("object", castObject);
      Code.ExprCode body =
          writeExpression(builder, properties, objectStartFused, object).genCode(ctx);
      bodyCode = body.code();
      bodyCode = bodyCode == null ? "" : ctx.optimizeMethodCode(bodyCode);
    }
    ctx.addMethod(
        "@Override public final",
        writeMethod(),
        "if (value == null) {\n" + "  writer.writeNull();\n" + "  return;\n" + "}\n" + bodyCode,
        void.class,
        writerType(),
        "writer",
        Object.class,
        "value");
    return ctx.genCode();
  }

  String genAnyWriterCode(
      JsonGeneratedCodecBuilder builder, Class<?> type, JsonFieldInfo[] properties, AnyInfo any) {
    ownerType = type;
    CodegenContext ctx = builder.context();
    ctx.addImports(writerType(), ObjectCodec.class, Map.class);
    ctx.implementsInterfaces(JsonCodegen.generatedCodecType(ctx, completeWriterType()));
    boolean objectStartFused = any.writeIndex() > 0 && canFuseObjectStart(properties);
    PrefixFields prefixFields = prefixFields(properties, objectStartFused);
    addWriterFields(ctx, properties, prefixFields);
    ctx.addField(ObjectCodec.class, "owner");
    if (any.writeGetter() != null) {
      addAnyGetterMethod(ctx, type, any);
    }
    boolean storesAnyWriter = storesAnyWriter(any);
    if (storesAnyWriter) {
      ctx.addField(JsonCodegen.generatedCodecType(ctx, completeWriterType()), "anyWriter");
      addGeneratedConstructor(
          ctx,
          anyWriterConstructorExpression(properties, prefixFields, true),
          ObjectCodec.class,
          "owner",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, codecArrayType()),
          "codecs",
          JsonCodegen.generatedCodecType(ctx, completeWriterType()),
          "anyWriter");
    } else {
      addGeneratedConstructor(
          ctx,
          anyWriterConstructorExpression(properties, prefixFields, false),
          ObjectCodec.class,
          "owner",
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, codecArrayType()),
          "codecs");
    }

    String bodyCode;
    if (properties.length >= splitMemberThreshold()) {
      String objectMethod = writeMethod() + "AnyObject";
      addGeneratedMethod(
          ctx,
          "private final",
          objectMethod,
          writeAnyExpression(
              builder,
              properties,
              any,
              objectStartFused,
              new Reference("object", TypeRef.of(type))),
          void.class,
          writerType(),
          "writer",
          type,
          "object");
      bodyCode = "this." + objectMethod + "(writer, (" + ctx.type(type) + ") value);\n";
    } else {
      ctx.clearExprState();
      Expression castObject =
          inline(
              new Expression.Cast(
                  new Reference("value", TypeRef.of(Object.class)), TypeRef.of(type)));
      Expression object =
          properties.length <= 1 ? castObject : new Expression.Variable("object", castObject);
      Code.ExprCode body =
          writeAnyExpression(builder, properties, any, objectStartFused, object).genCode(ctx);
      bodyCode = body.code();
      bodyCode = bodyCode == null ? "" : ctx.optimizeMethodCode(bodyCode);
    }
    ctx.addMethod(
        "@Override public final",
        writeMethod(),
        "if (value == null) {\n" + "  writer.writeNull();\n" + "  return;\n" + "}\n" + bodyCode,
        void.class,
        writerType(),
        "writer",
        Object.class,
        "value");

    return ctx.genCode();
  }

  String genUnwrappedWriterCode(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      ObjectCodec<?> owner,
      JsonUnwrappedInfo unwrapped) {
    ownerType = type;
    CodegenContext ctx = builder.context();
    AnyInfo any = owner.anyInfo();
    ctx.addImports(writerType());
    ctx.implementsInterfaces(JsonCodegen.generatedCodecType(ctx, completeWriterType()));
    JsonFieldInfo[] leaves = unwrapped.writeFields();
    IdentityHashMap<JsonFieldInfo, Integer> leafIndexes = new IdentityHashMap<>();
    for (int i = 0; i < leaves.length; i++) {
      leafIndexes.put(leaves[i], i);
    }
    PrefixFields prefixFields = new PrefixFields(leaves.length);
    for (int i = 0; i < leaves.length; i++) {
      if (usesPrefix(leaves[i])) {
        prefixFields.name[i] = true;
        prefixFields.comma[i] = true;
      }
    }
    addWriterFields(ctx, leaves, prefixFields);
    boolean writesAny = any != null && (any.writeField() != null || any.writeGetter() != null);
    if (!writesAny) {
      addGeneratedConstructor(
          ctx,
          writerConstructorExpression(leaves, prefixFields),
          JsonFieldInfo[].class,
          "properties",
          JsonCodegen.generatedCodecArrayType(ctx, codecArrayType()),
          "codecs");
    } else {
      ctx.addImports(ObjectCodec.class, Map.class);
      ctx.addField(ObjectCodec.class, "owner");
      if (any.writeGetter() != null) {
        addAnyGetterMethod(ctx, type, any);
      }
      if (storesAnyWriter(any)) {
        ctx.addField(JsonCodegen.generatedCodecType(ctx, completeWriterType()), "anyWriter");
        addGeneratedConstructor(
            ctx,
            anyWriterConstructorExpression(leaves, prefixFields, true),
            ObjectCodec.class,
            "owner",
            JsonFieldInfo[].class,
            "properties",
            JsonCodegen.generatedCodecArrayType(ctx, codecArrayType()),
            "codecs",
            JsonCodegen.generatedCodecType(ctx, completeWriterType()),
            "anyWriter");
      } else {
        addGeneratedConstructor(
            ctx,
            anyWriterConstructorExpression(leaves, prefixFields, false),
            ObjectCodec.class,
            "owner",
            JsonFieldInfo[].class,
            "properties",
            JsonCodegen.generatedCodecArrayType(ctx, codecArrayType()),
            "codecs");
      }
    }
    IdentityHashMap<Group, Integer> groupIndexes = new IdentityHashMap<>();
    List<Group> writeGroups = new ArrayList<>();
    collectUnwrappedGroups(unwrapped.writeEntries(), groupIndexes, writeGroups);
    boolean splitEntries =
        leaves.length >= splitMemberThreshold() || writeGroups.size() >= splitMemberThreshold();
    if (splitEntries) {
      // Group identity owns method indexes, while preorder owns deterministic source emission.
      addUnwrappedWriteMethods(
          builder, type, unwrapped.writeEntries(), any, leafIndexes, groupIndexes, writeGroups);
    } else {
      groupIndexes.clear();
    }
    ctx.clearExprState();
    Expression castObject =
        inline(
            new Expression.Cast(
                new Reference("value", TypeRef.of(Object.class)), TypeRef.of(type)));
    Expression object = new Expression.Variable("object", castObject);
    Reference writer = writerRef();
    Expression written = new Expression.Variable("written", Expression.Literal.ofInt(0));
    Expression.ListExpression body =
        new Expression.ListExpression(
            object, new Expression.Invoke(writer, "writeObjectStart"), written);
    if (splitEntries) {
      body.add(
          new Expression.Assign(
              written,
              invokeUnwrappedWrite(unwrappedWriteMethod(-1), writer, object, written, type)));
    } else {
      addUnwrappedWriteEntries(
          builder, body, unwrapped.writeEntries(), object, written, any, leafIndexes, groupIndexes);
    }
    body.add(new Expression.Invoke(writer, "writeObjectEnd"));
    Code.ExprCode bodyCode = body.genCode(ctx);
    String code = bodyCode.code();
    code = code == null ? "" : ctx.optimizeMethodCode(code);
    ctx.addMethod(
        "@Override public final",
        writeMethod(),
        "if (value == null) {\n" + "  writer.writeNull();\n" + "  return;\n" + "}\n" + code,
        void.class,
        writerType(),
        "writer",
        Object.class,
        "value");
    return ctx.genCode();
  }

  private static void collectUnwrappedGroups(
      WriteEntry[] entries, IdentityHashMap<Group, Integer> indexes, List<Group> groups) {
    ArrayDeque<WriteEntry> remaining = new ArrayDeque<>();
    addWriteEntries(remaining, entries);
    while (!remaining.isEmpty()) {
      WriteEntry entry = remaining.removeLast();
      if (entry.kind() != JsonUnwrappedInfo.GROUP) {
        continue;
      }
      Group group = entry.group();
      if (!indexes.containsKey(group)) {
        indexes.put(group, indexes.size());
        groups.add(group);
        addWriteEntries(remaining, group.writeEntries());
      }
    }
  }

  private static void addWriteEntries(ArrayDeque<WriteEntry> remaining, WriteEntry[] entries) {
    for (int i = entries.length - 1; i >= 0; i--) {
      remaining.addLast(entries[i]);
    }
  }

  private void addUnwrappedWriteMethods(
      JsonGeneratedCodecBuilder builder,
      Class<?> rootType,
      WriteEntry[] rootEntries,
      AnyInfo any,
      IdentityHashMap<JsonFieldInfo, Integer> leafIndexes,
      IdentityHashMap<Group, Integer> groupIndexes,
      List<Group> groups) {
    for (Group group : groups) {
      addUnwrappedWriteMethods(
          builder,
          groupIndexes.get(group),
          group.childCodec().type(),
          group.writeEntries(),
          any,
          leafIndexes,
          groupIndexes);
    }
    addUnwrappedWriteMethods(builder, -1, rootType, rootEntries, any, leafIndexes, groupIndexes);
  }

  private void addUnwrappedWriteMethods(
      JsonGeneratedCodecBuilder builder,
      int groupIndex,
      Class<?> objectType,
      WriteEntry[] entries,
      AnyInfo any,
      IdentityHashMap<JsonFieldInfo, Integer> leafIndexes,
      IdentityHashMap<Group, Integer> groupIndexes) {
    if (entries.length <= MAX_MEMBERS_PER_METHOD) {
      Reference writer = writerRef();
      Reference object = new Reference("object", TypeRef.of(objectType));
      Reference written = new Reference("written", TypeRef.of(int.class));
      Expression.ListExpression body = new Expression.ListExpression();
      addUnwrappedWriteEntries(
          builder,
          body,
          entries,
          0,
          entries.length,
          object,
          written,
          any,
          leafIndexes,
          groupIndexes);
      body.add(new Expression.Return(written));
      addGeneratedMethod(
          builder.context(),
          "private final",
          unwrappedWriteMethod(groupIndex),
          body,
          int.class,
          writerType(),
          "writer",
          objectType,
          "object",
          int.class,
          "written");
      return;
    }
    for (int start = 0; start < entries.length; start += MAX_MEMBERS_PER_METHOD) {
      int end = Math.min(start + MAX_MEMBERS_PER_METHOD, entries.length);
      Reference writer = writerRef();
      Reference object = new Reference("object", TypeRef.of(objectType));
      Reference written = new Reference("written", TypeRef.of(int.class));
      Expression.ListExpression body = new Expression.ListExpression();
      addUnwrappedWriteEntries(
          builder, body, entries, start, end, object, written, any, leafIndexes, groupIndexes);
      body.add(new Expression.Return(written));
      addGeneratedMethod(
          builder.context(),
          "private final",
          unwrappedWriteChunkMethod(groupIndex, start),
          body,
          int.class,
          writerType(),
          "writer",
          objectType,
          "object",
          int.class,
          "written");
    }
    Reference writer = writerRef();
    Reference object = new Reference("object", TypeRef.of(objectType));
    Reference written = new Reference("written", TypeRef.of(int.class));
    Expression.ListExpression dispatch = new Expression.ListExpression();
    for (int start = 0; start < entries.length; start += MAX_MEMBERS_PER_METHOD) {
      dispatch.add(
          new Expression.Assign(
              written,
              invokeUnwrappedWrite(
                  unwrappedWriteChunkMethod(groupIndex, start),
                  writer,
                  object,
                  written,
                  objectType)));
    }
    dispatch.add(new Expression.Return(written));
    addGeneratedMethod(
        builder.context(),
        "private final",
        unwrappedWriteMethod(groupIndex),
        dispatch,
        int.class,
        writerType(),
        "writer",
        objectType,
        "object",
        int.class,
        "written");
  }

  private Expression invokeUnwrappedWrite(
      String method, Expression writer, Expression object, Expression written, Class<?> type) {
    return new Expression.Invoke(
        new Reference("this", TypeRef.of(Object.class)),
        method,
        "",
        TypeRef.of(int.class),
        false,
        false,
        writer,
        new Expression.Cast(object, TypeRef.of(type)),
        written);
  }

  private String unwrappedWriteMethod(int groupIndex) {
    return writeMethod() + "Unwrapped" + (groupIndex < 0 ? "Root" : groupIndex);
  }

  private String unwrappedWriteChunkMethod(int groupIndex, int start) {
    return unwrappedWriteMethod(groupIndex) + "Chunk" + start;
  }

  private void addUnwrappedWriteEntries(
      JsonGeneratedCodecBuilder builder,
      Expression.ListExpression expressions,
      WriteEntry[] entries,
      Expression object,
      Expression written,
      AnyInfo any,
      IdentityHashMap<JsonFieldInfo, Integer> leafIndexes,
      IdentityHashMap<Group, Integer> groupIndexes) {
    addUnwrappedWriteEntries(
        builder,
        expressions,
        entries,
        0,
        entries.length,
        object,
        written,
        any,
        leafIndexes,
        groupIndexes);
  }

  private void addUnwrappedWriteEntries(
      JsonGeneratedCodecBuilder builder,
      Expression.ListExpression expressions,
      WriteEntry[] entries,
      int start,
      int end,
      Expression object,
      Expression written,
      AnyInfo any,
      IdentityHashMap<JsonFieldInfo, Integer> leafIndexes,
      IdentityHashMap<Group, Integer> groupIndexes) {
    for (int entryIndex = start; entryIndex < end; entryIndex++) {
      WriteEntry entry = entries[entryIndex];
      if (entry.kind() == JsonUnwrappedInfo.DIRECT) {
        JsonFieldInfo field = entry.field();
        int index = leafIndexes.get(field);
        expressions.add(writeProp(builder, field, index, false, written, object, writerRef()));
      } else if (entry.kind() == JsonUnwrappedInfo.ANY) {
        expressions.add(new Expression.Assign(written, writeAny(builder, any, object, written)));
      } else {
        Group group = entry.group();
        Integer existingIndex = groupIndexes.get(group);
        int index;
        if (existingIndex == null) {
          index = groupIndexes.size();
          groupIndexes.put(group, index);
        } else {
          index = existingIndex;
        }
        Class<?> childType = group.childCodec().type();
        Expression child =
            new Expression.Variable(
                "unwrapped" + index,
                cast(
                    inline(builder.unwrappedValue(group.declaration(), object)),
                    TypeRef.of(childType)));
        Expression.ListExpression present = new Expression.ListExpression();
        if (existingIndex != null) {
          present.add(
              new Expression.Assign(
                  written,
                  invokeUnwrappedWrite(
                      unwrappedWriteMethod(groupIndexes.get(group)),
                      writerRef(),
                      child,
                      written,
                      childType)));
        } else {
          addUnwrappedWriteEntries(
              builder,
              present,
              group.writeEntries(),
              child,
              written,
              any,
              leafIndexes,
              groupIndexes);
        }
        expressions.add(
            new Expression.ListExpression(
                child,
                new Expression.If(
                    ne(child, new Expression.Null(TypeRef.of(childType), false)), present)));
      }
    }
  }

  private void addAnyGetterMethod(CodegenContext ctx, Class<?> type, AnyInfo any) {
    String methodName = any.writeGetter().getName();
    ctx.addMethod(
        "private final",
        "getAnyMap",
        "try {\n"
            + "  return object."
            + methodName
            + "();\n"
            + "} catch (Throwable e) {\n"
            + "  if (e instanceof Error) {\n"
            + "    throw (Error) e;\n"
            + "  }\n"
            + "  throw owner.anyAccessorFailure(\""
            + methodName
            + "\", e);\n"
            + "}",
        Map.class,
        type,
        "object");
  }

  private void addWriterFields(
      CodegenContext ctx, JsonFieldInfo[] properties, PrefixFields prefixFields) {
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (usesWriteInfo(property)) {
        ctx.addField(JsonFieldInfo.class, "wp" + i);
      }
      if (storesWriteCodec(property)) {
        ctx.addField(JsonCodegen.generatedCodecType(ctx, codecFieldType(property)), "w" + i);
      }
      if (usesPrefix(property)) {
        addPrefixFields(ctx, property, i, prefixFields);
      }
    }
  }

  private Expression anyWriterConstructorExpression(
      JsonFieldInfo[] properties, PrefixFields prefixFields, boolean storesAnyWriter) {
    Expression.ListExpression expressions =
        new Expression.ListExpression(
            writerConstructorExpression(properties, prefixFields),
            new Expression.Assign(
                new Reference("this.owner", TypeRef.of(ObjectCodec.class)),
                new Reference("owner", TypeRef.of(ObjectCodec.class))));
    if (storesAnyWriter) {
      expressions.add(
          new Expression.Assign(
              new Reference("this.anyWriter", TypeRef.of(completeWriterType())),
              new Reference("anyWriter", TypeRef.of(completeWriterType()))));
    }
    return expressions;
  }

  private boolean storesAnyWriter(AnyInfo any) {
    return !any.valueTypeInfo().usesDefaultObjectCodec() || any.valueRawType() != ownerType;
  }

  static final class PrefixFields {
    final boolean[] name;
    final boolean[] comma;

    PrefixFields(int size) {
      name = new boolean[size];
      comma = new boolean[size];
    }
  }

  final boolean usesPrefix(JsonFieldInfo property) {
    JsonFieldKind kind = property.writeKind();
    return kind != JsonFieldKind.BOOLEAN && kind != JsonFieldKind.ENUM
        || property.writeNull() && !property.writeRawType().isPrimitive();
  }

  private static boolean usesWriteInfo(JsonFieldInfo property) {
    JsonFieldKind kind = property.writeKind();
    return kind == JsonFieldKind.BOOLEAN || kind == JsonFieldKind.ENUM;
  }

  private Expression writerConstructorExpression(
      JsonFieldInfo[] properties, PrefixFields prefixFields) {
    Expression.ListExpression expressions = new Expression.ListExpression();
    Reference propertiesRef = new Reference("properties", TypeRef.of(JsonFieldInfo[].class));
    Reference codecsRef = new Reference("codecs", TypeRef.of(codecArrayType()));
    for (int i = 0; i < properties.length; i++) {
      Expression id = Expression.Literal.ofInt(i);
      Expression property = new Expression.ArrayValue(propertiesRef, id);
      if (usesWriteInfo(properties[i])) {
        expressions.add(
            new Expression.Assign(
                new Reference("this.wp" + i, TypeRef.of(JsonFieldInfo.class)), property));
      }
      if (storesWriteCodec(properties[i])) {
        Class<?> codecType = codecFieldType(properties[i]);
        expressions.add(
            new Expression.Assign(
                new Reference("this.w" + i, TypeRef.of(codecType)),
                new Expression.Cast(
                    new Expression.ArrayValue(codecsRef, id), TypeRef.of(codecType))));
      }
      if (usesPrefix(properties[i])) {
        addPrefixAssignments(expressions, property, properties[i], i, prefixFields);
      }
    }
    return expressions;
  }

  private Expression writeExpression(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo[] properties,
      boolean objectStartFused,
      Expression object) {
    Reference writer = writerRef();
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(object);
    Expression index = null;
    int firstProperty = 0;
    if (!objectStartFused) {
      index = new Expression.Variable("index", Expression.Literal.ofInt(0));
      JsonFieldInfo first = properties.length == 0 ? null : properties[0];
      Expression value =
          first != null && !first.writeNull() && first.writeKind() == JsonFieldKind.STRING
              ? new Expression.Variable(
                  "v0", cast(inline(builder.fieldValue(first, object)), TypeRef.of(String.class)))
              : null;
      Expression fusedStart =
          value == null ? null : tryWriteObjectStartString(first, value, writer);
      if (fusedStart != null) {
        expressions.add(value);
        boolean hasRemainingProperties = properties.length > 1;
        if (hasRemainingProperties) {
          expressions.add(index);
        }
        Expression present = fusedStart;
        if (hasRemainingProperties) {
          present =
              new Expression.ListExpression(
                  fusedStart, new Expression.Assign(index, Expression.Literal.ofInt(1)));
        }
        expressions.add(
            new Expression.If(
                ne(value, new Expression.Null(TypeRef.of(String.class), false)),
                present,
                new Expression.Invoke(writer, "writeObjectStart")));
        firstProperty = 1;
      } else {
        expressions.add(new Expression.Invoke(writer, "writeObjectStart"));
        expressions.add(index);
      }
    }
    boolean commaKnown = objectStartFused;
    boolean splitMembers = properties.length >= splitMemberThreshold();
    List<Expression> memberGroup = splitMembers ? new ArrayList<>(MAX_MEMBERS_PER_METHOD) : null;
    for (int i = firstProperty; i < properties.length; i++) {
      Expression member;
      if (objectStartFused && i == 0) {
        member =
            writeObjectStartPrimitive(
                properties[i], inline(builder.fieldValue(properties[i], object)), writer);
      } else {
        member = writeProp(builder, properties[i], i, commaKnown, index, object, writer);
      }
      if (splitMembers && commaKnown) {
        memberGroup.add(member);
        if (memberGroup.size() == MAX_MEMBERS_PER_METHOD) {
          addMemberGroup(builder, expressions, memberGroup, object, writer);
        }
      } else {
        if (splitMembers) {
          addMemberGroup(builder, expressions, memberGroup, object, writer);
        }
        expressions.add(member);
      }
      if (properties[i].writeNull()) {
        commaKnown = true;
      }
    }
    if (splitMembers) {
      addMemberGroup(builder, expressions, memberGroup, object, writer, true);
    }
    expressions.add(new Expression.Invoke(writer, "writeObjectEnd"));
    return expressions;
  }

  private Expression writeAnyExpression(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo[] properties,
      AnyInfo any,
      boolean objectStartFused,
      Expression object) {
    Reference writer = writerRef();
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(object);
    Expression written = null;
    int firstProperty = 0;
    if (!objectStartFused) {
      written = new Expression.Variable("written", Expression.Literal.ofInt(0));
      JsonFieldInfo first = properties.length == 0 ? null : properties[0];
      Expression value =
          any.writeIndex() > 0
                  && first != null
                  && !first.writeNull()
                  && first.writeKind() == JsonFieldKind.STRING
              ? new Expression.Variable(
                  "v0", cast(inline(builder.fieldValue(first, object)), TypeRef.of(String.class)))
              : null;
      Expression fusedStart =
          value == null ? null : tryWriteObjectStartString(first, value, writer);
      if (fusedStart != null) {
        expressions.add(value);
        expressions.add(written);
        expressions.add(
            new Expression.If(
                ne(value, new Expression.Null(TypeRef.of(String.class), false)),
                new Expression.ListExpression(
                    fusedStart, new Expression.Assign(written, Expression.Literal.ofInt(1))),
                new Expression.Invoke(writer, "writeObjectStart")));
        firstProperty = 1;
      } else {
        expressions.add(new Expression.Invoke(writer, "writeObjectStart"));
        expressions.add(written);
      }
    }
    boolean commaKnown = objectStartFused;
    List<Expression> memberGroup =
        properties.length >= splitMemberThreshold()
            ? new ArrayList<>(MAX_MEMBERS_PER_METHOD)
            : null;
    for (int i = firstProperty; i <= properties.length; i++) {
      if (i == any.writeIndex()) {
        flushAnyMemberGroup(builder, expressions, memberGroup, object, writer);
        Expression state = written == null ? Expression.Literal.ofInt(1) : written;
        Expression dynamic = writeAny(builder, any, object, state);
        expressions.add(
            commaKnown || written == null ? dynamic : new Expression.Assign(written, dynamic));
      }
      if (i == properties.length) {
        break;
      }
      Expression member;
      if (objectStartFused && i == 0) {
        member =
            writeObjectStartPrimitive(
                properties[i], inline(builder.fieldValue(properties[i], object)), writer);
      } else {
        member = writeProp(builder, properties[i], i, commaKnown, written, object, writer);
      }
      if (memberGroup != null && commaKnown) {
        memberGroup.add(member);
        if (memberGroup.size() == MAX_MEMBERS_PER_METHOD) {
          addMemberGroup(builder, expressions, memberGroup, object, writer);
        }
      } else {
        flushAnyMemberGroup(builder, expressions, memberGroup, object, writer);
        expressions.add(member);
      }
      if (properties[i].writeNull()) {
        commaKnown = true;
      }
    }
    if (memberGroup != null) {
      addMemberGroup(builder, expressions, memberGroup, object, writer, true);
    }
    expressions.add(new Expression.Invoke(writer, "writeObjectEnd"));
    return expressions;
  }

  private void flushAnyMemberGroup(
      JsonGeneratedCodecBuilder builder,
      Expression.ListExpression expressions,
      List<Expression> memberGroup,
      Expression object,
      Reference writer) {
    if (memberGroup != null) {
      addMemberGroup(builder, expressions, memberGroup, object, writer, true);
    }
  }

  private Expression writeAny(
      JsonGeneratedCodecBuilder builder, AnyInfo any, Expression object, Expression written) {
    Expression mapValue;
    if (any.writeGetter() == null) {
      mapValue = builder.anyValue(any.writeField(), object);
    } else {
      mapValue =
          new Expression.Invoke(
              new Reference("this", TypeRef.of(Object.class)),
              "getAnyMap",
              "",
              TypeRef.of(Map.class),
              false,
              false,
              object);
    }
    Expression map =
        new Expression.Variable("anyMap", cast(inline(mapValue), TypeRef.of(Map.class)));
    return new Expression.ListExpression(
        map,
        new Expression.Invoke(
            fieldRef("owner", ObjectCodec.class),
            writeAnyMethod(),
            TypeRef.of(int.class),
            false,
            writerRef(),
            map,
            storesAnyWriter(any)
                ? fieldRef("anyWriter", completeWriterType())
                : new Reference("this", TypeRef.of(completeWriterType())),
            written));
  }

  private void addMemberGroup(
      JsonGeneratedCodecBuilder builder,
      Expression.ListExpression expressions,
      List<Expression> memberGroup,
      Expression object,
      Reference writer) {
    addMemberGroup(builder, expressions, memberGroup, object, writer, false);
  }

  private void addMemberGroup(
      JsonGeneratedCodecBuilder builder,
      Expression.ListExpression expressions,
      List<Expression> memberGroup,
      Expression object,
      Reference writer,
      boolean inlineSmallTail) {
    if (memberGroup.isEmpty()) {
      return;
    }
    if (memberGroup.size() == 1 || inlineSmallTail && memberGroup.size() <= INLINE_TAIL_MEMBERS) {
      for (Expression member : memberGroup) {
        expressions.add(member);
      }
      memberGroup.clear();
      return;
    }
    LinkedHashSet<Expression> cutPoints = new LinkedHashSet<>();
    cutPoints.add(object);
    cutPoints.add(writer);
    expressions.add(
        ExpressionOptimizer.invokeGenerated(
            builder.context(),
            cutPoints,
            new Expression.ListExpression(new ArrayList<>(memberGroup)),
            memberGroupMethod(),
            false));
    memberGroup.clear();
  }

  private static boolean canFuseObjectStart(JsonFieldInfo[] properties) {
    if (properties.length == 0 || !properties[0].writeRawType().isPrimitive()) {
      return false;
    }
    switch (properties[0].writeKind()) {
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
        return true;
      default:
        return false;
    }
  }

  private Expression writeProp(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      boolean commaKnown,
      Expression index,
      Expression object,
      Expression writer) {
    Class<?> rawType = property.writeRawType();
    if (rawType.isPrimitive()) {
      // Primitive members cannot be null and this path consumes the access once. Nullable
      // references stay cached below because their null check and write must share one value.
      Expression fieldValue = inline(builder.fieldValue(property, object));
      if (property.writeKind() == JsonFieldKind.OBJECT) {
        return new Expression.ListExpression(
            writeFieldName(property, id, commaKnown, index, writer),
            writeCodec(property, id, fieldValue, writer));
      }
      return writePrimitive(property, id, fieldValue, commaKnown, index, writer);
    }
    Expression value =
        new Expression.Variable(
            "v" + id, cast(inline(builder.fieldValue(property, object)), TypeRef.of(rawType)));
    Expression nullValue = new Expression.Null(TypeRef.of(rawType), false);
    if (property.writeNull()) {
      JsonFieldKind kind = property.writeKind();
      boolean onlyCodec =
          kind == JsonFieldKind.MAP
              || kind == JsonFieldKind.ARRAY && writeExactArray(property, value, writer) == null
              || kind == JsonFieldKind.OBJECT && writeExactScalar(property, value, writer) == null
              || kind == JsonFieldKind.COLLECTION
                  && !JsonCodegen.writesStringCollectionDirectly(property);
      if (onlyCodec) {
        return new Expression.ListExpression(
            value,
            writeFieldName(property, id, commaKnown, index, writer),
            writeCodec(property, id, value, writer));
      }
      if (isPrefixValue(property.writeKind())) {
        return new Expression.ListExpression(
            value,
            new Expression.If(
                eq(value, nullValue),
                new Expression.ListExpression(
                    writeFieldName(property, id, commaKnown, index, writer),
                    new Expression.Invoke(writer, "writeNull")),
                writeValue(property, id, value, commaKnown, index, writer)));
      }
      return new Expression.ListExpression(
          value,
          writeFieldName(property, id, commaKnown, index, writer),
          new Expression.If(
              eq(value, nullValue),
              new Expression.Invoke(writer, "writeNull"),
              writeValue(property, id, value, true, index, writer)));
    }
    Expression write =
        isPrefixValue(property.writeKind())
            ? writeValue(property, id, value, commaKnown, index, writer)
            : new Expression.ListExpression(
                writeFieldName(property, id, commaKnown, index, writer),
                writeValue(property, id, value, true, index, writer));
    return new Expression.ListExpression(value, new Expression.If(ne(value, nullValue), write));
  }

  private Expression writePrimitive(
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean commaKnown,
      Expression index,
      Expression writer) {
    switch (property.writeKind()) {
      case BOOLEAN:
        return writeRawFieldValue(
            commaKnown, index, booleanFieldValue(id, value, commaKnown, index), writer);
      case BYTE:
      case SHORT:
      case INT:
        return writeNumberField(property, id, value, false, commaKnown, index, writer);
      case LONG:
        return writeNumberField(property, id, value, true, commaKnown, index, writer);
      default:
        return new Expression.ListExpression(
            writeFieldName(property, id, commaKnown, index, writer),
            writePrimitiveScalar(property.writeKind(), value, writer));
    }
  }

  private Expression writeValue(
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean commaKnown,
      Expression index,
      Expression writer) {
    if (property.writesRawString()) {
      return new Expression.ListExpression(
          writeFieldName(property, id, commaKnown, index, writer),
          new Expression.Invoke(writer, "writeRawValue", value));
    }
    JsonFieldKind kind = property.writeKind();
    switch (kind) {
      case BOOLEAN:
        return writeRawFieldValue(
            commaKnown,
            index,
            booleanFieldValue(
                id,
                new Expression.Invoke(value, "booleanValue", TypeRef.of(boolean.class)).inline(),
                commaKnown,
                index),
            writer);
      case BYTE:
      case SHORT:
      case INT:
        return writeNumberField(
            property,
            id,
            new Expression.Invoke(value, "intValue", TypeRef.of(int.class)).inline(),
            false,
            commaKnown,
            index,
            writer);
      case LONG:
        return writeNumberField(
            property,
            id,
            new Expression.Invoke(value, "longValue", TypeRef.of(long.class)).inline(),
            true,
            commaKnown,
            index,
            writer);
      case STRING:
        return writeStringField(property, id, value, commaKnown, index, writer);
      case ENUM:
        return writeRawFieldValue(
            commaKnown,
            index,
            enumFieldValue(id, value, commaKnown, index),
            utf16EnumFieldValue(id, value, commaKnown, index),
            writer);
      case FLOAT:
      case DOUBLE:
      case CHAR:
        return writeScalar(kind, value, writer);
      case ARRAY:
        Expression array = writeExactArray(property, value, writer);
        return array == null ? writeCodec(property, id, value, writer) : array;
      case MAP:
        return writeCodec(property, id, value, writer);
      case COLLECTION:
        if (JsonCodegen.writesStringCollectionDirectly(property)) {
          return writeStringCollection(value, writer);
        }
        return writeCodec(property, id, value, writer);
      case OBJECT:
        Expression scalar = writeExactScalar(property, value, writer);
        return scalar == null ? writeCodec(property, id, value, writer) : scalar;
      default:
        return writeCodec(property, id, value, writer);
    }
  }

  private static Expression writeRawFieldValue(
      boolean commaKnown, Expression index, Expression value, Expression writer) {
    return writeRawFieldValue(commaKnown, index, value, null, writer);
  }

  private static Expression writeRawFieldValue(
      boolean commaKnown,
      Expression index,
      Expression value,
      Expression utf16Value,
      Expression writer) {
    Expression write =
        utf16Value == null
            ? new Expression.Invoke(writer, "writeRawValue", value)
            : new Expression.Invoke(writer, "writeRawValue", value, utf16Value);
    Expression.ListExpression expressions = new Expression.ListExpression(write);
    if (!commaKnown) {
      expressions.add(increment(index));
    }
    return expressions;
  }

  static long packedPrefixWord(byte[] prefix, int offset) {
    long word = 0;
    int end = Math.min(prefix.length, offset + Long.BYTES);
    for (int i = offset; i < end; i++) {
      word |= (prefix[i] & 0xffL) << ((i - offset) << 3);
    }
    return word;
  }

  private Expression writeCodec(
      JsonFieldInfo property, int id, Expression value, Expression writer) {
    boolean object = property.writeTypeInfo().usesDefaultObjectCodec();
    Expression codec =
        object && property.writeRawType() == ownerType
            ? new Reference("this", TypeRef.of(completeWriterType()))
            : fieldRef("w" + id, codecFieldType(property));
    return new Expression.Invoke(codec, writeMethod(), writer, value);
  }

  private boolean storesWriteCodec(JsonFieldInfo property) {
    return usesWriteCodec(property)
        && (!property.writeTypeInfo().usesDefaultObjectCodec()
            || property.writeRawType() != ownerType);
  }

  private static Expression writeStringCollection(Expression value, Expression writer) {
    return new Expression.Invoke(writer, "writeStringCollection", value);
  }

  private Expression writeScalar(JsonFieldKind kind, Expression value, Expression writer) {
    switch (kind) {
      case FLOAT:
        return new Expression.Invoke(
            writer,
            "writeFloat",
            new Expression.Invoke(value, "floatValue", TypeRef.of(float.class)).inline());
      case DOUBLE:
        return new Expression.Invoke(
            writer,
            "writeDouble",
            new Expression.Invoke(value, "doubleValue", TypeRef.of(double.class)).inline());
      case CHAR:
        return new Expression.Invoke(
            writer,
            "writeChar",
            new Expression.Invoke(value, "charValue", TypeRef.of(char.class)).inline());
      default:
        throw new ForyJsonException("Unsupported generated scalar kind " + kind);
    }
  }

  private Expression writePrimitiveScalar(JsonFieldKind kind, Expression value, Expression writer) {
    switch (kind) {
      case FLOAT:
        return new Expression.Invoke(writer, "writeFloat", value);
      case DOUBLE:
        return new Expression.Invoke(writer, "writeDouble", value);
      case CHAR:
        return new Expression.Invoke(writer, "writeChar", value);
      default:
        throw new ForyJsonException("Unsupported generated primitive kind " + kind);
    }
  }

  private static Expression commaFlag(boolean commaKnown, Expression index) {
    return commaKnown ? Expression.Literal.True : ne(index, Expression.Literal.ofInt(0));
  }

  static Expression increment(Expression value) {
    return new Expression.Assign(value, add(value, Expression.Literal.ofInt(1)));
  }

  private static boolean isPrefixValue(JsonFieldKind kind) {
    return kind == JsonFieldKind.BOOLEAN
        || kind == JsonFieldKind.BYTE
        || kind == JsonFieldKind.SHORT
        || kind == JsonFieldKind.INT
        || kind == JsonFieldKind.LONG
        || kind == JsonFieldKind.STRING
        || kind == JsonFieldKind.ENUM;
  }

  static Expression fieldValue(
      int id, String method, Expression value, boolean commaKnown, Expression index) {
    return new Expression.Invoke(
            fieldRef("wp" + id, JsonFieldInfo.class),
            method,
            TypeRef.of(byte[].class),
            value,
            commaFlag(commaKnown, index))
        .inline();
  }
}
