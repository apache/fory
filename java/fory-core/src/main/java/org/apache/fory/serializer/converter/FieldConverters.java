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

package org.apache.fory.serializer.converter;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.apache.fory.Fory;
import org.apache.fory.annotation.Internal;
import org.apache.fory.context.ReadContext;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DispatchId;
import org.apache.fory.type.Float16;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.unsigned.UInt16;
import org.apache.fory.type.unsigned.UInt32;
import org.apache.fory.type.unsigned.UInt64;
import org.apache.fory.type.unsigned.UInt8;

/** Factory for cold compatible-field scalar converters. */
public class FieldConverters {
  /**
   * Creates an appropriate field converter based on the target field type and source object type.
   *
   * @param from the source object type to convert from
   * @param field the target field to convert to
   * @return a FieldConverter instance that can handle the conversion, or null if no compatible
   *     converter exists
   */
  public static FieldConverter<?> getConverter(Class<?> from, Field field) {
    int fromDispatchId = CompatibleScalarConverter.dispatchId(TypeUtils.wrap(from));
    Class<?> to = field.getType();
    int toDispatchId = CompatibleScalarConverter.dispatchId(TypeUtils.wrap(to));
    if (!needsConverter(fromDispatchId, TypeUtils.wrap(from), toDispatchId, to)) {
      return null;
    }
    return new ScalarFieldConverter(
        FieldAccessor.createAccessor(field),
        fromDispatchId,
        TypeUtils.wrap(from),
        toDispatchId,
        to,
        field.getDeclaringClass().getName() + "." + field.getName());
  }

  /**
   * Creates a descriptor-aware converter for compatible field reads.
   *
   * <p>Descriptor dispatch IDs preserve scalar annotations such as uint widths and integer
   * encoding, so this path must be used when remote and local schema metadata are both available.
   */
  @Internal
  public static FieldConverter<?> getConverter(
      TypeResolver resolver, Descriptor from, Descriptor to) {
    Field field = to.getField();
    int fromDispatchId = DispatchId.getDispatchId(resolver, from);
    int toDispatchId = DispatchId.getDispatchId(resolver, to);
    if (field == null
        || from.isTrackingRef()
        || to.isTrackingRef()
        || !needsConverter(
            fromDispatchId,
            from.getRawType(),
            RefMode.of(from.isTrackingRef(), from.isNullable()),
            toDispatchId,
            to.getRawType(),
            RefMode.of(to.isTrackingRef(), to.isNullable()))) {
      return null;
    }
    return new ScalarFieldConverter(
        FieldAccessor.createAccessor(field),
        fromDispatchId,
        from.getRawType(),
        toDispatchId,
        to.getRawType(),
        to.getDeclaringClass() + "." + to.getName());
  }

  /** Returns whether a value of {@code from} can be assigned or converted to {@code to}. */
  @Internal
  public static boolean canConvert(Class<?> from, Class<?> to) {
    if (isDirectlyAssignable(from, to)) {
      return true;
    }
    Class<?> wrappedFrom = TypeUtils.wrap(from);
    Class<?> wrappedTo = TypeUtils.wrap(to);
    return CompatibleScalarConverter.canConvert(
        CompatibleScalarConverter.dispatchId(wrappedFrom),
        wrappedFrom,
        CompatibleScalarConverter.dispatchId(wrappedTo),
        wrappedTo);
  }

  /**
   * Returns whether descriptor-level compatible read can assign or convert {@code from} to {@code
   * to}.
   */
  @Internal
  public static boolean canConvert(TypeResolver resolver, Descriptor from, Descriptor to) {
    int fromDispatchId = DispatchId.getDispatchId(resolver, from);
    int toDispatchId = DispatchId.getDispatchId(resolver, to);
    if (isRefTrackedScalarSchemaMismatch(
        fromDispatchId,
        from.getRawType(),
        from.isTrackingRef(),
        from.isNullable(),
        toDispatchId,
        to.getRawType(),
        to.isTrackingRef(),
        to.isNullable())) {
      return false;
    }
    if (isDirectIdentity(
        fromDispatchId,
        from.getRawType(),
        RefMode.of(from.isTrackingRef(), from.isNullable()),
        toDispatchId,
        to.getRawType(),
        RefMode.of(to.isTrackingRef(), to.isNullable()))) {
      return true;
    }
    if (from.isTrackingRef() || to.isTrackingRef()) {
      return false;
    }
    return CompatibleScalarConverter.canConvert(
        fromDispatchId, from.getRawType(), toDispatchId, to.getRawType());
  }

