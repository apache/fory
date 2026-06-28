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

"use strict";

const assert = require("node:assert/strict");
const runTest =
  typeof globalThis.expect === "function" && typeof globalThis.test === "function"
    ? globalThis.test
    : require("node:test").test;
const { ReadContext } = require("../dist/lib/context");
const { AnyHelper } = require("../dist/lib/gen/any");
const { FieldInfo, TypeMeta } = require("../dist/lib/meta/TypeMeta");
const { TypeId } = require("../dist/lib/type");
const { Type } = require("../dist/lib/typeInfo");

function context(typeResolver = {}, config = {}) {
  const fullConfig = {
    compatible: true,
    maxTypeFields: 512,
    maxTypeMetaBytes: 4096,
    maxAverageSchemaVersionsPerType: 3,
    maxSchemaVersionsPerType: 1,
    useSliceString: false,
    ...config,
  };
  const resolver = {
    config: fullConfig,
    getSerializerById() {
      return undefined;
    },
    getSerializerByName() {
      return undefined;
    },
    ...typeResolver,
  };
  return new ReadContext(resolver, fullConfig);
}

function remoteStruct(
  name,
  fieldName,
  fieldType = Type.int32({ encoding: "fixed" }),
  typeId = TypeId.NAMED_STRUCT,
  userTypeId = -1,
) {
  return new TypeMeta([new FieldInfo(
    fieldName,
    fieldType.typeId,
    fieldType.userTypeId,
    fieldType.trackingRef === true,
    fieldType.nullable === true,
    fieldType.options,
  )], {
    namespace: "example",
    typeId,
    typeName: name,
    userTypeId,
  });
}

function anyStruct(fieldName, fieldType = Type.int32({ encoding: "fixed" })) {
  return remoteStruct("", fieldName, fieldType, TypeId.COMPATIBLE_STRUCT, 901);
}

function remoteNamedNonStruct(name, typeId) {
  return new TypeMeta([], {
    namespace: "example",
    typeId,
    typeName: name,
    userTypeId: -1,
  });
}

function readTypeMeta(readContext, typeMeta) {
  const encoded = typeMeta.toBytes();
  const bytes = new Uint8Array(encoded.length + 1);
  bytes[0] = 0;
  bytes.set(encoded, 1);
  readContext.reset(bytes);
  return readContext.readTypeMeta();
}

function readNamedTypeMeta(readContext, typeId, namespace, typeName, typeMeta) {
  const encoded = typeMeta.toBytes();
  const bytes = new Uint8Array(encoded.length + 1);
  bytes[0] = 0;
  bytes.set(encoded, 1);
  readContext.reset(bytes);
  return readContext.readNamedTypeMeta(typeId, namespace, typeName);
}

function headerParts(typeMeta) {
  const encoded = typeMeta.toBytes();
  const view = new DataView(encoded.buffer, encoded.byteOffset, encoded.byteLength);
  const header = view.getBigUint64(0, true);
  return {
    low: Number(header & 0xffffffffn),
    high: Number(header >> 32n),
  };
}

function readCompatibleStructSerializer(readContext, expectedHash, original, typeMeta) {
  const encoded = typeMeta.toBytes();
  const bytes = new Uint8Array(encoded.length + 1);
  bytes[0] = 0;
  bytes.set(encoded, 1);
  readContext.reset(bytes);
  return readContext.readCompatibleStructSerializer(expectedHash, original);
}

function detectAnySerializer(readContext, typeMeta) {
  const encoded = typeMeta.toBytes();
  const bytes = new Uint8Array(encoded.length + 2);
  bytes[0] = TypeId.COMPATIBLE_STRUCT;
  bytes[1] = 0;
  bytes.set(encoded, 2);
  readContext.reset(bytes);
  return AnyHelper.detectSerializer(readContext);
}

function localSerializer(typeInfo) {
  const typeMeta = TypeMeta.fromTypeInfo(typeInfo);
  return {
    getHash() {
      return typeMeta.getHash();
    },
    getTypeInfo() {
      return typeInfo;
    },
    getTypeMetaBytes() {
      return typeMeta.toBytes();
    },
  };
}

