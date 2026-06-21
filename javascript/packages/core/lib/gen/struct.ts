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
import { getCompatibleCollectionArrayReadAction } from "./collection";
import {
  CompatibleScalarConverter,
  getCompatibleScalarReadAction,
} from "../compatible/scalar";
import { shouldSkipCompatibleRead } from "../compatible/field";

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

function compatibleReadTargetExpr(typeInfo: TypeInfo, expr: string): string {
  const action = getCompatibleCollectionArrayReadAction(typeInfo);
  switch (action?.target) {
    case "array":
      return expr;
    case "list":
      return `Array.from(${expr})`;
    default:
      return expr;
  }
}

const sortProps = (typeInfo: TypeInfo, typeResolver: CodecBuilder["resolver"]) => {
  const props = typeInfo.options!.props;
  if (typeInfo.options!.preserveFieldOrder) {
    return typeInfo.options!.fieldEntries ?? Object.entries(props!).map(
      ([key, fieldTypeInfo]) => ({
        key,
        typeInfo: fieldTypeInfo,
      }),
    );
  }
  const names = TypeMeta.fromTypeInfo(typeInfo, typeResolver).getFieldInfo();
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

function isDirectVarInt32Field(
  typeInfo: TypeInfo,
  typeResolver: CodecBuilder["resolver"],
) {
  return varInt32ObjectReadKind(typeInfo, typeResolver) === "number";
}

function varInt32ObjectReadKind(
  typeInfo: TypeInfo,
  typeResolver: CodecBuilder["resolver"],
): "number" | "bigint" | null {
  if (
    toRefMode(typeInfo.trackingRef, typeInfo.nullable) !== RefMode.NONE
    || !typeResolver.isMonomorphic(typeInfo, typeInfo.dynamic)
  ) {
    return null;
  }
  const scalarAction = getCompatibleScalarReadAction(typeInfo);
  if (scalarAction !== undefined) {
    return scalarAction.remoteNullable !== true
      && scalarAction.remoteTypeId === TypeId.VARINT32
      && (scalarAction.localTypeId === TypeId.INT64
      || scalarAction.localTypeId === TypeId.VARINT64
      || scalarAction.localTypeId === TypeId.TAGGED_INT64)
      ? "bigint"
      : null;
  }
  return typeInfo.typeId === TypeId.VARINT32 ? "number" : null;
}

function directNumericFieldReadExpr(
  typeInfo: TypeInfo,
  builder: CodecBuilder,
): string | null {
  if (
    toRefMode(typeInfo.trackingRef, typeInfo.nullable) !== RefMode.NONE
    || !builder.resolver.isMonomorphic(typeInfo, typeInfo.dynamic)
    || getCompatibleScalarReadAction(typeInfo) !== undefined
  ) {
    return null;
  }
  switch (typeInfo.typeId) {
    case TypeId.INT8:
      return builder.reader.readInt8();
    case TypeId.INT16:
      return builder.reader.readInt16();
    case TypeId.INT32:
      return builder.reader.readInt32();
    case TypeId.VARINT32:
      return builder.reader.readVarInt32();
    case TypeId.INT64:
      return builder.reader.readInt64();
    case TypeId.VARINT64:
      return builder.reader.readVarInt64();
    case TypeId.TAGGED_INT64:
      return builder.reader.readTaggedInt64();
    case TypeId.UINT8:
      return builder.reader.readUint8();
    case TypeId.UINT16:
      return builder.reader.readUint16();
    case TypeId.UINT32:
      return builder.reader.readUint32();
    case TypeId.VAR_UINT32:
      return builder.reader.readVarUInt32();
    case TypeId.UINT64:
      return builder.reader.readUint64();
    case TypeId.VAR_UINT64:
      return builder.reader.readVarUInt64();
    case TypeId.TAGGED_UINT64:
      return builder.reader.readTaggedUInt64();
    case TypeId.FLOAT16:
      return builder.reader.readFloat16();
    case TypeId.BFLOAT16:
      return builder.reader.readBfloat16();
    case TypeId.FLOAT32:
      return builder.reader.readFloat32();
    case TypeId.FLOAT64:
      return builder.reader.readFloat64();
    default:
      return null;
  }
}

function compatibleScalarFieldReadExpr(
  remoteTypeId: number,
  localTypeId: number,
  builder: CodecBuilder,
): string | null {
  const converter = builder.getExternal(CompatibleScalarConverter.name);
  const remoteRead = compatibleScalarRemoteReadExpr(
    remoteTypeId,
    builder,
    converter,
  );
  if (remoteRead === null) {
    return null;
  }
  if (remoteTypeId === localTypeId) {
    return remoteRead;
  }
  const remoteCanonical = canonicalScalarTypeId(remoteTypeId);
  const localCanonical = canonicalScalarTypeId(localTypeId);
  switch (localCanonical) {
    case TypeId.BOOL:
      return scalarToBoolExpr(remoteCanonical, remoteRead, converter);
    case TypeId.STRING:
      return scalarToStringExpr(remoteCanonical, remoteRead, converter);
    case TypeId.DECIMAL:
      return scalarToDecimalExpr(remoteCanonical, remoteRead, converter);
    case TypeId.INT8:
    case TypeId.INT16:
    case TypeId.INT32:
    case TypeId.INT64:
    case TypeId.UINT8:
    case TypeId.UINT16:
    case TypeId.UINT32:
    case TypeId.UINT64:
      return scalarToIntegerExpr(
        remoteCanonical,
        localCanonical,
        remoteRead,
        converter,
      );
    case TypeId.FLOAT16:
    case TypeId.BFLOAT16:
    case TypeId.FLOAT32:
    case TypeId.FLOAT64:
      return scalarToFloatExpr(
        remoteCanonical,
        localCanonical,
        remoteRead,
        converter,
      );
    default:
      return null;
  }
}

function canonicalScalarTypeId(typeId: number): number {
  switch (typeId) {
    case TypeId.VARINT32:
      return TypeId.INT32;
    case TypeId.VARINT64:
    case TypeId.TAGGED_INT64:
      return TypeId.INT64;
    case TypeId.VAR_UINT32:
      return TypeId.UINT32;
    case TypeId.VAR_UINT64:
    case TypeId.TAGGED_UINT64:
      return TypeId.UINT64;
    default:
      return typeId;
  }
}

function compatibleScalarRemoteReadExpr(
  remoteTypeId: number,
  builder: CodecBuilder,
  converter: string,
): string | null {
  const reader = builder.reader;
  switch (remoteTypeId) {
    case TypeId.BOOL:
      return `${converter}.checkedBool(${reader.readUint8()})`;
    case TypeId.INT8:
      return reader.readInt8();
    case TypeId.INT16:
      return reader.readInt16();
    case TypeId.INT32:
      return reader.readInt32();
    case TypeId.VARINT32:
      return reader.readVarInt32();
    case TypeId.INT64:
      return reader.readInt64();
    case TypeId.VARINT64:
      return reader.readVarInt64();
    case TypeId.TAGGED_INT64:
      return reader.readTaggedInt64();
    case TypeId.UINT8:
      return reader.readUint8();
    case TypeId.UINT16:
      return reader.readUint16();
    case TypeId.UINT32:
      return reader.readUint32();
    case TypeId.VAR_UINT32:
      return reader.readVarUInt32();
    case TypeId.UINT64:
      return reader.readUint64();
    case TypeId.VAR_UINT64:
      return reader.readVarUInt64();
    case TypeId.TAGGED_UINT64:
      return reader.readTaggedUInt64();
    case TypeId.FLOAT16:
      return reader.readFloat16();
    case TypeId.BFLOAT16:
      return reader.readBfloat16();
    case TypeId.FLOAT32:
      return reader.readFloat32();
    case TypeId.FLOAT64:
      return reader.readFloat64();
    case TypeId.STRING:
      return reader.stringWithHeader();
    case TypeId.DECIMAL:
      return `${converter}.readDecimal(${reader.ownName()})`;
    default:
      return null;
  }
}

function scalarToBoolExpr(
  remoteTypeId: number,
  value: string,
  converter: string,
): string | null {
  switch (remoteTypeId) {
    case TypeId.BOOL:
      return value;
    case TypeId.STRING:
      return `${converter}.stringToBool(${value})`;
    case TypeId.DECIMAL:
      return `${converter}.decimalToBool(${value})`;
    case TypeId.FLOAT16:
    case TypeId.BFLOAT16:
    case TypeId.FLOAT32:
    case TypeId.FLOAT64:
      return `${converter}.floatToBool(${value})`;
    default:
      return `${converter}.integerToBool(${value})`;
  }
}

function scalarToStringExpr(
  remoteTypeId: number,
  value: string,
  converter: string,
): string | null {
  switch (remoteTypeId) {
    case TypeId.BOOL:
      return `(${value} ? "true" : "false")`;
    case TypeId.STRING:
      return value;
    case TypeId.DECIMAL:
      return `${converter}.decimalToString(${value})`;
    case TypeId.FLOAT16:
    case TypeId.BFLOAT16:
    case TypeId.FLOAT32:
    case TypeId.FLOAT64:
      return `${converter}.floatToString(${value})`;
    default:
      return `${value}.toString()`;
  }
}

function scalarToDecimalExpr(
  remoteTypeId: number,
  value: string,
  converter: string,
): string | null {
  switch (remoteTypeId) {
    case TypeId.BOOL:
      return `${converter}.boolToDecimal(${value})`;
    case TypeId.STRING:
      return `${converter}.stringToDecimal(${value})`;
    case TypeId.DECIMAL:
      return value;
    case TypeId.FLOAT16:
    case TypeId.BFLOAT16:
    case TypeId.FLOAT32:
    case TypeId.FLOAT64:
      return `${converter}.floatToDecimal(${value})`;
    default:
      return `${converter}.integerToDecimal(${value})`;
  }
}

function scalarToIntegerExpr(
  remoteTypeId: number,
  localTypeId: number,
  value: string,
  converter: string,
): string | null {
  const checkedMethod = checkedIntegerMethod(localTypeId);
  if (checkedMethod === null) {
    return null;
  }
  switch (remoteTypeId) {
    case TypeId.BOOL:
      if (localTypeId === TypeId.INT64 || localTypeId === TypeId.UINT64) {
        return `(${value} ? 1n : 0n)`;
      }
      return `(${value} ? 1 : 0)`;
    case TypeId.STRING:
      return `${converter}.${checkedMethod}(${converter}.stringToInteger(${value}))`;
    case TypeId.DECIMAL:
      return `${converter}.${checkedMethod}(${converter}.decimalToInteger(${value}))`;
    case TypeId.FLOAT16:
    case TypeId.BFLOAT16:
    case TypeId.FLOAT32:
    case TypeId.FLOAT64:
      return `${converter}.${checkedMethod}(${converter}.floatToInteger(${value}))`;
    default:
      return integerToIntegerExpr(remoteTypeId, localTypeId, value, converter);
  }
}

function integerToIntegerExpr(
  remoteTypeId: number,
  localTypeId: number,
  value: string,
  converter: string,
): string {
  if (remoteTypeId === localTypeId) {
    return value;
  }
  if (integerRangeFits(remoteTypeId, localTypeId)) {
    if (localTypeId === TypeId.INT64 || localTypeId === TypeId.UINT64) {
      return `BigInt(${value})`;
    }
    return value;
  }
  return `${converter}.${checkedIntegerMethod(localTypeId)}(${value})`;
}

function checkedIntegerMethod(localTypeId: number): string | null {
  switch (localTypeId) {
    case TypeId.INT8:
      return "checkedInt8";
    case TypeId.INT16:
      return "checkedInt16";
    case TypeId.INT32:
      return "checkedInt32";
    case TypeId.INT64:
      return "checkedInt64";
    case TypeId.UINT8:
      return "checkedUint8";
    case TypeId.UINT16:
      return "checkedUint16";
    case TypeId.UINT32:
      return "checkedUint32";
    case TypeId.UINT64:
      return "checkedUint64";
    default:
      return null;
  }
}

function integerRangeFits(remoteTypeId: number, localTypeId: number): boolean {
  switch (localTypeId) {
    case TypeId.INT16:
      return remoteTypeId === TypeId.INT8 || remoteTypeId === TypeId.UINT8;
    case TypeId.INT32:
      return remoteTypeId === TypeId.INT8
        || remoteTypeId === TypeId.INT16
        || remoteTypeId === TypeId.UINT8
        || remoteTypeId === TypeId.UINT16;
    case TypeId.INT64:
      return remoteTypeId === TypeId.INT8
        || remoteTypeId === TypeId.INT16
        || remoteTypeId === TypeId.INT32
        || remoteTypeId === TypeId.UINT8
        || remoteTypeId === TypeId.UINT16
        || remoteTypeId === TypeId.UINT32;
    case TypeId.UINT16:
      return remoteTypeId === TypeId.UINT8;
    case TypeId.UINT32:
      return remoteTypeId === TypeId.UINT8
        || remoteTypeId === TypeId.UINT16;
    case TypeId.UINT64:
      return remoteTypeId === TypeId.UINT8
        || remoteTypeId === TypeId.UINT16
        || remoteTypeId === TypeId.UINT32;
    default:
      return false;
  }
}

function scalarToFloatExpr(
  remoteTypeId: number,
  localTypeId: number,
  value: string,
  converter: string,
): string | null {
  const method = floatMethod("Float", localTypeId);
  if (method === null) {
    return null;
  }
  switch (remoteTypeId) {
    case TypeId.BOOL:
      return `(${value} ? 1 : 0)`;
    case TypeId.STRING:
      return `${converter}.stringTo${method}(${value})`;
    case TypeId.DECIMAL:
      return `${converter}.decimalTo${method}(${value})`;
    case TypeId.FLOAT16:
    case TypeId.BFLOAT16:
    case TypeId.FLOAT32:
    case TypeId.FLOAT64:
      if (remoteTypeId === localTypeId) {
        return value;
      }
      return `${converter}.floatTo${method}(${value})`;
    default:
      if (integerRangeFitsFloat(remoteTypeId, localTypeId)) {
        return `Number(${value})`;
      }
      return `${converter}.integerTo${method}(${value})`;
  }
}

function floatMethod(prefix: string, localTypeId: number): string | null {
  switch (localTypeId) {
    case TypeId.FLOAT16:
      return `${prefix}16`;
    case TypeId.BFLOAT16:
      return `${prefix === "Float" ? "Bfloat" : "bfloat"}16`;
    case TypeId.FLOAT32:
      return `${prefix}32`;
    case TypeId.FLOAT64:
      return `${prefix}64`;
    default:
      return null;
  }
}

function integerRangeFitsFloat(
  remoteTypeId: number,
  localTypeId: number,
): boolean {
  switch (localTypeId) {
    case TypeId.FLOAT16:
    case TypeId.BFLOAT16:
      return remoteTypeId === TypeId.INT8 || remoteTypeId === TypeId.UINT8;
    case TypeId.FLOAT32:
      return remoteTypeId === TypeId.INT8
        || remoteTypeId === TypeId.INT16
        || remoteTypeId === TypeId.UINT8
        || remoteTypeId === TypeId.UINT16;
    case TypeId.FLOAT64:
      return remoteTypeId === TypeId.INT8
        || remoteTypeId === TypeId.INT16
        || remoteTypeId === TypeId.INT32
        || remoteTypeId === TypeId.UINT8
        || remoteTypeId === TypeId.UINT16
        || remoteTypeId === TypeId.UINT32;
    default:
      return false;
  }
}

class StructSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;
  sortedProps: { key: string; typeInfo: TypeInfo }[];
  typeMeta: TypeMeta;
  serializerExpr: string;
  ownTypeInfoExpr: string;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
    this.sortedProps = sortProps(this.typeInfo, this.builder.resolver);
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

  private isDepthFreeStruct(): boolean {
    return this.sortedProps.length > 0
      && this.sortedProps.every(({ typeInfo }) => isDepthFreeField(typeInfo));
  }

  readField(fieldName: string, fieldTypeInfo: TypeInfo, assignStmt: (expr: string) => string, embedGenerator: SerializerGenerator) {
    const { nullable = false, dynamic, trackingRef } = fieldTypeInfo;
    const refMode = toRefMode(trackingRef, nullable);
    const assignCompatible = (expr: string) => assignStmt(compatibleReadTargetExpr(fieldTypeInfo, expr));
    if (shouldSkipCompatibleRead(fieldTypeInfo)) {
      const discard = (expr: string) => `${expr};`;
      if (this.builder.resolver.isMonomorphic(fieldTypeInfo, dynamic)) {
        if (refMode == RefMode.TRACKING || refMode === RefMode.NULL_ONLY) {
          return embedGenerator.readRefWithoutTypeInfo(discard);
        }
        if (isDepthFreeField(fieldTypeInfo)) {
          return embedGenerator.read(discard, "false");
        }
        return embedGenerator.readWithDepth(discard, "false");
      }
      if (refMode == RefMode.TRACKING || refMode === RefMode.NULL_ONLY) {
        return embedGenerator.readRef(discard);
      }
      return embedGenerator.readNoRef(discard, "false");
    }
    const scalarAction = getCompatibleScalarReadAction(fieldTypeInfo);
    if (scalarAction) {
      const readValue = compatibleScalarFieldReadExpr(
        scalarAction.remoteTypeId,
        scalarAction.localTypeId,
        this.builder,
      );
      if (readValue === null) {
        throw new Error(
          `Unsupported compatible scalar conversion from ${scalarAction.remoteTypeId} to ${scalarAction.localTypeId}`,
        );
      }
      if (scalarAction.remoteNullable !== true) {
        return assignStmt(readValue);
      }
      const refFlag = this.scope.uniqueName("refFlag");
      return `
        const ${refFlag} = ${this.builder.reader.readInt8()};
        switch (${refFlag}) {
          case ${RefFlags.NotNullValueFlag}:
            ${assignStmt(readValue)}
            break;
          case ${RefFlags.NullFlag}:
            ${assignStmt("null")}
            break;
          default:
            throw new Error("Invalid reference flag for compatible scalar field ${CodecBuilder.replaceBackslashAndQuote(fieldName)}");
        }
      `;
    }
    let stmt = "";
    // polymorphic type
    if (this.builder.resolver.isMonomorphic(fieldTypeInfo, dynamic)) {
      if (refMode == RefMode.TRACKING || refMode === RefMode.NULL_ONLY) {
        stmt = `
          ${embedGenerator.readRefWithoutTypeInfo(assignCompatible)}
        `;
      } else if (isDepthFreeField(fieldTypeInfo)) {
        // Leaf types and collections of leaf types cannot recurse — skip depth tracking.
        stmt = embedGenerator.read(assignCompatible, "false");
      } else {
        stmt = embedGenerator.readWithDepth(assignCompatible, "false");
      }
    } else {
      if (refMode == RefMode.TRACKING || refMode === RefMode.NULL_ONLY) {
        stmt = `${embedGenerator.readRef(assignCompatible)}`;
      } else {
        stmt = embedGenerator.readNoRef(assignCompatible, "false");
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
    const fieldWrites: string[] = [];
    for (let i = 0; i < this.sortedProps.length;) {
      const current = this.sortedProps[i];
      if (isDirectVarInt32Field(current.typeInfo, this.builder.resolver)) {
        let end = i + 1;
        while (
          end < this.sortedProps.length
          && isDirectVarInt32Field(this.sortedProps[end].typeInfo, this.builder.resolver)
        ) {
          end++;
        }
        if (end - i > 1) {
          fieldWrites.push(this.writeVarInt32Run(accessor, this.sortedProps.slice(i, end)));
          i = end;
          continue;
        }
      }
      const InnerGeneratorClass = CodegenRegistry.get(current.typeInfo.typeId);
      if (!InnerGeneratorClass) {
        throw new Error(`${current.typeInfo.typeId} generator not exists`);
      }
      const innerGenerator = new InnerGeneratorClass(current.typeInfo, this.builder, this.scope);
      const fieldAccessor = `${accessor}${CodecBuilder.safePropAccessor(current.key)}`;
      fieldWrites.push(this.writeField(current.key, current.typeInfo, fieldAccessor, innerGenerator.writeEmbed()));
      i++;
    }
    return `
      ${!this.builder.resolver.isCompatible() ? this.builder.writer.writeInt32(hash) : ""}
      ${fieldWrites.join(";\n")}
    `;
  }

  private writeVarInt32Run(
    accessor: string,
    fields: { key: string; typeInfo: TypeInfo }[],
  ) {
    const cursor = this.scope.uniqueName("cursor");
    const buffer = this.scope.uniqueName("buffer");
    const dataView = this.scope.uniqueName("dataView");
    const value = this.scope.uniqueName("value");
    const rawCursor = this.scope.uniqueName("rawCursor");
    const u32 = this.scope.uniqueName("u32");
    const locals = fields.map(({ key }) => {
      return {
        key,
        fieldAccessor: `${accessor}${CodecBuilder.safePropAccessor(key)}`,
        local: this.scope.uniqueName(key),
      };
    });
    const checks = locals.map(({ key, local }) => `
      if (${local} === null || ${local} === undefined) {
        throw new Error('Field ${CodecBuilder.safeString(key)} is not nullable');
      }
    `).join("\n");
    const writes = locals.map(({ local }) => `
      ${value} = ((${local} << 1) ^ (${local} >> 31)) >>> 0;
      if (${value} >>> 7 === 0) {
        ${buffer}[${cursor}++] = ${value};
      } else {
        ${rawCursor} = ${cursor};
        if (${value} >>> 14 === 0) {
          ${u32} = ((${value} & 0x7f | 0x80) << 24) | ((${value} >>> 7) << 16);
          ${cursor} += 2;
        } else if (${value} >>> 21 === 0) {
          ${u32} = ((${value} & 0x7f | 0x80) << 24) | ((${value} >>> 7 & 0x7f | 0x80) << 16) | ((${value} >>> 14) << 8);
          ${cursor} += 3;
        } else if (${value} >>> 28 === 0) {
          ${u32} = ((${value} & 0x7f | 0x80) << 24) | ((${value} >>> 7 & 0x7f | 0x80) << 16) | ((${value} >>> 14 & 0x7f | 0x80) << 8) | (${value} >>> 21);
          ${cursor} += 4;
        } else {
          ${u32} = ((${value} & 0x7f | 0x80) << 24) | ((${value} >>> 7 & 0x7f | 0x80) << 16) | ((${value} >>> 14 & 0x7f | 0x80) << 8) | (${value} >>> 21 & 0x7f | 0x80);
          ${buffer}[${rawCursor} + 4] = ${value} >>> 28;
          ${cursor} += 5;
        }
        ${dataView}.setUint32(${rawCursor}, ${u32});
      }
    `).join("\n");
    return `
      ${locals.map(({ local, fieldAccessor }) => `const ${local} = ${fieldAccessor};`).join("\n")}
      ${checks}
      let ${cursor} = ${this.builder.writer.writeGetCursor()};
      const ${buffer} = ${this.builder.writer.getPlatformBuffer()};
      const ${dataView} = ${this.builder.writer.getDataView()};
      let ${value};
      let ${rawCursor};
      let ${u32};
      ${writes}
      ${this.builder.writer.setWriteCursor(cursor)}
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
    const directNumericObjectRead = this.readDirectNumericObject(accessor, refState);
    if (directNumericObjectRead !== null) {
      return `
        ${!this.builder.resolver.isCompatible()
? `
        if(${this.builder.reader.readInt32()} !== ${hash}) {
          throw new Error("Read class version is not consistent with ${hash} ")
        }
      `
: ""}
        ${directNumericObjectRead}
      `;
    }
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
          if (shouldSkipCompatibleRead(this.typeInfo.options!.props![key])) {
            return "";
          }
          return `${CodecBuilder.safePropName(key)}: null`;
        }).filter(Boolean).join(",\n")}
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
        return `
          ${this.readField(key, typeInfo, expr => `${result}${CodecBuilder.safePropAccessor(key)} = ${expr}`, innerGenerator.readEmbed())}
        `;
      }).join(";\n")}
      ${accessor(result)}
    `;
  }

  private readDirectNumericObject(
    accessor: (expr: string) => string,
    refState: string,
  ): string | null {
    const varInt32ObjectRead = this.readDirectVarInt32Object(accessor, refState);
    if (varInt32ObjectRead !== null) {
      return varInt32ObjectRead;
    }
    if (this.typeInfo.options!.withConstructor || this.sortedProps.length === 0) {
      return null;
    }
    const fields: Array<{ key: string; expr: string }> = [];
    for (const { key, typeInfo } of this.sortedProps) {
      if (shouldSkipCompatibleRead(typeInfo)) {
        return null;
      }
      const scalarAction = getCompatibleScalarReadAction(typeInfo);
      const expr = scalarAction?.remoteNullable === true
        ? null
        : scalarAction
          ? compatibleScalarFieldReadExpr(
            scalarAction.remoteTypeId,
            scalarAction.localTypeId,
            this.builder,
          )
          : directNumericFieldReadExpr(typeInfo, this.builder);
      if (expr === null) {
        return null;
      }
      fields.push({ key, expr });
    }
    const result = this.scope.uniqueName("result");
    return `
      const ${result} = {
        ${fields.map(({ key, expr }) => `${CodecBuilder.safePropName(key)}: ${expr}`).join(",\n")}
      };
      ${this.maybeReference(result, refState)}
      ${accessor(result)}
    `;
  }

  private readDirectVarInt32Object(
    accessor: (expr: string) => string,
    refState: string,
  ): string | null {
    if (
      this.typeInfo.options!.withConstructor
      || this.sortedProps.length === 0
    ) {
      return null;
    }
    const fields = [];
    for (const { key, typeInfo } of this.sortedProps) {
      if (shouldSkipCompatibleRead(typeInfo)) {
        return null;
      }
      const kind = varInt32ObjectReadKind(typeInfo, this.builder.resolver);
      if (kind === null) {
        return null;
      }
      fields.push({
        key,
        kind,
        local: this.scope.uniqueName(key),
      });
    }
    const cursor = this.scope.uniqueName("cursor");
    const dataView = this.scope.uniqueName("dataView");
    const byteLength = this.scope.uniqueName("byteLength");
    const fourByteValue = this.scope.uniqueName("fourByteValue");
    const byte = this.scope.uniqueName("byte");
    const value = this.scope.uniqueName("value");
    const result = this.scope.uniqueName("result");
    const reads = fields.map(({ local, kind }) => `
      if (${byteLength} - ${cursor} >= 5) {
        ${fourByteValue} = ${dataView}.getUint32(${cursor}, true);
        ${cursor}++;
        ${value} = ${fourByteValue} & 0x7f;
        if ((${fourByteValue} & 0x80) !== 0) {
          ${cursor}++;
          ${value} |= (${fourByteValue} >>> 1) & 0x3f80;
          if ((${fourByteValue} & 0x8000) !== 0) {
            ${cursor}++;
            ${value} |= (${fourByteValue} >>> 2) & 0x1fc000;
            if ((${fourByteValue} & 0x800000) !== 0) {
              ${cursor}++;
              ${value} |= (${fourByteValue} >>> 3) & 0xfe00000;
              if ((${fourByteValue} & 0x80000000) !== 0) {
                ${value} |= ${dataView}.getUint8(${cursor}++) << 28;
              }
            }
          }
        }
      } else {
        ${byte} = ${dataView}.getUint8(${cursor}++);
        ${value} = ${byte} & 0x7f;
        if ((${byte} & 0x80) !== 0) {
          ${byte} = ${dataView}.getUint8(${cursor}++);
          ${value} |= (${byte} & 0x7f) << 7;
          if ((${byte} & 0x80) !== 0) {
            ${byte} = ${dataView}.getUint8(${cursor}++);
            ${value} |= (${byte} & 0x7f) << 14;
            if ((${byte} & 0x80) !== 0) {
              ${byte} = ${dataView}.getUint8(${cursor}++);
              ${value} |= (${byte} & 0x7f) << 21;
              if ((${byte} & 0x80) !== 0) {
                ${byte} = ${dataView}.getUint8(${cursor}++);
                ${value} |= ${byte} << 28;
              }
            }
          }
        }
      }
      const ${local} = ${kind === "bigint" ? "BigInt(" : ""}(${value} >>> 1) ^ -(${value} & 1)${kind === "bigint" ? ")" : ""};
    `).join("\n");
    return `
      let ${cursor} = ${this.builder.reader.readGetCursor()};
      const ${dataView} = ${this.builder.reader.getDataView()};
      const ${byteLength} = ${dataView}.byteLength;
      let ${fourByteValue};
      let ${byte};
      let ${value};
      ${reads}
      ${this.builder.reader.readSetCursor(cursor)}
      const ${result} = {
        ${fields.map(({ key, local }) => `${CodecBuilder.safePropName(key)}: ${local}`).join(",\n")}
      };
      ${this.maybeReference(result, refState)}
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
      return this.readTypeInfoThen(
        changedSerializer => `
          ${assignStmt(`${changedSerializer}.read(${refState})`)};
        `,
        () => `
        ${this.builder.getReadContextName()}.incReadDepth();
        let ${result} = ${this.serializerExpr}.read(${refState});
        ${this.builder.getReadContextName()}.decReadDepth();
        ${assignStmt(result)};
      `,
        true,
      );
    }
    if (this.isDepthFreeStruct()) {
      return this.readTypeInfoThen(
        changedSerializer => `
          ${assignStmt(`${changedSerializer}.read(${refState})`)};
        `,
        () => `
        let ${result};
          ${this.read(v => `${result} = ${v}`, refState)};
        ${assignStmt(result)};
      `,
        true,
      );
    }
    return this.readTypeInfoThen(
      changedSerializer => `
        ${assignStmt(`${changedSerializer}.read(${refState})`)};
      `,
      () => `
      ${this.builder.getReadContextName()}.incReadDepth();
      let ${result};
      ${this.read(v => `${result} = ${v}`, refState)};
      ${this.builder.getReadContextName()}.decReadDepth();
      ${assignStmt(result)};
    `,
      true,
    );
  }

  readTypeInfo(): string {
    return this.readTypeInfoThen();
  }

  private readTypeInfoThen(
    onMetaChanged?: (changedSerializer: string) => string,
    onMetaUnchanged?: () => string,
    metaChangedExits = false,
  ): string {
    const internalTypeId = this.getInternalTypeId();
    const localHash = this.getHash();
    let namesStmt = "";
    let typeMetaStmt = "";
    let readUserTypeIdStmt = "";
    const unchangedStmt = onMetaUnchanged?.() ?? "";
    const readCompatibleTypeMeta = () => {
      const changedSerializer = this.scope.uniqueName("changedSerializer");
      let unchangedBranch = "";
      if (unchangedStmt && !metaChangedExits) {
        unchangedBranch = ` else {
            ${unchangedStmt}
          }`;
      }
      return `
          const ${changedSerializer} = ${this.builder.typeMetaResolver.readCompatibleStructSerializer(localHash)};
          if (${changedSerializer} !== undefined) {
            ${onMetaChanged?.(changedSerializer) ?? `return ${changedSerializer};`}
          }${unchangedBranch}
          `;
    };
    switch (internalTypeId) {
      case TypeId.STRUCT:
        readUserTypeIdStmt = `${this.builder.reader.readVarUint32Small7()};`;
        break;
      case TypeId.NAMED_COMPATIBLE_STRUCT:
      case TypeId.COMPATIBLE_STRUCT:
        typeMetaStmt = readCompatibleTypeMeta();
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
          typeMetaStmt = readCompatibleTypeMeta();
        }
        break;
      default:
        if (this.builder.resolver.isCompatible()) {
          typeMetaStmt = readCompatibleTypeMeta();
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
      ${typeMetaStmt && !metaChangedExits ? "" : unchangedStmt}
    `;
  }

  readEmbed() {
    // Hoist the serializer lookup into a scope-level const, evaluated once during
    // factory init. Self-recursive structs may still point at a placeholder, so
    // only the fully generated serializer path can hoist derived values below.
    const hoisted = this.scope.declare("ser", this.serializerExpr);
    const scope = this.scope;
    const builder = this.builder;
    const internalTypeId = this.getInternalTypeId();
    const serializer = builder.resolver.getSerializerByTypeInfo(this.typeInfo);
    const canInlineCompatibleTypeInfo = internalTypeId === TypeId.COMPATIBLE_STRUCT
      || internalTypeId === TypeId.NAMED_COMPATIBLE_STRUCT
      || (internalTypeId === TypeId.NAMED_STRUCT && builder.resolver.isCompatible());
    const canUseHeaderCacheFastPath = canInlineCompatibleTypeInfo && serializer?._initialized;
    const inlineCompatibleTypeInfo = (
      onMetaChanged: (changedSerializer: string) => string,
      onMetaUnchanged: () => string,
    ) => {
      if (!canUseHeaderCacheFastPath) {
        const changedSerializer = scope.uniqueName("changedSerializer");
        return `
              const ${changedSerializer} = ${hoisted}.readTypeInfo();
              if (${changedSerializer} !== undefined) {
                ${onMetaChanged(changedSerializer)}
              } else {
                ${onMetaUnchanged()}
              }
            `;
      }
      const changedSerializer = scope.uniqueName("changedSerializer");
      const hoistedHash = scope.declare("serHash", `${hoisted}.getHash()`);
      return `
              ${builder.reader.readUint8()};
              const ${changedSerializer} = ${builder.typeMetaResolver.readCompatibleStructSerializer(hoistedHash, hoisted)};
              if (${changedSerializer} !== undefined) {
                ${onMetaChanged(changedSerializer)}
              } else {
                ${onMetaUnchanged()}
              }
            `;
    };
    return new Proxy({}, {
      get: (target, prop: string) => {
        if (prop === "readNoRef") {
          return (accessor: (expr: string) => string, refState: string) => {
            const result = scope.uniqueName("result");
            return `
              ${inlineCompatibleTypeInfo(
                changedSerializer => `${accessor(`${changedSerializer}.read(${refState})`)};`,
                () => `
                ${builder.getReadContextName()}.incReadDepth();
                let ${result} = ${hoisted}.read(${refState});
                ${builder.getReadContextName()}.decReadDepth();
                ${accessor(result)};
              `,
              )}
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
                ${inlineCompatibleTypeInfo(
                  changedSerializer => `${result} = ${changedSerializer}.read(${refFlag} === ${RefFlags.RefValueFlag});`,
                  () => `
                  ${builder.getReadContextName()}.incReadDepth();
                  ${result} = ${hoisted}.read(${refFlag} === ${RefFlags.RefValueFlag});
                  ${builder.getReadContextName()}.decReadDepth();
                `,
                )}
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
          const bytes = this.typeMetaBytesExpr();
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
            const bytes = this.typeMetaBytesExpr();
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

  getTypeMetaBytes(): string {
    return this.typeMetaBytesExpr();
  }

  private typeMetaBytesExpr(): string {
    return this.scope.declare(
      "typeInfoBytes",
      `new Uint8Array([${this.typeMeta.toBytes().join(",")}])`,
    );
  }
}

CodegenRegistry.register(TypeId.STRUCT, StructSerializerGenerator);
CodegenRegistry.register(TypeId.NAMED_STRUCT, StructSerializerGenerator);
CodegenRegistry.register(TypeId.COMPATIBLE_STRUCT, StructSerializerGenerator);
CodegenRegistry.register(TypeId.NAMED_COMPATIBLE_STRUCT, StructSerializerGenerator);
