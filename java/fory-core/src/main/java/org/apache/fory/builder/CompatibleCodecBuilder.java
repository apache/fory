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

import static org.apache.fory.codegen.Expression.Invoke.inlineInvoke;
import static org.apache.fory.codegen.Expression.Reference.fieldRef;
import static org.apache.fory.codegen.ExpressionUtils.cast;
import static org.apache.fory.codegen.ExpressionUtils.eq;
import static org.apache.fory.codegen.ExpressionUtils.lessThan;
import static org.apache.fory.type.TypeUtils.OBJECT_ARRAY_TYPE;
import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_BOOLEAN_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_BYTE_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_INT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_LONG_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_VOID_TYPE;
import static org.apache.fory.type.TypeUtils.getRawType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.builder.Generated.GeneratedCompatibleSerializer;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Assign;
import org.apache.fory.codegen.Expression.AssignArrayElem;
import org.apache.fory.codegen.Expression.BitAnd;
import org.apache.fory.codegen.Expression.BitOr;
import org.apache.fory.codegen.Expression.BitShift;
import org.apache.fory.codegen.Expression.Cast;
import org.apache.fory.codegen.Expression.Comparator;
import org.apache.fory.codegen.Expression.If;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.codegen.Expression.ListExpression;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.Expression.Return;
import org.apache.fory.codegen.Expression.StaticInvoke;
import org.apache.fory.codegen.Expression.While;
import org.apache.fory.codegen.ExpressionOptimizer;
import org.apache.fory.codegen.ExpressionUtils;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.FieldResolver;
import org.apache.fory.resolver.FieldResolver.CollectionFieldInfo;
import org.apache.fory.resolver.FieldResolver.FieldInfo;
import org.apache.fory.resolver.FieldResolver.FieldTypes;
import org.apache.fory.resolver.FieldResolver.MapFieldInfo;
import org.apache.fory.serializer.CompatibleSerializer;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.function.SerializableSupplier;
import org.apache.fory.util.record.RecordUtils;

/** A jit-version of {@link CompatibleSerializer}. */
public class CompatibleCodecBuilder extends BaseObjectCodecBuilder {
  public static final String FIELD_RESOLVER_NAME = "fieldResolver";
  private final FieldResolver fieldResolver;
  private Map<String, Integer> recordReversedMapping;
  private final Reference fieldResolverRef;
  private final Literal endTagLiteral;
  private final Map<String, Expression> methodCache;

  public CompatibleCodecBuilder(
      TypeRef<?> beanType, Fory fory, FieldResolver fieldResolver, Class<?> superSerializerClass) {
    // if not resolveParent, there won't be necessary to implement
    // `CompatibleSerializerBase.readAndSetFields`.
    super(beanType, fory, superSerializerClass);
    this.fieldResolver = fieldResolver;
    if (isRecord) {
      recordReversedMapping = RecordUtils.buildFieldToComponentMapping(beanClass);
    }
    ctx.reserveName(FIELD_RESOLVER_NAME);
    endTagLiteral = new Literal(fieldResolver.getEndTag(), PRIMITIVE_LONG_TYPE);
    TypeRef<FieldResolver> fieldResolverTypeRef = TypeRef.of(FieldResolver.class);
    fieldResolverRef = fieldRef(FIELD_RESOLVER_NAME, fieldResolverTypeRef);
    Expression fieldResolverExpr =
        inlineInvoke(
            classResolverRef,
            "getFieldResolver",
            fieldResolverTypeRef,
            getClassExpr(getRawType(beanType)));
    ctx.addField(ctx.type(fieldResolverTypeRef), FIELD_RESOLVER_NAME, fieldResolverExpr);
    if (isRecord) {
      buildRecordComponentDefaultValues();
    }
    methodCache = new HashMap<>();
  }

  @Override
  protected String codecSuffix() {
    return "Compatible";
  }

  @Override
  protected void addCommonImports() {
    super.addCommonImports();
    ctx.addImport(GeneratedCompatibleSerializer.class);
  }

  @Override
  protected boolean isMonomorphic(Class<?> clz) {
    Preconditions.checkArgument(!fory.isCrossLanguage());
    return ReflectionUtils.isMonomorphic(clz);
  }

  private Descriptor createDescriptor(FieldInfo fieldInfo) {
    TypeRef<?> typeRef;
    Field field = fieldInfo.getField();
    if (fieldInfo instanceof MapFieldInfo) {
      MapFieldInfo mapFieldInfo = (MapFieldInfo) fieldInfo;
      // Remove nested generics such as `Map<Integer, Map<Integer, Collection<Integer>>>` to keep
      // consistent with
      // CompatibleSerializer.
      // TODO support nested collection/map generics.
      typeRef =
          TypeUtils.mapOf(
              mapFieldInfo.getType(), mapFieldInfo.getKeyType(), mapFieldInfo.getValueType());
    } else {
      typeRef = TypeRef.of(field.getGenericType());
    }
    Method readerMethod = null;
    if (isRecord) {
      try {
        readerMethod = field.getDeclaringClass().getDeclaredMethod(field.getName());
      } catch (NoSuchMethodException e) {
        // impossible
        Platform.throwException(e);
      }
    }
    return new Descriptor(field, typeRef, readerMethod, null);
  }

