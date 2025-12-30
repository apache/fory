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

import Fory, { TypeInfo, InternalSerializerType, Type } from '../../packages/fory/index';
import { describe, expect, test } from '@jest/globals';


describe('protocol', () => {
    test('should polymorphic work', () => {
        
        const fory = new Fory({ refTracking: true });
        const { serialize, deserialize } = fory.registerSerializer(Type.struct({
            typeName: "example.foo"
        }, {
            foo: Type.string(),
            bar: Type.int32(),
            map: Type.map(Type.any(), Type.any()),
            set: Type.set(Type.any()),
            list: Type.array(Type.any()),
        }));
        const obj = {
            foo: "123",
            bar: 123,
            map: new Map([["hello", 1], ["world", 2]]),
            set: new Set([1, 2, "123"]),
            list: ["123", 123, true]
        };
        const bf = serialize(obj);
        const result = deserialize(bf);
        expect(result).toEqual(obj);
    });

    test('should enforce nullable flag for schema-based structs', () => {
        const fory = new Fory();

        // 1) nullable: false => null must throw
        const nonNullable = Type.struct({
            typeName: "example.nonNullable"
        }, {
            a: Object.assign(Type.string(), { nullable: false }),
        });
        const nonNullableSer = fory.registerSerializer(nonNullable);
        expect(() => nonNullableSer.serialize({ a: null })).toThrow(/Field 'a' is not nullable/);

        // 2) nullable not specified => keep old behavior (null allowed)
        const nullableUnspecified = Type.struct({
            typeName: "example.nullableUnspecified"
        }, {
            a: Type.string(),
        });
        const { serialize, deserialize } = fory.registerSerializer(nullableUnspecified);
        expect(deserialize(serialize({ a: null }))).toEqual({ a: null });
    });
});


