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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.fory.codegen.Code;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.ExpressionOptimizer;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.ArrayCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.codec.StringObjectWriter;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf8ObjectWriter;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;

/**
 * Shared generation mechanics for concrete String and UTF-8 object-writer capabilities.
 *
 * <p>The nested generators own representation-specific field prefixes, scalar stores, and child
 * capability types. This base shares source-construction algorithms only after the concrete writer
 * is selected; it is not a runtime output mode. Generated writers retain precomputed field tokens
 * and concrete child capabilities, fuse safe object prefixes, and split wide objects into bounded
 * methods to protect compiler and inlining budgets without adding per-field dispatch.
 */
abstract class JsonWriterCodegen {
  private static final int MIN_STRING_SPLIT_MEMBERS = 10;
  private static final int MIN_UTF8_SPLIT_MEMBERS = 12;
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

  abstract Class<?> objectWriterType();

  abstract Class<?> completeWriterType();

  abstract String writeMethod();

  abstract String membersMethod();

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

  private static Reference fieldRef(String name, Class<?> type) {
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
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean objectMembers) {
    ownerType = type;
    CodegenContext ctx = builder.context();
    ctx.addImports(writerType());
    ctx.implementsInterfaces(
        JsonCodegen.generatedCodecType(
            ctx, objectMembers ? objectWriterType() : completeWriterType()));
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
    if (objectMembers) {
      ctx.clearExprState();
      Expression memberObject =
          inline(
              new Expression.Cast(
                  new Reference("value", TypeRef.of(Object.class)), TypeRef.of(type)));
      Expression memberValue =
          properties.length <= 1 ? memberObject : new Expression.Variable("object", memberObject);
      Code.ExprCode membersBody =
          writeMembersExpression(builder, properties, memberValue).genCode(ctx);
      String membersCode = membersBody.code();
      membersCode = membersCode == null ? "" : ctx.optimizeMethodCode(membersCode);
      ctx.addMethod(
          "@Override public final",
          membersMethod(),
          membersCode,
          void.class,
          writerType(),
          "writer",
          Object.class,
          "value",
          int.class,
          "written");
    }
    return ctx.genCode();
  }

  String genAnyWriterCode(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      AnyInfo any,
      boolean objectMembers) {
    ownerType = type;
    CodegenContext ctx = builder.context();
    ctx.addImports(writerType(), ObjectCodec.class, Map.class);
    ctx.implementsInterfaces(
        JsonCodegen.generatedCodecType(
            ctx, objectMembers ? objectWriterType() : completeWriterType()));
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

    if (objectMembers) {
      String anyMembersMethod = membersMethod() + "Any";
      addGeneratedMethod(
          ctx,
          "private final",
          anyMembersMethod,
          writeAnyMembersExpression(
              builder,
              properties,
              any,
              new Reference("object", TypeRef.of(type)),
              new Reference("written", TypeRef.of(int.class)),
              new Reference("hasDiscriminator", TypeRef.of(boolean.class)),
              new Reference("discriminatorHash", TypeRef.of(long.class))),
          void.class,
          writerType(),
          "writer",
          type,
          "object",
          int.class,
          "written",
          boolean.class,
          "hasDiscriminator",
          long.class,
          "discriminatorHash");
      String typeName = ctx.type(type);
      ctx.addMethod(
          "@Override public final",
          membersMethod(),
          "this." + anyMembersMethod + "(writer, (" + typeName + ") value, written, false, 0L);",
          void.class,
          writerType(),
          "writer",
          Object.class,
          "value",
          int.class,
          "written");
      ctx.addMethod(
          "@Override public final",
          membersMethod(),
          "this."
              + anyMembersMethod
              + "(writer, ("
              + typeName
              + ") value, written, true, discriminatorHash);",
          void.class,
          writerType(),
          "writer",
          Object.class,
          "value",
          int.class,
          "written",
          long.class,
          "discriminatorHash");
    }
    return ctx.genCode();
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

  private Expression writeMembersExpression(
      JsonGeneratedCodecBuilder builder, JsonFieldInfo[] properties, Expression object) {
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(object);
    Expression index = new Reference("written", TypeRef.of(int.class));
    Reference writer = writerRef();
    boolean splitMembers = properties.length >= splitMemberThreshold();
    List<Expression> group = splitMembers ? new ArrayList<>(MAX_MEMBERS_PER_METHOD) : null;
    for (int i = 0; i < properties.length; i++) {
      Expression member = writeProp(builder, properties[i], i, true, index, object, writer);
      if (splitMembers) {
        group.add(member);
        if (group.size() == MAX_MEMBERS_PER_METHOD) {
          addMemberGroup(builder, expressions, group, object, writer);
        }
      } else {
        expressions.add(member);
      }
    }
    if (splitMembers) {
      addMemberGroup(builder, expressions, group, object, writer, true);
    }
    return expressions;
  }

  final PrefixFields stringPrefixFields(JsonFieldInfo[] properties, boolean objectStartFused) {
    PrefixFields fields = new PrefixFields(properties.length);
    boolean commaKnown = objectStartFused;
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (usesPrefix(property)) {
        if (objectStartFused && i == 0) {
          if (!canPackStringUtf16Prefix(property, false)) {
            fields.name[i] = true;
          }
        } else {
          markStringUtf16PrefixField(property, commaKnown, fields, i);
        }
      }
      if (property.writeNull()) {
        commaKnown = true;
      }
    }
    return fields;
  }

