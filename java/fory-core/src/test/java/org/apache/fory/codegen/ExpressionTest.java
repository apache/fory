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

import static org.apache.fory.codegen.ExpressionUtils.neq;
import static org.apache.fory.codegen.ExpressionUtils.or;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_DOUBLE_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_FLOAT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_SHORT_TYPE;
import static org.testng.Assert.assertNull;

import java.lang.reflect.Method;
import org.apache.fory.codegen.Code.ExprCode;
import org.apache.fory.codegen.Expression.ListExpression;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.Expression.Return;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ExpressionTest {

  @Test
  public void testIfExpression() {
    {
      String code =
          new Expression.If(
                  ExpressionUtils.eq(
                      Literal.ofInt(1), new Reference("classId", PRIMITIVE_SHORT_TYPE, false)),
                  new Return(Literal.True),
                  new Return(Literal.False))
              .genCode(new CodegenContext())
              .code();
      String expected =
          "if ((1 == classId)) {\n"
              + "    return true;\n"
              + "} else {\n"
              + "    return false;\n"
              + "}";
      Assert.assertEquals(code, expected);
    }
    {
      String code =
          new Expression.If(
                  ExpressionUtils.eq(
                      Literal.ofInt(1), new Reference("classId", PRIMITIVE_SHORT_TYPE, false)),
                  Literal.True,
                  Literal.False)
              .genCode(new CodegenContext())
              .code();
      String expected =
          "boolean value;\n"
              + "if ((1 == classId)) {\n"
              + "    value = true;\n"
              + "} else {\n"
              + "    value = false;\n"
              + "}\n";
      Assert.assertEquals(code, expected);
    }
  }

  @Test
  public void testListExpression() {
    {
      ListExpression exp = new ListExpression();
      String code = exp.genCode(new CodegenContext()).code();
      assertNull(code);
    }
  }

  @Test
  public void testMultipleOr() {
    CodegenContext ctx = new CodegenContext();
    Expression or =
        or(
            Literal.ofBoolean(false),
            neq(Literal.ofInt(3), Literal.ofInt(4)),
            neq(Literal.ofInt(5), Literal.ofInt(6)));
    ExprCode exprCode = or.genCode(ctx);
    Assert.assertEquals(exprCode.value().code(), "((3 != 4) || (5 != 6))");
  }

  @Test
  public void testLiteralSourceRoundTrip() throws Exception {
    String text =
        "quote\" slash\\ newline\n carriage\r tab\t backspace\b formfeed\f "
            + (char) 0
            + (char) 1
            + " unicode雪 literal\\u000a";
    CodegenContext ctx = new CodegenContext();
    String clsName = "LiteralRoundTrip";
    ctx.setClassName(clsName);
    ctx.setPackage("test");
    ctx.addMethod("text", new Return(Literal.ofString(text)).genCode(ctx).code(), String.class);
    ctx.addMethod(
        "floatValue",
        new Return(new Literal(Float.MIN_VALUE, PRIMITIVE_FLOAT_TYPE)).genCode(ctx).code(),
        float.class);
    ctx.addMethod(
        "doubleValue",
        new Return(new Literal(Double.MIN_VALUE, PRIMITIVE_DOUBLE_TYPE)).genCode(ctx).code(),
        double.class);

    ClassLoader loader =
        new CodeGenerator(getClass().getClassLoader())
            .compile(new CompileUnit("test", clsName, ctx.genCode()));
    Object generated = loader.loadClass("test." + clsName).getDeclaredConstructor().newInstance();
    Assert.assertEquals(generated.getClass().getMethod("text").invoke(generated), text);

    Method floatMethod = generated.getClass().getMethod("floatValue");
    float floatValue = (Float) floatMethod.invoke(generated);
    Assert.assertEquals(
        Float.floatToRawIntBits(floatValue), Float.floatToRawIntBits(Float.MIN_VALUE));

    Method doubleMethod = generated.getClass().getMethod("doubleValue");
    double doubleValue = (Double) doubleMethod.invoke(generated);
    Assert.assertEquals(
        Double.doubleToRawLongBits(doubleValue), Double.doubleToRawLongBits(Double.MIN_VALUE));
  }
}
