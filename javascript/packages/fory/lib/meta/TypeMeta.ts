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

import { BinaryWriter } from "../writer";
import { BinaryReader } from "../reader";
import { Encoding, MetaStringDecoder, MetaStringEncoder } from "./MetaString";
import { StructTypeInfo } from "../typeInfo";
import { TypeId } from "../type";
import { x64hash128 } from "../murmurHash3";

const fieldEncoder = new MetaStringEncoder("$", ".");
const fieldDecoder = new MetaStringDecoder("$", ".");
const pkgEncoder = new MetaStringEncoder("_", ".");
const pkgDecoder = new MetaStringDecoder("_", ".");
const typeNameEncoder = new MetaStringEncoder("_", ".");
const typeNameDecoder = new MetaStringDecoder("_", ".");

const COMPRESS_META_FLAG = 1n << 63n;
const HAS_FIELDS_META_FLAG = 1n << 62n;
const META_SIZE_MASKS = 0xFFF; // 22 bits
const NUM_HASH_BITS = 41;
const BIG_NAME_THRESHOLD = 0b111111;

const PRIMITIVE_TYPE_IDS = [
  TypeId.BOOL, TypeId.INT8, TypeId.INT16, TypeId.INT32, TypeId.VARINT32,
  TypeId.INT64, TypeId.VARINT64, TypeId.TAGGED_INT64, TypeId.UINT8,
  TypeId.UINT16, TypeId.UINT32, TypeId.VAR_UINT32, TypeId.UINT64,
  TypeId.VAR_UINT64, TypeId.TAGGED_UINT64, TypeId.FLOAT8, TypeId.FLOAT16,
  TypeId.BFLOAT16, TypeId.FLOAT32, TypeId.FLOAT64,
];

/**
 * Logic for skipping reference tracking.
 * We ONLY skip for primitives, strings, and time types.
 * We MUST NOT skip for STRUCT/NAMED_STRUCT or we hit infinite recursion.
 */
export const refTrackingAbleTypeId = (typeId: number): boolean => {
  return PRIMITIVE_TYPE_IDS.includes(typeId as any) || 
         [TypeId.DURATION, TypeId.DATE, TypeId.TIMESTAMP, TypeId.STRING].includes(typeId as any);
};

export const isPrimitiveTypeId = (typeId: number): boolean => {
  return PRIMITIVE_TYPE_IDS.includes(typeId as any);
};

export const isInternalTypeId = (typeId: number): boolean => {
  return [
    TypeId.STRING,
    TypeId.TIMESTAMP,
    TypeId.DURATION,
    TypeId.DECIMAL,
    TypeId.BINARY,
    TypeId.BOOL_ARRAY,
    TypeId.INT8_ARRAY,
    TypeId.INT16_ARRAY,
    TypeId.INT32_ARRAY,
    TypeId.INT64_ARRAY,
    TypeId.FLOAT8_ARRAY,
    TypeId.FLOAT16_ARRAY,
    TypeId.BFLOAT16_ARRAY,
    TypeId.FLOAT32_ARRAY,
    TypeId.FLOAT64_ARRAY,
    TypeId.UINT16_ARRAY,
    TypeId.UINT32_ARRAY,
    TypeId.UINT64_ARRAY,
  ].includes(typeId as any);
};

function getPrimitiveTypeSize(typeId: number) {
  switch (typeId) {
    case TypeId.BOOL:
    case TypeId.INT8:
    case TypeId.UINT8:
    case TypeId.FLOAT8:
      return 1;
    case TypeId.INT16:
    case TypeId.UINT16:
    case TypeId.FLOAT16:
    case TypeId.BFLOAT16:
      return 2;
    case TypeId.INT32:
    case TypeId.UINT32:
    case TypeId.FLOAT32:
    case TypeId.VARINT32:
    case TypeId.VAR_UINT32:
      return 4;
    case TypeId.INT64:
    case TypeId.UINT64:
    case TypeId.FLOAT64:
    case TypeId.VARINT64:
    case TypeId.VAR_UINT64:
    case TypeId.TAGGED_INT64:
    case TypeId.TAGGED_UINT64:
      return 8;
    default:
      return 0;
  }
}

