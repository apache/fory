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

import static org.apache.fory.codegen.CodeGenerator.getPackage;
import static org.apache.fory.codegen.Expression.Invoke.inlineInvoke;
import static org.apache.fory.codegen.Expression.Literal.ofInt;
import static org.apache.fory.codegen.Expression.Literal.ofString;
import static org.apache.fory.codegen.Expression.Reference.fieldRef;
import static org.apache.fory.codegen.ExpressionOptimizer.invokeGenerated;
import static org.apache.fory.codegen.ExpressionUtils.add;
import static org.apache.fory.codegen.ExpressionUtils.bitand;
import static org.apache.fory.codegen.ExpressionUtils.bitor;
import static org.apache.fory.codegen.ExpressionUtils.cast;
import static org.apache.fory.codegen.ExpressionUtils.eq;
import static org.apache.fory.codegen.ExpressionUtils.eqNull;
import static org.apache.fory.codegen.ExpressionUtils.gt;
import static org.apache.fory.codegen.ExpressionUtils.inline;
import static org.apache.fory.codegen.ExpressionUtils.invoke;
import static org.apache.fory.codegen.ExpressionUtils.invokeInline;
import static org.apache.fory.codegen.ExpressionUtils.list;
import static org.apache.fory.codegen.ExpressionUtils.neq;
import static org.apache.fory.codegen.ExpressionUtils.neqNull;
import static org.apache.fory.codegen.ExpressionUtils.not;
import static org.apache.fory.codegen.ExpressionUtils.nullValue;
import static org.apache.fory.codegen.ExpressionUtils.ofEnum;
import static org.apache.fory.codegen.ExpressionUtils.or;
import static org.apache.fory.codegen.ExpressionUtils.shift;
import static org.apache.fory.codegen.ExpressionUtils.subtract;
import static org.apache.fory.codegen.ExpressionUtils.uninline;
import static org.apache.fory.collection.Collections.ofHashSet;
import static org.apache.fory.serializer.CodegenSerializer.LazyInitBeanSerializer;
import static org.apache.fory.serializer.collection.MapFlags.KEY_DECL_TYPE;
import static org.apache.fory.serializer.collection.MapFlags.TRACKING_KEY_REF;
import static org.apache.fory.serializer.collection.MapFlags.TRACKING_VALUE_REF;
import static org.apache.fory.serializer.collection.MapFlags.VALUE_DECL_TYPE;
import static org.apache.fory.serializer.collection.MapLikeSerializer.MAX_CHUNK_SIZE;
import static org.apache.fory.type.TypeUtils.BOOLEAN_TYPE;
import static org.apache.fory.type.TypeUtils.BYTE_TYPE;
import static org.apache.fory.type.TypeUtils.CHAR_TYPE;
import static org.apache.fory.type.TypeUtils.CLASS_TYPE;
import static org.apache.fory.type.TypeUtils.COLLECTION_TYPE;
import static org.apache.fory.type.TypeUtils.DOUBLE_TYPE;
import static org.apache.fory.type.TypeUtils.FLOAT_TYPE;
import static org.apache.fory.type.TypeUtils.INT_TYPE;
import static org.apache.fory.type.TypeUtils.ITERATOR_TYPE;
import static org.apache.fory.type.TypeUtils.LIST_TYPE;
import static org.apache.fory.type.TypeUtils.LONG_TYPE;
import static org.apache.fory.type.TypeUtils.MAP_ENTRY_TYPE;
import static org.apache.fory.type.TypeUtils.MAP_TYPE;
import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_BOOLEAN_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_BYTE_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_INT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_LONG_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_VOID_TYPE;
import static org.apache.fory.type.TypeUtils.SET_TYPE;
import static org.apache.fory.type.TypeUtils.SHORT_TYPE;
import static org.apache.fory.type.TypeUtils.STRING_TYPE;
import static org.apache.fory.type.TypeUtils.getRawType;
import static org.apache.fory.type.TypeUtils.isBoxed;
import static org.apache.fory.type.TypeUtils.isPrimitive;
import static org.apache.fory.util.Preconditions.checkArgument;

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.fory.Fory;
import org.apache.fory.codegen.Code;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Assign;
import org.apache.fory.codegen.Expression.BitAnd;
import org.apache.fory.codegen.Expression.Break;
import org.apache.fory.codegen.Expression.Cast;
import org.apache.fory.codegen.Expression.ForEach;
import org.apache.fory.codegen.Expression.ForLoop;
import org.apache.fory.codegen.Expression.If;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.codegen.Expression.ListExpression;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.Expression.Return;
import org.apache.fory.codegen.Expression.Variable;
import org.apache.fory.codegen.Expression.While;
import org.apache.fory.codegen.ExpressionUtils;
import org.apache.fory.codegen.ExpressionVisitor.ExprHolder;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.DeferedLazySerializer.DeferredLazyObjectSerializer;
import org.apache.fory.serializer.EnumSerializer;
import org.apache.fory.serializer.FinalFieldReplaceResolveSerializer;
import org.apache.fory.serializer.MetaSharedSerializer;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.PrimitiveSerializers.LongSerializer;
import org.apache.fory.serializer.ReplaceResolveSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.StringSerializer;
import org.apache.fory.serializer.collection.CollectionFlags;
import org.apache.fory.serializer.collection.CollectionLikeSerializer;
import org.apache.fory.serializer.collection.MapLikeSerializer;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DispatchId;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;

/**
 * Generate sequential read/write code for java serialization to speed up performance. It also
 * reduces space overhead introduced by aligning. Codegen only for time-consuming field, others
 * delegate to fory.
 */
@SuppressWarnings("unchecked")
public abstract class BaseObjectCodecBuilder extends CodecBuilder {
  public static final String BUFFER_NAME = "_f_buffer";
  public static final String REF_RESOLVER_NAME = "_f_refResolver";
  public static final String TYPE_RESOLVER_NAME = "_f_typeResolver";
  public static final String POJO_CLASS_TYPE_NAME = "_f_classType";
  public static final String STRING_SERIALIZER_NAME = "_f_strSerializer";
  private static final TypeRef<?> STRING_SERIALIZER_TYPE_TOKEN = TypeRef.of(StringSerializer.class);
  private static final TypeRef<?> SERIALIZER_TYPE = TypeRef.of(Serializer.class);
  private static final TypeRef<?> COLLECTION_SERIALIZER_TYPE =
      TypeRef.of(CollectionLikeSerializer.class);
  private static final TypeRef<?> MAP_SERIALIZER_TYPE = TypeRef.of(MapLikeSerializer.class);
  private static final TypeRef<?> GENERIC_TYPE = TypeRef.of(GenericType.class);
  private static final TypeRef<?> FINAL_FIELD_SERIALIZER_TYPE =
      TypeRef.of(FinalFieldReplaceResolveSerializer.class);

  protected final Fory fory;
  protected final Reference refResolverRef;
  protected final Reference typeResolverRef;
  protected final TypeResolver typeResolver;
  protected final Reference stringSerializerRef;
  private final Map<Class<?>, Reference> serializerMap = new HashMap<>();
  private final Map<String, Object> sharedFieldMap = new HashMap<>();
  protected final Class<?> parentSerializerClass;
  private final Map<String, Expression> jitCallbackUpdateFields;
  protected LinkedList<String> walkPath = new LinkedList<>();
  protected final String writeMethodName;
  protected final String readMethodName;
  private final Map<Descriptor, Integer> descriptorDispatchId;

  public BaseObjectCodecBuilder(TypeRef<?> beanType, Fory fory, Class<?> parentSerializerClass) {
    super(new CodegenContext(), beanType);
    this.fory = fory;
    typeResolver = fory.getTypeResolver();
    this.parentSerializerClass = parentSerializerClass;
    writeMethodName = "write";
    readMethodName = "read";
    addCommonImports();
    ctx.reserveName(REF_RESOLVER_NAME);
    ctx.reserveName(TYPE_RESOLVER_NAME);
    // use concrete type to avoid virtual methods call in generated code
    TypeRef<?> refResolverTypeRef = TypeRef.of(fory.getRefResolver().getClass());
    refResolverRef = fieldRef(REF_RESOLVER_NAME, refResolverTypeRef);
    Expression refResolverExpr =
        new Invoke(foryRef, "getRefResolver", TypeRef.of(RefResolver.class));
    ctx.addField(
        ctx.type(refResolverTypeRef),
        REF_RESOLVER_NAME,
        new Cast(refResolverExpr, refResolverTypeRef));
    // use concrete type to avoid virtual methods call in generated code
    TypeRef<?> typeResolverType = TypeRef.of(typeResolver.getClass());
    typeResolverRef = fieldRef(TYPE_RESOLVER_NAME, typeResolverType);
    Expression typeResolverExpr =
        cast(
            inlineInvoke(foryRef, "getTypeResolver", TypeRef.of(TypeResolver.class)),
            typeResolverType);
    ctx.addField(ctx.type(typeResolverType), TYPE_RESOLVER_NAME, typeResolverExpr);
    ctx.reserveName(STRING_SERIALIZER_NAME);
    stringSerializerRef = fieldRef(STRING_SERIALIZER_NAME, STRING_SERIALIZER_TYPE_TOKEN);
    ctx.addField(
        ctx.type(TypeRef.of(StringSerializer.class)),
        STRING_SERIALIZER_NAME,
        inlineInvoke(foryRef, "getStringSerializer", typeResolverType));
    jitCallbackUpdateFields = new HashMap<>();
    descriptorDispatchId = new HashMap<>();
  }

  // Must be static to be shared across the whole process life.
  private static final Map<String, Map<String, Integer>> idGenerator = new ConcurrentHashMap<>();

  public String codecClassName(Class<?> beanClass) {
    String name = ReflectionUtils.getClassNameWithoutPackage(beanClass).replace("$", "_");
    StringBuilder nameBuilder = new StringBuilder(name);
    if (fory.isCrossLanguage()) {
      // Generated classes are different when xlang mode is enabled.
      // So we need to use a different class name for generated serializers.
      nameBuilder.append("Xlang");
    }
    if (fory.trackingRef()) {
      // Generated classes are different when referenceTracking is switched.
      // So we need to use a different name.
      nameBuilder.append("ForyRef");
    } else {
      nameBuilder.append("Fory");
    }
    nameBuilder.append("Codec").append(codecSuffix());
    Map<String, Integer> subGenerator =
        idGenerator.computeIfAbsent(nameBuilder.toString(), k -> new ConcurrentHashMap<>());
    String key = fory.getConfigHash() + "_" + CodeGenerator.getClassUniqueId(beanClass);
    Integer id = subGenerator.get(key);
    if (id == null) {
      synchronized (subGenerator) {
        id = subGenerator.computeIfAbsent(key, k -> subGenerator.size());
      }
    }
    nameBuilder.append('_').append(id);
    return nameBuilder.toString();
  }

  public String codecQualifiedClassName(Class<?> beanClass) {
    String pkg = getPackage(beanClass);
    if (StringUtils.isNotBlank(pkg)) {
      return pkg + "." + codecClassName(beanClass);
    } else {
      return codecClassName(beanClass);
    }
  }

  protected abstract String codecSuffix();

  protected <T> T fory(Function<Fory, T> function) {
    return fory.getJITContext().asyncVisitFory(function);
  }

  protected <T> T typeResolver(Function<TypeResolver, T> function) {
    return typeResolver(fory, function);
  }

  protected static <T> T typeResolver(Fory fory, Function<TypeResolver, T> function) {
    return fory.getJITContext().asyncVisitFory(f -> function.apply(f.getTypeResolver()));
  }

  private boolean needWriteRef(TypeRef<?> type) {
    return typeResolver(r -> r.needToWriteRef(type));
  }

  @Override
  public String genCode() {
    ctx.setPackage(getPackage(beanClass));
    String className = codecClassName(beanClass);
    ctx.setClassName(className);
    // don't addImport(beanClass), because user class may name collide.
    ctx.extendsClasses(ctx.type(parentSerializerClass));
    ctx.reserveName(POJO_CLASS_TYPE_NAME);
    ctx.addField(ctx.type(Fory.class), FORY_NAME);
    Expression encodeExpr = buildEncodeExpression();
    Expression decodeExpr = buildDecodeExpression();
    String constructorCode =
        StringUtils.format(
            ""
                + "super(${fory}, ${cls});\n"
                + "this.${fory} = ${fory};\n"
                + "${fory}.getTypeResolver().setSerializerIfAbsent(${cls}, this);\n",
            "fory",
            FORY_NAME,
            "cls",
            POJO_CLASS_TYPE_NAME);

    ctx.clearExprState();
    String encodeCode = encodeExpr.genCode(ctx).code();
    encodeCode = ctx.optimizeMethodCode(encodeCode);
    ctx.clearExprState();
    String decodeCode = decodeExpr.genCode(ctx).code();
    decodeCode = ctx.optimizeMethodCode(decodeCode);
    ctx.overrideMethod(
        writeMethodName,
        encodeCode,
        void.class,
        MemoryBuffer.class,
        BUFFER_NAME,
        Object.class,
        ROOT_OBJECT_NAME);
    ctx.overrideMethod(readMethodName, decodeCode, Object.class, MemoryBuffer.class, BUFFER_NAME);
    registerJITNotifyCallback();
    ctx.addConstructor(constructorCode, Fory.class, FORY_NAME, Class.class, POJO_CLASS_TYPE_NAME);
    return ctx.genCode();
  }

  protected static class InvokeHint {
    public boolean genNewMethod;
    public Set<Expression> cutPoints = new HashSet<>();

    public InvokeHint(boolean genNewMethod, Expression... cutPoints) {
      this.genNewMethod = genNewMethod;
      Collections.addAll(this.cutPoints, cutPoints);
    }

    public InvokeHint add(Expression cutPoint) {
      if (cutPoint != null) {
        cutPoints.add(cutPoint);
      }
      return this;
    }

    public InvokeHint copy() {
      InvokeHint invokeHint = new InvokeHint(genNewMethod);
      invokeHint.cutPoints = new HashSet<>(cutPoints);
      return invokeHint;
    }

