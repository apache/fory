---
title: Cross-Language Serialization
sidebar_position: 9
id: cross_language
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

Fory Swift can exchange payloads with other Fory runtimes using the xlang protocol.

## Recommended Cross-language Configuration

```swift
let fory = Fory(xlang: true, trackRef: false, compatible: true)
```

## Register Types with Shared Identity

### ID-based registration

```swift
@ForyObject
struct Order {
    var id: Int64 = 0
    var amount: Double = 0
}

let fory = Fory(xlang: true, compatible: true)
fory.register(Order.self, id: 100)
```

### Name-based registration

```swift
try fory.register(Order.self, namespace: "com.example", name: "Order")
```

## Cross-language Rules

- Keep type registration mapping consistent across languages
- Use compatible mode when independently evolving schemas
- Register all user-defined concrete types used by dynamic fields (`Any`, `any Serializer`)

## Swift IDL Workflow

Generate Swift models directly from Fory IDL/Proto/FBS inputs:

```bash
foryc schema.fdl --swift_out ./Sources/Generated
```

Generated Swift code includes:

- `@ForyObject` models and `@ForyField(id: ...)` metadata
- Tagged union enums (associated-value enum cases)
- `ForyRegistration.register(_:)` helpers with transitive import registration
- `toBytes` / `fromBytes` helpers on generated types

Use generated registration before cross-language serialization:

```swift
let fory = Fory(xlang: true, trackRef: true, compatible: true)
try Addressbook.ForyRegistration.register(fory)

let payload = try fory.serialize(book)
let decoded: Addressbook.AddressBook = try fory.deserialize(payload)
```

### Run Swift IDL Integration Tests

```bash
cd integration_tests/idl_tests
./run_swift_tests.sh
```

This runs Swift roundtrip matrix tests and Java peer roundtrip checks (`IDL_PEER_LANG=swift`).

## Debugging Cross-language Tests

Enable debug output when running xlang tests:

```bash
ENABLE_FORY_DEBUG_OUTPUT=1 FORY_SWIFT_JAVA_CI=1 mvn -T16 test -Dtest=org.apache.fory.xlang.SwiftXlangTest
```
