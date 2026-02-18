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

const MapFlags = {
  /** Whether track elements ref. */
  TRACKING_REF: 0b1,

  /** Whether collection has null. */
  HAS_NULL: 0b10,

  /** Whether collection elements type is not declare type. */
  DECL_ELEMENT_TYPE: 0b100,
};

class ElementInfo {
  constructor(public serializer: Serializer | null, public isNull: boolean, public trackRef: boolean) {
  }

  equalTo(other: ElementInfo | null) {
    if (other === null) {
      return false;
    }
    return this.serializer === other.serializer
      && this.isNull === other.isNull
      && this.trackRef === other.trackRef;
  }
}

class MapChunkWriter {
  private preKeyInfo: ElementInfo | null = null;
  private preValueInfo: ElementInfo | null = null;

  private chunkSize = 0;
  private chunkOffset = 0;
  private header = 0;

  constructor(private fory: Fory, private keySerializer?: Serializer | null, private valueSerializer?: Serializer | null) {

  }

  private getHead(keyInfo: ElementInfo, valueInfo: ElementInfo) {
    let flag = 0;
    if (valueInfo.isNull) {
      flag |= MapFlags.HAS_NULL;
    }
    if (valueInfo.trackRef) {
      flag |= MapFlags.TRACKING_REF;
    }
    if (this.valueSerializer) {
      flag |= MapFlags.DECL_ELEMENT_TYPE;
    }
    flag <<= 3;
    if (keyInfo.isNull) {
      flag |= MapFlags.HAS_NULL;
    }
    if (keyInfo.trackRef) {
      flag |= MapFlags.TRACKING_REF;
    }
    if (this.keySerializer) {
      flag |= MapFlags.DECL_ELEMENT_TYPE;
    }
    return flag;
  }

  private writeHead(keyInfo: ElementInfo, valueInfo: ElementInfo, withOutSize = false) {
    // KV header
    const header = this.getHead(keyInfo, valueInfo);
    // chunkSize default 0 | KV header
    this.fory.binaryWriter.writeUint8(header);

    if (!withOutSize) {
      // chunkSize, max 255
      this.chunkOffset = this.fory.binaryWriter.writeGetCursor();
      this.fory.binaryWriter.writeUint8(0);
    } else {
      this.chunkOffset = 0;
    }
    return header;
  }

  public isFirst() {
    return this.chunkSize === 0 || this.chunkSize === 1;
  }

  next(keyInfo: ElementInfo, valueInfo: ElementInfo) {
    if (keyInfo.isNull || valueInfo.isNull) {
      this.endChunk();
      this.header = this.writeHead(keyInfo, valueInfo, true);
      this.preKeyInfo = keyInfo;
      this.preValueInfo = valueInfo;
      return this.header;
    }
    // max size of chunk is 255
    if (this.chunkSize == 255
      || this.chunkOffset == 0
      || !keyInfo.equalTo(this.preKeyInfo)
      || !valueInfo.equalTo(this.preValueInfo)
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
      this.fory.binaryWriter.setUint8Position(this.chunkOffset, this.chunkSize);
      this.chunkSize = 0;
    }
  }
}

class MapAnySerializer {
  constructor(private fory: Fory, private keySerializer: Serializer | null, private valueSerializer: Serializer | null) {

  }

  private writeFlag(header: number, v: any) {
    if (header & MapFlags.HAS_NULL) {
      return true;
    }
    if (header & MapFlags.TRACKING_REF) {
      const keyRef = this.fory.referenceResolver.existsWriteObject(v);
      if (keyRef !== undefined) {
        this.fory.binaryWriter.writeInt8(RefFlags.RefFlag);
        this.fory.binaryWriter.writeVarUInt32(keyRef);
        return true;
      } else {
        this.fory.binaryWriter.writeInt8(RefFlags.RefValueFlag);
        return false;
      }
    }
    return false;
  }

