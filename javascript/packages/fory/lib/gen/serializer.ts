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

import { CodecBuilder } from "./builder";
import { RefFlags, TypeId } from "../type";
import { Scope } from "./scope";
import { TypeInfo } from "../typeInfo";
import { refTrackingUnableTypeId } from "../meta/TypeMeta";
import { BinaryWriter } from "../writer";

export const makeHead = (flag: RefFlags, typeId: number) => {
  const writer = new BinaryWriter();
  writer.writeUint8(flag);
  writer.writeUint8(typeId);
  const buffer = writer.dump();
  return buffer;
};

export interface SerializerGenerator {
  writeRef(accessor: string): string;
  writeNoRef(accessor: string): string;
  writeRefOrNull(accessor: string, assignStmt: (v: string) => string): string;
  writeTypeInfo(accessor: string): string;
  write(accessor: string): string;
  writeEmbed(): any;

  toSerializer(): string;
  getFixedSize(): number;
  needToWriteRef(): boolean;

  readRef(assignStmt: (v: string) => string): string;
  readRefWithoutTypeInfo(assignStmt: (v: string) => string): string;
  readNoRef(assignStmt: (v: string) => string, refState: string): string;
  readTypeInfo(): string;
  read(assignStmt: (v: string) => string, refState: string): string;
  readEmbed(): any;
  getHash(): string;

  getType(): number;
  getTypeId(): number | undefined;
}

export enum RefStateType {
  Condition = "condition",
  True = "true",
  False = "false",
}
export abstract class BaseSerializerGenerator implements SerializerGenerator {
  constructor(
    protected typeInfo: TypeInfo,
    protected builder: CodecBuilder,
    protected scope: Scope,
  ) {

  }

  abstract getFixedSize(): number;

  needToWriteRef(): boolean {
    if (refTrackingUnableTypeId(this.typeInfo.typeId)) {
      return false;
    }
    if (this.builder.fory.config.refTracking !== true) {
      return false;
    }
    if (typeof this.typeInfo.trackingRef === "boolean") {
      return this.typeInfo.trackingRef;
    }
    return true;
  }

  abstract write(accessor: string): string;

  writeEmbed() {
    const obj = {};
    return new Proxy(obj, {
      get: (target, prop) => {
        return (...args: any[]) => {
          return (this as any)[prop](...args);
        };
      },
    });
  }

  readEmbed() {
    const obj = {};
    return new Proxy(obj, {
      get: (target, prop) => {
        return (...args: any[]) => {
          return (this as any)[prop](...args);
        };
      },
    });
  }

  writeRef(accessor: string) {
    const noneedWrite = this.scope.uniqueName("noneedWrite");
    return `
      let ${noneedWrite} = false;
      ${this.writeRefOrNull(accessor, expr => `${noneedWrite} = ${expr}`)}
      if (!${noneedWrite}) {
        ${this.writeNoRef(accessor)}
      }
    `;
  }

  writeNoRef(accessor: string) {
    return `
      ${this.writeTypeInfo(accessor)};
      ${this.write(accessor)};
    `;
  }

  writeRefOrNull(accessor: string, assignStmt: (expr: string) => string) {
    let refFlagStmt = "";
    if (this.needToWriteRef()) {
      const existsId = this.scope.uniqueName("existsId");
      refFlagStmt = `
        const ${existsId} = ${this.builder.referenceResolver.existsWriteObject(accessor)};
        if (typeof ${existsId} === "number") {
            ${this.builder.writer.writeInt8(RefFlags.RefFlag)}
            ${this.builder.writer.writeVarUInt32(existsId)}
            ${assignStmt("true")};
        } else {
            ${this.builder.writer.writeInt8(RefFlags.RefValueFlag)}
            ${this.builder.referenceResolver.writeRef(accessor)}
        }
      `;
    } else {
      refFlagStmt = this.builder.writer.writeInt8(RefFlags.NotNullValueFlag);
    }
    return `
      if (${accessor} === null || ${accessor} === undefined) {
        ${this.builder.writer.writeInt8(RefFlags.NullFlag)};
        ${assignStmt("true")};
      } else {
        ${refFlagStmt}
      }
    `;
  }

  writeTypeInfo(accessor: string) {
    void accessor;
    const typeId = this.getTypeId();
    const userTypeId = this.typeInfo.userTypeId;
    const userTypeStmt = TypeId.needsUserTypeId(typeId) && typeId !== TypeId.COMPATIBLE_STRUCT
      ? this.builder.writer.writeVarUint32Small7(userTypeId)
      : "";
    return ` 
      ${this.builder.writer.writeUint8(typeId)};
      ${userTypeStmt}
    `;
  }

  getType() {
    return this.typeInfo.typeId;
  }

