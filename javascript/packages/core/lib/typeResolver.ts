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

import { ForyTypeInfoSymbol, WithForyClsInfo, Serializer, TypeId, MaxInt32, MinInt32, Config } from "./type";
import { Gen } from "./gen";
import { Dynamic, Type, TypeInfo } from "./typeInfo";
import { ReadContext, WriteContext } from "./context";

const uninitSerialize = {
  fixedSize: 0,
  getTypeInfo: () => {
    throw new Error("uninitSerialize");
  },
  getTypeId: () => {
    throw new Error("uninitSerialize");
  },
  getUserTypeId: () => {
    throw new Error("uninitSerialize");
  },
  needToWriteRef: () => {
    throw new Error("uninitSerialize");
  },
  getHash: () => {
    throw new Error("uninitSerialize");
  },
  write: (v: any) => {
    void v;
    throw new Error("uninitSerialize");
  },
  writeRef: (v: any) => {
    void v;
    throw new Error("uninitSerialize");
  },
  writeNoRef: (v: any) => {
    void v;
    throw new Error("uninitSerialize");
  },
  writeRefOrNull: (v: any) => {
    void v;
    throw new Error("uninitSerialize");
  },
  writeTypeInfo: (v: any) => {
    void v;
    throw new Error("uninitSerialize");
  },
  read: (fromRef: boolean) => {
    void fromRef;
    throw new Error("uninitSerialize");
  },
  readRef: () => {
    throw new Error("uninitSerialize");
  },
  readRefWithoutTypeInfo: () => {
    throw new Error("uninitSerialize");
  },
  readNoRef: (fromRef: boolean) => {
    void fromRef;
    throw new Error("uninitSerialize");
  },
  readTypeInfo: () => {
    throw new Error("uninitSerialize");
  },
};

export default class TypeResolver {
  private internalSerializer: Serializer[] = new Array(300);
  private customSerializer: Map<number | string, Serializer> = new Map();

  private writeContext!: WriteContext;
  private readContext!: ReadContext;

  private float64Serializer: null | Serializer = null;
  private float32Serializer: null | Serializer = null;
  private varint32Serializer: null | Serializer = null;
  private varInt64Serializer: null | Serializer = null;
  private int64Serializer: null | Serializer = null;
  private boolSerializer: null | Serializer = null;
  private datetimeSerializer: null | Serializer = null;
  private stringSerializer: null | Serializer = null;
  private setSerializer: null | Serializer = null;
  private arraySerializer: null | Serializer = null;
  private mapSerializer: null | Serializer = null;
  private uint8ArraySerializer: null | Serializer = null;
  private uint16ArraySerializer: null | Serializer = null;
  private uint32ArraySerializer: null | Serializer = null;
  private uint64ArraySerializer: null | Serializer = null;
  private int8ArraySerializer: null | Serializer = null;
  private int16ArraySerializer: null | Serializer = null;
  private int32ArraySerializer: null | Serializer = null;
  private int64ArraySerializer: null | Serializer = null;
  private float32ArraySerializer: null | Serializer = null;
  private float64ArraySerializer: null | Serializer = null;

  constructor(readonly config: Config) {
  }

  bindContexts(writeContext: WriteContext, readContext: ReadContext) {
    this.writeContext = writeContext;
    this.readContext = readContext;
  }

  isCompatible() {
    return this.config.compatible === true;
  }

  computeTypeId(typeInfo: TypeInfo) {
    const internalTypeId = typeInfo.typeId;
    if (internalTypeId !== TypeId.STRUCT && internalTypeId !== TypeId.NAMED_STRUCT) {
      return internalTypeId;
    }
    if (internalTypeId === TypeId.NAMED_STRUCT && this.isCompatible() && typeInfo.evolving) {
      return TypeId.NAMED_COMPATIBLE_STRUCT;
    }
    if (internalTypeId === TypeId.STRUCT && this.isCompatible() && typeInfo.evolving) {
      return TypeId.COMPATIBLE_STRUCT;
    }
    return internalTypeId;
  }

  isMonomorphic(typeInfo: TypeInfo, dynamic: Dynamic = Dynamic.AUTO) {
    switch (dynamic) {
      case Dynamic.TRUE:
        return false;
      case Dynamic.FALSE:
        return true;
      default:
        if (TypeId.structType(typeInfo.typeId)) {
          return false;
        }
        if (TypeId.enumType(typeInfo.typeId)) {
          return true;
        }
        if (this.isCompatible()) {
          return !TypeId.userDefinedType(typeInfo.typeId) && typeInfo.typeId !== TypeId.UNKNOWN;
        }
        return typeInfo.typeId !== TypeId.UNKNOWN;
    }
  }

