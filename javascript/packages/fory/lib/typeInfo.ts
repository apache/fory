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

import { TypeMeta } from "./meta/TypeMeta";
import { ForyTypeInfoSymbol, InternalSerializerType, WithForyClsInfo, TypeId } from "./type";

const initMeta = (target: new () => any, typeInfo: TypeInfo) => {
  if (!target.prototype) {
    target.prototype = {};
  }
  target.prototype[ForyTypeInfoSymbol] = {
    structTypeInfo: Type.struct({
      ...(
        TypeId.IS_NAMED_TYPE(typeInfo.typeId)
          ? {
            namespace: typeInfo.namespace,
            typeName: typeInfo.typeName,
          }
          : {
            typeId: typeInfo.typeId,
          }
      ),
    }, targetFields.get(target) || {}, {
      withConstructor: true,
    }),
  } as WithForyClsInfo;
};

const targetFields = new WeakMap<new () => any, { [key: string]: TypeInfo }>();

const addField = (target: new () => any, key: string, des: TypeInfo) => {
  if (!targetFields.has(target)) {
    targetFields.set(target, {});
  }
  targetFields.get(target)![key] = des;
};

// eslint-disable-next-line
class ExtensibleFunction extends Function {
  constructor(f: (target: any, key?: string | { name?: string }) => void) {
    super();
    return Object.setPrototypeOf(f, new.target.prototype);
  }
}

/**
 * T is for type matching
 */
// eslint-disable-next-line
export class TypeInfo<T = unknown> extends ExtensibleFunction {
  dynamicTypeId = -1;
  hash = BigInt(0);
  named = "";
  namespace = "";
  typeName = "";
  options?: any;

  private constructor(public type: InternalSerializerType, public typeId: number) {
    super(function (target: any, key?: string | { name?: string }) {
      if (key === undefined) {
        initMeta(target, that as unknown as StructTypeInfo);
      } else {
        const keyString = typeof key === "string" ? key : key?.name;
        if (!keyString) {
          throw new Error("Decorators can only be placed on classes and fields");
        }
        addField(target.constructor, keyString, that);
      }
    });
    // eslint-disable-next-line
    const that = this;
  }

  static fromNonParam<T extends InternalSerializerType>(type: T, typeId: number) {
    return new TypeInfo<{
      type: T;
    }>(type, typeId);
  }

  static fromStruct<T = any>(nameInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, props?: Record<string, TypeInfo>, {
    withConstructor = false,
  }: {
    withConstructor?: boolean;
  } = {}) {
    let typeId: number | undefined;
    let namespace: string | undefined;
    let typeName: string | undefined;
    if (typeof nameInfo === "string") {
      typeName = nameInfo;
    } else if (typeof nameInfo === "number") {
      typeId = nameInfo;
    } else {
      namespace = nameInfo.namespace;
      typeName = nameInfo.typeName;
      typeId = nameInfo.typeId;
    }
    if (typeId !== undefined && typeName !== undefined) {
      throw new Error(`type name ${typeName} and id ${typeId} should not be set at the same time`);
    }
    if (!typeId) {
      if (!typeName) {
        throw new Error(`type name and type id should be set at least one`);
      }
    }
    if (!namespace && typeName) {
      const splits = typeName!.split(".");
      if (splits.length > 1) {
        namespace = splits[0];
        typeName = splits.slice(1).join(".");
      }
    }
    const finalTypeId = typeId !== undefined ? ((typeId << 8) | TypeId.STRUCT) : TypeId.NAMED_STRUCT;
    const typeInfo = new TypeInfo<T>(InternalSerializerType.STRUCT, finalTypeId).cast<StructTypeInfo>();
    typeInfo.options = {
      props: props || {},
      withConstructor,
    };
    typeInfo.namespace = namespace || "";
    typeInfo.typeName = typeId !== undefined ? "" : typeName!;
    typeInfo.hash = TypeMeta.fromTypeInfo(typeInfo).getHash();
    typeInfo.named = `${typeInfo.namespace}$${typeInfo.typeName}`;
    return typeInfo as TypeInfo<T>;
  }

  static fromWithOptions<T extends InternalSerializerType, T2>(type: T, typeId: number, options: T2) {
    const typeInfo = new TypeInfo<{
      type: T;
      options: T2;
    }>(type, typeId);
    typeInfo.options = options;
    return typeInfo;
  }