  getTypeId() {
    return this.typeInfo.typeId;
  }

  getUserTypeId() {
    return this.typeInfo.userTypeId;
  }

  getInternalTypeId() {
    return this.getTypeId();
  }

  abstract read(assignStmt: (v: string) => string, refState: string): string;

  readTypeInfo(): string {
    const typeId = this.getTypeId();
    const readUserTypeStmt = TypeId.needsUserTypeId(typeId) && typeId !== TypeId.COMPATIBLE_STRUCT
      ? `${this.builder.reader.readVarUint32Small7()};`
      : "";
    return `
      ${this.builder.reader.readUint8()};
      ${readUserTypeStmt}
    `;
  }

  readNoRef(assignStmt: (v: string) => string, refState: string): string {
    return `
      fory.incReadDepth();
      try {
        ${this.readTypeInfo()}
        ${this.read(assignStmt, refState)};
      } finally {
        fory.decReadDepth();
      }
    `;
  }

  readRefWithoutTypeInfo(assignStmt: (v: string) => string): string {
    const refFlag = this.scope.uniqueName("refFlag");
    return `
        const ${refFlag} = ${this.builder.reader.readInt8()};
        switch (${refFlag}) {
            case ${RefFlags.NotNullValueFlag}:
            case ${RefFlags.RefValueFlag}:
                fory.incReadDepth();
                try {
                  ${this.read(assignStmt, `${refFlag} === ${RefFlags.RefValueFlag}`)}
                } finally {
                  fory.decReadDepth();
                }
                break;
            case ${RefFlags.RefFlag}:
                ${assignStmt(this.builder.referenceResolver.getReadObject(this.builder.reader.readVarUInt32()))}
                break;
            case ${RefFlags.NullFlag}:
                ${assignStmt("null")}
                break;
        }
    `;
  }

  readRef(assignStmt: (v: string) => string): string {
    const refFlag = this.scope.uniqueName("refFlag");
    return `
        const ${refFlag} = ${this.builder.reader.readInt8()};
        switch (${refFlag}) {
            case ${RefFlags.NotNullValueFlag}:
            case ${RefFlags.RefValueFlag}:
                ${this.readNoRef(assignStmt, `${refFlag} === ${RefFlags.RefValueFlag}`)}
                break;
            case ${RefFlags.RefFlag}:
                ${assignStmt(this.builder.referenceResolver.getReadObject(this.builder.reader.readVarUInt32()))}
                break;
            case ${RefFlags.NullFlag}:
                ${assignStmt("null")}
                break;
        }
    `;
  }

  protected maybeReference(accessor: string, refState: string) {
    if (refState === "false") {
      return "";
    }
    if (refState === "true") {
      return this.builder.referenceResolver.reference(accessor);
    }
    return `
      if (${refState}) {
        ${this.builder.referenceResolver.reference(accessor)}
      }
    `;
  }

  getHash(): string {
    return "0";
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
      const getHash = () => {
        return ${this.getHash()};
      }
      const write = (v) => {
        ${this.write("v")}
      };
      const writeRef = (v) => {
        ${this.writeRef("v")}
      };
      const writeNoRef = (v) => {
        ${this.writeNoRef("v")}
      };
      const writeRefOrNull = (v) => {
        ${this.writeRefOrNull("v", expr => `return ${expr};`)}
      };
      const writeTypeInfo = (v) => {
        ${this.writeTypeInfo("v")}
      };
      const read = (fromRef) => {
        ${this.read(assignStmt => `return ${assignStmt}`, "fromRef")}
      };
      const readRef = () => {
        ${this.readRef(assignStmt => `return ${assignStmt}`)}
      };
      const readRefWithoutTypeInfo = () => {
        ${this.readRefWithoutTypeInfo(assignStmt => `return ${assignStmt}`)}
      };
      const readNoRef = (fromRef) => {
        ${this.readNoRef(assignStmt => `return ${assignStmt}`, "fromRef")}
      };
      const readTypeInfo = () => {
        ${this.readTypeInfo()}
      };
    `;
    return `
        return function (fory, external, typeInfo, options) {
            ${this.scope.generate()}
            ${declare}
            return {
              fixedSize: ${this.getFixedSize()},
              needToWriteRef: () => ${this.needToWriteRef()},
              getTypeId: () => ${this.getTypeId()},
              getUserTypeId: () => ${this.getUserTypeId()},
              getTypeInfo: () => typeInfo,
              getHash,

              write,
              writeRef,
              writeNoRef,
              writeRefOrNull,
              writeTypeInfo,

              read,
              readRef,
              readRefWithoutTypeInfo,
              readNoRef,
              readTypeInfo,
            };
        }
        `;
  }
}
