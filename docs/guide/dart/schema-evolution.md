---
title: Schema Evolution
sidebar_position: 8
id: dart_schema_evolution
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

Fory Dart supports schema evolution through compatible structs.

## Two Struct Modes

### Schema-Consistent Mode

This is the default when `Config.compatible` is `false`.

- Structs use the schema-consistent path.
- `checkStructVersion` can validate schema-version compatibility.
- This mode is appropriate when both sides evolve in lockstep or when you want stricter validation.

### Compatible Mode

Enable compatible mode when you want evolving field metadata on the wire.

```dart
final fory = Fory(compatible: true);
```

In compatible mode:

- evolving structs use compatible struct encoding
- `checkStructVersion` is disabled
- field IDs become the stability anchor for changed schemas
- TypeDef metadata is shared and reused across payloads

## Generated Struct Controls

`@ForyStruct(evolving: true)` is the default and is the right choice when the type may evolve over time.

```dart
@ForyStruct(evolving: true)
class UserProfile {
  UserProfile();

  @ForyField(id: 1)
  String name = '';

  @ForyField(id: 2, nullable: true)
  String? nickname;
}
```

Use explicit stable field IDs when a schema may change.

## Safe Evolution Practices

Typical compatible changes include:

- adding a new optional field with a new field ID
- keeping existing field IDs stable
- widening consumer behavior to tolerate missing data

Changes that require extra care include:

- reusing an old field ID for different semantics
- changing a field's logical cross-language meaning
- changing registration identity for an existing type

## Cross-Language Guidance

Schema evolution only works when all runtimes agree on the type registration identity and the logical meaning of field IDs. Validate rolling upgrades with real round trips across the languages you support.

## Related Topics

- [Configuration](configuration.md)
- [Field Configuration](field-configuration.md)
- [Cross-Language](cross-language.md)
