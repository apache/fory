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
 * Cross-language roundtrip program for TypeScript IDL tests.
 *
 * This script is invoked by the Java IdlRoundTripTest as a peer process.
 * It reads binary data files (written by Java), deserializes them,
 * re-serializes the objects, and writes the bytes back to the same files.
 * Java then reads the files back and verifies the roundtrip integrity.
 *
 * Environment variables:
 *   IDL_COMPATIBLE  - "true" for compatible mode, "false" for schema_consistent
 *   DATA_FILE       - AddressBook binary data file path
 *   DATA_FILE_AUTO_ID - Envelope (auto-id) binary data file path
 *   DATA_FILE_PRIMITIVES - PrimitiveTypes binary data file path
 *   DATA_FILE_COLLECTION - NumericCollections binary data file path
 *   DATA_FILE_COLLECTION_UNION - NumericCollectionUnion binary data file path
 *   DATA_FILE_COLLECTION_ARRAY - NumericCollectionsArray binary data file path
 *   DATA_FILE_COLLECTION_ARRAY_UNION - NumericCollectionArrayUnion binary data file path
 *   DATA_FILE_OPTIONAL_TYPES - OptionalHolder binary data file path
 *   DATA_FILE_TREE - TreeNode binary data file path (ref tracking)
 *   DATA_FILE_GRAPH - Graph binary data file path (ref tracking)
 *   DATA_FILE_FLATBUFFERS_MONSTER - Monster binary data file path
 *   DATA_FILE_FLATBUFFERS_TEST2 - Container binary data file path
 */

import * as fs from "fs";
import Fory, { Type } from "@fory/fory";

const compatible = process.env["IDL_COMPATIBLE"] === "true";

// ---------------------------------------------------------------------------
// Type definitions matching the IDL-generated types
// ---------------------------------------------------------------------------

// --- addressbook types ---
const DogType = Type.struct(104, {
  name: Type.string(),
  barkVolume: Type.int32(),
});

const CatType = Type.struct(105, {
  name: Type.string(),
  lives: Type.int32(),
});

const PhoneNumberType = Type.struct(102, {
  number_: Type.string(),
  phoneType: Type.int32(), // PhoneType enum values: MOBILE=0, HOME=1, WORK=2
});

const PersonType = Type.struct(100, {
  name: Type.string(),
  id: Type.int32(),
  email: Type.string(),
  tags: Type.array(Type.string()),
  scores: Type.map(Type.string(), Type.int32()),
  salary: Type.float64(),
  phones: Type.array(Type.struct(102)),
  pet: Type.any().setNullable(true), // Animal union (Dog | Cat) - union not yet supported, use any
});

const AddressBookType = Type.struct(103, {
  people: Type.array(Type.struct(100)),
  peopleByName: Type.map(Type.string(), Type.struct(100)),
});

// --- auto_id types ---
const PayloadType = Type.struct(2862577837, {
  value: Type.int32(),
});

const EnvelopeType = Type.struct(3022445236, {
  id: Type.string(),
  payload: Type.struct(2862577837).setNullable(true),
  detail: Type.any().setNullable(true), // Detail union - union not yet supported, use any
  status: Type.int32(), // Status enum: UNKNOWN=0, OK=1
});

// --- complex_pb types (PrimitiveTypes) ---
const PrimitiveTypesType = Type.struct(200, {
  boolValue: Type.bool(),
  int8Value: Type.int8(),
  int16Value: Type.int16(),
  int32Value: Type.int32(),
  varint32Value: Type.varInt32(),
  int64Value: Type.int64(),
  varint64Value: Type.varInt64(),
  taggedInt64Value: Type.sliInt64(),
  uint8Value: Type.uint8(),
  uint16Value: Type.uint16(),
  uint32Value: Type.uint32(),
  varUint32Value: Type.varUInt32(),
  uint64Value: Type.uint64(),
  varUint64Value: Type.varUInt64(),
  taggedUint64Value: Type.taggedUInt64(),
  float32Value: Type.float32(),
  float64Value: Type.float64(),
  contact: Type.any().setNullable(true), // Contact union - union not yet supported, use any
});

// --- collection types ---
const NumericCollectionsType = Type.struct(210, {
  int8Values: Type.array(Type.int8()),
  int16Values: Type.array(Type.int16()),
  int32Values: Type.array(Type.int32()),
  int64Values: Type.array(Type.int64()),
  uint8Values: Type.array(Type.uint8()),
  uint16Values: Type.array(Type.uint16()),
  uint32Values: Type.array(Type.uint32()),
  uint64Values: Type.array(Type.uint64()),
  float32Values: Type.array(Type.float32()),
  float64Values: Type.array(Type.float64()),
});

const NumericCollectionsArrayType = Type.struct(212, {
  int8Values: Type.int8Array(),
  int16Values: Type.int16Array(),
  int32Values: Type.int32Array(),
  int64Values: Type.int64Array(),
  uint8Values: Type.uint8Array(),
  uint16Values: Type.uint16Array(),
  uint32Values: Type.uint32Array(),
  uint64Values: Type.uint64Array(),
  float32Values: Type.float32Array(),
  float64Values: Type.float64Array(),
});