  private makeUserTypeKey(userTypeId: number) {
    return `u:${userTypeId}`;
  }

  private initInternalSerializer() {
    const registerSerializer = (typeInfo: TypeInfo) => {
      return this.registerSerializer(typeInfo, new Gen(this).generateSerializer(typeInfo));
    };
    registerSerializer(Type.string());
    registerSerializer(new TypeInfo(TypeId.ENUM));
    registerSerializer(new TypeInfo(TypeId.NAMED_ENUM));
    registerSerializer(Type.any());
    registerSerializer(Type.array(Type.any()));
    registerSerializer(Type.map(Type.any(), Type.any()));
    registerSerializer(Type.bool());
    registerSerializer(Type.int8());
    registerSerializer(Type.int16());
    registerSerializer(Type.int32());
    registerSerializer(Type.varInt32());
    registerSerializer(Type.varUInt64());
    registerSerializer(Type.varInt64());
    registerSerializer(Type.int64());
    registerSerializer(Type.uint8());
    registerSerializer(Type.uint16());
    registerSerializer(Type.uint32());
    registerSerializer(Type.uint64());
    registerSerializer(Type.varInt32());
    registerSerializer(Type.varUInt32());
    registerSerializer(Type.varUInt64());
    registerSerializer(Type.varInt64());
    registerSerializer(Type.taggedUInt64());
    registerSerializer(Type.sliInt64());
    registerSerializer(Type.float16());
    registerSerializer(Type.bfloat16());
    registerSerializer(Type.float32());
    registerSerializer(Type.float64());
    registerSerializer(Type.timestamp());
    registerSerializer(Type.duration());
    registerSerializer(Type.date());
    registerSerializer(Type.set(Type.any()));
    registerSerializer(Type.binary());
    registerSerializer(Type.boolArray());
    registerSerializer(Type.uint8Array());
    registerSerializer(Type.int8Array());
    registerSerializer(Type.uint16Array());
    registerSerializer(Type.int16Array());
    registerSerializer(Type.uint32Array());
    registerSerializer(Type.int32Array());
    registerSerializer(Type.uint64Array());
    registerSerializer(Type.int64Array());
    registerSerializer(Type.float16Array());
    registerSerializer(Type.bfloat16Array());
    registerSerializer(Type.float32Array());
    registerSerializer(Type.float64Array());

    this.float64Serializer = this.getSerializerById(TypeId.FLOAT64);
    this.float32Serializer = this.getSerializerById(TypeId.FLOAT32);
    this.varint32Serializer = this.getSerializerById(TypeId.VARINT32);
    this.varInt64Serializer = this.getSerializerById(TypeId.VARINT64);
    this.int64Serializer = this.getSerializerById(TypeId.INT64);
    this.boolSerializer = this.getSerializerById(TypeId.BOOL);
    this.datetimeSerializer = this.getSerializerById(TypeId.TIMESTAMP);
    this.stringSerializer = this.getSerializerById(TypeId.STRING);
    this.setSerializer = this.getSerializerById(TypeId.SET);
    this.arraySerializer = this.getSerializerById(TypeId.LIST);
    this.mapSerializer = this.getSerializerById(TypeId.MAP);
    this.uint8ArraySerializer = this.getSerializerById(TypeId.UINT8_ARRAY);
    this.uint16ArraySerializer = this.getSerializerById(TypeId.UINT16_ARRAY);
    this.uint32ArraySerializer = this.getSerializerById(TypeId.UINT32_ARRAY);
    this.uint64ArraySerializer = this.getSerializerById(TypeId.UINT64_ARRAY);
    this.int8ArraySerializer = this.getSerializerById(TypeId.INT8_ARRAY);
    this.int16ArraySerializer = this.getSerializerById(TypeId.INT16_ARRAY);
    this.int32ArraySerializer = this.getSerializerById(TypeId.INT32_ARRAY);
    this.int64ArraySerializer = this.getSerializerById(TypeId.INT64_ARRAY);
    this.float32ArraySerializer = this.getSerializerById(TypeId.FLOAT32_ARRAY);
    this.float64ArraySerializer = this.getSerializerById(TypeId.FLOAT64_ARRAY);
  }

  init() {
    this.initInternalSerializer();
  }

