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

import { BinaryReader } from "./reader";
import { BinaryWriter } from "./writer";
import { MetaString, MetaStringDecoder, MetaStringEncoder } from "./meta/MetaString";
import {
  checkedTypeMetaSerializerSymbol,
  checkedTypeMetaWireTypeIdSymbol,
  InnerFieldInfo,
  localTypeMetaSymbol,
  TypeMeta,
} from "./meta/TypeMeta";
import { Type, TypeInfo } from "./typeInfo";
import { Config, RefFlags, Serializer, TypeId } from "./type";
import { markCompatibleCollectionArrayRead } from "./gen/collection";
import {
  isCompatibleScalarPair,
  isCompatibleScalarType,
  markCompatibleScalarRead,
} from "./compatible/scalar";
import { markCompatibleSkipRead } from "./compatible/field";

type TypeResolverLike = {
  config: Config;
  trackingRef: boolean;
  computeTypeId(typeInfo: TypeInfo): number;
  getSerializerById(id: number, userTypeId?: number): Serializer | undefined;
  getSerializerByName(name: string): Serializer | undefined;
  getSerializerByData(value: any): Serializer | null | undefined;
  isCompatible(): boolean;
  generateReadSerializer(typeInfo: TypeInfo): Serializer;
  regenerateReadSerializer(typeInfo: TypeInfo): Serializer;
  getUnknownStructSerializer(typeMeta?: TypeMeta, wireTypeId?: number): Serializer;
};

type CompatibleReadSerializerCacheEntry = {
  localHash: number;
  owner: TypeMeta;
  serializer: Serializer;
};

type LocalTypeMetaOwner = {
  [localTypeMetaSymbol]?: TypeMeta;
};

type CheckedTypeMeta = TypeMeta & {
  [checkedTypeMetaSerializerSymbol]?: Serializer;
};

type CheckedTypeMetaSerializer = Serializer & {
  [checkedTypeMetaWireTypeIdSymbol]?: number;
};

function remoteListElementType(fieldInfo: InnerFieldInfo): InnerFieldInfo | undefined {
  if (fieldInfo.typeId !== TypeId.LIST) {
    return undefined;
  }
  return fieldInfo.options?.inner;
}

function denseArrayElementTypeId(typeId: number): number | undefined {
  switch (typeId) {
    case TypeId.BOOL_ARRAY:
      return TypeId.BOOL;
    case TypeId.INT8_ARRAY:
      return TypeId.INT8;
    case TypeId.INT16_ARRAY:
      return TypeId.INT16;
    case TypeId.INT32_ARRAY:
      return TypeId.INT32;
    case TypeId.INT64_ARRAY:
      return TypeId.INT64;
    case TypeId.UINT8_ARRAY:
      return TypeId.UINT8;
    case TypeId.UINT16_ARRAY:
      return TypeId.UINT16;
    case TypeId.UINT32_ARRAY:
      return TypeId.UINT32;
    case TypeId.UINT64_ARRAY:
      return TypeId.UINT64;
    case TypeId.FLOAT16_ARRAY:
      return TypeId.FLOAT16;
    case TypeId.BFLOAT16_ARRAY:
      return TypeId.BFLOAT16;
    case TypeId.FLOAT32_ARRAY:
      return TypeId.FLOAT32;
    case TypeId.FLOAT64_ARRAY:
      return TypeId.FLOAT64;
    default:
      return undefined;
  }
}

function compatibleArrayElementTypeId(typeId: number): number {
  switch (typeId) {
    case TypeId.VARINT32:
      return TypeId.INT32;
    case TypeId.VARINT64:
    case TypeId.TAGGED_INT64:
      return TypeId.INT64;
    case TypeId.VAR_UINT32:
      return TypeId.UINT32;
    case TypeId.VAR_UINT64:
    case TypeId.TAGGED_UINT64:
      return TypeId.UINT64;
    default:
      return typeId;
  }
}

function typeInfoForElementTypeId(typeId: number): TypeInfo {
  switch (typeId) {
    case TypeId.BOOL:
      return Type.bool();
    case TypeId.INT8:
      return Type.int8();
    case TypeId.INT16:
      return Type.int16();
    case TypeId.INT32:
      return Type.int32({ encoding: "fixed" });
    case TypeId.VARINT32:
      return Type.int32();
    case TypeId.INT64:
      return Type.int64({ encoding: "fixed" });
    case TypeId.VARINT64:
      return Type.int64();
    case TypeId.TAGGED_INT64:
      return Type.int64({ encoding: "tagged" });
    case TypeId.UINT8:
      return Type.uint8();
    case TypeId.UINT16:
      return Type.uint16();
    case TypeId.UINT32:
      return Type.uint32({ encoding: "fixed" });
    case TypeId.VAR_UINT32:
      return Type.uint32();
    case TypeId.UINT64:
      return Type.uint64({ encoding: "fixed" });
    case TypeId.VAR_UINT64:
      return Type.uint64();
    case TypeId.TAGGED_UINT64:
      return Type.uint64({ encoding: "tagged" });
    case TypeId.FLOAT16:
      return Type.float16();
    case TypeId.BFLOAT16:
      return Type.bfloat16();
    case TypeId.FLOAT32:
      return Type.float32();
    case TypeId.FLOAT64:
      return Type.float64();
    default:
      return Type.any();
  }
}

function typeInfoForDenseArrayElementTypeId(typeId: number): TypeInfo {
  switch (typeId) {
    case TypeId.BOOL:
      return Type.boolArray();
    case TypeId.INT8:
      return Type.int8Array();
    case TypeId.INT16:
      return Type.int16Array();
    case TypeId.INT32:
      return Type.int32Array();
    case TypeId.INT64:
      return Type.int64Array();
    case TypeId.UINT8:
      return Type.uint8Array();
    case TypeId.UINT16:
      return Type.uint16Array();
    case TypeId.UINT32:
      return Type.uint32Array();
    case TypeId.UINT64:
      return Type.uint64Array();
    case TypeId.FLOAT16:
      return Type.float16Array();
    case TypeId.BFLOAT16:
      return Type.bfloat16Array();
    case TypeId.FLOAT32:
      return Type.float32Array();
    case TypeId.FLOAT64:
      return Type.float64Array();
    default:
      return Type.any();
  }
}

function compatibleListToArrayTypeInfo(
  remoteElement: InnerFieldInfo,
  targetElementTypeId: number,
): TypeInfo {
  const elementTypeInfo = typeInfoForElementTypeId(remoteElement.typeId)
    .setNullable(remoteElement.nullable === true)
    .setTrackingRef(remoteElement.trackingRef === true);
  const typeInfo = Type.list(elementTypeInfo);
  return markCompatibleCollectionArrayRead(typeInfo, {
    target: "array",
    elementTypeId: targetElementTypeId,
  });
}

function compatibleArrayToListTypeInfo(elementTypeId: number): TypeInfo {
  const typeInfo = typeInfoForDenseArrayElementTypeId(elementTypeId);
  return markCompatibleCollectionArrayRead(typeInfo, {
    target: "list",
    elementTypeId,
  });
}

class MetaStringBytes {
  dynamicWriteStringId = -1;

  constructor(public bytes: MetaString) {}
}

export class RefWriter {
  private writeObjects: Map<any, number> = new Map();

  reset() {
    if (this.writeObjects.size !== 0) {
      this.writeObjects.clear();
    }
  }

  writeRef(object: any) {
    this.writeObjects.set(object, this.writeObjects.size);
  }

  getWrittenRefId(obj: any) {
    return this.writeObjects.get(obj);
  }
}

export class RefReader {
  private readObjects: any[] = [];

  constructor(private reader: BinaryReader) {}

