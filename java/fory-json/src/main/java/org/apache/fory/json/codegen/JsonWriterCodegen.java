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
import static org.apache.fory.codegen.ExpressionUtils.valueOf;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.apache.fory.codegen.Code;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.ExpressionOptimizer;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.ArrayCodec;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.codec.StringObjectWriterCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf8ObjectWriterCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;

final class JsonWriterCodegen {
  private static final int MIN_STRING_SPLIT_MEMBERS = 10;
  private static final int MIN_UTF8_SPLIT_MEMBERS = 12;
  // Keep each dense member helper as an independent C2 compilation unit. Smaller groups are
  // readily absorbed into the generated entry method and can exhaust its node budget before later
  // concrete writer fast paths are compiled. The entry method keeps a small tail inline below.
  private static final int MEMBER_GROUP_SIZE = 16;
  private static final int INLINE_TAIL_MEMBERS = 4;

  private final JsonCodegen codegen;
  private final boolean writeNullFields;
  private Class<?> ownerType;
  private boolean inlineObjectCollections;

  JsonWriterCodegen(JsonCodegen codegen) {
    this.codegen = codegen;
    this.writeNullFields = codegen.writeNullFields;
  }

  private Class<?> codecFieldType(JsonFieldInfo property, boolean utf8) {
    if (JsonCodegen.writesObjectCollectionDirectly(property)) {
      return utf8 ? Utf8ObjectWriterCodec.class : StringObjectWriterCodec.class;
    }
    return codegen.writerFieldType(property.writeTypeInfo(), utf8);
  }

  private static boolean usesWriteCodec(JsonFieldInfo property) {
    return JsonCodegen.usesWriteCodec(property);
  }