runTest("remote schema limit rejects extra versions", () => {
  const readContext = context();
  readTypeMeta(readContext, remoteStruct("Shared", "first"));
  assert.throws(
    () => readTypeMeta(readContext, remoteStruct("Shared", "second")),
    /maxSchemaVersionsPerType/,
  );
});

runTest("remote non-struct TypeMeta uses schema limit", () => {
  const readContext = context({
    getSerializerByName(name) {
      return name === "example$SharedEnum" ? {} : undefined;
    },
  });
  readTypeMeta(readContext, remoteNamedNonStruct("SharedEnum", TypeId.NAMED_ENUM));
  assert.throws(
    () => readTypeMeta(readContext, remoteNamedNonStruct("SharedEnum", TypeId.NAMED_EXT)),
    /maxSchemaVersionsPerType/,
  );
});

runTest("failed non-struct TypeMeta does not consume schema limit", () => {
  let registered = false;
  const readContext = context({
    getSerializerByName(name) {
      return registered && name === "example$SharedEnum" ? {} : undefined;
    },
  });

  assert.throws(
    () => readTypeMeta(readContext, remoteNamedNonStruct("SharedEnum", TypeId.NAMED_ENUM)),
    /can't find serializer/,
  );

  registered = true;
  assert.doesNotThrow(
    () => readTypeMeta(readContext, remoteNamedNonStruct("SharedEnum", TypeId.NAMED_EXT)),
  );
});

runTest("exact local non-struct TypeMeta bypasses schema limit", () => {
  const enumInfo = Type.enum({ namespace: "example", typeName: "SharedEnum" }, { A: 0 });
  const enumMeta = TypeMeta.fromTypeInfo(enumInfo);
  const localSerializer = {
    getTypeInfo() {
      return enumInfo;
    },
    getTypeMetaBytes() {
      return enumMeta.toBytes();
    },
  };
  const readContext = context({
    computeTypeId(typeInfo) {
      return typeInfo.typeId;
    },
    getSerializerByName(name) {
      return name === "example$SharedEnum" ? localSerializer : undefined;
    },
  });

  readNamedTypeMeta(readContext, TypeId.NAMED_ENUM, "example", "SharedEnum", enumMeta);
  assert.doesNotThrow(() => readNamedTypeMeta(
    readContext,
    TypeId.NAMED_EXT,
    "example",
    "SharedEnum",
    remoteNamedNonStruct("SharedEnum", TypeId.NAMED_EXT),
  ));

  const genericReadContext = context({
    computeTypeId(typeInfo) {
      return typeInfo.typeId;
    },
    getSerializerByName(name) {
      return name === "example$SharedEnum" ? localSerializer : undefined;
    },
  });
  readTypeMeta(genericReadContext, enumMeta);
  assert.doesNotThrow(() => readTypeMeta(
    genericReadContext,
    remoteNamedNonStruct("SharedEnum", TypeId.NAMED_EXT),
  ));
});

runTest("named enum TypeMeta validates declared owner before caching", () => {
  const colorInfo = Type.enum({ namespace: "example", typeName: "Color" }, { Red: 0 });
  const otherInfo = Type.enum({ namespace: "example", typeName: "Other" }, { Blue: 0 });
  const colorMeta = TypeMeta.fromTypeInfo(colorInfo);
  const otherMeta = TypeMeta.fromTypeInfo(otherInfo);
  const readContext = context({
    getSerializerByName(name) {
      if (name === "example$Color") {
        return localSerializer(colorInfo);
      }
      if (name === "example$Other") {
        return localSerializer(otherInfo);
      }
      return undefined;
    },
  });

  assert.throws(
    () => readNamedTypeMeta(
      readContext,
      TypeId.NAMED_ENUM,
      "example",
      "Color",
      otherMeta,
    ),
    /TypeMeta mismatch/,
  );

  const wrongHeader = headerParts(otherMeta);
  assert.equal(
    readContext.typeMetaCache.get(wrongHeader.high)?.get(wrongHeader.low),
    undefined,
  );
  assert.doesNotThrow(
    () => readNamedTypeMeta(
      readContext,
      TypeId.NAMED_ENUM,
      "example",
      "Color",
      colorMeta,
    ),
  );
});

