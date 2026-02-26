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

import Fory from '../packages/fory/index';
import {describe, expect, test} from '@jest/globals';

const config = {};

describe('string', () => {
  test('should latin1 string work', () => {
    
    const fory = new Fory(config);    
    const input = fory.serialize("123")
    const result = fory.deserialize(
        input
    );
    expect(result).toEqual("123")
  });

  test('should utf8 string work', () => {
    
    const fory = new Fory(config);    
    const input = fory.serialize("我是Fory, 你好！😁א")
    const result = fory.deserialize(
        input
    );
    expect(result).toEqual("我是Fory, 你好！😁א")
  });

  test('should long latin1 string work', () => {
    const str = new Array(100).fill("123").join();
    const fory = new Fory(config);    
    const input = fory.serialize(str)
    const result = fory.deserialize(
        input
    );
    expect(result).toEqual(str)
  });

  test('should long utf8 string work', () => {
    const str = new Array(10).fill("我是Fory, 你好！😁א").join();
    const fory = new Fory(config);    
    const input = fory.serialize(str)
    const result = fory.deserialize(
        input
    );
    expect(result).toEqual(str)
  });

  test('should enforce maxStringBytes when configured', () => {
    const short = "abcd";
    const long = "abcdef";
    const fory = new Fory({ ...config, maxStringBytes: 4 });

    const ok = fory.serialize(short);
    expect(fory.deserialize(ok)).toEqual(short);

    const tooLong = fory.serialize(long);
    expect(() => fory.deserialize(tooLong)).toThrow(/maxStringBytes/);
  });
});
