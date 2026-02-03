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

import { MapTypeInfo, TypeInfo } from "../typeInfo";
import { CodecBuilder } from "./builder";
import { BaseSerializerGenerator, SerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { TypeId, RefFlags, Serializer } from "../type";
import { Scope } from "./scope";
import Fory from "../fory";
import { AnyHelper } from "./any";

const MapFlags = {
  /** Whether track elements ref. */
  TRACKING_REF: 0b1,

  /** Whether collection has null. */
  HAS_NULL: 0b10,

  /** Whether collection elements type is not declare type. */
  DECL_ELEMENT_TYPE: 0b100,
};

class MapHeadUtil {
  private static IS_NULL = 0b10;
  private static TRACKING_REF = 0b01;
  static elementInfo(typeId: number, isNull: 0 | 1, trackRef: 0 | 1) {
    return (typeId << 16) | (isNull << 1) | trackRef;
  }

  static isNull(info: number) {
    return info & this.IS_NULL;
  }

  static trackingRef(info: number) {
    return info & this.TRACKING_REF;
  }
}

class MapChunkWriter {
  private preKeyInfo = 0;
  private preValueInfo = 0;

  private chunkSize = 0;
  private chunkOffset = 0;
  private header = 0;

  constructor(private fory: Fory, private keySerializer?: Serializer | null, private valueSerializer?: Serializer | null) {

  }

  private getHead(keyInfo: number, valueInfo: number) {
    let flag = 0;
    if (MapHeadUtil.isNull(valueInfo)) {
      flag |= MapFlags.HAS_NULL;
    }
    if (MapHeadUtil.trackingRef(valueInfo)) {
      flag |= MapFlags.TRACKING_REF;
    }
    if (this.valueSerializer) {
      flag |= MapFlags.DECL_ELEMENT_TYPE;
    }
    flag <<= 3;
    if (MapHeadUtil.isNull(keyInfo)) {
      flag |= MapFlags.HAS_NULL;
    }
    if (MapHeadUtil.trackingRef(keyInfo)) {
      flag |= MapFlags.TRACKING_REF;
    }
    if (this.keySerializer) {
      flag |= MapFlags.DECL_ELEMENT_TYPE;
    }
    return flag;
  }

  private writeHead(keyInfo: number, valueInfo: number) {
    // chunkSize, max 255
    this.chunkOffset = this.fory.binaryWriter.getCursor();
    // KV header
    const header = this.getHead(keyInfo, valueInfo);
    // chunkSize default 0 | KV header
    this.fory.binaryWriter.uint16(header);
    if (this.keySerializer) {
      this.keySerializer.writeTypeInfo(null);
    }
    if (this.valueSerializer) {
      this.valueSerializer.writeTypeInfo(null);
    }
    return header;
  }

  next(keyInfo: number, valueInfo: number) {
    // max size of chunk is 255
    if (this.chunkSize == 255
      || this.chunkOffset == 0
      || this.preKeyInfo !== keyInfo
      || this.preValueInfo !== valueInfo
    ) {
      // new chunk
      this.endChunk();
      this.chunkSize++;
      this.preKeyInfo = keyInfo;
      this.preValueInfo = valueInfo;
      return this.header = this.writeHead(keyInfo, valueInfo);
    }
    this.chunkSize++;
    return this.header;
  }

  endChunk() {
    if (this.chunkOffset > 0) {
      this.fory.binaryWriter.setUint8Position(this.chunkOffset + 1, this.chunkSize);
      this.chunkSize = 0;
    }
  }
}

class MapAnySerializer {
  private keySerializer: Serializer | null = null;
  private valueSerializer: Serializer | null = null;

  constructor(private fory: Fory, keySerializerId: null | number, valueSerializerId: null | number) {
    if (keySerializerId !== null) {
      fory.classResolver.getSerializerById(keySerializerId);
    }
    if (valueSerializerId !== null) {
      fory.classResolver.getSerializerById(valueSerializerId);
    }
  }

  private writeFlag(header: number, v: any) {
    if (header & MapFlags.TRACKING_REF) {
      if (v === null || v === undefined) {
        this.fory.binaryWriter.uint8(RefFlags.NullFlag);
        return true;
      }
      const keyRef = this.fory.referenceResolver.existsWriteObject(v);
      if (keyRef !== undefined) {
        this.fory.binaryWriter.uint8(RefFlags.RefFlag);
        this.fory.binaryWriter.uint16(keyRef);
        return true;
      } else {
        this.fory.binaryWriter.uint8(RefFlags.RefValueFlag);
        return false;
      }
    } else if (header & MapFlags.HAS_NULL) {
      if (v === null || v === undefined) {
        this.fory.binaryWriter.uint8(RefFlags.NullFlag);
        return true;
      } else {
        this.fory.binaryWriter.uint8(RefFlags.NotNullValueFlag);
        return false;
      }
    }
    return false;
  }

  write(value: Map<any, any>) {
    const mapChunkWriter = new MapChunkWriter(this.fory, this.keySerializer, this.valueSerializer);
    this.fory.binaryWriter.writeVarUint32Small7(value.size);
    for (const [k, v] of value.entries()) {
      const keySerializer = this.keySerializer !== null ? this.keySerializer : this.fory.classResolver.getSerializerByData(k);
      const valueSerializer = this.valueSerializer !== null ? this.valueSerializer : this.fory.classResolver.getSerializerByData(v);

      const header = mapChunkWriter.next(
        MapHeadUtil.elementInfo(keySerializer!.getTypeId()!, k == null ? 1 : 0, keySerializer!.needToWriteRef() ? 1 : 0),
        MapHeadUtil.elementInfo(valueSerializer!.getTypeId()!, v == null ? 1 : 0, valueSerializer!.needToWriteRef() ? 1 : 0)
      );
      if (!this.writeFlag(header & 0b00001111, k)) {
        if (this.keySerializer) {
          keySerializer!.write(k);
        } else {
          keySerializer!.writeNoRef(k);
        }
      }
      if (!this.writeFlag(header >> 4, v)) {
        if (this.valueSerializer) {
          valueSerializer!.write(v);
        } else {
          valueSerializer!.writeNoRef(v);
        }
      }
    }
    mapChunkWriter.endChunk();
  }

  private readElement(header: number, serializer: Serializer | null) {
    const declared = header & MapFlags.DECL_ELEMENT_TYPE;
    const includeNone = header & MapFlags.HAS_NULL;
    const trackingRef = header & MapFlags.TRACKING_REF;

    if (!declared) {
      serializer = AnyHelper.detectSerializer(this.fory);
    }
    if (!trackingRef && !includeNone) {
      return serializer!.read(false);
    }
    const flag = this.fory.binaryReader.uint8();
    switch (flag) {
      case RefFlags.RefValueFlag:
        return serializer!.read(true);
      case RefFlags.RefFlag:
        return this.fory.referenceResolver.getReadObject(this.fory.binaryReader.varUInt32());
      case RefFlags.NullFlag:
        return null;
      case RefFlags.NotNullValueFlag:
        return serializer!.read(false);
    }
  }

  read(fromRef: boolean): any {
    let count = this.fory.binaryReader.readVarUint32Small7();
    const result = new Map();
    if (fromRef) {
      this.fory.referenceResolver.reference(result);
    }
    while (count > 0) {
      const header = this.fory.binaryReader.uint16();
      const valueHeader = (header >> 3) & 0b111;
      const keyHeader = header & 0b111;
      const chunkSize = header >> 8;

      let keySerializer = null;
      let valueSerializer = null;

      if (keyHeader & MapFlags.DECL_ELEMENT_TYPE) {
        keySerializer = AnyHelper.detectSerializer(this.fory);
      }
      if (valueHeader & MapFlags.DECL_ELEMENT_TYPE) {
        valueSerializer = AnyHelper.detectSerializer(this.fory);
      }
      for (let index = 0; index < chunkSize; index++) {
        const key = this.readElement(keyHeader, keySerializer);
        const value = this.readElement(valueHeader, valueSerializer);
        result.set(
          key,
          value
        );
        count--;
      }
    }
    return result;
  }
}

export class MapSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: MapTypeInfo;
  keyGenerator: SerializerGenerator;
  valueGenerator: SerializerGenerator;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = <MapTypeInfo>typeInfo;
    this.keyGenerator = CodegenRegistry.newGeneratorByTypeInfo(this.typeInfo.options.key, this.builder, this.scope);
    this.valueGenerator = CodegenRegistry.newGeneratorByTypeInfo(this.typeInfo.options.value, this.builder, this.scope);
  }

  private isAny() {
    return this.typeInfo.options.key.typeId === TypeId.UNKNOWN || this.typeInfo.options.value.typeId === TypeId.UNKNOWN;
  }

  private writeSpecificType(accessor: string) {
    const k = this.scope.uniqueName("k");
    const v = this.scope.uniqueName("v");
    let keyHeader = (this.keyGenerator.needToWriteRef() ? MapFlags.TRACKING_REF : 0);
    keyHeader |= MapFlags.DECL_ELEMENT_TYPE;
    let valueHeader = (this.valueGenerator.needToWriteRef() ? MapFlags.TRACKING_REF : 0);
    valueHeader |= MapFlags.DECL_ELEMENT_TYPE;
    const lastKeyIsNull = this.scope.uniqueName("lastKeyIsNull");
    const lastValueIsNull = this.scope.uniqueName("lastValueIsNull");
    const chunkSize = this.scope.uniqueName("chunkSize");
    const chunkSizeOffset = this.scope.uniqueName("chunkSizeOffset");
    const keyRef = this.scope.uniqueName("keyRef");
    const valueRef = this.scope.uniqueName("valueRef");

    return `
      ${this.builder.writer.writeVarUint32Small7(`${accessor}.size`)}
      let ${lastKeyIsNull} = false;
      let ${lastValueIsNull} = false;
      let ${chunkSize} = 0;
      let ${chunkSizeOffset} = 0;

      for (const [${k}, ${v}] of ${accessor}.entries()) {
        let keyIsNull = ${k} === null || ${k} === undefined;
        let valueIsNull = ${v} === null || ${v} === undefined;
        if (${lastKeyIsNull} !== keyIsNull || ${lastValueIsNull} !== valueIsNull || ${chunkSize} === 0 || ${chunkSize} === 255) {
          if (${chunkSize} > 0) {
            ${this.builder.writer.setUint8Position(`${chunkSizeOffset} + 1`, chunkSize)};
            ${chunkSize} = 0;
          }
          ${chunkSizeOffset} = ${this.builder.writer.getCursor()}
          debugger;
          ${this.builder.writer.uint16(
              `((${valueHeader} | (valueIsNull ? ${MapFlags.HAS_NULL} : 0)) << 3) | (${keyHeader} | (keyIsNull ? ${MapFlags.HAS_NULL} : 0))`
            )
          }
          ${lastKeyIsNull} = keyIsNull;
          ${lastValueIsNull} = valueIsNull;
        }
        if (keyIsNull) {
          ${this.builder.writer.uint8(RefFlags.NullFlag)}
        } else {
          ${this.keyGenerator.needToWriteRef()
          ? `
              const ${keyRef} = ${this.builder.referenceResolver.existsWriteObject(v)};
              if (${keyRef} !== undefined) {
                ${this.builder.writer.uint8(RefFlags.RefFlag)};
                ${this.builder.writer.uint16(keyRef)};
              } else {
                ${this.builder.writer.uint8(RefFlags.RefValueFlag)};
                ${this.keyGenerator.writeEmbed().write(k)}
              }
          `
          : this.keyGenerator.writeEmbed().write(k)}
        }

        if (valueIsNull) {
          ${this.builder.writer.uint8(RefFlags.NullFlag)}
        } else {
          ${this.valueGenerator.needToWriteRef()
          ? `
              const ${valueRef} = ${this.builder.referenceResolver.existsWriteObject(v)};
              if (${valueRef} !== undefined) {
                ${this.builder.writer.uint8(RefFlags.RefFlag)};
                ${this.builder.writer.uint16(valueRef)};
              } else {
                ${this.builder.writer.uint8(RefFlags.RefValueFlag)};
                ${this.valueGenerator.writeEmbed().write(v)};
              }
          `
          : this.valueGenerator.writeEmbed().write(v)}
        }

        ${chunkSize}++;
      }
      if (${chunkSize} > 0) {
        ${this.builder.writer.setUint8Position(`${chunkSizeOffset} + 1`, chunkSize)};
      }
    `;
  }

  write(accessor: string): string {
    const anySerializer = this.builder.getExternal(MapAnySerializer.name);
    if (!this.isAny()) {
      return this.writeSpecificType(accessor);
    }
    return `new (${anySerializer})(${this.builder.getForyName()}, ${this.typeInfo.options.key.typeId !== TypeId.UNKNOWN ? this.typeInfo.options.key.typeId : null
      }, ${this.typeInfo.options.value.typeId !== TypeId.UNKNOWN ? this.typeInfo.options.value.typeId : null
      }).write(${accessor})`;
  }

  private readSpecificType(accessor: (expr: string) => string, refState: string) {
    const count = this.scope.uniqueName("count");
    const result = this.scope.uniqueName("result");

    return `
      let ${count} = ${this.builder.reader.readVarUint32Small7()};
      debugger;
      const ${result} = new Map();
      if (${refState}) {
        ${this.builder.referenceResolver.reference(result)}
      }
      while (${count} > 0) {
        const header = ${this.builder.reader.uint16()};
        const keyHeader = header & 0b111;
        const valueHeader = (header >> 3) & 0b111;
        const chunkSize = header >> 8;
        const keyIncludeNone = keyHeader & ${MapFlags.HAS_NULL};
        const keyTrackingRef = keyHeader & ${MapFlags.TRACKING_REF};
        const valueIncludeNone = valueHeader & ${MapFlags.HAS_NULL};
        const valueTrackingRef = valueHeader & ${MapFlags.TRACKING_REF};
        for (let index = 0; index < chunkSize; index++) {
          let key;
          let value;
          if (keyTrackingRef || keyIncludeNone) {
            const flag = ${this.builder.reader.uint8()};
            switch (flag) {
              case ${RefFlags.RefValueFlag}:
                ${this.keyGenerator.read(x => `key = ${x}`, "true")}
                break;
              case ${RefFlags.RefFlag}:
                key = ${this.builder.referenceResolver.getReadObject(this.builder.reader.varInt32())}
                break;
              case ${RefFlags.NullFlag}:
                key = null;
                break;
              case ${RefFlags.NotNullValueFlag}:
                ${this.keyGenerator.read(x => `key = ${x}`, "false")}
                break;
            }
          } else {
              ${this.keyGenerator.read(x => `key = ${x}`, "false")}
          }
          
          if (valueTrackingRef || valueIncludeNone) {
            const flag = ${this.builder.reader.uint8()};
            switch (flag) {
              case ${RefFlags.RefValueFlag}:
                ${this.valueGenerator.read(x => `value = ${x}`, "true")}
                break;
              case ${RefFlags.RefFlag}:
                value = ${this.builder.referenceResolver.getReadObject(this.builder.reader.varInt32())}
                break;
              case ${RefFlags.NullFlag}:
                value = null;
                break;
              case ${RefFlags.NotNullValueFlag}:
                ${this.valueGenerator.read(x => `value = ${x}`, "false")}
                break;
            }
          } else {
            ${this.valueGenerator.read(x => `value = ${x}`, "false")}
          }
          
          ${result}.set(
            key,
            value
          );
          ${count}--;
        }
      }
      ${accessor(result)}
    `;
  }

  read(accessor: (expr: string) => string, refState: string): string {
    const anySerializer = this.builder.getExternal(MapAnySerializer.name);
    if (!this.isAny()) {
      return this.readSpecificType(accessor, refState);
    }
    return accessor(`new (${anySerializer})(${this.builder.getForyName()}, ${this.typeInfo.options.key.typeId !== TypeId.UNKNOWN ? (this.typeInfo.options.key.typeId) : null
      }, ${this.typeInfo.options.value.typeId !== TypeId.UNKNOWN ? (this.typeInfo.options.value.typeId) : null
      }).read(${refState})`);
  }

  getFixedSize(): number {
    return 7;
  }
}

CodegenRegistry.registerExternal(MapAnySerializer);
CodegenRegistry.register(TypeId.MAP, MapSerializerGenerator);
