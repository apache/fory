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

import { Scope } from "./scope";
import Fory from "../fory";

class TypeMetaBuilder {
  constructor(private fory: string) {

  }

  fromBytes(reader: string) {
    return `${this.fory}.typeMeta.fromBytes(${reader})`;
  }
}

export class BinaryReaderBuilder {
  constructor(private holder: string) {

  }

  ownName() {
    return this.holder;
  }

  readGetCursor() {
    return `${this.holder}.readGetCursor()`;
  }

  readSetCursor(v: number | string) {
    return `${this.holder}.readSetCursor(${v})`;
  }

  readVarInt32() {
    return `${this.holder}.readVarInt32()`;
  }

  readTaggedInt64() {
    return `${this.holder}.readTaggedInt64()`;
  }

  readTaggedUInt64() {
    return `${this.holder}.readTaggedUInt64()`;
  }

  readVarInt64() {
    return `${this.holder}.readVarInt64()`;
  }

  readVarUInt32() {
    return `${this.holder}.readVarUInt32()`;
  }

  readVarUInt64() {
    return `${this.holder}.readVarUInt64()`;
  }

  readInt8() {
    return `${this.holder}.readInt8()`;
  }

  buffer(len: string | number) {
    return `${this.holder}.buffer(${len})`;
  }

  bufferRef(len: string | number) {
    return `${this.holder}.bufferRef(${len})`;
  }

  readUint8() {
    return `${this.holder}.readUint8()`;
  }

  stringUtf8At() {
    return `${this.holder}.stringUtf8At()`;
  }

  stringUtf8() {
    return `${this.holder}.stringUtf8()`;
  }

  stringLatin1() {
    return `${this.holder}.stringLatin1()`;
  }

  stringWithHeader() {
    return `${this.holder}.stringWithHeader()`;
  }

  readFloat64() {
    return `${this.holder}.readFloat64()`;
  }

  readFloat32() {
    return `${this.holder}.readFloat32()`;
  }

  readFloat16() {
    return `${this.holder}.readFloat16()`;
  }

  readBfloat16() {
    return `${this.holder}.readBfloat16()`;
  }

  readUint16() {
    return `${this.holder}.readUint16()`;
  }

  readInt16() {
    return `${this.holder}.readInt16()`;
  }

  readVarUint32Small7() {
    return `${this.holder}.readVarUint32Small7()`;
  }

  readUint64() {
    return `${this.holder}.readUint64()`;
  }

  readSkip(v: number) {
    return `${this.holder}.readSkip(${v})`;
  }

  readInt64() {
    return `${this.holder}.readInt64()`;
  }

  readSliInt64() {
    return `${this.holder}.readSliInt64()`;
  }

  readUint32() {
    return `${this.holder}.readUint32()`;
  }

  readInt32() {
    return `${this.holder}.readInt32()`;
  }
}

class BinaryWriterBuilder {
  constructor(private holder: string) {

  }

  ownName() {
    return this.holder;
  }

  writeSkip(v: number | string) {
    return `${this.holder}.writeSkip(${v})`;
  }

  getByteLen() {
    return `${this.holder}.getByteLen()`;
  }

  getReserved() {
    return `${this.holder}.getReserved()`;
  }

  reserve(v: number | string) {
    return `${this.holder}.reserve(${v})`;
  }

  arrayBuffer(buffer: number | string, byteOffset: number | string, byteLength: number | string) {
    return `${this.holder}.arrayBuffer(${buffer}, ${byteOffset}, ${byteLength})`;
  }

  writeUint16(v: number | string) {
    return `${this.holder}.writeUint16(${v})`;
  }

  writeInt8(v: number | string) {
    return `${this.holder}.writeInt8(${v})`;
  }

  writeInt24(v: number | string) {
    return `${this.holder}.writeInt24(${v})`;
  }

  writeUint8(v: number | string) {
    return `${this.holder}.writeUint8(${v})`;
  }

  writeInt16(v: number | string) {
    return `${this.holder}.writeInt16(${v})`;
  }

