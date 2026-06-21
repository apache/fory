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

import Fory, {
  BFloat16Array,
  BoolArray,
  Decimal,
  Float16Array,
  Type,
} from "../packages/core/index";
import type { TypeInfo } from "../packages/core/index";
import { ReadContext } from "../packages/core/lib/context";
import { TypeMeta } from "../packages/core/lib/meta/TypeMeta";
import { x64hash128 } from "../packages/core/lib/murmurHash3";
import { BinaryReader } from "../packages/core/lib/reader";
import { RefFlags, TypeId } from "../packages/core/lib/type";
import { BinaryWriter } from "../packages/core/lib/writer";
import { describe, expect, test } from "@jest/globals";

const COMPRESS_META_FLAG = 1n << 8n;
const RESERVED_META_FLAGS = 0b111n << 9n;
const META_SIZE_MASK = 0xffn;
const HASH_SHIFT_BITS = 12n;
const LOW_HEADER_BITS_MASK = (1n << HASH_SHIFT_BITS) - 1n;
const UINT64_MASK = (1n << 64n) - 1n;
const HEADER_HASH_MASK = UINT64_MASK ^ LOW_HEADER_BITS_MASK;

function decimal(
  unscaledValue: string | bigint | number,
  scale: number,
): Decimal {
  return new Decimal(unscaledValue, scale);
}

function readCompatibleScalar(
  typeId: number,
  writerField: TypeInfo,
  readerField: TypeInfo,
  value: unknown,
): any {
  const writerFory = new Fory({ compatible: true });
  const readerFory = new Fory({ compatible: true });
  const writer = writerFory.register(
    Type.struct(typeId, {
      value: writerField,
    }),
  );
  const reader = readerFory.register(
    Type.struct(typeId, {
      value: readerField,
    }),
  );

  return reader.deserialize(writer.serialize({ value }));
}

function typeMetaRecord(typeMeta: TypeMeta): Uint8Array {
  const writer = new BinaryWriter({});
  writer.writeVarUInt32(0);
  writer.buffer(typeMeta.toBytes());
  return writer.dump();
}

function replaceFirstBytes(
  bytes: Uint8Array,
  search: Uint8Array,
  replacement: Uint8Array,
): Uint8Array {
  expect(search.length).toBe(replacement.length);
  const result = new Uint8Array(bytes);
  for (let i = 0; i <= result.length - search.length; i++) {
    let matched = true;
    for (let j = 0; j < search.length; j++) {
      if (result[i + j] !== search[j]) {
        matched = false;
        break;
      }
    }
    if (matched) {
      result.set(replacement, i);
      return result;
    }
  }
  throw new Error("bytes not found");
}

