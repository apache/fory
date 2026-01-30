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

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.fory.codegen.Expression.ListExpression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.Preconditions;

/**
 * Traverse expression tree with actions. The provided action will be executed at every expression
 * site. If action return false, the subtree will stop traverse. Big method split can use this util
 * for update subtree root nodes with {@link Reference} to cutoff dependencies to avoid duplicate
 * codegen.
 */
@SuppressWarnings("UnstableApiUsage")
public class ExpressionVisitor {

  /**
   * Used to capture expression in closure for update. Although we can set final field in closure to
   * modifiable, but it's not safe.
   */
  public static final class ExprHolder {
    private final Map<Object, Expression> expressionsMap;

    /** Null value will be skipped. */
    private ExprHolder(Object... kv) {
      Preconditions.checkArgument(kv.length % 2 == 0);
      expressionsMap = new HashMap<>();
      for (int i = 0; i < kv.length; i += 2) {
        Object value = kv[i + 1];
        if (value != null) {
          expressionsMap.put(kv[i], (Expression) value);
        }
      }
    }

    public static ExprHolder of(String k1, Expression v1) {
      return new ExprHolder(k1, v1);
    }

    public static ExprHolder of(String k1, Expression v1, String k2, Expression v2) {
      return new ExprHolder(k1, v1, k2, v2);
    }

    public static ExprHolder of(
        String k1, Expression v1, String k2, Expression v2, String k3, Expression v3) {
      return new ExprHolder(k1, v1, k2, v2, k3, v3);
    }

    public static ExprHolder of(
        String k1,
        Expression v1,
        String k2,
        Expression v2,
        String k3,
        Expression v3,
        String k4,
        Expression v4) {
      return new ExprHolder(k1, v1, k2, v2, k3, v3, k4, v4);
    }

    public static ExprHolder of(
        String k1,
        Expression v1,
        String k2,
        Expression v2,
        String k3,
        Expression v3,
        String k4,
        Expression v4,
        String k5,
        Expression v5) {
      return new ExprHolder(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
    }

    public Expression get(String key) {
      return expressionsMap.get(key);
    }

    public void add(String key, Expression expr) {
      expressionsMap.put(key, expr);
    }

    public Map<Object, Expression> getExpressionsMap() {
      return expressionsMap;
    }
  }

  public static class ExprSite {
    public final Expression current;
    public final Expression parent;
    private final Consumer<Expression> updateFunc;

    ExprSite(Expression current) {
      this(null, current, null);
    }

    ExprSite(Expression parent, Expression current, Consumer<Expression> updateFunc) {
      this.parent = parent;
      this.current = current;
      this.updateFunc = updateFunc;
    }

    public void update(Expression newExpr) {
      Preconditions.checkNotNull(updateFunc).accept(newExpr);
    }

    @Override
    public String toString() {
      return "ExprSite{"
          + "current="
          + current
          + ", parent="
          + parent
          + ", updateFunc="
          + updateFunc
          + '}';
    }
  }

  /**
   * traverse expression tree.
   *
   * @param expr target expr
   * @param func return true to continue traverse children, false to stop traverse children.
   */
  public void traverseExpression(Expression expr, Function<ExprSite, Boolean> func) {
    Preconditions.checkNotNull(expr);
    if (!func.apply(new ExprSite(expr))) {
      return;
    }
    traverseChildren(expr, func);
  }

