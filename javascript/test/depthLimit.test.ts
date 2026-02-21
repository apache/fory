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

import Fory, { Type } from '../packages/fory/index';
import { describe, expect, test } from '@jest/globals';

describe('depth-limit', () => {
  describe('configuration', () => {
    test('should have default maxDepth of 50', () => {
      const fory = new Fory();
      expect(fory.maxDepth).toBe(50);
    });

    test('should accept custom maxDepth', () => {
      const fory = new Fory({ maxDepth: 100 });
      expect(fory.maxDepth).toBe(100);
    });

    test('should initialize depth counter to 0', () => {
      const fory = new Fory();
      expect(fory.depth).toBe(0);
    });

    test('should reject maxDepth < 2', () => {
      expect(() => new Fory({ maxDepth: 1 })).toThrow(
        'maxDepth must be >= 2 but got 1'
      );
    });

    test('should reject maxDepth = 0', () => {
      expect(() => new Fory({ maxDepth: 0 })).toThrow(
        'maxDepth must be >= 2'
      );
    });

    test('should reject negative maxDepth', () => {
      expect(() => new Fory({ maxDepth: -5 })).toThrow(
        'maxDepth must be >= 2'
      );
    });
  });

  describe('depth operations', () => {
    test('should have incReadDepth method', () => {
      const fory = new Fory();
      expect(typeof fory.incReadDepth).toBe('function');
    });

    test('should have decReadDepth method', () => {
      const fory = new Fory();
      expect(typeof fory.decReadDepth).toBe('function');
    });

    test('incReadDepth should increment depth', () => {
      const fory = new Fory({ maxDepth: 100 });
      expect(fory.depth).toBe(0);
      fory.incReadDepth();
      expect(fory.depth).toBe(1);
      fory.incReadDepth();
      expect(fory.depth).toBe(2);
    });

    test('decReadDepth should decrement depth', () => {
      const fory = new Fory({ maxDepth: 100 });
      fory.incReadDepth();
      fory.incReadDepth();
      expect(fory.depth).toBe(2);
      fory.decReadDepth();
      expect(fory.depth).toBe(1);
      fory.decReadDepth();
      expect(fory.depth).toBe(0);
    });

    test('incReadDepth should throw when depth exceeds limit', () => {
      const fory = new Fory({ maxDepth: 2 });
      fory.incReadDepth(); // depth = 1
      fory.incReadDepth(); // depth = 2
      expect(() => fory.incReadDepth()).toThrow(
        'Deserialization depth limit exceeded: 3 > 2'
      );
    });

    test('depth error message should mention limit and hint', () => {
      const fory = new Fory({ maxDepth: 5 });
      try {
        for (let i = 0; i < 6; i++) {
          fory.incReadDepth();
        }
        throw new Error('Should have thrown depth limit error');
      } catch (e) {
        expect(e.message).toContain('Deserialization depth limit exceeded');
        expect(e.message).toContain('5');
        expect(e.message).toContain('increase maxDepth if needed');
      }
    });
  });

  describe('deserialization with depth tracking', () => {
    test('should deserialize simple struct without depth error', () => {
      const fory = new Fory({ maxDepth: 50 });
      const typeInfo = Type.struct({
        typeName: 'simple.struct',
      }, {
        a: Type.int32(),
        b: Type.string(),
      });

      const { serialize, deserialize } = fory.registerSerializer(typeInfo);
      const data = { a: 42, b: 'hello' };
      const serialized = serialize(data);
      const deserialized = deserialize(serialized);

      expect(deserialized).toEqual(data);
      expect(fory.depth).toBe(0); // Should be reset after deserialization
    });

    test('should deserialize nested struct within depth limit', () => {
      const fory = new Fory({ maxDepth: 10 });
      const nestedType = Type.struct({
        typeName: 'nested.outer',
      }, {
        value: Type.int32(),
        inner: Type.struct({
          typeName: 'nested.inner',
        }, {
          innerValue: Type.int32(),
        }).setNullable(true),
      });

      const { serialize, deserialize } = fory.registerSerializer(nestedType);
      const data = { value: 1, inner: { innerValue: 2 } };
      const serialized = serialize(data);
      const deserialized = deserialize(serialized);

      expect(deserialized).toEqual(data);
      expect(fory.depth).toBe(0); // Should be reset after deserialization
    });

    test('should deserialize array of primitives within depth limit', () => {
      const fory = new Fory({ maxDepth: 10 });
      const arrayType = Type.array(Type.int32());

      const { serialize, deserialize } = fory.registerSerializer(arrayType);
      const data = [1, 2, 3, 4, 5];
      const serialized = serialize(data);
      const deserialized = deserialize(serialized);

      expect(deserialized).toEqual(data);
      expect(fory.depth).toBe(0); // Should be 0 after deserialization
    });

    test('should deserialize map within depth limit', () => {
      const fory = new Fory({ maxDepth: 10 });
      const mapType = Type.map(Type.string(), Type.int32());

      const { serialize, deserialize } = fory.registerSerializer(mapType);
      const data = new Map([['a', 1], ['b', 2]]);
      const serialized = serialize(data);
      const deserialized = deserialize(serialized);

      expect(deserialized).toEqual(data);
      expect(fory.depth).toBe(0); // Should be 0 after deserialization
    });

    test('should reset depth at start of each deserialization', () => {
      const fory = new Fory({ maxDepth: 50 });
      const typeInfo = Type.struct({
        typeName: 'test.reset',
      }, {
        a: Type.int32(),
      });

      const { serialize, deserialize } = fory.registerSerializer(typeInfo);
      deserialize(serialize({ a: 1 }));

      // Depth will be reset at the start of resetRead() call
      expect(fory.depth).toBe(0);

      deserialize(serialize({ a: 2 }));
      expect(fory.depth).toBe(0);
    });
  });

  describe('cross-serialization depth limits', () => {
    test('should allow serialize with high limit and deserialize with low limit', () => {
      const serializeType = Type.struct({
        typeName: 'cross.test',
      }, {
        value: Type.int32(),
        next: Type.struct({
          typeName: 'cross.inner',
        }, {
          innerValue: Type.int32(),
        }).setNullable(true),
      });

      // Serialize with high limit
      const forySerialize = new Fory({ maxDepth: 100 });
      const { serialize } = forySerialize.registerSerializer(serializeType);

      const data = { value: 1, next: { innerValue: 2 } };
      const serialized = serialize(data);

      // Deserialize with different instance
      const foryDeserialize = new Fory({ maxDepth: 50 });
      const { deserialize } = foryDeserialize.registerSerializer(serializeType);

      const deserialized = deserialize(serialized);
      expect(deserialized).toEqual(data);
    });

    test('should have independent depth tracking per Fory instance', () => {
      const fory1 = new Fory({ maxDepth: 50 });
      const fory2 = new Fory({ maxDepth: 100 });

      fory1.incReadDepth();
      fory1.incReadDepth();
      expect(fory1.depth).toBe(2);

      fory2.incReadDepth();
      expect(fory2.depth).toBe(1);

      // Both instances have independent depth counters
      expect(fory1.depth).toBe(2);
      expect(fory2.depth).toBe(1);
    });
  });

  describe('error scenarios', () => {
    test('error message should include helpful suggestion', () => {
      const fory = new Fory({ maxDepth: 2 });
      try {
        for (let i = 0; i < 3; i++) {
          fory.incReadDepth();
        }
        throw new Error('Should have thrown');
      } catch (e) {
        expect(e.message).toContain('increase maxDepth if needed');
      }
    });

    test('should recover after depth error when deserialization resets depth', () => {
      const typeInfo = Type.struct({
        typeName: 'test.recovery',
      }, {
        a: Type.int32(),
      });

      const fory = new Fory({ maxDepth: 50 });
      const { serialize, deserialize } = fory.registerSerializer(typeInfo);

      // First deserialization
      let result = deserialize(serialize({ a: 1 }));
      expect(result).toEqual({ a: 1 });
      expect(fory.depth).toBe(0);

      // Second deserialization should also work (depth reset)
      result = deserialize(serialize({ a: 2 }));
      expect(result).toEqual({ a: 2 });
      expect(fory.depth).toBe(0);
    });
  });

  describe('edge cases', () => {
    test('should handle maxDepth exactly equal to required depth', () => {
      const typeInfo = Type.struct({
        typeName: 'edge.exact',
      }, {
        a: Type.int32(),
      });

      const fory = new Fory({ maxDepth: 2 });
      const { serialize, deserialize } = fory.registerSerializer(typeInfo);
      // Should deserialize without error
      const result = deserialize(serialize({ a: 42 }));
      expect(result).toEqual({ a: 42 });
    });

    test('should handle large maxDepth values', () => {
      const fory = new Fory({ maxDepth: 10000 });
      expect(fory.maxDepth).toBe(10000);
    });

    test('should handle minimum valid maxDepth of 2', () => {
      const typeInfo = Type.struct({
        typeName: 'edge.min',
      }, {
        a: Type.int32(),
      });

      const fory = new Fory({ maxDepth: 2 });
      expect(fory.maxDepth).toBe(2);
      const { serialize, deserialize } = fory.registerSerializer(typeInfo);
      // Should deserialize without error
      const result = deserialize(serialize({ a: 42 }));
      expect(result).toEqual({ a: 42 });
    });
  });

  describe('configuration with other options', () => {
    test('should work with refTracking enabled', () => {
      const fory = new Fory({
        maxDepth: 50,
        refTracking: true,
      });
      expect(fory.maxDepth).toBe(50);
    });

    test('should work with compatible mode enabled', () => {
      const fory = new Fory({
        maxDepth: 50,
        compatible: true,
      });
      expect(fory.maxDepth).toBe(50);
    });

    test('should work with useSliceString option', () => {
      const fory = new Fory({
        maxDepth: 50,
        useSliceString: true,
      });
      expect(fory.maxDepth).toBe(50);
    });

    test('should work with all options combined', () => {
      const fory = new Fory({
        maxDepth: 100,
        refTracking: true,
        compatible: true,
        useSliceString: true,
      });
      expect(fory.maxDepth).toBe(100);
    });
  });
});
