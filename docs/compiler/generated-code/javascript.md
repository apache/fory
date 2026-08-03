---
title: JavaScript/TypeScript
sidebar_position: 8
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

## Output Layout

JavaScript/TypeScript output is one `.ts` file per schema, for example:

- `<javascript_out>/addressbook.ts`

When the schema contains services, JavaScript can also emit service companions:

- `<javascript_out>/addressbook_grpc.ts` with `--grpc`
- `<javascript_out>/addressbook_grpc_web.ts` with `--grpc-web`

## Type Generation

Messages generate `export interface` declarations with camelCase field names:

```typescript
export interface Person {
  name: string;
  id: number;
  phones: PhoneNumber[];
  pet?: Animal | null;
}
```

Enums generate `export enum` declarations:

```typescript
export enum PhoneType {
  MOBILE = 0,
  HOME = 1,
  WORK = 2,
}
```

Unions generate a discriminated union with a case enum:

```typescript
export enum AnimalCase {
  DOG = 1,
  CAT = 2,
}

export type Animal =
  { case: AnimalCase.DOG; value: Dog } | { case: AnimalCase.CAT; value: Cat };
```

## Schema Helpers

Each generated model file exports a registration helper for custom `Fory`
instances and root serialization helpers. The public API looks like:

```typescript
import type Fory, { Serializer } from "@apache-fory/core";

export function registerAddressbookTypes(fory: Fory): {
  person: {
    serialize: (value: Person | null) => Uint8Array;
    deserialize: (bytes: Uint8Array) => Person;
    serializer: Serializer;
  };
};
export const serializePerson: (value: Person | null) => Uint8Array;
export const deserializePerson: (bytes: Uint8Array) => Person;
```

Imported schema modules are registered automatically by `registerXxxTypes(fory)`.
Use `serializeX` and `deserializeX` for the generated default serialization
path. Call `registerXxxTypes(fory)` when the application manages its own `Fory`
instance. Generated gRPC companions import the generated helpers automatically.

## gRPC Service Companions

`--grpc` emits `<module>_grpc.ts` with service and path constants, handler interfaces, service-definition and registration helpers, and both `<Service>Client` classes and `create<Service>Client` factories. `--grpc-web` emits `<module>_grpc_web.ts` with callback clients and, for unary RPCs, promise clients; both classes and factory functions are exported. See [JavaScript gRPC](../../grpc/javascript.md) for Node.js and browser usage.
