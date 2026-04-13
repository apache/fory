---
title: Troubleshooting
sidebar_position: 10
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

The Dart runtime only deserializes xlang frames. Make sure the peer runtime is writing xlang payloads rather than a language-native format.

- Java must use `withLanguage(Language.XLANG)`.
- Go must enable `WithXlang(true)`.
- Other peers must use their xlang-compatible path.

## `Type ... is not registered.`

Generated and manual user-defined types must be registered before reading or writing them.

Checklist:

1. Run code generation.
2. Register the type through the generated namespace or `registerSerializer`.
3. Register all nested user-defined types, not only the root type.

## Generated part file is missing or stale

Regenerate code:

```bash
cd dart/packages/fory
dart run build_runner build --delete-conflicting-outputs
```

If you moved files or renamed types, rebuild before re-running analysis or tests.

## `Deserialized value has type ..., expected ...`

`deserialize<T>` validates the runtime result after decoding. This error usually means:

- the payload describes a different root type than the requested `T`
- registration points to a different logical type than expected
- a dynamic payload should be decoded as `Object?` or a container type first

## Unexpected reference identity behavior

Check whether reference tracking is enabled in the correct place:

- root graph or root container: `trackRef: true`
- generated field graph: `@ForyField(ref: true)`
- customized serializer: use `writeRef`, `readRef`, and `context.reference(...)` correctly

`writeNonRef` intentionally does not seed later back-references.

## Cross-language field mismatch

Symptoms include missing data, default values, or type errors on peers.

Check:

- same registration identity on both sides
- stable field IDs for evolving structs
- matching logical field names and meanings
- compatible numeric widths and temporal types

## Validation Commands

For the main Dart workspace:

```bash
cd dart
dart run build_runner build --delete-conflicting-outputs
dart analyze
dart test
```

For the integration-style test package:

```bash
cd dart/packages/fory-test
dart run build_runner build --delete-conflicting-outputs
dart test
```

## Related Topics

- [Cross-Language](cross-language.md)
- [Code Generation](code-generation.md)
- [Custom Serializers](custom-serializers.md)