  writeVarInt32(v: number | string) {
    return `${this.holder}.writeVarInt32(${v})`;
  }

  writeVarUint32Small7(v: number | string) {
    return `${this.holder}.writeVarUint32Small7(${v})`;
  }

  writeVarUInt32(v: number | string) {
    return `${this.holder}.writeVarUInt32(${v})`;
  }

  writeVarUInt64(v: number | string) {
    return `${this.holder}.writeVarUInt64(${v})`;
  }

  writeTaggedInt64(v: number | string) {
    return `${this.holder}.writeTaggedInt64(${v})`;
  }

  writeTaggedUInt64(v: number | string) {
    return `${this.holder}.writeTaggedUInt64(${v})`;
  }

  writeVarInt64(v: number | string) {
    return `${this.holder}.writeVarInt64(${v})`;
  }

  stringWithHeader(str: string) {
    return `${this.holder}.stringWithHeader(${str})`;
  }

  bufferWithoutMemCheck(v: string) {
    return `${this.holder}.bufferWithoutMemCheck(${v})`;
  }

  writeUint64(v: number | string) {
    return `${this.holder}.writeUint64(${v})`;
  }

  buffer(v: string) { // Accepting Uint8Array as a parameter
    return `${this.holder}.buffer(${v})`;
  }

  writeFloat64(v: number | string) {
    return `${this.holder}.writeFloat64(${v})`;
  }

  writeFloat32(v: number | string) {
    return `${this.holder}.writeFloat32(${v})`;
  }

  writeFloat16(v: number | string) {
    return `${this.holder}.writeFloat16(${v})`;
  }

  writeBfloat16(v: number | string) {
    return `${this.holder}.writeBfloat16(${v})`;
  }

  writeInt64(v: number | string) {
    return `${this.holder}.writeInt64(${v})`;
  }

  writeSliInt64(v: number | string) {
    return `${this.holder}.writeSliInt64(${v})`;
  }

  writeUint32(v: number | string) {
    return `${this.holder}.writeUint32(${v})`;
  }

  writeInt32(v: number | string) {
    return `${this.holder}.writeInt32(${v})`;
  }

  writeGetCursor() {
    return `${this.holder}.writeGetCursor()`;
  }

  setUint32Position(offset: number | string, v: number | string) {
    return `${this.holder}.setUint32Position(${offset}, ${v})`;
  }

  setUint8Position(offset: number | string, v: number | string) {
    return `${this.holder}.setUint8Position(${offset}, ${v})`;
  }

  setUint16Position(offset: number | string, v: number | string) {
    return `${this.holder}.setUint16Position(${offset}, ${v})`;
  }
}

class ReferenceResolverBuilder {
  constructor(private holder: string) {

  }

  ownName() {
    return this.holder;
  }

  getReadObject(id: string | number) {
    return `${this.holder}.getReadObject(${id})`;
  }

  reference(obj: string) {
    return `${this.holder}.reference(${obj})`;
  }

  writeRef(obj: string) {
    return `${this.holder}.writeRef(${obj})`;
  }

  existsWriteObject(obj: string) {
    return `${this.holder}.existsWriteObject(${obj})`;
  }
}

class TypeResolverBuilder {
  constructor(private holder: string) {

  }

  ownName() {
    return this.holder;
  }

  getSerializerById(id: string | number, userTypeId?: string | number) {
    if (userTypeId === undefined) {
      return `${this.holder}.getSerializerById(${id})`;
    }
    return `${this.holder}.getSerializerById(${id}, ${userTypeId})`;
  }

  getSerializerByName(name: string) {
    return `${this.holder}.getSerializerByName("${name}")`;
  }

  getSerializerByData(v: string) {
    return `${this.holder}.readTag(${v})`;
  }

  getTypeInfo(v: string) {
    return `${this.holder}.getTypeInfo("${v}")`;
  }
}

class TypeMetaResolverBuilder {
  constructor(private holder: string) {

  }

  ownName() {
    return this.holder;
  }

  writeTypeMeta(typeInfo: string, writer: string, bytes: string) {
    return `${this.holder}.writeTypeMeta(${typeInfo}, ${writer}, ${bytes})`;
  }

