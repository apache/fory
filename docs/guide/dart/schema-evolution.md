---
title: Schema Evolution
sidebar_position: 8
id: schema_evolution
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

Schema evolution lets different versions of your app exchange messages safely — a v2 writer can produce a message that a v1 reader can still decode, and vice versa.

## Compatible Mode

Compatible mode is the Dart default. Keep this default when services may run different versions at
the same time, for example during a rolling deployment or when clients are not updated immediately.

```dart
final fory = Fory();
```

In compatible mode, Fory includes enough field metadata in each message so that the reader can skip unknown fields and use defaults for missing ones. Use stable field IDs (see below) to anchor the schema across changes.

Compatible readers also tolerate selected scalar field type changes when the value is lossless. A
matched field can read between `bool`, `String`, numeric scalars, and `Decimal` when the converted
value has the same logical value. For example, `"true"` and `"false"` can be read as booleans,
`"123"` can be read as a numeric field that can hold `123`, numbers and decimals can be read as
canonical strings, and numeric widening or narrowing succeeds only when no precision or range is
lost. Scalar conversion applies only to matched compatible fields, not root values or collection
elements. String-to-number conversion accepts finite ASCII decimal literals without whitespace, a
leading `+`, Unicode digits, underscores, `NaN`, or `Infinity`. Nullable fields still compose with
these conversions, but reference-tracked scalar type changes are incompatible. Invalid strings,
out-of-range values, and lossy conversions fail with `InvalidDataException` during deserialization.

## Setting Up for Evolution

To use compatible mode safely, mark your structs with `@ForyStruct(evolving: true)` (the default) and assign a stable `@ForyField(id: ...)` to every field **before you ship your first payload**:

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

If you add field IDs after payloads are already in production, existing stored messages won't have them and evolution won't work correctly.

## What You Can Safely Change

**Safe changes** (compatible on both sides):

- Add a new optional field with a new, unused field ID.
- Rename a field — as long as the `@ForyField(id: ...)` stays the same.
- Remove a field — the peer will just ignore the missing value and use the Dart default.
- Change selected scalar field types when all deployed values convert without precision or range
  loss.

**Unsafe changes** (may break existing messages):

- Reuse an existing field ID for a different field.
- Change a field's type to an incompatible type or to a scalar type that cannot represent the peer
  values exactly.
- Change the registration identity (`id` or `name`) of a type after messages are in production.
- Change a field's logical meaning without changing its ID.

## Xlang Notes

Evolution only works when **all** peers that exchange messages agree on:

1. The same `compatible` setting.
2. The same type registration identity (numeric ID or `name`).
3. The logical meaning of field IDs.

Test rolling-upgrade scenarios with real round trips before deploying.

## Same-Schema Optimization

Use `compatible: false` only when every reader and writer always uses the same schema and you want faster serialization and smaller size. For xlang payloads, set `compatible: false` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

```dart
final fory = Fory(compatible: false);
```

## Related Topics

- [Configuration](configuration.md)
- [Schema Metadata](schema-metadata.md)
- [Xlang Serialization](xlang-serialization.md)
