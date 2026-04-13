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
import { Serializer, TypeId } from "../type";
import { Scope } from "./scope";
import { TypeMeta } from "../meta/TypeMeta";
import { ReadContext, WriteContext } from "../context";

export class AnyHelper {
  static detectSerializer(readContext: ReadContext) {
    const reader = readContext.reader;
    const typeResolver = readContext.typeResolver;
    const typeId = reader.readUint8();
    let userTypeId = -1;
    if (TypeId.needsUserTypeId(typeId) && typeId !== TypeId.COMPATIBLE_STRUCT) {
      userTypeId = reader.readVarUint32Small7();
    }
    let serializer: Serializer | undefined;

    function buildNamedTypeKey(ns: string, typeName: string) {
      return `${ns}$${typeName}`;
    }

    function tryUpdateSerializer(serializer: Serializer | undefined | null, typeMeta: TypeMeta) {
      if (!serializer) {
        return readContext.genSerializerByTypeMetaRuntime(typeMeta);
      }
      const hash = serializer.getHash();
      if (hash !== typeMeta.getHash()) {
        return readContext.genSerializerByTypeMetaRuntime(typeMeta, serializer);
      }
      return serializer;
    }

    switch (typeId) {
      case TypeId.COMPATIBLE_STRUCT:
        {
          const typeMeta = readContext.readTypeMeta();
          serializer = typeResolver.getSerializerById(typeId, typeMeta.getUserTypeId());
          serializer = tryUpdateSerializer(serializer, typeMeta);
        }
        break;
      case TypeId.NAMED_ENUM:
      case TypeId.NAMED_UNION:
        if (readContext.isCompatible()) {
          const typeMeta = readContext.readTypeMeta();
          const ns = typeMeta.getNs();
          const typeName = typeMeta.getTypeName();
          serializer = typeResolver.getSerializerByName(buildNamedTypeKey(ns, typeName));
        } else {
          const ns = readContext.readNamespace();
          const typeName = readContext.readTypeName();
          serializer = typeResolver.getSerializerByName(buildNamedTypeKey(ns, typeName));
        }
        break;
      case TypeId.NAMED_STRUCT:
      case TypeId.NAMED_EXT:
      case TypeId.NAMED_COMPATIBLE_STRUCT:
        if (readContext.isCompatible() || typeId === TypeId.NAMED_COMPATIBLE_STRUCT) {
          const typeMeta = readContext.readTypeMeta();
          const ns = typeMeta.getNs();
          const typeName = typeMeta.getTypeName();
          const named = buildNamedTypeKey(ns, typeName);
          const namedSerializer = typeResolver.getSerializerByName(named);
          serializer = tryUpdateSerializer(namedSerializer, typeMeta);
        } else {
          const ns = readContext.readNamespace();
          const typeName = readContext.readTypeName();
          serializer = typeResolver.getSerializerByName(buildNamedTypeKey(ns, typeName));
        }
        break;
      default:
        serializer = typeResolver.getSerializerById(typeId, userTypeId);
        break;
    }
    if (!serializer) {
      throw new Error(`can't find implements of typeId: ${typeId}`);
    }
    return serializer;
  }

  static getSerializer(writeContext: WriteContext, v: any) {
    if (v === null || v === undefined) {
      throw new Error("can not guess the type of null or undefined");
    }

    const serializer = writeContext.typeResolver.getSerializerByData(v);
    if (!serializer) {
      throw new Error(`Failed to detect the Fory serializer from JavaScript type: ${typeof v}`);
    }
    writeContext.writer.reserve(serializer.fixedSize);
    return serializer;
  }
}

class AnySerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;
  detectedSerializer: string;
  writerSerializer: string;
  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
    this.detectedSerializer = this.scope.declareVar("detectedSerializer", "null");
    this.writerSerializer = this.scope.declareVar("writerSerializer", "null");
  }

  write(accessor: string): string {
    return `
      ${this.writerSerializer}.write(${accessor});;
    `;
  }

  writeTypeInfo(accessor: string): string {
    return `
      ${this.writerSerializer} = ${this.builder.getExternal(AnyHelper.name)}.getSerializer(${this.builder.getWriteContextName()}, ${accessor});
      ${this.writerSerializer}.writeTypeInfo();
    `;
  }

  readTypeInfo(): string {
    return `
      ${this.detectedSerializer} = ${this.builder.getExternal(AnyHelper.name)}.detectSerializer(${this.builder.getReadContextName()});
    `;
  }

  read(assignStmt: (v: string) => string, refState: string): string {
    return assignStmt(`${this.detectedSerializer}.read(${refState});`);
  }

  getFixedSize(): number {
    return 11;
  }
}

CodegenRegistry.register(TypeId.UNKNOWN, AnySerializerGenerator);
CodegenRegistry.registerExternal(AnyHelper);
