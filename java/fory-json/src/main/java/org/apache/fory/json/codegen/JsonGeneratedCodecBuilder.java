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

import static org.apache.fory.codegen.ExpressionUtils.inline;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.apache.fory.builder.CodecBuilder;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.json.codec.JsonUnwrappedInfo.Declaration;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Descriptor;

/**
 * Common field-access and source-class builder for one concrete generated JSON capability.
 *
 * <p>Each compiler entry creates a fresh builder with a concrete generated class name. Reader or
 * writer generators select the implemented narrow capability directly; this class does not carry a
 * path enum or dispatch between modes. It reuses Fory core's field access and object-construction
 * expressions so Janino erasure remains confined to generated source without changing handwritten
 * codec generics.
 */
final class JsonGeneratedCodecBuilder extends CodecBuilder {
  private final String generatedClassName;
  private final Set<String> directMethods = new HashSet<>();

  JsonGeneratedCodecBuilder(String generatedPackage, String generatedClassName, Class<?> type) {
    super(new CodegenContext(), TypeRef.of(type));
    this.generatedClassName = generatedClassName;
    ctx.setPackage(generatedPackage);
    ctx.setClassName(generatedClassName);
    ctx.setClassModifiers("final");
    ctx.addImports(JsonFieldInfo.class);
    String[] generatedMethodNames = {"object", "value", "writer", "reader"};
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
    return ctx.genCode();
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
    return writeDescriptor(property.writeField());
  }

  private Descriptor writeDescriptor(Field field) {
    return new Descriptor(field, TypeRef.of(field.getGenericType()), null, null);
  }

  private Descriptor readDescriptor(JsonFieldInfo property) {
    return readDescriptor(property.readField());
  }

  private Descriptor readDescriptor(Field field) {
    return new Descriptor(field, TypeRef.of(field.getGenericType()), null, null);
  }

  Expression fieldValue(JsonFieldInfo property, Expression object) {
    Method getter = property.writeGetter();
    if (getter != null) {
      // JSON writers check the returned member value directly. Requesting expression-level null
      // state here only emits an unused boolean for each nullable getter and bloats generated
      // object writers enough to hurt C2 inlining.
      if (DirectMethodCodegen.sourceNameable(getter)) {
        return new Expression.Invoke(
            object,
            getter.getName(),
            property.name(),
            TypeRef.of(getter.getGenericReturnType()),
            false);
      }
      String name = DirectMethodCodegen.getterName(getter);
      addDirectMethod(name, getter.getReturnType(), getter.getDeclaringClass(), "target");
      return directInvoke(name, property.name(), TypeRef.of(getter.getGenericReturnType()), object);
    }
    return getFieldValue(object, writeDescriptor(property));
  }

  Expression anyValue(Field field, Expression object) {
    return getFieldValue(object, writeDescriptor(field));
  }

  Expression unwrappedValue(Declaration declaration, Expression object) {
    Method getter = declaration.writeAccessor().getter();
    if (getter != null) {
      if (!DirectMethodCodegen.sourceNameable(getter)) {
        String name = DirectMethodCodegen.getterName(getter);
        addDirectMethod(name, getter.getReturnType(), getter.getDeclaringClass(), "target");
        return directInvoke(
            name, declaration.javaName(), TypeRef.of(getter.getGenericReturnType()), object);
      }
      return new Expression.Invoke(
          object,
          getter.getName(),
          declaration.javaName(),
          TypeRef.of(getter.getGenericReturnType()),
          false);
    }
    return getFieldValue(object, writeDescriptor(declaration.writeAccessor().field()));
  }

  Expression newObject() {
    return newBean();
  }

