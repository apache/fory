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

/**
 * Cross-language roundtrip program for JavaScript IDL tests.
 *
 * This peer is invoked by the existing Java `IdlRoundTripTest` flow. It
 * mirrors the Go roundtrip test shape: build the expected local message,
 * deserialize the Java-written payload, compare it against the local value,
 * then serialize the decoded value back to the temp file.
 */

import * as assert from "assert/strict";
import * as fs from "fs";

import Fory, { BFloat16, Decimal, Type } from "@apache-fory/core";
import { AnyHelper } from "@apache-fory/core/dist/lib/gen/any";
import {
  ConfigFlags,
  RefFlags,
  type Serializer,
} from "@apache-fory/core/dist/lib/type";

import {
  AddressBook,
  AnimalCase,
  Cat,
  Dog,
  Person,
  registerAddressbookTypes,
} from "./generated/addressbook";
import {
  Envelope,
  Status as AutoIdStatus,
  registerAutoIdTypes,
} from "./generated/auto_id";
import {
  NumericCollections,
  NumericCollectionsArray,
  NumericCollectionArrayUnion,
  NumericCollectionArrayUnionCase,
  NumericCollectionUnion,
  NumericCollectionUnionCase,
  registerCollectionTypes,
} from "./generated/collection";
import {
  Container,
  PayloadCase,
  ScalarPack,
  Status as ComplexFbsStatus,
  registerComplexFbsTypes,
} from "./generated/complex_fbs";
import { PrimitiveTypes } from "./generated/complex_pb";
import { Graph, registerGraphTypes } from "./generated/graph";
import {
  ExampleLeaf,
  ExampleLeafUnionCase,
  ExampleMessage,
  ExampleMessageUnion,
  ExampleMessageUnionCase,
  ExampleState,
  registerExampleTypes,
} from "./generated/example";
import {
  AllOptionalTypes,
  OptionalHolder,
  OptionalUnionCase,
  registerOptionalTypesTypes,
} from "./generated/optional_types";
import { Monster, Color, registerMonsterTypes } from "./generated/monster";
import { TreeNode, registerTreeTypes } from "./generated/tree";

type RegisterFn = (fory: Fory, type: typeof Type) => void;
type AssertFn<T> = (expected: T, actual: unknown) => void;

function resolveCompatibleModes(): boolean[] {
  const value = process.env.IDL_COMPATIBLE;
  if (value == null || value.trim() === "") {
    return [false, true];
  }
  const normalized = value.trim().toLowerCase();
  if (normalized === "1" || normalized === "true" || normalized === "yes") {
    return [true];
  }
  if (normalized === "0" || normalized === "false" || normalized === "no") {
    return [false];
  }
  throw new Error(`Unsupported IDL_COMPATIBLE value: ${value}`);
}

function buildFory(
  compatible: boolean,
  ref: boolean,
  registerFns: ReadonlyArray<RegisterFn>,
): Fory {
  const fory = new Fory({
    compatible,
    ref,
  });
  for (const registerFn of registerFns) {
    registerFn(fory, Type);
  }
  return fory;
}

function resolveRootSerializer(fory: Fory, bytes: Uint8Array): Serializer {
  fory.readContext.reset(bytes);
  const reader = fory.readContext.reader;
  const bitmap = reader.readUint8();
  if ((bitmap & ConfigFlags.isNullFlag) === ConfigFlags.isNullFlag) {
    throw new Error("IDL roundtrip does not support null root payloads");
  }
  if (
    (bitmap & ConfigFlags.isCrossLanguageFlag) !==
    ConfigFlags.isCrossLanguageFlag
  ) {
    throw new Error("support crosslanguage mode only");
  }
  if ((bitmap & ConfigFlags.isOutOfBandFlag) === ConfigFlags.isOutOfBandFlag) {
    throw new Error("outofband mode is not supported now");
  }
  const refFlag = fory.readContext.readRefFlag();
  if (refFlag === RefFlags.NullFlag) {
    throw new Error("IDL roundtrip does not support null root payloads");
  }
  if (refFlag === RefFlags.RefFlag) {
    throw new Error("IDL roundtrip root payload must not be a back reference");
  }
  // Generated JS IDL outputs are plain interfaces, so generic serialize(value)
  // cannot rediscover the root struct serializer. Reuse the runtime's existing
  // serializer detection from the payload, then map back to the local
  // registered serializer when available.
  const detectedSerializer = AnyHelper.detectSerializer(fory.readContext);
  const resolvedSerializer = (
    fory.typeResolver.getSerializerByTypeInfo(
      detectedSerializer.getTypeInfo(),
    ) ?? detectedSerializer
  );
  return resolvedSerializer;
}