    @Override
    public String toString() {
      return "InvokeHint{" + "genNewMethod=" + genNewMethod + ", cutPoints=" + cutPoints + '}';
    }
  }

  protected void registerJITNotifyCallback() {
    // build encode/decode expr before add constructor to fill up jitCallbackUpdateFields.
    if (!jitCallbackUpdateFields.isEmpty()) {
      StringJoiner stringJoiner = new StringJoiner(", ", "registerJITNotifyCallback(this,", ");\n");
      for (Map.Entry<String, Expression> entry : jitCallbackUpdateFields.entrySet()) {
        Code.ExprCode exprCode = entry.getValue().genCode(ctx);
        if (StringUtils.isNotBlank(exprCode.code())) {
          stringJoiner.add(exprCode.code());
        }
        stringJoiner.add("\"" + entry.getKey() + "\"");
        stringJoiner.add(exprCode.value().toString());
      }
      // add this code after field serialization initialization to avoid
      // it overrides field updates by this callback.
      ctx.addInitCode(stringJoiner.toString());
    }
  }

  /**
   * Add common imports to reduce generated code size to speed up jit. Since user class are
   * qualified, there won't be any conflict even if user class has the same name as fory classes.
   *
   * @see CodeGenerator#getClassUniqueId
   */
  protected void addCommonImports() {
    ctx.addImports(
        Fory.class, MemoryBuffer.class, fory.getRefResolver().getClass(), Platform.class);
    ctx.addImports(TypeInfo.class, TypeInfoHolder.class, ClassResolver.class);
    ctx.addImport(Generated.class);
    ctx.addImports(LazyInitBeanSerializer.class, EnumSerializer.class);
    ctx.addImports(Serializer.class, StringSerializer.class);
    ctx.addImports(ObjectSerializer.class, MetaSharedSerializer.class);
    ctx.addImports(CollectionLikeSerializer.class, MapLikeSerializer.class);
  }

  /**
   * Returns an expression that serialize an nullable <code>inputObject</code> to <code>buffer
   * </code>.
   */
  protected Expression serializeFor(
      Expression inputObject, Expression buffer, TypeRef<?> typeRef, boolean generateNewMethod) {
    return serializeFor(inputObject, buffer, typeRef, null, generateNewMethod);
  }

  protected Expression serializeFor(
      Expression inputObject,
      Expression buffer,
      TypeRef<?> typeRef,
      Expression serializer,
      boolean generateNewMethod) {
    if (needWriteRef(typeRef)) {
      return new If(
          not(writeRefOrNull(buffer, inputObject)),
          serializeForNotNull(inputObject, buffer, typeRef, serializer, generateNewMethod));
    } else {
      // if typeToken is not final, ref tracking of subclass will be ignored too.
      if (typeRef.isPrimitive()) {
        return serializeForNotNull(inputObject, buffer, typeRef, serializer, generateNewMethod);
      }
      Expression action =
          new ListExpression(
              new Invoke(
                  buffer, "writeByte", new Literal(Fory.NOT_NULL_VALUE_FLAG, PRIMITIVE_BYTE_TYPE)),
              serializeForNotNull(inputObject, buffer, typeRef, serializer, generateNewMethod));
      return new If(
          eqNull(inputObject),
          new Invoke(buffer, "writeByte", new Literal(Fory.NULL_FLAG, PRIMITIVE_BYTE_TYPE)),
          action);
    }
  }

  protected Expression serializeField(
      Expression fieldValue, Expression buffer, Descriptor descriptor) {
    TypeRef<?> typeRef = descriptor.getTypeRef();
    boolean nullable = descriptor.isNullable();
    // descriptor.isTrackingRef() already includes the needWriteRef check
    boolean useRefTracking = descriptor.isTrackingRef();

    if (useRefTracking) {
      return new If(
          not(writeRefOrNull(buffer, fieldValue)),
          serializeForNotNullForField(fieldValue, buffer, descriptor, null));
    } else {
      // if typeToken is not final, ref tracking of subclass will be ignored too.
      if (typeRef.isPrimitive()) {
        return serializeForNotNullForField(fieldValue, buffer, descriptor, null);
      }
      if (nullable) {
        Expression action =
            new ListExpression(
                new Invoke(buffer, "writeByte", Literal.ofByte(Fory.NOT_NULL_VALUE_FLAG)),
                serializeForNotNullForField(fieldValue, buffer, descriptor, null));
        return new If(
            eqNull(fieldValue),
            new Invoke(buffer, "writeByte", Literal.ofByte(Fory.NULL_FLAG)),
            action);
      } else {
        return serializeForNotNullForField(fieldValue, buffer, descriptor, null);
      }
    }
  }

  private Expression serializeForNotNullForField(
      Expression inputObject, Expression buffer, Descriptor descriptor, Expression serializer) {
    TypeRef<?> typeRef = descriptor.getTypeRef();
    Class<?> clz = getRawType(typeRef);
    if (isPrimitive(clz) || isBoxed(clz)) {
      return serializePrimitiveField(inputObject, buffer, descriptor);
    } else {
      if (clz == String.class) {
        return fory.getStringSerializer().writeStringExpr(stringSerializerRef, buffer, inputObject);
      }
      Expression action;
      if (useCollectionSerialization(typeRef)) {
        action = serializeForCollection(buffer, inputObject, typeRef, serializer, false);
      } else if (useMapSerialization(typeRef)) {
        action = serializeForMap(buffer, inputObject, typeRef, serializer, false);
      } else {
        action = serializeForNotNullObjectForField(inputObject, buffer, descriptor, serializer);
      }
      return action;
    }
  }

  private Expression serializePrimitiveField(
      Expression inputObject, Expression buffer, Descriptor descriptor) {
    int dispatchId = getNumericDescriptorDispatchId(descriptor);
    switch (dispatchId) {
      case DispatchId.BOOL:
        return new Invoke(buffer, "writeBoolean", inputObject);
      case DispatchId.INT8:
      case DispatchId.UINT8:
        return new Invoke(buffer, "writeByte", inputObject);
      case DispatchId.CHAR:
        return new Invoke(buffer, "writeChar", inputObject);
      case DispatchId.INT16:
      case DispatchId.UINT16:
        return new Invoke(buffer, "writeInt16", inputObject);
      case DispatchId.INT32:
      case DispatchId.UINT32:
        return new Invoke(buffer, "writeInt32", inputObject);
      case DispatchId.VARINT32:
        return new Invoke(buffer, "writeVarInt32", inputObject);
      case DispatchId.VAR_UINT32:
        return new Invoke(buffer, "writeVarUint32", inputObject);
      case DispatchId.INT64:
      case DispatchId.UINT64:
        return new Invoke(buffer, "writeInt64", inputObject);
      case DispatchId.VARINT64:
        return new Invoke(buffer, "writeVarInt64", inputObject);
      case DispatchId.TAGGED_INT64:
        return new Invoke(buffer, "writeTaggedInt64", inputObject);
      case DispatchId.VAR_UINT64:
        return new Invoke(buffer, "writeVarUint64", inputObject);
      case DispatchId.TAGGED_UINT64:
        return new Invoke(buffer, "writeTaggedUint64", inputObject);
      case DispatchId.FLOAT32:
        return new Invoke(buffer, "writeFloat32", inputObject);
      case DispatchId.FLOAT64:
        return new Invoke(buffer, "writeFloat64", inputObject);
      default:
        throw new IllegalStateException("Unsupported dispatchId: " + dispatchId);
    }
  }

  private Expression serializePrimitive(Expression inputObject, Expression buffer, Class<?> clz) {
    // for primitive, inline call here to avoid java boxing, rather call corresponding serializer.
    if (clz == byte.class || clz == Byte.class) {
      return new Invoke(buffer, "writeByte", inputObject);
    } else if (clz == boolean.class || clz == Boolean.class) {
      return new Invoke(buffer, "writeBoolean", inputObject);
    } else if (clz == char.class || clz == Character.class) {
      return new Invoke(buffer, "writeChar", inputObject);
    } else if (clz == short.class || clz == Short.class) {
      return new Invoke(buffer, "writeInt16", inputObject);
    } else if (clz == int.class || clz == Integer.class) {
      String func = fory.compressInt() ? "writeVarInt32" : "writeInt32";
      return new Invoke(buffer, func, inputObject);
    } else if (clz == long.class || clz == Long.class) {
      return LongSerializer.writeInt64(buffer, inputObject, fory.longEncoding(), true);
    } else if (clz == float.class || clz == Float.class) {
      return new Invoke(buffer, "writeFloat32", inputObject);
    } else if (clz == double.class || clz == Double.class) {
      return new Invoke(buffer, "writeFloat64", inputObject);
    } else {
      throw new IllegalStateException("impossible");
    }
  }

  private Expression serializeForNotNullObjectForField(
      Expression inputObject, Expression buffer, Descriptor descriptor, Expression serializer) {
    TypeRef<?> typeRef = descriptor.getTypeRef();
    Class<?> clz = getRawType(typeRef);
    if (serializer != null) {
      return new Invoke(serializer, writeMethodName, buffer, inputObject);
    }
    if (isMonomorphic(descriptor)) {
      // Use descriptor to get the appropriate serializer
      serializer = getSerializerForField(clz);
      return new Invoke(serializer, writeMethodName, buffer, inputObject);
    } else {
      return writeForNotNullNonFinalObject(inputObject, buffer, typeRef);
    }
  }

  private Expression getSerializerForField(Class<?> cls) {
    return getOrCreateSerializer(cls, true);
  }

  protected Expression serializeForNullable(
      Expression inputObject, Expression buffer, TypeRef<?> typeRef, boolean nullable) {
    return serializeForNullable(inputObject, buffer, typeRef, null, false, nullable);
  }

  protected Expression serializeForNullable(
      Expression inputObject,
      Expression buffer,
      TypeRef<?> typeRef,
      Expression serializer,
      boolean generateNewMethod,
      boolean nullable) {
    if (needWriteRef(typeRef)) {
      return new If(
          not(writeRefOrNull(buffer, inputObject)),
          serializeForNotNull(inputObject, buffer, typeRef, serializer, generateNewMethod));
    } else {
      // if typeToken is not final, ref tracking of subclass will be ignored too.
      if (typeRef.isPrimitive()) {
        return serializeForNotNull(inputObject, buffer, typeRef, serializer, generateNewMethod);
      }
      if (nullable) {
        Expression action =
            new ListExpression(
                new Invoke(buffer, "writeByte", Literal.ofByte(Fory.NOT_NULL_VALUE_FLAG)),
                serializeForNotNull(inputObject, buffer, typeRef, serializer, generateNewMethod));
        return new If(
            eqNull(inputObject),
            new Invoke(buffer, "writeByte", Literal.ofByte(Fory.NULL_FLAG)),
            action);
      } else {
        return serializeForNotNull(inputObject, buffer, typeRef, serializer, generateNewMethod);
      }
    }
  }

  protected Expression writeRefOrNull(Expression buffer, Expression object) {
    return inlineInvoke(refResolverRef, "writeRefOrNull", PRIMITIVE_BOOLEAN_TYPE, buffer, object);
  }

  protected Expression serializeForNotNull(
      Expression inputObject, Expression buffer, TypeRef<?> typeRef) {
    boolean genNewMethod = useCollectionSerialization(typeRef) || useMapSerialization(typeRef);
    return serializeForNotNull(inputObject, buffer, typeRef, null, genNewMethod);
  }

  /**
   * Returns an expression that serialize an not null <code>inputObject</code> to <code>buffer
   * </code>.
   */
  private Expression serializeForNotNull(
      Expression inputObject, Expression buffer, TypeRef<?> typeRef, boolean generateNewMethod) {
    return serializeForNotNull(inputObject, buffer, typeRef, null, generateNewMethod);
  }

  private Expression serializeForNotNull(
      Expression inputObject, Expression buffer, TypeRef<?> typeRef, Expression serializer) {
    boolean genNewMethod = useCollectionSerialization(typeRef) || useMapSerialization(typeRef);
    return serializeForNotNull(inputObject, buffer, typeRef, serializer, genNewMethod);
  }

  private Expression serializeForNotNull(
      Expression inputObject,
      Expression buffer,
      TypeRef<?> typeRef,
      Expression serializer,
      boolean generateNewMethod) {
    Class<?> clz = getRawType(typeRef);
    if (isPrimitive(clz) || isBoxed(clz)) {
      return serializePrimitive(inputObject, buffer, clz);
    } else {
      if (clz == String.class) {
        return fory.getStringSerializer().writeStringExpr(stringSerializerRef, buffer, inputObject);
      }
      Expression action;
      // this is different from ITERABLE_TYPE in RowCodecBuilder. In row-format we don't need to
      // ensure
      // class consistence, we only need to ensure interface consistence. But in java serialization,
      // we need to ensure class consistence.
      if (useCollectionSerialization(typeRef)) {
        action =
            serializeForCollection(buffer, inputObject, typeRef, serializer, generateNewMethod);
      } else if (useMapSerialization(typeRef)) {
        action = serializeForMap(buffer, inputObject, typeRef, serializer, generateNewMethod);
      } else {
        action = serializeForNotNullObject(inputObject, buffer, typeRef, serializer);
      }
      return action;
    }
  }

  protected boolean useCollectionSerialization(TypeRef<?> typeRef) {
    return useCollectionSerialization(TypeUtils.getRawType(typeRef));
  }

  protected boolean useCollectionSerialization(Class<?> type) {
    return typeResolver(r -> r.isCollection(type));
  }

  protected boolean useMapSerialization(TypeRef<?> typeRef) {
    return useMapSerialization(TypeUtils.getRawType(typeRef));
  }

  protected boolean useMapSerialization(Class<?> type) {
    return typeResolver(r -> r.isMap(type));
  }

  protected int getNumericDescriptorDispatchId(Descriptor descriptor) {
    Class<?> rawType = descriptor.getRawType();
    Preconditions.checkArgument(TypeUtils.unwrap(rawType).isPrimitive());
    return descriptorDispatchId.computeIfAbsent(descriptor, d -> DispatchId.getDispatchId(fory, d));
  }