  /**
   * Returns whether descriptor-level compatible read can assign or convert {@code from} to {@code
   * to}.
   */
  @Internal
  public static boolean canConvert(SerializationFieldInfo from, SerializationFieldInfo to) {
    if (isRefTrackedScalarSchemaMismatch(
        from.dispatchId,
        from.type,
        from.trackingRef,
        from.nullable,
        to.dispatchId,
        to.type,
        to.trackingRef,
        to.nullable)) {
      return false;
    }
    if (isDirectIdentity(
        from.dispatchId, from.type, from.refMode, to.dispatchId, to.type, to.refMode)) {
      return true;
    }
    if (from.trackingRef || to.trackingRef) {
      return false;
    }
    return CompatibleScalarConverter.canConvert(from.dispatchId, from.type, to.dispatchId, to.type);
  }

  /**
   * Returns whether a generated serializer can read {@code from} and adapt it with generated
   * field-access code for {@code to}.
   */
  @Internal
  public static boolean canReadGeneratedField(
      SerializationFieldInfo from, SerializationFieldInfo to) {
    if (canConvert(from, to)) {
      return true;
    }
    if (isScalarField(from) || isScalarField(to)) {
      return false;
    }
    // Generated serializers may adapt source-only carrier types, such as Kotlin unsigned arrays,
    // after reading the remote wire value. Keep that fallback out of scalar fields so ref/schema
    // guardrails remain owned by descriptor-level conversion above.
    return canConvert(from.typeRef.getRawType(), to.typeRef.getRawType());
  }

  /**
   * Converts {@code value} from {@code from} to {@code to}, or returns it for direct assignment.
   */
  @Internal
  public static Object convertValue(Class<?> from, Class<?> to, Object value) {
    if (isDirectlyAssignable(from, to)) {
      return value;
    }
    Class<?> wrappedFrom = TypeUtils.wrap(from);
    Class<?> wrappedTo = TypeUtils.wrap(to);
    return CompatibleScalarConverter.convert(
        CompatibleScalarConverter.dispatchId(wrappedFrom),
        wrappedFrom,
        CompatibleScalarConverter.dispatchId(wrappedTo),
        wrappedTo,
        value,
        "<unknown>");
  }

  /** Converts a compatible field value using descriptor-level scalar metadata. */
  @Internal
  public static Object convertValue(
      SerializationFieldInfo from, SerializationFieldInfo to, Object value) {
    if (isRefTrackedScalarSchemaMismatch(
        from.dispatchId,
        from.type,
        from.trackingRef,
        from.nullable,
        to.dispatchId,
        to.type,
        to.trackingRef,
        to.nullable)) {
      throw new IllegalArgumentException(
          "Reference-tracked scalar conversion is schema incompatible for "
              + to.qualifiedFieldName);
    }
    if (isDirectIdentity(
        from.dispatchId, from.type, from.refMode, to.dispatchId, to.type, to.refMode)) {
      return value;
    }
    if (from.trackingRef || to.trackingRef) {
      throw new IllegalArgumentException(
          "Reference-tracked scalar conversion is schema incompatible for "
              + to.qualifiedFieldName);
    }
    return CompatibleScalarConverter.convert(
        from.dispatchId, from.type, to.dispatchId, to.type, value, to.qualifiedFieldName);
  }