  reset() {
    if (this.readObjects.length !== 0) {
      this.readObjects.length = 0;
    }
  }

  getReadRef(refId: number) {
    // Missing compatible structs may surface as null field values, but they are
    // not published as reference targets; keep this hot path as a direct lookup.
    return this.readObjects[refId];
  }

  readRefFlag() {
    return this.reader.readInt8() as RefFlags;
  }

  reference(object: any) {
    this.readObjects.push(object);
  }
}

export class MetaStringWriter {
  private disposeMetaStringBytes: MetaStringBytes[] = [];
  private dynamicNameId = 0;
  private namespaceEncoder = new MetaStringEncoder(".", "_");
  private typenameEncoder = new MetaStringEncoder("$", "_");

  writeBytes(writer: BinaryWriter, bytes: MetaStringBytes) {
    if (bytes.dynamicWriteStringId !== -1) {
      writer.writeVarUInt32(((bytes.dynamicWriteStringId + 1) << 1) | 1);
    } else {
      bytes.dynamicWriteStringId = this.dynamicNameId;
      this.dynamicNameId += 1;
      this.disposeMetaStringBytes.push(bytes);
      const len = bytes.bytes.getBytes().byteLength;
      writer.writeVarUInt32(len << 1);
      if (len !== 0) {
        writer.writeUint8(bytes.bytes.getEncoding());
      }
      writer.buffer(bytes.bytes.getBytes());
    }
  }

  encodeNamespace(input: string) {
    return new MetaStringBytes(this.namespaceEncoder.encode(input));
  }

  encodeTypeName(input: string) {
    return new MetaStringBytes(this.typenameEncoder.encode(input));
  }

  reset() {
    this.disposeMetaStringBytes.forEach((item) => {
      item.dynamicWriteStringId = -1;
    });
    this.dynamicNameId = 0;
  }
}

export class MetaStringReader {
  private names: string[] = [];
  private namespaceDecoder = new MetaStringDecoder(".", "_");
  private typenameDecoder = new MetaStringDecoder("$", "_");

  readTypeName(reader: BinaryReader) {
    const idOrLen = reader.readVarUInt32();
    if (idOrLen & 1) {
      return this.names[(idOrLen >>> 1) - 1];
    }
    const len = idOrLen >> 1;
    if (len === 0) {
      this.names.push("");
      return "";
    }
    const encoding = reader.readUint8();
    const name = this.typenameDecoder.decode(reader, len, encoding);
    this.names.push(name);
    return name;
  }

  readNamespace(reader: BinaryReader) {
    const idOrLen = reader.readVarUInt32();
    if (idOrLen & 1) {
      return this.names[(idOrLen >>> 1) - 1];
    }
    const len = idOrLen >> 1;
    if (len === 0) {
      this.names.push("");
      return "";
    }
    const encoding = reader.readUint8();
    const name = this.namespaceDecoder.decode(reader, len, encoding);
    this.names.push(name);
    return name;
  }

  reset() {
    this.names = [];
  }
}

export class WriteContext {
  readonly writer: BinaryWriter;
  readonly refWriter: RefWriter;
  readonly metaStringWriter: MetaStringWriter;

  private disposeTypeMetaOwners: Array<{ dynamicTypeId: number }> = [];
  private dynamicTypeId = 0;

  constructor(
    readonly typeResolver: TypeResolverLike,
    config: { hps?: Config["hps"] } = {},
  ) {
    this.writer = new BinaryWriter(config);
    this.refWriter = new RefWriter();
    this.metaStringWriter = new MetaStringWriter();
  }

  reset() {
    this.writer.reset();
    this.refWriter.reset();
    this.metaStringWriter.reset();
    this.disposeTypeMetaOwners.forEach((owner) => {
      owner.dynamicTypeId = -1;
    });
    this.disposeTypeMetaOwners = [];
    this.dynamicTypeId = 0;
  }

  isCompatible() {
    return this.typeResolver.isCompatible();
  }

  writeRef(object: any) {
    this.refWriter.writeRef(object);
  }

  getWrittenRefId(object: any) {
    return this.refWriter.getWrittenRefId(object);
  }

  writeRefOrNull(object: any) {
    if (object === null || object === undefined) {
      this.writer.writeInt8(RefFlags.NullFlag);
      return true;
    }
    if (this.typeResolver.trackingRef) {
      const refId = this.refWriter.getWrittenRefId(object);
      if (typeof refId === "number") {
        this.writer.writeInt8(RefFlags.RefFlag);
        this.writer.writeVarUInt32(refId);
        return true;
      }
      this.writer.writeInt8(RefFlags.RefValueFlag);
      this.refWriter.writeRef(object);
      return false;
    }
    this.writer.writeInt8(RefFlags.NotNullValueFlag);
    return false;
  }

  writeTypeMeta(owner: { dynamicTypeId: number }, bytes: Uint8Array) {
    if (owner.dynamicTypeId !== -1) {
      this.writer.writeVarUInt32((owner.dynamicTypeId << 1) | 1);
      return;
    }
    const index = this.dynamicTypeId;
    owner.dynamicTypeId = index;
    this.dynamicTypeId += 1;
    this.disposeTypeMetaOwners.push(owner);
    this.writer.writeVarUInt32(index << 1);
    this.writer.buffer(bytes);
  }

  writeMetaStringBytes(bytes: MetaStringBytes) {
    this.metaStringWriter.writeBytes(this.writer, bytes);
  }

  writeBool(value: boolean) {
    this.writer.bool(value);
  }

  writeUint8(value: number) {
    this.writer.writeUint8(value);
  }

  writeInt8(value: number) {
    this.writer.writeInt8(value);
  }

  writeUint16(value: number) {
    this.writer.writeUint16(value);
  }

  writeInt16(value: number) {
    this.writer.writeInt16(value);
  }

  writeUint32(value: number) {
    this.writer.writeUint32(value);
  }

  writeInt32(value: number) {
    this.writer.writeInt32(value);
  }

  writeUint64(value: bigint) {
    this.writer.writeUint64(value);
  }

  writeInt64(value: bigint) {
    this.writer.writeInt64(value);
  }

  writeVarUInt32(value: number) {
    this.writer.writeVarUInt32(value);
  }

  writeVarUint32Small7(value: number) {
    this.writer.writeVarUint32Small7(value);
  }

  writeVarInt32(value: number) {
    this.writer.writeVarInt32(value);
  }

  writeVarUInt64(value: bigint | number) {
    this.writer.writeVarUInt64(value);
  }

  writeVarInt64(value: bigint) {
    this.writer.writeVarInt64(value);
  }

  writeTaggedUInt64(value: bigint | number) {
    return this.writer.writeTaggedUInt64(value);
  }

  writeTaggedInt64(value: bigint | number) {
    return this.writer.writeTaggedInt64(value);
  }

  writeSliInt64(value: bigint | number) {
    this.writer.writeSliInt64(value);
  }

  writeFloat16(value: number) {
    this.writer.writeFloat16(value);
  }

  writeBfloat16(value: number) {
    this.writer.writeBfloat16(value);
  }

  writeFloat32(value: number) {
    this.writer.writeFloat32(value);
  }

  writeFloat64(value: number) {
    this.writer.writeFloat64(value);
  }

  writeString(value: string) {
    this.writer.stringWithHeader(value);
  }

  writeBuffer(value: ArrayLike<number>) {
    this.writer.buffer(value);
  }

  reserve(length: number) {
    this.writer.reserve(length);
  }

  writeGetCursor() {
    return this.writer.writeGetCursor();
  }

  setUint8Position(offset: number, value: number) {
    this.writer.setUint8Position(offset, value);
  }

  setUint16Position(offset: number, value: number) {
    this.writer.setUint16Position(offset, value);
  }

