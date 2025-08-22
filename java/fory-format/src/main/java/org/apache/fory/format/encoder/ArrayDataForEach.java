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

package org.apache.fory.format.encoder;

import static org.apache.fory.type.TypeUtils.getRawType;

import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.ClosureVisitable;
import org.apache.fory.codegen.Code;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.AbstractExpression;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryUtils;
import org.apache.fory.format.type.CustomTypeEncoderRegistry;
import org.apache.fory.format.type.CustomTypeHandler;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeResolutionContext;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.function.SerializableBiFunction;
import org.apache.fory.util.function.SerializableFunction;

/**
 * Expression for iterate {@link org.apache.fory.format.row.ArrayData} with specified not null
 * element action expression and null element action expression.
 */
@Internal
public class ArrayDataForEach extends AbstractExpression {
  private final Expression inputArrayData;
  private final String accessMethod;
  private final TypeRef<?> elemType;

  @ClosureVisitable
  private final SerializableBiFunction<Expression, Expression, Expression> notNullAction;

  @ClosureVisitable private final SerializableFunction<Expression, Expression> nullAction;

  /**
   * inputArrayData.type() must be multi-dimension array or Collection, not allowed to be primitive
   * array
   */
  public ArrayDataForEach(
      Expression inputArrayData,
      TypeRef<?> elemType,
      SerializableBiFunction<Expression, Expression, Expression> notNullAction) {
    this(inputArrayData, elemType, notNullAction, null);
  }

  /**
   * inputArrayData.type() must be multi-dimension array or Collection, not allowed to be primitive
   * array
   */
  public ArrayDataForEach(
      Expression inputArrayData,
      TypeRef<?> elemType,
      SerializableBiFunction<Expression, Expression, Expression> notNullAction,
      SerializableFunction<Expression, Expression> nullAction) {
    super(inputArrayData);
    Preconditions.checkArgument(getRawType(inputArrayData.type()) == BinaryArray.class);
    this.inputArrayData = inputArrayData;
    CustomTypeHandler customTypeHandler = CustomTypeEncoderRegistry.customTypeHandler();
    CustomCodec<?, ?> customEncoder =
        customTypeHandler.findCodec(BinaryArray.class, elemType.getRawType());
    TypeRef<?> accessType;
    if (customEncoder == null) {
      accessType = elemType;
    } else {
      accessType = customEncoder.encodedType();
    }
    TypeResolutionContext ctx = new TypeResolutionContext(customTypeHandler, true);
    this.accessMethod = BinaryUtils.getElemAccessMethodName(accessType, ctx);
    this.elemType = BinaryUtils.getElemReturnType(accessType, ctx);
    this.notNullAction = notNullAction;
    this.nullAction = nullAction;
  }

  @Override
  public TypeRef<?> type() {
    return TypeUtils.PRIMITIVE_VOID_TYPE;
  }

  @Override
  public Code.ExprCode doGenCode(CodegenContext ctx) {
    StringBuilder codeBuilder = new StringBuilder();
    Code.ExprCode targetExprCode = inputArrayData.genCode(ctx);
    if (StringUtils.isNotBlank(targetExprCode.code())) {
      codeBuilder.append(targetExprCode.code()).append("\n");
    }
    String[] freshNames = ctx.newNames("i", "elemValue", "len");
    String i = freshNames[0];
    String elemValue = freshNames[1];
    String len = freshNames[2];
    // elemValue is only used in notNullAction, so set elemValueRef's nullable to false.
    Reference elemValueRef = new Reference(elemValue, elemType);
    Code.ExprCode notNullElemExprCode =
        notNullAction.apply(new Reference(i), elemValueRef).genCode(ctx);
    if (nullAction == null) {
      String code =
          StringUtils.format(
              ""
                  + "int ${len} = ${arr}.numElements();\n"
                  + "int ${i} = 0;\n"
                  + "while (${i} < ${len}) {\n"
                  + "    if (!${arr}.isNullAt(${i})) {\n"
                  + "        ${elemType} ${elemValue} = ${arr}.${method}(${i});\n"
                  + "        ${notNullElemExprCode}\n"
                  + "    }\n"
                  + "    ${i}++;\n"
                  + "}",
              "arr",
              targetExprCode.value(),
              "len",
              len,
              "i",
              i,
              "elemType",
              ctx.type(elemType),
              "elemValue",
              elemValue,
              "method",
              accessMethod,
              "notNullElemExprCode",
              CodeGenerator.alignIndent(notNullElemExprCode.code(), 8));
      codeBuilder.append(code);
    } else {
      Code.ExprCode nullExprCode = nullAction.apply(new Reference(i)).genCode(ctx);
      String code =
          StringUtils.format(
              ""
                  + "int ${len} = ${arr}.numElements();\n"
                  + "int ${i} = 0;\n"
                  + "while (${i} < ${len}) {\n"
                  + "    if (!${arr}.isNullAt(${i})) {\n"
                  + "        ${elemType} ${elemValue} = ${arr}.${method}(${i});\n"
                  + "        ${notNullElemExprCode}\n"
                  + "    } else {\n"
                  + "        ${nullElemExprCode}\n"
                  + "    }\n"
                  + "    ${i}++;\n"
                  + "}",
              "arr",
              targetExprCode.value(),
              "len",
              len,
              "i",
              i,
              "elemType",
              ctx.type(elemType),
              "elemValue",
              elemValue,
              "method",
              accessMethod,
              "notNullElemExprCode",
              CodeGenerator.alignIndent(notNullElemExprCode.code(), 8),
              "nullElemExprCode",
              CodeGenerator.alignIndent(nullExprCode.code(), 8));
      codeBuilder.append(code);
    }
    return new Code.ExprCode(codeBuilder.toString(), null, null);
  }
}
