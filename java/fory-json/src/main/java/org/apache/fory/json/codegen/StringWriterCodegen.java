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

import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.reflect.TypeRef;

final class StringWriterCodegen extends JsonWriterCodegen {
  private static final int MIN_SPLIT_MEMBERS = 10;

  StringWriterCodegen(JsonCodegen codegen, JsonTypeResolver resolver) {
    super(codegen, resolver);
  }

  @Override
  Class<?> codecFieldType(JsonFieldInfo property) {
    return codegen.stringWriterFieldType(property.writeTypeInfo(), resolver);
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
  Class<?> completeWriterType() {
    return StringWriterCodec.class;
  }

  @Override
  String writeMethod() {
    return "writeString";
  }

  @Override
  String writerSlotMethod() {
    return "stringWriter";
  }

  @Override
  String memberGroupMethod() {
    return "writeStringMembers";
  }

  @Override
  String writeAnyMethod() {
    return "writeStringAny";
  }

  @Override
  int splitMemberThreshold() {
    return MIN_SPLIT_MEMBERS;
  }

  @Override
  boolean writesStringCollectionDirectly(JsonFieldInfo property) {
    return JsonCodegen.writesStringCollectionDirectly(property);
  }

  @Override
  PrefixFields prefixFields(JsonFieldInfo[] properties, boolean objectStartFused) {
    PrefixFields fields = new PrefixFields(properties.length);
    boolean commaKnown = objectStartFused;
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (usesPrefix(property)) {
        if (objectStartFused && i == 0) {
          if (!canPackUtf16Prefix(property, false)) {
            fields.name[i] = true;
          }
        } else {
          markUtf16PrefixField(property, commaKnown, fields, i);
        }
      }
      if (property.writeNull()) {
        commaKnown = true;
      }
    }
    return fields;
  }

  private static void markUtf16PrefixField(
      JsonFieldInfo property, boolean commaKnown, PrefixFields fields, int id) {
    if (!commaKnown) {
      fields.name[id] = true;
      fields.comma[id] = true;
      return;
    }
    if (!canPackUtf16Prefix(property, true)) {
      fields.comma[id] = true;
    }
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
    if (canPackUtf16Prefix(property, false)) {
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
      if (canPackUtf16Prefix(property, true)) {
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
      if (canPackUtf16Prefix(property, true)) {
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
      if (canPackUtf16Prefix(property, true)) {
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
    return fieldValue(id, "stringUtf16EnumFieldValue", value, commaKnown, index);
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

  private static Reference utf16PrefixRef(boolean comma, int id) {
    return fieldRef((comma ? "sc16" : "s16") + id, byte[].class);
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

  private static boolean canPackUtf16Prefix(JsonFieldInfo property, boolean comma) {
    int length =
        comma
            ? property.stringUtf16CommaNamePrefix().length
            : property.stringUtf16NamePrefix().length;
    return length <= Long.BYTES * 4;
  }
}