  /**
   * Whether the provided type should be taken as final. Although the <code>clz</code> can be final,
   * the method can still return false. For example, we return false in meta share mode to write
   * class defs for the non-inner final types.
   */
  protected boolean isMonomorphic(Class<?> clz) {
    return typeResolver(r -> r.isMonomorphic(clz));
  }

  protected boolean isMonomorphic(TypeRef<?> typeRef) {
    return isMonomorphic(typeRef.getRawType());
  }

  protected boolean isMonomorphic(Descriptor descriptor) {
    return typeResolver(r -> r.isMonomorphic(descriptor));
  }

  protected Expression serializeForNotNullObject(
      Expression inputObject, Expression buffer, TypeRef<?> typeRef, Expression serializer) {
    Class<?> clz = getRawType(typeRef);
    if (serializer != null) {
      return new Invoke(serializer, writeMethodName, buffer, inputObject);
    }
    if (isMonomorphic(clz)) {
      serializer = getOrCreateSerializer(clz);
      return new Invoke(serializer, writeMethodName, buffer, inputObject);
    } else {
      return writeForNotNullNonFinalObject(inputObject, buffer, typeRef);
    }
  }

  // Note that `CompatibleCodecBuilder` may mark some final objects as non-final.
  protected Expression writeForNotNullNonFinalObject(
      Expression inputObject, Expression buffer, TypeRef<?> typeRef) {
    Class<?> clz = getRawType(typeRef);
    Expression clsExpr = new Invoke(inputObject, "getClass", "cls", CLASS_TYPE);
    ListExpression writeClassAndObject = new ListExpression();
    Tuple2<Reference, Boolean> classInfoRef = addTypeInfoField(clz);
    Expression classInfo = classInfoRef.f0;
    if (classInfoRef.f1) {
      writeClassAndObject.add(
          new If(
              neq(new Invoke(classInfo, "getCls", CLASS_TYPE), clsExpr),
              new Assign(
                  classInfo,
                  inlineInvoke(typeResolverRef, "getTypeInfo", classInfoTypeRef, clsExpr))));
    }
    writeClassAndObject.add(
        typeResolver(r -> r.writeClassExpr(typeResolverRef, buffer, classInfo)));
    writeClassAndObject.add(
        new Invoke(
            invokeInline(classInfo, "getSerializer", getSerializerType(clz)),
            writeMethodName,
            PRIMITIVE_VOID_TYPE,
            buffer,
            inputObject));
    return invokeGenerated(
        ctx, ofHashSet(buffer, inputObject), writeClassAndObject, "writeClassAndObject", false);
  }

  protected Expression writeTypeInfo(
      Expression buffer, Expression clsExpr, Class<?> declaredClass, boolean returnSerializer) {
    ListExpression writeClassAction = new ListExpression();
    Tuple2<Reference, Boolean> classInfoRef = addTypeInfoField(declaredClass);
    Expression classInfo = classInfoRef.f0;
    writeClassAction.add(
        new If(
            neq(inlineInvoke(classInfo, "getCls", CLASS_TYPE), clsExpr),
            new Assign(
                classInfo,
                inlineInvoke(typeResolverRef, "getTypeInfo", classInfoTypeRef, clsExpr))));
    writeClassAction.add(typeResolver(r -> r.writeClassExpr(typeResolverRef, buffer, classInfo)));
    if (returnSerializer) {
      writeClassAction.add(
          invoke(classInfo, "getSerializer", "serializer", getSerializerType(declaredClass)));
    }
    return writeClassAction;
  }

  /**
   * Returns a serializer expression which will be used to call write/read method to avoid virtual
   * methods calls in most situations.
   */
  protected Expression getOrCreateSerializer(Class<?> cls) {
    return getOrCreateSerializer(cls, false);
  }

  private Expression getOrCreateSerializer(Class<?> cls, boolean isField) {
    // Not need to check cls final, take collection writeSameTypeElements as an example.
    // Preconditions.checkArgument(isMonomorphic(cls), cls);
    Reference serializerRef = serializerMap.get(cls);
    if (serializerRef == null) {
      // potential recursive call for seq codec generation is handled in `getSerializerClass`.
      Class<? extends Serializer> serializerClass = typeResolver(r -> r.getSerializerClass(cls));
      boolean finalClassAsFieldCondition =
          !fory.isShareMeta()
              && !fory.isCompatible()
              && isField
              && Modifier.isFinal(cls.getModifiers())
              && serializerClass == ReplaceResolveSerializer.class;
      if (finalClassAsFieldCondition) {
        serializerClass = FinalFieldReplaceResolveSerializer.class;
      }

      Preconditions.checkNotNull(serializerClass, "Unsupported for class " + cls);
      if (!ReflectionUtils.isPublic(serializerClass)) {
        // TODO(chaokunyang) add jdk17+ unexported class check.
        // non-public class can't be accessed in generated class.
        serializerClass = Serializer.class;
      } else {
        ClassLoader beanClassClassLoader = beanClass.getClassLoader();
        if (beanClassClassLoader == null) {
          beanClassClassLoader = Thread.currentThread().getContextClassLoader();
          if (beanClassClassLoader == null) {
            beanClassClassLoader = Fory.class.getClassLoader();
          }
        }
        try {
          beanClassClassLoader.loadClass(serializerClass.getName());
        } catch (ClassNotFoundException e) {
          // If `cls` is loaded in another class different from `beanClassClassLoader`,
          // then serializerClass is loaded in another class different from `beanClassClassLoader`.
          serializerClass = LazyInitBeanSerializer.class;
        }
        if (serializerClass == LazyInitBeanSerializer.class
            || serializerClass == ObjectSerializer.class
            || serializerClass == MetaSharedSerializer.class
            || serializerClass == DeferredLazyObjectSerializer.class) {
          // field init may get jit serializer, which will cause cast exception if not use base
          // type.
          serializerClass = Serializer.class;
        }
      }
      if (useCollectionSerialization(cls)
          && !CollectionLikeSerializer.class.isAssignableFrom(serializerClass)) {
        serializerClass = CollectionLikeSerializer.class;
      } else if (useMapSerialization(cls)
          && !MapLikeSerializer.class.isAssignableFrom(serializerClass)) {
        serializerClass = MapLikeSerializer.class;
      }
      Expression fieldTypeExpr = getClassExpr(cls);
      // Don't invoke `Serializer.newSerializer` here, since it(ex. ObjectSerializer) may set itself
      // as global serializer, which overwrite serializer updates in jit callback.
      Expression newSerializerExpr;
      if (finalClassAsFieldCondition) {
        // Create serializer directly via static factory method
        newSerializerExpr =
            new Expression.NewInstance(FINAL_FIELD_SERIALIZER_TYPE, foryRef, fieldTypeExpr);
      } else {
        newSerializerExpr =
            inlineInvoke(typeResolverRef, "getRawSerializer", SERIALIZER_TYPE, fieldTypeExpr);
      }
      String name = ctx.newName(StringUtils.uncapitalize(serializerClass.getSimpleName()));
      // It's ok it jit already finished and this method return false, in such cases
      // `serializerClass` is already jit generated class.
      boolean hasJITResult = fory.getJITContext().hasJITResult(cls);
      if (hasJITResult) {
        jitCallbackUpdateFields.put(name, getClassExpr(cls));
        ctx.addField(
            false, ctx.type(Serializer.class), name, cast(newSerializerExpr, SERIALIZER_TYPE));
        serializerRef = new Reference(name, SERIALIZER_TYPE, false);
      } else {
        TypeRef<? extends Serializer> serializerTypeRef = TypeRef.of(serializerClass);
        ctx.addField(
            true, ctx.type(serializerClass), name, cast(newSerializerExpr, serializerTypeRef));
        serializerRef = fieldRef(name, serializerTypeRef);
      }
      serializerMap.put(cls, serializerRef);
    }
    return serializerRef;
  }

  protected Expression getClassExpr(Class<?> cls) {
    if (sourcePublicAccessible(cls)) {
      return Literal.ofClass(cls);
    } else {
      String name = cls.getName();
      if (cls.isArray()) {
        Tuple2<Class<?>, Integer> info = TypeUtils.getArrayComponentInfo(cls);
        name = StringUtils.repeat("a", info.f1) + "_" + info.f0.getName();
      }
      name = name.replace(".", "_");
      return staticClassFieldExpr(cls, "__class__" + name);
    }
  }

  private final Map<TypeRef<?>, String> namesForSharedGenericTypeFields = new HashMap<>();

  protected Expression getGenericTypeField(TypeRef<?> typeRef) {
    // create a field name from generic type, so multiple call of same generic type will reuse the
    // same field.
    String name =
        namesForSharedGenericTypeFields.computeIfAbsent(
            typeRef,
            k -> {
              String prefix;
              if (typeRef.getRawType().isArray()) {
                Tuple2<Class<?>, Integer> info =
                    TypeUtils.getArrayComponentInfo(typeRef.getRawType());
                prefix = info.f0.getSimpleName() + "_arr" + info.f1;
              } else {
                prefix = typeRef.getRawType().getSimpleName();
              }
              return StringUtils.uncapitalize(prefix) + namesForSharedGenericTypeFields.size();
            });

    return getOrCreateField(
        false,
        GenericType.class,
        name,
        () ->
            new Invoke(
                typeResolverRef,
                "getGenericTypeInStruct",
                GENERIC_TYPE,
                beanClassExpr(),
                ofString(typeRef.getType().getTypeName())));
  }

  /**
   * The boolean value in tuple indicates whether the classinfo needs update.
   *
   * @return false for tuple field1 if the classinfo doesn't need update.
   */
  protected Tuple2<Reference, Boolean> addTypeInfoField(Class<?> cls) {
    Expression classInfoExpr;
    boolean needUpdate = !ReflectionUtils.isMonomorphic(cls);
    String key;
    if (!needUpdate) {
      key = "classInfo:" + cls;
    } else {
      key = "classInfo:" + cls + walkPath;
    }
    Tuple2<Reference, Boolean> classInfoRef = (Tuple2<Reference, Boolean>) sharedFieldMap.get(key);
    if (classInfoRef != null) {
      return classInfoRef;
    }
    if (!needUpdate) {
      Expression clsExpr = getClassExpr(cls);
      classInfoExpr = inlineInvoke(typeResolverRef, "getTypeInfo", classInfoTypeRef, clsExpr);
      // Use `ctx.freshName(cls)` to avoid wrong name for arr type.
      String name = ctx.newName(ctx.newName(cls) + "TypeInfo");
      ctx.addField(true, ctx.type(TypeInfo.class), name, classInfoExpr);
      classInfoRef = Tuple2.of(fieldRef(name, classInfoTypeRef), false);
    } else {
      classInfoExpr = inlineInvoke(typeResolverRef, "nilTypeInfo", classInfoTypeRef);
      String name = ctx.newName(cls, "TypeInfo");
      ctx.addField(false, ctx.type(TypeInfo.class), name, classInfoExpr);
      // Can't use fieldRef, since the field is not final.
      classInfoRef = Tuple2.of(new Reference(name, classInfoTypeRef), true);
    }
    sharedFieldMap.put(key, classInfoRef);
    return classInfoRef;
  }

  protected Reference addTypeInfoHolderField(Class<?> cls) {
    // Final type need to write classinfo when meta share enabled.
    String key;
    if (ReflectionUtils.isMonomorphic(cls)) {
      key = "classInfoHolder:" + cls;
    } else {
      key = "classInfoHolder:" + cls + walkPath;
    }
    Reference reference = (Reference) sharedFieldMap.get(key);
    if (reference != null) {
      return reference;
    }
    Expression classInfoHolderExpr =
        inlineInvoke(typeResolverRef, "nilTypeInfoHolder", classInfoHolderTypeRef);
    String name = ctx.newName(cls, "TypeInfoHolder");
    ctx.addField(true, ctx.type(TypeInfoHolder.class), name, classInfoHolderExpr);
    // The class info field read only once, no need to shallow.
    reference = new Reference(name, classInfoHolderTypeRef);
    sharedFieldMap.put(key, reference);
    return reference;
  }

  protected Expression readTypeInfo(Class<?> cls, Expression buffer) {
    return readTypeInfo(cls, buffer, true);
  }

  protected Expression readTypeInfo(Class<?> cls, Expression buffer, boolean inlineReadTypeInfo) {
    if (ReflectionUtils.isMonomorphic(cls)) {
      Reference classInfoRef = addTypeInfoField(cls).f0;
      if (inlineReadTypeInfo) {
        return inlineInvoke(
            typeResolverRef, "readTypeInfo", classInfoTypeRef, buffer, classInfoRef);
      } else {
        return new Invoke(typeResolverRef, "readTypeInfo", classInfoTypeRef, buffer, classInfoRef);
      }
    }
    Reference classInfoHolderRef = addTypeInfoHolderField(cls);
    if (inlineReadTypeInfo) {
      return inlineInvoke(
          typeResolverRef, "readTypeInfo", classInfoTypeRef, buffer, classInfoHolderRef);
    } else {
      return new Invoke(
          typeResolverRef, "readTypeInfo", classInfoTypeRef, buffer, classInfoHolderRef);
    }
  }

  protected TypeRef<?> getSerializerType(TypeRef<?> objType) {
    return getSerializerType(objType.getRawType());
  }

  protected TypeRef<?> getSerializerType(Class<?> objType) {
    if (typeResolver(r -> r.isCollection(objType))) {
      return COLLECTION_SERIALIZER_TYPE;
    } else if (typeResolver(r -> r.isMap(objType))) {
      return MAP_SERIALIZER_TYPE;
    }
    return SERIALIZER_TYPE;
  }

