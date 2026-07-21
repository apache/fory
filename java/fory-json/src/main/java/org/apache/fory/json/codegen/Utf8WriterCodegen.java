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

import static org.apache.fory.codegen.ExpressionUtils.eq;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.ArrayCodec;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;

final class Utf8WriterCodegen extends JsonWriterCodegen {
  private static final int MIN_SPLIT_MEMBERS = 12;

  Utf8WriterCodegen(JsonCodegen codegen) {
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
  Class<?> completeWriterType() {
    return Utf8WriterCodec.class;
  }

  @Override
  String writeMethod() {
    return "writeUtf8";
  }

  @Override
  String memberGroupMethod() {
    return "writeUtf8Members";
  }

  @Override
  String writeAnyMethod() {
    return "writeUtf8Any";
  }

  @Override
  int splitMemberThreshold() {
    return MIN_SPLIT_MEMBERS;
  }

  @Override
  PrefixFields prefixFields(JsonFieldInfo[] properties, boolean objectStartFused) {
    PrefixFields fields = new PrefixFields(properties.length);
    boolean commaKnown = objectStartFused;
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (usesPrefix(property)) {
        if (i == 0
            && !objectStartFused
            && !property.writeNull()
            && canPackObjectStartString(property)) {
          // The generated first-field branch consumes neither ordinary prefix field.
        } else if (objectStartFused && i == 0) {
          if (!canPackPrefix(property, false)) {
            fields.name[i] = true;
          }
        } else if (!commaKnown) {
          if (property.writesRawString()
              || !canUsePackedDynamicPrefix(property)
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
    return !property.writesRawString()
        && property.writeKind() == JsonFieldKind.STRING
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

  private static boolean canPackSinglePrefix(JsonFieldInfo property, boolean comma) {
    int length = comma ? property.utf8CommaNamePrefix().length : property.utf8NamePrefix().length;
    return length <= Long.BYTES;
  }
}
