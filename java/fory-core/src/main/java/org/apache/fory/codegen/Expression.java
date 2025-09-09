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

package org.apache.fory.codegen;

import static org.apache.fory.codegen.Code.ExprCode;
import static org.apache.fory.codegen.Code.LiteralValue;
import static org.apache.fory.codegen.Code.LiteralValue.FalseLiteral;
import static org.apache.fory.codegen.Code.LiteralValue.TrueLiteral;
import static org.apache.fory.codegen.CodeGenerator.alignIndent;
import static org.apache.fory.codegen.CodeGenerator.appendNewlineIfNeeded;
import static org.apache.fory.codegen.CodeGenerator.indent;
import static org.apache.fory.collection.Collections.ofArrayList;
import static org.apache.fory.type.TypeUtils.BOOLEAN_TYPE;
import static org.apache.fory.type.TypeUtils.CLASS_TYPE;
import static org.apache.fory.type.TypeUtils.ITERABLE_TYPE;
import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_BOOLEAN_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_BYTE_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_INT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_LONG_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_SHORT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_VOID_TYPE;
import static org.apache.fory.type.TypeUtils.STRING_TYPE;
import static org.apache.fory.type.TypeUtils.VOID_TYPE;
import static org.apache.fory.type.TypeUtils.boxedType;
import static org.apache.fory.type.TypeUtils.defaultValue;
import static org.apache.fory.type.TypeUtils.getArrayType;
import static org.apache.fory.type.TypeUtils.getElementType;
import static org.apache.fory.type.TypeUtils.getRawType;
import static org.apache.fory.type.TypeUtils.getSizeOfPrimitiveType;
import static org.apache.fory.type.TypeUtils.isPrimitive;
import static org.apache.fory.type.TypeUtils.maxType;
import static org.apache.fory.util.Preconditions.checkArgument;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.function.Functions;
import org.apache.fory.util.function.SerializableBiFunction;
import org.apache.fory.util.function.SerializableFunction;
import org.apache.fory.util.function.SerializableSupplier;
import org.apache.fory.util.function.SerializableTriFunction;

/**
 * An expression represents a piece of code evaluation logic which can be generated to valid java
 * code. Expression can be used to compose complex code logic.
 *
 * <p>TODO(chaokunyang) refactor expression into Expression and Stmt with a common class Node.
 * Expression will have a value, the stmt won't have value. If/While/For/doWhile/ForEach are
 * statements instead of expression.
 *
 * <p>Note that all dependent expression field are marked as non-final, so when split expression
 * tree into subtrees, we can replace dependent expression fields in subtree root nodes with {@link
 * Reference}.
 */
public interface Expression {

  /**
   * Returns the Class<?> of the result of evaluating this expression. It is invalid to query the
   * type of unresolved expression (i.e., when `resolved` == false).
   */
  TypeRef<?> type();

  /**
   * If expression is already generated in this context, returned exprCode won't contains code, so
   * we can reuse/elimination expression code.
   */
  default ExprCode genCode(CodegenContext ctx) {
    // Ctx already contains expression code, which means that the code to evaluate it has already
    // been added before. In that case, we just reuse it.
    ExprState exprState = ctx.exprState.get(this);
    if (exprState != null) {
      exprState.incAccessCount();
      return exprState.getExprCode();
    } else {
      ExprCode genCode = doGenCode(ctx);
      ctx.exprState.put(this, new ExprState(new ExprCode(genCode.isNull(), genCode.value())));
      if (this instanceof Inlineable) {
        if (!((Inlineable) this).inlineCall) {
          Preconditions.checkArgument(
              StringUtils.isNotBlank(genCode.code()),
              "Expression %s has empty code %s",
              this,
              genCode);
        }
      }
      return genCode;
    }
  }

  /**
   * Used when Expression is requested to doGenCode.
   *
   * @param ctx a [[CodegenContext]]
   * @return an [[ExprCode]] containing the Java source code to generate the given expression
   */
  ExprCode doGenCode(CodegenContext ctx);

  default boolean nullable() {
    return false;
  }

  // ###########################################################
  // ####################### Expressions #######################
  // ###########################################################

  abstract class AbstractExpression implements Expression {
    protected final transient List<Expression> inputs;

    protected AbstractExpression(Expression input) {
      this.inputs = Collections.singletonList(input);
    }

    protected AbstractExpression(Expression[] inputs) {
      this.inputs = Arrays.asList(inputs);
    }

    protected AbstractExpression(List<Expression> inputs) {
      this.inputs = inputs;
    }
  }

  abstract class Inlineable extends AbstractExpression {
    protected boolean inlineCall = false;

    protected Inlineable(Expression input) {
      super(input);
    }

    protected Inlineable(Expression[] inputs) {
      super(inputs);
    }

    protected Inlineable(List<Expression> inputs) {
      super(inputs);
    }

    public Inlineable inline() {
      return inline(true);
    }

    public Inlineable inline(boolean inlineCall) {
      this.inlineCall = inlineCall;
      return this;
    }
  }

  /** An expression that have a value as the result of the evaluation. */
  abstract class ValueExpression extends Inlineable {
    // set to others to get a more context-dependent variable name.
    public String valuePrefix = "value";

    protected ValueExpression(Expression[] inputs) {
      super(inputs);
    }

    public String isNullPrefix() {
      return "is" + StringUtils.capitalize(valuePrefix) + "Null";
    }
  }

  /**
   * A ListExpression is a list of expressions. Use last expression's type/nullable/value/IsNull to
   * represent ListExpression's type/nullable/value/IsNull.
   */
  class ListExpression extends AbstractExpression {
    private final List<Expression> expressions;
    private Expression last;

    public ListExpression(Expression... expressions) {
      this(new ArrayList<>(Arrays.asList(expressions)));
    }

    public ListExpression(List<Expression> expressions) {
      super(expressions);
      this.expressions = expressions;
      if (!this.expressions.isEmpty()) {
        this.last = this.expressions.get(this.expressions.size() - 1);
      }
    }

    @Override
    public TypeRef<?> type() {
      Preconditions.checkNotNull(last);
      return last.type();
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      if (last == null) {
        return new ExprCode(null, null, null);
      }

      StringBuilder codeBuilder = new StringBuilder();
      boolean hasCode = false;
      for (Expression expr : expressions) {
        ExprCode code = expr.genCode(ctx);
        if (StringUtils.isNotBlank(code.code())) {
          appendNewlineIfNeeded(codeBuilder);
          codeBuilder.append(code.code());
          hasCode = true;
        }
      }
      ExprCode lastExprCode = last.genCode(ctx);
      String code = codeBuilder.toString();
      if (!hasCode) {
        code = null;
      }
      return new ExprCode(code, lastExprCode.isNull(), lastExprCode.value());
    }

    @Override
    public boolean nullable() {
      return last.nullable();
    }

    public List<Expression> expressions() {
      return expressions;
    }

    public Expression last() {
      return expressions.get(expressions.size() - 1);
    }

    public ListExpression add(Expression expr, boolean copy) {
      Preconditions.checkNotNull(expr);
      if (copy) {
        ListExpression listExpression = new ListExpression();
        listExpression.addAll(expressions);
        listExpression.add(expr);
        return listExpression;
      }
      return add(expr);
    }

    public ListExpression add(Expression expr) {
      Preconditions.checkNotNull(expr);
      this.expressions.add(expr);
      this.last = expr;
      return this;
    }

    public ListExpression add(Expression expr, Expression... exprs) {
      add(expr);
      return addAll(Arrays.asList(exprs));
    }

    public ListExpression addAll(List<Expression> exprs) {
      Preconditions.checkNotNull(exprs);
      this.expressions.addAll(exprs);
      if (!exprs.isEmpty()) {
        this.last = exprs.get(exprs.size() - 1);
      }
      return this;
    }

    @Override
    public String toString() {
      return expressions.stream().map(Object::toString).collect(Collectors.joining(","));
    }
  }

  class Variable extends AbstractExpression {
    private final String namePrefix;
    private Expression from;

    public Variable(String namePrefix, Expression from) {
      super(from);
      this.namePrefix = namePrefix;
      this.from = from;
    }

    @Override
    public TypeRef<?> type() {
      return from.type();
    }

    @Override
    public boolean nullable() {
      return from.nullable();
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      ExprCode targetExprCode = from.genCode(ctx);
      if (StringUtils.isNotBlank(targetExprCode.code())) {
        codeBuilder.append(targetExprCode.code()).append('\n');
      }
      String name = ctx.newName(namePrefix);
      String decl =
          StringUtils.format(
              "${type} ${name} = ${from};",
              "type",
              ctx.type(type()),
              "name",
              name,
              "from",
              targetExprCode.value());
      codeBuilder.append(decl);
      return new ExprCode(
          codeBuilder.toString(),
          targetExprCode.isNull(),
          Code.variable(type().getRawType(), name));
    }

    @Override
    public String toString() {
      return String.format("%s %s = %s;", type(), namePrefix, from);
    }
  }

  class Literal extends AbstractExpression {
    public static final Literal True = new Literal(true, PRIMITIVE_BOOLEAN_TYPE);
    public static final Literal False = new Literal(false, PRIMITIVE_BOOLEAN_TYPE);

    private final Object value;
    private final TypeRef<?> type;

    public Literal(String value) {
      super(new Expression[0]);
      this.value = value;
      this.type = STRING_TYPE;
    }

    public Literal(Object value, TypeRef<?> type) {
      super(new Expression[0]);
      this.value = value;
      this.type = type;
    }

    public static Literal ofBoolean(boolean v) {
      return v ? True : False;
    }

    public static Literal ofByte(short v) {
      return new Literal(v, PRIMITIVE_BYTE_TYPE);
    }

    public static Literal ofShort(short v) {
      return new Literal(v, PRIMITIVE_SHORT_TYPE);
    }

