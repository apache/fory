---
title: Schema Metadata
sidebar_position: 5
id: schema_metadata
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

Add `@ForyField(...)` to a field inside a `@ForyStruct()` class to change how that field is serialized.

## Quick Reference

```dart
@ForyField(
  skip: false,      // include the field; set true to exclude it
  id: 10,           // stable field ID for schema evolution
  nullable: true,   // override nullability detection
  ref: true,        // enable reference tracking for this field
  dynamic: false,   // control whether the concrete type is written
)
```

## `skip`

Exclude a field from serialization entirely. Useful for cached, computed, or UI-only values that should not land in a persisted or transmitted message.

```dart
@ForyField(skip: true)
String cachedDisplayName = '';
```

## `id`

Assigns a stable identity to the field so that Fory can match it by ID after a schema change (a field rename or reorder). **If you plan to add, remove, or rename fields in the future, assign IDs to all fields now** — before you ship the first payload.

```dart
@ForyField(id: 1)
String name = '';
```

Once a payload is shared across services, never reuse an `id` for a different field.

## `nullable`

Explicitly marks a field as nullable or non-nullable, overriding what Fory infers from the Dart type. Use this when the Dart type is non-nullable but you want Fory to accept `null` on the wire (e.g., reading messages from an older producer that can omit the field).

```dart
@ForyField(nullable: true)
String nickname = '';
```

In cross-language scenarios, make sure the nullability contract also matches what peer languages expect.

## `ref`

Enables reference tracking for a specific field. Use this when multiple objects in the graph can point to the same instance, or when the field type can be circular. Without `ref: true`, Fory serializes the same object value twice if it appears in two fields.

```dart
@ForyField(ref: true)
List<Object?> sharedNodes = <Object?>[];
```

Note: scalar types like `int`, `double`, and `bool` never benefit from reference tracking even if `ref: true` is set.

## `dynamic`

Controls whether Fory writes the concrete type of the field value into the payload.

- `null` (default) — Fory decides automatically based on the declared type.
- `false` — always use the declared field type; more compact but the deserializer must know the exact type.
- `true` — always write the actual concrete type; needed when the field is declared as `Object?` or a base class but can hold different concrete types (polymorphism).

```dart
@ForyField(dynamic: true)
Object? payload;  // can hold any registered type
```

## Numeric Field Types

Dart `int` stores a 64-bit value. When exchanging messages with Java, Go, or C#, the receiving side may expect a narrower integer. Use `@ForyField(type: ...)` to pin the exact wire format:

```dart
@ForyStruct()
class Sample {
  Sample();

  @ForyField(type: Int32Type(encoding: Encoding.fixed))
  int fixedWidthInt = 0;

  @ForyField(type: Int64Type(encoding: Encoding.tagged))
  Int64 compactLong = Int64(0);

  @ForyField(type: Uint32Type())
  int smallUnsigned = 0;
}
```

Available scalar type nodes include `Int8Type`, `Int16Type`, `Int32Type`,
`Int64Type`, `Uint8Type`, `Uint16Type`, `Uint32Type`, `Uint64Type`,
`Float16Type`, `Bfloat16Type`, and `Float32Type`.

For nested containers, use `ListField`, `SetField`, `MapField`, or a full
`ForyField(type: ...)` tree:

```dart
@MapField(
  value: ListType(
    element: Int32Type(encoding: Encoding.fixed),
  ),
)
Map<String, List<int?>> metrics = <String, List<int?>>{};
```

Generic `List<int>` still uses the `list` wire type even with primitive element
specs. Packed `*_array` wire kinds come from dedicated carriers such as
`Int32List`, `Uint32List`, `Int64List`, and `Uint64List`. If you annotate a
generic `List<int>` with a non-null fixed-width primitive element spec, code
generation rejects it and tells you to use the matching typed list carrier.

## Aligning Fields Across Languages

When the same model is defined in multiple languages:

- Assign stable `id` values to every field that might change over time.
- Use `dynamic: true` for fields that are genuinely polymorphic.
- Keep the logical meaning of each field consistent across languages — Fory matches fields by name or ID, but cannot reconcile semantic differences.

## Related Topics

- [Code Generation](code-generation.md)
- [Schema Evolution](schema-evolution.md)
- [Xlang Serialization](xlang-serialization.md)
