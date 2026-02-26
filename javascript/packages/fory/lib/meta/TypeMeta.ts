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
import { TypeInfo } from "../typeInfo";
import { TypeId } from "../type";
import { x64hash128 } from "../murmurHash3";
import { fromString } from "../platformBuffer";

const fieldEncoder = new MetaStringEncoder("$", "_");
const fieldDecoder = new MetaStringDecoder("$", "_");
const pkgEncoder = new MetaStringEncoder(".", "_");
const pkgDecoder = new MetaStringDecoder(".", "_");
const typeNameEncoder = new MetaStringEncoder("$", ".");
const typeNameDecoder = new MetaStringDecoder("$", ".");

// Constants from Java implementation
const COMPRESS_META_FLAG = 1n << 63n;
const HAS_FIELDS_META_FLAG = 1n << 62n;
const META_SIZE_MASKS = 0xFF; // 22 bits
const NUM_HASH_BITS = 41;
const BIG_NAME_THRESHOLD = 0b111111;

const PRIMITIVE_TYPE_IDS = [
  TypeId.BOOL, TypeId.INT8, TypeId.INT16, TypeId.INT32, TypeId.VARINT32,
  TypeId.INT64, TypeId.VARINT64, TypeId.TAGGED_INT64, TypeId.UINT8,
  TypeId.UINT16, TypeId.UINT32, TypeId.VAR_UINT32, TypeId.UINT64,
  TypeId.VAR_UINT64, TypeId.TAGGED_UINT64, TypeId.FLOAT8, TypeId.FLOAT16,
  TypeId.BFLOAT16, TypeId.FLOAT32, TypeId.FLOAT64,
];

export const isPrimitiveTypeId = (typeId: number): boolean => {
  return PRIMITIVE_TYPE_IDS.includes(typeId as any);
};

