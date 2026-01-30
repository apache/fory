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
import { Mode, RefFlags, Serializer, TypeId } from "../type";
import { Scope } from "./scope";
import Fory from "../fory";

class AnyHelper {
  static detectSerializer(fory: Fory) {
    const typeId = fory.binaryReader.readVarUint32Small7();
    let serializer: Serializer | undefined;
    const internalTypeId = typeId & 0xff;

    switch (internalTypeId) {
      case TypeId.NAMED_ENUM:
      case TypeId.NAMED_STRUCT:
      case TypeId.NAMED_EXT:
      case TypeId.NAMED_UNION:
      case TypeId.NAMED_COMPATIBLE_STRUCT:
        if (fory.config.mode === Mode.Compatible) {
          const typeMeta = fory.typeMetaResolver.readTypeMeta(fory.binaryReader);
          const ns = typeMeta.getNs();
          const typeName = typeMeta.getTypeName();
          const named = `${ns}$${typeName}`;
          serializer = fory.classResolver.getSerializerByName(named);
          if (!serializer) {
            throw new Error(`can't find implements of typeId: ${typeId}`);
          }
          const hash = serializer.getHash();
          if (hash !== typeMeta.getHash()) {
            serializer = fory.typeMetaResolver.genSerializerByTypeMetaRuntime(typeMeta);
          }
        } else {
          const ns = fory.metaStringResolver.readNamespace(fory.binaryReader);
          const typeName = fory.metaStringResolver.readTypeName(fory.binaryReader);
          serializer = fory.classResolver.getSerializerByName(`${ns}$${typeName}`);
        }
        break;
      case TypeId.COMPATIBLE_STRUCT:
        if (fory.config.mode === Mode.Compatible) {
          const typeMeta = fory.typeMetaResolver.readTypeMeta(fory.binaryReader);
          serializer = fory.classResolver.getSerializerById(typeId);
          if (!serializer) {
            throw new Error(`can't find implements of typeId: ${typeId}`);
          }
          const hash = serializer.getHash();
          if (hash !== typeMeta.getHash()) {
            serializer = fory.typeMetaResolver.genSerializerByTypeMetaRuntime(typeMeta);
          }
        } else {
          serializer = fory.classResolver.getSerializerById(typeId);
        }
        break;
      default:
        serializer = fory.classResolver.getSerializerById(typeId);
        break;
    }
    if (!serializer) {
      throw new Error(`can't find implements of typeId: ${typeId}`);
    }
    return serializer;
  }

  static getSerializer(fory: Fory, v: any) {
    if (v === null || v === undefined) {
      throw new Error("can not guess the type of null or undefined");
    }

    const serializer = fory.classResolver.getSerializerByData(v);
    if (!serializer) {
      throw new Error(`Failed to detect the Fory serializer from JavaScript type: ${typeof v}`);
    }
    fory.binaryWriter.reserve(serializer.fixedSize);
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

  writeClassInfo(accessor: string): string {
    return `
      ${this.writerSerializer} = ${this.builder.getExternal(AnyHelper.name)}.getSerializer(${this.builder.getForyName()}, ${accessor});
      ${this.writerSerializer}.writeClassInfo();
    `;
  }

  readClassInfo(): string {
    return `
      ${this.detectedSerializer} = ${this.builder.getExternal(AnyHelper.name)}.detectSerializer(${this.builder.getForyName()});
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