  setUint32Position(offset: number, value: number) {
    this.writer.setUint32Position(offset, value);
  }
}

export class ReadContext {
  private static readonly MIN_REMOTE_TYPE_META_LIMIT = 8192;
  private static readonly MAX_REMOTE_TYPE_KEYS = 8192;

  readonly reader: BinaryReader;
  readonly refReader: RefReader;
  readonly metaStringReader: MetaStringReader;

  private typeMeta: TypeMeta[] = [];
  /** Persistent checked cache keyed by the 52-bit TypeMeta header hash. */
  private typeMetaCache: Map<number, TypeMeta> = new Map();
  private totalAcceptedSchemaVersions = 0;
  private cachedTypeMeta: TypeMeta | undefined;
  private compatibleReadSerializers = new Map<number, CompatibleReadSerializerCacheEntry>();

  private _depth = 0;
  private _maxDepth: number;
  private readonly maxGraphMemoryBytes: number;
  private remainingGraphMemoryBytes = 0;
  private readonly maxUnbackedContainerItems: number;
  private remainingUnbackedContainerItems = 0;
  private remoteSchemaVersionsByType: Map<string | number, number> | undefined = undefined;

  constructor(
    readonly typeResolver: TypeResolverLike,
    config: Config,
  ) {
    this.reader = new BinaryReader(config);
    this.refReader = new RefReader(this.reader);
    this.metaStringReader = new MetaStringReader();
    this._maxDepth = config.maxDepth ?? 50;
    this.maxGraphMemoryBytes = config.maxGraphMemoryBytes;
    this.maxUnbackedContainerItems = config.maxUnbackedContainerItems;
  }

  reset(bytes: Uint8Array) {
    this.reader.reset(bytes);
    this.refReader.reset();
    this.metaStringReader.reset();
    this.typeMeta = [];
    this._depth = 0;
    this.remainingGraphMemoryBytes = this.maxGraphMemoryBytes;
    this.remainingUnbackedContainerItems = this.maxUnbackedContainerItems;
  }

  resetReadDepth() {
    // Root reads call this in finally; nested readers retain depth when a child throws.
    this._depth = 0;
    this.remainingUnbackedContainerItems = 0;
  }

  reserveGraphMemory(bytes: number) {
    const remaining = this.remainingGraphMemoryBytes - bytes;
    if (remaining >= 0 && bytes >= 0 && (bytes | 0) === bytes) {
      this.remainingGraphMemoryBytes = remaining;
      return;
    }
    this.reserveGraphMemorySlow(bytes, remaining);
  }

  private reserveGraphMemorySlow(bytes: number, remaining: number) {
    if (!Number.isSafeInteger(bytes) || bytes < 0) {
      this.throwGraphMemoryOverflow(bytes);
    }
    if (remaining < 0) {
      this.throwGraphBudgetExceeded(bytes);
    }
    this.remainingGraphMemoryBytes = remaining;
  }

  private throwGraphMemoryOverflow(bytes: number): never {
    throw new Error(`maxGraphMemoryBytes overflow: requested ${bytes} estimated graph bytes`);
  }

  private throwGraphBudgetExceeded(bytes: number): never {
    throw new Error(
      `maxGraphMemoryBytes exceeded: requested ${bytes} estimated graph bytes, ` +
        `${this.remainingGraphMemoryBytes} remaining, effective limit ` +
        `${this.maxGraphMemoryBytes}`,
    );
  }

  checkUnbackedContainerAllocation(items: number) {
    const requiredReadable = items - this.remainingUnbackedContainerItems;
    if (requiredReadable > 0) {
      this.reader.checkReadableBytes(requiredReadable);
    }
  }

  settleUnbackedContainerItems(items: number, startCursor: number) {
    const consumed = this.reader.readGetCursor() - startCursor;
    if (consumed >= items) {
      return;
    }
    const unbackedItems = items - consumed;
    if (unbackedItems <= this.remainingUnbackedContainerItems) {
      this.remainingUnbackedContainerItems -= unbackedItems;
      return;
    }
    this.throwUnbackedContainerLimit(unbackedItems);
  }

  private throwUnbackedContainerLimit(items: number): never {
    throw new Error(
      `maxUnbackedContainerItems exceeded: requested ${items}, ` +
        `${this.remainingUnbackedContainerItems} remaining`,
    );
  }

  isCompatible() {
    return this.typeResolver.isCompatible();
  }

  incReadDepth() {
    this._depth++;
    if (this._depth > this._maxDepth) {
      throw new Error(
        `Deserialization depth limit exceeded: ${this._depth} > ${this._maxDepth}. ` +
          "The data may be malicious, or increase maxDepth if needed.",
      );
    }
  }

  decReadDepth() {
    this._depth--;
  }

  readRefFlag() {
    return this.refReader.readRefFlag();
  }

  getReadRef(refId: number) {
    return this.refReader.getReadRef(refId);
  }

  reference(object: any) {
    this.refReader.reference(object);
  }

  private static typeMetaHeaderHash(headerLow: number, headerHigh: number) {
    return headerHigh * 0x100000 + (headerLow >>> 12);
  }

  private findCachedTypeMeta(headerHash: number): TypeMeta | undefined {
    const typeMeta = this.cachedTypeMeta;
    if (typeMeta !== undefined && typeMeta.headerHash === headerHash) {
      return typeMeta;
    }
    return undefined;
  }

  private rememberTypeMeta(typeMeta: TypeMeta) {
    this.cachedTypeMeta = typeMeta;
  }

  private checkNewTypeMetaIndex(dynamicTypeId: number) {
    // The root-local array length is the next writer-assigned slot. A new
    // marker must neither skip a slot nor overwrite metadata already bound.
    const expected = this.typeMeta.length;
    if (dynamicTypeId !== expected) {
      throw new Error(`Invalid new TypeMeta index ${dynamicTypeId}; expected ${expected}`);
    }
  }

  private checkCompatibleTypeMetaOwner(typeMeta: TypeMeta, original?: Serializer) {
    if (original === undefined) {
      return;
    }
    // This miss-only check binds newly validated metadata to the serializer
    // owner declared by the current compatible read.
    const expectedTypeInfo = original.getTypeInfo();
    const expectedTypeId = original.getTypeId();
    const ownerMatches = TypeId.isNamedType(expectedTypeId)
      ? typeMeta.getNs() === expectedTypeInfo.namespace &&
        typeMeta.getTypeName() === expectedTypeInfo.typeName
      : typeMeta.getUserTypeId() === expectedTypeInfo.userTypeId;
    if (typeMeta.getTypeId() !== expectedTypeId || !ownerMatches) {
      const expectedOwner = TypeId.isNamedType(expectedTypeId)
        ? `${expectedTypeInfo.namespace}$${expectedTypeInfo.typeName}`
        : expectedTypeInfo.userTypeId;
      const remoteOwner = TypeId.isNamedType(typeMeta.getTypeId())
        ? `${typeMeta.getNs()}$${typeMeta.getTypeName()}`
        : typeMeta.getUserTypeId();
      throw new Error(
        `Compatible TypeMeta owner mismatch: expected type ${expectedTypeId} owner ${String(
          expectedOwner,
        )}, got type ${typeMeta.getTypeId()} owner ${String(remoteOwner)}`,
      );
    }
  }

  readTypeMeta(): TypeMeta {
    return this.readTypeMetaValue() as TypeMeta;
  }

  private readTypeMetaSerializer(expectedWireTypeId: number): Serializer {
    return this.readTypeMetaValue(expectedWireTypeId) as Serializer;
  }

