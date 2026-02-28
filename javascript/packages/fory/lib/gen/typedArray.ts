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

function build(inner: TypeInfo, creator: string, size: number) {
  return class TypedArraySerializerGenerator extends BaseSerializerGenerator {
    typeInfo: TypeInfo;

    constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
      super(typeInfo, builder, scope);
      this.typeInfo = <TypeInfo>typeInfo;
    }

    write(accessor: string): string {
      return `
                ${this.builder.writer.writeVarUInt32(`${accessor}.byteLength`)}
                ${this.builder.writer.arrayBuffer(`${accessor}.buffer`, `${accessor}.byteOffset`, `${accessor}.byteLength`)}
            `;
    }

    read(accessor: (expr: string) => string, refState: string): string {
      const result = this.scope.uniqueName("result");
      const len = this.scope.uniqueName("len");
      const copied = this.scope.uniqueName("copied");

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
    return `
                ${this.builder.writer.writeVarUInt32(`${accessor}.length`)}
                ${this.builder.writer.reserve(`${accessor}.length`)};
                for (const ${item} of ${accessor}) {
                  ${this.builder.writer.writeUint8(`${item} ? 1 : 0`)}
                }
            `;
  }

  read(accessor: (expr: string) => string, refState: string): string {
    const result = this.scope.uniqueName("result");
    const len = this.scope.uniqueName("len");
    const idx = this.scope.uniqueName("idx");
    return `
                const ${len} = ${this.builder.reader.readVarUInt32()};
                const ${result} = new Array(${len});
                ${this.maybeReference(result, refState)}
                for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                  ${result}[${idx}] = ${this.builder.reader.readUint8()} === 1;
                }
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
    return `
        ${this.builder.writer.writeVarUInt32(`${accessor}.length * 2`)}
        ${this.builder.writer.reserve(`${accessor}.length * 2`)};
        for (const ${item} of ${accessor}) {
          ${this.builder.writer.writeFloat16(item)}
        }
    `;
  }

  read(accessor: (expr: string) => string, refState: string): string {
    const result = this.scope.uniqueName("result");
    const len = this.scope.uniqueName("len");
    const idx = this.scope.uniqueName("idx");
    return `
        const ${len} = ${this.builder.reader.readVarUInt32()} / 2;
        const ${result} = new Array(${len});
        ${this.maybeReference(result, refState)}
        for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
          ${result}[${idx}] = ${this.builder.reader.readFloat16()};
        }
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
    return `
        ${this.builder.writer.writeVarUInt32(`${accessor}.length * 2`)}
        ${this.builder.writer.reserve(`${accessor}.length * 2`)};
        for (const ${item} of ${accessor}) {
          ${this.builder.writer.writeBfloat16(item)}
        }
    `;
  }

  read(accessor: (expr: string) => string, refState: string): string {
    const result = this.scope.uniqueName("result");
    const len = this.scope.uniqueName("len");
    const idx = this.scope.uniqueName("idx");
    return `
        const ${len} = ${this.builder.reader.readVarUInt32()} / 2;
        const ${result} = new Array(${len});
        ${this.maybeReference(result, refState)}
        for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
          ${result}[${idx}] = ${this.builder.reader.readBfloat16()};
        }
        ${accessor(result)}
      `;
  }

  getFixedSize(): number {
    return 7;
  }
}

CodegenRegistry.register(TypeId.BOOL_ARRAY, BoolArraySerializerGenerator);
CodegenRegistry.register(TypeId.BINARY, build(Type.uint8(), `Uint8Array`, 1));
CodegenRegistry.register(TypeId.INT8_ARRAY, build(Type.int8(), `Int8Array`, 1));
CodegenRegistry.register(TypeId.INT16_ARRAY, build(Type.int16(), `Int16Array`, 2));
CodegenRegistry.register(TypeId.INT32_ARRAY, build(Type.int32(), `Int32Array`, 4));
CodegenRegistry.register(TypeId.INT64_ARRAY, build(Type.int64(), `BigInt64Array`, 8));
CodegenRegistry.register(TypeId.UINT8_ARRAY, build(Type.uint8(), `Uint8Array`, 1));
CodegenRegistry.register(TypeId.UINT16_ARRAY, build(Type.uint16(), `Uint16Array`, 2));
CodegenRegistry.register(TypeId.UINT32_ARRAY, build(Type.uint32(), `Uint32Array`, 4));
CodegenRegistry.register(TypeId.UINT64_ARRAY, build(Type.uint64(), `BigUint64Array`, 8));
CodegenRegistry.register(TypeId.FLOAT16_ARRAY, Float16ArraySerializerGenerator);
CodegenRegistry.register(TypeId.BFLOAT16_ARRAY, BFloat16ArraySerializerGenerator);
CodegenRegistry.register(TypeId.FLOAT32_ARRAY, build(Type.float32(), `Float32Array`, 4));
CodegenRegistry.register(TypeId.FLOAT64_ARRAY, build(Type.float64(), `Float64Array`, 6));
