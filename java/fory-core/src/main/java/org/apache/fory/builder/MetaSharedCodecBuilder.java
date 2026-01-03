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

import static org.apache.fory.builder.Generated.GeneratedMetaSharedSerializer.SERIALIZER_FIELD_NAME;
import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.TypeUtils.STRING_TYPE;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.Fory;
import org.apache.fory.builder.Generated.GeneratedMetaSharedSerializer;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.codegen.Expression.StaticInvoke;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.ClassDef;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.CodegenSerializer;
import org.apache.fory.serializer.MetaSharedSerializer;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.serializer.converter.FieldConverter;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorBuilder;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.util.DefaultValueUtils;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.record.RecordComponent;
import org.apache.fory.util.record.RecordUtils;

/**
 * A meta-shared compatible deserializer builder based on {@link ClassDef}. This builder will
 * compare fields between {@link ClassDef} and class fields, then create serializer to read and
 * set/skip corresponding fields to support type forward/backward compatibility. Serializer are
 * forward to {@link ObjectCodecBuilder} for now. We can consolidate fields between peers to create
 * better serializers to serialize common fields between peers for efficiency.
 *
 * <p>With meta context share enabled and compatible mode, the {@link ObjectCodecBuilder} will take
 * all non-inner final types as non-final, so that fory can write class definition when write class
 * info for those types.
 *
 * @see CompatibleMode
 * @see ForyBuilder#withMetaShare
 * @see GeneratedMetaSharedSerializer
 * @see MetaSharedSerializer
 */
public class MetaSharedCodecBuilder extends ObjectCodecBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(MetaSharedCodecBuilder.class);

  private final ClassDef classDef;
  private final String defaultValueLanguage;
  private final DefaultValueUtils.DefaultValueField[] defaultValueFields;

  public MetaSharedCodecBuilder(TypeRef<?> beanType, Fory fory, ClassDef classDef) {
    super(beanType, fory, GeneratedMetaSharedSerializer.class);
    Preconditions.checkArgument(
        !fory.getConfig().checkClassVersion(),
        "Class version check should be disabled when compatible mode is enabled.");
    this.classDef = classDef;
    Collection<Descriptor> descriptors =
        fory(
            f -> MetaSharedSerializer.consolidateFields(f._getTypeResolver(), beanClass, classDef));
    DescriptorGrouper grouper = typeResolver(r -> r.createDescriptorGrouper(descriptors, false));
    List<Descriptor> sortedDescriptors = grouper.getSortedDescriptors();
    if (org.apache.fory.util.Utils.debugOutputEnabled()) {
      LOG.info("========== sorted descriptors for {} ==========", classDef.getClassName());
      for (Descriptor d : sortedDescriptors) {
        LOG.info(
            "  {} -> {}, ref {}, nullable {}",
            d.getName(),
            d.getTypeName(),
            d.isTrackingRef(),
            d.isNullable());
      }
    }
    objectCodecOptimizer =
        new ObjectCodecOptimizer(beanClass, grouper, !fory.isBasicTypesRefIgnored(), ctx);

    String defaultValueLanguage = "None";
    DefaultValueUtils.DefaultValueField[] defaultValueFields =
        new DefaultValueUtils.DefaultValueField[0];
    if (fory.getConfig().isScalaOptimizationEnabled()) {
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
    Integer id = idGenerator.get(classDef.getId());
    if (id == null) {
      synchronized (idGenerator) {
        id = idGenerator.computeIfAbsent(classDef.getId(), k -> idGenerator.size());
      }
    }
    return "MetaShared" + id;
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
    ctx.addField(ctx.type(Fory.class), FORY_NAME);
    String constructorCode =
        StringUtils.format(
            ""
                + "super(${fory}, ${cls});\n"
                + "this.${fory} = ${fory};\n"
                + "${serializer} = ${builderClass}.setCodegenSerializer(${fory}, ${cls}, this);\n",
            "fory",
            FORY_NAME,
            "cls",
            POJO_CLASS_TYPE_NAME,
            "builderClass",
            MetaSharedCodecBuilder.class.getName(),
            "serializer",
            SERIALIZER_FIELD_NAME);
    ctx.clearExprState();
    Expression decodeExpr = buildDecodeExpression();
    String decodeCode = decodeExpr.genCode(ctx).code();
    decodeCode = ctx.optimizeMethodCode(decodeCode);
    ctx.overrideMethod(readMethodName, decodeCode, Object.class, MemoryBuffer.class, BUFFER_NAME);
    registerJITNotifyCallback();
    ctx.addConstructor(constructorCode, Fory.class, FORY_NAME, Class.class, POJO_CLASS_TYPE_NAME);
    return ctx.genCode();
  }

  @Override
  protected void addCommonImports() {
    super.addCommonImports();
    ctx.addImport(GeneratedMetaSharedSerializer.class);
  }

  // Invoked by JIT.
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Serializer setCodegenSerializer(
      Fory fory, Class<?> cls, GeneratedMetaSharedSerializer s) {
    if (GraalvmSupport.isGraalRuntime()) {
      return typeResolver(fory, r -> r.getSerializer(s.getType()));
    }
    // This method hold jit lock, so create jit serializer async to avoid block serialization.
    Class serializerClass =
        fory.getJITContext()
            .registerSerializerJITCallback(
                () -> ObjectSerializer.class,
                () -> CodegenSerializer.loadCodegenSerializer(fory, s.getType()),
                c -> s.serializer = Serializers.newSerializer(fory, s.getType(), c));
    return Serializers.newSerializer(fory, cls, serializerClass);
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
  protected Expression setFieldValue(Expression bean, Descriptor descriptor, Expression value) {
    if (descriptor.getField() == null) {
      FieldConverter<?> converter = descriptor.getFieldConverter();
      if (converter != null) {
        Field field = converter.getField();
        StaticInvoke converted =
            new StaticInvoke(
                converter.getClass(), "convertFrom", TypeRef.of(field.getType()), value);
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

  @Override
  protected Expression newBean() {
    Expression bean = super.newBean();
    if (defaultValueFields.length == 0) {
      return bean;
    }

    Expression.ListExpression setDefaultsExpr = new Expression.ListExpression();
    setDefaultsExpr.add(bean);
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
      setDefaultsExpr.add(super.setFieldValue(bean, descriptor, defaultValueExpr));
    }
    setDefaultsExpr.add(bean);
    return setDefaultsExpr;
  }
}