export const refTrackingUnableTypeId = (typeId: number): boolean => {
  return PRIMITIVE_TYPE_IDS.includes(typeId as any) || [TypeId.DURATION, TypeId.DATE, TypeId.TIMESTAMP, TypeId.STRING].includes(typeId as any);
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
    case TypeId.FLOAT8:
      return 1;
    case TypeId.FLOAT16:
      return 2;
    case TypeId.BFLOAT16:
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

export type InnerFieldInfoOptions = { key?: InnerFieldInfo; value?: InnerFieldInfo; inner?: InnerFieldInfo };
export interface InnerFieldInfo {
  typeId: number;
  userTypeId: number;
  trackingRef?: boolean;
  nullable?: boolean;
  options?: InnerFieldInfoOptions;
  fieldId?: number;
}
export class FieldInfo {
  constructor(
    public fieldName: string,
    public typeId: number,
    public userTypeId = -1,
    public trackingRef = false,
    public nullable = false,
    public options: InnerFieldInfoOptions = {},
    public fieldId?: number
  ) {
  }

  getFieldName() {
    return this.fieldName;
  }

  getTypeId() {
    return this.typeId;
  }

  getUserTypeId() {
    return this.userTypeId;
  }

  hasFieldId() {
    return typeof this.fieldId === "number";
  }

  getFieldId() {
    return this.fieldId;
  }

  static writeTypeId(writer: BinaryWriter, typeInfo: InnerFieldInfo, writeFlags = false) {
    let { typeId } = typeInfo;
    if (typeId === TypeId.NAMED_ENUM) {
      typeId = TypeId.ENUM;
    } else if (typeId === TypeId.NAMED_UNION || typeId === TypeId.TYPED_UNION) {
      typeId = TypeId.UNION;
    }
    const { trackingRef, nullable } = typeInfo;
    if (writeFlags) {
      typeId = (typeId << 2);
      if (nullable) {
        typeId |= 0b10;
      }
      if (trackingRef) {
        typeId |= 0b1;
      }
      writer.writeVarUint32Small7(typeId);
    } else {
      writer.writeUint8(typeId);
    }
    switch (typeInfo.typeId) {
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
    userTypeId: number;
  }) {
  }

  getHash() {
    return this.fields.length;
  }

  computeStructFingerprint(fields: FieldInfo[]) {
    let fieldInfos = [];
    for (const field of fields) {
      let typeId = field.getTypeId();
      if (TypeId.userDefinedType(typeId)) {
        typeId = TypeId.UNKNOWN;
      }
      let fieldIdentifier = "";
      if (field.getFieldId()) {
        fieldIdentifier = `${field.getFieldId()}`;
      } else {
        fieldIdentifier = TypeMeta.toSnakeCase(field.getFieldName());
      }
      const ref = field.trackingRef ? "1" : "0";
      const nullable = field.nullable ? "1" : "0";
      fieldInfos.push([fieldIdentifier, `${typeId}`, ref, nullable]);
    }
    fieldInfos = fieldInfos.sort((a, b) => a[0].localeCompare(b[0]));
    let result = "";
    for (const fieldInfo of fieldInfos) {
      result += [fieldInfo[0], fieldInfo[1], fieldInfo[2], fieldInfo[3]].join(",");
      result += ";";
    }
    return result;
  }

  computeStructHash() {
    const fields = TypeMeta.groupFieldsByType(this.fields);
    const fingerprint = this.computeStructFingerprint(fields);
    const bytes = fromString(fingerprint);
    const hashLong = x64hash128(bytes, 47).getBigInt64(0);
    const result = Number(BigInt.asIntN(32, hashLong));
    return result;
  }

  static fromTypeInfo(typeInfo: TypeInfo) {
    let fieldInfo: FieldInfo[] = [];
    if (TypeId.structType(typeInfo.typeId)) {
      const structTypeInfo = typeInfo;
      fieldInfo = Object.entries(structTypeInfo.options!.props!).map(([fieldName, typeInfo]) => {
        let fieldTypeId = typeInfo.typeId;
        if (fieldTypeId === TypeId.NAMED_ENUM) {
          fieldTypeId = TypeId.ENUM;
        } else if (fieldTypeId === TypeId.NAMED_UNION || fieldTypeId === TypeId.TYPED_UNION) {
          fieldTypeId = TypeId.UNION;
        }
        const { trackingRef, nullable, id, userTypeId, options } = typeInfo;
        return new FieldInfo(
          fieldName,
          fieldTypeId,
          userTypeId,
          trackingRef,
          nullable,
          options!,
          id
        );
      });
    }
    fieldInfo = TypeMeta.groupFieldsByType(fieldInfo);

    return new TypeMeta(fieldInfo, {
      typeId: typeInfo.typeId,
      namespace: typeInfo.namespace,
      typeName: typeInfo.typeName,
      userTypeId: typeInfo.userTypeId ?? -1,
    });
  }

  static fromBytes(reader: BinaryReader): TypeMeta {
    // Read header with hash and flags
    const headerLong = reader.readInt64();
    // todo support compress.
    // const isCompressed = (headerLong & COMPRESS_META_FLAG) !== 0n;
    // const hasFieldsMeta = (headerLong & HAS_FIELDS_META_FLAG) !== 0n;
    let metaSize = Number(headerLong & BigInt(META_SIZE_MASKS));

    if (metaSize === META_SIZE_MASKS) {
      metaSize += reader.readVarUInt32();
    }

    // Read class header
    const classHeader = reader.readUint8();
    let numFields = classHeader & SMALL_NUM_FIELDS_THRESHOLD;

    if (numFields === SMALL_NUM_FIELDS_THRESHOLD) {
      numFields += reader.readVarUInt32();
    }

    let typeId: number;
    let userTypeId = -1;
    let namespace = "";
    let typeName = "";

    if (classHeader & REGISTER_BY_NAME_FLAG) {
      // Read namespace and type name
      namespace = this.readPkgName(reader);
      typeName = this.readTypeName(reader);
      typeId = TypeId.NAMED_STRUCT; // Default for named types
    } else {
      typeId = reader.readUint8();
      userTypeId = reader.readVarUInt32();
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
      userTypeId,
    };

    return new TypeMeta(fields, typeInfo);
  }

  private static readFieldInfo(reader: BinaryReader): FieldInfo {
    const header = reader.readInt8();
    const encodingFlags = (header >>> 6) & 0b11;
    let size = (header >>> 2) & 0b1111;
    const bigSize = size === FIELD_NAME_SIZE_THRESHOLD;
    const nullable = (header & 0b10) > 0;
    const trackingRef = (header & 0b1) > 0;
    if (bigSize) {
      size += reader.readVarUint32Small7();
    }

    // Read type ID
    const { typeId, userTypeId, options, fieldId } = this.readTypeId(reader);

    let fieldName: string;
    if (encodingFlags === 3) {
      // TAG_ID encoding - field name is the size value
      fieldName = size.toString();
    } else {
      // Read field name
      const encoding = FieldInfo.u8ToEncoding(encodingFlags);

      fieldName = fieldDecoder.decode(reader, size + 1, encoding || Encoding.UTF_8);
      fieldName = TypeMeta.lowerUnderscoreToLowerCamelCase(fieldName);
    }

    return new FieldInfo(fieldName, typeId, userTypeId, trackingRef, nullable, options, fieldId);
  }

  private static readTypeId(reader: BinaryReader, readFlag = false): InnerFieldInfo {
    const options: InnerFieldInfoOptions = {};
    let nullable = false;
    let trackingRef = false;
    if (readFlag) {
      let typeId = reader.readVarUint32Small7();
      nullable = Boolean(typeId & 0b10);
      trackingRef = Boolean(typeId & 0b1);
      typeId = typeId >> 2;
      if (typeId === TypeId.NAMED_ENUM) {
        typeId = TypeId.ENUM;
      } else if (typeId === TypeId.NAMED_UNION || typeId === TypeId.TYPED_UNION) {
        typeId = TypeId.UNION;
      }
      this.readNestedTypeInfo(reader, typeId, options);
      return { typeId, userTypeId: -1, nullable, trackingRef, options };
    }
    let typeId = reader.readUint8();
    if (typeId === TypeId.NAMED_ENUM) {
      typeId = TypeId.ENUM;
    } else if (typeId === TypeId.NAMED_UNION || typeId === TypeId.TYPED_UNION) {
      typeId = TypeId.UNION;
    }
    this.readNestedTypeInfo(reader, typeId, options);
    return { typeId, userTypeId: -1, nullable, trackingRef, options };
  }

  private static readNestedTypeInfo(reader: BinaryReader, typeId: number, options: InnerFieldInfoOptions) {
    switch (typeId) {
      case TypeId.LIST:
        options.inner = this.readTypeId(reader, true);
        break;
      case TypeId.SET:
        options.key = this.readTypeId(reader, true);
        break;
      case TypeId.MAP:
        options.key = this.readTypeId(reader, true);
        options.value = this.readTypeId(reader, true);
        break;
      default:
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
    const header = reader.readUint8();
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

  getUserTypeId(): number {
    return this.type.userTypeId;
  }

  getFieldInfo(): FieldInfo[] {
    return this.fields;
  }

  toBytes() {
    const writer = new BinaryWriter({});
    writer.writeUint8(-1); // placeholder for header, update later
    let currentClassHeader = this.fields.length;

    if (this.fields.length >= SMALL_NUM_FIELDS_THRESHOLD) {
      currentClassHeader = SMALL_NUM_FIELDS_THRESHOLD;
      writer.writeVarUInt32(this.fields.length - SMALL_NUM_FIELDS_THRESHOLD);
    }

    if (!TypeId.isNamedType(this.type.typeId)) {
      writer.writeUint8(this.type.typeId);
      if (this.type.userTypeId === undefined || this.type.userTypeId === -1) {
        throw new Error(`userTypeId required for typeId ${this.type.typeId}`);
      }
      writer.writeVarUInt32(this.type.userTypeId);
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
      writer.writeUint8(header);
      writer.writeVarUint32Small7(encoded.length - BIG_NAME_THRESHOLD);
    } else {
      const header = (encoded.length << 2) | encoding;
      writer.writeUint8(header);
    }
    writer.buffer(encoded);
  }

  writeFieldName(writer: BinaryWriter, fieldName: string) {
    const name = TypeMeta.lowerCamelToLowerUnderscore(fieldName);
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
        size = fieldInfo.getFieldId()!;
        encodingFlags = 3; // TAG_ID encoding
      } else {
        // Convert camelCase to snake_case for xlang compatibility
        const fieldName = TypeMeta.lowerCamelToLowerUnderscore(fieldInfo.getFieldName());
        const metaString = fieldEncoder.encodeByEncodings(fieldName, fieldNameEncoding);
        encodingFlags = fieldNameEncoding.indexOf(metaString.getEncoding());
        encoded = metaString.getBytes();
        size = encoded.length - 1;
      }

      header |= (encodingFlags << 6);
      const bigSize = size >= FIELD_NAME_SIZE_THRESHOLD;

      if (bigSize) {
        header |= 0b00111100;
        writer.writeInt8(header);
        writer.writeVarUint32Small7(size - FIELD_NAME_SIZE_THRESHOLD);
      } else {
        header |= (size << 2);
        writer.writeInt8(header);
      }

      FieldInfo.writeTypeId(writer, fieldInfo);
      // Write field name if not using field ID
      if (!fieldInfo.hasFieldId() && encoded) {
        writer.buffer(encoded);
      }
    }
  }

  static lowerUnderscoreToLowerCamelCase(lowerUnderscore: string) {
    let result = "";
    const length = lowerUnderscore.length;

    let fromIndex = 0;
    let index;

    while ((index = lowerUnderscore.indexOf("_", fromIndex)) !== -1) {
      // 拼接下划线前的内容
      result += lowerUnderscore.substring(fromIndex, index);

      if (length > index + 1) {
        const symbol = lowerUnderscore.charAt(index + 1);
        // 判断是否为小写字母
        if (symbol >= "a" && symbol <= "z") {
          result += symbol.toUpperCase();
          fromIndex = index + 2;
          continue;
        }
      }

      fromIndex = index + 1;
    }

    // 处理剩余部分
    if (fromIndex < length) {
      result += lowerUnderscore.substring(fromIndex, length);
    }

    return result;
  }

  static lowerCamelToLowerUnderscore(lowerCamel: string) {
    let result = "";
    const length = lowerCamel.length;
    let fromIndex = 0;

    for (let i = 0; i < length; i++) {
      const symbol = lowerCamel.charAt(i);

      // 检查是否为大写字母
      if (symbol >= "A" && symbol <= "Z") {
        // 拼接从上一个索引到当前大写字母前的部分，加下划线，加小写化后的字母
        result += lowerCamel.substring(fromIndex, i);
        result += "_";
        result += symbol.toLowerCase();
        // 更新起始索引
        fromIndex = i + 1;
      }
    }

    // 处理剩余部分
    if (fromIndex < length) {
      result += lowerCamel.substring(fromIndex, length);
    }

    return result;
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
    writer.writeInt64(header);

    if (metaSize > META_SIZE_MASKS) {
      writer.writeVarUInt32(metaSize - META_SIZE_MASKS);
    }

    writer.buffer(buffer);
    return writer.dump();
  }

  static toSnakeCase(name: string) {
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
  }

  static getFieldSortKey(i: { fieldName: string; fieldId?: number }) {
    if (i.fieldId !== undefined && i.fieldId !== null) {
      return `${i.fieldId}`;
    }
    return TypeMeta.toSnakeCase(i.fieldName);
  }

  static groupFieldsByType<T extends { fieldName: string; nullable?: boolean; typeId: number; fieldId?: number }>(typeInfos: Array<T>): Array<T> {
    const primitiveFields: Array<T> = [];
    const nullablePrimitiveFields: Array<T> = [];
    const internalTypeFields: Array<T> = [];
    const listFields: Array<T> = [];
    const setFields: Array<T> = [];
    const mapFields: Array<T> = [];
    const otherFields: Array<T> = [];

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
      if (TypeId.isBuiltin(typeId)) {
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
    const primitiveComparator = (a: T, b: T) => {
      // Sort by type_id descending, then by name ascending
      const t1Compress = TypeId.isCompressedType(a.typeId);
      const t2Compress = TypeId.isCompressedType(b.typeId);

      if ((t1Compress && t2Compress) || (!t1Compress && !t2Compress)) {
        const sizea = getPrimitiveTypeSize(a.typeId);
        const sizeb = getPrimitiveTypeSize(b.typeId);
        // return nameSorter(a, b);

        let c = sizeb - sizea;
        if (c === 0) {
          c = b.typeId - a.typeId;
          // noinspection Duplicates
          if (c == 0) {
            return nameSorter(a, b);
          }
          return c;
        }
        return c;
      }
      if (t1Compress) {
        return 1;
      }
      // t2 compress
      return -1;
    };

    const typeIdThenNameSorter = (a: T, b: T) => {
      if (a.typeId !== b.typeId) {
        return a.typeId - b.typeId;
      }
      return nameSorter(a, b);
    };

    const nameSorter = (a: T, b: T) => {
      return TypeMeta.getFieldSortKey(a).localeCompare(TypeMeta.getFieldSortKey(b));
    };

    // Sort each group
    primitiveFields.sort(primitiveComparator);
    nullablePrimitiveFields.sort(primitiveComparator);
    internalTypeFields.sort(typeIdThenNameSorter);
    listFields.sort(typeIdThenNameSorter);
    setFields.sort(typeIdThenNameSorter);
    mapFields.sort(typeIdThenNameSorter);
    otherFields.sort(nameSorter);

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
