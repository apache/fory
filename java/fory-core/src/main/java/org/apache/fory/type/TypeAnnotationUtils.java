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

package org.apache.fory.type;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import org.apache.fory.annotation.ArrayType;
import org.apache.fory.annotation.BFloat16Type;
import org.apache.fory.annotation.Float16Type;
import org.apache.fory.annotation.Int32Type;
import org.apache.fory.annotation.Int64Type;
import org.apache.fory.annotation.Int8Type;
import org.apache.fory.annotation.UInt16Type;
import org.apache.fory.annotation.UInt32Type;
import org.apache.fory.annotation.UInt64Type;
import org.apache.fory.annotation.UInt8Type;
import org.apache.fory.collection.BFloat16List;
import org.apache.fory.collection.BoolList;
import org.apache.fory.collection.Float16List;
import org.apache.fory.collection.Float32List;
import org.apache.fory.collection.Float64List;
import org.apache.fory.collection.Int16List;
import org.apache.fory.collection.Int32List;
import org.apache.fory.collection.Int64List;
import org.apache.fory.collection.Int8List;
import org.apache.fory.collection.UInt16List;
import org.apache.fory.collection.UInt32List;
import org.apache.fory.collection.UInt64List;
import org.apache.fory.collection.UInt8List;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.config.Int64Encoding;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.TypeRef;

public class TypeAnnotationUtils {

  /**
   * Get the type id for the given type annotation and validate it against the field type.
   *
   * @param typeAnnotation the type annotation
   * @param fieldType the field type class
   * @return the type id
   * @throws IllegalArgumentException if the annotation is not compatible with the field type
   */
  public static int getTypeId(Annotation typeAnnotation, Class<?> fieldType) {
    if (typeAnnotation == null) {
      return Types.UNKNOWN;
    }
    if (typeAnnotation instanceof Int8Type) {
      checkFieldType(fieldType, "@Int8Type", byte.class, Byte.class, byte[].class);
      return fieldType == byte[].class ? Types.INT8_ARRAY : Types.INT8;
    } else if (typeAnnotation instanceof UInt8Type) {
      checkFieldType(fieldType, "@UInt8Type", int.class, Integer.class, byte[].class);
      if (fieldType == byte[].class) {
        return Types.UINT8_ARRAY;
      }
      return Types.UINT8;
    } else if (typeAnnotation instanceof UInt16Type) {
      checkFieldType(fieldType, "@UInt16Type", int.class, Integer.class, short[].class);
      if (fieldType == short[].class) {
        return Types.UINT16_ARRAY;
      }
      return Types.UINT16;
    } else if (typeAnnotation instanceof UInt32Type) {
      checkFieldType(fieldType, "@UInt32Type", long.class, Long.class, int[].class);
      if (fieldType == int[].class) {
        UInt32Type uint32Type = (UInt32Type) typeAnnotation;
        if (uint32Type.encoding() != Int32Encoding.VARINT) {
          checkArrayElementTypeIdHasNoEncodingModifier(Types.UINT32);
        }
        return Types.UINT32_ARRAY;
      }
      UInt32Type uint32Type = (UInt32Type) typeAnnotation;
      switch (uint32Type.encoding()) {
        case VARINT:
          return Types.VAR_UINT32;
        case FIXED:
          return Types.UINT32;
        default:
          throw new IllegalArgumentException("Unsupported encoding: " + uint32Type.encoding());
      }
    } else if (typeAnnotation instanceof UInt64Type) {
      checkFieldType(fieldType, "@UInt64Type", long.class, Long.class, long[].class);
      if (fieldType == long[].class) {
        UInt64Type uint64Type = (UInt64Type) typeAnnotation;
        if (uint64Type.encoding() != Int64Encoding.VARINT) {
          checkArrayElementTypeIdHasNoEncodingModifier(
              uint64Type.encoding() == Int64Encoding.FIXED ? Types.UINT64 : Types.TAGGED_UINT64);
        }
        return Types.UINT64_ARRAY;
      }
      UInt64Type uint64Type = (UInt64Type) typeAnnotation;
      switch (uint64Type.encoding()) {
        case VARINT:
          return Types.VAR_UINT64;
        case FIXED:
          return Types.UINT64;
        case TAGGED:
          return Types.TAGGED_UINT64;
        default:
          throw new IllegalArgumentException("Unsupported encoding: " + uint64Type.encoding());
      }
    } else if (typeAnnotation instanceof Int32Type) {
      checkFieldType(fieldType, "@Int32Type", int.class, Integer.class);
      Int32Type int32Type = (Int32Type) typeAnnotation;
      switch (int32Type.encoding()) {
        case VARINT:
          return Types.VARINT32;
        case FIXED:
          return Types.INT32;
        default:
          throw new IllegalArgumentException("Unsupported encoding: " + int32Type.encoding());
      }
    } else if (typeAnnotation instanceof Int64Type) {
      checkFieldType(fieldType, "@Int64Type", long.class, Long.class);
      Int64Type int64Type = (Int64Type) typeAnnotation;
      switch (int64Type.encoding()) {
        case VARINT:
          return Types.VARINT64;
        case FIXED:
          return Types.INT64;
        case TAGGED:
          return Types.TAGGED_INT64;
        default:
          throw new IllegalArgumentException("Unsupported encoding: " + int64Type.encoding());
      }
    } else if (typeAnnotation instanceof Float16Type) {
      checkFieldType(fieldType, "@Float16Type", short[].class);
      return Types.FLOAT16_ARRAY;
    } else if (typeAnnotation instanceof BFloat16Type) {
      checkFieldType(fieldType, "@BFloat16Type", short[].class);
      return Types.BFLOAT16_ARRAY;
    }
    throw new IllegalArgumentException("Unsupported type annotation: " + typeAnnotation.getClass());
  }

