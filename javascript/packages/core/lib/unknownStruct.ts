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

import type { ReadContext, WriteContext } from "./context";
import { InnerFieldInfo, TypeMeta, refTrackingUnableTypeId } from "./meta/TypeMeta";
import { RefFlags, Serializer, TypeId } from "./type";
import { Type, TypeInfo } from "./typeInfo";
import { CollectionAnySerializer } from "./gen/collection";
import { MapAnySerializer } from "./gen/map";

const REFERENCE_BYTES = 4;
const JS_STRUCT_OWNER_BYTES = 6 * REFERENCE_BYTES;
const UNKNOWN_TYPE_META = Symbol("foryUnknownStructTypeMeta");
const UNKNOWN_FIELDS = Symbol("foryUnknownStructFields");

type Resolver = {
  trackingRef: boolean;
  isCompatible(): boolean;
  getSerializerById(typeId: number, userTypeId?: number): Serializer | undefined;
};

type UnknownValue = Record<PropertyKey, any> & {
  [UNKNOWN_TYPE_META]: TypeMeta;
};

type PreparedField = {
  name: string;
  type: InnerFieldInfo;
  dynamic: boolean;
  leaf: boolean;
  serializer: Serializer;
  elementDynamic?: boolean;
  keyDynamic?: boolean;
  valueDynamic?: boolean;
  collection?: CollectionAnySerializer;
  map?: MapAnySerializer;
};

type PreparedStruct = {
  fields: PreparedField[];
  graphBytes: number;
};

type PreparedOwners = WeakMap<UnknownStructSerializer, PreparedStruct>;

export function getUnknownTypeMeta(value: unknown): TypeMeta | undefined {
  if (typeof value !== "object" || value === null) {
    return undefined;
  }
  return (value as Partial<UnknownValue>)[UNKNOWN_TYPE_META];
}

function preparedOwners(typeMeta: TypeMeta): PreparedOwners {
  let owners = (typeMeta as any)[UNKNOWN_FIELDS] as PreparedOwners | undefined;
  if (owners === undefined) {
    owners = new WeakMap();
    Object.defineProperty(typeMeta, UNKNOWN_FIELDS, { value: owners });
  }
  return owners;
}

class FieldBodySerializer implements Serializer {
  readonly _initialized = true;
  readonly fixedSize = 8;
  private readonly typeInfo;

  constructor(
    private readonly owner: UnknownStructSerializer,
    private readonly field: PreparedField,
  ) {
    this.typeInfo = new TypeInfo(this.field.type.typeId, this.field.type.userTypeId);
  }

  needToWriteRef = () => this.owner.trackingRef && !refTrackingUnableTypeId(this.field.type.typeId);

  getTypeId = () => this.field.type.typeId;
  getUserTypeId = () => this.field.type.userTypeId;
  getTypeInfo = () => this.typeInfo;
  getHash = () => 0;
  getTypeMetaBytes = () => undefined;
  write = (value: any) => this.owner.writeBody(this.field, value);
  read = (fromRef: boolean) => this.owner.readBody(this.field, fromRef);

  writeRefOrNull = (value: any) => {
    return this.owner.writeContext.writeRefOrNull(value);
  };

  writeRef = (value: any) => {
    if (!this.writeRefOrNull(value)) {
      this.writeTypeInfo(value);
      this.write(value);
    }
  };

  writeNoRef = (value: any) => {
    this.writeTypeInfo(value);
    this.write(value);
  };

  writeTypeInfo: Serializer["writeTypeInfo"] = () => {
    this.owner.writeContext.writer.writeUint8(this.field.type.typeId);
    if (
      TypeId.needsUserTypeId(this.field.type.typeId) &&
      this.field.type.typeId !== TypeId.COMPATIBLE_STRUCT
    ) {
      this.owner.writeContext.writer.writeVarUint32Small7(this.field.type.userTypeId);
    }
  };

  readRef = () => this.owner.readFramed(this, true);
  readRefWithoutTypeInfo = () => this.owner.readFramed(this, false);

  readNoRef = (fromRef: boolean) => {
    this.readTypeInfo();
    return this.owner.readNested(this, fromRef, this.field.leaf);
  };

  readTypeInfo = () => {
    this.owner.readContext.reader.readUint8();
    if (
      TypeId.needsUserTypeId(this.field.type.typeId) &&
      this.field.type.typeId !== TypeId.COMPATIBLE_STRUCT
    ) {
      this.owner.readContext.reader.readVarUint32Small7();
    }
  };

  readonly readDataAlwaysAdvances = false;
}

export class UnknownStructSerializer implements Serializer {
  readonly _initialized = true;
  readonly fixedSize = 8;
  readonly trackingRef: boolean;
  private readonly typeInfo = Type.any();
  private boundTypeMeta: TypeMeta | undefined;

  constructor(
    private readonly resolver: Resolver,
    readonly writeContext: WriteContext,
    readonly readContext: ReadContext,
  ) {
    this.trackingRef = resolver.trackingRef;
  }

  private get anySerializer() {
    return this.resolver.getSerializerById(TypeId.UNKNOWN)!;
  }

