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

// Constants from Java implementation
const COMPRESS_META_FLAG = 1n << 63n;
const HAS_FIELDS_META_FLAG = 1n << 62n;
const META_SIZE_MASKS = 0xFFF; // 22 bits
const NUM_HASH_BITS = 41;
const BIG_NAME_THRESHOLD = 0b111111;

const PRIMITIVE_TYPE_IDS = [
  TypeId.BOOL, TypeId.INT8, TypeId.INT16, TypeId.INT32, TypeId.VARINT32,
  TypeId.INT64, TypeId.VARINT64, TypeId.TAGGED_INT64, TypeId.UINT8,
  TypeId.UINT16, TypeId.UINT32, TypeId.VAR_UINT32, TypeId.UINT64,
  TypeId.VAR_UINT64, TypeId.TAGGED_UINT64, TypeId.FLOAT16,
  TypeId.FLOAT32, TypeId.FLOAT64,
];

export const isPrimitiveTypeId = (typeId: number): boolean => {
  return PRIMITIVE_TYPE_IDS.includes(typeId as any);
};

export const refTrackingAbleTypeId = (typeId: number): boolean => {
  return PRIMITIVE_TYPE_IDS.includes(typeId as any) || [TypeId.DURATION, TypeId.DATE, TypeId.TIMESTAMP].includes(typeId as any);
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
    TypeId.FLOAT16_ARRAY,
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
      return 1;
    case TypeId.INT8:
      return 1;
    case TypeId.INT16:
      return 2;
    case TypeId.INT32:
      return 4;
    case TypeId.VARINT32:
      return 4;
    case TypeId.INT64:
      return 8;
    case TypeId.VARINT64:
      return 8;
    case TypeId.TAGGED_INT64:
      return 8;
    case TypeId.FLOAT16:
      return 2;
    case TypeId.FLOAT32:
      return 4;
    case TypeId.FLOAT64:
      return 8;
    case TypeId.UINT8:
      return 1;
    case TypeId.UINT16:
      return 2;
    case TypeId.UINT32:
      return 4;
    case TypeId.VAR_UINT32:
      return 4;
    case TypeId.UINT64:
      return 8;
    case TypeId.VAR_UINT64:
      return 8;
    case TypeId.TAGGED_UINT64:
      return 8;
    default:
      return 0;
  }
}

type InnerFieldInfoOptions = { key?: InnerFieldInfo; value?: InnerFieldInfo; inner?: InnerFieldInfo };
interface InnerFieldInfo {
  typeId: number;
  trackingRef: boolean;
  nullable: boolean;
  options?: InnerFieldInfoOptions;
}
class FieldInfo {
  constructor(
    public fieldName: string,
    public typeId: number,
    public trackingRef = false,
    public nullable = false,
    public options: InnerFieldInfoOptions = {},
  ) {
  }

  getFieldName() {
    return this.fieldName;
  }

  getTypeId() {
    return this.typeId;
  }

  hasFieldId() {
    return false; // todo not impl yet.
  }

  getFieldId() {
    return 0;
  }

  static writeTypeId(writer: BinaryWriter, typeInfo: InnerFieldInfo, writeFlags = false) {
    let { typeId } = typeInfo;
    const { trackingRef, nullable } = typeInfo;
    if (writeFlags) {
      typeId = (typeId << 2);
      if (nullable) {
        typeId |= 0b10;
      }
      if (trackingRef) {
        typeId |= 0b1;
      }
    }
    writer.writeVarUint32Small7(typeId);
    switch (typeInfo.typeId & 0xff) {
      case TypeId.LIST:
        FieldInfo.writeTypeId(writer, typeInfo.options!.inner!, true);
        break;
      case TypeId.SET:
        FieldInfo.writeTypeId(writer, typeInfo.options!.key!, true);
        break;
      case TypeId.MAP:
        FieldInfo.writeTypeId(writer, typeInfo.options!.key!, true);
        FieldInfo.writeTypeId(writer, typeInfo.options!.value!, true);
        break;
      default:
        break;
    }
  }

