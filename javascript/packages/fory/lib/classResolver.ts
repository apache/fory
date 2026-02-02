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

import { ForyTypeInfoSymbol, WithForyClsInfo, Serializer, TypeId } from "./type";
import { Gen } from "./gen";
import { Type, TypeInfo } from "./typeInfo";
import Fory from "./fory";

const uninitSerialize = {
  // for writer
  fixedSize: 0,
  getTypeId: () => {
    throw new Error("uninitSerialize");
  },
  needToWriteRef: () => {
    throw new Error("uninitSerialize");
  },
  getHash: () => {
    throw new Error("uninitSerialize");
  },
  write: (v: any) => {
    throw new Error("uninitSerialize");
  },
  writeRef: (v: any) => {
    throw new Error("uninitSerialize");
  },
  writeNoRef: (v: any) => {
    throw new Error("uninitSerialize");
  },
  writeRefOrNull: (v: any) => {
    throw new Error("uninitSerialize");
  },
  writeClassInfo: (v: any) => {
    throw new Error("uninitSerialize");
  },
  read: (fromRef: boolean) => {
    throw new Error("uninitSerialize");
  },
  readRef: () => {
    throw new Error("uninitSerialize");
  },
  readNoRef: (fromRef: boolean) => {
    throw new Error("uninitSerialize");
  },
  readClassInfo: () => {
    throw new Error("uninitSerialize");
  },
};

export default class ClassResolver {
  private internalSerializer: Serializer[] = new Array(300);
  private customSerializer: Map<number | string, Serializer> = new Map();
  private typeInfoMap: Map<number | string, TypeInfo> = new Map();

  private initInternalSerializer() {
    const registerSerializer = (typeInfo: TypeInfo) => {
      return this.registerSerializer(typeInfo, new Gen(this.fory).generateSerializer(typeInfo));
    };
    registerSerializer(Type.string());
    registerSerializer(Type.any());
    registerSerializer(Type.array(Type.any()));
    registerSerializer(Type.map(Type.any(), Type.any()));
    registerSerializer(Type.bool());
    registerSerializer(Type.int8());
    registerSerializer(Type.int16());
    registerSerializer(Type.int32());
    registerSerializer(Type.varInt32());
    registerSerializer(Type.int64());
    registerSerializer(Type.sliInt64());
    registerSerializer(Type.float16());
    registerSerializer(Type.float32());
    registerSerializer(Type.float64());
    registerSerializer(Type.timestamp());
    registerSerializer(Type.duration());
    registerSerializer(Type.set(Type.any()));
    registerSerializer(Type.binary());
    registerSerializer(Type.boolArray());
    registerSerializer(Type.int8Array());
    registerSerializer(Type.int16Array());
    registerSerializer(Type.int32Array());
    registerSerializer(Type.int64Array());
    registerSerializer(Type.float16Array());
    registerSerializer(Type.float32Array());
    registerSerializer(Type.float64Array());

    this.numberSerializer = this.getSerializerById(TypeId.FLOAT64);
    this.int64Serializer = this.getSerializerById((TypeId.INT64));
    this.boolSerializer = this.getSerializerById((TypeId.BOOL));
    this.dateSerializer = this.getSerializerById((TypeId.TIMESTAMP));
    this.stringSerializer = this.getSerializerById((TypeId.STRING));
    this.setSerializer = this.getSerializerById((TypeId.SET));
    this.arraySerializer = this.getSerializerById((TypeId.LIST));
    this.mapSerializer = this.getSerializerById((TypeId.MAP));
    this.uint8ArraySerializer = this.getSerializerById(TypeId.UINT8_ARRAY);
    this.uint16ArraySerializer = this.getSerializerById(TypeId.UINT16_ARRAY);
    this.uint32ArraySerializer = this.getSerializerById(TypeId.UINT32_ARRAY);
    this.uint64ArraySerializer = this.getSerializerById(TypeId.UINT64_ARRAY);
    this.int8ArraySerializer = this.getSerializerById(TypeId.INT8_ARRAY);
    this.int16ArraySerializer = this.getSerializerById(TypeId.INT16_ARRAY);
    this.int32ArraySerializer = this.getSerializerById(TypeId.INT32_ARRAY);
    this.int64ArraySerializer = this.getSerializerById(TypeId.INT64_ARRAY);
  }

  private numberSerializer: null | Serializer = null;
  private int64Serializer: null | Serializer = null;
  private boolSerializer: null | Serializer = null;
  private dateSerializer: null | Serializer = null;
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

  constructor(private fory: Fory) {
  }

  init() {
    this.initInternalSerializer();
  }

  getTypeInfo(typeIdOrName: number | string) {
    return this.typeInfoMap.get(typeIdOrName);
  }

  registerSerializer(typeInfo: TypeInfo, serializer: Serializer = uninitSerialize) {
    if (!TypeId.isNamedType(typeInfo.typeId)) {
      const id = typeInfo.typeId;
      this.typeInfoMap.set(id, typeInfo);
      if (id <= 0xFF) {
        if (this.internalSerializer[id]) {
          Object.assign(this.internalSerializer[id], serializer);
        } else {
          this.internalSerializer[id] = { ...serializer };
        }
        return this.internalSerializer[id];
      } else {
        if (this.customSerializer.has(id)) {
          Object.assign(this.customSerializer.get(id)!, serializer);
        } else {
          this.customSerializer.set(id, { ...serializer });
        }
        return this.customSerializer.get(id);
      }
    } else {
      const namedTypeInfo = typeInfo.castToStruct();
      const name = namedTypeInfo.named!;
      if (this.customSerializer.has(name)) {
        Object.assign(this.customSerializer.get(name)!, serializer);
      } else {
        this.customSerializer.set(name, { ...serializer });
      }
      this.typeInfoMap.set(name, typeInfo);
      return this.customSerializer.get(name);
    }
  }

  typeInfoExists(typeInfo: TypeInfo) {
    if (typeInfo.isNamedType()) {
      return this.typeInfoMap.has((typeInfo.castToStruct()).named!);
    }
    return this.typeInfoMap.has(typeInfo.typeId);
  }

  getSerializerByTypeInfo(typeInfo: TypeInfo) {
    const typeId = typeInfo.computeTypeId(this.fory);
    if (TypeId.isNamedType(typeId)) {
      return this.customSerializer.get((typeInfo.castToStruct()).named!);
    }
    return this.getSerializerById(typeId);
  }

  getSerializerById(id: number) {
    if (id <= 0xff) {
      return this.internalSerializer[id]!;
    } else {
      return this.customSerializer.get(id)!;
    }
  }

  getSerializerByName(typeIdOrName: number | string) {
    return this.customSerializer.get(typeIdOrName);
  }

  getSerializerByData(v: any) {
    // internal types
    if (typeof v === "number") {
      return this.numberSerializer;
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
      return this.dateSerializer;
    }

    if (v instanceof Map) {
      return this.mapSerializer;
    }

    if (v instanceof Set) {
      return this.setSerializer;
    }

    // custome types
    if (typeof v === "object" && v !== null && ForyTypeInfoSymbol in v) {
      const typeInfo = (v[ForyTypeInfoSymbol] as WithForyClsInfo).structTypeInfo;
      return this.getSerializerByTypeInfo(typeInfo);
    }

    throw new Error(`Failed to detect the Fory type from JavaScript type: ${typeof v}`);
  }
}
