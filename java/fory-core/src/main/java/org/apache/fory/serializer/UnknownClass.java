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

package org.apache.fory.serializer;

import org.apache.fory.collection.LazyMap;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Config;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.type.TypeUtils;

/**
 * A class for hold deserialization data when the class doesn't exist in this process. When {@link
 * CompatibleMode#COMPATIBLE} is enabled
 *
 * @see Config#isMetaShareEnabled()
 */
public interface UnknownClass {
  // @formatter:off

  enum UnknownEnum implements UnknownClass {
    V0,
    V1,
    V2,
    V3,
    V4,
    V5,
    V6,
    V7,
    V8,
    V9,
    V10,
    V11,
    V12,
    V13,
    V14,
    V15,
    V16,
    V17,
    V18,
    V19,
    V20,
    V21,
    V22,
    V23,
    V24,
    V25,
    V26,
    V27,
    V28,
    V29,
    V30,
    V31,
    V32,
    V33,
    V34,
    V35,
    V36,
    V37,
    V38,
    V39,
    V40,
    V41,
    V42,
    V43,
    V44,
    V45,
    V46,
    V47,
    V48,
    V49,
    V50,
    V51,
    V52,
    V53,
    V54,
    V55,
    V56,
    V57,
    V58,
    V59,
    V60,
    V61,
    V62,
    V63,
    UNKNOWN
  }

  /** Ensure no fields here to avoid conflicts with peer class fields. */
  class UnknownEmptyStruct implements UnknownClass {}

  class UnknownStruct extends LazyMap implements UnknownClass {
    final TypeDef typeDef;

    public UnknownStruct(TypeDef typeDef) {
      this.typeDef = typeDef;
    }
  }

  Class<?> UnknownEnum1DArray = UnknownEnum[].class;
  Class<?> UnknownEnum2DArray = UnknownEnum[][].class;
  Class<?> UnknownEnum3DArray = UnknownEnum[][][].class;
  Class<?> UnknownEmptyStruct1DArray = UnknownEmptyStruct[].class;
  Class<?> UnknownEmptyStruct2DArray = UnknownEmptyStruct[][].class;
  Class<?> UnknownEmptyStruct3DArray = UnknownEmptyStruct[][][].class;
  Class<?> UnknownStruct1DArray = UnknownStruct[].class;
  Class<?> UnknownStruct2DArray = UnknownStruct[][].class;
  Class<?> UnknownStruct3DArray = UnknownStruct[][][].class;

  static boolean isUnknowClass(Class<?> cls) {
    if (cls.isArray()) {
      Class<?> component = TypeUtils.getArrayComponent(cls);
      return UnknownClass.class.isAssignableFrom(component);
    }
    return UnknownClass.class.isAssignableFrom(cls);
  }

  static Class<?> getUnknowClass(boolean isEnum, int arrayDims, boolean shareMeta) {
    return getUnknowClass("Unknown", isEnum, arrayDims, shareMeta);
  }

  static Class<?> getUnknowClass(
      String className, boolean isEnum, int arrayDims, boolean shareMeta) {
    if (arrayDims != 0) {
      if (isEnum) {
        switch (arrayDims) {
          case 1:
            return UnknownEnum1DArray;
          case 2:
            return UnknownEnum2DArray;
          case 3:
            return UnknownEnum3DArray;
          default:
            throw new UnsupportedOperationException(
                String.format(
                    "Unsupported array dimensions %s for nonexistent class %s",
                    arrayDims, className));
        }
      } else {
        switch (arrayDims) {
          case 1:
            return shareMeta ? UnknownStruct1DArray : UnknownEmptyStruct1DArray;
          case 2:
            return shareMeta ? UnknownStruct2DArray : UnknownEmptyStruct2DArray;
          case 3:
            return shareMeta ? UnknownStruct3DArray : UnknownEmptyStruct3DArray;
          default:
            throw new UnsupportedOperationException(
                String.format(
                    "Unsupported array dimensions %s for nonexistent class %s",
                    arrayDims, className));
        }
      }
    } else if (isEnum) {
      return UnknownEnum.class;
    } else {
      return shareMeta ? UnknownStruct.class : UnknownEmptyStruct.class;
    }
  }
}
