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
import { TypeId, MaxUInt32 } from "../type";
import { Scope } from "./scope";

class EnumSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
  }

  write(accessor: string): string {
    if (!this.typeInfo.options?.enumProps) {
      return this.builder.writer.writeVarUInt32(accessor);
    }
    if (Object.values(this.typeInfo.options.enumProps).length < 1) {
      throw new Error("An enum must contain at least one field");
    }
    return `
        ${Object.values(this.typeInfo.options.enumProps).map((value, index) => {
      if (typeof value !== "string" && typeof value !== "number") {
        throw new Error("Enum value must be string or number");
      }
      if (typeof value === "number") {
        if (value > MaxUInt32 || value < 0) {
          throw new Error("Enum value must be a valid uint32");
        }
      }
      const safeValue = typeof value === "string" ? `"${value}"` : value;
      return ` if (${accessor} === ${safeValue}) {
                    ${this.builder.writer.writeVarUInt32(index)}
                }`;
    }).join(" else ")}
        else {
            throw new Error("Enum received an unexpected value: " + ${accessor});
        }
    `;
  }

  readTypeInfo(): string {
    const internalTypeId = this.getInternalTypeId();
    let readUserTypeIdStmt = "";
    let namesStmt = "";
    switch (internalTypeId) {
      case TypeId.ENUM:
        readUserTypeIdStmt = `${this.builder.reader.readVarUint32Small7()};`;
        break;
      case TypeId.NAMED_ENUM:
        namesStmt = `
          ${
            // skip the namespace
            this.builder.metaStringResolver.readNamespace(this.builder.reader.ownName())
          }

          ${
          // skip the namespace
          this.builder.metaStringResolver.readTypeName(this.builder.reader.ownName())
          }
        `;
        break;
      default:
        break;
    }
    return `
      ${
      // skip the typeId
      this.builder.reader.readUint8()
      }
      ${readUserTypeIdStmt}
      ${namesStmt}
    `;
  }

  writeTypeInfo(): string {
    const internalTypeId = this.getInternalTypeId();
    let typeMeta = "";
    let writeUserTypeIdStmt = "";
    switch (internalTypeId) {
      case TypeId.ENUM:
        writeUserTypeIdStmt = this.builder.writer.writeVarUint32Small7(this.typeInfo.userTypeId);
        break;
      case TypeId.NAMED_ENUM:
        {
          const typeInfo = this.typeInfo;
          const nsBytes = this.scope.declare("nsBytes", this.builder.metaStringResolver.encodeNamespace(CodecBuilder.replaceBackslashAndQuote(typeInfo.namespace)));
          const typeNameBytes = this.scope.declare("typeNameBytes", this.builder.metaStringResolver.encodeTypeName(CodecBuilder.replaceBackslashAndQuote(typeInfo.typeName)));
          typeMeta = `
              ${this.builder.metaStringResolver.writeBytes(this.builder.writer.ownName(), nsBytes)}
              ${this.builder.metaStringResolver.writeBytes(this.builder.writer.ownName(), typeNameBytes)}
            `;
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

  read(accessor: (expr: string) => string): string {
    if (!this.typeInfo.options?.enumProps) {
      return accessor(this.builder.reader.readVarUInt32());
    }
    const enumValue = this.scope.uniqueName("enum_v");
    return `
        const ${enumValue} = ${this.builder.reader.readVarUInt32()};
        switch(${enumValue}) {
            ${Object.values(this.typeInfo.options.enumProps).map((value, index) => {
      if (typeof value !== "string" && typeof value !== "number") {
        throw new Error("Enum value must be string or number");
      }
      if (typeof value === "number") {
        if (value > MaxUInt32 || value < 0) {
          throw new Error("Enum value must be a valid uint32");
        }
      }
      const safeValue = typeof value === "string" ? `"${value}"` : `${value}`;
      return `
                case ${index}:
                    ${accessor(safeValue)}
                    break;
                `;
    }).join("\n")}
            default:
                throw new Error("Enum received an unexpected value: " + ${enumValue});
        }
    `;
  }

  getFixedSize(): number {
    return 7;
  }
}

CodegenRegistry.register(TypeId.ENUM, EnumSerializerGenerator);
CodegenRegistry.register(TypeId.NAMED_ENUM, EnumSerializerGenerator);
