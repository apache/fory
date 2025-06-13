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

import static org.apache.fory.type.TypeUtils.LIST_TYPE;
import static org.testng.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.util.Preconditions;
import org.testng.annotations.Test;

public class ExpressionVisitorTest {

  @Test
  public void testTraverseExpression() throws InvocationTargetException, IllegalAccessException {
    Expression.Reference ref =
        new Expression.Reference("a", TypeRef.of(ExpressionVisitorTest.class));
    Expression e1 = new Expression.Invoke(ref, "testTraverseExpression");
    Literal literal1 = Literal.ofInt(1);
    Expression list = new Expression.StaticInvoke(ImmutableList.class, "of", LIST_TYPE, literal1);
    ExpressionVisitor.ExprHolder holder =
        ExpressionVisitor.ExprHolder.of("e1", e1, "e2", new Expression.ListExpression());
    // FIXME ListExpression#add in lambda don't get executed, so ListExpression is the last expr.
    Expression.ForEach forLoop =
        new Expression.ForEach(
            list,
            false,
            (i, expr) -> ((Expression.ListExpression) (holder.get("e2"))).add(holder.get("e1")));
    List<Expression> expressions = new ArrayList<>();
    new ExpressionVisitor()
        .traverseExpression(forLoop, exprSite -> expressions.add(exprSite.current));
    Preconditions.checkArgument(forLoop.action != null);
    Method writeReplace =
        ReflectionUtils.findMethods(forLoop.action.getClass(), "writeReplace").get(0);
    writeReplace.setAccessible(true);
    SerializedLambda serializedLambda = (SerializedLambda) writeReplace.invoke(forLoop.action);
    assertEquals(serializedLambda.getCapturedArgCount(), 1);
    ExpressionVisitor.ExprHolder exprHolder =
        (ExpressionVisitor.ExprHolder) (serializedLambda.getCapturedArg(0));

    // Traversal relies on getDeclaredFields(), nondeterministic order.
    Set<Expression> expressionsSet = new HashSet<>(expressions);
    Set<Expression> expressionsSet2 =
        new HashSet<>(Arrays.asList(forLoop, e1, ref, exprHolder.get("e2"), list, literal1));
    assertEquals(expressionsSet, expressionsSet2);
  }
}
