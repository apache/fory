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
import { CodegenRegistry } from "../packages/core/lib/gen/router";
import { BinaryReader } from "../packages/core/lib/reader";
import { ConfigFlags, RefFlags, TypeId } from "../packages/core/lib/type";
import { describe, expect, test } from "@jest/globals";

function firstChunkSizeOffset(bytes: Uint8Array): number {
  const reader = new BinaryReader({});
  reader.reset(bytes);
  expect(reader.readUint8()).toBe(ConfigFlags.isCrossLanguageFlag);
  expect(reader.readInt8()).toBe(RefFlags.RefValueFlag);
  expect(reader.readUint8()).toBe(TypeId.MAP);
  expect(reader.readVarUint32Small7()).toBe(1);
  reader.readUint8();
  return reader.readGetCursor();
}

function structMapHeader(fory: Fory, bytes: Uint8Array, compatible: boolean, wrapperId: number) {
  fory.readContext.reset(bytes);
  const reader = fory.readContext.reader;
  expect(reader.readUint8()).toBe(ConfigFlags.isCrossLanguageFlag);
  expect(reader.readInt8()).toBe(RefFlags.RefValueFlag);
  if (compatible) {
    expect(reader.readUint8()).toBe(TypeId.COMPATIBLE_STRUCT);
    fory.readContext.readTypeMeta();
  } else {
    expect(reader.readUint8()).toBe(TypeId.STRUCT);
    expect(reader.readVarUint32Small7()).toBe(wrapperId);
    reader.readInt32();
  }
  expect(reader.readVarUint32Small7()).toBe(1);
  const header = reader.readUint8();
  expect(reader.readUint8()).toBe(1);
  const valueDeclared = (header >> 3) & 0b100;
  return {
    header,
    nextTypeId: compatible && !valueDeclared ? reader.readUint8() : undefined,
  };
}

describe("map", () => {
  test("should map work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const input = fory.serialize(
      new Map([
        ["foo", "bar"],
        ["foo2", "bar2"],
      ]),
    );
    const result = fory.deserialize(input);
    expect(result).toEqual(
      new Map([
        ["foo", "bar"],
        ["foo2", "bar2"],
      ]),
    );
  });

  test("should map specific type work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const { serialize, deserialize } = fory.register(
      Type.struct("class.foo", {
        f1: Type.map(Type.string(), Type.int32()),
      }),
    );
    const bin = serialize({
      f1: new Map([
        ["hello", 123],
        ["world", 456],
      ]),
    });
    const result = deserialize(bin);
    expect(result).toEqual({
      f1: new Map([
        ["hello", 123],
        ["world", 456],
      ]),
    });
  });

  test("preserves shared dynamic map entries", () => {
    const fory = new Fory({ compatible: false, ref: true });
    @Type.struct(301, {
      value: Type.int32(),
    })
    class Node {
      constructor(public value = 0) {}
    }
    fory.register(Node);
    const shared = new Node(7);

    const result = fory.deserialize(fory.serialize(new Map([[shared, shared]]))) as Map<Node, Node>;
    const [[key, value]] = Array.from(result.entries());

    expect(key).toBe(value);
    expect(value).toEqual(shared);
  });

  test.each([
    ["fixed", false, 320],
    ["evolving", true, 321],
  ])("round-trips %s map sides beside null", (_, evolving, itemId) => {
    const fory = new Fory({ compatible: true, ref: true });
    const itemType = Type.struct(
      { typeId: itemId, evolving },
      {
        value: Type.int32(),
      },
    );
    fory.register(itemType);
    const serializer = fory.register(
      Type.struct(itemId + 20, {
        values: Type.map(itemType, itemType),
      }),
    );
    const input = {
      values: new Map<any, any>([
        [{ value: 1 }, null],
        [null, { value: 2 }],
      ]),
    };

    const result = serializer.deserialize(serializer.serialize(input)) as {
      values: Map<any, any>;
    };

    expect(Array.from(result.values.entries())).toEqual([
      [{ value: 1 }, null],
      [null, { value: 2 }],
    ]);
  });

  test("preserves compatible struct map framing", () => {
    const serializeMap = (
      compatible: boolean,
      evolving: boolean,
      itemId: number,
      wrapperId: number,
    ) => {
      const fory = new Fory({ compatible, ref: true });
      const itemType = Type.struct(
        { typeId: itemId, evolving },
        {
          value: Type.int32(),
        },
      );
      fory.register(itemType);
      const serializer = fory.register(
        Type.struct(wrapperId, {
          // The field placeholder must inherit the final registered serializer's evolving flag.
          values: Type.map(Type.string(), Type.struct(itemId)),
        }),
      );
      const value = { values: new Map([["key", { value: 7 }]]) };
      const bytes = serializer.serialize(value);
      expect(serializer.deserialize(bytes)).toEqual(value);
      return structMapHeader(fory, bytes, compatible, wrapperId);
    };

    const compatible = serializeMap(true, true, 340, 341);
    const fixed = serializeMap(true, false, 342, 343);
    const native = serializeMap(false, true, 344, 345);
    expect((compatible.header >> 3) & 0b100).toBe(0);
    expect(compatible.nextTypeId).toBe(TypeId.COMPATIBLE_STRUCT);
    expect((fixed.header >> 3) & 0b100).toBe(0b100);
    expect(fixed.nextTypeId).toBeUndefined();
    expect((native.header >> 3) & 0b100).toBe(0b100);
  });

  test("rejects invalid runtime chunks before type detection", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const MapAnySerializer = CodegenRegistry.getExternal().MapAnySerializer;
    const serializer = new MapAnySerializer(fory.writeContext, fory.readContext, null, null);

    for (const chunkSize of [0, 2]) {
      fory.readContext.reset(new Uint8Array([1, 0, chunkSize]));
      expect(() => serializer.read(false)).toThrow();
    }
  });

  test("rejects invalid generated chunks and reuses the root", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(Type.map(Type.string(), Type.int32()));
    const value = new Map([["key", 1]]);
    const valid = serializer.serialize(value);
    const chunkSizeOffset = firstChunkSizeOffset(valid);

    for (const chunkSize of [0, 2]) {
      const malformed = new Uint8Array(valid.subarray(0, chunkSizeOffset + 1));
      malformed[chunkSizeOffset] = chunkSize;

      expect(() => serializer.deserialize(malformed)).toThrow();
      expect(fory.readContext.depth).toBe(0);
      expect(serializer.deserialize(valid)).toEqual(value);
    }
  });

  test("should large declared map work", () => {
    // The generated map write must reserve writer capacity for its entries.
    // Without it, unchecked DataView writes past the buffer end threw a
    // RangeError once the map body outgrew the initial buffer.
    const fory = new Fory({ compatible: false });
    const { serialize, deserialize } = fory.register(
      Type.struct(
        { namespace: "example", typeName: "BigMap" },
        { m: Type.map(Type.int32({ encoding: "fixed" }), Type.int32({ encoding: "fixed" })) },
      ),
    );
    const m = new Map<number, number>();
    for (let i = 0; i < 30000; i++) {
      m.set(i, i + 1);
    }
    expect(deserialize(serialize({ m })).m.get(29999)).toBe(30000);
  });
});