function runFileRoundTrip<T>(
  envVar: string,
  fory: Fory,
  expected: T,
  assertFn: AssertFn<T>,
): void {
  const filePath = process.env[envVar];
  if (!filePath) {
    return;
  }
  console.log(`Processing ${envVar}: ${filePath}`);
  const payload = new Uint8Array(fs.readFileSync(filePath));
  const serializer = resolveRootSerializer(fory, payload);
  const decoded = fory.deserialize(payload, serializer);
  assertFn(expected, decoded);
  const roundTripBytes = fory.serialize(decoded, serializer);
  fs.writeFileSync(filePath, roundTripBytes);
  console.log(`  OK: roundtrip complete for ${envVar}`);
}

function normalizeAcyclic(value: unknown): unknown {
  if (value instanceof Decimal) {
    return { __decimal: value.toString() };
  }
  if (value instanceof BFloat16) {
    return value.toFloat32();
  }
  if (value instanceof Date) {
    return { __dateMs: value.getTime() };
  }
  if (value instanceof Map) {
    const entries = Array.from(value.entries()).map(
      ([key, itemValue]) =>
        [normalizeAcyclic(key), normalizeAcyclic(itemValue)] as const,
    );
    entries.sort((left, right) =>
      String(left[0]).localeCompare(String(right[0])),
    );
    return entries;
  }
  if (ArrayBuffer.isView(value)) {
    if (value instanceof DataView) {
      return Array.from(
        new Uint8Array(value.buffer, value.byteOffset, value.byteLength),
      );
    }
    return Array.from(value as unknown as ArrayLike<unknown>, (item) =>
      normalizeAcyclic(item),
    );
  }
  if (Array.isArray(value)) {
    return value.map((item) => normalizeAcyclic(item));
  }
  if (value != null && typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>);
    entries.sort(([left], [right]) => left.localeCompare(right));
    return Object.fromEntries(
      entries.map(([key, itemValue]) => [key, normalizeAcyclic(itemValue)]),
    );
  }
  return value;
}

function assertAcyclicEqual<T>(
  label: string,
  expected: T,
  actual: unknown,
): void {
  assert.deepStrictEqual(
    normalizeAcyclic(actual),
    normalizeAcyclic(expected),
    `${label} mismatch`,
  );
}

function buildDog(): Dog {
  return { name: "Rex", barkVolume: 5 };
}

function buildCat(): Cat {
  return { name: "Mimi", lives: 9 };
}

function buildPhoneNumber(
  number_: string,
  phoneType: Person.PhoneType,
): Person.PhoneNumber {
  return { number_, phoneType };
}

function buildAddressBook(): AddressBook {
  const person: Person = {
    name: "Alice",
    id: 123,
    email: "alice@example.com",
    tags: ["friend", "colleague"],
    scores: new Map([
      ["math", 100],
      ["science", 98],
    ]),
    salary: 120000.5,
    phones: [
      buildPhoneNumber("555-0100", Person.PhoneType.MOBILE),
      buildPhoneNumber("555-0111", Person.PhoneType.WORK),
    ],
    pet: {
      case: AnimalCase.CAT,
      value: buildCat(),
    },
  };
  return {
    people: [person],
    peopleByName: new Map([[person.name, person]]),
  };
}

function buildAutoIdEnvelope(): Envelope {
  const payload: Envelope.Payload = { value: 42 };
  return {
    id: "env-1",
    payload,
    detail: { case: Envelope.DetailCase.PAYLOAD, value: payload },
    status: AutoIdStatus.OK,
  };
}

