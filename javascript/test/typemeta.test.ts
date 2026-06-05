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

  test("TypeMeta header cache hit skips the current body size", () => {
    const header = 0xffn;
    const typeMeta = TypeMeta.fromTypeInfo(Type.struct(7010, {}));
    const writer = new BinaryWriter({});
    writer.writeVarUInt32(0);
    writer.writeUint64(header);
    writer.writeVarUInt32(0);
    writer.buffer(new Uint8Array(0xff));
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
    (context as any).typeMetaCache.set(
      Number(header >> 32n),
      new Map([[Number(header & 0xffffffffn), typeMeta]]),
    );
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

  test("does not retain regenerated compatible serializer after a schema mismatch read", () => {
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

  test("caches regenerated compatible readers for alternating nested schemas", () => {
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

  test("rejects invalid bool scalars", () => {
    expect(() =>
      readCompatibleScalar(7225, Type.string(), Type.bool(), "yes"),
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(
        7226,
        Type.int32({ encoding: "fixed" }),
        Type.bool(),
        2,
      ),
    ).toThrow(/compatible field value/);
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
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(7248, Type.string(), Type.int32(), "+1"),
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(7249, Type.string(), Type.float64(), ".5"),
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(7250, Type.string(), Type.float64(), "1."),
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(
        7251,
        Type.string(),
        Type.decimal(),
        "1".repeat(257),
      ),
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(
        7253,
        Type.string(),
        Type.decimal(),
        `0.${"0".repeat(319)}`,
      ),
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(7257, Type.string(), Type.decimal(), "1e1000000"),
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(7258, Type.string(), Type.decimal(), "1e256"),
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(7233, Type.decimal(), Type.int32(), decimal(5n, 1)),
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(
        7259,
        Type.decimal(),
        Type.string(),
        decimal(1n, -256),
      ),
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(
        7234,
        Type.int32({ encoding: "fixed" }),
        Type.int8(),
        128,
      ),
    ).toThrow(/compatible field value/);
    expect(() =>
      readCompatibleScalar(7235, Type.float64(), Type.string(), Number.NaN),
    ).toThrow(/compatible field value/);
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

  test("applies tracking-ref scalar rules", () => {
    const writerFory = new Fory({ compatible: true, ref: true });
    const readerFory = new Fory({ compatible: true, ref: true });
    class RemoteScalars {
      flag = "true";
      count = "1";
      same = true;
      remoteNullable = true;
      localNullable = true;
      bothNullable = true;
    }
    class LocalScalars {
      flag = false;
      count = 0;
      same = false;
      remoteNullable = false;
      localNullable = false;
      bothNullable = false;
    }
    Type.struct(7254, {
      flag: Type.string().setId(1).setTrackingRef(true),
      count: Type.string().setId(2),
      same: Type.bool().setId(3).setTrackingRef(true),
      remoteNullable: Type.bool()
        .setId(4)
        .setTrackingRef(true)
        .setNullable(true),
      localNullable: Type.bool().setId(5).setTrackingRef(true),
      bothNullable: Type.bool().setId(6).setTrackingRef(true).setNullable(true),
    })(RemoteScalars);
    Type.struct(7254, {
      flag: Type.bool().setId(1),
      count: Type.int32().setId(2).setTrackingRef(true),
      same: Type.bool().setId(3).setTrackingRef(true),
      remoteNullable: Type.bool().setId(4).setTrackingRef(true),
      localNullable: Type.bool()
        .setId(5)
        .setTrackingRef(true)
        .setNullable(true),
      bothNullable: Type.bool().setId(6).setTrackingRef(true).setNullable(true),
    })(LocalScalars);
    const writer = writerFory.register(RemoteScalars);
    const reader = readerFory.register(LocalScalars);

    const result = reader.deserialize(writer.serialize(new RemoteScalars()));
    expect(result).toBeInstanceOf(LocalScalars);
    expect(result.flag).toBe(false);
    expect(result.count).toBe(0);
    expect(result.same).toBe(true);
    expect(result.remoteNullable).toBe(false);
    expect(result.localNullable).toBe(false);
    expect(result.bothNullable).toBe(true);
  });

  test("keeps nested scalars unconverted", () => {
    expect(
      readCompatibleScalar(
        7238,
        Type.list(Type.string()),
        Type.list(Type.int32()),
        ["1", "2"],
      ),
    ).toEqual({ value: ["1", "2"] });
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

  test("rejects compatible list to dense array when payload has nullable elements", () => {
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
    const nonNullBytes = serializer.serialize({
      values: [1, 2, 3],
    });
    const result = readerFory.register(readerType).deserialize(nonNullBytes);
    expect(Array.from(result.values as Int32Array)).toEqual([1, 2, 3]);

    const nullableBytes = serializer.serialize({
      values: [1, null, 3],
    });
    expect(() =>
      readerFory.register(readerType).deserialize(nullableBytes),
    ).toThrow();
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

  test("keeps compatible named schema evolution working when field count differs", () => {
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
      bar2: 123,
    });
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
    expect((result as ReaderHolder & { marker?: number }).marker).toBe(99);
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
