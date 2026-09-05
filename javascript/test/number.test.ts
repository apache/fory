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

import Fory, { Type } from "../packages/core/index";
import { describe, expect, test } from "@jest/globals";
import { toFloat16Bits } from "../packages/core/lib/types/float16";

describe("number", () => {
  test("should i8 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serialize = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.int8(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1 }, serialize);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 1 });
  });
  test("should i16 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serialize = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.int16(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1 }, serialize);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 1 });
  });
  test("should i32 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.int32(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1 }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 1 });
  });
  test("should i64 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.int64(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1 }, serializer);
    const result = fory.deserialize(input);
    result.a = Number(result.a);
    expect(result).toEqual({ a: 1 });
  });

  test("should float32 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.float32(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1.2 }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBeCloseTo(1.2);
  });
  test("should float64 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.float64(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1.2 }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBeCloseTo(1.2);
  });

  test("should float16 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.float16(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1.2 }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBeCloseTo(1.2, 1);
  });

  test("should float16 NAN work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.float16(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: NaN }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBe(NaN);
  });

  test("should float16 underflow tiny magnitudes to signed zero", () => {
    // Magnitudes below half the smallest float16 subnormal encode as zero;
    // shift counts of 32 or more wrapped (JS masks them with & 31) and left
    // garbage bits in the half.
    const fory = new Fory({ compatible: false, ref: true });
    const { serialize, deserialize } = fory.register(
      Type.struct({ typeName: "example.f16zero" }, { a: Type.float16() }),
    );
    expect(deserialize(serialize({ a: 1e-10 })).a).toBe(0);
    expect(deserialize(serialize({ a: 1e-11 })).a).toBe(0);
    expect(deserialize(serialize({ a: 1e-40 })).a).toBe(0);
    expect(deserialize(serialize({ a: -1e-10 })).a).toBe(-0);
  });

  test.each([
    [0, 0x0000],
    [Number.MIN_VALUE, 0x0000],
    [2 ** -25 - 2 ** -78, 0x0000],
    [2 ** -25, 0x0000],
    [2 ** -25 + 2 ** -77, 0x0001],
    [4e-8, 0x0001],
    [2 ** -24, 0x0001],
    [3 * 2 ** -25 - 2 ** -76, 0x0001],
    [3 * 2 ** -25, 0x0002],
    [2 ** -14 - 2 ** -25 - 2 ** -67, 0x03ff],
    [2 ** -14 - 2 ** -25, 0x0400],
    [2 ** -14, 0x0400],
    [1 + 2 ** -11, 0x3c00],
    [1 + 2 ** -11 + 2 ** -52, 0x3c01],
    [1.0008, 0x3c01],
    [1 + 3 * 2 ** -11 - 2 ** -52, 0x3c01],
    [1 + 3 * 2 ** -11, 0x3c02],
    [2 - 2 ** -11 - 2 ** -52, 0x3fff],
    [2 - 2 ** -11, 0x4000],
    [65504, 0x7bff],
    [65520 - 2 ** -37, 0x7bff],
    [65520, 0x7c00],
    [Number.MAX_VALUE, 0x7c00],
    [Infinity, 0x7c00],
  ])("rounds float16 %s to bits %s", (value, bits) => {
    expect(toFloat16Bits(value)).toBe(bits);
    expect(toFloat16Bits(-value)).toBe(bits | 0x8000);
  });

  test("rounds float16 fields to nearest even", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const { serialize, deserialize } = fory.register(
      Type.struct({ typeName: "example.f16round" }, { a: Type.float16() }),
    );
    expect(deserialize(serialize({ a: 4e-8 })).a).toBe(2 ** -24);
    expect(deserialize(serialize({ a: 1 + 3 * 2 ** -11 })).a).toBe(1 + 2 ** -9);
    expect(deserialize(serialize({ a: 1 + 2 ** -11 + 2 ** -52 })).a).toBe(1 + 2 ** -10);
    expect(deserialize(serialize({ a: -65520 })).a).toBe(-Infinity);
  });

  test("should float16 Infinity work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.float16(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: Infinity }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBeCloseTo(Infinity);
  });

  test("should bfloat16 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.bfloat16(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1.5 }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBeCloseTo(1.5, 2);
  });

  test("should bfloat16 accept number", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.bfloat16(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1.5 }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBeCloseTo(1.5, 2);
  });

  test("should bfloat16 NaN work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.bfloat16(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: NaN }, serializer);
    const result = fory.deserialize(input);
    expect(Number.isNaN(result.a)).toBe(true);
  });

  test("should bfloat16 Infinity work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.bfloat16(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: Infinity }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBe(Infinity);
  });

  test("should bfloat16 zero and neg zero round-trip", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.bfloat16(),
          b: Type.bfloat16(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 0, b: -0 }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBe(0);
    expect(result.b).toBe(-0);
    expect(1 / result.a).toBe(Infinity);
    expect(1 / result.b).toBe(-Infinity);
  });

  test("should uint8 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.uint8(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 255 }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 255 });
  });

  test("should uint16 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.uint16(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 65535 }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 65535 });
  });

  test("should uint32 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.uint32(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 4294967295 }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 4294967295 });
  });

  test("should varUInt32 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.uint32(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1000000 }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 1000000 });
  });

  test("should uint64 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.uint64(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 18446744073709551615n }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 18446744073709551615n });
  });

  test("should varUInt64 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.uint64(),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1n }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 1n });
  });

  test("should taggedUInt64 work", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.foo",
        },
        {
          a: Type.uint64({ encoding: "tagged" }),
        },
      ),
    ).serializer;
    const input = fory.serialize({ a: 1n }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 1n });
  });
});