  static fromEnum<T>(nameInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, props: { [key: string]: any }) {
    let typeId: number | undefined;
    let namespace: string | undefined;
    let typeName: string | undefined;
    if (typeof nameInfo === "string") {
      typeName = nameInfo;
    } else if (typeof nameInfo === "number") {
      typeId = nameInfo;
    } else {
      namespace = nameInfo.namespace;
      typeName = nameInfo.typeName;
      typeId = nameInfo.typeId;
    }
    if (typeId !== undefined && typeName !== undefined) {
      throw new Error(`type name ${typeName} and id ${typeId} should not be set at the same time`);
    }
    if (!typeId) {
      if (!typeName) {
        throw new Error(`type name and type id should be set at least one`);
      }
    }
    if (!namespace && typeName) {
      const splits = typeName!.split(".");
      if (splits.length > 1) {
        namespace = splits[0];
        typeName = splits.slice(1).join(".");
      }
    }
    const finalTypeId = typeId !== undefined ? ((typeId << 8) | TypeId.ENUM) : TypeId.NAMED_ENUM;
    const typeInfo = new TypeInfo<T>(InternalSerializerType.ENUM, finalTypeId);
    typeInfo.cast<EnumTypeInfo>().options = {
      inner: props,
    };
    typeInfo.namespace = namespace || "";
    typeInfo.typeName = typeId !== undefined ? "" : typeName!;
    typeInfo.named = `${typeInfo.namespace}$${typeInfo.typeName}`;
    return typeInfo;
  }

  castToStruct() {
    return this as unknown as StructTypeInfo;
  }

  cast<T>() {
    return this as unknown as T;
  }
}

export interface StructTypeInfo extends TypeInfo {
  options: {
    props?: { [key: string]: TypeInfo & { nullable?: boolean } };
    withConstructor?: boolean;
  };
}

export interface EnumTypeInfo extends TypeInfo {
  options: {
    inner: { [key: string]: any };
  };
}

export interface OneofTypeInfo extends TypeInfo {
  options: {
    inner: { [key: string]: TypeInfo };
  };
}

export interface ArrayTypeInfo extends TypeInfo {
  options: {
    inner: TypeInfo;
  };
}

export interface TupleTypeInfo extends TypeInfo {
  options: {
    inner: TypeInfo[];
  };
}

export interface SetTypeInfo extends TypeInfo {
  options: {
    key: TypeInfo;
  };
}

export interface MapTypeInfo extends TypeInfo {
  options: {
    key: TypeInfo;
    value: TypeInfo;
  };
}

type Props<T> = T extends {
  options: {
    props?: infer T2 extends { [key: string]: any };
  };
}
  ? {
    [P in keyof T2]?: (InputType<T2[P]> | null);
  }
  : unknown;

type InnerProps<T> = T extends {
  options: {
    inner: infer T2 extends TypeInfo;
  };
}
  ? (InputType<T2> | null)[]
  : unknown;

type MapProps<T> = T extends {
  options: {
    key: infer T2 extends TypeInfo;
    value: infer T3 extends TypeInfo;
  };
}
  ? Map<InputType<T2>, InputType<T3> | null>
  : unknown;

type TupleProps<T> = T extends {
  options: {
    inner: infer T2 extends readonly [...TypeInfo[]];
  };
}
  ? { [K in keyof T2]: InputType<T2[K]> }
  : unknown;

type Value<T> = T extends { [s: string]: infer T2 } ? T2 : unknown;

type EnumProps<T> = T extends {
  options: {
    inner: infer T2;
  };
}
  ? Value<T2>
  : unknown;

type OneofProps<T> = T extends {
  options: {
    inner?: infer T2 extends { [key: string]: any };
  };
}
  ? {
    [P in keyof T2]?: (InputType<T2[P]> | null);
  }
  : unknown;

type OneofResult<T> = T extends {
  options: {
    inner?: infer T2;
  };
}
  ? ResultType<Value<T2>>
  : unknown;

type SetProps<T> = T extends {
  options: {
    key: infer T2 extends TypeInfo;
  };
}
  ? Set<(InputType<T2> | null)>
  : unknown;

export type InputType<T> = T extends TypeInfo<infer M> ? HintInput<M> : unknown;