type InnerFieldInfoOptions = { key?: InnerFieldInfo; value?: InnerFieldInfo; inner?: InnerFieldInfo };
interface InnerFieldInfo {
  typeId: number;
  userTypeId: number;
  trackingRef: boolean;
  nullable: boolean;
  options?: InnerFieldInfoOptions;
}

class FieldInfo {
  constructor(
    public fieldName: string,
    public typeId: number,
    public userTypeId = -1,
    public trackingRef = false,
    public nullable = false,
    public options: InnerFieldInfoOptions = {},
  ) {}

  public getTypeId() { return this.typeId; }
  public getFieldName() { return this.fieldName; }

  static writeTypeId(writer: BinaryWriter, typeInfo: InnerFieldInfo, writeFlags = false) {
    let typeId = typeInfo.typeId;
    if (typeId === TypeId.NAMED_ENUM) {
      typeId = TypeId.ENUM;
    } else if (typeId === TypeId.NAMED_UNION || typeId === TypeId.TYPED_UNION) {
      typeId = TypeId.UNION;
    }
    
    if (writeFlags) {
      typeId = (typeId << 2);
      if (typeInfo.nullable) typeId |= 0b10;
      if (typeInfo.trackingRef) typeId |= 0b1;
      writer.writeVarUint32Small7(typeId);
    } else {
      writer.uint8(typeId);
    }

    const baseTypeId = typeId & 0xff;
    switch (baseTypeId) {
      case TypeId.LIST:
      case TypeId.SET:
        if (typeInfo.options?.inner) {
          FieldInfo.writeTypeId(writer, typeInfo.options.inner, true);
        }
        break;
      case TypeId.MAP:
        if (typeInfo.options?.key && typeInfo.options?.value) {
          FieldInfo.writeTypeId(writer, typeInfo.options.key, true);
          FieldInfo.writeTypeId(writer, typeInfo.options.value, true);
        }
        break;
    }
  }

  static u8ToEncoding(value: number) {
    switch (value) {
      case 0x00: return Encoding.UTF_8;
      case 0x01: return Encoding.ALL_TO_LOWER_SPECIAL;
      case 0x02: return Encoding.LOWER_UPPER_DIGIT_SPECIAL;
      default: return Encoding.UTF_8;
    }
  }
}

const SMALL_NUM_FIELDS_THRESHOLD = 0b11111;
const REGISTER_BY_NAME_FLAG = 0b100000;
const FIELD_NAME_SIZE_THRESHOLD = 0b1111;

const pkgNameEncoding = [Encoding.UTF_8, Encoding.ALL_TO_LOWER_SPECIAL, Encoding.LOWER_UPPER_DIGIT_SPECIAL];
const fieldNameEncoding = [Encoding.UTF_8, Encoding.ALL_TO_LOWER_SPECIAL, Encoding.LOWER_UPPER_DIGIT_SPECIAL];
const typeNameEncoding = [Encoding.UTF_8, Encoding.ALL_TO_LOWER_SPECIAL, Encoding.LOWER_UPPER_DIGIT_SPECIAL, Encoding.FIRST_TO_LOWER_SPECIAL];

export class TypeMeta {
  private constructor(private fields: FieldInfo[], private type: {
    typeId: number;
    typeName: string;
    namespace: string;
    userTypeId: number;
  }) {}

  public getTypeName() { return this.type.typeName; }
  public getNs() { return this.type.namespace; }
  public getTypeId() { return this.type.typeId; }
  public getFieldInfo() { return this.fields; }
  public getUserTypeId() { return this.type.userTypeId; }

  public getHash(): any {
    const writer = new BinaryWriter({});
    this.writeFieldsInfo(writer, this.fields);
    const buffer = writer.dump();
    const hash = x64hash128(buffer, 47);
    return BigInt(hash.getUint32(0, false)) << 32n | BigInt(hash.getUint32(4, false));
  }