  private Expression invokeGenerated(
      CodegenContext ctx,
      SerializableSupplier<Expression> expressionsGenerator,
      String methodPrefix,
      long fieldId) {
    return methodCache.computeIfAbsent(
        methodPrefix + fieldId,
        id -> ExpressionOptimizer.invokeGenerated(ctx, expressionsGenerator, methodPrefix));
  }

  @Override
  public Expression buildEncodeExpression() {
    Reference inputObject = new Reference(ROOT_OBJECT_NAME, OBJECT_TYPE, false);
    Reference buffer = new Reference(BUFFER_NAME, bufferTypeRef, false);

    ListExpression expressions = new ListExpression();
    Expression bean = tryCastIfPublic(inputObject, beanType, ctx.newName(beanClass));
    expressions.add(bean);
    groupFields(fieldResolver.getEmbedTypes4Fields(), 9)
        .forEach(
            group -> {
              Expression invokeGeneratedWrite =
                  ExpressionOptimizer.invokeGenerated(
                      ctx,
                      () -> {
                        ListExpression groupExpressions = new ListExpression();
                        for (FieldInfo fieldInfo : group) {
                          groupExpressions.add(
                              new Invoke(
                                  buffer,
                                  "writeInt32",
                                  new Literal(
                                      (int) fieldInfo.getEncodedFieldInfo(), PRIMITIVE_INT_TYPE)));
                          groupExpressions.add(writeEmbedTypeFieldValue(bean, buffer, fieldInfo));
                        }
                        return groupExpressions;
                      },
                      "writeEmbedTypes4Fields");
              expressions.add(invokeGeneratedWrite);
            });
    groupFields(fieldResolver.getEmbedTypes9Fields(), 9)
        .forEach(
            group -> {
              Expression invokeGeneratedWrite =
                  ExpressionOptimizer.invokeGenerated(
                      ctx,
                      () -> {
                        ListExpression groupExpressions = new ListExpression();
                        for (FieldInfo fieldInfo : group) {
                          groupExpressions.add(
                              new Invoke(
                                  buffer,
                                  "writeInt64",
                                  new Literal(
                                      fieldInfo.getEncodedFieldInfo(), PRIMITIVE_LONG_TYPE)));
                          groupExpressions.add(writeEmbedTypeFieldValue(bean, buffer, fieldInfo));
                        }
                        return groupExpressions;
                      },
                      "writeEmbedTypes9Fields");
              expressions.add(invokeGeneratedWrite);
            });
    groupFields(fieldResolver.getEmbedTypesHashFields(), 9)
        .forEach(
            group -> {
              Expression invokeGeneratedWrite =
                  ExpressionOptimizer.invokeGenerated(
                      ctx,
                      () -> {
                        ListExpression groupExpressions = new ListExpression();
                        for (FieldInfo fieldInfo : group) {
                          groupExpressions.add(
                              new Invoke(
                                  buffer,
                                  "writeInt64",
                                  new Literal(
                                      fieldInfo.getEncodedFieldInfo(), PRIMITIVE_LONG_TYPE)));
                          groupExpressions.add(writeEmbedTypeFieldValue(bean, buffer, fieldInfo));
                        }
                        return groupExpressions;
                      },
                      "writeEmbedTypesHashFields");
              expressions.add(invokeGeneratedWrite);
            });
    groupFields(fieldResolver.getSeparateTypesHashFields(), 9)
        .forEach(
            group -> {
              Expression invokeGeneratedWrite =
                  ExpressionOptimizer.invokeGenerated(
                      ctx,
                      () -> {
                        ListExpression groupExpressions = new ListExpression();
                        for (FieldInfo fieldInfo : group) {
                          groupExpressions.add(
                              new Invoke(
                                  buffer,
                                  "writeInt64",
                                  new Literal(
                                      fieldInfo.getEncodedFieldInfo(), PRIMITIVE_LONG_TYPE)));
                          Descriptor descriptor = createDescriptor(fieldInfo);
                          walkPath.add(descriptor.getDeclaringClass() + descriptor.getName());
                          byte fieldType = fieldInfo.getFieldType();
                          Expression writeFieldAction =
                              invokeGenerated(
                                  ctx,
                                  () -> {
                                    Expression fieldValue = getFieldValue(bean, descriptor);
                                    ListExpression writeFieldValue =
                                        new ListExpression(
                                            new Invoke(
                                                buffer,
                                                "writeByte",
                                                new Literal(fieldType, PRIMITIVE_BYTE_TYPE)));
                                    if (fieldType == FieldTypes.OBJECT) {
                                      writeFieldValue.add(
                                          writeForNotNullNonFinalObject(
                                              fieldValue, buffer, descriptor.getTypeRef()));
                                    } else {
                                      if (fieldType == FieldTypes.COLLECTION_ELEMENT_FINAL) {
                                        CollectionFieldInfo collectionFieldInfo =
                                            (CollectionFieldInfo) fieldInfo;
                                        writeFieldValue.add(
                                            writeFinalClassInfo(
                                                buffer, collectionFieldInfo.getElementType()));
                                      } else if (fieldType == FieldTypes.MAP_KV_FINAL) {
                                        MapFieldInfo mapFieldInfo = (MapFieldInfo) fieldInfo;
                                        Expression keyClassInfo =
                                            getFinalClassInfo(mapFieldInfo.getKeyType());
                                        Expression valueClassInfo =
                                            getFinalClassInfo(mapFieldInfo.getValueType());
                                        writeFieldValue.add(
                                            keyClassInfo,
                                            valueClassInfo,
                                            writeFinalClassInfo(buffer, mapFieldInfo.getKeyType()),
                                            writeFinalClassInfo(
                                                buffer, mapFieldInfo.getValueType()));
                                      } else if (fieldType == FieldTypes.MAP_KEY_FINAL) {
                                        MapFieldInfo mapFieldInfo = (MapFieldInfo) fieldInfo;
                                        writeFieldValue.add(
                                            writeFinalClassInfo(buffer, mapFieldInfo.getKeyType()));
                                      } else {
                                        Preconditions.checkArgument(
                                            fieldType == FieldTypes.MAP_VALUE_FINAL, fieldInfo);
                                        MapFieldInfo mapFieldInfo = (MapFieldInfo) fieldInfo;
                                        writeFieldValue.add(
                                            writeFinalClassInfo(
                                                buffer, mapFieldInfo.getValueType()));
                                      }
                                      Class<?> clz = descriptor.getRawType();
                                      if (ReflectionUtils.isMonomorphic(clz)) {
                                        // serializeForNotNull won't write field type if it's final,
                                        // but the type is useful if peer doesn't have this field.
                                        writeFieldValue.add(writeFinalClassInfo(buffer, clz));
                                      }
                                      writeFieldValue.add(
                                          serializeForNotNull(
                                              fieldValue, buffer, descriptor.getTypeRef()));
                                    }
                                    return new If(
                                        ExpressionUtils.not(writeRefOrNull(buffer, fieldValue)),
                                        writeFieldValue);
                                  },
                                  "writeField",
                                  fieldInfo.getEncodedFieldInfo());
                          walkPath.removeLast();
                          groupExpressions.add(writeFieldAction);
                        }
                        return groupExpressions;
                      },
                      "writeSeparateTypesHashFields");
              expressions.add(invokeGeneratedWrite);
            });
    expressions.add(
        new Invoke(
            buffer, "writeInt64", new Literal(fieldResolver.getEndTag(), PRIMITIVE_LONG_TYPE)));
    return expressions;
  }

