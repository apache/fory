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

describe('union', () => {
  test('should union with string case roundtrip', () => {
    const fory = new Fory({ refTracking: true });
    const { serialize, deserialize } = fory.register(Type.struct(100, {
      payload: Type.union({
        1: Type.string(),
        2: Type.varInt32(),
      }).setId(1),
    }));
    const input = { payload: { case: 1, value: "hello" } };
    const result = deserialize(serialize(input));
    expect(result).toEqual(input);
  });

  test('should union with int case roundtrip', () => {
    const fory = new Fory({ refTracking: true });
    const { serialize, deserialize } = fory.register(Type.struct(200, {
      payload: Type.union({
        1: Type.string(),
        2: Type.varInt32(),
      }).setId(1),
    }));
    const input = { payload: { case: 2, value: 42 } };
    const result = deserialize(serialize(input));
    expect(result).toEqual(input);
  });

  test('should union with struct case roundtrip', () => {
    const fory = new Fory({ refTracking: true });
    fory.register(Type.struct(301, {
      text: Type.string().setId(1),
    }));
    const { serialize, deserialize } = fory.register(Type.struct(300, {
      payload: Type.union({
        1: Type.struct(301),
        2: Type.varInt32(),
      }).setId(1),
    }));
    const input = { payload: { case: 1, value: { text: "a note" } } };
    const result = deserialize(serialize(input));
    expect(result).toEqual(input);
  });

  test('should nullable union roundtrip with value', () => {
    const fory = new Fory({ refTracking: true });
    const { serialize, deserialize } = fory.register(Type.struct(400, {
      payload: Type.union({
        1: Type.string(),
      }).setNullable(true).setId(1),
    }));
    const input = { payload: { case: 1, value: "hello" } };
    const result = deserialize(serialize(input));
    expect(result).toEqual(input);
  });

  test('should nullable union roundtrip with null', () => {
    const fory = new Fory({ refTracking: true });
    const { serialize, deserialize } = fory.register(Type.struct(500, {
      payload: Type.union({
        1: Type.string(),
      }).setNullable(true).setId(1),
    }));
    const input = { payload: null };
    const result = deserialize(serialize(input));
    expect(result).toEqual(input);
  });

  test('should named union roundtrip', () => {
    const fory = new Fory({ refTracking: true });
    fory.register(Type.struct({ namespace: "test", typeName: "Msg" }, {
      text: Type.string(),
    }));
    const { serialize, deserialize } = fory.register(Type.struct({ namespace: "test", typeName: "Wrapper" }, {
      payload: Type.union({ namespace: "test", typeName: "MyUnion" }, {
        1: Type.string(),
        2: Type.struct({ namespace: "test", typeName: "Msg" }),
      }),
    }));
    const input = { payload: { case: 1, value: "hello" } };
    const result = deserialize(serialize(input));
    expect(result).toEqual(input);
  });

  test('should named union with struct case roundtrip', () => {
    const fory = new Fory({ refTracking: true });
    fory.register(Type.struct({ namespace: "test", typeName: "Note" }, {
      text: Type.string(),
    }));
    const { serialize, deserialize } = fory.register(Type.struct({ namespace: "test", typeName: "Holder" }, {
      payload: Type.union({ namespace: "test", typeName: "ContentUnion" }, {
        1: Type.struct({ namespace: "test", typeName: "Note" }),
        2: Type.varInt32(),
      }),
    }));
    const input = { payload: { case: 1, value: { text: "a note" } } };
    const result = deserialize(serialize(input));
    expect(result).toEqual(input);
  });

  test('should union alongside other fields roundtrip', () => {
    const fory = new Fory({ refTracking: true });
    fory.register(Type.struct(601, {
      value: Type.float64().setId(1),
    }));
    const { serialize, deserialize } = fory.register(Type.struct(600, {
      id: Type.varInt32().setId(1),
      name: Type.string().setId(2),
      detail: Type.union({
        1: Type.string(),
        2: Type.struct(601),
      }).setId(3),
    }));
    const input = {
      id: 7,
      name: "test",
      detail: { case: 2, value: { value: 3.14 } },
    };
    const result = deserialize(serialize(input));
    expect(result).toEqual(input);
  });
});
