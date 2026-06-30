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
import {
  ConfigFlags,
  Serializer,
  Config,
  ForyTypeInfoSymbol,
  WithForyClsInfo,
  TypeId,
  CustomSerializer,
} from "./type";
import { InputType, ResultType, TypeInfo } from "./typeInfo";
import { Gen } from "./gen";
import { PlatformBuffer } from "./platformBuffer";
import { ReadContext, WriteContext } from "./context";

const DEFAULT_DEPTH_LIMIT = 50 as const;
const MIN_DEPTH_LIMIT = 2 as const;
const DEFAULT_MAX_TYPE_FIELDS = 512 as const;
const DEFAULT_MAX_TYPE_META_BYTES = 4096 as const;
const DEFAULT_MAX_SCHEMA_VERSIONS_PER_TYPE = 10 as const;
const DEFAULT_MAX_AVERAGE_SCHEMA_VERSIONS_PER_TYPE = 3 as const;
const DEFAULT_MAX_GRAPH_MEMORY_BYTES = -1 as const;
export default class Fory {
  readonly typeResolver: TypeResolver;
  readonly anySerializer: Serializer;
  readonly config: Config;
  readonly writeContext: WriteContext;
  readonly readContext: ReadContext;
  private readonly rootSerializers = new WeakMap<
    Serializer,
    (data: any) => PlatformBuffer
  >();

  private readonly rootDeserializers = new WeakMap<
    Serializer,
    (bytes: Uint8Array) => any
  >();

  constructor(config?: Partial<Config>) {
    this.config = this.initConfig(config);
    const maxDepth = this.config.maxDepth ?? DEFAULT_DEPTH_LIMIT;
    if (!Number.isInteger(maxDepth) || maxDepth < MIN_DEPTH_LIMIT) {
      throw new Error(
        `maxDepth must be an integer >= ${MIN_DEPTH_LIMIT} but got ${maxDepth}`,
      );
    }
    this.typeResolver = new TypeResolver(this.config);
    this.writeContext = new WriteContext(this.typeResolver, this.config);
    this.readContext = new ReadContext(this.typeResolver, this.config);
    this.typeResolver.bindContexts(this.writeContext, this.readContext);
    this.typeResolver.init();
    this.anySerializer = this.typeResolver.getSerializerById(TypeId.UNKNOWN);
  }

  private initConfig(config: Partial<Config> | undefined) {
    const maxTypeFields = config?.maxTypeFields ?? DEFAULT_MAX_TYPE_FIELDS;
    if (!Number.isInteger(maxTypeFields) || maxTypeFields <= 0) {
      throw new Error(
        `maxTypeFields must be a positive integer but got ${maxTypeFields}`,
      );
    }
    const maxTypeMetaBytes =
      config?.maxTypeMetaBytes ?? DEFAULT_MAX_TYPE_META_BYTES;
    if (!Number.isInteger(maxTypeMetaBytes) || maxTypeMetaBytes <= 0) {
      throw new Error(
        `maxTypeMetaBytes must be a positive integer but got ${maxTypeMetaBytes}`,
      );
    }
    const maxSchemaVersionsPerType =
      config?.maxSchemaVersionsPerType ?? DEFAULT_MAX_SCHEMA_VERSIONS_PER_TYPE;
    if (
      !Number.isInteger(maxSchemaVersionsPerType) ||
      maxSchemaVersionsPerType <= 0
    ) {
      throw new Error(
        `maxSchemaVersionsPerType must be a positive integer but got ${maxSchemaVersionsPerType}`,
      );
    }
    const maxAverageSchemaVersionsPerType =
      config?.maxAverageSchemaVersionsPerType ??
      DEFAULT_MAX_AVERAGE_SCHEMA_VERSIONS_PER_TYPE;
    if (
      !Number.isInteger(maxAverageSchemaVersionsPerType) ||
      maxAverageSchemaVersionsPerType <= 0
    ) {
      throw new Error(
        `maxAverageSchemaVersionsPerType must be a positive integer but got ${maxAverageSchemaVersionsPerType}`,
      );
    }
    const maxGraphMemoryBytes =
      config?.maxGraphMemoryBytes ?? DEFAULT_MAX_GRAPH_MEMORY_BYTES;
    if (
      !Number.isSafeInteger(maxGraphMemoryBytes) ||
      (maxGraphMemoryBytes !== -1 && maxGraphMemoryBytes <= 0)
    ) {
      throw new Error(
        `maxGraphMemoryBytes must be -1 or a positive safe integer but got ${maxGraphMemoryBytes}`,
      );
    }
    return {
      ref: Boolean(config?.ref),
      useSliceString: Boolean(config?.useSliceString),
      maxDepth: config?.maxDepth,
      maxGraphMemoryBytes,
      maxTypeFields,
      maxTypeMetaBytes,
      maxSchemaVersionsPerType,
      maxAverageSchemaVersionsPerType,
      hooks: config?.hooks || {},
      compatible: config?.compatible ?? true,
      hps: config?.hps,
    };
  }