  @Override
  public Expression buildXlangEncodeExpression() {
    throw new IllegalStateException("unreachable");
  }

  private Expression writeEmbedTypeFieldValue(
      Expression bean, Expression buffer, FieldInfo fieldInfo) {
    Descriptor descriptor = createDescriptor(fieldInfo);
    walkPath.add(descriptor.getDeclaringClass() + descriptor.getName());
    boolean nullable = descriptor.isNullable();
    Expression fieldValue = getFieldValue(bean, descriptor);
    walkPath.removeLast();
    return serializeForNullable(fieldValue, buffer, descriptor.getTypeRef(), nullable);
  }

  @Override
  public Expression buildDecodeExpression() {
    if (isRecord) {
      return buildRecordDecodeExpression();
    }
    Reference buffer = new Reference(BUFFER_NAME, bufferTypeRef, false);
    ListExpression expressionBuilder = new ListExpression();
    Expression bean = newBean();
    Expression referenceObject = new Invoke(refResolverRef, "reference", PRIMITIVE_VOID_TYPE, bean);
    expressionBuilder.add(bean);
    expressionBuilder.add(referenceObject);

    if (!GeneratedCompatibleSerializer.class.isAssignableFrom(parentSerializerClass)) {
      expressionBuilder.add(readAndSetFields(buffer, bean));
    } else {
      // Use cast to make generated method signature match
      // `CompatibleSerializerBase.readAndSetFields`
      Expression target1 = new Cast(bean, OBJECT_TYPE, "bean", false, false);
      Expression target2 = new Cast(target1, bean.type(), "bean", false, false);
      ListExpression readAndSetFieldsExpr = readAndSetFields(buffer, target2);
      readAndSetFieldsExpr.add(new Return(target2));
      expressionBuilder.add(
          ExpressionOptimizer.invokeGenerated(
              ctx,
              new LinkedHashSet<>(Arrays.asList(buffer, target1)),
              readAndSetFieldsExpr,
              "public",
              "readAndSetFields",
              false));
    }
    expressionBuilder.add(new Return(bean));
    return expressionBuilder;
  }

  @Override
  public Expression buildXlangDecodeExpression() {
    throw new IllegalStateException("unreachable");
  }