  private static Reference typeResolverRef() {
    return new Reference("typeResolver", TypeRef.of(JsonTypeResolver.class));
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
      JsonGeneratedCodecBuilder builder, Class<?> type, JsonFieldInfo[] properties, boolean utf8) {
    ownerType = type;
    CodegenContext ctx = builder.context();
    ctx.addImports(StringJsonWriter.class, Utf8JsonWriter.class);
    ctx.implementsInterfaces(
        ctx.type(utf8 ? Utf8ObjectWriterCodec.class : StringObjectWriterCodec.class));
    boolean objectStartFused = canFuseObjectStart(properties);
    StringPrefixFields stringPrefixFields =
        utf8 ? null : stringPrefixFields(properties, objectStartFused);
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (usesWriteInfo(property)) {
        ctx.addField(JsonFieldInfo.class, "wp" + i);
      }
      if (storesWriteCodec(property)) {
        ctx.addField(codecFieldType(property, utf8), "w" + i);
      }
      if (usesPrefix(property)) {
        if (utf8) {
          ctx.addField(byte[].class, "u" + i);
          ctx.addField(byte[].class, "uc" + i);
        } else {
          ctx.addField(byte[].class, "s" + i);
          ctx.addField(byte[].class, "sc" + i);
          if (stringPrefixFields.name[i]) {
            ctx.addField(byte[].class, "s16" + i);
          }
          if (stringPrefixFields.comma[i]) {
            ctx.addField(byte[].class, "sc16" + i);
          }
        }
      }
    }
    Class<?> writerType = utf8 ? Utf8JsonWriter.class : StringJsonWriter.class;
    Class<?> codecArrayType = utf8 ? Utf8WriterCodec[].class : StringWriterCodec[].class;
    addGeneratedConstructor(
        ctx,
        writerConstructorExpression(properties, utf8, stringPrefixFields),
        JsonFieldInfo[].class,
        "properties",
        codecArrayType,
        "codecs");
    ctx.addMethod(
        "@Override public final",
        utf8 ? "writeUtf8" : "writeString",
        "if (value == null) {\n"
            + "  writer.writeNull();\n"
            + "  return;\n"
            + "}\n"
            + (utf8 ? "writeUtf8NonNull" : "writeStringNonNull")
            + "(writer, value, typeResolver);",
        void.class,
        writerType,
        "writer",
        Object.class,
        "value",
        JsonTypeResolver.class,
        "typeResolver");
    addGeneratedMethod(
        ctx,
        "@Override public final",
        utf8 ? "writeUtf8NonNull" : "writeStringNonNull",
        writeExpression(builder, type, properties, utf8, objectStartFused),
        void.class,
        writerType,
        "writer",
        Object.class,
        "value",
        JsonTypeResolver.class,
        "typeResolver");
    return ctx.genCode();
  }

  private StringPrefixFields stringPrefixFields(
      JsonFieldInfo[] properties, boolean objectStartFused) {
    StringPrefixFields fields = new StringPrefixFields(properties.length);
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
      if (writeNullFields || property.writeRawType().isPrimitive()) {
        commaKnown = true;
      }
    }
    return fields;
  }

  private static void markStringUtf16PrefixField(
      JsonFieldInfo property, boolean commaKnown, StringPrefixFields fields, int id) {
    if (!commaKnown) {
      fields.name[id] = true;
      fields.comma[id] = true;
      return;
    }
    if (!canPackStringUtf16Prefix(property, true)) {
      fields.comma[id] = true;
    }
  }

  private static final class StringPrefixFields {
    private final boolean[] name;
    private final boolean[] comma;

    private StringPrefixFields(int size) {
      name = new boolean[size];
      comma = new boolean[size];
    }
  }

  private boolean usesPrefix(JsonFieldInfo property) {
    JsonFieldKind kind = property.writeKind();
    return kind != JsonFieldKind.BOOLEAN && kind != JsonFieldKind.ENUM
        || writeNullFields && !property.writeRawType().isPrimitive();
  }

  private static boolean usesWriteInfo(JsonFieldInfo property) {
    JsonFieldKind kind = property.writeKind();
    return kind == JsonFieldKind.BOOLEAN || kind == JsonFieldKind.ENUM;
  }

  private Expression writerConstructorExpression(
      JsonFieldInfo[] properties, boolean utf8, StringPrefixFields stringPrefixFields) {
    Expression.ListExpression expressions = new Expression.ListExpression();
    Reference propertiesRef = new Reference("properties", TypeRef.of(JsonFieldInfo[].class));
    Reference codecsRef =
        new Reference(
            "codecs", TypeRef.of(utf8 ? Utf8WriterCodec[].class : StringWriterCodec[].class));
    for (int i = 0; i < properties.length; i++) {
      Expression id = Expression.Literal.ofInt(i);
      Expression property = new Expression.ArrayValue(propertiesRef, id);
      if (usesWriteInfo(properties[i])) {
        expressions.add(
            new Expression.Assign(
                new Reference("this.wp" + i, TypeRef.of(JsonFieldInfo.class)), property));
      }
      if (storesWriteCodec(properties[i])) {
        Class<?> codecType = codecFieldType(properties[i], utf8);
        expressions.add(
            new Expression.Assign(
                new Reference("this.w" + i, TypeRef.of(codecType)),
                new Expression.Cast(
                    new Expression.ArrayValue(codecsRef, id), TypeRef.of(codecType))));
      }
      if (usesPrefix(properties[i])) {
        if (utf8) {
          expressions.add(
              new Expression.Assign(
                  new Reference("this.u" + i, TypeRef.of(byte[].class)),
                  new Expression.Invoke(property, "utf8NamePrefix", TypeRef.of(byte[].class))
                      .inline()));
          expressions.add(
              new Expression.Assign(
                  new Reference("this.uc" + i, TypeRef.of(byte[].class)),
                  new Expression.Invoke(property, "utf8CommaNamePrefix", TypeRef.of(byte[].class))
                      .inline()));
        } else {
          expressions.add(
              new Expression.Assign(
                  new Reference("this.s" + i, TypeRef.of(byte[].class)),
                  new Expression.Invoke(property, "stringNamePrefix", TypeRef.of(byte[].class))
                      .inline()));
          expressions.add(
              new Expression.Assign(
                  new Reference("this.sc" + i, TypeRef.of(byte[].class)),
                  new Expression.Invoke(property, "stringCommaNamePrefix", TypeRef.of(byte[].class))
                      .inline()));
          if (stringPrefixFields.name[i]) {
            expressions.add(
                new Expression.Assign(
                    new Reference("this.s16" + i, TypeRef.of(byte[].class)),
                    new Expression.Invoke(
                            property, "stringUtf16NamePrefix", TypeRef.of(byte[].class))
                        .inline()));
          }
          if (stringPrefixFields.comma[i]) {
            expressions.add(
                new Expression.Assign(
                    new Reference("this.sc16" + i, TypeRef.of(byte[].class)),
                    new Expression.Invoke(
                            property, "stringUtf16CommaNamePrefix", TypeRef.of(byte[].class))
                        .inline()));
          }
        }
      }
    }
    return expressions;
  }

  private Expression writeExpression(
      JsonGeneratedCodecBuilder builder,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean utf8,
      boolean objectStartFused) {
    Expression object =
        new Expression.Variable(
            "object",
            new Expression.Cast(
                new Reference("value", TypeRef.of(Object.class)), TypeRef.of(type)));
    Reference writer = writerRef(utf8);
    Reference typeResolver = typeResolverRef();
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(object);
    Expression index = null;
    if (!objectStartFused) {
      expressions.add(new Expression.Invoke(writer, "writeObjectStart"));
      index = new Expression.Variable("index", Expression.Literal.ofInt(0));
      expressions.add(index);
    }
    boolean commaKnown = objectStartFused;
    boolean splitMembers =
        properties.length >= (utf8 ? MIN_UTF8_SPLIT_MEMBERS : MIN_STRING_SPLIT_MEMBERS);
    inlineObjectCollections = splitMembers;
    List<Expression> memberGroup = splitMembers ? new ArrayList<>(MEMBER_GROUP_SIZE) : null;
    for (int i = 0; i < properties.length; i++) {
      Expression member;
      if (objectStartFused && i == 0) {
        member =
            writeObjectStartPrimitive(
                properties[i], builder.fieldValue(properties[i], object), utf8, writer);
      } else {
        member =
            writeProp(
                builder, properties[i], i, utf8, commaKnown, index, object, writer, typeResolver);
      }
      if (splitMembers && commaKnown) {
        memberGroup.add(member);
        if (memberGroup.size() == MEMBER_GROUP_SIZE) {
          addMemberGroup(builder, expressions, memberGroup, object, writer, typeResolver, utf8);
        }
      } else {
        if (splitMembers) {
          addMemberGroup(builder, expressions, memberGroup, object, writer, typeResolver, utf8);
        }
        expressions.add(member);
      }
      if (writeNullFields || properties[i].writeRawType().isPrimitive()) {
        commaKnown = true;
      }
    }
    if (splitMembers) {
      addMemberGroup(builder, expressions, memberGroup, object, writer, typeResolver, utf8, true);
    }
    expressions.add(new Expression.Invoke(writer, "writeObjectEnd"));
    return expressions;
  }

  private static void addMemberGroup(
      JsonGeneratedCodecBuilder builder,
      Expression.ListExpression expressions,
      List<Expression> memberGroup,
      Expression object,
      Reference writer,
      Reference typeResolver,
      boolean utf8) {
    addMemberGroup(builder, expressions, memberGroup, object, writer, typeResolver, utf8, false);
  }

  private static void addMemberGroup(
      JsonGeneratedCodecBuilder builder,
      Expression.ListExpression expressions,
      List<Expression> memberGroup,
      Expression object,
      Reference writer,
      Reference typeResolver,
      boolean utf8,
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
    cutPoints.add(typeResolver);
    expressions.add(
        ExpressionOptimizer.invokeGenerated(
            builder.context(),
            cutPoints,
            new Expression.ListExpression(new ArrayList<>(memberGroup)),
            utf8 ? "writeUtf8Members" : "writeStringMembers",
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

  private static Expression writeObjectStartPrimitive(
      JsonFieldInfo property, Expression value, boolean utf8, Expression writer) {
    switch (property.writeKind()) {
      case BYTE:
      case SHORT:
      case INT:
        if (utf8 && canPackPrefix(property, false)) {
          return new Expression.Invoke(
              writer, "writeObjectIntField", packedPrefixArgs(property, false, value));
        }
        if (!utf8) {
          if (canPackStringUtf16Prefix(property, false)) {
            return new Expression.Invoke(
                writer, "writeObjectIntField", stringPackedPrefixArgs(property, 0, false, value));
          }
          return new Expression.Invoke(
              writer,
              "writeObjectIntField",
              prefixRef(false, false, 0),
              utf16PrefixRef(false, 0),
              value);
        }
        return new Expression.Invoke(
            writer, "writeObjectIntField", prefixRef(utf8, false, 0), value);
      case LONG:
        if (utf8 && canPackPrefix(property, false)) {
          return new Expression.Invoke(
              writer, "writeObjectLongField", packedPrefixArgs(property, false, value));
        }
        if (!utf8) {
          if (canPackStringUtf16Prefix(property, false)) {
            return new Expression.Invoke(
                writer, "writeObjectLongField", stringPackedPrefixArgs(property, 0, false, value));
          }
          return new Expression.Invoke(
              writer,
              "writeObjectLongField",
              prefixRef(false, false, 0),
              utf16PrefixRef(false, 0),
              value);
        }
        return new Expression.Invoke(
            writer, "writeObjectLongField", prefixRef(utf8, false, 0), value);
      default:
        throw new ForyJsonException(
            "Unsupported generated object-start kind " + property.writeKind());
    }
  }

  private Expression writeProp(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      boolean utf8,
      boolean commaKnown,
      Expression index,
      Expression object,
      Expression writer,
      Expression typeResolver) {
    Class<?> rawType = property.writeRawType();
    if (rawType.isPrimitive()) {
      Expression fieldValue = builder.fieldValue(property, object);
      if (property.writeKind() == JsonFieldKind.OBJECT) {
        Expression value =
            new Expression.Variable(
                "v" + id, valueOf(TypeRef.of(rawType).wrap(), inline(fieldValue)));
        return new Expression.ListExpression(
            value,
            writeFieldName(property, id, utf8, commaKnown, index, writer),
            writeCodec(property, id, value, utf8, writer, typeResolver));
      }
      return writePrimitive(property, id, fieldValue, utf8, commaKnown, index, writer);
    }
    Expression value =
        new Expression.Variable(
            "v" + id, cast(inline(builder.fieldValue(property, object)), TypeRef.of(rawType)));
    Expression nullValue = new Expression.Null(TypeRef.of(rawType), false);
    if (writeNullFields) {
      if (isPrefixValue(property.writeKind())) {
        return new Expression.ListExpression(
            value,
            new Expression.If(
                eq(value, nullValue),
                new Expression.ListExpression(
                    writeFieldName(property, id, utf8, commaKnown, index, writer),
                    new Expression.Invoke(writer, "writeNull")),
                writeValue(
                    builder, property, id, value, utf8, commaKnown, index, writer, typeResolver)));
      }
      return new Expression.ListExpression(
          value,
          writeFieldName(property, id, utf8, commaKnown, index, writer),
          new Expression.If(
              eq(value, nullValue),
              new Expression.Invoke(writer, "writeNull"),
              writeValue(builder, property, id, value, utf8, true, index, writer, typeResolver)));
    }
    Expression write =
        isPrefixValue(property.writeKind())
            ? writeValue(
                builder, property, id, value, utf8, commaKnown, index, writer, typeResolver)
            : new Expression.ListExpression(
                writeFieldName(property, id, utf8, commaKnown, index, writer),
                writeValue(builder, property, id, value, utf8, true, index, writer, typeResolver));
    return new Expression.ListExpression(value, new Expression.If(ne(value, nullValue), write));
  }

  private Expression writePrimitive(
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean utf8,
      boolean commaKnown,
      Expression index,
      Expression writer) {
    switch (property.writeKind()) {
      case BOOLEAN:
        return writeRawFieldValue(
            commaKnown, index, booleanFieldValue(id, value, utf8, commaKnown, index), writer);
      case BYTE:
      case SHORT:
      case INT:
        return writeNumberField(property, id, value, false, utf8, commaKnown, index, writer);
      case LONG:
        return writeNumberField(property, id, value, true, utf8, commaKnown, index, writer);
      default:
        return new Expression.ListExpression(
            writeFieldName(property, id, utf8, commaKnown, index, writer),
            writePrimitiveScalar(property.writeKind(), value, writer));
    }
  }

  private static Expression writeNumberField(
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean longValue,
      boolean utf8,
      boolean commaKnown,
      Expression index,
      Expression writer) {
    String writerMethod = longValue ? "writeLongField" : "writeIntField";
    if (commaKnown) {
      if (utf8 && canPackPrefix(property, true)) {
        return new Expression.Invoke(writer, writerMethod, packedPrefixArgs(property, true, value));
      }
      if (!utf8) {
        if (canPackStringUtf16Prefix(property, true)) {
          return new Expression.Invoke(
              writer, writerMethod, stringPackedPrefixArgs(property, id, true, value));
        }
        return new Expression.Invoke(
            writer, writerMethod, prefixRef(false, true, id), utf16PrefixRef(true, id), value);
      }
      return new Expression.Invoke(writer, writerMethod, prefixRef(utf8, true, id), value);
    }
    if (utf8 && canPackSinglePrefix(property, false) && canPackSinglePrefix(property, true)) {
      return new Expression.ListExpression(
          new Expression.Invoke(
              writer, writerMethod, packedDynamicPrefixArgs(property, index, value)),
          increment(index));
    }
    Expression.ListExpression expressions =
        new Expression.ListExpression(
            utf8
                ? new Expression.Invoke(
                    writer,
                    writerMethod,
                    prefixRef(true, false, id),
                    prefixRef(true, true, id),
                    index,
                    value)
                : new Expression.Invoke(
                    writer,
                    writerMethod,
                    prefixRef(false, false, id),
                    prefixRef(false, true, id),
                    utf16PrefixRef(false, id),
                    utf16PrefixRef(true, id),
                    index,
                    value));
    expressions.add(increment(index));
    return expressions;
  }

  private static Expression writeStringField(
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean utf8,
      boolean commaKnown,
      Expression index,
      Expression writer) {
    if (commaKnown) {
      if (utf8 && canPackPrefix(property, true)) {
        return new Expression.Invoke(
            writer, "writeStringField", packedPrefixArgs(property, true, value));
      }
      if (!utf8) {
        if (canPackStringUtf16Prefix(property, true)) {
          return new Expression.Invoke(
              writer, "writeStringField", stringPackedPrefixArgs(property, id, true, value));
        }
        return new Expression.Invoke(
            writer,
            "writeStringField",
            prefixRef(false, true, id),
            utf16PrefixRef(true, id),
            value);
      }
      return new Expression.Invoke(writer, "writeStringField", prefixRef(utf8, true, id), value);
    }
    if (utf8 && canPackSinglePrefix(property, false) && canPackSinglePrefix(property, true)) {
      Expression.ListExpression expressions =
          new Expression.ListExpression(
              new Expression.Invoke(
                  writer, "writeStringField", packedDynamicPrefixArgs(property, index, value)));
      expressions.add(increment(index));
      return expressions;
    }
    Expression.ListExpression expressions =
        new Expression.ListExpression(
            utf8
                ? new Expression.Invoke(
                    writer,
                    "writeStringField",
                    prefixRef(true, false, id),
                    prefixRef(true, true, id),
                    index,
                    value)
                : new Expression.Invoke(
                    writer,
                    "writeStringField",
                    prefixRef(false, false, id),
                    prefixRef(false, true, id),
                    utf16PrefixRef(false, id),
                    utf16PrefixRef(true, id),
                    index,
                    value));
    expressions.add(increment(index));
    return expressions;
  }

  private static Expression writeFieldName(
      JsonFieldInfo property,
      int id,
      boolean utf8,
      boolean commaKnown,
      Expression index,
      Expression writer) {
    if (commaKnown && utf8 && canPackPrefix(property, true)) {
      return new Expression.Invoke(writer, "writeRawValue", packedPrefixArgs(property, true));
    }
    if (!utf8 && commaKnown) {
      if (canPackStringUtf16Prefix(property, true)) {
        return new Expression.Invoke(
            writer, "writeRawValue", stringPackedPrefixArgs(property, id, true));
      }
      return new Expression.Invoke(
          writer, "writeRawValue", prefixRef(false, true, id), utf16PrefixRef(true, id));
    }
    if (!utf8) {
      Expression.ListExpression expressions =
          new Expression.ListExpression(
              new Expression.Invoke(
                  writer,
                  "writeRawValue",
                  prefixRef(false, false, id),
                  prefixRef(false, true, id),
                  utf16PrefixRef(false, id),
                  utf16PrefixRef(true, id),
                  index));
      expressions.add(increment(index));
      return expressions;
    }
    Expression prefix =
        commaKnown
            ? prefixRef(utf8, true, id)
            : new Expression.Ternary(
                eq(index, Expression.Literal.ofInt(0)),
                prefixRef(utf8, false, id),
                prefixRef(utf8, true, id),
                true,
                TypeRef.of(byte[].class));
    Expression.ListExpression expressions =
        new Expression.ListExpression(new Expression.Invoke(writer, "writeRawValue", prefix));
    if (!commaKnown) {
      expressions.add(increment(index));
    }
    return expressions;
  }

  private Expression writeValue(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean utf8,
      boolean commaKnown,
      Expression index,
      Expression writer,
      Expression typeResolver) {
    JsonFieldKind kind = property.writeKind();
    switch (kind) {
      case BOOLEAN:
        return writeRawFieldValue(
            commaKnown,
            index,
            booleanFieldValue(
                id,
                new Expression.Invoke(value, "booleanValue", TypeRef.of(boolean.class)).inline(),
                utf8,
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
            utf8,
            commaKnown,
            index,
            writer);
      case LONG:
        return writeNumberField(
            property,
            id,
            new Expression.Invoke(value, "longValue", TypeRef.of(long.class)).inline(),
            true,
            utf8,
            commaKnown,
            index,
            writer);
      case STRING:
        return writeStringField(property, id, value, utf8, commaKnown, index, writer);
      case ENUM:
        return writeRawFieldValue(
            commaKnown,
            index,
            enumFieldValue(id, value, utf8, commaKnown, index),
            utf8 ? null : stringUtf16EnumFieldValue(id, value, commaKnown, index),
            writer);
      case FLOAT:
      case DOUBLE:
      case CHAR:
        return writeScalar(kind, value, writer);
      case ARRAY:
        Expression array = writeExactUtf8Array(property, value, utf8, writer);
        return array == null ? writeCodec(property, id, value, utf8, writer, typeResolver) : array;
      case MAP:
        return writeCodec(property, id, value, utf8, writer, typeResolver);
      case COLLECTION:
        if (JsonCodegen.writesStringCollectionDirectly(property)) {
          return writeStringCollection(value, utf8, writer);
        }
        if (JsonCodegen.writesObjectCollectionDirectly(property)) {
          return writeObjectCollection(builder, property, id, value, utf8, writer, typeResolver);
        }
        return writeCodec(property, id, value, utf8, writer, typeResolver);
      case OBJECT:
        Expression scalar = writeExactUtf8Scalar(property, value, utf8, writer);
        return scalar == null
            ? writeCodec(property, id, value, utf8, writer, typeResolver)
            : scalar;
      default:
        return writeCodec(property, id, value, utf8, writer, typeResolver);
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

  private static Expression[] stringPackedPrefixArgs(
      JsonFieldInfo property, int id, boolean comma, Expression... extraArgs) {
    byte[] prefix =
        comma ? property.stringUtf16CommaNamePrefix() : property.stringUtf16NamePrefix();
    Expression[] args = new Expression[6 + extraArgs.length];
    args[0] = prefixRef(false, comma, id);
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

  private static Expression booleanFieldValue(
      int id, Expression value, boolean utf8, boolean commaKnown, Expression index) {
    return new Expression.Invoke(
            fieldRef("wp" + id, JsonFieldInfo.class),
            utf8 ? "utf8BooleanFieldValue" : "stringBooleanFieldValue",
            TypeRef.of(byte[].class),
            value,
            commaFlag(commaKnown, index))
        .inline();
  }

  private static Expression enumFieldValue(
      int id, Expression value, boolean utf8, boolean commaKnown, Expression index) {
    return new Expression.Invoke(
            fieldRef("wp" + id, JsonFieldInfo.class),
            utf8 ? "utf8EnumFieldValue" : "stringEnumFieldValue",
            TypeRef.of(byte[].class),
            value,
            commaFlag(commaKnown, index))
        .inline();
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
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean utf8,
      Expression writer,
      Expression typeResolver) {
    boolean object = property.writeTypeInfo().usesDefaultObjectCodec();
    Expression codec =
        object && property.writeRawType() == ownerType
            ? new Reference(
                "this",
                TypeRef.of(utf8 ? Utf8ObjectWriterCodec.class : StringObjectWriterCodec.class))
            : fieldRef("w" + id, codecFieldType(property, utf8));
    return new Expression.Invoke(
        codec,
        utf8
            ? (object ? "writeUtf8NonNull" : "writeUtf8")
            : (object ? "writeStringNonNull" : "writeString"),
        writer,
        value,
        typeResolver);
  }

  private boolean storesWriteCodec(JsonFieldInfo property) {
    if (JsonCodegen.writesObjectCollectionDirectly(property)) {
      return property.writeElementRawType() != ownerType;
    }
    return usesWriteCodec(property)
        && (!property.writeTypeInfo().usesDefaultObjectCodec()
            || property.writeRawType() != ownerType);
  }

  private Expression writeObjectCollection(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean utf8,
      Expression writer,
      Expression typeResolver) {
    TypeRef<?> codecType =
        TypeRef.of(utf8 ? Utf8ObjectWriterCodec.class : StringObjectWriterCodec.class);
    Expression codec =
        property.writeElementRawType() == ownerType
            ? new Reference("this", codecType)
            : fieldRef("w" + id, codecType.getRawType());
    Expression arrayList =
        new Expression.Variable("list", new Expression.Cast(value, TypeRef.of(ArrayList.class)));
    Expression arrayListLoop =
        new Expression.ListExpression(
            arrayList,
            new Expression.ForLoop(
                Expression.Literal.ofInt(0),
                new Expression.Invoke(arrayList, "size", TypeRef.of(int.class)),
                Expression.Literal.ofInt(1),
                index ->
                    writeObjectCollectionElement(
                        index,
                        new Expression.Invoke(
                            arrayList, "get", TypeRef.of(Object.class), true, index),
                        codec,
                        utf8,
                        writer,
                        typeResolver)));
    Expression collection =
        new Expression.Variable(
            "collection", new Expression.Cast(value, TypeRef.of(Collection.class)));
    Expression collectionLoop =
        new Expression.ListExpression(
            collection,
            new Expression.ForEach(
                collection,
                TypeRef.of(Object.class),
                true,
                (index, element) ->
                    writeObjectCollectionElement(
                        index, element, codec, utf8, writer, typeResolver)));
    Expression exactArrayList =
        eq(
            new Expression.Invoke(value, "getClass", TypeRef.of(Class.class)).inline(),
            Expression.Literal.ofClass(ArrayList.class));
    Expression body =
        new Expression.ListExpression(
            new Expression.Invoke(writer, "writeArrayStart"),
            new Expression.If(exactArrayList, arrayListLoop, collectionLoop),
            new Expression.Invoke(writer, "writeArrayEnd"));
    if (inlineObjectCollections) {
      return body;
    }
    LinkedHashSet<Expression> cutPoints = new LinkedHashSet<>();
    cutPoints.add(value);
    cutPoints.add(writer);
    cutPoints.add(typeResolver);
    return ExpressionOptimizer.invokeGenerated(
        builder.context(),
        cutPoints,
        body,
        utf8 ? "writeUtf8ObjectCollection" : "writeStringObjectCollection",
        false);
  }

  private static Expression writeObjectCollectionElement(
      Expression index,
      Expression element,
      Expression codec,
      boolean utf8,
      Expression writer,
      Expression typeResolver) {
    return new Expression.ListExpression(
        element,
        new Expression.Invoke(writer, "writeComma", index),
        new Expression.If(
            org.apache.fory.codegen.ExpressionUtils.isNull(element),
            new Expression.Invoke(writer, "writeNull"),
            new Expression.Invoke(
                codec,
                utf8 ? "writeUtf8NonNull" : "writeStringNonNull",
                writer,
                element,
                typeResolver)));
  }

  private static Expression writeExactUtf8Scalar(
      JsonFieldInfo property, Expression value, boolean utf8, Expression writer) {
    if (!utf8) {
      return null;
    }
    Class<?> rawType = property.writeRawType();
    Object codec = property.writeTypeInfo().utf8Writer();
    String writerMethod;
    if (rawType == UUID.class && codec == ScalarCodecs.UuidCodec.INSTANCE) {
      writerMethod = "writeUuid";
    } else if (rawType == LocalDate.class && codec == ScalarCodecs.LocalDateCodec.INSTANCE) {
      writerMethod = "writeLocalDate";
    } else if (rawType == OffsetDateTime.class
        && codec == ScalarCodecs.OffsetDateTimeCodec.INSTANCE) {
      writerMethod = "writeOffsetDateTime";
    } else if (rawType == BigDecimal.class && codec == ScalarCodecs.BigDecimalCodec.INSTANCE) {
      writerMethod = "writeBigDecimal";
    } else {
      return null;
    }
    return new Expression.Invoke(writer, writerMethod, value);
  }

  private static Expression writeExactUtf8Array(
      JsonFieldInfo property, Expression value, boolean utf8, Expression writer) {
    if (!utf8) {
      return null;
    }
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

  private static Expression writeStringCollection(
      Expression value, boolean utf8, Expression writer) {
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

  private static Reference writerRef(boolean utf8) {
    return new Reference(
        "writer", TypeRef.of(utf8 ? Utf8JsonWriter.class : StringJsonWriter.class));
  }

  private static Reference prefixRef(boolean utf8, boolean comma, int id) {
    String prefix = utf8 ? (comma ? "uc" : "u") : (comma ? "sc" : "s");
    return fieldRef(prefix + id, byte[].class);
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
}
