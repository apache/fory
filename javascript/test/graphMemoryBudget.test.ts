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

const KNOWN_SLACK_BYTES = 64 * 1024;
const OBJECT_BYTES = 1;
const REFERENCE_BYTES = 4;

const objectBytes = (fields: number) => OBJECT_BYTES + fields * REFERENCE_BYTES;
const listBytes = (count: number) => OBJECT_BYTES + count * REFERENCE_BYTES;
const mapBytes = (count: number) => OBJECT_BYTES + count * 2 * REFERENCE_BYTES;

function serializeAny(value: unknown) {
  return new Fory({ compatible: false, ref: true }).serialize(value);
}

function deserializeAny(bytes: Uint8Array, maxGraphMemoryBytes: number) {
  return new Fory({
    compatible: false,
    ref: true,
    maxGraphMemoryBytes,
  }).deserialize(bytes);
}

describe("graph memory budget", () => {
  test("uses known length auto budget", () => {
    const inputBytes = 17;
    const fory = new Fory({ compatible: false });
    const budget = inputBytes * 8 + KNOWN_SLACK_BYTES;

    fory.readContext.reset(new Uint8Array(inputBytes));
    expect(() => fory.readContext.reserveGraphMemory(budget)).not.toThrow();
    expect(() => fory.readContext.reserveGraphMemory(1)).toThrow(
      /maxGraphMemoryBytes/,
    );
  });

  test("validates explicit config", () => {
    expect(() => new Fory({ maxGraphMemoryBytes: 0 })).toThrow(
      /maxGraphMemoryBytes/,
    );
    expect(() => new Fory({ maxGraphMemoryBytes: -2 })).toThrow(
      /maxGraphMemoryBytes/,
    );

    const fory = new Fory({ maxGraphMemoryBytes: 24 });
    fory.readContext.reset(new Uint8Array(1));
    expect(() => fory.readContext.reserveGraphMemory(0)).not.toThrow();
    expect(() => fory.readContext.reserveGraphMemory(24)).not.toThrow();
    expect(() => fory.readContext.reserveGraphMemory(1)).toThrow(
      /maxGraphMemoryBytes/,
    );
  });

  test("uses parent storage for nested empty containers", () => {
    const typeInfo = Type.struct("budget.nested.empty", {
      values: Type.list(Type.list(Type.int32({ encoding: "fixed" }))).setId(1),
    });
    const writer = new Fory({ compatible: false, ref: true });
    const bytes = writer.register(typeInfo).serialize({ values: [[]] });
    const passingReader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes: objectBytes(1) + listBytes(1) + listBytes(0),
    }).register(typeInfo);
    const failingReader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes: objectBytes(1) + listBytes(1) + listBytes(0) - 1,
    }).register(typeInfo);

    expect(() => failingReader.deserialize(bytes)).toThrow(
      /maxGraphMemoryBytes/,
    );
    expect(passingReader.deserialize(bytes)).toEqual({ values: [[]] });
  });

  test("reserves sibling containers cumulatively", () => {
    const typeInfo = Type.struct("budget.sibling.empty", {
      values: Type.list(Type.list(Type.int32({ encoding: "fixed" }))).setId(1),
    });
    const writer = new Fory({ compatible: false, ref: true });
    const bytes = writer.register(typeInfo).serialize({
      values: [[], [], []],
    });
    const passingReader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes: objectBytes(1) + listBytes(3) + 3 * listBytes(0),
    }).register(typeInfo);
    const failingReader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes: objectBytes(1) + listBytes(3) + 3 * listBytes(0) - 1,
    }).register(typeInfo);

    expect(() => failingReader.deserialize(bytes)).toThrow(
      /maxGraphMemoryBytes/,
    );
    expect(passingReader.deserialize(bytes)).toEqual({
      values: [[], [], []],
    });
  });

  test("reserves empty generated object owner", () => {
    const childType = Type.struct("budget.empty.object", {});
    const typeInfo = Type.struct("budget.empty.parent", {
      first: Type.struct("budget.empty.object").setId(1),
      second: Type.struct("budget.empty.object").setId(2),
    });
    const writer = new Fory({ compatible: false, ref: true });
    writer.register(childType);
    const bytes = writer.register(typeInfo).serialize({
      first: {},
      second: {},
    });
    const required = objectBytes(2) + 2 * OBJECT_BYTES;
    const passingReader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes: required,
    });
    passingReader.register(childType);
    passingReader.register(typeInfo);
    const failingReader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes: required - 1,
    });
    failingReader.register(childType);
    failingReader.register(typeInfo);

    expect(() => failingReader.deserialize(bytes)).toThrow(
      /maxGraphMemoryBytes/,
    );
    expect(passingReader.deserialize(bytes)).toEqual({
      first: {},
      second: {},
    });
  });

  test("preserves constructor for empty generated object owner", () => {
    class EmptyChild {}
    Type.struct("budget.empty.ctor.child", {})(EmptyChild);
    class EmptyParent {
      child = new EmptyChild();
    }
    Type.struct("budget.empty.ctor.parent", {
      child: Type.struct("budget.empty.ctor.child").setId(1),
    })(EmptyParent);

    const writer = new Fory({ compatible: false, ref: true });
    writer.register(EmptyChild);
    const bytes = writer.register(EmptyParent).serialize(new EmptyParent());
    const required = objectBytes(1) + OBJECT_BYTES;
    const passingReader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes: required,
    });
    passingReader.register(EmptyChild);
    passingReader.register(EmptyParent);
    const failingReader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes: required - 1,
    });
    failingReader.register(EmptyChild);
    failingReader.register(EmptyParent);

    expect(() => failingReader.deserialize(bytes)).toThrow(
      /maxGraphMemoryBytes/,
    );
    const decoded = passingReader.deserialize(bytes);
    expect(decoded).toBeInstanceOf(EmptyParent);
    expect(decoded.child).toBeInstanceOf(EmptyChild);
  });

  test("reserves map entries", () => {
    const bytes = serializeAny(new Map([[1, 2]]));

    expect(() => deserializeAny(bytes, mapBytes(1) - 1)).toThrow(
      /maxGraphMemoryBytes/,
    );
    expect(deserializeAny(bytes, mapBytes(1))).toEqual(new Map([[1, 2]]));
  });

  test("reserves generated containers", () => {
    const typeInfo = Type.struct("budget.generated", {
      list: Type.list(Type.int32({ encoding: "fixed" })).setId(1),
      set: Type.set(Type.string()).setId(2),
      map: Type.map(Type.string(), Type.int32({ encoding: "fixed" })).setId(3),
    });
    const writer = new Fory({ compatible: false, ref: true });
    const bytes = writer.register(typeInfo).serialize({
      list: [1],
      set: new Set(["a"]),
      map: new Map([["k", 1]]),
    });
    const passingReader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes:
        objectBytes(3) + listBytes(1) + listBytes(1) + mapBytes(1),
    }).register(typeInfo);
    const failingReader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes:
        objectBytes(3) + listBytes(1) + listBytes(1) + mapBytes(1) - 1,
    }).register(typeInfo);

    expect(() => failingReader.deserialize(bytes)).toThrow(
      /maxGraphMemoryBytes/,
    );
    expect(passingReader.deserialize(bytes)).toEqual({
      list: [1],
      set: new Set(["a"]),
      map: new Map([["k", 1]]),
    });
  });

  test("reserves compatible typed arrays", () => {
    const writerType = Type.struct(9010, {
      values: Type.list(Type.int32({ encoding: "fixed" })).setId(1),
    });
    const readerType = Type.struct(9010, {
      values: Type.int32Array().setId(1),
    });
    const writer = new Fory({ compatible: true });
    const bytes = writer.register(writerType).serialize({ values: [1, 2, 3] });
    const passingReader = new Fory({
      compatible: true,
      maxGraphMemoryBytes: objectBytes(1) + OBJECT_BYTES + 12,
    }).register(readerType);
    const failingReader = new Fory({
      compatible: true,
      maxGraphMemoryBytes: objectBytes(1) + OBJECT_BYTES + 12 - 1,
    }).register(readerType);

    expect(() => failingReader.deserialize(bytes)).toThrow(
      /maxGraphMemoryBytes/,
    );
    expect(Array.from(passingReader.deserialize(bytes).values)).toEqual([
      1, 2, 3,
    ]);
  });

  test("skips scalar dense owners", () => {
    const typeInfo = Type.struct("budget.skipped", {
      text: Type.string().setId(1),
      binary: Type.binary().setId(2),
      values: Type.int32Array().setId(3),
    });
    const writer = new Fory({ compatible: false, ref: true });
    const bytes = writer.register(typeInfo).serialize({
      text: "hello",
      binary: new Uint8Array([1, 2, 3]),
      values: new Int32Array([1, 2, 3]),
    });
    const reader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes: objectBytes(3),
    }).register(typeInfo);

    expect(reader.deserialize(bytes)).toEqual({
      text: "hello",
      binary: new Uint8Array([1, 2, 3]),
      values: new Int32Array([1, 2, 3]),
    });
  });

  test("keeps byte checks", () => {
    const typeInfo = Type.struct("budget.bytecheck", {
      values: Type.int32Array().setId(1),
    });
    const writer = new Fory({ compatible: false, ref: true });
    const bytes = writer.register(typeInfo).serialize({
      values: new Int32Array([1, 2, 3]),
    });
    const reader = new Fory({
      compatible: false,
      ref: true,
      maxGraphMemoryBytes: 1024 * 1024,
    }).register(typeInfo);

    expect(() =>
      reader.deserialize(bytes.slice(0, bytes.length - 1)),
    ).toThrow();
  });
});