  public Expression buildRecordDecodeExpression() {
    Reference buffer = new Reference(BUFFER_NAME, bufferTypeRef, false);
    StaticInvoke components =
        new StaticInvoke(
            Platform.class, "copyObjectArray", OBJECT_ARRAY_TYPE, recordComponentDefaultValues);
    ListExpression readAndSetFieldsExpr = new ListExpression();
    Expression partFieldInfo =
        new Invoke(buffer, readIntFunc(), "partFieldInfo", PRIMITIVE_LONG_TYPE);
    readAndSetFieldsExpr.add(partFieldInfo);
    readEmbedTypes4Fields(buffer, readAndSetFieldsExpr, components, partFieldInfo);
    BitOr newPartFieldInfo =
        new BitOr(
            new BitShift("<<", new Invoke(buffer, readIntFunc(), PRIMITIVE_LONG_TYPE), 32),
            new BitAnd(partFieldInfo, new Literal(0x00000000ffffffffL, PRIMITIVE_LONG_TYPE)));
    readAndSetFieldsExpr.add(new Assign(partFieldInfo, newPartFieldInfo));
    readEmbedTypes9Fields(buffer, readAndSetFieldsExpr, components, partFieldInfo);
    readEmbedTypesHashFields(buffer, readAndSetFieldsExpr, components, partFieldInfo);
    readSeparateTypesHashFields(buffer, readAndSetFieldsExpr, components, partFieldInfo);
    readAndSetFieldsExpr.add(new Return(components));
    Expression readActions =
        ExpressionOptimizer.invokeGenerated(
            ctx,
            new LinkedHashSet<>(Arrays.asList(buffer, components)),
            readAndSetFieldsExpr.add(components),
            "private",
            "readFields",
            false);
    StaticInvoke record =
        new StaticInvoke(
            RecordUtils.class,
            "invokeRecordCtrHandle",
            OBJECT_TYPE,
            getRecordCtrHandle(),
            components);
    return new ListExpression(buffer, components, readActions, new Return(record));
  }

  @Override
  protected Expression setFieldValue(Expression bean, Descriptor d, Expression value) {
    if (isRecord) {
      int index = recordReversedMapping.get(d.getName());
      return new AssignArrayElem(bean, value, Literal.ofInt(index));
    }
    return super.setFieldValue(bean, d, value);
  }

  private ListExpression readAndSetFields(Reference buffer, Expression bean) {
    ListExpression readAndSetFieldsExpr = new ListExpression();
    Expression partFieldInfo =
        new Invoke(buffer, readIntFunc(), "partFieldInfo", PRIMITIVE_LONG_TYPE);
    readAndSetFieldsExpr.add(partFieldInfo);
    readEmbedTypes4Fields(buffer, readAndSetFieldsExpr, bean, partFieldInfo);
    BitOr newPartFieldInfo =
        new BitOr(
            new BitShift("<<", new Invoke(buffer, readIntFunc(), PRIMITIVE_LONG_TYPE), 32),
            new BitAnd(partFieldInfo, new Literal(0x00000000ffffffffL, PRIMITIVE_LONG_TYPE)));
    readAndSetFieldsExpr.add(new Assign(partFieldInfo, newPartFieldInfo));
    readEmbedTypes9Fields(buffer, readAndSetFieldsExpr, bean, partFieldInfo);
    readEmbedTypesHashFields(buffer, readAndSetFieldsExpr, bean, partFieldInfo);
    readSeparateTypesHashFields(buffer, readAndSetFieldsExpr, bean, partFieldInfo);
    return readAndSetFieldsExpr;
  }

  private void readEmbedTypes4Fields(
      Reference buffer,
      ListExpression expressionBuilder,
      Expression bean,
      Expression partFieldInfo) {
    FieldInfo[] embedTypes4Fields = fieldResolver.getEmbedTypes4Fields();
    if (embedTypes4Fields.length > 0) {
      long minFieldInfo = embedTypes4Fields[0].getEncodedFieldInfo();
      expressionBuilder.add(skipDataBy4Until(bean, buffer, partFieldInfo, minFieldInfo, false));
      groupFields(embedTypes4Fields, 3)
          .forEach(
              group -> {
                Expression invokeGeneratedRead =
                    ExpressionOptimizer.invokeGenerated(
                        ctx,
                        () -> {
                          ListExpression groupExpressions = new ListExpression();
                          for (FieldInfo fieldInfo : group) {
                            long encodedFieldInfo = fieldInfo.getEncodedFieldInfo();
                            Descriptor descriptor = createDescriptor(fieldInfo);
                            walkPath.add(descriptor.getDeclaringClass() + descriptor.getName());
                            Expression readField =
                                readEmbedTypes4(bean, buffer, descriptor, partFieldInfo);
                            Expression tryReadField =
                                new ListExpression(
                                    skipDataBy4Until(
                                        bean, buffer, partFieldInfo, encodedFieldInfo, true),
                                    new If(
                                        eq(
                                            partFieldInfo,
                                            new Literal(encodedFieldInfo, PRIMITIVE_LONG_TYPE)),
                                        readEmbedTypes4(bean, buffer, descriptor, partFieldInfo)));
                            groupExpressions.add(
                                new If(
                                    eq(
                                        partFieldInfo,
                                        new Literal(encodedFieldInfo, PRIMITIVE_LONG_TYPE)),
                                    readField,
                                    tryReadField,
                                    false,
                                    PRIMITIVE_VOID_TYPE));
                            walkPath.removeLast();
                          }
                          groupExpressions.add(new Return(partFieldInfo));
                          return groupExpressions;
                        },
                        "readEmbedTypes4Fields",
                        true);
                expressionBuilder.add(
                    new Assign(partFieldInfo, invokeGeneratedRead),
                    new If(eq(partFieldInfo, endTagLiteral), new Return(bean)));
              });
    }
    expressionBuilder.add(skipField4End(bean, buffer, partFieldInfo));
  }

