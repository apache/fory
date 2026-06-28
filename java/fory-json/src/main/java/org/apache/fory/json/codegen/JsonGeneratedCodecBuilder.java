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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.apache.fory.builder.CodecBuilder;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Descriptor;
import org.apache.fory.util.record.RecordUtils;

final class JsonGeneratedCodecBuilder extends CodecBuilder {
  private final String generatedClassName;
  private final JsonFieldInfo[] properties;
  private final boolean utf8;
  private final boolean writer;
  private final boolean record;
  private final JsonCodegen codegen;

  JsonGeneratedCodecBuilder(
      JsonCodegen codegen,
      String generatedClassName,
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean utf8,
      boolean writer,
      boolean record) {
    super(new CodegenContext(), TypeRef.of(type));
    this.codegen = codegen;
    this.generatedClassName = generatedClassName;
    this.properties = properties;
    this.utf8 = utf8;
    this.writer = writer;
    this.record = record;
    ctx.setPackage(JsonCodegen.PACKAGE);
    ctx.setClassName(generatedClassName);
    ctx.setClassModifiers("final");
    ctx.addImports(JsonFieldInfo.class, JsonCodec.class, JsonTypeResolver.class);
    String[] generatedMethodNames = {
      "object", "value", "writer", "reader", "owner", "typeResolver"
    };
    for (String name : generatedMethodNames) {
      if (!ctx.containName(name)) {
        ctx.reserveName(name);
      }
    }
  }

  CodegenContext context() {
    return ctx;
  }

  @Override
  public String codecClassName(Class<?> cls) {
    return generatedClassName;
  }

  @Override
  public String genCode() {
    return writer
        ? new JsonWriterCodegen(codegen)
            .genWriterCode(this, generatedClassName, beanClass, properties, utf8)
        : new JsonReaderCodegen(codegen)
            .genReaderCode(this, generatedClassName, beanClass, properties, record);
  }

  @Override
  public Expression buildEncodeExpression() {
    return new Expression.Empty();
  }

  @Override
  public Expression buildDecodeExpression() {
    return new Expression.Empty();
  }

  private Descriptor writeDescriptor(JsonFieldInfo property) {
    Field field = property.writeField();
    return new Descriptor(field, TypeRef.of(field.getGenericType()), recordReadMethod(field), null);
  }

  private Descriptor readDescriptor(JsonFieldInfo property) {
    Field field = property.readField();
    return new Descriptor(field, TypeRef.of(field.getGenericType()), null, null);
  }

  private Method recordReadMethod(Field field) {
    if (!RecordUtils.isRecord(field.getDeclaringClass())) {
      return null;
    }
    try {
      return field.getDeclaringClass().getMethod(field.getName());
    } catch (NoSuchMethodException e) {
      throw new ForyJsonException("Cannot resolve record accessor for field " + field, e);
    }
  }

  Expression fieldValue(JsonFieldInfo property, Expression object) {
    return getFieldValue(object, writeDescriptor(property));
  }

  Expression newObject() {
    return newBean();
  }

  Expression setField(JsonFieldInfo property, Expression object, Expression value) {
    return setFieldValue(
        object,
        readDescriptor(property),
        tryInlineCast(value, TypeRef.of(property.readField().getGenericType())));
  }

  Expression setNull(JsonFieldInfo property, Expression object) {
    return setField(
        property, object, new Expression.Null(TypeRef.of(property.readRawType()), false));
  }
}