runTest("TypeMeta field limit rejects large struct metadata", () => {
  const readContext = context({}, { maxTypeFields: 1 });
  const fieldType = Type.int32({ encoding: "fixed" });
  const typeMeta = new TypeMeta([
    new FieldInfo("first", fieldType.typeId, fieldType.userTypeId, false, false, fieldType.options),
    new FieldInfo("second", fieldType.typeId, fieldType.userTypeId, false, false, fieldType.options),
  ], {
    namespace: "example",
    typeId: TypeId.NAMED_STRUCT,
    typeName: "TooManyFields",
    userTypeId: -1,
  });

  assert.throws(() => readTypeMeta(readContext, typeMeta), /maxTypeFields/);
});

runTest("TypeMeta body limit rejects large metadata", () => {
  const readContext = context({}, { maxTypeMetaBytes: 1 });

  assert.throws(
    () => readTypeMeta(readContext, remoteStruct("LargeMeta", "value")),
    /maxTypeMetaBytes/,
  );
});

runTest("TypeMeta cache hit skips current body", () => {
  const readContext = context();
  const typeMeta = remoteStruct("Cached", "value");
  const encoded = typeMeta.toBytes();

  readTypeMeta(readContext, typeMeta);

  const bytes = new Uint8Array(encoded.length + 1);
  bytes[0] = 0;
  bytes.set(encoded, 1);
  bytes[bytes.length - 1] ^= 0xff;
  const cached = readContext.readTypeMeta.bind(readContext);
  readContext.reset(bytes);

  assert.doesNotThrow(cached);
});

runTest("failed compatible TypeMeta does not consume schema limit", () => {
  const localTypeInfo = Type.struct(
    { namespace: "example", typeName: "Shared" },
    { value: Type.int32({ encoding: "fixed" }) },
  );
  const localHash = TypeMeta.fromTypeInfo(localTypeInfo).getHash();
  const original = localSerializer(localTypeInfo);
  const readContext = context({
    computeTypeId(typeInfo) {
      return typeInfo.typeId;
    },
    getSerializerById() {
      return undefined;
    },
    generateReadSerializer(typeInfo) {
      return {
        getTypeInfo() {
          return typeInfo;
        },
      };
    },
  });
  assert.throws(
    () => readCompatibleStructSerializer(
      readContext,
      localHash,
      original,
      remoteStruct("Shared", "value", Type.map(Type.string(), Type.int32({ encoding: "fixed" }))),
    ),
    /field schema mismatch/,
  );
  assert.doesNotThrow(() => readCompatibleStructSerializer(
    readContext,
    localHash,
    original,
    remoteStruct("Shared", "extra"),
  ));
});

runTest("exact local TypeMeta bypasses schema limit", () => {
  const localTypeInfo = Type.struct(
    { namespace: "example", typeName: "Shared" },
    { value: Type.int32({ encoding: "fixed" }) },
  );
  const generatingOriginal = localSerializer(localTypeInfo);
  const localMeta = TypeMeta.fromTypeInfo(localTypeInfo);
  const localBytes = localMeta.toBytes();
  const exactOriginal = {
    getHash() {
      return localMeta.getHash();
    },
    getTypeInfo() {
      throw new Error("exact local compare must use encoded bytes");
    },
    getTypeMetaBytes() {
      return localBytes;
    },
  };
  let activeOriginal = generatingOriginal;
  const localHash = generatingOriginal.getHash();
  const readContext = context({
    computeTypeId(typeInfo) {
      return typeInfo.typeId;
    },
    getSerializerById() {
      return undefined;
    },
    getSerializerByName(name) {
      return name === "example$Shared" ? activeOriginal : undefined;
    },
    generateReadSerializer(typeInfo) {
      return {
        getTypeInfo() {
          return typeInfo;
        },
      };
    },
  });

  readCompatibleStructSerializer(
    readContext,
    localHash,
    generatingOriginal,
    remoteStruct("Shared", "extra"),
  );
  activeOriginal = exactOriginal;
  assert.doesNotThrow(() => readCompatibleStructSerializer(
    readContext,
    localHash,
    undefined,
    localMeta,
  ));
  assert.doesNotThrow(() => readTypeMeta(
    readContext,
    localMeta,
  ));
});