  write(value: Map<any, any>) {
    const mapChunkWriter = new MapChunkWriter(this.fory, this.keySerializer, this.valueSerializer);
    this.fory.binaryWriter.writeVarUint32Small7(value.size);
    for (const [k, v] of value.entries()) {
      const keySerializer = this.keySerializer !== null ? this.keySerializer : this.fory.typeResolver.getSerializerByData(k);
      const valueSerializer = this.valueSerializer !== null ? this.valueSerializer : this.fory.typeResolver.getSerializerByData(v);

      const header = mapChunkWriter.next(
        new ElementInfo(keySerializer || null, k == null, keySerializer?.needToWriteRef() || false),
        new ElementInfo(valueSerializer || null, v == null, valueSerializer?.needToWriteRef() || false)
      );
      const keyHeader = header & 0b111;
      const valueHeader = (header >> 3);
      if (mapChunkWriter.isFirst()) {
        if (!(keyHeader & MapFlags.HAS_NULL) && !(valueHeader & MapFlags.HAS_NULL)) {
          if (!(keyHeader & MapFlags.DECL_ELEMENT_TYPE)) {
            keySerializer?.writeTypeInfo(null);
          }
          if (!(valueHeader & MapFlags.DECL_ELEMENT_TYPE)) {
            valueSerializer?.writeTypeInfo(null);
          }
        }
      }

      const includeNone = (keyHeader & MapFlags.HAS_NULL) || (valueHeader & MapFlags.HAS_NULL);
      if (!this.writeFlag(keyHeader, k)) {
        if (!includeNone) {
          keySerializer!.write(k);
        } else {
          keySerializer!.writeNoRef(k);
        }
      }
      if (!this.writeFlag(valueHeader, v)) {
        if (!includeNone) {
          valueSerializer!.write(v);
        } else {
          valueSerializer!.writeNoRef(v);
        }
      }
    }
    mapChunkWriter.endChunk();
  }