  public static int getPrimitiveListTypeId(Annotation typeAnnotation, Class<?> fieldType) {
    int elementTypeId = getPrimitiveListElementTypeId(typeAnnotation, fieldType, true);
    if (elementTypeId == Types.UNKNOWN) {
      return Types.UNKNOWN;
    }
    if (usesCollectionProtocolForPrimitiveListElementType(elementTypeId)) {
      return Types.LIST;
    }
    return Types.getPrimitiveArrayTypeId(elementTypeId);
  }

  public static int getPrimitiveListArrayTypeId(Class<?> fieldType) {
    if (fieldType == BoolList.class) {
      return Types.BOOL_ARRAY;
    }
    if (fieldType == Int8List.class) {
      return Types.INT8_ARRAY;
    }
    if (fieldType == Int16List.class) {
      return Types.INT16_ARRAY;
    }
    if (fieldType == Int32List.class) {
      return Types.INT32_ARRAY;
    }
    if (fieldType == Int64List.class) {
      return Types.INT64_ARRAY;
    }
    if (fieldType == UInt8List.class) {
      return Types.UINT8_ARRAY;
    }
    if (fieldType == UInt16List.class) {
      return Types.UINT16_ARRAY;
    }
    if (fieldType == UInt32List.class) {
      return Types.UINT32_ARRAY;
    }
    if (fieldType == UInt64List.class) {
      return Types.UINT64_ARRAY;
    }
    if (fieldType == Float16List.class) {
      return Types.FLOAT16_ARRAY;
    }
    if (fieldType == BFloat16List.class) {
      return Types.BFLOAT16_ARRAY;
    }
    if (fieldType == Float32List.class) {
      return Types.FLOAT32_ARRAY;
    }
    if (fieldType == Float64List.class) {
      return Types.FLOAT64_ARRAY;
    }
    return Types.UNKNOWN;
  }

  public static int getPrimitiveListElementTypeId(Annotation typeAnnotation, Class<?> fieldType) {
    return getPrimitiveListElementTypeId(typeAnnotation, fieldType, false);
  }

  public static int getPrimitiveListElementTypeId(
      Annotation typeAnnotation, Class<?> fieldType, boolean useDefault) {
    if (typeAnnotation == null) {
      return useDefault ? getDefaultPrimitiveListElementTypeId(fieldType) : Types.UNKNOWN;
    }
    if (fieldType == UInt8List.class && typeAnnotation instanceof UInt8Type) {
      return Types.UINT8;
    }
    if (fieldType == UInt16List.class && typeAnnotation instanceof UInt16Type) {
      return Types.UINT16;
    }
    if (fieldType == UInt32List.class && typeAnnotation instanceof UInt32Type) {
      UInt32Type uint32Type = (UInt32Type) typeAnnotation;
      switch (uint32Type.encoding()) {
        case VARINT:
          return Types.VAR_UINT32;
        case FIXED:
          return Types.UINT32;
        default:
          throw new IllegalArgumentException("Unsupported encoding: " + uint32Type.encoding());
      }
    }
    if (fieldType == UInt64List.class && typeAnnotation instanceof UInt64Type) {
      UInt64Type uint64Type = (UInt64Type) typeAnnotation;
      switch (uint64Type.encoding()) {
        case VARINT:
          return Types.VAR_UINT64;
        case FIXED:
          return Types.UINT64;
        case TAGGED:
          return Types.TAGGED_UINT64;
        default:
          throw new IllegalArgumentException("Unsupported encoding: " + uint64Type.encoding());
      }
    }
    if (fieldType == Int8List.class && typeAnnotation instanceof Int8Type) {
      return Types.INT8;
    }
    if (fieldType == Int32List.class && typeAnnotation instanceof Int32Type) {
      Int32Type int32Type = (Int32Type) typeAnnotation;
      switch (int32Type.encoding()) {
        case VARINT:
          return Types.VARINT32;
        case FIXED:
          return Types.INT32;
        default:
          throw new IllegalArgumentException("Unsupported encoding: " + int32Type.encoding());
      }
    }
    if (fieldType == Int64List.class && typeAnnotation instanceof Int64Type) {
      Int64Type int64Type = (Int64Type) typeAnnotation;
      switch (int64Type.encoding()) {
        case VARINT:
          return Types.VARINT64;
        case FIXED:
          return Types.INT64;
        case TAGGED:
          return Types.TAGGED_INT64;
        default:
          throw new IllegalArgumentException("Unsupported encoding: " + int64Type.encoding());
      }
    }
    if (fieldType == UInt8List.class
        || fieldType == UInt16List.class
        || fieldType == UInt32List.class
        || fieldType == UInt64List.class) {
      throw new IllegalArgumentException(
          typeAnnotation.annotationType().getSimpleName()
              + " is not compatible with primitive list field "
              + fieldType.getName());
    }
    return Types.UNKNOWN;
  }

