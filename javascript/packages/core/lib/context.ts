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
import { InnerFieldInfo, TypeMeta } from "./meta/TypeMeta";
import { Type, TypeInfo } from "./typeInfo";
import { Config, RefFlags, Serializer, TypeId } from "./type";

type TypeResolverLike = {
  config: Config;
  computeTypeId(typeInfo: TypeInfo): number;
  getSerializerById(id: number, userTypeId?: number): Serializer | undefined;
  getSerializerByName(name: string): Serializer | undefined;
  getSerializerByData(value: any): Serializer | null | undefined;
  isCompatible(): boolean;
  regenerateReadSerializer(typeInfo: TypeInfo): Serializer;
};

class MetaStringBytes {
  dynamicWriteStringId = -1;

  constructor(public bytes: MetaString) {
  }
}

export class RefWriter {
  private writeObjects: Map<any, number> = new Map();

  reset() {
    this.writeObjects = new Map();
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

  constructor(private reader: BinaryReader) {
  }

  reset() {
    this.readObjects = [];
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
  private _maxBinarySize: number;
  private _maxCollectionSize: number;

  constructor(
    readonly typeResolver: TypeResolverLike,
    config: Config,
  ) {
    this.writer = new BinaryWriter(config);
    this.refWriter = new RefWriter();
    this.metaStringWriter = new MetaStringWriter();
    this._maxBinarySize = config.maxBinarySize ?? 64 * 1024 * 1024;
    this._maxCollectionSize = config.maxCollectionSize ?? 1_000_000;
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

  checkCollectionSize(size: number) {
    if (size > this._maxCollectionSize) {
      throw new Error(
        `Collection size ${size} exceeds maxCollectionSize ${this._maxCollectionSize}. `
        + "The data may be malicious, or increase maxCollectionSize if needed."
      );
    }
  }

  checkBinarySize(size: number) {
    if (size > this._maxBinarySize) {
      throw new Error(
        `Binary size ${size} exceeds maxBinarySize ${this._maxBinarySize}. `
        + "The data may be malicious, or increase maxBinarySize if needed."
      );
    }
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
    if (this.typeResolver.config.refTracking === true) {
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

  get maxBinarySize() {
    return this._maxBinarySize;
  }

  get maxCollectionSize() {
    return this._maxCollectionSize;
  }
}

export class ReadContext {
  readonly reader: BinaryReader;
  readonly refReader: RefReader;
  readonly metaStringReader: MetaStringReader;

  private typeMeta: TypeMeta[] = [];
  private _depth = 0;
  private _maxDepth: number;
  private _maxBinarySize: number;
  private _maxCollectionSize: number;

  constructor(
    readonly typeResolver: TypeResolverLike,
    config: Config,
  ) {
    this.reader = new BinaryReader(config);
    this.refReader = new RefReader(this.reader);
    this.metaStringReader = new MetaStringReader();
    this._maxDepth = config.maxDepth ?? 50;
    this._maxBinarySize = config.maxBinarySize ?? 64 * 1024 * 1024;
    this._maxCollectionSize = config.maxCollectionSize ?? 1_000_000;
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
        + "The data may be malicious, or increase maxDepth if needed."
      );
    }
  }

  decReadDepth() {
    this._depth--;
  }

  checkCollectionSize(size: number) {
    if (size > this._maxCollectionSize) {
      throw new Error(
        `Collection size ${size} exceeds maxCollectionSize ${this._maxCollectionSize}. `
        + "The data may be malicious, or increase maxCollectionSize if needed."
      );
    }
  }

  checkBinarySize(size: number) {
    if (size > this._maxBinarySize) {
      throw new Error(
        `Binary size ${size} exceeds maxBinarySize ${this._maxBinarySize}. `
        + "The data may be malicious, or increase maxBinarySize if needed."
      );
    }
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

  readTypeMeta(): TypeMeta {
    const idOrLen = this.reader.readVarUInt32();
    if (idOrLen & 1) {
      return this.typeMeta[idOrLen >> 1];
    }
    const typeMeta = TypeMeta.fromBytes(this.reader);
    this.typeMeta.push(typeMeta);
    return typeMeta;
  }

  private fieldInfoToTypeInfo(fieldInfo: InnerFieldInfo): TypeInfo {
    switch (fieldInfo.typeId) {
      case TypeId.MAP:
        return Type.map(
          this.fieldInfoToTypeInfo(fieldInfo.options!.key!),
          this.fieldInfoToTypeInfo(fieldInfo.options!.value!)
        );
      case TypeId.LIST:
        return Type.array(this.fieldInfoToTypeInfo(fieldInfo.options!.inner!));
      case TypeId.SET:
        return Type.set(this.fieldInfoToTypeInfo(fieldInfo.options!.key!));
      default: {
        const serializer = this.typeResolver.getSerializerById(fieldInfo.typeId, fieldInfo.userTypeId);
        if (serializer) {
          return serializer.getTypeInfo().clone();
        }
        return Type.any();
      }
    }
  }

  genSerializerByTypeMetaRuntime(typeMeta: TypeMeta, original?: Serializer) {
    const typeId = typeMeta.getTypeId();
    if (!TypeId.structType(typeId)) {
      throw new Error("only support reconstructor struct type");
    }
    let typeInfo: TypeInfo;
    if (original) {
      typeInfo = original.getTypeInfo().clone();
    } else if (!TypeId.isNamedType(typeId)) {
      typeInfo = Type.struct(typeMeta.getUserTypeId());
    } else {
      typeInfo = Type.struct({ typeName: typeMeta.getTypeName(), namespace: typeMeta.getNs() });
    }
    const props = Object.fromEntries(typeMeta.getFieldInfo().map((fieldInfo) => {
      const fieldTypeInfo = this.fieldInfoToTypeInfo(fieldInfo)
        .setNullable(fieldInfo.nullable)
        .setTrackingRef(fieldInfo.trackingRef)
        .setId(fieldInfo.fieldId);
      return [fieldInfo.getFieldName(), fieldTypeInfo];
    }));
    typeInfo.options = {
      ...typeInfo.options,
      props,
    };
    return this.typeResolver.regenerateReadSerializer(typeInfo);
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

  get maxBinarySize() {
    return this._maxBinarySize;
  }

  get maxCollectionSize() {
    return this._maxCollectionSize;
  }
}
