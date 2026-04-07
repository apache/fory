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

import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.Fory;
import org.apache.fory.builder.Generated.GeneratedMetaSharedLayerSerializer;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.ListExpression;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.Expression.StaticInvoke;
import org.apache.fory.codegen.ExpressionUtils;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;

/**
 * A JIT codec builder for single-layer meta-shared serialization. This builder generates optimized
 * serializers that only handle fields from a specific class layer, without including parent class
 * fields.
 *
 * <p>This is used by {@link org.apache.fory.serializer.ObjectStreamSerializer} to generate JIT
 * serializers for each layer in the class hierarchy.
 *
 * @see org.apache.fory.serializer.MetaSharedLayerSerializer
 * @see MetaSharedCodecBuilder
 * @see GeneratedMetaSharedLayerSerializer
 */
public class MetaSharedLayerCodecBuilder extends ObjectCodecBuilder {
  private final TypeDef layerTypeDef;
  private final Class<?> layerMarkerClass;

  public MetaSharedLayerCodecBuilder(
      TypeRef<?> beanType, Fory fory, TypeDef layerTypeDef, Class<?> layerMarkerClass) {
    super(beanType, fory, GeneratedMetaSharedLayerSerializer.class);
    Preconditions.checkArgument(
        !fory.getConfig().checkClassVersion(),
        "Class version check should be disabled when compatible mode is enabled.");
    this.layerTypeDef = layerTypeDef;
    this.layerMarkerClass = layerMarkerClass;
    DescriptorGrouper grouper =
        typeResolver(r -> r.createDescriptorGrouper(layerTypeDef, beanClass));
    objectCodecOptimizer = new ObjectCodecOptimizer(beanClass, grouper, false, ctx);
  }

  private static final Map<Long, Integer> idGenerator = new ConcurrentHashMap<>();

  @Override
  protected String codecSuffix() {
    Integer id = idGenerator.get(layerTypeDef.getId());
    if (id == null) {
      synchronized (idGenerator) {
        id = idGenerator.computeIfAbsent(layerTypeDef.getId(), k -> idGenerator.size());
      }
    }
    return "MetaSharedLayer" + id;
  }

  @Override
  public String genCode() {
    ctx.setPackage(CodeGenerator.getPackage(beanClass));
    String className = codecClassName(beanClass);
    ctx.setClassName(className);
    ctx.extendsClasses(ctx.type(parentSerializerClass));
    ctx.reserveName(POJO_CLASS_TYPE_NAME);
    String constructorCode =
        StringUtils.format(
            ""
                + "super(${typeResolver}, ${cls});\n"
                + "this.${generatedTypeResolver} = (${generatedTypeResolverType}) ${typeResolver};\n"
                + "${typeResolver}.setSerializerIfAbsent(${cls}, this);\n",
            "typeResolver",
            CONSTRUCTOR_TYPE_RESOLVER_NAME,
            "generatedTypeResolver",
            TYPE_RESOLVER_NAME,
            "generatedTypeResolverType",
            ctx.type(concreteTypeResolverType),
            "cls",
            POJO_CLASS_TYPE_NAME);

    ctx.clearExprState();
    String encodeCode = buildEncodeExpression().genCode(ctx).code();
    encodeCode = ctx.optimizeMethodCode(encodeCode);
    encodeCode = encodeCode == null ? "" : encodeCode;
    encodeCode =
        StringUtils.format(
                "${bufferType} ${buffer} = ${writeContext}.getBuffer();\n",
                "bufferType",
                ctx.type(MemoryBuffer.class),
                "buffer",
                BUFFER_NAME,
                "writeContext",
                WRITE_CONTEXT_NAME)
            + encodeCode;
    if (encodeCode.contains(REF_WRITER_NAME)) {
      encodeCode =
          StringUtils.format(
                  "${refWriterType} ${refWriter} = (${refWriterType}) ${writeContext}.getRefWriter();\n",
                  "refWriterType",
                  ctx.type(concreteRefWriterType),
                  "refWriter",
                  REF_WRITER_NAME,
                  "writeContext",
                  WRITE_CONTEXT_NAME)
              + encodeCode;
    }

    ctx.clearExprState();
    String decodeCode = buildReadAndSetFieldsExpression().genCode(ctx).code();
    decodeCode = ctx.optimizeMethodCode(decodeCode);
    decodeCode = decodeCode == null ? "" : decodeCode;
    decodeCode =
        StringUtils.format(
                "${bufferType} ${buffer} = ${readContext}.getBuffer();\n",
                "bufferType",
                ctx.type(MemoryBuffer.class),
                "buffer",
                BUFFER_NAME,
                "readContext",
                READ_CONTEXT_NAME)
            + decodeCode;

    ctx.overrideMethod(
        "writeFieldsOnly",
        encodeCode,
        void.class,
        WriteContext.class,
        WRITE_CONTEXT_NAME,
        Object.class,
        ROOT_OBJECT_NAME);
    ctx.overrideMethod(
        "readAndSetFields",
        decodeCode,
        Object.class,
        ReadContext.class,
        READ_CONTEXT_NAME,
        Object.class,
        ROOT_OBJECT_NAME);
    registerJITNotifyCallback();
    ctx.addConstructor(
        constructorCode,
        org.apache.fory.resolver.TypeResolver.class,
        CONSTRUCTOR_TYPE_RESOLVER_NAME,
        Class.class,
        POJO_CLASS_TYPE_NAME);
    return ctx.genCode();
  }