  /**
   * Return an expression to write a collection to <code>buffer</code>. This expression can have
   * better efficiency for final element type. For final element type, it doesn't have to write
   * class info, no need to forward to <code>fory</code>.
   *
   * @param generateNewMethod Generated code for nested container will be greater than 325 bytes,
   *     which is not possible for inlining, and if code is bigger, jit compile may also be skipped.
   */
  protected Expression serializeForCollection(
      Expression buffer,
      Expression collection,
      TypeRef<?> typeRef,
      Expression serializer,
      boolean generateNewMethod) {
    // get serializer, write class info if necessary.
    if (serializer == null) {
      Class<?> clz = getRawType(typeRef);
      if (isMonomorphic(clz)) {
        serializer = getOrCreateSerializer(clz);
      } else {
        ListExpression writeClassAction = new ListExpression();
        Tuple2<Reference, Boolean> classInfoRef = addTypeInfoField(clz);
        Expression classInfo = classInfoRef.f0;
        Expression clsExpr = new Invoke(collection, "getClass", "cls", CLASS_TYPE);
        writeClassAction.add(
            new If(
                neq(new Invoke(classInfo, "getCls", CLASS_TYPE), clsExpr),
                new Assign(
                    classInfo,
                    inlineInvoke(typeResolverRef, "getTypeInfo", classInfoTypeRef, clsExpr))));
        writeClassAction.add(
            typeResolver(r -> r.writeClassExpr(typeResolverRef, buffer, classInfo)));
        writeClassAction.add(
            new Return(invokeInline(classInfo, "getSerializer", getSerializerType(typeRef))));
        // Spit this into a separate method to avoid method too big to inline.
        serializer =
            invokeGenerated(
                ctx,
                ofHashSet(buffer, collection),
                writeClassAction,
                "writeCollectionTypeInfo",
                false);
      }
    } else if (!TypeRef.of(CollectionLikeSerializer.class).isSupertypeOf(serializer.type())) {
      serializer = cast(serializer, TypeRef.of(CollectionLikeSerializer.class), "colSerializer");
    }
    TypeRef<?> elementType = getElementType(typeRef);
    // write collection data.
    Expression ifExpr =
        new If(
            inlineInvoke(serializer, "supportCodegenHook", PRIMITIVE_BOOLEAN_TYPE),
            writeCollectionData(buffer, collection, serializer, elementType),
            new Invoke(serializer, writeMethodName, buffer, collection));
    // Wrap collection and ifExpr in a ListExpression to ensure collection is evaluated before the
    // If. This is necessary because 'collection' is used in both branches of the If expression.
    // Without this, the code generator would assign collection to a variable inside the
    // then-branch, and then try to use that variable name in the else-branch where it's out of
    // scope.
    ListExpression actions = new ListExpression(collection, ifExpr);
    if (generateNewMethod) {
      return invokeGenerated(
          ctx, ofHashSet(buffer, collection, serializer), actions, "writeCollection", false);
    }
    return actions;
  }

  private TypeRef<?> getElementType(TypeRef<?> typeRef) {
    TypeRef<?> elementType = TypeUtils.getElementType(typeRef);
    if (elementType.equals(typeRef)) {
      elementType = OBJECT_TYPE;
    }
    return elementType;
  }

  protected Expression writeCollectionData(
      Expression buffer, Expression collection, Expression serializer, TypeRef<?> elementType) {
    Invoke onCollectionWrite =
        new Invoke(
            serializer,
            "onCollectionWrite",
            TypeUtils.collectionOf(elementType),
            buffer,
            collection);
    boolean isList = List.class.isAssignableFrom(getRawType(collection.type()));
    collection =
        isList ? new Cast(onCollectionWrite.inline(), LIST_TYPE, "list") : onCollectionWrite;
    Expression size = new Invoke(collection, "size", PRIMITIVE_INT_TYPE);
    walkPath.add(elementType.toString());
    ListExpression builder = new ListExpression();
    Class<?> elemClass = TypeUtils.getRawType(elementType);
    boolean trackingRef = needWriteRef(elementType);
    Tuple2<Expression, Invoke> writeElementsHeader =
        writeElementsHeader(elemClass, trackingRef, serializer, buffer, collection);
    Expression flags = writeElementsHeader.f0;
    builder.add(flags);
    boolean finalType = isMonomorphic(elemClass);
    if (finalType) {
      if (trackingRef) {
        builder.add(
            writeContainerElements(elementType, true, null, null, buffer, collection, size));
      } else {
        Literal hasNullFlag = ofInt(CollectionFlags.HAS_NULL);
        Expression hasNull = eq(new BitAnd(flags, hasNullFlag), hasNullFlag, "hasNull");
        builder.add(
            hasNull,
            writeContainerElements(elementType, false, null, hasNull, buffer, collection, size));
      }
    } else {
      Literal flag = ofInt(CollectionFlags.IS_SAME_TYPE);
      Expression sameElementClass = eq(new BitAnd(flags, flag), flag, "sameElementClass");
      builder.add(sameElementClass);
      //  if ((flags & Flags.NOT_DECL_ELEMENT_TYPE) == Flags.NOT_DECL_ELEMENT_TYPE)
      Literal isDeclTypeFlag = ofInt(CollectionFlags.IS_DECL_ELEMENT_TYPE);
      Expression isDeclType = eq(new BitAnd(flags, isDeclTypeFlag), isDeclTypeFlag);
      Expression elemSerializer; // make it in scope of `if(sameElementClass)`
      boolean maybeDecl = typeResolver(r -> r.isSerializable(elemClass));
      TypeRef<?> serializerType = getSerializerType(elementType);
      if (maybeDecl) {
        elemSerializer =
            new If(
                isDeclType,
                cast(getOrCreateSerializer(elemClass), serializerType),
                cast(writeElementsHeader.f1.inline(), serializerType),
                false,
                serializerType);
      } else {
        elemSerializer = cast(writeElementsHeader.f1.inline(), serializerType);
      }
      elemSerializer = uninline(elemSerializer);
      Expression action;
      if (trackingRef) {
        // declared elem type may be Object, but all actual elem are same type,
        // and they don't track ref, so we still need actual ref flags.
        Literal trackingRefFlag = ofInt(CollectionFlags.TRACKING_REF);
        Expression trackRef = eq(new BitAnd(flags, trackingRefFlag), trackingRefFlag, "trackRef");
        Literal hasNullFlag = ofInt(CollectionFlags.HAS_NULL);
        Expression hasNull = eq(new BitAnd(flags, hasNullFlag), hasNullFlag, "hasNull");
        builder.add(trackRef);
        builder.add(hasNull);
        ListExpression writeBuilder = new ListExpression(elemSerializer);
        writeBuilder.add(
            new If(
                trackRef,
                writeContainerElements(
                    elementType, true, elemSerializer, null, buffer, collection, size),
                writeContainerElements(
                    elementType, false, elemSerializer, hasNull, buffer, collection, size),
                false));
        Set<Expression> cutPoint = ofHashSet(buffer, collection, size, trackRef, hasNull);
        if (maybeDecl) {
          cutPoint.add(flags);
        }
        Expression differentTypeWrite =
            new If(
                trackRef,
                writeContainerElements(elementType, true, null, null, buffer, collection, size),
                writeContainerElements(elementType, false, null, hasNull, buffer, collection, size),
                false);
        action =
            new If(
                sameElementClass,
                invokeGenerated(ctx, cutPoint, writeBuilder, "sameElementClassWrite", false),
                differentTypeWrite);
      } else {
        // if declared elem type don't track ref, all elements must not write ref.
        Literal hasNullFlag = ofInt(CollectionFlags.HAS_NULL);
        Expression hasNull = eq(new BitAnd(flags, hasNullFlag), hasNullFlag, "hasNull");
        builder.add(hasNull);
        ListExpression writeBuilder = new ListExpression(elemSerializer);
        writeBuilder.add(
            writeContainerElements(
                elementType, false, elemSerializer, hasNull, buffer, collection, size));
        Set<Expression> cutPoint = ofHashSet(buffer, collection, size, hasNull);
        if (maybeDecl) {
          cutPoint.add(flags);
        }
        action =
            new If(
                sameElementClass,
                invokeGenerated(ctx, cutPoint, writeBuilder, "sameElementClassWrite", false),
                writeContainerElements(
                    elementType, false, null, hasNull, buffer, collection, size));
      }
      builder.add(action);
    }
    walkPath.removeLast();
    return new ListExpression(
        onCollectionWrite,
        new If(gt(size, ofInt(0)), builder),
        new Invoke(serializer, "onCollectionWriteFinish", collection));
  }

  /**
   * Write collection elements header: flags and maybe elements classinfo. Keep this consistent with
   * `CollectionLikeSerializer#writeElementsHeader`.
   *
   * @return Tuple(flags, Nullable ( element serializer))
   */
  private Tuple2<Expression, Invoke> writeElementsHeader(
      Class<?> elementType,
      boolean trackingRef,
      Expression collectionSerializer,
      Expression buffer,
      Expression value) {
    if (isMonomorphic(elementType)) {
      Expression bitmap;
      if (trackingRef) {
        bitmap =
            new ListExpression(
                new Invoke(buffer, "writeByte", ofInt(CollectionFlags.DECL_SAME_TYPE_TRACKING_REF)),
                ofInt(CollectionFlags.DECL_SAME_TYPE_TRACKING_REF));
      } else {
        bitmap =
            new Invoke(
                collectionSerializer, "writeNullabilityHeader", PRIMITIVE_INT_TYPE, buffer, value);
      }
      return Tuple2.of(bitmap, null);
    } else {
      Expression elementTypeExpr = getClassExpr(elementType);
      Expression classInfoHolder = addTypeInfoHolderField(elementType);
      Expression bitmap;
      if (trackingRef) {
        if (elementType == Object.class) {
          bitmap =
              new Invoke(
                  collectionSerializer,
                  "writeTypeHeader",
                  PRIMITIVE_INT_TYPE,
                  buffer,
                  value,
                  classInfoHolder);
        } else {
          bitmap =
              new Invoke(
                  collectionSerializer,
                  "writeTypeHeader",
                  PRIMITIVE_INT_TYPE,
                  buffer,
                  value,
                  elementTypeExpr,
                  classInfoHolder);
        }
      } else {
        bitmap =
            new Invoke(
                collectionSerializer,
                "writeTypeNullabilityHeader",
                PRIMITIVE_INT_TYPE,
                buffer,
                value,
                elementTypeExpr,
                classInfoHolder);
      }
      Invoke serializer = new Invoke(classInfoHolder, "getSerializer", SERIALIZER_TYPE);
      return Tuple2.of(bitmap, serializer);
    }
  }

  private Expression writeContainerElements(
      TypeRef<?> elementType,
      boolean trackingRef,
      Expression serializer,
      Expression hasNull,
      Expression buffer,
      Expression collection,
      Expression size) {
    ExprHolder exprHolder =
        ExprHolder.of("buffer", buffer, "hasNull", hasNull, "serializer", serializer);
    // If `List#get` raise UnsupportedException, we should make this collection class un-jit able.
    boolean isList = List.class.isAssignableFrom(getRawType(collection.type()));
    if (isList) {
      exprHolder.add("list", collection);
      return new ForLoop(
          new Literal(0, PRIMITIVE_INT_TYPE),
          size,
          new Literal(1, PRIMITIVE_INT_TYPE),
          i -> {
            Invoke elem = new Invoke(exprHolder.get("list"), "get", OBJECT_TYPE, false, i);
            return writeContainerElement(
                exprHolder.get("buffer"),
                elem,
                elementType,
                trackingRef,
                exprHolder.get("hasNull"),
                exprHolder.get("serializer"));
          });
    } else {
      return new ForEach(
          collection,
          false,
          (i, elem) ->
              writeContainerElement(
                  exprHolder.get("buffer"),
                  elem,
                  elementType,
                  trackingRef,
                  exprHolder.get("hasNull"),
                  exprHolder.get("serializer")));
    }
  }

  private Expression writeContainerElement(
      Expression buffer,
      Expression elem,
      TypeRef<?> elementType,
      boolean trackingRef,
      Expression hasNull,
      Expression elemSerializer) {
    boolean generateNewMethod =
        useCollectionSerialization(elementType) || useMapSerialization(elementType);
    Class<?> rawType = getRawType(elementType);
    boolean finalType = isMonomorphic(rawType);
    elem = tryCastIfPublic(elem, elementType);
    Expression write;
    if (trackingRef) {
      if (finalType) {
        write =
            new If(
                not(writeRefOrNull(buffer, elem)),
                serializeForNotNull(elem, buffer, elementType, generateNewMethod));
      } else {
        write =
            new If(
                not(writeRefOrNull(buffer, elem)),
                serializeForNotNull(elem, buffer, elementType, elemSerializer, generateNewMethod));
      }
    } else {
      if (hasNull != null) {
        Expression writeNotNullInNullBranch =
            finalType
                ? serializeForNotNull(elem, buffer, elementType, generateNewMethod)
                : serializeForNotNull(elem, buffer, elementType, elemSerializer, generateNewMethod);
        Expression writeNotNull =
            finalType
                ? serializeForNotNull(elem, buffer, elementType, generateNewMethod)
                : serializeForNotNull(elem, buffer, elementType, elemSerializer, generateNewMethod);
        write =
            new If(
                hasNull,
                new If(
                    eqNull(elem),
                    new Invoke(buffer, "writeByte", Literal.ofByte(Fory.NULL_FLAG)),
                    new ListExpression(
                        new Invoke(buffer, "writeByte", Literal.ofByte(Fory.NOT_NULL_VALUE_FLAG)),
                        writeNotNullInNullBranch)),
                writeNotNull,
                false);
      } else {
        write =
            finalType
                ? serializeForNotNull(elem, buffer, elementType, generateNewMethod)
                : serializeForNotNull(elem, buffer, elementType, elemSerializer, generateNewMethod);
      }
    }
    return new ListExpression(elem, write);
  }

