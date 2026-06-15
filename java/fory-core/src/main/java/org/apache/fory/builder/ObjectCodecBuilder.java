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

import static org.apache.fory.codegen.Code.LiteralValue.FalseLiteral;
import static org.apache.fory.codegen.Expression.Invoke.inlineInvoke;
import static org.apache.fory.codegen.ExpressionUtils.add;
import static org.apache.fory.codegen.ExpressionUtils.cast;
import static org.apache.fory.collection.Collections.ofHashSet;
import static org.apache.fory.type.TypeUtils.OBJECT_ARRAY_TYPE;
import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_BOOLEAN_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_BYTE_ARRAY_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_BYTE_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_CHAR_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_DOUBLE_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_FLOAT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_INT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_LONG_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_SHORT_TYPE;
import static org.apache.fory.type.TypeUtils.PRIMITIVE_VOID_TYPE;
import static org.apache.fory.type.TypeUtils.SHORT_TYPE;
import static org.apache.fory.type.TypeUtils.getRawType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.apache.fory.Fory;
import org.apache.fory.codegen.Code;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Cast;
import org.apache.fory.codegen.Expression.Inlineable;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.codegen.Expression.ListExpression;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.codegen.Expression.NewInstance;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.Expression.ReplaceStub;
import org.apache.fory.codegen.Expression.StaticInvoke;
import org.apache.fory.codegen.ExpressionVisitor;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.DispatchId;
import org.apache.fory.type.Float16;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.function.SerializableSupplier;
import org.apache.fory.util.record.RecordUtils;

/**
 * Generate sequential read/write code for java serialization to speed up performance. It also
 * reduces space overhead introduced by aligning. Codegen only for time-consuming field, others
 * delegate to fory.
 *
 * <p>In order to improve jit-compile and inline, serialization code should be spilt groups to avoid
 * huge/big methods.
 *
 * <p>With meta context share enabled and compatible mode, this serializer will take all non-inner
 * final types as non-final, so that fory can write class definition when write class info for those
 * types.
 *
 * @see ObjectCodecOptimizer for code stats and split heuristics.
 */