  Expression setField(JsonFieldInfo property, Expression object, Expression value) {
    // A parsed member value has one store owner. Keep that expression inline so generated readers
    // do not add a local store/load pair before the setter, field, or VarHandle write.
    value = inline(value);
    Method setter = property.readSetter();
    if (setter != null) {
      Class<?> rawType = setter.getParameterTypes()[0];
      TypeRef<?> typeRef = TypeRef.of(setter.getGenericParameterTypes()[0]);
      if (!rawType.isAssignableFrom(value.type().getRawType())) {
        value = tryInlineCast(value, typeRef);
      }
      if (DirectMethodCodegen.sourceNameable(setter)) {
        return new Expression.Invoke(object, setter.getName(), value);
      }
      String name = DirectMethodCodegen.setterName(setter);
      addDirectMethod(
          name,
          void.class,
          setter.getDeclaringClass(),
          "target",
          setter.getParameterTypes()[0],
          "value");
      return directInvoke(name, "", TypeRef.of(void.class), object, value);
    }
    return setFieldValue(
        object,
        readDescriptor(property),
        tryInlineCast(value, TypeRef.of(property.readField().getGenericType())));
  }

  Expression setNull(JsonFieldInfo property, Expression object) {
    return setField(
        property, object, new Expression.Null(TypeRef.of(property.readRawType()), false));
  }

  Expression setAnyField(Field field, Expression object, Expression value) {
    return setFieldValue(
        object,
        readDescriptor(field),
        tryInlineCast(inline(value), TypeRef.of(field.getGenericType())));
  }

  Expression setUnwrapped(Declaration declaration, Expression object, Expression value) {
    value = inline(value);
    Method setter = declaration.readAccessor().setter();
    if (setter != null) {
      Class<?> rawType = setter.getParameterTypes()[0];
      TypeRef<?> typeRef = TypeRef.of(setter.getGenericParameterTypes()[0]);
      if (!rawType.isAssignableFrom(value.type().getRawType())) {
        value = tryInlineCast(value, typeRef);
      }
      if (DirectMethodCodegen.sourceNameable(setter)) {
        return new Expression.Invoke(object, setter.getName(), value);
      }
      String name = DirectMethodCodegen.setterName(setter);
      addDirectMethod(
          name,
          void.class,
          setter.getDeclaringClass(),
          "target",
          setter.getParameterTypes()[0],
          "value");
      return directInvoke(name, "", TypeRef.of(void.class), object, value);
    }
    return setFieldValue(
        object,
        readDescriptor(declaration.readAccessor().field()),
        tryInlineCast(value, TypeRef.of(declaration.readAccessor().field().getGenericType())));
  }

  void addDirectMethod(String name, Class<?> returnType, Object... parameters) {
    if (directMethods.add(name)) {
      ctx.addMethod("final", name, "throw new AssertionError();", returnType, parameters);
    }
  }

  Expression directInvoke(String name, String valueName, TypeRef<?> type, Expression... arguments) {
    return new Expression.Invoke(
        new Expression.Reference("this", TypeRef.of(Object.class)),
        name,
        valueName,
        type,
        false,
        false,
        arguments);
  }

  Expression valueOperation(Method method, Expression... values) {
    Class<?>[] targetParameters = method.getParameterTypes();
    boolean isStatic = Modifier.isStatic(method.getModifiers());
    int expected = targetParameters.length + (isStatic ? 0 : 1);
    if (values.length != expected) {
      throw new IllegalArgumentException("Invalid generated value operation " + method);
    }
    Object[] parameters = new Object[expected << 1];
    Expression[] arguments = new Expression[expected];
    for (int i = 0; i < expected; i++) {
      Class<?> type =
          isStatic
              ? targetParameters[i]
              : i == 0 ? method.getDeclaringClass() : targetParameters[i - 1];
      parameters[i << 1] = type;
      parameters[(i << 1) + 1] = "value" + i;
      arguments[i] = tryInlineCast(inline(values[i]), TypeRef.of(type));
    }
    String name = DirectMethodCodegen.valueOperationName(method);
    addDirectMethod(name, method.getReturnType(), parameters);
    return directInvoke(name, "value", TypeRef.of(method.getReturnType()), arguments);
  }
}
