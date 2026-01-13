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

import Fory, { TypeInfo, InternalSerializerType, Type } from '../packages/fory/index';
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

  test('should float work', () => {
    
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