function buildPrimitiveTypes(): PrimitiveTypes {
  return {
    boolValue: true,
    int8Value: 12,
    int16Value: 1234,
    int32Value: -123456,
    varintI32Value: -12345,
    int64Value: -123456789n,
    varintI64Value: -987654321n,
    taggedI64Value: 123456789n,
    uint8Value: 200,
    uint16Value: 60000,
    uint32Value: 1234567890,
    varintU32Value: 1234567890,
    uint64Value: 9876543210n,
    varintU64Value: 12345678901n,
    taggedU64Value: 2222222222n,
    float32Value: 2.5,
    float64Value: 3.5,
    contact: {
      case: PrimitiveTypes.ContactCase.PHONE,
      value: 12345,
    },
  };
}

function buildNumericCollections(): NumericCollections {
  return {
    int8Values: [1, -2, 3],
    int16Values: [100, -200, 300],
    int32Values: [1000, -2000, 3000],
    int64Values: [10000n, -20000n, 30000n],
    uint8Values: [200, 250],
    uint16Values: [50000, 60000],
    uint32Values: [2000000000, 2100000000],
    uint64Values: [9000000000n, 12000000000n],
    float32Values: [1.5, 2.5],
    float64Values: [3.5, 4.5],
  };
}

function buildNumericCollectionUnion(): NumericCollectionUnion {
  return {
    case: NumericCollectionUnionCase.INT32_VALUES,
    value: [7, 8, 9],
  };
}

function buildNumericCollectionsArray(): NumericCollectionsArray {
  return {
    int8Values: new Int8Array([1, -2, 3]),
    int16Values: new Int16Array([100, -200, 300]),
    int32Values: new Int32Array([1000, -2000, 3000]),
    int64Values: new BigInt64Array([10000n, -20000n, 30000n]),
    uint8Values: new Uint8Array([200, 250]),
    uint16Values: new Uint16Array([50000, 60000]),
    uint32Values: new Uint32Array([2000000000, 2100000000]),
    uint64Values: new BigUint64Array([9000000000n, 12000000000n]),
    float32Values: new Float32Array([1.5, 2.5]),
    float64Values: new Float64Array([3.5, 4.5]),
  };
}

function buildNumericCollectionArrayUnion(): NumericCollectionArrayUnion {
  return {
    case: NumericCollectionArrayUnionCase.UINT16_VALUES,
    value: new Uint16Array([1000, 2000, 3000]),
  };
}

function buildMonster(): Monster {
  return {
    pos: {
      x: 1.0,
      y: 2.0,
      z: 3.0,
    },
    mana: 200,
    hp: 80,
    name: "Orc",
    friendly: true,
    inventory: new Uint8Array([1, 2, 3]),
    color: Color.Blue,
  };
}

function buildContainer(): Container {
  const scalars: ScalarPack = {
    b: -8,
    ub: 200,
    s: -1234,
    us: 40000,
    i: -123456,
    ui: 123456,
    l: -123456789n,
    ul: 987654321n,
    f: 1.5,
    d: 2.5,
    ok: true,
  };
  return {
    id: 9876543210n,
    status: ComplexFbsStatus.STARTED,
    bytes: new Int8Array([1, 2, 3]),
    numbers: new Int32Array([10, 20, 30]),
    scalars,
    names: ["alpha", "beta"],
    flags: [true, false],
    payload: {
      case: PayloadCase.METRIC,
      value: { value: 42.0 },
    },
  };
}

function buildLocalDate(year: number, month: number, day: number): Date {
  return new Date(year, month - 1, day, 0, 0, 0, 0);
}

function buildExampleLeaf(): ExampleLeaf {
  return {
    label: "leaf",
    count: 7,
  };
}

