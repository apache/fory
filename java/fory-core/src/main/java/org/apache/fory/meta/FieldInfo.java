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

package org.apache.fory.meta;

import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.util.Objects;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.Internal;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.converter.FieldConverter;
import org.apache.fory.serializer.converter.FieldConverters;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorBuilder;
import org.apache.fory.type.TypeAnnotationUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.StringUtils;

/**
 * FieldInfo contains all necessary info of a field to execute serialization/deserialization logic.
 */
public final class FieldInfo implements Serializable {
  /** where are current field defined. */
  final String definedClass;

  /** Name of a field. */
  final String fieldName;

  final FieldTypes.FieldType fieldType;

  /** Field ID for schema evolution, -1 means no field ID (use field name). */
  final short fieldId;

  public FieldInfo(String definedClass, String fieldName, FieldTypes.FieldType fieldType) {
    this(definedClass, fieldName, fieldType, (short) -1);
  }

  FieldInfo(String definedClass, String fieldName, FieldTypes.FieldType fieldType, short fieldId) {
    this.definedClass = definedClass;
    this.fieldName = fieldName;
    this.fieldType = fieldType;
    this.fieldId = fieldId;
  }

  /** Returns classname of current field defined. */
  public String getDefinedClass() {
    return definedClass;
  }

  /** Returns name of current field. */
  public String getFieldName() {
    return fieldName;
  }

  /** Returns whether field is annotated by an unsigned int id. */
  public boolean hasFieldId() {
    return fieldId >= 0;
  }

  /** Returns annotated field-id for the field. */
  public short getFieldId() {
    return fieldId;
  }

  /** Returns type of current field. */
  public FieldTypes.FieldType getFieldType() {
    return fieldType;
  }

