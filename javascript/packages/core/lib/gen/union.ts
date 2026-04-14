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
import { TypeMeta } from "../meta/TypeMeta";

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
          ${this.writerSerializer} = ${caseInfo}.named ? ${this.builder.getTypeResolverName()}.getSerializerByName(${caseInfo}.named) : ${this.builder.getTypeResolverName()}.getSerializerById(${caseInfo}.typeId, ${caseInfo}.userTypeId);
        } else {
          ${this.writerSerializer} = ${this.builder.getExternal(AnyHelper.name)}.getSerializer(${this.builder.getWriteContextName()}, ${unionValue});
        }
        if (${this.writerSerializer}.needToWriteRef()) {
          const existsId = ${this.builder.referenceResolver.getWrittenRefId(unionValue)};
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
        ${unionValue} = ${this.builder.referenceResolver.getReadRef(this.builder.reader.readVarUInt32())};
      } else {
        ${this.detectedSerializer} = ${this.builder.getExternal(AnyHelper.name)}.detectSerializer(${this.builder.getReadContextName()});
        ${this.builder.getReadContextName()}.incReadDepth();
        ${unionValue} = ${this.detectedSerializer}.read(${refFlag} === ${RefFlags.RefValueFlag});
        ${this.builder.getReadContextName()}.decReadDepth();
      }
      const ${result} = { case: ${caseIndex}, value: ${unionValue} };
      ${assignStmt(result)}
    `;
  }

  writeTypeInfo(): string {
    const internalTypeId = this.typeInfo._typeId;
    let writeUserTypeIdStmt = "";
    let typeMeta = "";
    switch (internalTypeId) {
      case TypeId.TYPED_UNION:
        writeUserTypeIdStmt = this.builder.writer.writeVarUint32Small7(this.typeInfo.userTypeId);
        break;
      case TypeId.NAMED_UNION:
        if (this.builder.resolver.isCompatible()) {
          const bytes = this.scope.declare("unionTypeInfoBytes", `new Uint8Array([${TypeMeta.fromTypeInfo(this.typeInfo).toBytes().join(",")}])`);
          const serializerExpr = `${this.builder.getTypeResolverName()}.getSerializerByName("${CodecBuilder.replaceBackslashAndQuote(this.typeInfo.named!)}")`;
          typeMeta = this.builder.typeMetaResolver.writeTypeMeta(`${serializerExpr}.getTypeInfo()`, bytes);
        } else {
          const nsBytes = this.scope.declare("unionNsBytes", this.builder.metaStringResolver.encodeNamespace(CodecBuilder.replaceBackslashAndQuote(this.typeInfo.namespace)));
          const typeNameBytes = this.scope.declare("unionTypeNameBytes", this.builder.metaStringResolver.encodeTypeName(CodecBuilder.replaceBackslashAndQuote(this.typeInfo.typeName)));
          typeMeta = `
            ${this.builder.metaStringResolver.writeBytes(nsBytes)}
            ${this.builder.metaStringResolver.writeBytes(typeNameBytes)}
          `;
        }
        break;
    }
    return `
      ${this.builder.writer.writeUint8(this.getTypeId())};
      ${writeUserTypeIdStmt}
      ${typeMeta}
    `;
  }

  readTypeInfo(): string {
    const internalTypeId = this.typeInfo._typeId;
    let readUserTypeIdStmt = "";
    let namesStmt = "";
    switch (internalTypeId) {
      case TypeId.TYPED_UNION:
        readUserTypeIdStmt = `${this.builder.reader.readVarUint32Small7()};`;
        break;
      case TypeId.NAMED_UNION:
        if (this.builder.resolver.isCompatible()) {
          const typeMeta = this.scope.uniqueName("unionTypeMeta");
          namesStmt = `
            const ${typeMeta} = ${this.builder.typeMetaResolver.readTypeMeta()};
          `;
        } else {
          namesStmt = `
            ${this.builder.metaStringResolver.readNamespace()};
            ${this.builder.metaStringResolver.readTypeName()};
          `;
        }
        break;
    }
    return `
      ${this.builder.reader.readUint8()};
      ${readUserTypeIdStmt}
      ${namesStmt}
    `;
  }

  getFixedSize(): number {
    return 12;
  }

  getTypeId() {
    return this.typeInfo._typeId;
  }
}

CodegenRegistry.register(TypeId.TYPED_UNION, UnionSerializerGenerator);
CodegenRegistry.register(TypeId.NAMED_UNION, UnionSerializerGenerator);