runTest("exact local TypeMeta does not consume schema limit", () => {
  const localTypeInfo = Type.struct(
    { namespace: "example", typeName: "Shared" },
    { value: Type.int32({ encoding: "fixed" }) },
  );
  const original = localSerializer(localTypeInfo);
  const readContext = context({
    computeTypeId(typeInfo) {
      return typeInfo.typeId;
    },
    getSerializerByName(name) {
      return name === "example$Shared" ? original : undefined;
    },
    generateReadSerializer(typeInfo) {
      return {
        getTypeInfo() {
          return typeInfo;
        },
      };
    },
  });

  readTypeMeta(readContext, TypeMeta.fromTypeInfo(localTypeInfo));

  assert.doesNotThrow(() => readTypeMeta(
    readContext,
    remoteStruct("Shared", "extra"),
  ));
});

runTest("failed Any TypeMeta does not consume schema limit", () => {
  const localTypeInfo = Type.struct(
    901,
    { value: Type.int32({ encoding: "fixed" }) },
  );
  const original = localSerializer(localTypeInfo);
  const readContext = context({
    computeTypeId(typeInfo) {
      return typeInfo.typeId;
    },
    getSerializerById(typeId, userTypeId) {
      return userTypeId === 901 ? original : undefined;
    },
    getSerializerByName() {
      return undefined;
    },
    generateReadSerializer(typeInfo) {
      return {
        getHash() {
          return TypeMeta.fromTypeInfo(typeInfo).getHash();
        },
        getTypeInfo() {
          return typeInfo;
        },
      };
    },
  });

  assert.throws(
    () => detectAnySerializer(
      readContext,
      anyStruct("value", Type.map(Type.string(), Type.int32({ encoding: "fixed" }))),
    ),
    /field schema mismatch/,
  );
  assert.doesNotThrow(() => detectAnySerializer(readContext, anyStruct("extra")));
});

runTest("exact Any TypeMeta bypasses schema limit", () => {
  const localTypeInfo = Type.struct(
    901,
    { value: Type.int32({ encoding: "fixed" }) },
  );
  const generatingOriginal = localSerializer(localTypeInfo);
  const localMeta = TypeMeta.fromTypeInfo(localTypeInfo);
  const localBytes = localMeta.toBytes();
  const exactOriginal = {
    getHash() {
      return localMeta.getHash();
    },
    getTypeInfo() {
      throw new Error("exact local compare must use encoded bytes");
    },
    getTypeMetaBytes() {
      return localBytes;
    },
  };
  let activeOriginal = generatingOriginal;
  const readContext = context({
    computeTypeId(typeInfo) {
      return typeInfo.typeId;
    },
    getSerializerById(typeId, userTypeId) {
      return userTypeId === 901 ? activeOriginal : undefined;
    },
    getSerializerByName() {
      return undefined;
    },
    generateReadSerializer(typeInfo) {
      return {
        getHash() {
          return TypeMeta.fromTypeInfo(typeInfo).getHash();
        },
        getTypeInfo() {
          return typeInfo;
        },
      };
    },
  });

  detectAnySerializer(readContext, anyStruct("extra"));
  activeOriginal = exactOriginal;
  assert.doesNotThrow(() => detectAnySerializer(
    readContext,
    localMeta,
  ));
  assert.doesNotThrow(() => readTypeMeta(
    readContext,
    localMeta,
  ));
});

runTest("remote schema limit keeps unknown structs separate", () => {
  const readContext = context();
  assert.equal(
    readTypeMeta(readContext, remoteStruct("UnknownA", "value")).getTypeName(),
    "UnknownA",
  );
  assert.equal(
    readTypeMeta(readContext, remoteStruct("UnknownB", "value")).getTypeName(),
    "UnknownB",
  );
});
