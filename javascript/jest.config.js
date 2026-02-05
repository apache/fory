/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/** @type {import('ts-jest').JestConfigWithTsJest} */

module.exports = {
  collectCoverage: true,
  preset: "ts-jest",
  testEnvironment: "node",
  collectCoverageFrom: [
    "**/*.ts",
    "!**/dist/**",
    "!**/build/**",
    "!packages/fory/lib/murmurHash3.ts",
  ],
  testPathIgnorePatterns: [
    "/node_modules/",
    "/dist/",
    "/build/",
    // Cross-language tests are explicitly enabled by not adding them here
  ],
  transform: {
    "\\.ts$": [
      "ts-jest",
      {
        tsconfig: {
          target: "ES2021",
          experimentalDecorators: true,
          emitDecoratorMetadata: true, // Ensures SimpleStruct decorators work correctly
        },
        diagnostics: {
          ignoreCodes: [151001],
        },
      },
    ],
  },
};
