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

import { TypeId, Mode, RefFlags } from "../type";
import { Scope } from "./scope";
import { CodecBuilder } from "./builder";
import { StructTypeInfo, TypeInfo } from "../typeInfo";
import { CodegenRegistry } from "./router";
import { BaseSerializerGenerator, SerializerGenerator } from "./serializer";
import { TypeMeta } from "../meta/TypeMeta";

const sortProps = (typeInfo: StructTypeInfo) => {
  const names = TypeMeta.fromTypeInfo(typeInfo).getFieldInfo();
  const props = typeInfo.options.props;
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

function toRefMode(trackingRef: boolean, nullable: boolean) {
  if (trackingRef) {
    return RefMode.TRACKING;
  } else if (nullable) {
    return RefMode.NULL_ONLY;
  } else {
    return RefMode.NONE;
  }
}

class StructSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: StructTypeInfo;
  sortedProps: { key: string; typeInfo: TypeInfo }[];
  metaChangedSerializer: string;
  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = <StructTypeInfo>typeInfo;
    this.sortedProps = sortProps(this.typeInfo);
    this.metaChangedSerializer = this.scope.declareVar("metaChangedSerializer", "null");
  }

  readField(fieldName: string, fieldTypeInfo: TypeInfo, assignStmt: (expr: string) => string, embedGenerator: SerializerGenerator, needToWriteRef: boolean) {
    const { nullable = false } = this.typeInfo.options.fieldInfo?.[fieldName] || {};
    let { trackingRef } = this.typeInfo.options.fieldInfo?.[fieldName] || {};
    if (typeof trackingRef !== "boolean") {
      trackingRef = needToWriteRef;
    }
    const refMode = toRefMode(trackingRef, nullable);
    let stmt = "";
    // polymorphic type
    if (fieldTypeInfo.isMonomorphic()) {
      if (refMode == RefMode.TRACKING || refMode === RefMode.NULL_ONLY) {
        stmt = `
            ${embedGenerator.readRef(assignStmt, true)}
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

  writeField(fieldName: string, fieldTypeInfo: TypeInfo, fieldAccessor: string, embedGenerator: SerializerGenerator, needToWriteRef: boolean) {
    const { nullable = false } = this.typeInfo.options.fieldInfo?.[fieldName] || {};
    let { trackingRef } = this.typeInfo.options.fieldInfo?.[fieldName] || {};
    if (typeof trackingRef !== "boolean") {
      trackingRef = needToWriteRef;
    }
    const refMode = toRefMode(trackingRef, nullable);
    let stmt = "";
    // polymorphic type
    if (fieldTypeInfo.isMonomorphic()) {
      if (refMode == RefMode.TRACKING) {
        const noneedWrite = this.scope.uniqueName("noneedWrite");
        stmt = `
            let ${noneedWrite} = false;
            ${embedGenerator.writeRefOrNull(expr => `${noneedWrite} = ${expr}`, fieldAccessor)}
            if (!${noneedWrite}) {
              ${embedGenerator.write(fieldAccessor)}
            }
        `;
      } else if (refMode == RefMode.NULL_ONLY) {
        stmt = `
            if (${fieldAccessor} === null || ${fieldAccessor} === undefined) {
              ${this.builder.writer.int8(RefFlags.NullFlag)}
            } else {
              ${this.builder.writer.int8(RefFlags.NotNullValueFlag)}
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
              ${this.builder.writer.int8(RefFlags.NullFlag)}
            } else {
              ${this.builder.writer.int8(RefFlags.NotNullValueFlag)}
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
    return `
      ${this.sortedProps.map(({ key, typeInfo }) => {
      const InnerGeneratorClass = CodegenRegistry.get(typeInfo.typeId);
      if (!InnerGeneratorClass) {
        throw new Error(`${typeInfo.typeId} generator not exists`);
      }
      const innerGenerator = new InnerGeneratorClass(typeInfo, this.builder, this.scope);

      const fieldAccessor = `${accessor}${CodecBuilder.safePropAccessor(key)}`;
      return this.writeField(key, typeInfo, fieldAccessor, innerGenerator.writeEmbed(), innerGenerator.needToWriteRef());
    }).join(";\n")}
    `;
  }

  read(accessor: (expr: string) => string, refState: string): string {
    const result = this.scope.uniqueName("result");
    return `
      ${this.typeInfo.options.withConstructor
        ? `
          const ${result} = new ${this.builder.getOptions("constructor")}();
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
        const needToWriteRef = innerGenerator.needToWriteRef();
        return this.readField(key, typeInfo, expr => `${result}${CodecBuilder.safePropAccessor(key)} = ${expr}`, innerGenerator.readEmbed(), needToWriteRef);
      }).join(";\n")}
      ${accessor(result)}
    `;
  }

  readNoRef(assignStmt: (v: string) => string, refState: string): string {
    return `
      ${this.readClassInfo()}
      if (${this.metaChangedSerializer} !== null) {
        ${assignStmt(`${this.metaChangedSerializer}.read(${refState})`)}
      } else {
        ${this.read(assignStmt, refState)};
      }
    `;
  }

  readClassInfo(): string {
    const typeMeta = this.scope.uniqueName("typeMeta");
    let namesStmt = "";
    if (!this.builder.fory.isCompatible() && TypeId.isNamedType(this.getTypeId())) {
      namesStmt = `
        ${
          this.builder.metaStringResolver.readNamespace(this.builder.reader.ownName())
        };
        ${
          this.builder.metaStringResolver.readTypeName(this.builder.reader.ownName())
        };
      `;
    }
    let typeMetaStmt = "";
    if (this.builder.fory.isCompatible()) {
      typeMetaStmt = `
      const ${typeMeta} = ${this.builder.typeMetaResolver.readTypeMeta(this.builder.reader.ownName())};
      if (getHash() !== ${typeMeta}.getHash()) {
        ${this.metaChangedSerializer} = ${this.builder.typeMetaResolver.genSerializerByTypeMetaRuntime(typeMeta)}
      }
      `;
    }
    return `
      ${
        this.builder.reader.readVarUint32Small7()
      };
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
              ? this.builder.classResolver.getSerializerByName(CodecBuilder.replaceBackslashAndQuote(this.typeInfo.named!))
              : this.builder.classResolver.getSerializerById(this.typeInfo.typeId)
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
            "tag_ser",
            TypeId.isNamedType(this.typeInfo.typeId)
              ? this.builder.classResolver.getSerializerByName(CodecBuilder.replaceBackslashAndQuote(this.typeInfo.named!))
              : this.builder.classResolver.getSerializerById(this.typeInfo.typeId)
          );
          return `${name}.${prop}(${accessor})`;
        };
      },
    });
  }

  writeClassInfo(): string {
    const internalTypeId = this.getInternalTypeId();
    let typeMeta = "";
    switch (internalTypeId) {
      case TypeId.NAMED_STRUCT:
      case TypeId.NAMED_COMPATIBLE_STRUCT:
        if (this.builder.fory.config.mode !== Mode.Compatible) {
          const typeInfo = this.typeInfo.castToStruct();
          const nsBytes = this.scope.declare("nsBytes", this.builder.metaStringResolver.encodeNamespace(CodecBuilder.replaceBackslashAndQuote(typeInfo.namespace)));
          const typeNameBytes = this.scope.declare("typeNameBytes", this.builder.metaStringResolver.encodeTypeName(CodecBuilder.replaceBackslashAndQuote(typeInfo.typeName)));
          typeMeta = `
            ${this.builder.metaStringResolver.writeBytes(this.builder.writer.ownName(), nsBytes)}
            ${this.builder.metaStringResolver.writeBytes(this.builder.writer.ownName(), typeNameBytes)}
          `;
        } else {
          const bytes = this.scope.declare("typeInfoBytes", `new Uint8Array([${TypeMeta.fromTypeInfo(<StructTypeInfo> this.typeInfo).toBytes().join(",")}])`);
          typeMeta = this.builder.typeMetaResolver.writeTypeMeta(this.builder.getTypeInfo(), this.builder.writer.ownName(), bytes);
        }
        break;
      case TypeId.COMPATIBLE_STRUCT:
        {
          const bytes = this.scope.declare("typeInfoBytes", `new Uint8Array([${TypeMeta.fromTypeInfo(<StructTypeInfo> this.typeInfo).toBytes().join(",")}])`);
          typeMeta = this.builder.typeMetaResolver.writeTypeMeta(this.builder.getTypeInfo(), this.builder.writer.ownName(), bytes);
        }
        break;
      default:
        break;
    }
    return ` 
      ${this.builder.writer.writeVarUint32Small7(this.getTypeId())};
      ${typeMeta}
    `;
  }

  getFixedSize(): number {
    const typeInfo = <StructTypeInfo> this.typeInfo;
    const options = typeInfo.options;
    let fixedSize = 8;
    if (options.props) {
      Object.values(options.props).forEach((x) => {
        const propGenerator = new (CodegenRegistry.get(x.typeId)!)(x, this.builder, this.scope);
        fixedSize += propGenerator.getFixedSize();
      });
    } else {
      fixedSize += this.builder.fory.classResolver.getSerializerByName(typeInfo.named!)!.fixedSize;
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
