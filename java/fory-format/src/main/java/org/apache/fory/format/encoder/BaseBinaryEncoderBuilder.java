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

import static org.apache.fory.type.TypeUtils.PRIMITIVE_INT_TYPE;
import static org.apache.fory.type.TypeUtils.getRawType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.apache.fory.builder.CodecBuilder;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Arithmetic;
import org.apache.fory.codegen.Expression.AssignArrayElem;
import org.apache.fory.codegen.Expression.Cast;
import org.apache.fory.codegen.Expression.FieldValue;
import org.apache.fory.codegen.Expression.ForEach;
import org.apache.fory.codegen.Expression.If;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.codegen.Expression.ListExpression;
import org.apache.fory.codegen.Expression.ListFromIterable;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.codegen.Expression.NewArray;
import org.apache.fory.codegen.Expression.NewInstance;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.Expression.StaticInvoke;
import org.apache.fory.codegen.Expression.ZipForEach;
import org.apache.fory.codegen.ExpressionUtils;
import org.apache.fory.format.row.ArrayData;
import org.apache.fory.format.row.Getters;
import org.apache.fory.format.row.MapData;
import org.apache.fory.format.row.Row;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.BinaryUtils;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.apache.fory.format.row.binary.writer.BinaryWriter;
import org.apache.fory.format.type.CustomTypeEncoderRegistry;
import org.apache.fory.format.type.CustomTypeHandler;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeResolutionContext;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.DateTimeUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;

/** Base encoder builder for {@link Row}, {@link ArrayData} and {@link MapData}. */
public abstract class BaseBinaryEncoderBuilder extends CodecBuilder {
  protected static final String REFERENCES_NAME = "references";
  protected static final TypeRef<Schema> SCHEMA_TYPE = TypeRef.of(Schema.class);
  protected static final TypeRef<Field> FORY_FIELD_TYPE = TypeRef.of(Field.class);
  protected static TypeRef<Schema> schemaTypeToken = TypeRef.of(Schema.class);
  protected static TypeRef<BinaryWriter> writerTypeToken = TypeRef.of(BinaryWriter.class);
  protected static TypeRef<Row> rowTypeToken = TypeRef.of(Row.class);
  protected static TypeRef<BinaryRow> binaryRowTypeToken = TypeRef.of(BinaryRow.class);
  protected static TypeRef<BinaryArray> binaryArrayTypeToken = TypeRef.of(BinaryArray.class);

  protected final Map<TypeRef<?>, Reference> arrayWriterMap = new HashMap<>();
  protected final Map<TypeRef<?>, Reference> beanEncoderMap = new HashMap<>();
  // We need to call beanEncoder's rowWriter.reset() before write a corresponding nested bean every
  // time.
  // Outermost beanEncoder's rowWriter.reset() should be called outside generated code before
  // writer an outermost bean every time.
  protected final Map<TypeRef<?>, Reference> rowWriterMap = new HashMap<>();
  protected final CustomTypeHandler customTypeHandler =
      CustomTypeEncoderRegistry.customTypeHandler();
  protected final TypeResolutionContext typeCtx;

  public BaseBinaryEncoderBuilder(CodegenContext context, Class<?> beanClass) {
    this(context, TypeRef.of(beanClass));
  }

  public BaseBinaryEncoderBuilder(CodegenContext context, TypeRef<?> beanType) {
    super(context, beanType);
    ctx.reserveName(REFERENCES_NAME);

    ctx.addImport(BinaryRow.class.getPackage().getName() + ".*");
    ctx.addImport(BinaryWriter.class.getPackage().getName() + ".*");
    ctx.addImport(Schema.class.getPackage().getName() + ".*");
    TypeResolutionContext typeCtx = new TypeResolutionContext(customTypeHandler, true);
    typeCtx.appendTypePath(beanClass);
    this.typeCtx = typeCtx;
  }

  @Override
  public String codecClassName(Class<?> beanClass) {
    return codecClassName(beanClass, "");
  }

  public String codecClassName(Class<?> beanClass, String prefix) {
    String name =
        ReflectionUtils.getClassNameWithoutPackage(beanClass)
            + prefix
            + codecSuffix()
            + CodeGenerator.getClassUniqueId(beanClass);
    return name.replace("$", "_");
  }

  protected String codecSuffix() {
    return "RowCodec";
  }

  public String codecQualifiedClassName(Class<?> beanClass) {
    return CodeGenerator.getPackage(beanClass) + "." + codecClassName(beanClass);
  }

  public String codecQualifiedClassName(Class<?> beanClass, String prefix) {
    return CodeGenerator.getPackage(beanClass) + "." + codecClassName(beanClass, prefix);
  }

  protected TypeRef<? extends BaseBinaryRowWriter> rowWriterType() {
    return TypeRef.of(BinaryRowWriter.class);
  }

  protected TypeRef<? extends BinaryArrayWriter> arrayWriterType() {
    return TypeRef.of(BinaryArrayWriter.class);
  }