  static u8ToEncoding(value: number) {
    switch (value) {
      case 0x00:
        return Encoding.UTF_8;
      case 0x01:
        return Encoding.ALL_TO_LOWER_SPECIAL;
      case 0x02:
        return Encoding.LOWER_UPPER_DIGIT_SPECIAL;
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
  }) {
  }

  getHash() {
    return this.fields.length;
  }

  static fromTypeInfo(typeInfo: StructTypeInfo) {
    let fieldInfo = Object.entries(typeInfo.options.props!).map(([fieldName, typeInfo]) => {
      return new FieldInfo(fieldName, typeInfo.typeId, false, false, typeInfo.options);
    });
    fieldInfo = TypeMeta.groupFieldsByType(fieldInfo);
    return new TypeMeta(fieldInfo, {
      typeId: typeInfo.typeId,
      namespace: typeInfo.namespace,
      typeName: typeInfo.typeName,
    });
  }

  static fromBytes(reader: BinaryReader): TypeMeta {
    // Read header with hash and flags
    const headerLong = reader.int64();
    // todo support compress.
    // const isCompressed = (headerLong & COMPRESS_META_FLAG) !== 0n;
    // const hasFieldsMeta = (headerLong & HAS_FIELDS_META_FLAG) !== 0n;
    let metaSize = Number(headerLong & BigInt(META_SIZE_MASKS));

    if (metaSize === META_SIZE_MASKS) {
      metaSize += reader.varUInt32();
    }

    // Read class header
    const classHeader = reader.uint8();
    let numFields = classHeader & SMALL_NUM_FIELDS_THRESHOLD;

    if (numFields === SMALL_NUM_FIELDS_THRESHOLD) {
      numFields += reader.varUInt32();
    }

    let typeId: number;
    let namespace = "";
    let typeName = "";

    if (classHeader & REGISTER_BY_NAME_FLAG) {
      // Read namespace and type name
      namespace = this.readPkgName(reader);
      typeName = this.readTypeName(reader);
      typeId = TypeId.NAMED_STRUCT; // Default for named types
    } else {
      typeId = reader.varUInt32();
    }

    // Read fields
    const fields: FieldInfo[] = [];
    for (let i = 0; i < numFields; i++) {
      const fieldInfo = this.readFieldInfo(reader);
      fields.push(fieldInfo);
    }

    // Create a basic TypeInfo for the decoded type
    const typeInfo = {
      typeId,
      namespace,
      typeName,
    };

    return new TypeMeta(fields, typeInfo);
  }

  private static readFieldInfo(reader: BinaryReader): FieldInfo {
    const header = reader.int8();
    const encodingFlags = (header >>> 6) & 0b11;
    let size = (header >>> 2) & 0b1111;
    const bigSize = size === FIELD_NAME_SIZE_THRESHOLD;

    if (bigSize) {
      size += reader.readVarUint32Small7();
    }

    // Read type ID
    const { typeId, trackingRef, nullable, options } = this.readTypeId(reader);

    let fieldName: string;
    if (encodingFlags === 3) {
      // TAG_ID encoding - field name is the size value
      fieldName = size.toString();
    } else {
      // Read field name
      const encoding = FieldInfo.u8ToEncoding(encodingFlags);
      fieldName = fieldDecoder.decode(reader, size + 1, encoding || Encoding.UTF_8);
    }

    return new FieldInfo(fieldName, typeId, trackingRef, nullable, options);
  }

  private static readTypeId(reader: BinaryReader, readFlag = false): InnerFieldInfo {
    const options: InnerFieldInfoOptions = {};
    let typeId = reader.readVarUint32Small7();
    let nullable = false;
    let trackingRef = false;
    if (readFlag) {
      nullable = Boolean(typeId & 0b10);
      trackingRef = Boolean(typeId & 0b1);
      typeId = typeId >> 2;
    }

    const baseTypeId = typeId & 0xff;

    // Handle nested type IDs for collections
    switch (baseTypeId) {
      case TypeId.LIST:
        options.inner = this.readTypeId(reader, true);
        break;
      case TypeId.SET:
        // Read inner type
        options.key = this.readTypeId(reader, true);
        break;
      case TypeId.MAP:
        options.key = this.readTypeId(reader, true);
        options.value = this.readTypeId(reader, true);
        break;
      default:
        break;
    }

    return { typeId, nullable, trackingRef, options };
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

    const encoding = encodings[encodingIndex];
    return decoder.decode(reader, size, encoding);
  }

