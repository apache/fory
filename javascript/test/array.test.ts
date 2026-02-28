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

import Fory, { Type, BFloat16Array } from '../packages/fory/index';
import { describe, expect, test } from '@jest/globals';
import * as beautify from 'js-beautify';

describe('array', () => {
  test('should array work', () => {


    const typeinfo = Type.struct({
      typeName: "example.bar"
    }, {
      c: Type.array(Type.struct({
        typeName: "example.foo"
      }, {
        a: Type.string()
      }))
    });
    const fory = new Fory({ refTracking: true, hooks: {
        afterCodeGenerated: (code: string) => {
          return beautify.js(code, { indent_size: 2, space_in_empty_paren: true, indent_empty_lines: true });
        }
    } });
    const { serialize, deserialize } = fory.registerSerializer(typeinfo);
    const o = { a: "123" };
    expect(deserialize(serialize({ c: [o, o] }))).toEqual({ c: [o, o] })
  });
  test('should typedarray work', () => {
    const typeinfo = Type.struct({
      typeName: "example.foo",
    }, {
      a: Type.boolArray(),
      a2: Type.int16Array(),
      a3: Type.int32Array(),
      a4: Type.int64Array(),
      a6: Type.float64Array()
    });

    const fory = new Fory({ refTracking: true }); 
    const serializer = fory.registerSerializer(typeinfo).serializer;
    const input = fory.serialize({
      a: [true, false],
      a2: new Int16Array([1, 2, 3]),
      a3: new Int32Array([3, 5, 76]),
      a4: new BigInt64Array([634n, 564n, 76n]),
      a6: new Float64Array([234243.555, 55654.679]),
    }, serializer);
    const result = fory.deserialize(
      input
    );
    expect(result).toEqual({
      a: [true, false],
      a2: new Int16Array([1, 2, 3]),
      a3: new Int32Array([3, 5, 76]),
      a4: new BigInt64Array([634n, 564n, 76n]),
      a6: new Float64Array([234243.555, 55654.679]),
    })
  });


  test('should floatarray work', () => {
    const typeinfo = Type.struct({
      typeName: "example.foo"
    }, {
      a5: Type.float32Array(),
    })

    const fory = new Fory({ refTracking: true }); const serialize = fory.registerSerializer(typeinfo).serializer;
    const input = fory.serialize({
      a5: new Float32Array([2.43, 654.4, 55]),
    }, serialize);
    const result = fory.deserialize(
      input
    );
    expect(result.a5[0]).toBeCloseTo(2.43)
    expect(result.a5[1]).toBeCloseTo(654.4)
    expect(result.a5[2]).toBeCloseTo(55)
  });

  test('should float16Array work', () => {
    const typeinfo = Type.struct({
      typeName: "example.foo"
    }, {
      a6: Type.float16Array(),
    })

    const fory = new Fory({ refTracking: true }); const serialize = fory.registerSerializer(typeinfo).serializer;
    const input = fory.serialize({
      a6: [1.5, 2.5, -4.5],
    }, serialize);
    const result = fory.deserialize(
      input
    );
    expect(result.a6[0]).toBeCloseTo(1.5, 1)
    expect(result.a6[1]).toBeCloseTo(2.5, 1)
    expect(result.a6[2]).toBeCloseTo(-4.5, 1)
  });

  test('should bfloat16Array work', () => {
    const typeinfo = Type.struct({
      typeName: "example.foo"
    }, {
      a7: Type.bfloat16Array(),
    });
    const fory = new Fory({ refTracking: true });
    const serialize = fory.registerSerializer(typeinfo).serializer;
    const input = fory.serialize({
      a7: [1.5, 2.5, -4.5],
    }, serialize);
    const result = fory.deserialize(input);
    expect(result.a7).toHaveLength(3);
    expect(result.a7[0].toFloat32()).toBeCloseTo(1.5, 2);
    expect(result.a7[1].toFloat32()).toBeCloseTo(2.5, 2);
    expect(result.a7[2].toFloat32()).toBeCloseTo(-4.5, 2);
  });

  test('should bfloat16Array accept BFloat16Array', () => {
    const typeinfo = Type.struct({
      typeName: "example.foo"
    }, {
      a7: Type.bfloat16Array(),
    });
    const fory = new Fory({ refTracking: true });
    const serialize = fory.registerSerializer(typeinfo).serializer;
    const arr = new BFloat16Array([1.25, -2.5, 0]);
    const input = fory.serialize({ a7: arr }, serialize);
    const result = fory.deserialize(input);
    expect(result.a7).toHaveLength(3);
    expect(result.a7[0].toFloat32()).toBeCloseTo(1.25, 2);
    expect(result.a7[1].toFloat32()).toBeCloseTo(-2.5, 2);
    expect(result.a7[2].toFloat32()).toBe(0);
  });
});