public class ObjectCodecBuilder extends BaseObjectCodecBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(ObjectCodecBuilder.class);

  private final Literal classVersionHash;
  protected ObjectCodecOptimizer objectCodecOptimizer;
  protected Map<String, Integer> recordReversedMapping;

  public ObjectCodecBuilder(Class<?> beanClass, Fory fory) {
    super(TypeRef.of(beanClass), fory, Generated.GeneratedObjectSerializer.class);
    Collection<Descriptor> descriptors;
    DescriptorGrouper grouper;
    boolean shareMeta = fory.getConfig().isMetaShareEnabled();
    if (shareMeta) {
      TypeDef typeDef = typeResolver(r -> r.getTypeDef(beanClass, true));
      descriptors = typeResolver(r -> typeDef.getDescriptors(r, beanClass));
      grouper = typeResolver(r -> r.createDescriptorGrouper(typeDef, beanClass));
    } else {
      grouper = typeResolver(r -> r.getFieldDescriptorGrouper(beanClass, true, false));
      descriptors = grouper.getSortedDescriptors();
    }
    if (org.apache.fory.util.Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info(
          "========== {} sorted descriptors for {} ==========",
          descriptors.size(),
          beanClass.getSimpleName());
      List<Descriptor> sortedDescriptors = grouper.getSortedDescriptors();
      for (Descriptor d : sortedDescriptors) {
        LOG.info(
            "  {} -> {}, ref {}, nullable {}",
            StringUtils.toSnakeCase(d.getName()),
            d.getTypeName(),
            d.isTrackingRef(),
            d.isNullable());
      }
    }
    classVersionHash =
        typeResolver.checkClassVersion()
            ? new Literal(
                ObjectSerializer.computeStructHash(typeResolver, grouper), PRIMITIVE_INT_TYPE)
            : null;
    objectCodecOptimizer = new ObjectCodecOptimizer(beanClass, grouper, false, ctx);
    if (isRecord) {
      if (!recordCtrAccessible) {
        buildRecordComponentDefaultValues();
      }
      recordReversedMapping = RecordUtils.buildFieldToComponentMapping(beanClass);
    }
  }

  protected ObjectCodecBuilder(TypeRef<?> beanType, Fory fory, Class<?> superSerializerClass) {
    super(beanType, fory, superSerializerClass);
    this.classVersionHash = null;
    if (isRecord) {
      if (!recordCtrAccessible) {
        buildRecordComponentDefaultValues();
      }
      recordReversedMapping = RecordUtils.buildFieldToComponentMapping(beanClass);
    }
  }

  @Override
  protected String codecSuffix() {
    return "";
  }

  @Override
  protected void addCommonImports() {
    super.addCommonImports();
    ctx.addImport(Generated.GeneratedObjectSerializer.class);
  }

  /**
   * Return an expression that serialize java bean of type {@link CodecBuilder#beanClass} to buffer.
   */
  @Override
  public Expression buildEncodeExpression() {
    Reference inputObject = new Reference(ROOT_OBJECT_NAME, OBJECT_TYPE, false);
    Reference buffer = new Reference(BUFFER_NAME, bufferTypeRef, false);

    ListExpression expressions = new ListExpression();
    Expression bean = tryCastIfPublic(inputObject, beanType, ctx.newName(beanClass));
    expressions.add(bean);
    if (typeResolver.checkClassVersion()) {
      expressions.add(new Invoke(buffer, "writeInt32", classVersionHash));
    }
    expressions.addAll(serializePrimitives(bean, buffer, objectCodecOptimizer.primitiveGroups));
    int numGroups = getNumGroups(objectCodecOptimizer);
    addGroupExpressions(
        objectCodecOptimizer.boxedWriteGroups, numGroups, expressions, bean, buffer);
    addGroupExpressions(
        objectCodecOptimizer.nonPrimitiveWriteGroups, numGroups, expressions, bean, buffer);
    return expressions;
  }

  private void addGroupExpressions(
      List<List<Descriptor>> writeGroup,
      int numGroups,
      ListExpression expressions,
      Expression bean,
      Reference buffer) {
    for (List<Descriptor> group : writeGroup) {
      if (group.isEmpty()) {
        continue;
      }
      boolean inline = hasFewFields() || (group.size() == 1 && numGroups < 10);
      expressions.add(serializeGroup(group, bean, buffer, inline));
    }
  }

  protected boolean hasFewFields() {
    return objectCodecOptimizer.descriptorGrouper.getNumDescriptors() < 6;
  }

  protected int getNumGroups(ObjectCodecOptimizer objectCodecOptimizer) {
    return objectCodecOptimizer.boxedWriteGroups.size()
        + objectCodecOptimizer.nonPrimitiveWriteGroups.size();
  }

  private Expression serializeGroup(
      List<Descriptor> group, Expression bean, Expression buffer, boolean inline) {
    SerializableSupplier<Expression> expressionSupplier =
        () -> {
          ListExpression groupExpressions = new ListExpression();
          for (Descriptor d : group) {
            // `bean` will be replaced by `Reference` to cut-off expr dependency.
            Expression fieldValue = getFieldValue(bean, d);
            walkPath.add(d.getDeclaringClass() + d.getName());
            Expression fieldExpr = serializeField(fieldValue, buffer, d);
            walkPath.removeLast();
            groupExpressions.add(fieldExpr);
          }
          return groupExpressions;
        };
    if (inline) {
      return expressionSupplier.get();
    }
    return objectCodecOptimizer.invokeGenerated(
        writeCutPoints(bean, buffer), expressionSupplier.get(), "writeFields");
  }

  /**
   * Return a list of expressions that serialize all primitive fields. This can reduce unnecessary
   * grow call and increment writerIndex in writeXXX.
   */
  private List<Expression> serializePrimitives(
      Expression bean, Expression buffer, List<List<Descriptor>> primitiveGroups) {
    int totalSize = getTotalSizeOfPrimitives(primitiveGroups);
    if (totalSize == 0) {
      return new ArrayList<>();
    }
    if (config.compressInt() || config.compressLong()) {
      return serializePrimitivesCompressed(bean, buffer, primitiveGroups, totalSize);
    } else {
      return serializePrimitivesUnCompressed(bean, buffer, primitiveGroups, totalSize);
    }
  }

  protected int getNumPrimitiveFields(List<List<Descriptor>> primitiveGroups) {
    return primitiveGroups.stream().mapToInt(List::size).sum();
  }

  private List<Expression> serializePrimitivesUnCompressed(
      Expression bean, Expression buffer, List<List<Descriptor>> primitiveGroups, int totalSize) {
    List<Expression> expressions = new ArrayList<>();
    Literal totalSizeLiteral = Literal.ofInt(totalSize);
    // After this grow, following writes can use unchecked low-level access.
    expressions.add(new Invoke(buffer, "grow", totalSizeLiteral));
    PrimitiveWriteAccess access;
    if (useIndexedAccess()) {
      Expression writerIndex = new Invoke(buffer, "writerIndex", "writerIndex", PRIMITIVE_INT_TYPE);
      expressions.add(writerIndex);
      access = new BufferWriteAccess(buffer, writerIndex);
    } else {
      // Must grow first, otherwise may get invalid address.
      Expression base = new Invoke(buffer, "getHeapMemory", "base", PRIMITIVE_BYTE_ARRAY_TYPE);
      Expression writerAddr =
          new Invoke(buffer, "_unsafeWriterAddress", "writerAddr", PRIMITIVE_LONG_TYPE);
      expressions.add(base);
      expressions.add(writerAddr);
      access = new UnsafeWriteAccess(buffer, base, writerAddr);
    }
    writePrimitiveGroups(expressions, bean, buffer, primitiveGroups, () -> access, false);
    expressions.add(new Invoke(buffer, "_increaseWriterIndexUnsafe", totalSizeLiteral));
    return expressions;
  }

  private List<Expression> serializePrimitivesCompressed(
      Expression bean, Expression buffer, List<List<Descriptor>> primitiveGroups, int totalSize) {
    List<Expression> expressions = new ArrayList<>();
    // int/long may need extra bytes for compressed writing.
    int extraSize = extraPrimitiveSize(primitiveGroups);
    int growSize = totalSize + extraSize;
    // After this grow, following writes can use unchecked low-level access.
    expressions.add(new Invoke(buffer, "grow", Literal.ofInt(growSize)));
    if (useIndexedAccess()) {
      writePrimitiveGroups(
          expressions,
          bean,
          buffer,
          primitiveGroups,
          () ->
              new BufferWriteAccess(
                  buffer, new Invoke(buffer, "writerIndex", "writerIndex", PRIMITIVE_INT_TYPE)),
          true);
    } else {
      // Must grow first, otherwise may get invalid address.
      Expression base = new Invoke(buffer, "getHeapMemory", "base", PRIMITIVE_BYTE_ARRAY_TYPE);
      expressions.add(base);
      writePrimitiveGroups(
          expressions,
          bean,
          buffer,
          primitiveGroups,
          () ->
              new UnsafeWriteAccess(
                  buffer,
                  base,
                  new Invoke(buffer, "_unsafeWriterAddress", "writerAddr", PRIMITIVE_LONG_TYPE)),
          true);
    }
    return expressions;
  }

  private void writePrimitiveGroups(
      List<Expression> expressions,
      Expression bean,
      Expression buffer,
      List<List<Descriptor>> primitiveGroups,
      WriteAccessFactory accessFactory,
      boolean compressed) {
    int numPrimitiveFields = getNumPrimitiveFields(primitiveGroups);
    int rawAcc = 0;
    for (List<Descriptor> group : primitiveGroups) {
      ListExpression groupExpressions = new ListExpression();
      PrimitiveWriteAccess access = accessFactory.get();
      PrimitiveWriteState state = new PrimitiveWriteState(compressed ? 0 : rawAcc);
      for (Descriptor descriptor : group) {
        int dispatchId = getNumericDescriptorDispatchId(descriptor);
        // `bean` will be replaced by `Reference` to cut-off expr dependency.
        Expression fieldValue = getFieldValue(bean, descriptor);
        if (fieldValue instanceof Inlineable) {
          ((Inlineable) fieldValue).inline();
        }
        if (compressed) {
          writeCompressed(
              groupExpressions, buffer, access, descriptor, dispatchId, fieldValue, state);
        } else {
          writeFixed(groupExpressions, access, descriptor, dispatchId, fieldValue, state);
        }
      }
      if (compressed) {
        if (!state.compressStarted) {
          // int/long are sorted in the last.
          addIncWriterIndexExpr(groupExpressions, buffer, state.acc);
        }
      } else {
        rawAcc = state.acc;
      }
      if (hasFewFields() || numPrimitiveFields < 4) {
        expressions.add(groupExpressions);
      } else {
        expressions.add(
            objectCodecOptimizer.invokeGenerated(
                compressed ? access.compressedScope(bean) : access.fixedScope(bean),
                groupExpressions,
                "writeFields"));
      }
    }
  }

  private void writeCompressed(
      ListExpression expressions,
      Expression buffer,
      PrimitiveWriteAccess access,
      Descriptor descriptor,
      int dispatchId,
      Expression fieldValue,
      PrimitiveWriteState state) {
    switch (dispatchId) {
      case DispatchId.VARINT32:
        startCompressed(expressions, buffer, state);
        expressions.add(new Invoke(buffer, "_unsafeWriteVarInt32", fieldValue));
        return;
      case DispatchId.VAR_UINT32:
        startCompressed(expressions, buffer, state);
        expressions.add(
            new Invoke(buffer, "_unsafeWriteVarUInt32", primitiveIntValue(fieldValue, descriptor)));
        return;
      case DispatchId.VARINT64:
        startCompressed(expressions, buffer, state);
        expressions.add(new Invoke(buffer, "writeVarInt64", fieldValue));
        return;
      case DispatchId.TAGGED_INT64:
        startCompressed(expressions, buffer, state);
        expressions.add(new Invoke(buffer, "writeTaggedInt64", fieldValue));
        return;
      case DispatchId.VAR_UINT64:
        startCompressed(expressions, buffer, state);
        expressions.add(new Invoke(buffer, "writeVarUInt64", fieldValue));
        return;
      case DispatchId.TAGGED_UINT64:
        startCompressed(expressions, buffer, state);
        expressions.add(new Invoke(buffer, "writeTaggedUInt64", fieldValue));
        return;
      default:
        writeFixed(expressions, access, descriptor, dispatchId, fieldValue, state);
    }
  }

  private void startCompressed(
      ListExpression expressions, Expression buffer, PrimitiveWriteState state) {
    if (!state.compressStarted) {
      addIncWriterIndexExpr(expressions, buffer, state.acc);
      state.compressStarted = true;
    }
  }

  private void writeFixed(
      ListExpression expressions,
      PrimitiveWriteAccess access,
      Descriptor descriptor,
      int dispatchId,
      Expression fieldValue,
      PrimitiveWriteState state) {
    switch (dispatchId) {
      case DispatchId.BOOL:
        expressions.add(access.putBoolean(state.acc, fieldValue));
        state.acc += 1;
        return;
      case DispatchId.INT8:
        expressions.add(access.putByte(state.acc, fieldValue));
        state.acc += 1;
        return;
      case DispatchId.UINT8:
        expressions.add(access.putByte(state.acc, primitiveByteValue(fieldValue, descriptor)));
        state.acc += 1;
        return;
      case DispatchId.CHAR:
        expressions.add(access.putChar(state.acc, fieldValue));
        state.acc += 2;
        return;
      case DispatchId.INT16:
        expressions.add(access.putInt16(state.acc, fieldValue));
        state.acc += 2;
        return;
      case DispatchId.UINT16:
        expressions.add(access.putInt16(state.acc, primitiveShortValue(fieldValue, descriptor)));
        state.acc += 2;
        return;
      case DispatchId.FLOAT16:
      case DispatchId.BFLOAT16:
        expressions.add(access.putInt16(state.acc, new Invoke(fieldValue, "toBits", SHORT_TYPE)));
        state.acc += 2;
        return;
      case DispatchId.INT32:
        expressions.add(access.putInt32(state.acc, fieldValue));
        state.acc += 4;
        return;
      case DispatchId.UINT32:
        expressions.add(access.putInt32(state.acc, primitiveIntValue(fieldValue, descriptor)));
        state.acc += 4;
        return;
      case DispatchId.INT64:
      case DispatchId.UINT64:
        expressions.add(access.putInt64(state.acc, fieldValue));
        state.acc += 8;
        return;
      case DispatchId.FLOAT32:
        expressions.add(access.putFloat32(state.acc, fieldValue));
        state.acc += 4;
        return;
      case DispatchId.FLOAT64:
        expressions.add(access.putFloat64(state.acc, fieldValue));
        state.acc += 8;
        return;
      default:
        throw new IllegalStateException("Unsupported dispatchId: " + dispatchId);
    }
  }

  private int extraPrimitiveSize(List<List<Descriptor>> primitiveGroups) {
    int extraSize = 0;
    for (List<Descriptor> group : primitiveGroups) {
      for (Descriptor d : group) {
        int id = getNumericDescriptorDispatchId(d);
        if (id == DispatchId.INT32
            || id == DispatchId.VARINT32
            || id == DispatchId.VAR_UINT32
            || id == DispatchId.UINT32) {
          // varint may be written as 5 bytes; reserve 4 extra bytes over the fixed size.
          extraSize += 4;
        } else if (id == DispatchId.INT64
            || id == DispatchId.VARINT64
            || id == DispatchId.TAGGED_INT64
            || id == DispatchId.VAR_UINT64
            || id == DispatchId.TAGGED_UINT64
            || id == DispatchId.UINT64) {
          // long uses 1~9 bytes; reserve one byte over the fixed size.
          extraSize += 1;
        }
      }
    }
    return extraSize;
  }

  private boolean useIndexedAccess() {
    return JdkVersion.MAJOR_VERSION >= 25;
  }

  private interface WriteAccessFactory {
    PrimitiveWriteAccess get();
  }

  private static final class PrimitiveWriteState {
    private int acc;
    private boolean compressStarted;

    private PrimitiveWriteState(int acc) {
      this.acc = acc;
    }
  }

  private abstract class PrimitiveWriteAccess {
    protected final Expression buffer;
    protected final Expression cursor;

    private PrimitiveWriteAccess(Expression buffer, Expression cursor) {
      this.buffer = buffer;
      this.cursor = cursor;
    }

    abstract Expression putByte(int acc, Expression value);

    abstract Expression putBoolean(int acc, Expression value);

    abstract Expression putChar(int acc, Expression value);

    abstract Expression putInt16(int acc, Expression value);

    abstract Expression putInt32(int acc, Expression value);

    abstract Expression putInt64(int acc, Expression value);

    abstract Expression putFloat32(int acc, Expression value);

    abstract Expression putFloat64(int acc, Expression value);

    abstract Set<Expression> fixedScope(Expression bean);

    abstract Set<Expression> compressedScope(Expression bean);
  }

  private final class UnsafeWriteAccess extends PrimitiveWriteAccess {
    private final Expression base;

    private UnsafeWriteAccess(Expression buffer, Expression base, Expression writerAddr) {
      super(buffer, writerAddr);
      this.base = base;
    }

    private Expression pos(int acc) {
      return getWriterPos(cursor, acc);
    }

    @Override
    Expression putByte(int acc, Expression value) {
      return unsafePut(base, pos(acc), value);
    }

    @Override
    Expression putBoolean(int acc, Expression value) {
      return unsafePutBoolean(base, pos(acc), value);
    }

    @Override
    Expression putChar(int acc, Expression value) {
      return unsafePutChar(base, pos(acc), value);
    }

    @Override
    Expression putInt16(int acc, Expression value) {
      return unsafePutShort(base, pos(acc), value);
    }

    @Override
    Expression putInt32(int acc, Expression value) {
      return unsafePutInt(base, pos(acc), value);
    }

    @Override
    Expression putInt64(int acc, Expression value) {
      return unsafePutLong(base, pos(acc), value);
    }

    @Override
    Expression putFloat32(int acc, Expression value) {
      return unsafePutFloat(base, pos(acc), value);
    }

    @Override
    Expression putFloat64(int acc, Expression value) {
      return unsafePutDouble(base, pos(acc), value);
    }

    @Override
    Set<Expression> fixedScope(Expression bean) {
      return ofHashSet(bean, base, cursor);
    }

    @Override
    Set<Expression> compressedScope(Expression bean) {
      return ofHashSet(bean, buffer, base);
    }
  }

  private final class BufferWriteAccess extends PrimitiveWriteAccess {
    private BufferWriteAccess(Expression buffer, Expression writerIndex) {
      super(buffer, writerIndex);
    }

    private Expression index(int acc) {
      return getBufferIndex(cursor, acc);
    }

    @Override
    Expression putByte(int acc, Expression value) {
      return bufferPutByte(buffer, index(acc), value);
    }

    @Override
    Expression putBoolean(int acc, Expression value) {
      return bufferPutBoolean(buffer, index(acc), value);
    }

    @Override
    Expression putChar(int acc, Expression value) {
      return bufferPutChar(buffer, index(acc), value);
    }

    @Override
    Expression putInt16(int acc, Expression value) {
      return bufferPutInt16(buffer, index(acc), value);
    }

    @Override
    Expression putInt32(int acc, Expression value) {
      return bufferPutInt32(buffer, index(acc), value);
    }

    @Override
    Expression putInt64(int acc, Expression value) {
      return bufferPutInt64(buffer, index(acc), value);
    }

    @Override
    Expression putFloat32(int acc, Expression value) {
      return bufferPutFloat32(buffer, index(acc), value);
    }

    @Override
    Expression putFloat64(int acc, Expression value) {
      return bufferPutFloat64(buffer, index(acc), value);
    }

    @Override
    Set<Expression> fixedScope(Expression bean) {
      return ofHashSet(bean, buffer, cursor);
    }

    @Override
    Set<Expression> compressedScope(Expression bean) {
      return ofHashSet(bean, buffer, cursor);
    }
  }

  private Expression bufferPutByte(Expression buffer, Expression index, Expression value) {
    return new Invoke(buffer, "_unsafePutByte", index, value);
  }

  private Expression bufferPutBoolean(Expression buffer, Expression index, Expression value) {
    return new Invoke(buffer, "_unsafePutBoolean", index, value);
  }

  private Expression bufferPutChar(Expression buffer, Expression index, Expression value) {
    return new Invoke(buffer, "_unsafePutChar", index, value);
  }

  private Expression bufferPutInt16(Expression buffer, Expression index, Expression value) {
    return new Invoke(buffer, "_unsafePutInt16", index, value);
  }

  private Expression bufferPutInt32(Expression buffer, Expression index, Expression value) {
    return new Invoke(buffer, "_unsafePutInt32", index, value);
  }

  private Expression bufferPutInt64(Expression buffer, Expression index, Expression value) {
    return new Invoke(buffer, "_unsafePutInt64", index, value);
  }

  private Expression bufferPutFloat32(Expression buffer, Expression index, Expression value) {
    return bufferPutInt32(
        buffer,
        index,
        new StaticInvoke(Float.class, "floatToRawIntBits", PRIMITIVE_INT_TYPE, value));
  }

  private Expression bufferPutFloat64(Expression buffer, Expression index, Expression value) {
    return bufferPutInt64(
        buffer,
        index,
        new StaticInvoke(Double.class, "doubleToRawLongBits", PRIMITIVE_LONG_TYPE, value));
  }

  private Expression bufferGetByte(Expression buffer, Expression index) {
    return new Invoke(buffer, "_unsafeGetByte", PRIMITIVE_BYTE_TYPE, index);
  }

  private Expression bufferGetBoolean(Expression buffer, Expression index) {
    return new Invoke(buffer, "_unsafeGetBoolean", PRIMITIVE_BOOLEAN_TYPE, index);
  }

  private Expression bufferGetChar(Expression buffer, Expression index) {
    return new Invoke(buffer, "_unsafeGetChar", PRIMITIVE_CHAR_TYPE, index);
  }

  private Expression bufferGetInt16(Expression buffer, Expression index) {
    return new Invoke(buffer, "_unsafeGetInt16", PRIMITIVE_SHORT_TYPE, index);
  }

  private Expression bufferGetInt32(Expression buffer, Expression index) {
    return new Invoke(buffer, "_unsafeGetInt32", PRIMITIVE_INT_TYPE, index);
  }

  private Expression bufferGetInt64(Expression buffer, Expression index) {
    return new Invoke(buffer, "_unsafeGetInt64", PRIMITIVE_LONG_TYPE, index);
  }

  private Expression bufferGetFloat32(Expression buffer, Expression index) {
    return new StaticInvoke(
        Float.class, "intBitsToFloat", PRIMITIVE_FLOAT_TYPE, bufferGetInt32(buffer, index));
  }

  private Expression bufferGetFloat64(Expression buffer, Expression index) {
    return new StaticInvoke(
        Double.class, "longBitsToDouble", PRIMITIVE_DOUBLE_TYPE, bufferGetInt64(buffer, index));
  }

  private Expression primitiveByteValue(Expression fieldValue, Descriptor descriptor) {
    return fieldValue.type().isPrimitive()
        ? cast(fieldValue, PRIMITIVE_BYTE_TYPE)
        : new Invoke(boxedNumericValue(fieldValue, descriptor), "byteValue", PRIMITIVE_BYTE_TYPE);
  }

  private Expression primitiveShortValue(Expression fieldValue, Descriptor descriptor) {
    return fieldValue.type().isPrimitive()
        ? cast(fieldValue, PRIMITIVE_SHORT_TYPE)
        : new Invoke(boxedNumericValue(fieldValue, descriptor), "shortValue", PRIMITIVE_SHORT_TYPE);
  }

  private Expression primitiveIntValue(Expression fieldValue, Descriptor descriptor) {
    return fieldValue.type().isPrimitive()
        ? cast(fieldValue, PRIMITIVE_INT_TYPE)
        : new Invoke(boxedNumericValue(fieldValue, descriptor), "intValue", PRIMITIVE_INT_TYPE);
  }

  private Expression boxedNumericValue(Expression fieldValue, Descriptor descriptor) {
    return Number.class.isAssignableFrom(getRawType(fieldValue.type()))
        ? fieldValue
        : cast(fieldValue, descriptor.getTypeRef());
  }

  private void addIncWriterIndexExpr(ListExpression expressions, Expression buffer, int diff) {
    if (diff != 0) {
      expressions.add(new Invoke(buffer, "_increaseWriterIndexUnsafe", Literal.ofInt(diff)));
    }
  }

  private int getTotalSizeOfPrimitives(List<List<Descriptor>> primitiveGroups) {
    return primitiveGroups.stream()
        .flatMap(Collection::stream)
        .mapToInt(
            d -> {
              Class<?> rawType = d.getRawType();
              if (TypeUtils.isPrimitive(rawType) || TypeUtils.isBoxed(rawType)) {
                return TypeUtils.getSizeOfPrimitiveType(TypeUtils.unwrap(rawType));
              }
              return Types.getPrimitiveTypeSize(Types.getDescriptorTypeId(typeResolver, d));
            })
        .sum();
  }

  private Expression getWriterPos(Expression writerPos, long acc) {
    if (acc == 0) {
      return writerPos;
    }
    return add(writerPos, Literal.ofLong(acc));
  }

  public Expression buildDecodeExpression() {
    Reference buffer = new Reference(BUFFER_NAME, bufferTypeRef, false);
    ListExpression expressions = new ListExpression();
    if (typeResolver.checkClassVersion()) {
      expressions.add(checkClassVersion(buffer));
    }
    Expression bean;
    if (!isRecord) {
      bean = newBean();
      Expression referenceObject = invokeReadContext("reference", bean);
      expressions.add(bean);
      expressions.add(referenceObject);
    } else {
      if (recordCtrAccessible) {
        bean = new FieldsCollector();
      } else {
        bean = buildComponentsArray();
      }
    }
    expressions.addAll(deserializePrimitives(bean, buffer, objectCodecOptimizer.primitiveGroups));
    int numGroups = getNumGroups(objectCodecOptimizer);
    deserializeReadGroup(
        objectCodecOptimizer.boxedReadGroups, numGroups, expressions, bean, buffer);
    deserializeReadGroup(
        objectCodecOptimizer.nonPrimitiveReadGroups, numGroups, expressions, bean, buffer);
    if (isRecord) {
      if (recordCtrAccessible) {
        assert bean instanceof FieldsCollector;
        FieldsCollector collector = (FieldsCollector) bean;
        bean = createRecord(collector.recordValuesMap);
      } else {
        typeResolver.getObjectInstantiator(beanClass); // trigger cache and make error raised early
        bean =
            new Invoke(
                getObjectInstantiator(beanClass), "newInstanceWithArguments", OBJECT_TYPE, bean);
      }
    }
    expressions.add(new Expression.Return(bean));
    return expressions;
  }

  protected void deserializeReadGroup(
      List<List<Descriptor>> readGroups,
      int numGroups,
      ListExpression expressions,
      Expression bean,
      Reference buffer) {
    for (List<Descriptor> group : readGroups) {
      if (group.isEmpty()) {
        continue;
      }
      boolean inline = hasFewFields() || (group.size() == 1 && numGroups < 10);
      expressions.add(deserializeGroup(group, bean, buffer, inline));
    }
  }

  protected Expression buildComponentsArray() {
    return new Cast(
        new Invoke(recordComponentDefaultValues, "clone", OBJECT_TYPE), OBJECT_ARRAY_TYPE);
  }

  protected Expression createRecord(SortedMap<Integer, Expression> recordComponents) {
    Expression[] params = recordComponents.values().toArray(new Expression[0]);
    return new NewInstance(beanType, params);
  }

  private class FieldsCollector extends Expression.AbstractExpression {
    private final TreeMap<Integer, Expression> recordValuesMap = new TreeMap<>();

    protected FieldsCollector() {
      super(new Expression[0]);
    }

    @Override
    public TypeRef<?> type() {
      return beanType;
    }

    @Override
    public Code.ExprCode doGenCode(CodegenContext ctx) {
      return new Code.ExprCode(FalseLiteral, Code.variable(getRawType(beanType), "null"));
    }
  }

  @Override
  protected Expression setFieldValue(Expression bean, Descriptor d, Expression value) {
    if (isRecord) {
      if (recordCtrAccessible) {
        if (value instanceof Inlineable) {
          ((Inlineable) value).inline(false);
        }
        int index = recordReversedMapping.get(d.getName());
        FieldsCollector collector = (FieldsCollector) bean;
        collector.recordValuesMap.put(index, value);
        return value;
      } else {
        int index = recordReversedMapping.get(d.getName());
        return new Expression.AssignArrayElem(bean, value, Literal.ofInt(index));
      }
    }
    return super.setFieldValue(bean, d, value);
  }

  protected Expression deserializeGroup(
      List<Descriptor> group, Expression bean, Expression buffer, boolean inline) {
    if (isRecord) {
      return deserializeGroupForRecord(group, bean, buffer);
    }
    SerializableSupplier<Expression> exprSupplier =
        () -> {
          ListExpression groupExpressions = new ListExpression();
          // use Reference to cut-off expr dependency.
          for (Descriptor d : group) {
            ExpressionVisitor.ExprHolder exprHolder = ExpressionVisitor.ExprHolder.of("bean", bean);
            walkPath.add(d.getDeclaringClass() + d.getName());
            TypeRef<?> castTypeRef = readValueTypeRef(d);
            Expression action =
                deserializeField(
                    buffer,
                    d,
                    // `bean` will be replaced by `Reference` to cut-off expr
                    // dependency.
                    expr ->
                        setFieldValue(exprHolder.get("bean"), d, tryInlineCast(expr, castTypeRef)));
            walkPath.removeLast();
            if (needsGeneratedReadFieldMethod(d)) {
              action =
                  objectCodecOptimizer.invokeGenerated(
                      readCutPoints(bean, buffer), action, "readField");
            }
            groupExpressions.add(action);
          }
          return groupExpressions;
        };
    if (inline) {
      return exprSupplier.get();
    } else {
      return objectCodecOptimizer.invokeGenerated(
          readCutPoints(bean, buffer), exprSupplier.get(), "readFields");
    }
  }

  private boolean needsGeneratedReadFieldMethod(Descriptor descriptor) {
    return !hasFewFields()
        && !isMonomorphic(descriptor)
        && !useCollectionSerialization(descriptor)
        && !useMapSerialization(descriptor.getTypeRef());
  }

  protected Expression deserializeGroupForRecord(
      List<Descriptor> group, Expression bean, Expression buffer) {
    ListExpression groupExpressions = new ListExpression();
    // use Reference to cut-off expr dependency.
    for (Descriptor d : group) {
      TypeRef<?> castTypeRef = readValueTypeRef(d);
      Expression value = deserializeField(buffer, d, expr -> expr);
      Expression action = setFieldValue(bean, d, tryInlineCast(value, castTypeRef));
      groupExpressions.add(action);
    }
    return groupExpressions;
  }

  private Expression checkClassVersion(Expression buffer) {
    return new StaticInvoke(
        ObjectSerializer.class,
        "checkClassVersion",
        PRIMITIVE_VOID_TYPE,
        false,
        beanClassExpr(),
        inlineInvoke(buffer, readIntFunc(), PRIMITIVE_INT_TYPE),
        Objects.requireNonNull(classVersionHash));
  }

  /**
   * Return a list of expressions that deserialize all primitive fields. This can reduce unnecessary
   * check call and increment readerIndex in writeXXX.
   */
  protected List<Expression> deserializePrimitives(
      Expression bean, Expression buffer, List<List<Descriptor>> primitiveGroups) {
    int totalSize = getTotalSizeOfPrimitives(primitiveGroups);
    if (totalSize == 0) {
      return new ArrayList<>();
    }
    if (config.compressInt() || config.compressLong()) {
      return deserializeCompressedPrimitives(bean, buffer, primitiveGroups);
    } else {
      return deserializeUnCompressedPrimitives(bean, buffer, primitiveGroups, totalSize);
    }
  }

  private List<Expression> deserializeUnCompressedPrimitives(
      Expression bean, Expression buffer, List<List<Descriptor>> primitiveGroups, int totalSize) {
    List<Expression> expressions = new ArrayList<>();
    Literal totalSizeLiteral = Literal.ofInt(totalSize);
    // After this check, following reads can use unchecked low-level access.
    expressions.add(new Invoke(buffer, "checkReadableBytes", totalSizeLiteral));
    PrimitiveReadAccess access;
    if (useIndexedAccess()) {
      Expression readerIndex = new Invoke(buffer, "readerIndex", "readerIndex", PRIMITIVE_INT_TYPE);
      expressions.add(readerIndex);
      access = new BufferReadAccess(buffer, readerIndex);
    } else {
      Expression heapBuffer =
          new Invoke(buffer, "getHeapMemory", "heapBuffer", PRIMITIVE_BYTE_ARRAY_TYPE);
      Expression readerAddr =
          new Invoke(buffer, "getUnsafeReaderAddress", "readerAddr", PRIMITIVE_LONG_TYPE);
      expressions.add(heapBuffer);
      expressions.add(readerAddr);
      access = new UnsafeReadAccess(buffer, heapBuffer, readerAddr);
    }
    readPrimitiveGroups(expressions, bean, buffer, primitiveGroups, ignored -> access, false);
    expressions.add(new Invoke(buffer, "increaseReaderIndex", totalSizeLiteral));
    return expressions;
  }

  private List<Expression> deserializeCompressedPrimitives(
      Expression bean, Expression buffer, List<List<Descriptor>> primitiveGroups) {
    List<Expression> expressions = new ArrayList<>();
    if (useIndexedAccess()) {
      readPrimitiveGroups(
          expressions,
          bean,
          buffer,
          primitiveGroups,
          readExpressions -> {
            Expression readerIndex =
                new Invoke(buffer, "readerIndex", "readerIndex", PRIMITIVE_INT_TYPE);
            readExpressions.add(readerIndex);
            return new BufferReadAccess(buffer, readerIndex);
          },
          true);
    } else {
      readPrimitiveGroups(
          expressions,
          bean,
          buffer,
          primitiveGroups,
          readExpressions -> {
            // checkReadableBytes first, `fillBuffer` may create a new heap buffer.
            Expression heapBuffer =
                new Invoke(buffer, "getHeapMemory", "heapBuffer", PRIMITIVE_BYTE_ARRAY_TYPE);
            readExpressions.add(heapBuffer);
            Expression readerAddr =
                new Invoke(buffer, "getUnsafeReaderAddress", "readerAddr", PRIMITIVE_LONG_TYPE);
            return new UnsafeReadAccess(buffer, heapBuffer, readerAddr);
          },
          true);
    }
    return expressions;
  }

  private void readPrimitiveGroups(
      List<Expression> expressions,
      Expression bean,
      Expression buffer,
      List<List<Descriptor>> primitiveGroups,
      ReadAccessFactory accessFactory,
      boolean compressed) {
    int numPrimitiveFields = getNumPrimitiveFields(primitiveGroups);
    int rawAcc = 0;
    for (List<Descriptor> group : primitiveGroups) {
      ReplaceStub checkReadableBytesStub = null;
      if (compressed) {
        // After this check, following reads can use unchecked low-level access.
        checkReadableBytesStub = new ReplaceStub();
        expressions.add(checkReadableBytesStub);
      }
      PrimitiveReadAccess access = accessFactory.get(expressions);
      ListExpression groupExpressions = new ListExpression();
      PrimitiveReadState state = new PrimitiveReadState(compressed ? 0 : rawAcc);
      for (Descriptor descriptor : group) {
        int dispatchId = getNumericDescriptorDispatchId(descriptor);
        Expression fieldValue =
            compressed
                ? readCompressed(groupExpressions, buffer, access, descriptor, dispatchId, state)
                : readFixed(access, descriptor, dispatchId, state);
        // `bean` will be replaced by `Reference` to cut-off expr dependency.
        groupExpressions.add(setFieldValue(bean, descriptor, fieldValue));
      }
      if (compressed) {
        if (state.acc != 0) {
          checkReadableBytesStub.setTargetObject(
              new Invoke(buffer, "checkReadableBytes", Literal.ofInt(state.acc)));
        }
        if (!state.compressStarted) {
          addIncReaderIndexExpr(groupExpressions, buffer, state.acc);
        }
      } else {
        rawAcc = state.acc;
      }
      if (hasFewFields() || numPrimitiveFields < 4 || isRecord) {
        expressions.add(groupExpressions);
      } else {
        expressions.add(
            objectCodecOptimizer.invokeGenerated(
                compressed ? access.compressedScope(bean) : access.fixedScope(bean),
                groupExpressions,
                "readFields"));
      }
    }
  }

  private Expression readCompressed(
      ListExpression expressions,
      Expression buffer,
      PrimitiveReadAccess access,
      Descriptor descriptor,
      int dispatchId,
      PrimitiveReadState state) {
    switch (dispatchId) {
      case DispatchId.VARINT32:
        startReadCompressed(expressions, buffer, state);
        return readVarInt32(buffer);
      case DispatchId.VAR_UINT32:
        startReadCompressed(expressions, buffer, state);
        return new StaticInvoke(
            Integer.class,
            "toUnsignedLong",
            descriptor.getTypeRef(),
            new Invoke(buffer, "readVarUInt32", PRIMITIVE_INT_TYPE));
      case DispatchId.VARINT64:
        startReadCompressed(expressions, buffer, state);
        return new Invoke(buffer, "readVarInt64", PRIMITIVE_LONG_TYPE);
      case DispatchId.TAGGED_INT64:
        startReadCompressed(expressions, buffer, state);
        return new Invoke(buffer, "readTaggedInt64", PRIMITIVE_LONG_TYPE);
      case DispatchId.VAR_UINT64:
        startReadCompressed(expressions, buffer, state);
        return new Invoke(buffer, "readVarUInt64", PRIMITIVE_LONG_TYPE);
      case DispatchId.TAGGED_UINT64:
        startReadCompressed(expressions, buffer, state);
        return new Invoke(buffer, "readTaggedUInt64", PRIMITIVE_LONG_TYPE);
      default:
        return readFixed(access, descriptor, dispatchId, state);
    }
  }

  private void startReadCompressed(
      ListExpression expressions, Expression buffer, PrimitiveReadState state) {
    if (!state.compressStarted) {
      state.compressStarted = true;
      addIncReaderIndexExpr(expressions, buffer, state.acc);
    }
  }

  private Expression readFixed(
      PrimitiveReadAccess access, Descriptor descriptor, int dispatchId, PrimitiveReadState state) {
    int acc = state.acc;
    switch (dispatchId) {
      case DispatchId.BOOL:
        state.acc = acc + 1;
        return access.getBoolean(acc);
      case DispatchId.INT8:
        state.acc = acc + 1;
        return access.getByte(acc);
      case DispatchId.UINT8:
        state.acc = acc + 1;
        return new StaticInvoke(
            Byte.class, "toUnsignedInt", descriptor.getTypeRef(), access.getByte(acc));
      case DispatchId.CHAR:
        state.acc = acc + 2;
        return access.getChar(acc);
      case DispatchId.INT16:
        state.acc = acc + 2;
        return access.getInt16(acc);
      case DispatchId.UINT16:
        state.acc = acc + 2;
        return new StaticInvoke(
            Short.class, "toUnsignedInt", descriptor.getTypeRef(), access.getInt16(acc));
      case DispatchId.FLOAT16:
        state.acc = acc + 2;
        return new StaticInvoke(
            Float16.class, "fromBits", TypeRef.of(Float16.class), access.getInt16(acc));
      case DispatchId.BFLOAT16:
        state.acc = acc + 2;
        return new StaticInvoke(
            BFloat16.class, "fromBits", TypeRef.of(BFloat16.class), access.getInt16(acc));
      case DispatchId.INT32:
        state.acc = acc + 4;
        return access.getInt32(acc);
      case DispatchId.UINT32:
        state.acc = acc + 4;
        return new StaticInvoke(
            Integer.class, "toUnsignedLong", descriptor.getTypeRef(), access.getInt32(acc));
      case DispatchId.INT64:
      case DispatchId.UINT64:
        state.acc = acc + 8;
        return access.getInt64(acc);
      case DispatchId.FLOAT32:
        state.acc = acc + 4;
        return access.getFloat32(acc);
      case DispatchId.FLOAT64:
        state.acc = acc + 8;
        return access.getFloat64(acc);
      default:
        throw new IllegalStateException("Unsupported dispatchId: " + dispatchId);
    }
  }

  private interface ReadAccessFactory {
    PrimitiveReadAccess get(List<Expression> expressions);
  }

  private static final class PrimitiveReadState {
    private int acc;
    private boolean compressStarted;

    private PrimitiveReadState(int acc) {
      this.acc = acc;
    }
  }

  private abstract class PrimitiveReadAccess {
    protected final Expression buffer;
    protected final Expression cursor;

    private PrimitiveReadAccess(Expression buffer, Expression cursor) {
      this.buffer = buffer;
      this.cursor = cursor;
    }

    abstract Expression getByte(int acc);

    abstract Expression getBoolean(int acc);

    abstract Expression getChar(int acc);

    abstract Expression getInt16(int acc);

    abstract Expression getInt32(int acc);

    abstract Expression getInt64(int acc);

    abstract Expression getFloat32(int acc);

    abstract Expression getFloat64(int acc);

    abstract Set<Expression> fixedScope(Expression bean);

    abstract Set<Expression> compressedScope(Expression bean);
  }

  private final class UnsafeReadAccess extends PrimitiveReadAccess {
    private final Expression heapBuffer;

    private UnsafeReadAccess(Expression buffer, Expression heapBuffer, Expression readerAddr) {
      super(buffer, readerAddr);
      this.heapBuffer = heapBuffer;
    }

    private Expression pos(int acc) {
      return getReaderAddress(cursor, acc);
    }

    @Override
    Expression getByte(int acc) {
      return unsafeGet(heapBuffer, pos(acc));
    }

    @Override
    Expression getBoolean(int acc) {
      return unsafeGetBoolean(heapBuffer, pos(acc));
    }

    @Override
    Expression getChar(int acc) {
      return unsafeGetChar(heapBuffer, pos(acc));
    }

    @Override
    Expression getInt16(int acc) {
      return unsafeGetShort(heapBuffer, pos(acc));
    }

    @Override
    Expression getInt32(int acc) {
      return unsafeGetInt(heapBuffer, pos(acc));
    }

    @Override
    Expression getInt64(int acc) {
      return unsafeGetLong(heapBuffer, pos(acc));
    }

    @Override
    Expression getFloat32(int acc) {
      return unsafeGetFloat(heapBuffer, pos(acc));
    }

    @Override
    Expression getFloat64(int acc) {
      return unsafeGetDouble(heapBuffer, pos(acc));
    }

    @Override
    Set<Expression> fixedScope(Expression bean) {
      return ofHashSet(bean, heapBuffer, cursor);
    }

    @Override
    Set<Expression> compressedScope(Expression bean) {
      return ofHashSet(bean, buffer, heapBuffer);
    }
  }

  private final class BufferReadAccess extends PrimitiveReadAccess {
    private BufferReadAccess(Expression buffer, Expression readerIndex) {
      super(buffer, readerIndex);
    }

    private Expression index(int acc) {
      return getBufferIndex(cursor, acc);
    }

    @Override
    Expression getByte(int acc) {
      return bufferGetByte(buffer, index(acc));
    }

    @Override
    Expression getBoolean(int acc) {
      return bufferGetBoolean(buffer, index(acc));
    }

    @Override
    Expression getChar(int acc) {
      return bufferGetChar(buffer, index(acc));
    }

    @Override
    Expression getInt16(int acc) {
      return bufferGetInt16(buffer, index(acc));
    }

    @Override
    Expression getInt32(int acc) {
      return bufferGetInt32(buffer, index(acc));
    }

    @Override
    Expression getInt64(int acc) {
      return bufferGetInt64(buffer, index(acc));
    }

    @Override
    Expression getFloat32(int acc) {
      return bufferGetFloat32(buffer, index(acc));
    }

    @Override
    Expression getFloat64(int acc) {
      return bufferGetFloat64(buffer, index(acc));
    }

    @Override
    Set<Expression> fixedScope(Expression bean) {
      return ofHashSet(bean, buffer, cursor);
    }

    @Override
    Set<Expression> compressedScope(Expression bean) {
      return ofHashSet(bean, buffer, cursor);
    }
  }

  private void addIncReaderIndexExpr(ListExpression expressions, Expression buffer, int diff) {
    if (diff != 0) {
      expressions.add(new Invoke(buffer, "increaseReaderIndex", Literal.ofInt(diff)));
    }
  }

  private Expression getReaderAddress(Expression readerPos, long acc) {
    if (acc == 0) {
      return readerPos;
    }
    return add(readerPos, new Literal(acc, PRIMITIVE_LONG_TYPE));
  }

  private Expression getBufferIndex(Expression index, int acc) {
    if (acc == 0) {
      return index;
    }
    return add(index, Literal.ofInt(acc));
  }
}
