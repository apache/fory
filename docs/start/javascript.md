---
title: JavaScript/TypeScript Setup
sidebar_position: 6
id: javascript
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Fory JavaScript/TypeScript provides xlang Object Serialization, generated
models, Node.js gRPC, and browser gRPC-Web clients. Packages are published on
npm. The core package works without native acceleration; the optional
`@apache-fory/hps` Node.js fast path requires Node.js 20 or later.

## Verify the Toolchain

```bash
node --version
npm --version
```

## Object Serialization

Install the core package:

```bash
npm install @apache-fory/core@1.6.1
```

Define a schema and run an xlang round trip:

```js title="example.cjs"
const { default: Fory, Type } = require("@apache-fory/core");

const userType = Type.struct(
  { typeName: "example.User" },
  {
    id: Type.int64(),
    name: Type.string(),
  },
);

const fory = new Fory();
const { serialize, deserialize } = fory.register(userType);

const bytes = serialize({ id: 1n, name: "Alice" });
console.log(deserialize(bytes));
```

```bash
node example.cjs
```

JavaScript uses xlang mode. Continue with
[JavaScript/TypeScript Object Serialization](../object-serialization/javascript/index.md),
[xlang types](../object-serialization/javascript/basic-serialization.md#cross-language-interoperability),
[configuration](../object-serialization/javascript/configuration.md), and
[schema evolution](../object-serialization/javascript/schema-evolution.md).

For the optional Node.js string fast path, install the matching package version:

```bash
npm install @apache-fory/core@1.6.1 @apache-fory/hps@1.6.1
```

## Other Capabilities

- **Fory IDL and Compiler** generates TypeScript interfaces, schemas, and registration helpers. See [Compiler Getting Started](../compiler/getting-started.md) and the [JavaScript generated-code guide](../compiler/generated-code/javascript.md).
- **Fory gRPC** supports Node.js gRPC and browser gRPC-Web transports with Fory-encoded messages. See [JavaScript gRPC](../grpc/javascript.md).