  private Expression readEmbedTypes4(
      Expression bean, Expression buffer, Descriptor descriptor, Expression partFieldInfo) {
    boolean nullable = descriptor.isNullable();
    Expression deserializeAction =
        deserializeForNullable(
            buffer,
            descriptor.getTypeRef(),
            expr -> setFieldValue(bean, descriptor, tryInlineCast(expr, descriptor.getTypeRef())),
            nullable);
    return new ListExpression(
        deserializeAction,
        new Assign(partFieldInfo, inlineInvoke(buffer, readIntFunc(), PRIMITIVE_LONG_TYPE)));
  }

  /**
   * Skip data whose field info is encoded as 4-bytes until specified field matched.
   *
   * <p>This is JIT-version of code:
   *
   * <pre>{@code
   * while ((partFieldInfo & 0b11) == FieldResolver.EMBED_TYPES_4_FLAG
   *         && partFieldInfo < targetFieldInfo) {
   *   if (fieldResolver.skipDataBy4(buffer, (int) partFieldInfo)) {
   *     return bean;
   *   }
   *   partFieldInfo = buffer.readInt32();
   * }
   * }</pre>
   */
  private Expression skipDataBy4Until(
      Expression bean,
      Expression buffer,
      Expression partFieldInfo,
      long targetFieldInfo,
      boolean returnEndTag) {
    Literal targetFieldInfoExpr = new Literal(targetFieldInfo, PRIMITIVE_LONG_TYPE);
    Expression.LogicalAnd predicate =
        new Expression.LogicalAnd(
            isEmbedType(partFieldInfo, 0b11, FieldResolver.EMBED_TYPES_4_FLAG),
            lessThan(partFieldInfo, targetFieldInfoExpr));
    return new While(
        predicate,
        () ->
            new ListExpression(
                new If(
                    eq(
                        inlineInvoke(
                            fieldResolverRef,
                            "skipDataBy4",
                            PRIMITIVE_BOOLEAN_TYPE,
                            buffer,
                            cast(partFieldInfo, PRIMITIVE_INT_TYPE)),
                        endTagLiteral),
                    returnEndTag ? new Return(endTagLiteral) : new Return(bean)),
                new Assign(
                    partFieldInfo, inlineInvoke(buffer, readIntFunc(), PRIMITIVE_LONG_TYPE))));
  }

  /**
   * Skip tailing fields with 4-byte field info.
   *
   * <p>This is JIT-version of code:
   *
   * <pre>{@code
   * while ((partFieldInfo & 0b11) == FieldResolver.EMBED_TYPES_4_FLAG) {
   *   if (fieldResolver.skipDataBy4(buffer, (int) partFieldInfo)) {
   *     return bean;
   *   }
   *   partFieldInfo = buffer.readInt32();
   * }
   * }</pre>
   */
  private Expression skipField4End(Expression bean, Expression buffer, Expression partFieldInfo) {
    return new While(
        isEmbedType(partFieldInfo, 0b11, FieldResolver.EMBED_TYPES_4_FLAG),
        () ->
            new ListExpression(
                new If(
                    eq(
                        inlineInvoke(
                            fieldResolverRef,
                            "skipDataBy4",
                            PRIMITIVE_BOOLEAN_TYPE,
                            buffer,
                            cast(partFieldInfo, PRIMITIVE_INT_TYPE)),
                        endTagLiteral),
                    new Return(bean)),
                new Assign(
                    partFieldInfo, inlineInvoke(buffer, readIntFunc(), PRIMITIVE_LONG_TYPE))));
  }

