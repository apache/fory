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
import { beforeAll, describe, expect, test } from "@jest/globals";

const { engines } = require("../packages/hps/package.json");
const skipableDescribe = require("semver").satisfies(process.version, engines.node)
  ? describe
  : describe.skip;

skipableDescribe("hps", () => {
  beforeAll(() => {
    // A missing addon on a supported Node.js version must fail, not skip these tests.
    expect(hps).not.toBeNull();
  });

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

  test.each([-1, -0.5, 0.5, NaN, Infinity, -Infinity, 2 ** 32])(
    "should reject invalid offset %s",
    (offset) => {
      const bf = Buffer.alloc(32, 0x7f);
      expect(() => hps!.serializeString("A", bf, offset)).toThrow(RangeError);
      expect(bf.every((b) => b === 0x7f)).toBe(true);
    },
  );

  test.each(["hello", "\u4f60\u597d", "😁"])(
    "should write %s into a view with non-zero byteOffset",
    (value) => {
      const { serializeString } = hps!;
      // Exercise both alignments of the UTF-16 body in the native slow callback.
      for (const offset of [0, 1]) {
        const backing = Buffer.alloc(200);
        const view = backing.subarray(101, 190);
        const end = serializeString(value, view, offset);
        expect(backing.subarray(0, 101 + offset).every((b) => b === 0)).toBe(true);
        expect(backing.subarray(101 + end).every((b) => b === 0)).toBe(true);
        const reader = new BinaryReader({});
        reader.reset(view.subarray(offset, end));
        expect(reader.stringWithHeader()).toBe(value);
      }
    },
  );

  test("should grow writer buffer for large strings", () => {
    const fory = new Fory({ hps });
    for (const value of ["A".repeat(200000), "\u4f60".repeat(200000)]) {
      expect(fory.deserialize(fory.serialize(value))).toBe(value);
    }
  });
});