  bind(typeMeta: TypeMeta) {
    this.validateTypeMeta(typeMeta);
    this.prepare(typeMeta);
    this.boundTypeMeta = typeMeta;
    return this;
  }

  prepare(typeMeta: TypeMeta) {
    const owners = preparedOwners(typeMeta);
    let prepared = owners.get(this);
    if (prepared !== undefined) {
      return prepared;
    }
    const fields = typeMeta.getFieldInfo().map((field) => this.prepareField(field));
    prepared = {
      fields,
      graphBytes: JS_STRUCT_OWNER_BYTES + fields.length * REFERENCE_BYTES,
    };
    owners.set(this, prepared);
    return prepared;
  }

  getTypeIdentity = (value: any) => getUnknownTypeMeta(value);
  needToWriteRef = () => this.trackingRef;
  getTypeId = () => TypeId.COMPATIBLE_STRUCT;
  getUserTypeId = () => -1;
  getTypeInfo = () => this.typeInfo;
  getHash = () => this.boundTypeMeta?.getHash() ?? 0;
  getTypeMetaBytes = () => this.boundTypeMeta?.toBytes();

  writeTypeInfo = (value: any) => {
    const typeMeta = this.validateValue(value);
    this.writeContext.writer.writeUint8(typeMeta.getTypeId());
    this.writeContext.writeTypeMeta(typeMeta, typeMeta.toBytes());
  };

  write = (value: any) => {
    const typeMeta = this.validateValue(value);
    const prepared = this.prepare(typeMeta);
    for (const field of prepared.fields) {
      this.writeField(field, value[field.name]);
    }
  };

  writeRefOrNull = (value: any) => this.writeContext.writeRefOrNull(value);

  writeRef = (value: any) => {
    if (!this.writeRefOrNull(value)) {
      this.writeTypeInfo(value);
      this.write(value);
    }
  };

  writeNoRef = (value: any) => {
    this.writeTypeInfo(value);
    this.write(value);
  };

  read = (fromRef: boolean) => {
    const typeMeta = this.boundTypeMeta;
    if (typeMeta === undefined) {
      throw new Error("UnknownStruct TypeMeta is not bound");
    }
    const prepared = this.prepare(typeMeta);
    const result = Object.create(null) as UnknownValue;
    Object.defineProperty(result, UNKNOWN_TYPE_META, { value: typeMeta });
    this.readContext.reserveGraphMemory(prepared.graphBytes);
    if (fromRef) {
      this.readContext.reference(result);
    }
    for (const field of prepared.fields) {
      result[field.name] = this.readField(field);
    }
    // Nested unknown fields bind this shared serializer to their own TypeMeta.
    // Restore the completed owner's schema so a homogeneous container can read
    // its next body without repeating TypeInfo. Root cleanup owns failed reads.
    this.boundTypeMeta = typeMeta;
    return result;
  };

  readRef = () => this.readFramed(this, true);
  readRefWithoutTypeInfo = () => this.readFramed(this, false);

  readNoRef = (fromRef: boolean) => {
    this.readTypeInfo();
    return this.readNested(this, fromRef, false);
  };

  readTypeInfo = () => {
    const typeId = this.readContext.reader.readUint8();
    let typeMeta: TypeMeta;
    switch (typeId) {
      case TypeId.COMPATIBLE_STRUCT:
      case TypeId.NAMED_COMPATIBLE_STRUCT:
      case TypeId.NAMED_STRUCT:
        typeMeta = this.readContext.readTypeMeta();
        break;
      default:
        throw new Error(`UnknownStruct requires compatible Struct TypeMeta, got ${typeId}`);
    }
    this.bind(typeMeta);
    return this;
  };

  readonly readDataAlwaysAdvances = false;

  readFramed(serializer: Serializer, withTypeInfo: boolean) {
    const flag = this.readContext.reader.readInt8();
    switch (flag) {
      case RefFlags.NullFlag:
        return null;
      case RefFlags.RefFlag:
        return this.readContext.getReadRef(this.readContext.reader.readVarUInt32());
      case RefFlags.NotNullValueFlag:
      case RefFlags.RefValueFlag:
        if (withTypeInfo) {
          serializer.readTypeInfo();
        }
        return this.readNested(serializer, flag === RefFlags.RefValueFlag, false);
      default:
        return null;
    }
  }

  readNested(serializer: Serializer, fromRef: boolean, leaf: boolean) {
    if (leaf) {
      return serializer.read(fromRef);
    }
    this.readContext.incReadDepth();
    const value = serializer.read(fromRef);
    this.readContext.decReadDepth();
    return value;
  }

  readBody(field: PreparedField, fromRef: boolean) {
    if (field.collection !== undefined) {
      if (field.type.typeId === TypeId.SET) {
        return field.collection.read(
          (result, _index, value) => result.add(value),
          () => new Set(),
          fromRef,
        );
      }
      return field.collection.read(
        (result, index, value) => (result[index] = value),
        (length) => new Array(length),
        fromRef,
      );
    }
    if (field.map !== undefined) {
      return field.map.read(fromRef);
    }
    throw new Error(`Unsupported unknown Struct field type ${field.type.typeId}`);
  }

