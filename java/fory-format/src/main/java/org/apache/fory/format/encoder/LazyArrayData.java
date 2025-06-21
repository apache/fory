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

import java.awt.List;
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

/**
 * Expression to represent {@link org.apache.fory.format.row.ArrayData} as a lazy List implemented
 * with array backing storage.
 */
@Internal
public class LazyArrayData extends AbstractExpression {
  private final Expression inputArrayData;
  private final String accessMethod;
  private final TypeRef<?> elemType;

  @ClosureVisitable
  private final SerializableBiFunction<Expression, Expression, Expression> notNullAction;

  @ClosureVisitable private final Expression nullValue;

  /**
   * inputArrayData.type() must be multi-dimension array or Collection, not allowed to be primitive
   * array
   */
  public LazyArrayData(
      Expression inputArrayData,
      TypeRef<?> elemType,
      SerializableBiFunction<Expression, Expression, Expression> notNullAction) {
    this(inputArrayData, elemType, notNullAction, null);
  }

  /**
   * inputArrayData.type() must be multi-dimension array or Collection, not allowed to be primitive
   * array
   */
  public LazyArrayData(
      Expression inputArrayData,
      TypeRef<?> elemType,
      SerializableBiFunction<Expression, Expression, Expression> notNullAction,
      Expression nullValue) {
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
    this.nullValue = nullValue;
  }

  @Override
  public TypeRef<?> type() {
    return TypeUtils.listOf(elemType.getRawType());
  }

  @Override
  public Code.ExprCode doGenCode(CodegenContext ctx) {
    StringBuilder codeBuilder = new StringBuilder();
    Code.ExprCode targetExprCode = inputArrayData.genCode(ctx);
    if (StringUtils.isNotBlank(targetExprCode.code())) {
      codeBuilder.append(targetExprCode.code()).append("\n");
    }
    Code.ExprCode notNullElemExprCode =
        new Expression.Return(
                notNullAction.apply(
                    new Reference("index", TypeUtils.INT_TYPE),
                    new Reference("elemValue", elemType)))
            .genCode(ctx);
    Code.ExprCode nullElemCode = nullValue.genCode(ctx);
    String result = ctx.newName("lazyArray");
    String code =
        StringUtils.format(
            ""
                + "java.util.List ${result} = \n"
                + "  new org.apache.fory.format.encoder.LazyArray(${arr}) {\n"
                + "    protected Object deserialize(int index) {\n"
                + "      if (!array().isNullAt(index)) {\n"
                + "        ${elemType} elemValue = array().${method}(index);\n"
                + "        ${notNullElemExprCode}\n"
                + "      } else {\n"
                + "        return ${nullElemCode};\n"
                + "      }\n"
                + "    }\n"
                + "  };",
            "result",
            result,
            "arr",
            targetExprCode.value(),
            "elemType",
            ctx.type(elemType),
            "method",
            accessMethod,
            "notNullElemExprCode",
            CodeGenerator.alignIndent(notNullElemExprCode.code(), 8),
            "nullElemCode",
            nullElemCode.value());
    codeBuilder.append(code);
    return new Code.ExprCode(codeBuilder.toString(), null, Code.variable(List.class, result));
  }
}
