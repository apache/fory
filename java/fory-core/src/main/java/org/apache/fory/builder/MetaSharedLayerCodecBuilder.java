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
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.MetaSharedLayerSerializer;
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
 * @see MetaSharedLayerSerializer
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

  // Must be static to be shared across the whole process life.
  private static final Map<Long, Integer> idGenerator = new ConcurrentHashMap<>();

  @Override
  protected String codecSuffix() {
    // For every class def sent from different peer, if the class def are different, then
    // a new serializer needs being generated.
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
    // don't addImport(beanClass), because user class may name collide.
    ctx.extendsClasses(ctx.type(parentSerializerClass));
    ctx.reserveName(POJO_CLASS_TYPE_NAME);
    ctx.addField(ctx.type(Fory.class), FORY_NAME);
    String constructorCode =
        StringUtils.format(
            "super(${fory}, ${cls});\nthis.${fory} = ${fory};\n",
            "fory",
            FORY_NAME,
            "cls",
            POJO_CLASS_TYPE_NAME);
    ctx.clearExprState();
    Expression encodeExpr = buildEncodeExpression();
    String encodeCode = encodeExpr.genCode(ctx).code();
    encodeCode = ctx.optimizeMethodCode(encodeCode);
    ctx.clearExprState();
    Expression decodeExpr = buildReadAndSetFieldsExpression();
    String decodeCode = decodeExpr.genCode(ctx).code();
    decodeCode = ctx.optimizeMethodCode(decodeCode);
    ctx.overrideMethod(
        "writeFieldsOnly",
        encodeCode,
        void.class,
        MemoryBuffer.class,
        BUFFER_NAME,
        Object.class,
        ROOT_OBJECT_NAME);
    ctx.overrideMethod(
        "readAndSetFields",
        decodeCode,
        Object.class,
        MemoryBuffer.class,
        BUFFER_NAME,
        Object.class,
        ROOT_OBJECT_NAME);
    registerJITNotifyCallback();
    ctx.addConstructor(constructorCode, Fory.class, FORY_NAME, Class.class, POJO_CLASS_TYPE_NAME);
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
      // Field doesn't exist in current class (e.g., from serialPersistentFields).
      // Skip setting this field value but still consume the read value.
      return new StaticInvoke(ExceptionUtils.class, "ignore", value);
    }
    return super.setFieldValue(bean, descriptor, value);
  }

  // Note: Layer class meta is read by ObjectStreamSerializer before calling this serializer.
  // The generated read() method only reads field data, not the layer class meta.

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
    for (Descriptor d : objectCodecOptimizer.descriptorGrouper.getCollectionDescriptors()) {
      expressions.add(
          deserializeGroup(java.util.Collections.singletonList(d), bean, buffer, false));
    }
    for (Descriptor d : objectCodecOptimizer.descriptorGrouper.getMapDescriptors()) {
      expressions.add(
          deserializeGroup(java.util.Collections.singletonList(d), bean, buffer, false));
    }
    deserializeReadGroup(
        objectCodecOptimizer.otherReadGroups, numGroups, expressions, bean, buffer);
    expressions.add(new Expression.Return(bean));
    return expressions;
  }
}