  static fromTypeInfo(typeInfo: StructTypeInfo) {
    const structTypeInfo = typeInfo;
    let fieldInfos = Object.entries(typeInfo.options.props!).map(([fieldName, info]) => {
      let fieldTypeId = info.typeId;
      if (fieldTypeId === TypeId.NAMED_ENUM) {
        fieldTypeId = TypeId.ENUM;
      } else if (fieldTypeId === TypeId.NAMED_UNION || fieldTypeId === TypeId.TYPED_UNION) {
        fieldTypeId = TypeId.UNION;
      }
      const { trackingRef, nullable } = structTypeInfo.options.fieldInfo?.[fieldName] || {};
      return new FieldInfo(
        fieldName,
        fieldTypeId,
        info.userTypeId,
        trackingRef,
        nullable,
        info.options,
      );
    });
    fieldInfos = TypeMeta.groupFieldsByType(fieldInfos);
    return new TypeMeta(fieldInfos, {
      typeId: typeInfo.typeId,
      namespace: typeInfo.namespace,
      typeName: typeInfo.typeName,
      userTypeId: typeInfo.userTypeId ?? -1,
    });
  }

  static fromBytes(reader: BinaryReader): TypeMeta {
    const headerLong = reader.int64();
    let metaSize = Number(headerLong & BigInt(META_SIZE_MASKS));
    if (metaSize === META_SIZE_MASKS) {
      metaSize += reader.varUInt32();
    }

    const classHeader = reader.uint8();
    let numFields = classHeader & SMALL_NUM_FIELDS_THRESHOLD;
    if (numFields === SMALL_NUM_FIELDS_THRESHOLD) {
      numFields += reader.varUInt32();
    }

    let typeId: number;
    let userTypeId = -1;
    let namespace = "";
    let typeName = "";

    if (classHeader & REGISTER_BY_NAME_FLAG) {
      namespace = this.readPkgName(reader);
      typeName = this.readTypeName(reader);
      typeId = TypeId.NAMED_STRUCT;
    } else {
      typeId = reader.uint8();
      userTypeId = reader.varUInt32();
    }

    const fields: FieldInfo[] = [];
    for (let i = 0; i < numFields; i++) {
      fields.push(this.readFieldInfo(reader));
    }

    return new TypeMeta(fields, { typeId, namespace, typeName, userTypeId });
  }

  private static readFieldInfo(reader: BinaryReader): FieldInfo {
    const header = reader.int8();
    const encodingFlags = (header >>> 6) & 0b11;
    let size = (header >>> 2) & 0b1111;
    if (size === FIELD_NAME_SIZE_THRESHOLD) {
      size += reader.readVarUint32Small7();
    }

    const { typeId, userTypeId, trackingRef, nullable, options } = this.readTypeId(reader, true);

    let fieldName: string;
    if (encodingFlags === 3) {
      fieldName = size.toString();
    } else {
      const encoding = FieldInfo.u8ToEncoding(encodingFlags);
      fieldName = fieldDecoder.decode(reader, size + 1, encoding);
    }

    return new FieldInfo(fieldName, typeId, userTypeId, trackingRef, nullable, options);
  }

  private static readTypeId(reader: BinaryReader, readFlag = false): InnerFieldInfo {
    const options: InnerFieldInfoOptions = {};
    let nullable = false;
    let trackingRef = false;
    let typeId: number;
    
    if (readFlag) {
      typeId = reader.readVarUint32Small7();
      nullable = Boolean(typeId & 0b10);
      trackingRef = Boolean(typeId & 0b1);
      typeId = typeId >> 2;
    } else {
      typeId = reader.uint8();
    }

    if (typeId === TypeId.NAMED_ENUM) {
      typeId = TypeId.ENUM;
    } else if (typeId === TypeId.NAMED_UNION || typeId === TypeId.TYPED_UNION) {
      typeId = TypeId.UNION;
    }
    
    this.readNestedTypeInfo(reader, typeId, options);
    return { typeId, userTypeId: -1, nullable, trackingRef, options };
  }

