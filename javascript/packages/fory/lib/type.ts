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

import Fory from "./fory";
import { BinaryReader } from "./reader";
import { TypeInfo } from "./typeInfo";
import { BinaryWriter } from "./writer";

export const TypeId = {
  // Unknown/polymorphic type marker.
  UNKNOWN: 0,
  // a boolean value (true or false).
  BOOL: 1,
  // a 8-bit signed integer.
  INT8: 2,
  // a 16-bit signed integer.
  INT16: 3,
  // a 32-bit signed integer.
  INT32: 4,
  // a 32-bit signed integer which uses fory var_int32 encoding.
  VARINT32: 5,
  // a 64-bit signed integer.
  INT64: 6,
  // a 64-bit signed integer which uses fory PVL encoding.
  VARINT64: 7,
  // a 64-bit signed integer which uses fory hybrid encoding.
  TAGGED_INT64: 8,
  // an 8-bit unsigned integer.
  UINT8: 9,
  // a 16-bit unsigned integer.
  UINT16: 10,
  // a 32-bit unsigned integer.
  UINT32: 11,
  // a 32-bit unsigned integer which uses fory var_uint32 encoding.
  VAR_UINT32: 12,
  // a 64-bit unsigned integer.
  UINT64: 13,
  // a 64-bit unsigned integer which uses fory var_uint64 encoding.
  VAR_UINT64: 14,
  // a 64-bit unsigned integer which uses fory hybrid encoding.
  TAGGED_UINT64: 15,
  // an 8-bit floating point number.
  FLOAT8: 16,
  // a 16-bit floating point number.
  FLOAT16: 17,
  // a 16-bit brain floating point number.
  BFLOAT16: 18,
  // a 32-bit floating point number.
  FLOAT32: 19,
  // a 64-bit floating point number including NaN and Infinity.
  FLOAT64: 20,
  // a text string encoded using Latin1/UTF16/UTF-8 encoding.
  STRING: 21,
  // a sequence of objects.
  LIST: 22,
  // an unordered set of unique elements.
  SET: 23,
  // a map of key-value pairs.
  MAP: 24,
  // a data type consisting of a set of named values.
  ENUM: 25,
  // an enum whose value will be serialized as the registered name.
  NAMED_ENUM: 26,
  // a morphic(final) type serialized by Fory Struct serializer.
  STRUCT: 27,
  // a morphic(final) type serialized by Fory compatible Struct serializer.
  COMPATIBLE_STRUCT: 28,
  // a `struct` whose type mapping will be encoded as a name.
  NAMED_STRUCT: 29,
  // a `compatible_struct` whose type mapping will be encoded as a name.
  NAMED_COMPATIBLE_STRUCT: 30,
  // a type which will be serialized by a customized serializer.
  EXT: 31,
  // an `ext` type whose type mapping will be encoded as a name.
  NAMED_EXT: 32,
  // a tagged union type that can hold one of several alternative types.
  UNION: 33,
  // a union value with embedded numeric union type ID.
  TYPED_UNION: 34,
  // a union value with embedded union type name/TypeDef.
  NAMED_UNION: 35,
  // represents an empty/unit value with no data.
  NONE: 36,
  // an absolute length of time, independent of any calendar/timezone, as a count of nanoseconds.
  DURATION: 37,
  // a point in time, independent of any calendar/timezone, as a count of nanoseconds.
  TIMESTAMP: 38,
  // a naive date without timezone.
  DATE: 39,
  // exact decimal value represented as an integer value in two's complement.
  DECIMAL: 40,
  // a variable-length array of bytes.
  BINARY: 41,
  // a multidimensional array which every sub-array can have different sizes but all have the same type.
  ARRAY: 42,
  // one dimensional bool array.
  BOOL_ARRAY: 43,
  // one dimensional int8 array.
  INT8_ARRAY: 44,
  // one dimensional int16 array.
  INT16_ARRAY: 45,
  // one dimensional int32 array.
  INT32_ARRAY: 46,
  // one dimensional int64 array.
  INT64_ARRAY: 47,
  // one dimensional uint8 array.
  UINT8_ARRAY: 48,
  // one dimensional uint16 array.
  UINT16_ARRAY: 49,
  // one dimensional uint32 array.
  UINT32_ARRAY: 50,
  // one dimensional uint64 array.
  UINT64_ARRAY: 51,
  // one dimensional float8 array.
  FLOAT8_ARRAY: 52,
  // one dimensional float16 array.
  FLOAT16_ARRAY: 53,
  // one dimensional bfloat16 array.
  BFLOAT16_ARRAY: 54,
  // one dimensional float32 array.
  FLOAT32_ARRAY: 55,
  // one dimensional float64 array.
  FLOAT64_ARRAY: 56,

  // BOUND id remains at 64
  BOUND: 64,

  isNamedType(id: number) {
    return [
      TypeId.NAMED_COMPATIBLE_STRUCT,
      TypeId.NAMED_ENUM,
      TypeId.NAMED_EXT,
      TypeId.NAMED_STRUCT,
      TypeId.NAMED_UNION,
    ].includes(id as any);
  },
  polymorphicType(id: number) {
    return [TypeId.STRUCT, TypeId.NAMED_STRUCT, TypeId.COMPATIBLE_STRUCT, TypeId.NAMED_COMPATIBLE_STRUCT, TypeId.EXT, TypeId.NAMED_EXT].includes(id as any);
  },
  structType(id: number) {
    return [TypeId.STRUCT, TypeId.NAMED_STRUCT, TypeId.COMPATIBLE_STRUCT, TypeId.NAMED_COMPATIBLE_STRUCT].includes(id as any);
  },
  extType(id: number) {
    return [TypeId.EXT, TypeId.NAMED_EXT].includes(id as any);
  },
  enumType(id: number) {
    return [TypeId.ENUM, TypeId.NAMED_ENUM].includes(id as any);
  },
  userDefinedType(id: number) {
    return this.structType(id)
      || this.extType(id)
      || this.enumType(id)
      || id == TypeId.TYPED_UNION
      || id == TypeId.NAMED_UNION;
  },
  isBuiltin(id: number) {
    return !this.userDefinedType(id) && id !== TypeId.UNKNOWN;
  },
  needsUserTypeId(id: number) {
    return [
      TypeId.ENUM,
      TypeId.STRUCT,
      TypeId.COMPATIBLE_STRUCT,
      TypeId.EXT,
      TypeId.TYPED_UNION,
    ].includes(id as any);
  },
  isCompressedType(typeId: number) {
    switch (typeId) {
      case TypeId.VARINT32:
      case TypeId.VAR_UINT32:
      case TypeId.VARINT64:
      case TypeId.VAR_UINT64:
      case TypeId.TAGGED_INT64:
      case TypeId.TAGGED_UINT64:
        return true;
      default:
        return false;
    }
  },
} as const;