  /**
   * Return an expression for serializing an object of given type to row format representation. The
   * inputObject will be written to position <code>ordinal</code> of row/array using given <code>
   * writer</code>
   */
  protected Expression serializeFor(
      Expression ordinal,
      Expression inputObject,
      Expression writer,
      TypeRef<?> typeRef,
      Field fieldIfKnown,
      Expression arrowField,
      Set<TypeRef<?>> visitedCustomTypes) {
    Class<?> rawType = getRawType(typeRef);
    TypeRef<?> rewrittenType = customTypeHandler.replacementTypeFor(beanType.getRawType(), rawType);
    if (rewrittenType != null
        && !visitedCustomTypes.contains(typeRef)
        && !typeRef.equals(rewrittenType)) {
      Expression newInputObject = customEncode(inputObject, rewrittenType);
      visitedCustomTypes.add(typeRef);
      Expression doSerialize =
          serializeFor(
              ordinal,
              newInputObject,
              writer,
              rewrittenType,
              fieldIfKnown,
              arrowField,
              visitedCustomTypes);
      return new If(
          new Expression.IsNull(inputObject),
          new Invoke(writer, "setNullAt", ordinal),
          doSerialize);
    } else if (rawType == Optional.class) {
      TypeRef<?> elemType = TypeUtils.getTypeArguments(typeRef).get(0);
      Invoke orNull =
          new Invoke(
              inputObject,
              "orElse",
              "",
              TypeUtils.OBJECT_TYPE,
              true,
              new Expression.Null(elemType));
      Expression unwrapped =
          new If(new Expression.IsNull(inputObject), new Expression.Null(elemType), orNull);
      return serializeFor(
          ordinal,
          new Expression.Cast(unwrapped, elemType),
          writer,
          elemType,
          fieldIfKnown,
          arrowField,
          visitedCustomTypes);
    } else if (TypeUtils.isPrimitive(rawType)) {
      return new ListExpression(
          // notNull is by default, no need to call setNotNullAt
          new Invoke(writer, "write", ordinal, inputObject));
    } else if (TypeUtils.isBoxed(rawType)) {
      // janino support autoboxing and unboxing, so we don't need to call intValue/longValue....
      return setValueOrNull(writer, ordinal, inputObject, inputObject);
    } else if (rawType == BigDecimal.class) {
      return setValueOrNull(writer, ordinal, inputObject, inputObject);
    } else if (rawType == java.math.BigInteger.class) {
      Invoke value = new Invoke(inputObject, "toByteArray", TypeRef.of(byte[].class));
      return setValueOrNull(writer, ordinal, inputObject, value);
    } else if (rawType == OptionalInt.class) {
      return serializeForOptional(ordinal, inputObject, writer, "getAsInt", TypeUtils.INT_TYPE);
    } else if (rawType == OptionalLong.class) {
      return serializeForOptional(ordinal, inputObject, writer, "getAsLong", TypeUtils.LONG_TYPE);
    } else if (rawType == OptionalDouble.class) {
      return serializeForOptional(
          ordinal, inputObject, writer, "getAsDouble", TypeUtils.DOUBLE_TYPE);
    } else if (rawType == java.time.LocalDate.class) {
      StaticInvoke value =
          new StaticInvoke(
              DateTimeUtils.class,
              "localDateToDays",
              TypeUtils.PRIMITIVE_INT_TYPE,
              false,
              inputObject);
      return setValueOrNull(writer, ordinal, inputObject, value);
    } else if (rawType == java.sql.Date.class) {
      StaticInvoke value =
          new StaticInvoke(
              DateTimeUtils.class,
              "fromJavaDate",
              TypeUtils.PRIMITIVE_INT_TYPE,
              false,
              inputObject);
      return setValueOrNull(writer, ordinal, inputObject, value);
    } else if (rawType == java.sql.Timestamp.class) {
      StaticInvoke value =
          new StaticInvoke(
              DateTimeUtils.class,
              "fromJavaTimestamp",
              TypeUtils.PRIMITIVE_LONG_TYPE,
              false,
              inputObject);
      return setValueOrNull(writer, ordinal, inputObject, value);
    } else if (rawType == java.time.Instant.class) {
      StaticInvoke value =
          new StaticInvoke(
              DateTimeUtils.class,
              "instantToMicros",
              TypeUtils.PRIMITIVE_LONG_TYPE,
              false,
              inputObject);
      return setValueOrNull(writer, ordinal, inputObject, value);
    } else if (rawType == String.class) {
      return setValueOrNull(writer, ordinal, inputObject, inputObject);
    } else if (rawType.isEnum()) {
      Invoke value = new Invoke(inputObject, "name", TypeUtils.STRING_TYPE);
      return setValueOrNull(writer, ordinal, inputObject, value);
    } else if (rawType.isArray() || TypeUtils.ITERABLE_TYPE.isSupertypeOf(typeRef)) {
      // place outer writer operations here, because map key/value arrays need to call
      // serializeForArray,
      // but don't setOffsetAndSize for array.
      Invoke originalWriterIndex =
          new Invoke(writer, "writerIndex", "writerIndex", TypeUtils.PRIMITIVE_INT_TYPE);
      Expression serializeArray =
          serializeForArray(inputObject, writer, typeRef, fieldIfKnown, arrowField);
      Arithmetic size =
          ExpressionUtils.subtract(
              new Invoke(writer, "writerIndex", "writerIndex", TypeUtils.PRIMITIVE_INT_TYPE),
              originalWriterIndex);
      Invoke setOffsetAndSize =
          new Invoke(writer, "setOffsetAndSize", ordinal, originalWriterIndex, size);
      Expression finishArrayWrite = new ListExpression(size, setOffsetAndSize);
      ListExpression expression =
          new ListExpression(originalWriterIndex, serializeArray, finishArrayWrite);
      return new If(
          ExpressionUtils.eqNull(inputObject),
          new Invoke(writer, "setNullAt", ordinal),
          expression);
    } else if (TypeUtils.MAP_TYPE.isSupertypeOf(typeRef)) {
      return serializeForMap(ordinal, writer, inputObject, typeRef, fieldIfKnown, arrowField);
    } else if (TypeUtils.isBean(rawType, typeCtx)) {
      return serializeForBean(ordinal, writer, inputObject, fieldIfKnown, typeRef, arrowField);
    } else if (rawType == BinaryArray.class) {
      Invoke writeExp =
          new Invoke(
              writer,
              "writeUnaligned",
              TypeRef.of(void.class),
              ordinal,
              Invoke.inlineInvoke(inputObject, "getBuffer", TypeRef.of(MemoryBuffer.class)),
              Invoke.inlineInvoke(inputObject, "getBaseOffset", TypeRef.of(int.class)),
              Invoke.inlineInvoke(inputObject, "getSizeInBytes", TypeRef.of(int.class)));
      return new If(
          ExpressionUtils.eqNull(inputObject), new Invoke(writer, "setNullAt", ordinal), writeExp);
    } else if (rawType == MemoryBuffer.class) {
      Invoke writeExp =
          new Invoke(
              writer,
              "writeUnaligned",
              TypeRef.of(void.class),
              ordinal,
              inputObject,
              Invoke.inlineInvoke(inputObject, "readerIndex", TypeRef.of(int.class)),
              Invoke.inlineInvoke(inputObject, "remaining", TypeRef.of(int.class)));
      return new If(
          ExpressionUtils.eqNull(inputObject), new Invoke(writer, "setNullAt", ordinal), writeExp);
    } else {
      return serializeForObject(ordinal, writer, inputObject);
    }
  }

