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
import { BaseSerializerGenerator, SerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { Scope } from "./scope";
import { TypeId } from "../type";

function build(inner: TypeInfo) {
  return class TypedArraySerializerGenerator extends BaseSerializerGenerator {
    typeInfo: TypeInfo;
    innerGenerator: SerializerGenerator;

    constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
      super(typeInfo, builder, scope);
      this.typeInfo = <TypeInfo>typeInfo;
      this.innerGenerator = CodegenRegistry.newGeneratorByTypeInfo(inner, builder, scope);
    }

    write(accessor: string): string {
      const item = this.scope.uniqueName("item");
      return `
                ${this.builder.writer.varUInt32(`${accessor}.length`)}
                ${this.builder.writer.reserve(`${this.innerGenerator.getFixedSize()} * ${accessor}.length`)};
                for (const ${item} of ${accessor}) {
                    ${this.innerGenerator.writeEmbed().write(item)}
                }
            `;
    }

    read(accessor: (expr: string) => string, refState: string): string {
      const result = this.scope.uniqueName("result");
      const len = this.scope.uniqueName("len");
      const idx = this.scope.uniqueName("idx");

      return `
                const ${len} = ${this.builder.reader.varUInt32()};
                const ${result} = new Array(${len});
                ${this.maybeReference(result, refState)}
                for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                    ${this.innerGenerator.read(x => `${result}[${idx}] = ${x};`, "false")}
                }
                ${accessor(result)}
             `;
    }

    getFixedSize(): number {
      return 7;
    }
  };
}

CodegenRegistry.register(TypeId.BOOL_ARRAY, build(Type.bool()));
CodegenRegistry.register(TypeId.INT8_ARRAY, build(Type.int8()));
CodegenRegistry.register(TypeId.INT16_ARRAY, build(Type.int16()));
CodegenRegistry.register(TypeId.INT32_ARRAY, build(Type.int32()));
CodegenRegistry.register(TypeId.INT64_ARRAY, build(Type.int64()));
CodegenRegistry.register(TypeId.UINT8_ARRAY, build(Type.uint8()));
CodegenRegistry.register(TypeId.UINT16_ARRAY, build(Type.uint16()));
CodegenRegistry.register(TypeId.UINT32_ARRAY, build(Type.uint32()));
CodegenRegistry.register(TypeId.UINT64_ARRAY, build(Type.uint64()));
CodegenRegistry.register(TypeId.FLOAT16_ARRAY, build(Type.float16()));
CodegenRegistry.register(TypeId.FLOAT32_ARRAY, build(Type.float32()));
CodegenRegistry.register(TypeId.FLOAT64_ARRAY, build(Type.float64()));