  private void readEmbedTypes9Fields(
      Expression buffer, ListExpression expressions, Expression bean, Expression partFieldInfo) {
    FieldInfo[] embedTypes9Fields = fieldResolver.getEmbedTypes9Fields();
    if (embedTypes9Fields.length > 0) {
      long minFieldInfo = embedTypes9Fields[0].getEncodedFieldInfo();
      expressions.add(
          skipDataBy8Until(
              bean,
              buffer,
              partFieldInfo,
              minFieldInfo,
              0b111,
              FieldResolver.EMBED_TYPES_9_FLAG,
              false));
      groupFields(embedTypes9Fields, 3)
          .forEach(
              group -> {
                Expression invokeGeneratedRead =
                    ExpressionOptimizer.invokeGenerated(
                        ctx,
                        () -> {
                          ListExpression groupExpressions = new ListExpression();
                          for (FieldInfo fieldInfo : group) {
                            groupExpressions.add(
                                processEmbedTypes8Field(
                                    buffer,
                                    bean,
                                    partFieldInfo,
                                    fieldInfo.getEncodedFieldInfo(),
                                    FieldResolver.EMBED_TYPES_9_FLAG,
                                    fieldInfo));
                          }
                          groupExpressions.add(new Return(partFieldInfo));
                          return groupExpressions;
                        },
                        "readEmbedTypes9Fields",
                        true);
                expressions.add(
                    new Assign(partFieldInfo, invokeGeneratedRead),
                    new If(eq(partFieldInfo, endTagLiteral), new Return(bean)));
              });
    }
    expressions.add(
        skipField8End(bean, buffer, partFieldInfo, 0b111, FieldResolver.EMBED_TYPES_9_FLAG));
  }

  private void readEmbedTypesHashFields(
      Expression buffer, ListExpression expressions, Expression bean, Expression partFieldInfo) {
    FieldInfo[] embedTypesHashFields = fieldResolver.getEmbedTypesHashFields();
    if (embedTypesHashFields.length > 0) {
      long minFieldInfo = embedTypesHashFields[0].getEncodedFieldInfo();
      expressions.add(
          skipDataBy8Until(
              bean,
              buffer,
              partFieldInfo,
              minFieldInfo,
              0b111,
              FieldResolver.EMBED_TYPES_HASH_FLAG,
              false));
      groupFields(embedTypesHashFields, 3)
          .forEach(
              group -> {
                Expression invokeGeneratedRead =
                    ExpressionOptimizer.invokeGenerated(
                        ctx,
                        () -> {
                          ListExpression groupExpressions = new ListExpression();
                          for (FieldInfo fieldInfo : group) {
                            groupExpressions.add(
                                processEmbedTypes8Field(
                                    buffer,
                                    bean,
                                    partFieldInfo,
                                    fieldInfo.getEncodedFieldInfo(),
                                    FieldResolver.EMBED_TYPES_HASH_FLAG,
                                    fieldInfo));
                          }
                          groupExpressions.add(new Return(partFieldInfo));
                          return groupExpressions;
                        },
                        "readEmbedTypesHashFields",
                        true);
                expressions.add(
                    new Assign(partFieldInfo, invokeGeneratedRead),
                    new If(eq(partFieldInfo, endTagLiteral), new Return(bean)));
              });
    }
    expressions.add(
        skipField8End(bean, buffer, partFieldInfo, 0b111, FieldResolver.EMBED_TYPES_HASH_FLAG));
  }

  private Expression processEmbedTypes8Field(
      Expression buffer,
      Expression bean,
      Expression partFieldInfo,
      long targetFieldInfo,
      byte flagValue,
      FieldInfo fieldInfo) {
    long encodedFieldInfo = fieldInfo.getEncodedFieldInfo();
    Descriptor descriptor = createDescriptor(fieldInfo);
    walkPath.add(descriptor.getDeclaringClass() + descriptor.getName());
    Expression readField = readEmbedTypes8Field(bean, buffer, descriptor, partFieldInfo);
    Expression tryReadField =
        new ListExpression(
            skipDataBy8Until(bean, buffer, partFieldInfo, targetFieldInfo, 0b111, flagValue, true),
            new If(
                eq(partFieldInfo, new Literal(encodedFieldInfo, PRIMITIVE_LONG_TYPE)),
                readEmbedTypes8Field(bean, buffer, descriptor, partFieldInfo)));
    walkPath.removeLast();
    return new If(
        eq(partFieldInfo, new Literal(encodedFieldInfo, PRIMITIVE_LONG_TYPE)),
        readField,
        tryReadField);
  }

  private Expression readEmbedTypes8Field(
      Expression bean, Expression buffer, Descriptor descriptor, Expression partFieldInfo) {
    boolean nullable = descriptor.isNullable();
    Expression deserializeAction =
        deserializeForNullable(
            buffer,
            descriptor.getTypeRef(),
            expr -> setFieldValue(bean, descriptor, tryInlineCast(expr, descriptor.getTypeRef())),
            nullable);
    return new ListExpression(
        deserializeAction,
        new Assign(partFieldInfo, inlineInvoke(buffer, readLongFunc(), PRIMITIVE_LONG_TYPE)));
  }

