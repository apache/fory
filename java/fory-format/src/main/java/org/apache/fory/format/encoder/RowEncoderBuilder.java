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

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
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
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.record.RecordComponent;
import org.apache.fory.util.record.RecordUtils;

/** Expression builder for building jit row encoder class. */
@SuppressWarnings("UnstableApiUsage")
class RowEncoderBuilder extends BaseBinaryEncoderBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(RowEncoderBuilder.class);
  static final String SCHEMA_NAME = "schema";
  static final String ROOT_ROW_NAME = "row";
  static final String ROOT_ROW_WRITER_NAME = "rowWriter";

  private final String className;
  private final SortedMap<String, Descriptor> descriptorsMap;
  protected final Schema schema;
  protected static final String BEAN_CLASS_NAME = "beanClass";
  protected Reference beanClassRef = new Reference(BEAN_CLASS_NAME, CLASS_TYPE);
  private final CodegenContext generatedBeanImpl;
  private final String generatedBeanImplName;

  /**
   * When non-null, this builder produces a decode-only projection codec: schema fields whose name
   * is in {@code projectionLiveNames} are assigned to the bean as usual; others are decoded for
   * offset arithmetic only and discarded. {@code toRow} on a projection codec throws.
   */
  private final Set<String> projectionLiveNames;

  private final String projectionClassSuffix;
  private final Map<Class<?>, String> nestedSuffixes;

  public RowEncoderBuilder(Class<?> beanClass) {
    this(TypeRef.of(beanClass));
  }

  public RowEncoderBuilder(TypeRef<?> beanType) {
    this(beanType, null, null, null, Collections.emptyMap());
  }

  /**
   * Construct a decode-only projection builder for an older version of {@code beanType}. The
   * supplied {@code historicalSchema} is used as the layout to decode; only fields whose name is in
   * {@code liveNames} are written into the resulting bean. {@code classSuffix} distinguishes this
   * codec from the current-version codec and from other historical projections. {@code
   * nestedSuffixes} routes each nested-bean type to a specific projection codec class (used when an
   * inner versioned bean was on the wire at an older version).
   */
  RowEncoderBuilder(
      TypeRef<?> beanType,
      Schema historicalSchema,
      Set<String> liveNames,
      String classSuffix,
      Map<Class<?>, String> nestedSuffixes) {
    super(new CodegenContext(), beanType);
    Preconditions.checkArgument(beanClass.isInterface() || TypeUtils.isBean(beanType, typeCtx));
    this.projectionLiveNames = liveNames;
    this.projectionClassSuffix = classSuffix;
    this.nestedSuffixes = nestedSuffixes == null ? Collections.emptyMap() : nestedSuffixes;
    className =
        projectionClassSuffix == null
            ? codecClassName(beanClass)
            : codecClassName(beanClass) + projectionClassSuffix;
    this.schema = historicalSchema != null ? historicalSchema : inferSchema(beanType);
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
      // Append the projection suffix so each historical version of an interface bean gets its
      // own impl class; the impl classes are inner classes of the codec and would collide on
      // the simple name otherwise.
      generatedBeanImplName =
          beanClass.getSimpleName()
              + "GeneratedImpl"
              + (projectionClassSuffix == null ? "" : projectionClassSuffix);
      generatedBeanImpl = buildImplClass();
    } else {
      generatedBeanImplName = null;
      generatedBeanImpl = null;
    }
  }

  protected Schema inferSchema(TypeRef<?> beanType) {
    return TypeInference.inferSchema(getRawType(beanType));
  }

  @Override
  protected String nestedBeanSuffix(TypeRef<?> typeRef) {
    String s = nestedSuffixes.get(getRawType(typeRef));
    return s != null ? s : super.nestedBeanSuffix(typeRef);
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
    String rowWriterType = ctx.type(rowWriterType());
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
            rowWriterType,
            "fory",
            FORY_NAME,
            "foryType",
            ctx.type(Fory.class));
    ctx.addField(ctx.type(Schema.class), SCHEMA_NAME);
    ctx.addField(rowWriterType, ROOT_ROW_WRITER_NAME);
    ctx.addField(ctx.type(Fory.class), FORY_NAME);

    // Order matters for projection codecs: the encode pass registers nested-bean encoder fields
    // on ctx as a side effect (see buildEncodeExpression), and the decode pass reads them. Building
    // decode first would fail with "No bean codec registered". Keep encode genCode before decode.
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
    long durationUs = (System.nanoTime() - startTime) / 1000;
    LOG.info("Generate codec for class {} take {} us", beanClass, durationUs);
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
    Reference writer = new Reference(ROOT_ROW_WRITER_NAME, rowWriterType(), false);
    Reference schemaExpr = new Reference(SCHEMA_NAME, schemaTypeToken, false);

    CustomCodec<?, ?> customCodec = customTypeHandler.findCodec(beanClass, beanClass);
    if (customCodec != null && customCodec.encodedType().equals(beanType)) {
      bean = customEncode(bean, beanType);
    }

    int numFields = schema.numFields();
    Expression.ListExpression expressions = new Expression.ListExpression();
    // schema field's name must correspond to descriptor's name.
    for (int i = 0; i < numFields; i++) {
      Field field = schema.field(i);
      if (projectionLiveNames != null && !projectionLiveNames.contains(field.name())) {
        // Removed wire field — no Java accessor to read from, so we cannot emit encode
        // code. The projection codec's encode body is unreachable anyway because
        // BinaryRowEncoder never dispatches a projection codec on write.
        continue;
      }
      Descriptor d = getDescriptorByFieldName(field.name());
      Preconditions.checkNotNull(d, "missing descriptor for schema field " + field.name());
      TypeRef<?> fieldType = d.getTypeRef();
      Expression fieldValue = getFieldValue(bean, d);
      Literal ordinal = Literal.ofInt(i);
      Expression.StaticInvoke foryField =
          new Expression.StaticInvoke(
              DataTypes.class, "fieldOfSchema", FORY_FIELD_TYPE, false, schemaExpr, ordinal);
      Expression fieldExpr =
          serializeFor(ordinal, fieldValue, writer, fieldType, field, foryField, new HashSet<>());
      expressions.add(fieldExpr);
    }
    if (projectionLiveNames != null) {
      // Decode-only: never run the writer logic. The expressions above were generated only for
      // their side effects on the codegen context (registering nested-bean encoder fields).
      return new Expression.Block(
          "throw new UnsupportedOperationException(\"projection codec is decode-only\");\n");
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
      int numFields = schema.numFields();
      // Build, in schema order, the per-slot bean-side info for live fields only. Discarded
      // slots are part of the row layout but have no Java target; we skip emitting any code
      // for them because BinaryRow's offset arithmetic is keyed on slot index, not on prior
      // reads.
      List<Descriptor> liveDescriptors = new ArrayList<>();
      List<Expression> liveValues = new ArrayList<>();
      for (int i = 0; i < numFields; i++) {
        Literal ordinal = Literal.ofInt(i);
        String wireName = schema.field(i).name();
        if (projectionLiveNames != null && !projectionLiveNames.contains(wireName)) {
          continue;
        }
        Descriptor d = getDescriptorByFieldName(wireName);
        Preconditions.checkNotNull(d, "missing descriptor for wire field " + wireName);
        TypeRef<?> fieldType = d.getTypeRef();
        Expression.Variable value =
            new Expression.Variable("value_" + d.getName(), nullValue(fieldType));
        liveDescriptors.add(d);
        liveValues.add(value);
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
        bean = buildRecordInstance(liveDescriptors, liveValues);
      } else {
        bean = newBean();
        expressions.add(bean);
        for (int i = 0; i < liveDescriptors.size(); i++) {
          expressions.add(setFieldValue(bean, liveDescriptors.get(i), liveValues.get(i)));
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

  /**
   * Build a record instance, supplying defaults for components not contributed by the wire. The
   * non-projection path always supplies every component; the projection path may supply a subset.
   */
  private Expression buildRecordInstance(
      List<Descriptor> liveDescriptors, List<Expression> liveValues) {
    Map<String, Expression> byName = new HashMap<>(liveDescriptors.size() * 2);
    for (int i = 0; i < liveDescriptors.size(); i++) {
      byName.put(liveDescriptors.get(i).getName(), liveValues.get(i));
    }
    RecordComponent[] components = RecordUtils.getRecordComponents(beanClass);
    Expression[] args = new Expression[components.length];
    for (int i = 0; i < components.length; i++) {
      String compName = components[i].getName();
      Expression value = byName.get(compName);
      if (value == null) {
        TypeRef<?> compType = TypeRef.of(components[i].getGenericType());
        value = nullValue(compType);
      }
      args[i] = value;
    }
    return new Expression.NewInstance(beanType, beanType.getRawType().getName(), args);
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
    int numFields = schema.numFields();
    for (int i = 0; i < numFields; i++) {
      Literal ordinal = Literal.ofInt(i);
      String wireName = schema.field(i).name();
      if (projectionLiveNames != null && !projectionLiveNames.contains(wireName)) {
        continue;
      }
      Descriptor d = getDescriptorByFieldName(wireName);
      TypeRef<?> fieldType = d.getTypeRef();
      Class<?> rawFieldType = fieldType.getRawType();
      // Resolve a codec on the raw field type before any Optional unwrap; keep in lockstep with the
      // canonical ordering in TypeInference.inferField.
      TypeRef<?> columnAccessType = fieldType;
      TypeRef<?> replacementType = customTypeHandler.replacementTypeFor(beanClass, rawFieldType);
      if (replacementType == null && rawFieldType == Optional.class) {
        columnAccessType = TypeUtils.getTypeArguments(fieldType).get(0);
        replacementType =
            customTypeHandler.replacementTypeFor(beanClass, columnAccessType.getRawType());
      }
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

    Map<String, Map<MethodType, Method>> methodsNeedingImpl = new HashMap<>();
    for (Method m : beanClass.getMethods()) {
      methodsNeedingImpl
          .computeIfAbsent(m.getName(), x -> new HashMap<>())
          .put(MethodType.methodType(m.getReturnType(), m.getParameterTypes()), m);
    }

    int numFields = schema.numFields();
    for (int i = 0; i < numFields; i++) {
      Literal ordinal = Literal.ofInt(i);
      String wireName = schema.field(i).name();
      if (projectionLiveNames != null && !projectionLiveNames.contains(wireName)) {
        // Removed wire field — no Java member to back this slot. The other interface methods
        // can still be served lazily from the row; the row's offset arithmetic does not need
        // us to read this slot.
        continue;
      }
      Descriptor d = getDescriptorByFieldName(wireName);
      TypeRef<?> fieldType = d.getTypeRef();
      Class<?> rawFieldType = fieldType.getRawType();

      Expression.Reference decodeValue =
          new Expression.Reference(decodeMethodName(i) + "(row)", fieldType);
      Expression getterImpl;
      if (fieldType.isPrimitive()) {
        getterImpl = new Expression.Return(decodeValue);
      } else {
        String fieldName = "f" + i + "_" + d.getName();
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
      methodsNeedingImpl
          .getOrDefault(d.getName(), new HashMap<>())
          .remove(MethodType.methodType(rawFieldType));
      implClass.addMethod(
          d.getName(), getterImpl.genCode(implClass).code(), fieldType.getRawType());
    }
    // Note: adding constructor captures init code, so must happen after all fields are collected
    implClass.addConstructor("this.row = row;", BinaryRow.class, "row");

    final boolean projecting = projectionLiveNames != null;
    methodsNeedingImpl.forEach(
        (methodName, signatures) ->
            signatures.forEach(
                (methodType, method) -> {
                  if (method.isDefault()) {
                    return;
                  }
                  Object[] params = new Object[methodType.parameterCount() * 2];
                  for (int i = 0; i < methodType.parameterCount(); i++) {
                    params[i * 2] = methodType.parameterType(i);
                    params[i * 2 + 1] = "unused" + i;
                  }
                  String body;
                  if (projecting && isAccessorOfAbsentField(methodName, methodType)) {
                    body =
                        "return "
                            + defaultValueExpression(methodType.returnType(), implClass)
                            + ";";
                  } else {
                    body = "throw new UnsupportedOperationException();";
                  }
                  implClass.addMethod(methodName, body, methodType.returnType(), params);
                }));

    return implClass;
  }

  /**
   * True when {@code methodName(returnType)} on the current bean class names a property whose field
   * is not in the historical schema this projection codec is generating. Such a method gets a
   * default-value body instead of {@code throw} so the interface proxy can serve callers that don't
   * know the field is missing in this version.
   */
  private boolean isAccessorOfAbsentField(String methodName, MethodType methodType) {
    // An accessor takes no arguments; the live-member pass above only removes the no-arg signature.
    // A parameterized method sharing a name and return type with a descriptor is not that field's
    // accessor, so it must still throw rather than be silenced into a default value.
    if (methodType.parameterCount() != 0) {
      return false;
    }
    // Look up by the raw method name, not via getDescriptorByFieldName's wire-name conversion: this
    // path runs only for interface beans (see buildImplClass), whose descriptor names are the
    // method
    // names themselves, so descriptorsMap is keyed by exactly the names we iterate here.
    Descriptor d = descriptorsMap.get(methodName);
    if (d == null) {
      return false;
    }
    // Match the raw return type, the same identity the live-field pass uses to remove an accessor
    // from methodsNeedingImpl above. A method whose return type differs is a different overload,
    // not this field's accessor, and must still throw.
    if (d.getTypeRef().getRawType() != methodType.returnType()) {
      return false;
    }
    // The main loop above emits getters for every wire field that is also a live Java member.
    // Anything left in methodsNeedingImpl that matches a descriptor by name and type must
    // correspond to a Java member whose wire field is not in this version.
    return true;
  }

  private static String defaultValueExpression(Class<?> returnType, CodegenContext ctx) {
    if (TypeUtils.isOptionalType(returnType)) {
      return ctx.type(returnType) + ".empty()";
    }
    return TypeUtils.defaultValue(returnType);
  }

  private Descriptor getDescriptorByFieldName(String fieldName) {
    String name = StringUtils.lowerUnderscoreToLowerCamelCase(fieldName);
    return descriptorsMap.get(name);
  }

  private String decodeMethodName(int i) {
    return "decode" + i + "_" + schema.field(i).name();
  }

  @Override
  protected Expression beanClassExpr() {
    if (GraalvmSupport.isGraalBuildTime()) {
      return staticBeanClassExpr();
    }
    return beanClassRef;
  }
}
