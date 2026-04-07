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

import { Type, TypeInfo } from "./typeInfo";
import fory from "./fory";
import { InnerFieldInfo, TypeMeta } from "./meta/TypeMeta";
import { BinaryReader } from "./reader";
import { Serializer, TypeId } from "./type";
import { BinaryWriter } from "./writer";

export class TypeMetaResolver {
  private disposeTypeInfo: TypeInfo[] = [];
  private dynamicTypeId = 0;
  private typeMeta: TypeMeta[] = [];

  constructor(private fory: fory) {

  }

  private fieldInfoToTypeInfo(fieldInfo: InnerFieldInfo): TypeInfo {
    const typeId = fieldInfo.typeId;

    switch (typeId) {
      case TypeId.MAP:
        return Type.map(this.fieldInfoToTypeInfo(fieldInfo.options!.key!), this.fieldInfoToTypeInfo(fieldInfo.options!.value!));
      case TypeId.LIST:
        return Type.array(this.fieldInfoToTypeInfo(fieldInfo.options!.inner!));
      case TypeId.SET:
        return Type.set(this.fieldInfoToTypeInfo(fieldInfo.options!.key!));
      default:
      {
        const serializer = this.fory.typeResolver.getSerializerById(typeId, fieldInfo.userTypeId);
        // registered type
        if (serializer) {
          return serializer.getTypeInfo().clone();
        }
        return Type.any();
      }
    }
  }

  genSerializerByTypeMetaRuntime(typeMeta: TypeMeta, original?: Serializer): Serializer {
    const typeName = typeMeta.getTypeName();
    const ns = typeMeta.getNs();
    const typeId = typeMeta.getTypeId();
    const userTypeId = typeMeta.getUserTypeId();
    if (!TypeId.structType(typeId)) {
      throw new Error("only support reconstructor struct type");
    }
    let typeInfo;
    if (original) {
      typeInfo = original.getTypeInfo().clone();
    } else {
      if (!TypeId.isNamedType(typeId)) {
        typeInfo = Type.struct(userTypeId);
      } else {
        typeInfo = Type.struct({ typeName, namespace: ns });
      }
    }
    // rebuild props
    const props = (Object.fromEntries(typeMeta.getFieldInfo().map((x) => {
      const fieldName = x.getFieldName();
      const fieldTypeInfo = this.fieldInfoToTypeInfo(x).setNullable(x.nullable).setTrackingRef(x.trackingRef).setId(x.fieldId);
      return [fieldName, fieldTypeInfo];
    })));
    typeInfo.options! = {
      ...typeInfo.options,
      props,
    };
    return this.fory.replaceSerializerReader(typeInfo);
  }

  readTypeMeta(reader: BinaryReader): TypeMeta {
    const idOrLen = reader.readVarUInt32();
    if (idOrLen & 1) {
      return this.typeMeta[idOrLen >> 1];
    } else {
      idOrLen >> 1; // not used
      const typeMeta = TypeMeta.fromBytes(reader);
      this.typeMeta.push(typeMeta);
      return typeMeta;
    }
  }

  writeTypeMeta(typeInfo: TypeInfo, writer: BinaryWriter, bytes: Uint8Array) {
    if (typeInfo.dynamicTypeId !== -1) {
      // Reference to previously written type: (index << 1) | 1, LSB=1
      writer.writeVarUInt32((typeInfo.dynamicTypeId << 1) | 1);
    } else {
      // New type: index << 1, LSB=0, followed by TypeMeta bytes inline
      const index = this.dynamicTypeId;
      typeInfo.dynamicTypeId = index;
      this.dynamicTypeId += 1;
      this.disposeTypeInfo.push(typeInfo);
      writer.writeVarUInt32(index << 1);
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

  resetRead() {
    this.typeMeta = [];
  }

  resetWrite() {
    this.disposeTypeInfo.forEach((x) => {
      x.dynamicTypeId = -1;
    });
    this.disposeTypeInfo = [];
    this.dynamicTypeId = 0;
  }
}