  writeBody(field: PreparedField, value: any) {
    if (field.collection !== undefined) {
      if (field.elementDynamic) {
        field.collection.write(value, value.size ?? value.length);
      } else {
        field.collection.writeDeclared(value, value.size ?? value.length);
      }
      return;
    }
    if (field.map !== undefined) {
      field.map.write(value, !field.keyDynamic, !field.valueDynamic);
      return;
    }
    throw new Error(`Unsupported unknown Struct field type ${field.type.typeId}`);
  }

  private prepareField(field: InnerFieldInfo & { fieldName?: string }): PreparedField {
    const prepared = this.prepareInner(field);
    prepared.name = field.fieldName ?? "";
    return prepared;
  }

  private prepareInner(type: InnerFieldInfo): PreparedField {
    const field = {
      name: "",
      type,
      dynamic: false,
      leaf: TypeId.isLeafTypeId(type.typeId),
      serializer: undefined as unknown as Serializer,
    } as PreparedField;
    if (type.typeId === TypeId.LIST || type.typeId === TypeId.SET) {
      const inner = this.prepareInner(
        type.typeId === TypeId.LIST ? type.options!.inner! : type.options!.key!,
      );
      field.elementDynamic = inner.dynamic;
      field.leaf = inner.leaf;
      field.collection = new CollectionAnySerializer(
        this.writeContext,
        this.readContext,
        inner.dynamic ? null : inner.serializer,
      );
      field.serializer = new FieldBodySerializer(this, field);
      return field;
    }
    if (type.typeId === TypeId.MAP) {
      const key = this.prepareInner(type.options!.key!);
      const value = this.prepareInner(type.options!.value!);
      field.leaf = key.leaf && value.leaf;
      field.keyDynamic = key.dynamic;
      field.valueDynamic = value.dynamic;
      field.map = new MapAnySerializer(
        this.writeContext,
        this.readContext,
        key.dynamic ? null : key.serializer,
        value.dynamic ? null : value.serializer,
      );
      field.serializer = new FieldBodySerializer(this, field);
      return field;
    }
    const serializer = this.resolveSerializer(type);
    if (serializer === undefined) {
      field.dynamic = true;
      field.leaf = false;
      field.serializer = this.anySerializer;
    } else {
      field.serializer = serializer;
    }
    return field;
  }

  private resolveSerializer(type: InnerFieldInfo) {
    if (
      type.typeId === TypeId.UNKNOWN ||
      TypeId.structType(type.typeId) ||
      TypeId.extType(type.typeId)
    ) {
      return undefined;
    }
    return this.resolver.getSerializerById(type.typeId, type.userTypeId);
  }

  private readField(field: PreparedField) {
    if (field.dynamic) {
      if (field.type.trackingRef || field.type.nullable) {
        return this.anySerializer.readRef();
      }
      return this.anySerializer.readNoRef(false);
    }
    if (field.type.trackingRef || field.type.nullable) {
      return this.readFramed(field.serializer, false);
    }
    return this.readNested(field.serializer, false, field.leaf);
  }

  private writeField(field: PreparedField, value: any) {
    if (field.dynamic) {
      if (field.type.trackingRef) {
        this.anySerializer.writeRef(value);
      } else if (field.type.nullable) {
        if (value === null || value === undefined) {
          this.writeContext.writer.writeInt8(RefFlags.NullFlag);
        } else {
          this.writeContext.writer.writeInt8(RefFlags.NotNullValueFlag);
          this.anySerializer.writeNoRef(value);
        }
      } else {
        this.requireValue(field.name, value);
        this.anySerializer.writeNoRef(value);
      }
      return;
    }
    if (field.type.trackingRef) {
      if (!field.serializer.writeRefOrNull(value)) {
        field.serializer.write(value);
      }
    } else if (field.type.nullable) {
      if (value === null || value === undefined) {
        this.writeContext.writer.writeInt8(RefFlags.NullFlag);
      } else {
        this.writeContext.writer.writeInt8(RefFlags.NotNullValueFlag);
        field.serializer.write(value);
      }
    } else {
      this.requireValue(field.name, value);
      field.serializer.write(value);
    }
  }

  private requireValue(name: string, value: any) {
    if (value === null || value === undefined) {
      throw new Error(`Field "${name}" is not nullable`);
    }
  }

  private validateTypeMeta(typeMeta: TypeMeta) {
    if (!this.resolver.isCompatible() || !TypeId.structType(typeMeta.getTypeId())) {
      throw new Error("UnknownStruct requires compatible Struct TypeMeta");
    }
  }

  private validateValue(value: any) {
    const typeMeta = getUnknownTypeMeta(value);
    if (typeMeta === undefined) {
      throw new Error("UnknownStruct value does not carry TypeMeta");
    }
    this.validateTypeMeta(typeMeta);
    for (const field of typeMeta.getFieldInfo()) {
      if (!Object.prototype.hasOwnProperty.call(value, field.getFieldName())) {
        throw new Error(`UnknownStruct field "${field.getFieldName()}" is missing`);
      }
    }
    return typeMeta;
  }
}