  public void traverseChildren(Expression expr, Function<ExprSite, Boolean> func) {
    if (expr instanceof ListExpression) {
      traverseList(expr, ((ListExpression) expr).expressions(), func);
    } else {
      for (Field field : ReflectionUtils.getFields(Objects.requireNonNull(expr).getClass(), true)) {
        int modifiers = field.getModifiers();
        if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers)) {
          try {
            if (Expression.class.isAssignableFrom(field.getType())) {
              traverseField(expr, field, func);
            } else if (Expression[].class == field.getType()) {
              Expression[] expressions = ReflectionUtils.getObjectFieldValue(expr, field);
              traverseArray(expr, expressions, func);
            } else if (field.getAnnotation(ClosureVisitable.class) != null) {
              traverseClosure(expr, field, func);
            } else {
              if (Iterable.class.isAssignableFrom(field.getType())) {
                TypeRef<?> fieldType = TypeRef.of(field.getGenericType());
                if (TypeUtils.getElementType(fieldType).equals(TypeRef.of(Expression.class))) {
                  List<Expression> expressions = ReflectionUtils.getObjectFieldValue(expr, field);
                  traverseList(expr, expressions, func);
                }
              }
              // TODO add map type support.
            }
          } catch (Exception e) {
            Platform.throwException(e);
          }
        }
      }
    }
  }

  private void traverseClosure(Expression expr, Field field, Function<ExprSite, Boolean> func)
      throws IllegalAccessException, InvocationTargetException {
    Object closure = ReflectionUtils.getObjectFieldValue(expr, field);
    Preconditions.checkArgument(closure instanceof Serializable);
    // TODO use method handle for serializable lambda to speed up perf.
    Method writeReplace = ReflectionUtils.findMethods(closure.getClass(), "writeReplace").get(0);
    writeReplace.setAccessible(true);
    SerializedLambda serializedLambda = (SerializedLambda) writeReplace.invoke(closure);
    for (int i = 0; i < serializedLambda.getCapturedArgCount(); i++) {
      Object capturedArg = serializedLambda.getCapturedArg(i);
      if (capturedArg instanceof Expression.Literal) {
        continue;
      }
      if (capturedArg instanceof Expression.EnumExpression) {
        continue;
      }
      if (capturedArg instanceof Expression || capturedArg == Expression[].class) {
        // FIXME may need to check list/container values types?
        throw new IllegalStateException(
            String.format(
                "Capture expression [%s: %s] in lambda %s are not allowed. \n"
                    + "SerializedLambda: %s",
                capturedArg.getClass(), capturedArg, closure, serializedLambda));
      }
      if (capturedArg instanceof ExprHolder) {
        traverseMap(expr, ((ExprHolder) capturedArg).getExpressionsMap(), func);
      }
    }
  }

  private void traverseMap(
      Expression expr, Map<Object, Expression> expressionsMap, Function<ExprSite, Boolean> func) {
    new HashMap<>(expressionsMap)
        .forEach(
            (k, childExpr) -> {
              if (func.apply(
                  new ExprSite(
                      expr, childExpr, newChildExpr -> expressionsMap.put(k, newChildExpr)))) {
                traverseChildren(childExpr, func);
              }
            });
  }

  private void traverseList(
      Expression expr, List<Expression> expressions, Function<ExprSite, Boolean> func) {
    for (int i = 0; i < expressions.size(); i++) {
      Expression childExpr = expressions.get(i);
      int index = i;
      if (func.apply(
          new ExprSite(expr, childExpr, newChildExpr -> expressions.set(index, newChildExpr)))) {
        traverseChildren(childExpr, func);
      }
    }
  }

  private void traverseField(Expression expr, Field field, Function<ExprSite, Boolean> func) {
    Expression childExpr = ReflectionUtils.getObjectFieldValue(expr, field);
    if (childExpr == null) {
      return;
    }
    Boolean continueVisit =
        func.apply(
            new ExprSite(
                expr,
                childExpr,
                newChildExpr -> ReflectionUtils.setObjectFieldValue(expr, field, newChildExpr)));
    if (continueVisit) {
      traverseChildren(childExpr, func);
    }
  }

  private void traverseArray(
      Expression expr, Expression[] expressions, Function<ExprSite, Boolean> func) {
    for (int i = 0; i < expressions.length; i++) {
      Expression childExpr = expressions[i];
      if (childExpr == null) {
        throw new IllegalStateException(
            String.format("Expression [%s] of type %s has null elements", expr, expr.getClass()));
      }
      int index = i;
      Boolean continueVisit =
          func.apply(
              new ExprSite(expr, childExpr, newChildExpr -> expressions[index] = newChildExpr));
      if (continueVisit) {
        traverseChildren(childExpr, func);
      }
    }
  }
}