describe("typemeta", () => {
  test("splits dotted names", () => {
    const structInfo = Type.struct({ typeName: "com.example.User" }, {});
    expect(structInfo.namespace).toBe("com.example");
    expect(structInfo.typeName).toBe("User");

    const enumInfo = Type.enum("com.example.Color", { Red: 1 });
    expect(enumInfo.namespace).toBe("com.example");
    expect(enumInfo.typeName).toBe("Color");

    const extInfo = Type.ext("com.example.External");
    expect(extInfo.namespace).toBe("com.example");
    expect(extInfo.typeName).toBe("External");

    const unionInfo = Type.union("com.example.Payload", { 1: Type.string() });
    expect(unionInfo.namespace).toBe("com.example");
    expect(unionInfo.typeName).toBe("Payload");

    const explicitInfo = Type.struct(
      { namespace: "", typeName: "com.example.Raw" },
      {},
    );
    expect(explicitInfo.namespace).toBe("");
    expect(explicitInfo.typeName).toBe("com.example.Raw");
  });

  test("writes TypeMeta header bits in the xlang layout", () => {
    const typeInfo = Type.struct(7001, {
      fullName: Type.string().setId(1),
      age: Type.int32().setId(2),
    });

    const bytes = TypeMeta.fromTypeInfo(typeInfo).toBytes();
    const header = new DataView(
      bytes.buffer,
      bytes.byteOffset,
      bytes.byteLength,
    ).getBigUint64(0, true);

    expect(Number(header & META_SIZE_MASK)).toBe(bytes.length - 8);
    expect((header & COMPRESS_META_FLAG) !== 0n).toBe(false);
    expect((header & RESERVED_META_FLAGS) !== 0n).toBe(false);
    expect(header >> HASH_SHIFT_BITS).toBeGreaterThan(0n);
    expect((bytes[8] & 0x80) !== 0).toBe(true);
  });

  test("orders tagged non-primitive fields directly by field id", () => {
    const typeMeta = TypeMeta.fromTypeInfo(
      Type.struct(7005, {
        stringValue: Type.string().setId(2),
        mapValue: Type.map(Type.string(), Type.int32()).setId(1),
        intValue: Type.int32().setId(10),
      }),
    );

    expect(typeMeta.getFieldInfo().map((field) => field.fieldName)).toEqual([
      "intValue",
      "mapValue",
      "stringValue",
    ]);
  });

  test("rejects negative field ids", () => {
    expect(() => Type.string().setId(-1)).toThrow(
      "field id must be non-negative",
    );
  });

  test("orders name-based identifiers with ordinal comparison", () => {
    const typeMeta = TypeMeta.fromTypeInfo(
      Type.struct(7011, {
        a_: Type.string(),
        a1: Type.string(),
      }),
    );

    expect(typeMeta.getFieldInfo().map((field) => field.fieldName)).toEqual([
      "a1",
      "a_",
    ]);
  });

  test("rejects duplicate explicit field ids", () => {
    expect(() =>
      TypeMeta.fromTypeInfo(
        Type.struct(7008, {
          first: Type.string().setId(1),
          second: Type.map(Type.string(), Type.int32()).setId(1),
        }),
      ),
    ).toThrow("Duplicate field id 1");
  });

  test("writes the zero size extension when the TypeMeta body is exactly 0xFF bytes", () => {
    const typeMeta = TypeMeta.fromTypeInfo(Type.struct(7003, {})) as any;
    const body = new Uint8Array(0xff);
    const bytes = typeMeta.prependHeader(body, false) as Uint8Array;
    const reader = new BinaryReader({});

    expect(bytes).toHaveLength(8 + 1 + body.length);
    expect(bytes[8]).toBe(0);

    reader.reset(bytes);
    const header = TypeMeta.readHeader(reader);
    TypeMeta.skipBody(reader, header);
    expect(reader.readGetCursor()).toBe(bytes.length);
  });

  test("validates TypeMeta body hash before caching parsed metadata", () => {
    const bytes = TypeMeta.fromTypeInfo(
      Type.struct(7006, {
        value: Type.string().setId(1),
      }),
    ).toBytes();
    const malformed = new Uint8Array(bytes);
    malformed[malformed.length - 1] ^= 1;

    const parseReader = new BinaryReader({});
    parseReader.reset(malformed);
    expect(() => TypeMeta.fromBytes(parseReader)).toThrow(
      "TypeMeta metadata hash mismatch",
    );

    const skipReader = new BinaryReader({});
    skipReader.reset(bytes);
    const header = TypeMeta.readHeader(skipReader);
    TypeMeta.skipBody(skipReader, header);
    expect(skipReader.readGetCursor()).toBe(bytes.length);
  });

  test("includes TypeMeta header low bits in the metadata hash", () => {
    const bytes = TypeMeta.fromTypeInfo(
      Type.struct(7007, {
        value: Type.string().setId(1),
      }),
    ).toBytes();
    const malformed = new Uint8Array(bytes);
    const view = new DataView(
      malformed.buffer,
      malformed.byteOffset,
      malformed.byteLength,
    );
    const header = view.getBigUint64(0, true);
    const bodyOffset = typeMetaBodyOffset(bytes);
    const bodyOnlyHash = bodyOnlyHeaderHashBits(bytes.subarray(bodyOffset));
    expect(header & HEADER_HASH_MASK).not.toBe(bodyOnlyHash);

    view.setBigUint64(0, bodyOnlyHash | (header & LOW_HEADER_BITS_MASK), true);
    const reader = new BinaryReader({});
    reader.reset(malformed);

    expect(() => TypeMeta.fromBytes(reader)).toThrow(
      "TypeMeta metadata hash mismatch",
    );
  });

  test("id enum does not use TypeMeta limits", () => {
    const Color = { Green: 0, Red: 1 };
    const fory = new Fory({
      compatible: true,
      maxTypeMetaBytes: 1,
      maxSchemaVersionsPerType: 1,
    });
    const serializer = fory.register(Type.enum(101, Color));

    expect(serializer.deserialize(serializer.serialize(Color.Red))).toBe(
      Color.Red,
    );
  });

  test("named enum uses TypeMeta for compatible dynamic reads", () => {
    const Color = { Green: 0, Red: 1 };
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });
    writerFory.register(Type.enum("example.Color", Color));
    readerFory.register(Type.enum("example.Color", Color));

    expect(readerFory.deserialize(writerFory.serialize(Color.Red))).toBe(
      Color.Red,
    );
  });

  test("generated named enum validates TypeMeta owner", () => {
    const colorInfo = Type.enum(
      { namespace: "example", typeName: "Color" },
      { Red: 0 },
    );
    const otherInfo = Type.enum(
      { namespace: "example", typeName: "Other" },
      { Blue: 0 },
    );
    const fory = new Fory({ compatible: true, ref: true });
    fory.register(otherInfo);
    const colorReg = fory.register(colorInfo);

    const tampered = replaceFirstBytes(
      colorReg.serialize(0),
      TypeMeta.fromTypeInfo(colorInfo).toBytes(),
      TypeMeta.fromTypeInfo(otherInfo).toBytes(),
    );

    expect(() => fory.deserialize(tampered, colorReg.serializer)).toThrow(
      "TypeMeta mismatch",
    );
  });

  test("generated named ext validates TypeMeta owner", () => {
    class Alpha {
      id = 0;
    }
    class Bravo {
      id = 0;
    }
    Type.ext({ namespace: "example", typeName: "Alpha" })(Alpha);
    Type.ext({ namespace: "example", typeName: "Bravo" })(Bravo);
    const customSerializer = {
      write: (writeContext: any, value: Alpha | Bravo) => {
        writeContext.writeVarInt32(value.id);
      },
      read: (readContext: any, result: Alpha | Bravo) => {
        result.id = readContext.readVarInt32();
      },
    };
    const fory = new Fory({ compatible: true, ref: true });
    const bravoReg = fory.register(Bravo, customSerializer);
    const alphaReg = fory.register(Alpha, customSerializer);
    const value = new Alpha();
    value.id = 7;

    const tampered = replaceFirstBytes(
      alphaReg.serialize(value),
      TypeMeta.fromTypeInfo(alphaReg.serializer.getTypeInfo()).toBytes(),
      TypeMeta.fromTypeInfo(bravoReg.serializer.getTypeInfo()).toBytes(),
    );

    expect(() => fory.deserialize(tampered, alphaReg.serializer)).toThrow(
      "TypeMeta mismatch",
    );
  });

  test("generated named union validates TypeMeta owner", () => {
    const leftInfo = Type.union(
      { namespace: "example", typeName: "Alpha" },
      { 1: Type.string() },
    );
    const rightInfo = Type.union(
      { namespace: "example", typeName: "Bravo" },
      { 1: Type.string() },
    );
    const fory = new Fory({ compatible: true, ref: true });
    const rightReg = fory.register(rightInfo);
    const leftReg = fory.register(leftInfo);

    const tampered = replaceFirstBytes(
      leftReg.serialize({ case: 1, value: "ok" }),
      TypeMeta.fromTypeInfo(leftReg.serializer.getTypeInfo()).toBytes(),
      TypeMeta.fromTypeInfo(rightReg.serializer.getTypeInfo()).toBytes(),
    );

    expect(() => fory.deserialize(tampered, leftReg.serializer)).toThrow(
      "TypeMeta mismatch",
    );
  });

  test("id ext does not use TypeMeta limits", () => {
    class IdExt {
      id = 0;
    }
    Type.ext(102)(IdExt);
    const customSerializer = {
      write: (writeContext: any, value: IdExt) => {
        writeContext.writeVarInt32(value.id);
      },
      read: (readContext: any, result: IdExt) => {
        result.id = readContext.readVarInt32();
      },
    };
    const fory = new Fory({
      compatible: true,
      maxTypeMetaBytes: 1,
      maxSchemaVersionsPerType: 1,
    });
    const serializer = fory.register(IdExt, customSerializer);

    const value = new IdExt();
    value.id = 42;
    expect(serializer.deserialize(serializer.serialize(value))).toEqual(value);
  });

  test("TypeMeta header cache hit skips the current body size", () => {
    const typeMeta = TypeMeta.fromTypeInfo(Type.struct(7010, {}));
    const encoded = typeMeta.toBytes();
    const headerReader = new BinaryReader({});
    headerReader.reset(encoded);
    const header = headerReader.readUint64();
    const bodySize = encoded.length - 8;
    const writer = new BinaryWriter({});
    writer.writeVarUInt32(0);
    writer.writeUint64(header);
    writer.buffer(new Uint8Array(bodySize));
    writer.buffer(new Uint8Array([0x7b]));

    const config = { ref: false, useSliceString: false, hooks: {} } as any;
    const context = new ReadContext(
      {
        config,
        trackingRef: false,
        computeTypeId: (typeInfo: any) => typeInfo.typeId,
        getSerializerById: () => undefined,
        getSerializerByName: () => undefined,
        getSerializerByData: () => undefined,
        isCompatible: () => false,
        generateReadSerializer: () => {
          throw new Error("unused");
        },
        regenerateReadSerializer: () => {
          throw new Error("unused");
        },
      } as any,
      config,
    );
    (context as any).typeMetaCache.set(typeMeta.getHash(), typeMeta);
    context.reset(writer.dump());

    expect(context.readTypeMeta()).toBe(typeMeta);
    expect(context.reader.readUint8()).toBe(0x7b);
  });

  test("encodes extended id-registered struct field counts without the name bit", () => {
    const fields: Record<string, any> = {};
    for (let i = 0; i < 32; i++) {
      fields[`field${i}`] = Type.int32().setId(i + 1);
    }
    const bytes = TypeMeta.fromTypeInfo(Type.struct(7201, fields)).toBytes();
    const reader = new BinaryReader({});
    const bodyOffset = typeMetaBodyOffset(bytes);

    expect(bytes[bodyOffset] & 0x1f).toBe(0x1f);
    expect(bytes[bodyOffset] & 0x20).toBe(0);

    reader.reset(bytes);
    const decoded = TypeMeta.fromBytes(reader);
    expect(decoded.getFieldInfo()).toHaveLength(32);
  });

  test("regenerates compatible named serializers when schema changes but field count stays the same", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct("example.item", {
      value: Type.string(),
    });
    const readerType = Type.struct("example.item", {
      value: Type.int32(),
    });

    const bytes = writerFory.register(writerType).serialize({ value: "123" });
    const result = readerFory.register(readerType).deserialize(bytes);

    expect(result).toEqual({ value: 123 });
  });

  test("changed-schema reader does not replace the original serializer", () => {
    const changedWriterFory = new Fory({ compatible: true });
    const localWriterFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const changedWriterType = Type.struct(7302, {
      value: Type.string().setId(1),
    });
    const localWriterType = Type.struct(7302, {
      value: Type.int32().setId(1),
    });
    const readerType = Type.struct(7302, {
      value: Type.int32().setId(1),
    });

    const changedBytes = changedWriterFory
      .register(changedWriterType)
      .serialize({
        value: "456",
      });
    const localBytes = localWriterFory.register(localWriterType).serialize({
      value: 123,
    });
    const reader = readerFory.register(readerType);

    expect(reader.deserialize(changedBytes)).toEqual({ value: 456 });
    expect(reader.deserialize(localBytes)).toEqual({ value: 123 });
  });

  test("regenerated read serializers keep getTypeInfo", () => {
    const fory = new Fory({ compatible: true });
    const serializer = (fory as any).typeResolver.regenerateReadSerializer(
      Type.struct(
        { namespace: "example", typeName: "repro_struct" },
        {
          value: Type.int32(),
        },
      ),
    );

    expect(typeof serializer.getTypeInfo).toBe("function");
    expect(serializer.getTypeInfo().named).toBe("example$repro_struct");
  });

  test("caches compatible readers for alternating nested schemas", () => {
    const stringWriterFory = new Fory({ compatible: true });
    const boolWriterFory = new Fory({ compatible: true });
    const localWriterFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const stringChildType = Type.struct(7311, {
      value: Type.string().setId(1),
    });
    const boolChildType = Type.struct(7311, {
      value: Type.bool().setId(1),
    });
    const localChildType = Type.struct(7311, {
      value: Type.int32().setId(1),
    });
    const createParentType = () =>
      Type.struct(7312, {
        child: Type.struct(7311).setId(1),
      });

    stringWriterFory.register(stringChildType);
    boolWriterFory.register(boolChildType);
    localWriterFory.register(localChildType);
    readerFory.register(localChildType);

    const stringWriter = stringWriterFory.register(createParentType());
    const boolWriter = boolWriterFory.register(createParentType());
    const localWriter = localWriterFory.register(createParentType());
    const reader = readerFory.register(createParentType());

    const typeResolver = (readerFory as any).typeResolver;
    const generateReadSerializer =
      typeResolver.generateReadSerializer.bind(typeResolver);
    let generatedReaders = 0;
    typeResolver.generateReadSerializer = (typeInfo: any) => {
      generatedReaders++;
      return generateReadSerializer(typeInfo);
    };

    const stringBytes = stringWriter.serialize({
      child: { value: "7" },
    });
    const boolBytes = boolWriter.serialize({
      child: { value: true },
    });
    const localBytes = localWriter.serialize({
      child: { value: 123 },
    });

    expect(reader.deserialize(stringBytes)).toEqual({
      child: { value: 7 },
    });
    expect(reader.deserialize(boolBytes)).toEqual({
      child: { value: 1 },
    });
    expect(reader.deserialize(stringBytes)).toEqual({
      child: { value: 7 },
    });
    expect(reader.deserialize(localBytes)).toEqual({
      child: { value: 123 },
    });
    expect(generatedReaders).toBe(2);
  });

  test("compatible reader cache uses remote hash and local stale guard", () => {
    const typeMeta = TypeMeta.fromTypeInfo(
      Type.struct(7313, {
        value: Type.string().setId(1),
      }),
    );
    const bytes = typeMetaRecord(typeMeta);
    const config = { ref: false, useSliceString: false, hooks: {} } as any;
    const context = new ReadContext(
      {
        config,
        trackingRef: false,
        computeTypeId: (typeInfo: any) => typeInfo.typeId,
        getSerializerById: () => undefined,
        getSerializerByName: () => undefined,
        getSerializerByData: () => undefined,
        isCompatible: () => true,
        generateReadSerializer: () => {
          throw new Error("unused");
        },
        regenerateReadSerializer: () => {
          throw new Error("unused");
        },
      } as any,
      config,
    );
    const serializers = [{ name: "localA" }, { name: "localB" }] as any[];
    let generatedReaders = 0;
    (context as any).genSerializerByTypeMetaRuntime = () =>
      serializers[generatedReaders++];
    const localHashA = typeMeta.getHash() + 1;
    const localHashB = typeMeta.getHash() + 2;
    const originalA = { name: "originalA" } as any;
    const originalB = { name: "originalB" } as any;
    const readStructInfo = (localHash: number, original: any) => {
      context.reset(bytes);
      return context.readCompatibleStructSerializer(localHash, original);
    };

    expect(readStructInfo(localHashA, originalA)).toBe(serializers[0]);
    expect(readStructInfo(localHashA, originalB)).toBe(serializers[0]);
    expect(readStructInfo(localHashB, originalA)).toBe(serializers[1]);
    expect(readStructInfo(localHashB, originalB)).toBe(serializers[1]);
    expect(generatedReaders).toBe(2);
  });

  test("remaps compatible tag-id fields onto local property names during regeneration", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct(7002, {
      fullName: Type.string().setId(1),
      note: Type.string().setId(2),
    });
    const readerType = Type.struct(7002, {
      name: Type.string().setId(1),
      alias: Type.string().setId(2),
    });

    const bytes = writerFory.register(writerType).serialize({
      fullName: "Alice",
      note: "ally",
    });
    const result = readerFory.register(readerType).deserialize(bytes);

    expect(result).toEqual({
      name: "Alice",
      alias: "ally",
    });
  });

  test("converts compatible bool scalars", () => {
    expect(
      readCompatibleScalar(7220, Type.string(), Type.bool(), "true"),
    ).toEqual({ value: true });
    expect(
      readCompatibleScalar(7221, Type.bool(), Type.string(), false),
    ).toEqual({ value: "false" });
    expect(
      readCompatibleScalar(
        7222,
        Type.int32({ encoding: "fixed" }),
        Type.bool(),
        1,
      ),
    ).toEqual({ value: true });
    expect(
      readCompatibleScalar(
        7223,
        Type.bool(),
        Type.int32({ encoding: "fixed" }),
        true,
      ),
    ).toEqual({ value: 1 });

    const decimalResult = readCompatibleScalar(
      7224,
      Type.bool(),
      Type.decimal(),
      false,
    );
    expect(decimalResult.value).toBeInstanceOf(Decimal);
    expect(decimalResult.value.equals(decimal(0n, 0))).toBe(true);
  });

  test("generates direct compatible scalar reads", () => {
    const generated: string[] = [];
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({
      compatible: true,
      hooks: {
        afterCodeGenerated: (code) => {
          generated.push(code);
          return code;
        },
      },
    });
    const writer = writerFory.register(
      Type.struct(7261, {
        wide: Type.int32({ encoding: "fixed" }).setId(1),
        flag: Type.string().setId(2),
        label: Type.bool().setId(3),
        narrow: Type.int64({ encoding: "fixed" }).setId(4),
      }),
    );
    const reader = readerFory.register(
      Type.struct(7261, {
        wide: Type.int64({ encoding: "fixed" }).setId(1),
        flag: Type.bool().setId(2),
        label: Type.string().setId(3),
        narrow: Type.int32({ encoding: "fixed" }).setId(4),
      }),
    );

    const result = reader.deserialize(
      writer.serialize({
        wide: 7,
        flag: "true",
        label: false,
        narrow: 42n,
      }),
    );
    const source = generated.join("\n");

    expect(result).toEqual({
      wide: 7n,
      flag: true,
      label: "false",
      narrow: 42,
    });
    expect(source).toContain("BigInt(br.readInt32())");
    expect(source).toContain(
      "external.CompatibleScalarConverter.stringToBool(br.stringWithHeader())",
    );
    expect(source).toContain(
      '(external.CompatibleScalarConverter.checkedBool(br.readUint8()) ? "true" : "false")',
    );
    expect(source).toContain(
      "external.CompatibleScalarConverter.checkedInt32(br.readInt64())",
    );
    expect(source).not.toContain("CompatibleScalarConverter.read(");
    expect(source).not.toContain("remoteTypeId");
    expect(source).not.toContain("scalarKind(");
  });

  test("preserves regenerated compatible remote field order", () => {
    const generated: string[] = [];
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({
      compatible: true,
      hooks: {
        afterCodeGenerated: (code) => {
          generated.push(code);
          return code;
        },
      },
    });
    const writer = writerFory.register(
      Type.struct(7264, {
        remoteFirst: Type.string().setId(1),
        remoteSecond: Type.string().setId(2),
      }),
    );
    const reader = readerFory.register(
      Type.struct(7264, {
        "10": Type.string().setId(1),
        "1": Type.string().setId(2),
      }),
    );

    const result = reader.deserialize(
      writer.serialize({
        remoteFirst: "first",
        remoteSecond: "second",
      }),
    );
    const source = generated.join("\n");

    expect(result).toEqual({
      "10": "first",
      "1": "second",
    });
    const firstRead = source.indexOf('["10"] = result_');
    const secondRead = source.indexOf('["1"] = result_');
    expect(firstRead).toBeGreaterThanOrEqual(0);
    expect(secondRead).toBeGreaterThan(firstRead);
  });

  test("rejects invalid bool scalars", () => {
    expect(() =>
      readCompatibleScalar(7225, Type.string(), Type.bool(), "yes"),
    ).toThrow(/not a boolean value/);
    expect(() =>
      readCompatibleScalar(
        7226,
        Type.int32({ encoding: "fixed" }),
        Type.bool(),
        2,
      ),
    ).toThrow(/not a boolean value/);
  });

  test("converts exact number scalars", () => {
    expect(
      readCompatibleScalar(
        7227,
        Type.int32({ encoding: "fixed" }),
        Type.int16(),
        300,
      ),
    ).toEqual({ value: 300 });
    expect(
      readCompatibleScalar(
        7228,
        Type.string(),
        Type.int64({ encoding: "fixed" }),
        "9223372036854775807",
      ),
    ).toEqual({ value: 9223372036854775807n });
    expect(
      readCompatibleScalar(7229, Type.string(), Type.float64(), "0.5"),
    ).toEqual({ value: 0.5 });

    const decimalResult = readCompatibleScalar(
      7230,
      Type.string(),
      Type.decimal(),
      "12.340",
    );
    expect(decimalResult.value).toBeInstanceOf(Decimal);
    expect(decimalResult.value.equals(decimal(1234n, 2))).toBe(true);

    expect(
      readCompatibleScalar(
        7231,
        Type.decimal(),
        Type.string(),
        decimal(12340n, 3),
      ),
    ).toEqual({ value: "12.34" });

    const digitBound = readCompatibleScalar(
      7255,
      Type.string(),
      Type.decimal(),
      "1".repeat(256),
    );
    expect(digitBound.value).toBeInstanceOf(Decimal);
    expect(digitBound.value.equals(decimal("1".repeat(256), 0))).toBe(true);

    const exponentBound = readCompatibleScalar(
      7256,
      Type.string(),
      Type.decimal(),
      "1e255",
    );
    expect(exponentBound.value).toBeInstanceOf(Decimal);
    expect(exponentBound.value.unscaledValue.toString()).toHaveLength(256);
    expect(exponentBound.value.scale).toBe(0);
  });

  test("rejects inexact number scalars", () => {
    expect(() =>
      readCompatibleScalar(7232, Type.string(), Type.float64(), "0.1"),
    ).toThrow(/not exactly representable/);
    expect(() =>
      readCompatibleScalar(7248, Type.string(), Type.int32(), "+1"),
    ).toThrow(/Invalid scalar string/);
    expect(() =>
      readCompatibleScalar(7249, Type.string(), Type.float64(), ".5"),
    ).toThrow(/Invalid scalar string/);
    expect(() =>
      readCompatibleScalar(7250, Type.string(), Type.float64(), "1."),
    ).toThrow(/Invalid scalar string/);
    expect(() =>
      readCompatibleScalar(
        7251,
        Type.string(),
        Type.decimal(),
        "1".repeat(257),
      ),
    ).toThrow(/Invalid scalar string/);
    expect(() =>
      readCompatibleScalar(
        7253,
        Type.string(),
        Type.decimal(),
        `0.${"0".repeat(319)}`,
      ),
    ).toThrow(/Invalid scalar string/);
    expect(() =>
      readCompatibleScalar(7257, Type.string(), Type.decimal(), "1e1000000"),
    ).toThrow(/Invalid scalar string/);
    expect(() =>
      readCompatibleScalar(7258, Type.string(), Type.decimal(), "1e256"),
    ).toThrow(/Invalid scalar string/);
    expect(() =>
      readCompatibleScalar(7233, Type.decimal(), Type.int32(), decimal(5n, 1)),
    ).toThrow(/not an integer/);
    expect(() =>
      readCompatibleScalar(
        7259,
        Type.decimal(),
        Type.string(),
        decimal(1n, -256),
      ),
    ).toThrow(/magnitude exceeds compatible conversion limit/);
    expect(() =>
      readCompatibleScalar(
        7234,
        Type.int32({ encoding: "fixed" }),
        Type.int8(),
        128,
      ),
    ).toThrow(/outside int8 range/);
    expect(() =>
      readCompatibleScalar(7235, Type.float64(), Type.string(), Number.NaN),
    ).toThrow(/Non-finite scalar value NaN/);
  });

  test("composes scalar conversion with nulls", () => {
    expect(
      readCompatibleScalar(
        7236,
        Type.string().setNullable(true),
        Type.bool(),
        "false",
      ),
    ).toEqual({ value: false });
    expect(
      readCompatibleScalar(
        7237,
        Type.string().setNullable(true),
        Type.bool(),
        null,
      ),
    ).toEqual({ value: null });
    expect(
      readCompatibleScalar(
        7252,
        Type.string(),
        Type.bool().setNullable(true),
        "true",
      ),
    ).toEqual({ value: true });
  });

  test("rejects tracking-ref scalar mismatches", () => {
    const writerFory = new Fory({ compatible: true, ref: true });
    const readerFory = new Fory({ compatible: true, ref: true });
    class RemoteScalars {
      flag = "true";
    }
    class LocalScalars {
      flag = false;
    }
    Type.struct(7254, {
      flag: Type.string().setId(1).setTrackingRef(true),
    })(RemoteScalars);
    Type.struct(7254, {
      flag: Type.bool().setId(1),
    })(LocalScalars);
    const writer = writerFory.register(RemoteScalars);
    const reader = readerFory.register(LocalScalars);

    expect(() =>
      reader.deserialize(writer.serialize(new RemoteScalars())),
    ).toThrow(/unsupported compatible scalar tracking-ref schema mismatch/);
  });

  test("rejects incompatible matched fields", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });
    const writer = writerFory.register(
      Type.struct(7255, {
        value: Type.string().setId(1),
      }),
    );
    const reader = readerFory.register(
      Type.struct(7255, {
        value: Type.map(Type.string(), Type.int32()).setId(1),
      }),
    );

    expect(() =>
      reader.deserialize(writer.serialize({ value: "abc" })),
    ).toThrow(/unsupported compatible field schema mismatch/);
  });

  test("rejects nested scalar mismatches", () => {
    expect(() =>
      readCompatibleScalar(
        7238,
        Type.list(Type.string()),
        Type.list(Type.int32()),
        ["1", "2"],
      ),
    ).toThrow(/unsupported compatible field schema mismatch/);

    expect(() =>
      readCompatibleScalar(
        7240,
        Type.map(Type.string(), Type.string()),
        Type.map(Type.string(), Type.int32()),
        new Map([["one", "1"]]),
      ),
    ).toThrow(/unsupported compatible field schema mismatch/);
  });

  test("rejects nested collection shape drift", () => {
    expect(() =>
      readCompatibleScalar(
        7263,
        Type.list(Type.string()),
        Type.list(Type.map(Type.string(), Type.int32())),
        ["one"],
      ),
    ).toThrow(/unsupported compatible field schema mismatch/);

    expect(() =>
      readCompatibleScalar(
        7264,
        Type.map(Type.string(), Type.list(Type.string())),
        Type.map(
          Type.string(),
          Type.list(Type.map(Type.string(), Type.int32())),
        ),
        new Map([["values", ["one"]]]),
      ),
    ).toThrow(/unsupported compatible field schema mismatch/);

    expect(() =>
      readCompatibleScalar(
        7268,
        Type.list(Type.any()),
        Type.list(Type.struct(7269, { name: Type.string() })),
        ["one"],
      ),
    ).toThrow(/unsupported compatible field schema mismatch/);
  });

  test("reads nested scalar nullable drift", () => {
    expect(
      readCompatibleScalar(
        7241,
        Type.list(Type.string().setNullable(true)),
        Type.list(Type.string()),
        ["a", null],
      ),
    ).toEqual({ value: ["a", null] });
    expect(
      readCompatibleScalar(
        7246,
        Type.map(Type.string(), Type.string().setNullable(true)),
        Type.map(Type.string(), Type.string()),
        new Map([["a", null]]),
      ),
    ).toEqual({ value: new Map([["a", null]]) });
  });

  test("rejects nested scalar tracking-ref drift", () => {
    expect(() =>
      readCompatibleScalar(
        7242,
        Type.list(Type.string().setTrackingRef(true)),
        Type.list(Type.string()),
        ["a"],
      ),
    ).toThrow(/unsupported compatible field schema mismatch/);
  });

  test("reuses local struct metadata across struct wire families", () => {
    const fory = new Fory({ compatible: true });
    const readContext = (fory as any).readContext;
    const local = Type.struct(7243, {
      name: Type.string(),
    });
    const remote = {
      typeId: TypeId.STRUCT,
      nullable: false,
      trackingRef: false,
      options: {},
    };

    const regenerated = readContext.fieldInfoToTypeInfo(remote, local);

    expect(regenerated.typeId).toBe(local.typeId);
    expect(regenerated.options.props.name.typeId).toBe(TypeId.STRING);
  });

  test("reuses local ext metadata across ext wire families", () => {
    const fory = new Fory({ compatible: true });
    const readContext = (fory as any).readContext;
    const numericLocal = Type.ext(7244);
    const namedRemote = {
      typeId: TypeId.NAMED_EXT,
      nullable: false,
      trackingRef: false,
      options: {},
    };

    const numericRegenerated = readContext.fieldInfoToTypeInfo(
      namedRemote,
      numericLocal,
    );
    expect(numericRegenerated.typeId).toBe(numericLocal.typeId);
    expect(numericRegenerated.userTypeId).toBe(7244);

    const namedLocal = Type.ext("example.External");
    const numericRemote = {
      typeId: TypeId.EXT,
      nullable: false,
      trackingRef: false,
      options: {},
    };

    const namedRegenerated = readContext.fieldInfoToTypeInfo(
      numericRemote,
      namedLocal,
    );
    expect(namedRegenerated.typeId).toBe(namedLocal.typeId);
    expect(namedRegenerated.typeName).toBe("External");
  });

  test("keeps same-schema scalar reads direct", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });
    const typeInfo = Type.struct(7239, {
      value: Type.float64(),
    });
    const writer = writerFory.register(typeInfo);
    const reader = readerFory.register(typeInfo);
    const typeResolver = (readerFory as any).typeResolver;
    const generateReadSerializer =
      typeResolver.generateReadSerializer.bind(typeResolver);
    let generatedReaders = 0;
    typeResolver.generateReadSerializer = (changedTypeInfo: any) => {
      generatedReaders++;
      return generateReadSerializer(changedTypeInfo);
    };

    const result = reader.deserialize(writer.serialize({ value: Number.NaN }));

    expect(Number.isNaN(result.value)).toBe(true);
    expect(generatedReaders).toBe(0);
  });

  test("strictly reads same-type nullable compatible scalar fields", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });
    const writer = writerFory.register(
      Type.struct(7260, {
        value: Type.bool().setNullable(true),
      }),
    );
    const reader = readerFory.register(
      Type.struct(7260, {
        value: Type.bool(),
      }),
    );
    const bytes = writer.serialize({ value: true });
    const flag = bytes.lastIndexOf(RefFlags.NotNullValueFlag & 0xff);
    expect(flag).toBeGreaterThanOrEqual(0);

    const badFlag = new Uint8Array(bytes);
    badFlag[flag] = RefFlags.RefValueFlag & 0xff;
    expect(() => reader.deserialize(badFlag)).toThrow(
      /Invalid reference flag for compatible scalar field value/,
    );

    const badPayload = new Uint8Array(bytes);
    badPayload[badPayload.length - 1] = 2;
    expect(() => reader.deserialize(badPayload)).toThrow(
      /Invalid boolean scalar value/,
    );
  });

  test("adapts only immediate compatible list and dense array field pairs", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct(7211, {
      values: Type.list(Type.int32({ encoding: "fixed" })).setId(1),
    });
    const readerType = Type.struct(7211, {
      values: Type.int32Array().setId(1),
    });

    const bytes = writerFory.register(writerType).serialize({
      values: [1, 2, 3],
    });
    const result = readerFory.register(readerType).deserialize(bytes);

    expect(result.values).toBeInstanceOf(Int32Array);
    expect(Array.from(result.values)).toEqual([1, 2, 3]);
  });

  test("adapts compatible list fields to reduced-precision dense array carriers", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct(7214, {
      bools: Type.list(Type.bool()).setId(1),
      float16s: Type.list(Type.float16()).setId(2),
      bfloat16s: Type.list(Type.bfloat16()).setId(3),
    });
    const readerType = Type.struct(7214, {
      bools: Type.boolArray().setId(1),
      float16s: Type.float16Array().setId(2),
      bfloat16s: Type.bfloat16Array().setId(3),
    });

    const bytes = writerFory.register(writerType).serialize({
      bools: [true, false],
      float16s: [1.5, -2],
      bfloat16s: [1.5, -2],
    });
    const result = readerFory.register(readerType).deserialize(bytes);

    expect(result.bools).toBeInstanceOf(BoolArray);
    expect(Array.from(result.bools)).toEqual([true, false]);
    expect(result.float16s).toBeInstanceOf(Float16Array as any);
    expect(Array.from(result.float16s as Iterable<number>)[0]).toBeCloseTo(
      1.5,
      1,
    );
    expect(Array.from(result.float16s as Iterable<number>)[1]).toBeCloseTo(
      -2,
      1,
    );
    expect(result.bfloat16s).toBeInstanceOf(BFloat16Array);
    expect(Array.from(result.bfloat16s as Iterable<number>)).toEqual([1.5, -2]);
  });

  test("adapts compatible dense array field to immediate list field", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct(7213, {
      values: Type.int32Array().setId(1),
    });
    const readerType = Type.struct(7213, {
      values: Type.list(Type.int32({ encoding: "fixed" })).setId(1),
    });

    const bytes = writerFory.register(writerType).serialize({
      values: new Int32Array([1, 2, 3]),
    });
    const result = readerFory.register(readerType).deserialize(bytes);

    expect(Array.isArray(result.values)).toBe(true);
    expect(result).toEqual({ values: [1, 2, 3] });
  });

  test("adapts immediate binary and uint8 array field pairs", () => {
    const bytes = new Uint8Array([0, 1, 2, 250, 255]);
    expect(
      Array.from(
        readCompatibleScalar(7265, Type.binary(), Type.uint8Array(), bytes)
          .value as Uint8Array,
      ),
    ).toEqual(Array.from(bytes));

    expect(
      Array.from(
        readCompatibleScalar(7266, Type.uint8Array(), Type.binary(), bytes)
          .value as Uint8Array,
      ),
    ).toEqual(Array.from(bytes));

    expect(
      Array.from(
        readCompatibleScalar(
          7270,
          Type.binary().setTrackingRef(true),
          Type.uint8Array().setTrackingRef(true),
          bytes,
        ).value as Uint8Array,
      ),
    ).toEqual(Array.from(bytes));
  });

  test("rejects nested binary and uint8 array positions", () => {
    expect(() =>
      readCompatibleScalar(
        7267,
        Type.list(Type.binary()),
        Type.list(Type.uint8Array()),
        [new Uint8Array([1, 2])],
      ),
    ).toThrow(/unsupported compatible field schema mismatch/);
  });

  test("adapts compatible nullable list schema to dense array", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct(7212, {
      values: Type.list(
        Type.int32({ encoding: "fixed" }).setNullable(true),
      ).setId(1),
    });
    const readerType = Type.struct(7212, {
      values: Type.int32Array().setId(1),
    });

    const serializer = writerFory.register(writerType);
    const bytes = serializer.serialize({
      values: [1, 2, 3],
    });
    const reader = readerFory.register(readerType);
    const decoded = reader.deserialize(bytes);
    expect(Array.from(decoded.values as Int32Array)).toEqual([1, 2, 3]);

    const nullBytes = serializer.serialize({
      values: [1, null, 3],
    });
    expect(() => reader.deserialize(nullBytes)).toThrow(/nullable/);
  });

  test("rejects compatible list and dense array root framing drift", () => {
    expect(() =>
      readCompatibleScalar(
        7261,
        Type.list(Type.int32({ encoding: "fixed" })).setNullable(true),
        Type.int32Array(),
        [1, 2, 3],
      ),
    ).toThrow(/list\/array/);

    expect(() =>
      readCompatibleScalar(
        7262,
        Type.int32Array(),
        Type.list(Type.int32({ encoding: "fixed" })).setNullable(true),
        new Int32Array([1, 2, 3]),
      ),
    ).toThrow(/list\/array/);
  });

  test("rejects incompatible immediate list and dense array element fields", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct(7215, {
      values: Type.list(Type.string()).setId(1),
    });
    const readerType = Type.struct(7215, {
      values: Type.int32Array().setId(1),
    });

    const bytes = writerFory.register(writerType).serialize({
      values: ["1", "2"],
    });

    expect(() => readerFory.register(readerType).deserialize(bytes)).toThrow(
      /list\/array/,
    );
  });

  test("rejects nested compatible list and dense array positions", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct(7216, {
      values: Type.list(Type.int32Array()).setId(1),
    });
    const readerType = Type.struct(7216, {
      values: Type.list(Type.list(Type.int32({ encoding: "fixed" }))).setId(1),
    });

    const bytes = writerFory.register(writerType).serialize({
      values: [new Int32Array([1, 2])],
    });

    expect(() => readerFory.register(readerType).deserialize(bytes)).toThrow(
      /list\/array/,
    );
  });

  test("skips remote-only named compatible fields", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct("example.foo", {
      bar: Type.string(),
      bar2: Type.int32(),
    });
    const readerType = Type.struct("example.foo", {
      bar: Type.string(),
    });

    const bytes = writerFory.register(writerType).serialize({
      bar: "hello",
      bar2: 123,
    });
    const result = readerFory.register(readerType).deserialize(bytes);

    expect(result).toEqual({
      bar: "hello",
    });
    expect((result as { bar2?: number }).bar2).toBeUndefined();
  });

  test("remaps regenerated compatible field names onto local snake_case properties", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    class WriterHolder {
      animalMap = new Map<string, number>();
      marker = 0;
    }
    Type.struct(7004, {
      animalMap: Type.map(Type.string(), Type.any()),
      marker: Type.int32(),
    })(WriterHolder);

    class ReaderHolder {
      animal_map = new Map<string, number>();
    }
    Type.struct(7004, {
      animal_map: Type.map(Type.string(), Type.any()),
    })(ReaderHolder);

    const writerReg = writerFory.register(WriterHolder);
    const readerReg = readerFory.register(ReaderHolder);

    const value = new WriterHolder();
    value.animalMap.set("dog", 7);
    value.marker = 99;

    const result = readerReg.deserialize(writerReg.serialize(value));

    expect(result).toBeInstanceOf(ReaderHolder);
    expect(result.animal_map.get("dog")).toBe(7);
    expect(
      (result as ReaderHolder & { animalMap?: Map<string, number> }).animalMap,
    ).toBeUndefined();
    expect(
      (result as ReaderHolder & { marker?: number }).marker,
    ).toBeUndefined();
  });

  test("skips unknown named custom fields by falling back to any when no local field exists", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    class MyExt {
      id = 0;
    }
    Type.ext("my_ext")(MyExt);

    const customSerializer = {
      write: (writeContext: any, value: MyExt) => {
        writeContext.writeVarInt32(value.id);
      },
      read: (readContext: any, result: MyExt) => {
        result.id = readContext.readVarInt32();
      },
    };

    writerFory.register(MyExt, customSerializer);
    readerFory.register(MyExt, customSerializer);

    class WriterWrapper {
      note = "";
      myExt = new MyExt();
    }
    Type.struct("example.wrapper", {
      note: Type.string(),
      myExt: Type.ext("my_ext"),
    })(WriterWrapper);

    class EmptyWrapper {}
    Type.struct("example.wrapper", {})(EmptyWrapper);

    const writerReg = writerFory.register(WriterWrapper);
    const readerReg = readerFory.register(EmptyWrapper);

    const value = new WriterWrapper();
    value.note = "hello";
    value.myExt.id = 42;

    const result = readerReg.deserialize(writerReg.serialize(value));

    expect(result).toBeInstanceOf(EmptyWrapper);
  });

  test("skips unknown compatible enum fields when regenerating an empty reader", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const TestEnum = {
      VALUE_A: 0,
      VALUE_B: 1,
      VALUE_C: 2,
    };
    writerFory.register(Type.enum(7101, TestEnum));
    readerFory.register(Type.enum(7101, TestEnum));

    class WriterStruct {
      f1 = TestEnum.VALUE_A;
      f2 = TestEnum.VALUE_B;
    }
    Type.struct(7102, {
      f1: Type.enum(7101, TestEnum),
      f2: Type.enum(7101, TestEnum),
    })(WriterStruct);

    class EmptyStruct {}
    Type.struct(7102, {})(EmptyStruct);

    const writerReg = writerFory.register(WriterStruct);
    const readerReg = readerFory.register(EmptyStruct);

    const value = new WriterStruct();
    const result = readerReg.deserialize(writerReg.serialize(value));

    expect(result).toBeInstanceOf(EmptyStruct);
  });

  test("skips unknown enum and named custom fields together during compatible regeneration", () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    writerFory.register(Type.enum("color", Color));
    readerFory.register(Type.enum("color", Color));

    class MyExt {
      id = 0;
    }
    Type.ext("my_ext")(MyExt);

    const customSerializer = {
      write: (writeContext: any, value: MyExt) => {
        writeContext.writeVarInt32(value.id);
      },
      read: (readContext: any, result: MyExt) => {
        result.id = readContext.readVarInt32();
      },
    };

    writerFory.register(MyExt, customSerializer);
    readerFory.register(MyExt, customSerializer);

    class MyStruct {
      id = 0;
    }
    Type.struct("my_struct", {
      id: Type.int32(),
    })(MyStruct);

    writerFory.register(MyStruct);
    readerFory.register(MyStruct);

    class WriterWrapper {
      color = Color.White;
      myStruct = new MyStruct();
      myExt = new MyExt();
    }
    Type.struct("my_wrapper", {
      color: Type.enum("color", Color),
      myStruct: Type.struct("my_struct"),
      myExt: Type.ext("my_ext"),
    })(WriterWrapper);

    class EmptyWrapper {}
    Type.struct("my_wrapper", {})(EmptyWrapper);

    const writerReg = writerFory.register(WriterWrapper);
    const readerReg = readerFory.register(EmptyWrapper);

    const value = new WriterWrapper();
    value.myStruct.id = 42;
    value.myExt.id = 43;

    const result = readerReg.deserialize(writerReg.serialize(value));

    expect(result).toBeInstanceOf(EmptyWrapper);
  });
});

function typeMetaBodyOffset(bytes: Uint8Array) {
  const reader = new BinaryReader({});
  reader.reset(bytes);
  const header = TypeMeta.readHeader(reader);
  if ((header & META_SIZE_MASK) === META_SIZE_MASK) {
    reader.readVarUInt32();
  }
  return reader.readGetCursor();
}

function bodyOnlyHeaderHashBits(buffer: Uint8Array) {
  const hash = x64hash128(buffer, 47);
  let header = BigInt.asIntN(64, hash.getBigInt64(0, false) << HASH_SHIFT_BITS);
  if (header < 0n) {
    header = -header;
  }
  return BigInt.asUintN(64, header) & HEADER_HASH_MASK;
}
