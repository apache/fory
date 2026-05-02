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

function asGeneratedNumberArray<T extends ArrayBufferView>(value: T): number[] {
  return value as unknown as number[];
}

function asGeneratedBigIntArray<T extends BigInt64Array | BigUint64Array>(
  value: T,
): (bigint | number)[] {
  return value as unknown as (bigint | number)[];
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
    varint32Value: -12345,
    int64Value: -123456789n,
    varint64Value: -987654321n,
    taggedInt64Value: 123456789n,
    uint8Value: 200,
    uint16Value: 60000,
    uint32Value: 1234567890,
    varUint32Value: 1234567890,
    uint64Value: 9876543210n,
    varUint64Value: 12345678901n,
    taggedUint64Value: 2222222222n,
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
    int8Values: asGeneratedNumberArray(new Int8Array([1, -2, 3])),
    int16Values: asGeneratedNumberArray(new Int16Array([100, -200, 300])),
    int32Values: asGeneratedNumberArray(new Int32Array([1000, -2000, 3000])),
    int64Values: asGeneratedBigIntArray(
      new BigInt64Array([10000n, -20000n, 30000n]),
    ),
    uint8Values: asGeneratedNumberArray(new Uint8Array([200, 250])),
    uint16Values: asGeneratedNumberArray(new Uint16Array([50000, 60000])),
    uint32Values: asGeneratedNumberArray(
      new Uint32Array([2000000000, 2100000000]),
    ),
    uint64Values: asGeneratedBigIntArray(
      new BigUint64Array([9000000000n, 12000000000n]),
    ),
    float32Values: asGeneratedNumberArray(new Float32Array([1.5, 2.5])),
    float64Values: asGeneratedNumberArray(new Float64Array([3.5, 4.5])),
  };
}

function buildNumericCollectionsArray(): NumericCollectionsArray {
  return {
    int8Values: asGeneratedNumberArray(new Int8Array([1, -2, 3])),
    int16Values: asGeneratedNumberArray(new Int16Array([100, -200, 300])),
    int32Values: asGeneratedNumberArray(new Int32Array([1000, -2000, 3000])),
    int64Values: asGeneratedBigIntArray(
      new BigInt64Array([10000n, -20000n, 30000n]),
    ),
    uint8Values: asGeneratedNumberArray(new Uint8Array([200, 250])),
    uint16Values: asGeneratedNumberArray(new Uint16Array([50000, 60000])),
    uint32Values: asGeneratedNumberArray(
      new Uint32Array([2000000000, 2100000000]),
    ),
    uint64Values: asGeneratedBigIntArray(
      new BigUint64Array([9000000000n, 12000000000n]),
    ),
    float32Values: asGeneratedNumberArray(new Float32Array([1.5, 2.5])),
    float64Values: asGeneratedNumberArray(new Float64Array([3.5, 4.5])),
  };
}

function buildNumericCollectionUnion(): NumericCollectionUnion {
  return {
    case: NumericCollectionUnionCase.INT64_VALUES,
    value: asGeneratedBigIntArray(new BigInt64Array([10000n, -20000n, 30000n])),
  };
}