function buildExampleMessage(): ExampleMessage {
  const leaf = buildExampleLeaf();
  const otherLeaf: ExampleLeaf = {
    label: "other",
    count: 8,
  };
  return {
    boolValue: true,
    int8Value: -12,
    int16Value: -1234,
    fixedI32Value: -123456,
    varintI32Value: -12345,
    fixedI64Value: -123456789n,
    varintI64Value: -987654321n,
    taggedI64Value: 123456789n,
    uint8Value: 200,
    uint16Value: 60000,
    fixedU32Value: 1234567890,
    varintU32Value: 1234567890,
    fixedU64Value: 9876543210n,
    varintU64Value: 12345678901n,
    taggedU64Value: 2222222222n,
    float16Value: 1.5,
    bfloat16Value: 2.5,
    float32Value: 3.5,
    float64Value: 4.5,
    stringValue: "example",
    bytesValue: new Uint8Array([1, 2, 3]),
    dateValue: buildLocalDate(2024, 2, 3),
    timestampValue: new Date("2024-02-03T04:05:06Z"),
    durationValue: 42000.007,
    decimalValue: Decimal.from(12345n, 2),
    enumValue: ExampleState.READY,
    messageValue: leaf,
    unionValue: {
      case: ExampleLeafUnionCase.LEAF,
      value: otherLeaf,
    },
    boolList: [true, false, true],
    int8List: [1, -2, 3],
    int16List: [100, -200, 300],
    fixedI32List: [1000, -2000, 3000],
    varintI32List: [-10, 20, -30],
    fixedI64List: [10000n, -20000n],
    varintI64List: [-40n, 50n],
    taggedI64List: [60n, 70n],
    uint8List: [200, 250],
    uint16List: [50000, 60000],
    fixedU32List: [2000000000, 2100000000],
    varintU32List: [100, 200],
    fixedU64List: [9000000000n],
    varintU64List: [12000000000n],
    taggedU64List: [13000000000n],
    float16List: [1, 2],
    bfloat16List: [1, 2],
    maybeFloat16List: [1, null, 2],
    maybeBfloat16List: [1, null, 3],
    float32List: [1.5, 2.5],
    float64List: [3.5, 4.5],
    stringList: ["alpha", "beta"],
    bytesList: [new Uint8Array([4, 5]), new Uint8Array([6, 7])],
    dateList: [buildLocalDate(2024, 1, 1), buildLocalDate(2024, 1, 2)],
    timestampList: [
      new Date("2024-01-01T00:00:00Z"),
      new Date("2024-01-02T00:00:00Z"),
    ],
    durationList: [1, 2000],
    decimalList: [Decimal.from(125n, 2), Decimal.from(250n, 2)],
    enumList: [ExampleState.UNKNOWN, ExampleState.FAILED],
    messageList: [leaf, otherLeaf],
    unionList: [
      { case: ExampleLeafUnionCase.NOTE, value: "note" },
      { case: ExampleLeafUnionCase.LEAF, value: otherLeaf },
    ],
    maybeFixedI32List: [1, null, 3],
    maybeUint64List: [10n, null, 30n],
    boolArray: [true, false],
    int8Array: new Int8Array([1, -2]),
    int16Array: new Int16Array([100, -200]),
    int32Array: new Int32Array([1000, -2000]),
    int64Array: new BigInt64Array([10000n, -20000n]),
    uint8Array: new Uint8Array([200, 250]),
    uint16Array: new Uint16Array([50000, 60000]),
    uint32Array: new Uint32Array([2000000000, 2100000000]),
    uint64Array: new BigUint64Array([9000000000n, 12000000000n]),
    float16Array: [1, 2],
    bfloat16Array: [1, 2],
    float32Array: new Float32Array([1.5, 2.5]),
    float64Array: new Float64Array([3.5, 4.5]),
    int32ArrayList: [new Int32Array([1, 2]), new Int32Array([3, 4])],
    uint8ArrayList: [new Uint8Array([201, 202]), new Uint8Array([203])],
    stringValuesByBool: new Map([[true, "bool"]]),
    stringValuesByInt8: new Map([[-1, "int8"]]),
    stringValuesByInt16: new Map([[-2, "int16"]]),
    stringValuesByFixedI32: new Map([[-3, "fixed-i32"]]),
    stringValuesByVarintI32: new Map([[4, "varint_i32"]]),
    stringValuesByFixedI64: new Map([[-5n, "fixed-i64"]]),
    stringValuesByVarintI64: new Map([[6n, "varint_i64"]]),
    stringValuesByTaggedI64: new Map([[7n, "tagged-i64"]]),
    stringValuesByUint8: new Map([[200, "uint8"]]),
    stringValuesByUint16: new Map([[60000, "uint16"]]),
    stringValuesByFixedU32: new Map([[1234567890, "fixed-u32"]]),
    stringValuesByVarintU32: new Map([[1234567891, "varint-u32"]]),
    stringValuesByFixedU64: new Map([[9876543210n, "fixed-u64"]]),
    stringValuesByVarintU64: new Map([[9876543211n, "varint-u64"]]),
    stringValuesByTaggedU64: new Map([[9876543212n, "tagged-u64"]]),
    stringValuesByString: new Map([["name", "value"]]),
    stringValuesByTimestamp: new Map([[new Date("2024-03-04T05:06:07Z"), "time"]]),
    stringValuesByDuration: new Map([[9000, "duration"]]),
    stringValuesByEnum: new Map([[ExampleState.READY, "ready"]]),
    float16ValuesByName: new Map([["f16", 1.25]]),
    maybeFloat16ValuesByName: new Map([["maybe-f16", 1.5]]),
    bfloat16ValuesByName: new Map([["bf16", 1.75]]),
    maybeBfloat16ValuesByName: new Map([["maybe-bf16", 2.25]]),
    bytesValuesByName: new Map([["bytes", new Uint8Array([8, 9])]]),
    dateValuesByName: new Map([["date", buildLocalDate(2024, 5, 6)]]),
    decimalValuesByName: new Map([["decimal", Decimal.from(9901n, 2)]]),
    messageValuesByName: new Map([["leaf", leaf]]),
    unionValuesByName: new Map([
      ["union", { case: ExampleLeafUnionCase.CODE, value: 42 }],
    ]),
    uint8ArrayValuesByName: new Map([["u8", new Uint8Array([201, 202])]]),
    float32ArrayValuesByName: new Map([
      ["f32", new Float32Array([1.25, 2.5])],
    ]),
    int32ArrayValuesByName: new Map([["i32", new Int32Array([101, 202])]]),
  };
}

