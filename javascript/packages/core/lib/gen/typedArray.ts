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

import { Type, TypeInfo } from "../typeInfo";
import { CodecBuilder } from "./builder";
import { BaseSerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { Scope } from "./scope";
import { TypeId } from "../type";
import { BFloat16Array } from "../types/bfloat16";
import { BoolArray } from "../types/boolArray";
import {
  createFloat16Array,
  createFloat16ArrayFromRaw,
  getFloat16Raw,
} from "../types/float16";

const endianProbe = new Uint16Array([0x00ff]);
const IS_LITTLE_ENDIAN = new Uint8Array(endianProbe.buffer)[0] === 0xff;

type TypedArrayReadMethod =
  | "readFloat32"
  | "readFloat64"
  | "readInt8"
  | "readInt16"
  | "readInt32"
  | "readInt64"
  | "readUint8"
  | "readUint16"
  | "readUint32"
  | "readUint64";

type TypedArrayWriteMethod =
  | "writeFloat32"
  | "writeFloat64"
  | "writeInt8"
  | "writeInt16"
  | "writeInt32"
  | "writeInt64"
  | "writeUint8"
  | "writeUint16"
  | "writeUint32"
  | "writeUint64";

const SMALL_TYPED_ARRAY_DIRECT_WRITE_BYTES = 96;

const directWriteSetters: Partial<Record<TypedArrayWriteMethod, string>> = {
  writeFloat32: "setFloat32",
  writeFloat64: "setFloat64",
  writeInt16: "setInt16",
  writeInt32: "setInt32",
  writeUint16: "setUint16",
  writeUint32: "setUint32",
};

function build(
  inner: TypeInfo,
  creator: string,
  size: number,
  readMethod: TypedArrayReadMethod,
  writeMethod: TypedArrayWriteMethod,
) {
  return class TypedArraySerializerGenerator extends BaseSerializerGenerator {
    typeInfo: TypeInfo;

    constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
      super(typeInfo, builder, scope);
      this.typeInfo = <TypeInfo>typeInfo;
    }

    write(accessor: string): string {
      const directSetter = directWriteSetters[writeMethod];
      if (size !== 1 && !IS_LITTLE_ENDIAN) {
        const idx = this.scope.uniqueName("idx");
        return `
                ${this.builder.writer.writeVarUInt32(`${accessor}.length * ${size}`)}
                ${this.builder.writer.reserve(`${accessor}.length * ${size}`)};
                for (let ${idx} = 0; ${idx} < ${accessor}.length; ${idx}++) {
                  ${this.builder.writer[writeMethod](`${accessor}[${idx}]`)}
                }
            `;
      }
      if (directSetter !== undefined) {
        const byteLength = this.scope.uniqueName("byteLength");
        const cursor = this.scope.uniqueName("cursor");
        const dataView = this.scope.uniqueName("dataView");
        const idx = this.scope.uniqueName("idx");
        return `
                const ${byteLength} = ${accessor}.byteLength;
                ${this.builder.writer.writeVarUInt32(byteLength)}
                if (${byteLength} <= ${SMALL_TYPED_ARRAY_DIRECT_WRITE_BYTES}) {
                  ${this.builder.writer.reserve(byteLength)};
                  let ${cursor} = ${this.builder.writer.writeGetCursor()};
                  const ${dataView} = ${this.builder.writer.getDataView()};
                  for (let ${idx} = 0; ${idx} < ${accessor}.length; ${idx}++) {
                    ${dataView}.${directSetter}(${cursor}, ${accessor}[${idx}], true);
                    ${cursor} += ${size};
                  }
                  ${this.builder.writer.setWriteCursor(cursor)}
                } else {
                  ${this.builder.writer.arrayBuffer(`${accessor}.buffer`, `${accessor}.byteOffset`, byteLength)}
                }
            `;
      }
      return `
                ${this.builder.writer.writeVarUInt32(`${accessor}.byteLength`)}
                ${this.builder.writer.arrayBuffer(`${accessor}.buffer`, `${accessor}.byteOffset`, `${accessor}.byteLength`)}
            `;
    }

    read(accessor: (expr: string) => string, refState: string): string {
      const result = this.scope.uniqueName("result");
      const len = this.scope.uniqueName("len");
      const copied = this.scope.uniqueName("copied");

      if (size !== 1 && !IS_LITTLE_ENDIAN) {
        const rawLen = this.scope.uniqueName("rawLen");
        const idx = this.scope.uniqueName("idx");
        return `
                const ${rawLen} = ${this.builder.reader.readVarUInt32()};
                if ((${rawLen} % ${size}) !== 0) {
                  throw new Error("dense array byte length is not divisible by element size");
                }
                ${this.builder.reader.checkReadableBytes(rawLen)};
                const ${len} = ${rawLen} / ${size};
                const ${result} = new ${creator}(${len});
                ${this.maybeReference(result, refState)}
                for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                  ${result}[${idx}] = ${this.builder.reader[readMethod]()};
                }
                ${accessor(result)}
             `;
      }
      return `
                const ${len} = ${this.builder.reader.readVarUInt32()};
                const ${copied} = ${this.builder.reader.buffer(len)}
                const ${result} = new ${creator}(${copied}.buffer, ${copied}.byteOffset, ${copied}.byteLength / ${size});
                ${this.maybeReference(result, refState)}
                ${accessor(result)}
             `;
    }

    getFixedSize(): number {
      return 7;
    }
  };
}

class BoolArraySerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = <TypeInfo>typeInfo;
  }

  write(accessor: string): string {
    const item = this.scope.uniqueName("item");
    const raw = this.scope.uniqueName("raw");
    return `
                if (${accessor} instanceof external.BoolArray) {
                  const ${raw} = ${accessor}.raw;
                  ${this.builder.writer.writeVarUInt32(`${raw}.byteLength`)}
                  ${this.builder.writer.buffer(raw)}
                } else {
                  ${this.builder.writer.writeVarUInt32(`${accessor}.length`)}
                  ${this.builder.writer.reserve(`${accessor}.length`)};
                  for (const ${item} of ${accessor}) {
                    ${this.builder.writer.writeUint8(`${item} ? 1 : 0`)}
                  }
                }
            `;
  }

  read(accessor: (expr: string) => string, refState: string): string {
    const result = this.scope.uniqueName("result");
    const len = this.scope.uniqueName("len");
    const idx = this.scope.uniqueName("idx");
    const raw = this.scope.uniqueName("raw");
    const bits = this.scope.uniqueName("bits");
    const copied = this.scope.uniqueName("copied");
    const readByte = this.builder.reader.readUint8();
    return `
                const ${len} = ${this.builder.reader.readVarUInt32()};
                ${this.builder.reader.checkReadableBytes(len)};
                let ${result};
                if (${len} <= 4) {
                  let ${bits};
                  switch (${len}) {
                    case 4:
                      ${bits} = ${this.builder.reader.readUint32()};
                      break;
                    case 3:
                      ${bits} = ${readByte} | (${readByte} << 8) | (${readByte} << 16);
                      break;
                    case 2:
                      ${bits} = ${this.builder.reader.readUint16()};
                      break;
                    case 1:
                      ${bits} = ${readByte};
                      break;
                    default:
                      ${bits} = 0;
                      break;
                  }
                  ${result} = external.BoolArray.fromPackedBytes(${bits}, ${len});
                } else if (${len} <= 32) {
                  ${result} = new external.BoolArray(${len});
                  const ${raw} = ${result}.raw;
                  for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                    ${raw}[${idx}] = ${readByte};
                  }
                } else {
                  const ${copied} = ${this.builder.reader.buffer(len)}
                  ${result} = external.BoolArray.fromRaw(${copied});
                }
                ${this.maybeReference(result, refState)}
                ${accessor(result)}
             `;
  }

  getFixedSize(): number {
    return 7;
  }
}

class Float16ArraySerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = <TypeInfo>typeInfo;
  }

  write(accessor: string): string {
    const item = this.scope.uniqueName("item");
    const raw = this.scope.uniqueName("raw");
    const idx = this.scope.uniqueName("idx");
    return `
        const ${raw} = external.getFloat16Raw(${accessor});
        if (${raw} !== null) {
          ${this.builder.writer.writeVarUInt32(`${raw}.byteLength`)}
          ${this.builder.writer.reserve(`${raw}.byteLength`)};
          for (let ${idx} = 0; ${idx} < ${raw}.length; ${idx}++) {
            ${this.builder.writer.writeUint16(`${raw}[${idx}]`)}
          }
        } else {
          ${this.builder.writer.writeVarUInt32(`${accessor}.length * 2`)}
          ${this.builder.writer.reserve(`${accessor}.length * 2`)};
          for (const ${item} of ${accessor}) {
            ${this.builder.writer.writeFloat16(item)}
          }
        }
    `;
  }

  read(accessor: (expr: string) => string, refState: string): string {
    const result = this.scope.uniqueName("result");
    const rawLen = this.scope.uniqueName("rawLen");
    const len = this.scope.uniqueName("len");
    const idx = this.scope.uniqueName("idx");
    const raw = this.scope.uniqueName("raw");
    return `
        const ${rawLen} = ${this.builder.reader.readVarUInt32()};
        if ((${rawLen} % 2) !== 0) {
          throw new Error("float16 array byte length is not divisible by element size");
        }
        ${this.builder.reader.checkReadableBytes(rawLen)};
        const ${len} = ${rawLen} / 2;
        const ${raw} = new Uint16Array(${len});
        for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
          ${raw}[${idx}] = ${this.builder.reader.readUint16()};
        }
        const ${result} = external.createFloat16ArrayFromRaw(${raw});
        ${this.maybeReference(result, refState)}
        ${accessor(result)}
      `;
  }

  getFixedSize(): number {
    return 7;
  }
}