function buildNumericCollectionArrayUnion(): NumericCollectionArrayUnion {
  return {
    case: NumericCollectionArrayUnionCase.FLOAT64_VALUES,
    value: asGeneratedNumberArray(new Float64Array([3.5, 4.5, 5.5])),
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
  return {
    boolValue: true,
    int8Value: 12,
    int16Value: 1234,
    fixedInt32Value: -123456,
    varint32Value: -12345,
    fixedInt64Value: -123456789n,
    varint64Value: -987654321n,
    taggedInt64Value: 123456789n,
    uint8Value: 200,
    uint16Value: 60000,
    fixedUint32Value: 1234567890,
    varUint32Value: 1234567890,
    fixedUint64Value: 9876543210n,
    varUint64Value: 12345678901n,
    taggedUint64Value: 2222222222n,
    float16Value: 1.5,
    bfloat16Value: 2.5,
    float32Value: 3.5,
    float64Value: 4.5,
    stringValue: "example",
    bytesValue: new Uint8Array([1, 2, 3]),
    dateValue: buildLocalDate(2024, 1, 2),
    timestampValue: new Date("2024-01-02T03:04:05Z"),
    durationValue: 3000,
    decimalValue: Decimal.from(1n << 70n, 2),
    enumValue: ExampleState.READY,
    messageValue: leaf,
    unionValue: {
      case: ExampleLeafUnionCase.LEAF,
      value: leaf,
    },
    boolList: [true, false],
    int8List: [],
    int16List: [],
    fixedInt32List: [],
    varint32List: [1, -2, 3],
    fixedInt64List: [],
    varint64List: [],
    taggedInt64List: [],
    uint8List: [],
    uint16List: [],
    fixedUint32List: [],
    varUint32List: [],
    fixedUint64List: [],
    varUint64List: [],
    taggedUint64List: [],
    float16List: [],
    bfloat16List: [],
    maybeFloat16List: [],
    maybeBfloat16List: [],
    float32List: [],
    float64List: [],
    stringList: ["alpha", "beta"],
    bytesList: [new Uint8Array([4, 5])],
    dateList: [],
    timestampList: [],
    durationList: [],
    decimalList: [],
    enumList: [],
    messageList: [leaf],
    unionList: [{ case: ExampleLeafUnionCase.NOTE, value: "note" }],
    maybeFixedInt32List: [],
    maybeUint64List: [],
    boolArray: [true, false],
    int8Array: new Int8Array(),
    int16Array: new Int16Array(),
    int32Array: new Int32Array([10, -20, 30]),
    int64Array: new BigInt64Array(),
    uint8Array: new Uint8Array([6, 7, 8]),
    uint16Array: new Uint16Array(),
    uint32Array: new Uint32Array(),
    uint64Array: new BigUint64Array(),
    float16Array: [],
    bfloat16Array: [],
    float32Array: new Float32Array([1.25, 2.5]),
    float64Array: new Float64Array(),
    int32ArrayList: [new Int32Array([1, 2]), new Int32Array([-3])],
    uint8ArrayList: [new Uint8Array([1, 2]), new Uint8Array([3])],
    stringValuesByBool: new Map(),
    stringValuesByInt8: new Map(),
    stringValuesByInt16: new Map(),
    stringValuesByFixedInt32: new Map(),
    stringValuesByVarint32: new Map(),
    stringValuesByFixedInt64: new Map(),
    stringValuesByVarint64: new Map(),
    stringValuesByTaggedInt64: new Map(),
    stringValuesByUint8: new Map(),
    stringValuesByUint16: new Map(),
    stringValuesByFixedUint32: new Map(),
    stringValuesByVarUint32: new Map(),
    stringValuesByFixedUint64: new Map(),
    stringValuesByVarUint64: new Map(),
    stringValuesByTaggedUint64: new Map(),
    stringValuesByString: new Map(),
    stringValuesByTimestamp: new Map(),
    stringValuesByDuration: new Map(),
    stringValuesByEnum: new Map(),
    float16ValuesByName: new Map(),
    maybeFloat16ValuesByName: new Map(),
    bfloat16ValuesByName: new Map(),
    maybeBfloat16ValuesByName: new Map(),
    bytesValuesByName: new Map(),
    dateValuesByName: new Map(),
    decimalValuesByName: new Map(),
    messageValuesByName: new Map(),
    unionValuesByName: new Map(),
    uint8ArrayValuesByName: new Map([["bytes", new Uint8Array([9, 10])]]),
    float32ArrayValuesByName: new Map([
      ["floats", new Float32Array([3.5, 4.5])],
    ]),
    int32ArrayValuesByName: new Map([["ints", new Int32Array([11, -12])]]),
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
    fixedInt32Value: -123456,
    varint32Value: -12345,
    int64Value: -123456789n,
    fixedInt64Value: -123456789n,
    varint64Value: -987654321n,
    taggedInt64Value: 123456789n,
    uint8Value: 200,
    uint16Value: 60000,
    uint32Value: 1234567890,
    fixedUint32Value: 1234567890,
    varUint32Value: 1234567890,
    uint64Value: 9876543210n,
    fixedUint64Value: 9876543210n,
    varUint64Value: 12345678901n,
    taggedUint64Value: 2222222222n,
    float32Value: 2.5,
    float64Value: 3.5,
    stringValue: "optional",
    bytesValue: new Uint8Array([1, 2, 3]),
    dateValue: buildLocalDate(2024, 1, 2),
    timestampValue: new Date("2024-01-02T03:04:05Z"),
    int32List: asGeneratedNumberArray(new Int32Array([1, 2, 3])),
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
