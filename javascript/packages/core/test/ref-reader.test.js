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

"use strict";

const assert = require("node:assert/strict");
const runTest =
  typeof globalThis.expect === "function" && typeof globalThis.test === "function"
    ? globalThis.test
    : require("node:test").test;
const Fory = require("../dist/lib/fory").default;
const { RefFlags, ConfigFlags } = require("../dist/lib/type");

runTest("invalid ref id is rejected", () => {
  const fory = new Fory({ ref: true });
  const bytes = Uint8Array.from([
    ConfigFlags.isCrossLanguageFlag,
    RefFlags.RefFlag & 0xff,
    0,
  ]);

  assert.throws(() => fory.deserialize(bytes), /Invalid ref id 0, current size 0/);
});
