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

import Fory, { BinaryReader } from "../packages/core/index";
import hps from "../packages/hps/index";
import { describe, expect, test } from "@jest/globals";

const skipableDescribe = hps ? describe : describe.skip;

skipableDescribe("hps", () => {
  test("should isLatin1 work", () => {
    const { serializeString } = hps!;
    for (let index = 0; index < 10000; index++) {
      const bf = Buffer.alloc(100);
      serializeString("hello", bf, 0);
      var reader = new BinaryReader({});
      reader.reset(bf);
      expect(reader.stringWithHeader()).toBe("hello");

      serializeString("😁", bf, 0);
      var reader = new BinaryReader({});
      reader.reset(bf);
      expect(reader.stringWithHeader()).toBe("😁");
    }
  });

  test("should reject strings exceeding buffer capacity", () => {
    const { serializeString } = hps!;
    const bf = Buffer.alloc(32);
    expect(() => serializeString("A".repeat(10000), bf, 0)).toThrow(RangeError);
  });

  test("should write into a view with non-zero byteOffset", () => {
    const { serializeString } = hps!;
    const backing = Buffer.alloc(200);
    const view = backing.subarray(100);
    serializeString("hello", view, 0);
    expect(backing.subarray(0, 100).every((b) => b === 0)).toBe(true);
    const reader = new BinaryReader({});
    reader.reset(view);
    expect(reader.stringWithHeader()).toBe("hello");
  });

  test("should grow writer buffer for large strings", () => {
    const fory = new Fory({ hps });
    for (const value of ["A".repeat(200000), "\u4f60".repeat(200000)]) {
      expect(fory.deserialize(fory.serialize(value))).toBe(value);
    }
  });
});
