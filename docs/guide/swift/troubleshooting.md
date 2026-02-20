---
title: Troubleshooting
sidebar_position: 11
id: troubleshooting
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

This page covers common Swift issues and how to debug them.

## Common Runtime Errors

### `Type not registered: ...`

Cause: user type was not registered on the current `Fory` instance.

Fix:

```swift
fory.register(MyType.self, id: 100)
```

### `Type mismatch: expected ..., got ...`

Cause: registration mapping or field type info differs across peers.

Fix:

- Ensure both sides register the same type ID/name mapping
- Verify field type compatibility

### `Invalid data: xlang bitmap mismatch`

Cause: serializer and deserializer use different `xlang` settings.

Fix: configure both sides with matching `xlang` mode.

### `Invalid data: class version hash mismatch`

Cause: schema changed while `compatible=false`.

Fix:

- Enable compatible mode for evolving schemas
- Or keep strict schema parity with schema-consistent mode

## Common Macro-time Errors

### `@ForyObject requires explicit types for stored properties`

Add explicit type annotations to stored properties.

### `@ForyObject enum associated values cannot have default values`

Remove default values from enum case associated values.

### `Set<...> with Any elements is not supported by @ForyObject yet`

Use `[Any]` or a typed set instead.

### `Dictionary<..., ...> with Any values is only supported for String, Int32, or AnyHashable keys`

Switch key type to `String`, `Int32`, or `AnyHashable`, or avoid dynamic `Any` map values.

## Debugging Commands

Run Swift tests:

```bash
cd swift
ENABLE_FORY_DEBUG_OUTPUT=1 swift test
```

Run Java-driven Swift xlang tests:

```bash
cd java/fory-core
ENABLE_FORY_DEBUG_OUTPUT=1 FORY_SWIFT_JAVA_CI=1 mvn -T16 test -Dtest=org.apache.fory.xlang.SwiftXlangTest
```