  /**
   * Return an expression to write a map to <code>buffer</code>. This expression can have better
   * efficiency for final key/value type. For final key/value type, it doesn't have to write class
   * info, no need to forward to <code>fory</code>.
   */
  protected Expression serializeForMap(
      Expression buffer,
      Expression map,
      TypeRef<?> typeRef,
      Expression serializer,
      boolean generateNewMethod) {
    if (serializer == null) {
      Class<?> clz = getRawType(typeRef);
      if (isMonomorphic(clz)) {
        serializer = getOrCreateSerializer(clz);
      } else {
        ListExpression writeClassAction = new ListExpression();
        Tuple2<Reference, Boolean> classInfoRef = addTypeInfoField(clz);
        Expression classInfo = classInfoRef.f0;
        Expression clsExpr = new Invoke(map, "getClass", "cls", CLASS_TYPE);
        writeClassAction.add(
            new If(
                neq(new Invoke(classInfo, "getCls", CLASS_TYPE), clsExpr),
                new Assign(
                    classInfo,
                    inlineInvoke(typeResolverRef, "getTypeInfo", classInfoTypeRef, clsExpr))));
        // Note: writeClassExpr is thread safe.
        writeClassAction.add(
            typeResolver(r -> r.writeClassExpr(typeResolverRef, buffer, classInfo)));
        writeClassAction.add(
            new Return(invokeInline(classInfo, "getSerializer", MAP_SERIALIZER_TYPE)));
        // Spit this into a separate method to avoid method too big to inline.
        serializer =
            invokeGenerated(
                ctx, ofHashSet(buffer, map), writeClassAction, "writeMapTypeInfo", false);
      }
    } else if (!MapLikeSerializer.class.isAssignableFrom(serializer.type().getRawType())) {
      serializer = cast(serializer, TypeRef.of(MapLikeSerializer.class), "mapSerializer");
    }
    Expression ifExpr =
        new If(
            inlineInvoke(serializer, "supportCodegenHook", PRIMITIVE_BOOLEAN_TYPE),
            jitWriteMap(buffer, map, serializer, typeRef),
            new Invoke(serializer, writeMethodName, buffer, map));
    // Wrap map and ifExpr in a ListExpression to ensure map is evaluated before the If.
    // This is necessary because 'map' is used in both branches of the If expression.
    // Without this, the code generator would assign map to a variable inside the then-branch,
    // and then try to use that variable name in the else-branch where it's out of scope.
    Expression write = new ListExpression(map, ifExpr);
    if (generateNewMethod) {
      return invokeGenerated(ctx, ofHashSet(buffer, map, serializer), write, "writeMap", false);
    }
    return write;
  }

  private Tuple2<TypeRef<?>, TypeRef<?>> getMapKeyValueType(TypeRef<?> typeRef) {
    Tuple2<TypeRef<?>, TypeRef<?>> keyValueType = TypeUtils.getMapKeyValueType(typeRef);
    TypeRef<?> keyType = keyValueType.f0;
    TypeRef<?> valueType = keyValueType.f1;
    if (keyType.equals(typeRef)) {
      keyType = OBJECT_TYPE;
    }
    if (valueType.equals(typeRef)) {
      valueType = OBJECT_TYPE;
    }
    return Tuple2.of(keyType, valueType);
  }

  private Expression jitWriteMap(
      Expression buffer, Expression map, Expression serializer, TypeRef<?> typeRef) {
    Tuple2<TypeRef<?>, TypeRef<?>> keyValueType = getMapKeyValueType(typeRef);
    TypeRef<?> keyType = keyValueType.f0;
    TypeRef<?> valueType = keyValueType.f1;
    map = new Invoke(serializer, "onMapWrite", TypeUtils.mapOf(keyType, valueType), buffer, map);
    Expression iterator =
        new Invoke(inlineInvoke(map, "entrySet", SET_TYPE), "iterator", ITERATOR_TYPE);
    Expression entry = cast(inlineInvoke(iterator, "next", OBJECT_TYPE), MAP_ENTRY_TYPE, "entry");
    boolean keyMonomorphic = isMonomorphic(keyType);
    boolean valueMonomorphic = isMonomorphic(valueType);
    boolean inline = keyMonomorphic && valueMonomorphic;
    Class<?> keyTypeRawType = keyType.getRawType();
    Class<?> valueTypeRawType = valueType.getRawType();
    boolean trackingKeyRef = needWriteRef(keyType);
    boolean trackingValueRef = needWriteRef(valueType);
    Tuple2<Expression, Expression> mapKVSerializer =
        getMapKVSerializer(keyTypeRawType, valueTypeRawType);
    Expression keySerializer = mapKVSerializer.f0;
    Expression valueSerializer = mapKVSerializer.f1;
    While whileAction =
        new While(
            neqNull(entry),
            () -> {
              boolean hasGenerics =
                  keyTypeRawType != Object.class || valueTypeRawType != Object.class;
              String method = hasGenerics ? "writeJavaNullChunkGeneric" : "writeJavaNullChunk";
              GenericType keyGenericType = typeResolver(r -> r.buildGenericType(keyType));
              GenericType valueGenericType = typeResolver(r -> r.buildGenericType(valueType));
              if (keyGenericType.hasGenericParameters()
                  || valueGenericType.hasGenericParameters()) {
                method = "writeJavaNullChunkGeneric";
              } else if (keyMonomorphic && valueMonomorphic) {
                if (!trackingKeyRef && !trackingValueRef) {
                  method = "writeNullChunkKVFinalNoRef";
                } else {
                  method = "writeJavaNullChunk";
                }
              }
              Expression writeNullChunk;
              if (method.equals("writeJavaNullChunkGeneric")) {
                writeNullChunk =
                    inlineInvoke(
                        serializer,
                        method,
                        MAP_ENTRY_TYPE,
                        buffer,
                        entry,
                        iterator,
                        getGenericTypeField(keyType),
                        getGenericTypeField(valueType));
              } else {
                writeNullChunk =
                    inlineInvoke(
                        serializer,
                        method,
                        MAP_ENTRY_TYPE,
                        buffer,
                        entry,
                        iterator,
                        keySerializer,
                        valueSerializer);
              }
              Expression writeChunk = writeChunk(buffer, entry, iterator, keyType, valueType);
              return new ListExpression(
                  new Assign(entry, writeNullChunk),
                  new If(
                      neqNull(entry), inline ? writeChunk : new Assign(entry, inline(writeChunk))));
            });
    return new ListExpression(
        new If(not(inlineInvoke(map, "isEmpty", PRIMITIVE_BOOLEAN_TYPE)), whileAction),
        new Invoke(serializer, "onMapWriteFinish", map));
  }

  private Tuple2<Expression, Expression> getMapKVSerializer(Class<?> keyType, Class<?> valueType) {
    Expression keySerializer, valueSerializer;
    boolean keyMonomorphic = isMonomorphic(keyType);
    boolean valueMonomorphic = isMonomorphic(valueType);
    if (keyMonomorphic && valueMonomorphic) {
      keySerializer = getOrCreateSerializer(keyType);
      valueSerializer = getOrCreateSerializer(valueType);
    } else if (keyMonomorphic) {
      keySerializer = getOrCreateSerializer(keyType);
      valueSerializer = nullValue(SERIALIZER_TYPE);
    } else if (valueMonomorphic) {
      keySerializer = nullValue(SERIALIZER_TYPE);
      valueSerializer = getOrCreateSerializer(valueType);
    } else {
      keySerializer = nullValue(SERIALIZER_TYPE);
      valueSerializer = nullValue(SERIALIZER_TYPE);
    }
    return Tuple2.of(keySerializer, valueSerializer);
  }

  protected Expression writeChunk(
      Expression buffer,
      Expression entry,
      Expression iterator,
      TypeRef<?> keyType,
      TypeRef<?> valueType) {
    ListExpression expressions = new ListExpression();
    boolean keyMonomorphic = isMonomorphic(keyType);
    boolean valueMonomorphic = isMonomorphic(valueType);
    Class<?> keyTypeRawType = keyType.getRawType();
    Class<?> valueTypeRawType = valueType.getRawType();
    Expression key =
        keyMonomorphic ? new Variable("key", keyType) : invoke(entry, "getKey", "key", keyType);
    Expression value =
        valueMonomorphic
            ? new Variable("value", valueType)
            : invoke(entry, "getValue", "value", valueType);
    Expression keyTypeExpr =
        keyMonomorphic
            ? getClassExpr(keyTypeRawType)
            : new Invoke(key, "getClass", "keyType", CLASS_TYPE);
    Expression valueTypeExpr =
        valueMonomorphic
            ? getClassExpr(valueTypeRawType)
            : new Invoke(value, "getClass", "valueType", CLASS_TYPE);
    Expression writePlaceHolder = new Invoke(buffer, "writeInt16", Literal.ofShort((short) -1));
    Expression chunkSizeOffset =
        subtract(
            inlineInvoke(buffer, "writerIndex", PRIMITIVE_INT_TYPE), ofInt(1), "chunkSizeOffset");
    expressions.add(
        key,
        value,
        keyTypeExpr,
        valueTypeExpr,
        writePlaceHolder,
        chunkSizeOffset,
        writePlaceHolder,
        chunkSizeOffset);

    Expression chunkHeader;
    Expression keySerializer, valueSerializer;
    boolean trackingKeyRef = needWriteRef(keyType);
    boolean trackingValueRef = needWriteRef(valueType);
    Expression keyWriteRef = Literal.ofBoolean(trackingKeyRef);
    Expression valueWriteRef = Literal.ofBoolean(trackingValueRef);
    boolean inline = keyMonomorphic && valueMonomorphic;
    if (keyMonomorphic && valueMonomorphic) {
      keySerializer = getOrCreateSerializer(keyTypeRawType);
      valueSerializer = getOrCreateSerializer(valueTypeRawType);
      int header = KEY_DECL_TYPE | VALUE_DECL_TYPE;
      if (trackingKeyRef) {
        header |= TRACKING_KEY_REF;
      }
      if (trackingValueRef) {
        header |= TRACKING_VALUE_REF;
      }
      chunkHeader = ofInt(header);
      expressions.add(chunkHeader);
    } else if (keyMonomorphic) {
      int header = KEY_DECL_TYPE;
      if (trackingKeyRef) {
        header |= TRACKING_KEY_REF;
      }
      keySerializer = getOrCreateSerializer(keyTypeRawType);
      walkPath.add("value:" + valueType);
      valueSerializer = writeTypeInfo(buffer, valueTypeExpr, valueTypeRawType, true);
      walkPath.removeLast();
      chunkHeader = ExpressionUtils.ofInt("chunkHeader", header);
      expressions.add(chunkHeader);
      if (trackingValueRef) {
        // value type may be subclass and not track ref.
        valueWriteRef =
            new Invoke(valueSerializer, "needToWriteRef", "valueWriteRef", PRIMITIVE_BOOLEAN_TYPE);
        expressions.add(
            new If(
                valueWriteRef,
                new Assign(chunkHeader, bitor(chunkHeader, ofInt(TRACKING_VALUE_REF)))));
      }
    } else if (valueMonomorphic) {
      walkPath.add("key:" + keyType);
      keySerializer = writeTypeInfo(buffer, keyTypeExpr, keyTypeRawType, true);
      walkPath.removeLast();
      valueSerializer = getOrCreateSerializer(valueTypeRawType);
      int header = VALUE_DECL_TYPE;
      if (trackingValueRef) {
        header |= TRACKING_VALUE_REF;
      }
      chunkHeader = ExpressionUtils.ofInt("chunkHeader", header);
      expressions.add(chunkHeader);
      if (trackingKeyRef) {
        // key type may be subclass and not track ref.
        keyWriteRef =
            new Invoke(keySerializer, "needToWriteRef", "keyWriteRef", PRIMITIVE_BOOLEAN_TYPE);
        expressions.add(
            new If(
                keyWriteRef, new Assign(chunkHeader, bitor(chunkHeader, ofInt(TRACKING_KEY_REF)))));
      }
    } else {
      walkPath.add("key:" + keyType);
      keySerializer = writeTypeInfo(buffer, keyTypeExpr, keyTypeRawType, true);
      walkPath.removeLast();
      walkPath.add("value:" + valueType);
      valueSerializer = writeTypeInfo(buffer, valueTypeExpr, valueTypeRawType, true);
      walkPath.removeLast();
      chunkHeader = ExpressionUtils.ofInt("chunkHeader", 0);
      expressions.add(chunkHeader);
      if (trackingKeyRef) {
        // key type may be subclass and not track ref.
        keyWriteRef =
            new Invoke(keySerializer, "needToWriteRef", "keyWriteRef", PRIMITIVE_BOOLEAN_TYPE);
        expressions.add(
            new If(
                keyWriteRef, new Assign(chunkHeader, bitor(chunkHeader, ofInt(TRACKING_KEY_REF)))));
      }
      if (trackingValueRef) {
        // key type may be subclass and not track ref.
        valueWriteRef =
            new Invoke(valueSerializer, "needToWriteRef", "valueWriteRef", PRIMITIVE_BOOLEAN_TYPE);
        expressions.add(
            new If(
                valueWriteRef,
                new Assign(chunkHeader, bitor(chunkHeader, ofInt(TRACKING_VALUE_REF)))));
      }
    }
    Expression chunkSize = ExpressionUtils.ofInt("chunkSize", 0);
    expressions.add(
        keySerializer,
        valueSerializer,
        keyWriteRef,
        valueWriteRef,
        new Invoke(buffer, "putByte", subtract(chunkSizeOffset, ofInt(1)), chunkHeader),
        chunkSize);
    Expression keyWriteRefExpr = keyWriteRef;
    Expression valueWriteRefExpr = valueWriteRef;
    While writeLoop =
        new While(
            Literal.ofBoolean(true),
            () -> {
              Expression keyAssign = new Assign(key, invokeInline(entry, "getKey", keyType));
              Expression valueAssign =
                  new Assign(value, invokeInline(entry, "getValue", valueType));
              Expression breakCondition;
              if (keyMonomorphic && valueMonomorphic) {
                breakCondition = or(eqNull(key), eqNull(value));
              } else if (keyMonomorphic) {
                breakCondition =
                    or(
                        eqNull(key),
                        eqNull(value),
                        neq(inlineInvoke(value, "getClass", CLASS_TYPE), valueTypeExpr));
              } else if (valueMonomorphic) {
                breakCondition =
                    or(
                        eqNull(key),
                        eqNull(value),
                        neq(inlineInvoke(key, "getClass", CLASS_TYPE), keyTypeExpr));
              } else {
                breakCondition =
                    or(
                        eqNull(key),
                        eqNull(value),
                        neq(inlineInvoke(key, "getClass", CLASS_TYPE), keyTypeExpr),
                        neq(inlineInvoke(value, "getClass", CLASS_TYPE), valueTypeExpr));
              }
              Expression writeKey = serializeForNotNull(key, buffer, keyType, keySerializer);
              if (trackingKeyRef) {
                writeKey =
                    new If(
                        or(
                            not(keyWriteRefExpr),
                            not(
                                inlineInvoke(
                                    refResolverRef,
                                    "writeRefOrNull",
                                    PRIMITIVE_BOOLEAN_TYPE,
                                    buffer,
                                    key))),
                        writeKey);
              }
              Expression writeValue =
                  serializeForNotNull(value, buffer, valueType, valueSerializer);
              if (trackingValueRef) {
                writeValue =
                    new If(
                        or(
                            not(valueWriteRefExpr),
                            not(
                                inlineInvoke(
                                    refResolverRef,
                                    "writeRefOrNull",
                                    PRIMITIVE_BOOLEAN_TYPE,
                                    buffer,
                                    value))),
                        writeValue);
              }
              return new ListExpression(
                  keyAssign,
                  valueAssign,
                  new If(breakCondition, new Break()),
                  writeKey,
                  writeValue,
                  new Assign(chunkSize, add(chunkSize, ofInt(1))),
                  new If(
                      inlineInvoke(iterator, "hasNext", PRIMITIVE_BOOLEAN_TYPE),
                      new Assign(
                          entry, cast(inlineInvoke(iterator, "next", OBJECT_TYPE), MAP_ENTRY_TYPE)),
                      list(new Assign(entry, new Literal(null, MAP_ENTRY_TYPE)), new Break())),
                  new If(eq(chunkSize, ofInt(MAX_CHUNK_SIZE)), new Break()));
            });
    expressions.add(writeLoop, new Invoke(buffer, "putByte", chunkSizeOffset, chunkSize));
    if (!inline) {
      expressions.add(new Return(entry));
      // method too big, spilt it into a new method.
      // Generate similar signature as `MapLikeSerializer.writeJavaChunk`(
      //   MemoryBuffer buffer,
      //   Entry<Object, Object> entry,
      //   Iterator<Entry<Object, Object>> iterator,
      //   Serializer keySerializer,
      //   Serializer valueSerializer
      //  )
      Set<Expression> params = ofHashSet(buffer, entry, iterator);
      return invokeGenerated(ctx, params, expressions, "writeChunk", false);
    }
    return expressions;
  }