  @Override
  protected void addCommonImports() {
    super.addCommonImports();
    ctx.addImport(GeneratedMetaSharedLayerSerializer.class);
  }

  @Override
  protected Expression getFieldValue(Expression bean, Descriptor descriptor) {
    if (descriptor.getField() == null) {
      return ExpressionUtils.defaultValue(descriptor.getRawType());
    }
    return super.getFieldValue(bean, descriptor);
  }

  @Override
  protected Expression serializeField(
      Expression fieldValue, Expression buffer, Descriptor descriptor) {
    if (descriptor.getField() == null
        && fieldValue instanceof Literal
        && ((Literal) fieldValue).getValue() == null
        && !descriptor.getTypeRef().isPrimitive()) {
      if (descriptor.isTrackingRef()) {
        return writeRefOrNull(buffer, fieldValue);
      }
      return serializeForNullable(
          fieldValue, buffer, descriptor.getTypeRef(), null, false, descriptor.isNullable());
    }
    return super.serializeField(fieldValue, buffer, descriptor);
  }

  @Override
  protected Expression serializeForNullable(
      Expression inputObject,
      Expression buffer,
      TypeRef<?> typeRef,
      Expression serializer,
      boolean generateNewMethod,
      boolean nullable) {
    if (inputObject instanceof Literal && ((Literal) inputObject).getValue() == null) {
      if (typeResolver(r -> r.needToWriteRef(typeRef))) {
        return writeRefOrNull(buffer, inputObject);
      }
      if (nullable) {
        return new Expression.Invoke(buffer, "writeByte", Literal.ofByte(Fory.NULL_FLAG));
      }
    }
    return super.serializeForNullable(
        inputObject, buffer, typeRef, serializer, generateNewMethod, nullable);
  }

  @Override
  protected Expression setFieldValue(Expression bean, Descriptor descriptor, Expression value) {
    if (descriptor.getField() == null) {
      return new StaticInvoke(ExceptionUtils.class, "ignore", value);
    }
    return super.setFieldValue(bean, descriptor, value);
  }

  private Expression buildReadAndSetFieldsExpression() {
    Reference buffer = new Reference(BUFFER_NAME, bufferTypeRef, false);
    Reference inputObject = new Reference(ROOT_OBJECT_NAME, OBJECT_TYPE, false);
    ListExpression expressions = new ListExpression();
    Expression bean = tryCastIfPublic(inputObject, beanType, ctx.newName(beanClass));
    expressions.add(bean);
    expressions.addAll(deserializePrimitives(bean, buffer, objectCodecOptimizer.primitiveGroups));
    int numGroups = getNumGroups(objectCodecOptimizer);
    deserializeReadGroup(
        objectCodecOptimizer.boxedReadGroups, numGroups, expressions, bean, buffer);
    deserializeReadGroup(
        objectCodecOptimizer.buildInReadGroups, numGroups, expressions, bean, buffer);
    for (Descriptor descriptor :
        objectCodecOptimizer.descriptorGrouper.getCollectionDescriptors()) {
      expressions.add(
          deserializeGroup(java.util.Collections.singletonList(descriptor), bean, buffer, false));
    }
    for (Descriptor descriptor : objectCodecOptimizer.descriptorGrouper.getMapDescriptors()) {
      expressions.add(
          deserializeGroup(java.util.Collections.singletonList(descriptor), bean, buffer, false));
    }
    deserializeReadGroup(
        objectCodecOptimizer.otherReadGroups, numGroups, expressions, bean, buffer);
    expressions.add(new Expression.Return(bean));
    return expressions;
  }

  @Override
  protected Expression buildComponentsArray() {
    return buildDefaultComponentsArray();
  }
}