  private readTypeMetaValue(expectedWireTypeId?: number): TypeMeta | Serializer {
    const idOrLen = this.reader.readVarUInt32();
    if (idOrLen & 1) {
      const typeMeta = this.readTypeMetaRef(idOrLen);
      return expectedWireTypeId === undefined
        ? typeMeta
        : this.checkedTypeMetaSerializer(typeMeta, expectedWireTypeId);
    }
    const dynamicTypeId = idOrLen >> 1;
    this.checkNewTypeMetaIndex(dynamicTypeId);
    const headerLow = this.reader.readUint32();
    const headerHigh = this.reader.readUint32();
    const headerHash = ReadContext.typeMetaHeaderHash(headerLow, headerHigh);
    if (expectedWireTypeId === undefined) {
      return this.readTypeMetaFromHeader(headerLow, headerHigh, headerHash);
    }
    return this.readTypeMetaFromHeader(
      headerLow,
      headerHigh,
      headerHash,
      undefined,
      undefined,
      expectedWireTypeId,
    );
  }

  readNamedTypeMeta(
    expectedTypeId: number,
    expectedNamespace: string,
    expectedTypeName: string,
  ): TypeMeta {
    const serializer = this.typeResolver.getSerializerByName(
      `${expectedNamespace}$${expectedTypeName}`,
    );
    if (serializer === undefined) {
      throw new Error(
        `can't find serializer for TypeMeta ${expectedNamespace}$${expectedTypeName}`,
      );
    }
    return this.readExpectedNamedTypeMeta(
      expectedTypeId,
      expectedNamespace,
      expectedTypeName,
      serializer,
    );
  }

  private readExpectedNamedTypeMeta(
    expectedTypeId: number,
    expectedNamespace: string,
    expectedTypeName: string,
    expectedSerializer: Serializer,
  ): TypeMeta {
    const localTypeMeta = (expectedSerializer as LocalTypeMetaOwner)[localTypeMetaSymbol];
    const idOrLen = this.reader.readVarUInt32();
    if (idOrLen & 1) {
      const typeMeta = this.readTypeMetaRef(idOrLen);
      this.checkTypeMetaSerializer(typeMeta, expectedSerializer, "TypeMeta owner mismatch");
      return typeMeta;
    }
    const dynamicTypeId = idOrLen >> 1;
    this.checkNewTypeMetaIndex(dynamicTypeId);
    const headerLow = this.reader.readUint32();
    const headerHigh = this.reader.readUint32();
    const headerHash = ReadContext.typeMetaHeaderHash(headerLow, headerHigh);
    let typeMeta: TypeMeta;
    // A statically selected named reader already owns the concrete local
    // metadata for this identity. Prefer it to any remote-cache object and use
    // the current frame only for the body bound and skip.
    if (localTypeMeta?.headerHash === headerHash) {
      TypeMeta.skipBodyByHeaderLow(this.reader, headerLow);
      this.typeMeta.push(localTypeMeta);
      return localTypeMeta;
    }
    // Header-cache hits intentionally compare only TypeMeta.headerHash, then skip
    // the body. That hash is the protocol metadata identity and is set only after
    // body/hash validation. Do not add low-bit state, parallel header slots,
    // rehashing, limits, exact-local checks, allocation, or policy work here;
    // the miss path owns that.
    const cachedTypeMeta = this.findCachedTypeMeta(headerHash);
    if (cachedTypeMeta !== undefined) {
      this.checkTypeMetaSerializer(cachedTypeMeta, expectedSerializer, "TypeMeta owner mismatch");
      TypeMeta.skipBodyByHeaderLow(this.reader, headerLow);
      typeMeta = cachedTypeMeta;
    } else {
      const cached = this.typeMetaCache.get(headerHash);
      if (cached !== undefined && cached.headerHash === headerHash) {
        this.checkTypeMetaSerializer(cached, expectedSerializer, "TypeMeta owner mismatch");
        TypeMeta.skipBodyByHeaderLow(this.reader, headerLow);
        typeMeta = cached;
        this.rememberTypeMeta(typeMeta);
      } else {
        const header = (BigInt(headerHigh) << 32n) | BigInt(headerLow);
        typeMeta = TypeMeta.fromBytesAfterHeader(
          this.reader,
          header,
          this.typeResolver.config.maxTypeFields,
          this.typeResolver.config.maxTypeMetaBytes,
        );
        this.checkNamedTypeMeta(typeMeta, expectedTypeId, expectedNamespace, expectedTypeName);
        const localSerializer = this.serializerByTypeMeta(typeMeta);
        if (localSerializer === undefined) {
          throw new Error(
            `can't find serializer for TypeMeta ${typeMeta.getNs()}$${typeMeta.getTypeName()}`,
          );
        }
        const exactLocalTypeMeta = this.matchingLocalTypeMeta(localSerializer, headerHash);
        if (exactLocalTypeMeta !== undefined) {
          typeMeta = exactLocalTypeMeta;
        } else {
          const typeKey = this.checkRemoteTypeMetaLimit(typeMeta);
          this.cacheTypeMeta(headerHash, typeMeta, typeKey, localSerializer, expectedTypeId);
        }
        this.typeMeta.push(typeMeta);
        return typeMeta;
      }
    }
    this.typeMeta.push(typeMeta);
    return typeMeta;
  }

  readCompatibleStructSerializer(
    localHash: number,
    original?: Serializer | TypeInfo,
  ): Serializer | undefined {
    const serializer =
      original instanceof TypeInfo
        ? (this.serializerByTypeInfo(original) ??
          this.typeResolver.generateReadSerializer(original))
        : original;
    if (serializer === undefined) {
      throw new Error("compatible TypeMeta requires a registered serializer owner");
    }
    return this.readCompatibleStructOwner(localHash, serializer);
  }

  private readCompatibleStructOwner(
    localHash: number,
    original: Serializer,
  ): Serializer | undefined {
    const localTypeMeta = (original as LocalTypeMetaOwner)[localTypeMetaSymbol];
    if (localTypeMeta === undefined) {
      throw new Error("compatible serializer is missing its local TypeMeta owner");
    }
    const idOrLen = this.reader.readVarUInt32();
    let typeMeta: TypeMeta;
    let remoteHash: number;
    const fromRef = (idOrLen & 1) !== 0;
    if (fromRef) {
      typeMeta = this.typeMeta[idOrLen >> 1];
      if (!typeMeta) {
        throw new Error(`missing TypeMeta reference ${idOrLen >> 1}`);
      }
      remoteHash = typeMeta.headerHash!;
    } else {
      const dynamicTypeId = idOrLen >> 1;
      this.checkNewTypeMetaIndex(dynamicTypeId);
      const headerLow = this.reader.readUint32();
      const headerHigh = this.reader.readUint32();
      const headerHash = ReadContext.typeMetaHeaderHash(headerLow, headerHigh);
      typeMeta = this.readTypeMetaFromHeader(
        headerLow,
        headerHigh,
        headerHash,
        localHash,
        original,
      );
      remoteHash = headerHash;
    }
    if (localHash !== remoteHash) {
      return this.checkedCompatibleReadSerializer(localHash, remoteHash, localTypeMeta);
    }
    if (fromRef) {
      this.checkTypeMetaSerializer(typeMeta, original, "Compatible TypeMeta owner mismatch");
    }
    return undefined;
  }

  private readTypeMetaRef(idOrLen: number): TypeMeta {
    const refId = idOrLen >> 1;
    const typeMeta = this.typeMeta[refId];
    if (!typeMeta) {
      throw new Error(`missing TypeMeta reference ${refId}`);
    }
    return typeMeta;
  }

