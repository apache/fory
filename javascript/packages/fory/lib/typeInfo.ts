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
import { refTrackingUnableTypeId } from "./meta/TypeMeta";
import { ForyTypeInfoSymbol, TypeId, Mode } from "./type";
import { BFloat16 } from "./bfloat16";


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

interface TypeInfoOptions {
  props?: { [key: string]: TypeInfo };
  withConstructor?: boolean;
  creator?: Function;
  key?: TypeInfo;
  value?: TypeInfo;
  inner?: TypeInfo;
  enumProps?: { [key: string]: number };
}

/**
 * T is for type matching
 */
// eslint-disable-next-line
export class TypeInfo<T = unknown> extends ExtensibleFunction {
  dynamicTypeId = -1;
  named = "";
  namespace = "";
  typeName = "";
  userTypeId = -1;
  options?: TypeInfoOptions;
  _typeId: number;
  nullable: boolean = false;

  setNullable(v: boolean) {
    this.nullable = v;
    return this;
  }
  trackingRef?: boolean;
  setTrackingRef(v: boolean) {
    this.trackingRef = v;
    return this;
  }
  id?: number;
  setId(v: number | undefined) {
    this.id = v;
    return this;
  }
  dynamic?: Dynamic;
  setDynamic(v: Dynamic) {
    this.dynamic = v;
    return this;
  }

  static fory: WeakRef<Fory> | null = null;

  static attach(fory: Fory) {
    TypeInfo.fory = new WeakRef(fory);
  }

  static detach() {
    TypeInfo.fory = null;
  }

  initMeta(target: new () => any) {
    if (!target.prototype) {
      target.prototype = {};
    }
    if (!this.options) {
      this.options = {}
    }
    this.options!.withConstructor = true;
    this.options!.creator = target;
    if (!this.options!.props) {
      this.options!.props = {};
    }
    Object.assign(this.options!.props, targetFields.get(target) || {})
    const that = this;
    Object.defineProperties(target.prototype, {
      [ForyTypeInfoSymbol]: {
        get() {
          return {
            structTypeInfo: that
          };
        },
        enumerable: false,
        set(_) {
          throw new Error("fory type info is readonly")
        },
      },
    });
  }

  public freeze() {
    Object.defineProperties(this, {
      'named': { writable: false, configurable: false },
      'namespace': { writable: false, configurable: false },
      'typeName': { writable: false, configurable: false },
      'userTypeId': { writable: false, configurable: false },
      'options': { writable: false, configurable: false },
      '_typeId': { writable: false, configurable: false },
      'nullable': { writable: false, configurable: false },
    });
    Object.freeze(this.options);
    if (this.options?.props) {
      Object.freeze(this.options!.props);
    }
    if (this.options?.enumProps) {
      Object.freeze(this.options!.enumProps);
    }
  }