  protected Expression serializeForArray(
      Expression inputObject,
      Expression writer,
      TypeRef<?> typeRef,
      Field fieldIfKnown,
      Expression arrowField) {
    Reference arrayWriter = getOrCreateArrayWriter(typeRef, arrowField, writer);
    return serializeForArrayByWriter(inputObject, arrayWriter, typeRef, fieldIfKnown, arrowField);
  }

  protected Expression serializeForArrayByWriter(
      Expression inputObject,
      Expression arrayWriter,
      TypeRef<?> typeRef,
      Field fieldIfKnown,
      Expression arrowField) {
    StaticInvoke arrayElementField =
        new StaticInvoke(
            DataTypes.class, "arrayElementField", "elemField", FORY_FIELD_TYPE, false, arrowField);
    Class<?> rawType = getRawType(typeRef);
    if (rawType.isArray()) {
      FieldValue length = new FieldValue(inputObject, "length", TypeUtils.PRIMITIVE_INT_TYPE);
      Expression reset = new Invoke(arrayWriter, "reset", length);
      if (rawType.getComponentType().isPrimitive()) {
        return new ListExpression(
            reset, new Invoke(arrayWriter, "fromPrimitiveArray", inputObject), arrayWriter);
      } else {
        ForEach forEach =
            new ForEach(
                inputObject,
                !rawType.getComponentType().isPrimitive(),
                (i, value) ->
                    serializeFor(
                        i,
                        value,
                        arrayWriter,
                        Objects.requireNonNull(typeRef.getComponentType()),
                        null,
                        arrayElementField,
                        new HashSet<>()));
        return new ListExpression(reset, forEach, arrayWriter);
      }
    } else {
      if (getRawType(typeRef) == Iterable.class) {
        inputObject = new ListFromIterable(inputObject);
      }
      Invoke size = new Invoke(inputObject, "size", TypeUtils.PRIMITIVE_INT_TYPE);
      Expression reset = new Invoke(arrayWriter, "reset", size);
      ForEach forEach =
          new ForEach(
              inputObject,
              !TypeUtils.getElementType(typeRef).isPrimitive(),
              (i, value) ->
                  serializeFor(
                      i,
                      value,
                      arrayWriter,
                      TypeUtils.getElementType(typeRef),
                      null,
                      arrayElementField,
                      new HashSet<>()));
      return new ListExpression(reset, forEach, arrayWriter);
    }
  }