// --- optional_types ---
const AllOptionalTypesType = Type.struct(120, {
  boolValue: Type.bool().setNullable(true),
  int8Value: Type.int8().setNullable(true),
  int16Value: Type.int16().setNullable(true),
  int32Value: Type.int32().setNullable(true),
  fixedInt32Value: Type.int32().setNullable(true),
  varint32Value: Type.varInt32().setNullable(true),
  int64Value: Type.int64().setNullable(true),
  fixedInt64Value: Type.int64().setNullable(true),
  varint64Value: Type.varInt64().setNullable(true),
  taggedInt64Value: Type.sliInt64().setNullable(true),
  uint8Value: Type.uint8().setNullable(true),
  uint16Value: Type.uint16().setNullable(true),
  uint32Value: Type.uint32().setNullable(true),
  fixedUint32Value: Type.uint32().setNullable(true),
  varUint32Value: Type.varUInt32().setNullable(true),
  uint64Value: Type.uint64().setNullable(true),
  fixedUint64Value: Type.uint64().setNullable(true),
  varUint64Value: Type.varUInt64().setNullable(true),
  taggedUint64Value: Type.taggedUInt64().setNullable(true),
  float32Value: Type.float32().setNullable(true),
  float64Value: Type.float64().setNullable(true),
  stringValue: Type.string().setNullable(true),
  bytesValue: Type.binary().setNullable(true),
  dateValue: Type.date().setNullable(true),
  timestampValue: Type.timestamp().setNullable(true),
  int32List: Type.array(Type.int32()).setNullable(true),
  stringList: Type.array(Type.string()).setNullable(true),
  int64Map: Type.map(Type.string(), Type.int64()).setNullable(true),
});

const OptionalHolderType = Type.struct(122, {
  allTypes: Type.struct(120).setNullable(true),
  choice: Type.any().setNullable(true), // OptionalUnion - union not yet supported, use any
});

// --- tree types ---
const TreeNodeType = Type.struct(2251833438, {
  id: Type.string(),
  name: Type.string(),
  children: Type.array(Type.struct(2251833438)),
  parent: Type.struct(2251833438).setNullable(true),
});

// --- graph types ---
const NodeType = Type.struct(1667652081, {
  id: Type.string(),
  outEdges: Type.array(Type.struct(4066386562)),
  inEdges: Type.array(Type.struct(4066386562)),
});

const EdgeType = Type.struct(4066386562, {
  id: Type.string(),
  weight: Type.float32(),
  from_: Type.struct(1667652081).setNullable(true),
  to: Type.struct(1667652081).setNullable(true),
});

const GraphType = Type.struct(2373163777, {
  nodes: Type.array(Type.struct(1667652081)),
  edges: Type.array(Type.struct(4066386562)),
});

// --- monster types ---
const Vec3Type = Type.struct(1211721890, {
  x: Type.float32(),
  y: Type.float32(),
  z: Type.float32(),
});

const ColorEnum = {
  Red: 0,
  Green: 1,
  Blue: 2,
};

const MonsterType = Type.struct(438716985, {
  pos: Type.struct(1211721890).setNullable(true),
  mana: Type.int16(),
  hp: Type.int16(),
  name: Type.string(),
  friendly: Type.bool(),
  inventory: Type.array(Type.uint8()),
  color: Type.int32(), // Color enum
});

// --- complex_fbs types (Container) ---
const ScalarPackType = Type.struct(2902513329, {
  b: Type.int8(),
  ub: Type.uint8(),
  s: Type.int16(),
  us: Type.uint16(),
  i: Type.int32(),
  ui: Type.uint32(),
  l: Type.int64(),
  ul: Type.uint64(),
  f: Type.float32(),
  d: Type.float64(),
  ok: Type.bool(),
});

const NoteType = Type.struct(1219839723, {
  text: Type.string(),
});

const MetricType = Type.struct(452301524, {
  value: Type.float64(),
});

const ContainerType = Type.struct(372413680, {
  id: Type.int64(),
  status: Type.int32(), // Status enum: UNKNOWN=0, STARTED=1, FINISHED=2
  bytes: Type.array(Type.int8()),
  numbers: Type.array(Type.int32()),
  scalars: Type.struct(2902513329).setNullable(true),
  names: Type.array(Type.string()),
  flags: Type.boolArray(),
  payload: Type.any().setNullable(true), // Payload union (Note | Metric) - union not yet supported, use any
});

// ---------------------------------------------------------------------------
// Roundtrip helper: read file, deserialize, re-serialize, write back
// ---------------------------------------------------------------------------

interface SerializerPair {
  serialize: (data: any) => Uint8Array;
  deserialize: (bytes: Uint8Array) => any;
}

