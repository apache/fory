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

import org.apache.fory.Fory;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.type.unsigned.Uint16;
import org.apache.fory.type.unsigned.Uint32;
import org.apache.fory.type.unsigned.Uint64;
import org.apache.fory.type.unsigned.Uint8;

/**
 * Dispatch IDs for basic types. These IDs identify the type for serialization dispatch. The
 * nullable/primitive handling is determined by SerializationFieldInfo.nullable and
 * SerializationFieldInfo.isPrimitiveField at runtime, not encoded in the dispatch ID.
 */
public class DispatchId {
  public static final int UNKNOWN = 0;
  public static final int BOOL = 1;
  public static final int INT8 = 2;
  public static final int CHAR = 3;
  public static final int INT16 = 4;
  public static final int INT32 = 5;
  public static final int VARINT32 = 6;
  public static final int INT64 = 7;
  public static final int VARINT64 = 8;
  public static final int TAGGED_INT64 = 9;
  public static final int FLOAT32 = 10;
  public static final int FLOAT64 = 11;
  public static final int UINT8 = 12;
  public static final int UINT16 = 13;
  public static final int UINT32 = 14;
  public static final int VAR_UINT32 = 15;
  public static final int UINT64 = 16;
  public static final int FLOAT16 = 17;
  public static final int VAR_UINT64 = 18;
  public static final int TAGGED_UINT64 = 19;
  public static final int EXT_UINT8 = 20;
  public static final int EXT_UINT16 = 21;
  public static final int EXT_UINT32 = 22;
  public static final int EXT_VAR_UINT32 = 23;
  public static final int EXT_UINT64 = 24;
  public static final int EXT_VAR_UINT64 = 25;
  public static final int STRING = 26;

  public static int getDispatchId(Fory fory, Descriptor d) {
    int typeId = Types.getDescriptorTypeId(fory, d);
    Class<?> rawType = d.getTypeRef().getRawType();
    if (rawType == Float16.class) {
      return FLOAT16;
    }
    if (fory.isCrossLanguage()) {
      return adjustUnsignedDispatchId(typeId, rawType, xlangTypeIdToDispatchId(typeId));
    } else {
      return adjustUnsignedDispatchId(typeId, rawType, nativeIdToDispatchId(typeId, d));
    }
  }

  private static int xlangTypeIdToDispatchId(int typeId) {
    switch (typeId) {
      case Types.BOOL:
        return BOOL;
      case Types.INT8:
        return INT8;
      case Types.INT16:
        return INT16;
      case Types.INT32:
        return INT32;
      case Types.VARINT32:
        return VARINT32;
      case Types.INT64:
        return INT64;
      case Types.VARINT64:
        return VARINT64;
      case Types.TAGGED_INT64:
        return TAGGED_INT64;
      case Types.UINT8:
        return UINT8;
      case Types.UINT16:
        return UINT16;
      case Types.UINT32:
        return UINT32;
      case Types.VAR_UINT32:
        return VAR_UINT32;
      case Types.UINT64:
        return UINT64;
      case Types.VAR_UINT64:
        return VAR_UINT64;
      case Types.TAGGED_UINT64:
        return TAGGED_UINT64;
      case Types.FLOAT16:
        return FLOAT16;
      case Types.FLOAT32:
        return FLOAT32;
      case Types.FLOAT64:
        return FLOAT64;
      case Types.STRING:
        return STRING;
      default:
        return UNKNOWN;
    }
  }

  private static int nativeIdToDispatchId(int nativeId, Descriptor descriptor) {
    if (nativeId >= Types.BOOL && nativeId <= ClassResolver.NATIVE_START_ID) {
      return xlangTypeIdToDispatchId(nativeId);
    }
    if (nativeId == ClassResolver.CHAR_ID || nativeId == ClassResolver.PRIMITIVE_CHAR_ID) {
      return CHAR;
    }
    if (nativeId >= ClassResolver.PRIMITIVE_VOID_ID
        && nativeId <= ClassResolver.PRIMITIVE_FLOAT64_ID) {
      throw new IllegalArgumentException(
          String.format(
              "%s should use `Types.BOOL~Types.FLOAT64` with nullable meta instead, but got %s",
              descriptor.getField(), nativeId));
    }
    return xlangTypeIdToDispatchId(nativeId);
  }

  private static int adjustUnsignedDispatchId(int typeId, Class<?> rawType, int dispatchId) {
    if (rawType == Uint8.class && typeId == Types.UINT8) {
      return EXT_UINT8;
    }
    if (rawType == Uint16.class && typeId == Types.UINT16) {
      return EXT_UINT16;
    }
    if (rawType == Uint32.class) {
      if (typeId == Types.VAR_UINT32) {
        return EXT_VAR_UINT32;
      }
      if (typeId == Types.UINT32) {
        return EXT_UINT32;
      }
    }
    if (rawType == Uint64.class) {
      if (typeId == Types.VAR_UINT64) {
        return EXT_VAR_UINT64;
      }
      if (typeId == Types.UINT64) {
        return EXT_UINT64;
      }
    }
    return dispatchId;
  }

  /** Check if the dispatch ID represents a basic type that can be inlined. */
  public static boolean isBasicType(int dispatchId) {
    return dispatchId >= BOOL && dispatchId <= STRING;
  }
}