  private static readNestedTypeInfo(reader: BinaryReader, typeId: number, options: InnerFieldInfoOptions) {
    const baseTypeId = typeId & 0xff;
    switch (baseTypeId) {
      case TypeId.LIST:
      case TypeId.SET:
        options.inner = this.readTypeId(reader, true);
        break;
      case TypeId.MAP:
        options.key = this.readTypeId(reader, true);
        options.value = this.readTypeId(reader, true);
        break;
    }
  }

  private static readPkgName(reader: BinaryReader): string {
    return this.readName(reader, pkgNameEncoding, pkgDecoder);
  }

  private static readTypeName(reader: BinaryReader): string {
    return this.readName(reader, typeNameEncoding, typeNameDecoder);
  }

  private static readName(reader: BinaryReader, encodings: Encoding[], decoder: MetaStringDecoder): string {
    const header = reader.uint8();
    const encodingIndex = header & 0b11;
    let size = (header >> 2) & 0b111111;
    if (size === BIG_NAME_THRESHOLD) {
      size += reader.readVarUint32Small7();
    }
    return decoder.decode(reader, size, encodings[encodingIndex]);
  }

  toBytes() {
    const writer = new BinaryWriter({});
    writer.uint8(0); 
    let currentClassHeader = Math.min(this.fields.length, SMALL_NUM_FIELDS_THRESHOLD);

    if (this.fields.length >= SMALL_NUM_FIELDS_THRESHOLD) {
      writer.varUInt32(this.fields.length - SMALL_NUM_FIELDS_THRESHOLD);
    }

    if (!TypeId.isNamedType(this.type.typeId)) {
      writer.uint8(this.type.typeId);
      if (this.type.userTypeId === undefined || this.type.userTypeId === -1) {
        throw new Error(`userTypeId required for typeId ${this.type.typeId}`);
      }
      writer.varUInt32(this.type.userTypeId);
    } else {
      currentClassHeader |= REGISTER_BY_NAME_FLAG;
      this.writePkgName(writer, this.type.namespace);
      this.writeTypeName(writer, this.type.typeName);
    }

    writer.setUint8Position(0, currentClassHeader);
    this.writeFieldsInfo(writer, this.fields);

    const buffer = writer.dump();
    return this.prependHeader(buffer, false, this.fields.length > 0);
  }

  private writeFieldsInfo(writer: BinaryWriter, fields: FieldInfo[]) {
    for (const fieldInfo of fields) {
      const fieldName = this.lowerCamelToLowerUnderscore(fieldInfo.fieldName);
      const metaString = fieldEncoder.encodeByEncodings(fieldName, fieldNameEncoding);
      const encoded = metaString.getBytes();
      const encodingFlags = fieldNameEncoding.indexOf(metaString.getEncoding());
      const size = encoded.length - 1;

      let header = (encodingFlags << 6);
      if (size >= FIELD_NAME_SIZE_THRESHOLD) {
        header |= 0b00111100;
        writer.int8(header);
        writer.writeVarUint32Small7(size - FIELD_NAME_SIZE_THRESHOLD);
      } else {
        header |= (size << 2);
        writer.int8(header);
      }

      FieldInfo.writeTypeId(writer, {
          typeId: fieldInfo.typeId,
          userTypeId: fieldInfo.userTypeId,
          nullable: fieldInfo.nullable,
          trackingRef: fieldInfo.trackingRef,
          options: fieldInfo.options
      });
      writer.buffer(encoded);
    }
  }

  writePkgName(writer: BinaryWriter, pkg: string) {
    const pkgMetaString = pkgEncoder.encodeByEncodings(pkg, pkgNameEncoding);
    this.writeName(writer, pkgMetaString.getBytes(), pkgNameEncoding.indexOf(pkgMetaString.getEncoding()));
  }

