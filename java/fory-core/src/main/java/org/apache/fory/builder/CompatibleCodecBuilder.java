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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorBuilder;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Float16;
import org.apache.fory.type.ScalaTypes;
import org.apache.fory.type.unsigned.UInt16;
import org.apache.fory.type.unsigned.UInt32;
import org.apache.fory.type.unsigned.UInt64;
import org.apache.fory.type.unsigned.UInt8;
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
  private static final String REMOTE_FIELD_INFOS_NAME = "_f_remoteFieldInfos";
  private static final String LOCAL_FIELD_INFOS_NAME = "_f_localFieldInfosByRemoteOrder";
  private static final String CONSTRUCTOR_TYPE_DEF_NAME = "_f_typeDef";

  private final TypeDef typeDef;
  private final String defaultValueLanguage;
  private final DefaultValueUtils.DefaultValueField[] defaultValueFields;
  private final Map<Descriptor, Integer> fieldInfoIds = new IdentityHashMap<>();
  private String remoteFieldInfosName;
  private String localFieldInfosName;

  public CompatibleCodecBuilder(TypeRef<?> beanType, Fory fory, TypeDef typeDef) {
    super(beanType, fory, GeneratedCompatibleSerializer.class);
    Preconditions.checkArgument(
        !fory.getConfig().checkClassVersion(),
        "Class version check should be disabled when compatible mode is enabled.");
    this.typeDef = typeDef;
    DescriptorGrouper grouper = typeResolver(r -> r.createDescriptorGrouper(typeDef, beanClass));
    List<Descriptor> sortedDescriptors = grouper.getSortedDescriptors();
    for (int i = 0; i < sortedDescriptors.size(); i++) {
      fieldInfoIds.put(sortedDescriptors.get(i), i);
    }
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
    ctx.reserveName(CONSTRUCTOR_TYPE_DEF_NAME);
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
        POJO_CLASS_TYPE_NAME,
        TypeDef.class,
        CONSTRUCTOR_TYPE_DEF_NAME);
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
        FieldConverter<?> converter = descriptor.getFieldConverter();
        Expression targetValue =
            converter == null ? null : fieldConverterTargetRead(descriptor, converter);
        if (targetValue != null) {
          expressions.add(
              new Expression.ListExpression(
                  targetValue,
                  setFieldConverterTargetValue(bean, descriptor, converter, targetValue)));
        } else {
          expressions.add(
              deserializeField(
                  buffer,
                  descriptor,
                  value ->
                      setFieldValue(
                          bean, descriptor, tryInlineCast(value, descriptor.getTypeRef()))));
        }
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
    Expression targetValue = fieldConverterTargetRead(descriptor, converter);
    Preconditions.checkState(
        targetValue != null,
        "Unsupported compatible scalar converter target " + FieldConverters.toType(converter));
    return new Expression.ListExpression(targetValue, callback.apply(targetValue));
  }

  @Override
  protected Expression setFieldValue(Expression bean, Descriptor descriptor, Expression value) {
    if (descriptor.getField() == null) {
      FieldConverter<?> converter = descriptor.getFieldConverter();
      if (converter != null) {
        return setFieldConverterTargetValue(bean, descriptor, converter, value);
      }
      // Field doesn't exist in current class, skip set this field value.
      // Note that the field value shouldn't be an inlined value, otherwise field value read may
      // be ignored.
      // Add an ignored call here to make expression type to void.
      return new StaticInvoke(ExceptionUtils.class, "ignore", value);
    }
    return super.setFieldValue(bean, descriptor, value);
  }

  private Expression setFieldConverterTargetValue(
      Expression bean, Descriptor descriptor, FieldConverter<?> converter, Expression value) {
    Field field = converter.getField();
    Descriptor targetDescriptor =
        new DescriptorBuilder(descriptor)
            .field(field)
            .type(field.getType())
            .typeRef(TypeRef.of(field.getType()))
            .build();
    return super.setFieldValue(bean, targetDescriptor, value);
  }

  private Expression fieldConverterTargetRead(Descriptor descriptor, FieldConverter<?> converter) {
    Class<?> targetType = converter.getField().getType();
    String helper = fieldConverterTargetReader(targetType);
    if (helper == null) {
      return null;
    }
    return new StaticInvoke(
        FieldConverters.class,
        helper,
        TypeRef.of(targetType),
        readContextRef(),
        remoteFieldInfo(descriptor),
        localFieldInfo(descriptor));
  }

  private static String fieldConverterTargetReader(Class<?> targetType) {
    if (targetType == boolean.class) {
      return "readBooleanTarget";
    } else if (targetType == Boolean.class) {
      return "readBoxedBooleanTarget";
    } else if (targetType == byte.class) {
      return "readByteTarget";
    } else if (targetType == Byte.class) {
      return "readBoxedByteTarget";
    } else if (targetType == short.class) {
      return "readShortTarget";
    } else if (targetType == Short.class) {
      return "readBoxedShortTarget";
    } else if (targetType == int.class) {
      return "readIntTarget";
    } else if (targetType == Integer.class) {
      return "readBoxedIntTarget";
    } else if (targetType == long.class) {
      return "readLongTarget";
    } else if (targetType == Long.class) {
      return "readBoxedLongTarget";
    } else if (targetType == float.class) {
      return "readFloatTarget";
    } else if (targetType == Float.class) {
      return "readBoxedFloatTarget";
    } else if (targetType == double.class) {
      return "readDoubleTarget";
    } else if (targetType == Double.class) {
      return "readBoxedDoubleTarget";
    } else if (targetType == String.class) {
      return "readStringTarget";
    } else if (targetType == BigDecimal.class) {
      return "readDecimalTarget";
    } else if (targetType == UInt8.class) {
      return "readUInt8Target";
    } else if (targetType == UInt16.class) {
      return "readUInt16Target";
    } else if (targetType == UInt32.class) {
      return "readUInt32Target";
    } else if (targetType == UInt64.class) {
      return "readUInt64Target";
    } else if (targetType == Float16.class) {
      return "readFloat16Target";
    } else if (targetType == BFloat16.class) {
      return "readBFloat16Target";
    }
    return null;
  }

  private static boolean hasFieldConverter(List<Descriptor> descriptors) {
    for (Descriptor descriptor : descriptors) {
      if (descriptor.getFieldConverter() != null) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected TypeRef<?> readValueTypeRef(Descriptor descriptor) {
    FieldConverter<?> converter = descriptor.getFieldConverter();
    if (converter != null) {
      return TypeRef.of(converter.getField().getGenericType());
    }
    return super.readValueTypeRef(descriptor);
  }

  private Expression remoteFieldInfo(Descriptor descriptor) {
    ensureCompatibleFieldInfos();
    return fieldInfo(remoteFieldInfosName, descriptor);
  }

  private Expression localFieldInfo(Descriptor descriptor) {
    ensureCompatibleFieldInfos();
    return fieldInfo(localFieldInfosName, descriptor);
  }

  private Expression fieldInfo(String fieldInfosName, Descriptor descriptor) {
    Integer fieldInfoId = fieldInfoIds.get(descriptor);
    Preconditions.checkState(
        fieldInfoId != null, "Unknown compatible field descriptor " + descriptor);
    return new Expression.Reference(
        fieldInfosName + "[" + fieldInfoId + "]", TypeRef.of(SerializationFieldInfo.class));
  }

  private void ensureCompatibleFieldInfos() {
    if (remoteFieldInfosName != null) {
      return;
    }
    remoteFieldInfosName = ctx.newName(REMOTE_FIELD_INFOS_NAME);
    localFieldInfosName = ctx.newName(LOCAL_FIELD_INFOS_NAME);
    Expression constructorResolver =
        new Expression.Reference(CONSTRUCTOR_TYPE_RESOLVER_NAME, TypeRef.of(TypeResolver.class));
    Expression constructorClass =
        new Expression.Reference(POJO_CLASS_TYPE_NAME, TypeRef.of(Class.class));
    Expression constructorTypeDef =
        new Expression.Reference(CONSTRUCTOR_TYPE_DEF_NAME, TypeRef.of(TypeDef.class));
    TypeRef<SerializationFieldInfo[]> fieldInfoArrayType =
        TypeRef.of(SerializationFieldInfo[].class);
    ctx.addField(
        false,
        true,
        ctx.type(fieldInfoArrayType),
        remoteFieldInfosName,
        new StaticInvoke(
            CompatibleCodecBuilder.class,
            "buildRemoteFieldInfos",
            fieldInfoArrayType,
            constructorResolver,
            constructorClass,
            constructorTypeDef));
    ctx.addField(
        false,
        true,
        ctx.type(fieldInfoArrayType),
        localFieldInfosName,
        new StaticInvoke(
            CompatibleCodecBuilder.class,
            "buildLocalFieldInfosByRemoteOrder",
            fieldInfoArrayType,
            constructorResolver,
            constructorClass,
            constructorTypeDef));
  }

  public static SerializationFieldInfo[] buildRemoteFieldInfos(
      TypeResolver typeResolver, Class<?> cls, TypeDef typeDef) {
    List<Descriptor> descriptors =
        typeResolver.createDescriptorGrouper(typeDef, cls).getSortedDescriptors();
    SerializationFieldInfo[] fieldInfos = new SerializationFieldInfo[descriptors.size()];
    for (int i = 0; i < descriptors.size(); i++) {
      fieldInfos[i] = new SerializationFieldInfo(typeResolver, descriptors.get(i));
    }
    return fieldInfos;
  }

  public static SerializationFieldInfo[] buildLocalFieldInfosByRemoteOrder(
      TypeResolver typeResolver, Class<?> cls, TypeDef typeDef) {
    List<Descriptor> descriptors =
        typeResolver.createDescriptorGrouper(typeDef, cls).getSortedDescriptors();
    Map<Field, Descriptor> localDescriptors = localDescriptorsByField(typeResolver, cls);
    SerializationFieldInfo[] fieldInfos = new SerializationFieldInfo[descriptors.size()];
    for (int i = 0; i < descriptors.size(); i++) {
      Descriptor localDescriptor = localDescriptor(descriptors.get(i), localDescriptors);
      if (localDescriptor != null) {
        fieldInfos[i] = new SerializationFieldInfo(typeResolver, localDescriptor);
      }
    }
    return fieldInfos;
  }

  private static Map<Field, Descriptor> localDescriptorsByField(
      TypeResolver typeResolver, Class<?> cls) {
    Collection<Descriptor> descriptors = typeResolver.getFieldDescriptors(cls, true);
    Map<Field, Descriptor> descriptorsByField = new HashMap<>();
    for (Descriptor descriptor : descriptors) {
      Field field = descriptor.getField();
      if (field != null) {
        descriptorsByField.put(field, descriptor);
      }
    }
    return descriptorsByField;
  }

  private static Descriptor localDescriptor(
      Descriptor remoteDescriptor, Map<Field, Descriptor> localDescriptors) {
    FieldConverter<?> converter = remoteDescriptor.getFieldConverter();
    if (converter != null) {
      return localDescriptors.get(converter.getField());
    }
    if (remoteDescriptor.getField() != null) {
      return remoteDescriptor;
    }
    return null;
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