  private readElement(header: number, serializer: Serializer | null) {
    const includeNone = header & MapFlags.HAS_NULL;
    const trackingRef = header & MapFlags.TRACKING_REF;

    if (includeNone) {
      return null;
    }
    if (!trackingRef) {
      serializer = serializer == null ? AnyHelper.detectSerializer(this.fory) : serializer;
      return serializer!.read(false);
    }

    const flag = this.fory.binaryReader.readInt8();
    switch (flag) {
      case RefFlags.RefValueFlag:
        serializer = serializer == null ? AnyHelper.detectSerializer(this.fory) : serializer;
        return serializer!.read(true);
      case RefFlags.RefFlag:
        return this.fory.referenceResolver.getReadObject(this.fory.binaryReader.readVarUInt32());
      case RefFlags.NullFlag:
        return null;
      case RefFlags.NotNullValueFlag:
        serializer = serializer == null ? AnyHelper.detectSerializer(this.fory) : serializer;
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
      const header = this.fory.binaryReader.readUint8();
      const valueHeader = (header >> 3) & 0b111;
      const keyHeader = header & 0b111;
      let chunkSize = 0;
      if ((valueHeader & MapFlags.HAS_NULL) || (keyHeader & MapFlags.HAS_NULL)) {
        chunkSize = 1;
      } else {
        chunkSize = this.fory.binaryReader.readUint8();
      }
      let keySerializer = this.keySerializer;
      let valueSerializer = this.valueSerializer;

      if (!(keyHeader & MapFlags.HAS_NULL) && !(valueHeader & MapFlags.HAS_NULL)) {
        if (!(keyHeader & MapFlags.DECL_ELEMENT_TYPE)) {
          keySerializer = AnyHelper.detectSerializer(this.fory);
        }

        if (!(valueHeader & MapFlags.DECL_ELEMENT_TYPE)) {
          valueSerializer = AnyHelper.detectSerializer(this.fory);
        }
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
  typeInfo: TypeInfo;
  keyGenerator: SerializerGenerator;
  valueGenerator: SerializerGenerator;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
    this.keyGenerator = CodegenRegistry.newGeneratorByTypeInfo(this.typeInfo.options!.key!, this.builder, this.scope);
    this.valueGenerator = CodegenRegistry.newGeneratorByTypeInfo(this.typeInfo.options!.value!, this.builder, this.scope);
  }

  private isAny() {
    return this.typeInfo.options?.key!.typeId === TypeId.UNKNOWN || this.typeInfo.options?.value!.typeId === TypeId.UNKNOWN || !this.typeInfo.options?.key?.isMonomorphic() || !this.typeInfo.options?.value?.isMonomorphic();
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
        if (${lastKeyIsNull} !== keyIsNull || ${lastValueIsNull} !== valueIsNull || ${chunkSize} === 0 || ${chunkSize} === 255 || keyIsNull || valueIsNull) {
          if (${chunkSize} > 0) {
            ${this.builder.writer.setUint8Position(`${chunkSizeOffset}`, chunkSize)};
            ${chunkSize} = 0;
          }
          if (keyIsNull || valueIsNull) {
            ${this.builder.writer.writeUint8(
                `((${valueHeader} | (valueIsNull ? ${MapFlags.HAS_NULL} : 0)) << 3) | (${keyHeader} | (keyIsNull ? ${MapFlags.HAS_NULL} : 0))`
              )
            }
          } else {
            ${chunkSizeOffset} = ${this.builder.writer.writeGetCursor()} + 1;
            ${this.builder.writer.writeUint16(
                `((${valueHeader} | (valueIsNull ? ${MapFlags.HAS_NULL} : 0)) << 3) | (${keyHeader} | (keyIsNull ? ${MapFlags.HAS_NULL} : 0))`
              )
            }
          }
          ${lastKeyIsNull} = keyIsNull;
          ${lastValueIsNull} = valueIsNull;
        }
        if (!keyIsNull) {
          ${this.keyGenerator.needToWriteRef()
          ? `
              const ${keyRef} = ${this.builder.referenceResolver.existsWriteObject(v)};
              if (${keyRef} !== undefined) {
                ${this.builder.writer.writeInt8(RefFlags.RefFlag)};
                ${this.builder.writer.writeVarUInt32(keyRef)};
              } else {
                ${this.builder.writer.writeInt8(RefFlags.RefValueFlag)};
                ${this.keyGenerator.writeEmbed().write(k)}
              }
          `
          : this.keyGenerator.writeEmbed().write(k)}
        }

        if (!valueIsNull) {
          ${this.valueGenerator.needToWriteRef()
          ? `
              const ${valueRef} = ${this.builder.referenceResolver.existsWriteObject(v)};
              if (${valueRef} !== undefined) {
                ${this.builder.writer.writeInt8(RefFlags.RefFlag)};
                ${this.builder.writer.writeVarUInt32(valueRef)};
              } else {
                ${this.builder.writer.writeInt8(RefFlags.RefValueFlag)};
                ${this.valueGenerator.writeEmbed().write(v)};
              }
          `
          : this.valueGenerator.writeEmbed().write(v)}
        }
        if (!keyIsNull && !valueIsNull) {
          ${chunkSize}++;
        }
      }
      if (${chunkSize} > 0) {
        ${this.builder.writer.setUint8Position(`${chunkSizeOffset}`, chunkSize)};
      }
    `;
  }

  write(accessor: string): string {
    const anySerializer = this.builder.getExternal(MapAnySerializer.name);
    if (!this.isAny()) {
      return this.writeSpecificType(accessor);
    }
    const innerSerializer = (innerTypeInfo: TypeInfo) => {
      if (!innerTypeInfo.isMonomorphic()) {
        return null;
      }
      return this.scope.declare(
        "map_inner_ser",
        TypeId.isNamedType(innerTypeInfo.typeId)
          ? this.builder.typeResolver.getSerializerByName(CodecBuilder.replaceBackslashAndQuote(innerTypeInfo.named!))
          : this.builder.typeResolver.getSerializerById(innerTypeInfo.typeId, innerTypeInfo.userTypeId)
      );
    };
    return `new (${anySerializer})(${this.builder.getForyName()}, ${this.typeInfo.options!.key!.typeId !== TypeId.UNKNOWN ? innerSerializer(this.typeInfo.options!.key!) : null
      }, ${this.typeInfo.options!.value!.typeId !== TypeId.UNKNOWN ? innerSerializer(this.typeInfo.options!.value!) : null
      }).write(${accessor})`;
  }

  private readSpecificType(accessor: (expr: string) => string, refState: string) {
    const count = this.scope.uniqueName("count");
    const result = this.scope.uniqueName("result");

    return `
      let ${count} = ${this.builder.reader.readVarUint32Small7()};
      const ${result} = new Map();
      if (${refState}) {
        ${this.builder.referenceResolver.reference(result)}
      }
      while (${count} > 0) {
        const header = ${this.builder.reader.readUint8()};
        const keyHeader = header & 0b111;
        const valueHeader = (header >> 3) & 0b111;
        const keyIncludeNone = keyHeader & ${MapFlags.HAS_NULL};
        const keyTrackingRef = keyHeader & ${MapFlags.TRACKING_REF};
        const valueIncludeNone = valueHeader & ${MapFlags.HAS_NULL};
        const valueTrackingRef = valueHeader & ${MapFlags.TRACKING_REF};
        let chunkSize = 1;
        if (!keyIncludeNone && !valueIncludeNone) {
          chunkSize = ${this.builder.reader.readUint8()};
        }
        for (let index = 0; index < chunkSize; index++) {
          let key;
          let value;
          if (keyIncludeNone) {
             key = null;
          } else if (keyTrackingRef) {
            const flag = ${this.builder.reader.readInt8()};
            switch (flag) {
              case ${RefFlags.RefValueFlag}:
                ${this.keyGenerator.read(x => `key = ${x}`, "true")}
                break;
              case ${RefFlags.RefFlag}:
                key = ${this.builder.referenceResolver.getReadObject(this.builder.reader.readVarUInt32())}
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
          
          if (valueIncludeNone) {
            value = null;
          } else if (valueTrackingRef) {
            const flag = ${this.builder.reader.readInt8()};
            switch (flag) {
              case ${RefFlags.RefValueFlag}:
                ${this.valueGenerator.read(x => `value = ${x}`, "true")}
                break;
              case ${RefFlags.RefFlag}:
                value = ${this.builder.referenceResolver.getReadObject(this.builder.reader.readVarUInt32())}
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
    const innerSerializer = (innerTypeInfo: TypeInfo) => {
      if (!innerTypeInfo.isMonomorphic()) {
        return null;
      }
      return this.scope.declare(
        "map_inner_ser",
        TypeId.isNamedType(innerTypeInfo.typeId)
          ? this.builder.typeResolver.getSerializerByName(CodecBuilder.replaceBackslashAndQuote(innerTypeInfo.named!))
          : this.builder.typeResolver.getSerializerById(innerTypeInfo.typeId, innerTypeInfo.userTypeId)
      );
    };
    return accessor(`new (${anySerializer})(${this.builder.getForyName()}, ${this.typeInfo.options!.key!.typeId! !== TypeId.UNKNOWN ? innerSerializer(this.typeInfo.options!.key!) : null
      }, ${this.typeInfo.options!.value!.typeId !== TypeId.UNKNOWN ? innerSerializer(this.typeInfo.options!.value!) : null
      }).read(${refState})`);
  }

  getFixedSize(): number {
    return 7;
  }
}

CodegenRegistry.registerExternal(MapAnySerializer);
CodegenRegistry.register(TypeId.MAP, MapSerializerGenerator);
