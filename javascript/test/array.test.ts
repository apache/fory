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
