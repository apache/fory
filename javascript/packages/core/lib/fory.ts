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

import TypeResolver from "./typeResolver";
import { ConfigFlags, Serializer, Config, ForyTypeInfoSymbol, WithForyClsInfo, TypeId, CustomSerializer } from "./type";
import { InputType, ResultType, TypeInfo } from "./typeInfo";
import { Gen } from "./gen";
import { PlatformBuffer } from "./platformBuffer";
import { ReadContext, WriteContext } from "./context";

const DEFAULT_DEPTH_LIMIT = 50 as const;
const MIN_DEPTH_LIMIT = 2 as const;
const DEFAULT_MAX_COLLECTION_SIZE = 1_000_000 as const;
const DEFAULT_MAX_BINARY_SIZE = 64 * 1024 * 1024; // 64 MiB

export default class Fory {
  readonly typeResolver: TypeResolver;
  readonly anySerializer: Serializer;
  readonly config: Config;
  readonly writeContext: WriteContext;
  readonly readContext: ReadContext;

  constructor(config?: Partial<Config>) {
    this.config = this.initConfig(config);
    const maxDepth = this.config.maxDepth ?? DEFAULT_DEPTH_LIMIT;
    if (!Number.isInteger(maxDepth) || maxDepth < MIN_DEPTH_LIMIT) {
      throw new Error(`maxDepth must be an integer >= ${MIN_DEPTH_LIMIT} but got ${maxDepth}`);
    }
    const maxBinarySize = this.config.maxBinarySize ?? DEFAULT_MAX_BINARY_SIZE;
    if (!Number.isInteger(maxBinarySize) || maxBinarySize < 0) {
      throw new Error(`maxBinarySize must be a non-negative integer but got ${maxBinarySize}`);
    }
    const maxCollectionSize = this.config.maxCollectionSize ?? DEFAULT_MAX_COLLECTION_SIZE;
    if (!Number.isInteger(maxCollectionSize) || maxCollectionSize < 0) {
      throw new Error(`maxCollectionSize must be a non-negative integer but got ${maxCollectionSize}`);
    }

    this.typeResolver = new TypeResolver(this.config);
    this.writeContext = new WriteContext(this.typeResolver, this.config);
    this.readContext = new ReadContext(this.typeResolver, this.config);
    this.typeResolver.bindContexts(this.writeContext, this.readContext);
    this.typeResolver.init();
    this.anySerializer = this.typeResolver.getSerializerById(TypeId.UNKNOWN);
  }

  private initConfig(config: Partial<Config> | undefined) {
    return {
      ref: Boolean(config?.ref),
      useSliceString: Boolean(config?.useSliceString),
      maxDepth: config?.maxDepth,
      maxBinarySize: config?.maxBinarySize,
      maxCollectionSize: config?.maxCollectionSize,
      hooks: config?.hooks || {},
      compatible: Boolean(config?.compatible),
    };
  }

  register<T>(constructor: new () => T, customSerializer: CustomSerializer<T>): {
    serializer: Serializer;
    serialize(data: InputType<T> | null): PlatformBuffer;
    deserialize(bytes: Uint8Array): ResultType<T>;
  };
  register<T extends TypeInfo>(typeInfo: T): {
    serializer: Serializer;
    serialize(data: InputType<T> | null): PlatformBuffer;
    deserialize(bytes: Uint8Array): ResultType<T>;
  };
  register<T extends new () => any>(constructor: T): {
    serializer: Serializer;
    serialize(data: Partial<InstanceType<T>> | null): PlatformBuffer;
    deserialize(bytes: Uint8Array): InstanceType<T> | null;
  };
  register(constructor: any, customSerializer?: CustomSerializer<any>) {
    let serializer: Serializer;
    if (constructor.prototype?.[ForyTypeInfoSymbol]) {
      const typeInfo: TypeInfo = (constructor.prototype[ForyTypeInfoSymbol] as WithForyClsInfo).structTypeInfo;
      typeInfo.freeze();
      serializer = new Gen(this.typeResolver, { creator: constructor, customSerializer }).generateSerializer(typeInfo);
      this.typeResolver.registerSerializer(typeInfo, serializer);
    } else {
      const typeInfo = constructor;
      typeInfo.freeze();
      serializer = new Gen(this.typeResolver, { customSerializer }).generateSerializer(typeInfo);
      this.typeResolver.registerSerializer(typeInfo, serializer);
    }
    return {
      serializer,
      serialize: (data: any) => this.serialize(data, serializer),
      deserialize: (bytes: Uint8Array) => {
        if (TypeId.polymorphicType(serializer.getTypeId())) {
          return this.deserialize(bytes, serializer);
        }
        return this.deserialize(bytes);
      },
    };
  }

  deserialize<T = any>(bytes: Uint8Array, serializer: Serializer = this.anySerializer): T | null {
    this.readContext.reset(bytes);
    const reader = this.readContext.reader;
    const bitmap = reader.readUint8();
    if ((bitmap & ConfigFlags.isNullFlag) === ConfigFlags.isNullFlag) {
      return null;
    }
    const isCrossLanguage = (bitmap & ConfigFlags.isCrossLanguageFlag) === ConfigFlags.isCrossLanguageFlag;
    if (!isCrossLanguage) {
      throw new Error("support crosslanguage mode only");
    }
    const isOutOfBandEnabled = (bitmap & ConfigFlags.isOutOfBandFlag) === ConfigFlags.isOutOfBandFlag;
    if (isOutOfBandEnabled) {
      throw new Error("outofband mode is not supported now");
    }
    return serializer.readRef();
  }

  private serializeInternal<T = any>(data: T, serializer: Serializer) {
    this.writeContext.reset();
    const writer = this.writeContext.writer;
    let bitmap = 0;
    if (data === null) {
      bitmap |= ConfigFlags.isNullFlag;
    }
    bitmap |= ConfigFlags.isCrossLanguageFlag;
    writer.writeUint8(bitmap);
    writer.reserve(serializer.fixedSize);
    serializer.writeRef(data);
    return writer;
  }

  serialize<T = any>(data: T, serializer: Serializer = this.anySerializer) {
    return this.serializeInternal(data, serializer).dump();
  }
}
