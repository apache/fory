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

import { TypeId, Mode } from "../type";
import { CodecBuilder } from "./builder";
import { RefFlags } from "../type";
import { Scope } from "./scope";
import { TypeInfo, StructTypeInfo } from "../typeInfo";
import { TypeMeta } from "../meta/TypeMeta";
import { BinaryWriter } from "../writer";

export const makeHead = (flag: RefFlags, typeId: number) => {
  const writer = new BinaryWriter();
  writer.uint8(flag);
  writer.writeVarUint32Small7(typeId);
  const buffer = writer.dump();
  return buffer;
};

export interface SerializerGenerator {
  toSerializer(): string;
  getFixedSize(): number;
  needToWriteRef(): boolean;
  getType(): number;
  getTypeId(): number | undefined;
  toWriteEmbed(accessor: string, excludeHead?: boolean): string;
  toReadEmbed(accessor: (expr: string) => string, excludeHead?: boolean, refState?: RefState): string;
}

export enum RefStateType {
  Condition = "condition",
  True = "true",
  False = "false",
}
export class RefState {
  constructor(private state: RefStateType, private conditionAccessor?: string) {

  }

  getState() {
    return this.state;
  }

  getCondition() {
    return this.conditionAccessor;
  }

  static fromCondition(conditionAccessor: string) {
    return new RefState(RefStateType.Condition, conditionAccessor);
  }

  static fromTrue() {
    return new RefState(RefStateType.True);
  }

  static fromFalse() {
    return new RefState(RefStateType.False);
  }

  static fromBool(v: boolean) {
    return v ? new RefState(RefStateType.True) : new RefState(RefStateType.False);
  }

  toConditionExpr() {
    const ref = this!.getState();
    if (ref === RefStateType.Condition) {
      return this.conditionAccessor;
    } else if (ref === RefStateType.False) {
      return "false";
    } else {
      return "true";
    }
  }

  wrap(inner: (need: boolean) => string) {
    const ref = this!.getState();
    if (ref === RefStateType.Condition) {
      return `
        if (${this.conditionAccessor}) {
          ${inner(true)}
        } else {
          ${inner(false)}
        }
      `;
    } else if (ref === RefStateType.False) {
      return inner(false);
    } else {
      return inner(true);
    }
  }
}

export abstract class BaseSerializerGenerator implements SerializerGenerator {
  constructor(
    protected typeInfo: TypeInfo,
    protected builder: CodecBuilder,
    protected scope: Scope,
  ) {

  }

  abstract getFixedSize(): number;

  abstract needToWriteRef(): boolean;

  abstract writeStmt(accessor: string): string;

  abstract readStmt(accessor: (expr: string) => string, refState: RefState): string;

  getType() {
    return this.typeInfo.typeId;
  }

  getTypeId() {
    return this.typeInfo.typeId;
  }

  protected maybeReference(accessor: string, refState: RefState) {
    if (refState.getState() === RefStateType.False) {
      return "";
    }
    if (refState.getState() === RefStateType.True) {
      return this.builder.referenceResolver.reference(accessor);
    }
    if (refState.getState() === RefStateType.Condition) {
      return `
        if (${refState.getCondition()}) {
          ${this.builder.referenceResolver.reference(accessor)}
        }
      `;
    }
  }

  protected wrapWriteHead(accessor: string, stmt: (accessor: string) => string) {
    if (!this.typeInfo.typeId) {
      throw new Error("typeId not provided, write failed");
    }
    const maybeNamed = () => {
      if (!TypeId.IS_NAMED_TYPE(this.typeInfo.typeId!)) {
        return "";
      }
      const typeInfo = this.typeInfo.castToStruct();
      const nsBytes = this.scope.declare("nsBytes", this.builder.metaStringResolver.encodeNamespace(CodecBuilder.replaceBackslashAndQuote(typeInfo.namespace)));
      const typeNameBytes = this.scope.declare("typeNameBytes", this.builder.metaStringResolver.encodeTypeName(CodecBuilder.replaceBackslashAndQuote(typeInfo.typeName)));
      return `
        ${this.builder.metaStringResolver.writeBytes(this.builder.writer.ownName(), nsBytes)}
        ${this.builder.metaStringResolver.writeBytes(this.builder.writer.ownName(), typeNameBytes)}
      `;
    };

    const maybeCompatiable = () => {
      if (this.builder.fory.config.mode === Mode.Compatible && (this.typeInfo.typeId === TypeId.STRUCT || this.typeInfo.typeId === TypeId.NAMED_STRUCT)) {
        const bytes = this.scope.declare("typeInfoBytes", `new Uint8Array([${TypeMeta.fromTypeInfo(<StructTypeInfo> this.typeInfo).toBytes().join(",")}])`);
        return this.builder.typeMetaResolver.writeTypeMeta(this.builder.getTypeInfo(), this.builder.writer.ownName(), bytes);
      }
      return "";
    };

    if (this.needToWriteRef()) {
      const head = makeHead(RefFlags.RefValueFlag, this.typeInfo.typeId);
      const bytes = this.scope.declare("headBytes", `new Uint8Array([${head.join(",")}])`);
      const existsId = this.scope.uniqueName("existsId");
      return `
                if (${accessor} !== null && ${accessor} !== undefined) {
                    const ${existsId} = ${this.builder.referenceResolver.existsWriteObject(accessor)};
                    if (typeof ${existsId} === "number") {
                        ${this.builder.writer.int8(RefFlags.RefFlag)}
                        ${this.builder.writer.varUInt32(existsId)}
                    } else {
                        ${this.builder.referenceResolver.writeRef(accessor)}
                        ${this.builder.writer.buffer(bytes)};
                        ${maybeNamed()}
                        ${maybeCompatiable()}
                        ${stmt(accessor)};
                    }
                } else {
                    ${this.builder.writer.int8(RefFlags.NullFlag)};
                }
                `;
    } else {
      const head = makeHead(RefFlags.NotNullValueFlag, this.typeInfo.typeId);
      const bytes = this.scope.declare("headBytes", `new Uint8Array([${head.join(",")}])`);
      return `
            if (${accessor} !== null && ${accessor} !== undefined) {
                ${this.builder.writer.buffer(bytes)};
                ${maybeNamed()}
                ${maybeCompatiable()}
                ${stmt(accessor)};
            } else {
                ${this.builder.writer.int8(RefFlags.NullFlag)};
            }`;
    }
  }

