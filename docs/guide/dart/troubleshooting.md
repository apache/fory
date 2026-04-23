---
title: Troubleshooting
sidebar_position: 11
id: dart_troubleshooting
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

This page covers common Dart runtime issues and fixes.

## `Only xlang payloads are supported by the Dart runtime.`

The writer is sending a native-mode (non-xlang) payload. Make sure every service uses the xlang-compatible path:

- **Java**: add `.withLanguage(Language.XLANG)` to the Fory builder.
- **Go**: use `WithXlang(true)` in the Fory options.
- **Other runtimes**: check their respective guides for enabling cross-language mode.

## `Type ... is not registered.`

Fory does not know how to serialize or deserialize this type. Fix it by:

1. Running code generation if you haven't: `dart run build_runner build --delete-conflicting-outputs`
2. Calling the generated `register` function (or `registerSerializer`) for the type **before** calling `serialize` or `deserialize`.
3. Registering **all** types that appear in a message, not just the root type. For example, if `Order` contains an `Address`, register both.

## Generated part file is missing or stale

Regenerate code:

```bash
cd dart/packages/fory
dart run build_runner build --delete-conflicting-outputs
```

If you moved files or renamed types, rebuild before re-running analysis or tests.

## `Deserialized value has type ..., expected ...`

The payload describes a different type than `T` in `deserialize<T>`. Common causes:

- You registered the type on the writing side with a different ID or name than on the reading side.
- The payload was produced by a different code path that serializes a different root object.
- You are trying to deserialize a heterogeneous container — decode it as `Object?` or `List<Object?>` first, then cast.

## Objects aren't the same instance after deserialization

Fory does not track object identity by default, so two fields pointing to the same object will produce two independent copies after a round trip.

To preserve identity:

- For fields inside a `@ForyStruct`, add `@ForyField(ref: true)` to those fields.
- For a top-level collection, pass `trackRef: true` to `fory.serialize(...)`.
- In a custom serializer, use `context.writeRef` / `context.readRef` and call `context.reference(obj)` before reading nested fields.

## Cross-language field mismatch (missing data or wrong values)

Symptoms: fields come back as default values or wrong types after a round trip to another language.

Checklist:

1. Same registration identity on both sides (same numeric ID **or** same `namespace + typeName`).
2. Stable `@ForyField(id: ...)` assigned before the first payload was produced.
3. Compatible numeric widths — use `Int32` in Dart when the peer field is `int` (Java), `int32` (Go), or `int` (C#).
4. `Timestamp` / `LocalDate` instead of raw `DateTime` for date/time fields.
5. `compatible: true` on **both** sides if using schema evolution.

## Int64 or Uint64 values fail on web

On Dart VM builds, Dart `int` can represent signed 64-bit values. On Dart web
builds, Dart `int` values are backed by JavaScript numbers and are only precise
inside the JS-safe integer range:

```text
-9007199254740991 <= value <= 9007199254740991
```

If a generated serializer writes an `int64` field declared as Dart `int`,
web builds reject values outside that range instead of silently writing
corrupted bytes. To exchange full signed 64-bit values on web, declare the
field as Fory's `Int64` wrapper:

```dart
@ForyStruct()
class LedgerEntry {
  LedgerEntry();

  Int64 sequence = Int64(0); // full signed 64-bit range on VM and web
}
```

For unsigned 64-bit values, prefer `Uint64` rather than Dart `int`. Dart `int`
cannot represent the full `uint64` range on either VM or web:

```dart
@ForyStruct()
class FileBlock {
  FileBlock();

  Uint64 offset = Uint64(0); // full unsigned 64-bit range
}
```

`@Int64Type` changes the wire encoding for a Dart `int` field, but it does not
remove the web integer precision limit. Use `Int64` for full-range signed
values and `Uint64` for full-range unsigned values. See
[Web Platform Support](web-platform-support.md) for the full browser support
matrix and migration guidance.

## Running Tests Locally

Main Dart package:

```bash
dart run build_runner build --delete-conflicting-outputs
dart analyze
dart test
```

Integration test package:

```bash
cd dart/packages/fory-test
dart run build_runner build --delete-conflicting-outputs
dart test
```

## Related Topics

- [Cross-Language](cross-language.md)
- [Code Generation](code-generation.md)
- [Custom Serializers](custom-serializers.md)
- [Web Platform Support](web-platform-support.md)
