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

import Fory, { Type, BFloat16 } from '../packages/fory/index';
import { describe, expect, test } from '@jest/globals';

describe('number', () => {
  test('should i8 work', () => {

    const fory = new Fory({ refTracking: true });
    const serialize = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.int8()
    })).serializer;
    const input = fory.serialize({ a: 1 }, serialize);
    const result = fory.deserialize(
      input
    );
    expect(result).toEqual({ a: 1 })
  });
  test('should i16 work', () => {

    const fory = new Fory({ refTracking: true });
    const serialize = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.int16()
    })).serializer;
    const input = fory.serialize({ a: 1 }, serialize);
    const result = fory.deserialize(
      input
    );
    expect(result).toEqual({ a: 1 })
  });
  test('should i32 work', () => {

    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.int32()
    })).serializer;
    const input = fory.serialize({ a: 1 }, serializer);
    const result = fory.deserialize(
      input
    );
    expect(result).toEqual({ a: 1 })
  });
  test('should i64 work', () => {

    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.int64()
    })).serializer;
    const input = fory.serialize({ a: 1 }, serializer);
    const result = fory.deserialize(
      input
    );
    result.a = Number(result.a)
    expect(result).toEqual({ a: 1 })
  });

  test('should float32 work', () => {

    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.float32()
    })).serializer;
    const input = fory.serialize({ a: 1.2 }, serializer);
    const result = fory.deserialize(
      input
    );
    expect(result.a).toBeCloseTo(1.2)
  });
  test('should float64 work', () => {

    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.float64()
    })).serializer;
    const input = fory.serialize({ a: 1.2 }, serializer);
    const result = fory.deserialize(
      input
    );
    expect(result.a).toBeCloseTo(1.2)
  });

  test('should float16 work', () => {

    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.float16()
    })).serializer;
    const input = fory.serialize({ a: 1.2 }, serializer);
    const result = fory.deserialize(
      input
    );
    expect(result.a).toBeCloseTo(1.2, 1)
  });

  test('should float16 NAN work', () => {

    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.float16()
    })).serializer;
    const input = fory.serialize({ a: NaN }, serializer);
    const result = fory.deserialize(
      input
    );
    expect(result.a).toBe(NaN)
  });

  test('should float16 Infinity work', () => {

    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.float16()
    })).serializer;
    const input = fory.serialize({ a: Infinity }, serializer);
    const result = fory.deserialize(
      input
    );
    expect(result.a).toBeCloseTo(Infinity)
  });

  test('should bfloat16 work', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.bfloat16()
    })).serializer;
    const input = fory.serialize({ a: BFloat16.fromFloat32(1.5) }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBeInstanceOf(BFloat16);
    expect(result.a.toFloat32()).toBeCloseTo(1.5, 2);
  });

  test('should bfloat16 accept number', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.bfloat16()
    })).serializer;
    const input = fory.serialize({ a: 1.5 }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBeInstanceOf(BFloat16);
    expect(result.a.toFloat32()).toBeCloseTo(1.5, 2);
  });

  test('should bfloat16 NaN work', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.bfloat16()
    })).serializer;
    const input = fory.serialize({ a: NaN }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBeInstanceOf(BFloat16);
    expect(Number.isNaN(result.a.toFloat32())).toBe(true);
  });

  test('should bfloat16 Infinity work', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.bfloat16()
    })).serializer;
    const input = fory.serialize({ a: Infinity }, serializer);
    const result = fory.deserialize(input);
    expect(result.a).toBeInstanceOf(BFloat16);
    expect(result.a.toFloat32()).toBe(Infinity);
  });

  test('should bfloat16 zero and neg zero round-trip', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.bfloat16(),
      b: Type.bfloat16()
    })).serializer;
    const input = fory.serialize({ a: 0, b: -0 }, serializer);
    const result = fory.deserialize(input);
    expect(result.a.toFloat32()).toBe(0);
    expect(result.b.toFloat32()).toBe(-0);
    expect(1 / result.a.toFloat32()).toBe(Infinity);
    expect(1 / result.b.toFloat32()).toBe(-Infinity);
  });

  test('should uint8 work', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.uint8()
    })).serializer;
    const input = fory.serialize({ a: 255 }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 255 });
  });

  test('should uint16 work', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.uint16()
    })).serializer;
    const input = fory.serialize({ a: 65535 }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 65535 });
  });

  test('should uint32 work', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.uint32()
    })).serializer;
    const input = fory.serialize({ a: 4294967295 }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 4294967295 });
  });

  test('should varUInt32 work', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.varUInt32()
    })).serializer;
    const input = fory.serialize({ a: 1000000 }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 1000000 });
  });

  test('should uint64 work', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.uint64()
    })).serializer;
    const input = fory.serialize({ a: 18446744073709551615n }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 18446744073709551615n });
  });

  test('should varUInt64 work', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.varUInt64()
    })).serializer;
    const input = fory.serialize({ a: 1n }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 1n });
  });

  test('should taggedUInt64 work', () => {
    const fory = new Fory({ refTracking: true });
    const serializer = fory.registerSerializer(Type.struct({
      typeName: "example.foo"
    }, {
      a: Type.taggedUInt64()
    })).serializer;
    const input = fory.serialize({ a: 1n }, serializer);
    const result = fory.deserialize(input);
    expect(result).toEqual({ a: 1n });
  });
});