  private checkNamedTypeMeta(
    typeMeta: TypeMeta,
    expectedTypeId: number,
    expectedNamespace: string,
    expectedTypeName: string,
  ) {
    if (
      typeMeta.getTypeId() !== expectedTypeId ||
      typeMeta.getNs() !== expectedNamespace ||
      typeMeta.getTypeName() !== expectedTypeName
    ) {
      throw new Error(
        `TypeMeta mismatch: expected ${expectedNamespace}$${expectedTypeName} ` +
          `type ${expectedTypeId}, got ${typeMeta.getNs()}$${typeMeta.getTypeName()} ` +
          `type ${typeMeta.getTypeId()}`,
      );
    }
  }

  private readTypeMetaFromHeader(
    headerLow: number,
    headerHigh: number,
    headerHash: number,
    localHash?: number,
    original?: Serializer,
  ): TypeMeta;
  private readTypeMetaFromHeader(
    headerLow: number,
    headerHigh: number,
    headerHash: number,
    localHash: number | undefined,
    original: Serializer | undefined,
    expectedWireTypeId: number,
  ): Serializer;
  private readTypeMetaFromHeader(
    headerLow: number,
    headerHigh: number,
    headerHash: number,
    localHash?: number,
    original?: Serializer,
    expectedWireTypeId?: number,
  ): TypeMeta | Serializer {
    const localTypeMeta = (original as LocalTypeMetaOwner | undefined)?.[localTypeMetaSymbol];
    // A generated compatible reader owns its concrete local TypeMeta. Its
    // 52-bit header hash is the complete schema identity, so this hit must win
    // even when the remote checked cache contains another object under the
    // same identity. The current low bits only bound and skip this frame.
    if (localTypeMeta?.headerHash === headerHash) {
      const checkedSerializer =
        expectedWireTypeId === undefined
          ? undefined
          : this.checkedTypeMetaSerializer(localTypeMeta, expectedWireTypeId);
      TypeMeta.skipBodyByHeaderLow(this.reader, headerLow);
      this.typeMeta.push(localTypeMeta);
      return checkedSerializer ?? localTypeMeta;
    }
    // Header-cache hits intentionally compare only TypeMeta.headerHash, then skip
    // the body. That hash is the protocol metadata identity and is set only after
    // body/hash validation. Do not add low-bit state, parallel header slots,
    // rehashing, limits, exact-local checks, allocation, or policy work here;
    // the miss path owns that.
    const changedSchema = localHash !== undefined && localHash !== headerHash;
    const cachedTypeMeta = this.findCachedTypeMeta(headerHash);
    if (cachedTypeMeta !== undefined) {
      const checkedSerializer =
        expectedWireTypeId === undefined
          ? undefined
          : this.checkedTypeMetaSerializer(cachedTypeMeta, expectedWireTypeId);
      TypeMeta.skipBodyByHeaderLow(this.reader, headerLow);
      this.typeMeta.push(cachedTypeMeta);
      return checkedSerializer ?? cachedTypeMeta;
    }

    const cached = this.typeMetaCache.get(headerHash);
    let typeMeta: TypeMeta;
    if (cached !== undefined && cached.headerHash === headerHash) {
      const checkedSerializer =
        expectedWireTypeId === undefined
          ? undefined
          : this.checkedTypeMetaSerializer(cached, expectedWireTypeId);
      TypeMeta.skipBodyByHeaderLow(this.reader, headerLow);
      typeMeta = cached;
      this.rememberTypeMeta(typeMeta);
      if (checkedSerializer !== undefined) {
        this.typeMeta.push(typeMeta);
        return checkedSerializer;
      }
    } else {
      const header = (BigInt(headerHigh) << 32n) | BigInt(headerLow);
      typeMeta = TypeMeta.fromBytesAfterHeader(
        this.reader,
        header,
        this.typeResolver.config.maxTypeFields,
        this.typeResolver.config.maxTypeMetaBytes,
      );
      const actualWireTypeId = typeMeta.getTypeId();
      if (expectedWireTypeId !== undefined && actualWireTypeId !== expectedWireTypeId) {
        this.throwTypeMetaWireType(expectedWireTypeId, actualWireTypeId);
      }
      if (changedSchema) {
        this.checkCompatibleTypeMetaOwner(typeMeta, original);
      }
      let localSerializer = original ?? this.serializerByTypeMeta(typeMeta);
      const exactLocalTypeMeta = this.matchingLocalTypeMeta(localSerializer, headerHash);
      let checkedSerializer: Serializer | undefined;
      if (exactLocalTypeMeta !== undefined) {
        typeMeta = exactLocalTypeMeta;
        if (expectedWireTypeId !== undefined) {
          checkedSerializer = this.checkedTypeMetaSerializer(typeMeta, expectedWireTypeId);
        }
      } else {
        if (localSerializer === undefined) {
          if (!TypeId.structType(typeMeta.getTypeId()) || !this.isCompatible()) {
            throw new Error(
              `can't find serializer for TypeMeta ${typeMeta.getNs()}$${typeMeta.getTypeName()}`,
            );
          }
        }
        const typeKey = this.checkRemoteTypeMetaLimit(typeMeta);
        let readSerializer: Serializer;
        if (localSerializer === undefined) {
          localSerializer = this.typeResolver.getUnknownStructSerializer(
            typeMeta,
            actualWireTypeId,
          );
          readSerializer = localSerializer;
        } else if (TypeId.structType(typeMeta.getTypeId())) {
          const expectedHash = localHash !== undefined ? localHash : localSerializer.getHash();
          if (expectedHash !== typeMeta.getHash()) {
            readSerializer = this.ensureCompatibleReadSerializer(
              typeMeta,
              expectedHash,
              typeMeta.getHash(),
              localSerializer,
            );
          } else {
            readSerializer = localSerializer;
          }
        } else if (localHash !== undefined && localHash !== typeMeta.getHash()) {
          readSerializer = this.ensureCompatibleReadSerializer(
            typeMeta,
            localHash,
            typeMeta.getHash(),
            original!,
          );
        } else {
          readSerializer = localSerializer;
        }
        checkedSerializer = this.cacheTypeMeta(
          headerHash,
          typeMeta,
          typeKey,
          readSerializer,
          actualWireTypeId,
        );
      }
      this.typeMeta.push(typeMeta);
      return checkedSerializer ?? typeMeta;
    }
    this.typeMeta.push(typeMeta);
    return typeMeta;
  }

  private ensureCompatibleReadSerializer(
    typeMeta: TypeMeta,
    localHash: number,
    remoteHash: number,
    original: Serializer,
  ): Serializer {
    const localTypeMeta = (original as LocalTypeMetaOwner)[localTypeMetaSymbol];
    if (localTypeMeta === undefined) {
      throw new Error("compatible serializer is missing its local TypeMeta owner");
    }
    const cached = this.compatibleReadSerializers.get(remoteHash);
    if (cached !== undefined) {
      if (cached.owner !== localTypeMeta) {
        throw new Error("Compatible TypeMeta owner mismatch");
      }
      if (cached.localHash === localHash) {
        return cached.serializer;
      }
    }
    const serializer = this.generateTypeMetaSerializer(typeMeta, original);
    this.compatibleReadSerializers.set(remoteHash, {
      localHash,
      owner: localTypeMeta,
      serializer,
    });
    return serializer;
  }

  private checkedCompatibleReadSerializer(
    localHash: number,
    remoteHash: number,
    localTypeMeta: TypeMeta,
  ): Serializer {
    const cached = this.compatibleReadSerializers.get(remoteHash);
    if (cached === undefined || cached.localHash !== localHash || cached.owner !== localTypeMeta) {
      throw new Error("Compatible TypeMeta owner mismatch");
    }
    return cached.serializer;
  }

