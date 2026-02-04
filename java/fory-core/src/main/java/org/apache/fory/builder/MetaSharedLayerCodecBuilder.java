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

import static org.apache.fory.builder.Generated.GeneratedMetaSharedLayerSerializer.SERIALIZER_FIELD_NAME;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.Fory;
import org.apache.fory.builder.Generated.GeneratedMetaSharedLayerSerializer;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.StaticInvoke;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.CodegenSerializer;
import org.apache.fory.serializer.MetaSharedLayerSerializer;
import org.apache.fory.serializer.MetaSharedLayerSerializerBase;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.GraalvmSupport;
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
    Collection<Descriptor> descriptors = layerTypeDef.getDescriptors(typeResolver, beanClass);
    DescriptorGrouper grouper = typeResolver(r -> r.createDescriptorGrouper(descriptors, false));
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
            MetaSharedLayerCodecBuilder.class.getName(),
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
    ctx.addImport(GeneratedMetaSharedLayerSerializer.class);
  }

  // Invoked by JIT.
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static MetaSharedLayerSerializerBase setCodegenSerializer(
      Fory fory, Class<?> cls, GeneratedMetaSharedLayerSerializer s) {
    if (GraalvmSupport.isGraalRuntime()) {
      return (MetaSharedLayerSerializerBase) typeResolver(fory, r -> r.getSerializer(s.getType()));
    }
    // This method hold jit lock, so create jit serializer async to avoid block serialization.
    // Use MetaSharedLayerSerializer as fallback since it's compatible with
    // MetaSharedLayerSerializerBase
    Class serializerClass =
        fory.getJITContext()
            .registerSerializerJITCallback(
                () -> MetaSharedLayerSerializer.class,
                () -> CodegenSerializer.loadCodegenSerializer(fory, s.getType()),
                c ->
                    s.serializer =
                        (MetaSharedLayerSerializerBase)
                            Serializers.newSerializer(fory, s.getType(), c));
    return (MetaSharedLayerSerializerBase) Serializers.newSerializer(fory, cls, serializerClass);
  }

  @Override
  public Expression buildEncodeExpression() {
    throw new IllegalStateException("unreachable");
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

  @Override
  protected Expression buildComponentsArray() {
    return buildDefaultComponentsArray();
  }
}
