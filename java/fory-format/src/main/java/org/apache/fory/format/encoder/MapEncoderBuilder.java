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

import static org.apache.fory.type.TypeUtils.CLASS_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_INT_TYPE;
import static org.apache.fory.type.TypeUtils.getRawType;

import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.ExpressionUtils;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.StringUtils;

/** Expression builder for building jit map encoder class. */
public class MapEncoderBuilder extends BaseBinaryEncoderBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(MapEncoderBuilder.class);
  private static final String FIELD_NAME = "field";
  private static final String KEY_FIELD_NAME = "keyField";
  private static final String VALUE_FIELD_NAME = "valueField";
  private static final String ROOT_MAP_NAME = "map";
  private static final String ROOT_KEY_NAME = "key";
  private static final String ROOT_VAL_NAME = "value";
  private static final String ROOT_KEY_WRITER_NAME = "keyArrayWriter";
  private static final String ROOT_VALUE_WRITER_NAME = "valueArrayWriter";

  private final TypeRef<?> mapToken;

  public MapEncoderBuilder(Class<?> mapCls, Class<?> keyClass) {
    this(TypeRef.of(mapCls), TypeRef.of(keyClass));
  }

  public MapEncoderBuilder(TypeRef<?> clsType, TypeRef<?> beanType) {
    super(new CodegenContext(), beanType);
    mapToken = clsType;
    ctx.reserveName(ROOT_KEY_WRITER_NAME);
    ctx.reserveName(ROOT_VALUE_WRITER_NAME);
    ctx.reserveName(ROOT_MAP_NAME);

    // add map class field
    Expression.Literal clsExpr = new Expression.Literal(getRawType(mapToken), CLASS_TYPE);
    ctx.addField(true, Class.class.getName(), "mapClass", clsExpr);
  }

  @Override
  public String genCode() {
    ctx.setPackage(CodeGenerator.getPackage(beanClass));
    String className = codecClassName(beanClass, TypeInference.inferTypeName(mapToken));
    ctx.setClassName(className);
    // don't addImport(arrayClass), because user class may name collide.
    // janino don't support generics, so GeneratedCodec has no generics
    ctx.implementsInterfaces(ctx.type(GeneratedMapEncoder.class));

    String constructorCode =
        StringUtils.format(
            "${keyField} = (${fieldType})${references}[0];\n"
                + "${keyArrayWriter} = (${arrayWriterType})${references}[2];\n"
                + "${valueField} = (${fieldType})${references}[1];\n"
                + "${valueArrayWriter} = (${arrayWriterType})${references}[3];\n"
                + "${fory} = (${foryType})${references}[4];\n"
                + "${field} = (${fieldType})${references}[5];\n",
            "references",
            REFERENCES_NAME,
            "keyField",
            KEY_FIELD_NAME,
            "fieldType",
            ctx.type(Field.class),
            "keyArrayWriter",
            ROOT_KEY_WRITER_NAME,
            "arrayWriterType",
            ctx.type(arrayWriterType()),
            "valueField",
            VALUE_FIELD_NAME,
            "fieldType",
            ctx.type(Field.class),
            "valueArrayWriter",
            ROOT_VALUE_WRITER_NAME,
            "arrayWriterType",
            ctx.type(arrayWriterType()),
            "fory",
            FORY_NAME,
            "foryType",
            ctx.type(Fory.class),
            "field",
            FIELD_NAME,
            "fieldType",
            ctx.type(Field.class));
    ctx.addField(ctx.type(Field.class), KEY_FIELD_NAME);
    ctx.addField(ctx.type(Field.class), VALUE_FIELD_NAME);
    ctx.addField(ctx.type(arrayWriterType()), ROOT_KEY_WRITER_NAME);
    ctx.addField(ctx.type(arrayWriterType()), ROOT_VALUE_WRITER_NAME);
    ctx.addField(ctx.type(Fory.class), FORY_NAME);
    ctx.addField(ctx.type(Field.class), FIELD_NAME);

    Expression encodeExpr = buildEncodeExpression();
    String encodeCode = encodeExpr.genCode(ctx).code();
    ctx.overrideMethod("toMap", encodeCode, BinaryMap.class, Object.class, ROOT_OBJECT_NAME);
    Expression decodeExpr = buildDecodeExpression();
    String decodeCode = decodeExpr.genCode(ctx).code();
    ctx.overrideMethod(
        "fromMap",
        decodeCode,
        Object.class,
        BinaryArray.class,
        ROOT_KEY_NAME,
        BinaryArray.class,
        ROOT_VAL_NAME);

    ctx.addConstructor(constructorCode, Object[].class, REFERENCES_NAME);

    long startTime = System.nanoTime();
    String code = ctx.genCode();
    long durationMs = (System.nanoTime() - startTime) / 1000_000;
    LOG.info("Generate map codec for class {} take {} us", beanClass, durationMs);
    return code;
  }

  /**
   * Returns an expression that serialize java bean of type {@link MapEncoderBuilder#mapToken} as a
   * <code>BinaryMap</code>.
   */
  @Override
  public Expression buildEncodeExpression() {
    Expression.ListExpression expressions = new Expression.ListExpression();

    Expression.Reference inputObject =
        new Expression.Reference(ROOT_OBJECT_NAME, TypeUtils.MAP_TYPE, false);
    Expression.Cast map =
        new Expression.Cast(inputObject, mapToken, ctx.newName(getRawType(mapToken)), false, false);

    Expression.Reference keyArrayWriter =
        new Expression.Reference(ROOT_KEY_WRITER_NAME, arrayWriterType(), false);
    Expression.Reference valArrayWriter =
        new Expression.Reference(ROOT_VALUE_WRITER_NAME, arrayWriterType(), false);

    Expression.Reference fieldExpr = new Expression.Reference(FIELD_NAME, FORY_FIELD_TYPE, false);
    Expression.Reference keyFieldExpr =
        new Expression.Reference(KEY_FIELD_NAME, FORY_FIELD_TYPE, false);
    Expression.Reference valFieldExpr =
        new Expression.Reference(VALUE_FIELD_NAME, FORY_FIELD_TYPE, false);

    @SuppressWarnings("unchecked")
    TypeRef<?> supertype = ((TypeRef<? extends Map<?, ?>>) mapToken).getSupertype(Map.class);
    TypeRef<?> keySetType = supertype.resolveType(TypeUtils.KEY_SET_RETURN_TYPE);
    TypeRef<?> valuesType = supertype.resolveType(TypeUtils.VALUES_RETURN_TYPE);

    Expression.Invoke keySet = new Expression.Invoke(map, "keySet", keySetType);
    Expression writerIndex =
        new Expression.Invoke(keyArrayWriter, "writerIndex", PRIMITIVE_INT_TYPE);
    expressions.add(writerIndex);
    expressions.add(
        new Expression.Invoke(keyArrayWriter, "writeDirectly", Expression.Literal.ofInt(-1)));
    Expression keySerializationExpr =
        serializeForArrayByWriter(keySet, keyArrayWriter, keySetType, null, keyFieldExpr);
    Expression.Invoke keyArray =
        new Expression.Invoke(keyArrayWriter, "toArray", TypeRef.of(BinaryArray.class));
    expressions.add(map);
    expressions.add(keySerializationExpr);
    expressions.add(keyArray);
    expressions.add(
        new Expression.Invoke(
            keyArrayWriter,
            "writeDirectly",
            writerIndex,
            Expression.Invoke.inlineInvoke(keyArray, "getSizeInBytes", PRIMITIVE_INT_TYPE)));

    Expression.Invoke values = new Expression.Invoke(map, "values", valuesType);
    Expression valueSerializationExpr =
        serializeForArrayByWriter(values, valArrayWriter, valuesType, null, valFieldExpr);
    Expression.Invoke valArray =
        new Expression.Invoke(valArrayWriter, "toArray", TypeRef.of(BinaryArray.class));

    expressions.add(valueSerializationExpr);
    expressions.add(valArray);
    expressions.add(
        new Expression.Return(
            new Expression.NewInstance(binaryMapType(), keyArray, valArray, fieldExpr)));
    return expressions;
  }

  protected TypeRef<? extends BinaryMap> binaryMapType() {
    return TypeRef.of(BinaryMap.class);
  }

  /**
   * Returns an expression that deserialize <code>row</code> as a java bean of type {@link
   * MapEncoderBuilder#mapToken}.
   */
  @Override
  public Expression buildDecodeExpression() {
    Expression.ListExpression expressions = new Expression.ListExpression();
    Expression map = newMap(mapToken);
    Expression.Reference keyArrayRef =
        new Expression.Reference(ROOT_KEY_NAME, binaryArrayTypeToken, false);
    Expression.Reference valArrayRef =
        new Expression.Reference(ROOT_VAL_NAME, binaryArrayTypeToken, false);

    Expression listExpression = directlyDeserializeMap(map, keyArrayRef, valArrayRef);
    expressions.add(listExpression);

    expressions.add(new Expression.Return(map));
    return expressions;
  }

  private Expression directlyDeserializeMap(
      Expression map, Expression keyArrayRef, Expression valArrayRef) {
    @SuppressWarnings("unchecked")
    TypeRef<?> supertype = ((TypeRef<? extends Map<?, ?>>) mapToken).getSupertype(Map.class);
    TypeRef<?> keySetType = supertype.resolveType(TypeUtils.KEY_SET_RETURN_TYPE);
    TypeRef<?> keysType = TypeUtils.getCollectionType(keySetType);
    TypeRef<?> valuesType = supertype.resolveType(TypeUtils.VALUES_RETURN_TYPE);
    Expression keyJavaArray;
    Expression valueJavaArray;
    if (TypeUtils.ITERABLE_TYPE.isSupertypeOf(keysType)) {
      keyJavaArray = deserializeForCollection(keyArrayRef, keysType);
    } else {
      keyJavaArray = deserializeForArray(keyArrayRef, keysType);
    }
    if (TypeUtils.ITERABLE_TYPE.isSupertypeOf(valuesType)) {
      valueJavaArray = deserializeForCollection(valArrayRef, valuesType);
    } else {
      valueJavaArray = deserializeForArray(valArrayRef, valuesType);
    }

    Expression.ZipForEach put =
        new Expression.ZipForEach(
            keyJavaArray,
            valueJavaArray,
            (i, key, value) ->
                new Expression.If(
                    ExpressionUtils.notNull(key), new Expression.Invoke(map, "put", key, value)));
    return new Expression.ListExpression(map, put);
  }
}