export type HintInput<T> = T extends unknown ? any : T extends {
  type: InternalSerializerType.STRUCT;
}
  ? Props<T>
  : T extends {
    type: InternalSerializerType.STRING;
  }
  ? string
  : T extends {
    type: InternalSerializerType.TUPLE;
  }
  ? TupleProps<T>
  : T extends {
    type:
    | InternalSerializerType.INT8
    | InternalSerializerType.INT16
    | InternalSerializerType.INT32
    | InternalSerializerType.VARINT32
    | InternalSerializerType.UINT8
    | InternalSerializerType.UINT16
    | InternalSerializerType.UINT32
    | InternalSerializerType.VAR_UINT32
    | InternalSerializerType.FLOAT16
    | InternalSerializerType.FLOAT32
    | InternalSerializerType.FLOAT64;
  }
  ? number

  : T extends {
    type: InternalSerializerType.VARINT64
    | InternalSerializerType.TAGGED_INT64
    | InternalSerializerType.INT64
    | InternalSerializerType.UINT64
    | InternalSerializerType.VAR_UINT64
    | InternalSerializerType.TAGGED_UINT64;
  }
  ? bigint
  : T extends {
    type: InternalSerializerType.MAP;
  }
  ? MapProps<T>
  : T extends {
    type: InternalSerializerType.SET;
  }
  ? SetProps<T>
  : T extends {
    type: InternalSerializerType.ARRAY;
  }
  ? InnerProps<T>
  : T extends {
    type: InternalSerializerType.BOOL;
  }
  ? boolean
  : T extends {
    type: InternalSerializerType.DURATION;
  }
  ? Date
  : T extends {
    type: InternalSerializerType.TIMESTAMP;
  }
  ? number
  : T extends {
    type: InternalSerializerType.ANY;
  }
  ? any
  : T extends {
    type: InternalSerializerType.BINARY;
  }
  ? Uint8Array
  : T extends {
    type: InternalSerializerType.ENUM;
  }
  ? EnumProps<T> : T extends {
    type: InternalSerializerType.ONEOF;
  } ? OneofProps<T> : unknown;

export type ResultType<T> = T extends TypeInfo<infer M> ? HintResult<M> : HintResult<T>;

export type HintResult<T> = T extends never ? any : T extends {
  type: InternalSerializerType.STRUCT;
}
  ? Props<T>
  : T extends {
    type: InternalSerializerType.STRING;
  }
  ? string
  : T extends {
    type: InternalSerializerType.TUPLE;
  }
  ? TupleProps<T>
  : T extends {
    type:
    | InternalSerializerType.INT8
    | InternalSerializerType.INT16
    | InternalSerializerType.INT32
    | InternalSerializerType.VARINT32
    | InternalSerializerType.UINT8
    | InternalSerializerType.UINT16
    | InternalSerializerType.UINT32
    | InternalSerializerType.VAR_UINT32
    | InternalSerializerType.FLOAT16
    | InternalSerializerType.FLOAT32
    | InternalSerializerType.FLOAT64;
  }
  ? number

  : T extends {
    type: InternalSerializerType.TAGGED_INT64
    | InternalSerializerType.INT64
    | InternalSerializerType.UINT64
    | InternalSerializerType.VAR_UINT64
    | InternalSerializerType.TAGGED_UINT64;
  }
  ? bigint
  : T extends {
    type: InternalSerializerType.MAP;
  }
  ? MapProps<T>
  : T extends {
    type: InternalSerializerType.SET;
  }
  ? SetProps<T>
  : T extends {
    type: InternalSerializerType.ARRAY;
  }
  ? InnerProps<T>
  : T extends {
    type: InternalSerializerType.BOOL;
  }
  ? boolean
  : T extends {
    type: InternalSerializerType.DURATION;
  }
  ? Date
  : T extends {
    type: InternalSerializerType.TIMESTAMP;
  }
  ? number
  : T extends {
    type: InternalSerializerType.BINARY;
  }
  ? Uint8Array : T extends {
    type: InternalSerializerType.ANY;
  }
  ? any
  : T extends {
    type: InternalSerializerType.ENUM;
  }
  ? EnumProps<T> : T extends {
    type: InternalSerializerType.ONEOF;
  } ? OneofResult<T> : unknown;