  /** Converts a compatible field value using scalar metadata captured at code generation time. */
  @Internal
  public static Object convertValue(
      int fromDispatchId,
      Class<?> fromType,
      int toDispatchId,
      Class<?> toType,
      String fieldName,
      Object value) {
    return CompatibleScalarConverter.convert(
        fromDispatchId, fromType, toDispatchId, toType, value, fieldName);
  }

  /** Returns whether descriptor-level compatible read must read a source scalar payload. */
  @Internal
  public static boolean requiresSourceScalarRead(
      SerializationFieldInfo from, SerializationFieldInfo to) {
    if (isRefTrackedScalarSchemaMismatch(
        from.dispatchId,
        from.type,
        from.trackingRef,
        from.nullable,
        to.dispatchId,
        to.type,
        to.trackingRef,
        to.nullable)) {
      return false;
    }
    if (isDirectIdentity(
        from.dispatchId, from.type, from.refMode, to.dispatchId, to.type, to.refMode)) {
      return false;
    }
    if (from.trackingRef || to.trackingRef) {
      return false;
    }
    return CompatibleScalarConverter.canConvert(from.dispatchId, from.type, to.dispatchId, to.type);
  }

  /** Reads a remote scalar conversion source value using descriptor-level scalar metadata. */
  @Internal
  public static Object readSourceScalar(
      ReadContext readContext, SerializationFieldInfo from, SerializationFieldInfo to) {
    return readSourceScalar(
        readContext, from, from.refMode, from.dispatchId, from.type, false, to.qualifiedFieldName);
  }

  /** Reads a remote scalar conversion source value for an existing field converter. */
  @Internal
  public static Object readSourceScalar(
      ReadContext readContext, SerializationFieldInfo from, FieldConverter<?> converter) {
    ScalarFieldConverter scalar = scalarConverter(converter);
    return readSourceScalar(
        readContext,
        from,
        from.refMode,
        scalar.fromDispatchId,
        scalar.fromType,
        false,
        scalar.fieldName);
  }

  /** Reads a remote scalar conversion source value from generated compatible serializers. */
  @Internal
  public static Object readSourceScalar(
      ReadContext readContext,
      int fromDispatchId,
      Class<?> fromType,
      boolean nullable,
      boolean declaredTypeInfo,
      String fieldName) {
    return readSourceScalar(
        readContext,
        null,
        nullable ? RefMode.NULL_ONLY : RefMode.NONE,
        fromDispatchId,
        fromType,
        declaredTypeInfo,
        fieldName);
  }