  readTypeMeta(reader: string) {
    return `${this.holder}.readTypeMeta(${reader})`;
  }

  genSerializerByTypeMetaRuntime(typeMeta: string) {
    return `${this.holder}.genSerializerByTypeMetaRuntime(${typeMeta})`;
  }
}

class MetaStringResolverBuilder {
  constructor(private holder: string) {

  }

  ownName() {
    return this.holder;
  }

  writeBytes(writer: string, bytes: string) {
    return `${this.holder}.writeBytes(${writer}, ${bytes})`;
  }

  readTypeName(reader: string) {
    return `${this.holder}.readTypeName(${reader})`;
  }

  readNamespace(reader: string) {
    return `${this.holder}.readNamespace(${reader})`;
  }

  encodeNamespace(input: string) {
    return `${this.holder}.encodeNamespace("${input}")`;
  }

  encodeTypeName(input: string) {
    return `${this.holder}.encodeTypeName("${input}")`;
  }
}

export class CodecBuilder {
  reader: BinaryReaderBuilder;
  writer: BinaryWriterBuilder;
  typeMeta: TypeMetaBuilder; // Use the TypeMetaWrapper
  referenceResolver: ReferenceResolverBuilder;
  typeResolver: TypeResolverBuilder;
  typeMetaResolver: TypeMetaResolverBuilder;
  metaStringResolver: MetaStringResolverBuilder;

  constructor(scope: Scope, public fory: Fory) {
    const br = scope.declareByName("br", "fory.binaryReader");
    const bw = scope.declareByName("bw", "fory.binaryWriter");
    const cr = scope.declareByName("cr", "fory.typeResolver");
    const rr = scope.declareByName("rr", "fory.referenceResolver");
    this.reader = new BinaryReaderBuilder(br);
    this.writer = new BinaryWriterBuilder(bw);
    this.typeResolver = new TypeResolverBuilder(cr);
    this.referenceResolver = new ReferenceResolverBuilder(rr);
    this.typeMeta = new TypeMetaBuilder("fory"); // Initialize the TypeMetaWrapper
    this.typeMetaResolver = new TypeMetaResolverBuilder("fory.typeMetaResolver");
    this.metaStringResolver = new MetaStringResolverBuilder("fory.metaStringResolver");
  }

  static isReserved(key: string) {
    return /^(?:do|if|in|for|let|new|try|var|case|else|enum|eval|false|null|this|true|void|with|break|catch|class|const|super|throw|while|yield|delete|export|import|public|return|static|switch|typeof|default|extends|finally|package|private|continue|debugger|function|arguments|interface|protected|implements|instanceof)$/.test(key);
  }

  static isDotPropAccessor(prop: string) {
    return /^[a-zA-Z_$][0-9a-zA-Z_$]*$/.test(prop);
  }

  static replaceBackslashAndQuote(v: string) {
    return v.replace(/\\/g, "\\\\").replace(/"/g, "\\\"");
  }

  static safeString(target: string) {
    if (!CodecBuilder.isDotPropAccessor(target) || CodecBuilder.isReserved(target)) {
      return `"${CodecBuilder.replaceBackslashAndQuote(target)}"`;
    }
    return `"${target}"`;
  }

  static safePropAccessor(prop: string) {
    if (!CodecBuilder.isDotPropAccessor(prop) || CodecBuilder.isReserved(prop)) {
      return `["${CodecBuilder.replaceBackslashAndQuote(prop)}"]`;
    }
    return `.${prop}`;
  }

  static safePropName(prop: string) {
    if (!CodecBuilder.isDotPropAccessor(prop) || CodecBuilder.isReserved(prop)) {
      return `["${CodecBuilder.replaceBackslashAndQuote(prop)}"]`;
    }
    return prop;
  }

  getForyName() {
    return "fory";
  }

  getExternal(key: string) {
    return `external.${key}`;
  }

  getOptions(key: string) {
    return `options.${key}`;
  }

  getTypeInfo() {
    return "typeInfo";
  }
}