function buildExampleMessageUnion(): ExampleMessageUnion {
  return {
    case: ExampleMessageUnionCase.INT32_ARRAY_LIST,
    value: [new Int32Array([11, 12]), new Int32Array([13, 14])],
  };
}

function buildOptionalHolder(): OptionalHolder {
  const allTypes: AllOptionalTypes = {
    boolValue: true,
    int8Value: 12,
    int16Value: 1234,
    int32Value: -123456,
    fixedI32Value: -123456,
    varintI32Value: -12345,
    int64Value: -123456789n,
    fixedI64Value: -123456789n,
    varintI64Value: -987654321n,
    taggedI64Value: 123456789n,
    uint8Value: 200,
    uint16Value: 60000,
    uint32Value: 1234567890,
    fixedU32Value: 1234567890,
    varintU32Value: 1234567890,
    uint64Value: 9876543210n,
    fixedU64Value: 9876543210n,
    varintU64Value: 12345678901n,
    taggedU64Value: 2222222222n,
    float32Value: 2.5,
    float64Value: 3.5,
    stringValue: "optional",
    bytesValue: new Uint8Array([1, 2, 3]),
    dateValue: buildLocalDate(2024, 1, 2),
    timestampValue: new Date("2024-01-02T03:04:05Z"),
    int32List: [1, 2, 3],
    stringList: ["alpha", "beta"],
    int64Map: new Map([
      ["alpha", 10n],
      ["beta", 20n],
    ]),
  };
  return {
    allTypes,
    choice: {
      case: OptionalUnionCase.NOTE,
      value: "optional",
    },
  };
}

function buildTree(): TreeNode {
  const childA: TreeNode = {
    id: "child-a",
    name: "child-a",
    children: [],
  };
  const childB: TreeNode = {
    id: "child-b",
    name: "child-b",
    children: [],
  };
  childA.parent = childB;
  childB.parent = childA;
  return {
    id: "root",
    name: "root",
    children: [childA, childA, childB],
  };
}

function buildGraph(): Graph {
  const nodeA = {
    id: "node-a",
    outEdges: [],
    inEdges: [],
  } as unknown as Graph["nodes"][number];
  const nodeB = {
    id: "node-b",
    outEdges: [],
    inEdges: [],
  } as unknown as Graph["nodes"][number];
  const edge = {
    id: "edge-1",
    weight: 1.5,
    from_: nodeA,
    to: nodeB,
  } as Graph["edges"][number];
  nodeA.outEdges = [edge];
  nodeA.inEdges = [edge];
  nodeB.inEdges = [edge];
  nodeB.outEdges = [];
  return {
    nodes: [nodeA, nodeB],
    edges: [edge],
  };
}

