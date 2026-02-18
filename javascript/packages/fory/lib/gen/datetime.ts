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
import { TypeId } from "../type";
import { Scope } from "./scope";

class TimestampSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
  }

  write(accessor: string): string {
    const msVar = this.scope.uniqueName("ts_ms");
    const secondsVar = this.scope.uniqueName("ts_sec");
    const nanosVar = this.scope.uniqueName("ts_nanos");
    return `
      const ${msVar} = (${accessor} instanceof Date) ? ${accessor}.getTime() : ${accessor};
      const ${secondsVar} = Math.floor(${msVar} / 1000);
      const ${nanosVar} = (${msVar} - ${secondsVar} * 1000) * 1000000;
      ${this.builder.writer.writeInt64(`${secondsVar}`)}
      ${this.builder.writer.writeUint32(`${nanosVar}`)}
      `;
  }

  read(accessor: (expr: string) => string): string {
    const seconds = this.builder.reader.readInt64();
    const nanos = this.builder.reader.readUint32();
    return accessor(`new Date(Number(${seconds}) * 1000 + Math.floor(${nanos} / 1000000))`);
  }

  getFixedSize(): number {
    return 12;
  }
}

class DurationSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
  }

  write(accessor: string): string {
    const msVar = this.scope.uniqueName("ts_ms");
    const secondsVar = this.scope.uniqueName("ts_sec");
    const nanosVar = this.scope.uniqueName("ts_nanos");
    return `
      const ${msVar} = ${accessor};
      const ${secondsVar} = Math.floor(${msVar} / 1000);
      const ${nanosVar} = (${msVar} - ${secondsVar} * 1000) * 1000000;
      ${this.builder.writer.writeInt64(`${secondsVar}`)}
      ${this.builder.writer.writeUint32(`${nanosVar}`)}
      `;
  }

  read(accessor: (expr: string) => string): string {
    const seconds = this.builder.reader.readInt64();
    const nanos = this.builder.reader.readUint32();
    return accessor(`Number(${seconds}) * 1000 + Math.floor(${nanos} / 1000000)`);
  }

  getFixedSize(): number {
    return 7;
  }
}

class DateSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
  }

  write(accessor: string): string {
    const epoch = this.scope.declareByName("epoch", `new Date("1970/01/01 00:00").getTime()`);
    return `
      if (${accessor} instanceof Date) {
        ${this.builder.writer.writeInt32(`Math.floor((${accessor}.getTime() - ${epoch}) / 1000 / (24 * 60 * 60))`)}
      } else {
        ${this.builder.writer.writeInt32(`Math.floor((${accessor} - ${epoch}) / 1000 / (24 * 60 * 60))`)}
      }
    `;
  }

  read(accessor: (expr: string) => string): string {
    const epoch = this.scope.declareByName("epoch", `new Date("1970/01/01 00:00").getTime()`);
    return accessor(`new Date(${epoch} + (${this.builder.reader.readInt32()} * (24 * 60 * 60) * 1000))`);
  }

  getFixedSize(): number {
    return 7;
  }
}

CodegenRegistry.register(TypeId.DURATION, DurationSerializerGenerator);
CodegenRegistry.register(TypeId.TIMESTAMP, TimestampSerializerGenerator);
CodegenRegistry.register(TypeId.DATE, DateSerializerGenerator);
