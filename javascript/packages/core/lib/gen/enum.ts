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
import { TypeMeta } from "../meta/TypeMeta";

class EnumSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
  }

  private getEnumEntries(): Array<[string, string | number]> {
    const enumProps = this.typeInfo.options?.enumProps;
    if (!enumProps) {
      return [];
    }
    return Object.entries(enumProps).filter(([key, value]) => {
      return !(typeof value === "string" && Number.isInteger(Number(key)));
    });
  }

  private useExplicitNumericWireValues(entries: Array<[string, string | number]>): boolean {
    if (entries.length < 1) {
      throw new Error("An enum must contain at least one field");
    }
    const seen = new Set<number>();
    for (const [, value] of entries) {
      if (typeof value === "string") {
        return false;
      }
      if (!Number.isInteger(value) || value > MaxUInt32 || value < 0) {
        throw new Error("Enum value must be a valid uint32");
      }
      if (seen.has(value)) {
        throw new Error("Enum numeric values must be unique");
      }
      seen.add(value);
    }
    return true;
  }

  write(accessor: string): string {
    if (!this.typeInfo.options?.enumProps) {
      return this.builder.writer.writeVarUInt32(accessor);
    }
    const enumEntries = this.getEnumEntries();
    const useExplicitNumericWireValues = this.useExplicitNumericWireValues(enumEntries);
    return `
        ${enumEntries.map(([, value], index) => {
      if (typeof value !== "string" && typeof value !== "number") {
        throw new Error("Enum value must be string or number");
      }
      if (typeof value === "number") {
        if (value > MaxUInt32 || value < 0) {
          throw new Error("Enum value must be a valid uint32");
        }
      }
      const safeValue = typeof value === "string" ? `"${value}"` : value;
      const wireValue = useExplicitNumericWireValues ? safeValue : index;
      return ` if (${accessor} === ${safeValue}) {
                    ${this.builder.writer.writeVarUInt32(wireValue)}
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
        if (this.builder.resolver.isCompatible()) {
          namesStmt = `
            ${this.builder.typeMetaResolver.readNamedTypeMeta(
              TypeId.NAMED_ENUM,
              this.typeInfo.namespace,
              this.typeInfo.typeName,
            )};
          `;
        } else {
          namesStmt = `
            ${
              // skip the namespace
              this.builder.metaStringResolver.readNamespace()
            }

            ${
            // skip the type name
            this.builder.metaStringResolver.readTypeName()
            }
          `;
        }
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
        if (this.builder.resolver.isCompatible()) {
          const bytes = this.scope.declare("enumTypeInfoBytes", `new Uint8Array([${TypeMeta.fromTypeInfo(this.typeInfo, this.builder.resolver).toBytes().join(",")}])`);
          typeMeta = this.builder.typeMetaResolver.writeTypeMeta(this.builder.getTypeInfo(), bytes);
        } else {
          const typeInfo = this.typeInfo;
          const nsBytes = this.scope.declare("nsBytes", this.builder.metaStringResolver.encodeNamespace(CodecBuilder.replaceBackslashAndQuote(typeInfo.namespace)));
          const typeNameBytes = this.scope.declare("typeNameBytes", this.builder.metaStringResolver.encodeTypeName(CodecBuilder.replaceBackslashAndQuote(typeInfo.typeName)));
          typeMeta = `
              ${this.builder.metaStringResolver.writeBytes(nsBytes)}
              ${this.builder.metaStringResolver.writeBytes(typeNameBytes)}
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
    const enumEntries = this.getEnumEntries();
    const useExplicitNumericWireValues = this.useExplicitNumericWireValues(enumEntries);
    const enumValue = this.scope.uniqueName("enum_v");
    return `
        const ${enumValue} = ${this.builder.reader.readVarUInt32()};
        switch(${enumValue}) {
            ${enumEntries.map(([, value], index) => {
      if (typeof value !== "string" && typeof value !== "number") {
        throw new Error("Enum value must be string or number");
      }
      if (typeof value === "number") {
        if (value > MaxUInt32 || value < 0) {
          throw new Error("Enum value must be a valid uint32");
        }
      }
      const safeValue = typeof value === "string" ? `"${value}"` : `${value}`;
      const wireValue = useExplicitNumericWireValues ? safeValue : `${index}`;
      return `
                case ${wireValue}:
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
