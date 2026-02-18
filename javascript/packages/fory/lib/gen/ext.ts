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

import { TypeId } from "../type";
import { Scope } from "./scope";
import { CodecBuilder } from "./builder";
import { TypeInfo } from "../typeInfo";
import { CodegenRegistry } from "./router";
import { BaseSerializerGenerator } from "./serializer";
import { TypeMeta } from "../meta/TypeMeta";

class ExtSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;
  typeMeta: TypeMeta;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
    this.typeMeta = TypeMeta.fromTypeInfo(this.typeInfo);
  }

  write(accessor: string): string {
    return `
      ${this.builder.getOptions("customSerializer")}.write(${accessor}, ${this.builder.writer.ownName()}, ${this.builder.getForyName()}, )
    `;
  }

  read(accessor: (expr: string) => string, refState: string): string {
    const result = this.scope.uniqueName("result");
    return `
      ${this.typeInfo.options!.withConstructor
        ? `
          const ${result} = new ${this.builder.getOptions("creator")}();
        `
        : `
          const ${result} = {};
        `
      }
      ${this.maybeReference(result, refState)}
      ${this.builder.getOptions("customSerializer")}.read(${result}, ${this.builder.reader.ownName()}, ${this.builder.getForyName()})
      ${
        accessor(result)
      }
    `;
  }

  readNoRef(assignStmt: (v: string) => string, refState: string): string {
    return `
      ${this.readTypeInfo()}
      ${this.read(assignStmt, refState)};
    `;
  }

  readTypeInfo(): string {
    const typeMeta = this.scope.uniqueName("typeMeta");
    const internalTypeId = this.getInternalTypeId();
    let namesStmt = "";
    let typeMetaStmt = "";
    let readUserTypeIdStmt = "";
    switch (internalTypeId) {
      case TypeId.EXT:
        readUserTypeIdStmt = `${this.builder.reader.readVarUint32Small7()};`;
        break;
      case TypeId.NAMED_EXT:
        if (!this.builder.fory.isCompatible()) {
          namesStmt = `
            ${
              this.builder.metaStringResolver.readNamespace(this.builder.reader.ownName())
            };
            ${
              this.builder.metaStringResolver.readTypeName(this.builder.reader.ownName())
            };
          `;
        } else {
          typeMetaStmt = `
          const ${typeMeta} = ${this.builder.typeMetaResolver.readTypeMeta(this.builder.reader.ownName())};
          `;
        }
        break;
    }
    return `
      ${
        this.builder.reader.readUint8()
      };
      ${readUserTypeIdStmt}
      ${
        namesStmt
      }
      ${
        typeMetaStmt
      }
    `;
  }

  readEmbed() {
    return new Proxy({}, {
      get: (target, prop: string) => {
        return (accessor: (expr: string) => string, ...args: string[]) => {
          const name = this.scope.declare(
            "ext_ser",
            TypeId.isNamedType(this.typeInfo.typeId)
              ? this.builder.typeResolver.getSerializerByName(CodecBuilder.replaceBackslashAndQuote(this.typeInfo.named!))
              : this.builder.typeResolver.getSerializerById(this.typeInfo.typeId, this.typeInfo.userTypeId)
          );
          return accessor(`${name}.${prop}(${args.join(",")})`);
        };
      },
    });
  }

  writeEmbed() {
    return new Proxy({}, {
      get: (target, prop: string) => {
        return (accessor: string) => {
          const name = this.scope.declare(
            "ext_ser",
            TypeId.isNamedType(this.typeInfo.typeId)
              ? this.builder.typeResolver.getSerializerByName(CodecBuilder.replaceBackslashAndQuote(this.typeInfo.named!))
              : this.builder.typeResolver.getSerializerById(this.typeInfo.typeId, this.typeInfo.userTypeId)
          );
          return `${name}.${prop}(${accessor})`;
        };
      },
    });
  }

  writeTypeInfo(): string {
    const internalTypeId = this.getInternalTypeId();
    let typeMeta = "";
    let writeUserTypeIdStmt = "";
    switch (internalTypeId) {
      case TypeId.EXT:
        writeUserTypeIdStmt = this.builder.writer.writeVarUint32Small7(this.typeInfo.userTypeId);
        break;
      case TypeId.NAMED_EXT:
        if (!this.builder.fory.isCompatible()) {
          const typeInfo = this.typeInfo;
          const nsBytes = this.scope.declare("nsBytes", this.builder.metaStringResolver.encodeNamespace(CodecBuilder.replaceBackslashAndQuote(typeInfo.namespace)));
          const typeNameBytes = this.scope.declare("typeNameBytes", this.builder.metaStringResolver.encodeTypeName(CodecBuilder.replaceBackslashAndQuote(typeInfo.typeName)));
          typeMeta = `
            ${this.builder.metaStringResolver.writeBytes(this.builder.writer.ownName(), nsBytes)}
            ${this.builder.metaStringResolver.writeBytes(this.builder.writer.ownName(), typeNameBytes)}
          `;
        } else {
          const bytes = this.scope.declare("typeInfoBytes", `new Uint8Array([${TypeMeta.fromTypeInfo(this.typeInfo).toBytes().join(",")}])`);
          typeMeta = this.builder.typeMetaResolver.writeTypeMeta(this.builder.getTypeInfo(), this.builder.writer.ownName(), bytes);
        }
        break;
      default:
        break;
    }
    return ` 
      ${this.builder.writer.writeUint8(this.getTypeId())};
      ${writeUserTypeIdStmt}
      ${typeMeta}
    `;
  }

  getFixedSize(): number {
    return 5;
  }

  getHash(): string {
    return "0";
  }
}

CodegenRegistry.register(TypeId.EXT, ExtSerializerGenerator);
CodegenRegistry.register(TypeId.NAMED_EXT, ExtSerializerGenerator);