  protected Reference getOrCreateArrayWriter(
      TypeRef<?> typeRef, Expression arrayDataType, Expression writer) {
    return arrayWriterMap.computeIfAbsent(
        typeRef,
        t -> {
          String name = ctx.newName("arrayWriter");
          ctx.addField(
              arrayWriterType().getRawType(),
              name,
              new NewInstance(arrayWriterType(), arrayDataType, writer));
          return new Reference(name, arrayWriterType(), false);
        });
  }

  /**
   * Returns an expression to write map <code>inputObject</code> to position <code>ordinal</code> of
   * row/array using given <code>writer</code>.
   */
  protected Expression serializeForMap(
      Expression ordinal,
      Expression writer,
      Expression inputObject,
      TypeRef<?> typeRef,
      Field fieldIfKnown,
      Expression arrowField) {
    StaticInvoke keyArrayField =
        new StaticInvoke(
            DataTypes.class,
            "keyArrayFieldForMap",
            "keyArrayField",
            FORY_FIELD_TYPE,
            false,
            arrowField);
    StaticInvoke valueArrayField =
        new StaticInvoke(
            DataTypes.class,
            "itemArrayFieldForMap",
            "valueArrayField",
            FORY_FIELD_TYPE,
            false,
            arrowField);

    @SuppressWarnings("unchecked")
    TypeRef<?> supertype = ((TypeRef<? extends Map<?, ?>>) typeRef).getSupertype(Map.class);
    TypeRef<?> keySetType = supertype.resolveType(TypeUtils.KEY_SET_RETURN_TYPE);
    TypeRef<?> valuesType = supertype.resolveType(TypeUtils.VALUES_RETURN_TYPE);

    ListExpression expressions = new ListExpression();

    Invoke offset = new Invoke(writer, "writerIndex", "writerIndex", TypeUtils.PRIMITIVE_INT_TYPE);
    // preserve 8 bytes to write the key array numBytes later
    Invoke preserve = new Invoke(writer, "writeDirectly", Literal.ofInt(-1));
    expressions.add(offset, preserve);

    Invoke keySet = new Invoke(inputObject, "keySet", keySetType);
    Expression keySerializationExpr =
        serializeForArray(keySet, writer, keySetType, fieldIfKnown, keyArrayField);
    expressions.add(keySet, keySerializationExpr);

    expressions.add(
        new Expression.Invoke(
            writer,
            "writeDirectly",
            offset,
            Expression.Invoke.inlineInvoke(keySerializationExpr, "size", PRIMITIVE_INT_TYPE)));

    Invoke values = new Invoke(inputObject, "values", valuesType);
    Expression valueSerializationExpr =
        serializeForArray(values, writer, valuesType, fieldIfKnown, valueArrayField);
    expressions.add(values, valueSerializationExpr);

    Arithmetic size =
        ExpressionUtils.subtract(
            new Invoke(writer, "writerIndex", "writerIndex", PRIMITIVE_INT_TYPE), offset);
    Invoke setOffsetAndSize = new Invoke(writer, "setOffsetAndSize", ordinal, offset, size);
    expressions.add(setOffsetAndSize);

    return new If(
        new Expression.IsNull(inputObject), new Invoke(writer, "setNullAt", ordinal), expressions);
  }

  /**
   * Returns an expression to write bean <code>inputObject</code> to position <code>ordinal</code>
   * of row/array using given <code>writer</code>.
   */
  protected Expression serializeForBean(
      Expression ordinal,
      Expression writer,
      Expression inputObject,
      Field fieldIfKnown,
      TypeRef<?> typeRef,
      Expression structField) {
    Class<?> rawType = getRawType(typeRef);
    Reference rowWriter;
    Reference beanEncoder = beanEncoderMap.get(typeRef);
    if (beanEncoder == null) {
      // janino generics don't add cast, so this `<${type}>` is only for generated code readability
      Expression schema = createSchemaFromStructField(structField);
      String rowWriterName =
          ctx.newName(StringUtils.uncapitalize(rawType.getSimpleName() + "RowWriter"));
      NewInstance newRowWriter = new NewInstance(rowWriterType(), schema, writer);
      ctx.addField(ctx.type(rowWriterType()), rowWriterName, newRowWriter);

      Preconditions.checkArgument(!codecClassName(rawType).contains("."));
      String encoderName = ctx.newName(StringUtils.uncapitalize(codecClassName(rawType)));
      String encoderClass = codecQualifiedClassName(rawType);
      TypeRef<?> codecTypeRef = TypeRef.of(GeneratedRowEncoder.class);
      NewInstance newEncoder =
          new NewInstance(
              codecTypeRef,
              encoderClass,
              ExpressionUtils.newObjectArray(schema, newRowWriter, foryRef));
      ctx.addField(encoderClass, encoderName, newEncoder);

      rowWriter = new Reference(rowWriterName, rowWriterType());
      rowWriterMap.put(typeRef, rowWriter);
      beanEncoder = new Reference(encoderName, codecTypeRef);
      beanEncoderMap.put(typeRef, beanEncoder);
    }
    rowWriter = rowWriterMap.get(typeRef);

    Expression expression =
        serializeForNotNullBean(ordinal, writer, inputObject, fieldIfKnown, rowWriter, beanEncoder);

    return new If(
        new Expression.IsNull(inputObject), new Invoke(writer, "setNullAt", ordinal), expression);
  }