  public constructor(typeId: number, userTypeId = -1) {
    super(function (target: any, key?: string | { name?: string }) {
      if (key === undefined) {
        that.initMeta(target);
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
    if (userTypeId !== -1) {
      if (!Number.isInteger(userTypeId) || userTypeId < 0 || userTypeId > 0xFFFFFFFE) {
        throw new Error("userTypeId must be in range [0, 0xfffffffe]");
      }
    }
    this._typeId = typeId;
    this.userTypeId = userTypeId;
  }

  clone() {
    const result = new TypeInfo(this._typeId, this.userTypeId);
    result.named = this.named;
    result.namespace = this.namespace;
    result.typeName = this.typeName;
    result.options = { ...this.options };
    result.dynamicTypeId = this.dynamicTypeId;
    result.nullable = this.nullable;
    result.trackingRef = this.trackingRef;
    result.id = this.id;
    result.dynamic = this.dynamic;
    return result;
  }


  computeTypeId(fory?: Fory) {
    const internalTypeId = this._typeId;
    if (internalTypeId !== TypeId.STRUCT && internalTypeId !== TypeId.NAMED_STRUCT) {
      return this._typeId;
    }
    if (!fory) {
      throw new Error("fory is not attached")
    }
    if (internalTypeId === TypeId.NAMED_STRUCT && fory.isCompatible()) {
      return TypeId.NAMED_COMPATIBLE_STRUCT;
    }
    if (internalTypeId === TypeId.STRUCT && fory.isCompatible()) {
      return TypeId.COMPATIBLE_STRUCT;
    }
    return this._typeId;
  }

  get typeId() {
    return this.computeTypeId(TypeInfo.fory?.deref());
  }

  isMonomorphic(dynamic: Dynamic = Dynamic.AUTO) {
    switch (dynamic) {
      case "TRUE":
        return false;
      case "FALSE":
        return true;
      default:
        if (TypeId.structType(this._typeId)) {
          return false;
        }
        if (TypeId.enumType(this._typeId)) {
          return true;
        }
        const internalTypeId = this._typeId;
        const fory = TypeInfo.fory?.deref();
        if (!fory) {
          throw new Error("fory is not attached")
        }
        if (fory.isCompatible()) {
          return !TypeId.userDefinedType(this._typeId) && internalTypeId != TypeId.UNKNOWN;
        }
        return internalTypeId != TypeId.UNKNOWN;
    }
  }

  isNamedType() {
    return TypeId.isNamedType(this._typeId);
  }

  static fromNonParam<T>(typeId: number) {
    return new TypeInfo<{
      type: T;
    }>(typeId);
  }

  static fromExt<T = any>(nameInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, {
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
    let finalTypeId = 0;
    let userTypeId = -1;
    if (typeId !== undefined) {
      finalTypeId = TypeId.EXT;
      userTypeId = typeId;
    } else {
      finalTypeId = TypeId.NAMED_EXT;
    }
    const typeInfo = new TypeInfo<T>(finalTypeId, userTypeId)
    typeInfo.options = {
      withConstructor,
    };
    typeInfo.namespace = namespace || "";
    typeInfo.typeName = typeId !== undefined ? "" : typeName!;
    typeInfo.named = `${typeInfo.namespace}$${typeInfo.typeName}`;
    return typeInfo as TypeInfo<T>;
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
    let finalTypeId = 0;
    let userTypeId = -1;
    if (typeId !== undefined) {
      finalTypeId = TypeId.STRUCT;
      userTypeId = typeId;
    } else {
      finalTypeId = TypeId.NAMED_STRUCT;
    }
    const typeInfo = new TypeInfo<T>(finalTypeId, userTypeId);
    typeInfo.options = {
      props: props || {},
      withConstructor,
    };
    typeInfo.namespace = namespace || "";
    typeInfo.typeName = typeId !== undefined ? "" : typeName!;
    typeInfo.named = `${typeInfo.namespace}$${typeInfo.typeName}`;
    return typeInfo as TypeInfo<T>;
  }

  static fromWithOptions<T, T2>(typeId: number, options: T2) {
    const typeInfo = new TypeInfo<{
      type: T;
      options: T2;
    }>(typeId);
    typeInfo.options = options as any;
    return typeInfo;
  }

  static fromEnum<T>(nameInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, props?: { [key: string]: any }) {
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
    const finalTypeId = typeId !== undefined ? TypeId.ENUM : TypeId.NAMED_ENUM;
    const userTypeId = typeId !== undefined ? typeId : -1;
    const typeInfo = new TypeInfo<T>(finalTypeId, userTypeId);
    typeInfo.options = {
      enumProps: props,
    };
    typeInfo.namespace = namespace || "";
    typeInfo.typeName = typeId !== undefined ? "" : typeName!;
    typeInfo.named = `${typeInfo.namespace}$${typeInfo.typeName}`;
    return typeInfo;
  }
}

export enum Dynamic {
  TRUE = "TRUE",
  FALSE = "FALSE",
  AUTO = "AUTO"
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


type Value<T> = T extends { [s: string]: infer T2 } ? T2 : unknown;

type EnumProps<T> = T extends {
  options: {
    inner: infer T2;
  };
}
  ? Value<T2>
  : unknown;

type SetProps<T> = T extends {
  options: {
    key: infer T2 extends TypeInfo;
  };
}
  ? Set<(InputType<T2> | null)>
  : unknown;

export type InputType<T> = T extends TypeInfo<infer M> ? HintInput<M> : unknown;


export type HintInput<T> = T extends {
  type: typeof TypeId.STRUCT;
}
  ? Props<T>
  : T extends {
    type: typeof TypeId.STRING;
  }
  ? string
  : T extends { type: typeof TypeId.BFLOAT16 }
  ? BFloat16 | number
  : T extends {
    type:
    | typeof TypeId["INT8"]
    | typeof TypeId.INT16
    | typeof TypeId.INT32
    | typeof TypeId.VARINT32
    | typeof TypeId.UINT8
    | typeof TypeId.UINT16
    | typeof TypeId.UINT32
    | typeof TypeId.UINT64
    | typeof TypeId.VAR_UINT64
    | typeof TypeId.VAR_UINT32
    | typeof TypeId.FLOAT8
    | typeof TypeId.FLOAT16
    | typeof TypeId.FLOAT32
    | typeof TypeId.FLOAT64;
  }
  ? number

  : T extends {
    type: typeof TypeId.VARINT64
    | typeof TypeId.TAGGED_INT64
    | typeof TypeId.INT64
    | typeof TypeId.UINT64
    | typeof TypeId.VAR_UINT64
    | typeof TypeId.TAGGED_UINT64;
  }
  ? bigint
  : T extends {
    type: typeof TypeId.MAP;
  }
  ? MapProps<T>
  : T extends {
    type: typeof TypeId.SET;
  }
  ? SetProps<T>
  : T extends {
    type: typeof TypeId.LIST;
  }
  ? InnerProps<T>
  : T extends {
    type: typeof TypeId.BOOL;
  }
  ? boolean
  : T extends {
    type: typeof TypeId.DURATION;
  }
  ? Date
  : T extends {
    type: typeof TypeId.TIMESTAMP;
  }
  ? number
  : T extends {
    type: typeof TypeId.BINARY;
  }
  ? Uint8Array
  : T extends {
    type: typeof TypeId.ENUM;
  }
  ? EnumProps<T> : any;

export type ResultType<T> = T extends TypeInfo<infer M> ? HintResult<M> : HintResult<T>;

export type HintResult<T> = T extends never ? any : T extends {
  type: typeof TypeId.STRUCT;
}
  ? Props<T>
  : T extends {
    type: typeof TypeId.STRING;
  }
  ? string
  : T extends { type: typeof TypeId.BFLOAT16 }
  ? BFloat16
  : T extends {
    type:
    | typeof TypeId.INT8
    | typeof TypeId.INT16
    | typeof TypeId.INT32
    | typeof TypeId.VARINT32
    | typeof TypeId.UINT8
    | typeof TypeId.UINT16
    | typeof TypeId.UINT32
    | typeof TypeId.VAR_UINT32
    | typeof TypeId.FLOAT8
    | typeof TypeId.FLOAT16
    | typeof TypeId.FLOAT32
    | typeof TypeId.FLOAT64;
  }
  ? number

  : T extends {
    type: typeof TypeId.TAGGED_INT64
    | typeof TypeId.INT64
    | typeof TypeId.UINT64
    | typeof TypeId.VAR_UINT64
    | typeof TypeId.TAGGED_UINT64;
  }
  ? bigint
  : T extends {
    type: typeof TypeId.MAP;
  }
  ? MapProps<T>
  : T extends {
    type: typeof TypeId.SET;
  }
  ? SetProps<T>
  : T extends {
    type: typeof TypeId.LIST;
  }
  ? InnerProps<T>
  : T extends {
    type: typeof TypeId.BOOL;
  }
  ? boolean
  : T extends {
    type: typeof TypeId.DURATION;
  }
  ? number
  : T extends {
    type: typeof TypeId.DATE;
  }
  ? (Date | number)
  : T extends {
    type: typeof TypeId.TIMESTAMP;
  }
  ? number
  : T extends {
    type: typeof TypeId.BINARY;
  }
  ? Uint8Array : T extends {
    type: typeof TypeId.ENUM;
  }
  ? EnumProps<T> : unknown;

export const Type = {
  any() {
    return TypeInfo.fromNonParam<typeof TypeId.UNKNOWN>(TypeId.UNKNOWN);
  },
  array<T extends TypeInfo>(inner: T) {
    return TypeInfo.fromWithOptions<typeof TypeId.LIST, { inner: T }>(TypeId.LIST, {
      inner,
    });
  },
  map<T1 extends TypeInfo, T2 extends TypeInfo>(
    key: T1,
    value: T2
  ) {
    return TypeInfo.fromWithOptions<typeof TypeId.MAP, {
      key: T1,
      value: T2
    }>(TypeId.MAP, {
      key,
      value,
    });
  },
  set<T extends TypeInfo>(key: T) {
    return TypeInfo.fromWithOptions<typeof TypeId.SET, {
      key: T
    }>(TypeId.SET, {
      key,
    });
  },
  enum<T1 extends { [key: string]: any }>(nameInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, t1?: T1) {
    return TypeInfo.fromEnum<{
      type: typeof TypeId.ENUM;
      options: {
        enumProps: T1;
      };
    }>(nameInfo, t1);
  },
  ext<T extends { [key: string]: TypeInfo }>(nameInfo: {
    typeId?: number;
    namespace?: string;
    typeName?: string;
  } | string | number, {
    withConstructor = false,
  }: {
    withConstructor?: boolean;
  } = {}) {
    return TypeInfo.fromExt<{
      type: typeof TypeId.EXT;
      options: {
        props: T;
      };
    }>(nameInfo, {
      withConstructor,
    });
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
      type: typeof TypeId.STRUCT;
      options: {
        props: T;
      };
    }>(nameInfo, props, {
      withConstructor,
    });
  },
  string() {
    return TypeInfo.fromNonParam<typeof TypeId.STRING>(
      (TypeId.STRING),
    );
  },
  bool() {
    return TypeInfo.fromNonParam<typeof TypeId.BOOL>(
      (TypeId.BOOL),
    );
  },
  int8() {
    return TypeInfo.fromNonParam<typeof TypeId.INT8>(
      (TypeId.INT8),
    );
  },
  int16() {
    return TypeInfo.fromNonParam<typeof TypeId.INT16>(
      (TypeId.INT16),

    );
  },
  int32() {
    return TypeInfo.fromNonParam<typeof TypeId.INT32>(
      (TypeId.INT32),

    );
  },
  varInt32() {
    return TypeInfo.fromNonParam<typeof TypeId.VARINT32>(
      (TypeId.VARINT32),

    );
  },
  int64() {
    return TypeInfo.fromNonParam<typeof TypeId.INT64>(
      (TypeId.INT64),

    );
  },
  sliInt64() {
    return TypeInfo.fromNonParam<typeof TypeId.TAGGED_INT64>(
      (TypeId.TAGGED_INT64),

    );
  },
  float16() {
    return TypeInfo.fromNonParam<typeof TypeId.FLOAT16>(
      (TypeId.FLOAT16),

    );
  },
  bfloat16() {
    return TypeInfo.fromNonParam<typeof TypeId.BFLOAT16>(
      (TypeId.BFLOAT16),

    );
  },
  float32() {
    return TypeInfo.fromNonParam<typeof TypeId.FLOAT32>(
      (TypeId.FLOAT32),

    );
  },
  float64() {
    return TypeInfo.fromNonParam<typeof TypeId.FLOAT64>(
      (TypeId.FLOAT64),

    );
  },
  uint8() {
    return TypeInfo.fromNonParam<typeof TypeId.UINT8>(
      (TypeId.UINT8),
    );
  },
  uint16() {
    return TypeInfo.fromNonParam<typeof TypeId.UINT16>(
      (TypeId.UINT16),
    );
  },
  uint32() {
    return TypeInfo.fromNonParam<typeof TypeId.UINT32>(
      (TypeId.UINT32),
    );
  },
  varUInt32() {
    return TypeInfo.fromNonParam<typeof TypeId.VAR_UINT32>(
      (TypeId.VAR_UINT32),
    );
  },
  uint64() {
    return TypeInfo.fromNonParam<typeof TypeId.UINT64>(
      (TypeId.UINT64),
    );
  },
  varUInt64() {
    return TypeInfo.fromNonParam<typeof TypeId.VAR_UINT64>(
      (TypeId.VAR_UINT64),
    );
  },
  varInt64() {
    return TypeInfo.fromNonParam<typeof TypeId.VARINT64>(
      (TypeId.VARINT64),
    );
  },
  taggedUInt64() {
    return TypeInfo.fromNonParam<typeof TypeId.TAGGED_UINT64>(
      (TypeId.TAGGED_UINT64),
    );
  },
  binary() {
    return TypeInfo.fromNonParam<typeof TypeId.BINARY>(
      (TypeId.BINARY),

    );
  },
  duration() {
    return TypeInfo.fromNonParam<typeof TypeId.DURATION>(
      (TypeId.DURATION),
    );
  },
  date() {
    return TypeInfo.fromNonParam<typeof TypeId.DATE>(
      (TypeId.DATE),
    );
  },
  timestamp() {
    return TypeInfo.fromNonParam<typeof TypeId.TIMESTAMP>(
      (TypeId.TIMESTAMP),
    );
  },
  boolArray() {
    return TypeInfo.fromNonParam<typeof TypeId.BOOL_ARRAY>(
      (TypeId.BOOL_ARRAY),

    );
  },
  int8Array() {
    return TypeInfo.fromNonParam<typeof TypeId.INT8_ARRAY>(
      (TypeId.INT8_ARRAY),

    );
  },
  int16Array() {
    return TypeInfo.fromNonParam<typeof TypeId.INT16_ARRAY>(
      (TypeId.INT16_ARRAY),

    );
  },
  int32Array() {
    return TypeInfo.fromNonParam<typeof TypeId.INT32_ARRAY>(
      (TypeId.INT32_ARRAY),

    );
  },
  int64Array() {
    return TypeInfo.fromNonParam<typeof TypeId.INT64_ARRAY>(
      (TypeId.INT64_ARRAY),

    );
  },
  uint8Array() {
    return TypeInfo.fromNonParam<typeof TypeId.UINT8_ARRAY>(
      (TypeId.UINT8_ARRAY),

    );
  },
  uint16Array() {
    return TypeInfo.fromNonParam<typeof TypeId.UINT16_ARRAY>(
      (TypeId.UINT16_ARRAY),

    );
  },
  uint32Array() {
    return TypeInfo.fromNonParam<typeof TypeId.UINT32_ARRAY>(
      (TypeId.UINT32_ARRAY),

    );
  },
  uint64Array() {
    return TypeInfo.fromNonParam<typeof TypeId.UINT64_ARRAY>(
      (TypeId.UINT64_ARRAY),

    );
  },
  float16Array() {
    return TypeInfo.fromNonParam<typeof TypeId.FLOAT16_ARRAY>(
      (TypeId.FLOAT16_ARRAY),

    );
  },
  bfloat16Array() {
    return TypeInfo.fromNonParam<typeof TypeId.BFLOAT16_ARRAY>(
      (TypeId.BFLOAT16_ARRAY),

    );
  },
  float32Array() {
    return TypeInfo.fromNonParam<typeof TypeId.FLOAT32_ARRAY>(
      (TypeId.FLOAT32_ARRAY),

    );
  },
  float64Array() {
    return TypeInfo.fromNonParam<typeof TypeId.FLOAT64_ARRAY>(
      (TypeId.FLOAT64_ARRAY)
    );
  },
};