  private static Object readSourceScalar(
      ReadContext readContext,
      SerializationFieldInfo from,
      RefMode refMode,
      int fromDispatchId,
      Class<?> fromType,
      boolean declaredTypeInfo,
      String fieldName) {
    if (refMode == RefMode.TRACKING) {
      throw new DeserializationException(
          "Reference-tracked scalar conversion is schema incompatible for " + fieldName);
    }
    MemoryBuffer buffer = readContext.getBuffer();
    if (refMode == RefMode.NULL_ONLY) {
      byte flag = buffer.readByte();
      if (flag == Fory.NULL_FLAG) {
        return null;
      }
      if (flag != Fory.NOT_NULL_VALUE_FLAG) {
        throw new DeserializationException(
            "Invalid nullable compatible scalar field flag " + flag + " for " + fieldName);
      }
    }
    switch (fromDispatchId) {
      case DispatchId.BOOL:
        return CompatibleScalarConverter.readBool(buffer, fromDispatchId, fromType, fieldName);
      case DispatchId.INT8:
        return buffer.readByte();
      case DispatchId.UINT8:
        return buffer.readByte() & 0xFF;
      case DispatchId.EXT_UINT8:
        return UInt8.valueOf(buffer.readByte());
      case DispatchId.INT16:
        return buffer.readInt16();
      case DispatchId.UINT16:
        return buffer.readInt16() & 0xFFFF;
      case DispatchId.EXT_UINT16:
        return UInt16.valueOf(buffer.readInt16());
      case DispatchId.INT32:
        return buffer.readInt32();
      case DispatchId.UINT32:
        return Integer.toUnsignedLong(buffer.readInt32());
      case DispatchId.EXT_UINT32:
        return UInt32.valueOf(buffer.readInt32());
      case DispatchId.VARINT32:
        return buffer.readVarInt32();
      case DispatchId.VAR_UINT32:
        return Integer.toUnsignedLong(buffer.readVarUInt32());
      case DispatchId.EXT_VAR_UINT32:
        return UInt32.valueOf(buffer.readVarUInt32());
      case DispatchId.INT64:
        return buffer.readInt64();
      case DispatchId.UINT64:
        return buffer.readInt64();
      case DispatchId.EXT_UINT64:
        return UInt64.valueOf(buffer.readInt64());
      case DispatchId.VARINT64:
        return buffer.readVarInt64();
      case DispatchId.TAGGED_INT64:
        return buffer.readTaggedInt64();
      case DispatchId.VAR_UINT64:
        return buffer.readVarUInt64();
      case DispatchId.EXT_VAR_UINT64:
        return UInt64.valueOf(buffer.readVarUInt64());
      case DispatchId.TAGGED_UINT64:
        return buffer.readTaggedUInt64();
      case DispatchId.FLOAT32:
        return buffer.readFloat32();
      case DispatchId.FLOAT64:
        return buffer.readFloat64();
      case DispatchId.FLOAT16:
        return Float16.fromBits(buffer.readInt16());
      case DispatchId.BFLOAT16:
        return BFloat16.fromBits(buffer.readInt16());
      case DispatchId.STRING:
        return readContext.readString();
      default:
        if (fromType == BigDecimal.class) {
          return readSourceDecimal(readContext, from, fromType, declaredTypeInfo);
        }
        throw new DeserializationException("Unsupported compatible scalar source " + fieldName);
    }
  }

  @Internal
  public static int fromDispatchId(FieldConverter<?> converter) {
    return scalarConverter(converter).fromDispatchId;
  }

  @Internal
  public static Class<?> fromType(FieldConverter<?> converter) {
    return scalarConverter(converter).fromType;
  }

  @Internal
  public static int toDispatchId(FieldConverter<?> converter) {
    return scalarConverter(converter).toDispatchId;
  }

  @Internal
  public static Class<?> toType(FieldConverter<?> converter) {
    return scalarConverter(converter).toType;
  }

  @Internal
  public static String fieldName(FieldConverter<?> converter) {
    return scalarConverter(converter).fieldName;
  }

  private static Object readSourceDecimal(
      ReadContext readContext,
      SerializationFieldInfo from,
      Class<?> fromType,
      boolean declaredTypeInfo) {
    if (from != null) {
      if (from.useDeclaredTypeInfo) {
        readContext.preserveRefId(-1);
        return readContext.readNonRef(from.typeInfo);
      }
      TypeInfo typeInfo = readContext.getTypeResolver().readTypeInfo(readContext, from.type);
      return typeInfo.getSerializer().read(readContext, RefMode.NONE);
    }
    if (declaredTypeInfo) {
      readContext.preserveRefId(-1);
      return readContext.readNonRef(readContext.getTypeResolver().getTypeInfo(fromType));
    }
    if (readContext.getTypeResolver().isCrossLanguage()) {
      Serializer<?> serializer =
          readContext.getTypeResolver().getTypeInfo(fromType).getSerializer();
      return serializer.read(readContext);
    }
    TypeInfo typeInfo = readContext.getTypeResolver().readTypeInfo(readContext, fromType);
    return typeInfo.getSerializer().read(readContext, RefMode.NONE);
  }

  private static boolean needsConverter(
      int fromDispatchId, Class<?> from, int toDispatchId, Class<?> to) {
    if (isDirectIdentity(fromDispatchId, from, toDispatchId, to)) {
      return false;
    }
    return CompatibleScalarConverter.canConvert(fromDispatchId, from, toDispatchId, to);
  }