  protected Expression createSchemaFromStructField(Expression structField) {
    return new StaticInvoke(
        DataTypes.class, "schemaFromStructField", "schema", SCHEMA_TYPE, false, structField);
  }

  protected Expression serializeForNotNullBean(
      Expression ordinal,
      Expression writer,
      Expression inputObject,
      Field fieldIfKnown,
      Reference rowWriter,
      Reference beanEncoder) {
    Invoke reset = beanWriterReset(writer, rowWriter, ordinal);
    Invoke offset = new Invoke(writer, "writerIndex", "writerIndex", TypeUtils.PRIMITIVE_INT_TYPE);
    Invoke toRow = new Invoke(beanEncoder, "toRow", inputObject);
    Arithmetic size =
        ExpressionUtils.subtract(
            new Invoke(writer, "writerIndex", "writerIndex", TypeUtils.PRIMITIVE_INT_TYPE), offset);
    Invoke setOffsetAndSize = new Invoke(writer, "setOffsetAndSize", ordinal, offset, size);
    return new ListExpression(
        offset,
        reset, // reset will change writerIndex. must call reset and toRow in pair.
        toRow,
        size,
        setOffsetAndSize);
  }

  protected Invoke beanWriterReset(Expression writer, Reference rowWriter, Expression ordinal) {
    return new Invoke(rowWriter, "reset");
  }

  /**
   * Return an expression to serialize opaque <code>inputObject</code> as binary using <code>fory
   * </code>. When deserialization, using fory to deserialize sliced MemoryBuffer. See {@link
   * BinaryUtils#getElemAccessMethodName(TypeRef)}, {@link Getters#getBuffer(int)}
   */
  protected Expression serializeForObject(
      Expression ordinal, Expression writer, Expression inputObject) {
    Invoke offset = new Invoke(writer, "writerIndex", "writerIndex", TypeUtils.PRIMITIVE_INT_TYPE);
    Invoke buffer = new Invoke(writer, "getBuffer", "buffer", TypeRef.of(MemoryBuffer.class));
    Expression setWriterIndex = new Invoke(buffer, "writerIndex", offset);
    Invoke serialize = new Invoke(foryRef, "serialize", buffer, inputObject);
    Invoke newWriterIndex =
        new Invoke(buffer, "writerIndex", "writerIndex", TypeUtils.PRIMITIVE_INT_TYPE);
    Arithmetic size = ExpressionUtils.subtract(newWriterIndex, offset, "size");
    Expression increaseWriterIndexToAligned =
        new Invoke(writer, "increaseWriterIndexToAligned", size);
    Invoke setOffsetAndSize = new Invoke(writer, "setOffsetAndSize", ordinal, offset, size);
    ListExpression expression =
        new ListExpression(
            offset,
            buffer,
            setWriterIndex,
            serialize,
            increaseWriterIndexToAligned,
            setOffsetAndSize);
    return new If(
        new Expression.IsNull(inputObject), new Invoke(writer, "setNullAt", ordinal), expression);
  }

  protected Expression serializeForOptional(
      Expression ordinal,
      Expression inputObject,
      Expression writer,
      String getMethod,
      TypeRef<?> wrapType) {
    Class<?> primType = TypeUtils.unwrap(wrapType.getRawType());
    If value =
        new If(
            new Expression.LogicalAnd(
                true,
                new Expression.Not(new Expression.IsNull(inputObject)),
                Invoke.inlineInvoke(inputObject, "isPresent", TypeUtils.PRIMITIVE_BOOLEAN_TYPE)),
            new Invoke(inputObject, getMethod, TypeRef.of(primType)),
            new Expression.Null(wrapType),
            true,
            wrapType);
    return setValueOrNull(writer, ordinal, value, value);
  }

  protected Expression setValueOrNull(
      Expression writer, Expression ordinal, Expression inputObject, Expression value) {
    Expression action = new Invoke(writer, "write", ordinal, value);
    return new If(
        new Expression.IsNull(inputObject), new Invoke(writer, "setNullAt", ordinal), action);
  }

