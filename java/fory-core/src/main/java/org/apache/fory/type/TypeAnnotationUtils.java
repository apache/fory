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
import org.apache.fory.annotation.Int32Type;
import org.apache.fory.annotation.Int64Type;
import org.apache.fory.annotation.Int8ArrayType;
import org.apache.fory.annotation.UInt16Elements;
import org.apache.fory.annotation.UInt16Type;
import org.apache.fory.annotation.UInt32Elements;
import org.apache.fory.annotation.UInt32Type;
import org.apache.fory.annotation.UInt64Elements;
import org.apache.fory.annotation.UInt64Type;
import org.apache.fory.annotation.UInt8Elements;
import org.apache.fory.annotation.UInt8Type;
import org.apache.fory.collection.UInt16List;
import org.apache.fory.collection.UInt32List;
import org.apache.fory.collection.UInt64List;
import org.apache.fory.collection.UInt8List;

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
    if (typeAnnotation instanceof UInt8Type) {
      checkFieldType(fieldType, "@UInt8Type", int.class, Integer.class);
      return Types.UINT8;
    } else if (typeAnnotation instanceof UInt16Type) {
      checkFieldType(fieldType, "@UInt16Type", int.class, Integer.class);
      return Types.UINT16;
    } else if (typeAnnotation instanceof UInt32Type) {
      checkFieldType(fieldType, "@UInt32Type", long.class, Long.class);
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
      checkFieldType(fieldType, "@UInt64Type", long.class, Long.class);
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
    } else if (typeAnnotation instanceof Int8ArrayType) {
      checkFieldType(fieldType, "@Int8ArrayType", byte[].class);
      return Types.INT8_ARRAY;
    } else if (typeAnnotation instanceof UInt8Elements) {
      checkFieldType(fieldType, "@UInt8Elements", byte[].class);
      return Types.UINT8_ARRAY;
    } else if (typeAnnotation instanceof UInt16Elements) {
      checkFieldType(fieldType, "@UInt16Elements", short[].class);
      return Types.UINT16_ARRAY;
    } else if (typeAnnotation instanceof UInt32Elements) {
      checkFieldType(fieldType, "@UInt32Elements", int[].class);
      return Types.UINT32_ARRAY;
    } else if (typeAnnotation instanceof UInt64Elements) {
      checkFieldType(fieldType, "@UInt64Elements", long[].class);
      return Types.UINT64_ARRAY;
    }
    throw new IllegalArgumentException("Unsupported type annotation: " + typeAnnotation.getClass());
  }

  public static int getPrimitiveListTypeId(Annotation typeAnnotation, Class<?> fieldType) {
    int elementTypeId = getPrimitiveListElementTypeId(typeAnnotation, fieldType);
    if (elementTypeId == Types.UNKNOWN) {
      return Types.UNKNOWN;
    }
    if (usesCollectionProtocolForPrimitiveListElementType(elementTypeId)) {
      return Types.LIST;
    }
    return Types.getPrimitiveArrayTypeId(elementTypeId);
  }

  public static int getPrimitiveListElementTypeId(Annotation typeAnnotation, Class<?> fieldType) {
    if (typeAnnotation == null) {
      return Types.UNKNOWN;
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

  public static boolean usesCollectionProtocolForPrimitiveList(
      Annotation typeAnnotation, Class<?> fieldType) {
    return usesCollectionProtocolForPrimitiveListElementType(
        getPrimitiveListElementTypeId(typeAnnotation, fieldType));
  }

  private static boolean usesCollectionProtocolForPrimitiveListElementType(int elementTypeId) {
    return elementTypeId == Types.VAR_UINT32
        || elementTypeId == Types.VAR_UINT64
        || elementTypeId == Types.TAGGED_UINT64;
  }

  public static Class<?> getPrimitiveListElementClass(Class<?> fieldType) {
    if (fieldType == UInt8List.class || fieldType == UInt16List.class) {
      return Integer.class;
    }
    if (fieldType == UInt32List.class || fieldType == UInt64List.class) {
      return Long.class;
    }
    return null;
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
    return annotation instanceof UInt8Type
        || annotation instanceof UInt16Type
        || annotation instanceof UInt32Type
        || annotation instanceof UInt64Type
        || annotation instanceof Int32Type
        || annotation instanceof Int64Type
        || annotation instanceof Int8ArrayType
        || annotation instanceof UInt8Elements
        || annotation instanceof UInt16Elements
        || annotation instanceof UInt32Elements
        || annotation instanceof UInt64Elements;
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
