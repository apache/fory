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

const MAX_DATE_MILLIS = 8_640_000_000_000_000;
const MAX_DATE_SECONDS = 8_640_000_000_000n;
const MAX_DATE_DAYS = 100_000_000n;
const NANOS_PER_SECOND = 1_000_000_000n;
const NANOS_PER_MILLISECOND = 1_000_000n;
const MAX_DURATION_NANOS = 9_007_199_254_740_991_000_000n;

export function timestampFromWire(seconds: bigint, nanos: number): Date {
  // The wire carries the full int64 range, while JavaScript Date accepts only
  // +/-100,000,000 days. Check the BigInt before converting it to Number so an
  // unrepresentable timestamp cannot silently round into a different value.
  if (seconds < -MAX_DATE_SECONDS || seconds > MAX_DATE_SECONDS) {
    throw new Error("timestamp is outside the JavaScript Date range");
  }
  const millis = Number(seconds) * 1000 + Math.floor(nanos / 1_000_000);
  if (!Number.isSafeInteger(millis) || Math.abs(millis) > MAX_DATE_MILLIS) {
    throw new Error("timestamp is outside the JavaScript Date range");
  }
  return new Date(millis);
}

export function durationFromWire(seconds: bigint, nanos: number): number {
  const totalNanos = seconds * NANOS_PER_SECOND + BigInt(nanos);
  if (totalNanos < -MAX_DURATION_NANOS || totalNanos > MAX_DURATION_NANOS) {
    throw new Error("duration is outside the JavaScript number range");
  }
  const wholeMillis = totalNanos / NANOS_PER_MILLISECOND;
  const subMillisecondNanos = totalNanos % NANOS_PER_MILLISECOND;
  return Number(wholeMillis) + Number(subMillisecondNanos) / 1_000_000;
}

export function dateFromWire(days: bigint, epoch: number): Date {
  if (days < -MAX_DATE_DAYS || days > MAX_DATE_DAYS) {
    throw new Error("date is outside the JavaScript Date range");
  }
  const millis = epoch + Number(days) * 86_400_000;
  if (!Number.isSafeInteger(millis) || Math.abs(millis) > MAX_DATE_MILLIS) {
    throw new Error("date is outside the JavaScript Date range");
  }
  return new Date(millis);
}

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
      ${this.builder.writer.writeInt32(`${nanosVar}`)}
      `;
  }

  read(accessor: (expr: string) => string): string {
    const seconds = this.builder.reader.readInt64();
    const nanos = this.builder.reader.readUint32();
    return accessor(`external.timestampFromWire(${seconds}, ${nanos})`);
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
      const ${nanosVar} = Math.round((${msVar} - ${secondsVar} * 1000) * 1000000);
      ${this.builder.writer.writeVarInt64(`${secondsVar}`)}
      ${this.builder.writer.writeInt32(`${nanosVar}`)}
      `;
  }

  read(accessor: (expr: string) => string): string {
    const seconds = this.builder.reader.readVarInt64();
    const nanos = this.builder.reader.readInt32();
    return accessor(`external.durationFromWire(${seconds}, ${nanos})`);
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
        ${this.builder.writer.writeVarInt64(`Math.floor((${accessor}.getTime() - ${epoch}) / 1000 / (24 * 60 * 60))`)}
      } else {
        ${this.builder.writer.writeVarInt64(`Math.floor((${accessor} - ${epoch}) / 1000 / (24 * 60 * 60))`)}
      }
    `;
  }

  read(accessor: (expr: string) => string): string {
    const epoch = this.scope.declareByName("epoch", `new Date("1970/01/01 00:00").getTime()`);
    return accessor(`external.dateFromWire(${this.builder.reader.readVarInt64()}, ${epoch})`);
  }

  getFixedSize(): number {
    return 11;
  }
}

CodegenRegistry.register(TypeId.DURATION, DurationSerializerGenerator);
CodegenRegistry.register(TypeId.TIMESTAMP, TimestampSerializerGenerator);
CodegenRegistry.register(TypeId.DATE, DateSerializerGenerator);
CodegenRegistry.registerExternal(timestampFromWire);
CodegenRegistry.registerExternal(durationFromWire);
CodegenRegistry.registerExternal(dateFromWire);
