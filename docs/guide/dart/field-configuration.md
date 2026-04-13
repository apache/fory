---
title: Field Configuration
sidebar_position: 6
id: dart_field_configuration
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

Use `@ForyField(...)` to override generated serializer behavior for individual fields.

## Options Overview

```dart
@ForyField(
  skip: false,
  id: 10,
  nullable: true,
  ref: true,
  dynamic: false,
)
```

## `skip`

Exclude a field from generated serialization.

```dart
@ForyField(skip: true)
String cachedDisplayName = '';
```

## `id`

Provides a stable field identifier for compatible structs.

```dart
@ForyField(id: 1)
String name = '';
```

In compatible mode, stable field IDs matter more than declaration order. Keep IDs fixed once payloads are shared.

## `nullable`

Overrides inferred nullability.

```dart
@ForyField(nullable: true)
String nickname = '';
```

`null` means "use the Dart type as written." In cross-language scenarios, make sure your nullability contract also matches peer runtimes.

## `ref`

Enables reference tracking for that field.

```dart
@ForyField(ref: true)
List<Object?> sharedNodes = <Object?>[];
```

Basic scalar values never track references even if `ref` is set to `true`.

## `dynamic`

Controls whether generated code writes runtime type metadata for the field.

```dart
@ForyField(dynamic: true)
Object? payload;
```

- `null`: auto
- `false`: use the declared field type
- `true`: write runtime type information

This is the key knob for polymorphic fields and heterogeneous object payloads.

## Numeric Encoding Overrides

Use numeric annotations to control the xlang wire type used for integer fields.

```dart
@ForyStruct()
class Sample {
  Sample();

  @Int32Type(compress: false)
  int fixedWidthInt = 0;

  @Int64Type(encoding: LongEncoding.tagged)
  int compactLong = 0;

  @Uint32Type(compress: true)
  int smallUnsigned = 0;
}
```

Available numeric annotations include:

- `@Int32Type(...)`
- `@Int64Type(...)`
- `@Uint8Type()`
- `@Uint16Type()`
- `@Uint32Type(...)`
- `@Uint64Type(...)`

These should be chosen to match the intended xlang wire type and peer-language expectations.

## Field Alignment Across Languages

Cross-language decoding depends on matching field names or stable field IDs. When models differ across languages:

- keep equivalent logical fields aligned
- prefer stable `id` values for evolving structs
- use `dynamic: true` only when the field is genuinely polymorphic

## Related Topics

- [Code Generation](code-generation.md)
- [Schema Evolution](schema-evolution.md)
- [Cross-Language](cross-language.md)