  private wrapReadHead(accessor: (expr: string) => string, stmt: (accessor: (expr: string) => string, refState: RefState) => string) {
    const refFlag = this.scope.uniqueName("refFlag");
    const ns = this.scope.uniqueName("ns");
    const typeName = this.scope.uniqueName("typeName");
    const typeId = this.scope.uniqueName("typeId");

    const typeMeta = (this.builder.fory.config.mode !== Mode.SchemaConsistent && this.scope.uniqueName("typeMeta")) || "";
    const lastestHash = (this.builder.fory.config.mode !== Mode.SchemaConsistent && this.scope.declare("hash_serializer", `{
      hash: ${(this.typeInfo.castToStruct()).hash.toString()},
      serializer: ${this.builder.classResolver.getSerializerByName(CodecBuilder.replaceBackslashAndQuote((this.typeInfo.castToStruct()).named!))},
    }`)) || "";

    return `
      const ${refFlag} = ${this.builder.reader.int8()};
      switch (${refFlag}) {
          case ${RefFlags.NotNullValueFlag}:
          case ${RefFlags.RefValueFlag}:
              const ${typeId} = ${this.builder.reader.readVarUint32Small7()};
              let ${ns};
              let ${typeName};
              if (${typeId} === ${TypeId.NAMED_STRUCT} || ${typeId} === ${TypeId.NAMED_ENUM}) {
                ${ns} = ${this.builder.metaStringResolver.readNamespace(this.builder.reader.ownName())};
                ${typeName} = ${this.builder.metaStringResolver.readTypeName(this.builder.reader.ownName())};
              }
              ${
                this.builder.fory.config.mode === Mode.Compatible && (this.typeInfo.typeId === TypeId.STRUCT || this.typeInfo.typeId === TypeId.NAMED_STRUCT)
                ? `
                  const ${typeMeta} = ${this.builder.typeMetaResolver.readTypeMeta(this.builder.reader.ownName())};
                  if (${lastestHash}.hash === ${typeMeta}.getHash()) {
                      return ${lastestHash}.serializer.readInner(${RefState.fromCondition(`${refFlag} === ${RefFlags.RefValueFlag}`).toConditionExpr()});
                  } else {
                      ${lastestHash}.serializer = ${this.builder.typeMetaResolver.genSerializerByTypeMetaRuntime(typeMeta, ns, typeName)};
                      ${lastestHash}.hash = ${typeMeta}.getHash();
                  }
                  ${accessor(`${lastestHash}.serializer.readInner(${RefState.fromCondition(`${refFlag} === ${RefFlags.RefValueFlag}`).toConditionExpr()})`)};
                `
: `
                  ${stmt(accessor, RefState.fromCondition(`${refFlag} === ${RefFlags.RefValueFlag}`))}
                `
              }
              break;
          case ${RefFlags.RefFlag}:
              ${accessor(this.builder.referenceResolver.getReadObject(this.builder.reader.varUInt32()))}
              break;
          case ${RefFlags.NullFlag}:
              ${accessor("null")}
              break;
      }
      `;
  }

  toWriteEmbed(accessor: string, excludeHead = false) {
    if (excludeHead) {
      return this.writeStmt(accessor);
    }
    return this.wrapWriteHead(accessor, accessor => this.writeStmt(accessor));
  }

  toReadEmbed(accessor: (expr: string) => string, excludeHead = false, refState?: RefState) {
    if (excludeHead) {
      return this.readStmt(accessor, refState!);
    }
    return this.wrapReadHead(accessor, (accessor, refState) => this.readStmt(accessor, refState));
  }

  toSerializer() {
    this.scope.assertNameNotDuplicate("read");
    this.scope.assertNameNotDuplicate("readInner");
    this.scope.assertNameNotDuplicate("write");
    this.scope.assertNameNotDuplicate("writeInner");
    this.scope.assertNameNotDuplicate("fory");
    this.scope.assertNameNotDuplicate("external");
    this.scope.assertNameNotDuplicate("options");
    this.scope.assertNameNotDuplicate("typeInfo");

    const declare = `
      const readInner = (fromRef) => {
        ${this.readStmt(x => `return ${x}`, RefState.fromCondition("fromRef"))}
      };
      const read = () => {
        ${this.wrapReadHead(x => `return ${x}`, (accessor, refState) => accessor(`readInner(${refState.getCondition()})`))}
      };
      const writeInner = (v) => {
        ${this.writeStmt("v")}
      };
      const write = (v) => {
        ${this.wrapWriteHead("v", accessor => `writeInner(${accessor})`)}
      };
    `;
    return `
        return function (fory, external, typeInfo, options) {
            ${this.scope.generate()}
            ${declare}
            return {
              read,
              readInner,
              write,
              writeInner,
              fixedSize: ${this.getFixedSize()},
              needToWriteRef: () => ${this.needToWriteRef()},
              getTypeId: () => ${this.getTypeId()}
            };
        }
        `;
  }
}