class BFloat16ArraySerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = <TypeInfo>typeInfo;
  }

  write(accessor: string): string {
    const item = this.scope.uniqueName("item");
    const raw = this.scope.uniqueName("raw");
    const idx = this.scope.uniqueName("idx");
    return `
        if (${accessor} instanceof external.BFloat16Array) {
          const ${raw} = ${accessor}.raw;
          ${this.builder.writer.writeVarUInt32(`${raw}.byteLength`)}
          ${this.builder.writer.reserve(`${raw}.byteLength`)};
          for (let ${idx} = 0; ${idx} < ${raw}.length; ${idx}++) {
            ${this.builder.writer.writeUint16(`${raw}[${idx}]`)}
          }
        } else {
          ${this.builder.writer.writeVarUInt32(`${accessor}.length * 2`)}
          ${this.builder.writer.reserve(`${accessor}.length * 2`)};
          for (const ${item} of ${accessor}) {
            ${this.builder.writer.writeBfloat16(item)}
          }
        }
    `;
  }

  read(accessor: (expr: string) => string, refState: string): string {
    const result = this.scope.uniqueName("result");
    const rawLen = this.scope.uniqueName("rawLen");
    const len = this.scope.uniqueName("len");
    const idx = this.scope.uniqueName("idx");
    const raw = this.scope.uniqueName("raw");
    return `
        const ${rawLen} = ${this.builder.reader.readVarUInt32()};
        if ((${rawLen} % 2) !== 0) {
          throw new Error("bfloat16 array byte length is not divisible by element size");
        }
        ${this.builder.reader.checkReadableBytes(rawLen)};
        const ${len} = ${rawLen} / 2;
        const ${raw} = new Uint16Array(${len});
        for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
          ${raw}[${idx}] = ${this.builder.reader.readUint16()};
        }
        const ${result} = external.BFloat16Array.fromRaw(${raw});
        ${this.maybeReference(result, refState)}
        ${accessor(result)}
      `;
  }

  getFixedSize(): number {
    return 7;
  }
}

CodegenRegistry.register(TypeId.BOOL_ARRAY, BoolArraySerializerGenerator);
CodegenRegistry.register(
  TypeId.BINARY,
  build(Type.uint8(), `Uint8Array`, 1, "readUint8", "writeUint8"),
);
CodegenRegistry.register(
  TypeId.INT8_ARRAY,
  build(Type.int8(), `Int8Array`, 1, "readInt8", "writeInt8"),
);
CodegenRegistry.register(
  TypeId.INT16_ARRAY,
  build(Type.int16(), `Int16Array`, 2, "readInt16", "writeInt16"),
);
CodegenRegistry.register(
  TypeId.INT32_ARRAY,
  build(Type.int32(), `Int32Array`, 4, "readInt32", "writeInt32"),
);
CodegenRegistry.register(
  TypeId.INT64_ARRAY,
  build(Type.int64(), `BigInt64Array`, 8, "readInt64", "writeInt64"),
);
CodegenRegistry.register(
  TypeId.UINT8_ARRAY,
  build(Type.uint8(), `Uint8Array`, 1, "readUint8", "writeUint8"),
);
CodegenRegistry.register(
  TypeId.UINT16_ARRAY,
  build(Type.uint16(), `Uint16Array`, 2, "readUint16", "writeUint16"),
);
CodegenRegistry.register(
  TypeId.UINT32_ARRAY,
  build(Type.uint32(), `Uint32Array`, 4, "readUint32", "writeUint32"),
);
CodegenRegistry.register(
  TypeId.UINT64_ARRAY,
  build(Type.uint64(), `BigUint64Array`, 8, "readUint64", "writeUint64"),
);
CodegenRegistry.register(TypeId.FLOAT16_ARRAY, Float16ArraySerializerGenerator);
CodegenRegistry.register(
  TypeId.BFLOAT16_ARRAY,
  BFloat16ArraySerializerGenerator,
);
CodegenRegistry.register(
  TypeId.FLOAT32_ARRAY,
  build(Type.float32(), `Float32Array`, 4, "readFloat32", "writeFloat32"),
);
CodegenRegistry.register(
  TypeId.FLOAT64_ARRAY,
  build(Type.float64(), `Float64Array`, 8, "readFloat64", "writeFloat64"),
);
CodegenRegistry.registerExternal(BFloat16Array);
CodegenRegistry.registerExternal(BoolArray);
CodegenRegistry.registerExternal(createFloat16Array);
CodegenRegistry.registerExternal(createFloat16ArrayFromRaw);
CodegenRegistry.registerExternal(getFloat16Raw);