  /**
   * Convert this field into a {@link Descriptor}, the corresponding {@link java.lang.reflect.Field}
   * field will be null. Don't invoke this method if class does have <code>fieldName</code> field.
   * In such case, reflection should be used to get the descriptor.
   */
  @Internal
  public Descriptor toDescriptor(TypeResolver resolver, Descriptor descriptor) {
    TypeRef<?> declared = descriptor != null ? descriptor.getTypeRef() : primitiveListCarrierType();
    TypeRef<?> typeRef = fieldType.toTypeToken(resolver, declared);
    String typeName = fieldType.getTypeName(resolver, typeRef);
    if (fieldType instanceof FieldTypes.RegisteredFieldType) {
      if (!Types.isPrimitiveType(fieldType.typeId)) {
        typeName = String.valueOf(((FieldTypes.RegisteredFieldType) fieldType).getTypeId());
      }
    }
    // Get nullable and trackingRef from remote FieldType - these are what the remote peer
    // used when serializing, so we must respect them when deserializing
    boolean remoteNullable = fieldType.nullable();
    boolean remoteTrackingRef = fieldType.trackingRef();

    if (descriptor != null) {
      Class<?> rawType = typeRef.getRawType();
      DescriptorBuilder builder =
          new DescriptorBuilder(descriptor)
              .typeName(typeName)
              .trackingRef(remoteTrackingRef)
              .nullable(remoteNullable)
              .typeRef(typeRef)
              .type(rawType);
      if (isTopLevelListArrayCompatibleReadPair(resolver, descriptor)) {
        if (listElementTypeId(fieldType) != Types.UNKNOWN) {
          TypeRef<?> peerListTypeRef = fieldType.toTypeToken(resolver, null);
          return builder
              .typeName(fieldType.getTypeName(resolver, peerListTypeRef))
              .typeRef(peerListTypeRef)
              .type(peerListTypeRef.getRawType())
              .build();
        }
        return builder.build();
      }
      // The local descriptor owns normalized wrapper nullability/ref-tracking bits. Rebuilding from
      // the raw field can make same-schema writer descriptors look incompatible and drop the field
      // accessor in compatible mode.
      FieldTypes.FieldType localFieldType = FieldTypes.buildFieldType(resolver, descriptor);
      int peerArrayTypeId = arrayTypeId(fieldType);
      // Static @ArrayType List<T> descriptors are fieldless, but the generated accessor still
      // reads and writes a List. Preserve the local descriptor so field metadata installs the
      // boxed-list dense-array serializer instead of treating the accessor value as a primitive
      // array.
      if (peerArrayTypeId != Types.UNKNOWN
          && TypeAnnotationUtils.isBoxedListArrayType(descriptor)
          && peerArrayTypeId == TypeAnnotationUtils.getBoxedListArrayTypeId(descriptor)) {
        return new DescriptorBuilder(descriptor)
            .trackingRef(remoteTrackingRef)
            .nullable(remoteNullable)
            .build();
      }
      if (peerArrayTypeId != Types.UNKNOWN
          && peerArrayTypeId == arrayTypeId(localFieldType)
          && TypeAnnotationUtils.isArrayType(descriptor)) {
        return new DescriptorBuilder(descriptor)
            .trackingRef(remoteTrackingRef)
            .nullable(remoteNullable)
            .build();
      }
      if (localFieldType != null && isListArrayRootPair(fieldType, localFieldType)) {
        throw new IllegalArgumentException(
            StringUtils.format(
                "Unsupported list/array compatible field mismatch for field ${definedClass}.${fieldName}: peer=${peer}, local=${local}",
                "definedClass",
                definedClass,
                "fieldName",
                fieldName,
                "peer",
                fieldType,
                "local",
                localFieldType));
      }
      if (localFieldType != null && hasNestedListArrayShapeMismatch(fieldType, localFieldType)) {
        // List/array bridging is only defined for the matched field itself. If the shape differs
        // deeper in a container, keep the remote descriptor for skipping but do not assign it to
        // the local field.
        TypeRef<?> remoteTypeRef = fieldType.toTypeToken(resolver, null);
        return new DescriptorBuilder(descriptor)
            .typeName(fieldType.getTypeName(resolver, remoteTypeRef))
            .trackingRef(remoteTrackingRef)
            .nullable(remoteNullable)
            .typeRef(remoteTypeRef)
            .type(remoteTypeRef.getRawType())
            .field(null)
            .build();
      }
      if (remoteNullable == descriptor.isNullable()
          && remoteTrackingRef == descriptor.isTrackingRef()
          && typeRef.equals(descriptor.getTypeRef())) {
        if (typeName.equals(descriptor.getTypeName())) {
          return descriptor;
        }
      }
      Descriptor remoteDescriptor = builder.build();
      if (localFieldType != null && isRefTrackedScalarSchemaMismatch(fieldType, localFieldType)) {
        return new DescriptorBuilder(remoteDescriptor).field(null).build();
      }
      FieldConverter<?> converter =
          FieldConverters.getConverter(resolver, remoteDescriptor, descriptor);
      if (converter != null) {
        return new DescriptorBuilder(remoteDescriptor)
            .field(null)
            .fieldConverter(converter)
            .build();
      }
      if (FieldTypes.useFieldType(rawType, descriptor)) {
        return remoteDescriptor;
      }
      // Local field exists - check if we need to update nullable/trackingRef
      boolean typeMismatch = !typeRef.equals(declared);
      if (typeMismatch) {
        if (!declared.getRawType().isAssignableFrom(rawType)) {
          // Check if both are primitives (or boxed primitives) of the same underlying type
          // For example: Integer <-> int is OK, but Long -> int is NOT OK
          Class<?> declaredPrimitive = declared.unwrap().getRawType();
          Class<?> remotePrimitive = typeRef.unwrap().getRawType();
          boolean bothPrimitives = declaredPrimitive.isPrimitive() && remotePrimitive.isPrimitive();
          boolean samePrimitiveType = bothPrimitives && declaredPrimitive.equals(remotePrimitive);
          // Set field to null if types are incompatible (not the same primitive type)
          if (!samePrimitiveType) {
            builder.field(null);
          }
        }
      }
      return builder.build();
    }
    // This field doesn't exist in peer class, so any legal modifier will be OK. Preserve the
    // remote tag id in the synthetic descriptor because descriptor grouping uses it as the sort key
    // for compatible payload order.
    // Use constant instead of reflection to avoid GraalVM native image issues.
    int stubModifiers = Modifier.PRIVATE | Modifier.FINAL;
    return new Descriptor(
        typeRef,
        typeName,
        fieldName,
        stubModifiers,
        definedClass,
        hasFieldId(),
        fieldId,
        remoteNullable,
        remoteTrackingRef,
        ForyField.Dynamic.AUTO);
  }

  private boolean isTopLevelListArrayCompatibleReadPair(
      TypeResolver resolver, Descriptor localDescriptor) {
    if (!resolver.isCrossLanguage()) {
      return false;
    }
    FieldTypes.FieldType localFieldType = FieldTypes.buildFieldType(resolver, localDescriptor);
    int peerListElementTypeId = listElementTypeId(fieldType);
    if (peerListElementTypeId != Types.UNKNOWN) {
      int localArrayTypeId = arrayTypeId(localFieldType);
      return localArrayTypeId != Types.UNKNOWN
          && localArrayTypeId == denseArrayTypeId(peerListElementTypeId);
    }
    int peerArrayTypeId = arrayTypeId(fieldType);
    if (peerArrayTypeId != Types.UNKNOWN) {
      int localListElementTypeId = listElementTypeId(localFieldType);
      return localListElementTypeId != Types.UNKNOWN
          && peerArrayTypeId == denseArrayTypeId(localListElementTypeId);
    }
    return false;
  }