  registerSerializer(typeInfo: TypeInfo, serializer: Serializer = uninitSerialize) {
    const typeId = this.computeTypeId(typeInfo);
    if (!TypeId.isNamedType(typeId)) {
      if (TypeId.needsUserTypeId(typeId) && typeInfo.userTypeId !== -1) {
        const key = this.makeUserTypeKey(typeInfo.userTypeId);
        if (this.customSerializer.has(key)) {
          Object.assign(this.customSerializer.get(key)!, serializer);
        } else {
          this.customSerializer.set(key, { ...serializer });
        }
        return this.customSerializer.get(key);
      }
      if (typeId <= 0xFF) {
        if (this.internalSerializer[typeId]) {
          Object.assign(this.internalSerializer[typeId], serializer);
        } else {
          this.internalSerializer[typeId] = { ...serializer };
        }
        return this.internalSerializer[typeId];
      }
      if (this.customSerializer.has(typeId)) {
        Object.assign(this.customSerializer.get(typeId)!, serializer);
      } else {
        this.customSerializer.set(typeId, { ...serializer });
      }
      return this.customSerializer.get(typeId);
    }

    const name = typeInfo.named!;
    if (this.customSerializer.has(name)) {
      Object.assign(this.customSerializer.get(name)!, serializer);
    } else {
      this.customSerializer.set(name, { ...serializer });
    }
    return this.customSerializer.get(name);
  }

  regenerateReadSerializer(typeInfo: TypeInfo) {
    const serializer = new Gen(this, { creator: typeInfo.options?.creator }).reGenerateSerializer(typeInfo);
    return this.registerSerializer(typeInfo, {
      getHash: serializer.getHash,
      read: serializer.read,
      readNoRef: serializer.readNoRef,
      readRef: serializer.readRef,
      readTypeInfo: serializer.readTypeInfo,
      readRefWithoutTypeInfo: serializer.readRefWithoutTypeInfo,
    } as any)!;
  }

  getSerializerByTypeInfo(typeInfo: TypeInfo) {
    const typeId = this.computeTypeId(typeInfo);
    if (TypeId.isNamedType(typeId)) {
      return this.customSerializer.get(typeInfo.named!);
    }
    return this.getSerializerById(typeId, typeInfo.userTypeId);
  }

  getSerializerById(id: number, userTypeId?: number) {
    if (TypeId.needsUserTypeId(id) && userTypeId !== undefined && userTypeId !== -1) {
      return this.customSerializer.get(this.makeUserTypeKey(userTypeId))!;
    }
    if (id <= 0xff) {
      return this.internalSerializer[id]!;
    }
    return this.customSerializer.get(id)!;
  }

  getSerializerByName(typeIdOrName: number | string) {
    return this.customSerializer.get(typeIdOrName);
  }

  getSerializerByData(v: any) {
    if (v === null || v === undefined) {
      return null;
    }
    if (typeof v === "number") {
      if (Number.isInteger(v)) {
        if (v > MaxInt32 || v < MinInt32) {
          return this.varInt64Serializer;
        }
        return this.varint32Serializer;
      }
      if (v > MaxInt32 || v < MinInt32) {
        return this.float64Serializer;
      }
      return this.float32Serializer;
    }

    if (typeof v === "bigint") {
      return this.varInt64Serializer;
    }

    if (typeof v === "string") {
      return this.stringSerializer;
    }

    if (v instanceof Uint8Array) {
      return this.uint8ArraySerializer;
    }

    if (v instanceof Uint16Array) {
      return this.uint16ArraySerializer;
    }

    if (v instanceof Uint32Array) {
      return this.uint32ArraySerializer;
    }

    if (v instanceof BigUint64Array) {
      return this.uint64ArraySerializer;
    }

    if (v instanceof Int8Array) {
      return this.int8ArraySerializer;
    }

    if (v instanceof Int16Array) {
      return this.int16ArraySerializer;
    }

    if (v instanceof Int32Array) {
      return this.int32ArraySerializer;
    }

    if (v instanceof BigInt64Array) {
      return this.int64ArraySerializer;
    }

    if (v instanceof Float32Array) {
      return this.float32ArraySerializer;
    }

    if (v instanceof Float64Array) {
      return this.float64ArraySerializer;
    }

    if (Array.isArray(v)) {
      return this.arraySerializer;
    }

    if (typeof v === "boolean") {
      return this.boolSerializer;
    }

    if (typeof v === "bigint") {
      return this.int64Serializer;
    }

    if (v instanceof Date) {
      return this.datetimeSerializer;
    }

    if (v instanceof Map) {
      return this.mapSerializer;
    }

    if (v instanceof Set) {
      return this.setSerializer;
    }

    if (typeof v === "object" && v !== null && ForyTypeInfoSymbol in v) {
      const typeInfo = (v[ForyTypeInfoSymbol] as WithForyClsInfo).structTypeInfo;
      return this.getSerializerByTypeInfo(typeInfo);
    }

    throw new Error(`Failed to detect the Fory type from JavaScript type: ${typeof v}`);
  }
}