    public static Literal ofInt(int v) {
      return new Literal(v, PRIMITIVE_INT_TYPE);
    }

    public static Literal ofLong(long v) {
      return new Literal(v, PRIMITIVE_LONG_TYPE);
    }

    public static Literal ofClass(Class<?> v) {
      return new Literal(v, CLASS_TYPE);
    }

    public static Literal ofString(String v) {
      return new Literal(v, STRING_TYPE);
    }

    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      Class<?> javaType = getRawType(type);
      if (isPrimitive(javaType)) {
        javaType = boxedType(javaType);
      }
      if (value == null) {
        LiteralValue defaultLiteral = new LiteralValue(javaType, defaultValue(javaType));
        return new ExprCode(null, TrueLiteral, defaultLiteral);
      } else {
        if (javaType == String.class) {
          return new ExprCode(FalseLiteral, new LiteralValue("\"" + value + "\""));
        } else if (javaType == Boolean.class || javaType == Integer.class) {
          return new ExprCode(null, FalseLiteral, new LiteralValue(javaType, value.toString()));
        } else if (javaType == Float.class) {
          Float f = (Float) value;
          if (f.isNaN()) {
            return new ExprCode(FalseLiteral, new LiteralValue(javaType, "Float.NaN"));
          } else if (f.equals(Float.POSITIVE_INFINITY)) {
            return new ExprCode(
                FalseLiteral, new LiteralValue(javaType, "Float.POSITIVE_INFINITY"));
          } else if (f.equals(Float.NEGATIVE_INFINITY)) {
            return new ExprCode(
                FalseLiteral, new LiteralValue(javaType, "Float.NEGATIVE_INFINITY"));
          } else {
            return new ExprCode(FalseLiteral, new LiteralValue(javaType, String.format("%fF", f)));
          }
        } else if (javaType == Double.class) {
          Double d = (Double) value;
          if (d.isNaN()) {
            return new ExprCode(FalseLiteral, new LiteralValue(javaType, "Double.NaN"));
          } else if (d.equals(Double.POSITIVE_INFINITY)) {
            return new ExprCode(
                FalseLiteral, new LiteralValue(javaType, "Double.POSITIVE_INFINITY"));
          } else if (d.equals(Double.NEGATIVE_INFINITY)) {
            return new ExprCode(
                FalseLiteral, new LiteralValue(javaType, "Double.NEGATIVE_INFINITY"));
          } else {
            return new ExprCode(FalseLiteral, new LiteralValue(javaType, String.format("%fD", d)));
          }
        } else if (javaType == Byte.class) {
          return new ExprCode(
              FalseLiteral, Code.exprValue(javaType, String.format("(%s)%s", "byte", value)));
        } else if (javaType == Short.class) {
          return new ExprCode(
              FalseLiteral, Code.exprValue(javaType, String.format("(%s)%s", "short", value)));
        } else if (javaType == Long.class) {
          return new ExprCode(
              FalseLiteral,
              new LiteralValue(javaType, String.format("%dL", ((Number) (value)).longValue())));
        } else if (isPrimitive(javaType)) {
          return new ExprCode(FalseLiteral, new LiteralValue(javaType, String.valueOf(value)));
        } else if (javaType == Class.class) {
          String v;
          Class<?> valueClass = (Class<?>) value;
          if (valueClass.isArray()) {
            v = String.format("%s.class", TypeUtils.getArrayClass((Class<?>) value));
          } else {
            v = String.format("%s.class", ((Class<?>) (value)).getName());
          }
          return new ExprCode(FalseLiteral, new LiteralValue(javaType, v));
        } else {
          throw new UnsupportedOperationException("Unsupported type " + javaType);
        }
      }
    }