function assertAddressBookEqual(expected: AddressBook, actual: unknown): void {
  assertAcyclicEqual("addressbook", expected, actual);
}

function assertAutoIdEnvelopeEqual(expected: Envelope, actual: unknown): void {
  assertAcyclicEqual("auto_id envelope", expected, actual);
}

function assertPrimitiveTypesEqual(
  expected: PrimitiveTypes,
  actual: unknown,
): void {
  assertAcyclicEqual("primitive types", expected, actual);
}

function assertNumericCollectionsEqual(
  expected: NumericCollections,
  actual: unknown,
): void {
  assertAcyclicEqual("numeric collections", expected, actual);
}

function assertNumericCollectionUnionEqual(
  expected: NumericCollectionUnion,
  actual: unknown,
): void {
  assertAcyclicEqual("numeric collection union", expected, actual);
}

function assertNumericCollectionsArrayEqual(
  expected: NumericCollectionsArray,
  actual: unknown,
): void {
  assertAcyclicEqual("numeric collections array", expected, actual);
}

function assertNumericCollectionArrayUnionEqual(
  expected: NumericCollectionArrayUnion,
  actual: unknown,
): void {
  assertAcyclicEqual("numeric collection array union", expected, actual);
}

function assertMonsterEqual(expected: Monster, actual: unknown): void {
  assertAcyclicEqual("monster", expected, actual);
}

function assertContainerEqual(expected: Container, actual: unknown): void {
  assertAcyclicEqual("complex_fbs container", expected, actual);
}

function assertOptionalHolderEqual(
  expected: OptionalHolder,
  actual: unknown,
): void {
  assertAcyclicEqual("optional holder", expected, actual);
}

function assertExampleMessageEqual(
  expected: ExampleMessage,
  actual: unknown,
): void {
  assertAcyclicEqual("example message", expected, actual);
}

function assertExampleMessageUnionEqual(
  expected: ExampleMessageUnion,
  actual: unknown,
): void {
  assertAcyclicEqual("example message union", expected, actual);
}

function assertTreeEqual(expected: TreeNode, actualValue: unknown): void {
  assert.ok(
    actualValue != null && typeof actualValue === "object",
    "tree payload must decode to an object",
  );
  const actual = actualValue as TreeNode;
  assert.equal(actual.id, expected.id, "tree root id mismatch");
  assert.equal(actual.name, expected.name, "tree root name mismatch");
  assert.equal(
    actual.children.length,
    expected.children.length,
    "tree children size mismatch",
  );
  assert.equal(expected.children.length, 3, "expected tree fixture drift");
  assert.strictEqual(
    expected.children[0],
    expected.children[1],
    "expected tree shared child drift",
  );
  assert.notStrictEqual(
    expected.children[0],
    expected.children[2],
    "expected tree distinct child drift",
  );
  assert.equal(
    actual.children[0].id,
    expected.children[0].id,
    "tree first child id mismatch",
  );
  assert.equal(
    actual.children[0].name,
    expected.children[0].name,
    "tree first child name mismatch",
  );
  assert.equal(
    actual.children[2].id,
    expected.children[2].id,
    "tree third child id mismatch",
  );
  assert.equal(
    actual.children[2].name,
    expected.children[2].name,
    "tree third child name mismatch",
  );
  assert.strictEqual(
    actual.children[0],
    actual.children[1],
    "tree shared child mismatch",
  );
  assert.notStrictEqual(
    actual.children[0],
    actual.children[2],
    "tree distinct child mismatch",
  );
  assert.strictEqual(
    actual.children[0].parent,
    actual.children[2],
    "tree parent back-pointer mismatch",
  );
  assert.strictEqual(
    actual.children[2].parent,
    actual.children[0],
    "tree parent reverse mismatch",
  );
}

