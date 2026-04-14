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

/**
 * Returns true when a field's read cannot recurse and needs no depth tracking.
 * Covers leaf scalars, typed arrays, and collections/maps whose elements are all leaf types.
 */
function isDepthFreeField(typeInfo: TypeInfo): boolean {
  const id = typeInfo.typeId;
  if (TypeId.isLeafTypeId(id)) return true;
  // LIST / SET with leaf element type
  if (id === TypeId.LIST || id === TypeId.SET) {
    const inner = typeInfo.options?.inner;
    return !!inner && TypeId.isLeafTypeId(inner.typeId);
  }
  // MAP with leaf key and value types
  if (id === TypeId.MAP) {
    const key = typeInfo.options?.key;
    const value = typeInfo.options?.value;
    return !!key && !!value && TypeId.isLeafTypeId(key.typeId) && TypeId.isLeafTypeId(value.typeId);
  }
  return false;
}

const sortProps = (typeInfo: TypeInfo, typeResolver: CodecBuilder["resolver"]) => {
  const names = TypeMeta.fromTypeInfo(typeInfo, typeResolver).getFieldInfo();
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
  serializerExpr: string;
  ownTypeInfoExpr: string;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
    this.sortedProps = sortProps(this.typeInfo, this.builder.resolver);
    this.metaChangedSerializer = this.scope.declareVar("metaChangedSerializer", "null");
    this.typeMeta = TypeMeta.fromTypeInfo(this.typeInfo, this.builder.resolver);
    // Build an expression that resolves this struct's own serializer at runtime.
    // This is needed so that nested struct generators (e.g., Person inside
    // AddressBook) use their own TypeInfo for meta-share tracking, not the
    // enclosing struct's TypeInfo.
    // Keep the raw expression for self-references (used in read/readNoRef for
    // edge cases). The self-serializer may not be registered yet during factory
    // initialization so we cannot hoist it eagerly.
    this.serializerExpr = TypeId.isNamedType(typeInfo.typeId)
      ? `${this.builder.getTypeResolverName()}.getSerializerByName("${CodecBuilder.replaceBackslashAndQuote(typeInfo.named!)}")`
      : `${this.builder.getTypeResolverName()}.getSerializerById(${typeInfo.typeId}, ${typeInfo.userTypeId})`;
    this.ownTypeInfoExpr = `${this.serializerExpr}.getTypeInfo()`;
  }

  readField(fieldTypeInfo: TypeInfo, assignStmt: (expr: string) => string, embedGenerator: SerializerGenerator) {
    const { nullable = false, dynamic, trackingRef } = fieldTypeInfo;
    const refMode = toRefMode(trackingRef, nullable);
    let stmt = "";
    // polymorphic type
    if (this.builder.resolver.isMonomorphic(fieldTypeInfo, dynamic)) {
      if (refMode == RefMode.TRACKING || refMode === RefMode.NULL_ONLY) {
        stmt = `
          ${embedGenerator.readRefWithoutTypeInfo(assignStmt)}
        `;
      } else if (isDepthFreeField(fieldTypeInfo)) {
        // Leaf types and collections of leaf types cannot recurse — skip depth tracking.
        stmt = embedGenerator.read(assignStmt, "false");
      } else {
        stmt = embedGenerator.readWithDepth(assignStmt, "false");
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
    if (this.builder.resolver.isMonomorphic(fieldTypeInfo, dynamic)) {
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
    if (!this.typeInfo.options?.props || Object.keys(this.typeInfo.options.props).length === 0) {
      const hash = this.typeMeta.computeStructHash();
      return `${!this.builder.resolver.isCompatible() ? this.builder.writer.writeInt32(hash) : ""}`;
    }
    const hash = this.typeMeta.computeStructHash();
    return `
      ${!this.builder.resolver.isCompatible() ? this.builder.writer.writeInt32(hash) : ""}
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
    if (!this.typeInfo.options?.props || Object.keys(this.typeInfo.options.props).length === 0) {
      return `
        let ${result} = ${this.serializerExpr}.read(${refState});
        ${accessor(result)};
      `;
    }
    const hash = this.typeMeta.computeStructHash();
    return `
      ${!this.builder.resolver.isCompatible()
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

  readWithDepth(assignStmt: (v: string) => string, refState: string): string {
    if (!this.typeInfo.options?.props || Object.keys(this.typeInfo.options.props).length === 0) {
      const result = this.scope.uniqueName("result");
      return `
        ${this.builder.getReadContextName()}.incReadDepth();
        let ${result} = ${this.serializerExpr}.read(${refState});
        ${this.builder.getReadContextName()}.decReadDepth();
        ${assignStmt(result)};
      `;
    }
    return super.readWithDepth(assignStmt, refState);
  }

  readNoRef(assignStmt: (v: string) => string, refState: string): string {
    const result = this.scope.uniqueName("result");
    if (!this.typeInfo.options?.props || Object.keys(this.typeInfo.options.props).length === 0) {
      return `
        ${this.readTypeInfo()}
        ${this.builder.getReadContextName()}.incReadDepth();
        let ${result} = ${this.serializerExpr}.read(${refState});
        ${this.builder.getReadContextName()}.decReadDepth();
        ${assignStmt(result)};
      `;
    }
    return `
      ${this.readTypeInfo()}
      ${this.builder.getReadContextName()}.incReadDepth();
      let ${result};
      if (${this.metaChangedSerializer} !== null) {
        ${result} = ${this.metaChangedSerializer}.read(${refState});
      } else {
        ${this.read(v => `${result} = ${v}`, refState)};
      }
      ${this.builder.getReadContextName()}.decReadDepth();
      ${assignStmt(result)};
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
          const ${typeMeta} = ${this.builder.typeMetaResolver.readTypeMeta()};
          if (getHash() !== ${typeMeta}.getHash()) {
            ${this.metaChangedSerializer} = ${this.builder.typeMetaResolver.genSerializerByTypeMetaRuntime(typeMeta)}
          }
          `;
        break;
      case TypeId.NAMED_STRUCT:
        if (!this.builder.resolver.isCompatible()) {
          namesStmt = `
            ${
              this.builder.metaStringResolver.readNamespace()
            };
            ${
              this.builder.metaStringResolver.readTypeName()
            };
          `;
        } else {
          typeMetaStmt = `
          const ${typeMeta} = ${this.builder.typeMetaResolver.readTypeMeta()};
          if (getHash() !== ${typeMeta}.getHash()) {
            ${this.metaChangedSerializer} = ${this.builder.typeMetaResolver.genSerializerByTypeMetaRuntime(typeMeta)}
          }
          `;
        }
        break;
      default:
        if (this.builder.resolver.isCompatible()) {
          typeMetaStmt = `
          const ${typeMeta} = ${this.builder.typeMetaResolver.readTypeMeta()};
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
    // Hoist the serializer lookup into a scope-level const, evaluated once during
    // factory init. This is safe because readEmbed() is called by the PARENT
    // struct whose factory runs after all child serializers are registered.
    const hoisted = this.scope.declare("ser", this.serializerExpr);
    const scope = this.scope;
    const builder = this.builder;
    return new Proxy({}, {
      get: (target, prop: string) => {
        if (prop === "readNoRef") {
          return (accessor: (expr: string) => string, refState: string) => {
            const result = scope.uniqueName("result");
            return `
              ${hoisted}.readTypeInfo();
              ${builder.getReadContextName()}.incReadDepth();
              let ${result} = ${hoisted}.read(${refState});
              ${builder.getReadContextName()}.decReadDepth();
              ${accessor(result)};
            `;
          };
        }
        if (prop === "readRef") {
          return (accessor: (expr: string) => string) => {
            const refFlag = scope.uniqueName("refFlag");
            const result = scope.uniqueName("result");
            return `
              const ${refFlag} = ${builder.reader.readInt8()};
              let ${result};
              if (${refFlag} === ${RefFlags.NullFlag}) {
                ${result} = null;
              } else if (${refFlag} === ${RefFlags.RefFlag}) {
                ${result} = ${builder.referenceResolver.getReadRef(builder.reader.readVarUInt32())};
              } else {
                ${hoisted}.readTypeInfo();
                ${builder.getReadContextName()}.incReadDepth();
                ${result} = ${hoisted}.read(${refFlag} === ${RefFlags.RefValueFlag});
                ${builder.getReadContextName()}.decReadDepth();
              }
              ${accessor(result)};
            `;
          };
        }
        if (prop === "readWithDepth") {
          return (accessor: (expr: string) => string, refState: string) => {
            const result = scope.uniqueName("result");
            return `
              ${builder.getReadContextName()}.incReadDepth();
              let ${result} = ${hoisted}.read(${refState});
              ${builder.getReadContextName()}.decReadDepth();
              ${accessor(result)};
            `;
          };
        }
        return (accessor: (expr: string) => string, ...args: string[]) => {
          return accessor(`${hoisted}.${prop}(${args.join(",")})`);
        };
      },
    });
  }

  writeEmbed() {
    // Hoist the serializer lookup — safe because writeEmbed() is used by
    // the parent struct whose factory runs after child serializers exist.
    const hoisted = this.scope.declare("ser", this.serializerExpr);
    const scope = this.scope;
    return new Proxy({}, {
      get: (target, prop: string) => {
        if (prop === "writeNoRef") {
          return (accessor: string) => {
            return `
              ${hoisted}.writeTypeInfo(${accessor});
              ${hoisted}.write(${accessor});
            `;
          };
        }
        if (prop === "writeRef") {
          return (accessor: string) => {
            const noneedWrite = scope.uniqueName("noneedWrite");
            return `
              let ${noneedWrite} = ${hoisted}.writeRefOrNull(${accessor});
              if (!${noneedWrite}) {
                ${hoisted}.writeTypeInfo(${accessor});
                ${hoisted}.write(${accessor});
              }
            `;
          };
        }
        return (accessor: string, ...args: any) => {
          if (prop === "writeRefOrNull") {
            return args[0](`${hoisted}.${prop}(${accessor})`);
          }
          return `${hoisted}.${prop}(${accessor})`;
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
          const bytes = this.scope.declare("typeInfoBytes", `new Uint8Array([${TypeMeta.fromTypeInfo(this.typeInfo, this.builder.resolver).toBytes().join(",")}])`);
          typeMeta = this.builder.typeMetaResolver.writeTypeMeta(this.builder.getTypeInfo(), bytes);
        }
        break;
      case TypeId.NAMED_STRUCT:
        {
          if (!this.builder.resolver.isCompatible()) {
            const typeInfo = this.typeInfo;
            const nsBytes = this.scope.declare("nsBytes", this.builder.metaStringResolver.encodeNamespace(CodecBuilder.replaceBackslashAndQuote(typeInfo.namespace)));
            const typeNameBytes = this.scope.declare("typeNameBytes", this.builder.metaStringResolver.encodeTypeName(CodecBuilder.replaceBackslashAndQuote(typeInfo.typeName)));
            typeMeta = `
              ${this.builder.metaStringResolver.writeBytes(nsBytes)}
              ${this.builder.metaStringResolver.writeBytes(typeNameBytes)}
            `;
          } else {
            const bytes = this.scope.declare("typeInfoBytes", `new Uint8Array([${TypeMeta.fromTypeInfo(this.typeInfo, this.builder.resolver).toBytes().join(",")}])`);
            typeMeta = this.builder.typeMetaResolver.writeTypeMeta(this.builder.getTypeInfo(), bytes);
          }
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
      fixedSize += this.builder.resolver.getSerializerByName(typeInfo.named!)!.fixedSize;
    }
    return fixedSize;
  }

  getHash(): string {
    return TypeMeta.fromTypeInfo(this.typeInfo, this.builder.resolver).getHash().toString();
  }
}

CodegenRegistry.register(TypeId.STRUCT, StructSerializerGenerator);
CodegenRegistry.register(TypeId.NAMED_STRUCT, StructSerializerGenerator);
CodegenRegistry.register(TypeId.COMPATIBLE_STRUCT, StructSerializerGenerator);
CodegenRegistry.register(TypeId.NAMED_COMPATIBLE_STRUCT, StructSerializerGenerator);
