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
import { RefFlags } from "../type";
import { Scope } from "./scope";
import { TypeInfo } from "../typeInfo";
import { refTrackingAbleTypeId } from "../meta/TypeMeta";
import { BinaryWriter } from "../writer";

export const makeHead = (flag: RefFlags, typeId: number) => {
  const writer = new BinaryWriter();
  writer.uint8(flag);
  writer.writeVarUint32Small7(typeId);
  const buffer = writer.dump();
  return buffer;
};

export interface SerializerGenerator {
  writeRef(accessor: string): string;
  writeNoRef(accessor: string): string;
  writeRefOrNull(assignStmt: (v: string) => string, accessor: string): string;
  writeClassInfo(accessor: string): string;
  write(accessor: string): string;
  writeEmbed(): any;

  toSerializer(): string;
  getFixedSize(): number;
  needToWriteRef(): boolean;

  readRef(assignStmt: (v: string) => string, withoutClassInfo?: boolean): string;
  readNoRef(assignStmt: (v: string) => string, refState: string): string;
  readClassInfo(): string;
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
    if (refTrackingAbleTypeId(this.typeInfo.typeId)) {
      return false;
    }
    return this.builder.fory.config.refTracking === true;
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
      ${this.writeRefOrNull(expr => `${noneedWrite} = ${expr}`, accessor)}
      if (!${noneedWrite}) {
        ${this.writeNoRef(accessor)}
      }
    `;
  }

  writeNoRef(accessor: string) {
    return `
      ${this.writeClassInfo(accessor)};
      ${this.write(accessor)};
    `;
  }

  writeRefOrNull(assignStmt: (expr: string) => string, accessor: string) {
    let refFlagStmt = "";
    if (this.needToWriteRef()) {
      const existsId = this.scope.uniqueName("existsId");
      refFlagStmt = `
        const ${existsId} = ${this.builder.referenceResolver.existsWriteObject(accessor)};
        if (typeof ${existsId} === "number") {
            ${this.builder.writer.int8(RefFlags.RefFlag)}
            ${this.builder.writer.varUInt32(existsId)}
            ${assignStmt("true")};
        } else {
            ${this.builder.writer.int8(RefFlags.RefValueFlag)}
            ${this.builder.referenceResolver.writeRef(accessor)}
        }
      `;
    } else {
      refFlagStmt = this.builder.writer.int8(RefFlags.NotNullValueFlag);
    }
    return `
      if (${accessor} === null || ${accessor} === undefined) {
        ${this.builder.writer.int8(RefFlags.NullFlag)};
        ${assignStmt("true")};
      } else {
        ${refFlagStmt}
      }
    `;
  }

  writeClassInfo(accessor: string) {
    return ` 
      ${this.builder.writer.writeVarUint32Small7(this.getTypeId())};
    `;
  }

  getType() {
    return this.typeInfo.typeId;
  }

  getTypeId() {
    return this.typeInfo.typeId;
  }

  getInternalTypeId() {
    if (this.getTypeId() <= 0xff) {
      return this.getTypeId();
    }
    return this.getTypeId() & 0xff;
  }

  abstract read(assignStmt: (v: string) => string, refState: string): string;

  readClassInfo(): string {
    return `
      ${this.builder.reader.readVarUint32Small7()};
    `;
  }

  readNoRef(assignStmt: (v: string) => string, refState: string): string {
    return `
      ${this.readClassInfo()}
      ${this.read(assignStmt, refState)};
    `;
  }

  readRef(assignStmt: (v: string) => string, withoutClassInfo = false): string {
    const refFlag = this.scope.uniqueName("refFlag");
    return `
        const ${refFlag} = ${this.builder.reader.int8()};
        switch (${refFlag}) {
            case ${RefFlags.NotNullValueFlag}:
            case ${RefFlags.RefValueFlag}:
                ${!withoutClassInfo ? this.readNoRef(assignStmt, `${refFlag} === ${RefFlags.RefValueFlag}`) : this.read(assignStmt, `${refFlag} === ${RefFlags.RefValueFlag}`)}
                break;
            case ${RefFlags.RefFlag}:
                ${assignStmt(this.builder.referenceResolver.getReadObject(this.builder.reader.varUInt32()))}
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
        ${this.writeRefOrNull(expr => `return ${expr};`, "v")}
      };
      const writeClassInfo = (v) => {
        ${this.writeClassInfo("v")}
      };
      const read = (fromRef) => {
        ${this.read(assignStmt => `return ${assignStmt}`, "fromRef")}
      };
      const readRef = () => {
        ${this.readRef(assignStmt => `return ${assignStmt}`)}
      };
      const readNoRef = (fromRef) => {
        ${this.readNoRef(assignStmt => `return ${assignStmt}`, "fromRef")}
      };
      const readClassInfo = () => {
        ${this.readClassInfo()}
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
              getHash,

              write,
              writeRef,
              writeNoRef,
              writeRefOrNull,
              writeClassInfo,

              read,
              readRef,
              readNoRef,
              readClassInfo,
            };
        }
        `;
  }
}