  register<T>(
    constructor: new () => T,
    customSerializer: CustomSerializer<T>,
  ): {
    serializer: Serializer;
    serialize(data: InputType<T> | null): PlatformBuffer;
    deserialize(bytes: Uint8Array): ResultType<T>;
  };
  register<T extends TypeInfo>(
    typeInfo: T,
  ): {
    serializer: Serializer;
    serialize(data: InputType<T> | null): PlatformBuffer;
    deserialize(bytes: Uint8Array): ResultType<T>;
  };
  register<T extends new () => any>(
    constructor: T,
  ): {
    serializer: Serializer;
    serialize(data: Partial<InstanceType<T>> | null): PlatformBuffer;
    deserialize(bytes: Uint8Array): InstanceType<T> | null;
  };
  register(constructor: any, customSerializer?: CustomSerializer<any>) {
    let serializer: Serializer;
    if (constructor.prototype?.[ForyTypeInfoSymbol]) {
      const typeInfo: TypeInfo = (
        constructor.prototype[ForyTypeInfoSymbol] as WithForyClsInfo
      ).structTypeInfo;
      typeInfo.freeze();
      serializer = new Gen(this.typeResolver, {
        creator: constructor,
        customSerializer,
      }).generateSerializer(typeInfo);
      this.typeResolver.registerSerializer(typeInfo, serializer);
    } else {
      const typeInfo = constructor;
      typeInfo.freeze();
      serializer = new Gen(this.typeResolver, {
        customSerializer,
      }).generateSerializer(typeInfo);
      this.typeResolver.registerSerializer(typeInfo, serializer);
    }
    return {
      serializer,
      serialize: this.getRootSerializer(serializer),
      deserialize: this.getRootDeserializer(serializer),
    };
  }

  deserialize<T = any>(
    bytes: Uint8Array,
    serializer: Serializer = this.anySerializer,
  ): T | null {
    this.readContext.reset(bytes);
    const reader = this.readContext.reader;
    const bitmap = reader.readUint8();
    if (bitmap !== ConfigFlags.isCrossLanguageFlag) {
      this.throwInvalidRootHeader(bitmap);
    }
    return serializer.readRef();
  }

  private throwInvalidRootHeader(bitmap: number): never {
    const knownFlags =
      ConfigFlags.isCrossLanguageFlag | ConfigFlags.isOutOfBandFlag;
    if ((bitmap & ~knownFlags) !== 0) {
      throw new Error(
        `unsupported root header bitmap 0x${bitmap.toString(16)}`,
      );
    }
    if ((bitmap & ConfigFlags.isCrossLanguageFlag) === 0) {
      throw new Error("support crosslanguage mode only");
    }
    throw new Error("outofband mode is not supported now");
  }

  private getRootSerializer(serializer: Serializer) {
    let rootSerializer = this.rootSerializers.get(serializer);
    if (rootSerializer !== undefined) {
      return rootSerializer;
    }
    const writeContext = this.writeContext;
    const writer = writeContext.writer;
    const rootHeader = ConfigFlags.isCrossLanguageFlag;
    rootSerializer = (data: any) => {
      writeContext.reset();
      writer.writeUint8(rootHeader);
      writer.reserve(serializer.fixedSize);
      serializer.writeRef(data);
      return writer.dump();
    };
    this.rootSerializers.set(serializer, rootSerializer);
    return rootSerializer;
  }

  private getRootDeserializer(serializer: Serializer) {
    let rootDeserializer = this.rootDeserializers.get(serializer);
    if (rootDeserializer !== undefined) {
      return rootDeserializer;
    }
    const readContext = this.readContext;
    const reader = readContext.reader;
    const rootSerializer = TypeId.polymorphicType(serializer.getTypeId())
      ? serializer
      : this.anySerializer;
    const rootHeader = ConfigFlags.isCrossLanguageFlag;
    rootDeserializer = (bytes: Uint8Array) => {
      readContext.reset(bytes);
      const bitmap = reader.readUint8();
      if (bitmap !== rootHeader) {
        this.throwInvalidRootHeader(bitmap);
      }
      return rootSerializer.readRef();
    };
    this.rootDeserializers.set(serializer, rootDeserializer);
    return rootDeserializer;
  }

  serialize<T = any>(data: T, serializer: Serializer = this.anySerializer) {
    return this.getRootSerializer(serializer)(data);
  }
}