export const Type = {
  any() {
    return TypeInfo.fromNonParam(InternalSerializerType.ANY, TypeId.STRUCT);
  },
  oneof<T extends { [key: string]: TypeInfo }>(inner?: T) {
    return TypeInfo.fromWithOptions(InternalSerializerType.ONEOF as const, TypeId.STRUCT, {
      inner,
    });
  },
  array<T extends TypeInfo>(inner: T) {
    return TypeInfo.fromWithOptions(InternalSerializerType.ARRAY as const, TypeId.ARRAY, {
      inner,
    });
  },
  tuple<T1 extends readonly [...readonly TypeInfo[]]>(t1: T1) {
    return TypeInfo.fromWithOptions(InternalSerializerType.TUPLE as const, TypeId.LIST, {
      inner: t1,
    });
  },
  map<T1 extends TypeInfo, T2 extends TypeInfo>(
    key: T1,
    value: T2
  ) {
    return TypeInfo.fromWithOptions(InternalSerializerType.MAP as const, TypeId.MAP, {
      key,
      value,
    });
  },
  set<T extends TypeInfo>(key: T) {
    return TypeInfo.fromWithOptions(InternalSerializerType.SET as const, TypeId.SET, {
      key,
    });
  },
  enum<T1 extends { [key: string]: any }>(nameInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, t1: T1) {
    return TypeInfo.fromEnum<{
      type: InternalSerializerType.ENUM;
      options: {
        inner: T1;
      };
    }>(nameInfo, t1);
  },
  struct<T extends { [key: string]: TypeInfo }>(nameInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, props?: T, {
    withConstructor = false,
  }: {
    withConstructor?: boolean;
  } = {}) {
    return TypeInfo.fromStruct<{
      type: InternalSerializerType.STRUCT;
      options: {
        props: T;
      };
    }>(nameInfo, props, {
      withConstructor,
    });
  },
  string() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.STRING as const,
      (TypeId.STRING),
    );
  },
  bool() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.BOOL as const,
      (TypeId.BOOL),
    );
  },
  int8() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.INT8 as const,
      (TypeId.INT8),
    );
  },
  int16() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.INT16 as const,
      (TypeId.INT16),

    );
  },
  int32() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.INT32 as const,
      (TypeId.INT32),

    );
  },
  varInt32() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.VARINT32 as const,
      (TypeId.VARINT32),

    );
  },
  int64() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.INT64 as const,
      (TypeId.INT64),

    );
  },
  sliInt64() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.TAGGED_INT64 as const,
      (TypeId.TAGGED_INT64),

    );
  },
  float16() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.FLOAT16 as const,
      (TypeId.FLOAT16),

    );
  },
  float32() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.FLOAT32 as const,
      (TypeId.FLOAT32),

    );
  },
  float64() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.FLOAT64 as const,
      (TypeId.FLOAT64),

    );
  },
  uint8() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.UINT8 as const,
      (TypeId.UINT8),
    );
  },
  uint16() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.UINT16 as const,
      (TypeId.UINT16),
    );
  },
  uint32() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.UINT32 as const,
      (TypeId.UINT32),
    );
  },
  varUInt32() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.VAR_UINT32 as const,
      (TypeId.VAR_UINT32),
    );
  },
  uint64() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.UINT64 as const,
      (TypeId.UINT64),
    );
  },
  varUInt64() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.VAR_UINT64 as const,
      (TypeId.VAR_UINT64),
    );
  },
  taggedUInt64() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.TAGGED_UINT64 as const,
      (TypeId.TAGGED_UINT64),
    );
  },
  binary() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.BINARY as const,
      (TypeId.BINARY),

    );
  },
  duration() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.DURATION as const,
      (TypeId.DURATION),

    );
  },
  timestamp() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.TIMESTAMP as const,
      (TypeId.TIMESTAMP),

    );
  },
  boolArray() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.BOOL_ARRAY as const,
      (TypeId.BOOL_ARRAY),

    );
  },
  int8Array() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.INT8_ARRAY as const,
      (TypeId.INT8_ARRAY),

    );
  },
  int16Array() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.INT16_ARRAY as const,
      (TypeId.INT16_ARRAY),

    );
  },
  int32Array() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.INT32_ARRAY as const,
      (TypeId.INT32_ARRAY),

    );
  },
  int64Array() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.INT64_ARRAY as const,
      (TypeId.INT64_ARRAY),

    );
  },
  float16Array() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.FLOAT16_ARRAY as const,
      (TypeId.FLOAT16_ARRAY),

    );
  },
  float32Array() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.FLOAT32_ARRAY as const,
      (TypeId.FLOAT32_ARRAY),

    );
  },
  float64Array() {
    return TypeInfo.fromNonParam(
      InternalSerializerType.FLOAT64_ARRAY as const,
      (TypeId.FLOAT64_ARRAY)
    );
  },
};
