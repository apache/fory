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

import Fory, { BFloat16, Decimal, Type } from "@apache-fory/core";
import {
  AddressBook,
  Animal,
  AnimalCase,
  Cat,
  Dog,
  Person,
  registerAddressbookTypes,
} from "../generated/addressbook";
import {
  Envelope,
  Status as AutoIdStatus,
  Wrapper,
  WrapperCase,
  registerAutoIdTypes,
} from "../generated/auto_id";
import {
  NumericCollections,
  NumericCollectionsArray,
  NumericCollectionArrayUnion,
  NumericCollectionArrayUnionCase,
  NumericCollectionUnion,
  NumericCollectionUnionCase,
  registerCollectionTypes,
} from "../generated/collection";
import {
  Container,
  PayloadCase,
  ScalarPack,
  Status as ComplexFbsStatus,
  registerComplexFbsTypes,
} from "../generated/complex_fbs";
import {
  PrimitiveTypes,
  registerComplexPbTypes,
} from "../generated/complex_pb";
import { Graph, registerGraphTypes } from "../generated/graph";
import {
  ExampleLeaf,
  ExampleLeafUnionCase,
  ExampleMessage,
  ExampleMessageUnion,
  ExampleMessageUnionCase,
  ExampleState,
  registerExampleTypes,
} from "../generated/example";
import {
  AllOptionalTypes,
  OptionalHolder,
  OptionalUnionCase,
  registerOptionalTypesTypes,
} from "../generated/optional_types";
import { Color, Monster, registerMonsterTypes } from "../generated/monster";
import { TreeNode, registerTreeTypes } from "../generated/tree";

type RegisterFn = (fory: Fory, type: typeof Type) => void;
type RegisteredTypeInfo =
  | ReturnType<typeof Type.struct>
  | ReturnType<typeof Type.union>;

const MODES = [
  { title: "schema-consistent", compatible: false },
  { title: "compatible", compatible: true },
] as const;

function buildFory(
  compatible: boolean,
  ref: boolean,
  registerFns: ReadonlyArray<RegisterFn>,
): Fory {
  const fory = new Fory({ compatible, ref });
  for (const registerFn of registerFns) {
    registerFn(fory, Type);
  }
  return fory;
}

function getSerializer(fory: Fory, typeInfo: RegisteredTypeInfo) {
  const serializer = fory.typeResolver.getSerializerByTypeInfo(typeInfo);
  if (!serializer) {
    throw new Error(`Missing serializer for type id ${typeInfo.typeId}`);
  }
  return serializer;
}

function roundTripValue<T>(
  fory: Fory,
  typeInfo: RegisteredTypeInfo,
  value: T,
): unknown {
  const serializer = getSerializer(fory, typeInfo);
  const bytes = fory.serialize(value, serializer);
  return fory.deserialize(bytes, serializer);
}

function roundTripStruct<T>(fory: Fory, typeId: number, value: T): unknown {
  return roundTripValue(fory, Type.struct(typeId), value);
}

function roundTripUnion<T>(fory: Fory, typeId: number, value: T): unknown {
  return roundTripValue(fory, Type.union(typeId), value);
}

function normalize(value: unknown): unknown {
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
      ([key, itemValue]) => [normalize(key), normalize(itemValue)] as const,
    );
    entries.sort((left, right) =>
      JSON.stringify(left[0]).localeCompare(JSON.stringify(right[0])),
    );
    return entries;
  }
  if (value instanceof Set) {
    return Array.from(value.values())
      .map((item) => normalize(item))
      .sort();
  }
  if (ArrayBuffer.isView(value)) {
    if (value instanceof DataView) {
      return Array.from(
        new Uint8Array(value.buffer, value.byteOffset, value.byteLength),
      );
    }
    return Array.from(value as unknown as ArrayLike<unknown>, (item) =>
      normalize(item),
    );
  }
  if (Array.isArray(value)) {
    return value.map((item) => normalize(item));
  }
  if (value != null && typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>);
    entries.sort(([left], [right]) => left.localeCompare(right));
    return Object.fromEntries(
      entries.map(([key, itemValue]) => [key, normalize(itemValue)]),
    );
  }
  return value;
}

