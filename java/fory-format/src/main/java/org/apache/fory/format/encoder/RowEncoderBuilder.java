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
import static org.apache.fory.type.TypeUtils.getRawType;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.Fory;
import org.apache.fory.builder.CodecBuilder;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.ExpressionUtils;
import org.apache.fory.format.row.ArrayData;
import org.apache.fory.format.row.MapData;
import org.apache.fory.format.row.Row;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.BinaryUtils;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.record.RecordUtils;

/** Expression builder for building jit row encoder class. */
@SuppressWarnings("UnstableApiUsage")
public class RowEncoderBuilder extends BaseBinaryEncoderBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(RowEncoderBuilder.class);
  static final String SCHEMA_NAME = "schema";
  static final String ROOT_ROW_NAME = "row";
  static final String ROOT_ROW_WRITER_NAME = "rowWriter";

  private final String className;
  private final SortedMap<String, Descriptor> descriptorsMap;
  private final Schema schema;
  protected static final String BEAN_CLASS_NAME = "beanClass";
  protected Reference beanClassRef = new Reference(BEAN_CLASS_NAME, CLASS_TYPE);
  private final CodegenContext generatedBeanImpl;
  private final String generatedBeanImplName;

  public RowEncoderBuilder(Class<?> beanClass) {
    this(TypeRef.of(beanClass));
  }

  public RowEncoderBuilder(TypeRef<?> beanType) {
    super(new CodegenContext(), beanType);
    Preconditions.checkArgument(beanClass.isInterface() || TypeUtils.isBean(beanType, typeCtx));
    className = codecClassName(beanClass);
    this.schema = TypeInference.inferSchema(getRawType(beanType));
    this.descriptorsMap = Descriptor.getDescriptorsMap(beanClass);
    ctx.reserveName(ROOT_ROW_WRITER_NAME);
    ctx.reserveName(SCHEMA_NAME);
    ctx.reserveName(ROOT_ROW_NAME);
    ctx.reserveName(BEAN_CLASS_NAME);
    Expression clsExpr;
    if (Modifier.isPublic(beanClass.getModifiers())) {
      clsExpr = Literal.ofClass(beanClass);
    } else {
      // non-public class is not accessible in other class.
      clsExpr =
          new Expression.StaticInvoke(
              Class.class, "forName", CLASS_TYPE, false, Literal.ofString(beanClass.getName()));
    }
    ctx.addField(Class.class, "beanClass", clsExpr);
    ctx.addImports(Field.class, Schema.class);
    ctx.addImports(Row.class, ArrayData.class, MapData.class);
    ctx.addImports(BinaryRow.class, BinaryArray.class, BinaryMap.class);
    if (beanClass.isInterface()) {
      generatedBeanImplName = beanClass.getSimpleName() + "GeneratedImpl";
      generatedBeanImpl = buildImplClass();
    } else {
      generatedBeanImplName = null;
      generatedBeanImpl = null;
    }
  }

  @Override
  protected String codecSuffix() {
    return "RowCodec";
  }

  @Override
  protected boolean fieldNullable(Descriptor descriptor) {
    return descriptor.isNullable();
  }

  @Override
  public String genCode() {
    ctx.setPackage(CodeGenerator.getPackage(beanClass));
    ctx.setClassName(className);
    // don't addImport(beanClass), because user class may name collide.
    // janino don't support generics, so GeneratedCodec has no generics
    ctx.implementsInterfaces(ctx.type(GeneratedRowEncoder.class));
    String constructorCode =
        StringUtils.format(
            "${schema} = (${schemaType})${references}[0];\n"
                + "${rowWriter} = (${rowWriterType})${references}[1];\n"
                + "${fory} = (${foryType})${references}[2];\n",
            "references",
            REFERENCES_NAME,
            "schema",
            SCHEMA_NAME,
            "schemaType",
            ctx.type(Schema.class),
            "rowWriter",
            ROOT_ROW_WRITER_NAME,
            "rowWriterType",
            ctx.type(BinaryRowWriter.class),
            "fory",
            FORY_NAME,
            "foryType",
            ctx.type(Fory.class));
    ctx.addField(ctx.type(Schema.class), SCHEMA_NAME);
    ctx.addField(ctx.type(BinaryRowWriter.class), ROOT_ROW_WRITER_NAME);
    ctx.addField(ctx.type(Fory.class), FORY_NAME);

    Expression encodeExpr = buildEncodeExpression();
    String encodeCode = encodeExpr.genCode(ctx).code();
    Expression decodeExpr = buildDecodeExpression();
    String decodeCode = decodeExpr.genCode(ctx).code();
    ctx.overrideMethod("toRow", encodeCode, BinaryRow.class, Object.class, ROOT_OBJECT_NAME);
    // T fromRow(BinaryRow row);
    ctx.overrideMethod("fromRow", decodeCode, Object.class, BinaryRow.class, ROOT_ROW_NAME);
    ctx.addConstructor(constructorCode, Object[].class, REFERENCES_NAME);

    long startTime = System.nanoTime();
    String code = ctx.genCode();
    // It would be nice if Expression let us write inner classes
    if (generatedBeanImpl != null) {
      int insertPoint = code.lastIndexOf('}');
      code =
          code.substring(0, insertPoint)
              + generatedBeanImpl.genCode()
              + code.substring(insertPoint);
    }
    long durationMs = (System.nanoTime() - startTime) / 1000;
    LOG.info("Generate codec for class {} take {} us", beanClass, durationMs);
    return code;
  }

  /**
   * Returns an expression that serialize java bean of type {@link CodecBuilder#beanClass} as a
   * <code>row</code>.
   */
  @Override
  public Expression buildEncodeExpression() {
    Reference inputObject = new Reference(ROOT_OBJECT_NAME, TypeUtils.OBJECT_TYPE, false);
    Expression bean = tryCastIfPublic(inputObject, beanType);
    Reference writer = new Reference(ROOT_ROW_WRITER_NAME, rowWriterTypeToken, false);
    Reference schemaExpr = new Reference(SCHEMA_NAME, schemaTypeToken, false);

    CustomCodec<?, ?> customCodec = customTypeHandler.findCodec(beanClass, beanClass);
    if (customCodec != null && customCodec.encodedType().equals(beanType)) {
      bean = customEncode(bean, beanType);
    }

    int numFields = schema.getFields().size();
    Expression.ListExpression expressions = new Expression.ListExpression();
    // schema field's name must correspond to descriptor's name.
    for (int i = 0; i < numFields; i++) {
      Descriptor d = getDescriptorByFieldName(schema.getFields().get(i).getName());
      Preconditions.checkNotNull(d);
      TypeRef<?> fieldType = d.getTypeRef();
      Expression fieldValue = getFieldValue(bean, d);
      Literal ordinal = Literal.ofInt(i);
      Expression.StaticInvoke field =
          new Expression.StaticInvoke(
              DataTypes.class, "fieldOfSchema", ARROW_FIELD_TYPE, false, schemaExpr, ordinal);
      Expression fieldExpr =
          serializeFor(ordinal, fieldValue, writer, fieldType, field, new HashSet<>());
      expressions.add(fieldExpr);
    }
    expressions.add(
        new Expression.Return(
            new Expression.Invoke(writer, "getRow", TypeRef.of(BinaryRow.class))));
    return expressions;
  }

  /**
   * Returns an expression that deserialize <code>row</code> as a java bean of type {@link
   * CodecBuilder#beanClass}.
   */
  @Override
  public Expression buildDecodeExpression() {
    Reference row = new Reference(ROOT_ROW_NAME, binaryRowTypeToken, false);

    addDecoderMethods();

    Expression.ListExpression expressions = new Expression.ListExpression();
    Expression bean;
    if (generatedBeanImpl != null) {
      bean = new Expression.Reference("new " + generatedBeanImplName + "(row)");
    } else {
      int numFields = schema.getFields().size();
      List<String> fieldNames = new ArrayList<>(numFields);
      Expression[] values = new Expression[numFields];
      Descriptor[] descriptors = new Descriptor[numFields];
      // schema field's name must correspond to descriptor's name.
      for (int i = 0; i < numFields; i++) {
        Literal ordinal = Literal.ofInt(i);
        Descriptor d = getDescriptorByFieldName(schema.getFields().get(i).getName());
        fieldNames.add(d.getName());
        descriptors[i] = d;
        TypeRef<?> fieldType = d.getTypeRef();
        Expression.Variable value = new Expression.Variable(d.getName(), nullValue(fieldType));
        values[i] = value;
        expressions.add(value);
        Expression.Invoke isNullAt =
            new Expression.Invoke(
                row,
                "isNullAt",
                "f" + i + "_" + d.getName() + "IsNull",
                TypeUtils.PRIMITIVE_BOOLEAN_TYPE,
                false,
                ordinal);
        Expression decode =
            new Expression.If(
                ExpressionUtils.not(isNullAt),
                new Expression.Assign(
                    value, new Expression.Reference(decodeMethodName(i) + "(row)", fieldType)));
        expressions.add(decode);
      }
      if (RecordUtils.isRecord(beanClass)) {
        int[] map = RecordUtils.buildRecordComponentMapping(beanClass, fieldNames);
        Expression[] args = new Expression[numFields];
        for (int i = 0; i < numFields; i++) {
          args[i] = values[map[i]];
        }
        bean = new Expression.NewInstance(beanType, beanType.getRawType().getName(), args);
      } else {
        bean = newBean();
        expressions.add(bean);
        for (int i = 0; i < values.length; i++) {
          expressions.add(setFieldValue(bean, descriptors[i], values[i]));
        }
      }
    }

    CustomCodec<?, ?> customCodec = customTypeHandler.findCodec(beanClass, beanClass);
    if (customCodec != null && customCodec.encodedType().equals(beanType)) {
      bean = customDecode(beanType, bean);
    }
    expressions.add(new Expression.Return(bean));
    return expressions;
  }

  private static Expression nullValue(TypeRef<?> fieldType) {
    Class<?> rawType = fieldType.getRawType();
    if (TypeUtils.isOptionalType(rawType)) {
      return new Expression.StaticInvoke(rawType, "empty", "", fieldType, false, true);
    }
    return new Expression.Reference(TypeUtils.defaultValue(rawType), fieldType);
  }

  private void addDecoderMethods() {
    Reference row = new Reference(ROOT_ROW_NAME, binaryRowTypeToken, false);
    int numFields = schema.getFields().size();
    for (int i = 0; i < numFields; i++) {
      Literal ordinal = Literal.ofInt(i);
      Descriptor d = getDescriptorByFieldName(schema.getFields().get(i).getName());
      TypeRef<?> fieldType = d.getTypeRef();
      Class<?> rawFieldType = fieldType.getRawType();
      TypeRef<?> columnAccessType = fieldType;
      if (rawFieldType == Optional.class) {
        columnAccessType = TypeUtils.getTypeArguments(fieldType).get(0);
      }
      TypeRef<?> replacementType =
          customTypeHandler.replacementTypeFor(beanClass, columnAccessType.getRawType());
      if (replacementType != null) {
        columnAccessType = replacementType;
      }
      String columnAccessMethodName =
          BinaryUtils.getElemAccessMethodName(columnAccessType, typeCtx);
      TypeRef<?> colType = BinaryUtils.getElemReturnType(columnAccessType, typeCtx);
      Expression.Invoke columnValue =
          new Expression.Invoke(
              row,
              columnAccessMethodName,
              ctx.newName(getRawType(colType)),
              colType,
              false,
              ordinal);
      Expression value =
          new Expression.Return(deserializeFor(columnValue, fieldType, typeCtx, new HashSet<>()));
      ctx.addMethod(
          decodeMethodName(i),
          value.doGenCode(ctx).code(),
          fieldType.getRawType(),
          BinaryRow.class,
          ROOT_ROW_NAME);
    }
  }

  private CodegenContext buildImplClass() {
    Reference row = new Reference(ROOT_ROW_NAME, binaryRowTypeToken, false);
    CodegenContext implClass = new CodegenContext();
    implClass.setClassModifiers("final");
    implClass.setClassName(generatedBeanImplName);
    implClass.implementsInterfaces(implClass.type(beanClass));
    implClass.addField(true, implClass.type(BinaryRow.class), "row", null);

    int numFields = schema.getFields().size();
    for (int i = 0; i < numFields; i++) {
      Literal ordinal = Literal.ofInt(i);
      Descriptor d = getDescriptorByFieldName(schema.getFields().get(i).getName());
      TypeRef<?> fieldType = d.getTypeRef();

      Expression.Reference decodeValue =
          new Expression.Reference(decodeMethodName(i) + "(row)", fieldType);
      Expression getterImpl;
      if (fieldType.isPrimitive()) {
        getterImpl = new Expression.Return(decodeValue);
      } else {
        String fieldName = "f" + i + "_" + d.getName();
        Class<?> rawFieldType = fieldType.getRawType();
        implClass.addField(false, ctx.type(rawFieldType), fieldName, nullValue(fieldType));

        Expression fieldRef = new Expression.Reference(fieldName, fieldType, true);
        Expression storeValue =
            new Expression.SetField(new Expression.Reference("this"), fieldName, decodeValue);
        Expression shouldLoad;
        if (TypeUtils.isOptionalType(rawFieldType)) {
          shouldLoad =
              new Expression.Not(
                  Expression.Invoke.inlineInvoke(fieldRef, "isPresent", TypeUtils.BOOLEAN_TYPE));
        } else {
          shouldLoad = new Expression.IsNull(fieldRef);
        }
        Expression loadIfFieldIsNull = new Expression.If(shouldLoad, storeValue);
        Expression assigner;

        if (d.isNullable()) {
          Expression isNotNullAt =
              new Expression.Not(
                  new Expression.Invoke(
                      row,
                      "isNullAt",
                      fieldName + "IsNull",
                      TypeUtils.PRIMITIVE_BOOLEAN_TYPE,
                      false,
                      ordinal));
          assigner = new Expression.If(isNotNullAt, loadIfFieldIsNull);
        } else {
          assigner = loadIfFieldIsNull;
        }
        getterImpl = new Expression.ListExpression(assigner, new Expression.Return(fieldRef));
      }
      implClass.addMethod(
          d.getName(), getterImpl.genCode(implClass).code(), fieldType.getRawType());
    }
    // Note: adding constructor captures init code, so must happen after all fields are collected
    implClass.addConstructor("this.row = row;", BinaryRow.class, "row");

    return implClass;
  }

  private Descriptor getDescriptorByFieldName(String fieldName) {
    String name = StringUtils.lowerUnderscoreToLowerCamelCase(fieldName);
    return descriptorsMap.get(name);
  }

  private String decodeMethodName(int i) {
    return "decode" + i + "_" + schema.getFields().get(i).getName();
  }

  @Override
  protected Expression beanClassExpr() {
    if (GraalvmSupport.isGraalBuildtime()) {
      return staticBeanClassExpr();
    }
    return beanClassRef;
  }
}