  private void readSeparateTypesHashFields(
      Expression buffer,
      ListExpression expressionBuilder,
      Expression bean,
      Expression partFieldInfo) {
    FieldInfo[] separateTypesHashFields = fieldResolver.getSeparateTypesHashFields();
    if (separateTypesHashFields.length > 0) {
      long minFieldInfo = separateTypesHashFields[0].getEncodedFieldInfo();
      expressionBuilder.add(
          skipDataBy8Until(
              bean,
              buffer,
              partFieldInfo,
              minFieldInfo,
              0b11,
              FieldResolver.SEPARATE_TYPES_HASH_FLAG,
              false));
      groupFields(separateTypesHashFields, 3)
          .forEach(
              group -> {
                Expression invokeGeneratedRead =
                    ExpressionOptimizer.invokeGenerated(
                        ctx,
                        () -> {
                          ListExpression groupExpressions = new ListExpression();
                          for (FieldInfo fieldInfo : group) {
                            long encodedFieldInfo = fieldInfo.getEncodedFieldInfo();
                            Descriptor descriptor = createDescriptor(fieldInfo);
                            walkPath.add(descriptor.getDeclaringClass() + descriptor.getName());
                            Expression readField =
                                readObjectField(fieldInfo, bean, buffer, descriptor, partFieldInfo);
                            Expression tryReadField =
                                new ListExpression(
                                    skipDataBy8Until(
                                        bean,
                                        buffer,
                                        partFieldInfo,
                                        encodedFieldInfo,
                                        0b11,
                                        FieldResolver.SEPARATE_TYPES_HASH_FLAG,
                                        true),
                                    new If(
                                        eq(
                                            partFieldInfo,
                                            new Literal(encodedFieldInfo, PRIMITIVE_LONG_TYPE)),
                                        readObjectField(
                                            fieldInfo, bean, buffer, descriptor, partFieldInfo)));
                            walkPath.removeLast();
                            groupExpressions.add(
                                new If(
                                    eq(
                                        partFieldInfo,
                                        new Literal(encodedFieldInfo, PRIMITIVE_LONG_TYPE)),
                                    readField,
                                    tryReadField));
                          }
                          groupExpressions.add(new Return(partFieldInfo));
                          return groupExpressions;
                        },
                        "readSeparateTypesHashFields",
                        true);
                expressionBuilder.add(
                    new Assign(partFieldInfo, invokeGeneratedRead),
                    new If(eq(partFieldInfo, endTagLiteral), new Return(bean)));
              });
    }
    expressionBuilder.add(new Invoke(fieldResolverRef, "skipEndFields", buffer, partFieldInfo));
  }

  private Expression readObjectField(
      FieldInfo fieldInfo,
      Expression bean,
      Expression buffer,
      Descriptor descriptor,
      Expression partFieldInfo) {
    Expression readAction =
        invokeGenerated(
            ctx,
            () -> {
              TypeRef<?> typeRef = descriptor.getTypeRef();
              Expression refId = tryPreserveRefId(buffer);
              // indicates that the object is first read.
              Expression needDeserialize =
                  ExpressionUtils.egt(
                      refId, new Literal(Fory.NOT_NULL_VALUE_FLAG, PRIMITIVE_BYTE_TYPE));
              byte type = fieldInfo.getFieldType();
              // check `FieldTypes` byte value.
              Expression expectType = new Literal(type, PRIMITIVE_BYTE_TYPE);
              ListExpression deserializedValue =
                  new ListExpression(
                      new Invoke(
                          fieldResolverRef,
                          "checkFieldType",
                          inlineInvoke(buffer, "readByte", PRIMITIVE_BYTE_TYPE),
                          expectType));
              if (type == FieldTypes.OBJECT) {
                deserializedValue.add(readForNotNullNonFinal(buffer, typeRef, null));
              } else {
                if (type == FieldTypes.COLLECTION_ELEMENT_FINAL) {
                  deserializedValue.add(
                      skipFinalClassInfo(
                          ((CollectionFieldInfo) fieldInfo).getElementType(), buffer));
                } else if (type == FieldTypes.MAP_KV_FINAL) {
                  deserializedValue.add(
                      skipFinalClassInfo(((MapFieldInfo) fieldInfo).getKeyType(), buffer));
                  deserializedValue.add(
                      skipFinalClassInfo(((MapFieldInfo) fieldInfo).getValueType(), buffer));
                } else if (type == FieldTypes.MAP_KEY_FINAL) {
                  deserializedValue.add(
                      skipFinalClassInfo(((MapFieldInfo) fieldInfo).getKeyType(), buffer));
                } else {
                  Preconditions.checkArgument(type == FieldTypes.MAP_VALUE_FINAL, fieldInfo);
                  deserializedValue.add(
                      skipFinalClassInfo(((MapFieldInfo) fieldInfo).getValueType(), buffer));
                }
                Class<?> clz = getRawType(typeRef);
                if (ReflectionUtils.isMonomorphic(clz)) {
                  // deserializeForNotNull won't read field type if it's final
                  deserializedValue.add(skipFinalClassInfo(clz, buffer));
                }
                deserializedValue.add(deserializeForNotNull(buffer, typeRef, null));
              }
              Expression setReadObject =
                  new Invoke(refResolverRef, "setReadObject", refId, deserializedValue);
              // use false to ignore null
              return new If(
                  needDeserialize,
                  new ListExpression(
                      refId,
                      deserializedValue,
                      setReadObject,
                      setFieldValue(bean, descriptor, tryInlineCast(deserializedValue, typeRef))),
                  setFieldValue(
                      bean,
                      descriptor,
                      tryInlineCast(
                          new Invoke(refResolverRef, "getReadObject", OBJECT_TYPE, false),
                          typeRef)),
                  false,
                  PRIMITIVE_VOID_TYPE);
            },
            "readField",
            fieldInfo.getEncodedFieldInfo());
    return new ListExpression(
        new Expression.ForceEvaluate(readAction),
        new Assign(partFieldInfo, inlineInvoke(buffer, readLongFunc(), PRIMITIVE_LONG_TYPE)));
  }

