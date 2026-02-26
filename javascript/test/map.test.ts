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
import {describe, expect, test} from '@jest/globals';

describe('map', () => {
  test('should map work', () => {
    
    const fory = new Fory({ refTracking: true });    
    const input = fory.serialize(new Map([["foo", "bar"], ["foo2", "bar2"]]));
    const result = fory.deserialize(
        input
    );
    expect(result).toEqual(new Map([["foo", "bar"],["foo2", "bar2"]]))
  });
  
  test('should map specific type work', () => {
    
    const fory = new Fory({ refTracking: true });  
    const { serialize, deserialize } = fory.registerSerializer(Type.struct("class.foo", {
      f1: Type.map(Type.string(), Type.varInt32())
    }))  
    const bin = serialize({
      f1: new Map([["hello", 123], ["world", 456]]),
    })
    const result = deserialize(bin);
    expect(result).toEqual({ f1: new Map([["hello", 123],["world", 456]])})
  });

  test('should enforce maxMapEntries when configured', () => {
    const fory = new Fory({ refTracking: true, maxMapEntries: 1 });

    const okInput = fory.serialize(new Map([["foo", "bar"]]));
    expect(fory.deserialize(okInput)).toEqual(new Map([["foo", "bar"]]));

    const tooManyInput = fory.serialize(new Map([["foo", "bar"], ["baz", "qux"]]));
    expect(() => fory.deserialize(tooManyInput)).toThrow(/maxMapEntries/);
  });
});


