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

import { StructTypeInfo, Type, TypeInfo } from "./typeInfo";
import fory from "./fory";
import { TypeMeta } from "./meta/TypeMeta";
import { BinaryReader } from "./reader";
import { Serializer, TypeId } from "./type";
import { BinaryWriter } from "./writer";

export class TypeMetaResolver {
  private disposeTypeInfo: StructTypeInfo[] = [];
  private dynamicTypeId = 0;
  private typeMeta: TypeMeta[] = [];

  constructor(private fory: fory) {

  }

  private updateTypeInfo(typeMeta: TypeMeta, typeInfo: TypeInfo) {
    typeInfo.options.props = Object.fromEntries(typeMeta.getFieldInfo().map((x) => {
      const typeId = x.getTypeId();
      const fieldName = x.getFieldName();
      const fieldTypeInfo = this.fory.classResolver.getTypeInfo(typeId);
      if (!fieldTypeInfo) {
        throw new Error(`typeid: ${typeId} in prop ${fieldName} not registered`);
      }
      if (!typeInfo.options.fieldInfo) {
        typeInfo.options.fieldInfo = {};
      }
      typeInfo.options.fieldInfo[x.fieldName] = {
        nullable: x.nullable,
        trackingRef: x.trackingRef,
        ...typeInfo.options.fieldInfo[x.fieldName],
      };
      return [fieldName, fieldTypeInfo];
    }));
  }

  genSerializerByTypeMetaRuntime(typeMeta: TypeMeta): Serializer {
    const typeName = typeMeta.getTypeName();
    const ns = typeMeta.getNs();
    const typeId = typeMeta.getTypeId();
    let typeInfo;
    if (!TypeId.isNamedType(typeId)) {
      typeInfo = this.fory.classResolver.getTypeInfo(typeId);
    } else {
      typeInfo = this.fory.classResolver.getTypeInfo(`${ns}$${typeName}`);
    }
    if (!typeInfo) {
      throw new Error(`${typeId} not registered`); // todo
    }
    this.updateTypeInfo(typeMeta, typeInfo);
    return this.fory.replaceSerializerReader(typeInfo);
  }

  readTypeMeta(reader: BinaryReader): TypeMeta {
    const idOrLen = reader.varUInt32();
    if (idOrLen & 1) {
      return this.typeMeta[idOrLen >> 1];
    } else {
      idOrLen >> 1; // not used
      const typeMeta = TypeMeta.fromBytes(reader);
      this.typeMeta.push(typeMeta);
      return typeMeta;
    }
  }

  writeTypeMeta(typeInfo: StructTypeInfo, writer: BinaryWriter, bytes: Uint8Array) {
    if (typeInfo.dynamicTypeId !== -1) {
      // Reference to previously written type: (index << 1) | 1, LSB=1
      writer.varUInt32((typeInfo.dynamicTypeId << 1) | 1);
    } else {
      // New type: index << 1, LSB=0, followed by TypeMeta bytes inline
      const index = this.dynamicTypeId;
      typeInfo.dynamicTypeId = index;
      this.dynamicTypeId += 1;
      this.disposeTypeInfo.push(typeInfo);
      writer.varUInt32(index << 1);
      writer.buffer(bytes);
    }
  }

  reset() {
    this.disposeTypeInfo.forEach((x) => {
      x.dynamicTypeId = -1;
    });
    this.disposeTypeInfo = [];
    this.dynamicTypeId = 0;
    this.typeMeta = [];
  }
}