function expectAcyclicEqual(expected: unknown, actual: unknown): void {
  expect(normalize(actual)).toEqual(normalize(expected));
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

function buildPerson(): Person {
  return {
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
}

function buildAddressBook(): AddressBook {
  const person = buildPerson();
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

function buildNumericCollectionUnion(): NumericCollectionUnion {
  return {
    case: NumericCollectionUnionCase.INT64_VALUES,
    value: [10000n, -20000n, 30000n],
  };
}

function buildNumericCollectionArrayUnion(): NumericCollectionArrayUnion {
  return {
    case: NumericCollectionArrayUnionCase.FLOAT64_VALUES,
    value: new Float64Array([3.5, 4.5, 5.5]),
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

function expectTreeEqual(expected: TreeNode, actualValue: unknown): void {
  const actual = actualValue as TreeNode;
  expect(actual.id).toBe(expected.id);
  expect(actual.name).toBe(expected.name);
  expect(actual.children).toHaveLength(expected.children.length);
  expect(expected.children).toHaveLength(3);
  expect(expected.children[0]).toBe(expected.children[1]);
  expect(expected.children[0]).not.toBe(expected.children[2]);
  expect(actual.children[0].id).toBe(expected.children[0].id);
  expect(actual.children[0].name).toBe(expected.children[0].name);
  expect(actual.children[2].id).toBe(expected.children[2].id);
  expect(actual.children[2].name).toBe(expected.children[2].name);
  expect(actual.children[0]).toBe(actual.children[1]);
  expect(actual.children[0]).not.toBe(actual.children[2]);
  expect(actual.children[0].parent).toBe(actual.children[2]);
  expect(actual.children[2].parent).toBe(actual.children[0]);
}

function expectGraphEqual(expected: Graph, actualValue: unknown): void {
  const actual = actualValue as Graph;
  expect(actual.nodes).toHaveLength(expected.nodes.length);
  expect(actual.edges).toHaveLength(expected.edges.length);
  expect(actual.nodes[0].id).toBe(expected.nodes[0].id);
  expect(actual.nodes[1].id).toBe(expected.nodes[1].id);
  expect(actual.edges[0].id).toBe(expected.edges[0].id);
  expect(actual.edges[0].weight).toBe(expected.edges[0].weight);
  expect(actual.nodes[0].outEdges[0]).toBe(actual.nodes[0].inEdges[0]);
  expect(actual.edges[0]).toBe(actual.nodes[0].outEdges[0]);
  expect(actual.edges[0].from_).toBe(actual.nodes[0]);
  expect(actual.edges[0].to).toBe(actual.nodes[1]);
}

describe.each(MODES)(
  "generated IDL local roundtrip ($title)",
  ({ compatible }) => {
    test("round-trips addressbook messages and root animal unions", () => {
      const fory = buildFory(compatible, false, [registerAddressbookTypes]);

      expectAcyclicEqual(
        buildAddressBook(),
        roundTripStruct(fory, 103, buildAddressBook()),
      );

      const dogAnimal: Animal = {
        case: AnimalCase.DOG,
        value: buildDog(),
      };
      const catAnimal: Animal = {
        case: AnimalCase.CAT,
        value: buildCat(),
      };
      expectAcyclicEqual(dogAnimal, roundTripUnion(fory, 106, dogAnimal));
      expectAcyclicEqual(catAnimal, roundTripUnion(fory, 106, catAnimal));
    });

    test("round-trips auto_id messages and root wrapper unions", () => {
      const fory = buildFory(compatible, false, [registerAutoIdTypes]);

      const envelope = buildAutoIdEnvelope();
      const wrapperEnvelope: Wrapper = {
        case: WrapperCase.ENVELOPE,
        value: envelope,
      };
      const wrapperRaw: Wrapper = {
        case: WrapperCase.RAW,
        value: "raw-payload",
      };

      expectAcyclicEqual(envelope, roundTripStruct(fory, 3022445236, envelope));
      expectAcyclicEqual(
        wrapperEnvelope,
        roundTripUnion(fory, 1471345060, wrapperEnvelope),
      );
      expectAcyclicEqual(
        wrapperRaw,
        roundTripUnion(fory, 1471345060, wrapperRaw),
      );
    });

    test("round-trips primitive and collection generated messages and unions", () => {
      const fory = buildFory(compatible, false, [
        registerComplexPbTypes,
        registerCollectionTypes,
      ]);

      expectAcyclicEqual(
        buildPrimitiveTypes(),
        roundTripStruct(fory, 200, buildPrimitiveTypes()),
      );
      expectAcyclicEqual(
        buildNumericCollections(),
        roundTripStruct(fory, 210, buildNumericCollections()),
      );
      expectAcyclicEqual(
        buildNumericCollectionsArray(),
        roundTripStruct(fory, 212, buildNumericCollectionsArray()),
      );
      expectAcyclicEqual(
        buildNumericCollectionUnion(),
        roundTripUnion(fory, 211, buildNumericCollectionUnion()),
      );
      expectAcyclicEqual(
        buildNumericCollectionArrayUnion(),
        roundTripUnion(fory, 213, buildNumericCollectionArrayUnion()),
      );
    });

    test("round-trips flatbuffers and optional generated messages", () => {
      const flatbufferFory = buildFory(compatible, false, [
        registerMonsterTypes,
        registerComplexFbsTypes,
        registerOptionalTypesTypes,
      ]);

      expectAcyclicEqual(
        buildMonster(),
        roundTripStruct(flatbufferFory, 438716985, buildMonster()),
      );
      expectAcyclicEqual(
        buildContainer(),
        roundTripStruct(flatbufferFory, 372413680, buildContainer()),
      );
      expectAcyclicEqual(
        buildOptionalHolder(),
        roundTripStruct(flatbufferFory, 122, buildOptionalHolder()),
      );
    });

    test("round-trips example messages and an array-valued root union", () => {
      const fory = buildFory(compatible, false, [registerExampleTypes]);

      expectAcyclicEqual(
        buildExampleMessage(),
        roundTripValue(
          fory,
          Type.struct({ typeId: 1500, evolving: true }),
          buildExampleMessage(),
        ),
      );
      expectAcyclicEqual(
        buildExampleMessageUnion(),
        roundTripUnion(fory, 1501, buildExampleMessageUnion()),
      );
    });
  },
);

describe.each(MODES)(
  "generated IDL local ref roundtrip ($title)",
  ({ compatible }) => {
    test("round-trips tree and preserves shared-node topology", () => {
      const fory = buildFory(compatible, true, [registerTreeTypes]);
      const tree = buildTree();
      expectTreeEqual(tree, roundTripStruct(fory, 2251833438, tree));
    });

    test("round-trips graph and preserves edge/node references", () => {
      const fory = buildFory(compatible, true, [registerGraphTypes]);
      const graph = buildGraph();
      expectGraphEqual(graph, roundTripStruct(fory, 2373163777, graph));
    });
  },
);
