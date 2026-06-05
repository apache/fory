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

package org.apache.fory.builder;

import static org.apache.fory.builder.Generated.GeneratedCompatibleSerializer.SERIALIZER_FIELD_NAME;
import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.TypeUtils.STRING_TYPE;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.builder.Generated.GeneratedCompatibleSerializer;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.codegen.Expression.StaticInvoke;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.context.ReadContext;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.CodegenSerializer;
import org.apache.fory.serializer.CompatibleSerializer;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.serializer.converter.FieldConverter;
import org.apache.fory.serializer.converter.FieldConverters;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorBuilder;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.ScalaTypes;
import org.apache.fory.util.DefaultValueUtils;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.record.RecordComponent;
import org.apache.fory.util.record.RecordUtils;

/**
 * A compatible deserializer builder based on shared {@link TypeDef} metadata. This builder compares
 * remote fields with local class fields, then generates code to read, set, or skip fields for type
 * forward/backward compatibility. Writes are delegated to {@link ObjectCodecBuilder} for now.
 *
 * <p>With meta context share enabled and compatible mode, the {@link ObjectCodecBuilder} will take
 * all non-inner final types as non-final, so that fory can write class definition when write class
 * info for those types.
 *
 * @see ForyBuilder#withMetaShare
 * @see GeneratedCompatibleSerializer
 * @see CompatibleSerializer
 */