  private static void markStringUtf16PrefixField(
      JsonFieldInfo property, boolean commaKnown, PrefixFields fields, int id) {
    if (!commaKnown) {
      fields.name[id] = true;
      fields.comma[id] = true;
      return;
    }
    if (!canPackStringUtf16Prefix(property, true)) {
      fields.comma[id] = true;
    }
  }

  final PrefixFields utf8PrefixFields(JsonFieldInfo[] properties, boolean objectStartFused) {
    PrefixFields fields = new PrefixFields(properties.length);
    boolean commaKnown = objectStartFused;
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (usesPrefix(property)) {
        if (i == 0
            && !objectStartFused
            && !property.writeNull()
            && Utf8Generator.canPackObjectStartString(property)) {
          // The generated first-field branch consumes neither ordinary prefix field.
        } else if (objectStartFused && i == 0) {
          if (!canPackPrefix(property, false)) {
            fields.name[i] = true;
          }
        } else if (!commaKnown) {
          if (!canUsePackedDynamicPrefix(property)
              || !canPackSinglePrefix(property, false)
              || !canPackSinglePrefix(property, true)) {
            fields.name[i] = true;
            fields.comma[i] = true;
          }
        } else if (!canPackPrefix(property, true)) {
          fields.comma[i] = true;
        }
      }
      if (property.writeNull()) {
        commaKnown = true;
      }
    }
    return fields;
  }

  private boolean canUsePackedDynamicPrefix(JsonFieldInfo property) {
    if (property.writeNull() && !property.writeRawType().isPrimitive()) {
      return false;
    }
    switch (property.writeKind()) {
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
      case STRING:
        return true;
      default:
        return false;
    }
  }

  private static final class PrefixFields {
    private final boolean[] name;
    private final boolean[] comma;

    private PrefixFields(int size) {
      name = new boolean[size];
      comma = new boolean[size];
    }
  }

  private boolean usesPrefix(JsonFieldInfo property) {
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
        Expression dynamic =
            writeAny(
                builder,
                any,
                object,
                state,
                Expression.Literal.False,
                Expression.Literal.ofLong(0));
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

  private Expression writeAnyMembersExpression(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo[] properties,
      AnyInfo any,
      Expression object,
      Expression written,
      Expression hasDiscriminator,
      Expression discriminatorHash) {
    Expression.ListExpression expressions = new Expression.ListExpression(object);
    Reference writer = writerRef();
    List<Expression> memberGroup =
        properties.length >= splitMemberThreshold()
            ? new ArrayList<>(MAX_MEMBERS_PER_METHOD)
            : null;
    for (int i = 0; i <= properties.length; i++) {
      if (i == any.writeIndex()) {
        flushAnyMemberGroup(builder, expressions, memberGroup, object, writer);
        expressions.add(
            new Expression.Assign(
                written,
                writeAny(builder, any, object, written, hasDiscriminator, discriminatorHash)));
      }
      if (i == properties.length) {
        break;
      }
      Expression member = writeProp(builder, properties[i], i, true, written, object, writer);
      if (memberGroup == null) {
        expressions.add(member);
      } else {
        memberGroup.add(member);
        if (memberGroup.size() == MAX_MEMBERS_PER_METHOD) {
          addMemberGroup(builder, expressions, memberGroup, object, writer);
        }
      }
    }
    if (memberGroup != null) {
      addMemberGroup(builder, expressions, memberGroup, object, writer, true);
    }
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
      JsonGeneratedCodecBuilder builder,
      AnyInfo any,
      Expression object,
      Expression written,
      Expression hasDiscriminator,
      Expression discriminatorHash) {
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
            written,
            hasDiscriminator,
            discriminatorHash));
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
            membersMethod(),
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

  private static Expression[] packedPrefixArgs(
      JsonFieldInfo property, boolean comma, Expression... extraArgs) {
    byte[] prefix = comma ? property.utf8CommaNamePrefix() : property.utf8NamePrefix();
    Expression[] args = new Expression[3 + extraArgs.length];
    args[0] = Expression.Literal.ofLong(packedPrefixWord(prefix, 0));
    args[1] = Expression.Literal.ofLong(packedPrefixWord(prefix, Long.BYTES));
    args[2] = Expression.Literal.ofInt(prefix.length);
    System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
    return args;
  }

  private static Expression[] objectPackedPrefixArgs(JsonFieldInfo property, Expression value) {
    byte[] namePrefix = property.utf8NamePrefix();
    byte[] prefix = new byte[namePrefix.length + 1];
    prefix[0] = '{';
    System.arraycopy(namePrefix, 0, prefix, 1, namePrefix.length);
    return new Expression[] {
      Expression.Literal.ofLong(packedPrefixWord(prefix, 0)),
      Expression.Literal.ofLong(packedPrefixWord(prefix, Long.BYTES)),
      Expression.Literal.ofInt(prefix.length),
      value
    };
  }

  private static Expression[] stringPackedPrefixArgs(
      JsonFieldInfo property, int id, boolean comma, Expression... extraArgs) {
    byte[] prefix =
        comma ? property.stringUtf16CommaNamePrefix() : property.stringUtf16NamePrefix();
    Expression[] args = new Expression[6 + extraArgs.length];
    args[0] = fieldRef((comma ? "sc" : "s") + id, byte[].class);
    args[1] = Expression.Literal.ofLong(packedPrefixWord(prefix, 0));
    args[2] = Expression.Literal.ofLong(packedPrefixWord(prefix, Long.BYTES));
    args[3] = Expression.Literal.ofLong(packedPrefixWord(prefix, Long.BYTES * 2));
    args[4] = Expression.Literal.ofLong(packedPrefixWord(prefix, Long.BYTES * 3));
    args[5] = Expression.Literal.ofInt(prefix.length);
    System.arraycopy(extraArgs, 0, args, 6, extraArgs.length);
    return args;
  }

  private static Expression[] packedDynamicPrefixArgs(
      JsonFieldInfo property, Expression index, Expression... extraArgs) {
    byte[] namePrefix = property.utf8NamePrefix();
    byte[] commaPrefix = property.utf8CommaNamePrefix();
    Expression[] args = new Expression[5 + extraArgs.length];
    args[0] = Expression.Literal.ofLong(packedPrefixWord(namePrefix, 0));
    args[1] = Expression.Literal.ofLong(packedPrefixWord(commaPrefix, 0));
    args[2] = Expression.Literal.ofInt(namePrefix.length);
    args[3] = Expression.Literal.ofInt(commaPrefix.length);
    args[4] = index;
    System.arraycopy(extraArgs, 0, args, 5, extraArgs.length);
    return args;
  }

  private static boolean canPackPrefix(JsonFieldInfo property, boolean comma) {
    int length = comma ? property.utf8CommaNamePrefix().length : property.utf8NamePrefix().length;
    return length <= Long.BYTES * 2;
  }

  private static boolean canPackStringUtf16Prefix(JsonFieldInfo property, boolean comma) {
    int length =
        comma
            ? property.stringUtf16CommaNamePrefix().length
            : property.stringUtf16NamePrefix().length;
    return length <= Long.BYTES * 4;
  }

  private static boolean canPackSinglePrefix(JsonFieldInfo property, boolean comma) {
    int length = comma ? property.utf8CommaNamePrefix().length : property.utf8NamePrefix().length;
    return length <= Long.BYTES;
  }

  private static long packedPrefixWord(byte[] prefix, int offset) {
    long word = 0;
    int end = Math.min(prefix.length, offset + Long.BYTES);
    for (int i = offset; i < end; i++) {
      word |= (prefix[i] & 0xffL) << ((i - offset) << 3);
    }
    return word;
  }

  private static Expression stringUtf16EnumFieldValue(
      int id, Expression value, boolean commaKnown, Expression index) {
    return new Expression.Invoke(
            fieldRef("wp" + id, JsonFieldInfo.class),
            "stringUtf16EnumFieldValue",
            TypeRef.of(byte[].class),
            value,
            commaFlag(commaKnown, index))
        .inline();
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

  private static Reference utf16PrefixRef(boolean comma, int id) {
    return fieldRef((comma ? "sc16" : "s16") + id, byte[].class);
  }

  private static Expression commaFlag(boolean commaKnown, Expression index) {
    return commaKnown ? Expression.Literal.True : ne(index, Expression.Literal.ofInt(0));
  }

  private static Expression increment(Expression value) {
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

  static final class StringGenerator extends JsonWriterCodegen {
    StringGenerator(JsonCodegen codegen) {
      super(codegen);
    }

    @Override
    Class<?> codecFieldType(JsonFieldInfo property) {
      return codegen.stringWriterFieldType(property.writeTypeInfo());
    }

    @Override
    Class<?> writerType() {
      return StringJsonWriter.class;
    }

    @Override
    Class<?> codecArrayType() {
      return StringWriterCodec[].class;
    }

    @Override
    Class<?> objectWriterType() {
      return StringObjectWriter.class;
    }

    @Override
    Class<?> completeWriterType() {
      return StringWriterCodec.class;
    }

    @Override
    String writeMethod() {
      return "writeString";
    }

    @Override
    String membersMethod() {
      return "writeStringMembers";
    }

    @Override
    String writeAnyMethod() {
      return "writeStringAny";
    }

    @Override
    int splitMemberThreshold() {
      return MIN_STRING_SPLIT_MEMBERS;
    }

    @Override
    PrefixFields prefixFields(JsonFieldInfo[] properties, boolean objectStartFused) {
      return stringPrefixFields(properties, objectStartFused);
    }

    @Override
    void addPrefixFields(CodegenContext ctx, JsonFieldInfo property, int id, PrefixFields fields) {
      ctx.addField(byte[].class, "s" + id);
      ctx.addField(byte[].class, "sc" + id);
      if (fields.name[id]) {
        ctx.addField(byte[].class, "s16" + id);
      }
      if (fields.comma[id]) {
        ctx.addField(byte[].class, "sc16" + id);
      }
    }

    @Override
    void addPrefixAssignments(
        Expression.ListExpression expressions,
        Expression property,
        JsonFieldInfo field,
        int id,
        PrefixFields fields) {
      expressions.add(
          new Expression.Assign(
              stringPrefixRef(false, id),
              new Expression.Invoke(property, "stringNamePrefix", TypeRef.of(byte[].class))
                  .inline()));
      expressions.add(
          new Expression.Assign(
              stringPrefixRef(true, id),
              new Expression.Invoke(property, "stringCommaNamePrefix", TypeRef.of(byte[].class))
                  .inline()));
      if (fields.name[id]) {
        expressions.add(
            new Expression.Assign(
                utf16PrefixRef(false, id),
                new Expression.Invoke(property, "stringUtf16NamePrefix", TypeRef.of(byte[].class))
                    .inline()));
      }
      if (fields.comma[id]) {
        expressions.add(
            new Expression.Assign(
                utf16PrefixRef(true, id),
                new Expression.Invoke(
                        property, "stringUtf16CommaNamePrefix", TypeRef.of(byte[].class))
                    .inline()));
      }
    }

    @Override
    Reference writerRef() {
      return new Reference("writer", TypeRef.of(StringJsonWriter.class));
    }

    @Override
    Expression writeObjectStartPrimitive(
        JsonFieldInfo property, Expression value, Expression writer) {
      String method;
      switch (property.writeKind()) {
        case BYTE:
        case SHORT:
        case INT:
          method = "writeObjectStartWithIntField";
          break;
        case LONG:
          method = "writeObjectStartWithLongField";
          break;
        default:
          throw new ForyJsonException(
              "Unsupported generated object-start kind " + property.writeKind());
      }
      if (canPackStringUtf16Prefix(property, false)) {
        return new Expression.Invoke(
            writer, method, stringPackedPrefixArgs(property, 0, false, value));
      }
      return new Expression.Invoke(
          writer, method, stringPrefixRef(false, 0), utf16PrefixRef(false, 0), value);
    }

    @Override
    Expression tryWriteObjectStartString(
        JsonFieldInfo property, Expression value, Expression writer) {
      return null;
    }

    @Override
    Expression writeNumberField(
        JsonFieldInfo property,
        int id,
        Expression value,
        boolean longValue,
        boolean commaKnown,
        Expression index,
        Expression writer) {
      String method = longValue ? "writeLongField" : "writeIntField";
      if (commaKnown) {
        if (canPackStringUtf16Prefix(property, true)) {
          return new Expression.Invoke(
              writer, method, stringPackedPrefixArgs(property, id, true, value));
        }
        return new Expression.Invoke(
            writer, method, stringPrefixRef(true, id), utf16PrefixRef(true, id), value);
      }
      Expression.ListExpression expressions =
          new Expression.ListExpression(
              new Expression.Invoke(
                  writer,
                  method,
                  stringPrefixRef(false, id),
                  stringPrefixRef(true, id),
                  utf16PrefixRef(false, id),
                  utf16PrefixRef(true, id),
                  index,
                  value));
      expressions.add(increment(index));
      return expressions;
    }

    @Override
    Expression writeStringField(
        JsonFieldInfo property,
        int id,
        Expression value,
        boolean commaKnown,
        Expression index,
        Expression writer) {
      if (commaKnown) {
        if (canPackStringUtf16Prefix(property, true)) {
          return new Expression.Invoke(
              writer, "writeStringField", stringPackedPrefixArgs(property, id, true, value));
        }
        return new Expression.Invoke(
            writer, "writeStringField", stringPrefixRef(true, id), utf16PrefixRef(true, id), value);
      }
      Expression.ListExpression expressions =
          new Expression.ListExpression(
              new Expression.Invoke(
                  writer,
                  "writeStringField",
                  stringPrefixRef(false, id),
                  stringPrefixRef(true, id),
                  utf16PrefixRef(false, id),
                  utf16PrefixRef(true, id),
                  index,
                  value));
      expressions.add(increment(index));
      return expressions;
    }

    @Override
    Expression writeFieldName(
        JsonFieldInfo property, int id, boolean commaKnown, Expression index, Expression writer) {
      if (commaKnown) {
        if (canPackStringUtf16Prefix(property, true)) {
          return new Expression.Invoke(
              writer, "writeRawValue", stringPackedPrefixArgs(property, id, true));
        }
        return new Expression.Invoke(
            writer, "writeRawValue", stringPrefixRef(true, id), utf16PrefixRef(true, id));
      }
      Expression.ListExpression expressions =
          new Expression.ListExpression(
              new Expression.Invoke(
                  writer,
                  "writeRawValue",
                  stringPrefixRef(false, id),
                  stringPrefixRef(true, id),
                  utf16PrefixRef(false, id),
                  utf16PrefixRef(true, id),
                  index));
      expressions.add(increment(index));
      return expressions;
    }

    @Override
    Expression booleanFieldValue(int id, Expression value, boolean commaKnown, Expression index) {
      return fieldValue(id, "stringBooleanFieldValue", value, commaKnown, index);
    }

    @Override
    Expression enumFieldValue(int id, Expression value, boolean commaKnown, Expression index) {
      return fieldValue(id, "stringEnumFieldValue", value, commaKnown, index);
    }

    @Override
    Expression utf16EnumFieldValue(int id, Expression value, boolean commaKnown, Expression index) {
      return stringUtf16EnumFieldValue(id, value, commaKnown, index);
    }

    @Override
    Expression writeExactScalar(JsonFieldInfo property, Expression value, Expression writer) {
      return null;
    }

    @Override
    Expression writeExactArray(JsonFieldInfo property, Expression value, Expression writer) {
      return null;
    }

    private static Reference stringPrefixRef(boolean comma, int id) {
      return fieldRef((comma ? "sc" : "s") + id, byte[].class);
    }
  }

  static final class Utf8Generator extends JsonWriterCodegen {
    Utf8Generator(JsonCodegen codegen) {
      super(codegen);
    }

    @Override
    Class<?> codecFieldType(JsonFieldInfo property) {
      return codegen.utf8WriterFieldType(property.writeTypeInfo());
    }

    @Override
    Class<?> writerType() {
      return Utf8JsonWriter.class;
    }

    @Override
    Class<?> codecArrayType() {
      return Utf8WriterCodec[].class;
    }

    @Override
    Class<?> objectWriterType() {
      return Utf8ObjectWriter.class;
    }

    @Override
    Class<?> completeWriterType() {
      return Utf8WriterCodec.class;
    }

    @Override
    String writeMethod() {
      return "writeUtf8";
    }

    @Override
    String membersMethod() {
      return "writeUtf8Members";
    }

    @Override
    String writeAnyMethod() {
      return "writeUtf8Any";
    }

    @Override
    int splitMemberThreshold() {
      return MIN_UTF8_SPLIT_MEMBERS;
    }

    @Override
    PrefixFields prefixFields(JsonFieldInfo[] properties, boolean objectStartFused) {
      return utf8PrefixFields(properties, objectStartFused);
    }

    @Override
    void addPrefixFields(CodegenContext ctx, JsonFieldInfo property, int id, PrefixFields fields) {
      if (fields.name[id]) {
        ctx.addField(byte[].class, "u" + id);
      }
      if (fields.comma[id]) {
        ctx.addField(byte[].class, "uc" + id);
      }
    }

    @Override
    void addPrefixAssignments(
        Expression.ListExpression expressions,
        Expression property,
        JsonFieldInfo field,
        int id,
        PrefixFields fields) {
      if (fields.name[id]) {
        expressions.add(
            new Expression.Assign(
                utf8PrefixRef(false, id),
                new Expression.Invoke(property, "utf8NamePrefix", TypeRef.of(byte[].class))
                    .inline()));
      }
      if (fields.comma[id]) {
        expressions.add(
            new Expression.Assign(
                utf8PrefixRef(true, id),
                new Expression.Invoke(property, "utf8CommaNamePrefix", TypeRef.of(byte[].class))
                    .inline()));
      }
    }

    @Override
    Reference writerRef() {
      return new Reference("writer", TypeRef.of(Utf8JsonWriter.class));
    }

    @Override
    Expression writeObjectStartPrimitive(
        JsonFieldInfo property, Expression value, Expression writer) {
      String method;
      switch (property.writeKind()) {
        case BYTE:
        case SHORT:
        case INT:
          method = "writeObjectStartWithIntField";
          break;
        case LONG:
          method = "writeObjectStartWithLongField";
          break;
        default:
          throw new ForyJsonException(
              "Unsupported generated object-start kind " + property.writeKind());
      }
      if (canPackPrefix(property, false)) {
        return new Expression.Invoke(writer, method, packedPrefixArgs(property, false, value));
      }
      return new Expression.Invoke(writer, method, utf8PrefixRef(false, 0), value);
    }

    @Override
    Expression tryWriteObjectStartString(
        JsonFieldInfo property, Expression value, Expression writer) {
      if (!canPackObjectStartString(property)) {
        return null;
      }
      return new Expression.Invoke(
          writer, "writeObjectStartWithStringField", objectPackedPrefixArgs(property, value));
    }

    private static boolean canPackObjectStartString(JsonFieldInfo property) {
      return property.writeKind() == JsonFieldKind.STRING
          && property.utf8NamePrefix().length < Long.BYTES * 2;
    }

    @Override
    Expression writeNumberField(
        JsonFieldInfo property,
        int id,
        Expression value,
        boolean longValue,
        boolean commaKnown,
        Expression index,
        Expression writer) {
      String method = longValue ? "writeLongField" : "writeIntField";
      if (commaKnown) {
        if (canPackPrefix(property, true)) {
          return new Expression.Invoke(writer, method, packedPrefixArgs(property, true, value));
        }
        return new Expression.Invoke(writer, method, utf8PrefixRef(true, id), value);
      }
      if (canPackSinglePrefix(property, false) && canPackSinglePrefix(property, true)) {
        return new Expression.ListExpression(
            new Expression.Invoke(writer, method, packedDynamicPrefixArgs(property, index, value)),
            increment(index));
      }
      Expression.ListExpression expressions =
          new Expression.ListExpression(
              new Expression.Invoke(
                  writer, method, utf8PrefixRef(false, id), utf8PrefixRef(true, id), index, value));
      expressions.add(increment(index));
      return expressions;
    }

    @Override
    Expression writeStringField(
        JsonFieldInfo property,
        int id,
        Expression value,
        boolean commaKnown,
        Expression index,
        Expression writer) {
      if (commaKnown) {
        if (canPackPrefix(property, true)) {
          return new Expression.Invoke(
              writer, "writeStringField", packedPrefixArgs(property, true, value));
        }
        return new Expression.Invoke(writer, "writeStringField", utf8PrefixRef(true, id), value);
      }
      if (canPackSinglePrefix(property, false) && canPackSinglePrefix(property, true)) {
        return new Expression.ListExpression(
            new Expression.Invoke(
                writer, "writeStringField", packedDynamicPrefixArgs(property, index, value)),
            increment(index));
      }
      Expression.ListExpression expressions =
          new Expression.ListExpression(
              new Expression.Invoke(
                  writer,
                  "writeStringField",
                  utf8PrefixRef(false, id),
                  utf8PrefixRef(true, id),
                  index,
                  value));
      expressions.add(increment(index));
      return expressions;
    }

    @Override
    Expression writeFieldName(
        JsonFieldInfo property, int id, boolean commaKnown, Expression index, Expression writer) {
      if (commaKnown && canPackPrefix(property, true)) {
        return new Expression.Invoke(writer, "writeRawValue", packedPrefixArgs(property, true));
      }
      Expression prefix =
          commaKnown
              ? utf8PrefixRef(true, id)
              : new Expression.Ternary(
                  eq(index, Expression.Literal.ofInt(0)),
                  utf8PrefixRef(false, id),
                  utf8PrefixRef(true, id),
                  true,
                  TypeRef.of(byte[].class));
      Expression.ListExpression expressions =
          new Expression.ListExpression(new Expression.Invoke(writer, "writeRawValue", prefix));
      if (!commaKnown) {
        expressions.add(increment(index));
      }
      return expressions;
    }

    @Override
    Expression booleanFieldValue(int id, Expression value, boolean commaKnown, Expression index) {
      return fieldValue(id, "utf8BooleanFieldValue", value, commaKnown, index);
    }

    @Override
    Expression enumFieldValue(int id, Expression value, boolean commaKnown, Expression index) {
      return fieldValue(id, "utf8EnumFieldValue", value, commaKnown, index);
    }

    @Override
    Expression utf16EnumFieldValue(int id, Expression value, boolean commaKnown, Expression index) {
      return null;
    }

    @Override
    Expression writeExactScalar(JsonFieldInfo property, Expression value, Expression writer) {
      Class<?> rawType = property.writeRawType();
      Object codec = property.writeTypeInfo().utf8Writer();
      String method;
      if (rawType == UUID.class && codec == ScalarCodecs.UuidCodec.INSTANCE) {
        method = "writeUuid";
      } else if (rawType == LocalDate.class && codec == ScalarCodecs.LocalDateCodec.INSTANCE) {
        method = "writeLocalDate";
      } else if (rawType == OffsetDateTime.class
          && codec == ScalarCodecs.OffsetDateTimeCodec.INSTANCE) {
        method = "writeOffsetDateTime";
      } else if (rawType == BigDecimal.class && codec == ScalarCodecs.BigDecimalCodec.INSTANCE) {
        method = "writeBigDecimal";
      } else {
        return null;
      }
      return new Expression.Invoke(writer, method, value);
    }

    @Override
    Expression writeExactArray(JsonFieldInfo property, Expression value, Expression writer) {
      Class<?> rawType = property.writeRawType();
      Class<?> codecType = property.writeTypeInfo().utf8Writer().getClass();
      if (rawType == String[].class && codecType == ArrayCodec.StringArrayCodec.class) {
        return new Expression.Invoke(writer, "writeStringArray", value);
      }
      if (rawType == long[].class && codecType == ArrayCodec.LongArrayCodec.class) {
        return new Expression.Invoke(writer, "writeLongArray", value);
      }
      return null;
    }

    private static Reference utf8PrefixRef(boolean comma, int id) {
      return fieldRef((comma ? "uc" : "u") + id, byte[].class);
    }
  }

  private static Expression fieldValue(
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