  getTypeId(): number {
    return this.type.typeId;
  }

  getNs(): string {
    return this.type.namespace;
  }

  getTypeName(): string {
    return this.type.typeName;
  }

  getFieldInfo(): FieldInfo[] {
    return this.fields;
  }

  toBytes() {
    const writer = new BinaryWriter({});
    writer.uint8(-1); // placeholder for header, update later
    let currentClassHeader = this.fields.length;

    if (this.fields.length >= SMALL_NUM_FIELDS_THRESHOLD) {
      currentClassHeader = SMALL_NUM_FIELDS_THRESHOLD;
      writer.varUInt32(this.fields.length - SMALL_NUM_FIELDS_THRESHOLD);
    }

    if (!TypeId.isNamedType(this.type.typeId)) {
      writer.varUInt32(this.type.typeId);
    } else {
      currentClassHeader |= REGISTER_BY_NAME_FLAG;
      const ns = this.type.namespace;
      const typename = this.type.typeName;
      this.writePkgName(writer, ns);
      this.writeTypeName(writer, typename);
    }

    // Update header at position 0
    writer.setUint8Position(0, currentClassHeader);

    // Write fields info
    this.writeFieldsInfo(writer, this.fields);

    const buffer = writer.dump();

    // For now, skip compression and just add header
    return this.prependHeader(buffer, false, this.fields.length > 0);
  }

  writePkgName(writer: BinaryWriter, pkg: string) {
    const pkgMetaString = pkgEncoder.encodeByEncodings(pkg, pkgNameEncoding);
    const encoded = pkgMetaString.getBytes();
    const encoding = pkgMetaString.getEncoding();
    this.writeName(writer, encoded, pkgNameEncoding.indexOf(encoding));
  }

  writeTypeName(writer: BinaryWriter, typeName: string) {
    const metaString = typeNameEncoder.encodeByEncodings(typeName, typeNameEncoding);
    const encoded = metaString.getBytes();
    const encoding = metaString.getEncoding();
    this.writeName(writer, encoded, typeNameEncoding.indexOf(encoding));
  }

  writeName(writer: BinaryWriter, encoded: Uint8Array, encoding: number) {
    const bigSize = encoded.length >= BIG_NAME_THRESHOLD;
    if (bigSize) {
      const header = (BIG_NAME_THRESHOLD << 2) | encoding;
      writer.uint8(header);
      writer.writeVarUint32Small7(encoded.length - BIG_NAME_THRESHOLD);
    } else {
      const header = (encoded.length << 2) | encoding;
      writer.uint8(header);
    }
    writer.buffer(encoded);
  }

  writeFieldName(writer: BinaryWriter, fieldName: string) {
    const name = this.lowerCamelToLowerUnderscore(fieldName);
    const metaString = fieldEncoder.encodeByEncodings(name, fieldNameEncoding);
    const encoded = metaString.getBytes();
    const encoding = fieldNameEncoding.indexOf(metaString.getEncoding());
    this.writeName(writer, encoded, encoding);
  }

  private writeFieldsInfo(writer: BinaryWriter, fields: FieldInfo[]) {
    for (const fieldInfo of fields) {
      // header: 2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag
      let header = fieldInfo.trackingRef ? 1 : 0; // Simplified - no ref tracking or nullability for now
      header |= fieldInfo.nullable ? 0b10 : 0b00;
      let size: number;
      let encodingFlags: number;
      let encoded: Uint8Array | null = null;

      if (fieldInfo.hasFieldId()) {
        size = fieldInfo.getFieldId();
        encodingFlags = 3; // TAG_ID encoding
      } else {
        // Convert camelCase to snake_case for xlang compatibility
        const fieldName = this.lowerCamelToLowerUnderscore(fieldInfo.getFieldName());
        const metaString = fieldEncoder.encodeByEncodings(fieldName, fieldNameEncoding);
        encodingFlags = fieldNameEncoding.indexOf(metaString.getEncoding());
        encoded = metaString.getBytes();
        size = encoded.length - 1;
      }

      header |= (encodingFlags << 6);
      const bigSize = size >= FIELD_NAME_SIZE_THRESHOLD;

      if (bigSize) {
        header |= 0b00111100;
        writer.int8(header);
        writer.writeVarUint32Small7(size - FIELD_NAME_SIZE_THRESHOLD);
      } else {
        header |= (size << 2);
        writer.int8(header);
      }

      FieldInfo.writeTypeId(writer, fieldInfo);
      // Write field name if not using field ID
      if (!fieldInfo.hasFieldId() && encoded) {
        writer.buffer(encoded);
      }
    }
  }

