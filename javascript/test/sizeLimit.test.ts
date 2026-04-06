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

import Fory, { Type } from '../packages/core/index';
import { describe, expect, test } from '@jest/globals';

describe('size-limit guardrails', () => {
  describe('configuration', () => {
    test('should have default limits matching Go', () => {
      const fory = new Fory();
      expect(fory.maxBinarySize).toBe(64 * 1024 * 1024);
      expect(fory.maxCollectionSize).toBe(1_000_000);
    });

    test('should accept custom maxBinarySize', () => {
      const fory = new Fory({ maxBinarySize: 1024 });
      expect(fory.maxBinarySize).toBe(1024);
    });

    test('should accept custom maxCollectionSize', () => {
      const fory = new Fory({ maxCollectionSize: 500 });
      expect(fory.maxCollectionSize).toBe(500);
    });

    test('should accept zero as a valid limit', () => {
      const fory = new Fory({ maxBinarySize: 0, maxCollectionSize: 0 });
      expect(fory.maxBinarySize).toBe(0);
      expect(fory.maxCollectionSize).toBe(0);
    });

    test('should reject negative maxBinarySize', () => {
      expect(() => new Fory({ maxBinarySize: -1 })).toThrow(
        'maxBinarySize must be a non-negative integer'
      );
    });

    test('should reject non-integer maxBinarySize', () => {
      expect(() => new Fory({ maxBinarySize: 1.5 })).toThrow(
        'maxBinarySize must be a non-negative integer'
      );
    });

    test('should reject NaN maxBinarySize', () => {
      expect(() => new Fory({ maxBinarySize: NaN })).toThrow(
        'maxBinarySize must be a non-negative integer'
      );
    });

    test('should reject negative maxCollectionSize', () => {
      expect(() => new Fory({ maxCollectionSize: -10 })).toThrow(
        'maxCollectionSize must be a non-negative integer'
      );
    });

    test('should reject non-integer maxCollectionSize', () => {
      expect(() => new Fory({ maxCollectionSize: 2.7 })).toThrow(
        'maxCollectionSize must be a non-negative integer'
      );
    });

    test('should work with other options combined', () => {
      const fory = new Fory({
        maxDepth: 100,
        maxBinarySize: 1024,
        maxCollectionSize: 500,
        refTracking: true,
        compatible: true,
      });
      expect(fory.maxDepth).toBe(100);
      expect(fory.maxBinarySize).toBe(1024);
      expect(fory.maxCollectionSize).toBe(500);
    });
  });

  describe('checkCollectionSize', () => {
    test('should not throw when size is within default limit', () => {
      const fory = new Fory();
      expect(() => fory.checkCollectionSize(999999)).not.toThrow();
    });

    test('should not throw when size is within limit', () => {
      const fory = new Fory({ maxCollectionSize: 100 });
      expect(() => fory.checkCollectionSize(100)).not.toThrow();
      expect(() => fory.checkCollectionSize(0)).not.toThrow();
    });

    test('should throw when size exceeds limit', () => {
      const fory = new Fory({ maxCollectionSize: 100 });
      expect(() => fory.checkCollectionSize(101)).toThrow(
        'Collection size 101 exceeds maxCollectionSize 100'
      );
    });

    test('error message should include helpful suggestion', () => {
      const fory = new Fory({ maxCollectionSize: 10 });
      expect(() => fory.checkCollectionSize(20)).toThrow(
        'increase maxCollectionSize if needed'
      );
    });
  });

  describe('checkBinarySize', () => {
    test('should not throw when size is within default limit', () => {
      const fory = new Fory();
      expect(() => fory.checkBinarySize(999999)).not.toThrow();
    });

    test('should not throw when size is within limit', () => {
      const fory = new Fory({ maxBinarySize: 1024 });
      expect(() => fory.checkBinarySize(1024)).not.toThrow();
      expect(() => fory.checkBinarySize(0)).not.toThrow();
    });

    test('should throw when size exceeds limit', () => {
      const fory = new Fory({ maxBinarySize: 1024 });
      expect(() => fory.checkBinarySize(1025)).toThrow(
        'Binary size 1025 exceeds maxBinarySize 1024'
      );
    });
  });

  describe('list deserialization with maxCollectionSize', () => {
    test('should deserialize list within limit', () => {
      const fory = new Fory({ maxCollectionSize: 10 });
      const { serialize, deserialize } = fory.registerSerializer(Type.array(Type.int32()));
      const data = [1, 2, 3];
      const result = deserialize(serialize(data));
      expect(result).toEqual(data);
    });

    test('should throw when list exceeds maxCollectionSize', () => {
      const serializeFory = new Fory();
      const { serialize } = serializeFory.registerSerializer(Type.array(Type.int32()));
      const bytes = serialize([1, 2, 3, 4, 5]);

      const deserializeFory = new Fory({ maxCollectionSize: 3 });
      const { deserialize } = deserializeFory.registerSerializer(Type.array(Type.int32()));
      expect(() => deserialize(bytes)).toThrow('exceeds maxCollectionSize');
    });

    test('should deserialize list at exact limit', () => {
      const fory = new Fory({ maxCollectionSize: 3 });
      const { serialize, deserialize } = fory.registerSerializer(Type.array(Type.int32()));
      const data = [1, 2, 3];
      const result = deserialize(serialize(data));
      expect(result).toEqual(data);
    });
  });

  describe('set deserialization with maxCollectionSize', () => {
    test('should deserialize set within limit', () => {
      const fory = new Fory({ maxCollectionSize: 10, refTracking: true });
      const { serialize, deserialize } = fory.registerSerializer(Type.set(Type.int32()));
      const data = new Set([1, 2, 3]);
      const result = deserialize(serialize(data));
      expect(result).toEqual(data);
    });

    test('should throw when set exceeds maxCollectionSize', () => {
      const serializeFory = new Fory({ refTracking: true });
      const { serialize } = serializeFory.registerSerializer(Type.set(Type.int32()));
      const bytes = serialize(new Set([1, 2, 3, 4, 5]));

      const deserializeFory = new Fory({ maxCollectionSize: 3, refTracking: true });
      const { deserialize } = deserializeFory.registerSerializer(Type.set(Type.int32()));
      expect(() => deserialize(bytes)).toThrow('exceeds maxCollectionSize');
    });
  });

  describe('map deserialization with maxCollectionSize', () => {
    test('should deserialize map within limit', () => {
      const fory = new Fory({ maxCollectionSize: 10, refTracking: true });
      const { serialize, deserialize } = fory.registerSerializer(
        Type.map(Type.string(), Type.int32())
      );
      const data = new Map([['a', 1], ['b', 2]]);
      const result = deserialize(serialize(data));
      expect(result).toEqual(data);
    });

    test('should throw when map exceeds maxCollectionSize', () => {
      const serializeFory = new Fory({ refTracking: true });
      const { serialize } = serializeFory.registerSerializer(
        Type.map(Type.string(), Type.int32())
      );
      const bytes = serialize(new Map([['a', 1], ['b', 2], ['c', 3], ['d', 4]]));

      const deserializeFory = new Fory({ maxCollectionSize: 2, refTracking: true });
      const { deserialize } = deserializeFory.registerSerializer(
        Type.map(Type.string(), Type.int32())
      );
      expect(() => deserialize(bytes)).toThrow('exceeds maxCollectionSize');
    });
  });

  describe('binary deserialization with maxBinarySize', () => {
    test('should deserialize binary within limit', () => {
      const fory = new Fory({ maxBinarySize: 1024, refTracking: true });
      const { serialize, deserialize } = fory.registerSerializer(Type.struct("test.binary", {
        data: Type.binary(),
      }));
      const data = { data: new Uint8Array([1, 2, 3]) };
      const result = deserialize(serialize(data));
      expect(result!.data![0]).toBe(1);
      expect(result!.data![1]).toBe(2);
      expect(result!.data![2]).toBe(3);
    });

    test('should throw when binary exceeds maxBinarySize', () => {
      const serializeFory = new Fory({ refTracking: true });
      const { serialize } = serializeFory.registerSerializer(Type.struct("test.binary2", {
        data: Type.binary(),
      }));
      const bytes = serialize({ data: new Uint8Array(100) });

      const deserializeFory = new Fory({ maxBinarySize: 50, refTracking: true });
      const { deserialize } = deserializeFory.registerSerializer(Type.struct("test.binary2", {
        data: Type.binary(),
      }));
      expect(() => deserialize(bytes)).toThrow('exceeds maxBinarySize');
    });
  });

  describe('default limits allow normal payloads', () => {
    test('should allow large collections within default limit', () => {
      const fory = new Fory({ refTracking: true });
      const { serialize, deserialize } = fory.registerSerializer(Type.array(Type.int32()));
      const bigArray = Array.from({ length: 1000 }, (_, i) => i);
      const result = deserialize(serialize(bigArray));
      expect(result).toEqual(bigArray);
    });
  });

  describe('polymorphic (any-typed) collection paths', () => {
    test('should enforce maxCollectionSize on untyped list', () => {
      const serializeFory = new Fory({ refTracking: true });
      const bytes = serializeFory.serialize([1, "two", 3.0]);

      const deserializeFory = new Fory({ maxCollectionSize: 2, refTracking: true });
      expect(() => deserializeFory.deserialize(bytes)).toThrow('exceeds maxCollectionSize');
    });

    test('should enforce maxCollectionSize on untyped map', () => {
      const serializeFory = new Fory({ refTracking: true });
      const bytes = serializeFory.serialize(new Map([["a", 1], ["b", 2], ["c", 3]]));

      const deserializeFory = new Fory({ maxCollectionSize: 2, refTracking: true });
      expect(() => deserializeFory.deserialize(bytes)).toThrow('exceeds maxCollectionSize');
    });
  });
});
