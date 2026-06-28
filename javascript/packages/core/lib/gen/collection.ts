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

import type { TypeInfo } from "../typeInfo";
import { CodecBuilder } from "./builder";
import { BaseSerializerGenerator, SerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { TypeId, RefFlags, Serializer } from "../type";
import { Scope } from "./scope";
import { AnyHelper } from "./any";
import type { ReadContext, WriteContext } from "../context";

export type CompatibleCollectionArrayReadAction = {
  target: "array" | "list";
  elementTypeId: number;
};

const compatibleCollectionArrayReadActions = new WeakMap<
  TypeInfo,
  CompatibleCollectionArrayReadAction
>();

export function markCompatibleCollectionArrayRead(
  typeInfo: TypeInfo,
  action: CompatibleCollectionArrayReadAction,
): TypeInfo {
  compatibleCollectionArrayReadActions.set(typeInfo, action);
  return typeInfo;
}

export function getCompatibleCollectionArrayReadAction(
  typeInfo: TypeInfo,
): CompatibleCollectionArrayReadAction | undefined {
  return compatibleCollectionArrayReadActions.get(typeInfo);
}

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

function compatibleArrayCollectionExpr(
  elementTypeId: number,
  len: string,
): string {
  switch (elementTypeId) {
    case TypeId.BOOL:
      return `new external.BoolArray(${len})`;
    case TypeId.INT8:
      return `new Int8Array(${len})`;
    case TypeId.INT16:
      return `new Int16Array(${len})`;
    case TypeId.INT32:
      return `new Int32Array(${len})`;
    case TypeId.INT64:
      return `new BigInt64Array(${len})`;
    case TypeId.UINT8:
      return `new Uint8Array(${len})`;
    case TypeId.UINT16:
      return `new Uint16Array(${len})`;
    case TypeId.UINT32:
      return `new Uint32Array(${len})`;
    case TypeId.UINT64:
      return `new BigUint64Array(${len})`;
    case TypeId.FLOAT16:
      return `external.createFloat16Array(${len})`;
    case TypeId.BFLOAT16:
      return `new external.BFloat16Array(${len})`;
    case TypeId.FLOAT32:
      return `new Float32Array(${len})`;
    case TypeId.FLOAT64:
      return `new Float64Array(${len})`;
    default:
      return `new Array(${len})`;
  }
}

function compatibleArrayPutAccessor(
  elementTypeId: number,
  result: string,
  item: string,
  index: string,
): string {
  switch (elementTypeId) {
    case TypeId.BOOL:
    case TypeId.BFLOAT16:
      return `${result}.setValue(${index}, ${item})`;
    case TypeId.FLOAT16:
      return `typeof ${result}.setValue === "function" ? ${result}.setValue(${index}, ${item}) : ${result}[${index}] = ${item}`;
    default:
      return `${result}[${index}] = ${item}`;
  }
}

class CollectionAnySerializer {
  constructor(
    private writeContext: WriteContext,
    private readContext: ReadContext,
  ) {}

  private readSerializerWithDepth(serializer: Serializer, fromRef: boolean) {
    this.readContext.incReadDepth();
    const result = serializer.read(fromRef);
    this.readContext.decReadDepth();
    return result;
  }

  protected writeElementsHeader(arr: any) {
    let flag = 0;
    let isSame = true;
    let serializer: Serializer | null | undefined = null;
    let includeNone = false;
    let trackingRef = false;

    for (const item of arr) {
      if (item === undefined || item === null) {
        includeNone = true;
        continue;
      }
      const current = this.writeContext.typeResolver.getSerializerByData(item);
      if (!current) {
        throw new Error("can't detect the type of item in list");
      }
      if (!trackingRef) {
        trackingRef = current.needToWriteRef();
      }
      if (isSame) {
        if (
          serializer !== null
          && serializer !== undefined
          && current !== serializer
        ) {
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
    this.writeContext.writer.writeUint8(flag);
    return {
      serializer,
      isSame,
      flag,
      includeNone,
      trackingRef,
    };
  }

  write(value: any, size: number) {
    this.writeContext.writer.writeVarUint32Small7(size);
    if (size === 0) {
      return;
    }
    const { serializer, isSame, includeNone, trackingRef }
      = this.writeElementsHeader(value);
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
            this.writeContext.writer.writeInt8(RefFlags.NullFlag);
          } else {
            this.writeContext.writer.writeInt8(RefFlags.NotNullValueFlag);
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
          const serializer
            = this.writeContext.typeResolver.getSerializerByData(item);
          serializer?.writeRef(item);
        }
      } else if (includeNone) {
        for (const item of value) {
          if (item === null || item === undefined) {
            this.writeContext.writer.writeInt8(RefFlags.NullFlag);
          } else {
            const serializer
              = this.writeContext.typeResolver.getSerializerByData(item);
            this.writeContext.writer.writeInt8(RefFlags.NotNullValueFlag);
            serializer!.writeNoRef(item);
          }
        }
      } else {
        for (const item of value) {
          const serializer
            = this.writeContext.typeResolver.getSerializerByData(item);
          serializer!.writeNoRef(item);
        }
      }
    }
  }

  read(
    accessor: (result: any, index: number, v: any) => void,
    createCollection: (len: number) => any,
    fromRef: boolean,
  ): any {
    void fromRef;
    const len = this.readContext.reader.readVarUint32Small7();
    if (len === 0) {
      return createCollection(len);
    }
    const flags = this.readContext.reader.readUint8();
    const result = createCollection(len);
    // IMPORTANT: collection readers must obey the ref/null bits written on the
    // wire, not local TypeScript metadata that may imply a different ref
    // policy. Shared xlang tests intentionally deserialize one ref policy and
    // then serialize another local payload. DO NOT REMOVE this comment.
    const isSame = flags & CollectionFlags.SAME_TYPE;
    const includeNone = flags & CollectionFlags.HAS_NULL;
    const refTracking = flags & CollectionFlags.TRACKING_REF;

    if (isSame) {
      const serializer = AnyHelper.detectSerializer(this.readContext);
      if (refTracking) {
        for (let i = 0; i < len; i++) {
          serializer.readRef();
          const refFlag = this.readContext.readRefFlag();
          if (refFlag === RefFlags.RefFlag) {
            const refId = this.readContext.reader.readVarUInt32();
            accessor(result, i, this.readContext.getReadRef(refId));
          } else if (refFlag === RefFlags.RefValueFlag) {
            accessor(
              result,
              i,
              this.readSerializerWithDepth(serializer!, true),
            );
          } else {
            accessor(result, i, null);
          }
        }
      } else if (includeNone) {
        for (let i = 0; i < len; i++) {
          const flag = this.readContext.reader.readInt8();
          if (flag === RefFlags.NullFlag) {
            accessor(result, i, null);
          } else {
            accessor(
              result,
              i,
              this.readSerializerWithDepth(serializer!, false),
            );
          }
        }
      } else {
        for (let i = 0; i < len; i++) {
          accessor(result, i, this.readSerializerWithDepth(serializer!, false));
        }
      }
    } else {
      if (refTracking) {
        for (let i = 0; i < len; i++) {
          const itemSerializer = AnyHelper.detectSerializer(this.readContext);
          accessor(result, i, itemSerializer!.readRef());
        }
      } else if (includeNone) {
        for (let i = 0; i < len; i++) {
          const flag = this.readContext.reader.readInt8();
          if (flag === RefFlags.NullFlag) {
            accessor(result, i, null);
          } else {
            const itemSerializer = AnyHelper.detectSerializer(this.readContext);
            accessor(
              result,
              i,
              this.readSerializerWithDepth(itemSerializer!, false),
            );
          }
        }
      } else {
        for (let i = 0; i < len; i++) {
          const itemSerializer = AnyHelper.detectSerializer(this.readContext);
          accessor(
            result,
            i,
            this.readSerializerWithDepth(itemSerializer!, false),
          );
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
    this.innerGenerator = CodegenRegistry.newGeneratorByTypeInfo(
      inner,
      this.builder,
      this.scope,
    );
  }

  abstract genericTypeDescriptin(): TypeInfo | undefined;

  private isAny() {
    return this.genericTypeDescriptin()?.typeId === TypeId.UNKNOWN;
  }

  abstract newCollection(lenAccessor: string): string;

  abstract putAccessor(result: string, item: string, index: string): string;

  abstract sizeProp(): string;

  private isDeclaredElementType() {
    const innerTypeId = this.innerGenerator.getTypeId();
    return (
      innerTypeId !== TypeId.STRUCT
      && innerTypeId !== TypeId.COMPATIBLE_STRUCT
      && innerTypeId !== TypeId.NAMED_STRUCT
      && innerTypeId !== TypeId.NAMED_COMPATIBLE_STRUCT
      && innerTypeId !== TypeId.EXT
      && innerTypeId !== TypeId.NAMED_EXT
    );
  }

  protected writeElementsHeader(accessor: string, flagAccessor: string) {
    const item = this.scope.uniqueName("item");
    const stmts = [];
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
    const flag = this.isDeclaredElementType()
      ? CollectionFlags.SAME_TYPE | CollectionFlags.DECL_ELEMENT_TYPE
      : CollectionFlags.SAME_TYPE;
    return `
            let ${flags} = ${(this.innerGenerator.needToWriteRef() ? CollectionFlags.TRACKING_REF : 0) | flag};
            ${this.builder.writer.writeVarUint32Small7(`${accessor}.${this.sizeProp()}`)}
            if (${accessor}.${this.sizeProp()} > 0) {
            ${this.writeElementsHeader(accessor, flags)}
            if (!(${flags} & ${CollectionFlags.DECL_ELEMENT_TYPE})) {
                ${this.innerGenerator.writeEmbed().writeTypeInfo("null")}
            }
            ${this.builder.writer.reserve(`${this.innerGenerator.getFixedSize()} * ${accessor}.${this.sizeProp()}`)};
            if (${flags} & ${CollectionFlags.TRACKING_REF}) {
                for (const ${item} of ${accessor}) {
                    if (${item} !== null && ${item} !== undefined) {
                        const ${existsId} = ${this.builder.referenceResolver.getWrittenRefId(item)};
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
            }
        `;
  }

  readSpecificType(
    accessor: (expr: string) => string,
    refState: string,
  ): string {
    const result = this.scope.uniqueName("result");
    const len = this.scope.uniqueName("len");
    const flags = this.scope.uniqueName("flags");
    const idx = this.scope.uniqueName("idx");
    const refFlag = this.scope.uniqueName("refFlag");
    const elemSerializer = this.scope.uniqueName("elemSerializer");
    const anyHelper = this.builder.getExternal(AnyHelper.name);
    const readContextName = this.builder.getReadContextName();
    const useDeclaredStructElementReader = TypeId.structType(
      this.innerGenerator.getTypeId()!,
    );
    const compatibleReadAction = getCompatibleCollectionArrayReadAction(
      this.typeInfo,
    );
    const compatibleListToArray = compatibleReadAction?.target === "array";
    const newCollection = compatibleListToArray
      ? compatibleArrayCollectionExpr(compatibleReadAction!.elementTypeId, len)
      : this.newCollection(len);
    const putAccessor = (item: string, index: string) =>
      compatibleListToArray
        ? compatibleArrayPutAccessor(
          compatibleReadAction!.elementTypeId,
          result,
          item,
          index,
        )
        : this.putAccessor(result, item, index);
    const rejectCompatiblePayload = compatibleListToArray
      ? `
                if (${flags} & (${CollectionFlags.HAS_NULL} | ${CollectionFlags.TRACKING_REF})) {
                    throw new Error("compatible list-to-array field cannot read nullable or ref-tracked elements");
                }
                if ((${flags} & (${CollectionFlags.SAME_TYPE} | ${CollectionFlags.DECL_ELEMENT_TYPE})) !== (${CollectionFlags.SAME_TYPE} | ${CollectionFlags.DECL_ELEMENT_TYPE})) {
                    throw new Error("compatible list-to-array field requires declared same-type elements");
                }
        `
      : "";
    // Skip depth tracking for leaf element types (primitives, string, enum, time, typed arrays).
    const innerIsLeaf = TypeId.isLeafTypeId(this.innerGenerator.getTypeId()!);
    const innerReader = useDeclaredStructElementReader
      ? this.innerGenerator.readEmbed()
      : this.innerGenerator;
    const readInnerElement = (
      assignStmt: (x: any) => string,
      refState: string,
    ) => {
      return innerIsLeaf
        ? this.innerGenerator.read(assignStmt, refState)
        : innerReader.readWithDepth(assignStmt, refState);
    };
    const readElementTypeInfo = useDeclaredStructElementReader
      ? this.innerGenerator
        .readEmbed()
        .readTypeInfo((expr: string) => `${elemSerializer} = ${expr};`)
      : `${elemSerializer} = ${anyHelper}.detectSerializer(${readContextName});`;
    return `
            const ${len} = ${this.builder.reader.readVarUint32Small7()};
            let ${flags} = 0;
            if (${len} > 0) {
                ${flags} = ${this.builder.reader.readUint8()};
                ${rejectCompatiblePayload}
                ${this.builder.reader.checkReadableBytes(len)}
            }
            const ${result} = ${newCollection};
            ${this.maybeReference(result, refState)}
            if (${len} > 0) {
                let ${elemSerializer} = null;
                if (!(${flags} & ${CollectionFlags.DECL_ELEMENT_TYPE})) {
                    ${readElementTypeInfo}
                }
                if (${flags} & ${CollectionFlags.TRACKING_REF}) {
                    for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                        const ${refFlag} = ${this.builder.reader.readInt8()};
                        switch (${refFlag}) {
                            case ${RefFlags.NotNullValueFlag}:
                            case ${RefFlags.RefValueFlag}:
                                if (${elemSerializer}) {
                                    ${innerIsLeaf ? "" : `${readContextName}.incReadDepth();`}
                                    ${putAccessor(`${elemSerializer}.read(${refFlag} === ${RefFlags.RefValueFlag})`, idx)}
                                    ${innerIsLeaf ? "" : `${readContextName}.decReadDepth();`}
                                } else {
                                    ${readInnerElement((x: any) => `${putAccessor(x, idx)}`, `${refFlag} === ${RefFlags.RefValueFlag}`)}
                                }
                                break;
                            case ${RefFlags.RefFlag}:
                                ${putAccessor(this.builder.referenceResolver.getReadRef(this.builder.reader.readVarUInt32()), idx)}
                                break;
                            case ${RefFlags.NullFlag}:
                                ${putAccessor("null", idx)}
                                break;
                        }
                    }
                } else if (${flags} & ${CollectionFlags.HAS_NULL}) {
                    for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                        if (${this.builder.reader.readInt8()} == ${RefFlags.NullFlag}) {
                            ${putAccessor("null", idx)}
                        } else {
                            if (${elemSerializer}) {
                                ${innerIsLeaf ? "" : `${readContextName}.incReadDepth();`}
                                ${putAccessor(`${elemSerializer}.read(false)`, idx)}
                                ${innerIsLeaf ? "" : `${readContextName}.decReadDepth();`}
                            } else {
                                ${readInnerElement((x: any) => `${putAccessor(x, idx)}`, "false")}
                            }
                        }
                    }
                } else {
                    if (${elemSerializer}) {
                        for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                            ${innerIsLeaf ? "" : `${readContextName}.incReadDepth();`}
                            ${putAccessor(`${elemSerializer}.read(false)`, idx)}
                            ${innerIsLeaf ? "" : `${readContextName}.decReadDepth();`}
                        }
                    } else {
                        for (let ${idx} = 0; ${idx} < ${len}; ${idx}++) {
                            ${readInnerElement((x: any) => `${putAccessor(x, idx)}`, "false")}
                        }
                    }
                }
            }
            ${accessor(result)}
        `;
  }

  write(accessor: string): string {
    if (this.isAny()) {
      return `
                new (${this.builder.getExternal(CollectionAnySerializer.name)})(${this.builder.getWriteContextName()}, ${this.builder.getReadContextName()}).write(${accessor}, ${accessor}.${this.sizeProp()})
            `;
    }
    return this.writeSpecificType(accessor);
  }

  read(accessor: (expr: string) => string, refState: string): string {
    if (this.isAny()) {
      return accessor(`new (${this.builder.getExternal(CollectionAnySerializer.name)})(${this.builder.getWriteContextName()}, ${this.builder.getReadContextName()}).read((result, i, v) => {
              ${this.putAccessor("result", "v", "i")};
          }, (len) => ${this.newCollection("len")}, ${refState});
      `);
    }
    return this.readSpecificType(accessor, refState);
  }
}

CodegenRegistry.registerExternal(CollectionSerializerGenerator);
CodegenRegistry.registerExternal(CollectionAnySerializer);