  private static boolean needsConverter(
      int fromDispatchId,
      Class<?> from,
      RefMode fromRefMode,
      int toDispatchId,
      Class<?> to,
      RefMode toRefMode) {
    if (isDirectIdentity(fromDispatchId, from, fromRefMode, toDispatchId, to, toRefMode)) {
      return false;
    }
    return CompatibleScalarConverter.canConvert(fromDispatchId, from, toDispatchId, to);
  }

  private static boolean isDirectIdentity(
      int fromDispatchId,
      Class<?> from,
      RefMode fromRefMode,
      int toDispatchId,
      Class<?> to,
      RefMode toRefMode) {
    if (!isDirectlyAssignable(from, to)) {
      return false;
    }
    boolean fromScalar = CompatibleScalarConverter.isScalar(fromDispatchId, from);
    boolean toScalar = CompatibleScalarConverter.isScalar(toDispatchId, to);
    if (fromScalar && toScalar) {
      return fromRefMode == toRefMode
          && CompatibleScalarConverter.sameScalar(fromDispatchId, from, toDispatchId, to);
    }
    return true;
  }

  private static boolean isDirectIdentity(
      int fromDispatchId, Class<?> from, int toDispatchId, Class<?> to) {
    if (!isDirectlyAssignable(from, to)) {
      return false;
    }
    boolean fromScalar = CompatibleScalarConverter.isScalar(fromDispatchId, from);
    boolean toScalar = CompatibleScalarConverter.isScalar(toDispatchId, to);
    return !fromScalar
        || !toScalar
        || CompatibleScalarConverter.sameScalar(fromDispatchId, from, toDispatchId, to);
  }

  private static boolean isRefTrackedScalarSchemaMismatch(
      int fromDispatchId,
      Class<?> from,
      boolean fromTrackingRef,
      boolean fromNullable,
      int toDispatchId,
      Class<?> to,
      boolean toTrackingRef,
      boolean toNullable) {
    boolean fromScalar = CompatibleScalarConverter.isScalar(fromDispatchId, from);
    boolean toScalar = CompatibleScalarConverter.isScalar(toDispatchId, to);
    if (!fromScalar || !toScalar) {
      return false;
    }
    if (fromTrackingRef != toTrackingRef) {
      return true;
    }
    return fromTrackingRef
        && (!CompatibleScalarConverter.sameScalar(fromDispatchId, from, toDispatchId, to)
            || fromNullable != toNullable);
  }

  private static boolean isScalarField(SerializationFieldInfo fieldInfo) {
    return CompatibleScalarConverter.isScalar(fieldInfo.dispatchId, fieldInfo.type);
  }

  private static boolean isDirectlyAssignable(Class<?> from, Class<?> to) {
    if (to.isAssignableFrom(from)) {
      return true;
    }
    if (from.isPrimitive() && !to.isPrimitive()) {
      return to.isAssignableFrom(TypeUtils.wrap(from));
    }
    return false;
  }

  private static ScalarFieldConverter scalarConverter(FieldConverter<?> converter) {
    if (!(converter instanceof ScalarFieldConverter)) {
      throw new IllegalArgumentException("Unsupported compatible field converter: " + converter);
    }
    return (ScalarFieldConverter) converter;
  }

  private static final class ScalarFieldConverter extends FieldConverter<Object> {
    private final int fromDispatchId;
    private final Class<?> fromType;
    private final int toDispatchId;
    private final Class<?> toType;
    private final String fieldName;

    private ScalarFieldConverter(
        FieldAccessor fieldAccessor,
        int fromDispatchId,
        Class<?> fromType,
        int toDispatchId,
        Class<?> toType,
        String fieldName) {
      super(fieldAccessor);
      this.fromDispatchId = fromDispatchId;
      this.fromType = fromType;
      this.toDispatchId = toDispatchId;
      this.toType = toType;
      this.fieldName = fieldName;
    }

    @Override
    public Object convert(Object from) {
      return CompatibleScalarConverter.convert(
          fromDispatchId, fromType, toDispatchId, toType, from, fieldName);
    }
  }
}