  private checkRemoteTypeMetaLimit(typeMeta: TypeMeta) {
    const typeKey = TypeId.isNamedType(typeMeta.getTypeId())
      ? `${typeMeta.getNs()}\u0000${typeMeta.getTypeName()}`
      : typeMeta.getUserTypeId();
    const versionsByType = this.remoteSchemaVersionsByType;
    const versionsForType = versionsByType?.get(typeKey) ?? 0;
    const acceptedTypeCount = versionsByType?.size ?? 0;
    const isNewType = versionsForType === 0;
    if (isNewType && acceptedTypeCount >= ReadContext.MAX_REMOTE_TYPE_KEYS) {
      throw new Error(
        `Remote TypeMeta key limit exceeded: ${acceptedTypeCount} accepted non-local types`,
      );
    }
    const maxSchemaVersionsPerType = this.typeResolver.config.maxSchemaVersionsPerType;
    if (versionsForType >= maxSchemaVersionsPerType) {
      throw new Error(
        `Remote schema version limit exceeded for type ${String(typeKey)}: ` +
          `${versionsForType} >= ${maxSchemaVersionsPerType}. The data may ` +
          "be malicious. If the data is not malicious, please increase " +
          "maxSchemaVersionsPerType.",
      );
    }
    const resultingTypeCount = isNewType ? acceptedTypeCount + 1 : acceptedTypeCount;
    const maxAverageSchemaVersionsPerType =
      this.typeResolver.config.maxAverageSchemaVersionsPerType;
    if (
      this.totalAcceptedSchemaVersions >= ReadContext.MIN_REMOTE_TYPE_META_LIMIT &&
      Math.floor(this.totalAcceptedSchemaVersions / resultingTypeCount) >=
        maxAverageSchemaVersionsPerType
    ) {
      throw new Error(
        `Remote schema version limit exceeded: ${this.totalAcceptedSchemaVersions} ` +
          `metadata versions for ${resultingTypeCount} accepted remote types ` +
          `exceeds the average limit ${maxAverageSchemaVersionsPerType}. ` +
          "The data may be malicious. If the data is not malicious, please " +
          "increase maxAverageSchemaVersionsPerType.",
      );
    }
    return typeKey;
  }

  private cacheTypeMeta(
    headerHash: number,
    typeMeta: TypeMeta,
    typeKey: string | number | undefined,
    serializer: Serializer,
    wireTypeId: number,
  ) {
    const checkedSerializer = this.bindTypeMetaSerializer(typeMeta, serializer, wireTypeId);
    this.typeMetaCache.set(headerHash, typeMeta);
    this.rememberTypeMeta(typeMeta);
    if (typeKey !== undefined) {
      let versionsByType = this.remoteSchemaVersionsByType;
      if (versionsByType === undefined) {
        versionsByType = new Map();
        this.remoteSchemaVersionsByType = versionsByType;
      }
      const versionsForType = versionsByType.get(typeKey) ?? 0;
      versionsByType.set(typeKey, versionsForType + 1);
      this.totalAcceptedSchemaVersions++;
    }
    return checkedSerializer;
  }

  private serializerByTypeMeta(typeMeta: TypeMeta) {
    const typeId = typeMeta.getTypeId();
    if (TypeId.isNamedType(typeId)) {
      return this.typeResolver.getSerializerByName(`${typeMeta.getNs()}$${typeMeta.getTypeName()}`);
    }
    if (TypeId.needsUserTypeId(typeId)) {
      return this.typeResolver.getSerializerById(typeId, typeMeta.getUserTypeId());
    }
    return this.typeResolver.getSerializerById(typeId);
  }

  private serializerByTypeInfo(typeInfo: TypeInfo) {
    const typeId = this.typeResolver.computeTypeId(typeInfo);
    if (TypeId.isNamedType(typeId)) {
      return this.typeResolver.getSerializerByName(typeInfo.named!);
    }
    if (TypeId.needsUserTypeId(typeId)) {
      return this.typeResolver.getSerializerById(typeId, typeInfo.userTypeId);
    }
    return this.typeResolver.getSerializerById(typeId);
  }

  private matchingLocalTypeMeta(
    serializer: Serializer | undefined,
    headerHash: number,
  ): TypeMeta | undefined {
    const localTypeMeta = (serializer as LocalTypeMetaOwner | undefined)?.[localTypeMetaSymbol];
    return localTypeMeta?.headerHash === headerHash ? localTypeMeta : undefined;
  }

  private bindTypeMetaSerializer(typeMeta: TypeMeta, serializer: Serializer, wireTypeId: number) {
    const checkedTypeMeta = typeMeta as CheckedTypeMeta;
    if (checkedTypeMeta[checkedTypeMetaSerializerSymbol] === undefined) {
      checkedTypeMeta[checkedTypeMetaSerializerSymbol] = serializer;
    }
    const checkedSerializer = checkedTypeMeta[checkedTypeMetaSerializerSymbol]!;
    const owner = checkedSerializer as CheckedTypeMetaSerializer;
    if (owner[checkedTypeMetaWireTypeIdSymbol] === undefined) {
      owner[checkedTypeMetaWireTypeIdSymbol] = wireTypeId;
    } else if (owner[checkedTypeMetaWireTypeIdSymbol] !== wireTypeId) {
      this.throwTypeMetaWireType(wireTypeId, owner[checkedTypeMetaWireTypeIdSymbol]!);
    }
    return checkedSerializer;
  }

  private checkedTypeMetaSerializer(typeMeta: TypeMeta, expectedWireTypeId: number) {
    const serializer = (typeMeta as CheckedTypeMeta)[checkedTypeMetaSerializerSymbol] as
      CheckedTypeMetaSerializer | undefined;
    const actualWireTypeId = serializer?.[checkedTypeMetaWireTypeIdSymbol];
    if (serializer === undefined || actualWireTypeId !== expectedWireTypeId) {
      this.throwTypeMetaWireType(expectedWireTypeId, actualWireTypeId);
    }
    return serializer;
  }

  private throwTypeMetaWireType(expectedWireTypeId: number, actualWireTypeId?: number): never {
    throw new Error(
      `TypeMeta wire type mismatch: expected ${expectedWireTypeId}, got ${String(actualWireTypeId)}`,
    );
  }

  private checkTypeMetaSerializer(
    typeMeta: TypeMeta,
    expectedSerializer: Serializer,
    message: string,
  ) {
    const checkedSerializer = (typeMeta as CheckedTypeMeta)[checkedTypeMetaSerializerSymbol];
    const expectedLocalTypeMeta = (expectedSerializer as LocalTypeMetaOwner)[localTypeMetaSymbol];
    if (
      checkedSerializer === expectedSerializer ||
      (expectedLocalTypeMeta !== undefined &&
        (checkedSerializer as LocalTypeMetaOwner | undefined)?.[localTypeMetaSymbol] ===
          expectedLocalTypeMeta)
    ) {
      return;
    }
    throw new Error(message);
  }

  private canonicalTypeId(typeId: number): number {
    switch (typeId) {
      case TypeId.NAMED_ENUM:
        return TypeId.ENUM;
      case TypeId.NAMED_EXT:
        return TypeId.EXT;
      case TypeId.NAMED_COMPATIBLE_STRUCT:
      case TypeId.NAMED_STRUCT:
      case TypeId.COMPATIBLE_STRUCT:
      case TypeId.STRUCT:
        return TypeId.STRUCT;
      case TypeId.NAMED_UNION:
      case TypeId.TYPED_UNION:
        return TypeId.UNION;
      default:
        return typeId;
    }
  }

  private canonicalFieldTypeId(typeInfo: TypeInfo): number {
    return this.canonicalTypeId(this.typeResolver.computeTypeId(typeInfo));
  }