  writeTypeName(writer: BinaryWriter, typeName: string) {
    const metaString = typeNameEncoder.encodeByEncodings(typeName, typeNameEncoding);
    this.writeName(writer, metaString.getBytes(), typeNameEncoding.indexOf(metaString.getEncoding()));
  }

  writeName(writer: BinaryWriter, encoded: Uint8Array, encoding: number) {
    const bigSize = encoded.length >= BIG_NAME_THRESHOLD;
    if (bigSize) {
      writer.uint8((BIG_NAME_THRESHOLD << 2) | encoding);
      writer.writeVarUint32Small7(encoded.length - BIG_NAME_THRESHOLD);
    } else {
      writer.uint8((encoded.length << 2) | encoding);
    }
    writer.buffer(encoded);
  }

  private lowerCamelToLowerUnderscore(str: string): string {
    return str.replace(/[A-Z]/g, letter => `_${letter.toLowerCase()}`);
  }

  private prependHeader(buffer: Uint8Array, isCompressed: boolean, hasFieldsMeta: boolean): Uint8Array {
    const metaSize = buffer.length;
    const hashValue: any = this.getHash(); 
    let header = hashValue;
    header = header << BigInt(64 - NUM_HASH_BITS);
    header = header >= 0n ? header : -header; 

    if (isCompressed) header |= COMPRESS_META_FLAG;
    if (hasFieldsMeta) header |= HAS_FIELDS_META_FLAG;
    header |= BigInt(Math.min(metaSize, META_SIZE_MASKS));

    const writer = new BinaryWriter({});
    writer.int64(header);
    if (metaSize > META_SIZE_MASKS) {
      writer.varUInt32(metaSize - META_SIZE_MASKS);
    }
    writer.buffer(buffer);
    return writer.dump();
  }

  static groupFieldsByType<T extends { fieldName: string; nullable?: boolean; typeId: number }>(typeInfos: Array<T>): Array<T> {
    const primitiveFields: Array<T> = [];
    const nullablePrimitiveFields: Array<T> = [];
    const internalTypeFields: Array<T> = [];
    const listFields: Array<T> = [];
    const setFields: Array<T> = [];
    const mapFields: Array<T> = [];
    const otherFields: Array<T> = [];

    const toSnakeCase = (name: string) => name.replace(/[A-Z]/g, letter => `_${letter.toLowerCase()}`);

    for (const typeInfo of typeInfos) {
      const typeId = typeInfo.typeId;
      if (isPrimitiveTypeId(typeId)) {
        if (typeInfo.nullable) nullablePrimitiveFields.push(typeInfo);
        else primitiveFields.push(typeInfo);
      } else if (isInternalTypeId(typeId)) {
        internalTypeFields.push(typeInfo);
      } else if (typeId === TypeId.LIST) {
        listFields.push(typeInfo);
      } else if (typeId === TypeId.SET) {
        setFields.push(typeInfo);
      } else if (typeId === TypeId.MAP) {
        mapFields.push(typeInfo);
      } else {
        otherFields.push(typeInfo);
      }
    }

    const nameSorter = (a: T, b: T) => toSnakeCase(a.fieldName).localeCompare(toSnakeCase(b.fieldName));
    const numericSorter = (a: T, b: T) => {
        const sizea = getPrimitiveTypeSize(a.typeId), sizeb = getPrimitiveTypeSize(b.typeId);
        return sizea !== sizeb ? sizeb - sizea : (a.typeId !== b.typeId ? b.typeId - a.typeId : nameSorter(a, b));
    };

    primitiveFields.sort(numericSorter);
    nullablePrimitiveFields.sort(numericSorter);
    internalTypeFields.sort((a, b) => a.typeId !== b.typeId ? b.typeId - a.typeId : nameSorter(a, b));
    [listFields, setFields, mapFields].forEach(g => g.sort(nameSorter));
    otherFields.sort((a, b) => a.typeId !== b.typeId ? b.typeId - a.typeId : nameSorter(a, b));

    return [...primitiveFields, ...nullablePrimitiveFields, ...internalTypeFields, ...listFields, ...setFields, ...mapFields, ...otherFields];
  }
}