  protected Expression tryPreserveRefId(Expression buffer) {
    return new Invoke(
        refResolverRef, "tryPreserveRefId", "refId", PRIMITIVE_INT_TYPE, false, buffer);
  }

  /**
   * Returns an expression that deserialize a nullable <code>inputObject</code> from <code>buffer
   * </code>.
   *
   * @param callback is used to consume the deserialized value to avoid an extra condition branch.
   */
  protected Expression deserializeFor(
      Expression buffer,
      TypeRef<?> typeRef,
      Function<Expression, Expression> callback,
      InvokeHint invokeHint) {
    if (typeResolver(r -> r.needToWriteRef(typeRef))) {
      return readRef(buffer, callback, () -> deserializeForNotNull(buffer, typeRef, invokeHint));
    } else {
      if (typeRef.isPrimitive()) {
        Expression value = deserializeForNotNull(buffer, typeRef, invokeHint);
        // Should put value expr ahead to avoid generated code in wrong scope.
        return new ListExpression(value, callback.apply(value));
      }
      return readNullableField(
          buffer, typeRef, callback, () -> deserializeForNotNull(buffer, typeRef, invokeHint));
    }
  }

  protected Expression deserializeForNullableField(
      Expression buffer,
      Descriptor descriptor,
      Function<Expression, Expression> callback,
      boolean nullable) {
    TypeRef<?> typeRef = descriptor.getTypeRef();
    if (typeResolver(r -> r.needToWriteRef(typeRef))) {
      return readRef(
          buffer, callback, () -> deserializeForNotNullForField(buffer, descriptor, null));
    } else {
      if (typeRef.isPrimitive() && !nullable) {
        // Only skip null check if BOTH: local type is primitive AND sender didn't write null flag
        Expression value = deserializeForNotNull(buffer, typeRef, null);
        // Should put value expr ahead to avoid generated code in wrong scope.
        return new ListExpression(value, callback.apply(value));
      }
      // Pass local field type so readNullable can use default value for primitives when null
      Class<?> localFieldType = typeRef.isPrimitive() ? typeRef.getRawType() : null;
      return readNullableField(
          buffer,
          descriptor,
          callback,
          () -> deserializeForNotNull(buffer, typeRef, null),
          nullable);
    }
  }

  private Expression readRef(
      Expression buffer,
      Function<Expression, Expression> callback,
      Supplier<Expression> deserializeForNotNull) {
    Expression refId = tryPreserveRefId(buffer);
    // indicates that the object is first read.
    Expression needDeserialize =
        ExpressionUtils.egt(refId, new Literal(Fory.NOT_NULL_VALUE_FLAG, PRIMITIVE_BYTE_TYPE));
    Expression deserializedValue = deserializeForNotNull.get();
    Expression setReadObject =
        new Invoke(refResolverRef, "setReadObject", refId, deserializedValue);
    Expression readValue = inlineInvoke(refResolverRef, "getReadObject", OBJECT_TYPE, false);
    // use false to ignore null
    return new If(
        needDeserialize,
        callback.apply(
            new ListExpression(refId, deserializedValue, setReadObject, deserializedValue)),
        callback.apply(readValue),
        false);
  }

  private Expression readNullableField(
      Expression buffer,
      TypeRef<?> typeRef,
      Function<Expression, Expression> callback,
      Supplier<Expression> deserializeForNotNull) {
    Expression notNull =
        neq(
            inlineInvoke(buffer, "readByte", PRIMITIVE_BYTE_TYPE),
            new Literal(Fory.NULL_FLAG, PRIMITIVE_BYTE_TYPE));
    Expression value = deserializeForNotNull.get();
    // use false to ignore null.
    return new If(notNull, callback.apply(value), callback.apply(nullValue(typeRef)), false);
  }

  private Expression readNullableField(
      Expression buffer,
      Descriptor descriptor,
      Function<Expression, Expression> callback,
      Supplier<Expression> deserializeForNotNull,
      boolean nullable) {
    if (nullable) {
      Expression notNull =
          neq(
              inlineInvoke(buffer, "readByte", PRIMITIVE_BYTE_TYPE),
              Literal.ofByte(Fory.NULL_FLAG));
      Expression value = deserializeForNotNull.get();
      // When local field is primitive but remote was nullable (boxed), use default value
      // instead of null. This handles compatibility between boxed/primitive field types.
      Expression nullExpr =
          nullValue(
              descriptor.getField() != null
                  ? descriptor.getField().getType()
                  : descriptor.getRawType());
      // use false to ignore null.
      return new If(notNull, callback.apply(value), callback.apply(nullExpr), false);
    } else {
      Expression value = deserializeForNotNull.get();
      return callback.apply(value);
    }
  }

  private Expression deserializeRef(
      Expression buffer, TypeRef<?> typeRef, Expression serializer, InvokeHint invokeHint) {
    Class<?> cls = getRawType(typeRef);
    if (isPrimitive(cls) || isBoxed(cls)) {
      throw new IllegalStateException("Primitive type don't track ref: " + typeRef);
    } else {
      if (cls == String.class) {
        return read(stringSerializerRef, buffer, RefMode.TRACKING, STRING_TYPE);
      }
      if (useCollectionSerialization(typeRef)) {
        return readRef(
            buffer,
            Function.identity(),
            () -> deserializeForCollection(buffer, typeRef, serializer, invokeHint));
      } else if (useMapSerialization(typeRef)) {
        return readRef(
            buffer,
            Function.identity(),
            () -> deserializeForMap(buffer, typeRef, serializer, invokeHint));
      } else {
        if (serializer != null) {
          return read(serializer, buffer, RefMode.TRACKING, OBJECT_TYPE);
        }
        if (isMonomorphic(cls)) {
          Expression newSerializer = getOrCreateSerializer(cls);
          Class<?> returnType =
              ReflectionUtils.getReturnType(getRawType(newSerializer.type()), readMethodName);
          return read(newSerializer, buffer, RefMode.TRACKING, TypeRef.of(returnType));
        } else {
          return readRef(
              buffer, Function.identity(), () -> readForNotNullNonFinal(buffer, typeRef, null));
        }
      }
    }
  }

  protected Expression deserializeForNotNull(
      Expression buffer, TypeRef<?> typeRef, InvokeHint invokeHint) {
    return deserializeForNotNull(buffer, typeRef, null, invokeHint);
  }

  /**
   * Return an expression that deserialize an not null <code>inputObject</code> from <code>buffer
   * </code>.
   *
   * @param invokeHint for generate new method to cut off dependencies.
   */
  protected Expression deserializeForNotNull(
      Expression buffer, TypeRef<?> typeRef, Expression serializer, InvokeHint invokeHint) {
    Class<?> cls = getRawType(typeRef);
    if (isPrimitive(cls) || isBoxed(cls)) {
      return deserializePrimitive(buffer, cls);
    } else {
      if (cls == String.class) {
        return fory.getStringSerializer().readStringExpr(stringSerializerRef, buffer);
      }
      Expression obj;
      if (useCollectionSerialization(typeRef)) {
        obj = deserializeForCollection(buffer, typeRef, serializer, invokeHint);
      } else if (useMapSerialization(typeRef)) {
        obj = deserializeForMap(buffer, typeRef, serializer, invokeHint);
      } else {
        if (serializer != null) {
          return read(serializer, buffer, OBJECT_TYPE);
        }
        if (isMonomorphic(cls)) {
          serializer = getOrCreateSerializer(cls);
          Class<?> returnType =
              ReflectionUtils.getReturnType(getRawType(serializer.type()), readMethodName);
          obj = read(serializer, buffer, TypeRef.of(returnType));
        } else {
          obj = readForNotNullNonFinal(buffer, typeRef, serializer);
        }
      }
      return obj;
    }
  }

  private Expression deserializeForNotNullNoRef(
      Expression buffer, TypeRef<?> typeRef, Expression serializer, InvokeHint invokeHint) {
    Expression value = deserializeForNotNull(buffer, typeRef, serializer, invokeHint);
    if (needWriteRef(TypeRef.of(typeRef.getRawType()))) {
      Expression preserveStubRefId =
          new Invoke(refResolverRef, "preserveRefId", new Literal(-1, PRIMITIVE_INT_TYPE));
      return new ListExpression(preserveStubRefId, value);
    }
    return value;
  }

  protected Expression deserializeField(
      Expression buffer, Descriptor descriptor, Function<Expression, Expression> callback) {
    TypeRef<?> typeRef = descriptor.getTypeRef();
    boolean nullable = descriptor.isNullable();
    // descriptor.isTrackingRef() already includes the needWriteRef check
    boolean useRefTracking = descriptor.isTrackingRef();
    // Check if the TYPE normally needs ref tracking, ignoring field-level metadata.
    // When global ref tracking is enabled, serializers call reference() at the end.
    // If field has trackingRef=false but the type's serializer calls reference(),
    // we need to push a stub -1 so reference() can pop it and skip setReadObject.
    // Use raw type without metadata to check type-level ref tracking.
    boolean serializerCallsReference = needWriteRef(TypeRef.of(typeRef.getRawType()));

    if (useRefTracking) {
      return readRef(
          buffer, callback, () -> deserializeForNotNullForField(buffer, descriptor, null));
    } else {
      if (!nullable) {
        Expression value = deserializeForNotNullForField(buffer, descriptor, null);

        if (serializerCallsReference) {
          // When a field explicitly disables ref tracking (@ForyField(trackingRef=false))
          // but global ref tracking is enabled, the serializer will call reference().
          // We need to preserve a -1 id so that when the deserializer calls reference(),
          // it will pop this -1 and skip the setReadObject call.
          Expression preserveStubRefId =
              new Invoke(refResolverRef, "preserveRefId", new Literal(-1, PRIMITIVE_INT_TYPE));
          return new ListExpression(preserveStubRefId, value, callback.apply(value));
        }
        return new ListExpression(value, callback.apply(value));
      }

      // Get local field type to handle primitive/boxed compatibility
      java.lang.reflect.Field field = descriptor.getField();
      Class<?> localFieldType = field != null ? field.getType() : null;
      Expression readNullableExpr =
          readNullableField(
              buffer,
              descriptor,
              callback,
              () -> deserializeForNotNullForField(buffer, descriptor, null),
              true);

      if (serializerCallsReference) {
        Expression preserveStubRefId =
            new Invoke(refResolverRef, "preserveRefId", new Literal(-1, PRIMITIVE_INT_TYPE));
        return new ListExpression(preserveStubRefId, readNullableExpr);
      }
      return readNullableExpr;
    }
  }

  private Expression deserializeForNotNullForField(
      Expression buffer, Descriptor descriptor, Expression serializer) {
    TypeRef<?> typeRef = descriptor.getTypeRef();
    Class<?> cls = getRawType(typeRef);
    if (isPrimitive(cls) || isBoxed(cls)) {
      return deserializePrimitiveField(buffer, descriptor);
    } else {
      if (cls == String.class) {
        return fory.getStringSerializer().readStringExpr(stringSerializerRef, buffer);
      }
      Expression obj;
      if (useCollectionSerialization(typeRef)) {
        obj = deserializeForCollection(buffer, typeRef, serializer, null);
      } else if (useMapSerialization(typeRef)) {
        obj = deserializeForMap(buffer, typeRef, serializer, null);
      } else {
        if (serializer != null) {
          return read(serializer, buffer, OBJECT_TYPE);
        }
        if (isMonomorphic(descriptor)) {
          // Use descriptor to get the appropriate serializer
          serializer = getSerializerForField(cls);
          Class<?> returnType =
              ReflectionUtils.getReturnType(getRawType(serializer.type()), readMethodName);
          obj = read(serializer, buffer, TypeRef.of(returnType));
        } else {
          obj = readForNotNullNonFinal(buffer, typeRef, serializer);
        }
      }
      return obj;
    }
  }