function assertGraphEqual(expected: Graph, actualValue: unknown): void {
  assert.ok(
    actualValue != null && typeof actualValue === "object",
    "graph payload must decode to an object",
  );
  const actual = actualValue as Graph;
  assert.equal(
    actual.nodes.length,
    expected.nodes.length,
    "graph node size mismatch",
  );
  assert.equal(
    actual.edges.length,
    expected.edges.length,
    "graph edge size mismatch",
  );
  assert.equal(expected.nodes.length, 2, "expected graph fixture drift");
  assert.equal(expected.edges.length, 1, "expected graph edge fixture drift");
  const actualNodeA = actual.nodes[0];
  const actualNodeB = actual.nodes[1];
  const actualEdge = actual.edges[0];
  assert.equal(
    actualNodeA.id,
    expected.nodes[0].id,
    "graph node-a id mismatch",
  );
  assert.equal(
    actualNodeB.id,
    expected.nodes[1].id,
    "graph node-b id mismatch",
  );
  assert.equal(actualEdge.id, expected.edges[0].id, "graph edge id mismatch");
  assert.equal(
    actualEdge.weight,
    expected.edges[0].weight,
    "graph edge weight mismatch",
  );
  assert.strictEqual(
    actualNodeA.outEdges[0],
    actualNodeA.inEdges[0],
    "graph shared edge mismatch",
  );
  assert.strictEqual(
    actualEdge,
    actualNodeA.outEdges[0],
    "graph edge link mismatch",
  );
  assert.strictEqual(actualEdge.from_, actualNodeA, "graph edge from mismatch");
  assert.strictEqual(actualEdge.to, actualNodeB, "graph edge to mismatch");
}

function runStandardRoundTrip(compatible: boolean): void {
  // AddressBook registration already includes the shared complex_pb types.
  const fory = buildFory(compatible, false, [
    registerAddressbookTypes,
    registerAutoIdTypes,
    registerMonsterTypes,
    registerComplexFbsTypes,
    registerCollectionTypes,
    registerOptionalTypesTypes,
    registerExampleTypes,
  ]);

  runFileRoundTrip(
    "DATA_FILE",
    fory,
    buildAddressBook(),
    assertAddressBookEqual,
  );
  runFileRoundTrip(
    "DATA_FILE_AUTO_ID",
    fory,
    buildAutoIdEnvelope(),
    assertAutoIdEnvelopeEqual,
  );
  runFileRoundTrip(
    "DATA_FILE_PRIMITIVES",
    fory,
    buildPrimitiveTypes(),
    assertPrimitiveTypesEqual,
  );
  runFileRoundTrip(
    "DATA_FILE_COLLECTION",
    fory,
    buildNumericCollections(),
    assertNumericCollectionsEqual,
  );
  runFileRoundTrip(
    "DATA_FILE_COLLECTION_UNION",
    fory,
    buildNumericCollectionUnion(),
    assertNumericCollectionUnionEqual,
  );
  runFileRoundTrip(
    "DATA_FILE_COLLECTION_ARRAY",
    fory,
    buildNumericCollectionsArray(),
    assertNumericCollectionsArrayEqual,
  );
  runFileRoundTrip(
    "DATA_FILE_COLLECTION_ARRAY_UNION",
    fory,
    buildNumericCollectionArrayUnion(),
    assertNumericCollectionArrayUnionEqual,
  );
  runFileRoundTrip(
    "DATA_FILE_OPTIONAL_TYPES",
    fory,
    buildOptionalHolder(),
    assertOptionalHolderEqual,
  );
  runFileRoundTrip(
    "DATA_FILE_EXAMPLE",
    fory,
    buildExampleMessage(),
    assertExampleMessageEqual,
  );
  runFileRoundTrip(
    "DATA_FILE_EXAMPLE_UNION",
    fory,
    buildExampleMessageUnion(),
    assertExampleMessageUnionEqual,
  );
  runFileRoundTrip(
    "DATA_FILE_FLATBUFFERS_MONSTER",
    fory,
    buildMonster(),
    assertMonsterEqual,
  );
  runFileRoundTrip(
    "DATA_FILE_FLATBUFFERS_TEST2",
    fory,
    buildContainer(),
    assertContainerEqual,
  );
}

function runRefRoundTrip(compatible: boolean): void {
  const refFory = buildFory(compatible, true, [
    registerTreeTypes,
    registerGraphTypes,
  ]);

  runFileRoundTrip("DATA_FILE_TREE", refFory, buildTree(), assertTreeEqual);
  runFileRoundTrip("DATA_FILE_GRAPH", refFory, buildGraph(), assertGraphEqual);
}

for (const compatible of resolveCompatibleModes()) {
  runStandardRoundTrip(compatible);
  runRefRoundTrip(compatible);
}

console.log("JavaScript roundtrip finished.");
