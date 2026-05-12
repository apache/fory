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
  Float16Array,
  Type,
} from "../packages/core/index";
import { ReadContext } from "../packages/core/lib/context";
import { TypeMeta } from "../packages/core/lib/meta/TypeMeta";
import { x64hash128 } from "../packages/core/lib/murmurHash3";
import { BinaryReader } from "../packages/core/lib/reader";
import { BinaryWriter } from "../packages/core/lib/writer";
import { describe, expect, test } from "@jest/globals";

const COMPRESS_META_FLAG = 1n << 8n;
const RESERVED_META_FLAGS = 0b111n << 9n;
const META_SIZE_MASK = 0xffn;
const HASH_SHIFT_BITS = 12n;
const LOW_HEADER_BITS_MASK = (1n << HASH_SHIFT_BITS) - 1n;
const UINT64_MASK = (1n << 64n) - 1n;
const HEADER_HASH_MASK = UINT64_MASK ^ LOW_HEADER_BITS_MASK;

describe("typemeta", () => {
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

  test("keeps tagged direct payload order grouped instead of field-id ordered", () => {
    const typeMeta = TypeMeta.fromTypeInfo(
      Type.struct(7005, {
        stringValue: Type.string().setId(1),
        mapValue: Type.map(Type.string(), Type.int32()).setId(2),
        intValue: Type.int32().setId(10),
      }),
    );

    expect(typeMeta.getFieldInfo().map((field) => field.fieldName)).toEqual([
      "intValue",
      "stringValue",
      "mapValue",
    ]);
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

    view.setBigUint64(
      0,
      bodyOnlyHash | (header & LOW_HEADER_BITS_MASK),
      true,
    );
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
    (context as any).typeMetaCache.set(Number(header >> 32n), new Map([
      [Number(header & 0xffffffffn), typeMeta],
    ]));
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

    const bytes = writerFory.register(writerType).serialize({ value: "hello" });
    const result = readerFory.register(readerType).deserialize(bytes);

    expect(result).toEqual({ value: "hello" });
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

    const changedBytes = changedWriterFory.register(changedWriterType).serialize({
      value: "hello",
    });
    const localBytes = localWriterFory.register(localWriterType).serialize({
      value: 123,
    });
    const reader = readerFory.register(readerType);

    expect(reader.deserialize(changedBytes)).toEqual({ value: "hello" });
    expect(reader.deserialize(localBytes)).toEqual({ value: 123 });
  });

  test("regenerated read serializers keep getTypeInfo", () => {
    const fory = new Fory({ compatible: true });
    const serializer = (fory as any).typeResolver.regenerateReadSerializer(
      Type.struct({ namespace: "example", typeName: "repro_struct" }, {
        value: Type.int32(),
      }),
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
    const createParentType = () => Type.struct(7312, {
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
    const generateReadSerializer
      = typeResolver.generateReadSerializer.bind(typeResolver);
    let generatedReaders = 0;
    typeResolver.generateReadSerializer = (typeInfo: any) => {
      generatedReaders++;
      return generateReadSerializer(typeInfo);
    };

    const stringBytes = stringWriter.serialize({
      child: { value: "hello" },
    });
    const boolBytes = boolWriter.serialize({
      child: { value: true },
    });
    const localBytes = localWriter.serialize({
      child: { value: 123 },
    });

    expect(reader.deserialize(stringBytes)).toEqual({
      child: { value: "hello" },
    });
    expect(reader.deserialize(boolBytes)).toEqual({
      child: { value: true },
    });
    expect(reader.deserialize(stringBytes)).toEqual({
      child: { value: "hello" },
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
      alias: Type.int32().setId(2),
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
    expect(Array.from(result.float16s as Iterable<number>)[0]).toBeCloseTo(1.5, 1);
    expect(Array.from(result.float16s as Iterable<number>)[1]).toBeCloseTo(-2, 1);
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
    expect(() => readerFory.register(readerType).deserialize(nullableBytes)).toThrow();
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

    expect(() => readerFory.register(readerType).deserialize(bytes)).toThrow(/list\/array/);
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

    expect(() => readerFory.register(readerType).deserialize(bytes)).toThrow(/list\/array/);
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
  let header = BigInt.asIntN(
    64,
    hash.getBigInt64(0, false) << HASH_SHIFT_BITS,
  );
  if (header < 0n) {
    header = -header;
  }
  return BigInt.asUintN(64, header) & HEADER_HASH_MASK;
}