  private Expression deserializePrimitiveField(Expression buffer, Descriptor descriptor) {
    int dispatchId = getNumericDescriptorDispatchId(descriptor);
    boolean isPrimitive = descriptor.getRawType().isPrimitive();
    switch (dispatchId) {
      case DispatchId.BOOL:
        return new Invoke(
            buffer, "readBoolean", isPrimitive ? PRIMITIVE_BOOLEAN_TYPE : BOOLEAN_TYPE);
      case DispatchId.INT8:
      case DispatchId.UINT8:
        return new Invoke(buffer, "readByte", isPrimitive ? PRIMITIVE_BYTE_TYPE : BYTE_TYPE);
      case DispatchId.CHAR:
        return isPrimitive ? readChar(buffer) : new Invoke(buffer, "readChar", CHAR_TYPE);
      case DispatchId.INT16:
      case DispatchId.UINT16:
        return isPrimitive ? readInt16(buffer) : new Invoke(buffer, readInt16Func(), SHORT_TYPE);
      case DispatchId.INT32:
      case DispatchId.UINT32:
        return isPrimitive ? readInt32(buffer) : new Invoke(buffer, readIntFunc(), INT_TYPE);
      case DispatchId.VARINT32:
        return isPrimitive
            ? readVarInt32(buffer)
            : new Invoke(buffer, readVarInt32Func(), INT_TYPE);
      case DispatchId.VAR_UINT32:
        return new Invoke(buffer, "readVarUint32", isPrimitive ? PRIMITIVE_INT_TYPE : INT_TYPE);
      case DispatchId.INT64:
      case DispatchId.UINT64:
        return isPrimitive ? readInt64(buffer) : new Invoke(buffer, readLongFunc(), LONG_TYPE);
      case DispatchId.VARINT64:
        return new Invoke(buffer, "readVarInt64", isPrimitive ? PRIMITIVE_LONG_TYPE : LONG_TYPE);
      case DispatchId.TAGGED_INT64:
        return new Invoke(buffer, "readTaggedInt64", isPrimitive ? PRIMITIVE_LONG_TYPE : LONG_TYPE);
      case DispatchId.VAR_UINT64:
        return new Invoke(buffer, "readVarUint64", isPrimitive ? PRIMITIVE_LONG_TYPE : LONG_TYPE);
      case DispatchId.TAGGED_UINT64:
        return new Invoke(
            buffer, "readTaggedUint64", isPrimitive ? PRIMITIVE_LONG_TYPE : LONG_TYPE);
      case DispatchId.FLOAT32:
        return isPrimitive
            ? readFloat32(buffer)
            : new Invoke(buffer, readFloat32Func(), FLOAT_TYPE);
      case DispatchId.FLOAT64:
        return isPrimitive
            ? readFloat64(buffer)
            : new Invoke(buffer, readFloat64Func(), DOUBLE_TYPE);
      default:
        throw new IllegalStateException("Unsupported dispatchId: " + dispatchId);
    }
  }

  private Expression deserializePrimitive(Expression buffer, Class<?> cls) {
    // for primitive, inline call here to avoid java boxing
    if (cls == byte.class || cls == Byte.class) {
      return new Invoke(buffer, "readByte", PRIMITIVE_BYTE_TYPE);
    } else if (cls == boolean.class || cls == Boolean.class) {
      return new Invoke(buffer, "readBoolean", PRIMITIVE_BOOLEAN_TYPE);
    } else if (cls == char.class || cls == Character.class) {
      return readChar(buffer);
    } else if (cls == short.class || cls == Short.class) {
      return readInt16(buffer);
    } else if (cls == int.class || cls == Integer.class) {
      return fory.compressInt() ? readVarInt32(buffer) : readInt32(buffer);
    } else if (cls == long.class || cls == Long.class) {
      return LongSerializer.readInt64(buffer, fory.longEncoding());
    } else if (cls == float.class || cls == Float.class) {
      return readFloat32(buffer);
    } else if (cls == double.class || cls == Double.class) {
      return readFloat64(buffer);
    } else {
      throw new IllegalStateException("impossible");
    }
  }

  protected Expression read(Expression serializer, Expression buffer, TypeRef<?> returnType) {
    return read(serializer, buffer, RefMode.NONE, returnType);
  }

  protected Expression read(
      Expression serializer, Expression buffer, RefMode refMode, TypeRef<?> returnType) {
    Class<?> type = returnType.getRawType();
    Expression read;
    if (refMode == RefMode.NONE) {
      read = new Invoke(serializer, readMethodName, returnType, buffer);
    } else {
      // janino take inherited generic method return type as return type of erased `T`.
      read = new Invoke(serializer, readMethodName, OBJECT_TYPE, buffer, ofEnum(refMode));
      read = cast(inline(read), returnType);
    }
    if (ReflectionUtils.isMonomorphic(type) && !TypeUtils.hasExpandableLeafs(type)) {
      return read;
    }
    read = uninline(read);
    return new ListExpression(
        new Invoke(foryRef, "incReadDepth"), read, new Invoke(foryRef, "decDepth"), read);
  }

  protected Expression readForNotNullNonFinal(
      Expression buffer, TypeRef<?> typeRef, Expression serializer) {
    if (serializer == null) {
      Expression classInfo;
      Class<?> rawType = typeRef.getRawType();
      if (fory.isCompatible() && rawType != Object.class) {
        TypeInfo typeInfo = typeResolver(r -> r.getTypeInfo(rawType, false));
        if (typeInfo == null
            || (typeInfo.getTypeId() == Types.COMPATIBLE_STRUCT
                || typeInfo.getTypeId() == Types.NAMED_COMPATIBLE_STRUCT)) {
          String name = ctx.newName(StringUtils.uncapitalize(rawType.getSimpleName()) + "Class");
          Expression clsExpr = staticClassFieldExpr(rawType, name);
          classInfo =
              inlineInvoke(typeResolverRef, "readTypeInfo", classInfoTypeRef, buffer, clsExpr);
        } else {
          classInfo = readTypeInfo(getRawType(typeRef), buffer);
        }
      } else {
        classInfo = readTypeInfo(getRawType(typeRef), buffer);
      }
      serializer = inlineInvoke(classInfo, "getSerializer", SERIALIZER_TYPE);
    }
    return read(serializer, buffer, OBJECT_TYPE);
  }

  /**
   * Return an expression to deserialize a collection from <code>buffer</code>. Must keep consistent
   * with {@link BaseObjectCodecBuilder#serializeForCollection}
   */
  protected Expression deserializeForCollection(
      Expression buffer, TypeRef<?> typeRef, Expression serializer, InvokeHint invokeHint) {
    TypeRef<?> elementType = getElementType(typeRef);
    if (serializer == null) {
      Class<?> cls = getRawType(typeRef);
      if (isMonomorphic(cls)) {
        serializer = getOrCreateSerializer(cls);
      } else {
        Expression classInfo = readTypeInfo(cls, buffer);
        serializer =
            invoke(classInfo, "getSerializer", "collectionSerializer", COLLECTION_SERIALIZER_TYPE);
      }
    } else {
      checkArgument(
          CollectionLikeSerializer.class.isAssignableFrom(serializer.type().getRawType()),
          "Expected CollectionLikeSerializer but got %s",
          serializer.type());
    }
    Invoke supportHook = inlineInvoke(serializer, "supportCodegenHook", PRIMITIVE_BOOLEAN_TYPE);
    Expression collection = new Invoke(serializer, "newCollection", COLLECTION_TYPE, buffer);
    Expression size = new Invoke(serializer, "getAndClearNumElements", "size", PRIMITIVE_INT_TYPE);
    // if add branch by `ArrayList`, generated code will be > 325 bytes.
    // and List#add is more likely be inlined if there is only one subclass.
    Expression hookRead = readCollectionCodegen(buffer, collection, size, elementType);
    hookRead = new Invoke(serializer, "onCollectionRead", OBJECT_TYPE, hookRead);
    Expression action =
        new If(
            supportHook,
            new ListExpression(collection, hookRead),
            read(serializer, buffer, OBJECT_TYPE),
            false);
    if (invokeHint != null && invokeHint.genNewMethod) {
      invokeHint.add(buffer);
      return invokeGenerated(
          ctx,
          invokeHint.cutPoints,
          new ListExpression(action, new Return(action)),
          "readCollection",
          false);
    }
    return action;
  }

  protected Expression readCollectionCodegen(
      Expression buffer, Expression collection, Expression size, TypeRef<?> elementType) {
    ListExpression builder = new ListExpression();
    Invoke flags = new Invoke(buffer, "readByte", "flags", PRIMITIVE_INT_TYPE, false);
    builder.add(flags);
    Class<?> elemClass = TypeUtils.getRawType(elementType);
    walkPath.add(elementType.toString());
    boolean finalType = isMonomorphic(elemClass);
    boolean trackingRef = fory.trackingRef() && !(isPrimitive(elemClass) || isBoxed(elemClass));
    Literal trackingRefFlag = ofInt(CollectionFlags.TRACKING_REF);
    Expression trackRef = eq(new BitAnd(flags, trackingRefFlag), trackingRefFlag, "trackRef");
    if (finalType) {
      Literal hasNullFlag = ofInt(CollectionFlags.HAS_NULL);
      Expression hasNull = eq(new BitAnd(flags, hasNullFlag), hasNullFlag, "hasNull");
      if (trackingRef) {
        builder.add(
            trackRef,
            new If(
                trackRef,
                readContainerElements(elementType, true, null, null, buffer, collection, size),
                readContainerElements(elementType, false, null, hasNull, buffer, collection, size),
                false));
      } else {
        builder.add(
            hasNull,
            readContainerElements(elementType, false, null, hasNull, buffer, collection, size));
      }
    } else {
      Literal isSameTypeFlag = ofInt(CollectionFlags.IS_SAME_TYPE);
      Expression sameElementClass =
          eq(new BitAnd(flags, isSameTypeFlag), isSameTypeFlag, "sameElementClass");
      //  if ((flags & Flags.NOT_DECL_ELEMENT_TYPE) == Flags.NOT_DECL_ELEMENT_TYPE)
      Literal isDeclTypeFlag = ofInt(CollectionFlags.IS_DECL_ELEMENT_TYPE);
      Expression isDeclType = eq(new BitAnd(flags, isDeclTypeFlag), isDeclTypeFlag);
      Invoke serializer =
          inlineInvoke(readTypeInfo(elemClass, buffer), "getSerializer", SERIALIZER_TYPE);
      TypeRef<?> serializerType = getSerializerType(elementType);
      Expression elemSerializer; // make it in scope of `if(sameElementClass)`
      boolean maybeDecl = typeResolver(r -> r.isSerializable(elemClass));
      if (maybeDecl) {
        elemSerializer =
            new If(
                isDeclType,
                cast(getOrCreateSerializer(elemClass), serializerType),
                cast(serializer.inline(), serializerType),
                false,
                serializerType);
      } else {
        elemSerializer = cast(serializer.inline(), serializerType);
      }
      elemSerializer = uninline(elemSerializer);
      builder.add(sameElementClass);
      Expression action;
      if (trackingRef) {
        Literal hasNullFlag = ofInt(CollectionFlags.HAS_NULL);
        Expression hasNull = eq(new BitAnd(flags, hasNullFlag), hasNullFlag, "hasNull");
        builder.add(hasNull);
        Set<Expression> cutPoint = ofHashSet(buffer, collection, size);
        Expression differentElemTypeRead =
            invokeGenerated(
                ctx,
                cutPoint,
                readContainerElements(elementType, true, null, null, buffer, collection, size),
                "differentTypeElemsRead",
                false);
        Set<Expression> noRefCutPoint = ofHashSet(buffer, collection, size, hasNull);
        Expression noRefDifferentTypeElemsRead =
            invokeGenerated(
                ctx,
                noRefCutPoint,
                readContainerElements(elementType, false, null, hasNull, buffer, collection, size),
                "differentTypeElemsNoRefRead",
                false);
        ListExpression sameTypeRead = new ListExpression(elemSerializer);
        sameTypeRead.add(
            new If(
                trackRef,
                readContainerElements(
                    elementType, true, elemSerializer, null, buffer, collection, size),
                readContainerElements(
                    elementType, false, elemSerializer, hasNull, buffer, collection, size),
                false));
        Expression diffTypeRead =
            new If(trackRef, differentElemTypeRead, noRefDifferentTypeElemsRead, false);
        builder.add(trackRef);
        action = new If(sameElementClass, sameTypeRead, diffTypeRead);
      } else {
        Literal hasNullFlag = ofInt(CollectionFlags.HAS_NULL);
        Expression hasNull = eq(new BitAnd(flags, hasNullFlag), hasNullFlag, "hasNull");
        builder.add(hasNull);
        // Same element class read start
        ListExpression readBuilder = new ListExpression(elemSerializer);
        readBuilder.add(
            readContainerElements(
                elementType, false, elemSerializer, hasNull, buffer, collection, size));
        // Same element class read end
        Set<Expression> cutPoint = ofHashSet(buffer, collection, size, hasNull);
        Expression differentTypeElemsRead =
            invokeGenerated(
                ctx,
                cutPoint,
                readContainerElements(elementType, false, null, hasNull, buffer, collection, size),
                "differentTypeElemsRead",
                false);
        action = new If(sameElementClass, readBuilder, differentTypeElemsRead);
      }
      builder.add(action);
    }
    walkPath.removeLast();
    // place newCollection as last as expr value
    return new ListExpression(size, collection, new If(gt(size, ofInt(0)), builder), collection);
  }

  private Expression readContainerElements(
      TypeRef<?> elementType,
      boolean trackingRef,
      Expression serializer,
      Expression hasNull,
      Expression buffer,
      Expression collection,
      Expression size) {
    ExprHolder exprHolder =
        ExprHolder.of(
            "collection",
            collection,
            "buffer",
            buffer,
            "hasNull",
            hasNull,
            "serializer",
            serializer);
    Expression start = new Literal(0, PRIMITIVE_INT_TYPE);
    Expression step = new Literal(1, PRIMITIVE_INT_TYPE);
    return new ForLoop(
        start,
        size,
        step,
        i ->
            readContainerElement(
                exprHolder.get("buffer"),
                elementType,
                trackingRef,
                exprHolder.get("hasNull"),
                exprHolder.get("serializer"),
                v -> new Invoke(exprHolder.get("collection"), "add", inline(v))));
  }