  public static boolean isBoxedListArrayType(Field field) {
    if (field == null || !field.isAnnotationPresent(ArrayType.class)) {
      return false;
    }
    Class<?> rawType = field.getType();
    if (TypeUtils.isPrimitiveListClass(rawType)) {
      return false;
    }
    validateBoxedListArrayType(field);
    return true;
  }

  public static int getBoxedListArrayTypeId(Field field) {
    validateBoxedListArrayType(field);
    TypeRef<?> elementTypeRef = TypeUtils.getElementType(TypeRef.of(field.getAnnotatedType()));
    int typeId = getArrayTypeIdFromElementType(elementTypeRef);
    if (typeId == Types.UNKNOWN) {
      throw new IllegalArgumentException(
          "@ArrayType List<T> field "
              + field
              + " must use a bool, numeric, float16, or bfloat16 element type");
    }
    return typeId;
  }

  public static int getArrayTypeIdFromElementType(TypeRef<?> elementTypeRef) {
    TypeExtMeta extMeta = elementTypeRef.getTypeExtMeta();
    if (extMeta != null && extMeta.typeId() != Types.UNKNOWN) {
      checkArrayElementTypeIdHasNoEncodingModifier(extMeta.typeId());
      return getArrayTypeIdFromElementTypeId(extMeta.typeId());
    }
    Class<?> elementRawType = TypeUtils.unwrap(elementTypeRef.getRawType());
    if (elementRawType == boolean.class) {
      return Types.BOOL_ARRAY;
    } else if (elementRawType == byte.class) {
      return Types.INT8_ARRAY;
    } else if (elementRawType == short.class) {
      return Types.INT16_ARRAY;
    } else if (elementRawType == int.class) {
      return Types.INT32_ARRAY;
    } else if (elementRawType == long.class) {
      return Types.INT64_ARRAY;
    } else if (elementRawType == float.class) {
      return Types.FLOAT32_ARRAY;
    } else if (elementRawType == double.class) {
      return Types.FLOAT64_ARRAY;
    } else if (elementTypeRef.getRawType() == org.apache.fory.type.Float16.class) {
      return Types.FLOAT16_ARRAY;
    } else if (elementTypeRef.getRawType() == org.apache.fory.type.BFloat16.class) {
      return Types.BFLOAT16_ARRAY;
    }
    return Types.UNKNOWN;
  }

