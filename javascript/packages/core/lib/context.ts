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
import {
  MetaString,
  MetaStringDecoder,
  MetaStringEncoder,
} from "./meta/MetaString";
import { InnerFieldInfo, TypeMeta } from "./meta/TypeMeta";
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
};

type CompatibleReadSerializerCacheEntry = {
  localHash: number;
  serializer: Serializer;
};

function remoteListElementType(
  fieldInfo: InnerFieldInfo,
): InnerFieldInfo | undefined {
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
      writer.writeVarUInt32(((this.dynamicNameId + 1) << 1) | 1);
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
      return this.names[idOrLen >> 1];
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
      return this.names[idOrLen >> 1];
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

  private disposeTypeInfo: TypeInfo[] = [];
  private dynamicTypeId = 0;

  constructor(
    readonly typeResolver: TypeResolverLike,
    config: Config,
  ) {
    this.writer = new BinaryWriter(config);
    this.refWriter = new RefWriter();
    this.metaStringWriter = new MetaStringWriter();
  }

  reset() {
    this.writer.reset();
    this.refWriter.reset();
    this.metaStringWriter.reset();
    this.disposeTypeInfo.forEach((typeInfo) => {
      typeInfo.dynamicTypeId = -1;
    });
    this.disposeTypeInfo = [];
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

  writeTypeMeta(typeInfo: TypeInfo, bytes: Uint8Array) {
    if (typeInfo.dynamicTypeId !== -1) {
      this.writer.writeVarUInt32((typeInfo.dynamicTypeId << 1) | 1);
      return;
    }
    const index = this.dynamicTypeId;
    typeInfo.dynamicTypeId = index;
    this.dynamicTypeId += 1;
    this.disposeTypeInfo.push(typeInfo);
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
  private static readonly MAX_CACHED_TYPE_META = 8192;
  private static readonly MAX_CACHED_COMPATIBLE_READ_SERIALIZER = 8192;

  readonly reader: BinaryReader;
  readonly refReader: RefReader;
  readonly metaStringReader: MetaStringReader;

  private typeMeta: TypeMeta[] = [];
  /** Persistent cross-message cache keyed by 8-byte type meta header. */
  private typeMetaCache: Map<number, Map<number, TypeMeta>> = new Map();
  private typeMetaCacheSize = 0;
  private lastTypeMetaHeaderLow = -1;
  private lastTypeMetaHeaderHigh = -1;
  private lastTypeMeta: TypeMeta | null = null;
  private recentTypeMetaHeaderLows = [-1, -1, -1, -1];
  private recentTypeMetaHeaderHighs = [-1, -1, -1, -1];
  private recentTypeMetas: Array<TypeMeta | null> = [null, null, null, null];
  private compatibleReadSerializers = new Map<
    number,
    CompatibleReadSerializerCacheEntry
  >();

  private _depth = 0;
  private _maxDepth: number;

  private static typeMetaHeaderHash(headerLow: number, headerHigh: number) {
    return headerHigh * 0x100000 + (headerLow >>> 12);
  }

  private findRecentTypeMeta(
    headerLow: number,
    headerHigh: number,
  ): TypeMeta | null {
    const lows = this.recentTypeMetaHeaderLows;
    const highs = this.recentTypeMetaHeaderHighs;
    const metas = this.recentTypeMetas;
    for (let i = 0; i < metas.length; i++) {
      const typeMeta = metas[i];
      if (
        typeMeta !== null
        && lows[i] === headerLow
        && highs[i] === headerHigh
      ) {
        return typeMeta;
      }
    }
    return null;
  }

  private rememberRecentTypeMeta(
    headerLow: number,
    headerHigh: number,
    typeMeta: TypeMeta,
  ) {
    const lows = this.recentTypeMetaHeaderLows;
    const highs = this.recentTypeMetaHeaderHighs;
    const metas = this.recentTypeMetas;
    if (lows[0] === headerLow && highs[0] === headerHigh) {
      metas[0] = typeMeta;
      return;
    }
    for (let i = metas.length - 1; i > 0; i--) {
      lows[i] = lows[i - 1];
      highs[i] = highs[i - 1];
      metas[i] = metas[i - 1];
    }
    lows[0] = headerLow;
    highs[0] = headerHigh;
    metas[0] = typeMeta;
  }

  constructor(
    readonly typeResolver: TypeResolverLike,
    config: Config,
  ) {
    this.reader = new BinaryReader(config);
    this.refReader = new RefReader(this.reader);
    this.metaStringReader = new MetaStringReader();
    this._maxDepth = config.maxDepth ?? 50;
  }

  reset(bytes: Uint8Array) {
    this.reader.reset(bytes);
    this.refReader.reset();
    this.metaStringReader.reset();
    this.typeMeta = [];
    this._depth = 0;
  }

  isCompatible() {
    return this.typeResolver.isCompatible();
  }

  incReadDepth() {
    this._depth++;
    if (this._depth > this._maxDepth) {
      throw new Error(
        `Deserialization depth limit exceeded: ${this._depth} > ${this._maxDepth}. `
        + "The data may be malicious, or increase maxDepth if needed.",
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

  private readTypeMetaFromHeader(
    dynamicTypeId: number,
    headerLow: number,
    headerHigh: number,
  ): TypeMeta {
    if (
      this.lastTypeMeta !== null
      && this.lastTypeMetaHeaderLow === headerLow
      && this.lastTypeMetaHeaderHigh === headerHigh
    ) {
      TypeMeta.skipBodyByHeaderLow(this.reader, headerLow);
      this.typeMeta[dynamicTypeId] = this.lastTypeMeta;
      return this.lastTypeMeta;
    }

    const recent = this.findRecentTypeMeta(headerLow, headerHigh);
    if (recent !== null) {
      TypeMeta.skipBodyByHeaderLow(this.reader, headerLow);
      this.lastTypeMetaHeaderLow = headerLow;
      this.lastTypeMetaHeaderHigh = headerHigh;
      this.lastTypeMeta = recent;
      this.typeMeta[dynamicTypeId] = recent;
      return recent;
    }

    const cached = this.typeMetaCache.get(headerHigh)?.get(headerLow);
    let typeMeta: TypeMeta;
    if (cached) {
      // Header-cache hits intentionally skip without rehashing. Entries reach this cache only
      // after a successful TypeMeta parse and 52-bit metadata-hash validation. The current body
      // size still comes from the current header bytes, not from the cached TypeMeta.
      TypeMeta.skipBodyByHeaderLow(this.reader, headerLow);
      typeMeta = cached;
      this.lastTypeMetaHeaderLow = headerLow;
      this.lastTypeMetaHeaderHigh = headerHigh;
      this.lastTypeMeta = typeMeta;
      this.rememberRecentTypeMeta(headerLow, headerHigh, typeMeta);
    } else {
      const header = (BigInt(headerHigh) << 32n) | BigInt(headerLow);
      typeMeta = TypeMeta.fromBytesAfterHeader(this.reader, header);
      if (this.typeMetaCacheSize < ReadContext.MAX_CACHED_TYPE_META) {
        let highCache = this.typeMetaCache.get(headerHigh);
        if (highCache === undefined) {
          highCache = new Map();
          this.typeMetaCache.set(headerHigh, highCache);
        }
        highCache.set(headerLow, typeMeta);
        this.typeMetaCacheSize++;
        this.lastTypeMetaHeaderLow = headerLow;
        this.lastTypeMetaHeaderHigh = headerHigh;
        this.lastTypeMeta = typeMeta;
        this.rememberRecentTypeMeta(headerLow, headerHigh, typeMeta);
      }
    }
    this.typeMeta[dynamicTypeId] = typeMeta;
    return typeMeta;
  }

  readTypeMeta(): TypeMeta {
    const idOrLen = this.reader.readVarUInt32();
    if (idOrLen & 1) {
      const typeMeta = this.typeMeta[idOrLen >> 1];
      if (!typeMeta) {
        throw new Error(`missing TypeMeta reference ${idOrLen >> 1}`);
      }
      return typeMeta;
    }
    const headerLow = this.reader.readUint32();
    const headerHigh = this.reader.readUint32();
    return this.readTypeMetaFromHeader(idOrLen >> 1, headerLow, headerHigh);
  }

  readTypeMetaIfSchemaChanged(
    expectedHash: number,
    original?: Serializer,
  ): Serializer | undefined {
    const idOrLen = this.reader.readVarUInt32();
    let typeMeta: TypeMeta;
    let remoteHash: number;
    if (idOrLen & 1) {
      typeMeta = this.typeMeta[idOrLen >> 1];
      if (!typeMeta) {
        throw new Error(`missing TypeMeta reference ${idOrLen >> 1}`);
      }
      remoteHash = typeMeta.getHash();
    } else {
      const headerLow = this.reader.readUint32();
      const headerHigh = this.reader.readUint32();
      typeMeta = this.readTypeMetaFromHeader(
        idOrLen >> 1,
        headerLow,
        headerHigh,
      );
      remoteHash = ReadContext.typeMetaHeaderHash(headerLow, headerHigh);
    }
    if (expectedHash !== remoteHash) {
      const cached = this.compatibleReadSerializers.get(remoteHash);
      if (cached !== undefined && cached.localHash === expectedHash) {
        return cached.serializer;
      }
      const serializer = this.genSerializerByTypeMetaRuntime(
        typeMeta,
        original,
      );
      if (
        this.compatibleReadSerializers.size
        < ReadContext.MAX_CACHED_COMPATIBLE_READ_SERIALIZER
      ) {
        this.compatibleReadSerializers.set(remoteHash, {
          localHash: expectedHash,
          serializer,
        });
      }
      return serializer;
    }
    return undefined;
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
    if (
      this.canonicalTypeId(remote.typeId) !== this.canonicalFieldTypeId(local)
    ) {
      return false;
    }
    if (
      (remote.trackingRef === true) !== (local.trackingRef === true)
      || (remote.nullable === true) !== (local.nullable === true)
    ) {
      return false;
    }
    switch (remote.typeId) {
      case TypeId.MAP:
        return (
          this.fieldSchemasEqual(remote.options?.key, local.options?.key)
          && this.fieldSchemasEqual(remote.options?.value, local.options?.value)
        );
      case TypeId.LIST:
        return this.fieldSchemasEqual(
          remote.options?.inner,
          local.options?.inner,
        );
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
      const compatible = this.compatibleFieldTypeInfo(
        fieldInfo,
        fallbackTypeInfo,
      );
      if (compatible) {
        return compatible;
      }
      if (
        isCompatibleScalarType(fieldInfo.typeId)
        && isCompatibleScalarType(fallbackTypeInfo.typeId)
        && ((fieldInfo.trackingRef === true)
        !== (fallbackTypeInfo.trackingRef === true)
        || ((fieldInfo.trackingRef === true
        || fallbackTypeInfo.trackingRef === true)
        && (fieldInfo.typeId !== fallbackTypeInfo.typeId
        || fieldInfo.nullable !== fallbackTypeInfo.nullable)))
      ) {
        throw new Error(
          "unsupported compatible scalar tracking-ref schema mismatch",
        );
      }
      if (
        isCompatibleScalarPair(fieldInfo.typeId, fallbackTypeInfo.typeId)
        && fieldInfo.typeId !== fallbackTypeInfo.typeId
        && (fieldInfo.trackingRef === true
        || fallbackTypeInfo.trackingRef === true)
      ) {
        throw new Error(
          "unsupported compatible scalar tracking-ref schema mismatch",
        );
      }
      if (
        this.hasUnsupportedListArrayMismatch(
          fieldInfo,
          fallbackTypeInfo,
          topLevel,
        )
      ) {
        throw new Error("unsupported compatible list/array schema mismatch");
      }
      if (
        fieldInfo.typeId !== TypeId.UNKNOWN
        && this.canonicalFieldTypeId(fallbackTypeInfo) !== TypeId.UNKNOWN
        && this.canonicalTypeId(fieldInfo.typeId)
        !== this.canonicalFieldTypeId(fallbackTypeInfo)
      ) {
        throw new Error("unsupported compatible field schema mismatch");
      }
    }
    if (
      this.hasUnsupportedListArrayMismatch(
        fieldInfo,
        fallbackTypeInfo,
        topLevel,
      )
    ) {
      throw new Error("unsupported compatible list/array schema mismatch");
    }
    if (this.hasNestedSchemaMismatch(fieldInfo, fallbackTypeInfo, topLevel)) {
      throw new Error("unsupported compatible field schema mismatch");
    }
    switch (fieldInfo.typeId) {
      case TypeId.MAP:
        return Type.map(
          this.fieldInfoToTypeInfo(
            fieldInfo.options!.key!,
            fallbackTypeInfo?.options?.key,
            false,
          ),
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
          this.fieldInfoToTypeInfo(
            fieldInfo.options!.key!,
            fallbackTypeInfo?.options?.key,
            false,
          ),
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
      this.schemaMatchTypeId(remote.typeId)
      !== this.schemaMatchTypeId(this.typeResolver.computeTypeId(local))
    ) {
      return true;
    }
    const remoteTracksRef = remote.trackingRef === true;
    const localTracksRef = local.trackingRef === true;
    if (
      remoteTracksRef !== localTracksRef
      || ((remoteTracksRef || localTracksRef)
      && (remote.nullable === true) !== (local.nullable === true))
    ) {
      return true;
    }
    switch (remote.typeId) {
      case TypeId.MAP:
        return (
          local.options?.key === undefined
          || local.options?.value === undefined
          || this.hasNestedSchemaMismatch(
            remote.options!.key!,
            local.options.key,
            false,
          )
          || this.hasNestedSchemaMismatch(
            remote.options!.value!,
            local.options.value,
            false,
          )
        );
      case TypeId.LIST:
        return (
          local.options?.inner === undefined
          || this.hasNestedSchemaMismatch(
            remote.options!.inner!,
            local.options.inner,
            false,
          )
        );
      case TypeId.SET:
        return (
          local.options?.key === undefined
          || this.hasNestedSchemaMismatch(
            remote.options!.key!,
            local.options.key,
            false,
          )
        );
      default:
        return false;
    }
  }

  private schemaMatchTypeId(typeId: number): number {
    return this.canonicalTypeId(typeId);
  }

  private compatibleFieldTypeInfo(
    remote: InnerFieldInfo,
    local: TypeInfo,
  ): TypeInfo | undefined {
    if (this.isByteSequenceRootPair(remote, local)) {
      if (
        (remote.nullable === true) !== (local.nullable === true)
        || (remote.trackingRef === true) !== (local.trackingRef === true)
      ) {
        return undefined;
      }
      return local.clone();
    }
    if (
      this.isListArrayRootPair(remote, local)
      && (remote.nullable === true
      || local.nullable === true
      || remote.trackingRef === true
      || local.trackingRef === true)
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
      remoteArrayElement !== undefined
      && local.typeId === TypeId.LIST
      && local.options?.inner
      && compatibleArrayElementTypeId(local.options.inner.typeId)
      === remoteArrayElement
    ) {
      return compatibleArrayToListTypeInfo(remoteArrayElement);
    }
    if (
      remote.trackingRef !== true
      && local.trackingRef !== true
      && !(
        remote.typeId === local.typeId
        && (remote.nullable === true) === (local.nullable === true)
      )
      && isCompatibleScalarPair(remote.typeId, local.typeId)
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
          this.hasUnsupportedListArrayMismatch(
            remote.options!.key!,
            local.options?.key,
            false,
          )
          || this.hasUnsupportedListArrayMismatch(
            remote.options!.value!,
            local.options?.value,
            false,
          )
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

  private isListArrayRootPair(
    remote: InnerFieldInfo,
    local: TypeInfo,
  ): boolean {
    return (
      (remote.typeId === TypeId.LIST
      && denseArrayElementTypeId(local.typeId) !== undefined)
      || (denseArrayElementTypeId(remote.typeId) !== undefined
      && local.typeId === TypeId.LIST)
    );
  }

  private isByteSequenceRootPair(
    remote: InnerFieldInfo,
    local: TypeInfo,
  ): boolean {
    return (
      (remote.typeId === TypeId.BINARY
      && local.typeId === TypeId.UINT8_ARRAY)
      || (remote.typeId === TypeId.UINT8_ARRAY && local.typeId === TypeId.BINARY)
    );
  }

  genSerializerByTypeMetaRuntime(typeMeta: TypeMeta, original?: Serializer) {
    const typeId = typeMeta.getTypeId();
    if (!TypeId.structType(typeId)) {
      throw new Error("only support reconstructor struct type");
    }
    if (!original) {
      if (TypeId.isNamedType(typeId)) {
        const named = `${typeMeta.getNs()}$${typeMeta.getTypeName()}`;
        original = this.typeResolver.getSerializerByName(named);
      } else {
        original = this.typeResolver.getSerializerById(
          typeId,
          typeMeta.getUserTypeId(),
        );
      }
    }
    let typeInfo: TypeInfo;
    if (original) {
      typeInfo = original.getTypeInfo().clone();
    } else if (!TypeId.isNamedType(typeId)) {
      typeInfo = Type.struct(typeMeta.getUserTypeId());
    } else {
      typeInfo = Type.struct({
        typeName: typeMeta.getTypeName(),
        namespace: typeMeta.getNs(),
      });
    }
    const localProps = original?.getTypeInfo().options?.props;
    const fieldEntries = typeMeta
      .remapFieldNames(localProps)
      .map((fieldInfo) => {
        const localFieldTypeInfo = localProps?.[fieldInfo.getFieldName()];
        let fieldTypeInfo = this.fieldInfoToTypeInfo(
          fieldInfo,
          localFieldTypeInfo,
        )
          .setNullable(fieldInfo.nullable)
          .setTrackingRef(fieldInfo.trackingRef)
          .setId(fieldInfo.fieldId);
        if (localFieldTypeInfo === undefined) {
          fieldTypeInfo = markCompatibleSkipRead(fieldTypeInfo);
        }
        return { key: fieldInfo.getFieldName(), typeInfo: fieldTypeInfo };
      });
    const props = Object.fromEntries(
      fieldEntries.map(({ key, typeInfo }) => [key, typeInfo]),
    );
    typeInfo.options = {
      ...typeInfo.options,
      preserveFieldOrder: true,
      fieldEntries,
      props,
    };
    const serializer = original
      ? this.typeResolver.generateReadSerializer(typeInfo)
      : this.typeResolver.regenerateReadSerializer(typeInfo);
    return serializer;
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