  private static boolean hasListArrayShapeMismatch(
      FieldTypes.FieldType peerFieldType, FieldTypes.FieldType localFieldType) {
    if (isListArrayRootPair(peerFieldType, localFieldType)) {
      return true;
    }
    if (peerFieldType.getTypeId() != localFieldType.getTypeId()) {
      return false;
    }
    if (peerFieldType instanceof FieldTypes.CollectionFieldType
        && localFieldType instanceof FieldTypes.CollectionFieldType) {
      return hasListArrayShapeMismatch(
          ((FieldTypes.CollectionFieldType) peerFieldType).getElementType(),
          ((FieldTypes.CollectionFieldType) localFieldType).getElementType());
    }
    if (peerFieldType instanceof FieldTypes.MapFieldType
        && localFieldType instanceof FieldTypes.MapFieldType) {
      FieldTypes.MapFieldType peerMap = (FieldTypes.MapFieldType) peerFieldType;
      FieldTypes.MapFieldType localMap = (FieldTypes.MapFieldType) localFieldType;
      return hasListArrayShapeMismatch(peerMap.getKeyType(), localMap.getKeyType())
          || hasListArrayShapeMismatch(peerMap.getValueType(), localMap.getValueType());
    }
    if (peerFieldType instanceof FieldTypes.ArrayFieldType
        && localFieldType instanceof FieldTypes.ArrayFieldType) {
      return hasListArrayShapeMismatch(
          ((FieldTypes.ArrayFieldType) peerFieldType).getComponentType(),
          ((FieldTypes.ArrayFieldType) localFieldType).getComponentType());
    }
    return false;
  }

  private static boolean hasNestedListArrayShapeMismatch(
      FieldTypes.FieldType peerFieldType, FieldTypes.FieldType localFieldType) {
    if (peerFieldType.getTypeId() != localFieldType.getTypeId()) {
      return false;
    }
    if (peerFieldType instanceof FieldTypes.CollectionFieldType
        && localFieldType instanceof FieldTypes.CollectionFieldType) {
      return hasListArrayShapeMismatch(
          ((FieldTypes.CollectionFieldType) peerFieldType).getElementType(),
          ((FieldTypes.CollectionFieldType) localFieldType).getElementType());
    }
    if (peerFieldType instanceof FieldTypes.MapFieldType
        && localFieldType instanceof FieldTypes.MapFieldType) {
      FieldTypes.MapFieldType peerMap = (FieldTypes.MapFieldType) peerFieldType;
      FieldTypes.MapFieldType localMap = (FieldTypes.MapFieldType) localFieldType;
      return hasListArrayShapeMismatch(peerMap.getKeyType(), localMap.getKeyType())
          || hasListArrayShapeMismatch(peerMap.getValueType(), localMap.getValueType());
    }
    if (peerFieldType instanceof FieldTypes.ArrayFieldType
        && localFieldType instanceof FieldTypes.ArrayFieldType) {
      return hasListArrayShapeMismatch(
          ((FieldTypes.ArrayFieldType) peerFieldType).getComponentType(),
          ((FieldTypes.ArrayFieldType) localFieldType).getComponentType());
    }
    return false;
  }

  private static boolean isRefTrackedScalarSchemaMismatch(
      FieldTypes.FieldType remoteFieldType, FieldTypes.FieldType localFieldType) {
    if (!compatibleScalarType(remoteFieldType.typeId)
        || !compatibleScalarType(localFieldType.typeId)) {
      return false;
    }
    if (remoteFieldType.trackingRef() != localFieldType.trackingRef()) {
      return true;
    }
    return remoteFieldType.trackingRef()
        && (remoteFieldType.typeId != localFieldType.typeId
            || remoteFieldType.nullable() != localFieldType.nullable());
  }

  private static boolean compatibleScalarType(int typeId) {
    return (typeId >= Types.BOOL && typeId <= Types.TAGGED_UINT64)
        || (typeId >= Types.FLOAT16 && typeId <= Types.STRING)
        || typeId == Types.DECIMAL;
  }

