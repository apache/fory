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

const stylistic = require("@stylistic/eslint-plugin");

const customized = stylistic.configs.customize({
  indent: 2,
  quotes: "double",
  semi: true,
  jsx: true,
});

module.exports = {
  extends: ["eslint:recommended", "plugin:@typescript-eslint/recommended"],
  parser: "@typescript-eslint/parser",
  plugins: ["@typescript-eslint", "@stylistic"],
  root: true,
  rules: {
    ...customized.rules,
    "@stylistic/brace-style": ["error", "1tbs"],
    "@typescript-eslint/no-non-null-assertion": "off",
    "@typescript-eslint/no-explicit-any": "off",
    "@typescript-eslint/no-var-requires": "off",
  },
  ignorePatterns: ["test/*", "dist/*", "*.js", "murmurHash3.ts", "packages/**/dist/*", "packages/**/build/*", "**/typeInfo.ts"],
};