  protected Expression getFinalClassInfo(Class<?> cls) {
    Preconditions.checkArgument(ReflectionUtils.isMonomorphic(cls));
    Tuple2<Reference, Boolean> classInfoRef = addClassInfoField(cls);
    Preconditions.checkArgument(!classInfoRef.f1);
    return classInfoRef.f0;
  }

  protected Expression writeFinalClassInfo(Expression buffer, Class<?> cls) {
    Preconditions.checkArgument(ReflectionUtils.isMonomorphic(cls));
    ClassInfo classInfo = fory(f -> f.getClassResolver().getClassInfo(cls, false));
    if (classInfo != null && classInfo.getClassId() != ClassResolver.NO_CLASS_ID) {
      return fory(f -> f.getClassResolver().writeClassExpr(buffer, classInfo.getClassId()));
    }
    Expression classInfoExpr = getFinalClassInfo(cls);
    return new Invoke(classResolverRef, "writeClassInfo", buffer, classInfoExpr);
  }

  protected Expression skipFinalClassInfo(Class<?> cls, Expression buffer) {
    Preconditions.checkArgument(ReflectionUtils.isMonomorphic(cls));
    ClassInfo classInfo = fory(f -> f.getClassResolver().getClassInfo(cls, false));
    if (classInfo != null && classInfo.getClassId() != ClassResolver.NO_CLASS_ID) {
      return fory(f -> f.getClassResolver().skipRegisteredClassExpr(buffer));
    }
    // read `ClassInfo` is not used, set `inlineReadClassInfo` false,
    // to avoid read doesn't happen in generated code.
    return readClassInfo(cls, buffer, false);
  }

  /**
   * Skip data whose field info is encoded as 8-bytes until specified field matched.
   *
   * <p>This is JIT-version of code:
   *
   * <pre>{@code
   * while ((partFieldInfo & flagBits) == flagValue
   *   && partFieldInfo < targetFieldInfo) {
   *   if (fieldResolver.skipDataBy8(buffer, partFieldInfo)) {
   *     return bean;
   *   }
   *   partFieldInfo = buffer.readInt64();
   * }
   * }</pre>
   */
  private Expression skipDataBy8Until(
      Expression bean,
      Expression buffer,
      Expression partFieldInfo,
      long targetFieldInfo,
      int flagBits,
      byte flagValue,
      boolean returnEndTag) {
    Literal targetFieldInfoExpr = new Literal(targetFieldInfo, PRIMITIVE_LONG_TYPE);
    Expression.LogicalAnd predicate =
        new Expression.LogicalAnd(
            isEmbedType(partFieldInfo, flagBits, flagValue),
            lessThan(partFieldInfo, targetFieldInfoExpr));
    return new While(
        predicate,
        () ->
            new ListExpression(
                new If(
                    eq(
                        inlineInvoke(
                            fieldResolverRef,
                            "skipDataBy8",
                            PRIMITIVE_BOOLEAN_TYPE,
                            buffer,
                            partFieldInfo),
                        endTagLiteral),
                    returnEndTag ? new Return(endTagLiteral) : new Return(bean)),
                new Assign(
                    partFieldInfo, inlineInvoke(buffer, readLongFunc(), PRIMITIVE_LONG_TYPE))));
  }

  private Expression skipField8End(
      Expression bean, Expression buffer, Expression partFieldInfo, int flagBits, byte flagValue) {
    return new While(
        isEmbedType(partFieldInfo, flagBits, flagValue),
        () ->
            new ListExpression(
                new If(
                    eq(
                        inlineInvoke(
                            fieldResolverRef,
                            "skipDataBy8",
                            PRIMITIVE_BOOLEAN_TYPE,
                            buffer,
                            partFieldInfo),
                        endTagLiteral),
                    new Return(bean)),
                new Assign(
                    partFieldInfo, inlineInvoke(buffer, readLongFunc(), PRIMITIVE_LONG_TYPE))));
  }

  private Comparator isEmbedType(Expression partFieldInfo, int flagBits, byte flagValue) {
    return eq(
        new BitAnd(partFieldInfo, new Literal(flagBits, PRIMITIVE_LONG_TYPE)),
        new Literal(flagValue, PRIMITIVE_BYTE_TYPE));
  }

  private static List<List<FieldInfo>> groupFields(FieldInfo[] fieldsInfo, int groupSize) {
    List<List<FieldInfo>> groups = new ArrayList<>();
    List<FieldInfo> group = new ArrayList<>();
    for (FieldInfo fieldInfo : fieldsInfo) {
      if (group.size() == groupSize) {
        groups.add(group);
        group = new ArrayList<>();
      }
      group.add(fieldInfo);
    }
    if (!group.isEmpty()) {
      groups.add(group);
    }
    return groups;
  }
}