  private static boolean isListArrayRootPair(
      FieldTypes.FieldType peerFieldType, FieldTypes.FieldType localFieldType) {
    boolean peerList = isListField(peerFieldType);
    boolean localList = isListField(localFieldType);
    boolean peerArray = arrayTypeId(peerFieldType) != Types.UNKNOWN;
    boolean localArray = arrayTypeId(localFieldType) != Types.UNKNOWN;
    return (peerList && localArray) || (peerArray && localList);
  }

  private static boolean isListField(FieldTypes.FieldType fieldType) {
    return fieldType instanceof FieldTypes.CollectionFieldType
        && fieldType.getTypeId() == Types.LIST;
  }

  private TypeRef<?> primitiveListCarrierType() {
    if (!(fieldType instanceof FieldTypes.CollectionFieldType)) {
      return null;
    }
    FieldTypes.CollectionFieldType collectionFieldType = (FieldTypes.CollectionFieldType) fieldType;
    FieldTypes.FieldType elementType = collectionFieldType.getElementType();
    if (!(elementType instanceof FieldTypes.RegisteredFieldType)
        || elementType.nullable()
        || elementType.trackingRef()) {
      return null;
    }
    int elementTypeId = ((FieldTypes.RegisteredFieldType) elementType).getTypeId();
    Class<?> carrierClass = FieldTypes.getPrimitiveListClassForElementType(elementTypeId);
    if (carrierClass == null) {
      return null;
    }
    // Native registered TypeDefs may not carry the writer class name for a removed field. A
    // LIST field with a non-null primitive element is the schema marker emitted by primitive-list
    // carriers, whose payload omits the native collection class header.
    return TypeRef.of(carrierClass);
  }

  private static int listElementTypeId(FieldTypes.FieldType fieldType) {
    if (!(fieldType instanceof FieldTypes.CollectionFieldType)
        || fieldType.getTypeId() != Types.LIST) {
      return Types.UNKNOWN;
    }
    FieldTypes.FieldType elementType =
        ((FieldTypes.CollectionFieldType) fieldType).getElementType();
    if (elementType instanceof FieldTypes.RegisteredFieldType) {
      return ((FieldTypes.RegisteredFieldType) elementType).getTypeId();
    }
    return Types.UNKNOWN;
  }

  private static int arrayTypeId(FieldTypes.FieldType fieldType) {
    if (fieldType instanceof FieldTypes.RegisteredFieldType) {
      int typeId = ((FieldTypes.RegisteredFieldType) fieldType).getTypeId();
      if (Types.isPrimitiveArray(typeId)) {
        return typeId;
      }
    }
    return Types.UNKNOWN;
  }

  private static int denseArrayTypeId(int elementTypeId) {
    switch (elementTypeId) {
      case Types.BOOL:
        return Types.BOOL_ARRAY;
      case Types.INT8:
        return Types.INT8_ARRAY;
      case Types.UINT8:
        return Types.UINT8_ARRAY;
      case Types.INT16:
        return Types.INT16_ARRAY;
      case Types.UINT16:
        return Types.UINT16_ARRAY;
      case Types.INT32:
      case Types.VARINT32:
        return Types.INT32_ARRAY;
      case Types.UINT32:
      case Types.VAR_UINT32:
        return Types.UINT32_ARRAY;
      case Types.INT64:
      case Types.VARINT64:
      case Types.TAGGED_INT64:
        return Types.INT64_ARRAY;
      case Types.UINT64:
      case Types.VAR_UINT64:
      case Types.TAGGED_UINT64:
        return Types.UINT64_ARRAY;
      case Types.FLOAT16:
        return Types.FLOAT16_ARRAY;
      case Types.BFLOAT16:
        return Types.BFLOAT16_ARRAY;
      case Types.FLOAT32:
        return Types.FLOAT32_ARRAY;
      case Types.FLOAT64:
        return Types.FLOAT64_ARRAY;
      default:
        return Types.UNKNOWN;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    FieldInfo fieldInfo = (FieldInfo) o;
    return fieldId == fieldInfo.fieldId
        && Objects.equals(definedClass, fieldInfo.definedClass)
        && Objects.equals(fieldName, fieldInfo.fieldName)
        && Objects.equals(fieldType, fieldInfo.fieldType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(definedClass, fieldName, fieldType, fieldId);
  }

  @Override
  public String toString() {
    return "FieldInfo{"
        + "fieldName='"
        + StringUtils.lowerCamelToLowerUnderscore(fieldName)
        + '\''
        + ", definedClass='"
        + definedClass
        + '\''
        + (fieldId >= 0 ? ", fieldID=" + fieldId : "")
        + ", fieldType="
        + fieldType
        + '}';
  }
}
