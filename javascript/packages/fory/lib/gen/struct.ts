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

import { InternalSerializerType, MaxInt32, Mode, TypeId } from "../type";
import { Scope } from "./scope";
import { CodecBuilder } from "./builder";
import { StructTypeInfo, TypeInfo } from "../typeInfo";
import { CodegenRegistry } from "./router";
import { BaseSerializerGenerator, RefState } from "./serializer";
import { fromString } from "../platformBuffer";

function computeFieldHash(hash: number, id: number): number {
  let newHash = (hash) * 31 + (id);
  while (newHash >= MaxInt32) {
    newHash = Math.floor(newHash / 7);
  }
  return newHash;
}

const computeStringHash = (str: string) => {
  const bytes = fromString(str);
  let hash = 17;
  bytes.forEach((b) => {
    hash = hash * 31 + b;
    while (hash >= MaxInt32) {
      hash = Math.floor(hash / 7);
    }
  });
  return hash;
};

const computeStructHash = (typeInfo: TypeInfo) => {
  let hash = 17;
  for (const [, value] of Object.entries((<StructTypeInfo>typeInfo).options.props!).sort()) {
    let id = value.typeId;
    if (TypeId.IS_NAMED_TYPE(value.typeId!)) {
      id = computeStringHash((<StructTypeInfo>value).namespace! + (<StructTypeInfo>value).typeName!);
    }
    hash = computeFieldHash(hash, id || 0);
  }
  return hash;
};

class StructSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: StructTypeInfo;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = <StructTypeInfo>typeInfo;
  }

  writeStmt(accessor: string): string {
    const options = this.typeInfo.options;

    return `
      ${this.builder.fory.config.mode === Mode.SchemaConsistent ? this.builder.writer.varUInt32(computeStructHash(this.typeInfo)) : ""}
      ${Object.entries(options.props!).sort().map(([key, inner]) => {
        const InnerGeneratorClass = CodegenRegistry.get(inner.type);
        if (!InnerGeneratorClass) {
            throw new Error(`${inner.type} generator not exists`);
        }
        const innerGenerator = new InnerGeneratorClass(inner, this.builder, this.scope);

        const fieldAccessor = `${accessor}${CodecBuilder.safePropAccessor(key)}`;
        return (inner as any).nullable === false
          ? `if (${fieldAccessor} === null || ${fieldAccessor} === undefined) { throw new Error("Field '${CodecBuilder.replaceBackslashAndQuote(key)}' is not nullable"); }\n${innerGenerator.toWriteEmbed(fieldAccessor, true)}`
          : innerGenerator.toWriteEmbed(fieldAccessor);
      }).join(";\n")}
    `;
  }

  readStmt(accessor: (expr: string) => string, refState: RefState): string {
    const options = this.typeInfo.options;
    const result = this.scope.uniqueName("result");

    return `
      ${
        this.builder.fory.config.mode === Mode.SchemaConsistent
? `
        if (${this.builder.reader.varUInt32()} !== ${computeStructHash(this.typeInfo)}) {
          throw new Error("hash error");
        }`
: ""
      }

      ${
        this.typeInfo.options.withConstructor && this.builder.fory.config?.constructClass
? `
          const ${result} = new ${this.builder.getOptions("constructor")}();
        `
: `
          const ${result} = {
            ${Object.entries(options.props!).sort().map(([key]) => {
              return `${CodecBuilder.safePropName(key)}: null`;
            }).join(",\n")}
          };
        `
      }
      ${this.maybeReference(result, refState)}
      ${Object.entries(options.props!).sort().map(([key, inner]) => {
        const InnerGeneratorClass = CodegenRegistry.get(inner.type);
        if (!InnerGeneratorClass) {
          throw new Error(`${inner.type} generator not exists`);
        }
        const innerGenerator = new InnerGeneratorClass(inner, this.builder, this.scope);
        return innerGenerator.toReadEmbed(expr => `${result}${CodecBuilder.safePropAccessor(key)} = ${expr}`);
      }).join(";\n")}
      ${accessor(result)}
    `;
  }

  toReadEmbed(accessor: (expr: string) => string, excludeHead?: boolean, refState?: RefState): string {
    const name = this.scope.declare(
      "tag_ser",
      TypeId.IS_NAMED_TYPE(this.typeInfo.typeId)
        ? this.builder.classResolver.getSerializerByName(CodecBuilder.replaceBackslashAndQuote(this.typeInfo.named!))
        : this.builder.classResolver.getSerializerById(this.typeInfo.typeId)
    );
    if (!excludeHead) {
      return accessor(`${name}.read()`);
    }
    return accessor(`${name}.readInner(${refState!.toConditionExpr()})`);
  }

  toWriteEmbed(accessor: string, excludeHead?: boolean): string {
    const name = this.scope.declare(
      "tag_ser",
      TypeId.IS_NAMED_TYPE(this.typeInfo.typeId)
        ? this.builder.classResolver.getSerializerByName(CodecBuilder.replaceBackslashAndQuote(this.typeInfo.named!))
        : this.builder.classResolver.getSerializerById(this.typeInfo.typeId)
    );
    if (!excludeHead) {
      return `${name}.write(${accessor})`;
    }
    return `${name}.writeInner(${accessor})`;
  }

  getFixedSize(): number {
    const typeInfo = <StructTypeInfo> this.typeInfo;
    const options = typeInfo.options;
    let fixedSize = 8;
    if (options.props) {
      Object.values(options.props).forEach((x) => {
        const propGenerator = new (CodegenRegistry.get(x.type)!)(x, this.builder, this.scope);
        fixedSize += propGenerator.getFixedSize();
      });
    } else {
      fixedSize += this.builder.fory.classResolver.getSerializerByName(typeInfo.named!)!.fixedSize;
    }
    return fixedSize;
  }

  needToWriteRef(): boolean {
    return Boolean(this.builder.fory.config.refTracking);
  }
}

CodegenRegistry.register(InternalSerializerType.STRUCT, StructSerializerGenerator);
