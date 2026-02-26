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
import { BaseSerializerGenerator, SerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { TypeId, RefFlags, Serializer } from "../type";
import { Scope } from "./scope";
import Fory from "../fory";
import { AnyHelper } from "./any";

export const CollectionFlags = {
  /** Whether track elements ref. */
  TRACKING_REF: 0b1,

  /** Whether collection has null. */
  HAS_NULL: 0b10,

  /** Whether collection elements type is not declare type. */
  DECL_ELEMENT_TYPE: 0b100,

  /** Whether collection elements type different. */
  SAME_TYPE: 0b1000,
};

class CollectionAnySerializer {
  constructor(private fory: Fory) {

  }

  protected writeElementsHeader(arr: any) {
    let flag = 0;
    let isSame = true;
    let serializer: Serializer | null | undefined = null;
    let includeNone = false;
    let trackingRef = false;

    for (const item of arr) {
      if ((item === undefined || item === null)) {
        includeNone = true;
        continue;
      }
      const current = this.fory.typeResolver.getSerializerByData(item);
      if (!current) {
        throw new Error("can't detect the type of item in list");
      }
      if (!trackingRef) {
        trackingRef = current.needToWriteRef();
      }
      if (isSame) {
        if (serializer !== null && serializer !== undefined && current !== serializer) {
          isSame = false;
        } else {
          serializer = current;
        }
      }
    }

    if (isSame) {
      flag |= CollectionFlags.SAME_TYPE;
    }
    if (includeNone) {
      flag |= CollectionFlags.HAS_NULL;
    }
    if (trackingRef) {
      flag |= CollectionFlags.TRACKING_REF;
    }
    this.fory.binaryWriter.writeUint8(flag);
    return {
      serializer,
      isSame,
      flag,
      includeNone,
      trackingRef,
    };
  }

  write(value: any, size: number) {
    this.fory.binaryWriter.writeVarUint32Small7(size);
    const { serializer, isSame, includeNone, trackingRef } = this.writeElementsHeader(value);
    if (isSame) {
      serializer!.writeTypeInfo(value);
      if (trackingRef) {
        for (const item of value) {
          if (!serializer!.writeRefOrNull(item)) {
            serializer!.write(item);
          }
        }
      } else if (includeNone) {
        for (const item of value) {
          if (item === null || item === undefined) {
            this.fory.binaryWriter.writeInt8(RefFlags.NullFlag);
          } else {
            this.fory.binaryWriter.writeInt8(RefFlags.NotNullValueFlag);
            serializer!.write(item);
          }
        }
      } else {
        for (const item of value) {
          serializer!.write(item);
        }
      }
    } else {
      if (trackingRef) {
        for (const item of value) {
          const serializer = this.fory.typeResolver.getSerializerByData(item);
          serializer?.writeRef(item);
        }
      } else if (includeNone) {
        for (const item of value) {
          if (item === null || item === undefined) {
            this.fory.binaryWriter.writeInt8(RefFlags.NullFlag);
          } else {
            const serializer = this.fory.typeResolver.getSerializerByData(item);
            this.fory.binaryWriter.writeInt8(RefFlags.NotNullValueFlag);
            serializer!.writeNoRef(item);
          }
        }
      } else {
        for (const item of value) {
          const serializer = this.fory.typeResolver.getSerializerByData(item);
          serializer!.writeNoRef(item);
        }
      }
    }
  }

  read(accessor: (result: any, index: number, v: any) => void, createCollection: (len: number) => any, fromRef: boolean): any {
    void fromRef;
    const len = this.fory.binaryReader.readVarUint32Small7();
    const maxLen = this.fory.config.maxCollectionLength;
    if (typeof maxLen === "number" && maxLen > 0 && len > maxLen) {
      throw new Error(`Collection length ${len} exceeds configured maxCollectionLength ${maxLen}`);
    }
    const flags = this.fory.binaryReader.readUint8();
    const isSame = flags & CollectionFlags.SAME_TYPE;
    const includeNone = flags & CollectionFlags.HAS_NULL;
    const refTracking = flags & CollectionFlags.TRACKING_REF;
    const result = createCollection(len);

    if (isSame) {
      const serializer = AnyHelper.detectSerializer(this.fory);
      if (refTracking) {
        for (let i = 0; i < len; i++) {
          serializer.readRef();
          const refFlag = this.fory.referenceResolver.readRefFlag();
          if (refFlag === RefFlags.RefFlag) {
            const refId = this.fory.binaryReader.readVarUInt32();
            accessor(result, i, this.fory.referenceResolver.getReadObject(refId));
          } else if (refFlag === RefFlags.RefValueFlag) {
            accessor(result, i, serializer!.read(true));
          } else {
            accessor(result, i, null);
          }
        }
      } else if (includeNone) {
        for (let i = 0; i < len; i++) {
          const flag = this.fory.binaryReader.readInt8();
          if (flag === RefFlags.NullFlag) {
            accessor(result, i, null);
          } else {
            accessor(result, i, serializer!.read(false));
          }
        }
      } else {
        for (let i = 0; i < len; i++) {
          accessor(result, i, serializer!.read(false));
        }
      }
    } else {
      if (refTracking) {
        for (let i = 0; i < len; i++) {
          const itemSerializer = AnyHelper.detectSerializer(this.fory);
          accessor(result, i, itemSerializer!.readRef());
        }
      } else if (includeNone) {
        for (let i = 0; i < len; i++) {
          const flag = this.fory.binaryReader.readInt8();
          if (flag === RefFlags.NullFlag) {
            accessor(result, i, null);
          } else {
            const itemSerializer = AnyHelper.detectSerializer(this.fory);
            accessor(result, i, itemSerializer!.read(false));
          }
        }
      } else {
        for (let i = 0; i < len; i++) {
          const itemSerializer = AnyHelper.detectSerializer(this.fory);
          accessor(result, i, itemSerializer!.read(false));
        }
      }
    }
    return result;
  }
}

export abstract class CollectionSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;
  innerGenerator: SerializerGenerator;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
    const inner = this.genericTypeDescriptin()!;
    this.innerGenerator = CodegenRegistry.newGeneratorByTypeInfo(inner, this.builder, this.scope);
  }

  abstract genericTypeDescriptin(): TypeInfo | undefined;

  private isAny() {
    return this.genericTypeDescriptin()?.typeId === TypeId.UNKNOWN;
  }

  abstract newCollection(lenAccessor: string): string;

  abstract putAccessor(result: string, item: string, index: string): string;

  abstract sizeProp(): string;

  protected writeElementsHeader(accessor: string, flagAccessor: string) {
    const item = this.scope.uniqueName("item");
    const stmts = [
    ];
    stmts.push(`
        for (const ${item} of ${accessor}) {
            if (${item} === null || ${item} === undefined) {
                ${flagAccessor} |= ${CollectionFlags.HAS_NULL};
                break;
            }
        }
    `);
    stmts.push(`${this.builder.writer.writeUint8(flagAccessor)}`);
    return stmts.join("\n");
  }

  writeSpecificType(accessor: string): string {
    const item = this.scope.uniqueName("item");
    const flags = this.scope.uniqueName("flags");
    const existsId = this.scope.uniqueName("existsId");
    const flag = CollectionFlags.SAME_TYPE | CollectionFlags.DECL_ELEMENT_TYPE;
    return `
            let ${flags} = ${(this.innerGenerator.needToWriteRef() ? CollectionFlags.TRACKING_REF : 0) | flag};
            ${this.builder.writer.writeVarUint32Small7(`${accessor}.${this.sizeProp()}`)}
            ${this.writeElementsHeader(accessor, flags)}
            ${this.builder.writer.reserve(`${this.innerGenerator.getFixedSize()} * ${accessor}.${this.sizeProp()}`)};
            if (${flags} & ${CollectionFlags.TRACKING_REF}) {
                for (const ${item} of ${accessor}) {
                    if (${item} !== null && ${item} !== undefined) {
                        const ${existsId} = ${this.builder.referenceResolver.existsWriteObject(item)};
                        if (typeof ${existsId} === "number") {
                            ${this.builder.writer.writeInt8(RefFlags.RefFlag)}
                            ${this.builder.writer.writeVarUInt32(existsId)}
                        } else {
                            ${this.builder.referenceResolver.writeRef(item)}
                            ${this.builder.writer.writeInt8(RefFlags.RefValueFlag)};
                            ${this.innerGenerator.writeEmbed().write(item)}
                        }
                    } else {
                        ${this.builder.writer.writeInt8(RefFlags.NullFlag)};
                    }
                }
            } else if (${flags} & ${CollectionFlags.HAS_NULL}) {
                for (const ${item} of ${accessor}) {
                    if (${item} !== null && ${item} !== undefined) {
                        ${this.builder.writer.writeInt8(RefFlags.NotNullValueFlag)};
                        ${this.innerGenerator.writeEmbed().write(item)}
                    } else {
                        ${this.builder.writer.writeInt8(RefFlags.NullFlag)};
                    }
                }
            } else {
                for (const ${item} of ${accessor}) {
                    ${this.innerGenerator.writeEmbed().write(item)}
                }
            }
        `;
  }

  readSpecificType(accessor: (expr: string) => string, refState: string): string {
    const result = this.scope.uniqueName("result");
    const len = this.scope.uniqueName("len");
    const flags = this.scope.uniqueName("flags");
    const idx = this.scope.uniqueName("idx");
    const refFlag = this.scope.uniqueName("refFlag");
    const foryName = this.builder.getForyName();
    const maxLenVar = this.scope.uniqueName("maxCollectionLength");
    return `
            const ${len} = ${this.builder.reader.readVarUint32Small7()};
            const ${maxLenVar} = ${foryName}.config.maxCollectionLength;
            if (typeof ${maxLenVar} === "number" && ${maxLenVar} > 0 && ${len} > ${maxLenVar}) {
                throw new Error(\`Collection length \${${len}} exceeds configured maxCollectionLength \${${maxLenVar}}\`);
            }
            const ${flags} = ${this.builder.reader.readUint8()};
            const ${result} = ${this.newCollection(len)};
            ${this.maybeReference(result, refState)}
            if (${flags} & ${CollectionFlags.TRACKING_REF}) {
                for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                    const ${refFlag} = ${this.builder.reader.readInt8()};
                    switch (${refFlag}) {
                        case ${RefFlags.NotNullValueFlag}:
                        case ${RefFlags.RefValueFlag}:
                            ${this.innerGenerator.readEmbed().read((x: any) => `${this.putAccessor(result, x, idx)}`, `${refFlag} === ${RefFlags.RefValueFlag}`)}
                            break;
                        case ${RefFlags.RefFlag}:
                            ${this.putAccessor(result, this.builder.referenceResolver.getReadObject(this.builder.reader.readVarUInt32()), idx)}
                            break;
                        case ${RefFlags.NullFlag}:
                            ${this.putAccessor(result, "null", idx)}
                            break;
                    }
                }
            } else if (${flags} & ${CollectionFlags.HAS_NULL}) {
                for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                    if (${this.builder.reader.readInt8()} == ${RefFlags.NullFlag}) {
                        ${this.putAccessor(result, "null", idx)}
                    } else {
                        ${this.innerGenerator.readEmbed().read((x: any) => `${this.putAccessor(result, x, idx)}`, "false")}
                    }
                }

            } else {
                for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                    ${this.innerGenerator.readEmbed().read((x: any) => `${this.putAccessor(result, x, idx)}`, "false")}
                }
            }
            ${accessor(result)}
        `;
  }

  write(accessor: string): string {
    if (this.isAny()) {
      return `
                new (${this.builder.getExternal(CollectionAnySerializer.name)})(${this.builder.getForyName()}).write(${accessor}, ${accessor}.${this.sizeProp()})
            `;
    }
    return this.writeSpecificType(accessor);
  }

  read(accessor: (expr: string) => string, refState: string): string {
    if (this.isAny()) {
      return accessor(`new (${this.builder.getExternal(CollectionAnySerializer.name)})(${this.builder.getForyName()}).read((result, i, v) => {
              ${this.putAccessor("result", "v", "i")};
          }, (len) => ${this.newCollection("len")}, ${refState});
      `);
    }
    return this.readSpecificType(accessor, refState);
  }
}

CodegenRegistry.registerExternal(CollectionSerializerGenerator);
CodegenRegistry.registerExternal(CollectionAnySerializer);