export enum ConfigFlags {
  isNullFlag = 1 << 0,
  isCrossLanguageFlag = 1 << 1,
  isOutOfBandFlag = 1 << 2,
}

export type CustomSerializer<T> = {
  read: (result: T, reader: BinaryReader, fory: Fory) => void;
  write: (v: T, writer: BinaryWriter, fory: Fory) => void;
};

// read, write
export type Serializer<T = any> = {
  fixedSize: number;
  getTypeInfo: () => TypeInfo;
  needToWriteRef: () => boolean;
  getTypeId: () => number;
  getUserTypeId: () => number;
  getHash: () => number;

  // for writing
  write: (v: T) => void;
  writeRef: (v: T) => void;
  writeNoRef: (v: T) => void;
  writeRefOrNull: (v: T) => boolean;
  writeTypeInfo: (v: T) => void;

  read: (fromRef: boolean) => T;
  readRef: () => T;
  readRefWithoutTypeInfo: () => T;
  readNoRef: (fromRef: boolean) => T;
  readTypeInfo: () => void;
};

export enum RefFlags {
  NullFlag = -3,
  // RefFlag indicates that object is a not-null value.
  // We don't use another byte to indicate REF, so that we can save one byte.
  RefFlag = -2,
  // NotNullValueFlag indicates that the object is a non-null value.
  NotNullValueFlag = -1,
  // RefValueFlag indicates that the object is a referencable and first read.
  RefValueFlag = 0,
}

export const MaxInt32 = 2147483647;
export const MinInt32 = -2147483648;
export const MaxUInt32 = 0xFFFFFFFF;
export const MinUInt32 = 0;
export const HalfMaxInt32 = MaxInt32 / 2;
export const HalfMinInt32 = MinInt32 / 2;

export const LATIN1 = 0;
export const UTF8 = 2;
export const UTF16 = 1;
export interface Hps {
  serializeString: (str: string, dist: Uint8Array, offset: number) => number;
}

export enum Mode {
  SchemaConsistent,
  Compatible,
}

export interface Config {
  hps?: Hps;
  refTracking: boolean | null;
  useSliceString: boolean;
  hooks: {
    afterCodeGenerated?: (code: string) => string;
  };
  compatible?: boolean;
  maxStringBytes?: number;
  maxCollectionLength?: number;
  maxMapEntries?: number;
}

export interface WithForyClsInfo {
  structTypeInfo: TypeInfo;
}

export const ForyTypeInfoSymbol = Symbol("foryTypeInfo");