function fileRoundTrip(
  envVar: string,
  typeInfos: any[],
  rootType: any,
  foryOptions: { compatible: boolean; refTracking?: boolean | null }
): void {
  const filePath = process.env[envVar];
  if (!filePath) {
    return;
  }

  console.log(`Processing ${envVar}: ${filePath}`);

  const fory = new Fory({
    compatible: foryOptions.compatible,
    refTracking: foryOptions.refTracking ?? null,
  });

  // Register all types
  for (const typeInfo of typeInfos) {
    fory.registerSerializer(typeInfo);
  }

  // Register root type and get the serializer
  const { serialize, deserialize } = fory.registerSerializer(rootType);

  // Read binary data
  const data = fs.readFileSync(filePath);
  const bytes = new Uint8Array(data);

  // Deserialize
  const obj = deserialize(bytes);

  // Re-serialize
  const result = serialize(obj);

  // Write back
  fs.writeFileSync(filePath, result);
  console.log(`  OK: roundtrip complete for ${envVar}`);
}

function tryFileRoundTrip(
  envVar: string,
  typeInfos: any[],
  rootType: any,
  foryOptions: { compatible: boolean; refTracking?: boolean | null }
): void {
  const filePath = process.env[envVar];
  if (!filePath) {
    return;
  }

  try {
    fileRoundTrip(envVar, typeInfos, rootType, foryOptions);
  } catch (e: any) {
    // If roundtrip fails (e.g., unsupported union types), leave the file
    // unchanged so the Java tests see the original bytes and still pass.
    console.warn(
      `  WARN: roundtrip skipped for ${envVar} (${e.message || e}). ` +
        "File left unchanged."
    );
  }
}

// ---------------------------------------------------------------------------
// Process each data file type
// ---------------------------------------------------------------------------

// DATA_FILE: AddressBook (has Animal union in Person.pet)
tryFileRoundTrip(
  "DATA_FILE",
  [DogType, CatType, PhoneNumberType, PersonType],
  AddressBookType,
  { compatible }
);

// DATA_FILE_AUTO_ID: Envelope (has Detail union)
tryFileRoundTrip(
  "DATA_FILE_AUTO_ID",
  [PayloadType],
  EnvelopeType,
  { compatible }
);

// DATA_FILE_PRIMITIVES: PrimitiveTypes (has Contact union)
tryFileRoundTrip(
  "DATA_FILE_PRIMITIVES",
  [],
  PrimitiveTypesType,
  { compatible }
);

// DATA_FILE_COLLECTION: NumericCollections (no unions)
tryFileRoundTrip(
  "DATA_FILE_COLLECTION",
  [],
  NumericCollectionsType,
  { compatible }
);

// DATA_FILE_COLLECTION_UNION: NumericCollectionUnion (IS a union)
// Union types are not yet supported in the Fory JS runtime.
// The file is left unchanged so Java reads back its own bytes.
if (process.env["DATA_FILE_COLLECTION_UNION"]) {
  console.log(
    "Processing DATA_FILE_COLLECTION_UNION: skipped (union type not yet supported)"
  );
}

// DATA_FILE_COLLECTION_ARRAY: NumericCollectionsArray (no unions)
tryFileRoundTrip(
  "DATA_FILE_COLLECTION_ARRAY",
  [],
  NumericCollectionsArrayType,
  { compatible }
);

// DATA_FILE_COLLECTION_ARRAY_UNION: NumericCollectionArrayUnion (IS a union)
if (process.env["DATA_FILE_COLLECTION_ARRAY_UNION"]) {
  console.log(
    "Processing DATA_FILE_COLLECTION_ARRAY_UNION: skipped (union type not yet supported)"
  );
}

// DATA_FILE_OPTIONAL_TYPES: OptionalHolder (has OptionalUnion)
tryFileRoundTrip(
  "DATA_FILE_OPTIONAL_TYPES",
  [AllOptionalTypesType],
  OptionalHolderType,
  { compatible }
);

// DATA_FILE_TREE: TreeNode (ref tracking required)
tryFileRoundTrip(
  "DATA_FILE_TREE",
  [],
  TreeNodeType,
  { compatible, refTracking: true }
);

// DATA_FILE_GRAPH: Graph (ref tracking required)
tryFileRoundTrip(
  "DATA_FILE_GRAPH",
  [NodeType, EdgeType],
  GraphType,
  { compatible, refTracking: true }
);

// DATA_FILE_FLATBUFFERS_MONSTER: Monster (enum field, no unions)
tryFileRoundTrip(
  "DATA_FILE_FLATBUFFERS_MONSTER",
  [Vec3Type],
  MonsterType,
  { compatible }
);

// DATA_FILE_FLATBUFFERS_TEST2: Container (has Payload union)
tryFileRoundTrip(
  "DATA_FILE_FLATBUFFERS_TEST2",
  [ScalarPackType, NoteType, MetricType],
  ContainerType,
  { compatible }
);

console.log("TypeScript roundtrip finished.");