public class CompatibleCodecBuilder extends ObjectCodecBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(CompatibleCodecBuilder.class);

  private final TypeDef typeDef;
  private final String defaultValueLanguage;
  private final DefaultValueUtils.DefaultValueField[] defaultValueFields;

  public CompatibleCodecBuilder(TypeRef<?> beanType, Fory fory, TypeDef typeDef) {
    super(beanType, fory, GeneratedCompatibleSerializer.class);
    Preconditions.checkArgument(
        !fory.getConfig().checkClassVersion(),
        "Class version check should be disabled when compatible mode is enabled.");
    this.typeDef = typeDef;
    DescriptorGrouper grouper = typeResolver(r -> r.createDescriptorGrouper(typeDef, beanClass));
    List<Descriptor> sortedDescriptors = grouper.getSortedDescriptors();
    if (org.apache.fory.util.Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info(
          "========== {} sorted descriptors for {} ==========",
          typeDef.getFieldCount(),
          typeDef.getClassName());
      for (Descriptor d : sortedDescriptors) {
        LOG.info(
            "  {} -> {}, ref {}, nullable {}",
            StringUtils.toSnakeCase(d.getName()),
            d.getTypeName(),
            d.isTrackingRef(),
            d.isNullable());
      }
    }
    objectCodecOptimizer = new ObjectCodecOptimizer(beanClass, grouper, false, ctx);

    String defaultValueLanguage = "None";
    DefaultValueUtils.DefaultValueField[] defaultValueFields =
        new DefaultValueUtils.DefaultValueField[0];
    if (ScalaTypes.SCALA_AVAILABLE) {
      // Check if this is a Scala case class and build default value fields
      defaultValueFields =
          DefaultValueUtils.getScalaDefaultValueSupport()
              .buildDefaultValueFields(fory, beanClass, sortedDescriptors);
      if (defaultValueFields.length > 0) {
        defaultValueLanguage = "Scala";
      }
    }
    if (defaultValueFields.length == 0) {
      DefaultValueUtils.DefaultValueSupport kotlinDefaultValueSupport =
          DefaultValueUtils.getKotlinDefaultValueSupport();
      if (kotlinDefaultValueSupport != null) {
        defaultValueFields =
            kotlinDefaultValueSupport.buildDefaultValueFields(fory, beanClass, sortedDescriptors);
        if (defaultValueFields.length > 0) {
          defaultValueLanguage = "Kotlin";
        }
      }
    }
    this.defaultValueLanguage = defaultValueLanguage;
    this.defaultValueFields = defaultValueFields;
  }

  // Must be static to be shared across the whole process life.
  private static final Map<Long, Integer> idGenerator = new ConcurrentHashMap<>();

  @Override
  protected String codecSuffix() {
    // For every class def sent from different peer, if the class def are different, then
    // a new serializer needs being generated.
    Integer id = idGenerator.get(typeDef.getId());
    if (id == null) {
      synchronized (idGenerator) {
        id = idGenerator.computeIfAbsent(typeDef.getId(), k -> idGenerator.size());
      }
    }
    return "Compatible" + id;
  }

  @Override
  public String genCode() {
    ctx.setPackage(CodeGenerator.getPackage(beanClass));
    String className = codecClassName(beanClass);
    ctx.setClassName(className);
    // don't addImport(beanClass), because user class may name collide.
    ctx.extendsClasses(ctx.type(parentSerializerClass));
    ctx.reserveName(POJO_CLASS_TYPE_NAME);
    ctx.reserveName(SERIALIZER_FIELD_NAME);
    String constructorCode =
        StringUtils.format(
            ""
                + "super(${typeResolver}, ${cls});\n"
                + "this.${generatedTypeResolver} = (${generatedTypeResolverType}) ${typeResolver};\n",
            "typeResolver",
            CONSTRUCTOR_TYPE_RESOLVER_NAME,
            "generatedTypeResolver",
            TYPE_RESOLVER_NAME,
            "generatedTypeResolverType",
            ctx.type(concreteTypeResolverType),
            "cls",
            POJO_CLASS_TYPE_NAME);
    constructorCode +=
        StringUtils.format(
            "${serializer} = ${builderClass}.setCodegenSerializer(${typeResolver}, ${cls}, this);\n",
            "serializer",
            SERIALIZER_FIELD_NAME,
            "builderClass",
            CompatibleCodecBuilder.class.getName(),
            "typeResolver",
            CONSTRUCTOR_TYPE_RESOLVER_NAME,
            "cls",
            POJO_CLASS_TYPE_NAME);
    ctx.clearExprState();
    Expression decodeExpr = buildDecodeExpression();
    String decodeCode = decodeExpr.genCode(ctx).code();
    decodeCode = ctx.optimizeMethodCode(decodeCode);
    decodeCode = decodeCode == null ? "" : decodeCode;
    decodeCode =
        StringUtils.format(
            "${bufferType} ${buffer} = ${readContext}.getBuffer();\n${code}",
            "bufferType",
            ctx.type(MemoryBuffer.class),
            "buffer",
            BUFFER_NAME,
            "readContext",
            READ_CONTEXT_NAME,
            "code",
            decodeCode);
    ctx.overrideMethod(
        readMethodName, decodeCode, Object.class, ReadContext.class, READ_CONTEXT_NAME);
    registerJITNotifyCallback();
    ctx.addConstructor(
        constructorCode,
        TypeResolver.class,
        CONSTRUCTOR_TYPE_RESOLVER_NAME,
        Class.class,
        POJO_CLASS_TYPE_NAME);
    return ctx.genCode();
  }

  @Override
  protected void addCommonImports() {
    super.addCommonImports();
    ctx.addImport(GeneratedCompatibleSerializer.class);
  }

  // Invoked by JIT.
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Serializer setCodegenSerializer(
      TypeResolver typeResolver, Class<?> cls, GeneratedCompatibleSerializer s) {
    if (GraalvmSupport.isGraalRuntime()) {
      return typeResolver
          .getJITContext()
          .asyncVisitFory(f -> f.getTypeResolver().getSerializer(s.getType()));
    }
    // This method hold jit lock, so create jit serializer async to avoid block serialization.
    Class serializerClass =
        typeResolver
            .getJITContext()
            .registerSerializerJITCallback(
                () -> ObjectSerializer.class,
                () -> CodegenSerializer.loadCodegenSerializer(typeResolver, s.getType()),
                c -> s.serializer = Serializers.newSerializer(typeResolver, s.getType(), c));
    return Serializers.newSerializer(typeResolver, cls, serializerClass);
  }

  @Override
  public Expression buildEncodeExpression() {
    throw new IllegalStateException("unreachable");
  }

  @Override
  protected Expression buildComponentsArray() {
    return buildDefaultComponentsArray();
  }

  protected Expression createRecord(SortedMap<Integer, Expression> recordComponents) {
    RecordComponent[] components = RecordUtils.getRecordComponents(beanClass);
    Object[] defaultValues = RecordUtils.buildRecordComponentDefaultValues(beanClass);
    for (int i = 0; i < defaultValues.length; i++) {
      if (!recordComponents.containsKey(i)) {
        Object defaultValue = defaultValues[i];
        assert components != null;
        RecordComponent component = components[i];
        recordComponents.put(i, new Literal(defaultValue, TypeRef.of(component.getType())));
      }
    }
    Expression[] params = recordComponents.values().toArray(new Expression[0]);
    return new Expression.NewInstance(beanType, params);
  }

  @Override
  protected List<Expression> deserializePrimitives(
      Expression bean, Expression buffer, List<List<Descriptor>> primitiveGroups) {
    boolean hasConverter = false;
    for (List<Descriptor> group : primitiveGroups) {
      for (Descriptor descriptor : group) {
        if (descriptor.getFieldConverter() != null) {
          hasConverter = true;
          break;
        }
      }
      if (hasConverter) {
        break;
      }
    }
    if (!hasConverter) {
      return super.deserializePrimitives(bean, buffer, primitiveGroups);
    }

    List<Expression> expressions = new ArrayList<>();
    List<List<Descriptor>> bulkGroups = new ArrayList<>();
    for (List<Descriptor> group : primitiveGroups) {
      if (!hasFieldConverter(group)) {
        bulkGroups.add(group);
        continue;
      }
      appendPrimitiveBulkReads(expressions, bean, buffer, bulkGroups);
      for (Descriptor descriptor : group) {
        expressions.add(
            deserializeField(
                buffer,
                descriptor,
                value ->
                    setFieldValue(
                        bean, descriptor, tryInlineCast(value, descriptor.getTypeRef()))));
      }
    }
    appendPrimitiveBulkReads(expressions, bean, buffer, bulkGroups);
    return expressions;
  }

  @Override
  protected Expression deserializeField(
      Expression buffer, Descriptor descriptor, Function<Expression, Expression> callback) {
    FieldConverter<?> converter = descriptor.getFieldConverter();
    if (converter == null) {
      return super.deserializeField(buffer, descriptor, callback);
    }
    StaticInvoke sourceValue =
        new StaticInvoke(
            FieldConverters.class,
            "readSourceScalar",
            OBJECT_TYPE,
            readContextRef(),
            Literal.ofInt(FieldConverters.fromDispatchId(converter)),
            Literal.ofClass(FieldConverters.fromType(converter)),
            Literal.ofBoolean(descriptor.isNullable()),
            Literal.ofBoolean(
                new SerializationFieldInfo(typeResolver, descriptor).useDeclaredTypeInfo),
            Literal.ofString(FieldConverters.fieldName(converter)));
    return new Expression.ListExpression(sourceValue, callback.apply(sourceValue));
  }

  @Override
  protected Expression setFieldValue(Expression bean, Descriptor descriptor, Expression value) {
    if (descriptor.getField() == null) {
      FieldConverter<?> converter = descriptor.getFieldConverter();
      if (converter != null) {
        Field field = converter.getField();
        StaticInvoke convertedValue =
            new StaticInvoke(
                FieldConverters.class,
                "convertValue",
                OBJECT_TYPE,
                Literal.ofInt(FieldConverters.fromDispatchId(converter)),
                Literal.ofClass(FieldConverters.fromType(converter)),
                Literal.ofInt(FieldConverters.toDispatchId(converter)),
                Literal.ofClass(FieldConverters.toType(converter)),
                Literal.ofString(FieldConverters.fieldName(converter)),
                value);
        Expression converted = new Expression.Cast(convertedValue, TypeRef.of(field.getType()));
        Descriptor newDesc =
            new DescriptorBuilder(descriptor)
                .field(field)
                .type(field.getType())
                .typeRef(TypeRef.of(field.getType()))
                .build();
        return super.setFieldValue(bean, newDesc, converted);
      }
      // Field doesn't exist in current class, skip set this field value.
      // Note that the field value shouldn't be an inlined value, otherwise field value read may
      // be ignored.
      // Add an ignored call here to make expression type to void.
      return new StaticInvoke(ExceptionUtils.class, "ignore", value);
    }
    return super.setFieldValue(bean, descriptor, value);
  }

  private static boolean hasFieldConverter(List<Descriptor> descriptors) {
    for (Descriptor descriptor : descriptors) {
      if (descriptor.getFieldConverter() != null) {
        return true;
      }
    }
    return false;
  }

  private void appendPrimitiveBulkReads(
      List<Expression> expressions,
      Expression bean,
      Expression buffer,
      List<List<Descriptor>> bulkGroups) {
    if (!bulkGroups.isEmpty()) {
      expressions.addAll(super.deserializePrimitives(bean, buffer, bulkGroups));
      bulkGroups.clear();
    }
  }

  @Override
  protected Expression newBean() {
    Expression bean = super.newBean();
    if (defaultValueFields.length == 0) {
      return bean;
    }

    Expression.ListExpression setDefaultsExpr = new Expression.ListExpression();
    setDefaultsExpr.add(bean);
    addDefaultValueSetters(setDefaultsExpr, bean);
    setDefaultsExpr.add(bean);
    return setDefaultsExpr;
  }

  private void addDefaultValueSetters(Expression.ListExpression expressions, Expression bean) {
    Map<Member, Descriptor> descriptors = Descriptor.getAllDescriptorsMap(beanClass);
    for (DefaultValueUtils.DefaultValueField defaultField : defaultValueFields) {
      Object defaultValue = defaultField.getDefaultValue();
      Member member = defaultField.getFieldAccessor().getField();
      Descriptor descriptor = descriptors.get(member);
      TypeRef<?> typeRef = descriptor.getTypeRef();
      Expression defaultValueExpr;
      if (typeRef.unwrap().isPrimitive() || typeRef.equals(STRING_TYPE)) {
        defaultValueExpr = new Literal(defaultValue, typeRef);
      } else {
        String funcName = "get" + defaultValueLanguage + "DefaultValue";
        defaultValueExpr =
            getOrCreateField(
                true,
                typeRef.getRawType(),
                member.getName(),
                () -> {
                  Expression expr =
                      new StaticInvoke(
                          DefaultValueUtils.class,
                          funcName,
                          OBJECT_TYPE,
                          staticBeanClassExpr(),
                          Literal.ofString(member.getName()));
                  return new Expression.Cast(expr, typeRef);
                });
      }
      expressions.add(super.setFieldValue(bean, descriptor, defaultValueExpr));
    }
  }
}