  private lowerCamelToLowerUnderscore(str: string): string {
    return str.replace(/[A-Z]/g, letter => `_${letter.toLowerCase()}`);
  }

  private prependHeader(buffer: Uint8Array, isCompressed: boolean, hasFieldsMeta: boolean): Uint8Array {
    const metaSize = buffer.length;
    const hash = x64hash128(buffer, 47);
    let header = BigInt(hash.getUint32(0, false)) << 32n | BigInt(hash.getUint32(4, false));
    header = header << BigInt(64 - NUM_HASH_BITS);
    header = header >= 0n ? header : -header; // Math.abs for bigint

    if (isCompressed) {
      header |= COMPRESS_META_FLAG;
    }
    if (hasFieldsMeta) {
      header |= HAS_FIELDS_META_FLAG;
    }
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

    const toSnakeCase = (name: string) => {
      const result = [];
      const chars = Array.from(name);

      for (let i = 0; i < chars.length; i++) {
        const c = chars[i];
        if (c >= "A" && c <= "Z") {
          if (i > 0) {
            const prevUpper = chars[i - 1] >= "A" && chars[i - 1] <= "Z";
            const nextUpperOrEnd = i + 1 >= chars.length || (chars[i + 1] >= "A" && chars[i + 1] <= "Z");

            if (!prevUpper || !nextUpperOrEnd) {
              result.push("_");
            }
          }
          result.push(c.toLowerCase());
        } else {
          result.push(c);
        }
      }
      return result.join("");
    };

    for (const typeInfo of typeInfos) {
      const typeId = typeInfo.typeId;

      if (isPrimitiveTypeId(typeId) && typeInfo.nullable) {
        nullablePrimitiveFields.push(typeInfo);
        continue;
      }

      // Check if it's a primitive type
      if (isPrimitiveTypeId(typeId)) {
        primitiveFields.push(typeInfo);
        continue;
      }

      // Categorize based on type_id
      if (isInternalTypeId(typeId)) {
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

    // Sort functions
    const numericSorter = (a: T, b: T) => {
      // Sort by type_id descending, then by name ascending

      const sizea = getPrimitiveTypeSize(a.typeId);
      const sizeb = getPrimitiveTypeSize(b.typeId);
      if (sizea !== sizeb) {
        return sizeb - sizea;
      }
      if (a.typeId !== b.typeId) {
        return b.typeId - a.typeId;
      }
      return nameSorter(a, b);
    };

    const typeIdThenNameSorter = (a: T, b: T) => {
      if (a.typeId !== b.typeId) {
        return b.typeId - a.typeId;
      }
      return nameSorter(a, b);
    };

    const nameSorter = (a: T, b: T) => {
      return toSnakeCase(a.fieldName).localeCompare(toSnakeCase(b.fieldName));
    };

    // Sort each group
    primitiveFields.sort(numericSorter);
    nullablePrimitiveFields.sort(numericSorter);
    internalTypeFields.sort(typeIdThenNameSorter);
    listFields.sort(nameSorter);
    setFields.sort(nameSorter);
    mapFields.sort(nameSorter);
    otherFields.sort(typeIdThenNameSorter);

    return [
      primitiveFields,
      nullablePrimitiveFields,
      internalTypeFields,
      listFields,
      setFields,
      mapFields,
      otherFields,
    ].flat();
  }
}
