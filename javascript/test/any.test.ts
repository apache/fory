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

describe('bool', () => {
    test('should write null work', () => {
        const fory = new Fory();
        const bin = fory.serialize(null);
        expect(fory.deserialize(bin)).toBe(null)
    });
    test('should write undefined work', () => {
        const fory = new Fory();
        const bin = fory.serialize(undefined);
        expect(fory.deserialize(bin)).toBe(null)
    });

    test('should write number work', () => {
        const fory = new Fory();
        const bin = fory.serialize(123);
        expect(fory.deserialize(bin)).toBe(123)
    });

    test('should write NaN work', () => {
        const fory = new Fory();
        const bin = fory.serialize(NaN);
        expect(fory.deserialize(bin)).toBe(NaN)
    });

    test('should write big number work', () => {
        const fory = new Fory();
        const bin = fory.serialize(3000000000);
        expect(fory.deserialize(bin)).toBe(3000000000n);
    });

    test('should write INFINITY work', () => {
        const fory = new Fory();
        const bin = fory.serialize(Number.NEGATIVE_INFINITY);
        expect(fory.deserialize(bin)).toBe(Number.NEGATIVE_INFINITY)
    });

    test('should write float work', () => {
        const fory = new Fory();
        const bin = fory.serialize(123.123);
        expect(fory.deserialize(bin).toFixed(3)).toBe("123.123")
    });

    test('should write bigint work', () => {
        const fory = new Fory();
        const bin = fory.serialize(BigInt(123));
        expect(fory.deserialize(bin)).toBe(BigInt(123))
    });

    test('should write true work', () => {
        const fory = new Fory();
        const bin = fory.serialize(true);
        expect(fory.deserialize(bin)).toBe(true)
    });

    test('should write false work', () => {
        const fory = new Fory();
        const bin = fory.serialize(false);
        expect(fory.deserialize(bin)).toBe(false)
    });

    test('should write date work', () => {
        const fory = new Fory();
        const dt = new Date();
        const bin = fory.serialize(dt);
        const ret = fory.deserialize(bin);
        expect(ret instanceof Date).toBe(true)
        expect(ret.toUTCString()).toBe(dt.toUTCString())
    });

    test('should write string work', () => {
        const fory = new Fory();
        const bin = fory.serialize("hello");
        expect(fory.deserialize(bin)).toBe("hello")
    });

    test('should write map work', () => {
        const fory = new Fory();
        const obj = new Map([[1, 2], [3, 4]]);
        const bin = fory.serialize(obj);
        const ret = fory.deserialize(bin);
        expect(ret instanceof Map).toBe(true)
        expect([...ret.values()]).toEqual([...obj.values()])
        expect([...ret.keys()]).toEqual([...obj.keys()])
    });

    test('should root any work', () => {
        const fory = new Fory();
        const { serialize, deserialize } = fory.registerSerializer(Type.any());
        const bin = serialize("hello");
        const result = deserialize(bin);
        expect(result).toEqual("hello")
    });
});
