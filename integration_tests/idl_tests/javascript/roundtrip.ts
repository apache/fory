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

const compatible = process.env["IDL_COMPATIBLE"] === "true";

// ---------------------------------------------------------------------------
// Roundtrip helper: read file, deserialize, re-serialize, write back
// ---------------------------------------------------------------------------

function fileRoundTrip(
  envVar: string,
  rootTypeId: number,
  registerFn: (fory: any, type: any) => void,
  foryOptions: { refTracking?: boolean | null; compatible?: boolean }
): void {
  const filePath = process.env[envVar];
  if (!filePath) {
    return;
  }

  console.log(`Processing ${envVar}: ${filePath}`);

  const fory = new Fory({
    compatible: foryOptions.compatible ?? false,
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
  let obj: any;
  try {
    obj = fory.deserialize(bytes, serializer);
  } catch (e: any) {
    console.error(`  DESERIALIZE ERROR for ${envVar}: ${e.message}`);
    throw e;
  }

  // Re-serialize
  const result = fory.serialize(obj, serializer);

  // Write back
  fs.writeFileSync(filePath, result);
  console.log(`  OK: roundtrip complete for ${envVar}`);
}

// ---------------------------------------------------------------------------
// Process each data file type using generated code
// ---------------------------------------------------------------------------


fileRoundTrip("DATA_FILE", 103, registerAddressbookTypes, { compatible });


fileRoundTrip("DATA_FILE_AUTO_ID", 3022445236, registerAutoIdTypes, { compatible });


fileRoundTrip("DATA_FILE_PRIMITIVES", 200, registerComplexPbTypes, { compatible });


fileRoundTrip("DATA_FILE_COLLECTION", 210, registerCollectionTypes, { compatible });

// DATA_FILE_COLLECTION_UNION: NumericCollectionUnion (IS a union)
// Union types are not yet supported in the Fory JS runtime.
if (process.env["DATA_FILE_COLLECTION_UNION"]) {
  console.log(
    "Processing DATA_FILE_COLLECTION_UNION: skipped (union type not yet supported)"
  );
}

// DATA_FILE_COLLECTION_ARRAY: NumericCollectionsArray (type ID 212)
fileRoundTrip("DATA_FILE_COLLECTION_ARRAY", 212, registerCollectionTypes, { compatible });

// DATA_FILE_COLLECTION_ARRAY_UNION: NumericCollectionArrayUnion (IS a union)
if (process.env["DATA_FILE_COLLECTION_ARRAY_UNION"]) {
  console.log(
    "Processing DATA_FILE_COLLECTION_ARRAY_UNION: skipped (union type not yet supported)"
  );
}


fileRoundTrip("DATA_FILE_OPTIONAL_TYPES", 122, registerOptionalTypesTypes, { compatible });


fileRoundTrip("DATA_FILE_TREE", 2251833438, registerTreeTypes, {
  refTracking: true,
  compatible,
});


fileRoundTrip("DATA_FILE_GRAPH", 2373163777, registerGraphTypes, {
  refTracking: true,
  compatible,
});


fileRoundTrip(
  "DATA_FILE_FLATBUFFERS_MONSTER",
  438716985,
  registerMonsterTypes,
  { compatible }
);


fileRoundTrip(
  "DATA_FILE_FLATBUFFERS_TEST2",
  372413680,
  registerComplexFbsTypes,
  { compatible }
);

console.log("TypeScript roundtrip finished.");
