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

import { TypeId, RefFlags } from "../type";
import { Scope } from "./scope";
import { CodecBuilder } from "./builder";
import { TypeInfo } from "../typeInfo";
import { CodegenRegistry } from "./router";
import { BaseSerializerGenerator, SerializerGenerator } from "./serializer";
import { TypeMeta } from "../meta/TypeMeta";

const sortProps = (typeInfo: TypeInfo) => {
  const names = TypeMeta.fromTypeInfo(typeInfo).getFieldInfo();
  const props = typeInfo.options!.props;
  return names.map((x) => {
    return {
      key: x.fieldName,
      typeInfo: props![x.fieldName],
    };
  });
};

enum RefMode {
  /** No ref tracking, field is non-nullable. Write data directly. */
  NONE,

  /** Field is nullable but no ref tracking. Write null/not-null flag then data. */
  NULL_ONLY,

  /** Ref tracking enabled (implies nullable). Write ref flags and handle shared references. */
  TRACKING,

}

function toRefMode(trackingRef?: boolean, nullable?: boolean) {
  if (trackingRef) {
    return RefMode.TRACKING;
  } else if (nullable) {
    return RefMode.NULL_ONLY;
  } else {
    return RefMode.NONE;
  }
}

class StructSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;
  sortedProps: { key: string; typeInfo: TypeInfo }[];
  metaChangedSerializer: string;
  typeMeta: TypeMeta;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
    this.sortedProps = sortProps(this.typeInfo);
    this.metaChangedSerializer = this.scope.declareVar("metaChangedSerializer", "null");
    this.typeMeta = TypeMeta.fromTypeInfo(this.typeInfo);
  }

  readField(fieldTypeInfo: TypeInfo, assignStmt: (expr: string) => string, embedGenerator: SerializerGenerator) {
    const { nullable = false, dynamic, trackingRef } = fieldTypeInfo;
    const refMode = toRefMode(trackingRef, nullable);
    let stmt = "";
    // polymorphic type
    if (fieldTypeInfo.isMonomorphic(dynamic)) {
      if (refMode == RefMode.TRACKING || refMode === RefMode.NULL_ONLY) {
        stmt = `
          ${embedGenerator.readRefWithoutTypeInfo(assignStmt)}
        `;
      } else {
        stmt = embedGenerator.read(assignStmt, "false");
      }
    } else {
      if (refMode == RefMode.TRACKING || refMode === RefMode.NULL_ONLY) {
        stmt = `${embedGenerator.readRef(assignStmt)}`;
      } else {
        stmt = embedGenerator.readNoRef(assignStmt, "false");
      }
    }
    return stmt;
  }

  writeField(fieldName: string, fieldTypeInfo: TypeInfo, fieldAccessor: string, embedGenerator: SerializerGenerator) {
    const { nullable = false, dynamic, trackingRef } = fieldTypeInfo;
    const refMode = toRefMode(trackingRef, nullable);
    let stmt = "";
    // polymorphic type
    if (fieldTypeInfo.isMonomorphic(dynamic)) {
      if (refMode == RefMode.TRACKING) {
        const noneedWrite = this.scope.uniqueName("noneedWrite");
        stmt = `
            let ${noneedWrite} = false;
            ${embedGenerator.writeRefOrNull(fieldAccessor, expr => `${noneedWrite} = ${expr}`)}
            if (!${noneedWrite}) {
              ${embedGenerator.write(fieldAccessor)}
            }
        `;
      } else if (refMode == RefMode.NULL_ONLY) {
        stmt = `
            if (${fieldAccessor} === null || ${fieldAccessor} === undefined) {
              ${this.builder.writer.writeInt8(RefFlags.NullFlag)}
            } else {
              ${this.builder.writer.writeInt8(RefFlags.NotNullValueFlag)}
              ${embedGenerator.write(fieldAccessor)}
            }
          `;
      } else {
        stmt = `
            if (${fieldAccessor} === null || ${fieldAccessor} === undefined) {
              throw new Error('Field ${CodecBuilder.safeString(fieldName)} is not nullable');
            } else {
              ${embedGenerator.write(fieldAccessor)}
            }
          `;
      }
    } else {
      if (refMode == RefMode.TRACKING) {
        stmt = `${embedGenerator.writeRef(fieldAccessor)}`;
      } else if (refMode == RefMode.NULL_ONLY) {
        stmt = `
            if (${fieldAccessor} === null || ${fieldAccessor} === undefined) {
              ${this.builder.writer.writeInt8(RefFlags.NullFlag)}
            } else {
              ${this.builder.writer.writeInt8(RefFlags.NotNullValueFlag)}
              ${embedGenerator.writeNoRef(fieldAccessor)}
            }
          `;
      } else {
        stmt = `
            if (${fieldAccessor} === null || ${fieldAccessor} === undefined) {
              throw new Error('Field ${CodecBuilder.safeString(fieldName)} is not nullable');
            } else {
              ${embedGenerator.writeNoRef(fieldAccessor)}
            }
          `;
      }
    }
    return stmt;
  }

  write(accessor: string): string {
    const hash = this.typeMeta.computeStructHash();
    return `
      ${!this.builder.fory.isCompatible() ? this.builder.writer.writeInt32(hash) : ""}
      ${this.sortedProps.map(({ key, typeInfo }) => {
      const InnerGeneratorClass = CodegenRegistry.get(typeInfo.typeId);
      if (!InnerGeneratorClass) {
        throw new Error(`${typeInfo.typeId} generator not exists`);
      }
      const innerGenerator = new InnerGeneratorClass(typeInfo, this.builder, this.scope);

      const fieldAccessor = `${accessor}${CodecBuilder.safePropAccessor(key)}`;
      return this.writeField(key, typeInfo, fieldAccessor, innerGenerator.writeEmbed());
    }).join(";\n")}
    `;
  }

  read(accessor: (expr: string) => string, refState: string): string {
    const result = this.scope.uniqueName("result");
    const hash = this.typeMeta.computeStructHash();
    return `
      ${!this.builder.fory.isCompatible()
? `
        if(${this.builder.reader.readInt32()} !== ${hash}) {
          throw new Error("Read class version is not consistent with ${hash} ")
        }
      `
: ""}
      ${this.typeInfo.options!.withConstructor
        ? `
          const ${result} = new ${this.builder.getOptions("creator")}();
        `
        : `
          const ${result} = {
            ${this.sortedProps.map(({ key }) => {
          return `${CodecBuilder.safePropName(key)}: null`;
        }).join(",\n")}
          };
        `
      }
      ${this.maybeReference(result, refState)}
      ${this.sortedProps.map(({ key, typeInfo }) => {
        const InnerGeneratorClass = CodegenRegistry.get(typeInfo.typeId);
        if (!InnerGeneratorClass) {
          throw new Error(`${typeInfo.typeId} generator not exists`);
        }
        const innerGenerator = new InnerGeneratorClass(typeInfo, this.builder, this.scope);
        return this.readField(typeInfo, expr => `${result}${CodecBuilder.safePropAccessor(key)} = ${expr}`, innerGenerator.readEmbed());
      }).join(";\n")}
      ${accessor(result)}
    `;
  }

  readNoRef(assignStmt: (v: string) => string, refState: string): string {
    return `
      ${this.readTypeInfo()}
      if (${this.metaChangedSerializer} !== null) {
        ${assignStmt(`${this.metaChangedSerializer}.read(${refState})`)}
      } else {
        ${this.read(assignStmt, refState)};
      }
    `;
  }

  readTypeInfo(): string {
    const typeMeta = this.scope.uniqueName("typeMeta");
    const internalTypeId = this.getInternalTypeId();
    let namesStmt = "";
    let typeMetaStmt = "";
    let readUserTypeIdStmt = "";
    switch (internalTypeId) {
      case TypeId.STRUCT:
        readUserTypeIdStmt = `${this.builder.reader.readVarUint32Small7()};`;
        break;
      case TypeId.NAMED_COMPATIBLE_STRUCT:
      case TypeId.COMPATIBLE_STRUCT:
        typeMetaStmt = `
          const ${typeMeta} = ${this.builder.typeMetaResolver.readTypeMeta(this.builder.reader.ownName())};
          if (getHash() !== ${typeMeta}.getHash()) {
            ${this.metaChangedSerializer} = ${this.builder.typeMetaResolver.genSerializerByTypeMetaRuntime(typeMeta)}
          }
          `;
        break;
      case TypeId.NAMED_STRUCT:
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
          if (getHash() !== ${typeMeta}.getHash()) {
            ${this.metaChangedSerializer} = ${this.builder.typeMetaResolver.genSerializerByTypeMetaRuntime(typeMeta)}
          }
          `;
        }
        break;
      default:
        if (this.builder.fory.isCompatible()) {
          typeMetaStmt = `
          const ${typeMeta} = ${this.builder.typeMetaResolver.readTypeMeta(this.builder.reader.ownName())};
          if (getHash() !== ${typeMeta}.getHash()) {
            ${this.metaChangedSerializer} = ${this.builder.typeMetaResolver.genSerializerByTypeMetaRuntime(typeMeta)}
          }
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
            "tag_ser",
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
        return (accessor: string, ...args: any) => {
          const name = this.scope.declare(
            "tag_ser",
            TypeId.isNamedType(this.typeInfo.typeId)
              ? this.builder.typeResolver.getSerializerByName(CodecBuilder.replaceBackslashAndQuote(this.typeInfo.named!))
              : this.builder.typeResolver.getSerializerById(this.typeInfo.typeId, this.typeInfo.userTypeId)
          );
          if (prop === "writeRefOrNull") {
            return args[0](`${name}.${prop}(${accessor})`);
          }
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
      case TypeId.STRUCT:
        writeUserTypeIdStmt = this.builder.writer.writeVarUint32Small7(this.typeInfo.userTypeId);
        break;
      case TypeId.NAMED_COMPATIBLE_STRUCT:
      case TypeId.COMPATIBLE_STRUCT:
        {
          const bytes = this.scope.declare("typeInfoBytes", `new Uint8Array([${TypeMeta.fromTypeInfo(this.typeInfo).toBytes().join(",")}])`);
          typeMeta = this.builder.typeMetaResolver.writeTypeMeta(this.builder.getTypeInfo(), this.builder.writer.ownName(), bytes);
        }
        break;
      case TypeId.NAMED_STRUCT:
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
    const typeInfo = this.typeInfo;
    const options = typeInfo.options;
    let fixedSize = 8;
    if (options!.props) {
      Object.values(options!.props).forEach((x) => {
        const propGenerator = new (CodegenRegistry.get(x.typeId)!)(x, this.builder, this.scope);
        fixedSize += propGenerator.getFixedSize();
      });
    } else {
      fixedSize += this.builder.fory.typeResolver.getSerializerByName(typeInfo.named!)!.fixedSize;
    }
    return fixedSize;
  }

  getHash(): string {
    return TypeMeta.fromTypeInfo(this.typeInfo).getHash().toString();
  }
}

CodegenRegistry.register(TypeId.STRUCT, StructSerializerGenerator);
CodegenRegistry.register(TypeId.NAMED_STRUCT, StructSerializerGenerator);
CodegenRegistry.register(TypeId.COMPATIBLE_STRUCT, StructSerializerGenerator);
CodegenRegistry.register(TypeId.NAMED_COMPATIBLE_STRUCT, StructSerializerGenerator);