  private Expression readContainerElement(
      Expression buffer,
      TypeRef<?> elementType,
      boolean trackingRef,
      Expression hasNull,
      Expression elemSerializer,
      Function<Expression, Expression> callback) {
    boolean genNewMethod =
        useCollectionSerialization(elementType) || useMapSerialization(elementType);
    InvokeHint invokeHint = new InvokeHint(genNewMethod, buffer);
    Class<?> rawType = getRawType(elementType);
    boolean finalType = isMonomorphic(rawType);
    Expression read;
    if (finalType) {
      if (trackingRef) {
        read = callback.apply(deserializeRef(buffer, elementType, null, invokeHint));
      } else {
        invokeHint.add(hasNull);
        read =
            new If(
                hasNull,
                deserializeFor(buffer, elementType, callback, invokeHint.copy()),
                callback.apply(
                    deserializeForNotNullNoRef(buffer, elementType, null, invokeHint.copy())));
      }
    } else {
      invokeHint.add(elemSerializer);
      if (trackingRef) {
        // eager callback, no need to use ExprHolder.
        read = callback.apply(deserializeRef(buffer, elementType, elemSerializer, invokeHint));
      } else {
        invokeHint.add(hasNull);
        read =
            new If(
                hasNull,
                readNullableField(
                    buffer,
                    elementType,
                    callback,
                    () ->
                        deserializeForNotNullNoRef(
                            buffer, elementType, elemSerializer, invokeHint.copy())),
                callback.apply(
                    deserializeForNotNullNoRef(
                        buffer, elementType, elemSerializer, invokeHint.copy())));
      }
    }
    return read;
  }

  /**
   * Return an expression to deserialize a map from <code>buffer</code>. Must keep consistent with
   * {@link BaseObjectCodecBuilder#serializeForMap}
   */
  protected Expression deserializeForMap(
      Expression buffer, TypeRef<?> typeRef, Expression serializer, InvokeHint invokeHint) {
    Tuple2<TypeRef<?>, TypeRef<?>> keyValueType = getMapKeyValueType(typeRef);
    TypeRef<?> keyType = keyValueType.f0;
    TypeRef<?> valueType = keyValueType.f1;
    if (serializer == null) {
      Class<?> cls = getRawType(typeRef);
      if (isMonomorphic(cls)) {
        serializer = getOrCreateSerializer(cls);
      } else {
        Expression classInfo = readTypeInfo(cls, buffer);
        serializer = invoke(classInfo, "getSerializer", "mapSerializer", MAP_SERIALIZER_TYPE);
      }
    } else {
      checkArgument(
          MapLikeSerializer.class.isAssignableFrom(serializer.type().getRawType()),
          "Expected MapLikeSerializer but got %s",
          serializer.type());
    }
    Expression mapSerializer = serializer;
    Invoke supportHook = inlineInvoke(serializer, "supportCodegenHook", PRIMITIVE_BOOLEAN_TYPE);
    ListExpression expressions = new ListExpression();
    Expression newMap = new Invoke(serializer, "newMap", MAP_TYPE, buffer);
    Expression size = new Invoke(serializer, "getAndClearNumElements", "size", PRIMITIVE_INT_TYPE);
    Expression chunkHeader =
        new If(
            eq(size, ofInt(0)),
            ofInt(0),
            inlineInvoke(buffer, "readUnsignedByte", PRIMITIVE_INT_TYPE));
    expressions.add(newMap, size, chunkHeader);
    Class<?> keyCls = keyType.getRawType();
    Class<?> valueCls = valueType.getRawType();
    boolean keyMonomorphic = isMonomorphic(keyCls);
    boolean valueMonomorphic = isMonomorphic(valueCls);
    boolean refKey = needWriteRef(keyType);
    boolean refValue = needWriteRef(valueType);
    boolean inline = keyMonomorphic && valueMonomorphic && (!refKey || !refValue);
    Tuple2<Expression, Expression> mapKVSerializer = getMapKVSerializer(keyCls, valueCls);
    Expression keySerializer = mapKVSerializer.f0;
    Expression valueSerializer = mapKVSerializer.f1;
    While chunksLoop =
        new While(
            gt(size, ofInt(0)),
            () -> {
              ListExpression exprs = new ListExpression();
              String method = "readJavaNullChunk";
              if (keyMonomorphic && valueMonomorphic) {
                if (!refKey && !refValue) {
                  method = "readNullChunkKVFinalNoRef";
                }
              }
              Expression sizeAndHeader =
                  new Invoke(
                      mapSerializer,
                      method,
                      "sizeAndHeader",
                      PRIMITIVE_LONG_TYPE,
                      false,
                      buffer,
                      newMap,
                      chunkHeader,
                      size,
                      keySerializer,
                      valueSerializer);
              exprs.add(
                  new Assign(
                      chunkHeader, cast(bitand(sizeAndHeader, ofInt(0xff)), PRIMITIVE_INT_TYPE)),
                  new Assign(size, cast(shift(">>>", sizeAndHeader, 8), PRIMITIVE_INT_TYPE)));
              exprs.add(new If(eq(size, ofInt(0)), new Break()));
              Expression sizeAndHeader2 =
                  readChunk(buffer, newMap, size, keyType, valueType, chunkHeader);
              if (inline) {
                exprs.add(sizeAndHeader2);
              } else {
                exprs.add(
                    new Assign(
                        chunkHeader, cast(bitand(sizeAndHeader2, ofInt(0xff)), PRIMITIVE_INT_TYPE)),
                    new Assign(size, cast(shift(">>>", sizeAndHeader2, 8), PRIMITIVE_INT_TYPE)));
              }
              return exprs;
            });
    Set<Expression> chunkLoopCutPoints =
        ofHashSet(buffer, newMap, chunkHeader, size, mapSerializer, keySerializer, valueSerializer);
    Expression chunkLoopExpr =
        invokeGenerated(ctx, chunkLoopCutPoints, chunksLoop, "readMapChunks", false);
    expressions.add(chunkLoopExpr, newMap);
    // first newMap to create map, last newMap as expr value
    Expression map = inlineInvoke(serializer, "onMapRead", OBJECT_TYPE, expressions);
    Expression action = new If(supportHook, map, read(serializer, buffer, OBJECT_TYPE), false);
    if (invokeHint != null && invokeHint.genNewMethod) {
      invokeHint.add(buffer);
      invokeHint.add(serializer);
      return invokeGenerated(
          ctx,
          invokeHint.cutPoints,
          new ListExpression(action, new Return(action)),
          "readMap",
          false);
    }
    return action;
  }

  private boolean mayTrackRefForCollectionRead(Class<?> type) {
    if (!fory.trackingRef()) {
      return false;
    }
    if (type.isPrimitive() || isBoxed(type)) {
      return false;
    }
    // 1. for xlang, other language may send serialized string with ref tracking, we skip string ref
    // because string is used commonly, we don't want introduce extra check ref header in data,
    // because the
    // writer is also java, and it won't write string ref if `StringRefIgnored`.
    // 2. we can't use `needWriteRef`, the collection/map read must follow ref track header
    // in serialized data. other language may write ref for elements even `needWriteRef` return
    // false
    if (type == String.class && !fory.isCrossLanguage()) {
      return !fory.getConfig().isStringRefIgnored();
    }
    return true;
  }

  private Expression readChunk(
      Expression buffer,
      Expression map,
      Expression size,
      TypeRef<?> keyType,
      TypeRef<?> valueType,
      Expression chunkHeader) {
    boolean keyMonomorphic = isMonomorphic(keyType);
    boolean valueMonomorphic = isMonomorphic(valueType);
    Class<?> keyTypeRawType = keyType.getRawType();
    Class<?> valueTypeRawType = valueType.getRawType();
    boolean trackingKeyRef = mayTrackRefForCollectionRead(keyTypeRawType);
    boolean trackingValueRef = mayTrackRefForCollectionRead(valueTypeRawType);
    boolean inline =
        keyMonomorphic && valueMonomorphic && (!needWriteRef(keyType) || !needWriteRef(valueType));
    ListExpression expressions = new ListExpression(buffer);
    Expression trackKeyRefRaw = neq(bitand(chunkHeader, ofInt(TRACKING_KEY_REF)), ofInt(0));
    Expression trackValueRefRaw = neq(bitand(chunkHeader, ofInt(TRACKING_VALUE_REF)), ofInt(0));
    Expression trackKeyRef = trackingKeyRef ? uninline(trackKeyRefRaw) : trackKeyRefRaw;
    Expression trackValueRef = trackingValueRef ? uninline(trackValueRefRaw) : trackValueRefRaw;
    Expression keyIsDeclaredType = neq(bitand(chunkHeader, ofInt(KEY_DECL_TYPE)), ofInt(0));
    Expression valueIsDeclaredType = neq(bitand(chunkHeader, ofInt(VALUE_DECL_TYPE)), ofInt(0));
    Expression chunkSize = new Invoke(buffer, "readUnsignedByte", "chunkSize", PRIMITIVE_INT_TYPE);
    expressions.add(chunkSize);
    if (trackingKeyRef) {
      expressions.add(trackKeyRef);
    }
    if (trackingValueRef) {
      expressions.add(trackValueRef);
    }

    Expression keySerializer, valueSerializer;
    if (!keyMonomorphic && !valueMonomorphic) {
      keySerializer = readOrGetSerializerForDeclaredType(buffer, keyTypeRawType, keyIsDeclaredType);
      valueSerializer =
          readOrGetSerializerForDeclaredType(buffer, valueTypeRawType, valueIsDeclaredType);
    } else if (!keyMonomorphic) {
      keySerializer = readOrGetSerializerForDeclaredType(buffer, keyTypeRawType, keyIsDeclaredType);
      valueSerializer = getOrCreateSerializer(valueTypeRawType);
    } else if (!valueMonomorphic) {
      keySerializer = getOrCreateSerializer(keyTypeRawType);
      valueSerializer =
          readOrGetSerializerForDeclaredType(buffer, valueTypeRawType, valueIsDeclaredType);
    } else {
      keySerializer = getOrCreateSerializer(keyTypeRawType);
      valueSerializer = getOrCreateSerializer(valueTypeRawType);
    }
    Expression keySerializerExpr = uninline(keySerializer);
    Expression valueSerializerExpr = uninline(valueSerializer);
    expressions.add(keySerializerExpr, valueSerializerExpr);
    ForLoop readKeyValues =
        new ForLoop(
            ofInt(0),
            chunkSize,
            ofInt(1),
            i -> {
              boolean genKeyMethod =
                  useCollectionSerialization(keyType) || useMapSerialization(keyType);
              boolean genValueMethod =
                  useCollectionSerialization(valueType) || useMapSerialization(valueType);
              walkPath.add("key:" + keyType);
              Expression keyAction, valueAction;
              InvokeHint keyHint = new InvokeHint(genKeyMethod);
              InvokeHint valueHint = new InvokeHint(genValueMethod);
              if (genKeyMethod) {
                keyHint.add(keySerializerExpr);
              }
              if (genValueMethod) {
                valueHint.add(valueSerializerExpr);
              }
              if (trackingKeyRef) {
                keyAction =
                    new If(
                        trackKeyRef,
                        deserializeRef(buffer, keyType, keySerializerExpr, keyHint),
                        deserializeForNotNullNoRef(buffer, keyType, keySerializerExpr, keyHint),
                        false);
              } else {
                keyAction = deserializeForNotNull(buffer, keyType, keySerializerExpr, keyHint);
              }
              walkPath.removeLast();
              walkPath.add("value:" + valueType);
              if (trackingValueRef) {
                valueAction =
                    new If(
                        trackValueRef,
                        deserializeRef(buffer, valueType, valueSerializerExpr, valueHint),
                        deserializeForNotNullNoRef(
                            buffer, valueType, valueSerializerExpr, valueHint),
                        false);
              } else {
                valueAction =
                    deserializeForNotNull(buffer, valueType, valueSerializerExpr, valueHint);
              }
              walkPath.removeLast();
              return list(
                  new Invoke(map, "put", keyAction, valueAction),
                  new Assign(size, subtract(size, ofInt(1))));
            });
    expressions.add(readKeyValues);

    if (inline) {
      expressions.add(
          new If(
              gt(size, ofInt(0)),
              new Assign(
                  chunkHeader, inlineInvoke(buffer, "readUnsignedByte", PRIMITIVE_INT_TYPE))));
      return expressions;
    } else {
      Expression returnSizeAndHeader =
          new If(
              gt(size, ofInt(0)),
              new Return(
                  (bitor(
                      shift("<<", size, 8),
                      inlineInvoke(buffer, "readUnsignedByte", PRIMITIVE_INT_TYPE)))),
              new Return(ofInt(0)));
      expressions.add(returnSizeAndHeader);
      // method too big, spilt it into a new method.
      // Generate similar signature as `MapLikeSerializer.writeJavaChunk`(
      //   MemoryBuffer buffer,
      //   long size,
      //   int chunkHeader,
      //   Serializer keySerializer,
      //   Serializer valueSerializer
      //  )
      Set<Expression> params = ofHashSet(buffer, size, chunkHeader, map);
      return invokeGenerated(ctx, params, expressions, "readChunk", false);
    }
  }

  private Expression readOrGetSerializerForDeclaredType(
      Expression buffer, Class<?> type, Expression isDeclaredType) {
    if (isMonomorphic(type)) {
      return getOrCreateSerializer(type);
    }
    TypeRef<?> serializerType = getSerializerType(type);
    if (ReflectionUtils.isAbstract(type) || type.isInterface()) {
      return invoke(readTypeInfo(type, buffer), "getSerializer", "serializer", serializerType);
    } else {
      return new If(
          isDeclaredType,
          getOrCreateSerializer(type),
          invokeInline(readTypeInfo(type, buffer), "getSerializer", serializerType),
          false);
    }
  }

  @Override
  protected Expression beanClassExpr() {
    if (GraalvmSupport.isGraalBuildtime()) {
      return staticBeanClassExpr();
    }
    // Serializer has a `type` field.
    return new Reference("super.type", CLASS_TYPE);
  }
}
