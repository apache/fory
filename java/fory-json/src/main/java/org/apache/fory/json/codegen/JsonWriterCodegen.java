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
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.GeneratedObjectWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.StringObjectWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.json.writer.Utf8ObjectWriter;
import org.apache.fory.reflect.TypeRef;

final class JsonWriterCodegen {
  private final JsonCodegen codegen;
  private final boolean writeNullFields;

  JsonWriterCodegen(JsonCodegen codegen) {
    this.codegen = codegen;
    this.writeNullFields = codegen.writeNullFields;
  }

  private Class<?> codecFieldType(JsonCodec codec) {
    return codegen.codecFieldType(codec);
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

  private static void addGeneratedConstructor(
      CodegenContext ctx, Expression expression, Object... params) {
    ctx.clearExprState();
    Code.ExprCode body = expression.genCode(ctx);
    String code = body.code();
    code = code == null ? "" : ctx.optimizeMethodCode(code);
    ctx.addConstructor(code, params);
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

  String genWriterCode(
      JsonGeneratedCodecBuilder builder,
      String className,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean utf8) {
    CodegenContext ctx = builder.context();
    ctx.addImports(StringJsonWriter.class, Utf8JsonWriter.class);
    ctx.extendsClasses(ctx.type(GeneratedObjectWriter.class));
    ctx.implementsInterfaces(ctx.type(utf8 ? Utf8ObjectWriter.class : StringObjectWriter.class));
    boolean objectStartFused = canFuseObjectStart(properties);
    boolean[] useInfo = new boolean[properties.length];
    boolean[] usePrefix = new boolean[properties.length];
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      useInfo[i] = true;
      usePrefix[i] = usesPrefix(property);
      if (useInfo[i]) {
        ctx.addField(JsonFieldInfo.class, "p" + i);
      }
      if (usesWriteCodec(property)) {
        ctx.addField(codecFieldType(property.writeTypeInfo().codec()), "c" + i);
      }
      if (usePrefix[i]) {
        if (utf8) {
          ctx.addField(byte[].class, "u" + i);
          ctx.addField(byte[].class, "uc" + i);
        } else {
          ctx.addField(byte[].class, "s" + i);
          ctx.addField(byte[].class, "sc" + i);
        }
      }
    }
    addGeneratedConstructor(
        ctx,
        writerConstructorExpression(properties, utf8),
        JsonFieldInfo[].class,
        "properties",
        JsonCodec[].class,
        "codecs");
    addGeneratedMethod(
        ctx,
        "public",
        utf8 ? "writeUtf8" : "writeString",
        writeExpression(builder, type, properties, utf8, objectStartFused),
        void.class,
        utf8 ? Utf8JsonWriter.class : StringJsonWriter.class,
        "writer",
        Object.class,
        "value",
        JsonTypeResolver.class,
        "typeResolver");
    return ctx.genCode();
  }

  private boolean usesPrefix(JsonFieldInfo property) {
    JsonFieldKind kind = property.writeKind();
    return kind != JsonFieldKind.BOOLEAN && kind != JsonFieldKind.ENUM
        || writeNullFields && !property.writeRawType().isPrimitive();
  }

  private Expression writerConstructorExpression(JsonFieldInfo[] properties, boolean utf8) {
    Expression.ListExpression expressions = new Expression.ListExpression();
    Reference propertiesRef = new Reference("properties", TypeRef.of(JsonFieldInfo[].class));
    Reference codecsRef = new Reference("codecs", TypeRef.of(JsonCodec[].class));
    expressions.add(new Expression.SuperCall(propertiesRef, codecsRef));
    for (int i = 0; i < properties.length; i++) {
      Expression id = Expression.Literal.ofInt(i);
      Expression property = new Expression.ArrayValue(propertiesRef, id);
      expressions.add(
          new Expression.Assign(
              new Reference("this.p" + i, TypeRef.of(JsonFieldInfo.class)), property));
      if (usesWriteCodec(properties[i])) {
        Class<?> codecType = codecFieldType(properties[i].writeTypeInfo().codec());
        expressions.add(
            new Expression.Assign(
                new Reference("this.c" + i, TypeRef.of(codecType)),
                new Expression.Cast(
                    new Expression.ArrayValue(codecsRef, id), TypeRef.of(codecType))));
      }
      if (usesPrefix(properties[i])) {
        if (utf8) {
          expressions.add(
              new Expression.Assign(
                  new Reference("this.u" + i, TypeRef.of(byte[].class)),
                  new Expression.Invoke(property, "utf8NamePrefix", TypeRef.of(byte[].class))));
          expressions.add(
              new Expression.Assign(
                  new Reference("this.uc" + i, TypeRef.of(byte[].class)),
                  new Expression.Invoke(
                      property, "utf8CommaNamePrefix", TypeRef.of(byte[].class))));
        } else {
          expressions.add(
              new Expression.Assign(
                  new Reference("this.s" + i, TypeRef.of(byte[].class)),
                  new Expression.Invoke(property, "stringNamePrefix", TypeRef.of(byte[].class))));
          expressions.add(
              new Expression.Assign(
                  new Reference("this.sc" + i, TypeRef.of(byte[].class)),
                  new Expression.Invoke(
                      property, "stringCommaNamePrefix", TypeRef.of(byte[].class))));
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
    Expression.ListExpression expressions = new Expression.ListExpression();
    expressions.add(object);
    Expression index = null;
    if (!objectStartFused) {
      expressions.add(new Expression.Invoke(writerRef(utf8), "writeObjectStart"));
      index = new Expression.Variable("index", Expression.Literal.ofInt(0));
      expressions.add(index);
    }
    boolean commaKnown = objectStartFused;
    for (int i = 0; i < properties.length; i++) {
      if (objectStartFused && i == 0) {
        expressions.add(
            writeObjectStartPrimitive(
                properties[i], builder.fieldValue(properties[i], object), utf8));
      } else {
        expressions.add(writeProp(builder, properties[i], i, utf8, commaKnown, index, object));
      }
      if (writeNullFields || properties[i].writeRawType().isPrimitive()) {
        commaKnown = true;
      }
    }
    expressions.add(new Expression.Invoke(writerRef(utf8), "writeObjectEnd"));
    return expressions;
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
      JsonFieldInfo property, Expression value, boolean utf8) {
    switch (property.writeKind()) {
      case BYTE:
      case SHORT:
      case INT:
        return new Expression.Invoke(
            writerRef(utf8), "writeObjectIntField", prefixRef(utf8, false, 0), value);
      case LONG:
        return new Expression.Invoke(
            writerRef(utf8), "writeObjectLongField", prefixRef(utf8, false, 0), value);
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
      Expression object) {
    Class<?> rawType = property.writeRawType();
    if (rawType.isPrimitive()) {
      return writePrimitive(
          property, id, builder.fieldValue(property, object), utf8, commaKnown, index);
    }
    Expression value =
        new Expression.Variable(
            "v" + id,
            new Expression.Cast(builder.fieldValue(property, object), TypeRef.of(rawType)));
    Expression nullValue = new Expression.Null(TypeRef.of(rawType), false);
    if (writeNullFields) {
      if (isPrefixValue(property.writeKind())) {
        return new Expression.ListExpression(
            value,
            new Expression.If(
                eq(value, nullValue),
                new Expression.ListExpression(
                    writeFieldName(id, utf8, commaKnown, index),
                    new Expression.Invoke(writerRef(utf8), "writeNull")),
                writeValue(property, id, value, utf8, commaKnown, index)));
      }
      return new Expression.ListExpression(
          value,
          writeFieldName(id, utf8, commaKnown, index),
          new Expression.If(
              eq(value, nullValue),
              new Expression.Invoke(writerRef(utf8), "writeNull"),
              writeValue(property, id, value, utf8, true, index)));
    }
    Expression write =
        isPrefixValue(property.writeKind())
            ? writeValue(property, id, value, utf8, commaKnown, index)
            : new Expression.ListExpression(
                writeFieldName(id, utf8, commaKnown, index),
                writeValue(property, id, value, utf8, true, index));
    return new Expression.ListExpression(value, new Expression.If(ne(value, nullValue), write));
  }

  private Expression writePrimitive(
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean utf8,
      boolean commaKnown,
      Expression index) {
    switch (property.writeKind()) {
      case BOOLEAN:
        return writeRawFieldValue(
            utf8, commaKnown, index, booleanFieldValue(id, value, utf8, commaKnown, index));
      case BYTE:
      case SHORT:
      case INT:
        return writeNumberField(id, value, false, utf8, commaKnown, index);
      case LONG:
        return writeNumberField(id, value, true, utf8, commaKnown, index);
      default:
        return new Expression.ListExpression(
            writeFieldName(id, utf8, commaKnown, index),
            writePrimitiveScalar(property.writeKind(), value, utf8));
    }
  }

  private static Expression writeNumberField(
      int id,
      Expression value,
      boolean longValue,
      boolean utf8,
      boolean commaKnown,
      Expression index) {
    String writerMethod = longValue ? "writeLongField" : "writeIntField";
    if (commaKnown) {
      return new Expression.Invoke(writerRef(utf8), writerMethod, prefixRef(utf8, true, id), value);
    }
    Expression.ListExpression expressions =
        new Expression.ListExpression(
            new Expression.Invoke(
                writerRef(utf8),
                writerMethod,
                prefixRef(utf8, false, id),
                prefixRef(utf8, true, id),
                index,
                value));
    expressions.add(increment(index));
    return expressions;
  }

  private static Expression writeStringField(
      int id, Expression value, boolean utf8, boolean commaKnown, Expression index) {
    if (commaKnown) {
      return new Expression.Invoke(
          writerRef(utf8), "writeStringField", prefixRef(utf8, true, id), value);
    }
    Expression.ListExpression expressions =
        new Expression.ListExpression(
            new Expression.Invoke(
                writerRef(utf8),
                "writeStringField",
                prefixRef(utf8, false, id),
                prefixRef(utf8, true, id),
                index,
                value));
    expressions.add(increment(index));
    return expressions;
  }

  private static Expression writeFieldName(
      int id, boolean utf8, boolean commaKnown, Expression index) {
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
        new Expression.ListExpression(
            new Expression.Invoke(writerRef(utf8), "writeRawValue", prefix));
    if (!commaKnown) {
      expressions.add(increment(index));
    }
    return expressions;
  }

  private Expression writeValue(
      JsonFieldInfo property,
      int id,
      Expression value,
      boolean utf8,
      boolean commaKnown,
      Expression index) {
    JsonFieldKind kind = property.writeKind();
    switch (kind) {
      case BOOLEAN:
        return writeRawFieldValue(
            utf8,
            commaKnown,
            index,
            booleanFieldValue(
                id,
                new Expression.Invoke(value, "booleanValue", TypeRef.of(boolean.class)).inline(),
                utf8,
                commaKnown,
                index));
      case BYTE:
      case SHORT:
      case INT:
        return writeNumberField(
            id,
            new Expression.Invoke(value, "intValue", TypeRef.of(int.class)).inline(),
            false,
            utf8,
            commaKnown,
            index);
      case LONG:
        return writeNumberField(
            id,
            new Expression.Invoke(value, "longValue", TypeRef.of(long.class)).inline(),
            true,
            utf8,
            commaKnown,
            index);
      case STRING:
        return writeStringField(id, value, utf8, commaKnown, index);
      case ENUM:
        return writeRawFieldValue(
            utf8, commaKnown, index, enumFieldValue(id, value, utf8, commaKnown, index));
      case FLOAT:
      case DOUBLE:
      case CHAR:
        return writeScalar(kind, value, utf8);
      case ARRAY:
      case MAP:
        return writeCodec(id, value, utf8);
      case COLLECTION:
        if (property.writeElementRawType() == String.class) {
          return writeStringCollection(value, utf8);
        }
        return writeCodec(id, value, utf8);
      default:
        return writeCodec(id, value, utf8);
    }
  }

  private static Expression writeRawFieldValue(
      boolean utf8, boolean commaKnown, Expression index, Expression value) {
    Expression.ListExpression expressions =
        new Expression.ListExpression(
            new Expression.Invoke(writerRef(utf8), "writeRawValue", value));
    if (!commaKnown) {
      expressions.add(increment(index));
    }
    return expressions;
  }

  private static Expression booleanFieldValue(
      int id, Expression value, boolean utf8, boolean commaKnown, Expression index) {
    return new Expression.Invoke(
            fieldRef("p" + id, JsonFieldInfo.class),
            utf8 ? "utf8BooleanFieldValue" : "stringBooleanFieldValue",
            TypeRef.of(byte[].class),
            value,
            commaFlag(commaKnown, index))
        .inline();
  }

  private static Expression enumFieldValue(
      int id, Expression value, boolean utf8, boolean commaKnown, Expression index) {
    return new Expression.Invoke(
            fieldRef("p" + id, JsonFieldInfo.class),
            utf8 ? "utf8EnumFieldValue" : "stringEnumFieldValue",
            TypeRef.of(byte[].class),
            value,
            commaFlag(commaKnown, index))
        .inline();
  }

  private static Expression writeCodec(int id, Expression value, boolean utf8) {
    return new Expression.Invoke(
        fieldRef("c" + id, JsonCodec.class),
        utf8 ? "writeUtf8" : "writeString",
        writerRef(utf8),
        value,
        typeResolverRef());
  }

  private static Expression writeStringCollection(Expression value, boolean utf8) {
    return new Expression.ListExpression(
        new Expression.Invoke(writerRef(utf8), "writeArrayStart"),
        new Expression.ForEach(
            value,
            TypeRef.of(String.class),
            true,
            (index, element) ->
                new Expression.Invoke(writerRef(utf8), "writeStringElement", index, element)),
        new Expression.Invoke(writerRef(utf8), "writeArrayEnd"));
  }

  private Expression writeScalar(JsonFieldKind kind, Expression value, boolean utf8) {
    switch (kind) {
      case FLOAT:
        return new Expression.Invoke(
            writerRef(utf8),
            "writeFloat",
            new Expression.Invoke(value, "floatValue", TypeRef.of(float.class)).inline());
      case DOUBLE:
        return new Expression.Invoke(
            writerRef(utf8),
            "writeDouble",
            new Expression.Invoke(value, "doubleValue", TypeRef.of(double.class)).inline());
      case CHAR:
        return new Expression.Invoke(
            writerRef(utf8),
            "writeChar",
            new Expression.Invoke(value, "charValue", TypeRef.of(char.class)).inline());
      default:
        throw new ForyJsonException("Unsupported generated scalar kind " + kind);
    }
  }

  private Expression writePrimitiveScalar(JsonFieldKind kind, Expression value, boolean utf8) {
    switch (kind) {
      case FLOAT:
        return new Expression.Invoke(writerRef(utf8), "writeFloat", value);
      case DOUBLE:
        return new Expression.Invoke(writerRef(utf8), "writeDouble", value);
      case CHAR:
        return new Expression.Invoke(writerRef(utf8), "writeChar", value);
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

  private static Expression commaFlag(boolean commaKnown, Expression index) {
    return commaKnown ? Expression.Literal.True : ne(index, Expression.Literal.ofInt(0));
  }

  private static Expression increment(Expression value) {
    return new Expression.Assign(value, new Expression.Add(value, Expression.Literal.ofInt(1)));
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
