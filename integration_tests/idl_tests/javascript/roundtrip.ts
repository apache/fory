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
import Fory, { Type } from "@apache-fory/core";

import { registerAddressbookTypes } from "./generated/addressbook";
import { registerAutoIdTypes } from "./generated/auto_id";
import { registerComplexPbTypes } from "./generated/complex_pb";
import { registerCollectionTypes } from "./generated/collection";
import { registerOptionalTypesTypes } from "./generated/optional_types";
import { registerTreeTypes } from "./generated/tree";
import { registerGraphTypes } from "./generated/graph";
import { registerMonsterTypes } from "./generated/monster";
import { registerComplexFbsTypes } from "./generated/complex_fbs";

// ---------------------------------------------------------------------------
// Capability: compatible mode
// ---------------------------------------------------------------------------
// The Fory JS runtime does not yet support compatible mode (class metadata /
// versioning is incomplete).  Set this to `true` once the runtime adds
// compatible-mode support; all gated skips below will then run normally.
const SUPPORTS_COMPATIBLE_MODE = false;

const compatible = process.env["IDL_COMPATIBLE"] === "true";

if (compatible && !SUPPORTS_COMPATIBLE_MODE) {
  // Enumerate every data-file env var so the test output makes the exact
  // coverage gap visible (mirrors how union-type skips are reported below).
  const dataFileVars = [
    "DATA_FILE",
    "DATA_FILE_AUTO_ID",
    "DATA_FILE_PRIMITIVES",
    "DATA_FILE_COLLECTION",
    "DATA_FILE_COLLECTION_UNION",
    "DATA_FILE_COLLECTION_ARRAY",
    "DATA_FILE_COLLECTION_ARRAY_UNION",
    "DATA_FILE_OPTIONAL_TYPES",
    "DATA_FILE_TREE",
    "DATA_FILE_GRAPH",
    "DATA_FILE_FLATBUFFERS_MONSTER",
    "DATA_FILE_FLATBUFFERS_TEST2",
  ];
  const present = dataFileVars.filter((v) => process.env[v]);
  console.log(
    "TypeScript roundtrip: compatible mode is NOT SUPPORTED " +
    "(SUPPORTS_COMPATIBLE_MODE = false)."
  );
  for (const v of present) {
    console.log(`  SKIP [compatible]: ${v}`);
  }
  console.log(
    `  0/${present.length} compatible-mode roundtrips executed. ` +
    "Files left unchanged so Java reads back its own bytes."
  );
  process.exit(0);
}

// ---------------------------------------------------------------------------
// Roundtrip helper: read file, deserialize, re-serialize, write back
// ---------------------------------------------------------------------------

function fileRoundTrip(
  envVar: string,
  rootTypeId: number,
  registerFn: (fory: any, type: any) => void,
  foryOptions: { refTracking?: boolean | null }
): void {
  const filePath = process.env[envVar];
  if (!filePath) {
    return;
  }

  console.log(`Processing ${envVar}: ${filePath}`);

  const fory = new Fory({
    compatible: false,
    refTracking: foryOptions.refTracking ?? null,
  });

  registerFn(fory, Type);

  const serializer = fory.typeResolver.getSerializerByTypeInfo(
    Type.struct(rootTypeId)
  );

  // Read binary data
  const data = fs.readFileSync(filePath);
  const bytes = new Uint8Array(data);

  // Deserialize
  const obj = fory.deserialize(bytes, serializer);

  // Re-serialize
  const result = fory.serialize(obj, serializer);

  // Write back
  fs.writeFileSync(filePath, result);
  console.log(`  OK: roundtrip complete for ${envVar}`);
}

// ---------------------------------------------------------------------------
// Process each data file type using generated code
// ---------------------------------------------------------------------------


fileRoundTrip("DATA_FILE", 103, registerAddressbookTypes, {});


fileRoundTrip("DATA_FILE_AUTO_ID", 3022445236, registerAutoIdTypes, {});


fileRoundTrip("DATA_FILE_PRIMITIVES", 200, registerComplexPbTypes, {});


fileRoundTrip("DATA_FILE_COLLECTION", 210, registerCollectionTypes, {});

// DATA_FILE_COLLECTION_UNION: NumericCollectionUnion (IS a union)
// Union types are not yet supported in the Fory JS runtime.
if (process.env["DATA_FILE_COLLECTION_UNION"]) {
  console.log(
    "Processing DATA_FILE_COLLECTION_UNION: skipped (union type not yet supported)"
  );
}

// DATA_FILE_COLLECTION_ARRAY: NumericCollectionsArray (type ID 212)
fileRoundTrip("DATA_FILE_COLLECTION_ARRAY", 212, registerCollectionTypes, {});

// DATA_FILE_COLLECTION_ARRAY_UNION: NumericCollectionArrayUnion (IS a union)
if (process.env["DATA_FILE_COLLECTION_ARRAY_UNION"]) {
  console.log(
    "Processing DATA_FILE_COLLECTION_ARRAY_UNION: skipped (union type not yet supported)"
  );
}


fileRoundTrip("DATA_FILE_OPTIONAL_TYPES", 122, registerOptionalTypesTypes, {});


fileRoundTrip("DATA_FILE_TREE", 2251833438, registerTreeTypes, {
  refTracking: true,
});


fileRoundTrip("DATA_FILE_GRAPH", 2373163777, registerGraphTypes, {
  refTracking: true,
});


fileRoundTrip(
  "DATA_FILE_FLATBUFFERS_MONSTER",
  438716985,
  registerMonsterTypes,
  {}
);


fileRoundTrip(
  "DATA_FILE_FLATBUFFERS_TEST2",
  372413680,
  registerComplexFbsTypes,
  {}
);

console.log("TypeScript roundtrip finished.");