  private fieldSchemasEqual(
    remote: InnerFieldInfo | undefined,
    local: TypeInfo | undefined,
  ): boolean {
    if (remote === undefined || local === undefined) {
      return false;
    }
    if (this.canonicalTypeId(remote.typeId) !== this.canonicalFieldTypeId(local)) {
      return false;
    }
    if (
      (remote.trackingRef === true) !== (local.trackingRef === true) ||
      (remote.nullable === true) !== (local.nullable === true)
    ) {
      return false;
    }
    switch (remote.typeId) {
      case TypeId.MAP:
        return (
          this.fieldSchemasEqual(remote.options?.key, local.options?.key) &&
          this.fieldSchemasEqual(remote.options?.value, local.options?.value)
        );
      case TypeId.LIST:
        return this.fieldSchemasEqual(remote.options?.inner, local.options?.inner);
      case TypeId.SET:
        return this.fieldSchemasEqual(remote.options?.key, local.options?.key);
      default:
        return true;
    }
  }

  private fieldInfoToTypeInfo(
    fieldInfo: InnerFieldInfo,
    fallbackTypeInfo?: TypeInfo,
    topLevel = true,
  ): TypeInfo {
    if (topLevel && fallbackTypeInfo) {
      if (this.fieldSchemasEqual(fieldInfo, fallbackTypeInfo)) {
        return fallbackTypeInfo.clone();
      }
      const compatible = this.compatibleFieldTypeInfo(fieldInfo, fallbackTypeInfo);
      if (compatible) {
        return compatible;
      }
      if (
        isCompatibleScalarType(fieldInfo.typeId) &&
        isCompatibleScalarType(fallbackTypeInfo.typeId) &&
        ((fieldInfo.trackingRef === true) !== (fallbackTypeInfo.trackingRef === true) ||
          ((fieldInfo.trackingRef === true || fallbackTypeInfo.trackingRef === true) &&
            (fieldInfo.typeId !== fallbackTypeInfo.typeId ||
              fieldInfo.nullable !== fallbackTypeInfo.nullable)))
      ) {
        throw new Error("unsupported compatible scalar tracking-ref schema mismatch");
      }
      if (
        isCompatibleScalarPair(fieldInfo.typeId, fallbackTypeInfo.typeId) &&
        fieldInfo.typeId !== fallbackTypeInfo.typeId &&
        (fieldInfo.trackingRef === true || fallbackTypeInfo.trackingRef === true)
      ) {
        throw new Error("unsupported compatible scalar tracking-ref schema mismatch");
      }
      if (this.hasUnsupportedListArrayMismatch(fieldInfo, fallbackTypeInfo, topLevel)) {
        throw new Error("unsupported compatible list/array schema mismatch");
      }
      if (
        fieldInfo.typeId !== TypeId.UNKNOWN &&
        this.canonicalFieldTypeId(fallbackTypeInfo) !== TypeId.UNKNOWN &&
        this.canonicalTypeId(fieldInfo.typeId) !== this.canonicalFieldTypeId(fallbackTypeInfo)
      ) {
        throw new Error("unsupported compatible field schema mismatch");
      }
    }
    if (this.hasUnsupportedListArrayMismatch(fieldInfo, fallbackTypeInfo, topLevel)) {
      throw new Error("unsupported compatible list/array schema mismatch");
    }
    if (this.hasNestedSchemaMismatch(fieldInfo, fallbackTypeInfo, topLevel)) {
      throw new Error("unsupported compatible field schema mismatch");
    }
    switch (fieldInfo.typeId) {
      case TypeId.MAP:
        return Type.map(
          this.fieldInfoToTypeInfo(fieldInfo.options!.key!, fallbackTypeInfo?.options?.key, false),
          this.fieldInfoToTypeInfo(
            fieldInfo.options!.value!,
            fallbackTypeInfo?.options?.value,
            false,
          ),
        );
      case TypeId.LIST:
        return Type.list(
          this.fieldInfoToTypeInfo(
            fieldInfo.options!.inner!,
            fallbackTypeInfo?.options?.inner,
            false,
          ),
        );
      case TypeId.SET:
        return Type.set(
          this.fieldInfoToTypeInfo(fieldInfo.options!.key!, fallbackTypeInfo?.options?.key, false),
        );
      default: {
        // Remote TypeMeta only carries the nested user-defined type kind, not the
        // concrete named type or custom serializer identity. Reuse the local field
        // declaration when available. When the local field is absent, still prefer
        // any generic serializer available for that kind (for example enums) before
        // falling back to `any`.
        if (TypeId.userDefinedType(fieldInfo.typeId)) {
          if (fallbackTypeInfo) {
            return fallbackTypeInfo.clone();
          }
          const serializer = this.typeResolver.getSerializerById(
            fieldInfo.typeId,
            fieldInfo.userTypeId,
          );
          if (serializer) {
            return serializer.getTypeInfo().clone();
          }
          return Type.any();
        }
        const serializer = this.typeResolver.getSerializerById(
          fieldInfo.typeId,
          fieldInfo.userTypeId,
        );
        if (serializer) {
          return serializer.getTypeInfo().clone();
        }
        if (fallbackTypeInfo) {
          return fallbackTypeInfo.clone();
        }
        return Type.any();
      }
    }
  }

  private hasNestedSchemaMismatch(
    remote: InnerFieldInfo,
    local: TypeInfo | undefined,
    topLevel: boolean,
  ): boolean {
    if (topLevel || local === undefined) {
      return false;
    }
    if (
      this.schemaMatchTypeId(remote.typeId) !==
      this.schemaMatchTypeId(this.typeResolver.computeTypeId(local))
    ) {
      return true;
    }
    const remoteTracksRef = remote.trackingRef === true;
    const localTracksRef = local.trackingRef === true;
    if (
      remoteTracksRef !== localTracksRef ||
      ((remoteTracksRef || localTracksRef) &&
        (remote.nullable === true) !== (local.nullable === true))
    ) {
      return true;
    }
    switch (remote.typeId) {
      case TypeId.MAP:
        return (
          local.options?.key === undefined ||
          local.options?.value === undefined ||
          this.hasNestedSchemaMismatch(remote.options!.key!, local.options.key, false) ||
          this.hasNestedSchemaMismatch(remote.options!.value!, local.options.value, false)
        );
      case TypeId.LIST:
        return (
          local.options?.inner === undefined ||
          this.hasNestedSchemaMismatch(remote.options!.inner!, local.options.inner, false)
        );
      case TypeId.SET:
        return (
          local.options?.key === undefined ||
          this.hasNestedSchemaMismatch(remote.options!.key!, local.options.key, false)
        );
      default:
        return false;
    }
  }

  private schemaMatchTypeId(typeId: number): number {
    return this.canonicalTypeId(typeId);
  }

  private compatibleFieldTypeInfo(remote: InnerFieldInfo, local: TypeInfo): TypeInfo | undefined {
    if (this.isByteSequenceRootPair(remote, local)) {
      if (
        (remote.nullable === true) !== (local.nullable === true) ||
        (remote.trackingRef === true) !== (local.trackingRef === true)
      ) {
        return undefined;
      }
      return local.clone();
    }
    if (
      this.isListArrayRootPair(remote, local) &&
      (remote.nullable === true ||
        local.nullable === true ||
        remote.trackingRef === true ||
        local.trackingRef === true)
    ) {
      return undefined;
    }
    const remoteElement = remoteListElementType(remote);
    const localElement = denseArrayElementTypeId(local.typeId);
    if (remoteElement !== undefined && localElement !== undefined) {
      // Nullable element schema is allowed for list<T?> -> array<T>; actual
      // null payload elements fail in the dense-array reader. Ref-tracked
      // element framing is rejected here because this path stays primitive-only.
      if (remoteElement.trackingRef === true) {
        return undefined;
      }
      if (compatibleArrayElementTypeId(remoteElement.typeId) !== localElement) {
        return undefined;
      }
      return compatibleListToArrayTypeInfo(remoteElement, localElement);
    }
    const remoteArrayElement = denseArrayElementTypeId(remote.typeId);
    if (
      remoteArrayElement !== undefined &&
      local.typeId === TypeId.LIST &&
      local.options?.inner &&
      compatibleArrayElementTypeId(local.options.inner.typeId) === remoteArrayElement
    ) {
      return compatibleArrayToListTypeInfo(remoteArrayElement);
    }
    if (
      remote.trackingRef !== true &&
      local.trackingRef !== true &&
      !(
        remote.typeId === local.typeId && (remote.nullable === true) === (local.nullable === true)
      ) &&
      isCompatibleScalarPair(remote.typeId, local.typeId)
    ) {
      return markCompatibleScalarRead(local.clone(), {
        remoteTypeId: remote.typeId,
        localTypeId: local.typeId,
        remoteNullable: remote.nullable,
      });
    }
    return undefined;
  }

