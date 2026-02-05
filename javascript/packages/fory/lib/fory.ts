/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import TypeResolver from "./typeResolver";
import { BinaryWriter } from "./writer";
import { BinaryReader } from "./reader";
import { ReferenceResolver } from "./referenceResolver";
import {
  ConfigFlags,
  Serializer,
  Config,
  Mode,
  ForyTypeInfoSymbol,
  WithForyClsInfo,
  TypeId,
} from "./type";
import { OwnershipError } from "./error";
import { InputType, ResultType, StructTypeInfo, TypeInfo } from "./typeInfo";
import { Gen } from "./gen";
import { TypeMeta } from "./meta/TypeMeta";
import { PlatformBuffer } from "./platformBuffer";
import { TypeMetaResolver } from "./typeMetaResolver";
import { MetaStringResolver } from "./metaStringResolver";

export default class {
  binaryReader: BinaryReader;
  binaryWriter: BinaryWriter;
  typeResolver: TypeResolver;
  typeMetaResolver: TypeMetaResolver;
  metaStringResolver: MetaStringResolver;
  referenceResolver: ReferenceResolver;
  anySerializer: Serializer;
  typeMeta = TypeMeta;
  config: Config;

  constructor(config?: Partial<Config>) {
    this.config = this.initConfig(config);
    this.binaryReader = new BinaryReader(this.config);
    this.binaryWriter = new BinaryWriter(this.config);
    this.referenceResolver = new ReferenceResolver(this.binaryReader);
    this.typeMetaResolver = new TypeMetaResolver(this);
    this.typeResolver = new TypeResolver(this);
    this.metaStringResolver = new MetaStringResolver(this);
    this.typeResolver.init();
    this.anySerializer = this.typeResolver.getSerializerById(TypeId.UNKNOWN);
  }

  private initConfig(config: Partial<Config> | undefined) {
    return {
      // Fix: Ensure refTracking is null if undefined, not coerced to false
      refTracking:
        config?.refTracking !== undefined && config?.refTracking !== null
          ? Boolean(config.refTracking)
          : null,
      useSliceString: Boolean(config?.useSliceString),
      hooks: config?.hooks || {},
      mode: config?.mode || Mode.SchemaConsistent,
    };
  }

  isCompatible() {
    return this.config.mode === Mode.Compatible;
  }

  // Overload: class with decorators
  registerSerializer<T extends new () => any>(
    constructor: T,
  ): {
    serializer: Serializer;
    serialize(data: Partial<InstanceType<T>> | null): PlatformBuffer;
    serializeVolatile(data: Partial<InstanceType<T>>): {
      get: () => Uint8Array;
      dispose: () => void;
    };
    deserialize(bytes: Uint8Array): InstanceType<T> | null;
  };

  // Overload: explicit TypeInfo
  registerSerializer<T extends TypeInfo>(
    typeInfo: T,
  ): {
    serializer: Serializer;
    serialize(data: InputType<T> | null): PlatformBuffer;
    serializeVolatile(data: InputType<T>): {
      get: () => Uint8Array;
      dispose: () => void;
    };
    deserialize(bytes: Uint8Array): ResultType<T>;
  };

  registerSerializer(constructorOrType: any) {
    let serializer: Serializer;

    TypeInfo.attach(this);
    try {
      if (constructorOrType.prototype?.[ForyTypeInfoSymbol]) {
        const typeInfo: TypeInfo = (
          constructorOrType.prototype[ForyTypeInfoSymbol] as WithForyClsInfo
        ).structTypeInfo;

        serializer = new Gen(this, {
          constructor: constructorOrType,
        }).generateSerializer(typeInfo);

        this.typeResolver.registerSerializer(typeInfo, serializer);
      } else {
        const typeInfo: TypeInfo = constructorOrType;
        serializer = this.typeResolver.getSerializerByTypeInfo(typeInfo);
        this.typeResolver.registerSerializer(typeInfo, serializer);
      }
    } finally {
      TypeInfo.detach();
    }

    return {
      serializer,
      serialize: (data: any) => {
        return this.serialize(data, serializer);
      },
      serializeVolatile: (data: any) => {
        return this.serializeVolatile(data, serializer);
      },
      deserialize: (bytes: Uint8Array) => {
        if (TypeId.polymorphicType(serializer.getTypeId())) {
          return this.deserialize(bytes, serializer);
        }
        return this.deserialize(bytes);
      },
    };
  }

  replaceSerializerReader(typeInfo: TypeInfo) {
    TypeInfo.attach(this);
    try {
      const serializer = new Gen(this, {
        constructor: (typeInfo as StructTypeInfo).options.constructor,
      }).reGenerateSerializer(typeInfo);

      const result = this.typeResolver.registerSerializer(typeInfo, {
        getHash: serializer.getHash,
        read: serializer.read,
        readNoRef: serializer.readNoRef,
        readRef: serializer.readRef,
        readTypeInfo: serializer.readTypeInfo,
      } as any)!;

      return result;
    } finally {
      TypeInfo.detach();
    }
  }

  deserialize<T = any>(
    bytes: Uint8Array,
    serializer: Serializer = this.anySerializer,
  ): T | null {
    this.referenceResolver.reset();
    this.binaryReader.reset(bytes);
    this.typeMetaResolver.reset();
    this.metaStringResolver.reset();

    const bitmap = this.binaryReader.uint8();
    if ((bitmap & ConfigFlags.isNullFlag) === ConfigFlags.isNullFlag) {
      return null;
    }

    const isCrossLanguage =
      (bitmap & ConfigFlags.isCrossLanguageFlag) ==
      ConfigFlags.isCrossLanguageFlag;
    if (!isCrossLanguage) {
      throw new Error("support crosslanguage mode only");
    }

    const isOutOfBandEnabled =
      (bitmap & ConfigFlags.isOutOfBandFlag) === ConfigFlags.isOutOfBandFlag;
    if (isOutOfBandEnabled) {
      throw new Error("outofband mode is not supported now");
    }

    return serializer.readRef();
  }

  private serializeInternal<T = any>(data: T, serializer: Serializer) {
    try {
      this.binaryWriter.reset();
    } catch (e) {
      if (e instanceof OwnershipError) {
        throw new Error(
          "Permission denied. To release the serialization ownership, you must call the dispose function returned by serializeVolatile.",
        );
      }
      throw e;
    }

    this.referenceResolver.reset();

    let bitmap = 0;
    const isNull = data === null;
    if (isNull) {
      bitmap |= ConfigFlags.isNullFlag;
    }
    bitmap |= ConfigFlags.isCrossLanguageFlag;

    this.binaryWriter.uint8(bitmap);

    // Fix: Short-circuit on null to prevent writeRef errors
    if (isNull) {
      return this.binaryWriter;
    }

    // reserve fixed size and safeguard against undefined/null fixedSize
    this.binaryWriter.reserve(serializer.fixedSize || 0);
    // start write
    serializer.writeRef(data);
    return this.binaryWriter;
  }

  serialize<T = any>(data: T, serializer: Serializer = this.anySerializer) {
    return this.serializeInternal(data, serializer).dump();
  }

  serializeVolatile<T = any>(
    data: T,
    serializer: Serializer = this.anySerializer,
  ) {
    return this.serializeInternal(data, serializer).dumpAndOwn();
  }
}
