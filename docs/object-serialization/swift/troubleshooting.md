---
title: Troubleshooting
sidebar_position: 90
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

Cause: the selected user serializer was not registered on the current `Fory`
instance.

Fix:

```swift
try fory.register(MyType.self, id: 100)
```

For an external target, register its serializer:

```swift
try fory.register(UserSerializer.self, id: 100)
```

### `Type mismatch: expected ..., got ...`

Cause: registration mapping or field type info differs across peers.

Fix:

- Ensure both sides register the same type ID/name mapping
- Verify field type compatibility

### `Invalid data: xlang bitmap mismatch`

Cause: the input was produced by a peer that did not write the xlang
wire format Swift expects.

Fix: configure the peer serializer to write xlang format. Swift already uses
xlang format and has no native-mode switch.

### `Invalid data: class version hash mismatch`

Cause: schema changed while `compatible: false`.

Fix:

- Keep compatible mode enabled for evolving schemas.
- Or use `compatible: false` only when every reader and writer uses the same schema.

## Common Macro-time Errors

### `@ForyStruct requires explicit types for stored properties`

Add explicit type annotations to stored properties.

### `Fory enum associated values cannot have default values`

Remove default values from enum case associated values.

### Selected serializer target does not match the field type

The serializer selected by `with` must target the exact field node. Select the
matching carrier for optional or collection fields:

```swift
@ForyField(with: OptionalSerializer<UserSerializer>.self)
var user: ThirdParty.User?
```

### External target construction errors

An external struct needs readable matching properties and an accessible
matching initializer. An external class needs an accessible zero-argument
initializer and writable matching properties.

Use a [custom serializer](custom-serializers.md) when the target does not expose
that construction surface.

### Union case has multiple associated values

Swift uses the xlang union format, whose known cases contain zero or one value.
Move multiple logical fields into an explicit `@ForyStruct` payload.

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