  /**
   * Returns an expression that deserialize <code>value</code> as a java object of type <code>
   * typeToken</code>.
   */
  protected Expression deserializeFor(
      Expression value,
      TypeRef<?> typeRef,
      TypeResolutionContext ctx,
      Set<TypeRef<?>> visitedCustomTypes) {
    Class<?> rawType = getRawType(typeRef);
    TypeRef<?> rewrittenType = customTypeHandler.replacementTypeFor(beanType.getRawType(), rawType);
    if (rewrittenType != null
        && !visitedCustomTypes.contains(typeRef)
        && !typeRef.equals(rewrittenType)) {
      visitedCustomTypes.add(typeRef);
      final Expression deserializedValue =
          deserializeFor(value, rewrittenType, ctx, visitedCustomTypes);
      return customDecode(typeRef, deserializedValue);
    } else if (rawType == Optional.class) {
      TypeRef<?> elemType = TypeUtils.getTypeArguments(typeRef).get(0);
      return new Expression.StaticInvoke(
          Optional.class,
          "ofNullable",
          "optional",
          typeRef,
          true,
          deserializeFor(value, elemType, ctx, visitedCustomTypes));
    } else if (TypeUtils.isPrimitive(rawType) || TypeUtils.isBoxed(rawType)) {
      return value;
    } else if (rawType == BigDecimal.class) {
      return value;
    } else if (rawType == java.math.BigInteger.class) {
      return new NewInstance(TypeUtils.BIG_INTEGER_TYPE, value);
    } else if (rawType == OptionalInt.class
        || rawType == OptionalLong.class
        || rawType == OptionalDouble.class) {
      return deserializeForOptional(value, TypeRef.of(rawType));
    } else if (rawType == java.time.LocalDate.class) {
      return new StaticInvoke(
          DateTimeUtils.class, "daysToLocalDate", TypeUtils.LOCAL_DATE_TYPE, false, value);
    } else if (rawType == java.sql.Date.class) {
      return new StaticInvoke(DateTimeUtils.class, "toJavaDate", TypeUtils.DATE_TYPE, false, value);
    } else if (rawType == java.sql.Timestamp.class) {
      return new StaticInvoke(
          DateTimeUtils.class, "toJavaTimestamp", TypeUtils.TIMESTAMP_TYPE, false, value);
    } else if (rawType == java.time.Instant.class) {
      return new StaticInvoke(
          DateTimeUtils.class, "microsToInstant", TypeUtils.INSTANT_TYPE, false, value);
    } else if (rawType == String.class) {
      return value;
    } else if (rawType == MemoryBuffer.class) {
      return value;
    } else if (rawType == BinaryArray.class) {
      return value;
    } else if (rawType.isEnum()) {
      return ExpressionUtils.valueOf(typeRef, value);
    } else if (rawType.isArray()) {
      return deserializeForArray(value, typeRef);
    } else if (TypeUtils.ITERABLE_TYPE.isSupertypeOf(typeRef)) {
      return deserializeForCollection(value, typeRef);
    } else if (TypeUtils.MAP_TYPE.isSupertypeOf(typeRef)) {
      return deserializeForMap(value, typeRef);
    } else if (TypeUtils.isBean(rawType, ctx)) {
      return deserializeForBean(value, typeRef);
    } else {
      return deserializeForObject(value, typeRef);
    }
  }

  /**
   * Returns an expression that deserialize <code>row</code> as a java bean of type <code>typeToken
   * </code>.
   */
  protected Expression deserializeForBean(Expression row, TypeRef<?> typeRef) {
    Reference beanEncoder = beanEncoderMap.get(typeRef);
    if (beanEncoder == null) {
      throw new IllegalStateException("beanEncoder should have be added in serializeForBean()");
    }
    Invoke beanObj = new Invoke(beanEncoder, "fromRow", TypeUtils.OBJECT_TYPE, false, row);
    return new Cast(beanObj, typeRef, "bean");
  }

  /** Returns an expression that deserialize <code>mapData</code> as a java map. */
  protected Expression deserializeForMap(Expression mapData, TypeRef<?> typeRef) {
    Expression javaMap = newMap(typeRef);
    @SuppressWarnings("unchecked")
    TypeRef<?> supertype = ((TypeRef<? extends Map<?, ?>>) typeRef).getSupertype(Map.class);
    TypeRef<?> keySetType = supertype.resolveType(TypeUtils.KEY_SET_RETURN_TYPE);
    TypeRef<?> keysType = TypeUtils.getCollectionType(keySetType);
    TypeRef<?> valuesType = supertype.resolveType(TypeUtils.VALUES_RETURN_TYPE);
    Expression keyArray = new Invoke(mapData, "keyArray", binaryArrayTypeToken, false);
    Expression valueArray = new Invoke(mapData, "valueArray", binaryArrayTypeToken, false);
    Expression keyJavaArray;
    Expression valueJavaArray;
    if (TypeUtils.ITERABLE_TYPE.isSupertypeOf(keysType)) {
      keyJavaArray = deserializeForCollection(keyArray, keysType);
    } else {
      keyJavaArray = deserializeForArray(keyArray, keysType);
    }
    if (TypeUtils.ITERABLE_TYPE.isSupertypeOf(valuesType)) {
      valueJavaArray = deserializeForCollection(valueArray, valuesType);
    } else {
      valueJavaArray = deserializeForArray(valueArray, valuesType);
    }

    ZipForEach put =
        new ZipForEach(
            keyJavaArray,
            valueJavaArray,
            (i, key, value) ->
                new If(ExpressionUtils.notNull(key), new Invoke(javaMap, "put", key, value)));
    return new ListExpression(javaMap, put, javaMap);
  }