  public static int getArrayTypeIdFromElementTypeId(int elementTypeId) {
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
      case Types.VARINT32:
        return Types.INT32_ARRAY;
      case Types.VAR_UINT32:
        return Types.UINT32_ARRAY;
      case Types.VARINT64:
        return Types.INT64_ARRAY;
      case Types.VAR_UINT64:
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

  private static void checkArrayElementTypeIdHasNoEncodingModifier(int elementTypeId) {
    switch (elementTypeId) {
      case Types.INT32:
      case Types.INT64:
      case Types.TAGGED_INT64:
      case Types.UINT32:
      case Types.UINT64:
      case Types.TAGGED_UINT64:
        throw new IllegalArgumentException(
            "array<T> element type must not use scalar encoding modifiers");
      default:
        break;
    }
  }

  private static void validateBoxedListArrayType(Field field) {
    Class<?> rawType = field.getType();
    if (!List.class.isAssignableFrom(rawType)) {
      String allowed = "Fory primitive-list carriers or ordered java.util.List<T> fields";
      if (Collection.class.isAssignableFrom(rawType)) {
        throw new IllegalArgumentException(
            "@ArrayType can only be applied to " + allowed + ", but got " + rawType.getName());
      }
      throw new IllegalArgumentException(
          "@ArrayType can only be applied to "
              + allowed
              + "; primitive arrays already use array<T> schema");
    }
  }

  public static int getDefaultPrimitiveListElementTypeId(Class<?> fieldType) {
    if (fieldType == BoolList.class) {
      return Types.BOOL;
    }
    if (fieldType == Int8List.class) {
      return Types.INT8;
    }
    if (fieldType == Int16List.class) {
      return Types.INT16;
    }
    if (fieldType == Int32List.class) {
      return Types.VARINT32;
    }
    if (fieldType == Int64List.class) {
      return Types.VARINT64;
    }
    if (fieldType == UInt8List.class) {
      return Types.UINT8;
    }
    if (fieldType == UInt16List.class) {
      return Types.UINT16;
    }
    if (fieldType == UInt32List.class) {
      return Types.VAR_UINT32;
    }
    if (fieldType == UInt64List.class) {
      return Types.VAR_UINT64;
    }
    if (fieldType == Float16List.class) {
      return Types.FLOAT16;
    }
    if (fieldType == BFloat16List.class) {
      return Types.BFLOAT16;
    }
    if (fieldType == Float32List.class) {
      return Types.FLOAT32;
    }
    if (fieldType == Float64List.class) {
      return Types.FLOAT64;
    }
    return Types.UNKNOWN;
  }

  public static boolean usesCollectionProtocolForPrimitiveList(
      Annotation typeAnnotation, Class<?> fieldType) {
    return usesCollectionProtocolForPrimitiveListElementType(
        getPrimitiveListElementTypeId(typeAnnotation, fieldType, true));
  }

  private static boolean usesCollectionProtocolForPrimitiveListElementType(int elementTypeId) {
    return elementTypeId != Types.UNKNOWN;
  }

  public static Class<?> getPrimitiveListElementClass(Class<?> fieldType) {
    if (fieldType == BoolList.class) {
      return Boolean.class;
    }
    if (fieldType == Int8List.class) {
      return Byte.class;
    }
    if (fieldType == Int16List.class) {
      return Short.class;
    }
    if (fieldType == Int32List.class) {
      return Integer.class;
    }
    if (fieldType == Int64List.class) {
      return Long.class;
    }
    if (fieldType == UInt8List.class || fieldType == UInt16List.class) {
      return Integer.class;
    }
    if (fieldType == UInt32List.class || fieldType == UInt64List.class) {
      return Long.class;
    }
    if (fieldType == Float16List.class) {
      return org.apache.fory.type.Float16.class;
    }
    if (fieldType == BFloat16List.class) {
      return org.apache.fory.type.BFloat16.class;
    }
    if (fieldType == Float32List.class) {
      return Float.class;
    }
    if (fieldType == Float64List.class) {
      return Double.class;
    }
    return null;
  }

  public static TypeRef<?> getPrimitiveListElementTypeRef(
      Annotation typeAnnotation, Class<?> fieldType) {
    int elementTypeId = getPrimitiveListElementTypeId(typeAnnotation, fieldType, true);
    Class<?> elementClass = getPrimitiveListElementClass(fieldType);
    if (elementTypeId == Types.UNKNOWN || elementClass == null) {
      return null;
    }
    return TypeRef.of(elementClass, TypeExtMeta.of(elementTypeId, true, false));
  }

  public static boolean isArrayType(Descriptor descriptor) {
    if (descriptor.getField() != null
        && descriptor.getField().isAnnotationPresent(ArrayType.class)) {
      return true;
    }
    return descriptor.getReadMethod() != null
        && descriptor.getReadMethod().isAnnotationPresent(ArrayType.class);
  }

  public static Annotation getTypeAnnotation(Annotation[] annotations) {
    for (Annotation annotation : annotations) {
      if (isTypeAnnotation(annotation)) {
        return annotation;
      }
    }
    return null;
  }

  public static boolean isTypeAnnotation(Annotation annotation) {
    return annotation instanceof Int8Type
        || annotation instanceof UInt8Type
        || annotation instanceof UInt16Type
        || annotation instanceof UInt32Type
        || annotation instanceof UInt64Type
        || annotation instanceof Int32Type
        || annotation instanceof Int64Type
        || annotation instanceof Float16Type
        || annotation instanceof BFloat16Type;
  }

  private static void checkFieldType(
      Class<?> fieldType, String annotationName, Class<?>... allowedTypes) {
    for (Class<?> allowedType : allowedTypes) {
      if (fieldType == allowedType) {
        return;
      }
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < allowedTypes.length; i++) {
      if (i > 0) {
        sb.append(" or ");
      }
      sb.append(allowedTypes[i].getSimpleName());
    }
    throw new IllegalArgumentException(
        annotationName + " can only be applied to " + sb + " fields, but got " + fieldType);
  }
}