  private hasUnsupportedListArrayMismatch(
    remote: InnerFieldInfo,
    local: TypeInfo | undefined,
    topLevel: boolean,
  ): boolean {
    if (!local) {
      return false;
    }
    if (this.isListArrayRootPair(remote, local)) {
      return !(topLevel && this.compatibleFieldTypeInfo(remote, local));
    }
    if (remote.typeId !== local.typeId) {
      return false;
    }
    switch (remote.typeId) {
      case TypeId.MAP:
        return (
          this.hasUnsupportedListArrayMismatch(remote.options!.key!, local.options?.key, false) ||
          this.hasUnsupportedListArrayMismatch(remote.options!.value!, local.options?.value, false)
        );
      case TypeId.LIST:
        return this.hasUnsupportedListArrayMismatch(
          remote.options!.inner!,
          local.options?.inner,
          false,
        );
      case TypeId.SET:
        return this.hasUnsupportedListArrayMismatch(
          remote.options!.key!,
          local.options?.key,
          false,
        );
      default:
        return false;
    }
  }

  private isListArrayRootPair(remote: InnerFieldInfo, local: TypeInfo): boolean {
    return (
      (remote.typeId === TypeId.LIST && denseArrayElementTypeId(local.typeId) !== undefined) ||
      (denseArrayElementTypeId(remote.typeId) !== undefined && local.typeId === TypeId.LIST)
    );
  }

  private isByteSequenceRootPair(remote: InnerFieldInfo, local: TypeInfo): boolean {
    return (
      (remote.typeId === TypeId.BINARY && local.typeId === TypeId.UINT8_ARRAY) ||
      (remote.typeId === TypeId.UINT8_ARRAY && local.typeId === TypeId.BINARY)
    );
  }

  genSerializerByTypeMetaRuntime(
    typeMeta: TypeMeta,
    original?: Serializer | TypeInfo,
    expectedLocalHash?: number,
  ) {
    void expectedLocalHash;
    const typeId = typeMeta.getTypeId();
    if (!TypeId.structType(typeId)) {
      throw new Error("only support reconstructor struct type");
    }
    let originalSerializer = original instanceof TypeInfo ? undefined : original;
    let originalTypeInfo = original instanceof TypeInfo ? original : original?.getTypeInfo();
    if (originalSerializer === undefined && originalTypeInfo === undefined) {
      originalSerializer = this.serializerByTypeMeta(typeMeta);
      originalTypeInfo = originalSerializer?.getTypeInfo();
    }
    if (originalSerializer === undefined) {
      originalTypeInfo ??= TypeId.isNamedType(typeId)
        ? Type.struct({
            typeName: typeMeta.getTypeName(),
            namespace: typeMeta.getNs(),
          })
        : Type.struct(typeMeta.getUserTypeId());
      originalSerializer = this.typeResolver.generateReadSerializer(originalTypeInfo);
    }
    // This legacy direct-generation API accepts caller-owned metadata. It must
    // not publish into the checked wire cache; only the validated read path can
    // do that after binding a concrete local TypeMeta owner.
    return this.generateTypeMetaSerializer(typeMeta, originalSerializer);
  }

  private generateTypeMetaSerializer(typeMeta: TypeMeta, original: Serializer) {
    const typeId = typeMeta.getTypeId();
    if (!TypeId.structType(typeId)) {
      throw new Error("only support reconstructor struct type");
    }
    const originalTypeInfo = original.getTypeInfo();
    const typeInfo = originalTypeInfo
      ? originalTypeInfo.clone()
      : TypeId.isNamedType(typeId)
        ? Type.struct({
            typeName: typeMeta.getTypeName(),
            namespace: typeMeta.getNs(),
          })
        : Type.struct(typeMeta.getUserTypeId());
    const localProps = originalTypeInfo?.options?.props;
    const fieldEntries = typeMeta.remapFieldNames(localProps).map((fieldInfo) => {
      const localFieldTypeInfo = localProps?.[fieldInfo.getFieldName()];
      let fieldTypeInfo = this.fieldInfoToTypeInfo(fieldInfo, localFieldTypeInfo)
        .setNullable(fieldInfo.nullable)
        .setTrackingRef(fieldInfo.trackingRef);
      fieldTypeInfo.id = fieldInfo.fieldId;
      if (localFieldTypeInfo === undefined) {
        fieldTypeInfo = markCompatibleSkipRead(fieldTypeInfo);
      }
      return { key: fieldInfo.getFieldName(), typeInfo: fieldTypeInfo };
    });
    const props = Object.fromEntries(fieldEntries.map(({ key, typeInfo }) => [key, typeInfo]));
    typeInfo.options = {
      ...typeInfo.options,
      preserveFieldOrder: true,
      fieldEntries,
      props,
    };
    return this.typeResolver.generateReadSerializer(typeInfo);
  }

  readNamespace() {
    return this.metaStringReader.readNamespace(this.reader);
  }

  readTypeName() {
    return this.metaStringReader.readTypeName(this.reader);
  }

  readBool() {
    return this.reader.readUint8() === 1;
  }

  readUint8() {
    return this.reader.readUint8();
  }

  readInt8() {
    return this.reader.readInt8();
  }

  readUint16() {
    return this.reader.readUint16();
  }

  readInt16() {
    return this.reader.readInt16();
  }

  readUint32() {
    return this.reader.readUint32();
  }

  readInt32() {
    return this.reader.readInt32();
  }

  readUint64() {
    return this.reader.readUint64();
  }

  readInt64() {
    return this.reader.readInt64();
  }

  readVarUInt32() {
    return this.reader.readVarUInt32();
  }

  readVarUint32Small7() {
    return this.reader.readVarUint32Small7();
  }

  readVarInt32() {
    return this.reader.readVarInt32();
  }

  readVarUInt64() {
    return this.reader.readVarUInt64();
  }

  readVarInt64() {
    return this.reader.readVarInt64();
  }

  readTaggedUInt64() {
    return this.reader.readTaggedUInt64();
  }

  readTaggedInt64() {
    return this.reader.readTaggedInt64();
  }

  readSliInt64() {
    return this.reader.readSliInt64();
  }

  readFloat16() {
    return this.reader.readFloat16();
  }

  readBfloat16() {
    return this.reader.readBfloat16();
  }

  readFloat32() {
    return this.reader.readFloat32();
  }

  readFloat64() {
    return this.reader.readFloat64();
  }

  readString() {
    return this.reader.stringWithHeader();
  }

  readBuffer(length: number) {
    return this.reader.buffer(length);
  }

  readBufferRef(length: number) {
    return this.reader.bufferRef(length);
  }

  readGetCursor() {
    return this.reader.readGetCursor();
  }

  readSetCursor(value: number) {
    this.reader.readSetCursor(value);
  }

  get depth() {
    return this._depth;
  }

  get maxDepth() {
    return this._maxDepth;
  }
}