  /** Returns an expression that deserialize <code>arrayData</code> as a java collection. */
  protected Expression deserializeForCollection(Expression arrayData, TypeRef<?> typeRef) {
    TypeRef<?> elemType = TypeUtils.getElementType(typeRef);
    if (typeRef.getRawType() == List.class) {
      return new LazyArrayData(
          arrayData,
          elemType,
          (i, value) -> deserializeFor(value, elemType, typeCtx, new HashSet<>()),
          ExpressionUtils.nullValue(elemType));
    }
    Expression collection = newCollection(arrayData, typeRef);
    try {
      ArrayDataForEach addElemsOp =
          new ArrayDataForEach(
              arrayData,
              elemType,
              (i, value) ->
                  new Invoke(
                      collection, "add", deserializeFor(value, elemType, typeCtx, new HashSet<>())),
              i -> new Invoke(collection, "add", ExpressionUtils.nullValue(elemType)));
      return new ListExpression(collection, addElemsOp, collection);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected Expression deserializeForOptional(Expression value, TypeRef<?> type) {
    return new Expression.If(
        new Expression.IsNull(value),
        new Expression.StaticInvoke(type.getRawType(), "empty", type),
        new Expression.StaticInvoke(type.getRawType(), "of", type, value),
        false,
        type);
  }

  /**
   * Create a java collection. Create a {@link ArrayList} if <code>typeToken</code> is super class
   * of {@link ArrayList}; Create a {@link HashSet} if <code>typeToken</code> is super class of
   * {@link HashSet}; Create an instance of <code>typeToken</code> otherwise.
   */
  protected Expression newCollection(Expression arrayData, TypeRef<?> typeRef) {
    Class<?> clazz = getRawType(typeRef);
    Expression size = Invoke.inlineInvoke(arrayData, "numElements", TypeRef.of(int.class));
    Expression collection;
    TypeRef<?> componentClass = TypeUtils.getElementType(typeRef);
    CustomCollectionFactory<?, ?> customFactory =
        customTypeHandler.findCollectionFactory(clazz, componentClass.getRawType());
    if (customFactory != null) {
      collection =
          new StaticInvoke(
              customTypeHandler.getClass(),
              "newCollection",
              typeRef,
              false,
              new Expression.Null(typeRef, true),
              new Expression.Null(componentClass, true),
              size);
    } else if (TypeRef.of(clazz).isSupertypeOf(TypeRef.of(ArrayList.class))) {
      collection = new NewInstance(TypeRef.of(ArrayList.class), size);
    } else if (TypeRef.of(clazz).isSupertypeOf(TypeRef.of(HashSet.class))) {
      collection = new NewInstance(TypeRef.of(HashSet.class), size);
    } else {
      if (ReflectionUtils.isAbstract(clazz) || clazz.isInterface()) {
        String msg = String.format("class %s can't be abstract or interface", clazz);
        throw new UnsupportedOperationException(msg);
      }
      collection = new NewInstance(typeRef);
    }
    return collection;
  }

  /**
   * Create a java map. Create a {@link HashMap} if <code>typeToken</code> is super class of
   * HashMap; Create an instance of <code>typeToken</code> otherwise.
   */
  protected Expression newMap(TypeRef<?> typeRef) {
    Class<?> clazz = getRawType(typeRef);
    Expression javaMap;
    // use TypeToken.of(clazz) rather typeToken to strip generics.
    if (TypeRef.of(clazz).isSupertypeOf(TypeRef.of(HashMap.class))) {
      javaMap = new NewInstance(TypeRef.of(HashMap.class));
    } else {
      if (ReflectionUtils.isAbstract(clazz) || clazz.isInterface()) {
        String msg = String.format("class %s can't be abstract or interface", clazz);
        throw new UnsupportedOperationException(msg);
      }
      javaMap = new NewInstance(typeRef);
    }
    return javaMap;
  }

  /**
   * Returns an expression to deserialize multi-array from <code>arrayData</code>, and set value to
   * <code>rootJavaArray</code>.
   */
  protected Expression deserializeForMultiDimensionArray(
      Expression arrayData,
      Expression rootJavaArray,
      int numDimensions,
      TypeRef<?> typeRef,
      Expression[] indexes) {
    Preconditions.checkArgument(numDimensions > 1);
    Preconditions.checkArgument(typeRef.isArray());
    TypeRef<?> elemType = typeRef.getComponentType();
    if (numDimensions == 2) {
      return new ArrayDataForEach(
          arrayData,
          elemType,
          (i, value) -> {
            Expression[] newIndexes = Arrays.copyOf(indexes, indexes.length + 1);
            newIndexes[indexes.length] = i;
            Expression elemArr =
                deserializeForArray(value, Objects.requireNonNull(typeRef.getComponentType()));
            return new AssignArrayElem(rootJavaArray, elemArr, newIndexes);
          });
    } else {
      return new ArrayDataForEach(
          arrayData,
          elemType,
          (i, value) -> {
            Expression[] newIndexes = Arrays.copyOf(indexes, indexes.length + 1);
            newIndexes[indexes.length] = i;
            return deserializeForMultiDimensionArray(
                value, rootJavaArray, numDimensions - 1, elemType, newIndexes);
          });
    }
  }

  /**
   * Return an expression that deserialize <code>arrayData</code>. If array is multi-array, forward
   * to {@link BaseBinaryEncoderBuilder#deserializeForMultiDimensionArray}
   */
  protected Expression deserializeForArray(Expression arrayData, TypeRef<?> typeRef) {
    int numDimensions = TypeUtils.getArrayDimensions(typeRef);
    if (numDimensions > 1) {
      // If some dimension's elements is all null, we take outer-most array as null,
      // and don't create multi-array. return an no-ops expression or return null.
      StaticInvoke dimensions =
          new StaticInvoke(
              BinaryArray.class,
              "getDimensions",
              "dims",
              TypeRef.of(int[].class),
              true,
              arrayData,
              new Literal(numDimensions, TypeUtils.INT_TYPE));
      TypeRef<?> innerElemType = TypeUtils.getMultiDimensionArrayElementType(typeRef);
      Class<?> innerElemClass = getRawType(innerElemType);
      Expression rootJavaMultiDimArray = new NewArray(innerElemClass, numDimensions, dimensions);
      Expression op =
          deserializeForMultiDimensionArray(
              arrayData, rootJavaMultiDimArray, numDimensions, typeRef, new Expression[0]);
      // although the value maybe null, we don't use this info, so we set nullability to false.
      return new If(
          ExpressionUtils.notNull(dimensions),
          new ListExpression(rootJavaMultiDimArray, op, rootJavaMultiDimArray),
          ExpressionUtils.nullValue(rootJavaMultiDimArray.type()),
          false);
    } else {
      TypeRef<?> elemType = typeRef.getComponentType();
      Class<?> innerElemClass = getRawType(Objects.requireNonNull(elemType));
      if (byte.class == innerElemClass) {
        return new Invoke(arrayData, "toByteArray", TypeUtils.PRIMITIVE_BYTE_ARRAY_TYPE);
      } else if (boolean.class == innerElemClass) {
        return new Invoke(arrayData, "toBooleanArray", TypeUtils.PRIMITIVE_BOOLEAN_ARRAY_TYPE);
      } else if (short.class == innerElemClass) {
        return new Invoke(arrayData, "toShortArray", TypeUtils.PRIMITIVE_SHORT_ARRAY_TYPE);
      } else if (int.class == innerElemClass) {
        return new Invoke(arrayData, "toIntArray", TypeUtils.PRIMITIVE_INT_ARRAY_TYPE);
      } else if (long.class == innerElemClass) {
        return new Invoke(arrayData, "toLongArray", TypeUtils.PRIMITIVE_LONG_ARRAY_TYPE);
      } else if (float.class == innerElemClass) {
        return new Invoke(arrayData, "toFloatArray", TypeUtils.PRIMITIVE_FLOAT_ARRAY_TYPE);
      } else if (double.class == innerElemClass) {
        return new Invoke(arrayData, "toDoubleArray", TypeUtils.PRIMITIVE_DOUBLE_ARRAY_TYPE);
      } else {
        Invoke dim = new Invoke(arrayData, "numElements", TypeUtils.PRIMITIVE_INT_TYPE);
        NewArray javaArray = new NewArray(innerElemClass, dim);
        ArrayDataForEach op =
            new ArrayDataForEach(
                arrayData,
                elemType,
                (i, value) -> {
                  Expression elemValue = deserializeFor(value, elemType, typeCtx, new HashSet<>());
                  return new AssignArrayElem(javaArray, elemValue, i);
                });
        // add javaArray at last as expression value
        return new ListExpression(javaArray, op, javaArray);
      }
    }
  }

  /**
   * Using fory to deserialize sliced MemoryBuffer. see {@link
   * BinaryUtils#getElemAccessMethodName(TypeRef)}, {@link Getters#getBuffer(int)}
   */
  protected Expression deserializeForObject(Expression value, TypeRef<?> typeRef) {
    return new Invoke(foryRef, "deserialize", typeRef, value);
  }

  protected Expression customEncode(Expression inputObject, TypeRef<?> rewrittenType) {
    return new Expression.StaticInvoke(
        customTypeHandler.getClass(),
        "encode",
        "rewrittenValue",
        rewrittenType,
        true,
        new Expression.Null(beanType, true),
        inputObject);
  }

  protected Expression customDecode(TypeRef<?> typeRef, final Expression deserializedValue) {
    return new Expression.StaticInvoke(
        customTypeHandler.getClass(),
        "decode",
        "decodedValue",
        typeRef,
        true,
        new Expression.Null(beanType, true),
        new Expression.Null(typeRef, true),
        deserializedValue);
  }
}
