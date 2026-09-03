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
  Type,
  BFloat16Array,
  BoolArray,
  Float16Array,
  ForyFloat16Array,
} from "../packages/core/index";
import { TypeId } from "../packages/core/lib/type";
import { describe, expect, test } from "@jest/globals";
import * as beautify from "js-beautify";

describe("array", () => {
  test("should distinguish list and dense array schema builders", () => {
    expect(Type.list(Type.int32()).typeId).toBe(TypeId.LIST);
    expect(Type.int32Array().typeId).toBe(TypeId.INT32_ARRAY);
    expect(Type.uint8Array().typeId).toBe(TypeId.UINT8_ARRAY);
    expect(Type.boolArray().typeId).toBe(TypeId.BOOL_ARRAY);
  });

  test("should expose dense arrays only through element-specific builders", () => {
    expect((Type as Record<string, unknown>).array).toBeUndefined();
    expect(() => Type.map(Type.uint8Array(), Type.string())).toThrow("map key");
  });

  test("should array work", () => {
    const typeinfo = Type.struct(
      {
        typeName: "example.bar",
      },
      {
        c: Type.list(
          Type.struct(
            {
              typeName: "example.foo",
            },
            {
              a: Type.string(),
            },
          ),
        ),
      },
    );
    const fory = new Fory({
      compatible: false,
      ref: true,
      hooks: {
        afterCodeGenerated: (code: string) => {
          return beautify.js(code, {
            indent_size: 2,
            space_in_empty_paren: true,
            indent_empty_lines: true,
          });
        },
      },
    });
    const { serialize, deserialize } = fory.register(typeinfo);
    const o = { a: "123" };
    expect(deserialize(serialize({ c: [o, o] }))).toEqual({ c: [o, o] });
  });

  test("should root list use declared element type", () => {
    // A root Type.list(...) registration previously fell back to the internal
    // any-typed list serializer, silently discarding declared element types:
    // declared float32 must narrow, while dynamic dispatch keeps float64.
    const fory = new Fory({ compatible: false });
    const { serialize, deserialize } = fory.register(Type.list(Type.float32()));
    expect(deserialize(serialize([0.1]))).toEqual([Math.fround(0.1)]);

    // The dynamic list serializer must stay untouched by the registration.
    expect(fory.deserialize(fory.serialize([0.1, "a"]))).toEqual([0.1, "a"]);
  });

  test("should root set use declared element type", () => {
    const fory = new Fory({ compatible: false });
    const { serialize, deserialize } = fory.register(Type.set(Type.float32()));
    expect(deserialize(serialize(new Set([0.1])))).toEqual(new Set([Math.fround(0.1)]));
    expect(fory.deserialize(fory.serialize(new Set([0.1, "a"])))).toEqual(new Set([0.1, "a"]));
  });

  test("should root container registered before its ext codec work", () => {
    // Registration order is free before the first root operation: the
    // container's forward ext placeholder must be filled when the extension
    // codec registers later, so the generated serializer binds to the
    // completed codec instead of capturing undefined.
    class ListedExtension {
      constructor(public id = 0) {}
    }
    Type.ext(921)(ListedExtension);
    const extCodec = {
      write(context: any, value: ListedExtension) {
        context.writeUint8(value.id);
      },
      read(context: any, result: ListedExtension) {
        result.id = context.readUint8();
      },
    };

    const listFory = new Fory({ compatible: false });
    const list = listFory.register(Type.list(Type.ext(921)));
    listFory.register(ListedExtension, extCodec);
    const listResult = list.deserialize(list.serialize([new ListedExtension(7)]));
    expect(listResult).toEqual([new ListedExtension(7)]);

    const setFory = new Fory({ compatible: false });
    const set = setFory.register(Type.set(Type.ext(921)));
    setFory.register(ListedExtension, extCodec);
    const setResult = set.deserialize(set.serialize(new Set([new ListedExtension(9)])));
    expect(setResult).toEqual(new Set([new ListedExtension(9)]));
  });

  test("preserves a self-reference in a dynamic list", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const value: any[] = [];
    value.push(value);

    const result = fory.deserialize(fory.serialize(value)) as any[];

    expect(result[0]).toBe(result);
  });

  test("round-trips dynamic list frames", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const shared = ["nested"];
    const value = [shared, null, 1, shared];

    const result = fory.deserialize(fory.serialize(value)) as unknown[];

    expect(result[0]).toBe(result[3]);
    expect(result[1]).toBeNull();
    expect(result[2]).toBe(1);
    expect(fory.deserialize(fory.serialize([null, null]))).toEqual([null, null]);
  });

  test("round-trips compact empty-struct lists", () => {
    @Type.struct(302)
    class DynamicEmpty {}

    const dynamicFory = new Fory({ compatible: true, ref: false });
    dynamicFory.register(DynamicEmpty);
    const dynamicValues = Array.from({ length: 256 }, () => new DynamicEmpty());
    const dynamicBytes = dynamicFory.serialize(dynamicValues);

    expect(dynamicBytes.length).toBeLessThan(dynamicValues.length);
    const dynamicResult = dynamicFory.deserialize(dynamicBytes) as DynamicEmpty[];
    expect(dynamicResult).toHaveLength(dynamicValues.length);
    expect(dynamicResult.every((value) => value instanceof DynamicEmpty)).toBe(true);

    const staticFory = new Fory({ compatible: true, ref: false });
    const staticEmptyType = Type.struct(304, {});
    @staticEmptyType
    class StaticEmpty {}
    staticFory.register(StaticEmpty);
    const staticSerializer = staticFory.register(
      Type.struct(303, {
        values: Type.list(staticEmptyType),
      }),
    );
    const staticValues = Array.from({ length: 256 }, () => new StaticEmpty());
    const staticValue = { values: staticValues };
    const staticBytes = staticSerializer.serialize(staticValue);

    expect(staticSerializer.deserialize(staticBytes)).toEqual(staticValue);
  });

  test("should typedarray work", () => {
    const typeinfo = Type.struct(
      {
        typeName: "example.foo",
      },
      {
        a: Type.boolArray(),
        a2: Type.int16Array(),
        a3: Type.int32Array(),
        a4: Type.int64Array(),
        a6: Type.float64Array(),
      },
    );

    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(typeinfo).serializer;
    const input = fory.serialize(
      {
        a: [true, false],
        a2: new Int16Array([1, 2, 3]),
        a3: new Int32Array([3, 5, 76]),
        a4: new BigInt64Array([634n, 564n, 76n]),
        a6: new Float64Array([234243.555, 55654.679]),
      },
      serializer,
    );
    const result = fory.deserialize(input);
    expect(result.a).toBeInstanceOf(BoolArray);
    expect(Array.from(result.a)).toEqual([true, false]);
    expect(result.a2).toEqual(new Int16Array([1, 2, 3]));
    expect(result.a3).toEqual(new Int32Array([3, 5, 76]));
    expect(result.a4).toEqual(new BigInt64Array([634n, 564n, 76n]));
    expect(result.a6).toEqual(new Float64Array([234243.555, 55654.679]));
  });

  test("should floatarray work", () => {
    const typeinfo = Type.struct(
      {
        typeName: "example.foo",
      },
      {
        a5: Type.float32Array(),
      },
    );

    const fory = new Fory({ compatible: false, ref: true });
    const serialize = fory.register(typeinfo).serializer;
    const input = fory.serialize(
      {
        a5: new Float32Array([2.43, 654.4, 55]),
      },
      serialize,
    );
    const result = fory.deserialize(input);
    expect(result.a5[0]).toBeCloseTo(2.43);
    expect(result.a5[1]).toBeCloseTo(654.4);
    expect(result.a5[2]).toBeCloseTo(55);
  });

  test("should float16Array work", () => {
    const typeinfo = Type.struct(
      {
        typeName: "example.foo",
      },
      {
        a6: Type.float16Array(),
      },
    );

    const fory = new Fory({ compatible: false, ref: true });
    const serialize = fory.register(typeinfo).serializer;
    const input = fory.serialize(
      {
        a6: [1.5, 2.5, -4.5],
      },
      serialize,
    );
    const result = fory.deserialize(input);
    expect(result.a6).toBeInstanceOf(Float16Array as any);
    expect(Array.from(result.a6 as Iterable<number>)[0]).toBeCloseTo(1.5, 1);
    expect(Array.from(result.a6 as Iterable<number>)[1]).toBeCloseTo(2.5, 1);
    expect(Array.from(result.a6 as Iterable<number>)[2]).toBeCloseTo(-4.5, 1);
  });

  test("rounds float16 arrays to nearest even", () => {
    const values = [4e-8, 1 + 2 ** -11, 1 + 3 * 2 ** -11, 1 + 2 ** -11 + 2 ** -52, -65520];
    const expected = [2 ** -24, 1, 1 + 2 ** -9, 1 + 2 ** -10, -Infinity];
    const array = new ForyFloat16Array(values);
    expect(Array.from(array)).toEqual(expected);

    const fory = new Fory({ compatible: false, ref: true });
    const { serialize, deserialize } = fory.register(
      Type.struct({ typeName: "example.f16round" }, { values: Type.float16Array() }),
    );
    for (const input of [values, array]) {
      expect(Array.from(deserialize(serialize({ values: input })).values)).toEqual(expected);
    }
  });

  test("should bfloat16Array work", () => {
    const typeinfo = Type.struct(
      {
        typeName: "example.foo",
      },
      {
        a7: Type.bfloat16Array(),
      },
    );
    const fory = new Fory({ compatible: false, ref: true });
    const serialize = fory.register(typeinfo).serializer;
    const input = fory.serialize(
      {
        a7: [1.5, 2.5, -4.5],
      },
      serialize,
    );
    const result = fory.deserialize(input);
    expect(result.a7).toBeInstanceOf(BFloat16Array);
    expect(result.a7).toHaveLength(3);
    expect(result.a7.get(0)).toBeCloseTo(1.5, 2);
    expect(result.a7.get(1)).toBeCloseTo(2.5, 2);
    expect(result.a7.get(2)).toBeCloseTo(-4.5, 2);
  });

  test("should bfloat16Array accept BFloat16Array", () => {
    const typeinfo = Type.struct(
      {
        typeName: "example.foo",
      },
      {
        a7: Type.bfloat16Array(),
      },
    );
    const fory = new Fory({ compatible: false, ref: true });
    const serialize = fory.register(typeinfo).serializer;
    const arr = new BFloat16Array([1.25, -2.5, 0]);
    const input = fory.serialize({ a7: arr }, serialize);
    const result = fory.deserialize(input);
    expect(result.a7).toBeInstanceOf(BFloat16Array);
    expect(result.a7).toHaveLength(3);
    expect(result.a7.get(0)).toBeCloseTo(1.25, 2);
    expect(result.a7.get(1)).toBeCloseTo(-2.5, 2);
    expect(result.a7.get(2)).toBe(0);
  });

  test("should expose bool and reduced-precision array carriers", () => {
    const bools = new BoolArray([true, false, true]);
    bools.setValue(1, true);
    expect(Array.from(bools)).toEqual([true, true, true]);
    expect(bools.raw).toEqual(new Uint8Array([1, 1, 1]));

    const f16 = new ForyFloat16Array([1.5, -2]);
    expect(f16.get(0)).toBeCloseTo(1.5, 1);
    expect(f16.get(1)).toBeCloseTo(-2, 1);

    const bf16 = new BFloat16Array([1.5, -2]);
    expect(Array.from(bf16)).toEqual([1.5, -2]);
  });

  test("should write dense array protocol bytes in little-endian order", () => {
    const fory = new Fory({ compatible: false, ref: true });

    const uint16Type = Type.struct(
      { typeName: "example.uint16array" },
      {
        values: Type.uint16Array(),
      },
    );
    const uint16Serializer = fory.register(uint16Type).serializer;
    const uint16Bytes = fory.serialize(
      {
        values: new Uint16Array([0x1234, 0xabcd]),
      },
      uint16Serializer,
    );
    expect(containsBytes(uint16Bytes, [0x34, 0x12, 0xcd, 0xab])).toBe(true);

    const float16Type = Type.struct(
      { typeName: "example.float16array" },
      {
        values: Type.float16Array(),
      },
    );
    const float16Serializer = fory.register(float16Type).serializer;
    const float16Bytes = fory.serialize(
      {
        values: new ForyFloat16Array([1, -2]),
      },
      float16Serializer,
    );
    expect(containsBytes(float16Bytes, [0x00, 0x3c, 0x00, 0xc0])).toBe(true);

    const bfloat16Type = Type.struct(
      { typeName: "example.bfloat16array" },
      {
        values: Type.bfloat16Array(),
      },
    );
    const bfloat16Serializer = fory.register(bfloat16Type).serializer;
    const bfloat16Bytes = fory.serialize(
      {
        values: new BFloat16Array([1, -2]),
      },
      bfloat16Serializer,
    );
    expect(containsBytes(bfloat16Bytes, [0x80, 0x3f, 0x00, 0xc0])).toBe(true);
  });

  test("should large any-typed list work", () => {
    // The dynamic element write path must reserve writer capacity per item.
    // Without it, single-byte writes past the buffer end were silent no-ops
    // while the cursor advanced, so dump() returned uninitialized tail bytes.
    const fory = new Fory({ compatible: false });
    const { serialize, deserialize } = fory.register(Type.list(Type.any()));
    const arr = new Array(150000).fill(1);
    const result = deserialize(serialize(arr)) as number[];
    expect(result.length).toBe(150000);
    expect(result.every((x) => x === 1)).toBe(true);
  });

  test("should large mixed-type list work", () => {
    // Mixed element types disable the same-type aggregate reserve, so this
    // exercises the per-item reserves in the dynamic write loops, with and
    // without null elements. Numeric elements only: string bodies reserve
    // internally, which would mask a missing per-item reserve.
    const fory = new Fory({ compatible: false });
    const { serialize, deserialize } = fory.register(Type.list(Type.any()));
    const arr: (number | bigint | null)[] = [];
    for (let i = 0; i < 50000; i++) {
      arr.push(i, BigInt(i), i % 100 === 0 ? null : -i);
    }
    expect(deserialize(serialize(arr))).toEqual(arr);

    const noNulls = arr.filter((x) => x !== null);
    expect(deserialize(serialize(noNulls))).toEqual(noNulls);
  });

  test("should reserialize unknown struct with a large declared list", () => {
    // Reserializing an unknown compatible struct writes declared list fields
    // through CollectionAnySerializer.writeDeclared, which must reserve
    // writer capacity for the whole list body.
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });
    const writer = writerFory.register(Type.struct(7501, { values: Type.list(Type.int32()) }));
    const values = new Array(30000).fill(123456789);
    const unknown = readerFory.deserialize(writer.serialize({ values }));
    expect(writer.deserialize(readerFory.serialize(unknown))).toEqual({ values });
  });
});

function containsBytes(bytes: Uint8Array, needle: number[]) {
  outer: for (let i = 0; i <= bytes.length - needle.length; i++) {
    for (let j = 0; j < needle.length; j++) {
      if (bytes[i + j] !== needle[j]) {
        continue outer;
      }
    }
    return true;
  }
  return false;
}
