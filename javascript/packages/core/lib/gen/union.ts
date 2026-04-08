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

import { TypeInfo } from "../typeInfo";
import { CodecBuilder } from "./builder";
import { BaseSerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { RefFlags, TypeId } from "../type";
import { Scope } from "./scope";
import { AnyHelper } from "./any";

class UnionSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;
  detectedSerializer: string;
  writerSerializer: string;
  caseTypesVar: string;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
    this.detectedSerializer = this.scope.declareVar("detectedSerializer", "null");
    this.writerSerializer = this.scope.declareVar("writerSerializer", "null");

    // Build case-to-type mapping from typeInfo.options.cases
    const cases = typeInfo.options?.cases;
    if (cases) {
      const caseEntries: string[] = [];
      for (const [caseIdx, caseTypeInfo] of Object.entries(cases)) {
        const ti = caseTypeInfo as TypeInfo;
        const isNamed = TypeId.isNamedType(ti._typeId);
        const named = isNamed ? `"${ti.named}"` : "null";
        caseEntries.push(`${caseIdx}: { typeId: ${ti.typeId}, userTypeId: ${ti.userTypeId ?? -1}, named: ${named} }`);
      }
      this.caseTypesVar = this.scope.declareVar("caseTypes", `{ ${caseEntries.join(", ")} }`);
    } else {
      this.caseTypesVar = this.scope.declareVar("caseTypes", "null");
    }
  }

  needToWriteRef(): boolean {
    return false;
  }

  write(accessor: string): string {
    const caseIndex = this.scope.uniqueName("caseIndex");
    const unionValue = this.scope.uniqueName("unionValue");
    const caseInfo = this.scope.uniqueName("caseInfo");
    return `
      const ${caseIndex} = ${accessor} && ${accessor}.case != null ? ${accessor}.case : 0;
      ${this.builder.writer.writeVarUInt32(caseIndex)}
      const ${unionValue} = ${accessor} && ${accessor}.value != null ? ${accessor}.value : null;
      if (${unionValue} === null || ${unionValue} === undefined) {
        ${this.builder.writer.writeInt8(RefFlags.NullFlag)}
      } else {
        const ${caseInfo} = ${this.caseTypesVar} ? ${this.caseTypesVar}[${caseIndex}] : null;
        if (${caseInfo}) {
          ${this.writerSerializer} = ${caseInfo}.named ? fory.typeResolver.getSerializerByName(${caseInfo}.named) : fory.typeResolver.getSerializerById(${caseInfo}.typeId, ${caseInfo}.userTypeId);
        } else {
          ${this.writerSerializer} = ${this.builder.getExternal(AnyHelper.name)}.getSerializer(${this.builder.getForyName()}, ${unionValue});
        }
        if (${this.writerSerializer}.needToWriteRef()) {
          const existsId = ${this.builder.referenceResolver.existsWriteObject(unionValue)};
          if (typeof existsId === "number") {
            ${this.builder.writer.writeInt8(RefFlags.RefFlag)}
            ${this.builder.writer.writeVarUInt32("existsId")}
          } else {
            ${this.builder.writer.writeInt8(RefFlags.RefValueFlag)}
            ${this.builder.referenceResolver.writeRef(unionValue)}
            ${this.writerSerializer}.writeTypeInfo();
            ${this.writerSerializer}.write(${unionValue});
          }
        } else {
          ${this.builder.writer.writeInt8(RefFlags.NotNullValueFlag)}
          ${this.writerSerializer}.writeTypeInfo();
          ${this.writerSerializer}.write(${unionValue});
        }
      }
    `;
  }

  read(assignStmt: (v: string) => string, refState: string): string {
    void refState;
    const caseIndex = this.scope.uniqueName("caseIndex");
    const refFlag = this.scope.uniqueName("refFlag");
    const unionValue = this.scope.uniqueName("unionValue");
    const result = this.scope.uniqueName("result");
    return `
      const ${caseIndex} = ${this.builder.reader.readVarUInt32()};
      const ${refFlag} = ${this.builder.reader.readInt8()};
      let ${unionValue} = null;
      if (${refFlag} === ${RefFlags.NullFlag}) {
        ${unionValue} = null;
      } else if (${refFlag} === ${RefFlags.RefFlag}) {
        ${unionValue} = ${this.builder.referenceResolver.getReadObject(this.builder.reader.readVarUInt32())};
      } else {
        ${this.detectedSerializer} = ${this.builder.getExternal(AnyHelper.name)}.detectSerializer(${this.builder.getForyName()});
        fory.incReadDepth();
        ${unionValue} = ${this.detectedSerializer}.read(${refFlag} === ${RefFlags.RefValueFlag});
        fory.decReadDepth();
      }
      const ${result} = { case: ${caseIndex}, value: ${unionValue} };
      ${assignStmt(result)}
    `;
  }

  getFixedSize(): number {
    return 12;
  }

  getTypeId() {
    return TypeId.TYPED_UNION;
  }
}

CodegenRegistry.register(TypeId.TYPED_UNION, UnionSerializerGenerator);
CodegenRegistry.register(TypeId.NAMED_UNION, UnionSerializerGenerator);