    @Override
    public String toString() {
      if (value == null) {
        return "null";
      } else {
        return value.toString();
      }
    }
  }

  class Null extends AbstractExpression {
    private TypeRef<?> type;
    private final boolean typedNull;

    public Null(TypeRef<?> type) {
      this(type, false);
    }

    public Null(TypeRef<?> type, boolean typedNull) {
      super(new Expression[0]);
      this.type = type;
      this.typedNull = typedNull;
    }

    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      Class<?> javaType = getRawType(type);
      LiteralValue defaultLiteral =
          new LiteralValue(javaType, typedNull ? String.format("((%s)null)", type) : "null");
      return new ExprCode(null, TrueLiteral, defaultLiteral);
    }

    @Override
    public String toString() {
      return String.format("(%s)(null)", getRawType(type).getSimpleName());
    }
  }

  /** A Reference is a variable/field that can be accessed in the expression's CodegenContext. */
  class Reference extends AbstractExpression {
    private String name;
    private final TypeRef<?> type;
    private final boolean nullable;
    private final boolean fieldRef;

    public Reference(String name) {
      this(name, OBJECT_TYPE);
    }

    public Reference(String name, TypeRef<?> type) {
      this(name, type, false);
    }

    public Reference(String name, TypeRef<?> type, boolean nullable) {
      this(name, type, nullable, false);
    }

    public Reference(String name, TypeRef<?> type, boolean nullable, boolean fieldRef) {
      super(new Expression[0]);
      this.name = name;
      this.type = type;
      this.nullable = nullable;
      this.fieldRef = fieldRef;
    }

    public static Reference fieldRef(String name, TypeRef<?> type) {
      return new Reference(name, type, false, true);
    }

    public void setName(String name) {
      this.name = name;
    }

    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      if (nullable) {
        String isNull = ctx.newName("isNull");
        String code =
            StringUtils.format(
                "boolean ${isNull} = ${name} == null;", "isNull", isNull, "name", name);
        return new ExprCode(
            code, Code.isNullVariable(isNull), Code.variable(getRawType(type), name));
      } else {
        return new ExprCode(FalseLiteral, Code.variable(getRawType(type), name));
      }
    }

    @Override
    public boolean nullable() {
      return nullable;
    }

    public String name() {
      return name;
    }

    public boolean isFieldRef() {
      return fieldRef;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  class Empty extends AbstractExpression {

    public Empty() {
      super(new Expression[0]);
    }

    @Override
    public TypeRef<?> type() {
      return PRIMITIVE_VOID_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      return new ExprCode("");
    }
  }

  class Block extends AbstractExpression {
    private final String code;

    public Block(String code) {
      super(new Expression[0]);
      this.code = code;
    }

    @Override
    public TypeRef<?> type() {
      return PRIMITIVE_VOID_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      return new ExprCode(code, null, null);
    }
  }

  class ForceEvaluate extends AbstractExpression {
    private Expression expression;

    public ForceEvaluate(Expression expression) {
      super(expression);
      this.expression = expression;
    }

    @Override
    public TypeRef<?> type() {
      return expression.type();
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      return expression.doGenCode(ctx);
    }

    @Override
    public boolean nullable() {
      return expression.nullable();
    }

    @Override
    public String toString() {
      return expression.toString();
    }
  }

  class FieldValue extends Inlineable {
    private Expression targetObject;
    private final String fieldName;
    private final TypeRef<?> type;
    private final boolean fieldNullable;

    public FieldValue(Expression targetObject, String fieldName, TypeRef<?> type) {
      this(targetObject, fieldName, type, !type.isPrimitive(), false);
    }

    public FieldValue(
        Expression targetObject,
        String fieldName,
        TypeRef<?> type,
        boolean fieldNullable,
        boolean inline) {
      super(targetObject);
      Preconditions.checkArgument(type != null);
      this.targetObject = targetObject;
      this.fieldName = fieldName;
      this.type = type;
      this.fieldNullable = fieldNullable;
      this.inlineCall = inline;
      if (inline) {
        Preconditions.checkArgument(!fieldNullable);
      }
    }

    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      ExprCode targetExprCode = targetObject.genCode(ctx);
      if (StringUtils.isNotBlank(targetExprCode.code())) {
        codeBuilder.append(targetExprCode.code()).append("\n");
      }
      Class<?> rawType = getRawType(type);
      // although isNull is not always used, we place it outside and get freshNames simultaneously
      // to have same suffix, thus get more readability.
      String[] freshNames =
          ctx.newNames(fieldName, "is" + StringUtils.capitalize(fieldName) + "Null");
      String value = freshNames[0];
      String isNull = freshNames[1];
      if (inlineCall) {
        String inlinedValue =
            StringUtils.format(
                "${target}.${fieldName}", "target", targetExprCode.value(), "fieldName", fieldName);
        return new ExprCode(
            codeBuilder.toString(), FalseLiteral, Code.variable(rawType, inlinedValue));
      } else if (fieldNullable) {
        codeBuilder.append(String.format("boolean %s = false;\n", isNull));
        String code =
            StringUtils.format(
                "${type} ${value} = ${target}.${fieldName};\n",
                "type",
                ctx.type(type),
                "value",
                value,
                "defaultValue",
                defaultValue(rawType),
                "target",
                targetExprCode.value(),
                "fieldName",
                fieldName);
        codeBuilder.append(code);

        String nullCode =
            StringUtils.format(
                "if (${value} == null) {\n" + "    ${isNull} = true;\n" + "}",
                "value",
                value,
                "isNull",
                isNull);
        codeBuilder.append(nullCode);
        return new ExprCode(
            codeBuilder.toString(), Code.isNullVariable(isNull), Code.variable(rawType, value));
      } else {
        String code =
            StringUtils.format(
                "${type} ${value} = ${target}.${fieldName};",
                "type",
                ctx.type(type),
                "value",
                value,
                "target",
                targetExprCode.value(),
                "fieldName",
                fieldName);
        codeBuilder.append(code);
        return new ExprCode(codeBuilder.toString(), FalseLiteral, Code.variable(rawType, value));
      }
    }

    @Override
    public boolean nullable() {
      return fieldNullable;
    }

    @Override
    public String toString() {
      return String.format("(%s).%s", targetObject, fieldName);
    }
  }

  class SetField extends AbstractExpression {
    private Expression targetObject;
    private final String fieldName;
    private Expression fieldValue;

    public SetField(Expression targetObject, String fieldName, Expression fieldValue) {
      super(targetObject);
      this.targetObject = targetObject;
      this.fieldName = fieldName;
      this.fieldValue = fieldValue;
    }

    @Override
    public TypeRef<?> type() {
      return PRIMITIVE_VOID_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      ExprCode targetExprCode = targetObject.genCode(ctx);
      ExprCode fieldValueExprCode = fieldValue.genCode(ctx);
      Stream.of(targetExprCode, fieldValueExprCode)
          .forEach(
              exprCode -> {
                if (StringUtils.isNotBlank(exprCode.code())) {
                  codeBuilder.append(exprCode.code()).append('\n');
                }
              });
      // don't check whether ${target} null, place it in if expression
      String assign =
          StringUtils.format(
              "${target}.${fieldName} = ${fieldValue};",
              "target",
              targetExprCode.value(),
              "fieldName",
              fieldName,
              "fieldValue",
              fieldValueExprCode.value());
      codeBuilder.append(assign);
      return new ExprCode(codeBuilder.toString(), null, null);
    }

    public String toString() {
      return String.format("SetField(%s, %s, %s)", targetObject, fieldName, fieldValue);
    }
  }

  /**
   * An expression to set up a stub in expression tree, so later tree building can replace it with
   * other expression.
   */
  class ReplaceStub extends AbstractExpression {
    private Expression targetObject;

    public ReplaceStub() {
      super(new Expression[0]);
    }

    public ReplaceStub(Expression targetObject) {
      super(targetObject);
      this.targetObject = targetObject;
    }

    @Override
    public TypeRef<?> type() {
      return targetObject != null ? targetObject.type() : PRIMITIVE_VOID_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      return targetObject != null ? targetObject.doGenCode(ctx) : new ExprCode("", null, null);
    }

    @Override
    public boolean nullable() {
      return targetObject != null && targetObject.nullable();
    }

    public void setTargetObject(Expression targetObject) {
      this.targetObject = targetObject;
    }
  }

  class Cast extends Inlineable {
    private Expression targetObject;
    private final String castedValueNamePrefix;
    private final TypeRef<?> type;
    private final boolean ignoreUpcast;

    public Cast(Expression targetObject, TypeRef<?> type) {
      this(targetObject, type, "castedValue", true, true);
    }

    public Cast(Expression targetObject, TypeRef<?> type, String castedValueNamePrefix) {
      this(targetObject, type, castedValueNamePrefix, false, true);
    }

    public Cast(
        Expression targetObject,
        TypeRef<?> type,
        String castedValueNamePrefix,
        boolean inline,
        boolean ignoreUpcast) {
      super(targetObject);
      this.targetObject = targetObject;
      this.type = type;
      this.castedValueNamePrefix = castedValueNamePrefix;
      inlineCall = inline;
      this.ignoreUpcast = ignoreUpcast;
      checkArgument(!ReflectionUtils.isPrivate(type), "Type %s is private", type);
      checkArgument(
          TypeUtils.getRawType(type).getCanonicalName() != null,
          "Local/Anonymous type %s isn't supported.",
          type);
    }

    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      if (ignoreUpcast
          && (targetObject.type().equals(type)
              || getRawType(type).isAssignableFrom(getRawType((targetObject.type()))))) {
        return targetObject.genCode(ctx);
      }
      StringBuilder codeBuilder = new StringBuilder();
      Class<?> rawType = getRawType(type);
      ExprCode targetExprCode = targetObject.genCode(ctx);
      if (StringUtils.isNotBlank(targetExprCode.code())) {
        codeBuilder.append(targetExprCode.code());
      }
      // workaround: Cannot cast "java.lang.Object" to "long". then use java auto box/unbox.
      String withCast = ctx.type(type);
      if (type.isPrimitive() && !targetObject.type().isPrimitive()) {
        withCast = ctx.type(boxedType(getRawType(type)));
      }
      if (inlineCall) {
        String value =
            StringUtils.format(
                "((${withCast})${target})", "withCast", withCast, "target", targetExprCode.value());
        return new ExprCode(
            codeBuilder.toString(), targetExprCode.isNull(), Code.variable(rawType, value));
      } else {
        String castedValue = ctx.newName(castedValueNamePrefix);
        if (StringUtils.isNotBlank(targetExprCode.code())) {
          codeBuilder.append("\n");
        }
        String cast =
            StringUtils.format(
                "${type} ${castedValue} = (${withCast})${target};",
                "type",
                ctx.type(type),
                "withCast",
                withCast,
                "castedValue",
                castedValue,
                "target",
                targetExprCode.value());
        codeBuilder.append(cast);
        return new ExprCode(
            codeBuilder.toString(), targetExprCode.isNull(), Code.variable(rawType, castedValue));
      }
    }

    @Override
    public Cast inline(boolean inlineCall) {
      if (!inlineCall) {
        AbstractExpression expr = (AbstractExpression) targetObject;
        if (expr instanceof Inlineable && expr.inputs.size() > 1) {
          ((Inlineable) targetObject).inlineCall = false;
        }
      }
      return (Cast) super.inline(inlineCall);
    }

    @Override
    public boolean nullable() {
      return targetObject.nullable();
    }

    @Override
    public String toString() {
      return String.format("(%s)%s", type, targetObject);
    }
  }

  abstract class BaseInvoke extends Inlineable {
    final String functionName;
    final TypeRef<?> type;
    Expression[] arguments;
    String returnNamePrefix;
    final boolean returnNullable;
    boolean needTryCatch;

    public BaseInvoke(
        String functionName,
        TypeRef<?> type,
        Expression[] arguments,
        String returnNamePrefix,
        boolean returnNullable,
        boolean inline,
        boolean needTryCatch) {
      super(arguments);
      this.functionName = functionName;
      this.type = type;
      this.arguments = arguments;
      this.returnNamePrefix = returnNamePrefix;
      this.returnNullable = returnNullable;
      inlineCall = inline;
      this.needTryCatch = needTryCatch;
    }
  }

  class Invoke extends BaseInvoke {
    public Expression targetObject;

    /** Invoke don't return value, this is a procedure call for side effect. */
    public Invoke(Expression targetObject, String functionName, Expression... arguments) {
      this(targetObject, functionName, PRIMITIVE_VOID_TYPE, false, arguments);
    }

    /** Invoke don't accept arguments. */
    public Invoke(Expression targetObject, String functionName, TypeRef<?> type) {
      this(targetObject, functionName, type, false);
    }

    /** Invoke don't accept arguments. */
    public Invoke(
        Expression targetObject, String functionName, String returnNamePrefix, TypeRef<?> type) {
      this(targetObject, functionName, returnNamePrefix, type, false);
    }

    public Invoke(
        Expression targetObject, String functionName, TypeRef<?> type, Expression... arguments) {
      this(targetObject, functionName, "", type, false, arguments);
    }

    public Invoke(
        Expression targetObject,
        String functionName,
        TypeRef<?> type,
        boolean returnNullable,
        Expression... arguments) {
      this(targetObject, functionName, "", type, returnNullable, arguments);
    }

    public Invoke(
        Expression targetObject,
        String functionName,
        String returnNamePrefix,
        TypeRef<?> type,
        boolean returnNullable,
        Expression... arguments) {
      this(
          targetObject,
          functionName,
          returnNamePrefix,
          type,
          returnNullable,
          ReflectionUtils.hasException(getRawType(targetObject.type()), functionName),
          arguments);
    }

    public Invoke(
        Expression targetObject,
        String functionName,
        String returnNamePrefix,
        TypeRef<?> type,
        boolean returnNullable,
        boolean needTryCatch,
        Expression... arguments) {
      super(functionName, type, arguments, returnNamePrefix, returnNullable, false, needTryCatch);
      this.targetObject = targetObject;
    }

    public static Invoke inlineInvoke(
        Expression targetObject, String functionName, TypeRef<?> type, Expression... arguments) {
      Invoke invoke = new Invoke(targetObject, functionName, type, false, arguments);
      invoke.inlineCall = true;
      return invoke;
    }

    public static Invoke inlineInvoke(
        Expression targetObject,
        String functionName,
        TypeRef<?> type,
        boolean needTryCatch,
        Expression... arguments) {
      Invoke invoke =
          new Invoke(targetObject, functionName, "", type, false, needTryCatch, arguments);
      invoke.inlineCall = true;
      return invoke;
    }

    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      ExprCode targetExprCode = targetObject.genCode(ctx);
      if (StringUtils.isNotBlank(targetExprCode.code())) {
        codeBuilder.append(targetExprCode.code());
        codeBuilder.append("\n");
      }
      int len = arguments.length;
      StringBuilder argsBuilder = new StringBuilder();
      if (len > 0) {
        for (int i = 0; i < len; i++) {
          Expression argExpr = arguments[i];
          ExprCode argExprCode = argExpr.genCode(ctx);
          if (StringUtils.isNotBlank(argExprCode.code())) {
            codeBuilder.append(argExprCode.code()).append("\n");
          }
          if (i != 0) {
            argsBuilder.append(", ");
          }
          argsBuilder.append(argExprCode.value());
        }
      }
      if (!inlineCall && type != null && !PRIMITIVE_VOID_TYPE.equals(type)) {
        Class<?> rawType = getRawType(type);
        if (StringUtils.isBlank(returnNamePrefix)) {
          returnNamePrefix = ctx.namePrefix(getRawType(type));
        }
        String[] freshNames = ctx.newNames(returnNamePrefix, returnNamePrefix + "IsNull");
        String value = freshNames[0];
        String isNull = freshNames[1];
        if (returnNullable) {
          codeBuilder.append(String.format("boolean %s = false;\n", isNull));
          String callCode =
              ExpressionUtils.callFunc(
                  ctx.type(type),
                  value,
                  targetExprCode.value().code(),
                  functionName,
                  argsBuilder.toString(),
                  needTryCatch);
          codeBuilder.append(callCode).append('\n');
        } else {
          String callCode =
              ExpressionUtils.callFunc(
                  ctx.type(type),
                  value,
                  targetExprCode.value().code(),
                  functionName,
                  argsBuilder.toString(),
                  needTryCatch);
          codeBuilder.append(callCode);
        }

        if (returnNullable) {
          String nullCode =
              StringUtils.format(
                  "if (${value} == null) {\n" + "    ${isNull} = true;\n" + "}",
                  "value",
                  value,
                  "isNull",
                  isNull);
          codeBuilder.append(nullCode);
          return new ExprCode(
              codeBuilder.toString(), Code.isNullVariable(isNull), Code.variable(rawType, value));
        } else {
          return new ExprCode(codeBuilder.toString(), FalseLiteral, Code.variable(rawType, value));
        }
      } else if (inlineCall) {
        CodeGenerator.stripIfHasLastNewline(codeBuilder);
        String call =
            ExpressionUtils.callFunc(
                targetExprCode.value().code(), functionName, argsBuilder.toString(), needTryCatch);
        call = call.substring(0, call.length() - 1);
        return new ExprCode(codeBuilder.toString(), null, Code.variable(getRawType(type), call));
      } else {
        String call =
            ExpressionUtils.callFunc(
                targetExprCode.value().code(), functionName, argsBuilder.toString(), needTryCatch);
        codeBuilder.append(call);
        return new ExprCode(codeBuilder.toString(), null, null);
      }
    }

    @Override
    public boolean nullable() {
      return returnNullable;
    }

    @Override
    public String toString() {
      return String.format("%s.%s", targetObject, functionName);
    }
  }

  class StaticInvoke extends BaseInvoke {
    private final Class<?> staticObject;

    public StaticInvoke(Class<?> staticObject, String functionName, TypeRef<?> type) {
      this(staticObject, functionName, type, false);
    }

    public StaticInvoke(Class<?> staticObject, String functionName, Expression... arguments) {
      this(staticObject, functionName, PRIMITIVE_VOID_TYPE, false, arguments);
    }

    public StaticInvoke(
        Class<?> staticObject, String functionName, TypeRef<?> type, Expression... arguments) {
      this(staticObject, functionName, "", type, false, arguments);
    }

    public StaticInvoke(
        Class<?> staticObject,
        String functionName,
        TypeRef<?> type,
        boolean returnNullable,
        Expression... arguments) {
      this(staticObject, functionName, "", type, returnNullable, arguments);
    }

    public StaticInvoke(
        Class<?> staticObject,
        String functionName,
        String returnNamePrefix,
        TypeRef<?> type,
        boolean returnNullable,
        Expression... arguments) {
      this(staticObject, functionName, returnNamePrefix, type, returnNullable, false, arguments);
    }

    /**
     * static invoke method.
     *
     * @param staticObject The target of the static call
     * @param functionName The name of the method to call
     * @param returnNamePrefix returnNamePrefix
     * @param type return type of the function call
     * @param returnNullable When false, indicating the invoked method will always return non-null
     *     value.
     * @param arguments An optional list of expressions to pass as arguments to the function
     */
    public StaticInvoke(
        Class<?> staticObject,
        String functionName,
        String returnNamePrefix,
        TypeRef<?> type,
        boolean returnNullable,
        boolean inline,
        Expression... arguments) {
      super(
          functionName,
          type,
          arguments,
          returnNamePrefix,
          returnNullable,
          inline,
          ReflectionUtils.hasException(staticObject, functionName));
      this.staticObject = staticObject;
      if (inline && needTryCatch) {
        throw new UnsupportedOperationException(
            String.format(
                "Method %s in %s has exception signature and can't be inlined",
                functionName, staticObject));
      }
    }

    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      int len = arguments.length;
      StringBuilder argsBuilder = new StringBuilder();
      if (len > 0) {
        for (int i = 0; i < len; i++) {
          Expression argExpr = arguments[i];
          ExprCode argExprCode = argExpr.genCode(ctx);
          if (StringUtils.isNotBlank(argExprCode.code())) {
            codeBuilder.append(argExprCode.code()).append("\n");
          }
          if (i != 0) {
            argsBuilder.append(", ");
          }
          argsBuilder.append(argExprCode.value());
        }
      }

      if (!inlineCall && StringUtils.isBlank(returnNamePrefix)) {
        returnNamePrefix = ctx.newName(getRawType(type));
      }
      if (!inlineCall && type != null && !PRIMITIVE_VOID_TYPE.equals(type)) {
        Class<?> rawType = getRawType(type);
        String[] freshNames = ctx.newNames(returnNamePrefix, "isNull");
        String value = freshNames[0];
        String isNull = freshNames[1];
        if (returnNullable) {
          codeBuilder.append(String.format("boolean %s = false;\n", isNull));
          String callCode =
              ExpressionUtils.callFunc(
                  ctx.type(type),
                  value,
                  ctx.type(staticObject),
                  functionName,
                  argsBuilder.toString(),
                  needTryCatch);
          codeBuilder.append(callCode).append('\n');
        } else {
          String callCode =
              ExpressionUtils.callFunc(
                  ctx.type(type),
                  value,
                  ctx.type(staticObject),
                  functionName,
                  argsBuilder.toString(),
                  needTryCatch);
          codeBuilder.append(callCode);
        }

        if (returnNullable) {
          String nullCode =
              StringUtils.format(
                  "if (${value} == null) {\n" + "   ${isNull} = true;\n" + "}",
                  "value",
                  value,
                  "isNull",
                  isNull);
          codeBuilder.append(nullCode);
          return new ExprCode(
              codeBuilder.toString(), Code.isNullVariable(isNull), Code.variable(rawType, value));
        } else {
          return new ExprCode(codeBuilder.toString(), FalseLiteral, Code.variable(rawType, value));
        }
      } else if (inlineCall) {
        CodeGenerator.stripIfHasLastNewline(codeBuilder);
        String call =
            ExpressionUtils.callFunc(
                ctx.type(staticObject), functionName, argsBuilder.toString(), needTryCatch);
        call = call.substring(0, call.length() - 1);
        return new ExprCode(codeBuilder.toString(), null, Code.variable(getRawType(type), call));
      } else {
        String call =
            ExpressionUtils.callFunc(
                ctx.type(staticObject), functionName, argsBuilder.toString(), needTryCatch);
        codeBuilder.append(call);
        return new ExprCode(codeBuilder.toString(), null, null);
      }
    }

    @Override
    public boolean nullable() {
      return returnNullable;
    }

    @Override
    public String toString() {
      return String.format("%s.%s", staticObject, functionName);
    }
  }

  class NewInstance extends AbstractExpression {
    private TypeRef<?> type;
    private final Class<?> rawType;
    private String unknownClassName;
    private List<Expression> arguments;
    private Expression outerPointer;
    private final boolean needOuterPointer;

    /**
     * Create an expr which create a new object instance.
     *
     * @param interfaceType object declared in this type. actually the type is {@code
     *     unknownClassName}
     * @param unknownClassName unknownClassName that's unknown in compile-time
     */
    public NewInstance(TypeRef<?> interfaceType, String unknownClassName, Expression... arguments) {
      this(interfaceType, Arrays.asList(arguments), null);
      this.unknownClassName = unknownClassName;
      check();
    }

    public NewInstance(TypeRef<?> type, Expression... arguments) {
      this(type, Arrays.asList(arguments), null);
      check();
    }

    private NewInstance(TypeRef<?> type, List<Expression> arguments, Expression outerPointer) {
      super(ofArrayList(outerPointer, arguments));
      this.type = type;
      rawType = getRawType(type);
      this.outerPointer = outerPointer;
      this.arguments = arguments;
      this.needOuterPointer =
          getRawType(type).isMemberClass() && !Modifier.isStatic(getRawType(type).getModifiers());
      if (needOuterPointer && (outerPointer == null)) {
        String msg =
            String.format("outerPointer can't be null when %s is instance inner class", type);
        throw new CodegenException(msg);
      }
    }

    private void check() {
      Preconditions.checkArgument(
          !type.isArray(), "Please use " + NewArray.class + " to create array.");
      if (unknownClassName == null && !arguments.isEmpty()) {
        // If unknownClassName is not null, we don't have actual type object,
        // we assume we can create instance of unknownClassName.
        // If arguments size is 0, we can always create instance of class, even by
        // unsafe.allocateInstance
        boolean anyMatchParamCount =
            Stream.of(getRawType(type).getConstructors())
                .anyMatch(c -> c.getParameterCount() == arguments.size());
        if (!anyMatchParamCount) {
          String msg =
              String.format(
                  "%s doesn't have a public constructor that take %d params",
                  type, arguments.size());
          throw new IllegalArgumentException(msg);
        }
      }
      checkArgument(
          rawType.getCanonicalName() != null, "Local/Anonymous type %s isn't supported.", type);
    }

    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      int len = arguments.size();
      StringBuilder argsBuilder = new StringBuilder();
      if (len > 0) {
        for (int i = 0; i < len; i++) {
          Expression argExpr = arguments.get(i);
          ExprCode argExprCode = argExpr.genCode(ctx);
          if (StringUtils.isNotBlank(argExprCode.code())) {
            codeBuilder.append(argExprCode.code()).append("\n");
          }
          if (i != 0) {
            argsBuilder.append(", ");
          }
          argsBuilder.append(argExprCode.value());
        }
      }

      Class<?> rawType = getRawType(type);
      String typename = ctx.type(rawType);
      String clzName = unknownClassName;
      if (clzName == null) {
        clzName = rawType.getName();
      }
      if (needOuterPointer) {
        // "${gen.value}.new ${cls.getSimpleName}($argString)"
        throw new UnsupportedOperationException();
      } else {
        String value = ctx.newName(rawType);
        // class don't have a public no-arg constructor
        if (arguments.isEmpty() && !ReflectionUtils.hasPublicNoArgConstructor(rawType)) {
          // janino doesn't generics, so we cast manually.
          String instance = ctx.newName("instance");
          String code =
              ExpressionUtils.callFunc(
                  "Object",
                  instance,
                  ctx.type(Platform.class),
                  "newInstance",
                  clzName + ".class",
                  false);
          codeBuilder.append(code).append('\n');
          String cast =
              StringUtils.format(
                  "${type} ${value} = (${type})${instance};",
                  "type",
                  typename,
                  "value",
                  value,
                  "instance",
                  instance);
          codeBuilder.append(cast);
        } else {
          String code =
              StringUtils.format(
                  "${type} ${value} = new ${type}(${args});",
                  "type",
                  ReflectionUtils.isAbstract(rawType) ? clzName : typename,
                  "value",
                  value,
                  "args",
                  argsBuilder.toString());
          codeBuilder.append(code);
        }
        return new ExprCode(codeBuilder.toString(), null, Code.variable(rawType, value));
      }
    }

    @Override
    public boolean nullable() {
      return false;
    }

    @Override
    public String toString() {
      return String.format("newInstance(%s)", type);
    }
  }

  class NewArray extends AbstractExpression {
    private TypeRef<?> type;
    private Expression[] elements;

    private int numDimensions;
    private Class<?> elemType;

    private Expression dim;

    private Expression dims;

    public NewArray(Class<?> elemType, Expression dim) {
      super(dim);
      this.numDimensions = 1;
      this.elemType = elemType;
      this.dim = dim;
      type = TypeRef.of(Array.newInstance(elemType, 1).getClass());
    }

    /**
     * Dynamic created array doesn't have generic type info, so we don't pass in TypeToken.
     *
     * @param elemType elemType
     * @param numDimensions numDimensions
     * @param dims an int[] represent dims
     */
    public NewArray(Class<?> elemType, int numDimensions, Expression dims) {
      super(dims);
      this.numDimensions = numDimensions;
      this.elemType = elemType;
      this.dims = dims;

      int[] stubSizes = new int[numDimensions];
      for (int i = 0; i < numDimensions; i++) {
        stubSizes[i] = 1;
      }
      type = TypeRef.of(Array.newInstance(elemType, stubSizes).getClass());
    }

    public NewArray(TypeRef<?> type, Expression... elements) {
      super(elements);
      this.type = type;
      this.elements = elements;
      this.numDimensions = 1;
    }

    /** ex: new int[3][][]. */
    public static NewArray newArrayWithFirstDim(
        Class<?> elemType, int numDimensions, Expression firstDim) {
      NewArray array = new NewArray(elemType, firstDim);
      array.numDimensions = numDimensions;
      return array;
    }

    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      Class<?> rawType = getRawType(type);
      String arrayType = getArrayType(rawType);
      String value = ctx.newName("arr");
      if (dims != null) {
        // multi-dimension array
        ExprCode dimsExprCode = dims.genCode(ctx);
        if (StringUtils.isNotBlank(dimsExprCode.code())) {
          codeBuilder.append(dimsExprCode.code()).append('\n');
        }
        // "${arrType} ${value} = new ${elementType}[$?][$?]...
        codeBuilder
            .append(arrayType)
            .append(' ')
            .append(value)
            .append(" = new ")
            .append(ctx.type(elemType));
        for (int i = 0; i < numDimensions; i++) {
          // dims is dimensions array, which store size of per dim.
          String idim = StringUtils.format("${dims}[${i}]", "dims", dimsExprCode.value(), "i", i);
          codeBuilder.append('[').append(idim).append("]");
        }
        codeBuilder.append(';');
      } else if (dim != null) {
        ExprCode dimExprCode = dim.genCode(ctx);
        if (StringUtils.isNotBlank(dimExprCode.code())) {
          codeBuilder.append(dimExprCode.code()).append('\n');
        }
        if (numDimensions > 1) {
          // multi-dimension array
          // "${arrType} ${value} = new ${elementType}[$?][][][]...
          codeBuilder
              .append(arrayType)
              .append(' ')
              .append(value)
              .append(" = new ")
              .append(ctx.type(elemType));
          codeBuilder.append('[').append(dimExprCode.value()).append(']');
          for (int i = 1; i < numDimensions; i++) {
            codeBuilder.append('[').append("]");
          }
          codeBuilder.append(';');
        } else {
          // one-dimension array
          String code =
              StringUtils.format(
                  "${type} ${value} = new ${elemType}[${dim}];",
                  "type",
                  arrayType,
                  "elemType",
                  ctx.type(elemType),
                  "value",
                  value,
                  "dim",
                  dimExprCode.value());
          codeBuilder.append(code);
        }
      } else {
        // create array with init value
        int len = elements.length;
        StringBuilder argsBuilder = new StringBuilder();
        if (len > 0) {
          for (int i = 0; i < len; i++) {
            Expression argExpr = elements[i];
            ExprCode argExprCode = argExpr.genCode(ctx);
            if (StringUtils.isNotBlank(argExprCode.code())) {
              codeBuilder.append(argExprCode.code()).append("\n");
            }
            if (i != 0) {
              argsBuilder.append(", ");
            }
            argsBuilder.append(argExprCode.value());
          }
        }

        String code =
            StringUtils.format(
                "${type} ${value} = new ${type} {${args}};",
                "type",
                arrayType,
                "value",
                value,
                "args",
                argsBuilder.toString());
        codeBuilder.append(code);
      }

      return new ExprCode(codeBuilder.toString(), null, Code.variable(rawType, value));
    }
  }

  class AssignArrayElem extends AbstractExpression {
    private Expression targetArray;
    private Expression value;
    private Expression[] indexes;

    public AssignArrayElem(Expression targetArray, Expression value, Expression... indexes) {
      super(ofArrayList(targetArray, value, indexes));
      this.targetArray = targetArray;
      this.value = value;
      this.indexes = indexes;
    }

    @Override
    public TypeRef<?> type() {
      return PRIMITIVE_VOID_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      ExprCode targetExprCode = targetArray.genCode(ctx);
      ExprCode valueCode = value.genCode(ctx);
      Stream.of(targetExprCode, valueCode)
          .forEach(
              exprCode -> {
                if (StringUtils.isNotBlank(exprCode.code())) {
                  codeBuilder.append(exprCode.code()).append('\n');
                }
              });

      ExprCode[] indexExprCodes = new ExprCode[indexes.length];
      for (int i = 0; i < indexes.length; i++) {
        Expression indexExpr = indexes[i];
        ExprCode indexExprCode = indexExpr.genCode(ctx);
        indexExprCodes[i] = indexExprCode;
        if (StringUtils.isNotBlank(indexExprCode.code())) {
          codeBuilder.append(indexExprCode.code()).append("\n");
        }
      }
      codeBuilder.append(targetExprCode.value());
      for (int i = 0; i < indexes.length; i++) {
        codeBuilder.append('[').append(indexExprCodes[i].value()).append(']');
      }
      codeBuilder.append(" = ").append(valueCode.value()).append(";");
      return new ExprCode(codeBuilder.toString(), FalseLiteral, null);
    }
  }

  class If extends AbstractExpression {
    private Expression predicate;
    private Expression trueExpr;
    private Expression falseExpr;
    private TypeRef<?> type;
    private boolean nullable;

    public If(Expression predicate, Expression trueExpr) {
      super(new Expression[] {predicate, trueExpr});
      this.predicate = predicate;
      this.trueExpr = trueExpr;
      this.nullable = false;
      this.type = PRIMITIVE_VOID_TYPE;
    }

    public If(
        Expression predicate,
        Expression trueExpr,
        Expression falseExpr,
        boolean nullable,
        TypeRef<?> type) {
      super(new Expression[] {predicate, trueExpr, falseExpr});
      this.predicate = predicate;
      this.trueExpr = trueExpr;
      this.falseExpr = falseExpr;
      this.nullable = nullable;
      this.type = type;
    }

    public If(Expression predicate, Expression trueExpr, Expression falseExpr, boolean nullable) {
      this(predicate, trueExpr, falseExpr);
      this.nullable = nullable;
    }

    /** if predicate eval to null, take predicate as false. */
    public If(Expression predicate, Expression trueExpr, Expression falseExpr) {
      super(new Expression[] {predicate, trueExpr, falseExpr});
      this.predicate = predicate;
      this.trueExpr = trueExpr;
      this.falseExpr = falseExpr;
      if (ExpressionUtils.isReturn(trueExpr) && ExpressionUtils.isReturn(falseExpr)) {
        type = PRIMITIVE_VOID_TYPE;
      }
      if (trueExpr.type() == falseExpr.type()) {
        if (trueExpr.type() != null && !PRIMITIVE_VOID_TYPE.equals(trueExpr.type())) {
          type = trueExpr.type();
        } else {
          type = PRIMITIVE_VOID_TYPE;
        }
      } else {
        if (trueExpr.type() != null && falseExpr.type() != null) {
          if (TypeUtils.isBoxed(getRawType(trueExpr.type()))
              && trueExpr.type().equals(falseExpr.type().wrap())) {
            type = trueExpr.type();
          } else if (TypeUtils.isBoxed(getRawType(falseExpr.type()))
              && falseExpr.type().equals(trueExpr.type().wrap())) {
            type = falseExpr.type();
          } else if (trueExpr.type().isSupertypeOf(falseExpr.type())) {
            type = trueExpr.type();
          } else if (falseExpr.type().isSupertypeOf(trueExpr.type())) {
            type = falseExpr.type();
          } else if (PRIMITIVE_VOID_TYPE.equals(trueExpr.type())
              || PRIMITIVE_VOID_TYPE.equals(falseExpr.type())) {
            type = PRIMITIVE_VOID_TYPE;
          } else {
            type = OBJECT_TYPE;
          }
        } else {
          type = PRIMITIVE_VOID_TYPE;
        }
      }
      nullable = !PRIMITIVE_VOID_TYPE.equals(type) && !type.isPrimitive();
    }

    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      ExprCode condEval = predicate.genCode(ctx);
      ExprCode trueEval = trueExpr.doGenCode(ctx);
      StringBuilder codeBuilder = new StringBuilder();
      if (StringUtils.isNotBlank(condEval.code())) {
        codeBuilder.append(condEval.code()).append('\n');
      }
      String cond;
      if (condEval.isNull() != null && !"false".equals(condEval.isNull().code())) {
        // indicate condEval.isNull() is a variable. "false" is a java keyword, thus is not a
        // variable
        cond =
            StringUtils.format(
                "!${condEvalIsNull} && ${condEvalValue}",
                "condEvalIsNull",
                condEval.isNull(),
                "condEvalValue",
                condEval.value());
      } else {
        cond = StringUtils.format("${condEvalValue}", "condEvalValue", condEval.value());
      }
      TypeRef<?> type = this.type;
      if (!PRIMITIVE_VOID_TYPE.equals(type.unwrap())) {
        if (ExpressionUtils.isReturn(trueExpr) && ExpressionUtils.isReturn(falseExpr)) {
          type = PRIMITIVE_VOID_TYPE;
        }
      }
      if (!PRIMITIVE_VOID_TYPE.equals(type.unwrap())) {
        ExprCode falseEval = falseExpr.doGenCode(ctx);
        Preconditions.checkNotNull(trueEval.value());
        Preconditions.checkNotNull(falseEval.value());
        Class<?> rawType = getRawType(type);
        String[] freshNames = ctx.newNames(rawType, "isNull");
        String value = freshNames[0];
        String isNull = freshNames[1];
        codeBuilder.append(String.format("%s %s;\n", ctx.type(type), value));
        String ifCode;
        if (nullable) {
          Preconditions.checkArgument(trueEval.isNull() != null || falseEval.isNull() != null);
          codeBuilder.append(String.format("boolean %s = false;\n", isNull));
          String trueEvalIsNull;
          if (trueEval.isNull() == null) {
            trueEvalIsNull = "false";
          } else {
            trueEvalIsNull = trueEval.isNull().code();
          }
          String falseEvalIsNull;
          if (falseEval.isNull() == null) {
            falseEvalIsNull = "false";
          } else {
            falseEvalIsNull = falseEval.isNull().code();
          }
          ifCode =
              StringUtils.format(
                  ""
                      + "if (${cond}) {\n"
                      + "    ${trueEvalCode}\n"
                      + "    ${isNull} = ${trueEvalIsNull};\n"
                      + "    ${value} = ${trueEvalValue};\n"
                      + "} else {\n"
                      + "    ${falseEvalCode}\n"
                      + "    ${isNull} = ${falseEvalIsNull};\n"
                      + "    ${value} = ${falseEvalValue};\n"
                      + "}",
                  "isNull",
                  isNull,
                  "value",
                  value,
                  "cond",
                  cond,
                  "trueEvalCode",
                  alignIndent(trueEval.code()),
                  "trueEvalIsNull",
                  trueEvalIsNull,
                  "trueEvalValue",
                  trueEval.value(),
                  "falseEvalCode",
                  alignIndent(falseEval.code()),
                  "falseEvalIsNull",
                  falseEvalIsNull,
                  "falseEvalValue",
                  falseEval.value());
        } else {
          ifCode =
              StringUtils.format(
                  ""
                      + "if (${cond}) {\n"
                      + "    ${trueEvalCode}\n"
                      + "    ${value} = ${trueEvalValue};\n"
                      + "} else {\n"
                      + "    ${falseEvalCode}\n"
                      + "    ${value} = ${falseEvalValue};\n"
                      + "}",
                  "cond",
                  cond,
                  "value",
                  value,
                  "trueEvalCode",
                  alignIndent(trueEval.code()),
                  "trueEvalValue",
                  trueEval.value(),
                  "falseEvalCode",
                  alignIndent(falseEval.code()),
                  "falseEvalValue",
                  falseEval.value());
        }
        codeBuilder.append(StringUtils.stripBlankLines(ifCode));
        return new ExprCode(
            codeBuilder.toString(), Code.isNullVariable(isNull), Code.variable(rawType, value));
      } else {
        String ifCode;
        if (falseExpr != null) {
          ExprCode falseEval = falseExpr.doGenCode(ctx);
          ifCode =
              StringUtils.format(
                  "if (${cond}) {\n"
                      + "    ${trueEvalCode}\n"
                      + "} else {\n"
                      + "    ${falseEvalCode}\n"
                      + "}",
                  "cond",
                  cond,
                  "trueEvalCode",
                  alignIndent(trueEval.code()),
                  "falseEvalCode",
                  alignIndent(falseEval.code()));
        } else {
          ifCode =
              StringUtils.format(
                  "if (${cond}) {\n" + "    ${trueEvalCode}\n" + "}",
                  "cond",
                  cond,
                  "trueEvalCode",
                  alignIndent(trueEval.code()));
        }
        codeBuilder.append(ifCode);
        return new ExprCode(codeBuilder.toString());
      }
    }

    @Override
    public boolean nullable() {
      return nullable;
    }

    @Override
    public String toString() {
      if (falseExpr != null) {
        return String.format("if (%s) %s else %s", predicate, trueExpr, falseExpr);
      } else {
        return String.format("if (%s) %s", predicate, trueExpr);
      }
    }
  }

  class IsNull extends AbstractExpression {
    private Expression expr;

    public IsNull(Expression expr) {
      super(expr);
      this.expr = expr;
    }

    @Override
    public TypeRef<?> type() {
      return PRIMITIVE_BOOLEAN_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      ExprCode targetExprCode = expr.genCode(ctx);
      Preconditions.checkNotNull(targetExprCode.isNull());
      return new ExprCode(targetExprCode.code(), FalseLiteral, targetExprCode.isNull());
    }

    @Override
    public boolean nullable() {
      return false;
    }

    @Override
    public String toString() {
      return String.format("IsNull(%s)", expr);
    }
  }

  class Not extends AbstractExpression {
    private Expression target;

    public Not(Expression target) {
      super(target);
      this.target = target;
      Preconditions.checkArgument(
          target.type() == PRIMITIVE_BOOLEAN_TYPE || target.type() == BOOLEAN_TYPE);
    }

    @Override
    public TypeRef<?> type() {
      return target.type();
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      ExprCode targetExprCode = target.genCode(ctx);
      // whether need to check null for BOOLEAN_TYPE. The question is what to do when target.value
      // is null.
      String value = String.format("(!%s)", targetExprCode.value());
      return new ExprCode(targetExprCode.code(), FalseLiteral, Code.variable(boolean.class, value));
    }

    @Override
    public boolean nullable() {
      return false;
    }

    @Override
    public String toString() {
      return String.format("!(%s)", target);
    }
  }

  class BinaryOperator extends ValueExpression {
    private final String operator;
    private final TypeRef<?> type;
    private Expression left;
    private Expression right;

    public BinaryOperator(String operator, Expression left, Expression right) {
      this(false, operator, left, right);
    }

    public BinaryOperator(boolean inline, String operator, Expression left, Expression right) {
      this(inline, operator, left, right, null);
    }

    protected BinaryOperator(
        boolean inline, String operator, Expression left, Expression right, TypeRef<?> t) {
      super(new Expression[] {left, right});
      this.inlineCall = inline;
      this.operator = operator;
      this.left = left;
      this.right = right;
      if (t == null) {
        if (isPrimitive(getRawType(left.type()))) {
          Preconditions.checkArgument(isPrimitive(getRawType(right.type())));
          type =
              getSizeOfPrimitiveType(left.type()) > getSizeOfPrimitiveType(right.type())
                  ? left.type()
                  : right.type();
        } else {
          if (left.type().isSupertypeOf(right.type())) {
            type = left.type();
          } else if (left.type().isSubtypeOf(right.type())) {
            type = right.type();
          } else {
            throw new IllegalArgumentException(
                String.format("Arguments type %s vs %s inconsistent", left.type(), right.type()));
          }
        }
      } else {
        type = t;
      }
    }

    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      StringBuilder arith = new StringBuilder();
      Expression[] operands = new Expression[] {left, right};
      for (int i = 0; i < operands.length; i++) {
        Expression operand = operands[i];
        ExprCode code = operand.genCode(ctx);
        if (StringUtils.isNotBlank(code.code())) {
          appendNewlineIfNeeded(codeBuilder);
          codeBuilder.append(code.code());
        }
        if (i != operands.length - 1) {
          arith.append(code.value()).append(' ').append(operator).append(' ');
        } else {
          arith.append(code.value());
        }
      }

      if (inlineCall) {
        String value = String.format("(%s)", arith);
        String code = StringUtils.isBlank(codeBuilder) ? null : codeBuilder.toString();
        return new ExprCode(code, FalseLiteral, Code.variable(getRawType(type), value));
      } else {
        appendNewlineIfNeeded(codeBuilder);
        String value = ctx.newName(valuePrefix);
        String valueExpr =
            StringUtils.format(
                "${type} ${value} = ${arith};",
                "type",
                ctx.type(type),
                "value",
                value,
                "arith",
                arith);
        codeBuilder.append(valueExpr);
        return new ExprCode(
            codeBuilder.toString(), FalseLiteral, Code.variable(getRawType(type), value));
      }
    }

    @Override
    public String toString() {
      return String.format("%s %s %s", left, operator, right);
    }
  }

  class Comparator extends BinaryOperator {
    public Comparator(String operator, Expression left, Expression right, boolean inline) {
      super(inline, operator, left, right, PRIMITIVE_BOOLEAN_TYPE);
    }
  }

  class Arithmetic extends BinaryOperator {
    public Arithmetic(String operator, Expression left, Expression right) {
      super(operator, left, right);
    }

    public Arithmetic(boolean inline, String operator, Expression left, Expression right) {
      super(inline, operator, left, right);
    }
  }

  class Add extends Arithmetic {
    public Add(Expression left, Expression right) {
      super("+", left, right);
    }

    public Add(boolean inline, Expression left, Expression right) {
      super(inline, "+", left, right);
    }
  }

  class Subtract extends Arithmetic {
    public Subtract(Expression left, Expression right) {
      super("-", left, right);
    }

    public Subtract(boolean inline, Expression left, Expression right) {
      super(inline, "-", left, right);
    }
  }

  class BitAnd extends Arithmetic {
    public BitAnd(Expression left, Expression right) {
      super(true, "&", left, right);
    }

    public BitAnd(boolean inline, Expression left, Expression right) {
      super(inline, "&", left, right);
    }
  }

  class BitOr extends Arithmetic {

    public BitOr(Expression left, Expression right) {
      this(true, left, right);
    }

    public BitOr(boolean inline, Expression left, Expression right) {
      super(inline, "|", left, right);
    }
  }

  class BitShift extends Arithmetic {

    public BitShift(String operator, Expression operand, int numBits) {
      this(true, operator, operand, new Literal(numBits, PRIMITIVE_INT_TYPE));
    }

    public BitShift(boolean inline, String operator, Expression left, Expression right) {
      super(inline, operator, left, right);
    }
  }

  class LogicalOperator extends BinaryOperator {

    public LogicalOperator(String operator, Expression left, Expression right) {
      super(operator, left, right);
    }

    public LogicalOperator(boolean inline, String operator, Expression left, Expression right) {
      super(inline, operator, left, right);
    }
  }

  class LogicalAnd extends LogicalOperator {
    public LogicalAnd(Expression left, Expression right) {
      super(true, "&&", left, right);
    }

    public LogicalAnd(boolean inline, Expression left, Expression right) {
      super(inline, "&&", left, right);
    }
  }

  class LogicalOr extends LogicalOperator {
    public LogicalOr(Expression left, Expression right) {
      super(true, "||", left, right);
    }

    public LogicalOr(boolean inline, Expression left, Expression right) {
      super(inline, "||", left, right);
    }
  }

  /**
   * While expression for java while. TODO(chaokunyang) refactor to:
   *
   * <pre>
   *   def while_loop(cond_fun, body_fun, init_val):
   *     val = init_val
   *     while cond_fun(val):
   *       val = body_fun(val)
   *     return val
   * </pre>
   */
  class While extends AbstractExpression {
    private final Expression predicate;
    private Expression action;
    private Expression[] cutPoints;

    /**
     * Create a while-block with specified predict and while body.
     *
     * @param predicate predicate must be inline.
     */
    public While(Expression predicate, Expression action) {
      this(predicate, action, new Expression[0]);
    }

    /**
     * Use lambda to create a new context, and by capturing variables, we can make the codegen of
     * those variable expressions happen before while loop.
     */
    public While(Expression predicate, SerializableSupplier<Expression> action) {
      this(
          predicate,
          action.get(),
          Functions.extractCapturedVariables(action, o -> o instanceof Expression)
              .toArray(new Expression[0]));
    }

    public While(Expression predicate, Expression action, Expression[] cutPoints) {
      super(ofArrayList(predicate, action, cutPoints));
      this.predicate = predicate;
      this.action = action;
      this.cutPoints = cutPoints;
    }

    @Override
    public TypeRef<?> type() {
      return PRIMITIVE_VOID_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      for (Expression cutPoint : cutPoints) {
        ExprCode code = cutPoint.genCode(ctx);
        if (StringUtils.isNotBlank(code.code())) {
          appendNewlineIfNeeded(codeBuilder);
          codeBuilder.append(code.code()).append("\n");
        }
      }
      ExprCode predicateExprCode = predicate.genCode(ctx);
      if (StringUtils.isNotBlank(predicateExprCode.code())) {
        codeBuilder.append(predicateExprCode.code());
        appendNewlineIfNeeded(codeBuilder);
      }
      ExprCode actionExprCode = action.genCode(ctx);
      String whileCode =
          StringUtils.format(
              "while (${predicate}) {\n" + "${action}\n" + "}",
              "predicate",
              predicateExprCode.value(),
              "action",
              indent(actionExprCode.code()));
      codeBuilder.append(whileCode);
      return new ExprCode(codeBuilder.toString(), FalseLiteral, null);
    }

    @Override
    public String toString() {
      return String.format("while(%s) {%s}", predicate, action);
    }
  }

  class ForEach extends AbstractExpression {
    private Expression inputObject;

    @ClosureVisitable final SerializableBiFunction<Expression, Expression, Expression> action;

    private final TypeRef<?> elementType;
    private final boolean elementNullable;

    /**
     * inputObject.type() must be multi-dimension array or Collection, not allowed to be primitive
     * array
     */
    public ForEach(
        Expression inputObject,
        boolean elementNullable,
        SerializableBiFunction<Expression, Expression, Expression> action) {
      super(inputObject);
      this.inputObject = inputObject;
      this.elementNullable = elementNullable;
      this.action = action;
      TypeRef<?> elementType;
      if (inputObject.type().isArray()) {
        elementType = inputObject.type().getComponentType();
      } else {
        elementType = getElementType(inputObject.type());
      }
      this.elementType = ReflectionUtils.getPublicSuperType(elementType);
    }

    public ForEach(
        Expression inputObject,
        TypeRef<?> beanType,
        boolean elementNullable,
        SerializableBiFunction<Expression, Expression, Expression> action) {
      super(inputObject);
      this.inputObject = inputObject;
      this.action = action;
      this.elementType = beanType;
      this.elementNullable = elementNullable;
    }

    @Override
    public TypeRef<?> type() {
      return PRIMITIVE_VOID_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      ExprCode targetExprCode = inputObject.genCode(ctx);
      if (StringUtils.isNotBlank(targetExprCode.code())) {
        codeBuilder.append(targetExprCode.code()).append("\n");
      }
      String i = ctx.newName("i");
      String elemValue = ctx.newName("elemValue");
      Expression elementExpr =
          action.apply(new Reference(i), new Reference(elemValue, elementType, elementNullable));
      ExprCode elementExprCode = elementExpr.genCode(ctx);

      if (inputObject.type().isArray()) {
        String code =
            StringUtils.format(
                ""
                    + "int ${len} = ${arr}.length;\n"
                    + "int ${i} = 0;\n"
                    + "while (${i} < ${len}) {\n"
                    + "    ${elemType} ${elemValue} = ${arr}[${i}];\n"
                    + "    ${elementExprCode}\n"
                    + "    ${i}++;\n"
                    + "}",
                "arr",
                targetExprCode.value(),
                "len",
                ctx.newName("len"),
                "i",
                i,
                "elemType",
                ctx.type(elementType),
                "elemValue",
                elemValue,
                "i",
                i,
                "elementExprCode",
                alignIndent(elementExprCode.code()));
        codeBuilder.append(code);
        return new ExprCode(codeBuilder.toString(), null, null);
      } else {
        // don't use forloop for List, because it may be LinkedList.
        Preconditions.checkArgument(
            ITERABLE_TYPE.isSupertypeOf(inputObject.type()),
            "Unsupported type " + inputObject.type());
        String elemTypeCast = "";
        if (getRawType(elementType) != Object.class) {
          // elementType may be unresolved generic`E`
          elemTypeCast = "(" + ctx.type(elementType) + ")";
        }
        String code =
            StringUtils.format(
                ""
                    + "java.util.Iterator ${iter} = ${input}.iterator();\n"
                    + "int ${i} = 0;\n"
                    + "while (${iter}.hasNext()) {\n"
                    + "    ${elemType} ${elemValue} = ${elemTypeCast}${iter}.next();\n"
                    + "    ${elementExprCode}\n"
                    + "    ${i}++;\n"
                    + "}",
                "iter",
                ctx.newName("iter"),
                "i",
                i,
                "input",
                targetExprCode.value().code(),
                "elemType",
                ctx.type(elementType),
                "elemTypeCast",
                elemTypeCast,
                "elemValue",
                elemValue,
                "elementExprCode",
                alignIndent(elementExprCode.code()));
        codeBuilder.append(code);
        return new ExprCode(codeBuilder.toString(), null, null);
      }
    }

    @Override
    public String toString() {
      return String.format("ForEach(%s, %s)", inputObject, action);
    }
  }

  class ZipForEach extends AbstractExpression {
    private Expression left;
    private Expression right;

    @ClosureVisitable
    private final SerializableTriFunction<Expression, Expression, Expression, Expression> action;

    public ZipForEach(
        Expression left,
        Expression right,
        SerializableTriFunction<Expression, Expression, Expression, Expression> action) {
      super(new Expression[] {left, right});
      this.left = left;
      this.right = right;
      this.action = action;
      Preconditions.checkArgument(
          left.type().isArray() || TypeRef.of(Collection.class).isSupertypeOf(left.type()));
      Preconditions.checkArgument(
          right.type().isArray() || TypeRef.of(Collection.class).isSupertypeOf(right.type()));
      if (left.type().isArray()) {
        Preconditions.checkArgument(
            right.type().isArray(), "Should both be array or neither be array");
      }
    }

    @Override
    public TypeRef<?> type() {
      return PRIMITIVE_VOID_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      ExprCode leftExprCode = left.genCode(ctx);
      ExprCode rightExprCode = right.genCode(ctx);
      Stream.of(leftExprCode, rightExprCode)
          .forEach(
              exprCode -> {
                if (StringUtils.isNotBlank(exprCode.code())) {
                  codeBuilder.append(exprCode.code()).append('\n');
                }
              });
      String i = ctx.newName("i");
      String leftElemValue = ctx.newName("leftElemValue");
      String rightElemValue = ctx.newName("rightElemValue");
      TypeRef<?> leftElemType;
      if (left.type().isArray()) {
        leftElemType = left.type().getComponentType();
      } else {
        leftElemType = getElementType(left.type());
      }
      leftElemType = ReflectionUtils.getPublicSuperType(leftElemType);
      TypeRef<?> rightElemType;
      if (right.type().isArray()) {
        rightElemType = right.type().getComponentType();
      } else {
        rightElemType = getElementType(right.type());
      }
      rightElemType = ReflectionUtils.getPublicSuperType(rightElemType);
      Expression elemExpr =
          action.apply(
              new Reference(i),
              new Reference(leftElemValue, leftElemType, true),
              // elemValue nullability check uses isNullAt inside action, so elemValueRef's nullable
              // is false.
              new Reference(rightElemValue, rightElemType, false));
      ExprCode elementExprCode = elemExpr.genCode(ctx);

      if (left.type().isArray()) {
        String code =
            StringUtils.format(
                ""
                    + "int ${len} = ${leftArr}.length;\n"
                    + "int ${i} = 0;\n"
                    + "while (${i} < ${len}) {\n"
                    + "    ${leftElemType} ${leftElemValue} = ${leftArr}[${i}];\n"
                    + "    ${rightElemType} ${rightElemValue} = ${rightArr}[${i}];\n"
                    + "    ${elementExprCode}\n"
                    + "    ${i}++;\n"
                    + "}",
                "leftArr",
                leftExprCode.value(),
                "len",
                ctx.newName("len"),
                "i",
                i,
                "leftElemType",
                ctx.type(leftElemType),
                "leftElemValue",
                leftElemValue,
                "rightElemType",
                ctx.type(rightElemType),
                "rightElemValue",
                rightElemValue,
                "rightArr",
                rightExprCode.value(),
                "elementExprCode",
                alignIndent(elementExprCode.code()));
        codeBuilder.append(code);
        return new ExprCode(codeBuilder.toString(), null, null);
      } else {
        // CHECKSTYLE.OFF:LineLength
        String code =
            StringUtils.format(
                ""
                    + "java.util.Iterator ${leftIter} = ${leftInput}.iterator();\n"
                    + "java.util.Iterator ${rightIter} = ${rightInput}.iterator();\n"
                    + "int ${i} = 0;\n"
                    + "while (${leftIter}.hasNext() && ${rightIter}.hasNext()) {\n"
                    + "    ${leftElemType} ${leftElemValue} = (${leftElemType})${leftIter}.next();\n"
                    + "    ${rightElemType} ${rightElemValue} = (${rightElemType})${rightIter}.next();\n"
                    + "    ${elementExprCode}\n"
                    + "    ${i}++;\n"
                    + "}",
                "leftIter",
                // CHECKSTYLE.ON:LineLength
                ctx.newName("leftIter"),
                "leftInput",
                leftExprCode.value(),
                "rightIter",
                ctx.newName("rightIter"),
                "rightInput",
                rightExprCode.value(),
                "i",
                i,
                "leftElemType",
                ctx.type(leftElemType),
                "leftElemValue",
                leftElemValue,
                "rightElemType",
                ctx.type(rightElemType),
                "rightElemValue",
                rightElemValue,
                "elementExprCode",
                alignIndent(elementExprCode.code()));
        codeBuilder.append(code);
        return new ExprCode(codeBuilder.toString(), null, null);
      }
    }
  }

  class ForLoop extends AbstractExpression {
    public Expression start;
    public Expression end;
    public Expression step;
    private final Class<?> maxType;
    private final Reference iref;
    public Expression loopAction;

    public ForLoop(
        Expression start,
        Expression end,
        Expression step,
        SerializableFunction<Expression, Expression> action) {
      super(new Expression[] {start, end, step});
      this.start = start;
      this.end = end;
      this.step = step;
      this.maxType = maxType(getRawType(start.type()), getRawType(end.type()));
      Preconditions.checkArgument(maxType.isPrimitive());
      iref = new Reference(String.valueOf(System.identityHashCode(this)), TypeRef.of(maxType));
      this.loopAction = action.apply(iref);
    }

    @Override
    public TypeRef<?> type() {
      return PRIMITIVE_VOID_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      String i = ctx.newName("i");
      iref.setName(i);
      ExprCode startExprCode = start.genCode(ctx);
      ExprCode endExprCode = end.genCode(ctx);
      ExprCode stepExprCode = step.genCode(ctx);
      ExprCode actionExprCode = loopAction.genCode(ctx);
      Stream.of(startExprCode, endExprCode, stepExprCode)
          .forEach(
              exprCode -> {
                if (StringUtils.isNotBlank(exprCode.code())) {
                  codeBuilder.append(exprCode.code()).append('\n');
                }
              });

      String forCode =
          StringUtils.format(
              ""
                  + "for (${type} ${i} = ${start}; ${i} < ${end}; ${i}+=${step}) {\n"
                  + "${actionCode}\n"
                  + "}",
              "type",
              maxType.toString(),
              "i",
              i,
              "start",
              startExprCode.value(),
              "end",
              endExprCode.value(),
              "step",
              stepExprCode.value(),
              "actionCode",
              indent(actionExprCode.code()));
      codeBuilder.append(forCode);
      return new ExprCode(codeBuilder.toString(), null, null);
    }
  }

  /**
   * build list from iterable.
   *
   * <p>Since value is declared to be {@code List<elementType>}, no need to cast in other expression
   * that need List
   */
  class ListFromIterable extends AbstractExpression {
    private final TypeRef elementType;
    private Expression inputObject;
    private final TypeRef<?> type;

    public ListFromIterable(Expression inputObject) {
      super(inputObject);
      this.inputObject = inputObject;
      Preconditions.checkArgument(
          getRawType(inputObject.type()) == Iterable.class,
          "wrong type of inputObject, get " + inputObject.type());
      elementType = getElementType(inputObject.type());
      this.type = inputObject.type().getSubtype(List.class);
    }

    /** Returns inputObject.type(), not {@code List<elementType>}. */
    @Override
    public TypeRef<?> type() {
      return type;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      ExprCode targetExprCode = inputObject.genCode(ctx);
      if (StringUtils.isNotBlank(targetExprCode.code())) {
        codeBuilder.append(targetExprCode.code()).append("\n");
      }
      String iter = ctx.newName("iter");
      String list = ctx.newName("list");
      String elemValue = ctx.newName("elemValue");

      Class rawType = getRawType(elementType);
      // janino don't support Type inference for generic instance creation
      String code =
          StringUtils.format(
              ""
                  + "java.util.List<${className}> ${list} = new java.util.ArrayList<${className}>();\n"
                  + "java.util.Iterator ${iter} = ${iterable}.iterator();\n"
                  + "while (${iter}.hasNext()) {\n"
                  + "   ${className} ${elemValue} = (${className})${iter}.next();\n"
                  + "   ${list}.add(${elemValue});\n"
                  + "}",
              "className",
              ctx.type(rawType),
              "list",
              list,
              "iter",
              iter,
              "iterable",
              targetExprCode.value(),
              "elemValue",
              elemValue);
      codeBuilder.append(code);
      return new ExprCode(codeBuilder.toString(), FalseLiteral, Code.variable(rawType, list));
    }

    @Override
    public String toString() {
      return String.format("%s(%s)", getClass().getSimpleName(), inputObject);
    }
  }

  class Return extends AbstractExpression {
    private Expression expression;

    public Return() {
      super(new Expression[0]);
    }

    public Return(Expression expression) {
      super(expression);
      this.expression = expression;
    }

    @Override
    public TypeRef<?> type() {
      return expression == null ? VOID_TYPE : expression.type();
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      if (expression == null) {
        return new ExprCode("return;", null, null);
      }
      StringBuilder codeBuilder = new StringBuilder();
      ExprCode targetExprCode = expression.genCode(ctx);
      if (StringUtils.isNotBlank(targetExprCode.code())) {
        codeBuilder.append(targetExprCode.code()).append('\n');
      }
      codeBuilder.append("return ").append(targetExprCode.value()).append(';');
      return new ExprCode(codeBuilder.toString(), null, targetExprCode.value());
    }

    @Override
    public String toString() {
      return expression != null ? String.format("return %s", expression) : "return;";
    }
  }

  class Break extends AbstractExpression {
    public Break() {
      super(new Expression[0]);
    }

    @Override
    public TypeRef<?> type() {
      return PRIMITIVE_VOID_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      return new ExprCode("break;", null, null);
    }

    @Override
    public String toString() {
      return "break;";
    }
  }

  class Assign extends AbstractExpression {
    private Expression to;
    private Expression from;

    public Assign(Expression to, Expression from) {
      super(new Expression[] {to, from});
      this.to = to;
      this.from = from;
    }

    @Override
    public TypeRef<?> type() {
      return PRIMITIVE_VOID_TYPE;
    }

    @Override
    public ExprCode doGenCode(CodegenContext ctx) {
      StringBuilder codeBuilder = new StringBuilder();
      ExprCode fromExprCode = from.genCode(ctx);
      ExprCode toExprCode = to.genCode(ctx);
      Stream.of(fromExprCode, toExprCode)
          .forEach(
              exprCode -> {
                if (StringUtils.isNotBlank(exprCode.code())) {
                  codeBuilder.append(exprCode.code()).append('\n');
                }
              });
      String assign =
          StringUtils.format(
              "${to} = ${from};", "from", fromExprCode.value(), "to", toExprCode.value());
      codeBuilder.append(assign);
      return new ExprCode(codeBuilder.toString(), null, null);
    }

    @Override
    public String toString() {
      return String.format("%s = %s", from, to);
    }
  }
}
