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

import {
  fromUint8Array,
  alloc,
  BrowserBuffer,
  PlatformBuffer,
} from "../packages/fory/lib/platformBuffer";
import { describe, expect, test } from "@jest/globals";

describe("platformBuffer", () => {
  test("should fromUint8Array work", () => {
    const bf = fromUint8Array(new Uint8Array([1, 2, 3]));
    expect(Buffer.isBuffer(bf)).toBe(true);

    const bf2 = fromUint8Array(Buffer.from([1, 2, 3]));
    expect(Buffer.isBuffer(bf2)).toBe(true);
  });

  test("should alloc work", () => {
    const bf = alloc(10);
    expect(bf.byteLength).toBe(10);

    const bf2 = BrowserBuffer.alloc(10);
    expect(bf2.byteLength).toBe(10);
  });

  test("should latin1Write work", () => {
    const bb = BrowserBuffer.alloc(100);
    bb.write("hello, world", 0, "latin1");

    const str = bb.toString("latin1", 0, 12);
    expect(str).toBe("hello, world");
  });

  test("should utf8Write work", () => {
    const rawStr = "我是Fory, 你好！😁א";
    const bb = BrowserBuffer.alloc(100);
    bb.write(rawStr, 0);

    const str = bb.toString("utf8", 0, 27);
    expect(str).toBe(rawStr);
  });

  test("should utf8 work", () => {
    const rawStr = "我是Fory, 你好！😁א";
    const bb = BrowserBuffer.alloc(100);
    bb.write(rawStr, 0, "utf8");

    const str2 = bb.toString("utf8", 0, 27);
    expect(str2).toBe(rawStr);
  });

  test("should byteLength work", () => {
    expect(BrowserBuffer.byteLength("hello, world")).toBe(12);
    expect(BrowserBuffer.byteLength("我是Fory, 你好！😁א")).toBe(27);
  });

  test("should copy work", () => {
    const bb = BrowserBuffer.alloc(100);
    bb.write("hello", 0, "latin1");
    const target = new Uint8Array(5);
    bb.copy(target, 0, 0, 5);
    expect([...target]).toEqual([104, 101, 108, 108, 111]);
  });

  test("Buffer can be assigned to PlatformBuffer", () => {
    const cc = Buffer.alloc(20);
    cc.write("hello", 0, "latin1");
    const bb: PlatformBuffer = cc;
    expect(bb.toString("latin1", 0, 5)).toBe("hello");
    bb.write("world", 5, "latin1");
    expect(bb.toString("latin1", 0, 10)).toBe("helloworld");
  });
});
