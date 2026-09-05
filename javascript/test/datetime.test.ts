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

import Fory, { Type } from "../packages/core/index";
import { describe, expect, test } from "@jest/globals";
import { TypeId } from "../packages/core/lib/type";
import { BinaryWriter } from "../packages/core/lib/writer";

function temporalBytes(typeId: number, writeBody: (writer: BinaryWriter) => void) {
  const writer = new BinaryWriter({});
  writer.writeUint8(1);
  writer.writeInt8(-1);
  writer.writeUint8(typeId);
  writeBody(writer);
  return writer.dump();
}

describe("datetime", () => {
  test("should date work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const now = new Date();
    const input = fory.serialize(now);
    const result: Date | null = fory.deserialize(input);
    expect(result?.getFullYear()).toEqual(now.getFullYear());
    expect(result?.getDate()).toEqual(now.getDate());
  });
  test("should datetime work", () => {
    const typeinfo = Type.struct("example.foo", {
      a: Type.timestamp(),
      b: Type.duration(),
    });
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(typeinfo).serializer;
    const d = new Date("2021/10/20 09:13");
    const input = fory.serialize({ a: d, b: d }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: d, b: d.getTime() });
  });
  test("should use signed varint64 for date payloads", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(Type.date()).serializer;
    const value = new Date(1969, 11, 31);

    const encoded = fory.serialize(value, serializer);
    expect(Array.from(encoded)).toEqual([0x01, 0xff, TypeId.DATE, 0x01]);
    expect(fory.deserialize(encoded, serializer)).toEqual(value);
  });

  test("rejects temporal values outside JavaScript ranges", () => {
    const timestampFory = new Fory({ compatible: false, ref: true });
    const timestamp = timestampFory.register(Type.timestamp()).serializer;
    const timestampBytes = temporalBytes(TypeId.TIMESTAMP, (writer) => {
      writer.writeInt64((1n << 63n) - 1n);
      writer.writeInt32(0);
    });
    expect(() => timestampFory.deserialize(timestampBytes, timestamp)).toThrow();

    const durationFory = new Fory({ compatible: false, ref: true });
    const duration = durationFory.register(Type.duration()).serializer;
    const durationBytes = temporalBytes(TypeId.DURATION, (writer) => {
      writer.writeVarInt64((1n << 63n) - 1n);
      writer.writeInt32(0);
    });
    expect(() => durationFory.deserialize(durationBytes, duration)).toThrow();

    const normalizedDurationFory = new Fory({ compatible: false, ref: true });
    const normalizedDuration = normalizedDurationFory.register(Type.duration()).serializer;
    const normalizedDurationBytes = temporalBytes(TypeId.DURATION, (writer) => {
      writer.writeVarInt64(-9_007_199_254_741n);
      writer.writeInt32(9_000_000);
    });
    expect(normalizedDurationFory.deserialize(normalizedDurationBytes, normalizedDuration)).toBe(
      -9_007_199_254_740_991,
    );

    const dateFory = new Fory({ compatible: false, ref: true });
    const date = dateFory.register(Type.date()).serializer;
    const dateBytes = temporalBytes(TypeId.DATE, (writer) => {
      writer.writeVarInt64((1n << 63n) - 1n);
    });
    expect(() => dateFory.deserialize(dateBytes, date)).toThrow();
  });
});
